package dev.mntl.exbloattv.ui.screens

import dev.mntl.exbloattv.catalog.Category
import dev.mntl.exbloattv.cleanup.CleanupPlanner
import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.data.LoadedTv
import dev.mntl.exbloattv.data.SavedChoices
import dev.mntl.exbloattv.safety.SafetyGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(loaded: LoadedTv = MiBoxFixture.loaded(), saved: SavedChoices? = null) =
        KeepViewModel(load = { loaded }, savedChoices = { saved })

    private fun KeepViewModel.ready() = state.value as KeepUiState.Ready
    private fun KeepUiState.Ready.row(pkg: String) =
        (groups.flatMap { it.rows } + alwaysOff).first { it.pkg == pkg }

    @Test fun groupsFollowTheKeepScreenOrder() {
        val r = vm().ready()
        val shown = r.groups.map { it.category }
        val order = listOf(
            Category.STREAMING, Category.MUSIC, Category.LIVE_TV, Category.FEATURES, Category.GOOGLE_EXTRAS,
            Category.MANUFACTURER, Category.SIDELOAD_TOOLS, Category.OTHER,
        )
        assertEquals(KEEP_GROUP_ORDER, order)
        assertEquals(order.filter { it in shown }, shown)
        for (c in listOf(Category.STREAMING, Category.LIVE_TV, Category.FEATURES, Category.GOOGLE_EXTRAS, Category.MANUFACTURER, Category.OTHER)) {
            assertTrue("$c should be shown", c in shown)
        }
        assertEquals(Category.OTHER, shown.last())
        assertTrue(r.groups.first { it.category == Category.OTHER }.rows.any { it.pkg == MiBoxFixture.UNKNOWN_USER_APP })
        for (hidden in listOf(Category.ADS_TRACKING, Category.SYSTEM, Category.LAUNCHER)) assertFalse(hidden in shown)
    }

    @Test fun adsAreCollapsedAndAlwaysOff() {
        val r = vm().ready()
        val ads = r.alwaysOff.map { it.pkg }
        assertTrue("com.miui.tv.analytics" in ads)
        assertTrue("tv.alphonso.alphonso_eula" in ads)
        assertTrue(r.alwaysOff.all { !it.keep && !it.toggleable })
    }

    @Test fun remoteInputAppsAreNeverShown() {
        val r = vm().ready()
        val everything = r.groups.flatMap { it.rows }.map { it.pkg } + r.alwaysOff.map { it.pkg } + r.system.map { it.pkg }
        assertTrue("com.droidlogic.ble" !in everything)
        assertTrue("com.google.android.tv.remote.service" !in everything)
        assertTrue("com.google.android.inputmethod.latin" !in everything)
        assertTrue(everything.none { SafetyGuard.isRemoteInput(it) })
        // Launchers and system apps are only in the read-only list.
        assertTrue("com.google.android.tvlauncher" in r.system.map { it.pkg })
        assertTrue("com.android.tv.settings" in r.system.map { it.pkg })
    }

    @Test fun lockedRowsAreNotToggleable() {
        val v = vm()
        val before = v.ready().choices
        v.setKeep("tv.alphonso.alphonso_eula", true)
        v.setKeep("com.google.android.tvlauncher", false)
        v.setKeep("com.android.tv.settings", false)
        assertEquals(before, v.ready().choices)
        assertFalse(v.ready().row("tv.alphonso.alphonso_eula").keep)
    }

    @Test fun togglingUpdatesChoicesKeep() {
        val v = vm()
        assertTrue(v.ready().row("com.netflix.ninja").keep)
        v.setKeep("com.netflix.ninja", false)
        assertEquals(false, v.ready().choices.keep["com.netflix.ninja"])
        assertFalse(v.ready().row("com.netflix.ninja").keep)
        v.setKeep("com.google.android.videos", true)
        assertEquals(true, v.ready().choices.keep["com.google.android.videos"])
    }

    @Test fun reviewCountsMatchThePlanner() {
        val loaded = MiBoxFixture.loaded()
        val v = vm(loaded)
        fun expected(): List<Step> = CleanupPlanner.plan(loaded.apps, loaded.inventory, v.ready().choices, loaded.removedForUser)

        var r = v.ready()
        assertEquals(expected(), r.plan)
        assertEquals(expected().count { it is Step.Disable }, r.review.switchOff)
        assertEquals(0, r.review.remove)
        assertTrue(r.review.switchOff > 10)
        assertTrue(r.review.newHomeScreen)

        // Switch off two apps the person installed, then ask for them to be deleted.
        v.setKeep(MiBoxFixture.UNKNOWN_USER_APP, false)
        v.setKeep("com.sonyliv", false)
        v.setDeleteUserApps(true)
        r = v.ready()
        assertEquals(expected(), r.plan)
        assertEquals(expected().count { it is Step.Disable }, r.review.switchOff)
        assertEquals(expected().count { it is Step.Uninstall }, r.review.remove)
        assertEquals(2, r.review.remove)

        v.setSpeed(SpeedOption.NEW_HOME_SCREEN, false)
        assertFalse(v.ready().review.newHomeScreen)
        assertTrue(v.ready().plan.none { it is Step.SwapLauncher })
    }

    @Test fun savedChoicesPrefillOnlyWhereTheTvAgrees() {
        val saved = SavedChoices(
            keep = mapOf("com.google.android.videos" to true, "com.netflix.ninja" to false, "tv.alphonso.alphonso_eula" to true),
            fasterAnimations = false, stopBackground = true, clearCaches = false, newHomeScreen = true,
        )
        val r = vm(saved = saved).ready()
        assertTrue(r.row("com.google.android.videos").keep)
        assertFalse(r.row("com.netflix.ninja").keep)
        assertFalse(r.row("tv.alphonso.alphonso_eula").keep) // locked stays locked
        assertFalse(r.choices.fasterAnimations)
        assertFalse(r.choices.clearCaches)
        assertFalse(r.choices.deleteUserApps) // never remembered: asked fresh every time
    }

    @Test fun appsRemovedForTheUserShowOffAndCanBeBroughtBack() {
        val inv = MiBoxFixture.inventory().let { it.copy(removedForUser = setOf("com.google.android.videos")) }
            .let { i -> i.copy(packages = i.packages.filterNot { it.name == "com.google.android.videos" }) }
        val v = vm(MiBoxFixture.loaded(inv))
        val row = v.ready().row("com.google.android.videos")
        assertFalse(row.keep)
        assertTrue(row.toggleable)
        assertTrue(v.ready().plan.none { it is Step.Disable && it.pkg == "com.google.android.videos" })
        v.setKeep("com.google.android.videos", true)
        assertTrue(Step.InstallExisting("com.google.android.videos") in v.ready().plan)
        assertEquals(1, v.ready().review.switchOn)
    }

    @Test fun loadFailureCanBeRetried() {
        var calls = 0
        val v = KeepViewModel(load = { if (calls++ == 0) error("no tv") else MiBoxFixture.loaded() }, savedChoices = { null })
        assertTrue(v.state.value is KeepUiState.Failed)
        v.retry()
        assertTrue(v.state.value is KeepUiState.Ready)
    }
}
