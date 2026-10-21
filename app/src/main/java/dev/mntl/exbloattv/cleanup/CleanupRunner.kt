package dev.mntl.exbloattv.cleanup

import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.adb.ShellResult
import dev.mntl.exbloattv.safety.SafetyGuard
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.state.RestorePoint
import dev.mntl.exbloattv.state.TvLockedException
import dev.mntl.exbloattv.state.TvStateReadException
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.state.TvStateWriteException
import dev.mntl.exbloattv.tv.Inventory
import dev.mntl.exbloattv.tv.Parsers
import dev.mntl.exbloattv.tv.TvReadException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the planner's steps against one TV (spec §6 items 9–11, §7, §12a), verifying every change by reading the TV
 * back and logging it in the TV's shared state file so any phone can undo it.
 *
 * **Why factories, not instances.** A [TvStateStore] and a [LauncherSwap] talk over one connection, and a run may
 * reconnect (lost Wi-Fi, the restart step), so the runner takes [connect], [storeFor] and [swapFor] and builds a
 * fresh store and swap on every new connection. [connect] must open a *new* connection on each call; the runner
 * owns every client it gets and closes it when it is replaced or when the run ends.
 *
 * **How a run goes.** Step 0 of every run is [Step.SaveRestorePoint]: take the lock, save the restore point built
 * from `before` ([TvStateStore.setOriginalIfAbsent], then always as `latest`), then [LauncherSwap.recover]. The
 * plan follows in order. Before each step the lock is re-taken as this phone (so a long run can't expire into
 * another phone's hands), and after every new connection [LauncherSwap.recover] runs before anything else.
 *
 * **Every change is written ahead.** Before a command an unverified [Change] (with the `from` state read from the
 * TV just then) is appended; once the TV is read back in the new state it is marked verified. If the read-back shows
 * nothing changed the entry is dropped; if the TV can't be read (lost connection, failed read) it stays unverified
 * ("maybe happened", which Undo reverses harmlessly), and a retry of the step settles it from the TV's state.
 * Already-in-the-right-state steps are DONE with no command and no entry.
 *
 * **Failures.** A failed step is FAILED with a plain message and the run goes on with the next one. On
 * [AdbError.Disconnected]/[AdbError.Timeout] the runner reconnects after 2 s, 5 s and 10 s and retries the same
 * step (finished steps never run again). After three failed attempts in a row it pauses with
 * [RunProgress.waitingForTv] until [retry] or [skip]. If another phone holds the lock the step fails and nothing
 * more runs until [retry]. The flow completes once every step is DONE or SKIPPED; while a step is FAILED it waits
 * for [retry]/[skip] (or for the collector to go away).
 *
 * **Cancellation.** Each step's read–change–verify–log runs to the end even if the collector is cancelled, so the
 * log is never left half-written; the lock is then released.
 */
class CleanupRunner(
    private val connect: suspend () -> AdbClient,
    private val storeFor: (AdbClient) -> TvStateStore,
    private val swapFor: (TvStateStore) -> LauncherSwap,
    private val phone: String,
    private val serial: String,
    /** Package -> friendly name, for messages that name apps. */
    private val appNames: Map<String, String> = emptyMap(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val active = AtomicReference<Session?>(null)

    /** Runs [plan] once collected. [before] is the inventory the plan was made from (for the restore point). */
    fun run(plan: List<Step>, before: Inventory): Flow<RunProgress> = channelFlow {
        require(Step.SaveRestorePoint !in plan) { "the runner adds the restore point itself" }
        require(before.info.serial == serial) { "inventory is for ${before.info.serial}, not $serial" }
        val session = Session(listOf(Step.SaveRestorePoint) + plan, before, channel)
        check(active.compareAndSet(null, session)) { "a cleanup run is already in progress" }
        try {
            session.execute()
        } finally {
            active.compareAndSet(session, null)
        }
    }

    /** Runs the FAILED step at [index] (of [RunProgress.steps]) again; resumes a paused run. Ignored otherwise. */
    suspend fun retry(index: Int) {
        active.get()?.retry(index)
    }

    /**
     * Marks the PENDING or FAILED step at [index] SKIPPED; resumes a paused run. Step 0 (lock and restore point)
     * can't be skipped: nothing may change on the TV without them.
     */
    suspend fun skip(index: Int) {
        active.get()?.skip(index)
    }

    private sealed interface Outcome {
        data class Done(val message: String? = null) : Outcome
        /** [block]: nothing more may run until the person acts. [waitForTv]: the TV can't be reached. */
        data class Failed(val message: String, val block: Boolean = false, val waitForTv: Boolean = false) : Outcome
    }

    private enum class Presence { ENABLED, DISABLED, ABSENT }

    private inner class Session(
        private val steps: List<Step>,
        private val before: Inventory,
        private val out: SendChannel<RunProgress>,
    ) {
        // Shared with retry()/skip(); guarded by [mutex].
        private val mutex = Mutex()
        private val states = steps.map { StepState(it, StepStatus.PENDING) }.toMutableList()
        private var waitingForTv = false
        private var blocked = false
        private var resumeRequested = false
        private var closed = false
        private val wake = Channel<Unit>(Channel.CONFLATED)

        // Owned by the run loop.
        private val runId = "run-${now()}-${UUID.randomUUID().toString().take(8)}"
        private var adb: AdbClient? = null
        private lateinit var store: TvStateStore
        private lateinit var swap: LauncherSwap
        private var connectNow = true
        private var reconnects = 0
        private var pauseMessage = LOST
        private var needsRecover = false
        /** Write-ahead entries of steps whose outcome on the TV is not known yet, by step index. */
        private val inflight = mutableMapOf<Int, Change>()
        private val verifiedDisabled = linkedSetOf<String>()
        private val verifiedRemoved = linkedSetOf<String>()
        private var swapDone = false
        /** RebootAndVerify steps whose restart already happened (a retry then only checks). */
        private val rebooted = mutableSetOf<Int>()

        suspend fun execute() {
            try {
                mutex.withLock { emit() }
                while (true) {
                    // Nothing below suspends cancellably when uncontended, so check explicitly between steps.
                    currentCoroutineContext().ensureActive()
                    val next = mutex.withLock {
                        nextLocked()?.also {
                            states[it] = StepState(steps[it], StepStatus.RUNNING)
                            emit()
                        } ?: if (states.all { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }) -1 else null
                    }
                    when {
                        next == -1 -> break
                        next != null -> runStep(next)
                        else -> wake.receive()
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { closed = true }
                    adb?.let { client ->
                        try {
                            store.releaseLock(serial, phone)
                        } catch (_: Exception) {
                            // Unreachable TV: the lock expires on its own after 10 minutes.
                        }
                        runCatching { client.close() }
                    }
                    adb = null
                }
            }
        }

        // isClosedForSend: a run cancelled a moment ago must not throw into the caller.
        @OptIn(DelicateCoroutinesApi::class)
        suspend fun retry(index: Int) {
            mutex.withLock {
                if (closed || out.isClosedForSend) return
                val s = states.getOrNull(index) ?: return
                if (s.status != StepStatus.FAILED) return
                states[index] = StepState(s.step, StepStatus.PENDING)
                resumeLocked()
                emit()
            }
            wake.trySend(Unit)
        }

        // isClosedForSend: a run cancelled a moment ago must not throw into the caller.
        @OptIn(DelicateCoroutinesApi::class)
        suspend fun skip(index: Int) {
            mutex.withLock {
                if (closed || out.isClosedForSend || index == 0) return
                val s = states.getOrNull(index) ?: return
                if (s.status != StepStatus.PENDING && s.status != StepStatus.FAILED) return
                states[index] = StepState(s.step, StepStatus.SKIPPED)
                if (s.status == StepStatus.FAILED) resumeLocked()
                emit()
            }
            wake.trySend(Unit)
        }

        private fun resumeLocked() {
            blocked = false
            waitingForTv = false
            resumeRequested = true
        }

        /** The next step to run, or null to wait for the person. Step 0 must be DONE before any other runs. */
        private fun nextLocked(): Int? {
            if (resumeRequested) {
                resumeRequested = false
                reconnects = 0
                connectNow = true
                pauseMessage = LOST
            }
            if (blocked || waitingForTv) return null
            when (states[0].status) {
                StepStatus.PENDING -> return 0
                StepStatus.DONE -> Unit
                else -> return null
            }
            return states.indexOfFirst { it.status == StepStatus.PENDING }.takeIf { it >= 0 }
        }

        private suspend fun emit() {
            val finished = states.count {
                it.status == StepStatus.DONE || it.status == StepStatus.FAILED || it.status == StepStatus.SKIPPED
            }
            out.send(RunProgress(states.toList(), finished.toFloat() / states.size, waitingForTv))
        }

        private suspend fun runStep(i: Int) {
            while (true) {
                if (!ensureConnected()) {
                    finish(i, Outcome.Failed(pauseMessage, waitForTv = true))
                    return
                }
                val outcome = try {
                    withContext(NonCancellable) { executeStep(i) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AdbError.Disconnected) {
                    dropConnection(LOST)
                    continue
                } catch (e: AdbError.Timeout) {
                    dropConnection(LOST)
                    continue
                } catch (e: SwapStateUnknown) {
                    // The home screen state is unknown: never treat it as done; retry on a fresh connection.
                    dropConnection(SWAP_UNKNOWN)
                    continue
                } catch (e: TvRestartTimeoutException) {
                    adb = null // rebootAndReconnect already closed it
                    Outcome.Failed(NO_COMEBACK, waitForTv = true)
                } catch (e: TvLockedException) {
                    Outcome.Failed(lockedBy(e.byPhone), block = true)
                } catch (e: TvReadException) {
                    Outcome.Failed("Couldn't check the TV (${e.what}). Tap Retry")
                } catch (e: TvStateReadException) {
                    Outcome.Failed(LOG_FAILED)
                } catch (e: TvStateWriteException) {
                    Outcome.Failed(LOG_FAILED)
                } catch (e: AdbError) {
                    Outcome.Failed(TALK_FAILED)
                } catch (e: Exception) {
                    Outcome.Failed(UNEXPECTED)
                }
                currentCoroutineContext().ensureActive() // cancelled mid-step: the step finished logging; stop here
                if (adb != null) reconnects = 0
                finish(i, outcome)
                return
            }
        }

        private suspend fun finish(i: Int, o: Outcome) = mutex.withLock {
            states[i] = when (o) {
                is Outcome.Done -> StepState(steps[i], StepStatus.DONE, o.message)
                is Outcome.Failed -> StepState(steps[i], StepStatus.FAILED, o.message)
            }
            if (o is Outcome.Failed) {
                if (o.block) blocked = true
                if (o.waitForTv) waitingForTv = true
            }
            emit()
        }

        // --- connection ---------------------------------------------------------------------------------------

        /** True once there is a connection; false after the first try (if due) plus three backed-off attempts. */
        private suspend fun ensureConnected(): Boolean {
            if (adb != null) return true
            if (connectNow) {
                connectNow = false
                if (tryConnect()) return true
            }
            while (reconnects < BACKOFF_MS.size) {
                delay(BACKOFF_MS[reconnects])
                reconnects++
                if (tryConnect()) return true
            }
            return false
        }

        private suspend fun tryConnect(): Boolean {
            val client = try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pauseMessage = LOST
                return false
            }
            adopt(client)
            needsRecover = true
            return true
        }

        private suspend fun open(): AdbClient = TimedAdbClient(connect())

        private fun adopt(client: AdbClient) {
            adb = client
            store = storeFor(client)
            swap = swapFor(store)
        }

        /** [reason] is shown if reconnecting runs out of attempts. */
        private fun dropConnection(reason: String) {
            adb?.let { runCatching { it.close() } }
            adb = null
            pauseMessage = reason
        }

        /**
         * LauncherSwap reads the TV (packages, HOME) as it goes; when one of those reads fails, what the swap did is
         * unknown. That is retried like a lost connection (reconnect, [LauncherSwap.recover], same step), never DONE.
         */
        private suspend fun <T> swapCall(block: suspend () -> T): T =
            try {
                block()
            } catch (e: TvReadException) {
                throw SwapStateUnknown(e)
            }

        // --- steps --------------------------------------------------------------------------------------------

        private suspend fun executeStep(i: Int): Outcome {
            val adb = checkNotNull(adb)
            store.acquireLock(serial, phone).getOrThrow()
            val step = steps[i]
            var note: String? = null
            if (step == Step.SaveRestorePoint) saveRestorePoint()
            if (needsRecover && step != Step.RebootAndVerify) {
                val r = swapCall { swap.recover(adb, serial) }
                needsRecover = false
                if (r is SwapResult.RolledBack) note = onSwapRolledBack(r, i)
            }
            return when (step) {
                Step.SaveRestorePoint -> Outcome.Done(note)
                is Step.Disable -> disable(adb, i, step.pkg)
                is Step.Enable -> enable(adb, i, step.pkg)
                is Step.InstallExisting -> installExisting(adb, i, step.pkg)
                is Step.Uninstall -> uninstall(adb, i, step.pkg)
                is Step.SetAnimations -> setAnimations(adb, i, step.scale)
                is Step.RestrictBackground -> restrictBackground(adb, i, step.pkg)
                Step.TrimCaches -> trimCaches(adb)
                Step.SwapLauncher -> when (val r = swapCall { swap.swap(adb, serial) }) {
                    SwapResult.Swapped, SwapResult.AlreadyDone -> {
                        swapDone = true
                        Outcome.Done()
                    }
                    is SwapResult.RolledBack -> Outcome.Failed(r.reason)
                }
                Step.RebootAndVerify -> rebootAndVerify(i)
            }
        }

        private suspend fun saveRestorePoint() {
            val rp = RestorePoint(
                takenAt = now(),
                byPhone = phone,
                enabled = before.packages.filter { it.enabled }.map { it.name },
                disabled = before.packages.filterNot { it.enabled }.map { it.name },
                userInstalled = before.packages.filterNot { it.isSystem }.map { it.name },
                resolvedHome = before.resolvedHome,
                animations = before.animations,
                backgroundOps = before.backgroundOps,
            )
            store.setOriginalIfAbsent(serial, rp)
            store.save(store.load(serial).copy(latest = rp))
        }

        /**
         * A recovery put the old home screen back. If this run's swap had worked, that step is now FAILED. Returns
         * the reason when it belongs on step [i] instead (a swap from before this run, seen at the restore point).
         */
        private suspend fun onSwapRolledBack(r: SwapResult.RolledBack, i: Int): String? {
            if (swapDone) {
                swapDone = false
                mutex.withLock {
                    val s = states.indexOfFirst { it.step == Step.SwapLauncher && it.status == StepStatus.DONE }
                    if (s >= 0) states[s] = StepState(Step.SwapLauncher, StepStatus.FAILED, r.reason)
                }
                return null
            }
            return r.reason.takeIf { i == 0 }
        }

        private suspend fun disable(adb: AdbClient, i: Int, pkg: String): Outcome {
            if (SafetyGuard.isProtected(pkg, activeHome(adb))) return Outcome.Failed(PROTECTED)
            var entry = inflight[i]
            if (entry == null) {
                if (presence(adb, pkg) != Presence.ENABLED) return Outcome.Done() // already off
                entry = begin(i, ChangeKind.DISABLE, pkg, "enabled", "disabled")
            } else {
                settleDisable(i, entry, pkg, presence(adb, pkg))?.let { return it }
            }
            if (entry.kind == ChangeKind.DISABLE) {
                adb.shell("pm disable-user --user 0 $pkg")
                settleDisable(i, entry, pkg, presence(adb, pkg))?.let { return it }
                // Printed success but still on: remove it for this user instead (Undo: install-existing).
                entry = entry.copy(kind = ChangeKind.REMOVE_FOR_USER, to = REMOVED)
                store.upsertChange(serial, entry)
                inflight[i] = entry
            }
            adb.shell("pm uninstall -k --user 0 $pkg")
            settleDisable(i, entry, pkg, presence(adb, pkg))?.let { return it }
            discard(i, entry)
            return Outcome.Failed("Couldn't switch this app off")
        }

        /** Decides a disable from what the TV shows; null while the app is still on. */
        private suspend fun settleDisable(i: Int, entry: Change, pkg: String, seen: Presence): Outcome? = when (seen) {
            Presence.DISABLED -> {
                confirm(i, entry.copy(kind = ChangeKind.DISABLE, to = "disabled"))
                verifiedDisabled += pkg
                Outcome.Done()
            }
            Presence.ABSENT -> {
                if (entry.kind == ChangeKind.REMOVE_FOR_USER) {
                    confirm(i, entry)
                    verifiedRemoved += pkg
                } else {
                    discard(i, entry) // switching off never removes an app: something else did
                }
                Outcome.Done()
            }
            Presence.ENABLED -> null
        }

        private suspend fun enable(adb: AdbClient, i: Int, pkg: String): Outcome {
            var entry = inflight[i]
            if (listed(adb, "-e", pkg)) {
                entry?.let { confirm(i, it) }
                return Outcome.Done()
            }
            if (!listed(adb, "-d", pkg)) {
                entry?.let { discard(i, it) }
                return Outcome.Failed("This app isn't on the TV any more")
            }
            if (entry == null) entry = begin(i, ChangeKind.ENABLE, pkg, "disabled", "enabled")
            adb.shell("pm enable $pkg")
            if (listed(adb, "-e", pkg)) {
                confirm(i, entry)
                return Outcome.Done()
            }
            discard(i, entry)
            return Outcome.Failed("Couldn't switch this app on")
        }

        private suspend fun installExisting(adb: AdbClient, i: Int, pkg: String): Outcome {
            var entry = inflight[i]
            if (listed(adb, "", pkg)) {
                entry?.let { confirm(i, it) }
                return Outcome.Done()
            }
            if (entry == null) entry = begin(i, ChangeKind.INSTALL_EXISTING, pkg, REMOVED, "installed")
            adb.shell("cmd package install-existing $pkg")
            if (listed(adb, "", pkg)) {
                confirm(i, entry)
                return Outcome.Done()
            }
            discard(i, entry)
            return Outcome.Failed("Couldn't bring this app back")
        }

        private suspend fun uninstall(adb: AdbClient, i: Int, pkg: String): Outcome {
            if (SafetyGuard.isProtected(pkg, activeHome(adb))) return Outcome.Failed(PROTECTED)
            var entry = inflight[i]
            if (!listed(adb, "", pkg)) {
                entry?.let { confirm(i, it) }
                return Outcome.Done()
            }
            if (entry == null) {
                if (!listed(adb, "-3", pkg)) return Outcome.Failed("This app came with the TV, so it can't be deleted")
                entry = begin(i, ChangeKind.UNINSTALL, pkg, "installed", "deleted")
            }
            adb.shell("pm uninstall $pkg")
            if (!listed(adb, "", pkg)) {
                confirm(i, entry)
                return Outcome.Done()
            }
            discard(i, entry)
            return Outcome.Failed("Couldn't delete this app")
        }

        private suspend fun setAnimations(adb: AdbClient, i: Int, scale: Float): Outcome {
            val target = List(ANIMATION_KEYS.size) { scale }
            val entry = inflight[i]
            val current = ANIMATION_KEYS.map { scale(adb, it) }
            if (current == target) {
                entry?.let { confirm(i, it) }
                return Outcome.Done()
            }
            val e = entry ?: begin(i, ChangeKind.ANIMATIONS, null, scales(current), scales(target))
            ANIMATION_KEYS.forEach { adb.shell("settings put global $it $scale") }
            val after = ANIMATION_KEYS.map { scale(adb, it) }
            if (after == target) {
                confirm(i, e)
                return Outcome.Done()
            }
            // Drop the entry only if nothing changed; a partial change stays logged so Undo puts it back.
            if (scales(after) == e.from) discard(i, e)
            return Outcome.Failed("Couldn't change the animation speed")
        }

        private suspend fun restrictBackground(adb: AdbClient, i: Int, pkg: String): Outcome {
            val entry = inflight[i]
            val from = appop(adb, pkg)
            if (from == IGNORE) {
                entry?.let { confirm(i, it) }
                return Outcome.Done()
            }
            val e = entry ?: begin(i, ChangeKind.APPOPS, pkg, from, IGNORE)
            adb.shell("cmd appops set $pkg $RUN_ANY_IN_BACKGROUND $IGNORE")
            val after = appop(adb, pkg)
            if (after == IGNORE) {
                confirm(i, e)
                return Outcome.Done()
            }
            if (after == e.from) discard(i, e)
            return Outcome.Failed("Couldn't stop this app running in the background")
        }

        private suspend fun trimCaches(adb: AdbClient): Outcome {
            val r = adb.shell("pm trim-caches 999G")
            return if (r.exitCode == 0 && !Parsers.isPmError(r.output)) Outcome.Done()
            else Outcome.Failed("Couldn't clear temporary files")
        }

        /** Spec §6.11: restart, reconnect, check the home screen, and that everything this run switched off stayed off. */
        private suspend fun rebootAndVerify(i: Int): Outcome {
            if (i !in rebooted) {
                adopt(swap.rebootAndReconnect(checkNotNull(adb), reconnect = { open() }))
                rebooted += i
            }
            val adb = checkNotNull(adb)
            val home = swapCall { if (swapDone) swap.verifyAfterReboot(adb, serial) else swap.recover(adb, serial) }
            needsRecover = false
            val problems = mutableListOf<String>()
            if (home is SwapResult.RolledBack) {
                onSwapRolledBack(home, i)
                problems += home.reason
            }
            val disabledNow = Parsers.packageNames(read(adb, "switched-off app list", "pm list packages -d"))
            val installedNow =
                if (verifiedRemoved.isEmpty()) emptySet()
                else Parsers.packageNames(read(adb, "app list", "pm list packages"))
            val back = verifiedDisabled.filter { it !in disabledNow } + verifiedRemoved.filter { it in installedNow }
            if (back.isNotEmpty()) {
                problems += "These apps switched themselves back on: " + back.joinToString(", ") { appName(it, appNames) }
            }
            rebooted -= i
            return if (problems.isEmpty()) Outcome.Done() else Outcome.Failed(problems.joinToString(". "))
        }

        // --- change log ---------------------------------------------------------------------------------------

        /** Appends the unverified entry before the command, so a lost reply still leaves a record. */
        private suspend fun begin(i: Int, kind: ChangeKind, pkg: String?, from: String, to: String): Change {
            val c = Change("$runId:$i:${UUID.randomUUID().toString().take(8)}", kind, pkg, from, to, phone, now(), false)
            store.appendChange(serial, c)
            inflight[i] = c
            return c
        }

        private suspend fun confirm(i: Int, c: Change) {
            store.upsertChange(serial, c.copy(verified = true))
            inflight.remove(i)
        }

        /** The TV was read back and shows the change didn't happen. */
        private suspend fun discard(i: Int, c: Change) {
            store.removeChanges(serial, setOf(c.id))
            inflight.remove(i)
        }

        // --- reads --------------------------------------------------------------------------------------------

        private suspend fun read(adb: AdbClient, what: String, cmd: String): String {
            val r = adb.shell(cmd)
            if (r.exitCode != 0) throw TvReadException(what, "exit code ${r.exitCode}")
            if (Parsers.isPmError(r.output)) throw TvReadException(what, "TV reported an error")
            return r.output
        }

        /** True if [pkg] is in `pm list packages [flag] <pkg>` ("" = installed for this user, "-e" on, "-d" off, "-3" user app). */
        private suspend fun listed(adb: AdbClient, flag: String, pkg: String): Boolean {
            val cmd = listOf("pm list packages", flag, pkg).filter { it.isNotEmpty() }.joinToString(" ")
            return pkg in Parsers.packageNames(read(adb, "app list", cmd))
        }

        private suspend fun presence(adb: AdbClient, pkg: String): Presence = when {
            listed(adb, "-d", pkg) -> Presence.DISABLED
            listed(adb, "", pkg) -> Presence.ENABLED
            else -> Presence.ABSENT
        }

        /** The package HOME resolves to right now (protected until a replacement is verified). */
        private suspend fun activeHome(adb: AdbClient): String? =
            Parsers.resolveActivityBrief(read(adb, "current home screen", RESOLVE_HOME))?.substringBefore('/')

        /** Same rule as the inventory: only the literal "null" (never set) means the default 1.0. */
        private suspend fun scale(adb: AdbClient, key: String): Float {
            val text = read(adb, "animation settings", "settings get global $key").trim()
            if (text == "null") return 1.0f
            return text.toFloatOrNull() ?: throw TvReadException("animation settings", "unexpected value '$text' for $key")
        }

        private suspend fun appop(adb: AdbClient, pkg: String): String =
            Parsers.appop(read(adb, "background setting", "cmd appops get $pkg $RUN_ANY_IN_BACKGROUND"), RUN_ANY_IN_BACKGROUND)
                ?: "default"

        private fun scales(values: List<Float>) = values.joinToString(",")
    }

    /**
     * Bounds every shell call: a read on a wedged connection can't be cancelled, only ended by closing the socket,
     * so on timeout the connection is closed and [AdbError.Timeout] thrown (the runner then reconnects).
     */
    private class TimedAdbClient(private val inner: AdbClient) : AdbClient by inner {
        override suspend fun shell(cmd: String): ShellResult = coroutineScope {
            val work = async { inner.shell(cmd) }
            if (withTimeoutOrNull(SHELL_TIMEOUT_MS) { work.join() } == null) {
                runCatching { inner.close() }
                work.cancel()
                throw AdbError.Timeout()
            }
            work.await()
        }
    }

    private class SwapStateUnknown(cause: TvReadException) : Exception(cause.message, cause)

    private fun lockedBy(name: String) = "Another phone ($name) is cleaning this TV"

    private companion object {
        val BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L)
        const val SHELL_TIMEOUT_MS = 60_000L
        val ANIMATION_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        const val RUN_ANY_IN_BACKGROUND = "RUN_ANY_IN_BACKGROUND"
        const val IGNORE = "ignore"
        const val REMOVED = "removed"
        const val RESOLVE_HOME =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val LOST = "Lost connection to the TV. Check it's on and on the same Wi-Fi, then tap Retry"
        const val SWAP_UNKNOWN = "Couldn't check the TV's home screen. Check the TV is on, then tap Retry"
        const val NO_COMEBACK = "The TV didn't come back after restarting. Check it's on, then tap Retry"
        const val PROTECTED = "This app keeps the TV working, so it was left on"
        const val LOG_FAILED = "Couldn't update the record of changes on the TV. Tap Retry"
        const val TALK_FAILED = "Something went wrong talking to the TV. Tap Retry"
        const val UNEXPECTED = "Something unexpected went wrong. Tap Retry"
    }
}
