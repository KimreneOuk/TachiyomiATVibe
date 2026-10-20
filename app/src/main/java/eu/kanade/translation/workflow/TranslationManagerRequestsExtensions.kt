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

// Startup reconciliation ------------------------------------------------

/**
 * Called by the downloader once its asynchronous queue restore has
 * completed (both sides of the readiness barrier). Idempotent.
 */

internal fun TranslationManager.onDownloadQueueRestoredImpl(queuedChapterIds: Set<Long>) {
        downloadQueueRestoredChapterIds = queuedChapterIds
        applicationScope.launch { runStartupReconciliationIfReady() }
    }


/**
 * Readiness barrier: runs once after BOTH queue restores completed. The
 * once-guard is only consumed when the downloader snapshot is present, so
 * a late downloader restore still triggers the pass.
 */

internal fun TranslationManager.runStartupReconciliationIfReadyImpl() {
        val downloadSnapshot = downloadQueueRestoredChapterIds ?: return
        if (!startupReconciliationOnce.compareAndSet(false, true)) return
        applicationScope.launch {
            translationQueueRestoreJob?.join()
            reconcilePendingRequestsForStartup(downloadQueueChapterIds = downloadSnapshot)
            // Consume interrupted-attempt ledger entries for this bounded
            // chapter set; never scan the whole library.
            reconcileAttemptLedgersForStartup(
                chapterIds = translator.persistedQueueChapterIds() +
                    pendingTranslationRequestsState.value.keys +
                    pendingRequestStore.load(),
            )
        }
    }


/**
 * One idempotent, bounded pass over the durable pending requests:
 * - pending + translation-queue member -> queue wins: clear pending;
 * - pending + download-queue member -> normalize to WAITING;
 * - pending + valid downloaded files (no owner) -> admit translation once;
 * - pending + neither -> explicit FAILED (interrupted), never deleted;
 * - chapter/source missing -> purge stale ownership.
 *
 * Race-safe against a concurrently completing download or cancel: the
 * record (with its generation) is re-read at decision time and every
 * durable mutation is fenced on that generation, so a stale decision
 * cannot resurrect a cancelled request or double-admit.
 *
 * Admission intentionally does NOT auto-start OCR/LLM: the chapter lands
 * in the translation queue as QUEUE and the user resumes explicitly,
 * matching the restored-work policy.
 */

internal suspend fun TranslationManager.reconcilePendingRequestsForStartupImpl(
        downloadQueueChapterIds: Set<Long>,
        resolveTranslation: suspend (Long) -> Translation?,
        hasDownloadedFiles: (Translation) -> Boolean,
    ) {
        val pendingIds = (pendingRequestStore.load() + pendingTranslationRequestsState.value.keys)
            .distinct()
        if (pendingIds.isEmpty()) return
        val translationQueueIds = queueState.value.mapNotNull { it.chapter.id }.toSet()
        pendingIds.forEach { chapterId ->
            val current = pendingTranslationRequestsState.value[chapterId]
                ?: pendingRequestStore.record(chapterId)?.let { record ->
                    TranslationRequestState(
                        chapterId = record.chapterId,
                        phase = record.phase,
                        reason = record.reason,
                        generation = record.generation,
                        failureKind = record.failureKind,
                    )
                }
            if (current == null || current.isTerminal) return@forEach
            val translation = resolveTranslation(chapterId)
            when {
                translation == null -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: purging stale pending request $chapterId (chapter/source missing)"
                    }
                    clearPendingTranslationRequest(chapterId)
                }
                chapterId in translationQueueIds -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: pending $chapterId is owned by the translation queue; clearing pending"
                    }
                    clearPendingTranslationRequest(chapterId)
                }
                chapterId in downloadQueueChapterIds -> {
                    // A restore-admitted WAITING request is marked
                    // one-shot so the download handoff in this process enqueues
                    // PAUSED instead of auto-starting translation.
                    restoreAdmittedChapterIds.add(chapterId)
                    if (current.phase != TranslationRequestPhase.WAITING_FOR_DOWNLOAD) {
                        setPendingTranslationRequest(
                            chapterId,
                            TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                        )
                    }
                }
                hasDownloadedFiles(translation) -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: pending $chapterId has downloaded files and no owner; admitting translation"
                    }
                    admitRestoredPendingTranslation(translation, current.generation)
                }
                else -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: pending $chapterId has no queue owner and no files; failing as interrupted"
                    }
                    markTranslationDownloadFailed(
                        chapterId,
                        "Interrupted before the chapter download or translation could run",
                        TranslationRequestFailureKind.INTERRUPTED,
                    )
                }
            }
        }
    }


