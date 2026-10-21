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
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Finds TVs on the phone's Wi-Fi: a port scan of the local /24 plus mDNS, merged by address. */
class Discovery(
    private val context: Context,
    private val scanner: SubnetScanner = SubnetScanner(SocketPortProbe),
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
        val seen = HashMap<String, FoundTv>()
        suspend fun offer(tv: FoundTv) {
            if (tv.host == phone?.first) return
            val old = seen[tv.host]
            val merged = when {
                old == null -> tv
                old.readiness == TvReadiness.READY || tv.readiness == TvReadiness.NEEDS_SETUP ->
                    old.copy(name = old.name ?: tv.name)
                else -> tv.copy(name = tv.name ?: old.name)
            }
            if (merged == old) return
            seen[tv.host] = merged
            send(merged)
        }

        val nsd = context.getSystemService(NsdManager::class.java)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val typeOf = HashMap<String, String>()

        if (nsd != null) {
            for (type in listOf(ADB_TYPE, REMOTE_TYPE)) {
                val l = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        typeOf[serviceInfo.serviceName] = type
                        found.trySend(serviceInfo)
                    }
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
                @Suppress("DEPRECATION")
                val host = resolved.host as? Inet4Address ?: continue // IPv4 only
                val readiness = if (typeOf[info.serviceName] == ADB_TYPE) TvReadiness.READY else TvReadiness.NEEDS_SETUP
                offer(FoundTv(host.hostAddress!!, readiness, resolved.serviceName))
            }
        }

        launch {
            if (phone != null) scanner.scan(scanner.hostsFor(phone.first, phone.second)).collect { offer(it) }
            delay(MDNS_GRACE_MS)
            close()
        }

        awaitClose {
            found.close()
            listeners.forEach { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
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
    }
}
