package dev.mntl.exbloattv.adb

import android.content.Context
import android.os.Build
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DadbAdbClient(private val dadb: Dadb, override val host: String) : AdbClient {

    override suspend fun shell(cmd: String): ShellResult = io {
        val r = dadb.shell(cmd)
        ShellResult(r.allOutput, r.exitCode)
    }

    override suspend fun install(apk: File): ShellResult {
        val r = io { dadb.install(apk) }
        return when (r) {
            is InstallResult.Success -> ShellResult("Success", 0)
            is InstallResult.Failure -> ShellResult(r.reason, 1)
        }
    }

    override suspend fun push(local: File, remote: String) {
        val r = io { dadb.push(local, remote) }
        if (r is SyncResult.Failure) throw IOException("push to $remote failed: ${r.reason}")
    }

    override suspend fun pull(remote: String, local: File) {
        val r = io { dadb.pull(local, remote) }
        if (r is SyncResult.Failure) throw IOException("pull of $remote failed: ${r.reason}")
    }

    /** The TV drops the connection while rebooting, so a lost connection here means it worked. */
    override suspend fun reboot() {
        try {
            io { dadb.shell("reboot") }
        } catch (_: AdbError.Disconnected) {
        }
    }

    override fun close() = dadb.close()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw mapAdbException(e)
        }
    }
}

class DadbConnector(private val context: Context) : AdbConnector {

    override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient {
        val keys = withContext(Dispatchers.IO) {
            AdbKeys.loadOrCreate(File(context.filesDir, "adb"), "exbloattv@${Build.MODEL}")
        }
        val dadb = Dadb.create(host, port, keys, CONNECT_TIMEOUT_MS, 0)
        try {
            probe(dadb, authTimeoutMs)
        } catch (e: Throwable) {
            runCatching { dadb.close() }
            throw if (e is CancellationException && e !is TimeoutCancellationException) e else toAdbError(e)
        }
        return DadbAdbClient(dadb, host)
    }

    /**
     * dadb connects lazily, so run a trivial command to force the handshake. A blocking read cannot
     * be cancelled, so on timeout the caller closes [dadb], which unblocks the reader thread.
     */
    private suspend fun probe(dadb: Dadb, authTimeoutMs: Long) = coroutineScope {
        val work = async(Dispatchers.IO) { dadb.shell("echo exbloattv") }
        withTimeout(authTimeoutMs) { work.await() }
    }

    private fun toAdbError(e: Throwable): AdbError =
        if (e is TimeoutCancellationException) AdbError.Unauthorized() else mapAdbException(e, duringHandshake = true)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