/**
 *  Startup reconciliation for the
 * durable attempt ledger. Bounded to the caller-supplied chapter set
 * (persisted translation-queue members ∪ pending request ids ∪ active
 * stores) — NEVER a library scan. Every unresolved entry is a paid call a
 * process death interrupted; each consumes one consecutive attempt for its
 * page. A page reaching the cap records an INTERRUPTED-class durable
 * failure (never auto-retryable) and flips the page PARTIAL, and the
 * chapter's queue entry moves to the existing PAUSED state so the user
 * sees it needs attention.
 */

internal suspend fun TranslationManager.reconcileAttemptLedgersForStartupImpl(
        chapterIds: Set<Long>,
        resolveStore: TranslationStoreResolver,
    ) {
        if (chapterIds.isEmpty()) return
        chapterIds.forEach { chapterId ->
            val store = resolveStore.resolve(chapterId) ?: return@forEach
            val counters = runCatching { store.consumeUnresolvedAttemptsAtStartup() }
                .onFailure {
                    logcat(LogPriority.WARN) {
                        "T917 D9: startup attempt-ledger consume failed (fail-open): chapterId=$chapterId"
                    }
                }
                .getOrNull() ?: return@forEach
            val cappedPages = counters
                .filterValues { count -> count >= ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED }
            if (cappedPages.isEmpty()) return@forEach
            cappedPages.forEach { (pageKey, count) ->
                runCatching { store.applyAttemptCapPause(pageKey, count) }
                    .onFailure {
                        logcat(LogPriority.WARN) {
                            "T917 D9: attempt-cap pause failed (fail-open): " +
                                "chapterId=$chapterId pageKey=$pageKey"
                        }
                    }
            }
            translator.queueState.value
                .firstOrNull { entry ->
                    entry.chapter.id == chapterId && entry.status == Translation.State.QUEUE
                }
                ?.let { entry ->
                    entry.status = Translation.State.PAUSED
                    logcat(LogPriority.INFO) {
                        "T917 D9: chapter paused after repeated interrupted attempts: chapterId=$chapterId"
                    }
                }
        }
    }


internal fun TranslationManager.admitRestoredPendingTranslationImpl(translation: Translation, expectedGeneration: Long) {
        val manga = translation.manga
        val chapter = translation.chapter
        val chapterId = chapter.id ?: return
        if (!admitBatchSession(setOf(chapterId))) return
        synchronized(pendingRequestMutationLock) {
            // After a restart, the request may exist only in the durable record.
            // Cancellation or replacement changes the generation and drops admission.
            val current = pendingTranslationRequestsState.value[chapterId]
            val currentGeneration = current?.generation
                ?: pendingRequestStore.record(chapterId)?.generation
            if (currentGeneration != expectedGeneration) return
            scheduler.invalidateQueueSession(chapterId)
            markTranslationRequestPreparing(chapterId)
            translator.queueChapter(manga, chapter)
            if (queueState.value.any { it.chapter.id == chapterId }) {
                // Mark the admission one-shot so any later
                // handoff for this chapter cannot auto-start it either.
                restoreAdmittedChapterIds.add(chapterId)
                clearPendingTranslationRequest(chapterId)
            } else {
                markTranslationQueueFailureIfAcknowledged(manga, chapterId)
            }
        }
        // Deliberately no startTranslation(): restored work never auto-resumes
        // OCR/LLM; the admitted QUEUE entry is resumed by the user.
    }


