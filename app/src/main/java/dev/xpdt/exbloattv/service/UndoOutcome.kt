package dev.xpdt.exbloattv.service

import dev.xpdt.exbloattv.cleanup.UndoReport
import dev.xpdt.exbloattv.state.ChangeKind

/**
 * Something Undo could not put back: [pkg] + [kind] as the engine reports it (pkg == null with
 * [ChangeKind.LAUNCHER_SWAP] is the home screen). The screens turn it into a name.
 */
data class UndoFailureInfo(val pkg: String?, val kind: ChangeKind)

/** What "Undo everything" did, for the screens. [interrupted]: it stopped partway; Retry carries on. */
data class UndoOutcome(
    val restored: Int,
    val failed: List<UndoFailureInfo>,
    val reinstallFromStore: List<String>,
    val interrupted: Boolean = false,
)

/** The only code that reads the engine's [UndoReport]. */
internal fun UndoReport.toOutcome(interrupted: Boolean = false) = UndoOutcome(
    restored = restored,
    failed = failed.map { UndoFailureInfo(it.pkg, it.kind) },
    reinstallFromStore = reinstallFromStore,
    interrupted = interrupted,
)
