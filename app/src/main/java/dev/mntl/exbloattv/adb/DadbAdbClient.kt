package dev.mntl.exbloattv.adb

import android.content.Context
import android.os.Build
import dadb.AdbKeyPair
import dadb.AdbOperationFailedException
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DadbAdbClient(private val dadb: Dadb, override val host: String) : AdbClient {

    override suspend fun shell(cmd: String): ShellResult = io {
        val r = dadb.shell(cmd)
        ShellResult(r.allOutput, r.exitCode)
    }

    override suspend fun install(apk: File): ShellResult {
        requireLocalFile(apk)
        return when (val r = io { dadb.install(apk) }) {
            is InstallResult.Success -> ShellResult("Success", 0)
            is InstallResult.Failure -> ShellResult(r.reason, 1)
        }
    }

    override suspend fun push(local: File, remote: String) {
        requireLocalFile(local)
        val r = io { dadb.push(local, remote) }
        if (r is SyncResult.Failure) throw AdbError.TransferFailed("push to $remote: ${r.reason}")
    }

    override suspend fun pull(remote: String, local: File) {
        val parent = local.absoluteFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw AdbError.LocalFile("cannot create ${parent.path}")
        }
        val r = io { dadb.pull(local, remote) }
        if (r is SyncResult.Failure) throw AdbError.TransferFailed("pull of $remote: ${r.reason}")
    }

    /** The TV drops the connection while rebooting, so a lost connection here means it worked. */
    override suspend fun reboot() {
        try {
            io { dadb.shell("reboot") }
        } catch (_: AdbError.Disconnected) {
        }
    }

    override fun close() = dadb.close()

    private fun requireLocalFile(f: File) {
        if (!f.isFile) throw AdbError.LocalFile("${f.path} does not exist")
    }

    /** Only transport failures become connection errors; local-file problems stay distinct. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw AdbError.LocalFile(e.message ?: "file not found")
        } catch (e: IllegalArgumentException) {
            throw AdbError.LocalFile(e.message ?: "invalid file")
        } catch (e: SecurityException) {
            throw AdbError.LocalFile(e.message ?: "no access")
        } catch (e: AdbOperationFailedException) {
            throw AdbError.TransferFailed(e.message ?: "operation failed")
        } catch (e: IOException) {
            throw mapAdbException(e)
        }
    }
}

class DadbConnector internal constructor(
    private val keys: () -> AdbKeyPair,
    private val factory: (host: String, port: Int, keys: AdbKeyPair) -> Dadb = { h, p, k ->
        Dadb.create(h, p, k, CONNECT_TIMEOUT_MS, 0)
    },
) : AdbConnector {

    constructor(context: Context) : this({
        AdbKeys.loadOrCreate(File(context.filesDir, "adb"), "exbloattv@${Build.MODEL}")
    })

    override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient {
        val keyPair = withContext(Dispatchers.IO) { keys() }
        val dadb = factory(host, port, keyPair)
        // dadb connects lazily; this trivial command forces the handshake. The blocking read cannot be
        // interrupted, so the work runs in a detached scope and we close dadb ourselves the moment we
        // time out or are cancelled; closing the socket is what unblocks the reader thread.
        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { dadb.shell("echo exbloattv") }
        try {
            withTimeout(authTimeoutMs) { work.await() }
        } catch (e: Throwable) {
            work.cancel()
            runCatching { dadb.close() }
            if (e is TimeoutCancellationException) throw AdbError.Unauthorized()
            if (e is CancellationException) throw e
            throw mapAdbException(e, duringHandshake = true)
        }
        return DadbAdbClient(dadb, host)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
