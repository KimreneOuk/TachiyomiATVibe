package eu.kanade.translation.workflow

import android.content.Context
import androidx.annotation.VisibleForTesting
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.translationQueueAdmissionFailureKind
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.persistence.ArtifactManifestProbe
import eu.kanade.translation.persistence.ChapterAttemptLedgerDocument
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterResetPreflight
import eu.kanade.translation.persistence.ChapterStoreOpener
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.DurableChapterRead
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.scheduling.TranslationPendingRequestStore
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.scheduling.ReaderSessionToken
import eu.kanade.translation.scheduling.ReaderSubmission
import eu.kanade.translation.scheduling.ReaderTrigger
import eu.kanade.translation.scheduling.TranslationSession
import eu.kanade.translation.scheduling.TranslationStoreResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal suspend fun TranslationManager.openTranslationSessionImpl(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): TranslationSession? {
        val chapterId = chapter.id ?: return null
        // Rolling-auto owns the chapter from here; a stale DOWNLOAD_FAILED
        // batch request must not keep projecting its failed state.
        clearStaleDownloadFailedRequest(chapterId)
        val store = openOrCreateActiveChapterTranslationStoreSuspend(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
            manga.id,
        ) ?: return null
        val key = "${source.id}:${manga.id}:$chapterId"
        return TranslationSession(key, manga, chapter, source, store)
    }


/**
 * Typed reader queue facade (Phase 4 S3). Binds or reuses the queue session
 * admitted for [session]'s chapter and returns its token, applying the same
 * workflow admission and BATCH-suppression gates as the window entry. Null
 * when admission is denied.
 */

internal fun TranslationManager.bindReaderQueueImpl(
        session: TranslationSession,
        computeClass: eu.kanade.translation.engines.translator.TranslatorComputeClass,
    ): ReaderSessionToken? {
        val chapterId = session.chapter.id ?: return null
        when (val admission = sessionCoordinator.requestReaderSession(ReaderSessionIntent(chapterId))) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> Unit

            is SessionAdmission.Rejected -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT reader auto admission rejected: reason=${admission.reason} " +
                        "chapterId=$chapterId"
                }
                return null
            }
        }
        if (isChapterBatchActive(chapterId, includePaused = true)) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT auto window suppressed while the chapter batch is queued: chapterId=$chapterId"
            }
            return null
        }
        val token = scheduler.bindReaderQueueSession(chapterId, session, computeClass) ?: return null
        val previousChapterId = activeReaderCoordinatorChapterId
        if (previousChapterId != null && previousChapterId != chapterId) {
            readerViewportStates.remove(previousChapterId)
            applicationScope.launch { readerCoordinator.cancelChapter(previousChapterId.toString()) }
        }
        activeReaderCoordinatorChapterId = chapterId
        return token
    }


internal suspend fun TranslationManager.submitReaderPageImpl(
        token: ReaderSessionToken,
        work: eu.kanade.translation.scheduling.ReaderPageWork,
        trigger: ReaderTrigger,
        force: Boolean = false,
    ): ReaderSubmission {
        val session = scheduler.sessionFor(token) ?: return ReaderSubmission.Existing(null)
        val chapterId = session.chapter.id ?: return ReaderSubmission.Existing(null)
        val streamFn = work.streamFn ?: return ReaderSubmission.Existing(null)
        if (isChapterBatchActive(chapterId, includePaused = true)) return ReaderSubmission.Existing(null)
        val store = session.store
        if (force) {
            store.updatePageFromCurrentSnapshot(work.pageKey, "reader:forced-retry") { current ->
                (current ?: eu.kanade.translation.model.PageTranslation(sourceFileName = work.pageKey))
                    .apply { prepareForcedRetry() }
            }
        }
        val companionDir = provider.getCompanionImageDir(
            session.manga.title,
            session.source,
            session.chapter.name,
            session.chapter.scanlator,
        )
        val accepted = readerCoordinator.enqueuePage(
            ReaderTranslationCoordinator.PageRequest(
                chapterKey = chapterId.toString(),
                pageKey = work.pageKey,
                isManual = trigger == ReaderTrigger.MANUAL_TAP,
                streamFn = streamFn,
                companionDir = companionDir,
                force = force,
                store = store,
            ),
        )
        return if (accepted) ReaderSubmission.Queued(work.pageKey) else ReaderSubmission.Existing(null)
    }


