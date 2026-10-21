package dev.mntl.exbloattv.cleanup

import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.safety.SafetyGuard
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.tv.Parsers
import dev.mntl.exbloattv.tv.TvReadException
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val FLAUNCHER_PKG = "me.efesser.flauncher"
const val FLAUNCHER_VERSION = "0.18.0"
const val FLAUNCHER_URL =
    "https://gitlab.com/api/v4/projects/26632151/packages/generic/flauncher/0.18.0/flauncher-0.18.0.apk"
const val FLAUNCHER_SHA256 = "376fc528c880cdf7c7c04b2d3708c69f911144498218babb6300808b772af32e"

sealed interface SwapResult {
    data object Swapped : SwapResult
    data object AlreadyDone : SwapResult
    /** The home screen was not changed (or was put back). [reason] is plain-language UI text. */
    data class RolledBack(val reason: String) : SwapResult
}

/** After a restart the TV did not go down, or did not come back and finish booting, within [timeoutMs]. */
class TvRestartTimeoutException(val timeoutMs: Long, cause: Throwable? = null) :
    Exception("TV did not come back within ${timeoutMs / 1000} s", cause)

/**
 * Replaces the TV's stock home screen with FLauncher (spec §6 items 10–11). Every step is verified by reading the
 * TV back; if FLauncher is not confirmed as the home screen, everything this swap changed is put back.
 *
 * The change log on the TV is the only record of what a swap did, so a swap cut off by a lost connection can be
 * undone later from a fresh connection ([recover]). Entries of one swap share the id prefix `swap-<ts>-<rand>:`:
 * - Before each change an unverified entry is written; once the TV is read back it is marked verified:
 *   `:install` (`INSTALL_APK`, only when FLauncher was not on the TV), `:enable:<FLauncher>` (`ENABLE`, only when the
 *   person's own FLauncher was switched off), `:disable:<pkg>` (`DISABLE`, each stock launcher and the setup wizard).
 * - `:home` (`LAUNCHER_SWAP`, `from` = the stock home packages switched off, comma-separated; `to` = FLauncher) is
 *   written, verified, only once FLauncher is confirmed as the home screen. A swap without it is unfinished.
 * - Rollback removes the entries of everything it verifiably put back, plus `:home`. Whatever could not be put back
 *   stays logged, so a later [recover] (or Undo) tries again. No `ENABLE` reversal is ever appended: Undo would turn
 *   it into a disable of the stock launcher. An unverified `:install` only leads to an uninstall if FLauncher's first
 *   install time falls in the swap's window; otherwise it is the person's own copy and stays.
 *
 * Reads fail closed: an unreadable answer throws [TvReadException] and never passes for "not installed" or
 * "switched off". Every TV command has a deadline (a wedged connection is closed and reported as
 * [AdbError.Timeout]), so no step and no rollback can hang.
 *
 * [store] must talk to the same TV connection passed to each call. After a lost connection or a restart, build a
 * new [LauncherSwap] with a store on the new client, then call [recover] / [verifyAfterReboot].
 */
