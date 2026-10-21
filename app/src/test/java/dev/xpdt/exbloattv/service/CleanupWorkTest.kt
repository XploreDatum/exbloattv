package dev.xpdt.exbloattv.service

import dev.xpdt.exbloattv.adb.AdbClient
import dev.xpdt.exbloattv.adb.AdbConnector
import dev.xpdt.exbloattv.adb.AdbError
import dev.xpdt.exbloattv.cleanup.ApkDownloader
import dev.xpdt.exbloattv.cleanup.CleanupRunner
import dev.xpdt.exbloattv.cleanup.FakeTv
import dev.xpdt.exbloattv.data.ActiveJob
import kotlinx.coroutines.awaitCancellation
import dev.xpdt.exbloattv.cleanup.UndoFailure
import dev.xpdt.exbloattv.cleanup.UndoReport
import dev.xpdt.exbloattv.state.ChangeKind
import dev.xpdt.exbloattv.state.TvStateStore
import dev.xpdt.exbloattv.cleanup.LauncherSwap
import dev.xpdt.exbloattv.cleanup.Step
import dev.xpdt.exbloattv.cleanup.StepStatus
import dev.xpdt.exbloattv.ui.screens.MiBoxFixture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupWorkTest {
    /** A TV that is switched off: every connection is refused. */
    private val offTv = object : AdbConnector {
        override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient = throw AdbError.Refused()
    }

    private fun request() = MiBoxFixture.inventory().let { inv ->
        CleanupRequest(inv.info.serial, "192.168.10.3", "Mi Box 4", listOf(Step.Disable("com.google.android.videos")), inv, emptyMap())
    }

    @Test fun unreachableTvWaitsAndFinishAnywayNeverSkipsTheRestorePoint() = runTest {
        // Not backgroundScope: advanceUntilIdle doesn't run background work.
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val work = CleanupWork(
            connector = offTv,
            phoneName = { "Pixel · ab12" },
            swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
            appScope = appScope,
            settleSeconds = 0,
        )
        assertTrue(work.startCleanup(request()))
        advanceUntilIdle()

        val s = work.state.value as WorkState.Cleaning
        val p = s.progress!!
        assertTrue(p.waitingForTv)
        assertEquals(StepStatus.FAILED, p.steps[0].status)

        // Only one run at a time.
        assertFalse(work.startCleanup(request()))
        assertFalse(work.startUndo(UndoRequest("x", "h", "n")))

        work.finishAnyway()
        advanceUntilIdle()
        val after = (work.state.value as WorkState.Cleaning).progress!!
        assertEquals(StepStatus.FAILED, after.steps[0].status)
        assertEquals(StepStatus.PENDING, after.steps[1].status)

        // "Check the TV again": the run is cancelled without a result.
        work.abandonCleanup()
        advanceUntilIdle()
        assertEquals(WorkState.Idle, work.state.value)
        assertTrue(work.startCleanup(request()))
        work.abandonCleanup()
        appScope.cancel()
    }

    private fun TestScope.workOn(connector: AdbConnector, appScope: CoroutineScope) = CleanupWork(
        connector = connector,
        phoneName = { "Pixel · ab12" },
        swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
        appScope = appScope,
        settleSeconds = 0,
        now = { testScheduler.currentTime },
    )

    @Test fun stopWhenTheRestorePointFailedBecauseAnotherPhoneHoldsTheLock() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val tv = FakeTv()
        // The runner CleanupWork builds checks the lock against the wall clock.
        val held = """{"version":1,"serial":"MIBOX-SERIAL","lock":{"byPhone":"Mum's phone","at":${System.currentTimeMillis()}}}"""
        tv.fs[TvStateStore.FILE] = held
        val work = workOn(object : AdbConnector {
            override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient = tv.adb
        }, appScope)
        assertTrue(work.startCleanup(request()))
        advanceUntilIdle()
        val p = (work.state.value as WorkState.Cleaning).progress!!
        assertEquals(StepStatus.FAILED, p.steps[0].status)
        assertFalse(p.waitingForTv)

        work.abandonCleanup()
        advanceUntilIdle()

        assertEquals(WorkState.Idle, work.state.value)
        assertFalse(work.state.value.busy)
        assertEquals(held, tv.fs[TvStateStore.FILE]) // Mum's lock and log untouched
        assertTrue(tv.adb.log.none { it.startsWith("pm ") })
        assertTrue(work.startCleanup(request())) // free for the next job
        work.abandonCleanup()
        appScope.cancel()
    }

    @Test fun stopWhileWaitingForATvThatWentAwayMidRunKeepsWhatWasDone() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val tv = FakeTv()
        tv.installed += listOf("com.example.one", "com.example.two")
        var off = false
        tv.adb.onCommand.let { base ->
            tv.adb.onCommand = { cmd ->
                base(cmd)
                if (cmd == "pm disable-user --user 0 com.example.two") off = true
            }
        }
        val connector = object : AdbConnector {
            override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient {
                if (off) throw AdbError.Refused()
                return object : AdbClient by tv.adb {
                    override suspend fun shell(cmd: String) = if (off) throw AdbError.Disconnected() else tv.adb.shell(cmd)
                    override fun close() = Unit
                }
            }
        }
        val work = workOn(connector, appScope)
        val inv = MiBoxFixture.inventory()
        val plan = listOf(Step.Disable("com.example.one"), Step.Disable("com.example.two"), Step.Disable("com.example.three"))
        assertTrue(work.startCleanup(CleanupRequest(inv.info.serial, "h", "Mi Box 4", plan, inv, emptyMap())))
        advanceUntilIdle()
        val p = (work.state.value as WorkState.Cleaning).progress!!
        assertTrue(p.waitingForTv)
        assertEquals(StepStatus.DONE, p.steps[1].status)
        assertEquals(StepStatus.FAILED, p.steps[2].status)

        work.abandonCleanup()
        advanceUntilIdle()

        assertEquals(WorkState.Idle, work.state.value)
        assertTrue(tv.adb.log.none { it.contains("com.example.three") })
        val log = tv.fs.getValue(TvStateStore.FILE)
        assertTrue(log.contains("com.example.one")) // what was done stays recorded for Undo
        appScope.cancel()
    }

    @Test fun stopConfirmedAsTheRestartStepBeginsNeverRestartsTheTv() = runTest {
        // The live E2E: Stop confirmed around the end of the home-screen step; the restart step had already begun.
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val tv = FakeTv()
        tv.installed += "com.example.one"
        val bootId = "cat /proc/sys/kernel/random/boot_id"
        lateinit var work: CleanupWork
        tv.adb.onCommand.let { base ->
            tv.adb.onCommand = { cmd ->
                if (cmd == bootId) {
                    // The person's Stop lands now (the UI calls abandonCleanup); its cancel takes effect at once.
                    appScope.launch(start = CoroutineStart.UNDISPATCHED) { work.abandonCleanup() }
                    tv.adb.responses[cmd] = "boot-1\n"
                    tv.adb.exitCodes[cmd] = 0
                } else {
                    base(cmd)
                }
            }
        }
        work = workOn(object : AdbConnector {
            override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient = tv.adb
        }, appScope)
        val inv = MiBoxFixture.inventory()
        val plan = listOf(Step.Disable("com.example.one"), Step.RebootAndVerify)
        assertTrue(work.startCleanup(CleanupRequest(inv.info.serial, "h", "Mi Box 4", plan, inv, emptyMap())))
        advanceUntilIdle()

        assertTrue(tv.adb.log.contains(bootId)) // the restart step had begun
        assertFalse(tv.adb.log.contains("reboot"))
        assertEquals(WorkState.Idle, work.state.value)
        assertFalse(tv.fs.getValue(TvStateStore.FILE).contains("\"lock\":{")) // lock released
        appScope.cancel()
    }

    @Test fun stopBeforeTheServiceTookTheJobDropsTheJob() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val work = workOn(offTv, appScope).also { it.startService = { true } } // the service hasn't claimed it yet
        assertTrue(work.startCleanup(request()))

        work.abandonCleanup()

        assertEquals(WorkState.Idle, work.state.value)
        assertEquals(null, work.claimPending()) // the service, starting a moment later, finds nothing to run
        appScope.cancel()
    }

    @Test fun undoAgainstAnUnreachableTvReportsIt() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val work = CleanupWork(
            connector = offTv,
            phoneName = { "Pixel · ab12" },
            swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
            appScope = appScope,
        )
        assertTrue(work.startUndo(UndoRequest("S", "192.168.10.3", "Mi Box 4")))
        advanceUntilIdle()
        val s = work.state.value as WorkState.UndoFailed
        assertEquals(UndoProblem.UNREACHABLE, s.problem)
        work.acknowledge("S")
        assertEquals(WorkState.Idle, work.state.value)
        appScope.cancel()
    }

    @Test fun undoOnATvSetUpByANewerAppVersionSaysSoAndChangesNothing() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val tv = FakeTv()
        val v2 = """{"version":2,"serial":"S","changes":[]}"""
        tv.fs[TvStateStore.FILE] = v2
        val work = CleanupWork(
            connector = object : AdbConnector {
                override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient = tv.adb
            },
            phoneName = { "Pixel · ab12" },
            swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
            appScope = appScope,
        )
        assertTrue(work.startUndo(UndoRequest("S", "192.168.10.3", "Mi Box 4")))
        advanceUntilIdle()
        assertEquals(UndoProblem.TOO_NEW, (work.state.value as WorkState.UndoFailed).problem)
        assertEquals(mapOf(TvStateStore.FILE to v2), tv.fs)
        appScope.cancel()
    }

    private fun TestScope.undoOn(tv: FakeTv, onUndoneAll: suspend (String) -> Unit): CleanupWork {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        return CleanupWork(
            connector = object : AdbConnector {
                override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient = tv.adb
            },
            phoneName = { "Pixel · ab12" },
            swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
            appScope = appScope,
            onUndoneAll = onUndoneAll,
        )
    }

    private fun FakeTv.withOneSwitchedOff(pkg: String) {
        installed += pkg
        disabled += pkg
        fs[TvStateStore.FILE] = """{"version":1,"serial":"S","changes":[{"id":"run-1:1:a","kind":"DISABLE","pkg":"$pkg",""" +
            """"from":"enabled","to":"disabled","byPhone":"Pixel · ab12","at":1,"verified":true}]}"""
    }

    @Test fun anUndoThatEmptiesTheLogForgetsWhenTheTvWasCleaned() = runTest {
        val tv = FakeTv().apply { withOneSwitchedOff("com.example.games") }
        val undone = mutableListOf<String>()
        val work = undoOn(tv) { undone += it }
        assertTrue(work.startUndo(UndoRequest("S", "h", "Mi Box 4")))
        advanceUntilIdle()
        assertEquals(1, (work.state.value as WorkState.Undone).outcome.restored)
        assertEquals(listOf("S"), undone)
    }

    @Test fun anUndoThatLeavesChangesOnRecordKeepsTheCleanedTime() = runTest {
        val tv = FakeTv().apply {
            withOneSwitchedOff("com.example.games")
            failingEnables += "com.example.games"
        }
        val undone = mutableListOf<String>()
        val work = undoOn(tv) { undone += it }
        assertTrue(work.startUndo(UndoRequest("S", "h", "Mi Box 4")))
        advanceUntilIdle()
        assertTrue((work.state.value as WorkState.Undone).outcome.failed.isNotEmpty())
        assertEquals(emptyList<String>(), undone)
    }

    private class Journal : JobJournal {
        val events = mutableListOf<String>()
        override suspend fun started(job: ActiveJob) { events += "started ${job.kind} ${job.serial}" }
        override suspend fun ended() { events += "ended" }
    }

    @Test fun theRunningJobIsRecordedAndClearedEvenWhenCancelled() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val journal = Journal()
        val work = CleanupWork(
            connector = offTv,
            phoneName = { "Pixel · ab12" },
            swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
            appScope = appScope,
            journal = journal,
        )
        work.startCleanup(request())
        advanceUntilIdle()
        assertEquals(listOf("started CLEAN MIBOX-SERIAL"), journal.events)
        appScope.cancel() // e.g. the system stopped the service
        advanceUntilIdle()
        assertEquals(listOf("started CLEAN MIBOX-SERIAL", "ended"), journal.events)
        assertTrue(work.state.value is WorkState.CleanupStopped)
    }

    @Test fun stoppedWhileMeasuringAfterEveryStepStillCountsAsCleaned() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val tv = FakeTv()
        var connects = 0
        val connector = object : AdbConnector {
            override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient {
                if (connects++ == 0) return tv.adb
                awaitCancellation() // the TV doesn't answer the "after" read
            }
        }
        var cleanedAt: Long? = null
        val work = CleanupWork(
            connector = connector,
            phoneName = { "Pixel · ab12" },
            swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
            appScope = appScope,
            onCleaned = { _, at -> cleanedAt = at },
            now = { testScheduler.currentTime },
        )
        val inv = MiBoxFixture.inventory()
        work.startCleanup(CleanupRequest(inv.info.serial, "h", "Mi Box 4", emptyList(), inv, emptyMap()))
        advanceUntilIdle()
        val measuring = work.state.value as WorkState.Cleaning
        assertTrue(measuring.measuring)
        appScope.cancel()
        advanceUntilIdle()
        val done = work.state.value as WorkState.Cleaned
        assertEquals(null, done.summary.memoryFreedKb)
        assertEquals(null, done.summary.switchedOff)
        assertFalse(done.summary.measuredAfterRestart)
        assertTrue(cleanedAt != null)
    }

    @Test fun aJournalThatCantWriteNeverStopsTheJob() = runTest {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val work = CleanupWork(
            connector = offTv,
            phoneName = { "Pixel · ab12" },
            swapFor = { store, phone -> LauncherSwap(ApkDownloader(OkHttpClient(), File("build/tmp-apk")), store, phone) },
            appScope = appScope,
            journal = object : JobJournal {
                override suspend fun started(job: ActiveJob) = throw java.io.IOException("disk full")
                override suspend fun ended() = throw java.io.IOException("disk full")
            },
        )
        assertTrue(work.startUndo(UndoRequest("S", "192.168.10.3", "Mi Box 4")))
        advanceUntilIdle()
        assertEquals(UndoProblem.UNREACHABLE, (work.state.value as WorkState.UndoFailed).problem)
        appScope.cancel()
    }

    @Test fun changedElsewhereIsRecognised() {
        assertTrue(isChangedElsewhere(CleanupRunner.CHANGED_ELSEWHERE_MESSAGE))
        assertFalse(isChangedElsewhere("Couldn't switch this app off"))
        assertFalse(isChangedElsewhere(null))
    }

    @Test fun undoReportKeepsPackagesAndKinds() {
        val report = UndoReport(
            restored = 3,
            failed = listOf(UndoFailure("com.netflix.ninja", ChangeKind.DISABLE), UndoFailure(null, ChangeKind.LAUNCHER_SWAP)),
            reinstallFromStore = listOf("com.example.game"),
        )
        val o = report.toOutcome(interrupted = true)
        assertEquals(3, o.restored)
        assertEquals(listOf(UndoFailureInfo("com.netflix.ninja", ChangeKind.DISABLE), UndoFailureInfo(null, ChangeKind.LAUNCHER_SWAP)), o.failed)
        assertEquals(listOf("com.example.game"), o.reinstallFromStore)
        assertTrue(o.interrupted)
    }
}