internal fun TranslationManager.onMemoryPressureImpl(level: Int) {
        val pressureClass = MemoryPressurePolicy.classify(level)
        translator.onMemoryPressure(level, pressureClass)
        // The queue defers prefetch on the live memory gate; a pressure event
        // (including recovery) rearms deferred demand through reconcile. The
        // same-chapter batch-active guard keeps a suppressed window dormant.
        scheduler.reconcileReaderQueues(admissionGuard = { chapterId ->
            !isChapterBatchActive(chapterId, includePaused = true)
        })
    }


internal fun TranslationManager.startTranslationImpl() {
        if (!admitBatchSession(queueState.value.mapNotNull { it.chapter.id }.toSet())) return
        if (!translator.isRunning) {
            translator.start()
        }
        if (isAnyBatchTranslationActive) {
            TranslationForegroundService.start(context)
        }
    }


internal fun TranslationManager.pauseTranslationImpl() {
        translator.pause()
    }


internal suspend fun TranslationManager.requeueTranslationImpl(chapterId: Long, force: Boolean = false): Boolean {
        if (!admitBatchSession(setOf(chapterId))) return false
        return translator.requeueExisting(chapterId, force)
    }


internal fun TranslationManager.clearQueueImpl() {
        translator.clearQueue()
        translator.stop()
        clearAllPendingTranslationRequests()
        sessionCoordinator.finishSession()
    }


internal fun TranslationManager.getQueuedTranslationOrNullImpl(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }


internal fun TranslationManager.prioritizeChapterImpl(chapterId: Long) {
        translator.prioritizeChapter(chapterId)
    }


internal suspend fun TranslationManager.warmUpImpl() {
        pipeline.warmUp()
    }


internal fun TranslationManager.setInpaintingHardwareOverrideImpl(override: InpaintingHardwareOverride) {
        pipeline.setInpaintingHardwareOverride(override)
    }


internal fun TranslationManager.setReaderPaddleOcrComputeDeviceOverrideImpl(device: tachiyomi.domain.translation.PaddleOcrComputeDevice?) {
        pipeline.setReaderPaddleOcrComputeDeviceOverride(device)
    }


internal fun TranslationManager.admitChapterThroughQueueImpl(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long?,
        admissionContext: eu.kanade.translation.pipeline.batch.BatchAdmissionContext?,
        evictStaleQueuedEntry: Boolean,
    ): TranslationManager.AdmissionResult {
        val chapterId = chapter.id ?: return TranslationManager.AdmissionResult.QUEUE_FAILED
        if (expectedGeneration != null) {
            val current = pendingTranslationRequestsState.value[chapterId]
            if (current == null || current.generation != expectedGeneration) {
                if (evictStaleQueuedEntry) {
                    val expectedRequestGeneration = expectedGeneration
                    logcat(LogPriority.INFO) {
                        "T911 dropped stale translate admission for chapter $chapterId " +
                            "(expectedGeneration=$expectedRequestGeneration)"
                    }
                } else {
                    val expected = expectedGeneration
                    logcat(LogPriority.INFO) {
                        "T911 dropped stale translate admission for chapter $chapterId " +
                            "(expectedGeneration=$expected currentGeneration=${current?.generation})"
                    }
                }
                return TranslationManager.AdmissionResult.STALE_GENERATION
            }
        }
        scheduler.invalidateQueueSession(chapterId)
        if (evictStaleQueuedEntry) {
            evictStaleQueuedChapters(chapterId, manga.source)
        }
        markTranslationRequestPreparing(chapterId)
        translator.queueChapter(
            manga,
            chapter,
            admissionContext?.probedSourcePageCount,
            admissionContext?.sourceCountKnown ?: false,
        )
        return if (queueState.value.any { it.chapter.id == chapterId }) {
            clearPendingTranslationRequest(chapterId)
            TranslationManager.AdmissionResult.QUEUED
        } else {
            markTranslationQueueFailureIfAcknowledged(manga, chapterId)
            TranslationManager.AdmissionResult.QUEUE_FAILED
        }
    }


/**
 * When [expectedRequestGeneration] is supplied, the
 * whole admit sequence runs under the request mutation lock and is
 * aborted when the live request's generation moved (user cancel between
 * check and use). Existing callers keep the unfenced behavior.
 */

