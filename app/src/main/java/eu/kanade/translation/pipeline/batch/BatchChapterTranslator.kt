package eu.kanade.translation.pipeline.batch

import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.TranslationPipeline.Companion.ONNX_PHASE_TIMEOUT_MS
import eu.kanade.translation.TranslationPipeline.Companion.SINGLE_PAGE_TIMEOUT_MS
import eu.kanade.translation.TranslationPipeline.Companion.UNKNOWN_SOURCE_FINGERPRINT
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationScheduleTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.translator.contextual.ChapterGlossaryBuilder
import eu.kanade.translation.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.translator.analysis.AnalysisChunkExecutor
import eu.kanade.translation.translator.analysis.AnalysisEngineTransport
import eu.kanade.translation.translator.providers.AiTranslator
import eu.kanade.translation.translator.providers.LmStudioTranslator
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T909 Phase 20.6: the staged batch translation shell moved verbatim from
 * `TranslationPipeline.translateBatch` (T909 phase 20). Owns the batch
 * preamble/generation protocol, engine-setup admission, component wiring
 * (write gate, resume planner, held-bitmap registry, render join, lane
 * workers, coordinator), pass-1 reconciliation, and the outer teardown
 * (lease release/cancel, `NonCancellable` flush, artifact retention,
 * `onBatchClosed`). Engine/native collaborators arrive as constructor
 * lambdas behind same-name private members.
 */
