package dev.xpdt.exbloattv.state

import dev.xpdt.exbloattv.adb.AdbClient
import dev.xpdt.exbloattv.adb.ShellResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64
import java.util.UUID

/** The state file could not be written on the TV (non-zero exit from a write step). */
class TvStateWriteException(val step: String, val exitCode: Int, detail: String) :
    Exception("could not write TV state ($step, exit $exitCode): $detail")

/** The state file exists (or might) but could not be read; callers must not treat this as "empty". */
class TvStateReadException(val exitCode: Int, detail: String) :
    Exception("could not read TV state (exit $exitCode): $detail")

/**
 * The state file was written by a newer version of ExBloat TV (a higher `version`, or valid JSON of our shape that
 * this version can't decode, such as a kind of change it doesn't know). It is never read as empty, set aside or
 * rewritten by this version: the person must update the app on this phone first.
 */
class TvStateTooNewException(val version: Int?) :
    Exception("TV state was written by a newer version of ExBloat TV (version ${version ?: "unknown"})")

/** Another phone holds a fresh run lock. */
class TvLockedException(val byPhone: String, val at: Long) : Exception("TV is being cleaned by $byPhone")

/**
 * Reads and writes the shared state file on the TV. Everything is plain shell: `cat`, `mkdir -p`,
 * `echo <base64> | base64 -d > / >> file.tmp`, `mv`. Writes are atomic (temp file then `mv`).
 * [AdbError][dev.xpdt.exbloattv.adb.AdbError] and cancellation propagate untouched.
 */
