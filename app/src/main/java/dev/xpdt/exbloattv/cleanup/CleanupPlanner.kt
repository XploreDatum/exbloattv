package dev.xpdt.exbloattv.cleanup

import dev.xpdt.exbloattv.catalog.CategorizedApp
import dev.xpdt.exbloattv.catalog.Category
import dev.xpdt.exbloattv.safety.SafetyGuard
import dev.xpdt.exbloattv.tv.Inventory

/** Diffs the TV's current state against the switches and returns the ordered steps to run. */
object CleanupPlanner {
    private const val FLAUNCHER = "me.efesser.flauncher"
    private const val FAST = 0.5f

    fun plan(apps: List<CategorizedApp>, inv: Inventory, choices: Choices, removedForUser: Set<String>): List<Step> {
        val activeLauncher = inv.resolvedHome?.substringBefore('/')
        val removals = mutableListOf<Step>()
        val restores = mutableListOf<Step>()
        val background = mutableListOf<Step>()

        for (app in apps) {
            if (app.category == Category.LAUNCHER || app.category == Category.SYSTEM) continue
            val name = app.pkg.name
            val keep = when {
                app.locked -> app.defaultKeep
                else -> choices.keep[name] ?: continue
            }
            if (keep) {
                when {
                    name in removedForUser -> restores += Step.InstallExisting(name)
                    !app.pkg.enabled -> restores += Step.Enable(name)
                    wantsBackgroundStop(app, inv, choices) -> background += Step.RestrictBackground(name)
                }
            } else if (name !in removedForUser && app.pkg.enabled && !SafetyGuard.isProtected(name, activeLauncher)) {
                removals += if (!app.pkg.isSystem && choices.deleteUserApps) Step.Uninstall(name) else Step.Disable(name)
            }
        }

        val swap = choices.newHomeScreen && activeLauncher != FLAUNCHER &&
            !inv.info.manufacturer.equals("Amazon", ignoreCase = true)
        val a = inv.animations

        return buildList {
            addAll(removals)
            addAll(restores)
            addAll(background)
            if (choices.fasterAnimations && (a.window != FAST || a.transition != FAST || a.animator != FAST)) add(Step.SetAnimations(FAST))
            if (choices.clearCaches) add(Step.TrimCaches)
            if (swap) add(Step.SwapLauncher)
            if (swap || removals.any { it is Step.Disable }) add(Step.RebootAndVerify)
        }
    }

    private fun wantsBackgroundStop(app: CategorizedApp, inv: Inventory, choices: Choices) =
        choices.stopBackground && app.launchable && !app.musicApp &&
            (app.category == Category.STREAMING || app.category == Category.LIVE_TV) &&
            inv.backgroundOps[app.pkg.name] != "ignore"
}
