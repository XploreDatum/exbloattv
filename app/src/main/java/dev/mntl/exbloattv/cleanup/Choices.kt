package dev.mntl.exbloattv.cleanup

import dev.mntl.exbloattv.catalog.CategorizedApp

/** The person's switches. [keep] is package name -> "should be on" (the end state). */
data class Choices(
    val keep: Map<String, Boolean>,
    val fasterAnimations: Boolean = true,
    val stopBackground: Boolean = true,
    val clearCaches: Boolean = true,
    val newHomeScreen: Boolean = true,
    val deleteUserApps: Boolean = false,
)

/** Starting switches: apps already off on the TV show off; locked rows use their default; speed options on. */
fun defaultChoices(apps: List<CategorizedApp>): Choices =
    Choices(keep = apps.associate { it.pkg.name to if (it.locked) it.defaultKeep else (it.pkg.enabled && it.defaultKeep) })
