package dev.mntl.exbloattv.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Finds TVs on the phone's Wi-Fi: a port scan of the local /24 plus mDNS, merged by address. */
class Discovery(
    private val context: Context,
    private val probe: PortProbe = SocketPortProbe,
    private val scanner: SubnetScanner = SubnetScanner(probe),
) {

    /** The phone's Wi-Fi IPv4 address and prefix length, or null when not on Wi-Fi. */
    fun phoneWifiIpv4(): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val addr = cm.getLinkProperties(net)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address } ?: return null
        return addr.address.hostAddress!! to addr.prefixLength
    }

    fun scan(): Flow<FoundTv> = callbackFlow {
        val phone = phoneWifiIpv4()
        // Every source (subnet scan, mDNS) funnels through this one channel, so `seen` has one writer.
        val events = Channel<FoundTv>(Channel.UNLIMITED)
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val nsd = context.getSystemService(NsdManager::class.java)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        if (nsd != null) {
            for (type in listOf(ADB_TYPE, REMOTE_TYPE)) {
                val l = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.trySend(serviceInfo) }
                }
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                    listeners += l
                } catch (_: Exception) {
                    // mDNS unavailable; the subnet scan still works.
                }
            }
        }

        // NsdManager resolves one service at a time, so resolve sequentially.
        launch {
            for (info in found) {
                val resolved = nsd?.let { resolve(it, info) } ?: continue
                val host = resolved.host as? Inet4Address ?: continue // IPv4 only
                events.trySend(classifyMdnsHit(host.hostAddress!!, resolved.serviceName, probe))
            }
        }

        launch {
            if (phone != null) scanner.scan(scanner.hostsFor(phone.first, phone.second)).collect { events.trySend(it) }
            delay(MDNS_GRACE_MS)
            events.close()
        }

        launch {
            val seen = HashMap<String, FoundTv>()
            for (tv in events) {
                if (tv.host == phone?.first) continue
                val next = mergeFound(seen[tv.host], tv) ?: continue
                seen[tv.host] = next
                send(next)
            }
            close()
        }

        awaitClose {
            found.close()
            events.close()
            listeners.forEach { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveOnce(nsd, info) }

    @Suppress("DEPRECATION")
    private suspend fun resolveOnce(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private companion object {
        const val ADB_TYPE = "_adb._tcp."
        const val REMOTE_TYPE = "_androidtvremote2._tcp."
        const val MDNS_GRACE_MS = 3_000L
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}
