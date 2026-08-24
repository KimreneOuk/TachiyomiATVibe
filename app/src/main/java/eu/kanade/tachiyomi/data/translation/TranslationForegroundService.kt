package eu.kanade.tachiyomi.data.translation

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.TranslationProgressSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationForegroundService : Service() {

    private val manager: TranslationManager by lazy { Injekt.get() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            Notifications.ID_TRANSLATION_PROGRESS,
            notificationBuilder(Notifications.CHANNEL_TRANSLATION_PROGRESS) {
                setSmallIcon(R.drawable.ic_mihon)
                setContentTitle(stringResource(ATMR.strings.manga_translation_progress))
                setContentText(stringResource(ATMR.strings.manga_translation_no_progress))
                setOngoing(true)
                setOnlyAlertOnce(true)
                addAction(R.drawable.ic_close_24dp, stringResource(ATMR.strings.reader_translation_stop_all), stopIntent())
            }.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        monitorJob = serviceScope.launch { monitorQueue() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager.clearQueue()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorQueue() {
        while (serviceScope.isActive) {
            val queued = manager.queueState.value
            if (!BatchTranslationForegroundPolicy.shouldKeepServiceRunning(queued.map { it.status })) {
                stopSelf()
                return
            }
            val active = queued.firstOrNull { it.status == eu.kanade.translation.model.Translation.State.TRANSLATING }
                ?: queued.first()
            publishProgress(
                active.chapter.name,
                active.chapter.id?.let(manager::getTranslationProgress)?.first(),
            )
            delay(PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private fun publishProgress(chapterName: String, snapshot: TranslationProgressSnapshot?) {
        val completed = snapshot?.processedPages ?: 0
        val total = snapshot?.totalPages ?: 0
        val content = if (total > 0) {
            stringResource(ATMR.strings.reader_translation_queue_running, completed, total)
        } else {
            stringResource(ATMR.strings.manga_translation_no_progress)
        }
        val notification = notificationBuilder(Notifications.CHANNEL_TRANSLATION_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(chapterName)
            setContentText(content)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(total, completed, total == 0)
            addAction(R.drawable.ic_close_24dp, stringResource(ATMR.strings.reader_translation_stop_all), stopIntent())
        }.build()
        NotificationManagerCompat.from(this).notify(Notifications.ID_TRANSLATION_PROGRESS, notification)
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, TranslationForegroundService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val ACTION_STOP = "eu.kanade.tachiyomi.action.STOP_BATCH_TRANSLATION"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TranslationForegroundService::class.java))
        }
    }
}