internal fun TranslationManager.translateChapterImpl(
        manga: Manga,
        chapters: Chapter,
        expectedRequestGeneration: Long? = null,
        // The trigger's admission-probe cross-check; null
        // keeps the existing no-cross-check path.
        admissionContext: eu.kanade.translation.pipeline.batch.BatchAdmissionContext? = null,
        // False for a restore-admitted request handed over by the
        // download completion — enqueue without starting.
        autoStart: Boolean = true,
    ) {
        val chapterId = chapters.id ?: return
        if (!admitBatchSession(setOf(chapterId))) return
        val admissionResult = synchronized(pendingRequestMutationLock) {
            admitChapterThroughQueue(
                manga = manga,
                chapter = chapters,
                expectedGeneration = expectedRequestGeneration,
                admissionContext = admissionContext,
                evictStaleQueuedEntry = true,
            )
        }
        if (admissionResult == TranslationManager.AdmissionResult.STALE_GENERATION) return
        if (autoStart) {
            // An explicit per-chapter request is the only gate
            // that re-arms a PAUSED/ERROR queue entry (queueChapter is a no-op
            // for an existing entry); a generic queue start must not resurrect
            // that work.
            queueState.value.firstOrNull {
                it.chapter.id == chapterId &&
                    (
                        it.status == Translation.State.PAUSED ||
                            it.status == Translation.State.ERROR
                        )
            }?.status = Translation.State.QUEUE
            startTranslation()
        } else {
            // Restore-admitted work is enqueued but stays paused —
            // the UI shows paused, not a fake-active spinner, and no OCR/LLM
            // runs without an explicit user action.
            queueState.value.firstOrNull {
                it.chapter.id == chapterId && it.status == Translation.State.QUEUE
            }?.status = Translation.State.PAUSED
        }
    }


internal fun TranslationManager.translateChaptersImpl(manga: Manga, chapters: List<Chapter>) {
        translateChaptersInternal(manga, chapters, expectedGenerations = null)
    }


/**
 * List admission is fenced per chapter by the request
 * generation captured by the confirmation probe. Chapters whose request
 * was cancelled or re-requested after the acknowledgement are dropped;
 * the surviving set is admitted atomically against cancel.
 *
 * [admissionContexts] carries the per-chapter
 * download-probe cross-check for subset admissions; chapters without an
 * entry keep the current no-cross-check behavior.
 */

internal fun TranslationManager.translateChaptersIfCurrentImpl(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>,
        admissionContexts: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionContext> = emptyMap(),
    ): Boolean {
        if (chapters.isEmpty()) return false
        return translateChaptersInternal(manga, chapters, expectedGenerations, admissionContexts).isNotEmpty()
    }


internal fun TranslationManager.translateChaptersInternalImpl(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>?,
        admissionContexts: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionContext> = emptyMap(),
    ): List<Chapter> {
        if (chapters.isEmpty()) return emptyList()
        if (!admitBatchSession(chapters.mapNotNull { it.id }.toSet())) return emptyList()
        val admitted = mutableListOf<Chapter>()
        synchronized(pendingRequestMutationLock) {
            chapters.forEach { chapter ->
                val chapterId = chapter.id ?: return@forEach
                val expectedGeneration = expectedGenerations?.get(chapterId)
                if (expectedGenerations != null && expectedGeneration == null) return@forEach
                val result = admitChapterThroughQueue(
                    manga = manga,
                    chapter = chapter,
                    expectedGeneration = expectedGeneration,
                    admissionContext = admissionContexts[chapterId],
                    evictStaleQueuedEntry = false,
                )
                if (result == TranslationManager.AdmissionResult.QUEUED) {
                    // An explicit selection re-arms a PAUSED/ERROR
                    // queue entry (queueChapter is a no-op for an existing
                    // entry); a generic queue start must not resurrect that work.
                    queueState.value.firstOrNull {
                        it.chapter.id == chapterId &&
                            (
                                it.status == Translation.State.PAUSED ||
                                    it.status == Translation.State.ERROR
                                )
                    }?.status = Translation.State.QUEUE
                    admitted += chapter
                }
            }
        }
        if (admitted.isNotEmpty()) {
            startTranslation()
        }
        return admitted
    }


