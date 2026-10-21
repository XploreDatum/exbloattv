package dev.mntl.exbloattv.adb

import com.google.common.truth.Truth.assertThat
import dadb.Dadb
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the real dadb stack against local sockets; no TV involved. */
class DadbConnectorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val servers = mutableListOf<ServerSocket>()
    private val accepted = mutableListOf<java.net.Socket>()

    private lateinit var connector: DadbConnector

    @Before fun setUp() {
        val dir = File(tmp.root, "adb")
        connector = DadbConnector({ AdbKeys.loadOrCreate(dir, "test") })
    }

    @After fun tearDown() {
        accepted.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    /** Accepts connections and never replies, like a TV waiting for "Allow USB debugging?". */
    private fun silentServer(): Int {
        val s = ServerSocket(0).also { servers += it }
        Thread { while (!s.isClosed) runCatching { accepted += s.accept() } }.apply { isDaemon = true }.start()
        return s.localPort
    }

    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun refusedPortIsRefused() = runBlocking {
        val e = runCatching { connector.connect("127.0.0.1", deadPort(), 5_000) }.exceptionOrNull()
        assertThat(e).isInstanceOf(AdbError.Refused::class.java)
    }

    @Test fun silentTvGivesUnauthorizedAfterAuthTimeoutNotLater() = runBlocking {
        val port = silentServer()
        val start = System.nanoTime()
        val e = runCatching { connector.connect("127.0.0.1", port, 700) }.exceptionOrNull()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertThat(e).isInstanceOf(AdbError.Unauthorized::class.java)
        assertThat(ms).isAtLeast(600L)
        assertThat(ms).isLessThan(3_000L)
    }

    @Test fun timeoutReleasesTheClientSocketBeforeAnyTeardown() = runBlocking {
        val port = silentServer()
        runCatching { connector.connect("127.0.0.1", port, 500) }
        val server = accepted.single()
        server.soTimeout = 2_000
        // Drain what the client sent (its CNXN); the next read must see EOF/reset, not a read timeout.
        val end = runCatching { val i = server.getInputStream(); while (i.read(ByteArray(4096)) >= 0) { } }.exceptionOrNull()
        assertThat(end).isNotInstanceOf(java.net.SocketTimeoutException::class.java)
    }

    @Test fun cancellingTheCallerReleasesTheClientSocket() = runBlocking {
        val port = silentServer()
        val job = launch(Dispatchers.Default) { connector.connect("127.0.0.1", port, 60_000) }
        kotlinx.coroutines.delay(300)
        withTimeout(3_000) { job.cancelAndJoinCompat() }
        val server = accepted.single()
        server.soTimeout = 2_000
        val end = runCatching { val i = server.getInputStream(); while (i.read(ByteArray(4096)) >= 0) { } }.exceptionOrNull()
        assertThat(end).isNotInstanceOf(java.net.SocketTimeoutException::class.java)
    }

    @Test fun cancellingTheCallerReturnsPromptly() = runBlocking {
        val port = silentServer()
        val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            connector.connect("127.0.0.1", port, 60_000)
        }
        kotlinx.coroutines.delay(300)
        val start = System.nanoTime()
        withTimeout(3_000) { job.cancelAndJoinCompat() }
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000L)
    }

    @Test fun failedTransfersSurfaceAsAdbErrors() = runBlocking {
        val client = DadbAdbClient(StubDadb.syncFailing(), "stub")
        val apk = tmp.newFile("a.apk")
        val push = runCatching { client.push(apk, "/x") }.exceptionOrNull()
        val pull = runCatching { client.pull("/x", File(tmp.root, "o")) }.exceptionOrNull()
        assertThat(push).isInstanceOf(AdbError.TransferFailed::class.java)
        assertThat(pull).isInstanceOf(AdbError.TransferFailed::class.java)
    }

    @Test fun missingLocalFileIsLocalFileNotConnectionLost() = runBlocking {
        val client = DadbAdbClient(StubDadb.syncFailing(), "stub")
        val missing = File(tmp.root, "nope.apk")
        assertThat(runCatching { client.push(missing, "/x") }.exceptionOrNull())
            .isInstanceOf(AdbError.LocalFile::class.java)
        assertThat(runCatching { client.install(missing) }.exceptionOrNull())
            .isInstanceOf(AdbError.LocalFile::class.java)
    }

    @Test fun transportDropMidTransferIsDisconnected() = runBlocking {
        val client = DadbAdbClient(StubDadb.throwing(dadb.AdbConnectionClosedException("closed", null)), "stub")
        val apk = tmp.newFile("b.apk")
        assertThat(runCatching { client.push(apk, "/x") }.exceptionOrNull())
            .isInstanceOf(AdbError.Disconnected::class.java)
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinCompat() { cancel(); join() }
}

/** Minimal Dadb whose sync operations fail or throw; everything else is unused. */
private object StubDadb {
    fun syncFailing(): Dadb = proxy { name ->
        when (name) {
            "push", "pull" -> dadb.SyncResult.Failure("device said no")
            else -> error("unexpected $name")
        }
    }

    fun throwing(t: Throwable): Dadb = proxy { throw t }

    private fun proxy(handler: (String) -> Any?): Dadb = java.lang.reflect.Proxy.newProxyInstance(
        Dadb::class.java.classLoader, arrayOf(Dadb::class.java),
    ) { _, m, _ -> if (m.name == "close") null else handler(m.name) } as Dadb
}
