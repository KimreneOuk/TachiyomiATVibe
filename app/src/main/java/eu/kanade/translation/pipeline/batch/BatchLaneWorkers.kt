package eu.kanade.translation.pipeline.batch

import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.AttemptOrigin
import eu.kanade.translation.persistence.chapter.TranslationProvider
import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.ocr.PageRecognitionEngine
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.LowMemoryDecodeDeferredException
import eu.kanade.translation.pipeline.LowMemoryRecognitionDeferredException
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.TranslationPipeline.Companion.SINGLE_PAGE_TIMEOUT_MS
import eu.kanade.translation.scheduling.CrossOriginBitmapBudget
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.translator.contextual.StableBlockIds
import eu.kanade.translation.translator.contextual.StreamingChunkPlanner
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.retry.classifyProviderFailure
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Native-lane admission runner: the workers' nested `withNativeLane` calls are
 * served by the pipeline's own `withNativeLane` through this seam ( phase 20).
 */
interface NativeLaneRunner {
    suspend fun <T> run(
        timeoutMs: Long,
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        block: suspend () -> T,
    ): T?
}

/**
 *  Phase 20.5: the batch lane workers moved verbatim from
 * `TranslationPipeline.translateBatch` ( phase 20): `nativeWorker`,
 * `translatorWorker`. The closure web became class state — every captured
 * registry/identity/frontier instance is injected here as the SAME instance
 * the batch shell holds; pipeline-provided collaborators (native lane,
 * OCR/inpaint/decode/persist helpers, abort) arrive as constructor lambdas
 * behind same-name private members.
 *
 *  zero-legacy: the legacy AI-chunk engine (`translateChunkAi`,
 * `completeChunklessPage`, the chunk-completion bridge) had no surviving
 * caller after the SequentialBatchCoordinator deletion — the PROFILE lane
 * translates through [ProfileEnvelopeExecutor] — and was removed.
 */
