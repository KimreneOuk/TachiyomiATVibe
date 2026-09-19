package eu.kanade.translation.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Foreground marker for headless benchmark runs; it does not own OCR work. */
class PaddleBenchmarkForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Paddle OCR benchmark",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Paddle OCR benchmark")
            .setContentText("Benchmark run in progress")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "eu.kanade.translation.benchmark.START"
        const val ACTION_STOP = "eu.kanade.translation.benchmark.STOP"
        private const val CHANNEL_ID = "paddle_benchmark"
        private const val NOTIFICATION_ID = 0x5044
    }
}
