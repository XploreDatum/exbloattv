package dev.mntl.exbloattv.state

import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.ShellResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

/** The state file could not be written on the TV (non-zero exit from a write step). */
class TvStateWriteException(val step: String, val exitCode: Int, detail: String) :
    Exception("could not write TV state ($step, exit $exitCode): $detail")

/** The state file exists (or might) but could not be read; callers must not treat this as "empty". */
class TvStateReadException(val exitCode: Int, detail: String) :
    Exception("could not read TV state (exit $exitCode): $detail")

/** Another phone holds a fresh run lock. */
class TvLockedException(val byPhone: String, val at: Long) : Exception("TV is being cleaned by $byPhone")

/**
 * Reads and writes the shared state file on the TV. Everything is plain shell: `cat`, `mkdir -p`,
 * `echo <base64> | base64 -d > / >> file.tmp`, `mv`. Writes are atomic (temp file then `mv`).
 * [AdbError][dev.mntl.exbloattv.adb.AdbError] and cancellation propagate untouched.
 */
class TvStateStore(
    private val adb: AdbClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun load(serial: String): TvState = mutex.withLock { loadLocked(serial) }

    suspend fun save(state: TvState) = mutex.withLock { saveLocked(state) }

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
        val parsed = try {
            json.decodeFromString<TvState>(r.output)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        if (parsed != null && parsed.serial == serial) return parsed
        // Corrupted, or written for a different TV: keep it aside and start fresh.
        val aside = adb.shell("mv $FILE $FILE.bad-${now()}")
        if (aside.exitCode != 0) throw TvStateWriteException("set-aside", aside.exitCode, aside.output.trim().take(200))
        return TvState(serial = serial)
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
        private const val CHUNK = 32 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
