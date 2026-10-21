package dev.mntl.exbloattv.discovery

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

enum class TvReadiness { READY, NEEDS_SETUP }

data class FoundTv(val host: String, val readiness: TvReadiness, val name: String? = null)

fun interface PortProbe {
    suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean
}

/**
 * Real probe: a plain TCP connect with a hard timeout. [bind] routes the socket over the phone's Wi-Fi (see
 * [WifiNetworkProvider.bind]) so probes never go out over mobile data; it is a no-op in tests.
 */
class SocketPortProbe(private val bind: (Socket) -> Unit = {}) : PortProbe {
    override suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(ProbeDispatcher) {
            try {
                Socket().use {
                    bind(it)
                    it.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
}

/** Blocking connects each hold a thread; Dispatchers.IO alone caps at 64 and would serialize the scan. */
val ProbeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(256)

class SubnetScanner(private val probe: PortProbe, private val parallelism: Int = 64) {

    /** Usable hosts of the phone's network, never wider than its /24, minus the phone itself. */
    fun hostsFor(ipv4: String, prefix: Int): List<String> {
        val octets = ipv4.split('.').map { it.toInt() }
        require(octets.size == 4) { "not an IPv4 address: $ipv4" }
        val ip = octets.fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
        val p = prefix.coerceIn(24, 30)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return ((network + 1) until broadcast).filter { it != ip }.map { v ->
            "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
        }
    }

    fun scan(hosts: List<String>): Flow<FoundTv> = channelFlow {
        val gate = Semaphore(parallelism)
        for (host in hosts) {
            launch {
                gate.withPermit {
                    classify(host)?.let { send(it) }
                }
            }
        }
    }

    /** 5555 first; the cast/remote ports are only tried (concurrently) when it is closed. */
    private suspend fun classify(host: String): FoundTv? {
        if (probe.isOpen(host, ADB_PORT, TIMEOUT_MS)) return FoundTv(host, TvReadiness.READY)
        val anySetup = coroutineScope {
            SETUP_PORTS.map { port -> async { probe.isOpen(host, port, TIMEOUT_MS) } }.awaitAll().any { it }
        }
        return if (anySetup) FoundTv(host, TvReadiness.NEEDS_SETUP) else null
    }

    companion object {
        const val ADB_PORT = 5555
        val SETUP_PORTS = listOf(6466, 6467, 8008, 8009)
        const val TIMEOUT_MS = 800
    }
}
