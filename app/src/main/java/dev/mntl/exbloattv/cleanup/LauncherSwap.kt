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
 * TV back; if FLauncher is not confirmed as the home screen, everything this swap switched off is switched back on.
 *
 * Change log contract (what Undo sees):
 * - `INSTALL_APK` / `ENABLE` for FLauncher once it is verified installed / switched on.
 * - `DISABLE` for each stock launcher and the setup wizard, appended as soon as that package is verified off.
 * - `LAUNCHER_SWAP` (`from` = the stock home packages switched off, comma-separated; `to` = FLauncher) only once
 *   FLauncher is verified as the home screen.
 * - On rollback, the `DISABLE` entries of packages verified back on (and the `LAUNCHER_SWAP`, after a restart) are
 *   removed, so the log never claims a package is off when it is on. No `ENABLE` reversal is appended: Undo would
 *   turn it back into a disable of the stock launcher. If this swap installed FLauncher, rollback uninstalls it
 *   (after the old home screen is back on, so HOME can't land on a chooser) and removes its `INSTALL_APK` entry;
 *   a FLauncher that was already on the TV is the person's app and stays.
 *
 * [store] must talk to the same TV connection passed to each call. After a restart, build a new [LauncherSwap]
 * with a store on the reconnected client before calling [verifyAfterReboot].
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
        val homeBefore = resolvedHome(adb)
        if (homeBefore == FLAUNCHER_PKG) return SwapResult.AlreadyDone

        val swapId = "swap-${now()}-${UUID.randomUUID().toString().take(8)}"
        val installId = when (val prep = prepareFlauncher(adb, serial, swapId)) {
            is Prep.Failed -> return SwapResult.RolledBack(prep.reason)
            is Prep.Ready -> if (prep.installedHere) "$swapId:install" else null
        }

        // Packages this swap tried to switch off; added before the command so a lost connection still rolls them back.
        val touched = mutableListOf<String>()
        val failure = try {
            switchHome(adb, serial, swapId, homeBefore, touched) ?: return SwapResult.Swapped
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                try {
                    rollback(adb, serial, swapId, touched, installId, emptySet(), "")
                } catch (rollbackError: Throwable) {
                    e.addSuppressed(rollbackError)
                }
            }
            throw e
        }
        return rollback(adb, serial, swapId, touched, installId, emptySet(), failure)
    }

    /** Presses HOME after a restart and checks FLauncher comes up; if not, undoes the last logged swap. */
    suspend fun verifyAfterReboot(adb: AdbClient, serial: String): SwapResult {
        adb.shell(HOME_KEY)
        if (poll { homeIsFlauncher(adb) }) return SwapResult.Swapped
        val changes = store.load(serial).changes
        val swap = changes.lastOrNull { it.kind == ChangeKind.LAUNCHER_SWAP }
        val swapId = swap?.id?.substringBefore(':')
        val disabled = if (swapId == null) emptyList() else changes
            .filter { it.kind == ChangeKind.DISABLE && it.id.startsWith("$swapId:disable:") }
            .mapNotNull { it.pkg }
        // Installed by this swap = an INSTALL_APK entry with the same swap id; otherwise it is the person's own copy.
        val installId = swapId?.let { id ->
            changes.firstOrNull { it.kind == ChangeKind.INSTALL_APK && it.id == "$id:install" }?.id
        }
        return rollback(adb, serial, swapId, disabled, installId, setOfNotNull(swap?.id), AFTER_RESTART)
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
                val r = c.shell(BOOT_PROP)
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
        data class Ready(val installedHere: Boolean) : Prep
        data class Failed(val reason: String) : Prep
    }

    /** Makes sure FLauncher is installed and switched on. */
    private suspend fun prepareFlauncher(adb: AdbClient, serial: String, swapId: String): Prep {
        if (!listed(adb, "", FLAUNCHER_PKG)) {
            val apk = try {
                downloader.fetch(FLAUNCHER_URL, FLAUNCHER_SHA256)
            } catch (e: ChecksumMismatch) {
                return Prep.Failed("The new home screen download didn't match the expected file, so it wasn't used")
            } catch (e: IOException) {
                return Prep.Failed("Couldn't download the new home screen. Check the phone's internet connection")
            }
            try {
                adb.install(apk)
            } catch (e: AdbError.TransferFailed) {
                return Prep.Failed(INSTALL_FAILED)
            } catch (e: AdbError.LocalFile) {
                return Prep.Failed(INSTALL_FAILED)
            }
            if (!listed(adb, "", FLAUNCHER_PKG)) return Prep.Failed(INSTALL_FAILED)
            log(serial, "$swapId:install", ChangeKind.INSTALL_APK, FLAUNCHER_PKG, "not installed", FLAUNCHER_VERSION)
            return Prep.Ready(installedHere = true)
        } else if (listed(adb, "-d", FLAUNCHER_PKG)) {
            adb.shell("pm enable $FLAUNCHER_PKG")
            if (!listed(adb, "-e", FLAUNCHER_PKG)) return Prep.Failed("Couldn't switch on the new home screen")
            log(serial, "$swapId:enable:$FLAUNCHER_PKG", ChangeKind.ENABLE, FLAUNCHER_PKG, "disabled", "enabled")
        }
        return Prep.Ready(installedHere = false)
    }

    /** Opens FLauncher, switches off the stock home screen(s) and confirms HOME. Returns a failure reason or null. */
    private suspend fun switchHome(
        adb: AdbClient,
        serial: String,
        swapId: String,
        homeBefore: String?,
        touched: MutableList<String>,
    ): String? {
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
            touched += pkg
            adb.shell("pm disable-user --user 0 $pkg")
            if (!listed(adb, "-d", pkg)) return "Couldn't switch off the old home screen"
            log(serial, "$swapId:disable:$pkg", ChangeKind.DISABLE, pkg, "enabled", "disabled")
        }
        adb.shell(HOME_KEY)
        if (!poll { homeIsFlauncher(adb) }) return "The TV didn't switch to the new home screen"
        log(serial, "$swapId:home", ChangeKind.LAUNCHER_SWAP, FLAUNCHER_PKG, stock.joinToString(","), FLAUNCHER_PKG)
        return null
    }

    /**
     * Switches [pkgs] back on (newest first), uninstalls FLauncher if this swap installed it ([installId] non-null;
     * otherwise it stays as the person's app), presses HOME, checks a home screen other than FLauncher is in front,
     * then drops from the log the `DISABLE` entries of packages verified back on, [installId] once FLauncher is
     * verified gone, and [dropIds].
     */
    private suspend fun rollback(
        adb: AdbClient,
        serial: String,
        swapId: String?,
        pkgs: List<String>,
        installId: String?,
        dropIds: Set<String>,
        reason: String,
    ): SwapResult.RolledBack {
        val restored = mutableListOf<String>()
        for (pkg in pkgs.asReversed()) {
            adb.shell("pm enable $pkg")
            if (listed(adb, "-e", pkg)) restored += pkg
        }
        // Only after the old home screen is back on, so the TV is never left with no home app.
        val uninstalled = installId != null && run {
            adb.shell("pm uninstall $FLAUNCHER_PKG")
            !listed(adb, "", FLAUNCHER_PKG)
        }
        adb.shell(HOME_KEY)
        val homes = homePackages(adb).orEmpty() - FLAUNCHER_PKG
        val back = restored.size == pkgs.size && (installId == null || uninstalled) &&
            poll { foreground(adb).let { it != null && it in homes } }
        // Log last: a state-file failure must not stop the TV getting its home screen back.
        val drop = dropIds + restored.map { "$swapId:disable:$it" } + listOfNotNull(installId.takeIf { uninstalled })
        if (drop.isNotEmpty()) store.removeChanges(serial, drop)
        return SwapResult.RolledBack(
            if (back) "$reason. Your old home screen is back"
            else "$reason. Press Home on the remote; if the old home screen doesn't come back, use Undo everything",
        )
    }

    private suspend fun launchFlauncher(adb: AdbClient): Boolean {
        adb.shell("monkey -p $FLAUNCHER_PKG -c android.intent.category.LAUNCHER 1")
        if (poll { foreground(adb) == FLAUNCHER_PKG }) return true
        adb.shell("am start -n $FLAUNCHER_PKG/.MainActivity")
        return poll { foreground(adb) == FLAUNCHER_PKG }
    }

    // --- reads --------------------------------------------------------------------------------------------

    private suspend fun isFireTv(adb: AdbClient): Boolean {
        val r = adb.shell("getprop ro.product.manufacturer")
        if (r.exitCode != 0) throw TvReadException("device info", "exit code ${r.exitCode}")
        return r.output.trim().equals("Amazon", ignoreCase = true)
    }

    /** Both what HOME resolves to and what is actually in front must be FLauncher. */
    private suspend fun homeIsFlauncher(adb: AdbClient) =
        resolvedHome(adb) == FLAUNCHER_PKG && foreground(adb) == FLAUNCHER_PKG

    private suspend fun resolvedHome(adb: AdbClient): String? {
        val r = adb.shell(RESOLVE_HOME)
        if (r.exitCode != 0) return null
        return Parsers.resolveActivityBrief(r.output)?.substringBefore('/')
    }

    private suspend fun homePackages(adb: AdbClient): List<String>? {
        val r = adb.shell(QUERY_HOME)
        if (r.exitCode != 0) return null
        return Parsers.queryActivitiesPackages(r.output).takeIf { it.isNotEmpty() }
    }

    private suspend fun foreground(adb: AdbClient): String? {
        val r = adb.shell("dumpsys activity activities")
        return if (r.exitCode == 0) Parsers.foregroundPackage(r.output) else null
    }

    /** True if [pkg] shows up in `pm list packages [flag]` (flag "" = installed, "-e" = on, "-d" = off). */
    private suspend fun listed(adb: AdbClient, flag: String, pkg: String): Boolean {
        val r = adb.shell(listOf("pm list packages", flag, pkg).filter { it.isNotEmpty() }.joinToString(" "))
        return r.exitCode == 0 && pkg in Parsers.packageNames(r.output)
    }

    /** True if the TV still answers on the old connection; a probe that hangs counts as gone (and closes it). */
    private suspend fun stillUp(adb: AdbClient): Boolean = coroutineScope {
        val probe = async {
            try {
                adb.shell(BOOT_PROP)
                true
            } catch (_: AdbError) {
                false
            }
        }
        withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.await() } ?: run {
            runCatching { adb.close() } // unblocks a read stuck on a dead socket
            probe.cancel()
            false
        }
    }

    private suspend fun poll(timeoutMs: Long = VERIFY_TIMEOUT_MS, check: suspend () -> Boolean): Boolean {
        val start = timeSource.markNow()
        while (true) {
            delay(POLL_MS)
            if (check()) return true
            if (start.elapsedNow() >= timeoutMs.milliseconds) return false
        }
    }

    private suspend fun log(serial: String, id: String, kind: ChangeKind, pkg: String, from: String, to: String) =
        store.appendChange(serial, Change(id, kind, pkg, from, to, phone, now(), verified = true))

    private companion object {
        const val TV_SETTINGS = "com.android.tv.settings"
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val HOME_KEY = "input keyevent KEYCODE_HOME"
        const val BOOT_PROP = "getprop sys.boot_completed"
        const val RESOLVE_HOME =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val QUERY_HOME =
            "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val FIRE_TV = "Fire TV not supported yet"
        const val INSTALL_FAILED = "Couldn't install the new home screen"
        const val AFTER_RESTART = "The new home screen didn't come back after the restart"
        const val POLL_MS = 500L
        const val VERIFY_TIMEOUT_MS = 15_000L
        const val DOWN_POLL_MS = 1_000L
        const val PROBE_TIMEOUT_MS = 5_000L
        const val RECONNECT_POLL_MS = 5_000L
    }
}
