package dev.mntl.exbloattv.cleanup

import dev.mntl.exbloattv.catalog.Catalog
import dev.mntl.exbloattv.catalog.CategorizedApp
import dev.mntl.exbloattv.catalog.Categorizer
import dev.mntl.exbloattv.catalog.Category
import dev.mntl.exbloattv.safety.SafetyGuard
import dev.mntl.exbloattv.tv.AnimationScales
import dev.mntl.exbloattv.tv.Fixtures
import dev.mntl.exbloattv.tv.InstalledPackage
import dev.mntl.exbloattv.tv.Inventory
import dev.mntl.exbloattv.tv.Parsers
import dev.mntl.exbloattv.tv.TvInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CleanupPlannerTest {
    private val categorizer = Categorizer(Catalog.parse(File("src/main/assets/catalog.json").readText()))
    private val stockHome = "com.google.android.tvlauncher/.MainActivity"

    private fun info(manufacturer: String = "Xiaomi") = TvInfo("s", "MIBOX4", "Xiaomi", manufacturer, "9", 28)

    private fun inv(
        pkgs: List<InstalledPackage>,
        launchable: Set<String> = emptySet(),
        home: String? = stockHome,
        anims: AnimationScales = AnimationScales(1f, 1f, 1f),
        ops: Map<String, String> = emptyMap(),
        manufacturer: String = "Xiaomi",
    ) = Inventory(info(manufacturer), pkgs, launchable, listOf("com.google.android.tvlauncher"), home, null, null, anims, ops)

    private fun pkg(name: String, system: Boolean = false, enabled: Boolean = true) =
        InstalledPackage(name, "/x/$name.apk", null, 10000, system, enabled)

    private fun app(
        name: String, category: Category, defaultKeep: Boolean = true, locked: Boolean = false, music: Boolean = false,
        system: Boolean = false, enabled: Boolean = true, launchable: Boolean = true,
    ) = CategorizedApp(pkg(name, system, enabled), category, name, "", defaultKeep, locked, music, launchable)

    private val none = Choices(emptyMap(), fasterAnimations = false, stopBackground = false, clearCaches = false, newHomeScreen = false)

    private fun planOf(apps: List<CategorizedApp>, choices: Choices, removed: Set<String> = emptySet(), i: Inventory = inv(apps.map { it.pkg })) =
        CleanupPlanner.plan(apps, i, choices, removed)

    // ---- Mi Box, pre-cleanup ----
    private fun preCleanupInventory(): Inventory {
        val enabled = Parsers.packageNames(Fixtures.text("pm_list_enabled_before_cleanup.txt"))
        val system = Parsers.packageNames(Fixtures.text("pm_list_system.txt"))
        val pkgs = Fixtures.text("pm_list_f_before_cleanup.txt").lines().filter { it.startsWith("package:") }.map {
            val body = it.removePrefix("package:")
            val name = body.substringAfterLast('=')
            InstalledPackage(name, body.substringBeforeLast('='), null, 0, name in system, name in enabled)
        }
        val launchable = (Parsers.queryActivitiesPackages(Fixtures.text("query_leanback.txt")) + "com.google.android.tvlauncher").toSet()
        return Inventory(
            info(), pkgs, launchable,
            listOf("com.google.android.tvlauncher", "com.xiaomi.mitv.setupwraith", "com.android.tv.settings"),
            stockHome, null, null, AnimationScales(1f, 1f, 1f), emptyMap(),
        )
    }

    @Test fun miBoxPreCleanupDefaultPlan() {
        val inv = preCleanupInventory()
        val apps = categorizer.categorize(inv)
        val plan = CleanupPlanner.plan(apps, inv, defaultChoices(apps), emptySet())
        val disabled = plan.filterIsInstance<Step.Disable>().map { it.pkg }.toSet()
        for (p in listOf("com.miui.tv.analytics", "tv.alphonso.alphonso_eula", "com.google.android.videos", "com.mitv.tvhome.atv"))
            assertTrue("$p should be disabled", p in disabled)
        for (p in listOf("com.google.android.katniss", "com.netflix.ninja")) assertFalse(p in disabled)
        assertTrue(disabled.none { SafetyGuard.isProtected(it, "com.google.android.tvlauncher") })
        assertTrue(plan.none { it is Step.Enable || it is Step.InstallExisting || it is Step.Uninstall })
        assertEquals(Step.SwapLauncher, plan[plan.size - 2])
        assertEquals(Step.RebootAndVerify, plan.last())
    }

    @Test fun defaultChoicesOnCleanedTvMarkDisabledAppsOff() {
        val inv = inv(
            listOf(pkg("com.google.android.videos", enabled = false), pkg("com.netflix.ninja")),
        )
        val apps = categorizer.categorize(inv)
        val c = defaultChoices(apps)
        assertEquals(false, c.keep["com.google.android.videos"])
        assertEquals(true, c.keep["com.netflix.ninja"])
        assertTrue(c.fasterAnimations && c.stopBackground && c.clearCaches && c.newHomeScreen)
        assertFalse(c.deleteUserApps)
        val plan = CleanupPlanner.plan(apps, inv, c, emptySet())
        assertTrue(plan.none { it is Step.Disable || it is Step.Enable })
    }

    @Test fun lockedRowsUseDefaultRegardlessOfSwitch() {
        val a = app("tv.alphonso.alphonso_eula", Category.ADS_TRACKING, defaultKeep = false, locked = true)
        assertEquals(listOf<Step>(Step.Disable(a.pkg.name), Step.RebootAndVerify), planOf(listOf(a), none.copy(keep = mapOf(a.pkg.name to true))))
    }

    @Test fun launcherAndSystemNeverGetSwitchSteps() {
        val l = app("com.example.home", Category.LAUNCHER)
        val s = app("com.example.sys", Category.SYSTEM, system = true)
        assertEquals(emptyList<Step>(), planOf(listOf(l, s), none.copy(keep = mapOf(l.pkg.name to false, s.pkg.name to false))))
    }

    @Test fun protectedPackagesAreDropped() {
        val a = app("com.google.android.gms", Category.GOOGLE_EXTRAS, system = true)
        val b = app("com.example.home", Category.OTHER)
        val i = inv(listOf(a.pkg, b.pkg), home = "com.example.home/.Main")
        assertEquals(emptyList<Step>(), planOf(listOf(a, b), none.copy(keep = mapOf(a.pkg.name to false, b.pkg.name to false)), i = i))
    }

    @Test fun userAppOffIsDisabledOrUninstalled() {
        val a = app("com.example.game", Category.OTHER)
        val off = none.copy(keep = mapOf(a.pkg.name to false))
        assertEquals(Step.Disable(a.pkg.name), planOf(listOf(a), off).first())
        assertEquals(Step.Uninstall(a.pkg.name), planOf(listOf(a), off.copy(deleteUserApps = true)).first())
        val sys = app("com.example.sysapp", Category.MANUFACTURER, system = true)
        assertEquals(Step.Disable(sys.pkg.name), planOf(listOf(sys), none.copy(keep = mapOf(sys.pkg.name to false), deleteUserApps = true)).first())
    }

    @Test fun enableAndInstallExisting() {
        val d = app("com.example.a", Category.OTHER, enabled = false)
        val r = app("com.example.b", Category.OTHER)
        val plan = planOf(listOf(d, r), none.copy(keep = mapOf(d.pkg.name to true, r.pkg.name to true)), removed = setOf(r.pkg.name))
        assertEquals(listOf<Step>(Step.Enable(d.pkg.name), Step.InstallExisting(r.pkg.name)), plan)
    }

    @Test fun alreadyInRightStateIsNoStep() {
        val d = app("com.example.a", Category.OTHER, enabled = false)
        val e = app("com.example.b", Category.OTHER)
        val r = app("com.example.c", Category.OTHER)
        val c = none.copy(keep = mapOf(d.pkg.name to false, e.pkg.name to true, r.pkg.name to false))
        assertEquals(emptyList<Step>(), planOf(listOf(d, e, r), c, removed = setOf(r.pkg.name)))
    }

    @Test fun restrictBackgroundOnlyForKeptStreamingAndLiveNonMusic() {
        val s = app("s", Category.STREAMING)
        val live = app("live", Category.LIVE_TV)
        val music = app("m", Category.STREAMING, music = true)
        val notLaunch = app("nl", Category.STREAMING, launchable = false)
        val done = app("done", Category.STREAMING)
        val off = app("off", Category.STREAMING)
        val other = app("o", Category.OTHER)
        val apps = listOf(s, live, music, notLaunch, done, off, other)
        val i = inv(apps.map { it.pkg }, ops = mapOf("done" to "ignore"))
        val c = none.copy(stopBackground = true, keep = apps.associate { it.pkg.name to (it.pkg.name != "off") })
        val plan = CleanupPlanner.plan(apps, i, c, emptySet())
        assertEquals(listOf<Step>(Step.Disable("off"), Step.RestrictBackground("s"), Step.RestrictBackground("live"), Step.RebootAndVerify), plan)
        assertEquals(emptyList<Step>(), CleanupPlanner.plan(apps, i, c.copy(stopBackground = false, keep = c.keep + ("off" to true)), emptySet()))
    }

    @Test fun animationsOnlyWhenNotAlreadyHalf() {
        val a = none.copy(fasterAnimations = true)
        assertEquals(listOf<Step>(Step.SetAnimations(0.5f)), planOf(emptyList(), a))
        assertEquals(listOf<Step>(Step.SetAnimations(0.5f)), planOf(emptyList(), a, i = inv(emptyList(), anims = AnimationScales(0.5f, 0.5f, 1f))))
        assertEquals(emptyList<Step>(), planOf(emptyList(), a, i = inv(emptyList(), anims = AnimationScales(0.5f, 0.5f, 0.5f))))
    }

    @Test fun launcherSwapRules() {
        val c = none.copy(newHomeScreen = true)
        assertEquals(listOf<Step>(Step.SwapLauncher, Step.RebootAndVerify), planOf(emptyList(), c))
        assertEquals(emptyList<Step>(), planOf(emptyList(), c, i = inv(emptyList(), home = "me.efesser.flauncher/.MainActivity")))
        assertEquals(emptyList<Step>(), planOf(emptyList(), c, i = inv(emptyList(), manufacturer = "amazon")))
    }

    @Test fun orderingAndReboot() {
        val dis = app("dis", Category.OTHER)
        val en = app("en", Category.OTHER, enabled = false)
        val st = app("st", Category.STREAMING)
        val c = Choices(mapOf("dis" to false, "en" to true, "st" to true))
        val plan = planOf(listOf(dis, en, st), c)
        assertEquals(
            listOf<Step>(Step.Disable("dis"), Step.Enable("en"), Step.RestrictBackground("st"), Step.SetAnimations(0.5f), Step.TrimCaches, Step.SwapLauncher, Step.RebootAndVerify),
            plan,
        )
    }

    @Test fun noRebootWithoutDisableOrSwap() {
        val en = app("en", Category.OTHER, enabled = false)
        assertEquals(listOf<Step>(Step.Enable("en"), Step.TrimCaches), planOf(listOf(en), none.copy(clearCaches = true, keep = mapOf("en" to true))))
    }

    @Test fun emptyDiffAndNoSpeedOptionsIsEmpty() {
        assertEquals(emptyList<Step>(), planOf(emptyList(), none))
    }

    @Test fun describeUsesFriendlyNames() {
        val names = mapOf("com.netflix.ninja" to "Netflix")
        assertEquals("Switch off Netflix", Step.Disable("com.netflix.ninja").describe(names))
        assertEquals("Switch on Netflix", Step.Enable("com.netflix.ninja").describe(names))
        assertEquals("Switch off Foo", Step.Disable("com.x.foo").describe(emptyMap()))
        assertEquals("Restart and check everything still works", Step.RebootAndVerify.describe(emptyMap()))
    }
}
