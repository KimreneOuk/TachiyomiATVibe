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

/**
 * Source factory for a manual tap on a downloaded chapter without a live
 * reader stream. Each invocation rescans the chapter dir on the worker and
 * opens a fresh stream; a missing dir or page throws so the attempt fails
 * with a typed pipeline failure instead of a null dereference.
 */

internal fun TranslationManager.downloadedPageStreamFactoryImpl(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> java.io.InputStream)? {
        val chapterId = chapter.id ?: return null
        return {
            val chapterPath = downloadProvider.findChapterDir(
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            ) ?: throw java.io.IOException(
                "downloaded chapter dir not found for manual tap: chapterId=$chapterId pageKey=$pageKey",
            )
            eu.kanade.translation.util.getChapterPages(context, chapterPath)
                .firstOrNull { it.first == pageKey }
                ?.second?.invoke()
                ?: throw java.io.IOException(
                    "downloaded page not found for manual tap: chapterId=$chapterId pageKey=$pageKey",
                )
        }
    }


internal fun TranslationManager.admitBatchSessionImpl(chapterIds: Set<Long>): Boolean {
        if (chapterIds.isEmpty()) return false
        return when (val admission = sessionCoordinator.requestBatchSession(BatchSessionIntent(chapterIds))) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> true

            is SessionAdmission.Rejected -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch admission rejected: reason=${admission.reason} chapters=$chapterIds"
                }
                false
            }
        }
    }


internal fun TranslationManager.queueTranslationAfterDownloadImpl(manga: Manga, chapter: Chapter) {
        // A live request supersedes any restore-admitted mark.
        chapter.id?.let { chapterId -> restoreAdmittedChapterIds.remove(chapterId) }
        requestCoordinator.queueTranslationAfterDownload(manga, chapter)
    }


internal fun TranslationManager.acknowledgeTranslationRequestsImpl(chapters: List<Chapter>) {
        // Live acknowledgements supersede restore-admitted marks.
        chapters.forEach { chapter ->
            chapter.id?.let { chapterId -> restoreAdmittedChapterIds.remove(chapterId) }
        }
        requestCoordinator.acknowledgeTranslationRequests(chapters)
    }


internal fun TranslationManager.markTranslationRequestPreparingImpl(chapterId: Long) {
        requestCoordinator.markTranslationRequestPreparing(chapterId)
    }


internal fun TranslationManager.markTranslationDownloadFailedImpl(
        chapterId: Long,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.DOWNLOAD_FAILED,
    ) {
        requestCoordinator.markTranslationDownloadFailed(chapterId, reason, failureKind)
    }


/**
 * The chapter's files were finalized, but translation startup failed during
 * rekey, handoff, or admission. The download keeps its `DOWNLOADED` status;
 * the request receives R10 admission-failure typing instead of a false
 * download failure. No-op without a pending request, so ordinary downloads
 * are unaffected.
 */

internal fun TranslationManager.markTranslationHandoffFailedImpl(
        chapterId: Long,
        reason: String? = null,
    ) {
        requestCoordinator.markTranslationHandoffFailed(chapterId, reason)
    }


internal fun TranslationManager.onDownloadCancelledForTranslationImpl(chapterId: Long) {
        requestCoordinator.onDownloadCancelled(chapterId)
    }


internal fun TranslationManager.onDownloadQueueClearedForTranslationImpl(chapterId: Long) {
        requestCoordinator.onDownloadQueueCleared(chapterId)
    }


internal fun TranslationManager.onDownloadStoppedForTranslationImpl(chapterId: Long, reason: String?) {
        requestCoordinator.onDownloadStopped(chapterId, reason)
    }


internal fun TranslationManager.clearStaleDownloadFailedRequestImpl(chapterId: Long) {
        requestCoordinator.clearStaleDownloadFailedRequest(chapterId)
    }


internal fun TranslationManager.setPendingTranslationRequestImpl(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.NONE,
    ) {
        requestCoordinator.setPendingTranslationRequest(chapterId, phase, reason, failureKind)
    }


internal fun TranslationManager.clearPendingTranslationRequestImpl(chapterId: Long) {
        requestCoordinator.clearPendingTranslationRequest(chapterId)
    }


internal fun TranslationManager.clearAllPendingTranslationRequestsImpl() {
        requestCoordinator.clearAllPendingTranslationRequests()
    }


internal suspend fun TranslationManager.startTranslationAfterDownloadIfRequestedImpl(manga: Manga, chapter: Chapter) {
        // One-shot gate. A pending request startup reconciliation
        // admitted is enqueued PAUSED when its download completes in this
        // process; live requests carry no mark and auto-start unchanged.
        val chapterId = chapter.id
        val restoreAdmitted = chapterId != null && restoreAdmittedChapterIds.remove(chapterId)
        requestCoordinator.startTranslationAfterDownloadIfRequested(manga, chapter, !restoreAdmitted)
    }
