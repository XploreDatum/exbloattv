package dev.mntl.exbloattv.adb

import android.content.Context
import android.os.Build
import dadb.AdbKeyPair
import dadb.AdbOperationFailedException
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dev.mntl.exbloattv.discovery.bindOrDefault
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
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
        } catch (e: IllegalStateException) {
            // dadb/okio throw this ("closed") when the connection was closed under a call.
            throw AdbError.Disconnected()
        } catch (e: AdbOperationFailedException) {
            throw AdbError.TransferFailed(e.message ?: "operation failed")
        } catch (e: IOException) {
            throw mapAdbException(e)
        }
    }
}

/**
 * A [Dadb] over a socket and [AdbConnection] that this app owns. dadb's own `Dadb.create` keeps the socket
 * private until the handshake finishes, so a stuck handshake could not be aborted; holding the socket lets
 * [DadbConnector] close it directly. Everything except `open`/`supportsFeature`/`close` is dadb's default
 * implementation on top of `open`.
 */
internal class OwnedDadb(
    private val connection: RawAdbConnection,
    private val socket: Socket,
) : Dadb {
    override fun open(destination: String): AdbStream = connection.open(destination)
    override fun supportsFeature(feature: String): Boolean = connection.supportsFeature(feature)
    override fun close() {
        runCatching { connection.close() }
        runCatching { socket.close() }
    }
}

/**
 * [bind] is called on each new socket before it connects; the app uses it to route TV connections over the phone's
 * Wi-Fi even when Android's default network is mobile data. The default leaves the socket unbound; a [bind] that
 * throws leaves it on default routing (see [bindOrDefault]).
 */
class DadbConnector internal constructor(
    private val keys: () -> AdbKeyPair,
    private val bind: (Socket) -> Unit = {},
) : AdbConnector {

    constructor(context: Context, bind: (Socket) -> Unit = {}) : this({
        AdbKeys.loadOrCreate(File(context.filesDir, "adb"), "exbloattv@${Build.MODEL}")
    }, bind)

    override suspend fun connect(host: String, port: Int, authTimeoutMs: Long): AdbClient {
        val keyPair = withContext(Dispatchers.IO) { keys() }
        val socket = Socket()
        // Before connecting and outside any error mapping: a refused bind (VPN, stale network) keeps default routing.
        withContext(Dispatchers.IO) { bindOrDefault(socket, bind) }
        // Only a failure after the TCP connection is up can mean "not approved on the TV yet".
        val inHandshake = AtomicBoolean(false)
        // The handshake blocks until the person accepts the key on the TV and a blocking read cannot be
        // interrupted. We own the socket, so on timeout/cancel/error we close it, which fails the read
        // immediately and frees the thread; a late approval then finds a closed socket.
        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            inHandshake.set(true)
            socket.keepAlive = true
            RawAdbConnection.connect(socket, keyPair)
        }
        val connection = try {
            withTimeout(authTimeoutMs) { work.await() }
        } catch (e: Throwable) {
            work.cancel()
            runCatching { socket.close() }
            if (e is TimeoutCancellationException) throw if (inHandshake.get()) AdbError.Unauthorized() else AdbError.Timeout()
            if (e is CancellationException) throw e
            throw mapAdbException(e, duringHandshake = inHandshake.get())
        }
        return DadbAdbClient(OwnedDadb(connection, socket), host)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
