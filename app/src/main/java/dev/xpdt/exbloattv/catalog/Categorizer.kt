package dev.xpdt.exbloattv.catalog

import dev.xpdt.exbloattv.safety.SafetyGuard
import dev.xpdt.exbloattv.tv.InstalledPackage
import dev.xpdt.exbloattv.tv.Inventory

data class CategorizedApp(
    val pkg: InstalledPackage,
    val category: Category,
    val name: String,
    val description: String,
    val defaultKeep: Boolean,
    val locked: Boolean,
    val musicApp: Boolean,
    val launchable: Boolean,
)

/** Turns the TV's raw package list into grouped, named apps with default keep/off. */
class Categorizer(private val catalog: Catalog) {

    fun categorize(inv: Inventory): List<CategorizedApp> =
        inv.packages.map { categorizeOne(it, inv) }
            .sortedWith(compareBy<CategorizedApp> { it.category.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    private fun categorizeOne(p: InstalledPackage, inv: Inventory): CategorizedApp {
        val launchable = p.name in inv.launchable
        val entry = catalog.match(p.name)
        // Remote-input and never-disable packages are system, whatever the catalog says.
        if (SafetyGuard.isProtected(p.name)) {
            return CategorizedApp(p, Category.SYSTEM, entry?.name ?: prettify(p.name), entry?.description.orEmpty(), true, true, false, launchable)
        }
        if (entry != null) {
            return CategorizedApp(p, entry.category, entry.name, entry.description, entry.defaultKeep, entry.locked, entry.musicApp, launchable)
        }
        if (p.name in inv.homePackages) {
            return CategorizedApp(p, Category.LAUNCHER, prettify(p.name), "A home screen app", true, true, false, launchable)
        }
        return if (p.isSystem) {
            CategorizedApp(p, Category.SYSTEM, prettify(p.name), "", true, true, false, launchable)
        } else {
            CategorizedApp(p, Category.OTHER, prettify(p.name), "", true, false, false, launchable)
        }
    }

    private fun prettify(pkg: String): String {
        val last = pkg.substringAfterLast('.').ifBlank { pkg }
        return last.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
