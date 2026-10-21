package dev.mntl.exbloattv.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One network as seen by the phone: is it Wi-Fi, and its addresses with prefix lengths. */
data class NetworkCandidate<N>(val network: N, val isWifi: Boolean, val addresses: List<Pair<InetAddress, Int>>)

/** The Wi-Fi network the TVs are on, with the phone's IPv4 address and prefix length there. */
data class WifiLink<N>(val network: N, val ipv4: String, val prefix: Int)

/**
 * Picks the phone's Wi-Fi from all its networks. Wi-Fi does not have to be the default network or have internet
 * (a home router without internet, or a phone that prefers mobile data, still reaches the TV over Wi-Fi). The default
 * network wins when it is Wi-Fi; otherwise the first Wi-Fi with a usable IPv4 address.
 */
fun <N> chooseWifi(candidates: List<NetworkCandidate<N>>, default: N?): WifiLink<N>? {
    val usable = candidates.filter { it.isWifi }.mapNotNull { c -> pickIpv4(c.addresses)?.let { WifiLink(c.network, it.first, it.second) } }
    return usable.firstOrNull { it.network == default } ?: usable.firstOrNull()
}

/** The first IPv4 address that isn't loopback or link-local (169.254/16), preferring private (site-local) ones. */
fun pickIpv4(addresses: List<Pair<InetAddress, Int>>): Pair<String, Int>? {
    val v4 = addresses.filter { (a, _) -> a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress }
    val best = v4.firstOrNull { it.first.isSiteLocalAddress } ?: v4.firstOrNull() ?: return null
    return best.first.hostAddress!! to best.second
}

/**
 * Tracks the phone's Wi-Fi network, whether or not Android uses it as the default (it may not be validated, or may
 * have no internet). [current] updates as Wi-Fi comes and goes; [bind] routes a socket over it, so TV connections
 * never go out over mobile data.
 */
class WifiNetworkProvider(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _current = MutableStateFlow<WifiLink<Network>?>(null)
    val current: StateFlow<WifiLink<Network>?> = _current.asStateFlow()

    init {
        refresh()
        cm?.let { manager ->
            // Listen only (never asks Android to bring a network up). No INTERNET/VALIDATED requirement.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching {
                manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { refresh() }
                    override fun onLost(network: Network) { refresh() }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
                    override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) { refresh() }
                })
            }
        }
    }

    /** Looks again now (e.g. "I'm on Wi-Fi now", or the screen resumed) and returns the result. */
    fun refresh(): WifiLink<Network>? {
        val manager = cm ?: return null.also { _current.value = null }
        @Suppress("DEPRECATION")
        val candidates = runCatching { manager.allNetworks.toList() }.getOrDefault(emptyList()).map { n ->
            val caps = runCatching { manager.getNetworkCapabilities(n) }.getOrNull()
            val addrs = runCatching { manager.getLinkProperties(n)?.linkAddresses }.getOrNull().orEmpty()
                .map { it.address to it.prefixLength }
            NetworkCandidate(n, caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true, addrs)
        }
        val link = chooseWifi(candidates, runCatching { manager.activeNetwork }.getOrNull())
        _current.value = link
        return link
    }

    /** Routes [socket] over the Wi-Fi network; leaves it unbound (default routing) when there is no Wi-Fi. */
    fun bind(socket: Socket) {
        val network = _current.value?.network ?: refresh()?.network ?: return
        network.bindSocket(socket)
    }
}
