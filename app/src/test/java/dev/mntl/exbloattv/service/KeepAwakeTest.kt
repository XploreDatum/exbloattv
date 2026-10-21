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
}