class TvStateStore(
    private val adb: AdbClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun load(serial: String): TvState = mutex.withLock { loadLocked(serial) }

    suspend fun save(state: TvState) = mutex.withLock { saveLocked(state) }

    /**
     * Read-only early check (e.g. before the person makes their choices): throws [TvStateTooNewException] if a newer
     * version of the app set this TV up. Anything else (no file, unreadable, damaged) is left for a run to handle.
     */
    suspend fun requireNotTooNew(serial: String) = mutex.withLock {
        val r = adb.shell("cat $FILE")
        if (r.exitCode == 0) {
            val p = parse(r.output)
            if (p is Parsed.TooNew) throw TvStateTooNewException(p.version)
        }
    }

    suspend fun acquireLock(serial: String, phone: String): Result<TvState> = mutex.withLock {
        val state = loadLocked(serial)
        val held = state.lock
        if (held != null && held.byPhone != phone && now() - held.at < LOCK_EXPIRY_MS) {
            return@withLock Result.failure(TvLockedException(held.byPhone, held.at))
        }
        val locked = state.copy(lock = Lock(phone, now()))
        saveLocked(locked)
        // Two phones can read at the same moment; the last writer wins, so confirm it was us.
        val back = loadLocked(serial)
        if (back.lock?.byPhone != phone) {
            val other = back.lock
            return@withLock Result.failure(TvLockedException(other?.byPhone ?: "another phone", other?.at ?: now()))
        }
        Result.success(back)
    }

    suspend fun releaseLock(serial: String, phone: String) = mutex.withLock {
        val state = loadLocked(serial)
        if (state.lock?.byPhone == phone) saveLocked(state.copy(lock = null))
    }

    suspend fun appendChange(serial: String, change: Change) = mutex.withLock {
        val state = loadLocked(serial)
        saveLocked(state.copy(changes = state.changes + change))
    }

    /** Replaces the change with the same id in place (e.g. to mark it verified), or appends it if there is none. */
    suspend fun upsertChange(serial: String, change: Change) = mutex.withLock {
        val state = loadLocked(serial)
        val i = state.changes.indexOfFirst { it.id == change.id }
        val changes = if (i < 0) state.changes + change else state.changes.toMutableList().also { it[i] = change }
        saveLocked(state.copy(changes = changes))
    }

    /** Drops the changes with these ids (e.g. a rolled-back step), keeping everything else; writes only if one matched. */
    suspend fun removeChanges(serial: String, ids: Set<String>) = mutex.withLock {
        val state = loadLocked(serial)
        val kept = state.changes.filterNot { it.id in ids }
        if (kept.size != state.changes.size) saveLocked(state.copy(changes = kept))
    }

    suspend fun setOriginalIfAbsent(serial: String, rp: RestorePoint) = mutex.withLock {
        val state = loadLocked(serial)
        if (state.original == null) saveLocked(state.copy(original = rp))
    }

    private suspend fun loadLocked(serial: String): TvState {
        val r = adb.shell("cat $FILE")
        if (r.exitCode != 0) {
            if (r.output.contains("No such file", ignoreCase = true)) return TvState(serial = serial)
            throw TvStateReadException(r.exitCode, r.output.trim().take(200))
        }
        when (val p = parse(r.output)) {
            is Parsed.TooNew -> throw TvStateTooNewException(p.version)
            is Parsed.Ok -> if (p.state.serial == serial) return p.state
            Parsed.NotOurs -> Unit
        }
        // Not JSON / not our file at all, or written for a different TV: keep it aside and start fresh.
        val aside = adb.shell("mv $FILE $FILE.bad-${now()}")
        if (aside.exitCode != 0) throw TvStateWriteException("set-aside", aside.exitCode, aside.output.trim().take(200))
        return TvState(serial = serial)
    }

    private sealed interface Parsed {
        data class Ok(val state: TvState) : Parsed
        data class TooNew(val version: Int?) : Parsed
        data object NotOurs : Parsed
    }

    /**
     * Lenient first: only text that isn't a JSON object with a string `serial` is "not ours". Anything of our shape
     * with a higher (or unreadable) `version`, or that doesn't decode (a newer app's change kind, a field whose type
     * changed), was written by a newer app and must never be replaced.
     */
    private fun parse(text: String): Parsed {
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Parsed.NotOurs
        val serial = obj["serial"] as? JsonPrimitive
        if (serial == null || !serial.isString) return Parsed.NotOurs
        val version = when (val v = obj["version"]) {
            null -> SUPPORTED_VERSION
            is JsonPrimitive -> if (v.isString) null else v.intOrNull
            else -> null
        }
        if (version == null || version > SUPPORTED_VERSION) return Parsed.TooNew(version)
        return try {
            Parsed.Ok(json.decodeFromJsonElement(TvState.serializer(), obj))
        } catch (e: Exception) {
            Parsed.TooNew(version)
        }
    }

    private suspend fun saveLocked(state: TvState) {
        val encoded = Base64.getEncoder().encodeToString(json.encodeToString(TvState.serializer(), state).toByteArray())
        // Unique per write so two phones writing at once never share a temp file.
        val tmp = "$FILE.tmp.${now()}-${UUID.randomUUID().toString().take(8)}"
        try {
            requireOk(adb.shell("mkdir -p $DIR"), "mkdir")
            // 32768 is a multiple of 4, so each chunk is independently valid base64.
            encoded.chunked(CHUNK).forEachIndexed { i, chunk ->
                requireOk(adb.shell("echo $chunk | base64 -d ${if (i == 0) ">" else ">>"} $tmp"), "write")
            }
            requireOk(adb.shell("mv $tmp $FILE"), "mv")
        } catch (e: Throwable) {
            // Best effort: never leave a half-written temp file behind, and never mask the real failure.
            try { withContext(NonCancellable) { adb.shell("rm -f $tmp") } } catch (_: Exception) {}
            throw e
        }
    }

    private fun requireOk(r: ShellResult, step: String) {
        if (r.exitCode != 0) throw TvStateWriteException(step, r.exitCode, r.output.trim().take(200))
    }

    companion object {
        const val DIR = "/data/local/tmp/exbloattv"
        const val FILE = "$DIR/state.json"
        const val LOCK_EXPIRY_MS = 10 * 60 * 1000L
        /**
         * The newest state-file `version` this app understands. A later version that adds anything this one must not
         * drop on a rewrite (unknown keys are ignored and so lost when this version writes) must raise it.
         */
        const val SUPPORTED_VERSION = 1
        private const val CHUNK = 32 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
