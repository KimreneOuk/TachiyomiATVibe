package eu.kanade.tachiyomi.data.translation

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.ui.NotificationAction
import eu.kanade.translation.ui.TranslationNotificationCopy
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
import java.text.DateFormat
import java.util.Date

class TranslationForegroundService : Service() {

    private val manager: TranslationManager by lazy { Injekt.get() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null

    @Volatile
    private var retainNotification = false

    @Volatile
    private var retryInFlight = false

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
        when (intent?.action) {
            ACTION_STOP -> {
                manager.clearQueue()
                stopSelf()
            }
            ACTION_RETRY -> {
                val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
                if (chapterId >= 0L) {
                    retryInFlight = true
                    serviceScope.launch {
                        val resumed = manager.requeueTranslation(chapterId)
                        retryInFlight = false
                        if (resumed) {
                            manager.startTranslation()
                        } else {
                            val snapshot = manager.getTranslationProgress(chapterId).first()
                            val translation = manager.getQueuedTranslationOrNull(chapterId)
                            if (translation != null) {
                                retainNotification = true
                                publishPaused(translation.chapter.name, chapterId, snapshot)
                            }
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        if (retainNotification) {
            // The paused notification is an ordinary, non-ongoing reminder;
            // leave it visible after this active service exits.
            stopForeground(false)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorQueue() {
        while (serviceScope.isActive) {
            // A notification retry re-enters through onStartCommand while the
            // service's monitor is already alive. Keep the service around until
            // the cooldown-aware requeue finishes; otherwise the first paused
            // poll would stop the service and cancel the retry coroutine.
            if (retryInFlight) {
                delay(100)
                continue
            }
            val queued = manager.queueState.value
            val paused = queued.firstOrNull {
                it.status == eu.kanade.translation.model.Translation.State.PAUSED
            }
            val active = queued.firstOrNull {
                it.status == eu.kanade.translation.model.Translation.State.TRANSLATING ||
                    it.status == eu.kanade.translation.model.Translation.State.QUEUE
            }
            if (paused != null && active == null) {
                retainNotification = true
                val snapshot = paused.chapter.id?.let { manager.getTranslationProgress(it).first() }
                publishPaused(paused.chapter.name, paused.chapter.id, snapshot)
                stopSelf()
                return
            }
            if (!BatchTranslationForegroundPolicy.shouldKeepServiceRunning(queued.map { it.status })) {
                stopSelf()
                return
            }
            active ?: run {
                stopSelf()
                return
            }
            publishProgress(
                active.chapter.name,
                active.chapter.id,
                active.chapter.id?.let(manager::getTranslationProgress)?.first(),
            )
            delay(PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    /**
     * T917 Phase 5: the notification body is the pure
     * [TranslationNotificationCopy] projection; the service only renders it
     * and attaches intents for actions it can actually deliver. Unknown
     * source totals render an indeterminate bar — never a lying percentage.
     */
    private fun publishProgress(
        chapterName: String,
        chapterId: Long?,
        snapshot: TranslationProgressSnapshot?,
    ) {
        val copy = TranslationNotificationCopy.of(chapterName, snapshot)
        val total = snapshot?.totalPages ?: 0
        val done = snapshot?.let { (it.donePages - it.failedCount).coerceAtLeast(0) } ?: 0
        val trusted = snapshot != null && snapshot.expectedPageCountTrusted && total > 0
        val builder = notificationBuilder(Notifications.CHANNEL_TRANSLATION_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(copy.title)
            setContentText(copy.text)
            setOngoing(copy.ongoing)
            setOnlyAlertOnce(true)
            setProgress(if (trusted) total else 0, if (trusted) done else 0, !trusted)
            addAction(R.drawable.ic_close_24dp, stringResource(ATMR.strings.reader_translation_stop_all), stopIntent())
            if (NotificationAction.RETRY in copy.actions) {
                chapterId?.let { id ->
                    retryIntent(this@TranslationForegroundService, id)?.let {
                        addAction(R.drawable.ic_play_arrow_24dp, "Retry translation", it)
                    }
                }
            }
        }.build()
        this.notify(Notifications.ID_TRANSLATION_PROGRESS, builder)
    }

    private fun publishPaused(
        chapterName: String,
        chapterId: Long?,
        snapshot: TranslationProgressSnapshot?,
    ) {
        showPaused(this, chapterName, chapterId, snapshot)
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, TranslationForegroundService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val ACTION_STOP = "eu.kanade.tachiyomi.action.STOP_BATCH_TRANSLATION"
        private const val ACTION_RETRY = "eu.kanade.tachiyomi.action.RETRY_BATCH_TRANSLATION"
        private const val EXTRA_CHAPTER_ID = "chapter_id"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TranslationForegroundService::class.java))
        }

        /**
         * Posts a non-ongoing pause reminder without advertising active work.
         * T917 Phase 5: text and actions come from the pure
         * [TranslationNotificationCopy] projection; the service appends the
         * locale-formatted retry time when the copy carries a retry epoch.
         */
        fun showPaused(
            context: Context,
            chapterName: String,
            chapterId: Long?,
            snapshot: TranslationProgressSnapshot?,
        ) {
            val copy = TranslationNotificationCopy.of(chapterName, snapshot)
            val content = copy.retryAtEpochMs
                ?.let { epoch -> copy.text + " after " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epoch)) }
                ?: copy.text
            val builder = context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_PROGRESS) {
                setSmallIcon(R.drawable.ic_mihon)
                setContentTitle(copy.title)
                setContentText(content)
                setOngoing(copy.ongoing)
                setOnlyAlertOnce(true)
                if (NotificationAction.RETRY in copy.actions) {
                    retryIntent(context, chapterId)?.let {
                        addAction(R.drawable.ic_play_arrow_24dp, "Retry translation", it)
                    }
                }
            }
            context.notify(Notifications.ID_TRANSLATION_PROGRESS, builder.build())
        }

        private fun retryIntent(context: Context, chapterId: Long?): PendingIntent? {
            if (chapterId == null) return null
            val intent = Intent(context, TranslationForegroundService::class.java)
                .setAction(ACTION_RETRY)
                .putExtra(EXTRA_CHAPTER_ID, chapterId)
            return PendingIntent.getService(
                context,
                chapterId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
