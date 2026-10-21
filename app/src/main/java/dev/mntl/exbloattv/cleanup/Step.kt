package dev.mntl.exbloattv.cleanup

/**
 * One thing the runner does on the TV. Steps carry no UI copy; the Cleaning screen
 * turns them into checklist text with [describe].
 */
sealed interface Step {
    data class Disable(val pkg: String) : Step
    data class Enable(val pkg: String) : Step
    data class InstallExisting(val pkg: String) : Step
    data class Uninstall(val pkg: String) : Step
    data class SetAnimations(val scale: Float) : Step
    data class RestrictBackground(val pkg: String) : Step
    data object TrimCaches : Step
    data object SwapLauncher : Step
    data object RebootAndVerify : Step
    /**
     * Take the run lock and save the restore point. [CleanupRunner] puts this first on every checklist (spec §6
     * item 4); the planner never emits it.
     */
    data object SaveRestorePoint : Step
}

/** [appNames] maps package -> friendly name; unknown packages fall back to a prettified name. */
fun appName(pkg: String, appNames: Map<String, String>): String = appNames[pkg]
    ?: pkg.substringAfterLast('.').ifBlank { pkg }.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Plain-language checklist text. [appNames] maps package -> friendly name; unknown packages fall back to a prettified name. */
fun Step.describe(appNames: Map<String, String>): String {
    fun name(pkg: String) = appName(pkg, appNames)
    return when (this) {
        is Step.Disable -> "Switch off ${name(pkg)}"
        is Step.Enable -> "Switch on ${name(pkg)}"
        is Step.InstallExisting -> "Bring back ${name(pkg)}"
        is Step.Uninstall -> "Delete ${name(pkg)}"
        is Step.SetAnimations -> "Make menus and screens snappier"
        is Step.RestrictBackground -> "Stop ${name(pkg)} running in the background"
        Step.TrimCaches -> "Clear temporary files"
        Step.SwapLauncher -> "Set up the new home screen"
        Step.RebootAndVerify -> "Restart and check everything still works"
        Step.SaveRestorePoint -> "Save a restore point"
    }
}
