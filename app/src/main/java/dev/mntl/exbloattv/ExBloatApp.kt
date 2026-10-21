package dev.mntl.exbloattv

import android.app.Application
import dev.mntl.exbloattv.adb.DadbConnector
import dev.mntl.exbloattv.apps.AppLabelResolver
import dev.mntl.exbloattv.catalog.Catalog
import dev.mntl.exbloattv.catalog.Categorizer
import dev.mntl.exbloattv.cleanup.ApkDownloader
import dev.mntl.exbloattv.cleanup.LauncherSwap
import dev.mntl.exbloattv.data.ApkArchiveParser
import dev.mntl.exbloattv.data.TvRepository
import dev.mntl.exbloattv.data.TvSession
import dev.mntl.exbloattv.discovery.Discovery
import dev.mntl.exbloattv.service.CleanupService
import dev.mntl.exbloattv.service.CleanupWork
import dev.mntl.exbloattv.service.Notifications
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val connector = DadbConnector(app)
    val discovery = Discovery(app)
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
    ).also { it.startService = { CleanupService.start(app) } }

    private companion object {
        const val MIN_FREE_FOR_ICONS = 200L * 1024 * 1024
    }
}
