package dev.xpdt.exbloattv.discovery

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkTest {
    private fun a(ip: String, prefix: Int) = InetAddress.getByName(ip) to prefix
    private fun c(name: String, wifi: Boolean, vararg addrs: Pair<InetAddress, Int>) = NetworkCandidate(name, wifi, addrs.toList())

    @Test fun wifiIsFoundEvenWhenMobileDataIsTheDefaultNetwork() {
        // The emulator case: Wi-Fi up but not validated, so Android's default stays cellular.
        val cell = c("cell", false, a("10.0.2.15", 24))
        val wifi = c("wlan0", true, a("fe80::1", 64), a("10.0.2.16", 24))
        assertEquals(WifiLink("wlan0", "10.0.2.16", 24), chooseWifi(listOf(cell, wifi), default = "cell"))
    }

    @Test fun theDefaultNetworkWinsWhenItIsWifi() {
        val w1 = c("w1", true, a("192.168.1.20", 24))
        val w2 = c("w2", true, a("192.168.10.5", 24))
        assertEquals("w2", chooseWifi(listOf(w1, w2), default = "w2")!!.network)
        assertEquals("w1", chooseWifi(listOf(w1, w2), default = null)!!.network)
    }

    @Test fun noWifiOrNoUsableAddressMeansNoWifi() {
        assertNull(chooseWifi(listOf(c("cell", false, a("10.0.0.2", 8))), default = "cell"))
        assertNull(chooseWifi(listOf(c("w", true, a("169.254.3.4", 16), a("fe80::2", 64))), default = null))
        assertNull(chooseWifi(emptyList<NetworkCandidate<String>>(), default = null))
    }

    @Test fun privateIpv4IsPreferred() {
        assertEquals("192.168.1.9" to 24, pickIpv4(listOf(a("100.64.0.9", 10), a("192.168.1.9", 24))))
        assertEquals("100.64.0.9" to 10, pickIpv4(listOf(a("100.64.0.9", 10))))
        assertNull(pickIpv4(listOf(a("127.0.0.1", 8))))
    }

    @Test fun probeBindsEverySocketBeforeConnecting() = runBlocking {
        val bound = mutableListOf<Boolean>()
        val probe = SocketPortProbe { s: Socket -> bound += s.isConnected }
        ServerSocket(0).use { server ->
            assertTrue(probe.isOpen("127.0.0.1", server.localPort, 1_000))
        }
        assertEquals(listOf(false), bound) // called once, before connect
    }

    @Test fun aBinderThatFailsStillProbesWithDefaultRouting() = runBlocking {
        // A lockdown VPN refuses binding to Wi-Fi (EPERM): the probe must still look, not report every TV as gone.
        var tried = 0
        val probe = SocketPortProbe { tried++; throw java.net.SocketException("Operation not permitted") }
        ServerSocket(0).use { server -> assertTrue(probe.isOpen("127.0.0.1", server.localPort, 1_000)) }
        assertEquals(1, tried)
        val closed = ServerSocket(0).use { it.localPort }
        assertFalse(probe.isOpen("127.0.0.1", closed, 1_000))
    }

    @Test fun bindOrDefaultReportsTheFailureAndLeavesTheSocketUsable() {
        val seen = mutableListOf<Exception>()
        Socket().use { s ->
            bindOrDefault(s, { throw IllegalStateException("stale network") }) { seen += it }
            ServerSocket(0).use { server ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 1_000)
                assertTrue(s.isConnected)
            }
        }
        assertEquals(1, seen.size)
    }
}
