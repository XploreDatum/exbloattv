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
import dev.mntl.exbloattv.state.TvState
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.state.TvStateWriteException
import dev.mntl.exbloattv.tv.Inventory
import dev.mntl.exbloattv.tv.Parsers
import dev.mntl.exbloattv.tv.TvReadException
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
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
        /** Write-ahead entries whose outcome on the TV is not known yet, by step index ("3", or "3:<op>" for app-ops). */
        private val inflight = mutableMapOf<String, Change>()
        /**
         * Other phones' change ids as of our last write that could touch them; any difference later means the TV
         * changed under us. Only LauncherSwap's recover/rollback ever removes another phone's entries (the runner's
         * own writes touch only its own ids), so it is re-taken after each LauncherSwap call. Not re-taken at the end
         * of every step, which would silently absorb another phone writing during that step.
         */
        private var foreignAtStart: Set<String>? = null
        /** A LauncherSwap call was cut off, so what it removed is unknown: take a new snapshot at the next check. */
        private var foreignDirty = false
        /** Ids of changes this run verified; another phone's Undo removes them. */
        private val confirmedIds = mutableSetOf<String>()
        /** Set once another phone changed the TV mid-run: the plan is stale, nothing more may run. */
        private var stale = false
        private val verifiedDisabled = linkedSetOf<String>()
        private val verifiedRemoved = linkedSetOf<String>()
        private var swapDone = false
        /** RebootAndVerify steps whose restart is confirmed (a retry then only checks). */
        private val rebooted = mutableSetOf<Int>()
        /**
         * boot_id read before a restart was requested, kept until the restart is confirmed or ruled out: after a lost
         * connection or a TV that came back late, the retry compares instead of restarting again.
         */
        private val bootBefore = mutableMapOf<Int, String>()

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

        /** For updates made inside a step (NonCancellable): a collector that went away must not abort the step. */
        @OptIn(DelicateCoroutinesApi::class)
        private suspend fun emitIfOpen() {
            if (out.isClosedForSend) return
            try {
                emit()
            } catch (_: ClosedSendChannelException) {
            } catch (_: CancellationException) {
                // The collector was cancelled; the step goes on and the run stops after it.
            }
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
        private suspend fun <T> swapCall(block: suspend () -> T): T {
            var settled = false
            try {
                val r = block()
                foreignAtStart = foreignIds(store.load(serial))
                settled = true
                return r
            } catch (e: TvReadException) {
                throw SwapStateUnknown(e)
            } finally {
                if (!settled) foreignDirty = true
            }
        }

        // --- steps --------------------------------------------------------------------------------------------

        private suspend fun executeStep(i: Int): Outcome {
            val adb = checkNotNull(adb)
            if (i != 0) changedElsewhere()?.let { return it }
            val locked = store.acquireLock(serial, phone).getOrThrow()
            if (foreignAtStart == null) foreignAtStart = foreignIds(locked)
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

        /**
         * Before re-taking the lock for a step: a fresh lock held by another phone fails the step (it is cleaning
         * now). A lock that isn't ours any more (released or taken over while this run waited), or a change log that
         * gained or lost another phone's entries, or lost ours (e.g. another phone's Undo), means the plan no longer
         * matches the TV: the run stops for good and the person must read the TV again.
         */
        private suspend fun changedElsewhere(): Outcome? {
            val s = store.load(serial)
            val lock = s.lock
            if (lock != null && lock.byPhone != phone && now() - lock.at < TvStateStore.LOCK_EXPIRY_MS) {
                throw TvLockedException(lock.byPhone, lock.at)
            }
            if (foreignDirty) {
                // Our interrupted recovery may have removed another phone's swap entries. The lock check below still
                // catches any other phone that worked on the TV meanwhile (it must have taken and released our lock).
                foreignAtStart = foreignIds(s)
                foreignDirty = false
            }
            if (!stale) {
                stale = lock?.byPhone != phone ||
                    foreignIds(s) != foreignAtStart ||
                    !s.changes.map { it.id }.containsAll(confirmedIds)
            }
            return if (stale) Outcome.Failed(CHANGED_ELSEWHERE_MESSAGE, block = true) else null
        }

        private fun foreignIds(s: TvState) = s.changes.filter { it.byPhone != phone }.map { it.id }.toSet()

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
                    if (s >= 0) {
                        states[s] = StepState(Step.SwapLauncher, StepStatus.FAILED, r.reason)
                        emitIfOpen()
                    }
                }
                return null
            }
            return r.reason.takeIf { i == 0 }
        }

        private suspend fun disable(adb: AdbClient, i: Int, pkg: String): Outcome {
            if (isProtected(adb, pkg)) return Outcome.Failed(PROTECTED)
            var entry = inflight["$i"]
            if (entry == null) {
                if (presence(adb, pkg) != Presence.ENABLED) return Outcome.Done() // already off
                entry = begin("$i", ChangeKind.DISABLE, pkg, "enabled", "disabled")
            } else {
                settleDisable(i, entry, pkg, presence(adb, pkg))?.let { return it }
            }
            if (entry.kind == ChangeKind.DISABLE) {
                adb.shell("pm disable-user --user 0 $pkg")
                settleDisable(i, entry, pkg, presence(adb, pkg))?.let { return it }
                // Printed success but still on: remove it for this user instead (Undo: install-existing).
                entry = entry.copy(kind = ChangeKind.REMOVE_FOR_USER, to = REMOVED)
                inflight["$i"] = entry
                store.upsertChange(serial, entry)
            }
            adb.shell("pm uninstall -k --user 0 $pkg")
            settleDisable(i, entry, pkg, presence(adb, pkg))?.let { return it }
            discard("$i", entry)
            return Outcome.Failed("Couldn't switch this app off")
        }

        /** Decides a disable from what the TV shows; null while the app is still on. */
        private suspend fun settleDisable(i: Int, entry: Change, pkg: String, seen: Presence): Outcome? = when (seen) {
            Presence.DISABLED -> {
                confirm("$i", entry.copy(kind = ChangeKind.DISABLE, to = "disabled"))
                verifiedDisabled += pkg
                Outcome.Done()
            }
            Presence.ABSENT -> {
                if (entry.kind == ChangeKind.REMOVE_FOR_USER) {
                    confirm("$i", entry)
                    verifiedRemoved += pkg
                } else {
                    discard("$i", entry) // switching off never removes an app: something else did
                }
                Outcome.Done()
            }
            Presence.ENABLED -> null
        }

        private suspend fun enable(adb: AdbClient, i: Int, pkg: String): Outcome {
            var entry = inflight["$i"]
            if (listed(adb, "-e", pkg)) {
                entry?.let { confirm("$i", it) }
                return Outcome.Done()
            }
            if (!listed(adb, "-d", pkg)) {
                entry?.let { discard("$i", it) }
                return Outcome.Failed("This app isn't on the TV any more")
            }
            if (entry == null) entry = begin("$i", ChangeKind.ENABLE, pkg, "disabled", "enabled")
            adb.shell("pm enable $pkg")
            if (listed(adb, "-e", pkg)) {
                confirm("$i", entry)
                return Outcome.Done()
            }
            discard("$i", entry)
            return Outcome.Failed("Couldn't switch this app on")
        }

        private suspend fun installExisting(adb: AdbClient, i: Int, pkg: String): Outcome {
            var entry = inflight["$i"]
            if (listed(adb, "", pkg)) {
                entry?.let { confirm("$i", it) }
                return Outcome.Done()
            }
            if (entry == null) entry = begin("$i", ChangeKind.INSTALL_EXISTING, pkg, REMOVED, "installed")
            adb.shell("cmd package install-existing $pkg")
            if (listed(adb, "", pkg)) {
                confirm("$i", entry)
                return Outcome.Done()
            }
            discard("$i", entry)
            return Outcome.Failed("Couldn't bring this app back")
        }

        private suspend fun uninstall(adb: AdbClient, i: Int, pkg: String): Outcome {
            if (isProtected(adb, pkg)) return Outcome.Failed(PROTECTED)
            var entry = inflight["$i"]
            if (!listed(adb, "", pkg)) {
                entry?.let { confirm("$i", it) }
                return Outcome.Done()
            }
            if (entry == null) {
                if (!listed(adb, "-3", pkg)) return Outcome.Failed("This app came with the TV, so it can't be deleted")
                entry = begin("$i", ChangeKind.UNINSTALL, pkg, "installed", "deleted")
            }
            adb.shell("pm uninstall $pkg")
            if (!listed(adb, "", pkg)) {
                confirm("$i", entry)
                return Outcome.Done()
            }
            discard("$i", entry)
            return Outcome.Failed("Couldn't delete this app")
        }

        /**
         * Logged for Undo as the raw `settings get global` answers joined "window,transition,animator", with
         * "null" kept for a scale that was never set (e.g. from "1.0,null,1.0", to "0.5,0.5,0.5"). Verified by value.
         */
        private suspend fun setAnimations(adb: AdbClient, i: Int, scale: Float): Outcome {
            val entry = inflight["$i"]
            val current = ANIMATION_KEYS.map { rawScale(adb, it) }
            if (current.all { valueOf(it) == scale }) {
                entry?.let { confirm("$i", it) }
                return Outcome.Done()
            }
            val e = entry ?: begin("$i", ChangeKind.ANIMATIONS, null, joined(current), joined(ANIMATION_KEYS.map { "$scale" }))
            ANIMATION_KEYS.forEach { adb.shell("settings put global $it $scale") }
            val after = ANIMATION_KEYS.map { rawScale(adb, it) }
            if (after.all { valueOf(it) == scale }) {
                confirm("$i", e)
                return Outcome.Done()
            }
            // Drop the entry only if nothing changed; a partial change stays logged so Undo puts it back.
            if (joined(after) == e.from) discard("$i", e)
            return Outcome.Failed("Couldn't change the animation speed")
        }

        /**
         * Spec §6.7 + controller ruling: `RUN_IN_BACKGROUND`, and on Android 9+ (API 28, where it exists) also
         * `RUN_ANY_IN_BACKGROUND`, set to ignore. One APPOPS change per op, encoded `from = "<OP>=<mode>"`,
         * `to = "<OP>=ignore"`, each read back. Stops at the first op that doesn't take.
         */
        private suspend fun restrictBackground(adb: AdbClient, i: Int, pkg: String): Outcome {
            val ops = if (before.info.sdkInt >= 28) listOf(RUN_IN_BACKGROUND, RUN_ANY_IN_BACKGROUND) else listOf(RUN_IN_BACKGROUND)
            for (op in ops) {
                val key = "$i:$op"
                val entry = inflight[key]
                val from = appop(adb, pkg, op)
                if (from == IGNORE) {
                    entry?.let { confirm(key, it) }
                    continue
                }
                val e = entry ?: begin(key, ChangeKind.APPOPS, pkg, "$op=$from", "$op=$IGNORE")
                adb.shell("cmd appops set $pkg $op $IGNORE")
                val after = appop(adb, pkg, op)
                if (after == IGNORE) {
                    confirm(key, e)
                    continue
                }
                if ("$op=$after" == e.from) discard(key, e)
                return Outcome.Failed("Couldn't stop this app running in the background")
            }
            return Outcome.Done()
        }

        private suspend fun trimCaches(adb: AdbClient): Outcome {
            val r = adb.shell("pm trim-caches 999G")
            return if (r.exitCode == 0 && !Parsers.isPmError(r.output)) Outcome.Done()
            else Outcome.Failed("Couldn't clear temporary files")
        }

        /** Spec §6.11: restart, reconnect, check the home screen, and that everything this run switched off stayed off. */
        private suspend fun rebootAndVerify(i: Int): Outcome {
            if (i !in rebooted) {
                // boot_id changes on every boot: the only proof the restart really happened. A reboot command that
                // never reached the TV would otherwise "reconnect" to the same running TV and pass every check.
                val before = bootBefore[i] ?: bootId(checkNotNull(adb)).also {
                    bootBefore[i] = it
                    adopt(swap.rebootAndReconnect(checkNotNull(adb), reconnect = { open() }))
                }
                val after = bootId(checkNotNull(adb))
                bootBefore.remove(i) // either way, a retry starts with a fresh restart
                if (after == before) return Outcome.Failed(NO_RESTART)
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

        /**
         * Writes the unverified entry before the command, so a lost reply still leaves a record. It is remembered
         * first and written with upsert, so a write whose reply was lost is never duplicated by the retry.
         */
        private suspend fun begin(key: String, kind: ChangeKind, pkg: String?, from: String, to: String): Change {
            val c = Change("$runId:$key:${UUID.randomUUID().toString().take(8)}", kind, pkg, from, to, phone, now(), false)
            inflight[key] = c
            store.upsertChange(serial, c)
            return c
        }

        private suspend fun confirm(key: String, c: Change) {
            store.upsertChange(serial, c.copy(verified = true))
            confirmedIds += c.id
            inflight.remove(key)
        }

        /** The TV was read back and shows the change didn't happen. */
        private suspend fun discard(key: String, c: Change) {
            store.removeChanges(serial, setOf(c.id))
            inflight.remove(key)
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

        /**
         * SafetyGuard plus the home screen, read right now. Fails closed: if HOME doesn't resolve to one real package
         * (nothing, the chooser, the black-screen fallback home or the setup wizard), every HOME-capable package is
         * protected; an empty list of them is a failed read, never "nothing to protect".
         */
        private suspend fun isProtected(adb: AdbClient, pkg: String): Boolean {
            if (SafetyGuard.isProtected(pkg)) return true
            val home = Parsers.resolveActivityBrief(read(adb, "current home screen", RESOLVE_HOME))?.substringBefore('/')
            if (home != null && home !in NOT_A_REAL_HOME) return pkg == home
            val candidates = Parsers.queryActivitiesPackages(read(adb, "home screen apps", QUERY_HOME))
            if (candidates.isEmpty()) throw TvReadException("home screen apps", "none found")
            return pkg in candidates
        }

        /** The raw answer ("null" = never set); anything that isn't "null" or a number is a failed read. */
        private suspend fun rawScale(adb: AdbClient, key: String): String {
            val text = read(adb, "animation settings", "settings get global $key").trim()
            if (text != "null" && text.toFloatOrNull() == null) {
                throw TvReadException("animation settings", "unexpected value '$text' for $key")
            }
            return text
        }

        /** Same rule as the inventory: never set means the default 1.0. */
        private fun valueOf(raw: String): Float = if (raw == "null") 1.0f else raw.toFloat()

        private suspend fun appop(adb: AdbClient, pkg: String, op: String): String =
            Parsers.appop(read(adb, "background setting", "cmd appops get $pkg $op"), op) ?: "default"

        private fun joined(values: List<String>) = values.joinToString(",")

        private suspend fun bootId(adb: AdbClient): String =
            read(adb, "restart check", BOOT_ID).trim().ifEmpty { throw TvReadException("restart check", "empty boot id") }
    }

    /**
     * Bounds every call to the TV: a read on a wedged connection (the TV left the Wi-Fi without closing TCP) can't
     * be cancelled, only ended by closing the socket. On timeout the connection is closed and [AdbError.Timeout]
     * thrown (the runner then reconnects), except for [reboot]: a reboot command that never answers is taken as
     * sent. Closing the connection makes LauncherSwap's "is it still up?" probe answer "gone" even if the command
     * never reached the TV, so that probe proves nothing; the runner compares the TV's boot_id before and after.
     */
    private class TimedAdbClient(private val inner: AdbClient) : AdbClient by inner {
        override suspend fun shell(cmd: String): ShellResult = bounded(SHELL_TIMEOUT_MS) { inner.shell(cmd) }
        override suspend fun install(apk: File): ShellResult = bounded(INSTALL_TIMEOUT_MS) { inner.install(apk) }
        override suspend fun push(local: File, remote: String) = bounded(TRANSFER_TIMEOUT_MS) { inner.push(local, remote) }
        override suspend fun pull(remote: String, local: File) = bounded(TRANSFER_TIMEOUT_MS) { inner.pull(remote, local) }
        override suspend fun reboot() {
            try {
                bounded(REBOOT_TIMEOUT_MS) { inner.reboot() }
            } catch (_: AdbError.Timeout) {
                // Sent, as far as anyone can tell; the connection is closed now.
            }
        }

        private suspend fun <T> bounded(timeoutMs: Long, block: suspend () -> T): T = coroutineScope {
            val work = async { block() }
            if (withTimeoutOrNull(timeoutMs) { work.join() } == null) {
                runCatching { inner.close() }
                work.cancel()
                throw AdbError.Timeout()
            }
            work.await()
        }
    }

    private class SwapStateUnknown(cause: TvReadException) : Exception(cause.message, cause)

    private fun lockedBy(name: String) = "Another phone ($name) is cleaning this TV"

    // internal (not private): the Cleaning screen recognises CHANGED_ELSEWHERE_MESSAGE.
    internal companion object {
        val BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L)
        const val SHELL_TIMEOUT_MS = 60_000L
        const val INSTALL_TIMEOUT_MS = 5 * 60_000L
        const val TRANSFER_TIMEOUT_MS = 2 * 60_000L
        const val REBOOT_TIMEOUT_MS = 15_000L
        val ANIMATION_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        const val RUN_IN_BACKGROUND = "RUN_IN_BACKGROUND"
        const val RUN_ANY_IN_BACKGROUND = "RUN_ANY_IN_BACKGROUND"
        const val IGNORE = "ignore"
        const val REMOVED = "removed"
        const val RESOLVE_HOME =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val QUERY_HOME =
            "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
        /** Never "the" home screen (mirrors LauncherSwap): chooser, fallback home, setup wizard. */
        val NOT_A_REAL_HOME = setOf("android", "com.android.tv.settings", "com.google.android.tungsten.setupwraith")
        const val BOOT_ID = "cat /proc/sys/kernel/random/boot_id"
        const val NO_RESTART = "The TV didn't restart. Tap Retry."
        const val CHANGED_ELSEWHERE_MESSAGE =
            "This TV was changed from another phone while you were away. Check the TV again before carrying on"
        const val LOST = "Lost connection to the TV. Check it's on and on the same Wi-Fi, then tap Retry"
        const val SWAP_UNKNOWN = "Couldn't check the TV's home screen. Check the TV is on, then tap Retry"
        const val NO_COMEBACK = "The TV didn't come back after restarting. Check it's on, then tap Retry"
        const val PROTECTED = "This app keeps the TV working, so it was left on"
        const val LOG_FAILED = "Couldn't update the record of changes on the TV. Tap Retry"
        const val TALK_FAILED = "Something went wrong talking to the TV. Tap Retry"
        const val UNEXPECTED = "Something unexpected went wrong. Tap Retry"
    }
}
