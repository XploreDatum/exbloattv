package dev.mntl.exbloattv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mntl.exbloattv.apps.AppLabel
import dev.mntl.exbloattv.catalog.CategorizedApp
import dev.mntl.exbloattv.catalog.Category
import dev.mntl.exbloattv.cleanup.Choices
import dev.mntl.exbloattv.cleanup.CleanupPlanner
import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.cleanup.defaultChoices
import dev.mntl.exbloattv.data.LoadedTv
import dev.mntl.exbloattv.data.SavedChoices
import dev.mntl.exbloattv.safety.SafetyGuard
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Keep-screen groups, top to bottom. Ads & tracking, launchers and system apps are never switch groups. */
val KEEP_GROUP_ORDER = listOf(
    Category.STREAMING, Category.MUSIC, Category.LIVE_TV, Category.FEATURES, Category.GOOGLE_EXTRAS,
    Category.MANUFACTURER, Category.SIDELOAD_TOOLS, Category.OTHER,
)

data class KeepRow(
    val pkg: String,
    val name: String,
    val description: String,
    val keep: Boolean,
    val toggleable: Boolean,
)

data class KeepGroup(val category: Category, val rows: List<KeepRow>)

/** A read-only row under "Show system apps". */
data class SystemRow(val pkg: String, val name: String)

/** The Review sheet's plain counts, all taken from the planner's steps. */
data class ReviewCounts(
    val switchOff: Int,
    val remove: Int,
    val switchOn: Int,
    val removeNames: List<String>,
    val newHomeScreen: Boolean,
    val restarts: Boolean,
    val nothingToDo: Boolean,
)

enum class SpeedOption { FASTER_ANIMATIONS, STOP_BACKGROUND, CLEAR_CACHES, NEW_HOME_SCREEN }

sealed interface KeepUiState {
    data object Loading : KeepUiState
    data class Failed(val error: Throwable) : KeepUiState
    data class Ready(
        val tv: LoadedTv,
        val groups: List<KeepGroup>,
        /** Ads & tracking: shown collapsed as "Always switched off (N)". */
        val alwaysOff: List<KeepRow>,
        val system: List<SystemRow>,
        val choices: Choices,
        val plan: List<Step>,
        val review: ReviewCounts,
        /** Fire TV keeps its own home screen in this version. */
        val homeScreenSwapAvailable: Boolean,
        /** Advanced: the person chose to decide about ads & tracking apps themselves (they still start off). */
        val adsUnlocked: Boolean = false,
    ) : KeepUiState
}

/**
 * The "Keep what you watch" screen. [load] reads the TV (or returns this session's copy); [savedChoices] is what the
 * person chose last time; [resolveLabel], if given, fills in real app names and icons one by one.
 */
