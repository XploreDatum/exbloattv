package dev.xpdt.exbloattv.ui.screens

import dev.xpdt.exbloattv.state.Lock
import dev.xpdt.exbloattv.state.TvStateStore
import org.junit.Assert.assertEquals
import org.junit.Test

class LockViewTest {
    private val me = "Pixel 9 · ab12"
    private val now = 1_000_000L

    @Test fun ownFreshLockWithNothingRunningIsAnInterruptedRunNotAnotherPhone() {
        assertEquals(LockView.THIS_PHONE_INTERRUPTED, lockView(Lock(me, now - 60_000), me, now, runningHere = false))
    }

    @Test fun ownLockWhileThisPhoneIsWorkingShowsNothing() {
        assertEquals(LockView.NONE, lockView(Lock(me, now), me, now, runningHere = true))
    }

    @Test fun anotherPhonesFreshLock() {
        assertEquals(LockView.OTHER_PHONE, lockView(Lock("Pixel 9 · ffff", now - 1_000), me, now, runningHere = false))
        assertEquals(LockView.OTHER_PHONE, lockView(Lock("Galaxy · 1234", now), me, now, runningHere = true))
    }

    @Test fun expiredOrMissingLocksMeanNothing() {
        assertEquals(LockView.NONE, lockView(null, me, now, runningHere = false))
        assertEquals(LockView.NONE, lockView(Lock("Galaxy · 1234", now - TvStateStore.LOCK_EXPIRY_MS), me, now, runningHere = false))
        assertEquals(LockView.NONE, lockView(Lock(me, now - TvStateStore.LOCK_EXPIRY_MS - 1), me, now, runningHere = false))
    }

    @Test fun unknownPhoneNameNeverClaimsTheLock() {
        assertEquals(LockView.OTHER_PHONE, lockView(Lock(me, now), null, now, runningHere = false))
    }
}
