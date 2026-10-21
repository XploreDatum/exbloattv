package dev.mntl.exbloattv.service

import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbConnector
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.cleanup.ApkDownloader
import dev.mntl.exbloattv.cleanup.CleanupRunner
import dev.mntl.exbloattv.cleanup.FakeTv
import dev.mntl.exbloattv.data.ActiveJob
import kotlinx.coroutines.awaitCancellation
import dev.mntl.exbloattv.cleanup.UndoFailure
import dev.mntl.exbloattv.cleanup.UndoReport
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.cleanup.LauncherSwap
import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.cleanup.StepStatus
import dev.mntl.exbloattv.ui.screens.MiBoxFixture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
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
