package dev.xpdt.exbloattv

import android.app.Application
import dev.xpdt.exbloattv.adb.DadbConnector
import dev.xpdt.exbloattv.apps.AppLabelResolver
import dev.xpdt.exbloattv.catalog.Catalog
import dev.xpdt.exbloattv.catalog.Categorizer
import dev.xpdt.exbloattv.cleanup.ApkDownloader
import dev.xpdt.exbloattv.cleanup.LauncherSwap
import dev.xpdt.exbloattv.data.ActiveJob
import dev.xpdt.exbloattv.data.ApkArchiveParser
import dev.xpdt.exbloattv.data.TvRepository
import dev.xpdt.exbloattv.data.TvSession
import dev.xpdt.exbloattv.discovery.Discovery
import dev.xpdt.exbloattv.discovery.PortProbe
import dev.xpdt.exbloattv.discovery.SocketPortProbe
import dev.xpdt.exbloattv.discovery.WifiNetworkProvider
import dev.xpdt.exbloattv.service.CleanupService
import dev.xpdt.exbloattv.service.CleanupWork
import dev.xpdt.exbloattv.service.JobJournal
import dev.xpdt.exbloattv.service.Notifications
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class ExBloatApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
    }
}

/** The app's long-lived objects, built once per process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository = TvRepository(app)
    /** The phone's Wi-Fi, even when it isn't Android's default network; every TV socket is bound to it. */
    val wifi = WifiNetworkProvider(app)
    val portProbe: PortProbe = SocketPortProbe(wifi::bind)
    val connector = DadbConnector(app, wifi::bind)
    val discovery = Discovery(app, wifi, portProbe)
    val catalog: Catalog by lazy { app.assets.open("catalog.json").bufferedReader().use { Catalog.parse(it.readText()) } }
    private val categorizer by lazy { Categorizer(catalog) }
    private val http by lazy { OkHttpClient() }
    private val downloader by lazy { ApkDownloader(http, File(app.cacheDir, "apk")) }
    private val labels by lazy {
        val parser = ApkArchiveParser(app, File(app.cacheDir, "parsed"))
        AppLabelResolver(app.cacheDir) { parser.parse(it) }
    }
    val session: TvSession by lazy { TvSession(connector, categorizer, catalog, repository, labels) { app.cacheDir.usableSpace < MIN_FREE_FOR_ICONS } }
    val work = CleanupWork(
        connector = connector,
        phoneName = { repository.phoneName() },
        swapFor = { store, phone -> LauncherSwap(downloader, store, phone) },
        appScope = scope,
        onCleaned = { serial, at -> repository.markCleaned(serial, at) },
        onUndoneAll = { serial -> repository.markUndone(serial) },
        journal = object : JobJournal {
            override suspend fun started(job: ActiveJob) = repository.setActiveJob(job)
            override suspend fun ended() = repository.clearActiveJob()
        },
    ).also { it.startService = { CleanupService.start(app) } }

    init {
        scope.launch { runCatching { repository.clearStaleActiveJob() } }
    }

    private companion object {
        const val MIN_FREE_FOR_ICONS = 200L * 1024 * 1024
    }
}
