package dev.xpdt.exbloattv.data

import dev.xpdt.exbloattv.adb.AdbClient
import dev.xpdt.exbloattv.adb.AdbConnector
import dev.xpdt.exbloattv.apps.AppLabel
import dev.xpdt.exbloattv.apps.AppLabelResolver
import dev.xpdt.exbloattv.catalog.Catalog
import dev.xpdt.exbloattv.catalog.CategorizedApp
import dev.xpdt.exbloattv.catalog.Categorizer
import dev.xpdt.exbloattv.state.TvStateStore
import dev.xpdt.exbloattv.state.TvStateTooNewException
import dev.xpdt.exbloattv.tv.InstalledPackage
import dev.xpdt.exbloattv.tv.Inventory
import dev.xpdt.exbloattv.tv.TvInfo
import dev.xpdt.exbloattv.tv.TvInspector
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Everything the Keep screen needs about one TV, read when the person connected ("before" numbers included). */
data class LoadedTv(
    val host: String,
    val name: String,
    val inventory: Inventory,
    val apps: List<CategorizedApp>,
    /** Apps removed for the TV's user (the planner brings kept ones back with install-existing). */
    val removedForUser: Set<String>,
) {
    val serial: String get() = inventory.info.serial
}

/** The remembered TV is gone from this phone (forgotten, or app data cleared). */
class UnknownTvException(serial: String) : Exception("no remembered TV $serial")

/**
 * Process-scoped holder of the TVs read in this session and their open connections (used for app names and icons
 * while the person makes their choices). Cleanup and Undo never use these connections: [dev.xpdt.exbloattv.service]
 * opens its own.
 */
class TvSession(
    private val connector: AdbConnector,
    private val categorizer: Categorizer,
    private val catalog: Catalog,
    private val repo: TvRepository,
    private val labels: AppLabelResolver,
    /** True when the phone is short of space: icons (which need the app pulled to the phone) are skipped. */
    private val lowStorage: () -> Boolean = { false },
) {
    private val loaded = ConcurrentHashMap<String, LoadedTv>()
    private val clients = ConcurrentHashMap<String, AdbClient>()

    /** Friendly names seen on the network (mDNS), by address. */
    val hostNames = ConcurrentHashMap<String, String>()

    /** Reads the TV over [client] (which this session then owns) and remembers it by serial. */
    suspend fun load(host: String, client: AdbClient): LoadedTv {
        val inv = TvInspector(client).read()
        val serial = inv.info.serial
        val name = repo.tv(serial)?.name ?: hostNames[host] ?: friendlyName(inv.info)
        repo.remember(serial, name, host)
        val tv = LoadedTv(host, name, inv, appsOf(inv, categorizer), inv.removedForUser)
        loaded[serial] = tv
        clients.put(serial, client)?.takeIf { it !== client }?.let { runCatching { it.close() } }
        return tv
    }

    /** Connects to the remembered address again and re-reads the TV (after the app was restarted, or "Clean up again"). */
    suspend fun reload(serial: String): LoadedTv {
        val tv = repo.tv(serial) ?: throw UnknownTvException(serial)
        val client = connector.connect(tv.lastHost)
        return try {
            load(tv.lastHost, client)
        } catch (e: Throwable) {
            runCatching { client.close() }
            throw e
        }
    }

    fun cached(serial: String): LoadedTv? = loaded[serial]

    /**
     * Read-only, before the person makes their choices: throws [TvStateTooNewException] if a newer version of the app
     * set this TV up. Any other problem (no connection, unreadable file) is left for the cleanup itself to report.
     */
    suspend fun requireStateNotTooNew(serial: String) {
        val client = clients[serial] ?: return
        try {
            TvStateStore(client).requireNotTooNew(serial)
        } catch (e: TvStateTooNewException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not this check's business; the cleanup's first step reads the file again and says what's wrong.
        }
    }

    /** Drops this session's copy, so the next Keep screen reads the TV again. */
    fun invalidate(serial: String) {
        loaded.remove(serial)
        release(serial)
    }

    /** Closes this session's connection to the TV, e.g. before a cleanup restarts it. */
    fun release(serial: String) {
        clients.remove(serial)?.let { runCatching { it.close() } }
    }

    /** A readable name and, if cheap, the real icon. Falls back to the catalog/prettified name when the TV is gone. */
    suspend fun label(serial: String, app: CategorizedApp): AppLabel {
        val client = clients[serial] ?: return AppLabel(app.name, null)
        if (lowStorage()) return AppLabel(app.name, null)
        return labels.resolve(client, app, null)
    }

    /** A plain name for a package, for screens that have no app list (Undo results, the change log). */
    fun appName(pkg: String, serial: String? = null): String =
        serial?.let { s -> loaded[s]?.apps?.firstOrNull { it.pkg.name == pkg }?.name }
            ?: catalog.match(pkg)?.name
            ?: AppLabelResolver.prettify(pkg)

    companion object {
        /**
         * The Keep list: the TV's apps plus those removed for its user, shown as switched off so they can be brought
         * back (spec §12a.3). Removal for the user is only ever done to system apps (user apps are switched off).
         */
        fun appsOf(inv: Inventory, categorizer: Categorizer): List<CategorizedApp> {
            val removed = inv.removedForUser.map { InstalledPackage(it, "", null, -1, isSystem = true, enabled = false) }
            return categorizer.categorize(if (removed.isEmpty()) inv else inv.copy(packages = inv.packages + removed))
        }

        /** "Xiaomi" + "MIBOX4" -> "Xiaomi MIBOX4"; a model that already names the brand is used alone. */
        fun friendlyName(info: TvInfo): String {
            val model = info.model.trim()
            val brand = info.brand.trim().replaceFirstChar { it.uppercase() }
            return when {
                model.isEmpty() -> brand.ifEmpty { "TV" }
                brand.isEmpty() || model.contains(brand, ignoreCase = true) -> model
                else -> "$brand $model"
            }
        }
    }
}
