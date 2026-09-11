package eu.kanade.translation.pipeline.batch
import eu.kanade.translation.translator.retry.AiChunkOutcome
import eu.kanade.translation.translator.retry.translateAiChunkWithAdaptiveRetry

import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.TranslationPipeline.Companion.SINGLE_PAGE_TIMEOUT_MS
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.AttemptOrigin
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.diagnostics.TranslationScheduleTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.pipeline.LowMemoryDecodeDeferredException
import eu.kanade.translation.pipeline.LowMemoryRecognitionDeferredException
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.translator.contextual.ChapterGlossaryBuilder
import eu.kanade.translation.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.contextual.StableBlockIds
import eu.kanade.translation.translator.contextual.StreamingChunkPlanner
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.translator.retry.applyAiChunkOutcomeToPages
import eu.kanade.translation.translator.retry.classifyProviderFailure
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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

/**
 * Native-lane admission runner: the workers' nested `withNativeLane` calls are
 * served by the pipeline's own `withNativeLane` through this seam (T909 phase 20).
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
 * T909 Phase 20.5: the batch lane workers moved verbatim from
 * `TranslationPipeline.translateBatch` (T909 phase 20): `nativeWorker`,
 * `translatorWorker`, `translateChunkAi`, `completeChunklessPage`. The closure
 * web became class state — every captured registry/identity/frontier instance
 * is injected here as the SAME instance the batch shell holds; pipeline-provided
 * collaborators (native lane, OCR/inpaint/decode/persist helpers, abort) arrive
 * as constructor lambdas behind same-name private members.
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
    private val contextualTranslator: ContextualTextTranslator?,
    private val textTranslatorFn: () -> TextTranslator,
    private val recognitionEngineFn: () -> PageRecognitionEngine,
    private val fromLang: TextRecognizerLanguage,
    private val orderedStreams: List<Pair<String, () -> InputStream>>,
    private val resolvedNaturalPageIndexes: Map<String, Int>,
    private val requestedOutputTokens: Int,
    private val chunkProfile: TranslationContextChunkPlanner.Profile,
    private val glossaryStats: ChapterGlossaryBuilder.Stats,
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
    private val deferredPages: MutableMap<String, PageWriteOrigin?>? = null,

    /**
     * T922 Phase 4: the shared batch schedule. Provider-envelope work is
     * measured ONCE at schedule scope here (plan §4.4 batch, §9) — never
     * multiplied into page runs; per-page provider waiting is attributed
     * separately by the coordinator. Null when tracing is off → fail-open.
     */
    private val scheduleTrace: TranslationScheduleTrace? = null,
) {

    val chunkCounter = AtomicLong(0L)

    private val ensureCompanionDir: suspend () -> UniFile? = {
        provider.getCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
    }

    // Same-name wiring for the injected collaborators: the moved bodies call
    // these as plain named functions / property-style reads.
    private val rollingContext get() = resumePlanner.rollingContext

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


    /**
     * Translates one AI envelope and commits its completed pages in
     * natural order. The rolling context is the bounded window of
     * recent source=>target pairs (voice/terminology continuity);
     * a page whose output is a structural refusal fails alone — it
     * never cascades to later pages of the envelope.
     *
     * T922 Phase 4: the envelope duration is measured ONCE at SCHEDULE scope
     * (a stage=translate lane=provider schedule event carrying the existing
     * opaque envelope ID) — it is never multiplied into page runs. Per-page
     * provider waiting is attributed separately by the coordinator's
     * translate spans. The span settles with a typed outcome on every exit.
     */
    suspend fun translateChunkAi(
        chunk: TranslationContextChunk,
        rolling: String,
    ): ChunkCompletionOutcome {
        val envelopeSpan = scheduleTrace?.beginStage(
            TranslationTraceStage.TRANSLATE,
            lane = TranslationTraceLane.PROVIDER,
            provider = TranslationTraceProvider.REMOTE,
            items = chunk.blockCount,
        )
        val envelopeToken = BatchTranslationDiagnostics.traceEnvelopeToken(chunk.pages.keys)
        val outcome = try {
            translateChunkAiTraced(chunk, rolling)
        } catch (t: Throwable) {
            envelopeSpan?.end(
                outcome = if (t is CancellationException) {
                    TranslationTraceOutcome.CANCELLED
                } else {
                    TranslationTraceOutcome.FAILURE
                },
                error = t,
                envelope = envelopeToken,
            )
            throw t
        }
        envelopeSpan?.end(
            outcome = when (outcome) {
                is ChunkCompletionOutcome.Completed -> TranslationTraceOutcome.SUCCESS
                is ChunkCompletionOutcome.Paused -> TranslationTraceOutcome.PAUSE
                is ChunkCompletionOutcome.Failed -> TranslationTraceOutcome.FAILURE
                is ChunkCompletionOutcome.Unexpected -> TranslationTraceOutcome.FAILURE
                is ChunkCompletionOutcome.PersistenceRejected -> TranslationTraceOutcome.PERSISTENCE_REJECTED
            },
            envelope = envelopeToken,
        )
        return outcome
    }

    private suspend fun translateChunkAiTraced(
        chunk: TranslationContextChunk,
        rolling: String,
    ): ChunkCompletionOutcome {
        coroutineContext.ensureActive()
        val ct = contextualTranslator ?: return ChunkCompletionOutcome.Completed()

        fun firstUnresolved(completed: Set<String>): String? = chunk.pages.keys
            .sortedBy { resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE }
            .firstOrNull { it !in completed }

        fun protocolFailure(reason: String): ProviderFailure = ProviderFailure(
            kind = ProviderFailureKind.PROTOCOL,
            retryability = ProviderFailureRetryability.TERMINAL,
            safeSummary = reason,
            requestId = BatchTranslationDiagnostics.envelopeId(chunk.pages.keys),
        )

        contextFrontier.gapIndex?.let { gapIndex ->
            val blocked = chunk.pages.keys.sortedBy { resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE }.filter { pageKey ->
                contextFrontier.blocksLaterAi(pageKey)
            }
            if (blocked.isNotEmpty()) {
                val reason = "blocked by non-textless terminal context gap at index $gapIndex"
                val anchor = blocked.first()
                // The terminal predecessor already owns the durable error. This
                // later page is only the first blocked admission; recording another
                // failure here would synthesize a tail error and make the user fix a
                // page that was never sent to the provider.
                return ChunkCompletionOutcome.Failed(
                    anchorPageKey = anchor,
                    terminalPageKeys = emptySet(),
                    failure = protocolFailure(reason),
                    reason = reason,
                )
            }
        }

        val glossaryText = ChapterGlossaryBuilder.formatGlossary(glossaryStats.build())
        val contextualChunk = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = rolling,
            requestedOutputTokens = requestedOutputTokens,
            profile = chunkProfile,
            glossary = glossaryText,
        )
        var admissionFailure: String? = null
        var admissionFailurePageKey: String? = null
        admission@ for (pk in chunk.pages.keys) {
            val p = translationRegistry[pk] ?: continue
            val running = guardedBatchUpdate(pk, "batch translation running", BatchStage.TRANSLATION) {
                (it ?: p).apply {
                    translationStatus = StageStatus.RUNNING
                    errorMessage = null
                    updatedAt = System.currentTimeMillis()
                }
            }
            if (running is ChapterTranslationStore.PatchResult.Rejected) {
                // T924 device fix: STOP at the first rejection — the envelope
                // is not admissible, and continuing burns one serialized store
                // write (≈1s SAF publication each) per remaining page while
                // the outcome is already decided.
                admissionFailure = "translation admission rejected for $pk: ${running.reason}"
                admissionFailurePageKey = pk
                break@admission
            }
            tracker?.markTranslateRunning(pk)
            tracker?.markAiRunning(pk)
        }
        admissionFailure?.let { reason ->
            // T924 device fix: an admission rejection is a local CAS conflict
            // (stale write identity, or a foreign writer holding the page) —
            // never a provider protocol failure. The guarded-write heal in
            // BatchWriteGate already absorbs same-lease drift, so reaching
            // here means the page is genuinely contended: classify it as a
            // retryable PAUSE (T918 affordance on the anchor, siblings keep
            // their committed work for the next pass) instead of the previous
            // TERMINAL failure that durably failed the anchor and cancelled
            // every sibling without a single provider call.
            val failure = protocolFailure(reason).copy(
                retryability = ProviderFailureRetryability.PAUSE,
            )
            val anchor = admissionFailurePageKey ?: firstUnresolved(emptySet()) ?: return ChunkCompletionOutcome.Failed(
                failure = failure,
                reason = reason,
            )
            val page = translationRegistry[anchor]
            if (page != null) {
                persistAiFailureOrThrow(
                    pageKey = anchor,
                    page = page,
                    failure = failure,
                    retryable = true,
                    partialCandidate = false,
                    envelopeId = null,
                    missingBlockIds = emptySet(),
                )
            }
            tracker?.markTranslatePaused(anchor, reason)
            tracker?.markAiPaused(anchor, reason)
            return ChunkCompletionOutcome.Paused(
                anchorPageKey = anchor,
                retryablePageKeys = setOf(anchor),
                failure = failure,
                reason = reason,
            )
        }
        BatchTranslationDiagnostics.envelopeLifecycle(
            phase = BatchEnvelopeLifecycle.ADMITTED,
            pageKeys = chunk.pages.keys,
            expectedItemCount = chunk.blockCount,
        )
        try {
            val adaptiveOutcome = translateAiChunkWithAdaptiveRetry(
                translator = ct,
                chunk = contextualChunk,
                requestedOutputTokens = requestedOutputTokens,
                profile = chunkProfile,
                label = "stream-${chunkCounter.incrementAndGet()}",
                retryDepth = 0,
            )
            val orderedChunkKeys = chunk.pages.keys.sortedBy {
                resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE
            }
            val reportedCompletedKeys = adaptiveOutcome.completedPageKeys
                .filter { it in chunk.pages }
                .toSet()
            // The provider outcome may contain accepted blocks from a later page,
            // but natural context and durable promotion are prefix-only. Never
            // publish a later page across the first unresolved anchor.
            val completedKeys = orderedChunkKeys
                .takeWhile(reportedCompletedKeys::contains)
                .toSet()
            val anchor = when (adaptiveOutcome) {
                is eu.kanade.translation.translator.retry.AiChunkOutcome.Complete -> null
                is eu.kanade.translation.translator.retry.AiChunkOutcome.Paused,
                is eu.kanade.translation.translator.retry.AiChunkOutcome.Terminal,
                -> firstUnresolved(completedKeys)
            }
            val applyKeys = if (anchor == null) {
                completedKeys
            } else {
                completedKeys + anchor
            }
            applyAiChunkOutcomeToPages(
                outcome = adaptiveOutcome,
                pages = translationRegistry,
                pageIndexes = resolvedNaturalPageIndexes,
                pageKeys = applyKeys,
            )
            val orderedCompletion = orderedChunkKeys.filter { it in completedKeys }
            val committedPages = linkedMapOf<String, PageTranslation>()
            for (pk in orderedCompletion) {
                val p = translationRegistry[pk] ?: continue
                val refused = p.blocks.any { block ->
                    block.text.isNotBlank() &&
                        TranslationResponseFaithfulness.isStructuralRefusal(block.translation)
                }
                if (refused) {
                    val failure = ProviderFailure(
                        kind = ProviderFailureKind.REFUSAL,
                        retryability = ProviderFailureRetryability.TERMINAL,
                        safeSummary = "provider refused translation",
                        requestId = adaptiveOutcome.envelopeId,
                    )
                    persistAiFailureOrThrow(
                        pageKey = pk,
                        page = p,
                        failure = failure,
                        retryable = false,
                        partialCandidate = false,
                        envelopeId = adaptiveOutcome.envelopeId,
                        missingBlockIds = emptySet(),
                    )
                    tracker?.markTranslateFailed(pk, "provider refusal")
                    tracker?.markAiFailed(pk, "provider refusal")
                    return ChunkCompletionOutcome.Failed(
                        anchorPageKey = pk,
                        completedPageKeys = committedPages.keys,
                        terminalPageKeys = setOf(pk),
                        failure = failure,
                        reason = failure.safeSummary,
                    )
                }
                val status = TranslationBlockValidation.applyTo(p)
                when (status) {
                    StageStatus.READY -> {
                        tracker?.markTranslateDone(pk)
                    }
                    StageStatus.PARTIAL -> {
                        val failure = protocolFailure("translation output remained partial")
                        persistAiFailureOrThrow(
                            pageKey = pk,
                            page = p,
                            failure = failure,
                            retryable = true,
                            partialCandidate = true,
                            envelopeId = adaptiveOutcome.envelopeId,
                            missingBlockIds = adaptiveOutcome.missingBlockIds,
                        )
                        tracker?.markTranslatePaused(pk, failure.safeSummary)
                        tracker?.markAiPaused(pk, failure.safeSummary)
                        return ChunkCompletionOutcome.Paused(
                            anchorPageKey = pk,
                            completedPageKeys = committedPages.keys,
                            retryablePageKeys = setOf(pk),
                            failure = failure,
                            reason = failure.safeSummary,
                        )
                    }
                    StageStatus.FAILED -> {
                        // Validation details may include provider/user text. Keep the
                        // durable diagnostic provider-neutral and safe to persist.
                        val failure = protocolFailure("translation output failed validation")
                        persistAiFailureOrThrow(
                            pageKey = pk,
                            page = p,
                            failure = failure,
                            retryable = false,
                            partialCandidate = false,
                            envelopeId = adaptiveOutcome.envelopeId,
                            missingBlockIds = adaptiveOutcome.missingBlockIds,
                        )
                        tracker?.markTranslateFailed(pk, failure.safeSummary)
                        tracker?.markAiFailed(pk, failure.safeSummary)
                        return ChunkCompletionOutcome.Failed(
                            anchorPageKey = pk,
                            completedPageKeys = committedPages.keys,
                            terminalPageKeys = setOf(pk),
                            failure = failure,
                            reason = failure.safeSummary,
                        )
                    }
                }
                val persisted = guardedBatchUpdate(pk, "batch translation chunk commit", BatchStage.TRANSLATION) {
                    (it ?: p).apply {
                        translationStatus = p.translationStatus
                        if (status == StageStatus.FAILED) {
                            translationError = p.translationError
                            retryCount = p.retryCount
                            attemptCount = p.attemptCount
                        }
                        if (it != null && it !== p) {
                            blocks = p.blocks.toMutableList()
                        }
                        updatedAt = System.currentTimeMillis()
                    }
                }
                if (persisted is ChapterTranslationStore.PatchResult.Rejected) {
                    val reason = "translation commit rejected: ${persisted.reason}"
                    tracker?.markTranslateFailed(pk, reason)
                    tracker?.markAiFailed(pk, reason)
                    abortBatchCandidate(pk, "translation commit rejected: ${persisted.reason}")
                    throw BatchPersistenceRejectedException(
                        pageKey = pk,
                        stage = BatchDiagnosticStage.TRANSLATION,
                    )
                }
                if (status == StageStatus.READY || status == StageStatus.PARTIAL) {
                    tracker?.markAiSucceeded(pk)
                }
                if (status == StageStatus.READY) recordContextPage(pk, p)
                committedPages[pk] = p
                tryRender(pk)
            }
            // Accumulate this envelope's committed pairs into the chapter
            // glossary and persist it.
            var translatedPairs = 0
            committedPages.values.forEach { page ->
                page.blocks.forEach { b ->
                    glossaryStats.add(b.text, b.translation)
                    if (b.translation.isNotBlank()) translatedPairs++
                }
            }
            val newGlossary = glossaryStats.build()
            store.updateGlossary(newGlossary)
            logcat(LogPriority.INFO) {
                "TachiyomiAT batch stage2-AI chunk: translatedPairs=$translatedPairs " +
                    "glossaryEntries=${newGlossary.size} committed=${committedPages.size} " +
                    "of ${orderedCompletion.size}"
            }
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = when (adaptiveOutcome) {
                    is eu.kanade.translation.translator.retry.AiChunkOutcome.Complete ->
                        BatchEnvelopeLifecycle.SUCCEEDED
                    is eu.kanade.translation.translator.retry.AiChunkOutcome.Paused,
                    is eu.kanade.translation.translator.retry.AiChunkOutcome.Terminal,
                    -> BatchEnvelopeLifecycle.FAILED
                },
                pageKeys = chunk.pages.keys,
                expectedItemCount = chunk.blockCount,
                receivedItemCount = committedPages.values.sumOf { page ->
                    page.blocks.count { block -> block.translation.isNotBlank() }
                },
                reason = when (adaptiveOutcome) {
                    is eu.kanade.translation.translator.retry.AiChunkOutcome.Complete ->
                        BatchDiagnosticReason.SUCCESS
                    is eu.kanade.translation.translator.retry.AiChunkOutcome.Paused ->
                        BatchDiagnosticReason.TRANSIENT_FAILURE
                    is eu.kanade.translation.translator.retry.AiChunkOutcome.Terminal ->
                        BatchDiagnosticReason.TERMINAL_FAILURE
                },
            )
            when (adaptiveOutcome) {
                is eu.kanade.translation.translator.retry.AiChunkOutcome.Complete -> {
                    return ChunkCompletionOutcome.Completed(committedPages.keys)
                }
                is eu.kanade.translation.translator.retry.AiChunkOutcome.Paused -> {
                    val unresolved = anchor ?: firstUnresolved(committedPages.keys)
                    if (unresolved == null) return ChunkCompletionOutcome.Completed(committedPages.keys)
                    val page = translationRegistry[unresolved]
                    if (page != null) {
                        persistAiFailureOrThrow(
                            pageKey = unresolved,
                            page = page,
                            failure = adaptiveOutcome.failure,
                            retryable = true,
                            partialCandidate = adaptiveOutcome.partialCandidate,
                            envelopeId = adaptiveOutcome.envelopeId,
                            missingBlockIds = adaptiveOutcome.missingBlockIds,
                        )
                        tracker?.markTranslatePaused(unresolved, adaptiveOutcome.failure.safeSummary)
                        tracker?.markAiPaused(unresolved, adaptiveOutcome.failure.safeSummary)
                    }
                    return ChunkCompletionOutcome.Paused(
                        anchorPageKey = unresolved,
                        completedPageKeys = committedPages.keys,
                        retryablePageKeys = setOf(unresolved),
                        failure = adaptiveOutcome.failure,
                        nextEligibleRetryAtEpochMs = adaptiveOutcome.nextEligibleRetryAtEpochMs,
                        reason = adaptiveOutcome.failure.safeSummary,
                    )
                }
                is eu.kanade.translation.translator.retry.AiChunkOutcome.Terminal -> {
                    val unresolved = anchor ?: firstUnresolved(committedPages.keys)
                    if (unresolved == null) return ChunkCompletionOutcome.Completed(committedPages.keys)
                    val page = translationRegistry[unresolved]
                    if (page != null) {
                        persistAiFailureOrThrow(
                            pageKey = unresolved,
                            page = page,
                            failure = adaptiveOutcome.failure,
                            retryable = false,
                            partialCandidate = adaptiveOutcome.acceptedBlockIds.isNotEmpty(),
                            envelopeId = adaptiveOutcome.envelopeId,
                            missingBlockIds = adaptiveOutcome.missingBlockIds,
                        )
                        tracker?.markTranslateFailed(unresolved, adaptiveOutcome.failure.safeSummary)
                        tracker?.markAiFailed(unresolved, adaptiveOutcome.failure.safeSummary)
                    }
                    return ChunkCompletionOutcome.Failed(
                        anchorPageKey = unresolved,
                        completedPageKeys = committedPages.keys,
                        terminalPageKeys = setOf(unresolved),
                        failure = adaptiveOutcome.failure,
                        reason = adaptiveOutcome.failure.safeSummary,
                    )
                }
            }
        } catch (e: BatchPersistenceRejectedException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val failure = if (e is ProviderFailureException) {
                e.failure
            } else {
                eu.kanade.translation.translator.retry.classifyProviderFailure(e)
            }
            val reason = failure.safeSummary
            val anchor = firstUnresolved(emptySet())
            val page = anchor?.let(translationRegistry::get)
            if (page != null) {
                val retryable = failure.retryability != ProviderFailureRetryability.TERMINAL
                persistAiFailureOrThrow(
                    pageKey = anchor,
                    page = page,
                    failure = failure,
                    retryable = retryable,
                    partialCandidate = false,
                    envelopeId = null,
                    missingBlockIds = emptySet(),
                )
                if (retryable) {
                    tracker?.markTranslatePaused(anchor, reason)
                    tracker?.markAiPaused(anchor, reason)
                } else {
                    tracker?.markTranslateFailed(anchor, reason)
                    tracker?.markAiFailed(anchor, reason)
                }
            }
            logcat(LogPriority.ERROR, e) {
                "TachiyomiAT contextual batch translate failed: envelope=${
                    BatchTranslationDiagnostics.envelopeId(chunk.pages.keys)
                }"
            }
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = BatchEnvelopeLifecycle.FAILED,
                pageKeys = chunk.pages.keys,
                expectedItemCount = chunk.blockCount,
                reason = if (failure.retryability == ProviderFailureRetryability.TERMINAL) {
                    BatchDiagnosticReason.TERMINAL_FAILURE
                } else {
                    BatchDiagnosticReason.TRANSIENT_FAILURE
                },
            )
            if (anchor != null && failure.retryability != ProviderFailureRetryability.TERMINAL) {
                return ChunkCompletionOutcome.Paused(
                    anchorPageKey = anchor,
                    retryablePageKeys = setOf(anchor),
                    failure = failure,
                    reason = reason,
                )
            }
            return ChunkCompletionOutcome.Failed(
                anchorPageKey = anchor,
                terminalPageKeys = anchor?.let(::setOf).orEmpty(),
                failure = failure,
                reason = reason,
            )
        }
    }

    suspend fun completeChunklessPage(pk: String): ChunkCompletionOutcome {
        val p = translationRegistry[pk] ?: return ChunkCompletionOutcome.Completed()
        TranslationBlockValidation.applyTo(p)
        p.translationStatus = StageStatus.READY
        val persisted = guardedBatchUpdate(pk, "batch chunkless translation commit", BatchStage.TRANSLATION) {
            (it ?: p).apply {
                translationStatus = StageStatus.READY
                translationError = null
                if (it != null && it !== p) {
                    blocks = p.blocks.toMutableList()
                }
                updatedAt = System.currentTimeMillis()
            }
        }
        if (persisted is ChapterTranslationStore.PatchResult.Rejected) {
            val reason = "chunkless translation commit rejected: ${persisted.reason}"
            tracker?.markTranslateFailed(pk, reason)
            tracker?.markAiFailed(pk, reason)
            abortBatchCandidate(pk, reason)
            return ChunkCompletionOutcome.PersistenceRejected(
                anchorPageKey = pk,
                stage = BatchDiagnosticStage.TRANSLATION,
                reason = "Batch persistence publication rejected",
            )
        }
        tracker?.markTranslateDone(pk)
        tracker?.markAiSucceeded(pk)
        recordContextPage(pk, p)
        tryRender(pk)
        return ChunkCompletionOutcome.Completed(setOf(pk))
    }

    // ---- TachiyomiAT Phase 5: consolidated sequential coordinator ----
    // The batch schedule is driven by the batch coordinator pass (one
    // serialized native lane, token-adaptive whole-page AI chunks with one
    // retained OCR-only probe, one ordered translation lane, and a per-page
    // render join). The pipeline supplies the adapter implementations of
    // [NativeLaneWorker] / [TranslatorLaneWorker] / [RenderJoinWorker] that
    // reuse the existing OCR/inpaint/persist/translate/render helpers above,
    // so the heavy Android/ONNX/HTTP logic is unchanged — only the schedule
    // is centralized. Both AI and standard translators use this schedule.
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
            // Phase 3: the batch owns this page until it reaches a
            // terminal render/failure boundary. A reader-owned page is
            // skipped this pass and rescanned later — never a
            // competing writer.
            val batchLease = when (val acquisition = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)) {
                is LeaseAcquisition.Denied -> {
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT batch defers ${acquisition.owner}-owned page: " +
                            "chapter=${chapter.name} pageKey=$pageKey reason=${acquisition.reason}"
                    }
                    // T917 D3: record the deferral so the coordinator re-runs
                    // the page within the same pass once the lease is free,
                    // and emit the typed schedule event for observability.
                    deferredPages?.putIfAbsent(pageKey, acquisition.owner)
                    scheduleListener.ocrDeferred(pageKey, acquisition.owner)
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
            // T917 D3: a page this pass deferred earlier (its lease was denied)
            // that the OTHER origin has since driven to a terminal render must
            // not be re-run: the resume plans were built before that work
            // existed, so resumeGate would still plan RUN/WAIT_FOR_DEPENDENCY
            // and the provider call would repeat. Force the SKIP_ALL route —
            // the terminal state is already published, the render join keeps
            // it, and the paid call is never repeated.
            val externallyCompletedByOwner = existing != null &&
                existing.hasRenderedResult &&
                deferredPages?.remove(pageKey) != null
            val gate = if (externallyCompletedByOwner) {
                BatchResumeGate.SKIP_ALL
            } else {
                resumeGate(existing)
            }
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
            // T922 Phase 4: page queueing on the pipeline native lane. The
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
                val latest = store.state.value[pageKey]
                val innerGate = resumeGate(latest)
                if (innerGate == BatchResumeGate.SKIP_ALL) {
                    val p = latest!!
                    translationRegistry[pageKey] = p
                    if (!plannedTranslationNeedsWork(pageKey) && !translationFailureFence(pageKey)) {
                        tryRender(pageKey)
                        recordReusableContextPage(pageKey, p)
                    }
                    producedTarget = p
                    return@withNativeLane
                }
                if (innerGate == BatchResumeGate.INPAINT_ONLY) {
                    // OCR artifacts are planned for reuse: no decode or
                    // recognition runs here. The page's single remaining
                    // decode happens in the inpaint stage.
                    translationRegistry[pageKey] = latest ?: PageTranslation(sourceFileName = pageKey)
                    producedTarget = translationRegistry[pageKey]
                    return@withNativeLane
                }
                try {
                    val decoded = try {
                        decodePageBitmapForTranslation(pageKey, streamFn)
                    } catch (deferred: LowMemoryDecodeDeferredException) {
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
                        return@withNativeLane
                    } ?: run {
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
                        return@withNativeLane
                    }
                    producedDecoded = decoded
                    // Decode succeeds: the bitmap is owned by this handle until
                    // [releaseNativeResources]. OCR persistence runs here (analyzePage
                    // persists blocks + ocrStatus BEFORE inpaint), closing the OCR crash
                    // window first and producing the immutable work item offered to the
                    // translation lane below.
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
            // T922 Phase 4: page queueing on the pipeline native lane for the
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
                    val handedOffDecoded = nativeHandoff as? DecodedPage
                    var decoded: DecodedPage? = handedOffDecoded
                    try {
                        tracker?.markInpaintRunning(pageKey)
                        if (decoded == null) {
                            val streamFn = streamsByKey[pageKey] ?: return@withNativeLane
                            decoded = decodePageBitmapForTranslation(pageKey, streamFn)
                        }
                        if (decoded == null) {
                            tracker?.markInpaintFailed(pageKey, "Null bitmap for inpaint")
                            return@withNativeLane
                        }
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
                // T922 Phase 4: cleaned-image publication substage with typed
                // outcome; settles even when the persist call throws.
                val persistSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.CLEANED_PERSIST,
                    lane = TranslationTraceLane.STORAGE,
                )
                val published = try {
                    persistCleanedBitmap(
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
                } catch (t: Throwable) {
                    persistSpan.end(
                        if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                        error = t,
                    )
                    throw t
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
                    abortBatchCandidate(pageKey, "cleaned image publication rejected")
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.INPAINT,
                    )
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
                try {
                    decoded.bitmap.recycle()
                } catch (_: Exception) {}
            }
            BitmapPool.releaseAll()
            try {
                recognitionEngine.reclaimPooledMemory()
            } catch (_: Exception) {}
        }
    }

    // The translator lane owns the streaming AI chunk planner (contextual path) or
    // performs per-page translation (standard path). Either way it is a SINGLE
    // serialized lane so only one provider request is in flight at a time. AI pages
    // are admitted into the token planner during OCR, but planner emissions remain
    // buffered until the coordinator's chunk barrier calls completeChunk.
    val translatorWorker = object : TranslatorLaneWorker {
        override val usesChunkAdmission: Boolean = isAi

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
        private val handledRejectedPages = mutableSetOf<String>()
        private val contextBlockedPages = linkedSetOf<String>()
        private var lastAdmission: ChunkAdmission = ChunkAdmission.ACCEPT
        private var standardOutcome: ChunkCompletionOutcome? = null

        override suspend fun admit(ref: OcrReadyPageRef): ChunkAdmission {
            lastAdmission = ChunkAdmission.ACCEPT
            translate(ref)
            return lastAdmission
        }

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
            // T917 Phase 6: the plan's PRIOR_PAGE_INCOMPLETE marker is a
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
            // T917 Phase 6: standard-lane twin of the AI gap check. The plan
            // snapshot cannot see commits that happen while the pass runs —
            // the batch's own unblocked pages, or a manual tap that finished
            // mid-pass (D1: manual output is authoritative and must never be
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
                    lastAdmission = ChunkAdmission.PROBE
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
                    if (emission.chunk != null) {
                        // The page that caused the prior whole-page envelope to
                        // flush is the one allowed OCR-only probe. It has already
                        // been admitted to the planner, but cannot enter any later
                        // stage until the coordinator starts the next chunk.
                        lastAdmission = ChunkAdmission.PROBE
                    }
                }
            } else {
                // Standard (per-page) path: translate, validate, persist, render.
                var succeeded = false
                var failedOutcome: ChunkCompletionOutcome? = null
                // T917 Phase 3 (D9, design §3.2): durable attempt entry BEFORE
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
                        standardOutcome = ChunkCompletionOutcome.Completed(setOf(pageKey))
                    }
                }
            }
        }

        private suspend fun processAiEmission(
            emission: StreamingChunkPlanner.Emission,
        ): ChunkCompletionOutcome {
            if (emission.chunk != null) {
                return translateChunkAi(
                    emission.chunk,
                    rollingContext,
                )
            } else {
                val completed = linkedSetOf<String>()
                for (pk in emission.completedPages.sortedBy {
                    resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE
                }) {
                    when (val result = completeChunklessPage(pk)) {
                        is ChunkCompletionOutcome.Completed -> completed += result.completedPageKeys
                        is ChunkCompletionOutcome.Paused -> return result.copy(
                            completedPageKeys = completed + result.completedPageKeys,
                        )
                        is ChunkCompletionOutcome.Failed -> return result.copy(
                            completedPageKeys = completed + result.completedPageKeys,
                        )
                        is ChunkCompletionOutcome.Unexpected -> return result.copy(
                            completedPageKeys = completed + result.completedPageKeys,
                        )
                        is ChunkCompletionOutcome.PersistenceRejected -> return result.copy(
                            completedPageKeys = completed + result.completedPageKeys,
                        )
                    }
                }
                return ChunkCompletionOutcome.Completed(completed)
            }
        }

        override suspend fun completeChunkOutcome(finalChunk: Boolean): ChunkCompletionOutcome {
            val planner = planner ?: return ChunkCompletionOutcome.Completed()
            fun ChunkCompletionOutcome.completedKeys(): Set<String> = when (this) {
                is ChunkCompletionOutcome.Completed -> completedPageKeys
                is ChunkCompletionOutcome.Paused -> completedPageKeys
                is ChunkCompletionOutcome.Failed -> completedPageKeys
                is ChunkCompletionOutcome.Unexpected -> completedPageKeys
                is ChunkCompletionOutcome.PersistenceRejected -> completedPageKeys
            }
            var completed = linkedSetOf<String>()
            var result: ChunkCompletionOutcome = ChunkCompletionOutcome.Completed()
            try {
                pendingAiEmissions.toList().forEach { emission ->
                    if (result !is ChunkCompletionOutcome.Completed) return@forEach
                    val processed = processAiEmission(emission)
                    result = processed
                    completed += processed.completedKeys()
                }
            } finally {
                pendingAiEmissions.clear()
            }

            if (result is ChunkCompletionOutcome.Completed && finalChunk) {
                val flush = planner.flushRemaining()
                if (flush.finalChunk != null) {
                    val processed = processAiEmission(
                        StreamingChunkPlanner.Emission(
                            chunk = flush.finalChunk,
                            completedPages = flush.completedPages,
                        ),
                    )
                    result = processed
                    completed += processed.completedKeys()
                } else {
                    val completedFlush = linkedSetOf<String>()
                    flush.completedPages
                        .sortedBy { pk -> resolvedNaturalPageIndexes[pk] ?: Int.MAX_VALUE }
                        .forEach { pk ->
                            if (result !is ChunkCompletionOutcome.Completed) return@forEach
                            val processed = completeChunklessPage(pk)
                            result = processed
                            if (processed is ChunkCompletionOutcome.Completed) {
                                completedFlush += processed.completedPageKeys
                            }
                        }
                    completed += completedFlush
                }
            }

            if (result is ChunkCompletionOutcome.Completed && contextBlockedPages.isNotEmpty()) {
                val blocked = contextBlockedPages.minByOrNull {
                    resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE
                }!!
                contextBlockedPages.clear()
                result = ChunkCompletionOutcome.Failed(
                    anchorPageKey = blocked,
                    completedPageKeys = completed,
                    terminalPageKeys = emptySet(),
                    reason = "blocked by non-textless terminal context gap",
                )
            }

            if (result is ChunkCompletionOutcome.Completed) {
                planner.rejectedPages
                    .filterKeys { it !in handledRejectedPages }
                    .forEach { (pk, reason) ->
                        handledRejectedPages += pk
                        val rejectedPage = translationRegistry[pk] ?: return@forEach
                        val failure = ProviderFailure(
                            kind = ProviderFailureKind.PROTOCOL,
                            retryability = ProviderFailureRetryability.TERMINAL,
                            safeSummary = reason,
                            requestId = BatchTranslationDiagnostics.envelopeId(setOf(pk)),
                        )
                        persistAiFailureOrThrow(
                            pageKey = pk,
                            page = rejectedPage,
                            failure = failure,
                            retryable = false,
                            partialCandidate = false,
                            envelopeId = failure.requestId,
                            missingBlockIds = emptySet(),
                        )
                        tracker?.markTranslateFailed(pk, reason)
                        tracker?.markAiFailed(pk, reason)
                        result = ChunkCompletionOutcome.Failed(
                            anchorPageKey = pk,
                            completedPageKeys = completed,
                            terminalPageKeys = setOf(pk),
                            failure = failure,
                            reason = reason,
                        )
                    }
            }
            val finalResult = result
            return when (finalResult) {
                is ChunkCompletionOutcome.Completed -> finalResult.copy(completedPageKeys = completed)
                is ChunkCompletionOutcome.Paused -> finalResult.copy(completedPageKeys = completed + finalResult.completedPageKeys)
                is ChunkCompletionOutcome.Failed -> finalResult.copy(completedPageKeys = completed + finalResult.completedPageKeys)
                is ChunkCompletionOutcome.Unexpected -> finalResult.copy(completedPageKeys = completed + finalResult.completedPageKeys)
                is ChunkCompletionOutcome.PersistenceRejected -> finalResult.copy(completedPageKeys = completed + finalResult.completedPageKeys)
            }
        }
    }
}
