package dev.mntl.exbloattv.ui.screens

import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.cleanup.StepState
import dev.mntl.exbloattv.cleanup.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistTest {
    @Test fun stepsOfOneKindShareALineInRunOrder() {
        val steps = listOf(
            StepState(Step.SaveRestorePoint, StepStatus.DONE),
            StepState(Step.Disable("a"), StepStatus.DONE),
            StepState(Step.Disable("b"), StepStatus.FAILED, "nope"),
            StepState(Step.Disable("c"), StepStatus.SKIPPED),
            StepState(Step.Enable("d"), StepStatus.PENDING),
            StepState(Step.SetAnimations(0.5f), StepStatus.RUNNING),
            StepState(Step.SwapLauncher, StepStatus.PENDING),
            StepState(Step.RebootAndVerify, StepStatus.PENDING),
        )
        val lines = checklist(steps)
        assertEquals(
            listOf(LineKind.RESTORE, LineKind.DISABLE, LineKind.SWITCH_ON, LineKind.ANIMATIONS, LineKind.SWAP, LineKind.REBOOT),
            lines.map { it.kind },
        )
        val off = lines[1]
        assertEquals(listOf(1, 2, 3), off.indices)
        assertEquals(StepStatus.FAILED, off.status)
        assertEquals(2, off.finished)
        assertEquals(StepStatus.DONE, lines[0].status)
        assertEquals(StepStatus.RUNNING, lines[3].status)
        assertEquals(StepStatus.PENDING, lines[4].status)
    }
}