class KeepViewModel(
    private val load: suspend () -> LoadedTv,
    private val savedChoices: suspend () -> SavedChoices?,
    private val resolveLabel: (suspend (CategorizedApp) -> AppLabel)? = null,
    /** Called when the person leaves the screen (back or on to cleaning): frees the session's TV connection. */
    private val onLeave: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<KeepUiState>(KeepUiState.Loading)
    val state: StateFlow<KeepUiState> = _state.asStateFlow()

    private val _labels = MutableStateFlow<Map<String, AppLabel>>(emptyMap())
    val labels: StateFlow<Map<String, AppLabel>> = _labels.asStateFlow()

    private var labelJob: Job? = null

    init {
        start()
    }

    override fun onCleared() = onLeave()

    fun retry() {
        if (_state.value is KeepUiState.Failed) start()
    }

    private fun start() {
        _state.value = KeepUiState.Loading
        viewModelScope.launch {
            try {
                val tv = load()
                val choices = prefill(tv.apps, savedChoices())
                _state.value = build(tv, choices)
                resolveLabels(tv)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = KeepUiState.Failed(e)
            }
        }
    }

    fun setKeep(pkg: String, keep: Boolean) = edit { r ->
        val app = effectiveApps(r.tv, r.adsUnlocked).firstOrNull { it.pkg.name == pkg } ?: return@edit null
        if (!isSwitch(app, r.adsUnlocked)) return@edit null
        r.choices.copy(keep = r.choices.keep + (pkg to keep))
    }

    /**
     * Advanced (spec §6: ads & tracking are "unlockable only in Advanced"): makes those rows switches. They stay off
     * until switched on; locking them again puts them back to off.
     */
    fun setAdsUnlocked(on: Boolean) {
        val r = _state.value as? KeepUiState.Ready ?: return
        if (r.adsUnlocked == on) return
        val ads = r.tv.apps.filter { it.category == Category.ADS_TRACKING }
        val keep = if (on) r.choices.keep else r.choices.keep + ads.associate { it.pkg.name to it.defaultKeep }
        _state.value = build(r.tv, r.choices.copy(keep = keep), on)
    }

    fun setSpeed(option: SpeedOption, on: Boolean) = edit { r ->
        when (option) {
            SpeedOption.FASTER_ANIMATIONS -> r.choices.copy(fasterAnimations = on)
            SpeedOption.STOP_BACKGROUND -> r.choices.copy(stopBackground = on)
            SpeedOption.CLEAR_CACHES -> r.choices.copy(clearCaches = on)
            SpeedOption.NEW_HOME_SCREEN -> if (r.homeScreenSwapAvailable) r.choices.copy(newHomeScreen = on) else null
        }
    }

    fun setDeleteUserApps(on: Boolean) = edit { it.choices.copy(deleteUserApps = on) }

    /** Stops pulling icons from the TV (before a cleanup takes over the connection). */
    fun stopLabels() {
        labelJob?.cancel()
    }

    private inline fun edit(change: (KeepUiState.Ready) -> Choices?) {
        val r = _state.value as? KeepUiState.Ready ?: return
        val next = change(r) ?: return
        if (next != r.choices) _state.value = build(r.tv, next, r.adsUnlocked)
    }

    private fun resolveLabels(tv: LoadedTv) {
        val resolve = resolveLabel ?: return
        labelJob?.cancel()
        labelJob = viewModelScope.launch {
            // Switch rows first, in screen order; nothing else is shown with an icon.
            val shown = KEEP_GROUP_ORDER.flatMap { c -> tv.apps.filter { it.category == c && isVisible(it) } }
            for (app in shown) {
                val label = try {
                    resolve(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                _labels.update { it + (app.pkg.name to label) }
            }
        }
    }

    companion object {
        private fun isVisible(app: CategorizedApp) = !SafetyGuard.isRemoteInput(app.pkg.name)

        private fun isSwitch(app: CategorizedApp, adsUnlocked: Boolean = false) =
            isVisible(app) && !app.locked && (app.category in KEEP_GROUP_ORDER || adsUnlocked && app.category == Category.ADS_TRACKING)

        /** With ads unlocked their rows are plain (unlocked) apps, so the planner follows their switches. */
        private fun effectiveApps(tv: LoadedTv, adsUnlocked: Boolean): List<CategorizedApp> =
            if (!adsUnlocked) tv.apps
            else tv.apps.map { if (it.category == Category.ADS_TRACKING && it.locked) it.copy(locked = false) else it }

        /**
         * Starts from the TV's state; previous choices fill in the switches where the TV agrees (an app that is off
         * on the TV now shows off). Locked rows keep their default. "Delete apps I installed" is never remembered.
         */
        fun prefill(apps: List<CategorizedApp>, saved: SavedChoices?): Choices {
            val base = defaultChoices(apps)
            if (saved == null) return base
            val keep = base.keep.toMutableMap()
            for (app in apps) {
                if (!isSwitch(app) || !app.pkg.enabled) continue
                saved.keep[app.pkg.name]?.let { keep[app.pkg.name] = it }
            }
            return base.copy(
                keep = keep,
                fasterAnimations = saved.fasterAnimations,
                stopBackground = saved.stopBackground,
                clearCaches = saved.clearCaches,
                newHomeScreen = saved.newHomeScreen,
            )
        }

        fun build(tv: LoadedTv, choices: Choices, adsUnlocked: Boolean = false): KeepUiState.Ready {
            val apps = effectiveApps(tv, adsUnlocked)
            val visible = apps.filter { isVisible(it) }
            fun row(app: CategorizedApp) = KeepRow(
                pkg = app.pkg.name,
                name = app.name,
                description = app.description,
                keep = if (app.locked) app.defaultKeep else choices.keep[app.pkg.name] ?: app.defaultKeep,
                toggleable = isSwitch(app, adsUnlocked),
            )
            val groups = KEEP_GROUP_ORDER.mapNotNull { c ->
                visible.filter { it.category == c }.takeIf { it.isNotEmpty() }?.let { KeepGroup(c, it.map(::row)) }
            }
            val alwaysOff = visible.filter { it.category == Category.ADS_TRACKING }.map(::row)
            val system = visible.filter { it.category == Category.SYSTEM || it.category == Category.LAUNCHER }
                .sortedBy { it.name.lowercase() }
                .map { SystemRow(it.pkg.name, it.name) }
            val plan = CleanupPlanner.plan(apps, tv.inventory, choices, tv.removedForUser)
            val names = tv.apps.associate { it.pkg.name to it.name }
            val review = ReviewCounts(
                switchOff = plan.count { it is Step.Disable },
                remove = plan.count { it is Step.Uninstall },
                switchOn = plan.count { it is Step.Enable || it is Step.InstallExisting },
                removeNames = plan.filterIsInstance<Step.Uninstall>().map { names[it.pkg] ?: it.pkg },
                newHomeScreen = Step.SwapLauncher in plan,
                restarts = Step.RebootAndVerify in plan,
                nothingToDo = plan.isEmpty(),
            )
            return KeepUiState.Ready(
                tv = tv,
                groups = groups,
                alwaysOff = alwaysOff,
                system = system,
                choices = choices,
                plan = plan,
                review = review,
                homeScreenSwapAvailable = !tv.inventory.info.manufacturer.equals("Amazon", ignoreCase = true),
                adsUnlocked = adsUnlocked,
            )
        }
    }
}
