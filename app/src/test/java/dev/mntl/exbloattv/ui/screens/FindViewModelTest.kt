package dev.mntl.exbloattv.ui.screens

import dev.mntl.exbloattv.data.RememberedTv
import dev.mntl.exbloattv.discovery.FoundTv
import dev.mntl.exbloattv.discovery.TvReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val miBox = RememberedTv("S1", "Mi Box 4", "192.168.10.3", 1_000L)
    private val bedroom = RememberedTv("S2", "Bedroom TV", "192.168.10.17", null)

    @Test fun mergesRememberedTvsBySerialAndMarksANewAddress() = runTest(dispatcher) {
        val serials = mapOf("192.168.10.40" to "S1", "192.168.10.50" to "SOMEONE-ELSE")
        val probed = mutableListOf<String>()
        val moved = mutableListOf<Pair<String, String>>()
        val vm = FindViewModel(
            remembered = MutableStateFlow(listOf(miBox, bedroom)),
            wifiAvailable = { true },
            scan = {
                flowOf(
                    FoundTv("192.168.10.17", TvReadiness.NEEDS_SETUP),
                    FoundTv("192.168.10.40", TvReadiness.READY, "Living room"),
                    FoundTv("192.168.10.50", TvReadiness.READY),
                )
            },
            serialAt = { host -> probed += host; serials[host] },
            onMoved = { serial, host -> moved += serial to host },
        )
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.scanning)
        assertFalse(s.nothingFound)
        val byHost = s.rows.associateBy { it.host }
        assertEquals(setOf("192.168.10.17", "192.168.10.40", "192.168.10.50"), byHost.keys)

        val bed = byHost.getValue("192.168.10.17")
        assertEquals("S2", bed.rememberedSerial)
        assertEquals("Bedroom TV", bed.name)
        assertNull(bed.movedFrom)
        assertEquals(TvReadiness.NEEDS_SETUP, bed.readiness)

        val box = byHost.getValue("192.168.10.40")
        assertEquals("S1", box.rememberedSerial)
        assertEquals("Mi Box 4", box.name)
        assertEquals("192.168.10.3", box.movedFrom)

        val other = byHost.getValue("192.168.10.50")
        assertNull(other.rememberedSerial)
        assertNull(other.movedFrom)

        assertEquals(listOf("S1" to "192.168.10.40"), moved)
        // A TV that needs setup can't be asked for its serial.
        assertTrue("192.168.10.17" !in probed)
        // Remembered TVs come first.
        assertEquals("S1", s.rows.first().rememberedSerial ?: s.rows[1].rememberedSerial)
    }

    @Test fun noProbingWhenEveryRememberedTvIsAtItsAddress() = runTest(dispatcher) {
        val probed = mutableListOf<String>()
        val vm = FindViewModel(
            remembered = MutableStateFlow(listOf(miBox)),
            wifiAvailable = { true },
            scan = { flowOf(FoundTv("192.168.10.3", TvReadiness.READY), FoundTv("192.168.10.9", TvReadiness.READY)) },
            serialAt = { probed += it; null },
            onMoved = { _, _ -> error("not moved") },
        )
        advanceUntilIdle()
        assertTrue(probed.isEmpty())
        assertEquals("S1", vm.state.value.rows.first { it.host == "192.168.10.3" }.rememberedSerial)
    }

    @Test fun noWifiMeansNoScan() = runTest(dispatcher) {
        var scanned = false
        val vm = FindViewModel(
            remembered = MutableStateFlow(emptyList()),
            wifiAvailable = { false },
            scan = { scanned = true; flowOf() },
            serialAt = { null },
            onMoved = { _, _ -> },
        )
        advanceUntilIdle()
        assertFalse(vm.state.value.wifi)
        assertFalse(scanned)
    }

    @Test fun emptyScanSaysNothingFoundAndRescanWorks() = runTest(dispatcher) {
        var round = 0
        val vm = FindViewModel(
            remembered = MutableStateFlow(emptyList()),
            wifiAvailable = { true },
            scan = { if (round++ == 0) flowOf() else flowOf(FoundTv("10.0.0.5", TvReadiness.READY)) },
            serialAt = { null },
            onMoved = { _, _ -> },
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.nothingFound)
        vm.rescan()
        advanceUntilIdle()
        assertFalse(vm.state.value.nothingFound)
        assertEquals(listOf("10.0.0.5"), vm.state.value.rows.map { it.host })
    }
}