internal fun TranslationManager.updateReaderViewportImpl(
        token: ReaderSessionToken,
        chapterId: Long,
        pageIndex: Int,
        pageCount: Int,
        resolve: (Int) -> eu.kanade.translation.scheduling.ReaderPageWork?,
    ) {
        if (isChapterBatchActive(chapterId, includePaused = true)) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT auto window suppressed while the chapter batch is queued: chapterId=$chapterId"
            }
            return
        }
        if (!translationPreferences.autoTranslate().get()) return
        val session = scheduler.sessionFor(token) ?: return
        if (session.chapter.id != chapterId) return
        activeReaderCoordinatorChapterId = chapterId
        readerViewportStates[chapterId] = pageIndex to pageCount
        val revision = readerViewportRevisions.computeIfAbsent(chapterId) { AtomicLong() }.incrementAndGet()
        val chapterKey = chapterId.toString()
        readerCoordinator.setAutoEnabled(chapterKey, true)
        applicationScope.launch {
            readerViewportLocks.computeIfAbsent(chapterId) { Mutex() }.withLock {
                if (readerViewportRevisions[chapterId]?.get() != revision) return@withLock
                val visibleOrNear = (pageIndex..minOf(pageIndex + 1, pageCount - 1))
                    .mapNotNull(resolve)
                    .distinctBy { it.pageKey }
                if (readerViewportRevisions[chapterId]?.get() != revision) return@withLock
                readerCoordinator.updateViewportWindow(
                    chapterKey,
                    visibleOrNear.mapTo(linkedSetOf()) { it.pageKey },
                )
                visibleOrNear.forEach { work ->
                    val streamFn = work.streamFn ?: return@forEach
                    readerCoordinator.enqueuePage(
                        ReaderTranslationCoordinator.PageRequest(
                            chapterKey = chapterKey,
                            pageKey = work.pageKey,
                            isManual = false,
                            streamFn = streamFn,
                            companionDir = provider.getCompanionImageDir(
                                session.manga.title,
                                session.source,
                                session.chapter.name,
                                session.chapter.scanlator,
                            ),
                            store = session.store,
                        ),
                    )
                }
            }
        }
    }


internal fun TranslationManager.setReaderAutoEnabledImpl(token: ReaderSessionToken, enabled: Boolean): Boolean {
        val coordinatorHadDemand = scheduler.sessionFor(token)?.chapter?.id?.toString()?.let {
            readerCoordinator.setAutoEnabled(it, enabled)
        } ?: false
        val schedulerHadDemand = scheduler.setReaderAutoEnabled(token, enabled)
        return coordinatorHadDemand || schedulerHadDemand
    }


/**
 * Re-drives queued demands for the captured session after a source-ready,
 * memory or foreground event. A stale window is not re-admitted mid-batch.
 */

internal fun TranslationManager.reconcileReaderQueueImpl(
        token: ReaderSessionToken,
        chapterId: Long,
        resolve: (Int) -> eu.kanade.translation.scheduling.ReaderPageWork?,
    ) {
        if (isChapterBatchActive(chapterId, includePaused = true)) return
        val viewport = readerViewportStates[chapterId] ?: return
        updateReaderViewport(token, chapterId, viewport.first, viewport.second, resolve)
    }


internal fun TranslationManager.disposeBatchTrackerImpl(chapterId: Long) {
        batchTrackerRegistry.dispose(chapterId)
    }


internal fun TranslationManager.cancelQueuedTranslationImpl(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }


internal fun TranslationManager.removeFromTranslationQueueImpl(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else if (queueState.value.isNotEmpty()) {
                translator.start()
            }
        }
        if (!isAnyBatchTranslationActive && queueState.value.none { it.status == Translation.State.PAUSED }) {
            sessionCoordinator.finishSession(TranslationSessionState.BATCH_SESSION)
        }
    }
