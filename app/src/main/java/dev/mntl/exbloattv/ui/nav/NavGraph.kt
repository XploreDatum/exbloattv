package dev.mntl.exbloattv.ui.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.mntl.exbloattv.AppGraph
import dev.mntl.exbloattv.data.SavedChoices
import dev.mntl.exbloattv.discovery.SocketPortProbe
import dev.mntl.exbloattv.discovery.TvReadiness
import dev.mntl.exbloattv.service.CleanupRequest
import dev.mntl.exbloattv.service.UndoRequest
import dev.mntl.exbloattv.service.WorkState
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.ui.screens.ApproveScreen
import dev.mntl.exbloattv.ui.screens.ApproveViewModel
import dev.mntl.exbloattv.ui.screens.CleaningScreen
import dev.mntl.exbloattv.ui.screens.DebugStepsScreen
import dev.mntl.exbloattv.ui.screens.DoneScreen
import dev.mntl.exbloattv.ui.screens.FindScreen
import dev.mntl.exbloattv.ui.screens.FindViewModel
import dev.mntl.exbloattv.ui.screens.HomeScreen
import dev.mntl.exbloattv.ui.screens.HomeViewModel
import dev.mntl.exbloattv.ui.screens.KeepScreen
import dev.mntl.exbloattv.ui.screens.KeepViewModel
import dev.mntl.exbloattv.ui.screens.TvDetailsScreen
import dev.mntl.exbloattv.ui.screens.TvDetailsViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val FIND = "find"
    const val DEBUG = "debug/{host}"
    const val APPROVE = "approve/{host}"
    const val KEEP = "keep/{serial}"
    const val CLEANING = "cleaning/{serial}"
    const val DONE = "done/{serial}"
    const val TV = "tv/{serial}"

    fun debug(host: String) = "debug/$host"
    fun approve(host: String) = "approve/$host"
    fun keep(serial: String) = "keep/${enc(serial)}"
    fun cleaning(serial: String) = "cleaning/${enc(serial)}"
    fun done(serial: String) = "done/${enc(serial)}"
    fun tv(serial: String) = "tv/${enc(serial)}"

    /** Serials are opaque text from the TV; keep them path-safe. */
    private fun enc(s: String) = android.net.Uri.encode(s)
}

