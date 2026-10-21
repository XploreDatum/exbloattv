package dev.mntl.exbloattv.ui.screens

import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.state.TvState
import dev.mntl.exbloattv.state.TvStateTooNewException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvDetailsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(read: suspend () -> TvState) =
        TvDetailsViewModel("S", flowOf(null), flowOf(null), { "Pixel" }, { _, _ -> read() })

    @Test fun aTvSetUpByANewerAppVersionIsShownAsSuchNotAsUnreachable() {
        val vm = vm { throw TvStateTooNewException(2) }
        vm.refresh("h")
        assertEquals(LogState.TooNew, vm.log.value)
    }

    @Test fun otherFailuresAreStillUnreachable() {
        val vm = vm { throw AdbError.Refused() }
        vm.refresh("h")
        assertEquals(LogState.Unreachable, vm.log.value)
    }
}
