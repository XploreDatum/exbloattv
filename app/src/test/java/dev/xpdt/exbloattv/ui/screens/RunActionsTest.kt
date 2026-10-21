package dev.xpdt.exbloattv.ui.screens

import dev.xpdt.exbloattv.cleanup.CleanupRunner
import dev.xpdt.exbloattv.cleanup.RunProgress
import dev.xpdt.exbloattv.cleanup.Step
import dev.xpdt.exbloattv.cleanup.StepState
import dev.xpdt.exbloattv.cleanup.StepStatus
import dev.xpdt.exbloattv.cleanup.StepStatus.DONE
import dev.xpdt.exbloattv.cleanup.StepStatus.FAILED
import dev.xpdt.exbloattv.cleanup.StepStatus.PENDING
import dev.xpdt.exbloattv.cleanup.StepStatus.RUNNING
import dev.xpdt.exbloattv.service.WorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunActionsTest {
    private fun cleaning(vararg s: Pair<StepStatus, String?>, waiting: Boolean = false, settle: Int? = null, measuring: Boolean = false) =
        WorkState.Cleaning(
            "S", "TV",
            RunProgress(
                s.mapIndexed { i, (st, msg) -> StepState(if (i == 0) Step.SaveRestorePoint else Step.Disable("a.b$i"), st, msg) },
                0.5f, waiting,
            ),
            emptyMap(), settle, measuring,
        )

    @Test fun aRunGoingWellOffersStopOnlyInTheTopBar() {
        val a = runActions(cleaning(DONE to null, RUNNING to null, PENDING to null))
        assertTrue(a.topStop)
        assertFalse(a.stop)
        assertFalse(a.finishAnyway)
        assertNull(a.terminal)
    }

    @Test fun aStartingRunCanBeStoppedFromTheTopBar() {
        val a = runActions(WorkState.Cleaning("S", "TV", null, emptyMap()))
        assertTrue(a.topStop)
        assertFalse(a.stop)
    }

    @Test fun aFailedRestorePointOffersStopNotFinishAnyway() {
        // e.g. another phone holds the lock, or the TV was off when the run started.
        val a = runActions(cleaning(FAILED to "Another phone (Mum) is cleaning this TV", PENDING to null))
        assertTrue(a.stop)
        assertFalse(a.finishAnyway)
    }

    @Test fun waitingForTheTvOffersStop() {
        val a = runActions(cleaning(DONE to null, DONE to null, FAILED to CleanupRunner.LOST, waiting = true))
        assertTrue(a.stop)
        assertTrue(a.finishAnyway)
    }

    @Test fun aChangedElsewhereRunOffersCheckAgainAndStop() {
        val a = runActions(cleaning(DONE to null, FAILED to CleanupRunner.CHANGED_ELSEWHERE_MESSAGE))
        assertTrue(a.checkAgain)
        assertTrue(a.stop)
        assertFalse(a.finishAnyway)
        assertEquals(CleanupRunner.CHANGED_ELSEWHERE_MESSAGE, a.terminal)
    }

    @Test fun aTvSetUpByANewerAppOffersOnlyStop() {
        val a = runActions(cleaning(FAILED to CleanupRunner.TOO_NEW_MESSAGE, PENDING to null))
        assertTrue(a.stop)
        assertFalse(a.checkAgain)
        assertFalse(a.finishAnyway)
        assertEquals(CleanupRunner.TOO_NEW_MESSAGE, a.terminal)
    }

    @Test fun afterEveryStepStopIsNotOfferedWhileSettlingOrMeasuring() {
        assertFalse(runActions(cleaning(DONE to null, DONE to null, settle = 30)).topStop)
        assertFalse(runActions(cleaning(DONE to null, DONE to null, measuring = true)).topStop)
    }
}
