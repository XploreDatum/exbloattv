package dev.mntl.exbloattv.service

import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.cleanup.StepState
import dev.mntl.exbloattv.cleanup.StepStatus
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.tv.Inventory

/**
 * The numbers on the "All clean" screen. Only verified changes count; a number that couldn't be measured is null
 * (shown as "—"), never zero.
 */
data class DoneSummary(
    val memoryBeforeKb: Long?,
    val memoryAfterKb: Long?,
    val storageBeforeKb: Long?,
    val storageAfterKb: Long?,
    /** Verified switch-offs, removals and deletions of this run (stock launchers included); null if the log was unreadable. */
    val switchedOff: Int?,
    /** A verified home screen swap from this run; null if the log was unreadable. */
    val homeIsFlauncher: Boolean?,
    /** Packages this run deleted (only the Play Store can bring them back). */
    val deleted: List<String>,
    /** Steps the person chose to skip. */
    val skipped: List<Step>,
) {
    val memoryFreedKb: Long? get() = diff(memoryBeforeKb, memoryAfterKb)
    val storageFreedKb: Long? get() = diff(storageBeforeKb, storageAfterKb)

    companion object {
        private val SWITCH_OFF = setOf(ChangeKind.DISABLE, ChangeKind.REMOVE_FOR_USER, ChangeKind.UNINSTALL)

        /**
         * [after] is the TV re-read after the restart and settle time (null if it couldn't be read); [changes] is the
         * TV's change log after the run (null if unreadable). "This run" = entries written by [phone] at or after
         * [since] with the runner's (`run-`) or the launcher swap's (`swap-`) ids.
         */
        fun compute(
            before: Inventory,
            after: Inventory?,
            changes: List<Change>?,
            phone: String,
            since: Long,
            steps: List<StepState>,
        ): DoneSummary {
            val mine = changes?.filter {
                it.verified && it.byPhone == phone && it.at >= since && (it.id.startsWith("run-") || it.id.startsWith("swap-"))
            }
            return DoneSummary(
                memoryBeforeKb = before.memory?.usedPssKb,
                memoryAfterKb = after?.memory?.usedPssKb,
                storageBeforeKb = before.storage?.usedKb,
                storageAfterKb = after?.storage?.usedKb,
                switchedOff = mine?.filter { it.kind in SWITCH_OFF }?.distinctBy { it.id }?.size,
                homeIsFlauncher = mine?.any { it.kind == ChangeKind.LAUNCHER_SWAP && it.id.startsWith("swap-") },
                deleted = mine.orEmpty().filter { it.kind == ChangeKind.UNINSTALL }.mapNotNull { it.pkg }.distinct(),
                skipped = steps.filter { it.status == StepStatus.SKIPPED }.map { it.step },
            )
        }

        private fun diff(before: Long?, after: Long?): Long? = if (before != null && after != null) before - after else null
    }
}
