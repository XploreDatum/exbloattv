package dev.mntl.exbloattv.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Keeps the CPU and Wi-Fi awake while TV work runs, so a locked phone doesn't stall timers or drop the connection. */
interface KeepAwake {
    fun acquire()
    fun release()
}

/** Holds [KeepAwake] for exactly as long as [block] runs, released even when it fails or is cancelled. */
suspend fun <T> KeepAwake.whileRunning(block: suspend () -> T): T {
    acquire()
    try {
        return block()
    } finally {
        release()
    }
}

/**
 * Takes the locks with [take] and lets them go with [drop]; if [release] doesn't come within [timeoutMs] (a job that
 * never ends), [drop] runs anyway, so nothing (the Wi-Fi lock included) is held forever. [schedule] runs a callback
 * after a delay and returns a way to cancel it. A timeout left over from an earlier hold never drops a newer one.
 */
class TimedHold(
    private val timeoutMs: Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val take: () -> Unit,
    private val drop: () -> Unit,
) : KeepAwake {
    private var generation = 0L
    private var cancelTimeout: (() -> Unit)? = null

    @Synchronized
    override fun acquire() {
        cancelTimeout?.invoke()
        val mine = ++generation
        take()
        cancelTimeout = schedule(timeoutMs) { timedOut(mine) }
    }

    @Synchronized
    override fun release() {
        cancelTimeout?.invoke()
        cancelTimeout = null
        generation++
        drop()
    }

    @Synchronized
    private fun timedOut(hold: Long) {
        if (hold != generation) return
        cancelTimeout = null
        generation++
        drop()
    }
}

/**
 * A partial wake lock plus a Wi-Fi lock that stops Wi-Fi power saving. Both are let go together by [TimedHold]'s
 * safety timeout; the wake lock also carries its own, as a backstop.
 */
class AndroidKeepAwake(context: Context) : KeepAwake {
    private val wake = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "exbloattv:cleanup")
        ?.apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "exbloattv:cleanup",
        )
        ?.apply { setReferenceCounted(false) }

    private val handler = Handler(Looper.getMainLooper())

    private val hold = TimedHold(
        timeoutMs = TIMEOUT_MS,
        schedule = { ms, run ->
            val r = Runnable(run)
            handler.postDelayed(r, ms)
            ({ handler.removeCallbacks(r) })
        },
        take = {
            runCatching { wake?.acquire(TIMEOUT_MS) }
            runCatching { wifi?.acquire() }
        },
        drop = {
            runCatching { if (wifi?.isHeld == true) wifi.release() }
            runCatching { if (wake?.isHeld == true) wake.release() }
        },
    )

    override fun acquire() = hold.acquire()

    override fun release() = hold.release()

    private companion object {
        /** Re-acquired for every job; a run (with its restart and retries) fits well inside this. */
        const val TIMEOUT_MS = 30 * 60_000L
    }
}
