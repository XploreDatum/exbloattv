package dev.mntl.exbloattv.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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

/** A partial wake lock (with a safety timeout) plus a Wi-Fi lock that stops Wi-Fi power saving. */
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

    override fun acquire() {
        runCatching { wake?.acquire(TIMEOUT_MS) }
        runCatching { wifi?.acquire() }
    }

    override fun release() {
        runCatching { if (wifi?.isHeld == true) wifi.release() }
        runCatching { if (wake?.isHeld == true) wake.release() }
    }

    private companion object {
        /** Re-acquired for every job; a run (with its restart and retries) fits well inside this. */
        const val TIMEOUT_MS = 30 * 60_000L
    }
}