internal class BatchChapterTranslator(
    private val provider: TranslationProvider,
    private val translationPreferences: TranslationPreferences,
    private val nativeLane: NativeLaneRunner,
    private val engineRebuildMutex: Mutex,
    private val ensureEnginesBuiltFor: suspend (TextRecognizerLanguage, TextTranslatorLanguage) -> Unit,
    private val recognitionEngineFn: () -> PageRecognitionEngine,
    private val textTranslatorFn: () -> TextTranslator,
    private val computeSourceFingerprintFn: suspend (() -> InputStream) -> String?,
    private val batchExpectedFingerprintsFn: (TextRecognizerLanguage, TextTranslatorLanguage) -> BatchExpectedFingerprints,
    private val inpaintingModeFromPref: () -> InpaintingMode,
    private val releaseBatchPageLease: suspend (ChapterTranslationStore, String) -> Unit,
    private val persistPageWithOomRecovery: suspend (
        ChapterTranslationStore,
        String,
        PageTranslation,
        ChapterTranslationStore.PatchPrecondition?,
    ) -> ChapterTranslationStore.PatchResult,
    private val loadPersistedCleanedBitmap: suspend (Manga, Chapter, HttpSource, String) -> Bitmap?,
    private val deleteRetiredCleanedFile: suspend (Manga, Chapter, HttpSource, String, ChapterTranslationStore) -> Unit,
    private val markPageTimedOut: suspend (Manga, Chapter, HttpSource, String) -> Unit,
    private val analyzePage: suspend (String, Bitmap, DecodedPage, ChapterTranslationStore, BatchExpectedFingerprints) -> PageTranslation,
    private val decodePageBitmapForTranslation: suspend (String, () -> InputStream) -> DecodedPage?,
    private val preflightInpaintGate: (Bitmap, String) -> Unit,
    private val inpaintPage: suspend (
        String,
        Bitmap,
        PageTranslation,
        String?,
        suspend (String, (PageTranslation?) -> PageTranslation) -> ChapterTranslationStore.PatchResult,
    ) -> PageTranslation,
    private val retryInpaintDownscaled: suspend (
        Manga,
        Chapter,
        HttpSource,
        String,
        List<Pair<String, () -> InputStream>>,
        DecodedPage,
        PageTranslation,
    ) -> PageTranslation,
    private val persistCleanedBitmap: suspend (
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
    private val updatePageFromCurrentSnapshotFn: suspend (
        ChapterTranslationStore,
        String,
        String,
        (PageTranslation?) -> PageTranslation,
    ) -> ChapterTranslationStore.PatchResult,
    private val onBatchClosedFn: () -> (suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit)?,
) {

    // Same-name wiring for the injected collaborators: the moved bodies call
    // these as plain named functions / property-style reads.
    private val recognitionEngine get() = recognitionEngineFn()

    private val textTranslator get() = textTranslatorFn()

    private val onBatchClosed get() = onBatchClosedFn()

    private suspend fun <T> withNativeLane(
        timeoutMs: Long,
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        block: suspend () -> T,
    ): T? = nativeLane.run(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block)

    private suspend fun computeSourceFingerprint(streamFn: () -> InputStream): String? =
        computeSourceFingerprintFn(streamFn)

    private fun batchExpectedFingerprints(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): BatchExpectedFingerprints = batchExpectedFingerprintsFn(fromLang, toLang)

    /**
     * Stage-7 review F-4: per-page display evidence for the FF-01e.2a
     * TreatAsFinished gate — a page counts only when its display is durably
     * retired: a committed bundle in the artifact manifest OR a durable
     * textless terminal. A page with neither has no display to retire, so
     * [ChapterProfileBatchCoordinator.resumeCompletedOutcome] must NOT fire
     * TreatAsFinished — the chapter is not actually finished.
     */
    private fun activeRunPagesDisplayCommitted(
        store: ChapterTranslationStore,
        orderedPages: List<Pair<String, Int>>,
    ): Boolean = orderedPages.all { (pageKey, _) ->
        store.artifactManifest?.pages?.get(pageKey)?.committed != null ||
            store.state.value[pageKey]?.isTextlessTerminal == true
    }

    private suspend fun updatePageFromCurrentSnapshot(
        store: ChapterTranslationStore,
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult = updatePageFromCurrentSnapshotFn(store, pageKey, description, update)

    /**
     * TachiyomiAT: STAGED BATCH translation — the pre-translate path used by the
     * manga-screen "translate chapter" action (and anything that wants to
     * prepare a whole chapter before the reader opens). Replaces the old
     * page-1-first sequential loop.
     *
     * Stages (per the staged-batch design):
     *   1. DETECT + OCR batch  — [analyzePage] for each page in [orderedStreams],
     *      serialized by native quarantine (one page's bitmap/tensor set
     *      alive at a time). Persists ocrStatus=READY + blocks per page, so this
     *      stage is resumable (skips pages already analyzed).
     *   2. INPAINT ‖ TRANSLATE — for each page with blocks: inpaint (re-decoded
     *      bitmap, serialized under the permit) runs concurrently with
     *      textTranslator.translatePage (HTTP-only, no permit). The HTTP work
     *      overlaps the ONNX work — free parallelism, no extra peak memory.
     *   3. RENDER               — for each page with translated text + a cleaned
     *      image, recompute render colors and persist page state. The reader
     *      draws translated text live over the cleaned image.
     *
     * Memory model: one page bitmap is alive at a time (recycled after analyze,
     * re-decoded for inpaint, recycled after inpaint). Stage 2 persists each
     * cleaned image to disk (.cleaned.jpg) and releases the in-memory cleaned
     * bitmap immediately — stage 3 reloads one at a time — so the batch holds at
     * most one cleaned bitmap at any instant regardless of chapter length.
     * (Previously stage 2 kept every cleaned bitmap live across the whole chapter
     * until stage 3, which OOM'd on large chapters.) The reader is NOT open on
     * this path, so there is no concurrent display decode to race.
     *
     * [orderedStreams] is already in natural page order (1..N). Resume is a
     * per-stage decision; reader viewport and last-read position never rotate
     * the chapter batch.
     */
    suspend fun translateBatch(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        store: ChapterTranslationStore,
        orderedStreams: List<Pair<String, () -> InputStream>>,
        tracker: TranslationBatchProgressTracker? = null,
        naturalPageIndexes: Map<String, Int> = emptyMap(),
    ): eu.kanade.translation.pipeline.batch.ReconciliationResult? {
        // T922 Phase 4 (plan §4.4 batch): ONE schedule trace per batch
        // invocation, created before engine setup and closed in the OUTER
        // finally on EVERY exit (empty batch, setup timeout, OOM, pause,
        // failure, cancellation, success). The closure is exception-safe even
        // when the teardown/flush/callback region itself throws.
        val scheduleTrace = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.BATCH,
            origin = TranslationTraceMode.BATCH,
            chapterRaw = chapter.name,
            pages = orderedStreams.size.takeIf { it > 0 },
        )
        BatchTranslationDiagnostics.noteActiveSchedule(scheduleTrace)
        var scheduleOutcome = TranslationTraceOutcome.TEARDOWN_EXCEPTION
        try {
            if (orderedStreams.isEmpty()) {
                // T911 slice 3 (R4): a zero-page chapter must terminate its tracker.
                // The empty ordered set keeps this a DISTINCT zero-page failure
                // (0 total pages, aborted with a reason) — the sheet's hero renders
                // it as FAILED_NO_PAGES, never as a numeric 0/0 or a generic error.
                scheduleOutcome = TranslationTraceOutcome.SKIP
                tracker?.abort(
                    remainingPageKeys = emptySet(),
                    reason = "Chapter has no readable pages to translate",
                )
                return null
            }
            return translateBatchTraced(
                manga = manga,
                chapter = chapter,
                source = source,
                store = store,
                orderedStreams = orderedStreams,
                tracker = tracker,
                naturalPageIndexes = naturalPageIndexes,
                scheduleTrace = scheduleTrace,
                setScheduleOutcome = { scheduleOutcome = it },
            )
        } catch (e: CancellationException) {
            scheduleOutcome = TranslationTraceOutcome.CANCELLED
            throw e
        } catch (t: Throwable) {
            scheduleOutcome = TranslationTraceOutcome.TEARDOWN_EXCEPTION
            throw t
        } finally {
            // Exactly-once terminal summary; the Phase 2 handle is idempotent
            // and never throws, so teardown exceptions above cannot starve it.
            scheduleTrace.end(scheduleOutcome)
            if (BatchTranslationDiagnostics.activeSchedule === scheduleTrace) {
                BatchTranslationDiagnostics.noteActiveSchedule(null)
            }
        }
    }

    /**
     * T922 Phase 4: the traced batch body (verbatim pre-existing shell) plus
     * schedule-scoped stage measurement. [setScheduleOutcome] publishes the
     * typed terminal for every planned exit; unplanned throwaways keep the
     * [TranslationTraceOutcome.TEARDOWN_EXCEPTION] default set by the caller.
     */
    private suspend fun translateBatchTraced(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        store: ChapterTranslationStore,
        orderedStreams: List<Pair<String, () -> InputStream>>,
        tracker: TranslationBatchProgressTracker?,
        naturalPageIndexes: Map<String, Int>,
        scheduleTrace: TranslationScheduleTrace,
        setScheduleOutcome: (TranslationTraceOutcome) -> Unit,
    ): eu.kanade.translation.pipeline.batch.ReconciliationResult? {
        val resolvedNaturalPageIndexes = if (naturalPageIndexes.isNotEmpty()) {
            naturalPageIndexes
        } else {
            orderedStreams.map { it.first }
                .distinct()
                .sortedWith { left, right -> left.compareToCaseInsensitiveNaturalOrder(right) }
                .mapIndexed { index, pageKey -> pageKey to index }
                .toMap()
        }
        val batchGeneration = store.beginGeneration("batch start chapter=${chapter.name}")
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        return store.withGeneration(batchGeneration) {
            val batchWriteIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            // Paused/terminal durable failures retain their candidate and
            // manifest metadata until the next explicit retry/reset. The
            // outer teardown releases these leases rather than cancelling
            // the candidate that explains the durable state.
            val durableFailurePageKeys = ConcurrentHashMap.newKeySet<String>()
            try {
                // T922 Phase 4: schedule-scoped engine_setup stage; the span
                // settles on every exit, including a teardown-lane throw.
                val engineSetupSpan = scheduleTrace.beginStage(
                    TranslationTraceStage.ENGINE_SETUP,
                )
                var engineSetupTimedOut = false
                val engineSetupResult = try {
                    withNativeLane(
                        timeoutMs = ONNX_PHASE_TIMEOUT_MS,
                        chapterId = chapter.id,
                        chapterName = chapter.name,
                        pageKey = "<engine-setup>",
                        onTimeout = {
                            engineSetupTimedOut = true
                            store.invalidateGeneration("engine setup timeout chapter=${chapter.name}")
                        },
                    ) {
                        engineRebuildMutex.withLock {
                            ensureEnginesBuiltFor(fromLang, toLang)
                        }
                    }
                } catch (t: Throwable) {
                    engineSetupSpan.end(TranslationTraceOutcome.FAILURE, error = t)
                    throw t
                }
                engineSetupSpan.end(
                    if (engineSetupTimedOut) TranslationTraceOutcome.TIMEOUT else TranslationTraceOutcome.SUCCESS,
                )
                if (engineSetupResult == null) {
                    // Phase 3: no batch page lease outlives its run, whatever exit
                    // path the batch takes.
                    setScheduleOutcome(
                        if (engineSetupTimedOut) TranslationTraceOutcome.TIMEOUT else TranslationTraceOutcome.FAILURE,
                    )
                    store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                    // T911 slice 3: an engine-setup failure is an exceptional
                    // exit — terminate the tracker with the typed reason instead
                    // of leaving a live nonterminal tracker behind.
                    tracker?.abort(
                        remainingPageKeys = remainingAbortKeys(orderedStreams, store),
                        reason = "Translation could not start: batch engine setup failed or timed out",
                    )
                    return@withGeneration null
                }


                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch START chapter=${chapter.name} pages=${orderedStreams.size} " +
                        "engine=${recognitionEngine::class.simpleName} translator=${textTranslator::class.simpleName}"
                }

                // SequentialBatchCoordinator owns the phase barriers. AI admission feeds
                // the token planner without provider calls; one overflow page may be kept
                // as an OCR-only probe. Each actual chunk OCRs completely before its AI
                // request and native inpaint branches overlap, and render joins both.
                val isAi = translationPreferences.translationEngineCategory().get() == TranslationEngineCategory.AI_MODEL &&
                    textTranslator is ContextualTextTranslator
                val contextualTranslator = textTranslator as? ContextualTextTranslator
                val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
                    ?: TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
                val chunkProfile = if (contextualTranslator is LmStudioTranslator) {
                    TranslationContextChunkPlanner.Profile.LM_STUDIO
                } else {
                    TranslationContextChunkPlanner.Profile.DEFAULT
                }

                // Chapter-level glossary accumulator for cross-chunk name/pronoun continuity.
                // Seeded from already-translated pairs on batch resume so recurring terms
                // established before a restart still feed the glossary.
                val glossaryStats = ChapterGlossaryBuilder.Stats()
                if (isAi) {
                    store.translatedPairs().forEach { (s, t) -> glossaryStats.add(s, t) }
                }

                // T909 Phase 20.1: held-cleaned-bitmap registry moved to
                // pipeline/batch/HeldBitmapRegistry.kt. The same-name aliases below
                // keep the not-yet-moved closures reading the same registry state.
                val heldBitmapRegistry = HeldBitmapRegistry()
                val heldBitmapBytes = heldBitmapRegistry.heldBitmapBytes
                val countSlots = heldBitmapRegistry.countSlots
                val bitmapRegistry = heldBitmapRegistry.bitmapRegistry
                val translationRegistry = ConcurrentHashMap<String, PageTranslation>()
                val aborted = AtomicBoolean(false)
                val expectedBatchFingerprints = batchExpectedFingerprints(fromLang, toLang)

                // Resume context is a natural-order frontier, never a chapter-wide
                // unordered snapshot. Pages after the first missing/failed predecessor
                // remain durable and reusable, but cannot become context until traversal
                // reaches them; a non-textless terminal gap blocks later AI admission.
                val contextFrontier = BatchContextFrontier(resolvedNaturalPageIndexes)

                // Source identity is a direct input to detection and inpaint.
                // Hash the downloaded bytes before planning so replacing a
                // page under the same natural key cannot reuse old artifacts.
                // This is an I/O-only preflight; no detector/OCR/inpaint or
                // translator work is invoked for a matching completed page.
                // T922 Phase 4: schedule-scoped source_fingerprint stage.
                val fingerprintSpan = scheduleTrace.beginStage(
                    TranslationTraceStage.SOURCE_FINGERPRINT,
                    items = orderedStreams.size,
                )
                val sourceFingerprints = try {
                    coroutineScope {
                        orderedStreams.map { (pageKey, streamFn) ->
                            async(Dispatchers.IO) {
                                pageKey to (computeSourceFingerprint(streamFn) ?: UNKNOWN_SOURCE_FINGERPRINT)
                            }
                        }.awaitAll().toMap()
                    }
                } catch (t: Throwable) {
                    fingerprintSpan.end(TranslationTraceOutcome.FAILURE, error = t)
                    throw t
                }
                fingerprintSpan.end(
                    TranslationTraceOutcome.SUCCESS,
                    items = sourceFingerprints.size,
                )

                // T909 Phase 20.3: resume planning (page plans, provenance stamping,
                // translation failure fence, context-frontier bookkeeping, resume gate)
                // moved to pipeline/batch/BatchResumePlanner.kt. The frontier is the SAME
                // instance the shell and the lane workers hold; same-name local delegates
                // below keep the not-yet-moved closures' call sites.
                val resumePlanner = BatchResumePlanner(
                    store = store,
                    provider = provider,
                    manga = manga,
                    source = source,
                    chapter = chapter,
                    orderedStreams = orderedStreams,
                    isAi = isAi,
                    sourceFingerprints = sourceFingerprints,
                    expectedBatchFingerprints = expectedBatchFingerprints,
                    contextFrontier = contextFrontier,
                    inpaintingModeFromPref = inpaintingModeFromPref,
                )
                val batchPagePlans by resumePlanner::batchPagePlans
                val rollingContext by resumePlanner::rollingContext

                // T909 Phase 20.2: the batch write gate moved to
                // pipeline/batch/BatchWriteGate.kt. It receives the SAME identity-map
                // and durable-failure-set instances the shell holds; the same-name
                // local delegates below keep the not-yet-moved closures' call sites.
                val batchWriteGate = BatchWriteGate(
                    store = store,
                    batchWriteIdentities = batchWriteIdentities,
                    durableFailurePageKeys = durableFailurePageKeys,
                    expectedBatchFingerprints = expectedBatchFingerprints,
                    stampBatchProvenance = { page, stage -> resumePlanner.stampBatchProvenance(page, stage) },
                    releaseBatchPageLeaseFn = releaseBatchPageLease,
                    persistPageWithOomRecoveryFn = persistPageWithOomRecovery,
                )

                resumePlanner.seed()

                fun recordContextPage(
                    pageKey: String,
                    page: PageTranslation,
                    terminalFailure: Boolean = false,
                ) = resumePlanner.recordContextPage(pageKey, page, terminalFailure)

                fun translationFailureFence(pageKey: String): Boolean =
                    resumePlanner.translationFailureFence(pageKey)

                fun recordReusableContextPage(pageKey: String, page: PageTranslation) =
                    resumePlanner.recordReusableContextPage(pageKey, page)

                fun plannedTranslationNeedsWork(pageKey: String): Boolean =
                    resumePlanner.plannedTranslationNeedsWork(pageKey)

                fun plannedRenderNeedsWork(pageKey: String): Boolean =
                    resumePlanner.plannedRenderNeedsWork(pageKey)

                suspend fun resumeGate(page: PageTranslation?) = resumePlanner.resumeGate(page)

                // T909 Phase 20.2: guardedBatchUpdate / refreshBatchIdentity /
                // batchWritePrecondition / persistAiFailure(OrThrow) / releaseBatchLease /
                // persistBatchPageWithOomRecovery moved to pipeline/batch/BatchWriteGate.kt.
                suspend fun guardedBatchUpdate(
                    pageKey: String,
                    description: String,
                    stage: BatchStage?,
                    update: (PageTranslation?) -> PageTranslation,
                ) = batchWriteGate.guardedBatchUpdate(pageKey, description, stage, update)

                fun refreshBatchIdentity(pageKey: String, snapshot: ChapterTranslationStore.PageSnapshot) =
                    batchWriteGate.refreshBatchIdentity(pageKey, snapshot)

                fun batchWritePrecondition(pageKey: String): ChapterTranslationStore.PatchPrecondition? =
                    batchWriteGate.batchWritePrecondition(pageKey)

                suspend fun persistAiFailureOrThrow(
                    pageKey: String,
                    page: PageTranslation,
                    failure: ProviderFailure,
                    retryable: Boolean,
                    partialCandidate: Boolean,
                    envelopeId: String?,
                    missingBlockIds: Set<String>,
                ) = batchWriteGate.persistAiFailureOrThrow(
                    pageKey = pageKey,
                    page = page,
                    failure = failure,
                    retryable = retryable,
                    partialCandidate = partialCandidate,
                    envelopeId = envelopeId,
                    missingBlockIds = missingBlockIds,
                )

                suspend fun releaseBatchLease(pageKey: String) =
                    batchWriteGate.releaseBatchLease(pageKey)

                suspend fun persistBatchPageWithOomRecovery(
                    pageKey: String,
                    pageTranslation: PageTranslation,
                ) = batchWriteGate.persistBatchPageWithOomRecovery(pageKey, pageTranslation)

                // T909 Phase 20.3: the resumeGate body moved verbatim to
                // pipeline/batch/BatchResumePlanner.kt (delegate above keeps call sites).

                suspend fun abortBatchCandidate(pageKey: String, reason: String) {
                    heldBitmapRegistry.recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    batchWriteIdentities.remove(pageKey)
                    if (pageKey in durableFailurePageKeys) {
                        releaseBatchPageLease(store, pageKey)
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT batch durable failure retained: pageKey=$pageKey reason=$reason"
                        }
                        return
                    }
                    val cancelled = store.cancelPageStageWork(pageKey, PageWriteOrigin.BATCH)
                    logcat(if (cancelled) LogPriority.INFO else LogPriority.WARN) {
                        "TachiyomiAT batch candidate aborted: pageKey=$pageKey " +
                            "cancelled=$cancelled reason=$reason"
                    }
                }

                // T909 Phase 20.4: tryRender + the render join moved to
                // pipeline/batch/BatchRenderJoin.kt (it implements RenderJoinWorker;
                // bitmap recycle sites stayed with tryRender).
                val renderJoin = BatchRenderJoin(
                    store = store,
                    manga = manga,
                    chapter = chapter,
                    source = source,
                    tracker = tracker,
                    translationRegistry = translationRegistry,
                    heldBitmapRegistry = heldBitmapRegistry,
                    resumePlanner = resumePlanner,
                    writeGate = batchWriteGate,
                    expectedBatchFingerprints = expectedBatchFingerprints,
                    loadPersistedCleanedBitmapFn = loadPersistedCleanedBitmap,
                    deleteRetiredCleanedFileFn = deleteRetiredCleanedFile,
                    abortBatchCandidateFn = ::abortBatchCandidate,
                )

                suspend fun tryRender(pageKey: String) = renderJoin.tryRender(pageKey)

                val computeClass = TranslatorComputeClass.forTranslator(textTranslator)

                // T909 Phase 20.5: the lane workers moved to
                // pipeline/batch/BatchLaneWorkers.kt (nativeWorker, translatorWorker,
                // translateChunkAi, completeChunklessPage). The closure web became class
                // state; the SAME registry/identity/frontier instances are injected.
                // T917 D3 defer-and-rescan: shared per-batch deferral record +
                // typed schedule listener. The native worker records denials
                // (and routes externally-completed rescans via the same map);
                // the coordinator re-runs recorded pages within the pass once
                // their lease is handed back.
                val deferredPages = LinkedHashMap<String, PageWriteOrigin?>()
                val batchScheduleListener = object : BatchScheduleListener() {
                    override fun ocrDeferred(pageKey: String, owner: PageWriteOrigin?) {
                        deferredPages.putIfAbsent(pageKey, owner)
                    }
                }

                val batchLaneWorkers = BatchLaneWorkers(
                    store = store,
                    manga = manga,
                    chapter = chapter,
                    source = source,
                    provider = provider,
                    translationPreferences = translationPreferences,
                    tracker = tracker,
                    batchGeneration = batchGeneration,
                    isAi = isAi,
                    contextualTranslator = contextualTranslator,
                    textTranslatorFn = { textTranslator },
                    recognitionEngineFn = { recognitionEngine },
                    fromLang = fromLang,
                    orderedStreams = orderedStreams,
                    resolvedNaturalPageIndexes = resolvedNaturalPageIndexes,
                    requestedOutputTokens = requestedOutputTokens,
                    chunkProfile = chunkProfile,
                    glossaryStats = glossaryStats,
                    translationRegistry = translationRegistry,
                    batchWriteIdentities = batchWriteIdentities,
                    aborted = aborted,
                    expectedBatchFingerprints = expectedBatchFingerprints,
                    contextFrontier = contextFrontier,
                    resumePlanner = resumePlanner,
                    writeGate = batchWriteGate,
                    renderJoin = renderJoin,
                    heldBitmapRegistry = heldBitmapRegistry,
                    nativeLane = object : NativeLaneRunner {
                        override suspend fun <T> run(
                            timeoutMs: Long,
                            chapterId: Long?,
                            chapterName: String,
                            pageKey: String,
                            onTimeout: suspend () -> Unit,
                            block: suspend () -> T,
                        ): T? = withNativeLane(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block)
                    },
                    markPageTimedOutFn = markPageTimedOut,
                    analyzePageFn = analyzePage,
                    decodePageBitmapForTranslationFn = decodePageBitmapForTranslation,
                    preflightInpaintGateFn = preflightInpaintGate,
                    inpaintPageFn = inpaintPage,
                    retryInpaintDownscaledFn = retryInpaintDownscaled,
                    persistCleanedBitmapFn = persistCleanedBitmap,
                    abortBatchCandidateFn = ::abortBatchCandidate,
                    scheduleListener = batchScheduleListener,
                    deferredPages = deferredPages,
                    scheduleTrace = scheduleTrace,
                )


                /**
                 * T924-FF-01a: THE single FF-01 dispatch point. The flag is
                 * read ONCE per run here (T924-FF-01d — the value is frozen
                 * into the flagged run's ChapterRunRecord; mid-run settings
                 * changes never re-read it, T924-FF-01e/ST-15). Flag ON
                 * constructs the T924 [ChapterProfileBatchCoordinator] (WP4
                 * shell: OCR preflight through its stop/diagnostic terminal);
                 * flag OFF constructs the legacy [SequentialBatchCoordinator]
                 * unchanged (FF-01b byte-for-byte legacy behavior).
                 *
                 * T924 F3 (wave-2 review R3/gap 8): the flagged coordinator
                 * currently stops at the OCR-preflight terminal, so dispatch
                 * additionally requires the engine-category parity of the
                 * legacy contextual-AI lane (`isAi` above):
                 * `translationEngineCategory == AI_MODEL && textTranslator is
                 * ContextualTextTranslator`. Any other engine keeps running the
                 * verbatim legacy coordinator — never a preflight-only flagged
                 * run — until the flagged path serves those stages.
                 */
                var dispatchedFlaggedLane = false
                suspend fun runBatchPass1(
                    orderedPages: List<PageKey>,
                    computeClass: TranslatorComputeClass,
                ): BatchPass1Outcome {
                    val profilePipelineEnabled = translationPreferences
                        .translationBatchProfilePipeline()
                        .get()
                    // T924 wave-2 F1 (Stage 7): the FIRST COMPLETE publisher
                    // exists, so the FF-01e resume decision tree wires in. A
                    // flag-OFF run over a chapter whose recorded run reached
                    // COMPLETE is treated as finished — the legacy schedule
                    // never re-runs it. Flag ON / no COMPLETE record keeps the
                    // dispatch below byte-identical. Stage-7 review F-4: the
                    // gate also demands per-page display evidence — a COMPLETE
                    // record over an unrendered page dispatches normally so
                    // the legacy schedule re-derives the missing display.
                    ChapterProfileBatchCoordinator.resumeCompletedOutcome(
                        record = ChapterProfileBatchCoordinator.activeRunRecordOrNull(store),
                        currentFlagOn = profilePipelineEnabled,
                        orderedPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first },
                        allPagesDisplayCommitted =
                            activeRunPagesDisplayCommitted(store, orderedPages),
                    )?.let { finished -> return finished }
                    // T924 LI-1: remember which lane this pass dispatched so the
                    // post-pass completion projection can pick the matching
                    // reconciler (flagged COMPLETED outcomes are
                    // translation-terminal without an in-pass render).
                    // Phase 4 Wave A: BOTH flagged lanes (AI + standard) end
                    // translation-terminal, so the projection keys on any
                    // non-legacy dispatch.
                    val engineCategoryIsStandard =
                        translationPreferences.translationEngineCategory().get() ==
                            TranslationEngineCategory.STANDARD
                    val dispatchKind = profilePipelineDispatchKind(
                        flagOn = profilePipelineEnabled,
                        engineCategoryIsStandard = engineCategoryIsStandard,
                        contextualAiParity = isAi,
                    )
                    dispatchedFlaggedLane =
                        dispatchKind != ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
                    // T924 Phase 4 Wave A: the injected standard translate seam —
                    // the coordinator's per-page tail calls THIS, and it
                    // delegates verbatim to `TranslatorLaneWorker.translateOutcome`
                    // (the SBC per-page bridge; usesChunkAdmission=false
                    // semantics, exactly the legacy standard path SBC drives).
                    // ADAPTATION (the one delta vs the legacy schedule): the
                    // flagged preflight releases each page's lease strictly
                    // after its checkpoint, so the tail's ref carries durable
                    // identity but NO live lease — and the legacy worker's
                    // guarded commit fences against a live BATCH identity. The
                    // wrapper therefore acquires a fresh BATCH page lease,
                    // registers the write identity, arms the ref with it, and
                    // releases the lease in `finally` (TX-06: strictly after
                    // the page's translate settled). A denied lease (a MANUAL
                    // owner owns the page) is a SKIP — that origin's outcome
                    // is authoritative, never preempted (T917 D1).
                    suspend fun standardTranslateOutcome(
                        ref: OcrReadyPageRef,
                    ): ChunkCompletionOutcome {
                        val pageKey = ref.pageKey
                        return when (
                            val acquisition = store.tryAcquirePageStageLease(
                                pageKey,
                                PageStage.Translation,
                                PageWriteOrigin.BATCH,
                            )
                        ) {
                            is LeaseAcquisition.Denied -> {
                                logcat(LogPriority.INFO) {
                                    "TachiyomiAT t924 standard translate defers ${acquisition.owner}-owned " +
                                        "page: pageKey=$pageKey"
                                }
                                ChunkCompletionOutcome.Completed(emptySet())
                            }
                            is LeaseAcquisition.Granted -> {
                                val lease = acquisition.lease
                                batchWriteIdentities[pageKey] = BatchWriteIdentity(
                                    generation = lease.generation,
                                    pageVersion = lease.pageVersion,
                                    leaseToken = lease.token,
                                    candidateGenerationId = lease.candidateGenerationId,
                                    dependencyFingerprint = lease.dependencyFingerprint,
                                    artifactPageVersion = lease.artifactPageVersion,
                                )
                                try {
                                    batchLaneWorkers.translatorWorker.translateOutcome(
                                        ref.copy(
                                            leaseToken = lease.token,
                                            candidateGenerationId = lease.candidateGenerationId,
                                            dependencyFingerprint = lease.dependencyFingerprint,
                                            artifactPageVersion = lease.artifactPageVersion,
                                        ),
                                    )
                                } finally {
                                    releaseBatchPageLease(store, pageKey)
                                }
                            }
                        }
                    }
                    return when (dispatchKind) {
                        ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE -> {
                            // T924-D4 (wave-3 owed): the run snapshot freezes
                            // the REAL provider/model identity from the active
                            // translator configuration, not a class name. The
                            // credential freezes as a one-way signature, never
                            // a raw key (T924-FP-04). Wave-7c: the engine part
                            // of the key uses the TRANSLATOR'S governor backend
                            // spelling (e.g. `lm_studio`, not the enum's
                            // `lmstudio`) so the envelope work builder's
                            // `substringBefore(':')` derivation and every
                            // Batch admission key share ONE spelling per
                            // backend (wave-6 F-W6-4 alignment).
                            val aiEnginePref = translationPreferences.translationAiEngine().get()
                            val aiEngine = contextualTranslator as? AiTranslator
                            val providerKeyEngine = aiEngine?.analysisBackendId
                                ?: aiEnginePref.name.lowercase(Locale.ROOT)
                            val aiModel = translationPreferences.translationAiModel(aiEnginePref)
                                .get()
                                .ifBlank { "unspecified" }
                            val credentialSecret = if (aiEnginePref == AiEngine.LMSTUDIO) {
                                translationPreferences.translationAiBaseUrlLmStudio().get()
                            } else {
                                translationPreferences.translationAiApiKey(aiEnginePref).get()
                            }
                            // T924 wave-7c: the typed analysis transport rides
                            // the SAME engine instance — the runner seam is
                            // now production-wired for every engine that
                            // exposes a raw completion (Gemini +
                            // OpenAI-compatible family). Engines without one
                            // keep the typed CONFIGURATION pause.
                            val analysisRunner = aiEngine
                                ?.takeIf { it.analysisBackendId != null }
                                ?.let { engine ->
                                    AnalysisChunkExecutor(
                                        transport = AnalysisEngineTransport(engine),
                                    ).runner()
                                }
                            // T924 Stage 7 (D1/D2): the overlap scheduler runs
                            // the EXISTING native inpaint lane inside each
                            // remote envelope window (ST-13) and the render
                            // join publishes persisted layouts per page
                            // (T924-TX-23). Both ride the shared identity map
                            // + lease release idiom; the legacy OFF branch
                            // below stays byte-identical.
                            val overlapScheduler = OverlapScheduler(
                                store = store,
                                nativeWorker = batchLaneWorkers.nativeWorker,
                                orderedPageKeys = orderedPages.map { it.first },
                                batchWriteIdentities = batchWriteIdentities,
                                releaseBatchLease = { pageKey -> releaseBatchPageLease(store, pageKey) },
                            )
                            ChapterProfileBatchCoordinator(
                                store = store,
                                nativeWorker = batchLaneWorkers.nativeWorker,
                                listener = batchScheduleListener,
                                frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
                                    sourceLang = fromLang.code,
                                    targetLang = toLang.code,
                                    ocrEngine = recognitionEngine::class.java.simpleName,
                                    inpaintMode = inpaintingModeFromPref().name,
                                    providerKey = "$providerKeyEngine:$aiModel",
                                    credentialId = credentialSecret.takeIf { it.isNotBlank() }
                                        ?.let { ChapterProfileBatchCoordinator.sha256Hex(it).take(16) }
                                        .orEmpty(),
                                    flagProfilePipeline = profilePipelineEnabled,
                                ),
                                orderedSourcePairs = orderedStreams.map { (pageKey, _) ->
                                    pageKey to (sourceFingerprints[pageKey] ?: UNKNOWN_SOURCE_FINGERPRINT)
                                },
                                flagProfilePipeline = true,
                                releaseBatchLease = { pageKey -> releaseBatchPageLease(store, pageKey) },
                                // T924 Stage-6 slice A: the resolved AI text
                                // translator rides the FF-01 ON branch ONLY —
                                // the legacy OFF construction below stays
                                // byte-identical. `null` (non-AI engines are
                                // already fenced by profilePipelineDispatchKind)
                                // is the typed CONFIGURATION pause inside the
                                // coordinator.
                                textTranslator = contextualTranslator,
                                // T924 wave-7c: production analysis transport
                                // (engine-backed raw completions) feeds the
                                // Stage 3-5 profile chunk runner; null keeps
                                // the typed CONFIGURATION pause.
                                analysisChunkRunner = analysisRunner,
                                overlapScheduler = overlapScheduler,
                                renderJoin = renderJoin,
                            ).runPass1(orderedPages, computeClass)
                        }
                        ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE -> {
                            // T924 Phase 4 Wave A: a STANDARD engine rides the
                            // SAME flagged coordinator with its per-page
                            // translate tail (FF-01 ON + STANDARD dispatch).
                            // The provider identity freezes as
                            // `standard:<engine>`; DeepL is the only credentialed
                            // standard engine (one-way signature, never a raw
                            // key). NO AnalysisChunkExecutor and NO contextual
                            // translator on this lane — analysis/profile/
                            // envelope/glossary work never runs here.
                            val standardEngine = translationPreferences.translationStandardEngine().get()
                            val credentialSecret = if (standardEngine == StandardEngine.DEEPL) {
                                translationPreferences.translationDeeplApiKey().get()
                            } else {
                                ""
                            }
                            val overlapScheduler = OverlapScheduler(
                                store = store,
                                nativeWorker = batchLaneWorkers.nativeWorker,
                                orderedPageKeys = orderedPages.map { it.first },
                                batchWriteIdentities = batchWriteIdentities,
                                releaseBatchLease = { pageKey -> releaseBatchPageLease(store, pageKey) },
                            )
                            ChapterProfileBatchCoordinator(
                                store = store,
                                nativeWorker = batchLaneWorkers.nativeWorker,
                                listener = batchScheduleListener,
                                frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
                                    sourceLang = fromLang.code,
                                    targetLang = toLang.code,
                                    ocrEngine = recognitionEngine::class.java.simpleName,
                                    inpaintMode = inpaintingModeFromPref().name,
                                    providerKey = "standard:" + standardEngine.name.lowercase(Locale.ROOT),
                                    credentialId = credentialSecret.takeIf { it.isNotBlank() }
                                        ?.let { ChapterProfileBatchCoordinator.sha256Hex(it).take(16) }
                                        .orEmpty(),
                                    flagProfilePipeline = profilePipelineEnabled,
                                ),
                                orderedSourcePairs = orderedStreams.map { (pageKey, _) ->
                                    pageKey to (sourceFingerprints[pageKey] ?: UNKNOWN_SOURCE_FINGERPRINT)
                                },
                                flagProfilePipeline = true,
                                releaseBatchLease = { pageKey -> releaseBatchPageLease(store, pageKey) },
                                // The plain per-page standard translator rides
                                // the WIDENED seam type; the envelope path's
                                // contextual cast never runs on this lane.
                                textTranslator = textTranslator,
                                overlapScheduler = overlapScheduler,
                                renderJoin = renderJoin,
                                standardLane = true,
                                standardTranslateOutcome = { ref -> standardTranslateOutcome(ref) },
                            ).runPass1(orderedPages, computeClass)
                        }
                        ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL ->
                            SequentialBatchCoordinator(
                                nativeWorker = batchLaneWorkers.nativeWorker,
                                translatorWorker = batchLaneWorkers.translatorWorker,
                                renderJoin = renderJoin,
                                listener = batchScheduleListener,
                                scheduleTrace = scheduleTrace,
                                awaitLeaseHandback = { pageKey ->
                                    // T917 D3 (design §3.2): the deferring owner commits its
                                    // terminal stage BEFORE releasing its lease (the manual
                                    // boundary's finally), so observing the release is
                                    // sufficient — the terminal state is already published
                                    // when the rescan re-offers the page and the worker's
                                    // externally-completed gate routes it to SKIP_ALL.
                                    store.awaitPageLeaseRelease(pageKey, LEASE_HANDBACK_WAIT_MS)
                                },
                                deferredPages = deferredPages,
                            ).runPass1(orderedPages, computeClass)
                    }
                }

                var pass1Outcome: BatchPass1Outcome? = null
                try {
                    coroutineScope {
                        val orderedPages = orderedStreams.mapIndexed { index, (pageKey, _) ->
                            pageKey to (resolvedNaturalPageIndexes[pageKey] ?: index)
                        }

                        pass1Outcome = runBatchPass1(orderedPages, computeClass)
                    }
                } finally {
                    // Only the registry's REMAINING entries need a release here: consumed/
                    // recycled/spilled bitmaps already balanced themselves. Releasing exactly
                    // `leaked` slots restores countSlots with no double-release. This is the
                    // ONLY release site that observes pages whose render never ran.
                    val leaked = bitmapRegistry.size
                    bitmapRegistry.values.forEach {
                        try {
                            it.recycle()
                        } catch (_: Exception) {}
                    }
                    bitmapRegistry.clear()
                    heldBitmapBytes.set(0L)
                    repeat(leaked) { countSlots.release() }
                }

                // OOM abort: markChapterError already recorded the abort reason on every
                // still-pending page, so skip the reconciler finish.
                if (aborted.get()) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch aborted (OOM), skipping reconciler finish: chapter=${chapter.name}"
                    }
                    setScheduleOutcome(TranslationTraceOutcome.FAILURE)
                    // T911 slice 3: the OOM abort is a terminal exit — emit the
                    // aborted snapshot with the still-untranslated pages instead
                    // of leaving a live nonterminal tracker behind.
                    tracker?.abort(
                        remainingPageKeys = remainingAbortKeys(orderedStreams, store),
                        reason = "Translation aborted: device memory pressure (OOM)",
                    )
                    store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                    store.flush()
                    return@withGeneration null
                }
                val stoppedOutcome = pass1Outcome
                if (stoppedOutcome != null && stoppedOutcome.status != BatchPass1Status.COMPLETED) {
                    // Unexpected stage failures are normally made durable before
                    // reconciliation. If that publication is rejected, preserve
                    // the rejection as an explicit in-memory-only outcome: do not
                    // add the page to durableFailurePageKeys and do not let the
                    // outer cleanup claim a durable terminal record exists.
                    val effectiveOutcome = try {
                        if (stoppedOutcome.unexpectedStage != null && stoppedOutcome.anchorPageKey != null) {
                            persistUnexpectedBatchStageFailure(
                                store = store,
                                pageKey = stoppedOutcome.anchorPageKey,
                                stage = stoppedOutcome.unexpectedStage,
                                reason = stoppedOutcome.reason ?: "Unexpected batch stage failure",
                            )
                            // Keep the unexpected-stage terminal snapshot intact during the
                            // outer lease cleanup. A plain cancellation here would erase the
                            // visible failure and make the page look pending again.
                            durableFailurePageKeys += stoppedOutcome.anchorPageKey
                            store.flush()
                        }
                        stoppedOutcome
                    } catch (rejected: BatchPersistenceRejectedException) {
                        stoppedOutcome.copy(
                            status = BatchPass1Status.PERSISTENCE_REJECTED,
                            anchorPageKey = rejected.pageKey ?: stoppedOutcome.anchorPageKey,
                            retryablePageKeys = emptySet(),
                            terminalPageKeys = emptySet(),
                            failure = null,
                            nextEligibleRetryAtEpochMs = null,
                            reason = "Batch persistence publication rejected",
                            unexpectedStage = null,
                            persistenceRejectedStage = rejected.stage ?: stoppedOutcome.unexpectedStage,
                        )
                    }
                    val reconciliation = BatchProgressReconciler.reconcile(
                        pageMap = store.state.value,
                        orderedKeys = orderedStreams.map { it.first },
                        activeGeneration = store.currentGeneration,
                        pauseOutcome = effectiveOutcome,
                    )
                    store.flush()
                    if (effectiveOutcome.status == BatchPass1Status.PAUSED) {
                        tracker?.pause(effectiveOutcome, orderedStreams.size)
                    } else {
                        tracker?.finish(reconciliation)
                    }
                    setScheduleOutcome(effectiveOutcome.status.toScheduleOutcome())
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch stopped before tail reconciliation chapter=${chapter.name} " +
                            "status=${effectiveOutcome.status} anchor=${effectiveOutcome.anchorPageKey?.let(ShortHash::hash)}"
                    }
                    store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                    return@withGeneration reconciliation
                }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch first pass complete chapter=${chapter.name} pages=${orderedStreams.size}"
                }

                // T924 LI-1: the post-pass completion projection is LANE-aware.
                // A flagged COMPLETED run ends pages translation-terminal
                // WITHOUT an in-pass render, so the legacy done-predicate would
                // project every healthy page as stranded and report the chapter
                // ERROR. Legacy outcomes keep the existing reconcile unchanged
                // (FF-01 OFF stays byte-identical in behavior).
                val reconciliation = postPassReconciliation(
                    flaggedLane = dispatchedFlaggedLane,
                    status = BatchPass1Status.COMPLETED,
                    pageMap = store.state.value,
                    orderedKeys = orderedStreams.map { it.first },
                    activeGeneration = store.currentGeneration,
                )
                // Persist every expected stranded page before emitting terminal progress. This
                // ensures callers that observe the terminal state can also inspect retryable failures.
                reconciliation.strandedPages.forEach { (pageKey, reason) ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch stranded page: chapter=${chapter.name} pageKey=$pageKey reason=$reason"
                    }
                    guardedBatchUpdate(pageKey, "batch stranded page", null) { existing ->
                        (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                            ocrStatus = StageStatus.FAILED
                            errorMessage = reason
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                }
                store.flush()
                tracker?.finish(reconciliation)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch complete chapter=${chapter.name} pages=${orderedStreams.size} outcome=${reconciliation.chapterStatus}"
                }
                setScheduleOutcome(TranslationTraceOutcome.SUCCESS)
                store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                return@withGeneration reconciliation
            } finally {
                // Cancellation, an unexpected worker exception, or a provider
                // failure must not strand a BATCH lease for the next run.
                batchWriteIdentities.keys.toList().forEach { pageKey ->
                    if (pageKey in durableFailurePageKeys) {
                        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
                    } else {
                        store.cancelPageStageWork(pageKey, PageWriteOrigin.BATCH)
                    }
                }
                batchWriteIdentities.clear()
                store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                withContext(NonCancellable) {
                    // T922 Phase 4: schedule-scoped store_flush; try/finally so
                    // the span settles even when the flush itself throws.
                    val flushSpan = scheduleTrace.beginStage(
                        TranslationTraceStage.STORE_FLUSH,
                        lane = TranslationTraceLane.STORAGE,
                    )
                    try {
                        store.flush()
                    } finally {
                        flushSpan.end()
                    }
                }
                store.reconcileArtifactRetention()
                onBatchClosed?.invoke(manga, chapter, source, store)
            }
        }
    }

    /** T922 Phase 4: typed schedule terminal per pass-1 stop status. */
    private fun BatchPass1Status.toScheduleOutcome(): TranslationTraceOutcome = when (this) {
        BatchPass1Status.PAUSED -> TranslationTraceOutcome.PAUSE
        BatchPass1Status.PERSISTENCE_REJECTED -> TranslationTraceOutcome.PERSISTENCE_REJECTED
        BatchPass1Status.FAILED -> TranslationTraceOutcome.FAILURE
        BatchPass1Status.COMPLETED -> TranslationTraceOutcome.SUCCESS
    }

    /**
     * Gives an unexpected worker exception a visible page/stage result before
     * pass reconciliation. The coordinator deliberately stops the pass, so a
     * later page remains pending; this write makes the affected page visibly
     * failed even when the worker failed before its normal stage patch.
     */
    private suspend fun persistUnexpectedBatchStageFailure(
        store: ChapterTranslationStore,
        pageKey: String,
        stage: BatchDiagnosticStage,
        reason: String,
    ): Boolean {
        val current = store.state.value[pageKey]
        val alreadyFailed = current?.let {
            it.ocrStatus == StageStatus.FAILED ||
                it.inpaintStatus == StageStatus.FAILED ||
                it.translationStatus == StageStatus.FAILED ||
                it.renderStatus == StageStatus.FAILED
        } == true
        if (alreadyFailed) return true
        val result = updatePageFromCurrentSnapshot(
            store = store,
            pageKey = pageKey,
            description = "batch unexpected ${stage.name.lowercase()} stage failure",
        ) { existing ->
            (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                sourceFileName = pageKey
                when (stage) {
                    BatchDiagnosticStage.OCR -> ocrStatus = StageStatus.FAILED
                    BatchDiagnosticStage.INPAINT -> inpaintStatus = StageStatus.FAILED
                    BatchDiagnosticStage.TRANSLATION,
                    BatchDiagnosticStage.CONTEXT,
                    -> translationStatus = StageStatus.FAILED
                    BatchDiagnosticStage.RENDER,
                    BatchDiagnosticStage.ARTIFACT,
                    -> renderStatus = StageStatus.FAILED
                }
                errorMessage = reason
                recordAttemptFailure()
                updatedAt = System.currentTimeMillis()
            }
        }
        if (result is ChapterTranslationStore.PatchResult.Rejected) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT unexpected stage failure could not be persisted: " +
                    "pageHash=${ShortHash.hash(pageKey)} stage=${stage.name}"
            }
            throw BatchPersistenceRejectedException(pageKey = pageKey, stage = stage)
        }
        return true
    }

    internal companion object {
        /**
         * T924 LI-1: the pass-1 POST-PASS projection selector. A flagged-lane
         * (FF-01 ON) COMPLETED outcome projects through
         * [BatchProgressReconciler.reconcileFlaggedCompleted] — the flagged
         * pipeline commits translations without an in-pass render, so the
         * legacy done-predicate would strand every healthy page — while every
         * other shape (any legacy outcome, and a legacy lane regardless of
         * status) keeps the existing [BatchProgressReconciler.reconcile]
         * unchanged. Pure and unit-testable; the single post-pass site in
         * `translateBatchTraced` consults this and nothing else.
         */
        internal fun postPassReconciliation(
            flaggedLane: Boolean,
            status: BatchPass1Status,
            pageMap: Map<String, PageTranslation>,
            orderedKeys: List<String>,
            activeGeneration: Long,
        ): ReconciliationResult = if (flaggedLane && status == BatchPass1Status.COMPLETED) {
            BatchProgressReconciler.reconcileFlaggedCompleted(pageMap, orderedKeys, activeGeneration)
        } else {
            BatchProgressReconciler.reconcile(pageMap, orderedKeys, activeGeneration)
        }

        /**
         * T924 F3 (wave-2 review R3, gap 8) + Phase 4 Wave A: the FF-01
         * dispatch decision WITH engine-category parity. The flagged
         * [ChapterProfileBatchCoordinator] is constructed when the flag is ON
         * AND the engine parity matches a lane it can carry:
         *  - STANDARD engine → STANDARD_PIPELINE (the same coordinator's
         *    standard tail — pure FULL OCR preflight, then per-page legacy
         *    batch translation without the glossary);
         *  - AI_MODEL with the contextual translator parity of the legacy AI
         *    lane (`contextualAiParity`) → PROFILE_PIPELINE;
         *  - AI_MODEL without that parity (degenerate AI config) → the
         *    verbatim legacy [SequentialBatchCoordinator] (FF-01b/F3);
         *  - flag OFF for any engine → the legacy coordinator, byte-identical.
         * Pure and unit-testable; the single dispatch site in `runBatchPass1`
         * consults this and nothing else (T924-FF-01a).
         */
        internal fun profilePipelineDispatchKind(
            flagOn: Boolean,
            engineCategoryIsStandard: Boolean,
            contextualAiParity: Boolean,
        ): ChapterProfileBatchCoordinator.BatchCoordinatorKind =
            ChapterProfileBatchCoordinator.dispatchKind(
                translationBatchProfilePipeline = flagOn,
                engineCategoryIsStandard = engineCategoryIsStandard,
                contextualAiParity = contextualAiParity,
            )

        /**
         * T917 D3 defer-and-rescan: bound on how long a deferred page's lease
         * handback wait may suspend before the pass gives up on that page and
         * lets the reconciler report it. Matches the single-page pipeline's
         * own stage budget, so a reader-owned page that finishes its work in
         * reasonable time is always rejoined in-pass.
         */
        internal val LEASE_HANDBACK_WAIT_MS: Long = SINGLE_PAGE_TIMEOUT_MS

        /**
         * T911 slice 3: pages that are NOT durably terminal when the batch
         * aborts (OOM / engine-setup failure). Rendered, textless, and already
         * failed pages carry their outcome in the store; everything else in the
         * ordered work set is "remaining" and is reported by the aborted
         * terminal snapshot. Pure and unit-testable.
         */
        internal fun remainingAbortKeys(
            orderedStreams: List<Pair<String, () -> InputStream>>,
            store: ChapterTranslationStore,
        ): Set<String> {
            val durablyTerminal = store.state.value.filterValues { page ->
                page.hasRenderedResult || page.isTextlessTerminal || page.isStageFailed
            }.keys
            return orderedStreams.mapTo(mutableSetOf()) { it.first } - durablyTerminal
        }
    }
}
