package com.mochigalbi.catchpopped.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mochigalbi.catchpopped.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app process and the CPU running while [IdeaProcessor] works, so transcription
 * continues with the screen off or the app in the background. It does no work itself:
 * [IdeaProcessor] starts it, and it stops itself once [IdeaProcessor.active] turns false.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "받아쓰기 진행", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called first: the system kills the app if a started foreground service
        // does not call startForeground in time.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        lastStartId = startId
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CatchPopped:processing")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        if (watcher == null) {
            watcher = scope.launch {
                // stopSelf(id) is ignored if a newer start arrived in the meantime.
                IdeaProcessor.get(this@ProcessingService).active.collect { active ->
                    if (!active) stopSelf(lastStartId)
                }
            }
        }
        // If the process is killed, interrupted ideas are marked as failed; nothing to resume.
        return START_NOT_STICKY
    }

    /** Android 15 limits data sync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("아이디어 처리 중")
        .setContentText("끝나면 목록에 표시됩니다.")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        /** Returns false if the system refused; processing then runs only while the app is open. */
        fun start(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, ProcessingService::class.java))
        }.isSuccess
    }
}
