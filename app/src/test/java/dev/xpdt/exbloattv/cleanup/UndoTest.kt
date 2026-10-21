package dev.xpdt.exbloattv.cleanup

import com.google.common.truth.Truth.assertThat
import dev.xpdt.exbloattv.adb.AdbError
import dev.xpdt.exbloattv.cleanup.FakeTv.Companion.SETUPWRAITH
import dev.xpdt.exbloattv.cleanup.FakeTv.Companion.STOCK
import dev.xpdt.exbloattv.cleanup.FakeTv.Companion.TV_SETTINGS
import dev.xpdt.exbloattv.state.Change
import dev.xpdt.exbloattv.state.ChangeKind
import dev.xpdt.exbloattv.state.Lock
import dev.xpdt.exbloattv.state.RestorePoint
import dev.xpdt.exbloattv.state.TvLockedException
import dev.xpdt.exbloattv.state.TvState
import dev.xpdt.exbloattv.state.TvStateStore
import dev.xpdt.exbloattv.tv.AnimationScales
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UndoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val serial = "SER1"
    private val tv = FakeTv()
    private val pixel = "Pixel 8"
    private val galaxy = "Galaxy S24"
    private val home = "input keyevent KEYCODE_HOME"

    // --- extra TV behaviour Undo needs (FakeTv covers the launcher swap and the state file) ---------------------

    private val removedForUser = mutableSetOf<String>()
    private val settings = mutableMapOf(
        "window_animation_scale" to "1.0",
        "transition_animation_scale" to "1.0",
        "animator_duration_scale" to "1.0",
    )
    /** "<pkg>:<OP>" -> mode; absent = default. */
    private val appops = mutableMapOf<String, String>()
    /** Commands that run the given hook after the TV has applied them. */
    private val after = mutableMapOf<String, () -> Unit>()
    /** Runs after every command. */
    private var afterAny: (String) -> Unit = {}

    init {
        tv.installed += listOf(ADS, NEWS, SPORTS, BLOAT, GAME)
        val base = tv.adb.onCommand
        tv.adb.onCommand = { cmd ->
            val code = extra(cmd)
            if (code == null) base(cmd) else tv.adb.exitCodes[cmd] = code
            after[cmd]?.invoke()
            afterAny(cmd)
        }
    }

    private fun extra(cmd: String): Int? {
        val out = tv.adb.responses
        Regex("""^settings get global (\S+)$""").matchEntire(cmd)?.let { out[cmd] = (settings[it.groupValues[1]] ?: "null") + "\n"; return 0 }
        Regex("""^settings put global (\S+) (\S+)$""").matchEntire(cmd)?.let { settings[it.groupValues[1]] = it.groupValues[2]; out[cmd] = ""; return 0 }
        Regex("""^settings delete global (\S+)$""").matchEntire(cmd)?.let { settings -= it.groupValues[1]; out[cmd] = "Deleted 1 rows\n"; return 0 }
        Regex("""^cmd appops get (\S+) (RUN_ANY_IN_BACKGROUND|RUN_IN_BACKGROUND)$""").matchEntire(cmd)?.let {
            val (p, op) = it.destructured
            out[cmd] = appops["$p:$op"]?.let { m -> "$op: $m; time=+4m2s ago\n" } ?: "No operations.\n"
            return 0
        }
        Regex("""^cmd appops set (\S+) (RUN_ANY_IN_BACKGROUND|RUN_IN_BACKGROUND) (\w+)$""").matchEntire(cmd)?.let {
            val (p, op, mode) = it.destructured
            if (mode == "default") appops -= "$p:$op" else appops["$p:$op"] = mode
            out[cmd] = ""
            return 0
        }
        Regex("""^cmd package install-existing (\S+)$""").matchEntire(cmd)?.let {
            val p = it.groupValues[1]
            if (p !in removedForUser && p !in tv.installed) { out[cmd] = "Error: package $p not found\n"; return 1 }
            removedForUser -= p
            tv.installed += p
            out[cmd] = "Package $p installed for user: 0\n"
            return 0
        }
        Regex("""^pm uninstall -k --user 0 (\S+)$""").matchEntire(cmd)?.let {
            val p = it.groupValues[1]
            if (!tv.installed.remove(p)) { out[cmd] = "Failure [not installed for 0]\n"; return 1 }
            removedForUser += p
            out[cmd] = "Success\n"
            return 0
        }
        if (cmd.startsWith("am start -a android.intent.action.VIEW -d ")) {
            out[cmd] = "Starting: Intent { act=android.intent.action.VIEW pkg=com.android.vending }\n"
            return 0
        }
        return null
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private inner class StubDownloader : ApkDownloader(OkHttpClient(), File("unused")) {
        override suspend fun fetch(url: String, sha256: String): File =
            File(tmp.root, "$sha256.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private fun TestScope.store() = TvStateStore(tv.adb) { testScheduler.currentTime }

    private fun TestScope.undo(phone: String = pixel): Undo {
        tv.clockMs = { testScheduler.currentTime }
        return Undo(store(), phone, testScheduler.timeSource) { testScheduler.currentTime }
    }

    /** A real launcher swap by [phone], as the cleanup runner does it. */
    private suspend fun TestScope.swap(phone: String = pixel) {
        tv.clockMs = { testScheduler.currentTime }
        val r = LauncherSwap(StubDownloader(), store(), phone, testScheduler.timeSource) { testScheduler.currentTime }
            .swap(tv.adb, serial)
        assertThat(r).isEqualTo(SwapResult.Swapped)
    }

    private var seq = 0

    /** Logs one cleanup-runner change and applies it to the simulated TV. */
    private suspend fun TestScope.record(
        phone: String,
        kind: ChangeKind,
        pkg: String?,
        from: String,
        to: String,
        verified: Boolean = true,
        apply: () -> Unit = {},
    ): Change {
        val c = Change("run-${seq}-abcd1234:${seq++}:ef567890", kind, pkg, from, to, phone, testScheduler.currentTime, verified)
        store().appendChange(serial, c)
        apply()
        return c
    }

    private suspend fun TestScope.disable(phone: String, pkg: String, verified: Boolean = true) =
        record(phone, ChangeKind.DISABLE, pkg, "enabled", "disabled", verified) { tv.disabled += pkg }

    private suspend fun TestScope.state(): TvState = store().load(serial)

    private val original = RestorePoint(
        takenAt = 1, byPhone = pixel, enabled = listOf(STOCK), disabled = emptyList(), userInstalled = listOf(GAME),
        resolvedHome = STOCK, animations = AnimationScales(1f, 1f, 1f), backgroundOps = mapOf(NETFLIX to "allow"),
    )

    private val mutating = listOf(
        "pm enable ", "pm disable-user ", "pm uninstall", "cmd package install-existing ", "settings put ",
        "settings delete ", "cmd appops set ", "input keyevent ", "am start ", "monkey ",
    )

    private fun mutations() = tv.adb.log.filter { c -> mutating.any { c.startsWith(it) } }

    private suspend inline fun <reified T : Throwable> thrown(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    /** Two phones' worth of changes; the home-screen swap is the oldest. */
    private suspend fun TestScope.twoPhoneLogWithSwapFirst() {
        store().setOriginalIfAbsent(serial, original)
        swap(pixel)
        appops["$NETFLIX:$ANY"] = "allow"
        disable(pixel, ADS)
        record(pixel, ChangeKind.ANIMATIONS, null, "1.0,1.0,1.0", "0.5,0.5,0.5") {
            settings.keys.forEach { settings[it] = "0.5" }
        }
        record(pixel, ChangeKind.APPOPS, NETFLIX, "$ANY=allow", "$ANY=ignore") { appops["$NETFLIX:$ANY"] = "ignore" }
        record(pixel, ChangeKind.APPOPS, NETFLIX, "$RIB=default", "$RIB=ignore") { appops["$NETFLIX:$RIB"] = "ignore" }
        tv.disabled += SPORTS
        tv.installed -= MUSIC
        removedForUser += MUSIC
        record(galaxy, ChangeKind.REMOVE_FOR_USER, BLOAT, "enabled", "removed") { tv.installed -= BLOAT; removedForUser += BLOAT }
        record(galaxy, ChangeKind.ENABLE, SPORTS, "disabled", "enabled") { tv.disabled -= SPORTS }
        record(galaxy, ChangeKind.INSTALL_EXISTING, MUSIC, "removed", "installed") { removedForUser -= MUSIC; tv.installed += MUSIC }
        record(galaxy, ChangeKind.UNINSTALL, GAME, "installed", "deleted") { tv.installed -= GAME }
        // Cut off before it was confirmed: it may or may not have happened, so it is reversed too.
        disable(galaxy, NEWS, verified = false)
        tv.adb.log.clear()
    }

    // --- the whole log --------------------------------------------------------------------------------------

    @Test fun twoPhoneLogIsReversedNewestFirstWithFlauncherRemovedLastBeforeTheHomeCheck() = runTest {
        twoPhoneLogWithSwapFirst()

        val report = undo(galaxy).undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly(
            "pm enable $NEWS",
            "pm uninstall -k --user 0 $MUSIC",
            "pm disable-user --user 0 $SPORTS",
            "cmd package install-existing $BLOAT",
            "cmd appops set $NETFLIX $RIB default",
            "cmd appops set $NETFLIX $ANY allow",
            "settings put global window_animation_scale 1.0",
            "settings put global transition_animation_scale 1.0",
            "settings put global animator_duration_scale 1.0",
            "pm enable $ADS",
            "pm enable $SETUPWRAITH",
            "pm enable $STOCK",
            "pm uninstall $FLAUNCHER_PKG",
            home, // LauncherSwap's own rollback check
            home, // the final check after everything
        ).inOrder()
        assertThat(report).isEqualTo(UndoReport(restored = 11, failed = emptyList(), reinstallFromStore = listOf(GAME)))

        // The TV is back as it was (the deleted app aside).
        assertThat(tv.disabled).containsExactly(SPORTS)
        assertThat(tv.installed).containsAtLeast(STOCK, SETUPWRAITH, ADS, NEWS, BLOAT)
        assertThat(tv.installed).containsNoneOf(FLAUNCHER_PKG, MUSIC, GAME)
        assertThat(tv.foreground).isEqualTo(STOCK)
        assertThat(settings.values.toSet()).containsExactly("1.0")
        assertThat(appops).containsExactly("$NETFLIX:$ANY", "allow")

        // Only the deleted app stays logged (it can't be put back by the TV); the restore point is untouched.
        val s = state()
        assertThat(s.changes.map { it.kind to it.pkg }).containsExactly(ChangeKind.UNINSTALL to GAME)
        assertThat(s.original).isEqualTo(original)
        assertThat(s.lock).isNull()
    }

    @Test fun aSwapInTheMiddleIsUndoneAsOneUnitBetweenNewerAndOlderChanges() = runTest {
        disable(pixel, ADS)
        swap(pixel)
        disable(galaxy, NEWS)
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly(
            "pm enable $NEWS",
            "pm enable $SETUPWRAITH",
            "pm enable $STOCK",
            "pm uninstall $FLAUNCHER_PKG",
            home,
            "pm enable $ADS",
            home,
        ).inOrder()
        assertThat(report.failed).isEmpty()
        assertThat(report.restored).isEqualTo(5)
        assertThat(state().changes).isEmpty()
        assertThat(tv.foreground).isEqualTo(STOCK)
    }

    @Test fun noSwapLoggedMeansHomeIsNeverPressed() = runTest {
        disable(pixel, ADS)
        tv.adb.log.clear()
        val report = undo().undoAll(tv.adb, serial)
        assertThat(mutations()).containsExactly("pm enable $ADS")
        assertThat(report).isEqualTo(UndoReport(1, emptyList(), emptyList()))
    }

    @Test fun emptyLogChangesNothingAndReleasesTheLock() = runTest {
        val report = undo().undoAll(tv.adb, serial)
        assertThat(report).isEqualTo(UndoReport(0, emptyList(), emptyList()))
        assertThat(mutations()).isEmpty()
        assertThat(state().lock).isNull()
    }

    // --- failures -------------------------------------------------------------------------------------------

    @Test fun aReversalThatFailsVerificationStaysLoggedAndIsReportedWhileTheRestGoOn() = runTest {
        disable(pixel, ADS)
        disable(pixel, NEWS)
        tv.failingEnables += ADS
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly("pm enable $NEWS", "pm enable $ADS").inOrder()
        assertThat(report).isEqualTo(UndoReport(1, listOf(UndoFailure(ADS, ChangeKind.DISABLE)), emptyList()))
        assertThat(state().changes.map { it.pkg }).containsExactly(ADS)
        assertThat(state().lock).isNull()
    }

    @Test fun anUnreadableAnswerCountsAsNotUndone() = runTest {
        disable(pixel, ADS)
        tv.failingReads += "pm list packages -e $ADS"
        val report = undo().undoAll(tv.adb, serial)
        assertThat(report.failed).containsExactly(UndoFailure(ADS, ChangeKind.DISABLE))
        assertThat(state().changes).hasSize(1)
    }

    @Test fun olderChangesToAnAppWaitWhileANewerOneCouldNotBeUndone() = runTest {
        disable(pixel, SPORTS)
        record(galaxy, ChangeKind.ENABLE, SPORTS, "disabled", "enabled") { tv.disabled -= SPORTS }
        tv.stubbornPackages += SPORTS // switching it off again doesn't stick
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        // Undoing the older "switch off" now would leave a log that no longer matches the TV.
        assertThat(mutations()).containsExactly("pm disable-user --user 0 $SPORTS")
        assertThat(report.restored).isEqualTo(0)
        assertThat(report.failed).containsExactly(
            UndoFailure(SPORTS, ChangeKind.ENABLE),
            UndoFailure(SPORTS, ChangeKind.DISABLE),
        ).inOrder()
        assertThat(state().changes.map { it.kind }).containsExactly(ChangeKind.DISABLE, ChangeKind.ENABLE).inOrder()
    }

    @Test fun olderChangesToADeletedAppStayQuietlyUntilItIsReinstalled() = runTest {
        disable(pixel, GAME)
        record(galaxy, ChangeKind.UNINSTALL, GAME, "installed", "deleted") { tv.installed -= GAME; tv.disabled -= GAME }
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).isEmpty()
        assertThat(report).isEqualTo(UndoReport(0, emptyList(), listOf(GAME)))
        assertThat(state().changes).hasSize(2)
    }

    @Test fun neverSwitchesOffAProtectedAppEvenIfTheLogSaysSo() = runTest {
        tv.installed += "com.android.vending"
        tv.disabled += "com.android.vending"
        record(galaxy, ChangeKind.ENABLE, "com.android.vending", "disabled", "enabled") { tv.disabled -= "com.android.vending" }
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).isEmpty()
        assertThat(report.failed).containsExactly(UndoFailure("com.android.vending", ChangeKind.ENABLE))
        assertThat(state().changes).hasSize(1)
    }

    @Test fun neverRemovesTheAppThatIsCurrentlyTheHomeScreen() = runTest {
        removedForUser += STOCK
        tv.installed -= STOCK
        record(galaxy, ChangeKind.INSTALL_EXISTING, STOCK, "removed", "installed") { removedForUser -= STOCK; tv.installed += STOCK }
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).isEmpty()
        assertThat(report.failed).containsExactly(UndoFailure(STOCK, ChangeKind.INSTALL_EXISTING))
        assertThat(tv.installed).contains(STOCK)
    }

    @Test fun aTamperedLogEntryIsNeverRunAsACommand() = runTest {
        record(pixel, ChangeKind.DISABLE, "com.x;reboot", "enabled", "disabled")
        record(pixel, ChangeKind.ANIMATIONS, null, "1.0;reboot,1.0,1.0", "0.5,0.5,0.5")
        record(pixel, ChangeKind.APPOPS, NETFLIX, "$ANY=allow;reboot", "$ANY=ignore")
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).isEmpty()
        assertThat(tv.adb.log.none { "reboot" in it }).isTrue()
        assertThat(report.restored).isEqualTo(0)
        assertThat(report.failed).containsExactly(
            UndoFailure(NETFLIX, ChangeKind.APPOPS),
            UndoFailure(null, ChangeKind.ANIMATIONS),
            UndoFailure(null, ChangeKind.DISABLE),
        ).inOrder()
        assertThat(state().changes).hasSize(3)
    }

    @Test fun homeNotComingBackIsReported() = runTest {
        swap(pixel)
        tv.onHome = { TV_SETTINGS } // the black fallback screen
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        // The stock launcher is back on but HOME lands on the fallback screen: the person is told.
        assertThat(report.failed).containsExactly(UndoFailure(null, ChangeKind.LAUNCHER_SWAP)) // once, not twice
        assertThat(tv.disabled).isEmpty()
    }

    // --- the home screen ------------------------------------------------------------------------------------

    @Test fun thePersonsOwnFlauncherStays() = runTest {
        tv.installed += FLAUNCHER_PKG // installed by the person before any swap
        swap(pixel)
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).doesNotContain("pm uninstall $FLAUNCHER_PKG")
        assertThat(tv.installed).contains(FLAUNCHER_PKG)
        assertThat(tv.disabled).isEmpty()
        assertThat(tv.foreground).isEqualTo(STOCK)
        assertThat(report.failed).isEmpty()
        assertThat(state().changes).isEmpty()
    }

    @Test fun anUnfinishedSwapIsPutBackToo() = runTest {
        tv.installed += FLAUNCHER_PKG
        // Cut off right after switching the stock launcher off: no ":home", nothing confirmed.
        store().appendChange(serial, Change("swap-5-abcd1234:disable:$STOCK", ChangeKind.DISABLE, STOCK, "enabled", "disabled", pixel, 5, false))
        tv.disabled += STOCK
        tv.foreground = FLAUNCHER_PKG
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly("pm enable $STOCK", home, home).inOrder()
        assertThat(report).isEqualTo(UndoReport(1, emptyList(), emptyList()))
        assertThat(tv.foreground).isEqualTo(STOCK)
        assertThat(state().changes).isEmpty()
    }

    // --- settings -------------------------------------------------------------------------------------------

    @Test fun animationsNeverSetBeforeAreClearedAgain() = runTest {
        settings -= "window_animation_scale"
        record(pixel, ChangeKind.ANIMATIONS, null, "null,0.5,1", "0.5,0.5,0.5") {
            listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach { settings[it] = "0.5" }
        }
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly(
            "settings delete global window_animation_scale",
            "settings put global transition_animation_scale 0.5",
            "settings put global animator_duration_scale 1",
        ).inOrder()
        assertThat(report.restored).isEqualTo(1)
        assertThat(settings).doesNotContainKey("window_animation_scale")
        assertThat(state().changes).isEmpty()
    }

    @Test fun aBackgroundSettingThatWasNeverSetGoesBackToDefault() = runTest {
        // An Android 8 TV only has RUN_IN_BACKGROUND.
        record(pixel, ChangeKind.APPOPS, NETFLIX, "$RIB=default", "$RIB=ignore") { appops["$NETFLIX:$RIB"] = "ignore" }
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly("cmd appops set $NETFLIX $RIB default")
        assertThat(tv.adb.log).contains("cmd appops get $NETFLIX $RIB")
        assertThat(appops).isEmpty()
        assertThat(report.restored).isEqualTo(1)
    }

    @Test fun anOlderBackgroundEntryWithoutTheOpNameIsRunAnyInBackground() = runTest {
        record(pixel, ChangeKind.APPOPS, NETFLIX, "allow", "ignore") { appops["$NETFLIX:$ANY"] = "ignore" }
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly("cmd appops set $NETFLIX $ANY allow")
        assertThat(appops).containsExactly("$NETFLIX:$ANY", "allow")
        assertThat(report.restored).isEqualTo(1)
    }

    @Test fun aBackgroundSettingThatDoesNotStickIsReported() = runTest {
        record(pixel, ChangeKind.APPOPS, NETFLIX, "$ANY=allow", "$ANY=ignore") { appops["$NETFLIX:$ANY"] = "ignore" }
        after["cmd appops set $NETFLIX $ANY allow"] = { appops["$NETFLIX:$ANY"] = "ignore" }
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(report).isEqualTo(UndoReport(0, listOf(UndoFailure(NETFLIX, ChangeKind.APPOPS)), emptyList()))
        assertThat(state().changes).hasSize(1)
    }

    @Test fun anAppRemovedForTheUserComesBackSwitchedOn() = runTest {
        record(galaxy, ChangeKind.REMOVE_FOR_USER, BLOAT, "enabled", "removed") {
            tv.installed -= BLOAT; removedForUser += BLOAT
        }
        tv.disabled += BLOAT // the TV kept a half-applied "switched off" for it
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly("cmd package install-existing $BLOAT", "pm enable $BLOAT").inOrder()
        assertThat(report.restored).isEqualTo(1)
        assertThat(tv.disabled).doesNotContain(BLOAT)
    }

    // --- lock -----------------------------------------------------------------------------------------------

    @Test fun anotherPhoneHoldingTheTvStopsUndoBeforeAnythingChanges() = runTest {
        disable(pixel, ADS)
        val s = state()
        store().save(s.copy(lock = Lock(galaxy, testScheduler.currentTime)))
        tv.adb.log.clear()

        val e = thrown<TvLockedException> { undo(pixel).undoAll(tv.adb, serial) }

        assertThat(e.byPhone).isEqualTo(galaxy)
        assertThat(tv.adb.log.filterNot { FakeTv.isStateFileCommand(it) }).isEmpty()
        assertThat(state().changes).hasSize(1)
        assertThat(state().lock?.byPhone).isEqualTo(galaxy)
    }

    @Test fun losingTheLockMidwayStopsAndLeavesTheOtherPhonesLockAlone() = runTest {
        disable(pixel, ADS)
        disable(pixel, NEWS)
        // While the first reversal runs, our lock lapses and another phone takes the TV.
        after["pm enable $NEWS"] = {
            runBlocking { TvStateStore(tv.adb) { testScheduler.currentTime }.let { it.save(it.load(serial).copy(lock = Lock(galaxy, testScheduler.currentTime))) } }
        }
        tv.adb.log.clear()

        val e = thrown<UndoInterruptedException> { undo(pixel).undoAll(tv.adb, serial) }

        assertThat(e.cause).isInstanceOf(TvLockedException::class.java)
        assertThat(e.partial).isEqualTo(UndoReport(1, emptyList(), emptyList()))
        assertThat(mutations()).containsExactly("pm enable $NEWS")
        assertThat(state().lock?.byPhone).isEqualTo(galaxy)
        assertThat(state().changes.map { it.pkg }).containsExactly(ADS)
    }

    // --- interruptions --------------------------------------------------------------------------------------

    @Test fun cancellingFinishesTheCurrentReversalThenStopsAndReleasesTheLock() = runTest {
        disable(pixel, ADS)
        disable(pixel, NEWS)
        tv.adb.log.clear()
        lateinit var job: Job
        after["pm enable $NEWS"] = { job.cancel() }

        job = launch { undo().undoAll(tv.adb, serial) }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(mutations()).containsExactly("pm enable $NEWS")
        assertThat(tv.adb.log).contains("pm list packages -e $NEWS") // verified despite the cancel
        val s = state()
        assertThat(s.changes.map { it.pkg }).containsExactly(ADS) // and logged: a new Undo carries on from here
        assertThat(s.lock).isNull()
    }

    @Test fun aLostConnectionStopsUndoAndKeepsWhatWasAlreadyUndone() = runTest {
        disable(pixel, ADS)
        disable(pixel, NEWS)
        tv.adb.log.clear()
        tv.adb.failNext("pm enable $ADS", AdbError.Disconnected())

        val e = thrown<UndoInterruptedException> { undo().undoAll(tv.adb, serial) }

        assertThat(e.cause).isInstanceOf(AdbError.Disconnected::class.java)
        assertThat(e.partial).isEqualTo(UndoReport(1, emptyList(), emptyList())) // what was put back before it stopped
        assertThat(state().changes.map { it.pkg }).containsExactly(ADS)
        assertThat(state().lock).isNull()

        // A second Undo picks up where the first stopped.
        tv.adb.log.clear()
        val report = undo().undoAll(tv.adb, serial)
        assertThat(mutations()).containsExactly("pm enable $ADS")
        assertThat(report.restored).isEqualTo(1)
        assertThat(state().changes).isEmpty()
    }

    @Test fun connectionLostRightAfterTheSwapIsMarkedUnfinishedIsCompletedByTheNextUndo() = runTest {
        swap(pixel)
        tv.adb.log.clear()
        val file = TvStateStore.FILE
        var cut = false
        afterAny = { cmd ->
            // The write that drops ":home" just landed: the next read (recover() loading the log) loses the TV.
            if (!cut && cmd.startsWith("mv ") && tv.fs[file]?.contains(":home") == false) {
                cut = true
                tv.adb.failNext("cat $file", AdbError.Disconnected())
            }
        }

        val e = thrown<UndoInterruptedException> { undo().undoAll(tv.adb, serial) }

        assertThat(e.cause).isInstanceOf(AdbError.Disconnected::class.java)
        assertThat(tv.disabled).containsExactly(STOCK, SETUPWRAITH) // nothing on the TV changed yet
        assertThat(state().changes.map { it.id.substringAfter(':') })
            .containsExactly("install", "disable:$STOCK", "disable:$SETUPWRAITH")

        tv.adb.log.clear()
        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly(
            "pm enable $SETUPWRAITH", "pm enable $STOCK", "pm uninstall $FLAUNCHER_PKG", home, home,
        ).inOrder()
        assertThat(report).isEqualTo(UndoReport(3, emptyList(), emptyList()))
        assertThat(tv.installed).doesNotContain(FLAUNCHER_PKG)
        assertThat(tv.foreground).isEqualTo(STOCK)
        assertThat(state().changes).isEmpty()
    }

    @Test fun aSwapRecordWithNothingToPutBackIsDroppedWithoutPressingHome() = runTest {
        store().appendChange(
            serial,
            Change("swap-7-abcd1234:home", ChangeKind.LAUNCHER_SWAP, FLAUNCHER_PKG, "", FLAUNCHER_PKG, pixel, 7, true),
        )
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).isEmpty()
        assertThat(report).isEqualTo(UndoReport(0, emptyList(), emptyList()))
        assertThat(state().changes).isEmpty()
    }

    // --- another phone changing the log --------------------------------------------------------------------

    @Test fun aLogChangedByAnotherPhoneMidwayIsPlannedAgain() = runTest {
        disable(pixel, SPORTS)
        disable(pixel, ADS)
        disable(pixel, NEWS)
        // While this phone was away (its lock lapsed), another phone switched Sports back on, logged it and left.
        after["pm enable $NEWS"] = {
            runBlocking {
                val other = TvStateStore(tv.adb) { testScheduler.currentTime }
                val s = other.load(serial)
                val foreign = Change("run-9-abcd1234:0:ef567890", ChangeKind.ENABLE, SPORTS, "disabled", "enabled", galaxy, 9, true)
                other.save(s.copy(changes = s.changes + foreign, lock = null))
            }
            tv.disabled -= SPORTS
        }
        tv.adb.log.clear()

        val report = undo(pixel).undoAll(tv.adb, serial)

        // The newer change is undone first, then the older ones, so Sports ends as it started: on.
        assertThat(mutations()).containsExactly(
            "pm enable $NEWS",
            "pm disable-user --user 0 $SPORTS",
            "pm enable $ADS",
            "pm enable $SPORTS",
        ).inOrder()
        assertThat(report).isEqualTo(UndoReport(4, emptyList(), emptyList()))
        assertThat(tv.disabled).isEmpty()
        assertThat(state().changes).isEmpty()
    }

    // --- deleted apps ---------------------------------------------------------------------------------------

    @Test fun aDeletedAppThatWasReinstalledIsDroppedAndItsOlderChangesUndone() = runTest {
        disable(pixel, GAME)
        record(galaxy, ChangeKind.UNINSTALL, GAME, "installed", "deleted") { tv.installed -= GAME; tv.disabled -= GAME }
        tv.installed += GAME // the person got it back from the Play Store
        tv.adb.log.clear()

        val report = undo().undoAll(tv.adb, serial)

        assertThat(mutations()).containsExactly("pm enable $GAME")
        assertThat(report).isEqualTo(UndoReport(2, emptyList(), emptyList()))
        assertThat(state().changes).isEmpty()
    }

    @Test fun anUnconfirmedDeleteOfAMissingAppIsListedToo() = runTest {
        record(galaxy, ChangeKind.UNINSTALL, GAME, "installed", "deleted", verified = false) { tv.installed -= GAME }
        val report = undo().undoAll(tv.adb, serial)
        assertThat(report).isEqualTo(UndoReport(0, emptyList(), listOf(GAME)))
        assertThat(state().changes).hasSize(1)
    }

    @Test fun aReinstalledAppThatIsSwitchedOffIsReportedNotListed() = runTest {
        record(galaxy, ChangeKind.UNINSTALL, GAME, "installed", "deleted") { tv.disabled += GAME }
        val report = undo().undoAll(tv.adb, serial)
        assertThat(report).isEqualTo(UndoReport(0, listOf(UndoFailure(GAME, ChangeKind.UNINSTALL)), emptyList()))
        assertThat(state().changes).hasSize(1)
    }

    @Test fun anUnreadableAppListNeverListsOrDropsADeletedApp() = runTest {
        record(galaxy, ChangeKind.UNINSTALL, GAME, "installed", "deleted") { tv.installed -= GAME }
        tv.failingReads += "pm list packages $GAME"
        val report = undo().undoAll(tv.adb, serial)
        assertThat(report).isEqualTo(UndoReport(0, listOf(UndoFailure(GAME, ChangeKind.UNINSTALL)), emptyList()))
        assertThat(state().changes).hasSize(1)
    }

    // --- Play Store -----------------------------------------------------------------------------------------

    @Test fun openInPlayStoreSendsTheStoreIntent() = runTest {
        undo().openInPlayStore(tv.adb, GAME)
        assertThat(tv.adb.log).containsExactly(
            "am start -a android.intent.action.VIEW -d 'market://details?id=$GAME' -p com.android.vending",
        )
    }

    @Test fun openInPlayStoreRefusesSomethingThatIsNotAnAppName() = runTest {
        thrown<IllegalArgumentException> { undo().openInPlayStore(tv.adb, "x';reboot;'") }
        assertThat(tv.adb.log).isEmpty()
    }

    @Test fun openInPlayStoreReportsATvThatCantOpenIt() = runTest {
        val cmd = "am start -a android.intent.action.VIEW -d 'market://details?id=$GAME' -p com.android.vending"
        after[cmd] = { tv.adb.responses[cmd] = "Error: Activity not started, unable to resolve Intent\n" }
        thrown<PlayStoreOpenException> { undo().openInPlayStore(tv.adb, GAME) }
    }

    private companion object {
        const val ADS = "com.example.ads"
        const val NEWS = "com.example.news"
        const val SPORTS = "com.example.sports"
        const val BLOAT = "com.example.bloat"
        const val MUSIC = "com.example.music"
        const val GAME = "com.user.game"
        const val NETFLIX = "com.netflix.ninja"
        const val ANY = "RUN_ANY_IN_BACKGROUND"
        const val RIB = "RUN_IN_BACKGROUND"
    }
}
