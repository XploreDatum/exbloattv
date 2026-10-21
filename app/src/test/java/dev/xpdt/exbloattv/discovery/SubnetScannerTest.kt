package dev.xpdt.exbloattv.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubnetScannerTest {
    private fun probeOf(vararg open: String, slow: Map<String, Long> = emptyMap()) = PortProbe { host, port, _ ->
        slow[host]?.let { delay(it) }
        "$host:$port" in open
    }

    @Test fun hostsForSlash24ExcludesPhone() {
        val hosts = SubnetScanner(probeOf()).hostsFor("192.168.10.42", 24)
        assertThat(hosts).hasSize(253)
        assertThat(hosts.first()).isEqualTo("192.168.10.1")
        assertThat(hosts.last()).isEqualTo("192.168.10.254")
        assertThat(hosts).doesNotContain("192.168.10.42")
    }

    @Test fun largerNetworkClampsToSlash24() {
        val hosts = SubnetScanner(probeOf()).hostsFor("10.1.7.9", 16)
        assertThat(hosts).hasSize(253)
        assertThat(hosts.first()).isEqualTo("10.1.7.1")
    }

    @Test fun smallerNetworkKeepsItsOwnRange() {
        val hosts = SubnetScanner(probeOf()).hostsFor("192.168.1.130", 26)
        assertThat(hosts.first()).isEqualTo("192.168.1.129")
        assertThat(hosts.last()).isEqualTo("192.168.1.190")
        assertThat(hosts).doesNotContain("192.168.1.130")
    }

    @Test fun classifiesReadyAndNeedsSetupOnly() = runTest {
        val s = SubnetScanner(probeOf("192.168.10.3:5555", "192.168.10.17:8009", "192.168.10.50:80"))
        val found = s.scan(s.hostsFor("192.168.10.42", 24)).toList()
        assertThat(found.map { it.host to it.readiness }).containsExactly(
            "192.168.10.3" to TvReadiness.READY,
            "192.168.10.17" to TvReadiness.NEEDS_SETUP,
        )
    }

    @Test fun port5555WinsOverCastPorts() = runTest {
        val s = SubnetScanner(probeOf("1.1.1.1:5555", "1.1.1.1:8009", "1.1.1.1:6466"))
        assertThat(s.scan(listOf("1.1.1.1")).toList()).containsExactly(FoundTv("1.1.1.1", TvReadiness.READY))
    }

    @Test fun resultsStreamBeforeAllProbesFinish() = runTest {
        val s = SubnetScanner(probeOf("192.168.10.3:5555", slow = mapOf("192.168.10.200" to 5_000L)))
        var firstAt = -1L
        s.scan(s.hostsFor("192.168.10.42", 24)).collect { if (firstAt < 0) firstAt = currentTime }
        assertThat(firstAt).isEqualTo(0L)
        assertThat(currentTime).isAtLeast(5_000L)
    }

    @Test fun fullSlash24FinishesFastWithParallelism() = runTest {
        val s = SubnetScanner(probeOf(slow = (1..254).associate { "10.0.0.$it" to 800L }))
        s.scan(s.hostsFor("10.0.0.99", 24)).toList()
        assertThat(currentTime).isAtMost(4 * 2 * 800L)
    }

    @Test fun blockingProbesRunMoreThan64AtOnce() {
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val probe = PortProbe { _, _, _ ->
            withContext(ProbeDispatcher) {
                val n = inFlight.incrementAndGet()
                peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                Thread.sleep(100) // a blocking connect that times out
                inFlight.decrementAndGet()
                false
            }
        }
        val s = SubnetScanner(probe)
        val started = System.nanoTime()
        kotlinx.coroutines.runBlocking { s.scan(s.hostsFor("10.0.0.99", 24)).toList() }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertThat(peak.get()).isGreaterThan(64)
        // 4 waves of 64 hosts x (5555 + setup ports) = ~8 sleeps deep; serialized on 64 threads it would be ~3x that.
        assertThat(ms).isLessThan(3_000)
    }

    @Test fun setupPortsAreSkippedWhenAdbPortOpen() = runTest {
        val asked = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val s = SubnetScanner(PortProbe { _, port, _ -> asked += port; port == 5555 })
        s.scan(listOf("1.1.1.1")).toList()
        assertThat(asked.toList()).containsExactly(5555)
    }
}