class LauncherSwap(
    private val downloader: ApkDownloader,
    private val store: TvStateStore,
    private val phone: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun swap(adb: AdbClient, serial: String): SwapResult {
        if (isFireTv(adb)) return SwapResult.RolledBack(FIRE_TV)
        val log = onStore(adb) { store.load(serial) }.changes
        val last = latestSwapId(log)
        val lastDone = last != null && isDone(log, last)
        if (last != null && !lastDone) {
            // An earlier swap was cut off: put it back first, and stop if that can't be confirmed.
            val undo = rollback(adb, serial, last, INTERRUPTED)
            if (!undo.complete) return undo.result
        }
        val homeBefore = resolvedHome(adb)
        if (homeBefore == FLAUNCHER_PKG &&
            (lastDone || foreground(adb) == FLAUNCHER_PKG) &&
            !listed(adb, "-e", SETUPWRAITH)
        ) {
            return SwapResult.AlreadyDone
        }

        val swapId = "swap-${now()}-${UUID.randomUUID().toString().take(8)}"
        val failure = try {
            when (val prep = prepareFlauncher(adb, serial, swapId)) {
                is Prep.Failed -> if (prep.changedSomething) prep.reason else return SwapResult.RolledBack(prep.reason)
                Prep.Ready -> switchHome(adb, serial, swapId, homeBefore) ?: return SwapResult.Swapped
            }
        } catch (e: Throwable) {
            try {
                rollback(adb, serial, swapId, "")
            } catch (rollbackError: Throwable) {
                e.addSuppressed(rollbackError)
            }
            throw e
        }
        return rollback(adb, serial, swapId, failure).result
    }

    /**
     * Call after reconnecting to a TV where a swap may have been cut off. Undoes an unfinished swap. A finished one
     * is kept as long as HOME still resolves to FLauncher; the screen is not looked at or touched (the TV may be
     * asleep or playing something). Null when no swap is logged. A failed read throws and changes nothing.
     */
    suspend fun recover(adb: AdbClient, serial: String): SwapResult? = recoverLatest(adb, serial, afterRestart = false)

    /** Presses HOME after a restart and checks FLauncher comes up; if not, undoes the last logged swap. */
    suspend fun verifyAfterReboot(adb: AdbClient, serial: String): SwapResult =
        recoverLatest(adb, serial, afterRestart = true) ?: run {
            sh(adb, HOME_KEY)
            if (poll { homeIsFlauncher(adb) }) SwapResult.AlreadyDone else SwapResult.RolledBack(AFTER_RESTART)
        }

    /**
     * Restarts the TV and returns a fresh connection once `sys.boot_completed` is 1. Waits for the old connection
     * to drop first, so the TV that is still shutting down is never mistaken for the rebooted one. The old client is
     * always closed.
     */
    suspend fun rebootAndReconnect(
        adb: AdbClient,
        reconnect: suspend () -> AdbClient,
        timeoutMs: Long = 180_000,
    ): AdbClient {
        val start = timeSource.markNow()
        fun left() = timeoutMs - start.elapsedNow().inWholeMilliseconds
        try {
            adb.reboot()
        } catch (_: AdbError) {
            // The connection dropping during the reboot command is expected.
        }
        while (stillUp(adb)) {
            if (left() <= 0) {
                runCatching { adb.close() }
                throw TvRestartTimeoutException(timeoutMs)
            }
            delay(DOWN_POLL_MS)
        }
        runCatching { adb.close() }

        var last: Throwable? = null
        while (left() > 0) {
            delay(RECONNECT_POLL_MS)
            val c = withTimeoutOrNull(left().coerceAtLeast(1)) {
                try {
                    reconnect()
                } catch (e: AdbError) {
                    last = e
                    null
                }
            } ?: continue
            var booted = false
            try {
                val r = bounded(c) { c.shell(BOOT_PROP) }
                booted = r.exitCode == 0 && r.output.trim() == "1"
            } catch (e: AdbError) {
                last = e
            } finally {
                if (!booted) runCatching { c.close() }
            }
            if (booted) return c
        }
        throw TvRestartTimeoutException(timeoutMs, last)
    }

    // --- steps --------------------------------------------------------------------------------------------

    private sealed interface Prep {
        data object Ready : Prep
        /** [changedSomething]: a write-ahead entry was left, so the swap must be rolled back from the log. */
        data class Failed(val reason: String, val changedSomething: Boolean) : Prep
    }

    /** Makes sure FLauncher is installed and switched on, logging ahead of each change. */
    private suspend fun prepareFlauncher(adb: AdbClient, serial: String, swapId: String): Prep {
        if (!listed(adb, "", FLAUNCHER_PKG)) {
            val apk = try {
                downloader.fetch(FLAUNCHER_URL, FLAUNCHER_SHA256)
            } catch (e: ChecksumMismatch) {
                return Prep.Failed("The new home screen download didn't match the expected file, so it wasn't used", false)
            } catch (e: IOException) {
                return Prep.Failed("Couldn't download the new home screen. Check the phone's internet connection", false)
            }
            val entry = change("$swapId:install", ChangeKind.INSTALL_APK, FLAUNCHER_PKG, "not installed", FLAUNCHER_VERSION)
            onStore(adb) { store.appendChange(serial, entry) }
            val result = try {
                adb.install(apk)
            } catch (e: AdbError.TransferFailed) {
                null
            } catch (e: AdbError.LocalFile) {
                null
            }
            // Only a reported success that the TV confirms counts. Otherwise the rollback decides, from the install
            // time, whether anything that did appear is ours.
            if (result?.exitCode != 0 || !listed(adb, "", FLAUNCHER_PKG)) return Prep.Failed(INSTALL_FAILED, true)
            onStore(adb) { store.upsertChange(serial, entry.copy(verified = true)) }
        } else if (listed(adb, "-d", FLAUNCHER_PKG)) {
            val entry = change("$swapId:enable:$FLAUNCHER_PKG", ChangeKind.ENABLE, FLAUNCHER_PKG, "disabled", "enabled")
            onStore(adb) { store.appendChange(serial, entry) }
            sh(adb, "pm enable $FLAUNCHER_PKG")
            if (!listed(adb, "-e", FLAUNCHER_PKG)) return Prep.Failed("Couldn't switch on the new home screen", true)
            onStore(adb) { store.upsertChange(serial, entry.copy(verified = true)) }
        }
        return Prep.Ready
    }

    /** Opens FLauncher, switches off the stock home screen(s) and confirms HOME. Returns a failure reason or null. */
    private suspend fun switchHome(adb: AdbClient, serial: String, swapId: String, homeBefore: String?): String? {
        if (!launchFlauncher(adb)) return "The new home screen didn't open"
        val homes = homePackages(adb) ?: return "Couldn't read the TV's home screens"
        // com.android.tv.settings is the system fallback home; SafetyGuard also refuses it and anything else vital.
        val stock = homes.filter {
            it != FLAUNCHER_PKG && it != TV_SETTINGS &&
                !SafetyGuard.isProtected(it, activeLauncher = homeBefore, replacementVerified = true)
        }
        val wizard = SETUPWRAITH.takeIf {
            it !in stock && listed(adb, "-e", it) && !SafetyGuard.isProtected(it, homeBefore, replacementVerified = true)
        }
        for (pkg in stock + listOfNotNull(wizard)) {
            val entry = change("$swapId:disable:$pkg", ChangeKind.DISABLE, pkg, "enabled", "disabled")
            onStore(adb) { store.appendChange(serial, entry) } // before the command: a lost reply must leave a record
            sh(adb, "pm disable-user --user 0 $pkg")
            if (!listed(adb, "-d", pkg)) return "Couldn't switch off the old home screen"
            onStore(adb) { store.upsertChange(serial, entry.copy(verified = true)) }
        }
        sh(adb, HOME_KEY)
        if (!poll { homeIsFlauncher(adb) }) return "The TV didn't switch to the new home screen"
        onStore(adb) {
            store.appendChange(
                serial,
                change("$swapId:home", ChangeKind.LAUNCHER_SWAP, FLAUNCHER_PKG, stock.joinToString(","), FLAUNCHER_PKG)
                    .copy(verified = true),
            )
        }
        return null
    }

    private suspend fun recoverLatest(adb: AdbClient, serial: String, afterRestart: Boolean): SwapResult? {
        val log = onStore(adb) { store.load(serial) }.changes
        val swapId = latestSwapId(log) ?: return null
        if (!isDone(log, swapId)) return rollback(adb, serial, swapId, INTERRUPTED).result
        if (afterRestart) {
            sh(adb, HOME_KEY)
            if (poll { homeIsFlauncher(adb) }) return SwapResult.Swapped
            return rollback(adb, serial, swapId, AFTER_RESTART).result
        }
        if (resolvedHome(adb) == FLAUNCHER_PKG) return SwapResult.Swapped
        return rollback(adb, serial, swapId, NOT_HOME).result
    }

    private class Rollback(val result: SwapResult.RolledBack, val complete: Boolean)

    /**
     * Puts back everything the log says swap [swapId] did. Runs to the end even if cancelled, and each step is
     * best-effort so one failure never skips the rest:
     * 1. switch every logged `DISABLE` package back on (newest first), verifying each;
     * 2. only if all came back and a real home screen other than FLauncher is listed: uninstall FLauncher if this
     *    swap installed it, or switch it off again if this swap switched it on;
     * 3. press HOME; it worked only if a restored stock launcher (or, if none was switched off, another real home
     *    screen) is in front: never the fallback home, the setup wizard or the chooser;
     * 4. drop from the log what was verifiably put back, and `:home`.
     */
    private suspend fun rollback(adb: AdbClient, serial: String, swapId: String, reason: String): Rollback =
        withContext(NonCancellable) {
            val entries = onStore(adb) { store.load(serial) }.changes.filter { it.id.startsWith("$swapId:") }
            val disabled = entries.filter { it.kind == ChangeKind.DISABLE }.mapNotNull { it.pkg }
            // Names come from the shared log: never put one in a command unchecked. An invalid one stays logged and
            // counts as not restored, so FLauncher stays too.
            val restored = disabled.asReversed().filter { pkg ->
                isPackageName(pkg) && attempt { sh(adb, "pm enable $pkg"); listed(adb, "-e", pkg) }
            }
            val allRestored = restored.size == disabled.size
            val realHomes = attemptOrNull { homePackages(adb) }.orEmpty().filter { it !in NOT_A_REAL_HOME }
            val install = entries.firstOrNull { it.id == "$swapId:install" }
            val switchedOn = entries.firstOrNull { it.id == "$swapId:enable:$FLAUNCHER_PKG" }
            // Never take FLauncher away before another real home screen is confirmed back.
            val canDropFlauncher = allRestored && realHomes.isNotEmpty()
            val flauncherUndone = when {
                install != null -> canDropFlauncher && attempt { undoInstall(adb, install) }
                switchedOn != null -> canDropFlauncher &&
                    attempt { sh(adb, "pm disable-user --user 0 $FLAUNCHER_PKG"); listed(adb, "-d", FLAUNCHER_PKG) }
                else -> true
            }
            attempt { sh(adb, HOME_KEY); true }
            val expected = restored.filter { it !in NOT_A_REAL_HOME }.ifEmpty { realHomes }
            val back = allRestored && flauncherUndone && expected.isNotEmpty() &&
                attempt { poll { foreground(adb) in expected } }
            // Log last: a state-file failure must not stop the TV getting its home screen back.
            val drop = buildSet {
                add("$swapId:home")
                restored.forEach { add("$swapId:disable:$it") }
                if (flauncherUndone) listOfNotNull(install, switchedOn).forEach { add(it.id) }
            }
            onStore(adb) { store.removeChanges(serial, drop) }
            Rollback(
                SwapResult.RolledBack(
                    if (back) "$reason. Your old home screen is back"
                    else "$reason. Press Home on the remote; if the old home screen doesn't come back, use Undo everything",
                ),
                back,
            )
        }

    /**
     * Removes the FLauncher a swap installed. True once nothing of ours is left: FLauncher is absent or uninstalled,
     * or the entry was never confirmed and FLauncher's install time shows it is not ours (the person installed it
     * some other time), in which case it stays.
     */
    private suspend fun undoInstall(adb: AdbClient, entry: Change): Boolean {
        if (!listed(adb, "", FLAUNCHER_PKG)) return true
        if (!entry.verified && !installedDuring(adb, entry.at)) return true
        sh(adb, "pm uninstall $FLAUNCHER_PKG")
        return !listed(adb, "", FLAUNCHER_PKG)
    }

    /**
     * Whether FLauncher was first installed between 60 s before [at] (phone clock) and [OWN_INSTALL_WINDOW_MS] after.
     * The TV prints local times, so its age is measured against the TV's own `date` and laid onto the phone's clock;
     * time zones and clock differences between phone and TV cancel out.
     */
    private suspend fun installedDuring(adb: AdbClient, at: Long): Boolean {
        val first = Parsers.firstInstallTime(read(adb, "app details", "dumpsys package $FLAUNCHER_PKG")) ?: return false
        val tvNow = Parsers.parseTvTime(read(adb, "TV clock", "date '+%Y-%m-%d %H:%M:%S'")) ?: return false
        val installedAt = now() - java.time.Duration.between(first, tvNow).toMillis()
        return installedAt in (at - 60_000)..(at + OWN_INSTALL_WINDOW_MS)
    }

    private suspend fun launchFlauncher(adb: AdbClient): Boolean {
        sh(adb, "monkey -p $FLAUNCHER_PKG -c android.intent.category.LAUNCHER 1")
        if (poll { foreground(adb) == FLAUNCHER_PKG }) return true
        sh(adb, "am start -n $FLAUNCHER_PKG/.MainActivity")
        return poll { foreground(adb) == FLAUNCHER_PKG }
    }

    // --- reads --------------------------------------------------------------------------------------------

    private suspend fun isFireTv(adb: AdbClient): Boolean {
        val r = sh(adb, "getprop ro.product.manufacturer")
        if (r.exitCode != 0) throw TvReadException("device info", "exit code ${r.exitCode}")
        return r.output.trim().equals("Amazon", ignoreCase = true)
    }

    /** Both what HOME resolves to and what is actually in front must be FLauncher; an unreadable answer is "not yet". */
    private suspend fun homeIsFlauncher(adb: AdbClient) =
        try {
            resolvedHome(adb) == FLAUNCHER_PKG && foreground(adb) == FLAUNCHER_PKG
        } catch (e: TvReadException) {
            false
        }

    /** The package HOME resolves to (null if none, e.g. "No activity found"). A failed read throws. */
    private suspend fun resolvedHome(adb: AdbClient): String? =
        Parsers.resolveActivityBrief(read(adb, "current home screen", RESOLVE_HOME))?.substringBefore('/')

    private suspend fun homePackages(adb: AdbClient): List<String>? {
        val r = sh(adb, QUERY_HOME)
        if (r.exitCode != 0) return null
        return Parsers.queryActivitiesPackages(r.output).takeIf { it.isNotEmpty() }
    }

    private suspend fun foreground(adb: AdbClient): String? {
        val r = sh(adb, "dumpsys activity activities")
        return if (r.exitCode == 0) Parsers.foregroundPackage(r.output) else null
    }

    /**
     * True if [pkg] shows up in `pm list packages [flag]` (flag "" = installed, "-e" = on, "-d" = off). A failed read
     * throws: it must never pass for "not installed" or "switched off".
     */
    private suspend fun listed(adb: AdbClient, flag: String, pkg: String): Boolean {
        val cmd = listOf("pm list packages", flag, pkg).filter { it.isNotEmpty() }.joinToString(" ")
        return pkg in Parsers.packageNames(read(adb, "app list", cmd, pmOutput = true))
    }

    /** Output of a read; a non-zero exit (or a package-manager error) throws [TvReadException]. */
    private suspend fun read(adb: AdbClient, what: String, cmd: String, pmOutput: Boolean = false): String {
        val r = sh(adb, cmd)
        if (r.exitCode != 0) throw TvReadException(what, "exit code ${r.exitCode}")
        if (pmOutput && Parsers.isPmError(r.output)) throw TvReadException(what, "TV reported an error")
        return r.output
    }

    /** True if the TV still answers on the old connection; a probe that hangs counts as gone. */
    private suspend fun stillUp(adb: AdbClient): Boolean =
        try {
            bounded(adb) { adb.shell(BOOT_PROP) }
            true
        } catch (_: AdbError) {
            false
        }

    // --- helpers ------------------------------------------------------------------------------------------

    /**
     * Runs one TV read with a deadline. A blocking read on a wedged connection can't be cancelled, only ended by
     * closing the socket, so on timeout [adb] is closed and [AdbError.Timeout] thrown (callers then recover).
     */
    private suspend fun <T> bounded(adb: AdbClient, timeoutMs: Long = CALL_TIMEOUT_MS, block: suspend () -> T): T =
        coroutineScope {
            val work = async { block() }
            if (withTimeoutOrNull(timeoutMs) { work.join() } == null) {
                runCatching { adb.close() }
                work.cancel()
                throw AdbError.Timeout()
            }
            work.await()
        }

    /** Every TV command goes through here, so a wedged connection can never hang a step (or a rollback). */
    private suspend fun sh(adb: AdbClient, cmd: String) = bounded(adb) { adb.shell(cmd) }

    /** State-file reads/writes are several commands on the same connection; bounded as a whole. */
    private suspend fun <T> onStore(adb: AdbClient, block: suspend () -> T): T = bounded(adb, STORE_TIMEOUT_MS, block)

    private suspend fun poll(check: suspend () -> Boolean): Boolean {
        val start = timeSource.markNow()
        while (true) {
            delay(POLL_MS)
            if (check()) return true
            if (start.elapsedNow() >= VERIFY_TIMEOUT_MS.milliseconds) return false
        }
    }

    /** One best-effort rollback step: any failure (lost connection, odd output) counts as "didn't work". */
    private suspend inline fun attempt(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    private suspend inline fun <T> attemptOrNull(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun latestSwapId(log: List<Change>): String? =
        log.lastOrNull { it.id.startsWith("swap-") && ':' in it.id }?.id?.substringBefore(':')

    private fun isDone(log: List<Change>, swapId: String) = log.any { it.id == "$swapId:home" && it.verified }

    /** An unverified entry; callers mark it verified once the TV is read back. */
    private fun change(id: String, kind: ChangeKind, pkg: String, from: String, to: String) =
        Change(id, kind, pkg, from, to, phone, now(), verified = false)

    private companion object {
        const val TV_SETTINGS = "com.android.tv.settings"
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        /** Never counts as "the old home screen is back": fallback home (black screen), setup wizard, FLauncher, chooser. */
        val NOT_A_REAL_HOME = setOf(TV_SETTINGS, SETUPWRAITH, FLAUNCHER_PKG, "android")
        const val HOME_KEY = "input keyevent KEYCODE_HOME"
        const val BOOT_PROP = "getprop sys.boot_completed"
        const val RESOLVE_HOME =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val QUERY_HOME =
            "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val FIRE_TV = "Fire TV not supported yet"
        const val INSTALL_FAILED = "Couldn't install the new home screen"
        const val AFTER_RESTART = "The new home screen didn't come back after the restart"
        const val NOT_HOME = "The TV wasn't showing the new home screen"
        const val INTERRUPTED = "The connection to the TV was lost while setting up the new home screen"
        const val POLL_MS = 500L
        const val VERIFY_TIMEOUT_MS = 15_000L
        const val CALL_TIMEOUT_MS = 10_000L
        const val STORE_TIMEOUT_MS = 30_000L
        /** Our install happens right after its log entry is written; anything first installed later is not ours. */
        const val OWN_INSTALL_WINDOW_MS = 10 * 60_000L
        const val DOWN_POLL_MS = 1_000L
        const val RECONNECT_POLL_MS = 5_000L
    }
}
