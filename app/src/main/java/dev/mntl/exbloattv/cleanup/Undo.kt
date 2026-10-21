package dev.mntl.exbloattv.cleanup

import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.safety.SafetyGuard
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.tv.Parsers
import dev.mntl.exbloattv.tv.TvReadException
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * What "Undo everything" did. [restored]: log entries verifiably put back (and dropped from the log). [failed]: plain
 * names of what could not be put back; it stays logged, so the next Undo tries again. [reinstallFromStore]: packages
 * of deleted apps, which only the Play Store can bring back ([Undo.openInPlayStore]); their entries stay logged.
 */
data class UndoReport(val restored: Int, val failed: List<String>, val reinstallFromStore: List<String>)

/** The TV could not open the Play Store page (no Play Store, or it refused the request). */
class PlayStoreOpenException(detail: String) : Exception("Couldn't open the Play Store on the TV: $detail")

/**
 * "Undo everything" (spec §6, §12a item 4): reverses every change in the TV's shared change log, whichever phone made
 * it, newest first (log order: one file, written under the lock, so it is the true order across phones).
 *
 * - Unverified entries may or may not have happened; their reversals are harmless either way, so they are reversed.
 * - Each reversal is verified by reading the TV back; only then is its entry dropped from the log, one entry per write,
 *   so an Undo cut off anywhere resumes cleanly. A reversal that fails stays logged and is reported.
 * - Once a change to something could not be undone, older changes to the same thing wait (stay logged): undoing them
 *   out of order would leave a log that no longer describes the TV.
 * - A launcher swap (`swap-<ts>-<rand>:*` entries) is undone as one unit, at the position of its newest entry, by
 *   [LauncherSwap]: dropping its `:home` marks it unfinished (LauncherSwap's own convention) and
 *   [LauncherSwap.recover] then rolls it back: stock launchers and the setup wizard back on, FLauncher removed only if
 *   the swap installed it and a real home screen is back. After everything, HOME is pressed and a real stock home
 *   screen must come to the front.
 * - Deleted user apps (`UNINSTALL`) can't be put back from here: they are listed in [UndoReport.reinstallFromStore].
 * - Commands never include unchecked text from the log: package names, scales and app-op modes are validated first.
 * - The run lock is held throughout and refreshed before each reversal. Cancellation is honoured between reversals;
 *   each reversal (command, check, log write) always runs to the end.
 *
 * [store] must talk to the same TV connection passed to [undoAll].
 */
