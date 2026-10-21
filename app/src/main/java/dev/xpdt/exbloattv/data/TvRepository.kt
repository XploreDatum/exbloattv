package dev.xpdt.exbloattv.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A TV this phone has worked with. Recognised by [serial]; [lastHost] follows the TV when its address changes. */
@Serializable
data class RememberedTv(
    val serial: String,
    val name: String,
    val lastHost: String,
    val lastCleanedAt: Long? = null,
)

/** The switches the person chose last time on a TV ("Clean up again" starts from these). Delete is never remembered. */
@Serializable
data class SavedChoices(
    val keep: Map<String, Boolean>,
    val fasterAnimations: Boolean = true,
    val stopBackground: Boolean = true,
    val clearCaches: Boolean = true,
    val newHomeScreen: Boolean = true,
)

/** A cleanup or undo that was running; still present after the app restarts means it was cut off. */
@Serializable
data class ActiveJob(
    val serial: String,
    val kind: Kind,
    val startedAt: Long,
    val tvName: String,
    val host: String,
) {
    enum class Kind { CLEAN, UNDO }
}

private val Context.tvStore: DataStore<Preferences> by preferencesDataStore(name = "tvs")

/** Remembered TVs, last choices per TV and this phone's identity, kept in DataStore. */
class TvRepository(
    private val store: DataStore<Preferences>,
    private val model: String = Build.MODEL ?: "Phone",
    private val now: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context) : this(context.applicationContext.tvStore)

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(RememberedTv.serializer())

    val tvs: Flow<List<RememberedTv>> = store.data.map { prefs -> decodeTvs(prefs[TVS]) }

    suspend fun tv(serial: String): RememberedTv? = tvs.first().firstOrNull { it.serial == serial }

    /** Adds the TV or updates its address; an existing name and last-cleaned time are kept. */
    suspend fun remember(serial: String, name: String, host: String): RememberedTv {
        var result: RememberedTv? = null
        store.edit { prefs ->
            val list = decodeTvs(prefs[TVS])
            val old = list.firstOrNull { it.serial == serial }
            val next = old?.copy(lastHost = host) ?: RememberedTv(serial, name, host)
            result = next
            prefs[TVS] = encode(list.filterNot { it.serial == serial } + next)
        }
        return result!!
    }

    suspend fun markCleaned(serial: String, at: Long) = update(serial) { it.copy(lastCleanedAt = at) }

    /** Everything on record was undone: the TV is no longer "cleaned", so it says "Never cleaned" again. */
    suspend fun markUndone(serial: String) = update(serial) { it.copy(lastCleanedAt = null) }

    suspend fun forget(serial: String) {
        store.edit { prefs ->
            prefs[TVS] = encode(decodeTvs(prefs[TVS]).filterNot { it.serial == serial })
            prefs.remove(choicesKey(serial))
            if (decodeJob(prefs[ACTIVE_JOB])?.serial == serial) prefs.remove(ACTIVE_JOB)
        }
    }

    /** The job running now, or one the app was killed in the middle of (when nothing is running). */
    val activeJob: Flow<ActiveJob?> = store.data.map { prefs -> decodeJob(prefs[ACTIVE_JOB])?.takeUnless { isStale(it) } }

    /** Drops a record too old to mean anything (no job runs anywhere near this long), or one that can't be read. */
    suspend fun clearStaleActiveJob() {
        store.edit { prefs ->
            val raw = prefs[ACTIVE_JOB] ?: return@edit
            val job = decodeJob(raw)
            if (job == null || isStale(job)) prefs.remove(ACTIVE_JOB)
        }
    }

    private fun decodeJob(raw: String?): ActiveJob? =
        raw?.let { runCatching { json.decodeFromString(ActiveJob.serializer(), it) }.getOrNull() }

    private fun isStale(job: ActiveJob) = now() - job.startedAt > ACTIVE_JOB_MAX_AGE_MS

    suspend fun setActiveJob(job: ActiveJob) {
        store.edit { it[ACTIVE_JOB] = json.encodeToString(ActiveJob.serializer(), job) }
    }

    suspend fun clearActiveJob() {
        store.edit { it.remove(ACTIVE_JOB) }
    }

    /** Whether "Nearby devices" was asked for once already (never ask twice). */
    suspend fun nearbyAsked(): Boolean = store.data.first()[NEARBY_ASKED] == "1"

    suspend fun setNearbyAsked() {
        store.edit { it[NEARBY_ASKED] = "1" }
    }

    suspend fun saveChoices(serial: String, choices: SavedChoices) {
        store.edit { it[choicesKey(serial)] = json.encodeToString(SavedChoices.serializer(), choices) }
    }

    suspend fun savedChoices(serial: String): SavedChoices? =
        store.data.first()[choicesKey(serial)]?.let { runCatching { json.decodeFromString(SavedChoices.serializer(), it) }.getOrNull() }

    /**
     * How this phone signs its changes on the TV: "<model> · <4 chars of a random install id>", so two phones of the
     * same model are told apart in the lock and the change log.
     */
    suspend fun phoneName(): String {
        var id = store.data.first()[INSTALL_ID]
        if (id == null) {
            store.edit { prefs ->
                id = prefs[INSTALL_ID] ?: UUID.randomUUID().toString().replace("-", "").also { prefs[INSTALL_ID] = it }
            }
        }
        return "$model · ${id!!.take(4)}"
    }

    private suspend fun update(serial: String, change: (RememberedTv) -> RememberedTv) {
        store.edit { prefs ->
            prefs[TVS] = encode(decodeTvs(prefs[TVS]).map { if (it.serial == serial) change(it) else it })
        }
    }

    private fun decodeTvs(raw: String?): List<RememberedTv> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()

    private fun encode(list: List<RememberedTv>) = json.encodeToString(listSerializer, list)

    private fun choicesKey(serial: String) = stringPreferencesKey("choices:$serial")

    private companion object {
        val TVS = stringPreferencesKey("remembered_tvs")
        val INSTALL_ID = stringPreferencesKey("install_id")
        val ACTIVE_JOB = stringPreferencesKey("active_job")
        val NEARBY_ASKED = stringPreferencesKey("nearby_asked")
        const val ACTIVE_JOB_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
