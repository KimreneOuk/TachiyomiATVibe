package eu.kanade.translation.benchmark

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.io.Closeable

/** Keeps long benchmark runs resident and prevents screen-off CPU suspension. */
class PaddleBenchmarkRunLease(private val context: Context) : Closeable {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TachiyomiAT:PaddleBenchmark")
    private var started = false

    fun start() {
        if (started) return
        started = true
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
        val serviceIntent = Intent(context, PaddleBenchmarkForegroundService::class.java)
            .setAction(PaddleBenchmarkForegroundService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    override fun close() {
        if (!started) return
        started = false
        if (wakeLock?.isHeld == true) wakeLock.release()
        context.stopService(
            Intent(context, PaddleBenchmarkForegroundService::class.java)
                .setAction(PaddleBenchmarkForegroundService.ACTION_STOP),
        )
    }
}
