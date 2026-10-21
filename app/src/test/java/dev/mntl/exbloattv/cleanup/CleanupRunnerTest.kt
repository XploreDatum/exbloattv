package dev.mntl.exbloattv.cleanup

import com.google.common.truth.Truth.assertThat
import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.adb.ShellResult
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.RESOLVE_HOME
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.SETUPWRAITH
import dev.mntl.exbloattv.cleanup.FakeTv.Companion.STOCK
import dev.mntl.exbloattv.cleanup.StepStatus.DONE
import dev.mntl.exbloattv.cleanup.StepStatus.FAILED
import dev.mntl.exbloattv.cleanup.StepStatus.PENDING
import dev.mntl.exbloattv.cleanup.StepStatus.SKIPPED
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.state.Lock
import dev.mntl.exbloattv.state.TvState
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.tv.AnimationScales
import dev.mntl.exbloattv.tv.InstalledPackage
import dev.mntl.exbloattv.tv.Inventory
import dev.mntl.exbloattv.tv.TvInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class) // virtual time: testScheduler.currentTime / timeSource
class CleanupRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val serial = "SER1"
    private val phone = "Pixel 8"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tv = FakeTv()

    // --- what FakeTv doesn't model --------------------------------------------------------------------------

    private val userApps = mutableSetOf(USERAPP)
    private val removedForUser = mutableSetOf(MUSIC)
    /** Packages whose `pm uninstall -k --user 0` prints Success but changes nothing. */
    private val stubbornRemovals = mutableSetOf<String>()
    private val settings = mutableMapOf<String, String>()
    /** "<pkg> <op>" -> mode. */
    private val appops = mutableMapOf<String, String>()
    /** Runs after the TV acted on a command; throwing here simulates the reply being lost. */
    private var afterCommand: (String) -> Unit = {}

    // --- the connection -------------------------------------------------------------------------------------

    private var connects = 0
    private val connectTimes = mutableListOf<Long>()
    /** The TV can't be reached at all. */
    private var tvOff = false
    /** The TV is restarting; the next connect brings it back (and runs [onBoot]) unless [neverBoots]. */
    private var tvDown = false
    private var neverBoots = false
    private var onBoot: () -> Unit = {}
    /** The next [hangInstalls] installs never return; a reboot never answers when [hangReboot]. */
    private var hangInstalls = 0
    private var hangReboot = false

    init {
        tv.installed += listOf(ADS, OTHER, THIRD, GAMES, USERAPP)
        tv.disabled += GAMES
        val base = tv.adb.onCommand
        tv.adb.onCommand = { cmd ->
            if (!extra(cmd)) base(cmd)
            afterCommand(cmd)
        }
    }

    private fun extra(cmd: String): Boolean {
        val out = tv.adb.responses
        fun ok(text: String): Boolean {
            out[cmd] = text
            tv.adb.exitCodes[cmd] = 0
            return true
        }
        fun lines(pkgs: Iterable<String>) = pkgs.joinToString("") { "package:$it\n" }
        Regex("""^pm list packages -3 (\S+)$""").matchEntire(cmd)?.let { m ->
            return ok(lines(tv.installed.filter { it in userApps && it.contains(m.groupValues[1]) }))
        }
        if (cmd == "pm list packages -d") return ok(lines(tv.installed.filter { it in tv.disabled }))
        if (cmd == "pm list packages") return ok(lines(tv.installed))
        Regex("""^pm uninstall -k --user 0 (\S+)$""").matchEntire(cmd)?.let { m ->
            val p = m.groupValues[1]
            if (p !in stubbornRemovals && tv.installed.remove(p)) {
                removedForUser += p
                tv.disabled -= p
            }
            return ok("Success\n")
        }
        Regex("""^cmd package install-existing (\S+)$""").matchEntire(cmd)?.let { m ->
            val p = m.groupValues[1]
            if (!removedForUser.remove(p)) {
                out[cmd] = "Package $p doesn't exist\n"
                tv.adb.exitCodes[cmd] = 1
                return true
            }
            tv.installed += p
            return ok("Package $p installed for user: 0\n")
        }
        Regex("""^settings get global (\S+)$""").matchEntire(cmd)?.let { m ->
            return ok((settings[m.groupValues[1]] ?: "null") + "\n")
        }
        Regex("""^settings put global (\S+) (\S+)$""").matchEntire(cmd)?.let { m ->
            settings[m.groupValues[1]] = m.groupValues[2]
            return ok("")
        }
        Regex("""^cmd appops get (\S+) (\S+)$""").matchEntire(cmd)?.let { m ->
            val op = m.groupValues[2]
            return ok(appops["${m.groupValues[1]} $op"]?.let { "$op: $it; time=+2m ago\n" } ?: "No operations.\n")
        }
        Regex("""^cmd appops set (\S+) (\S+) (\S+)$""").matchEntire(cmd)?.let { m ->
            appops["${m.groupValues[1]} ${m.groupValues[2]}"] = m.groupValues[3]
            return ok("")
        }
        if (cmd == "pm trim-caches 999G") return ok("")
        if (cmd == "getprop sys.boot_completed") return ok("1\n")
        return false
    }

    /** One connection to the shared [tv]; dead once closed or while the TV restarts. */
    private inner class Conn : AdbClient {
        override val host = "fake-tv"
        private var closed = false
        override suspend fun shell(cmd: String): ShellResult {
            if (closed || tvDown) throw AdbError.Disconnected()
            return tv.adb.shell(cmd)
        }
        override suspend fun install(apk: File): ShellResult {
            if (hangInstalls > 0) {
                hangInstalls--
                awaitCancellation() // the TV left the Wi-Fi mid-transfer without closing TCP
            }
            return tv.adb.install(apk)
        }
        override suspend fun push(local: File, remote: String) = tv.adb.push(local, remote)
        override suspend fun pull(remote: String, local: File) = tv.adb.pull(remote, local)
        override suspend fun reboot() {
            tv.adb.reboot()
            tvDown = true
            if (hangReboot) awaitCancellation()
        }
        override fun close() {
            closed = true
        }
    }

    private inner class StubDownloader : ApkDownloader(OkHttpClient(), File("unused")) {
        var failure: Throwable? = null
        override suspend fun fetch(url: String, sha256: String): File {
            failure?.let { throw it }
            return File(tmp.root, "$sha256.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        }
    }

    private val downloader = StubDownloader()

    private fun TestScope.runner() = CleanupRunner(
        connect = {
            connects++
            connectTimes += testScheduler.currentTime
            if (tvOff) throw AdbError.Refused()
            if (tvDown) {
                if (neverBoots) throw AdbError.Refused()
                tvDown = false
                onBoot()
            }
            Conn()
        },
        storeFor = { TvStateStore(it) { testScheduler.currentTime } },
        swapFor = { LauncherSwap(downloader, it, phone, testScheduler.timeSource) { testScheduler.currentTime } },
        phone = phone,
        serial = serial,
        appNames = mapOf(ADS to "Ads Hub", OTHER to "Weather"),
        now = { testScheduler.currentTime },
    )

    /** What the TV looks like now, as the Reading screen would have seen it. */
    private var sdkInt = 28

    private fun before() = Inventory(
        info = TvInfo(serial, "MIBOX4", "Xiaomi", "Xiaomi", "9", sdkInt),
        packages = tv.installed.map {
            InstalledPackage(it, "/data/app/$it/base.apk", null, 10_000, it !in userApps, it !in tv.disabled)
        },
        launchable = setOf("com.netflix.ninja"),
        homePackages = listOf(STOCK),
        resolvedHome = "$STOCK/.MainActivity",
        memory = null,
        storage = null,
        animations = AnimationScales(1f, 1f, 1f),
        backgroundOps = mapOf(NETFLIX to "allow"),
    )

    private fun state(): TvState = json.decodeFromString(TvState.serializer(), tv.fs.getValue(TvStateStore.FILE))

    private fun writeState(s: TvState) {
        tv.fs[TvStateStore.FILE] = json.encodeToString(TvState.serializer(), s)
    }

    private fun tvCommands() = tv.adb.log.filterNot { FakeTv.isStateFileCommand(it) }

    private class Collected(val job: Job, val seen: MutableList<RunProgress>) {
        val last get() = seen.last()
        val statuses get() = last.steps.map { it.status }
    }

    /** Collects in a separate job, for runs that pause (failed steps wait for retry/skip); cancel it if it does. */
    private fun TestScope.start(runner: CleanupRunner, plan: List<Step>): Collected {
        val seen = mutableListOf<RunProgress>()
        val job = launch { runner.run(plan, before()).collect { seen += it } }
        return Collected(job, seen)
    }

    private fun disableCmd(pkg: String) = "pm disable-user --user 0 $pkg"

    // --- happy path -----------------------------------------------------------------------------------------

    @Test fun happyPathRunsEveryStepVerifiesItAndLogsChangesInOrder() = runTest {
        appops["$NETFLIX RUN_ANY_IN_BACKGROUND"] = "allow"
        val plan = listOf(
            Step.Disable(ADS), Step.Enable(GAMES), Step.InstallExisting(MUSIC), Step.Uninstall(USERAPP),
            Step.SetAnimations(0.5f), Step.RestrictBackground(NETFLIX), Step.TrimCaches,
        )

        val progress = runner().run(plan, before()).toList()

        val first = progress.first()
        assertThat(first.steps.map { it.step }).isEqualTo(listOf(Step.SaveRestorePoint) + plan)
        assertThat(first.steps.map { it.status }.toSet()).containsExactly(PENDING)
        assertThat(first.fraction).isEqualTo(0f)
        val last = progress.last()
        assertThat(last.steps.map { it.status }).isEqualTo(List(8) { DONE })
        assertThat(last.fraction).isEqualTo(1f)
        assertThat(last.waitingForTv).isFalse()

        val changes = state().changes
        assertThat(changes.map { listOf(it.kind, it.pkg, it.from, it.to) }).containsExactly(
            listOf(ChangeKind.DISABLE, ADS, "enabled", "disabled"),
            listOf(ChangeKind.ENABLE, GAMES, "disabled", "enabled"),
            listOf(ChangeKind.INSTALL_EXISTING, MUSIC, "removed", "installed"),
            listOf(ChangeKind.UNINSTALL, USERAPP, "installed", "deleted"),
            listOf(ChangeKind.ANIMATIONS, null, "null,null,null", "0.5,0.5,0.5"),
            listOf(ChangeKind.APPOPS, NETFLIX, "RUN_IN_BACKGROUND=default", "RUN_IN_BACKGROUND=ignore"),
            listOf(ChangeKind.APPOPS, NETFLIX, "RUN_ANY_IN_BACKGROUND=allow", "RUN_ANY_IN_BACKGROUND=ignore"),
        ).inOrder()
        assertThat(changes.all { it.verified && it.byPhone == phone }).isTrue()
        assertThat(changes.map { it.id }.toSet()).hasSize(7)
        assertThat(state().lock).isNull()

        assertThat(tv.disabled).contains(ADS)
        assertThat(tv.disabled).doesNotContain(GAMES)
        assertThat(tv.installed).contains(MUSIC)
        assertThat(tv.installed).doesNotContain(USERAPP)
        assertThat(settings.values.toList()).isEqualTo(listOf("0.5", "0.5", "0.5"))
        assertThat(appops).containsExactly("$NETFLIX RUN_IN_BACKGROUND", "ignore", "$NETFLIX RUN_ANY_IN_BACKGROUND", "ignore")

        assertThat(tvCommands()).containsExactly(
            RESOLVE_HOME, "pm list packages -d $ADS", "pm list packages $ADS", disableCmd(ADS), "pm list packages -d $ADS",
            "pm list packages -e $GAMES", "pm list packages -d $GAMES", "pm enable $GAMES", "pm list packages -e $GAMES",
            "pm list packages $MUSIC", "cmd package install-existing $MUSIC", "pm list packages $MUSIC",
            RESOLVE_HOME, "pm list packages $USERAPP", "pm list packages -3 $USERAPP", "pm uninstall $USERAPP",
            "pm list packages $USERAPP",
            "settings get global window_animation_scale", "settings get global transition_animation_scale",
            "settings get global animator_duration_scale",
            "settings put global window_animation_scale 0.5", "settings put global transition_animation_scale 0.5",
            "settings put global animator_duration_scale 0.5",
            "settings get global window_animation_scale", "settings get global transition_animation_scale",
            "settings get global animator_duration_scale",
            "cmd appops get $NETFLIX RUN_IN_BACKGROUND", "cmd appops set $NETFLIX RUN_IN_BACKGROUND ignore",
            "cmd appops get $NETFLIX RUN_IN_BACKGROUND",
            "cmd appops get $NETFLIX RUN_ANY_IN_BACKGROUND", "cmd appops set $NETFLIX RUN_ANY_IN_BACKGROUND ignore",
            "cmd appops get $NETFLIX RUN_ANY_IN_BACKGROUND",
            "pm trim-caches 999G",
        ).inOrder()
    }

    @Test fun progressEmitsRunningThenDoneForEveryStep() = runTest {
        val progress = runner().run(listOf(Step.TrimCaches), before()).toList()

        assertThat(progress.map { p -> p.steps.map { it.status } }).containsExactly(
            listOf(PENDING, PENDING),
            listOf(StepStatus.RUNNING, PENDING),
            listOf(DONE, PENDING),
            listOf(DONE, StepStatus.RUNNING),
            listOf(DONE, DONE),
        ).inOrder()
        assertThat(progress.map { it.fraction }).containsExactly(0f, 0f, 0.5f, 0.5f, 1f).inOrder()
    }

    @Test fun backgroundSettingThatWasNeverSetIsRecordedAsDefault() = runTest {
        runner().run(listOf(Step.RestrictBackground(NETFLIX)), before()).toList()

        assertThat(state().changes.map { it.from })
            .containsExactly("RUN_IN_BACKGROUND=default", "RUN_ANY_IN_BACKGROUND=default").inOrder()
        assertThat(state().changes.all { it.verified }).isTrue()
    }

    @Test fun beforeAndroid9OnlyRunInBackgroundIsSet() = runTest {
        sdkInt = 26

        val last = runner().run(listOf(Step.RestrictBackground(NETFLIX)), before()).toList().last()

        assertThat(last.steps[1].status).isEqualTo(DONE)
        assertThat(state().changes.map { it.from to it.to })
            .containsExactly("RUN_IN_BACKGROUND=default" to "RUN_IN_BACKGROUND=ignore")
        assertThat(tvCommands().none { "RUN_ANY_IN_BACKGROUND" in it }).isTrue()
    }

    @Test fun backgroundOpAlreadyIgnoredIsLeftAloneAndTheOtherIsSet() = runTest {
        appops["$NETFLIX RUN_IN_BACKGROUND"] = "ignore"

        runner().run(listOf(Step.RestrictBackground(NETFLIX)), before()).toList()

        assertThat(state().changes.map { it.from }).containsExactly("RUN_ANY_IN_BACKGROUND=default")
        assertThat(tvCommands()).doesNotContain("cmd appops set $NETFLIX RUN_IN_BACKGROUND ignore")
    }

    @Test fun animationsLogTheRawValuesKeepingNullForAScaleNeverSet() = runTest {
        settings["window_animation_scale"] = "1.0"
        settings["animator_duration_scale"] = "1.0"

        runner().run(listOf(Step.SetAnimations(0.5f)), before()).toList()

        val change = state().changes.single()
        assertThat(change.from).isEqualTo("1.0,null,1.0")
        assertThat(change.to).isEqualTo("0.5,0.5,0.5")
        assertThat(change.verified).isTrue()
    }

    // --- restore point --------------------------------------------------------------------------------------

    @Test fun firstRunSetsOriginalAndLatestSecondRunOnlyUpdatesLatest() = runTest {
        runner().run(listOf(Step.TrimCaches), before()).toList()

        val first = state()
        val rp = first.original!!
        assertThat(first.latest).isEqualTo(rp)
        assertThat(rp.byPhone).isEqualTo(phone)
        assertThat(rp.enabled).containsAtLeast(ADS, OTHER, USERAPP, STOCK)
        assertThat(rp.disabled).containsExactly(GAMES)
        assertThat(rp.userInstalled).containsExactly(USERAPP)
        assertThat(rp.resolvedHome).isEqualTo("$STOCK/.MainActivity")
        assertThat(rp.animations).isEqualTo(AnimationScales(1f, 1f, 1f))
        assertThat(rp.backgroundOps).containsExactly(NETFLIX, "allow")

        advanceTimeBy(60_000)
        tv.disabled += ADS
        runner().run(listOf(Step.TrimCaches), before()).toList()

        val second = state()
        assertThat(second.original).isEqualTo(rp)
        assertThat(second.latest!!.takenAt).isEqualTo(60_000)
        assertThat(second.latest!!.disabled).containsExactly(GAMES, ADS)
    }

    @Test fun restorePointAndUnverifiedEntryAreOnTheTvBeforeTheCommandRuns() = runTest {
        var atCommand: TvState? = null
        afterCommand = { if (it == disableCmd(ADS)) atCommand = state() }

        runner().run(listOf(Step.Disable(ADS)), before()).toList()

        val s = atCommand!!
        assertThat(s.original).isNotNull()
        assertThat(s.latest).isNotNull()
        assertThat(s.lock?.byPhone).isEqualTo(phone)
        assertThat(s.changes.map { it.kind to it.verified }).containsExactly(ChangeKind.DISABLE to false)
        assertThat(state().changes.single().verified).isTrue()
    }

    // --- disable --------------------------------------------------------------------------------------------

    @Test fun disableThatDoesNotTakeFallsBackToRemoveForUser() = runTest {
        tv.stubbornPackages += ADS

        val last = runner().run(listOf(Step.Disable(ADS)), before()).toList().last()

        assertThat(last.steps[1].status).isEqualTo(DONE)
        val change = state().changes.single()
        assertThat(listOf(change.kind, change.pkg, change.from, change.to, change.verified))
            .isEqualTo(listOf(ChangeKind.REMOVE_FOR_USER, ADS, "enabled", "removed", true))
        assertThat(tv.installed).doesNotContain(ADS)
        assertThat(tvCommands()).containsExactly(
            RESOLVE_HOME, "pm list packages -d $ADS", "pm list packages $ADS",
            disableCmd(ADS), "pm list packages -d $ADS", "pm list packages $ADS",
            "pm uninstall -k --user 0 $ADS", "pm list packages -d $ADS", "pm list packages $ADS",
        ).inOrder()
    }

    @Test fun disableAndFallbackBothFailingFailsTheStepLogsNothingAndTheRunContinues() = runTest {
        tv.stubbornPackages += ADS
        stubbornRemovals += ADS
        val runner = runner()

        val c = start(runner, listOf(Step.Disable(ADS), Step.Disable(OTHER)))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED, DONE))
        assertThat(c.last.steps[1].message).isEqualTo("Couldn't switch this app off")
        assertThat(state().changes.map { it.pkg to it.verified }).containsExactly(OTHER to true)
        assertThat(c.job.isActive).isTrue() // waits for Retry or Skip on the failed step

        runner.skip(1)
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, SKIPPED, DONE))
        assertThat(c.job.isCompleted).isTrue()
        assertThat(state().lock).isNull()
    }

    @Test fun alreadyDisabledIsDoneWithoutACommandOrAChange() = runTest {
        tv.disabled += ADS

        val last = runner().run(listOf(Step.Disable(ADS)), before()).toList().last()

        assertThat(last.steps[1].status).isEqualTo(DONE)
        assertThat(tvCommands()).doesNotContain(disableCmd(ADS))
        assertThat(state().changes).isEmpty()
    }

    @Test fun protectedPackagesAndTheActiveHomeScreenAreNeverSwitchedOff() = runTest {
        val c = start(runner(), listOf(Step.Disable("com.android.systemui"), Step.Disable(STOCK), Step.Uninstall(STOCK)))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED, FAILED, FAILED))
        assertThat(c.last.steps[1].message).isEqualTo("This app keeps the TV working, so it was left on")
        assertThat(tvCommands().filter { it.startsWith("pm disable-user") || it.startsWith("pm uninstall") }).isEmpty()
        assertThat(state().changes).isEmpty()
        c.job.cancel()
    }

    @Test fun homeScreenThatDoesNotResolveProtectsEveryHomeCandidate() = runTest {
        tv.homePriority[FLAUNCHER_PKG] = 1 // tie with the stock launcher: HOME shows the chooser ("android")
        tv.installed += FLAUNCHER_PKG

        val c = start(runner(), listOf(Step.Disable(STOCK), Step.Disable(FLAUNCHER_PKG), Step.Disable(ADS)))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED, FAILED, DONE))
        assertThat(c.last.steps[1].message).isEqualTo("This app keeps the TV working, so it was left on")
        assertThat(tvCommands().filter { it.startsWith("pm disable-user") }).containsExactly(disableCmd(ADS))
        c.job.cancel()
    }

    @Test fun enableThatDoesNotTakeFailsAndLogsNothing() = runTest {
        tv.failingEnables += GAMES

        val c = start(runner(), listOf(Step.Enable(GAMES)))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED))
        assertThat(c.last.steps[1].message).isEqualTo("Couldn't switch this app on")
        assertThat(state().changes).isEmpty()
        c.job.cancel()
    }

    @Test fun deletingABuiltInAppIsRefused() = runTest {
        val c = start(runner(), listOf(Step.Uninstall(ADS)))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED))
        assertThat(tvCommands()).doesNotContain("pm uninstall $ADS")
        assertThat(state().changes).isEmpty()
        c.job.cancel()
    }

    // --- lost connection ------------------------------------------------------------------------------------

    @Test fun droppedConnectionReconnectsAndRetriesOnlyTheSameStep() = runTest {
        tv.adb.failNext(disableCmd(THIRD), AdbError.Disconnected())

        val last = runner().run(listOf(Step.Disable(ADS), Step.Disable(OTHER), Step.Disable(THIRD)), before())
            .toList().last()

        assertThat(last.steps.map { it.status }).isEqualTo(List(4) { DONE })
        assertThat(connects).isEqualTo(2)
        assertThat(connectTimes).containsExactly(0L, 2_000L).inOrder()
        val log = tvCommands()
        assertThat(log.count { it == disableCmd(ADS) }).isEqualTo(1)
        assertThat(log.count { it == disableCmd(OTHER) }).isEqualTo(1)
        assertThat(log.count { it == disableCmd(THIRD) }).isEqualTo(2)
        assertThat(state().changes.map { it.pkg to it.verified })
            .containsExactly(ADS to true, OTHER to true, THIRD to true).inOrder()
    }

    @Test fun lostReplyAfterTheTvActedIsVerifiedOnReconnectWithoutRepeatingTheCommand() = runTest {
        var drop = true
        afterCommand = { if (it == disableCmd(THIRD) && drop) { drop = false; throw AdbError.Disconnected() } }

        val last = runner().run(listOf(Step.Disable(ADS), Step.Disable(OTHER), Step.Disable(THIRD)), before())
            .toList().last()

        assertThat(last.steps.map { it.status }).isEqualTo(List(4) { DONE })
        assertThat(tvCommands().count { it == disableCmd(THIRD) }).isEqualTo(1)
        assertThat(state().changes.map { listOf(it.kind, it.pkg, it.verified) }.last())
            .isEqualTo(listOf(ChangeKind.DISABLE, THIRD, true))
        assertThat(state().changes).hasSize(3)
    }

    @Test fun threeFailedReconnectsPauseUntilRetryWithoutBusyLooping() = runTest {
        var drop = true
        afterCommand = {
            if (it == disableCmd(THIRD) && drop) {
                drop = false
                tvOff = true
                throw AdbError.Disconnected()
            }
        }
        val runner = runner()

        val c = start(runner, listOf(Step.Disable(ADS), Step.Disable(OTHER), Step.Disable(THIRD)))
        advanceUntilIdle()

        assertThat(c.last.waitingForTv).isTrue()
        assertThat(c.statuses).isEqualTo(listOf(DONE, DONE, DONE, FAILED))
        assertThat(c.last.steps[3].message).startsWith("Lost connection to the TV")
        assertThat(connectTimes).containsExactly(0L, 2_000L, 7_000L, 17_000L).inOrder()
        // The TV acted but nobody saw it: the entry stays, unverified ("maybe happened").
        assertThat(state().changes.map { it.pkg to it.verified }.last()).isEqualTo(THIRD to false)

        val seen = c.seen.size
        advanceTimeBy(3_600_000)
        runCurrent()
        assertThat(connects).isEqualTo(4)
        assertThat(c.seen.size).isEqualTo(seen)

        tvOff = false
        runner.retry(3)
        advanceUntilIdle()

        assertThat(c.last.waitingForTv).isFalse()
        assertThat(c.statuses).isEqualTo(List(4) { DONE })
        assertThat(connects).isEqualTo(5)
        assertThat(tvCommands().count { it == disableCmd(THIRD) }).isEqualTo(1)
        assertThat(state().changes.map { it.pkg to it.verified }.last()).isEqualTo(THIRD to true)
        assertThat(c.job.isCompleted).isTrue()
        assertThat(state().lock).isNull()
    }

    @Test fun fourthConsecutiveDropPausesEvenWhenReconnectingWorks() = runTest {
        // The TV stays reachable; the step itself keeps losing the connection.
        afterCommand = { if (it == "pm list packages -d $THIRD") throw AdbError.Disconnected() }

        val c = start(runner(), listOf(Step.Disable(ADS), Step.Disable(THIRD)))
        advanceUntilIdle()

        assertThat(c.last.waitingForTv).isTrue()
        assertThat(c.statuses).isEqualTo(listOf(DONE, DONE, FAILED))
        assertThat(connectTimes).containsExactly(0L, 2_000L, 7_000L, 17_000L).inOrder()
        c.job.cancel()
    }

    @Test fun unreachableTvAtStartWaitsWithoutChangingAnything() = runTest {
        tvOff = true

        val c = start(runner(), listOf(Step.Disable(ADS)))
        advanceUntilIdle()

        assertThat(c.last.waitingForTv).isTrue()
        assertThat(c.statuses).isEqualTo(listOf(FAILED, PENDING))
        assertThat(connects).isEqualTo(4)
        assertThat(tv.fs).isEmpty()
        c.job.cancel()
    }

    @Test fun unfinishedSwapIsRecoveredAfterReconnectBeforeTheStepIsRetried() = runTest {
        var drop = true
        afterCommand = { if (it == disableCmd(STOCK) && drop) { drop = false; throw AdbError.Disconnected() } }

        val last = runner().run(listOf(Step.SwapLauncher), before()).toList().last()

        assertThat(last.steps[1].status).isEqualTo(DONE)
        val log = tvCommands()
        val firstDisable = log.indexOf(disableCmd(STOCK))
        val reEnable = log.indexOf("pm enable $STOCK")
        assertThat(reEnable).isGreaterThan(firstDisable)
        assertThat(log.lastIndexOf(disableCmd(STOCK))).isGreaterThan(reEnable)
        assertThat(tv.resolvedHome()).isEqualTo(FLAUNCHER_PKG)
        assertThat(state().changes.all { it.verified }).isTrue()
        assertThat(state().changes.last().kind).isEqualTo(ChangeKind.LAUNCHER_SWAP)
    }

    @Test fun swapReadFailureIsRetriedLikeALostConnectionNeverDone() = runTest {
        var fail = true
        afterCommand = { if (it == MANUFACTURER && fail) { fail = false; tv.adb.exitCodes[it] = 1 } }

        val progress = runner().run(listOf(Step.SwapLauncher), before()).toList()

        assertThat(progress.last().steps[1].status).isEqualTo(DONE)
        assertThat(connectTimes).containsExactly(0L, 2_000L).inOrder()
        assertThat(tvCommands().count { it == MANUFACTURER }).isEqualTo(2)
        assertThat(tv.resolvedHome()).isEqualTo(FLAUNCHER_PKG)
        // Never shown as done while the TV's state was unknown.
        assertThat(progress.dropLast(1).none { it.steps[1].status == DONE }).isTrue()
    }

    @Test fun swapReadFailingEveryTimePausesWaitingForTv() = runTest {
        afterCommand = { if (it == MANUFACTURER) tv.adb.exitCodes[it] = 1 }

        val c = start(runner(), listOf(Step.SwapLauncher, Step.TrimCaches))
        advanceUntilIdle()

        assertThat(c.last.waitingForTv).isTrue()
        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED, PENDING))
        assertThat(c.last.steps[1].message).startsWith("Couldn't check the TV")
        assertThat(connectTimes).containsExactly(0L, 2_000L, 7_000L, 17_000L).inOrder()
        assertThat(tvCommands()).doesNotContain("pm trim-caches 999G")
        c.job.cancel()
    }

    // --- lock -----------------------------------------------------------------------------------------------

    @Test fun lockHeldByAnotherPhoneFailsTheFirstStepAndChangesNothing() = runTest {
        writeState(TvState(serial = serial, lock = Lock("Mum's phone", 0)))
        val runner = runner()

        val c = start(runner, listOf(Step.Disable(ADS)))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(FAILED, PENDING))
        assertThat(c.last.steps[0].message).isEqualTo("Another phone (Mum's phone) is cleaning this TV")
        assertThat(tvCommands()).isEmpty()
        assertThat(state()).isEqualTo(TvState(serial = serial, lock = Lock("Mum's phone", 0)))

        runner.skip(0) // the restore point can't be skipped
        advanceUntilIdle()
        assertThat(c.statuses).isEqualTo(listOf(FAILED, PENDING))

        c.job.cancel()
        advanceUntilIdle()
        assertThat(state().lock).isEqualTo(Lock("Mum's phone", 0))
    }

    @Test fun lockIsRefreshedBeforeEveryStepSoATakeoverStopsTheRun() = runTest {
        afterCommand = { if (it == disableCmd(OTHER)) writeState(state().copy(lock = Lock("Mum's phone", 0))) }

        val c = start(runner(), listOf(Step.Disable(ADS), Step.Disable(OTHER), Step.Disable(THIRD), Step.TrimCaches))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, DONE, DONE, FAILED, PENDING))
        assertThat(c.last.steps[3].message).isEqualTo("Another phone (Mum's phone) is cleaning this TV")
        assertThat(tvCommands()).doesNotContain(disableCmd(THIRD))
        assertThat(tvCommands()).doesNotContain("pm trim-caches 999G")

        c.job.cancel()
        advanceUntilIdle()
        assertThat(state().lock).isEqualTo(Lock("Mum's phone", 0))
    }

    /** Pauses the run on step 3 (TV unreachable) so another phone can act while this one waits. */
    private fun TestScope.pausedOnThird(): Pair<CleanupRunner, Collected> {
        var drop = true
        afterCommand = { if (it == "pm list packages -d $THIRD" && drop) { drop = false; tvOff = true; throw AdbError.Disconnected() } }
        val runner = runner()
        val c = start(runner, listOf(Step.Disable(ADS), Step.Disable(OTHER), Step.Disable(THIRD), Step.TrimCaches))
        advanceUntilIdle()
        assertThat(c.last.waitingForTv).isTrue()
        return runner to c
    }

    private suspend fun TestScope.resumeAfterAnHour(runner: CleanupRunner, c: Collected) {
        advanceTimeBy(3_600_000)
        tvOff = false
        val mark = tv.adb.log.size
        runner.retry(3)
        advanceUntilIdle()
        assertThat(c.statuses).isEqualTo(listOf(DONE, DONE, DONE, FAILED, PENDING))
        assertThat(c.last.steps[3].message).isEqualTo(
            "This TV was changed from another phone while you were away. Check the TV again before carrying on",
        )
        assertThat(tv.adb.log.drop(mark).filterNot { FakeTv.isStateFileCommand(it) }).isEmpty()

        runner.retry(3) // still stale: the plan is out of date for good
        advanceUntilIdle()
        assertThat(c.statuses[3]).isEqualTo(FAILED)
        assertThat(tvCommands()).doesNotContain(disableCmd(THIRD))
        c.job.cancel()
    }

    @Test fun anotherPhonesUndoWhileWaitingStopsTheRun() = runTest {
        val (runner, c) = pausedOnThird()
        // Mum's phone took the expired lock, undid everything, and released it.
        tv.disabled -= setOf(ADS, OTHER)
        writeState(state().copy(changes = emptyList(), lock = null))

        resumeAfterAnHour(runner, c)
        assertThat(state().lock).isNull()
    }

    @Test fun anotherPhonesChangesWhileWaitingStopTheRunEvenIfTheLockLooksOurs() = runTest {
        val (runner, c) = pausedOnThird()
        val s = state()
        val foreign = s.changes.first().copy(id = "run-mum:1", pkg = THIRD, byPhone = "Mum's phone")
        writeState(s.copy(changes = s.changes + foreign))

        resumeAfterAnHour(runner, c)
    }

    @Test fun ourOwnExpiredLockIsTakenBackAndTheRunCarriesOn() = runTest {
        val (runner, c) = pausedOnThird()
        advanceTimeBy(3_600_000)
        tvOff = false

        runner.retry(3)
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(List(5) { DONE })
        assertThat(c.job.isCompleted).isTrue()
    }

    @Test fun cancellingFinishesTheCurrentStepsLoggingThenReleasesTheLock() = runTest {
        lateinit var c: Collected
        afterCommand = { if (it == disableCmd(OTHER)) c.job.cancel() }

        c = start(runner(), listOf(Step.Disable(ADS), Step.Disable(OTHER), Step.Disable(THIRD)))
        advanceUntilIdle()

        assertThat(c.job.isCancelled).isTrue()
        assertThat(state().changes.map { it.pkg to it.verified }).containsExactly(ADS to true, OTHER to true).inOrder()
        assertThat(tvCommands()).doesNotContain(disableCmd(THIRD))
        assertThat(state().lock).isNull()
    }

    // --- retry / skip ---------------------------------------------------------------------------------------

    @Test fun retryReRunsOnlyThatStep() = runTest {
        tv.stubbornPackages += ADS
        stubbornRemovals += ADS
        val runner = runner()
        val c = start(runner, listOf(Step.Disable(ADS), Step.Disable(OTHER)))
        advanceUntilIdle()
        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED, DONE))

        val mark = tv.adb.log.size
        runner.retry(2) // already done: ignored
        advanceUntilIdle()
        assertThat(tv.adb.log.size).isEqualTo(mark)

        tv.stubbornPackages -= ADS
        runner.retry(1)
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, DONE, DONE))
        val since = tv.adb.log.drop(mark).filterNot { FakeTv.isStateFileCommand(it) }
        assertThat(since).contains(disableCmd(ADS))
        assertThat(since.none { OTHER in it }).isTrue()
        assertThat(state().changes.map { it.pkg to it.verified }).containsExactly(OTHER to true, ADS to true).inOrder()
        assertThat(c.job.isCompleted).isTrue()
    }

    // --- home screen and restart ----------------------------------------------------------------------------

    @Test fun swapLauncherSwappedIsDone() = runTest {
        val last = runner().run(listOf(Step.SwapLauncher), before()).toList().last()

        assertThat(last.steps[1].status).isEqualTo(DONE)
        assertThat(tv.resolvedHome()).isEqualTo(FLAUNCHER_PKG)
        assertThat(state().changes.last().kind).isEqualTo(ChangeKind.LAUNCHER_SWAP)
    }

    @Test fun swapLauncherRolledBackFailsWithItsReason() = runTest {
        downloader.failure = IOException("no internet")

        val c = start(runner(), listOf(Step.SwapLauncher, Step.TrimCaches))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED, DONE))
        assertThat(c.last.steps[1].message)
            .isEqualTo("Couldn't download the new home screen. Check the phone's internet connection")
        c.job.cancel()
    }

    @Test fun installThatHangsIsBoundedThenRetriedOnAFreshConnection() = runTest {
        hangInstalls = 1

        val last = runner().run(listOf(Step.SwapLauncher), before()).toList().last()

        assertThat(last.steps[1].status).isEqualTo(DONE)
        assertThat(testScheduler.currentTime).isAtLeast(5 * 60_000L)
        assertThat(connects).isEqualTo(2)
        assertThat(tv.resolvedHome()).isEqualTo(FLAUNCHER_PKG)
    }

    @Test fun rebootThatNeverAnswersCountsAsSent() = runTest {
        hangReboot = true

        val last = runner().run(listOf(Step.Disable(ADS), Step.RebootAndVerify), before()).toList().last()

        assertThat(last.steps.map { it.status }).isEqualTo(List(3) { DONE })
        assertThat(tvCommands().count { it == "reboot" }).isEqualTo(1)
        assertThat(state().lock).isNull()
    }

    @Test fun swapUndoneByALaterRecoveryIsShownFailedRightAway() = runTest {
        var drop = true
        afterCommand = {
            if (it == "pm trim-caches 999G" && drop) {
                drop = false
                tv.disabled -= STOCK // the stock home screen came back on behind FLauncher's back
                throw AdbError.Disconnected()
            }
        }

        val c = start(runner(), listOf(Step.SwapLauncher, Step.TrimCaches))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, FAILED, DONE))
        // Emitted while the trim step was still running, not only when it finished.
        assertThat(c.seen.any { it.steps[1].status == FAILED && it.steps[2].status == StepStatus.RUNNING }).isTrue()
        c.job.cancel()
    }

    @Test fun restartReconnectsAndConfirmsEverythingStayedOff() = runTest {
        val last = runner().run(listOf(Step.Disable(ADS), Step.SwapLauncher, Step.RebootAndVerify), before())
            .toList().last()

        assertThat(last.steps.map { it.status }).isEqualTo(List(4) { DONE })
        assertThat(tvCommands()).contains("reboot")
        assertThat(connects).isEqualTo(2)
        assertThat(tvCommands().last()).isEqualTo("pm list packages -d")
        assertThat(tv.disabled).containsAtLeast(ADS, STOCK, SETUPWRAITH)
        assertThat(state().lock).isNull()
    }

    @Test fun appThatSwitchedItselfBackOnAfterRestartFailsTheCheckByName() = runTest {
        onBoot = { tv.disabled -= ADS }

        val c = start(runner(), listOf(Step.Disable(ADS), Step.Disable(OTHER), Step.RebootAndVerify))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, DONE, DONE, FAILED))
        assertThat(c.last.steps[3].message).isEqualTo("These apps switched themselves back on: Ads Hub")
        c.job.cancel()
    }

    @Test fun tvThatNeverComesBackFailsAndWaits() = runTest {
        neverBoots = true

        val c = start(runner(), listOf(Step.Disable(ADS), Step.RebootAndVerify))
        advanceUntilIdle()

        assertThat(c.statuses).isEqualTo(listOf(DONE, DONE, FAILED))
        assertThat(c.last.steps[2].message).startsWith("The TV didn't come back")
        assertThat(c.last.waitingForTv).isTrue()
        c.job.cancel()
    }

    private companion object {
        const val ADS = "com.example.adshub"
        const val OTHER = "com.example.weather"
        const val THIRD = "com.example.shopping"
        const val GAMES = "com.example.games"
        const val MUSIC = "com.example.music"
        const val USERAPP = "com.example.userapp"
        const val NETFLIX = "com.netflix.ninja"
        const val MANUFACTURER = "getprop ro.product.manufacturer"
    }
}
