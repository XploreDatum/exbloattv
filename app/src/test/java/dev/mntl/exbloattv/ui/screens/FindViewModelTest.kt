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

    @Test fun recognisesRememberedTvsByAddressOrAnnouncedNameAndNeverConnects() = runTest(dispatcher) {
        val vm = FindViewModel(
            remembered = MutableStateFlow(listOf(miBox, bedroom)),
            wifiAvailable = { true },
            scan = {
                flowOf(
                    FoundTv("192.168.10.17", TvReadiness.NEEDS_SETUP),
                    FoundTv("192.168.10.40", TvReadiness.READY, "mi box 4 "), // the Mi Box at a new address
                    FoundTv("192.168.10.50", TvReadiness.READY),
                    FoundTv("192.168.10.60", TvReadiness.READY, "Mi Box 4"), // same name again: only one can be it
                )
            },
        )
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.scanning)
        assertFalse(s.nothingFound)
        val byHost = s.rows.associateBy { it.host }
        assertEquals(setOf("192.168.10.17", "192.168.10.40", "192.168.10.50", "192.168.10.60"), byHost.keys)

        val bed = byHost.getValue("192.168.10.17")
        assertEquals("S2", bed.rememberedSerial)
        assertEquals("Bedroom TV", bed.name)
        assertNull(bed.movedFrom)
        assertEquals(TvReadiness.NEEDS_SETUP, bed.readiness)

        val box = byHost.getValue("192.168.10.40")
        assertEquals("S1", box.rememberedSerial)
        assertEquals("Mi Box 4", box.name)
        assertEquals("192.168.10.3", box.movedFrom)

        for (h in listOf("192.168.10.50", "192.168.10.60")) {
            assertNull(byHost.getValue(h).rememberedSerial)
            assertNull(byHost.getValue(h).movedFrom)
        }
        // Remembered TVs come first.
        assertTrue(s.rows.take(2).all { it.rememberedSerial != null })
    }

    @Test fun sameAddressWinsOverName() = runTest(dispatcher) {
        val vm = FindViewModel(
            remembered = MutableStateFlow(listOf(miBox)),
            wifiAvailable = { true },
            scan = { flowOf(FoundTv("192.168.10.3", TvReadiness.READY), FoundTv("192.168.10.9", TvReadiness.READY, "Mi Box 4")) },
        )
        advanceUntilIdle()
        val rows = vm.state.value.rows.associateBy { it.host }
        assertEquals("S1", rows.getValue("192.168.10.3").rememberedSerial)
        assertNull(rows.getValue("192.168.10.9").rememberedSerial) // the remembered TV is already placed
    }

    @Test fun noWifiMeansNoScan() = runTest(dispatcher) {
        var scanned = false
        val vm = FindViewModel(
            remembered = MutableStateFlow(emptyList()),
            wifiAvailable = { false },
            scan = { scanned = true; flowOf() },
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
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.nothingFound)
        vm.rescan()
        advanceUntilIdle()
        assertFalse(vm.state.value.nothingFound)
        assertEquals(listOf("10.0.0.5"), vm.state.value.rows.map { it.host })
    }
}
