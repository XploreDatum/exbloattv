package dev.xpdt.exbloattv.ui.screens

import dev.xpdt.exbloattv.catalog.Catalog
import dev.xpdt.exbloattv.catalog.Categorizer
import dev.xpdt.exbloattv.data.LoadedTv
import dev.xpdt.exbloattv.data.TvSession
import dev.xpdt.exbloattv.tv.AnimationScales
import dev.xpdt.exbloattv.tv.Fixtures
import dev.xpdt.exbloattv.tv.InstalledPackage
import dev.xpdt.exbloattv.tv.Inventory
import dev.xpdt.exbloattv.tv.MemoryStats
import dev.xpdt.exbloattv.tv.Parsers
import dev.xpdt.exbloattv.tv.StorageStats
import dev.xpdt.exbloattv.tv.TvInfo
import java.io.File

/** The Mi Box 4 as it was before the 2026-10-21 cleanup, plus one unknown user-installed app. */
object MiBoxFixture {
    const val UNKNOWN_USER_APP = "com.example.unknowngame"
    val categorizer = Categorizer(Catalog.parse(File("src/main/assets/catalog.json").readText()))

    fun inventory(extra: List<InstalledPackage> = listOf(InstalledPackage(UNKNOWN_USER_APP, "/data/app/x/base.apk", null, 10_500, false, true))): Inventory {
        val enabled = Parsers.packageNames(Fixtures.text("pm_list_enabled_before_cleanup.txt"))
        val system = Parsers.packageNames(Fixtures.text("pm_list_system.txt"))
        val pkgs = Fixtures.text("pm_list_f_before_cleanup.txt").lines().filter { it.startsWith("package:") }.map {
            val body = it.removePrefix("package:")
            val name = body.substringAfterLast('=')
            InstalledPackage(name, body.substringBeforeLast('='), null, 0, name in system, name in enabled)
        } + extra
        val launchable = (Parsers.queryActivitiesPackages(Fixtures.text("query_leanback.txt")) + "com.google.android.tvlauncher").toSet()
        return Inventory(
            TvInfo("MIBOX-SERIAL", "MIBOX4", "Xiaomi", "Xiaomi", "9", 28), pkgs, launchable,
            listOf("com.google.android.tvlauncher", "com.xiaomi.mitv.setupwraith", "com.android.tv.settings"),
            "com.google.android.tvlauncher/.MainActivity",
            MemoryStats(2_000_000, 500_000, 1_200_000), StorageStats(8_000_000, 6_000_000, 2_000_000),
            AnimationScales(1f, 1f, 1f), emptyMap(),
        )
    }

    fun loaded(inv: Inventory = inventory()) =
        LoadedTv(host = "192.168.10.3", name = "Mi Box 4", inventory = inv, apps = TvSession.appsOf(inv, categorizer), removedForUser = inv.removedForUser)
}