internal class BatchLaneWorkers(
    private val store: ChapterTranslationStore,
    private val manga: Manga,
    private val chapter: Chapter,
    private val source: HttpSource,
    private val provider: TranslationProvider,
    private val translationPreferences: TranslationPreferences,
    private val tracker: TranslationBatchProgressTracker?,
    private val batchGeneration: Long,
    private val isAi: Boolean,
    private val textTranslatorFn: () -> TextTranslator,
    private val recognitionEngineFn: () -> PageRecognitionEngine,
    private val fromLang: TextRecognizerLanguage,
    private val orderedStreams: List<Pair<String, () -> InputStream>>,
    private val resolvedNaturalPageIndexes: Map<String, Int>,
    private val requestedOutputTokens: Int,
    private val chunkProfile: TranslationContextChunkPlanner.Profile,
    private val translationRegistry: ConcurrentHashMap<String, PageTranslation>,
    private val batchWriteIdentities: ConcurrentHashMap<String, BatchWriteIdentity>,
    private val aborted: AtomicBoolean,
    private val expectedBatchFingerprints: BatchExpectedFingerprints,
    private val contextFrontier: BatchContextFrontier,
    private val resumePlanner: BatchResumePlanner,
    private val writeGate: BatchWriteGate,
    private val renderJoin: BatchRenderJoin,
    private val heldBitmapRegistry: HeldBitmapRegistry,
    private val nativeLane: NativeLaneRunner,
    private val markPageTimedOutFn: suspend (Manga, Chapter, HttpSource, String) -> Unit,
    private val analyzePageFn: suspend (String, Bitmap, DecodedPage, ChapterTranslationStore, BatchExpectedFingerprints) -> PageTranslation,
    private val decodePageBitmapForTranslationFn: suspend (String, () -> InputStream) -> DecodedPage?,
    private val preflightInpaintGateFn: (Bitmap, String) -> Unit,
    private val inpaintPageFn: suspend (
        String,
        Bitmap,
        PageTranslation,
        String?,
        suspend (String, (PageTranslation?) -> PageTranslation) -> ChapterTranslationStore.PatchResult,
    ) -> PageTranslation,
    private val retryInpaintDownscaledFn: suspend (
        Manga,
        Chapter,
        HttpSource,
        String,
        List<Pair<String, () -> InputStream>>,
        DecodedPage,
        PageTranslation,
    ) -> PageTranslation,
    private val persistCleanedBitmapFn: suspend (
        PageTranslation,
        Bitmap,
        UniFile?,
        String,
        String,
        ChapterTranslationStore,
        Long,
        Long,
        Long?,
        ChapterTranslationStore.PatchPrecondition?,
    ) -> ChapterTranslationStore.PageSnapshot?,
    private val abortBatchCandidateFn: suspend (String, String) -> Unit,
    private val scheduleListener: BatchScheduleListener = BatchScheduleListener.NOOP,

) {

    val chunkCounter = AtomicLong(0L)

    private val ensureCompanionDir: suspend () -> UniFile? = {
        provider.getCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
    }

    private val batchPagePlans get() = resumePlanner.batchPagePlans

    private val textTranslator get() = textTranslatorFn()

    private val recognitionEngine get() = recognitionEngineFn()

    private fun plannedTranslationNeedsWork(pageKey: String): Boolean =
        resumePlanner.plannedTranslationNeedsWork(pageKey)

    /**
     * Whether the natural-order predecessor of [pageKey] has reached a terminal
     * translation outcome in the store at call time (READY, or SKIPPED for a
     * textless page). The first page has no predecessor and counts as
     * unblocked. A missing or still-pending/failed predecessor keeps the
     * historical ordered-context skip — a failed predecessor must continue to
     * strand its successors rather than let them translate with a context gap.
     */
    private fun naturalOrderPredecessorTerminal(pageKey: String): Boolean {
        val index = orderedStreams.indexOfFirst { it.first == pageKey }
        if (index <= 0) return true
        val predecessorKey = orderedStreams[index - 1].first
        val predecessor = store.state.value[predecessorKey] ?: return false
        return predecessor.translationStatus in setOf(StageStatus.READY, StageStatus.SKIPPED)
    }

    private fun translationFailureFence(pageKey: String): Boolean =
        resumePlanner.translationFailureFence(pageKey)

    private fun recordReusableContextPage(pageKey: String, page: PageTranslation) =
        resumePlanner.recordReusableContextPage(pageKey, page)

    private fun recordContextPage(
        pageKey: String,
        page: PageTranslation,
        terminalFailure: Boolean = false,
    ) = resumePlanner.recordContextPage(pageKey, page, terminalFailure)

    private suspend fun resumeGate(page: PageTranslation?) = resumePlanner.resumeGate(page)

    private suspend fun guardedBatchUpdate(
        pageKey: String,
        description: String,
        stage: BatchStage?,
        update: (PageTranslation?) -> PageTranslation,
    ) = writeGate.guardedBatchUpdate(pageKey, description, stage, update)

    private fun refreshBatchIdentity(pageKey: String, snapshot: ChapterTranslationStore.PageSnapshot) =
        writeGate.refreshBatchIdentity(pageKey, snapshot)

    private fun batchWritePrecondition(pageKey: String): ChapterTranslationStore.PatchPrecondition? =
        writeGate.batchWritePrecondition(pageKey)

    private suspend fun persistAiFailureOrThrow(
        pageKey: String,
        page: PageTranslation,
        failure: ProviderFailure,
        retryable: Boolean,
        partialCandidate: Boolean,
        envelopeId: String?,
        missingBlockIds: Set<String>,
    ) = writeGate.persistAiFailureOrThrow(
        pageKey = pageKey,
        page = page,
        failure = failure,
        retryable = retryable,
        partialCandidate = partialCandidate,
        envelopeId = envelopeId,
        missingBlockIds = missingBlockIds,
    )

    private suspend fun releaseBatchLease(pageKey: String) =
        writeGate.releaseBatchLease(pageKey)

    private suspend fun tryRender(pageKey: String) = renderJoin.tryRender(pageKey)

    private suspend fun abortBatchCandidate(pageKey: String, reason: String) =
        abortBatchCandidateFn(pageKey, reason)

    private suspend fun <T> withNativeLane(
        timeoutMs: Long,
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        block: suspend () -> T,
    ): T? = nativeLane.run(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block)

    private suspend fun markPageTimedOut(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) =
        markPageTimedOutFn(manga, chapter, source, pageKey)

    private suspend fun analyzePage(
        fileName: String,
        bitmap: Bitmap,
        decoded: DecodedPage,
        store: ChapterTranslationStore,
        batchFingerprints: BatchExpectedFingerprints,
    ): PageTranslation = analyzePageFn(fileName, bitmap, decoded, store, batchFingerprints)

    private suspend fun decodePageBitmapForTranslation(fileName: String, streamFn: () -> InputStream): DecodedPage? =
        decodePageBitmapForTranslationFn(fileName, streamFn)

    private fun preflightInpaintGate(bitmap: Bitmap, fileName: String) =
        preflightInpaintGateFn(bitmap, fileName)

    private suspend fun inpaintPage(
        fileName: String,
        bitmap: Bitmap,
        pageTranslation: PageTranslation,
        batchFingerprint: String?,
        guardedWrite: suspend (String, (PageTranslation?) -> PageTranslation) -> ChapterTranslationStore.PatchResult,
    ): PageTranslation = inpaintPageFn(fileName, bitmap, pageTranslation, batchFingerprint, guardedWrite)

    private suspend fun retryInpaintDownscaled(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streams: List<Pair<String, () -> InputStream>>,
        decoded: DecodedPage,
        pageTranslation: PageTranslation,
    ): PageTranslation = retryInpaintDownscaledFn(manga, chapter, source, pageKey, streams, decoded, pageTranslation)

    private suspend fun persistCleanedBitmap(
        pageTranslation: PageTranslation,
        cleanedBitmap: Bitmap,
        companionDir: UniFile?,
        pageKey: String,
        chapterName: String,
        store: ChapterTranslationStore,
        sourceId: Long,
        mangaId: Long,
        chapterId: Long?,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition?,
    ): ChapterTranslationStore.PageSnapshot? = persistCleanedBitmapFn(
        pageTranslation,
        cleanedBitmap,
        companionDir,
        pageKey,
        chapterName,
        store,
        sourceId,
        mangaId,
        chapterId,
        expectedPrecondition,
    )

    // ---- TachiyomiAT Phase 5: consolidated sequential coordinator ----
    // The batch schedule is driven by the coordinator pass (one serialized
    // native lane and one ordered translation lane;  zero-legacy
    // removed the SBC AI-chunk machinery). The pipeline supplies the adapter
    // implementations of [NativeLaneWorker] / [TranslatorLaneWorker] that
    // reuse the existing OCR/inpaint/persist/translate/render helpers above,
    // so the heavy Android/ONNX/HTTP logic is unchanged — only the schedule
    // is centralized.
    //
    // TranslatorComputeClass drives lane routing: ML Kit (LOCAL_COMPUTE) is kept
    // inline on the native lane so its on-device inference never overlaps native
    // OCR/inpaint; remote providers (REMOTE_IO) overlap their network wait with
    // the current chunk's native inpaint after the OCR barrier has released.

    val streamsByKey = LinkedHashMap(orderedStreams.toMap())
    val nativeWorker = object : NativeLaneWorker {
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            coroutineContext.ensureActive()
            if (aborted.get()) return null
            val streamFn = streamsByKey[pageKey] ?: return null
            // Session admission prevents a reader-owned page from reaching
            // this batch worker. A defensive foreign-owner denial remains a
            // fail-closed skip; the coordinator publishes the incomplete
            // OCR corpus and pauses rather than rescan another session.
            val batchLease = when (val acquisition = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)) {
                is LeaseAcquisition.Denied -> {
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT batch skips ${acquisition.owner}-owned page under session exclusion: " +
                            "chapter=${chapter.name} pageKey=$pageKey reason=${acquisition.reason}"
                    }
                    return null
                }
                is LeaseAcquisition.Granted -> acquisition.lease
            }
            batchWriteIdentities[pageKey] = BatchWriteIdentity(
                generation = batchLease.generation,
                pageVersion = batchLease.pageVersion,
                leaseToken = batchLease.token,
                candidateGenerationId = batchLease.candidateGenerationId,
                dependencyFingerprint = batchLease.dependencyFingerprint,
                artifactPageVersion = batchLease.artifactPageVersion,
            )
            val existing = store.state.value[pageKey]
            val gate = resumeGate(existing)
            if (gate == BatchResumeGate.SKIP_ALL) {
                // Fully durable (OCR+inpaint done): no decode/slot; render reloads disk.
                val p = existing!!
                translationRegistry[pageKey] = p
                val translationNeedsWork = plannedTranslationNeedsWork(pageKey)
                if (!translationNeedsWork && !translationFailureFence(pageKey)) {
                    tryRender(pageKey)
                    recordReusableContextPage(pageKey, p)
                }
                val translationTerminal = p.translationStatus == StageStatus.READY ||
                    p.translationStatus == StageStatus.PARTIAL ||
                    p.translationStatus == StageStatus.SKIPPED
                if (translationTerminal && !translationNeedsWork) return null
                val persisted = store.snapshot(pageKey)
                refreshBatchIdentity(pageKey, persisted)
                return OcrReadyPageRef(
                    pageKey = pageKey,
                    pageIndex = pageIndex,
                    generation = batchGeneration,
                    blockFingerprints = persisted.blockFingerprints,
                    leaseToken = batchWriteIdentities[pageKey]?.leaseToken,
                    candidateGenerationId = persisted.candidateGenerationId,
                    dependencyFingerprint = persisted.dependencyFingerprint,
                    artifactPageVersion = persisted.artifactPageVersion,
                )
            }
            var producedTarget: PageTranslation? = null
            var producedDecoded: DecodedPage? = null

            val latest = store.state.value[pageKey]
            val innerGate = resumeGate(latest)
            if (innerGate == BatchResumeGate.SKIP_ALL) {
                val p = latest!!
                translationRegistry[pageKey] = p
                if (!plannedTranslationNeedsWork(pageKey) && !translationFailureFence(pageKey)) {
                    tryRender(pageKey)
                    recordReusableContextPage(pageKey, p)
                }
                val persisted = store.snapshot(pageKey)
                refreshBatchIdentity(pageKey, persisted)
                return OcrReadyPageRef(
                    pageKey = pageKey,
                    pageIndex = pageIndex,
                    generation = batchGeneration,
                    blockFingerprints = persisted.blockFingerprints,
                    leaseToken = batchWriteIdentities[pageKey]?.leaseToken,
                    candidateGenerationId = persisted.candidateGenerationId,
                    dependencyFingerprint = persisted.dependencyFingerprint,
                    artifactPageVersion = persisted.artifactPageVersion,
                )
            }
            if (innerGate == BatchResumeGate.INPAINT_ONLY) {
                val p = latest ?: PageTranslation(sourceFileName = pageKey)
                translationRegistry[pageKey] = p
                val persisted = store.snapshot(pageKey)
                refreshBatchIdentity(pageKey, persisted)
                return OcrReadyPageRef(
                    pageKey = pageKey,
                    pageIndex = pageIndex,
                    generation = batchGeneration,
                    blockFingerprints = persisted.blockFingerprints,
                    leaseToken = batchWriteIdentities[pageKey]?.leaseToken,
                    candidateGenerationId = persisted.candidateGenerationId,
                    dependencyFingerprint = persisted.dependencyFingerprint,
                    artifactPageVersion = persisted.artifactPageVersion,
                )
            }

            // S2: Decode off the native permit (F.2); M4: CrossOriginBitmapBudget permit
            CrossOriginBitmapBudget.acquireBatchPermit()
            val decoded = try {
                decodePageBitmapForTranslation(pageKey, streamFn)
            } catch (deferred: LowMemoryDecodeDeferredException) {
                CrossOriginBitmapBudget.releaseBatchPermit()
                tracker?.markOcrFailed(pageKey, deferred.message ?: "Decode deferred")
                val deferredWrite = guardedBatchUpdate(pageKey, "batch decode deferred", BatchStage.OCR) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        ocrStatus = StageStatus.FAILED
                        errorMessage = deferred.message
                        retryCount = (it?.retryCount ?: 0) + 1
                        attemptCount = (it?.attemptCount ?: 0) + 1
                        updatedAt = System.currentTimeMillis()
                    }
                }
                if (deferredWrite is ChapterTranslationStore.PatchResult.Rejected) {
                    abortBatchCandidate(pageKey, "decode deferral rejected: ${deferredWrite.reason}")
                }
                return null
            } catch (t: Throwable) {
                CrossOriginBitmapBudget.releaseBatchPermit()
                throw t
            } ?: run {
                CrossOriginBitmapBudget.releaseBatchPermit()
                tracker?.markOcrFailed(pageKey, "Failed to decode page: null bitmap")
                val failedWrite = guardedBatchUpdate(pageKey, "batch decode failed", BatchStage.OCR) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        ocrStatus = StageStatus.FAILED
                        errorMessage = "Failed to decode page: null bitmap"
                        retryCount = (it?.retryCount ?: 0) + 1
                        attemptCount = (it?.attemptCount ?: 0) + 1
                        updatedAt = System.currentTimeMillis()
                    }
                }
                if (failedWrite is ChapterTranslationStore.PatchResult.Rejected) {
                    abortBatchCandidate(pageKey, "decode failure rejected: ${failedWrite.reason}")
                }
                return null
            }
            producedDecoded = decoded

            //  Phase 4: page queueing on the pipeline native lane. The
            // span settles when admission grants (first statement inside the
            // lane) and is re-settled (idempotently) on timeout/failure.
            val nativeQueueSpan = TranslationTrace.beginStage(TranslationTraceStage.NATIVE_QUEUE)
            val ocrLaneResult: Unit? = try {
                withNativeLane(
                    timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    pageKey = pageKey,
                    onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                ) {
                    nativeQueueSpan.end()
                    try {
                        tracker?.markOcrRunning(pageKey)
                        val analyzed = analyzePage(
                            pageKey,
                            decoded.bitmap,
                            decoded,
                            store,
                            expectedBatchFingerprints,
                        )
                        tracker?.markOcrDone(pageKey)
                        translationRegistry[pageKey] = analyzed
                        producedTarget = translationRegistry[pageKey]
                    } catch (deferred: LowMemoryRecognitionDeferredException) {
                        val t = translationRegistry[pageKey]
                        if (t != null) {
                            t.inpaintStatus = StageStatus.FAILED
                            t.errorMessage = deferred.message
                        }
                        tracker?.markInpaintFailed(pageKey, deferred.message ?: "Recognition deferred")
                    } catch (rejected: BatchPersistenceRejectedException) {
                        tracker?.markOcrFailed(pageKey, rejected.message ?: "OCR persistence rejected")
                        abortBatchCandidate(pageKey, rejected.message ?: "OCR persistence rejected")
                        throw rejected
                    }
                }
            } catch (t: Throwable) {
                nativeQueueSpan.end(
                    if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                    error = t,
                )
                throw t
            }
            if (ocrLaneResult == null) {
                // Timeout path: admission never granted (or onTimeout ran);
                // the queue span settles exactly once via CAS.
                nativeQueueSpan.end(TranslationTraceOutcome.TIMEOUT)
            }
            val target = producedTarget ?: run {
                releaseDecodedPage(producedDecoded)
                return null
            }

            val persisted = store.snapshot(pageKey)
            refreshBatchIdentity(pageKey, persisted)
            return OcrReadyPageRef(
                pageKey = pageKey,
                pageIndex = pageIndex,
                generation = batchGeneration,
                blockFingerprints = persisted.blockFingerprints,
                leaseToken = batchWriteIdentities[pageKey]?.leaseToken,
                candidateGenerationId = persisted.candidateGenerationId,
                dependencyFingerprint = persisted.dependencyFingerprint,
                artifactPageVersion = persisted.artifactPageVersion,
                nativeHandoff = producedDecoded,
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {
            runInpaintStage(pageKey, null)
        }

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            val latest = store.state.value[pageKey] ?: return
            val target = translationRegistry[pageKey] ?: latest
            val plannedInpaint = batchPagePlans[pageKey]?.stages?.firstOrNull {
                it.stage == BatchStage.INPAINT
            }
            val plannedCleanedPresent = if (
                plannedInpaint?.decision == eu.kanade.translation.model.StageDecision.REUSE &&
                latest.cleanedImageName != null
            ) {
                withContext(Dispatchers.IO) {
                    provider.findPageCleanedImage(
                        manga.title,
                        source,
                        chapter.name,
                        chapter.scanlator,
                        latest.cleanedImageName!!,
                    )?.let { it.exists() && it.length() > 0L } == true
                }
            } else {
                false
            }
            if (plannedInpaint?.decision == eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE ||
                plannedInpaint?.decision == eu.kanade.translation.model.StageDecision.REUSE &&
                plannedCleanedPresent
            ) {
                target.cleanedImageName = latest.cleanedImageName
                target.inpaintingModeUsed = latest.inpaintingModeUsed
                target.inpaintStatus = latest.inpaintStatus
                target.inpaintRevision = latest.inpaintRevision
                target.inpaintFingerprint = latest.inpaintFingerprint
                target.inpaintMaskBoxes = latest.inpaintMaskBoxes
                target.cleanedBitmap = null
                return
            }
            // SKIP_ALL-resume durable cleaned image shortcut: keep existing result.
            val hasDurableCleaned = latest.cleanedImageName != null &&
                latest.inpaintStatus == StageStatus.READY &&
                latest.hasCurrentInpaintResult &&
                resumeGate(latest) == BatchResumeGate.SKIP_ALL
            if (hasDurableCleaned) {
                target.cleanedImageName = latest.cleanedImageName
                target.inpaintingModeUsed = latest.inpaintingModeUsed
                target.inpaintStatus = StageStatus.READY
                target.cleanedBitmap = null
                return
            }
            val handedOffDecoded = nativeHandoff as? DecodedPage
            var decoded: DecodedPage? = handedOffDecoded
            // S2: Decode off the native permit (F.2); M4: CrossOriginBitmapBudget permit
            var newlyDecoded = false
            if (decoded == null) {
                val streamFn = streamsByKey[pageKey] ?: return
                CrossOriginBitmapBudget.acquireBatchPermit()
                try {
                    decoded = decodePageBitmapForTranslation(pageKey, streamFn)
                    newlyDecoded = true
                } catch (t: Throwable) {
                    CrossOriginBitmapBudget.releaseBatchPermit()
                    throw t
                }
            }
            if (decoded == null) {
                if (newlyDecoded) {
                    CrossOriginBitmapBudget.releaseBatchPermit()
                }
                tracker?.markInpaintFailed(pageKey, "Null bitmap for inpaint")
                return
            }

            //  Phase 4: page queueing on the pipeline native lane for the
            // inpaint pass. The span settles on admission (first statement in
            // the lane) and is re-settled (idempotently) on timeout/failure.
            val inpaintQueueSpan = TranslationTrace.beginStage(TranslationTraceStage.NATIVE_QUEUE)
            val inpaintLaneResult: Unit?
            try {
                inpaintLaneResult = withNativeLane(
                    timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    pageKey = pageKey,
                    onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                ) {
                    inpaintQueueSpan.end()
                    try {
                        tracker?.markInpaintRunning(pageKey)
                        preflightInpaintGate(decoded.bitmap, pageKey)
                        inpaintPage(
                            fileName = pageKey,
                            bitmap = decoded.bitmap,
                            pageTranslation = target,
                            batchFingerprint = expectedBatchFingerprints.inpaint,
                            guardedWrite = { description, update ->
                                val result = guardedBatchUpdate(pageKey, description, BatchStage.INPAINT, update)
                                if (result is ChapterTranslationStore.PatchResult.Rejected) {
                                    throw BatchPersistenceRejectedException(
                                        pageKey = pageKey,
                                        stage = BatchDiagnosticStage.INPAINT,
                                    )
                                }
                                result
                            },
                        )
                        if (target.inpaintStatus == StageStatus.FAILED &&
                            target.cleanedBitmap == null &&
                            target.blocks.isNotEmpty()
                        ) {
                            // One bounded recovery pass reclaims memory and re-runs
                            // recognition/inpaint at a larger sample size. It never
                            // changes OCR engines or models.
                            retryInpaintDownscaled(
                                manga,
                                chapter,
                                source,
                                pageKey,
                                orderedStreams,
                                decoded,
                                target,
                            )
                        }
                        if (target.inpaintStatus == StageStatus.READY) {
                            tracker?.markInpaintDone(pageKey)
                        } else {
                            tracker?.markInpaintFailed(pageKey, target.errorMessage ?: "Inpaint failed")
                        }
                    } catch (deferred: LowMemoryRecognitionDeferredException) {
                        target.inpaintStatus = StageStatus.FAILED
                        target.errorMessage = deferred.message
                        tracker?.markInpaintFailed(pageKey, deferred.message ?: "Recognition deferred")
                    } finally {
                        if (handedOffDecoded == null) releaseDecodedPage(decoded)
                    }
                }
            } catch (cancelled: CancellationException) {
                inpaintQueueSpan.end(TranslationTraceOutcome.CANCELLED, error = cancelled)
                throw cancelled
            } catch (rejected: BatchPersistenceRejectedException) {
                inpaintQueueSpan.end(TranslationTraceOutcome.FAILURE, error = rejected)
                tracker?.markInpaintFailed(pageKey, rejected.message ?: "Inpaint persistence rejected")
                abortBatchCandidate(pageKey, rejected.message ?: "Inpaint persistence rejected")
                throw rejected
            }
            if (inpaintLaneResult == null) {
                // Timeout path: admission never granted (or onTimeout ran);
                // the queue span settles exactly once via CAS.
                inpaintQueueSpan.end(TranslationTraceOutcome.TIMEOUT)
            }
            // Persist the cleaned bitmap off the native permit (matches the prior
            // producer's post-inpaint publication step) and hand it to render.
            val cleaned = target.cleanedBitmap
            if (cleaned != null) {
                val companionDir = ensureCompanionDir()
                //  Phase 4: cleaned-image publication substage with typed
                // outcome; settles even when the persist call throws.
                val persistSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.CLEANED_PERSIST,
                    lane = TranslationTraceLane.STORAGE,
                )
                suspend fun publishOnce(): ChapterTranslationStore.PageSnapshot? {
                    // Mirror guardedBatchUpdate's stale-cache recovery: the
                    // group-commit flush can publish this page's OWN staged
                    // translation record DURING inpaint, bumping
                    // artifactPageVersion after the lease-time identity was
                    // captured — the stale precondition was our own cache, not
                    // a foreign writer, and it rejected EVERY page
                    // deterministically (2026-09-16 bubble-cleaning failures).
                    // A lease/generation change still rejects below (
                    // ownership fence) — the manual lane is never preempted.
                    batchWriteIdentities[pageKey]?.let { identity ->
                        val live = store.snapshot(pageKey)
                        if (live.generation == identity.generation &&
                            live.leaseToken == identity.leaseToken
                        ) {
                            refreshBatchIdentity(pageKey, live)
                        }
                    }
                    return persistCleanedBitmap(
                        target,
                        cleaned,
                        companionDir,
                        pageKey,
                        chapter.name,
                        store,
                        source.id,
                        manga.id,
                        chapter.id,
                        expectedPrecondition = batchWritePrecondition(pageKey),
                    )
                }
                var published = try {
                    publishOnce()
                } catch (t: Throwable) {
                    persistSpan.end(
                        if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                        error = t,
                    )
                    throw t
                }
                if (published == null) {
                    //  coexistence: the precondition raced a concurrent
                    // writer (typically the reader's live translate-on-view
                    // lane committing blocks). ONE fresh-snapshot retry; a
                    // second rejection means the page is being rewritten
                    // underneath us continuously and we YIELD it (typed
                    // contention) instead of livelocking the drain.
                    published = try {
                        publishOnce()
                    } catch (t: Throwable) {
                        persistSpan.end(
                            if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                            error = t,
                        )
                        throw t
                    }
                }
                persistSpan.end(
                    if (published == null) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS,
                )
                published?.let { refreshBatchIdentity(pageKey, it) }
                if (published == null) {
                    // Never render an in-memory cleaned bitmap whose durable
                    // publication failed. The reader must remain on the original
                    // and receive a retryable failure instead of a transient overlay.
                    target.cleanedBitmap = null
                    tracker?.markInpaintFailed(pageKey, "Cleaned image publication rejected")
                    abortBatchCandidate(pageKey, "cleaned image publication rejected (yielded to concurrent writer)")
                    throw BatchContentionRejectedException(
                        yieldPageKey = pageKey,
                        stage = BatchDiagnosticStage.INPAINT,
                    )
                }
                //  no-render batch design: the batch never runs an in-pass
                // render stage — the reader draws the text overlay on demand
                // from the committed blocks. A page that just reached
                // inpaint-terminal with translated blocks is therefore
                // display-complete as-is, but the durable record kept
                // renderStatus PENDING, so hasRenderedResult never fired: the
                // page never promoted to a committed display bundle (no
                // pageSnapshotFileName), the reader's displayImageName gate
                // stayed null and every batch page showed the ORIGINAL forever
                // (2026-09-16 field report), while the reader's stranded-page
                // sweep healed these healthy pages one by one. Stamp render
                // terminal here: the durable write carries hasRenderedResult,
                // which fires the committed promotion and flips the reader gate.
                if (target.translationStatus == StageStatus.READY ||
                    target.translationStatus == StageStatus.PARTIAL
                ) {
                    val stamp = guardedBatchUpdate(pageKey, "batch render terminal stamp", BatchStage.LAYOUT) { current ->
                        (current ?: target).apply {
                            if (renderStatus == StageStatus.PENDING && cleanedImageName != null) {
                                renderStatus = StageStatus.READY
                                updatedAt = System.currentTimeMillis()
                            }
                        }
                    }
                    if (stamp is ChapterTranslationStore.PatchResult.Rejected) {
                        // Non-fatal: the page stays translation/inpaint-terminal
                        // and a later run's adoption stamp (E2) retries it.
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT batch render terminal stamp rejected: " +
                                "pageKey=$pageKey reason=${stamp.reason}"
                        }
                    } else {
                        tracker?.markRenderDone(pageKey)
                    }
                }
            } else {
                val terminal = guardedBatchUpdate(pageKey, "batch inpaint terminal state", BatchStage.INPAINT) {
                    (it ?: target).apply {
                        inpaintStatus = target.inpaintStatus
                        errorMessage = target.errorMessage
                        updatedAt = System.currentTimeMillis()
                    }
                }
                if (terminal is ChapterTranslationStore.PatchResult.Rejected) {
                    abortBatchCandidate(pageKey, "inpaint terminal write rejected: ${terminal.reason}")
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.INPAINT,
                    )
                }
            }
            heldBitmapRegistry.holdCleaned(pageKey, target.cleanedBitmap)
            target.cleanedBitmap = null
        }

        override fun releaseNativeHandoff(ref: OcrReadyPageRef) {
            val decoded = ref.nativeHandoff as? DecodedPage ?: return
            releaseDecodedPage(decoded)
        }

        private fun releaseDecodedPage(decoded: DecodedPage?) {
            if (decoded != null) {
                val wasRecycled = try {
                    decoded.bitmap.isRecycled
                } catch (_: Exception) {
                    false
                }
                if (!wasRecycled) {
                    try {
                        decoded.bitmap.recycle()
                    } catch (_: Exception) {}
                    CrossOriginBitmapBudget.releaseBatchPermit()
                }
            }
            BitmapPool.releaseAll()
            try {
                recognitionEngine.reclaimPooledMemory()
            } catch (_: Exception) {}
        }
    }

    // The translator lane performs per-page translation (standard path) as a
    // SINGLE serialized lane so only one provider request is in flight at a
    // time.  zero-legacy: the legacy SBC AI-chunk machinery (streaming
    // chunk completion, admission/probe buffering) had no surviving caller and
    // was deleted — the PROFILE lane translates through ProfileEnvelopeExecutor.
    val translatorWorker = object : TranslatorLaneWorker {
        // AI contextual streaming state, owned by this lane.
        val planner: StreamingChunkPlanner? =
            if (isAi) {
                StreamingChunkPlanner(
                    requestedOutputTokens = requestedOutputTokens,
                    profile = chunkProfile,
                    naturalPageIndexes = resolvedNaturalPageIndexes,
                )
            } else {
                null
            }

        private val pendingAiEmissions = mutableListOf<StreamingChunkPlanner.Emission>()
        private val contextBlockedPages = linkedSetOf<String>()
        private var standardOutcome: ChunkCompletionOutcome? = null

        override suspend fun translateOutcome(ref: OcrReadyPageRef): ChunkCompletionOutcome {
            standardOutcome = null
            translate(ref)
            return standardOutcome ?: ChunkCompletionOutcome.Completed(setOf(ref.pageKey))
        }

        override suspend fun translate(ref: OcrReadyPageRef) {
            val pageKey = ref.pageKey
            val identity = batchWriteIdentities[pageKey]
            if (identity == null ||
                (ref.leaseToken != null && identity.leaseToken != ref.leaseToken) ||
                (ref.candidateGenerationId != null && identity.candidateGenerationId != ref.candidateGenerationId) ||
                (ref.dependencyFingerprint != null && identity.dependencyFingerprint != ref.dependencyFingerprint)
            ) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT batch translation reference rejected (stale lease): pageKey=$pageKey"
                }
                return
            }
            val p = translationRegistry[pageKey] ?: store.state.value[pageKey]?.detachedCopy()?.also {
                translationRegistry[pageKey] = it
            } ?: return
            val plannedTranslation = batchPagePlans[pageKey]?.stages?.firstOrNull {
                it.stage == BatchStage.TRANSLATION
            }
            val dependencyReadyAfterNative = p.ocrStatus == StageStatus.READY ||
                p.ocrStatus == StageStatus.TEXTLESS
            //  Phase 6: the plan's PRIOR_PAGE_INCOMPLETE marker is a
            // batch-start snapshot; whether the predecessor is STILL incomplete
            // is a live question. On the ordered standard lane a predecessor
            // that has already reached a terminal translation outcome must not
            // keep blocking this page — the static block stranded every page
            // after the first on multi-page chapters.
            val priorPageBlocksStandardTranslation = !isAi &&
                plannedTranslation?.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE &&
                !naturalOrderPredecessorTerminal(pageKey)
            val completedAiPageAfterPriorGap = isAi &&
                plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                plannedTranslation?.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE &&
                p.translationStatus in setOf(StageStatus.READY, StageStatus.SKIPPED) &&
                (
                    expectedBatchFingerprints.translation == null ||
                        p.translationFingerprint == expectedBatchFingerprints.translation
                    )
            //  Phase 6: standard-lane twin of the AI gap check. The plan
            // snapshot cannot see commits that happen while the pass runs —
            // the batch's own unblocked pages, or a manual tap that finished
            // mid-pass ( manual output is authoritative and must never be
            // re-paid) — so this check reads the LIVE store rather than the
            // possibly-stale registry snapshot, and is deliberately
            // origin/fingerprint-blind: plan-time REUSE evidence still governs
            // re-translation of pre-pass state.
            val livePage = store.state.value[pageKey]
            val completedStandardPageAfterPriorGap = !isAi &&
                plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                plannedTranslation?.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE &&
                livePage?.translationStatus in setOf(StageStatus.READY, StageStatus.SKIPPED)
            val retryableTranslation = plannedTranslation?.decision ==
                eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE
            val shouldSkipTranslation = plannedTranslation?.decision ==
                eu.kanade.translation.model.StageDecision.REUSE ||
                plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE ||
                plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.FAILED ||
                plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.FAILED_TERMINAL ||
                retryableTranslation &&
                plannedTranslation?.retryEligible != true ||
                completedAiPageAfterPriorGap ||
                completedStandardPageAfterPriorGap ||
                plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                (
                    priorPageBlocksStandardTranslation ||
                        !dependencyReadyAfterNative
                    )
            if (shouldSkipTranslation) {
                // Native/layout-only resume, an ordered context wait,
                // or a failed page never invokes the provider. The
                // render join still receives its branch completion.
                when {
                    plannedTranslation?.decision in setOf(
                        eu.kanade.translation.model.StageDecision.FAILED,
                        eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                    ) ||
                        p.translationStatus == StageStatus.FAILED &&
                        store.durableFailure(pageKey)?.status != ArtifactStageStatus.FAILED_RETRYABLE -> tracker?.markAiFailed(
                        pageKey,
                        p.activeError ?: "Translation stage failed",
                    )
                    p.translationStatus == StageStatus.READY ||
                        p.translationStatus == StageStatus.PARTIAL ||
                        p.translationStatus == StageStatus.SKIPPED -> tracker?.markAiSucceeded(pageKey)
                }
                if (isAi &&
                    plannedTranslation?.decision !in setOf(
                        eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                        eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                        eu.kanade.translation.model.StageDecision.FAILED,
                    )
                ) {
                    if (p.translationStatus != StageStatus.PARTIAL) {
                        recordContextPage(
                            pageKey,
                            p,
                            terminalFailure = false,
                        )
                    }
                }
                return
            }
            // Persisted OCR order is the stable identity source. Assign IDs before the
            // user-selected reading-order sort so RTL/LTR changes never rename a block.
            StableBlockIds.assign(p, ref.pageIndex)
            val readingOrder = translationPreferences.translationReadingOrder().get()
            p.blocks = eu.kanade.translation.util.TranslationBlockSorter.sort(p.blocks, fromLang, readingOrder)
            translationRegistry[pageKey] = p
            val sourceBlocks = p.blocks.count { it.text.isNotBlank() }
            if (sourceBlocks == 0) {
                p.translationStatus = StageStatus.SKIPPED
                p.renderStatus = StageStatus.SKIPPED
                val textless = guardedBatchUpdate(pageKey, "batch textless translation commit", BatchStage.TRANSLATION) { p }
                if (textless is ChapterTranslationStore.PatchResult.Rejected) {
                    abortBatchCandidate(pageKey, "textless commit rejected: ${textless.reason}")
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.TRANSLATION,
                    )
                }
                tracker?.markTranslateSkipped(pageKey)
                tracker?.markAiSucceeded(pageKey)
                tracker?.markRenderSkipped(pageKey)
                if (p.inpaintStatus == StageStatus.SKIPPED || p.inpaintStatus == StageStatus.READY) {
                    heldBitmapRegistry.recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    releaseBatchLease(pageKey)
                }
                return
            }
            if (isAi && planner != null) {
                if (contextFrontier.blocksLaterAi(pageKey)) {
                    val gapIndex = contextFrontier.gapIndex
                    val reason = "blocked by non-textless terminal context gap at index $gapIndex"
                    // Keep this page as the first unresolved anchor.
                    // The coordinator will process the planner's
                    // rejection at the chunk barrier, after the
                    // already-admitted prefix has settled.
                    contextBlockedPages += pageKey
                    tracker?.markAiPaused(pageKey, reason)
                    tracker?.markTranslatePaused(pageKey, reason)
                    return
                }
                // Contextual path: accept this page into the streaming planner,
                // but retain every emission until the coordinator releases the
                // current OCR barrier. Buffering is not translation completion.
                val emission = planner.accept(pageKey, p)
                planner.rejectedPages[pageKey]?.let { reason ->
                    tracker?.markAiFailed(pageKey, reason)
                } ?: run {
                    tracker?.markAiBuffered(pageKey)
                }
                emission?.let { emission ->
                    pendingAiEmissions += emission
                }
            } else {
                // Standard (per-page) path: translate, validate, persist, render.
                var succeeded = false
                var failedOutcome: ChunkCompletionOutcome? = null
                //  Phase 3 ( design §3.2): durable attempt entry BEFORE
                // the paid call; resolved on any completed call (success or
                // typed provider failure). Write failure is fail-open.
                runCatching {
                    store.recordAttemptStart(
                        pageKey = pageKey,
                        providerKeyHash = ShortHash.hash(textTranslator.javaClass.name),
                        origin = AttemptOrigin.BATCH,
                        generation = store.currentGeneration,
                    )
                }.onFailure {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT D9: batch attempt-ledger record failed (fail-open): pageKey=$pageKey"
                    }
                }
                try {
                    tracker?.markAiRunning(pageKey)
                    tracker?.markTranslateRunning(pageKey)
                    try {
                        textTranslator.translatePage(pageKey, p)
                    } catch (e: CancellationException) {
                        // Process death / scope kill: the entry stays unresolved
                        // for startup reconciliation.
                        throw e
                    } catch (t: Throwable) {
                        runCatching { store.resolveAttempt(pageKey) }
                        throw t
                    }
                    runCatching { store.resolveAttempt(pageKey) }
                    TranslationBlockValidation.applyTo(p)
                    val s = p.translationStatus
                    when (s) {
                        StageStatus.READY -> tracker?.markTranslateDone(pageKey)
                        StageStatus.PARTIAL -> {
                            val failure = ProviderFailure(
                                kind = ProviderFailureKind.PROTOCOL,
                                retryability = ProviderFailureRetryability.PAUSE,
                                safeSummary = "translation output is partial",
                            )
                            persistAiFailureOrThrow(
                                pageKey = pageKey,
                                page = p,
                                failure = failure,
                                retryable = true,
                                partialCandidate = true,
                                envelopeId = null,
                                missingBlockIds = emptySet(),
                            )
                            tracker?.markTranslatePaused(pageKey, failure.safeSummary)
                            tracker?.markAiPaused(pageKey, failure.safeSummary)
                            failedOutcome = ChunkCompletionOutcome.Paused(
                                anchorPageKey = pageKey,
                                retryablePageKeys = setOf(pageKey),
                                failure = failure,
                                reason = failure.safeSummary,
                            )
                        }
                        else -> {
                            val failure = ProviderFailure(
                                kind = ProviderFailureKind.PROTOCOL,
                                retryability = ProviderFailureRetryability.TERMINAL,
                                safeSummary = "translation returned an unknown state",
                            )
                            persistAiFailureOrThrow(
                                pageKey = pageKey,
                                page = p,
                                failure = failure,
                                retryable = false,
                                partialCandidate = false,
                                envelopeId = null,
                                missingBlockIds = emptySet(),
                            )
                            tracker?.markTranslateFailed(pageKey, failure.safeSummary)
                            tracker?.markAiFailed(pageKey, failure.safeSummary)
                            failedOutcome = ChunkCompletionOutcome.Failed(
                                anchorPageKey = pageKey,
                                terminalPageKeys = setOf(pageKey),
                                failure = failure,
                                reason = failure.safeSummary,
                            )
                        }
                    }
                    succeeded = s == StageStatus.READY
                    if (succeeded) tracker?.markAiSucceeded(pageKey)
                } catch (e: BatchPersistenceRejectedException) {
                    throw e
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val failure = if (e is ProviderFailureException) e.failure else classifyProviderFailure(e)
                    val retryable = failure.retryability != ProviderFailureRetryability.TERMINAL
                    persistAiFailureOrThrow(
                        pageKey = pageKey,
                        page = p,
                        failure = failure,
                        retryable = retryable,
                        partialCandidate = false,
                        envelopeId = null,
                        missingBlockIds = emptySet(),
                    )
                    if (retryable) {
                        tracker?.markTranslatePaused(pageKey, failure.safeSummary)
                        tracker?.markAiPaused(pageKey, failure.safeSummary)
                        failedOutcome = ChunkCompletionOutcome.Paused(
                            anchorPageKey = pageKey,
                            retryablePageKeys = setOf(pageKey),
                            failure = failure,
                            reason = failure.safeSummary,
                        )
                    } else {
                        tracker?.markTranslateFailed(pageKey, failure.safeSummary)
                        tracker?.markAiFailed(pageKey, failure.safeSummary)
                        failedOutcome = ChunkCompletionOutcome.Failed(
                            anchorPageKey = pageKey,
                            terminalPageKeys = setOf(pageKey),
                            failure = failure,
                            reason = failure.safeSummary,
                        )
                    }
                    logcat(LogPriority.ERROR, e) { "TachiyomiAT batch translate failed: $pageKey" }
                }
                if (failedOutcome != null) {
                    standardOutcome = failedOutcome
                } else if (succeeded) {
                    val persisted = guardedBatchUpdate(pageKey, "batch translation final commit", BatchStage.TRANSLATION) {
                        (it ?: p).apply {
                            translationStatus = p.translationStatus
                            translationError = null
                            blocks = p.blocks.toMutableList()
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                    if (persisted is ChapterTranslationStore.PatchResult.Rejected) {
                        val reason = "translation commit rejected: ${persisted.reason}"
                        tracker?.markTranslateFailed(pageKey, reason)
                        tracker?.markAiFailed(pageKey, reason)
                        standardOutcome = ChunkCompletionOutcome.PersistenceRejected(
                            anchorPageKey = pageKey,
                            stage = BatchDiagnosticStage.TRANSLATION,
                            reason = "Batch persistence publication rejected",
                        )
                    } else {
                        if (!isAi) {
                            val pairs = p.blocks.mapNotNull { block ->
                                val s = block.text.trim()
                                val t = block.translation?.trim().orEmpty()
                                if (s.isBlank() || t.isBlank() || t == s) null else s to t
                            }
                            if (pairs.isNotEmpty()) {
                                store.foldPageContribution(pageKey, pairs)
                            }
                        }
                        standardOutcome = ChunkCompletionOutcome.Completed(setOf(pageKey))
                    }
                }
            }
        }
    }
}