class Undo(
    private val store: TvStateStore,
    private val phone: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Only its rollback is used, which never downloads. */
    private val launcher by lazy { LauncherSwap(NoDownloads(), store, phone, timeSource, now) }

    /**
     * Throws [dev.mntl.exbloattv.state.TvLockedException] if another phone holds the TV (before anything changes, or
     * midway if the lock was lost) and [AdbError] if the connection is lost; the log keeps what is left either way.
     */
    suspend fun undoAll(adb: AdbClient, serial: String): UndoReport {
        val locked = onStore(adb) { store.acquireLock(serial, phone) }.getOrThrow()
        try {
            return undoLog(adb, serial, locked.changes)
        } finally {
            withContext(NonCancellable) {
                try {
                    onStore(adb) { store.releaseLock(serial, phone) }
                } catch (_: Exception) {
                    // Best effort: the lock expires on its own, and the real outcome must not be masked.
                }
            }
        }
    }

    /** Opens the app's Play Store page on the TV, so the person can reinstall a deleted app with the remote. */
    suspend fun openInPlayStore(adb: AdbClient, pkg: String) {
        require(isPackageName(pkg)) { "not an app package name" }
        val r = sh(adb, "am start -a android.intent.action.VIEW -d 'market://details?id=$pkg' -p $PLAY_STORE")
        if (r.exitCode != 0) throw PlayStoreOpenException("exit code ${r.exitCode}")
        if (Parsers.isPmError(r.output)) throw PlayStoreOpenException(r.output.trim().take(200))
    }

    // --- the log ------------------------------------------------------------------------------------------

    private sealed interface Work
    private data class One(val change: Change) : Work
    private data class Swap(val id: String, val entries: List<Change>) : Work

    /** Newest first; a swap's entries form one unit at the position of its newest entry. */
    private fun plan(log: List<Change>): List<Work> {
        val seen = mutableSetOf<String>()
        return log.asReversed().mapNotNull { c ->
            val id = swapIdOf(c) ?: return@mapNotNull One(c)
            if (seen.add(id)) Swap(id, log.filter { swapIdOf(it) == id }) else null
        }
    }

    private suspend fun undoLog(adb: AdbClient, serial: String, log: List<Change>): UndoReport {
        var restored = 0
        val failed = LinkedHashSet<String>()
        val reinstall = LinkedHashSet<String>()
        /** Things with a newer change still logged: their older changes must wait. */
        val waiting = HashSet<String>()
        val stockHomes = LinkedHashSet<String>()
        var swapUndone = false

        for (work in plan(log)) {
            currentCoroutineContext().ensureActive()
            val keys = keysOf(work)
            if (keys.any { it in waiting }) {
                waiting += keys
                // A deleted app's older changes wait quietly for it to be reinstalled; it is already listed.
                if (!(work is One && work.change.pkg in reinstall)) failed += nameOf(work)
                continue
            }
            when (work) {
                is One -> {
                    val c = work.change
                    val pkg = c.pkg
                    if (c.kind == ChangeKind.UNINSTALL && pkg != null && isPackageName(pkg)) {
                        reinstall += pkg
                        waiting += keys
                        continue
                    }
                    refreshLock(adb, serial)
                    if (withContext(NonCancellable) { undoOne(adb, serial, c) }) {
                        restored++
                    } else {
                        failed += nameOf(work)
                        waiting += keys
                    }
                }
                is Swap -> {
                    swapUndone = true
                    stockHomes += stockHomesOf(work)
                    refreshLock(adb, serial)
                    val r = withContext(NonCancellable) { undoSwap(adb, serial, work) }
                    restored += r.restored
                    if (r.left.isNotEmpty()) {
                        failed += HOME_SCREEN
                        waiting += r.left.flatMap(::keysOf)
                    }
                }
            }
        }
        if (swapUndone) {
            currentCoroutineContext().ensureActive()
            if (!withContext(NonCancellable) { stockHomeInFront(adb, stockHomes) }) failed += HOME_SCREEN
        }
        return UndoReport(restored, failed.toList(), reinstall.toList())
    }

    /** Reverses one change and drops it from the log. False (entry kept) unless the TV confirms it. */
    private suspend fun undoOne(adb: AdbClient, serial: String, c: Change): Boolean {
        if (!step { reverse(adb, c) }) return false
        return step { onStore(adb) { store.removeChanges(serial, setOf(c.id)) }; true }
    }

    private suspend fun reverse(adb: AdbClient, c: Change): Boolean {
        val pkg = c.pkg?.takeIf(::isPackageName)
        return when (c.kind) {
            ChangeKind.DISABLE -> pkg != null && run {
                sh(adb, "pm enable $pkg")
                listed(adb, "-e", pkg)
            }
            ChangeKind.REMOVE_FOR_USER -> pkg != null && run {
                sh(adb, "cmd package install-existing $pkg")
                // It was on when it was removed; a half-applied earlier switch-off may come back with it.
                if (listed(adb, "-d", pkg)) sh(adb, "pm enable $pkg")
                listed(adb, "-e", pkg)
            }
            ChangeKind.ENABLE -> pkg != null && mayRemove(adb, pkg) && run {
                sh(adb, "pm disable-user --user 0 $pkg")
                listed(adb, "-d", pkg)
            }
            ChangeKind.INSTALL_EXISTING -> pkg != null && mayRemove(adb, pkg) && run {
                sh(adb, "pm uninstall -k --user 0 $pkg")
                !listed(adb, "", pkg)
            }
            ChangeKind.ANIMATIONS -> restoreAnimations(adb, c.from)
            ChangeKind.APPOPS -> pkg != null && restoreAppop(adb, pkg, c.from)
            // Deleted apps need the Play Store; FLauncher and HOME belong to a swap and are undone with it.
            ChangeKind.UNINSTALL, ChangeKind.INSTALL_APK, ChangeKind.LAUNCHER_SWAP -> false
        }
    }

    /** `from` is "window,transition,animator"; "null" means never set (deleted again). */
    private suspend fun restoreAnimations(adb: AdbClient, from: String): Boolean {
        val values = from.split(',').map { it.trim() }
        if (values.size != ANIMATION_KEYS.size || values.any { it != "null" && !SCALE.matches(it) }) return false
        val wanted = ANIMATION_KEYS.zip(values)
        for ((key, v) in wanted) {
            sh(adb, if (v == "null") "settings delete global $key" else "settings put global $key $v")
        }
        return wanted.all { (key, v) ->
            val now = read(adb, "animation settings", "settings get global $key").trim()
            if (v == "null") now == "null" else now.toFloatOrNull() == v.toFloat()
        }
    }

    /** `from` is "<OP>=<mode>"; an older entry without "=" is RUN_ANY_IN_BACKGROUND. */
    private suspend fun restoreAppop(adb: AdbClient, pkg: String, from: String): Boolean {
        val op = if ('=' in from) from.substringBefore('=') else RUN_ANY_IN_BACKGROUND
        val mode = from.substringAfter('=')
        if (op !in APPOPS || mode !in APPOP_MODES) return false
        sh(adb, "cmd appops set $pkg $op $mode")
        val now = Parsers.appop(read(adb, "background setting", "cmd appops get $pkg $op", pmOutput = true), op) ?: "default"
        return effective(now) == effective(mode)
    }

    /** Both background ops default to allowed: the TV may print either "default" (or nothing) or "allow" for it. */
    private fun effective(mode: String) = if (mode == "default") "allow" else mode

    /** Switching off or removing for the user: never something vital, and never the home screen in use. */
    private suspend fun mayRemove(adb: AdbClient, pkg: String) = !SafetyGuard.isProtected(pkg, resolvedHome(adb))

    // --- the home screen ----------------------------------------------------------------------------------

    private class SwapUndo(val restored: Int, val left: List<Change>)

    private suspend fun undoSwap(adb: AdbClient, serial: String, swap: Swap): SwapUndo {
        val homeId = "${swap.id}:home"
        try {
            val log = onStore(adb) { store.load(serial) }.changes
            val mine = log.filter { swapIdOf(it) == swap.id }
            val onTv = mine.filter { it.id != homeId }
            if (onTv.isEmpty()) {
                // The swap switched nothing off and installed nothing: only its record is left.
                if (mine.isNotEmpty()) onStore(adb) { store.removeChanges(serial, setOf(homeId)) }
                return SwapUndo(0, emptyList())
            }
            // recover() acts on the newest logged swap; anything else would undo the wrong one.
            if (log.lastOrNull { it.id != homeId && swapIdOf(it) != null }?.let(::swapIdOf) != swap.id) {
                return SwapUndo(0, mine)
            }
            // Without ":home" the swap counts as unfinished; if Undo stops right here, the next recover() (or Undo)
            // still puts it back.
            if (mine.size != onTv.size) onStore(adb) { store.removeChanges(serial, setOf(homeId)) }
            launcher.recover(adb, serial)
            val left = onStore(adb) { store.load(serial) }.changes.filter { swapIdOf(it) == swap.id }
            return SwapUndo(onTv.count { c -> left.none { it.id == c.id } }, left)
        } catch (e: AdbError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SwapUndo(0, swap.entries)
        }
    }

    /** The stock home screens a swap switched off (its `:home` record, else its logged switch-offs). */
    private fun stockHomesOf(swap: Swap): List<String> {
        val recorded = swap.entries.firstOrNull { it.id == "${swap.id}:home" }?.from?.split(',').orEmpty()
        val switchedOff = swap.entries.filter { it.kind == ChangeKind.DISABLE }.mapNotNull { it.pkg }
        return (recorded + switchedOff).map { it.trim() }.filter { it.isNotEmpty() && it !in NOT_A_REAL_HOME }
    }

    /**
     * Presses HOME; true once one of [stock] (or, if none is known, another real home screen) is in front: never the
     * fallback home, the setup wizard, FLauncher or the chooser.
     */
    private suspend fun stockHomeInFront(adb: AdbClient, stock: Set<String>): Boolean = step {
        val expected = stock.ifEmpty { homePackages(adb).filter { it !in NOT_A_REAL_HOME }.toSet() }
        if (expected.isEmpty()) return@step false
        sh(adb, HOME_KEY)
        poll { foreground(adb) in expected }
    }

    // --- reads --------------------------------------------------------------------------------------------

    private suspend fun resolvedHome(adb: AdbClient): String? =
        Parsers.resolveActivityBrief(read(adb, "current home screen", RESOLVE_HOME))?.substringBefore('/')

    private suspend fun homePackages(adb: AdbClient): List<String> =
        Parsers.queryActivitiesPackages(read(adb, "home screens", QUERY_HOME))

    private suspend fun foreground(adb: AdbClient): String? {
        val r = sh(adb, "dumpsys activity activities")
        return if (r.exitCode == 0) Parsers.foregroundPackage(r.output) else null
    }

    /** True if [pkg] is in `pm list packages [flag] <pkg>` ("" installed for this user, "-e" on, "-d" off). */
    private suspend fun listed(adb: AdbClient, flag: String, pkg: String): Boolean {
        val cmd = listOf("pm list packages", flag, pkg).filter { it.isNotEmpty() }.joinToString(" ")
        return pkg in Parsers.packageNames(read(adb, "app list", cmd, pmOutput = true))
    }

    /** Output of a read; a non-zero exit (or a package-manager error) throws: it never passes for an answer. */
    private suspend fun read(adb: AdbClient, what: String, cmd: String, pmOutput: Boolean = false): String {
        val r = sh(adb, cmd)
        if (r.exitCode != 0) throw TvReadException(what, "exit code ${r.exitCode}")
        if (pmOutput && Parsers.isPmError(r.output)) throw TvReadException(what, "TV reported an error")
        return r.output
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private suspend fun refreshLock(adb: AdbClient, serial: String) {
        onStore(adb) { store.acquireLock(serial, phone) }.getOrThrow()
    }

    /**
     * One best-effort step: an odd or unreadable answer, or a state-file failure, counts as "not done". A lost
     * connection ([AdbError]) and cancellation propagate: nothing after them could work.
     */
    private suspend inline fun step(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: AdbError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    /**
     * Runs one TV call with a deadline. A blocking read on a wedged connection can't be cancelled, only ended by
     * closing the socket, so on timeout [adb] is closed and [AdbError.Timeout] thrown.
     */
    private suspend fun <T> bounded(adb: AdbClient, timeoutMs: Long, block: suspend () -> T): T =
        coroutineScope {
            val work = async { block() }
            if (withTimeoutOrNull(timeoutMs) { work.join() } == null) {
                runCatching { adb.close() }
                work.cancel()
                throw AdbError.Timeout()
            }
            work.await()
        }

    private suspend fun sh(adb: AdbClient, cmd: String) = bounded(adb, CALL_TIMEOUT_MS) { adb.shell(cmd) }

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

    private fun swapIdOf(c: Change): String? = c.id.takeIf { it.startsWith("swap-") && ':' in it }?.substringBefore(':')

    /** What a change is about; older changes to the same thing must not be undone before newer ones. */
    private fun keysOf(c: Change): List<String> = when (c.kind) {
        ChangeKind.ANIMATIONS -> listOf("animations")
        ChangeKind.APPOPS -> listOf("appops:${c.pkg}:${if ('=' in c.from) c.from.substringBefore('=') else RUN_ANY_IN_BACKGROUND}")
        else -> listOf("app:${c.pkg}")
    }

    private fun keysOf(work: Work): List<String> = when (work) {
        is One -> keysOf(work.change)
        is Swap -> work.entries.flatMap(::keysOf)
    }

    private fun nameOf(work: Work): String {
        val c = (work as? One)?.change ?: return HOME_SCREEN
        val pkg = c.pkg?.takeIf(::isPackageName)
        return when {
            c.kind == ChangeKind.ANIMATIONS -> "Animation speed"
            c.kind == ChangeKind.LAUNCHER_SWAP || pkg == FLAUNCHER_PKG -> HOME_SCREEN
            pkg == null -> UNKNOWN
            c.kind == ChangeKind.APPOPS -> "${plainName(pkg)} (running in the background)"
            else -> plainName(pkg)
        }
    }

    /** Same fallback as the cleaning checklist: the last part of the package name, tidied. */
    private fun plainName(pkg: String) =
        pkg.substringAfterLast('.').ifBlank { pkg }.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private class NoDownloads : ApkDownloader(OkHttpClient(), File("")) {
        override suspend fun fetch(url: String, sha256: String): File = throw IOException("Undo never downloads")
    }

    private companion object {
        const val HOME_SCREEN = "Home screen"
        const val UNKNOWN = "An unrecognised change"
        const val PLAY_STORE = "com.android.vending"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        /** Never counts as "the old home screen is back": fallback home (black screen), setup wizard, FLauncher, chooser. */
        val NOT_A_REAL_HOME = setOf(TV_SETTINGS, SETUPWRAITH, FLAUNCHER_PKG, "android")
        val ANIMATION_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val SCALE = Regex("""\d{1,3}(\.\d{1,6})?""")
        const val RUN_ANY_IN_BACKGROUND = "RUN_ANY_IN_BACKGROUND"
        val APPOPS = setOf(RUN_ANY_IN_BACKGROUND, "RUN_IN_BACKGROUND")
        val APPOP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")
        val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
        fun isPackageName(s: String) = PACKAGE.matches(s)
        const val HOME_KEY = "input keyevent KEYCODE_HOME"
        const val RESOLVE_HOME =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val QUERY_HOME =
            "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val POLL_MS = 500L
        const val VERIFY_TIMEOUT_MS = 15_000L
        const val CALL_TIMEOUT_MS = 10_000L
        const val STORE_TIMEOUT_MS = 30_000L
    }
}