/**
 * A translation-queue admission rejection is never a
 * download failure. The phase is [TranslationRequestPhase.ADMISSION_FAILED]
 * with a typed kind (source unsupported / config invalid / admission).
 */

internal fun TranslationManager.markTranslationQueueFailureIfAcknowledgedImpl(manga: Manga, chapterId: Long) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId)) {
            val failureKind = translationQueueAdmissionFailureKind(
                sourceIsHttp = sourceManager.get(manga.source) is HttpSource,
                configValid = translator.isQueueConfigValid(),
            )
            setPendingTranslationRequest(
                chapterId,
                TranslationRequestPhase.ADMISSION_FAILED,
                "Translation could not be queued",
                failureKind,
            )
        }
    }


/**
 * TachiyomiAT bug 3 fix: preflight check for an explicit Start Batch action.
 * Returns the running conflict (if any) so the UI can ask the user before
 * cancelling in-flight work on a different chapter of the same source.
 *
 * Stale QUEUE entries are NOT reported here; they are evicted automatically
 * by [translateChapter] since dropping a not-yet-started queue entry never
 * loses accepted artifacts. Only an actively TRANSLATING chapter needs user
 * confirmation because cancelling it mid-OCR/inpaint discards the in-flight
 * page's native work.
 */

internal fun TranslationManager.translateChapterPreflightImpl(manga: Manga, chapter: Chapter): ChapterQueuePreflight {
        val chapterId = chapter.id
            ?: return ChapterQueuePreflight.NoConflict
        val view = queueState.value.map { it.toQueuedChapterView() }
        val conflict = findRunningSameSourceConflict(view, chapterId, manga.source)
            ?: return ChapterQueuePreflight.NoConflict
        return ChapterQueuePreflight.RunningConflict(
            chapterId = conflict.chapterId,
            chapterName = conflict.chapterName,
        )
    }


/**
 * Evicts every queued (status == QUEUE) chapter of [sourceId] other than
 * [keepChapterId] from the batch queue. Preserves accepted artifacts:
 * [removeFromTranslationQueue] only drops the queue entry; the chapter's
 * ChapterTranslationStore and its persisted OCR/inpaint/translation data
 * stay intact, so a later Start Batch on that chapter resumes via the
 * BatchResumeGateDecider's artifact scan without redoing completed work.
 */

internal fun TranslationManager.evictStaleQueuedChaptersImpl(keepChapterId: Long, sourceId: Long) {
        val staleIds = staleQueuedChaptersToEvict(
            queueState.value.map { it.toQueuedChapterView() },
            keepChapterId,
            sourceId,
        ).map { it.chapterId }.toSet()
        if (staleIds.isEmpty()) return
        val stale = queueState.value
            .filter { it.chapter.id != null && it.chapter.id in staleIds }
            .map { it.chapter }
        stale.forEach { chapter ->
            logcat(LogPriority.INFO) {
                "TachiyomiAT evicting stale queued chapter ${chapter.id} (source=$sourceId) in favor of $keepChapterId; artifacts preserved"
            }
            removeFromTranslationQueue(chapter)
        }
    }


/**
 * TachiyomiAT bug 3 fix: cancels an actively running translation of
 * [chapterId] (same source) so a subsequent [translateChapter] can start on
 * a different chapter. Used after the UI confirms a [ChapterQueuePreflight.RunningConflict].
 * Cancels in-flight page jobs, durably clears transient queue pages
 * (preserving rendered/terminal artifacts), and drops the queue entry.
 * Suspending: awaits each leg in order; callers must invoke from a coroutine.
 */

internal suspend fun TranslationManager.cancelRunningChapterForReplaceImpl(chapterId: Long) {
        val chapter = queueState.value
            .firstOrNull { it.chapter.id == chapterId }
            ?.chapter
            ?: return
        scheduler.cancelPageTranslations(chapterId)
        activeStores.get(chapterId)?.let { store ->
            store.clearTransientQueuePages("Replaced by another chapter's batch")
        }
        removeFromTranslationQueue(chapter)
    }
