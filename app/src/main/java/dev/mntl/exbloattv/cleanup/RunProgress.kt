package dev.mntl.exbloattv.cleanup

enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** [message] is plain-language UI text: why a step failed, or a note on a step that worked. */
data class StepState(val step: Step, val status: StepStatus, val message: String? = null)

/**
 * One snapshot of a cleanup run. [steps] starts with [Step.SaveRestorePoint], then the plan in order; indexes into
 * it are what [CleanupRunner.retry] and [CleanupRunner.skip] take. [fraction] is (done + failed + skipped) / total.
 * [waitingForTv] is true while the run is paused because the TV can't be reached; it resumes on retry/skip.
 */
data class RunProgress(val steps: List<StepState>, val fraction: Float, val waitingForTv: Boolean = false)
