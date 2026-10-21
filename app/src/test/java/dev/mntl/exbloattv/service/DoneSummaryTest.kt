package dev.mntl.exbloattv.service

import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.cleanup.StepState
import dev.mntl.exbloattv.cleanup.StepStatus
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.tv.MemoryStats
import dev.mntl.exbloattv.tv.StorageStats
import dev.mntl.exbloattv.ui.screens.MiBoxFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoneSummaryTest {
    private val phone = "Pixel 9 · ab12"
    private val since = 100L
    private val before = MiBoxFixture.inventory().copy(
        memory = MemoryStats(2_000_000, 500_000, 1_200_000),
        storage = StorageStats(8_000_000, 6_000_000, 2_000_000),
    )
    private val after = before.copy(
        memory = MemoryStats(2_000_000, 900_000, 800_000),
        storage = StorageStats(8_000_000, 5_000_000, 3_000_000),
    )

    private fun c(id: String, kind: ChangeKind, pkg: String? = "p.$id", verified: Boolean = true, by: String = phone, at: Long = 200L) =
        Change(id, kind, pkg, "a", "b", by, at, verified)

    private val log = listOf(
        c("run-0-old:x", ChangeKind.DISABLE, at = 50L), // an earlier run
        c("run-1-aa:a", ChangeKind.DISABLE),
        c("run-1-aa:b", ChangeKind.REMOVE_FOR_USER),
        c("run-1-aa:c", ChangeKind.UNINSTALL, pkg = "com.example.game"),
        c("run-1-aa:d", ChangeKind.DISABLE, verified = false),
        c("run-1-aa:e", ChangeKind.ANIMATIONS, pkg = null),
        c("run-1-aa:f", ChangeKind.APPOPS),
        c("run-1-aa:g", ChangeKind.ENABLE),
        c("run-2-zz:h", ChangeKind.DISABLE, by = "Other phone · 9999"),
        c("manual:i", ChangeKind.DISABLE),
        c("swap-3-bb:disable:com.google.android.tvlauncher", ChangeKind.DISABLE),
        c("swap-3-bb:install", ChangeKind.INSTALL_APK),
        c("swap-3-bb:home", ChangeKind.LAUNCHER_SWAP),
    )

    private val steps = listOf(
        StepState(Step.SaveRestorePoint, StepStatus.DONE),
        StepState(Step.Disable("p.a"), StepStatus.DONE),
        StepState(Step.Disable("p.z"), StepStatus.SKIPPED),
        StepState(Step.RebootAndVerify, StepStatus.DONE),
    )

    @Test fun countsOnlyVerifiedSwitchOffsFromThisRun() {
        val s = DoneSummary.compute(before, after, log, phone, since, steps)
        assertEquals(400_000L, s.memoryFreedKb)
        assertEquals(1_000_000L, s.storageFreedKb)
        assertEquals(4, s.switchedOff) // a, b, c, the stock launcher
        assertEquals(true, s.homeIsFlauncher)
        assertEquals(listOf("com.example.game"), s.deleted)
        assertEquals(listOf<Step>(Step.Disable("p.z")), s.skipped)
        assertEquals(1_200_000L, s.memoryBeforeKb)
        assertEquals(800_000L, s.memoryAfterKb)
    }

    @Test fun missingNumbersStayMissing() {
        val s = DoneSummary.compute(before, null, log, phone, since, steps)
        assertNull(s.memoryFreedKb)
        assertNull(s.storageFreedKb)
        val s2 = DoneSummary.compute(before.copy(memory = null), after, log, phone, since, steps)
        assertNull(s2.memoryFreedKb)
        assertEquals(1_000_000L, s2.storageFreedKb)
        val s3 = DoneSummary.compute(before, after.copy(storage = null), log, phone, since, steps)
        assertNull(s3.storageFreedKb)
    }

    @Test fun unreadableLogMeansUnknownCounts() {
        val s = DoneSummary.compute(before, after, null, phone, since, steps)
        assertNull(s.switchedOff)
        assertNull(s.homeIsFlauncher)
    }

    @Test fun rolledBackSwapIsNotAHomeScreenChange() {
        val s = DoneSummary.compute(before, after, log.filterNot { it.id.endsWith(":home") }, phone, since, steps)
        assertEquals(false, s.homeIsFlauncher)
        val unverified = log.map { if (it.id.endsWith(":home")) it.copy(verified = false) else it }
        assertEquals(false, DoneSummary.compute(before, after, unverified, phone, since, steps).homeIsFlauncher)
    }
}
