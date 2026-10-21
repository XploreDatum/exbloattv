package dev.mntl.exbloattv.cleanup

import com.google.common.truth.Truth.assertThat
import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.adb.FakeAdbClient
import dev.mntl.exbloattv.adb.ShellResult
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.QUERY_HOME
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.RESOLVE_HOME
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.SETUPWRAITH
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.STOCK
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.TV_SETTINGS
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.state.TvStateWriteException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class) // virtual time: testScheduler.currentTime / timeSource
class LauncherSwapTest {
    @get:Rule val tmp = TemporaryFolder()

    private val serial = "SER1"
    private val tv = FakeTv()
    private val home = "input keyevent KEYCODE_HOME"
    private val monkey = "monkey -p $FLAUNCHER_PKG -c android.intent.category.LAUNCHER 1"
    private val dumpsys = "dumpsys activity activities"

    /** Hands out a local file instead of downloading; records what was asked for. */
    private inner class StubDownloader : ApkDownloader(OkHttpClient(), File("unused")) {
        val calls = mutableListOf<Pair<String, String>>()
        var failure: Throwable? = null
        override suspend fun fetch(url: String, sha256: String): File {
            calls += url to sha256
            failure?.let { throw it }
            return File(tmp.root, "$sha256.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        }
    }

    private val downloader = StubDownloader()

    private fun TestScope.swapper(store: TvStateStore = TvStateStore(tv.adb) { testScheduler.currentTime }) =
        LauncherSwap(downloader, store, "Pixel 8", testScheduler.timeSource) { testScheduler.currentTime }

    private suspend fun changes(): List<Change> = TvStateStore(tv.adb).load(serial).changes

    private fun tvCommands() = tv.adb.log.filterNot { FakeTv.isStateFileCommand(it) }

    private suspend inline fun <reified T : Throwable> thrown(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    // --- swap ---------------------------------------------------------------------------------------------

    @Test fun happyPathIssuesCommandsInOrderAndLogsVerifiedChanges() = runTest {
        val result = swapper().swap(tv.adb, serial)

        assertThat(result).isEqualTo(SwapResult.Swapped)
        assertThat(downloader.calls).containsExactly(FLAUNCHER_URL to FLAUNCHER_SHA256)
        assertThat(tvCommands()).containsExactly(
            "getprop ro.product.manufacturer",
            RESOLVE_HOME,
            "pm list packages $FLAUNCHER_PKG",
            "install:$FLAUNCHER_SHA256.apk",
            "pm list packages $FLAUNCHER_PKG",
            monkey,
            dumpsys,
            QUERY_HOME,
            "pm list packages -e $SETUPWRAITH",
            "pm disable-user --user 0 $STOCK",
            "pm list packages -d $STOCK",
            "pm disable-user --user 0 $SETUPWRAITH",
            "pm list packages -d $SETUPWRAITH",
            home,
            RESOLVE_HOME,
            dumpsys,
        ).inOrder()
        assertThat(tv.disabled).containsExactly(STOCK, SETUPWRAITH)
        assertThat(tv.foreground).isEqualTo(FLAUNCHER_PKG)

        val log = changes()
        assertThat(log.map { it.kind to it.pkg }).containsExactly(
            ChangeKind.INSTALL_APK to FLAUNCHER_PKG,
            ChangeKind.DISABLE to STOCK,
            ChangeKind.DISABLE to SETUPWRAITH,
            ChangeKind.LAUNCHER_SWAP to FLAUNCHER_PKG,
        ).inOrder()
        assertThat(log.all { it.verified && it.byPhone == "Pixel 8" }).isTrue()
        val swap = log.last()
        assertThat(swap.from).isEqualTo(STOCK)
        assertThat(swap.to).isEqualTo(FLAUNCHER_PKG)
    }

    @Test fun neverDisablesTvSettingsOrOtherProtectedHomeApps() = runTest {
        tv.installed += "com.android.settings"
        tv.homePriority["com.android.settings"] = -999
        assertThat(swapper().swap(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        assertThat(tv.disabled).containsExactly(STOCK, SETUPWRAITH)
        assertThat(tvCommands().filter { it.startsWith("pm disable-user") })
            .containsExactly("pm disable-user --user 0 $STOCK", "pm disable-user --user 0 $SETUPWRAITH")
        assertThat(tvCommands()).doesNotContain("pm disable-user --user 0 $TV_SETTINGS")
    }

    @Test fun setupwraithAlreadyOffIsLeftAloneAndNotReEnabledOnRollback() = runTest {
        tv.disabled += SETUPWRAITH
        tv.onHome = { "com.netflix.ninja" } // HOME never reaches FLauncher
        val result = swapper().swap(tv.adb, serial)
        assertThat(result).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tvCommands()).doesNotContain("pm disable-user --user 0 $SETUPWRAITH")
        assertThat(tvCommands()).doesNotContain("pm enable $SETUPWRAITH")
        assertThat(tv.disabled).containsExactly(SETUPWRAITH)
    }

    @Test fun homeStolenBySetupwraithRollsBackAndReEnablesEverything() = runTest {
        // While the stock launcher is off, HOME lands on the setup wizard and stays there.
        tv.onHome = { if (STOCK in tv.disabled) SETUPWRAITH else tv.resolvedHome() }
        val result = swapper().swap(tv.adb, serial)

        assertThat(result).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat((result as SwapResult.RolledBack).reason).contains("old home screen is back")
        assertThat(tv.disabled).isEmpty()
        val cmds = tvCommands()
        val firstHome = cmds.indexOf(home)
        val tail = cmds.drop(firstHome + 1)
        // FLauncher, installed by this swap, goes only after the old home screen is back on; then HOME.
        assertThat(tail).containsAtLeast("pm enable $SETUPWRAITH", "pm enable $STOCK", "pm uninstall $FLAUNCHER_PKG", home).inOrder()
        assertThat(tv.installed).doesNotContain(FLAUNCHER_PKG)
        assertThat(tv.foreground).isEqualTo(STOCK)
        assertThat(testScheduler.currentTime).isAtLeast(15_000L)
        // Undo must never see a DISABLE for a package that is enabled again, a swap that did not happen,
        // or an install that was removed.
        assertThat(changes()).isEmpty()
    }

    @Test fun rollbackKeepsAPreinstalledFlauncher() = runTest {
        tv.installed += FLAUNCHER_PKG
        tv.onHome = { if (STOCK in tv.disabled) SETUPWRAITH else tv.resolvedHome() }
        assertThat(swapper().swap(tv.adb, serial)).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tvCommands().none { it.startsWith("pm uninstall") }).isTrue()
        assertThat(tv.installed).contains(FLAUNCHER_PKG)
        assertThat(tv.disabled).isEmpty()
    }

    @Test fun unwritableStateFileDoesNotStopTheTvGettingItsHomeBack() = runTest {
        tv.onHome = {
            if (STOCK in tv.disabled) SETUPWRAITH.also { tv.stateWritesFail = true } else tv.resolvedHome()
        }
        thrown<TvStateWriteException> { swapper().swap(tv.adb, serial) }
        assertThat(tv.disabled).isEmpty()
        assertThat(tv.foreground).isEqualTo(STOCK)
    }

    @Test fun homeResolvingToChooserRollsBack() = runTest {
        tv.homePriority[TV_SETTINGS] = 0 // ties with FLauncher once the stock launcher is off
        val result = swapper().swap(tv.adb, serial)
        assertThat(result).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tv.disabled).isEmpty()
    }

    @Test fun disableThatDoesNotStickRollsBack() = runTest {
        tv.stubbornPackages += SETUPWRAITH
        val result = swapper().swap(tv.adb, serial)
        assertThat(result).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tv.disabled).isEmpty()
        assertThat(tvCommands()).containsAtLeast("pm enable $STOCK", "pm uninstall $FLAUNCHER_PKG").inOrder()
        assertThat(changes()).isEmpty()
    }

    @Test fun monkeyNotEnoughFallsBackToAmStartOnce() = runTest {
        tv.monkeyWorks = false
        assertThat(swapper().swap(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        assertThat(tvCommands().count { it == "am start -n $FLAUNCHER_PKG/.MainActivity" }).isEqualTo(1)
        assertThat(tvCommands().indexOf("am start -n $FLAUNCHER_PKG/.MainActivity"))
            .isGreaterThan(tvCommands().indexOf(monkey))
    }

    @Test fun flauncherThatNeverOpensRollsBackWithoutDisablingAnything() = runTest {
        tv.monkeyWorks = false
        tv.amStartWorks = false
        val result = swapper().swap(tv.adb, serial)
        assertThat(result).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tvCommands().none { it.startsWith("pm disable-user") }).isTrue()
        assertThat(tvCommands().last { it == home || it.startsWith("am start") }).isEqualTo(home)
        assertThat(tv.foreground).isEqualTo(STOCK)
        assertThat(testScheduler.currentTime).isAtLeast(30_000L)
    }

    @Test fun fireTvIsSkippedWithoutAnyOtherCommand() = runTest {
        tv.manufacturer = "Amazon"
        val result = swapper().swap(tv.adb, serial)
        assertThat(result).isEqualTo(SwapResult.RolledBack("Fire TV not supported yet"))
        assertThat(tv.adb.log).containsExactly("getprop ro.product.manufacturer")
        assertThat(downloader.calls).isEmpty()
    }

    @Test fun alreadySwappedIsAlreadyDone() = runTest {
        tv.installed += FLAUNCHER_PKG
        tv.disabled += STOCK
        val result = swapper().swap(tv.adb, serial)
        assertThat(result).isEqualTo(SwapResult.AlreadyDone)
        assertThat(tv.adb.log).containsExactly("getprop ro.product.manufacturer", RESOLVE_HOME).inOrder()
        assertThat(downloader.calls).isEmpty()
    }

    @Test fun installedButNotHomeSkipsDownloadAndInstall() = runTest {
        tv.installed += FLAUNCHER_PKG
        assertThat(swapper().swap(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        assertThat(downloader.calls).isEmpty()
        assertThat(tv.adb.log.none { it.startsWith("install:") }).isTrue()
        assertThat(changes().map { it.kind }).doesNotContain(ChangeKind.INSTALL_APK)
    }

    @Test fun installedButSwitchedOffIsSwitchedOnAndLogged() = runTest {
        tv.installed += FLAUNCHER_PKG
        tv.disabled += FLAUNCHER_PKG
        assertThat(swapper().swap(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        assertThat(tvCommands()).contains("pm enable $FLAUNCHER_PKG")
        val enable = changes().first()
        assertThat(enable.kind).isEqualTo(ChangeKind.ENABLE)
        assertThat(enable.pkg).isEqualTo(FLAUNCHER_PKG)
        assertThat(enable.verified).isTrue()
    }

    @Test fun checksumMismatchLeavesTvUntouched() = runTest {
        downloader.failure = ChecksumMismatch(FLAUNCHER_SHA256, "0".repeat(64))
        val result = swapper().swap(tv.adb, serial)
        assertThat(result).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tvCommands()).containsExactly(
            "getprop ro.product.manufacturer", RESOLVE_HOME, "pm list packages $FLAUNCHER_PKG",
        ).inOrder()
    }

    @Test fun downloadFailureLeavesTvUntouched() = runTest {
        downloader.failure = IOException("no network")
        assertThat(swapper().swap(tv.adb, serial)).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tv.adb.log.none { it.startsWith("install:") || it.startsWith("monkey") }).isTrue()
    }

    @Test fun failedInstallStopsBeforeLaunching() = runTest {
        tv.installWorks = false
        tv.adb.installResult = ShellResult("INSTALL_FAILED_INSUFFICIENT_STORAGE", 1)
        assertThat(swapper().swap(tv.adb, serial)).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tvCommands().none { it.startsWith("monkey") || it.startsWith("pm disable-user") }).isTrue()
        assertThat(changes()).isEmpty()
    }

    @Test fun connectionErrorMidSwapStillRollsBackThenRethrows() = runTest {
        tv.adb.failNext(home, AdbError.Disconnected())
        thrown<AdbError.Disconnected> { swapper().swap(tv.adb, serial) }
        assertThat(tv.disabled).isEmpty()
        assertThat(tvCommands()).containsAtLeast("pm enable $SETUPWRAITH", "pm enable $STOCK", "pm uninstall $FLAUNCHER_PKG").inOrder()
        assertThat(tv.installed).doesNotContain(FLAUNCHER_PKG)
        assertThat(changes()).isEmpty()
    }

    // --- verifyAfterReboot --------------------------------------------------------------------------------

    @Test fun verifyAfterRebootKeepsAWorkingSwap() = runTest {
        assertThat(swapper().swap(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        tv.foreground = null // just booted
        tv.adb.log.clear()
        assertThat(swapper().verifyAfterReboot(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        assertThat(tvCommands().none { it.startsWith("pm ") }).isTrue()
        assertThat(tv.disabled).containsExactly(STOCK, SETUPWRAITH)
    }

    @Test fun verifyAfterRebootRollsBackWhenHomeIsNotFlauncher() = runTest {
        assertThat(swapper().swap(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        // After the restart HOME lands on the setup wizard while the stock launcher is off.
        tv.onHome = { if (STOCK in tv.disabled) SETUPWRAITH else tv.resolvedHome() }
        tv.foreground = SETUPWRAITH
        tv.adb.log.clear()

        val result = swapper().verifyAfterReboot(tv.adb, serial)

        assertThat(result).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tv.disabled).isEmpty()
        assertThat(tvCommands())
            .containsAtLeast("pm enable $SETUPWRAITH", "pm enable $STOCK", "pm uninstall $FLAUNCHER_PKG", home).inOrder()
        assertThat(tv.installed).doesNotContain(FLAUNCHER_PKG)
        assertThat(tv.foreground).isEqualTo(STOCK)
        assertThat(changes()).isEmpty()
    }

    @Test fun verifyAfterRebootKeepsAPreinstalledFlauncher() = runTest {
        tv.installed += FLAUNCHER_PKG
        assertThat(swapper().swap(tv.adb, serial)).isEqualTo(SwapResult.Swapped)
        tv.onHome = { if (STOCK in tv.disabled) SETUPWRAITH else tv.resolvedHome() }
        tv.adb.log.clear()

        assertThat(swapper().verifyAfterReboot(tv.adb, serial)).isInstanceOf(SwapResult.RolledBack::class.java)
        assertThat(tvCommands().none { it.startsWith("pm uninstall") }).isTrue()
        assertThat(tv.installed).contains(FLAUNCHER_PKG)
        assertThat(tv.disabled).isEmpty()
        assertThat(changes()).isEmpty()
    }

    // --- rebootAndReconnect -------------------------------------------------------------------------------

    @Test fun rebootWaitsForDisconnectThenForBootCompleted() = runTest {
        val old = FakeAdbClient(mutableMapOf("getprop sys.boot_completed" to "1\n"))
        var probes = 0
        old.onCommand = { if (++probes >= 2) throw AdbError.Disconnected() }
        val booting = FakeAdbClient(mutableMapOf("getprop sys.boot_completed" to "\n"))
        val ready = FakeAdbClient(mutableMapOf("getprop sys.boot_completed" to "1\n"))
        val script = ArrayDeque<() -> AdbClient>(listOf({ throw AdbError.Refused() }, { booting }, { ready }))

        val client = swapper().rebootAndReconnect(old, { script.removeFirst()() })

        assertThat(client).isSameInstanceAs(ready)
        assertThat(old.log.first()).isEqualTo("reboot")
        assertThat(old.closed).isTrue()
        assertThat(booting.closed).isTrue()
        assertThat(ready.closed).isFalse()
        assertThat(script).isEmpty()
    }

    @Test fun rebootTimesOutWithTypedError() = runTest {
        val old = FakeAdbClient()
        old.onCommand = { throw AdbError.Disconnected() }
        val e = thrown<TvRestartTimeoutException> {
            swapper().rebootAndReconnect(old, { throw AdbError.Refused() }, timeoutMs = 180_000)
        }
        assertThat(e.cause).isInstanceOf(AdbError.Refused::class.java)
        assertThat(testScheduler.currentTime).isAtLeast(180_000L)
        assertThat(testScheduler.currentTime).isLessThan(200_000L)
    }

    @Test fun tvThatNeverGoesDownTimesOut() = runTest {
        val old = FakeAdbClient(mutableMapOf("getprop sys.boot_completed" to "1\n"))
        var reconnects = 0
        thrown<TvRestartTimeoutException> {
            swapper().rebootAndReconnect(old, { reconnects++; old }, timeoutMs = 60_000)
        }
        assertThat(reconnects).isEqualTo(0)
    }

    @Test fun hungProbeAfterRebootCountsAsDisconnected() = runTest {
        val hung = object : AdbClient {
            var closed = false
            override val host = "hung"
            override suspend fun shell(cmd: String): ShellResult = awaitCancellation()
            override suspend fun install(apk: File) = error("unused")
            override suspend fun push(local: File, remote: String) = error("unused")
            override suspend fun pull(remote: String, local: File) = error("unused")
            override suspend fun reboot() {}
            override fun close() { closed = true }
        }
        val ready = FakeAdbClient(mutableMapOf("getprop sys.boot_completed" to "1\n"))
        assertThat(swapper().rebootAndReconnect(hung, { ready })).isSameInstanceAs(ready)
        assertThat(hung.closed).isTrue()
    }
}
