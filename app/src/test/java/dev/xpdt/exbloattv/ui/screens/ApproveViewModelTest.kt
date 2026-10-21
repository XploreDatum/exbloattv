package dev.xpdt.exbloattv.ui.screens

import dev.xpdt.exbloattv.adb.AdbError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApproveViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun theApprovalWindowEndingMidConnectIsTimedOutNotUnreachable() {
        // The last attempt gets only what is left of the window; running out then is the window ending.
        val vm = ApproveViewModel(
            host = "h",
            connect = { _, left -> Thread.sleep(left); throw AdbError.Timeout() },
            load = { _, _ -> error("never loads") },
            windowMs = 100,
        )
        assertEquals(ApproveState.TimedOut, vm.state.value)
    }

    @Test fun aConnectTimeoutWithTimeLeftIsUnreachable() {
        val vm = ApproveViewModel(
            host = "h",
            connect = { _, _ -> throw AdbError.Timeout() },
            load = { _, _ -> error("never loads") },
            windowMs = 60_000,
        )
        assertEquals(ApproveState.Unreachable, vm.state.value)
    }
}
