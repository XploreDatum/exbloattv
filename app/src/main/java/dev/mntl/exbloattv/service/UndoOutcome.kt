package dev.mntl.exbloattv.service

import dev.mntl.exbloattv.cleanup.UndoReport
import dev.mntl.exbloattv.state.ChangeKind

/**
 * Something Undo could not put back. [pkg] + [kind] is the engine's shape (pkg == null with [ChangeKind.LAUNCHER_SWAP]
 * is the home screen); the screens label it. [label] carries a ready-made name only when the engine gave one.
 */
data class UndoFailureInfo(val pkg: String?, val kind: ChangeKind?, val label: String? = null)

/** What "Undo everything" did, for the screens. [interrupted]: the connection was lost midway; Retry carries on. */
data class UndoOutcome(
    val restored: Int,
    val failed: List<UndoFailureInfo>,
    val reinstallFromStore: List<String>,
    val interrupted: Boolean = false,
)

/**
 * The only code that reads the engine's [UndoReport]. Task 10's fix round changes `failed` to
 * `List<UndoFailure(pkg, kind)>` and adds `UndoInterruptedException(partial)`; on that merge this maps
 * `UndoFailure` -> [UndoFailureInfo] (pkg, kind) and [CleanupWork] maps the exception to an interrupted outcome.
 */
internal fun UndoReport.toOutcome(interrupted: Boolean = false) = UndoOutcome(
    restored = restored,
    failed = failed.map { UndoFailureInfo(pkg = null, kind = null, label = it) },
    reinstallFromStore = reinstallFromStore,
    interrupted = interrupted,
)
