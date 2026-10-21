package dev.mntl.exbloattv.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeepAwakeTest {
    private class Recorder : KeepAwake {
        val events = mutableListOf<String>()
        override fun acquire() { events += "acquire" }
        override fun release() { events += "release" }
    }

    @Test fun heldExactlyAroundTheWork() = runTest {
        val r = Recorder()
        val out = r.whileRunning { r.events += "work"; 42 }
        assertEquals(42, out)
        assertEquals(listOf("acquire", "work", "release"), r.events)
    }

    @Test fun releasedWhenTheWorkFails() {
        val r = Recorder()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { r.whileRunning { r.events += "work"; error("boom") } }
        }
        assertEquals(listOf("acquire", "work", "release"), r.events)
    }

    @Test fun releasedWhenTheWorkIsCancelled() = runTest {
        val r = Recorder()
        val job = async { r.whileRunning { r.events += "work"; awaitCancellation() } }
        runCurrent()
        assertEquals(listOf("acquire", "work"), r.events)
        job.cancel(CancellationException("service stopped"))
        runCurrent()
        assertEquals(listOf("acquire", "work", "release"), r.events)
    }

    /** A manual clock: [fire] runs whatever timeouts are still scheduled. */
    private class Timer {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        val schedule: (Long, () -> Unit) -> (() -> Unit) = { ms, r ->
            val e = ms to r
            pending += e
            ({ pending.remove(e) })
        }
        fun fire() = pending.toList().also { pending.clear() }.forEach { it.second() }
    }

    @Test fun theTimeoutReleasesWakeAndWifiTogether() {
        val t = Timer()
        val events = mutableListOf<String>()
        val hold = TimedHold(30_000, t.schedule, take = { events += "take" }, drop = { events += "drop" })
        hold.acquire()
        assertEquals(listOf(30_000L), t.pending.map { it.first })
        t.fire() // the job never released (e.g. stuck): both locks still go
        assertEquals(listOf("take", "drop"), events)
        hold.release() // releasing after the timeout is harmless
        assertEquals(listOf("take", "drop", "drop"), events)
    }

    @Test fun releasingCancelsTheTimeout() {
        val t = Timer()
        val events = mutableListOf<String>()
        val hold = TimedHold(30_000, t.schedule, take = { events += "take" }, drop = { events += "drop" })
        hold.acquire()
        hold.release()
        assertEquals(emptyList<Any>(), t.pending)
        assertEquals(listOf("take", "drop"), events)
    }

    @Test fun anOldTimeoutNeverDropsANewerHold() {
        val t = Timer()
        val events = mutableListOf<String>()
        val hold = TimedHold(30_000, t.schedule, take = { events += "take" }, drop = { events += "drop" })
        hold.acquire()
        val stale = t.pending.single().second
        hold.release()
        hold.acquire()
        stale() // a timeout already on its way when the first hold was released
        assertEquals(listOf("take", "drop", "take"), events)
    }
}
