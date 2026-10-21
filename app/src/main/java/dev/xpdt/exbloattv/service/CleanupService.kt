package dev.xpdt.exbloattv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.xpdt.exbloattv.ExBloatApp
import dev.xpdt.exbloattv.MainActivity
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.cleanup.StepStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that runs cleanups and undos from [CleanupWork], so locking the phone or leaving
 * the app doesn't stop them. Shows "Cleaning up <TV name>…" with progress, then a result notification.
 */
class CleanupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false
    /** The newest start; stopping with it never cuts off a start that came in later. */
    @Volatile private var latestStartId = 0
    private val awake: KeepAwake by lazy { AndroidKeepAwake(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val work = (application as ExBloatApp).graph.work
        if (!goForeground(Notifications.progress(this, work.state.value))) {
            // Not allowed to be a foreground service right now: run the job in the app instead, and stop cleanly.
            work.claimPending()?.let(work::runWithoutService)
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val job = work.claimPending()
        if (job == null) {
            if (!running) stop(startId)
            return START_NOT_STICKY
        }
        running = true
        scope.launch {
            val updates = launch {
                work.state.collect {
                    notify(Notifications.ONGOING_ID, Notifications.progress(this@CleanupService, it))
                    delay(UPDATE_EVERY_MS) // the system drops notification updates that come faster
                }
            }
            try {
                // CPU and Wi-Fi stay awake for the whole job: settle waits, restart polling and retries keep time.
                awake.whileRunning { work.execute(job) }
            } finally {
                updates.cancel()
                running = false
                Notifications.result(this@CleanupService, work.state.value)?.let { notify(Notifications.RESULT_ID, it) }
                stop(latestStartId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ caps dataSync services; stop cleanly (the runner releases the TV lock as it is cancelled). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        awake.release()
        stop(latestStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        awake.release()
        super.onDestroy()
    }

    /** False if the system refused (for example a start from the background); the caller then runs the job itself. */
    private fun goForeground(n: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return try {
            ServiceCompat.startForeground(this, Notifications.ONGOING_ID, n, type)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stop(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun notify(id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, n)
        } catch (e: SecurityException) {
            // Notifications not allowed: the app screens still show everything.
        }
    }

    companion object {
        private const val UPDATE_EVERY_MS = 1_000L

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, CleanupService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }
}

internal object Notifications {
    const val CHANNEL_WORK = "cleanup"
    const val CHANNEL_RESULT = "results"
    const val ONGOING_ID = 1
    const val RESULT_ID = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WORK, context.getString(R.string.notif_channel_work), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, context.getString(R.string.notif_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, state: WorkState): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (state) {
            is WorkState.Cleaning -> {
                b.setContentTitle(context.getString(R.string.notif_cleaning_title, state.tvName))
                val p = state.progress
                val needsYou = p != null && (p.waitingForTv || p.steps.any { it.status == StepStatus.FAILED })
                b.setContentText(
                    when {
                        needsYou -> context.getString(R.string.notif_needs_you)
                        state.settleSecondsLeft != null || state.measuring -> context.getString(R.string.notif_checking)
                        else -> context.getString(R.string.notif_leave_remote)
                    },
                )
                if (p == null) b.setProgress(0, 0, true) else b.setProgress(100, (p.fraction * 100).toInt(), false)
            }
            is WorkState.Undoing -> b.setContentTitle(context.getString(R.string.notif_undoing_title, state.tvName)).setProgress(0, 0, true)
            else -> b.setContentTitle(context.getString(R.string.notif_working))
        }
        return b.build()
    }

    fun result(context: Context, state: WorkState): Notification? {
        val (title, text) = when (state) {
            is WorkState.Cleaned -> context.getString(R.string.notif_cleaned_title, state.tvName) to context.getString(R.string.notif_cleaned_text)
            is WorkState.CleanupStopped -> context.getString(R.string.notif_stopped_title, state.tvName) to context.getString(R.string.notif_open_app)
            is WorkState.Undone -> context.getString(R.string.notif_undone_title, state.tvName) to context.getString(R.string.notif_open_app)
            is WorkState.UndoFailed -> context.getString(R.string.notif_undo_failed_title, state.tvName) to context.getString(R.string.notif_open_app)
            else -> return null
        }
        return NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
    }
}