@Composable
fun ExBloatNavGraph(graph: AppGraph) {
    val nav = rememberNavController()
    val work by graph.work.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // The foreground notification needs this on Android 13+; the work runs either way.
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = viewModel { HomeViewModel(graph.repository.tvs, graph.repository.activeJob, SocketPortProbe) },
                work = work,
                onAddTv = { nav.navigate(Routes.FIND) },
                onOpenTv = { nav.navigate(Routes.tv(it)) },
                onOpenWork = { w ->
                    val serial = w.tvSerial ?: return@HomeScreen
                    nav.navigate(
                        when (w) {
                            is WorkState.Cleaning, is WorkState.CleanupStopped -> Routes.cleaning(serial)
                            is WorkState.Cleaned -> Routes.done(serial)
                            else -> Routes.tv(serial)
                        },
                    )
                },
                onEmpty = { nav.navigate(Routes.FIND) { popUpTo(Routes.HOME) { inclusive = true } } },
            )
        }
        composable(Routes.FIND) {
            var nearbyAsked by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) { nearbyAsked = graph.repository.nearbyAsked() }
            FindScreen(
                vm = viewModel {
                    FindViewModel(
                        remembered = graph.repository.tvs,
                        wifiAvailable = { graph.discovery.phoneWifiIpv4() != null },
                        scan = { graph.discovery.scan() },
                    )
                },
                onBack = backOrNull(nav),
                onPick = { row ->
                    row.name?.let { graph.session.hostNames[row.host] = it }
                    nav.navigate(if (row.readiness == TvReadiness.READY) Routes.approve(row.host) else Routes.debug(row.host))
                },
                onManualHost = { nav.navigate(Routes.approve(it)) },
                nearbyAsked = nearbyAsked,
                onNearbyAsked = {
                    nearbyAsked = true
                    graph.scope.launch { graph.repository.setNearbyAsked() }
                },
            )
        }
        composable(Routes.DEBUG) { entry ->
            val host = entry.arguments?.getString("host").orEmpty()
            DebugStepsScreen(
                host = host,
                tvName = graph.session.hostNames[host],
                probe = SocketPortProbe,
                onBack = { nav.popBackStack() },
                onReady = { nav.navigate(Routes.approve(host)) { popUpTo(Routes.DEBUG) { inclusive = true } } },
            )
        }
        composable(Routes.APPROVE) { entry ->
            val host = entry.arguments?.getString("host").orEmpty()
            ApproveScreen(
                vm = viewModel {
                    ApproveViewModel(
                        host = host,
                        connect = { h, timeout -> graph.connector.connect(h, authTimeoutMs = timeout) },
                        load = { h, client -> graph.session.load(h, client) },
                    )
                },
                onBack = { nav.popBackStack() },
                onNeedsSetup = { nav.navigate(Routes.debug(host)) { popUpTo(Routes.APPROVE) { inclusive = true } } },
                onLoaded = { serial -> nav.navigate(Routes.keep(serial)) { popUpTo(Routes.APPROVE) { inclusive = true } } },
            )
        }
        composable(Routes.KEEP) { entry ->
            val serial = entry.arguments?.getString("serial").orEmpty()
            val vm = viewModel {
                KeepViewModel(
                    load = { graph.session.cached(serial) ?: graph.session.reload(serial) },
                    savedChoices = { graph.repository.savedChoices(serial) },
                    resolveLabel = { app -> graph.session.label(serial, app) },
                    onLeave = { graph.session.release(serial) },
                )
            }
            KeepScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onFindAgain = { nav.navigate(Routes.FIND) },
                onStart = start@{ ready ->
                    if (graph.work.state.value.busy) return@start false
                    val labels = vm.labels.value
                    val request = CleanupRequest(
                        serial = ready.tv.serial,
                        host = ready.tv.host,
                        tvName = ready.tv.name,
                        plan = ready.plan,
                        before = ready.tv.inventory,
                        appNames = ready.tv.apps.associate { it.pkg.name to (labels[it.pkg.name]?.name ?: it.name) },
                    )
                    val switches = ready.groups.flatMap { it.rows }.filter { it.toggleable }.map { it.pkg }.toSet()
                    val c = ready.choices
                    graph.scope.launch {
                        graph.repository.saveChoices(
                            ready.tv.serial,
                            SavedChoices(c.keep.filterKeys { it in switches }, c.fasterAnimations, c.stopBackground, c.clearCaches, c.newHomeScreen),
                        )
                    }
                    if (!graph.work.startCleanup(request)) return@start false
                    // Only now: a refused start leaves the Keep screen working as it was.
                    vm.stopLabels()
                    graph.session.release(ready.tv.serial)
                    askForNotifications()
                    resetTo(nav, Routes.cleaning(ready.tv.serial))
                    true
                },
            )
        }
        composable(Routes.CLEANING) { entry ->
            val serial = entry.arguments?.getString("serial").orEmpty()
            val activeJob by graph.repository.activeJob.collectAsStateWithLifecycle(null)
            CleaningScreen(
                serial = serial,
                work = work,
                actions = graph.work,
                onDone = { resetTo(nav, Routes.done(serial)) },
                onHome = { goHome(nav) },
                onRecheck = {
                    graph.session.invalidate(serial)
                    nav.navigate(Routes.keep(serial)) { popUpTo(Routes.CLEANING) { inclusive = true } }
                },
                interrupted = activeJob?.takeIf { it.serial == serial },
                onOpenTv = { resetTo(nav, Routes.tv(serial)) },
                onUndo = { host, name ->
                    askForNotifications()
                    graph.work.startUndo(UndoRequest(serial, host, name))
                    resetTo(nav, Routes.tv(serial))
                },
                onAcknowledge = { graph.work.acknowledge(serial) },
            )
        }
        composable(Routes.DONE) { entry ->
            val serial = entry.arguments?.getString("serial").orEmpty()
            DoneScreen(
                serial = serial,
                work = work,
                onDone = {
                    graph.work.acknowledge(serial)
                    goHome(nav)
                },
                onUndo = { host, name ->
                    askForNotifications()
                    graph.work.startUndo(UndoRequest(serial, host, name))
                    resetTo(nav, Routes.tv(serial))
                },
            )
        }
        composable(Routes.TV) { entry ->
            val serial = entry.arguments?.getString("serial").orEmpty()
            TvDetailsScreen(
                vm = viewModel {
                    TvDetailsViewModel(
                        serial = serial,
                        tv = graph.repository.tvs.map { list -> list.firstOrNull { it.serial == serial } },
                        activeJob = graph.repository.activeJob,
                        phoneName = { graph.repository.phoneName() },
                        readState = { host, s ->
                            graph.connector.connect(host, authTimeoutMs = 15_000).use { TvStateStore(it).load(s) }
                        },
                        onLeave = {
                            val w = graph.work.state.value
                            if (w is WorkState.Undone || w is WorkState.UndoFailed) graph.work.acknowledge(serial)
                        },
                    )
                },
                work = work,
                nameOf = { graph.session.appName(it, serial) },
                onBack = { if (!nav.popBackStack()) goHome(nav) },
                onCleanAgain = { host ->
                    graph.session.invalidate(serial)
                    nav.navigate(Routes.approve(host))
                },
                onUndo = { host, name ->
                    askForNotifications()
                    graph.work.startUndo(UndoRequest(serial, host, name))
                },
                onOpenInStore = { host, pkg -> graph.work.openInPlayStore(host, pkg) },
                onSeeCleaning = { nav.navigate(Routes.cleaning(serial)) },
            )
        }
    }
}

private fun backOrNull(nav: NavHostController): (() -> Unit)? =
    if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null

private fun goHome(nav: NavHostController) {
    nav.navigate(Routes.HOME) {
        popUpTo(nav.graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** Home underneath, [route] on top: Back from a run or a result always lands on the TV list. */
private fun resetTo(nav: NavHostController, route: String) {
    goHome(nav)
    nav.navigate(route)
}
