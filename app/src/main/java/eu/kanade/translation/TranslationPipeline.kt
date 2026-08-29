package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.batch.BatchContextFrontier
import eu.kanade.translation.batch.BatchDiagnosticDecision
import eu.kanade.translation.batch.BatchDiagnosticReason
import eu.kanade.translation.batch.BatchDiagnosticStage
import eu.kanade.translation.batch.BatchEnvelopeLifecycle
import eu.kanade.translation.batch.BatchPass1Outcome
import eu.kanade.translation.batch.BatchPass1Status
import eu.kanade.translation.batch.BatchPersistenceRejectedException
import eu.kanade.translation.batch.BatchProgressReconciler
import eu.kanade.translation.batch.BatchResumeGateDecider
import eu.kanade.translation.batch.BatchTranslationDiagnostics
import eu.kanade.translation.batch.ChunkAdmission
import eu.kanade.translation.batch.ChunkCompletionOutcome
import eu.kanade.translation.batch.NativeLaneWorker
import eu.kanade.translation.batch.OcrReadyPageRef
import eu.kanade.translation.batch.RenderJoinWorker
import eu.kanade.translation.batch.SequentialBatchCoordinator
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.batch.TranslatorLaneWorker
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchPlannerInput
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageWorkPlanner
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.model.resetAttemptCharge
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.CleanedPublication
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.pipeline.EngineLane
import eu.kanade.translation.pipeline.batch.BatchWriteGate
import eu.kanade.translation.pipeline.batch.BatchWriteIdentity
import eu.kanade.translation.pipeline.batch.HeldBitmapRegistry
import eu.kanade.translation.pipeline.LowMemoryDecodeDeferredException
import eu.kanade.translation.pipeline.LowMemoryRecognitionDeferredException
import eu.kanade.translation.pipeline.MemoryGovernance
import eu.kanade.translation.pipeline.OnnxPhaseResult
import eu.kanade.translation.pipeline.PageDecode
import eu.kanade.translation.pipeline.PageStoreWriter
import eu.kanade.translation.pipeline.SinglePageHttpRenderPhase
import eu.kanade.translation.pipeline.SinglePageOnnxPhase
import eu.kanade.translation.pipeline.copyForResume
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.scheduling.NativeRunQuarantine
import eu.kanade.translation.scheduling.PreparedPage
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationStageEvent
import eu.kanade.translation.scheduling.TranslationStageListener
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.scheduling.isPreparedPageTerminal
import eu.kanade.translation.scheduling.publishPreparedPageFromOcr
import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.AiTranslationRetryPlanner
import eu.kanade.translation.translator.ChapterGlossaryBuilder
import eu.kanade.translation.translator.ContextualTextTranslator
import eu.kanade.translation.translator.LmStudioTranslator
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.StableBlockIds
import eu.kanade.translation.translator.StreamingChunkPlanner
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.translator.TranslationContextChunk
import eu.kanade.translation.translator.TranslationContextChunkPlanner
import eu.kanade.translation.translator.TranslationResponseFaithfulness
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.applyAiChunkOutcomeToPages
import eu.kanade.translation.translator.classifyProviderFailure
import eu.kanade.translation.translator.translateAiChunkWithAdaptiveRetry
import eu.kanade.translation.translator.withProviderRequestPriority
import eu.kanade.translation.util.ShortHash
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class LayoutFailureException(val blockIds: List<String>, message: String) : Exception(message)

// T909 Phase 20.2: ProviderFailure.toFailureCategory moved to
// pipeline/batch/BatchWriteGate.kt (its only caller, persistAiFailure).

class TranslationPipeline(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
) : TranslationExecutor, java.io.Closeable {

    override fun close() {
        nativeRunScope.cancel()
    }

    companion object {
        /**
         * Maximum wall-clock time a single page may hold the sole
         * native lane during [translateSinglePage]. Bounds the damage of
         * a hung ONNX inference or a stalled AI/HTTP call so it can't starve
         * every other page's translation for the whole session. Generous
         * (on-device OCR + inpaint + render of one page can take tens of seconds
         * on a large image), but finite.
         */
        const val SINGLE_PAGE_TIMEOUT_MS = 120_000L

        /**
         * Maximum number of local retries the single-page path performs when a
         * translation comes back PARTIAL (some blocks translated, some not).
         * Each retry re-requests ONLY the still-untranslated blocks via
         * [AiTranslationRetryPlanner.untranslatedBlocks]. Mirrors the batch
         * path's adaptive retry behaviour so the interactive reader path
         * benefits from the same second-chance logic. Does NOT bump the page's
         * persistent retryCount — PARTIAL must not burn the permanent retry
         * budget (contract #14b); this is a local loop counter only.
         */
        const val SINGLE_PAGE_PARTIAL_MAX_RETRIES = 2

        /**
         * Tighter timeout for the permit-held ONNX phase (decode → OCR → inpaint →
         * persist .cleaned). The HTTP translate + Canvas render phase runs outside
         * the permit and has its own timeout (OkHttp timeouts + fast Canvas). Keeping
         * the ONNX phase shorter ensures the permit is released promptly so the next
         * prefetch page's ONNX work can overlap this page's network call.
         */
        const val ONNX_PHASE_TIMEOUT_MS = 90_000L

        // T909 Phase 20.1: HELD_BITMAP_MAX_COUNT / HELD_BITMAP_BYTE_CEILING moved to
        // pipeline/batch/HeldBitmapRegistry.kt with the held-bitmap registry.

        const val UNKNOWN_SOURCE_FINGERPRINT = "source-fingerprint-unavailable"
    }

    /** Per-page resume decision in the 3-lane batch pipeline (Lane A). Lives at
     *  class scope because Kotlin forbids local enum classes. */
    private enum class BatchResumeGate { SKIP_ALL, INPAINT_ONLY, FULL }

    /** Native admission is owned by [nativeRunQuarantine]. */

    // T909 Phase 10: PermitHolder/native-permit bookkeeping moved into pipeline/EngineLane.kt.
    internal fun permitHolderPageKeySnapshot(): String? = engines.permitHolderPageKeySnapshot()

    private val engineRebuildMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * pageKeys currently mid-flight in [translateSinglePage]. Guards against
     * the same page being queued behind itself (e.g. auto-mode re-enqueue on
     * scroll, or a user double-tapping the per-page button). Thread-safe
     * because native quarantine and [closeEngines] touch this outside
     * the native lane.
     */
    private val inFlightPageKeys = ConcurrentHashMap.newKeySet<String>()

    // Native work runs in an independent scope so caller cancellation cannot
    // falsely signal native exit. The quarantine owns admission until real exit.
    private val nativeRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeRunQuarantine = NativeRunQuarantine(nativeRunScope)

    /**
     * Listener notified by [withNativeLane] when a page overruns its deadline,
     * after the real native invocation exits quarantine. Wired by
     * [TranslationManager] so it can also evict the dead job from
     * `activePageJobs`, otherwise the `existing.isActive` dedup keeps silently
     * dropping every retry of that page forever.
     */
    @Volatile
    var onPageStuck: ((chapterId: Long?, pageKey: String) -> Unit)? = null

    /**
     * TachiyomiAT: factory that creates a [TranslationBatchProgressTracker] for a
     * batch and registers it in the manager's tracker map so the UI can observe
     * it. Mirrors the [activeStoreResolver] pattern. Set by [TranslationManager].
     */
    @Volatile
    var batchTrackerFactory: ((chapterId: Long, store: ChapterTranslationStore, orderedPageKeys: List<String>) -> TranslationBatchProgressTracker?)? = null

    // T909 Phase 10: engine cache + native lane moved to pipeline/EngineLane.kt
    // (defensive init semantics preserved: EngineLane's init builds the engines
    // defensively at construction). Same-signature stubs keep call sites.
    internal val engines = EngineLane(
        context = context,
        translationPreferences = translationPreferences,
        nativeRunQuarantine = nativeRunQuarantine,
        inFlightPageKeys = inFlightPageKeys,
        onPageStuck = { onPageStuck },
    )

    private suspend fun <T> withNativeLane(
        timeoutMs: Long,
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        block: suspend () -> T,
    ): T? = engines.withNativeLane(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block)

    // T909 Phase 10: engine-cache fields live in [engines]; the staying paths
    // keep reading them through these same-name getters.
    private val currentOcrModel get() = engines.currentOcrModel

    private val currentReadingOrder get() = engines.currentReadingOrder

    private val textTranslator get() = engines.textTranslator

    private val recognitionEngine get() = engines.recognitionEngine

    private val currentInpaintingMode get() = engines.currentInpaintingMode

    private val currentTranslatorSignature get() = engines.currentTranslatorSignature

    private fun inpaintingModeFromPref(): InpaintingMode = engines.inpaintingModeFromPref()

    fun closeEngines() {
        engines.closeEngines()
    }


    /**
     * Listener that lets the translator share the same [ChapterTranslationStore]
     * with the [TranslationManager] (and the reader observing it). If no
     * listener is provided, the translator falls back to opening a local store.
     */
    @Volatile
    var activeStoreResolver: ((Translation) -> ChapterTranslationStore?)? = null

    /** Chapter-close hook for bounded storage cleanup owned by TranslationManager. */
    @Volatile
    var onBatchClosed: (suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit)? = null

    // T909 Phase 6: stream peek + canonical FAILED placeholder + store-patch/failure-write
    // bodies moved to translation/pipeline/PageStoreWriter.kt (stateless over the injected
    // resolver + store guarded-write API). Same-signature private stubs keep call sites.
    private val pageStoreWriter = PageStoreWriter(
        activeStoreResolver = { activeStoreResolver },
        streamRegistry = streamRegistry,
        handleCriticalTranslationOom = this::handleCriticalTranslationOom,
    )

    private fun peekReaderPageStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> InputStream)? = pageStoreWriter.peekReaderPageStream(manga, chapter, source, pageKey)

    private fun createFailedPagePlaceholder(
        fileName: String,
        errorMessage: String?,
        imgWidth: Float = 0f,
        imgHeight: Float = 0f,
        originalImgWidth: Float = 0f,
        originalImgHeight: Float = 0f,
        decodeSampleSize: Int = 1,
        retryCount: Int = 0,
        // Per-attempt exhaustion counter. Merge callers pass (existing.attemptCount + 1);
        // standalone first-failure callers pass the default 1.
        attemptCount: Int = 1,
    ): PageTranslation = pageStoreWriter.createFailedPagePlaceholder(
        fileName,
        errorMessage,
        imgWidth,
        imgHeight,
        originalImgWidth,
        originalImgHeight,
        decodeSampleSize,
        retryCount,
        attemptCount,
    )

    private fun resolveActiveStore(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): ChapterTranslationStore? = pageStoreWriter.resolveActiveStore(manga, chapter, source)

    private suspend fun markPageTimedOut(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ) {
        pageStoreWriter.markPageTimedOut(manga, chapter, source, pageKey)
    }

    // T909 Phase 6: PageSnapshot.toPrecondition moved to pipeline/PageStoreWriter.kt
    // (imported top-level extension — all call sites below resolve through it).

    private suspend fun updatePageFromCurrentSnapshot(
        store: ChapterTranslationStore,
        pageKey: String,
        description: String,
        expectedGeneration: Long? = null,
        update: (PageTranslation?) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult =
        pageStoreWriter.updatePageFromCurrentSnapshot(store, pageKey, description, expectedGeneration, update)

    private suspend fun markPageFailed(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        error: Throwable,
    ) {
        pageStoreWriter.markPageFailed(manga, chapter, source, pageKey, error)
    }

    /**
     * Translates a single page identified by [pageKey] within [chapter] of [manga].
     *
     * Phase ONNX (under the permit): setup, decode, recognize (OCR+inpaint), persist
     * .cleaned. Phase translation/render-metadata (outside the permit): cooperative
     * cancel check, textTranslator.translatePage, color estimation, and page-state
     * persistence. The reader draws translated text live over the cleaned image.
     * Splitting the permit-held ONNX work from the network-bound HTTP translate lets the next
     * prefetch page's ONNX overlap this page's network call — the same asymmetry
     * benefit the batch path already derives.
     *
     * Scheduling scope: this single-page path bypasses the batch coordinator
     * (`SequentialBatchCoordinator.runPass1`) by design — it processes
     * exactly one page, so there is no chapter-wide pass to join. The
     * chapter-level "no inpaint before all OCR terminal" invariant only applies
     * to batch/pre-translation; here OCR and inpaint of the same single page run
     * sequentially inside this function.
     */
    override suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {
        withProviderRequestPriority(AdmissionPriority.INTERACTIVE) {
            runSinglePageBoundary(
                manga = manga,
                chapter = chapter,
                source = source,
                pageKey = pageKey,
                streamFn = null,
                force = force,
                stageListener = stageListener,
            )
        }
    }

    override suspend fun translateSinglePageFromStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {
        runSinglePageBoundary(
            manga = manga,
            chapter = chapter,
            source = source,
            pageKey = pageKey,
            streamFn = streamFn,
            force = force,
            stageListener = stageListener,
        )
    }

    /**
     * Shared single-page driver: native phase under the permit, durable
     * cleaned-image publication, then HTTP translate + render outside the
     * permit. Used by the legacy composing wrappers above and by the prepared-
     * page boundary methods below. [streamFn] is null for the reader-stream
     * peek path.
     *
     * Phase 3: the whole boundary runs under a `READER_ADHOC` page lease. When
     * a batch run owns the page, the request attaches to the batch result
     * (observes store emissions) instead of opening a competing writer.
     */
    private suspend fun runSinglePageBoundary(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {
        val leaseStore = resolveActiveStore(manga, chapter, source)
        if (!acquireReaderPageLease(leaseStore, chapter, pageKey)) return
        try {
            val onnxResult = withNativeLane(
                timeoutMs = ONNX_PHASE_TIMEOUT_MS,
                chapterId = chapter.id,
                chapterName = chapter.name,
                pageKey = pageKey,
                onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
            ) {
                if (!inFlightPageKeys.add(pageKey)) {
                    logcat(LogPriority.WARN) { "TachiyomiAT native admission rejected: chapter=${chapter.name} pageKey=$pageKey reason=already in flight" }
                    return@withNativeLane null
                }
                try {
                    val store = resolveActiveStore(manga, chapter, source)
                    val generation = store?.snapshot(pageKey)?.generation
                    if (store != null && generation != null) {
                        store.withGeneration(generation) {
                            translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                        }
                    } else {
                        translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logcat(LogPriority.ERROR, t) { "TachiyomiAT ONNX phase failed: pageKey=$pageKey" }
                    markPageFailed(manga, chapter, source, pageKey, t)
                    throw t
                } finally {
                    inFlightPageKeys.remove(pageKey)
                }
            } ?: return

            val publishedResult = persistOnnxCleanedImage(manga, chapter, source, pageKey, onnxResult) ?: return

            try {
                withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                    translateSinglePageHttpRender(manga, chapter, source, pageKey, publishedResult, stageListener)
                } ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT HTTP+render phase timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                            "pageKey=$pageKey chapter=${chapter.name}"
                    }
                    markPageTimedOut(manga, chapter, source, pageKey)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT HTTP+render phase failed: pageKey=$pageKey"
                }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            }
        } finally {
            releaseReaderPageLease(leaseStore, pageKey)
        }
    }

    /**
     * Phase 3 lease admission for reader-originated single-page work. Returns
     * false when another origin (a batch run) owns the page — the request then
     * attaches to the owner's result through store emissions rather than
     * opening a competing writer.
     */
    private suspend fun acquireReaderPageLease(
        store: ChapterTranslationStore?,
        chapter: Chapter,
        pageKey: String,
    ): Boolean {
        if (store == null) return true
        return when (val acquisition = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.READER_ADHOC)) {
            is LeaseAcquisition.Granted -> true
            is LeaseAcquisition.Denied -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT reader single-page request attaches to ${acquisition.owner} owner: " +
                        "chapter=${chapter.name} pageKey=$pageKey reason=${acquisition.reason}"
                }
                false
            }
        }
    }

    private suspend fun releaseReaderPageLease(store: ChapterTranslationStore?, pageKey: String) {
        if (store == null) return
        store.releasePageStageLease(pageKey, PageWriteOrigin.READER_ADHOC)
    }

    /** Releases the batch's page lease at an atomic stage boundary (terminal render/failure). */
    private suspend fun releaseBatchPageLease(store: ChapterTranslationStore, pageKey: String) {
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
    }

    // T909 Phase 7: cleaned-image publication bodies moved to
    // translation/pipeline/CleanedPublication.kt (wraps CleanedImagePublisher;
    // currentInpaintingMode injected as a getter). Same-signature stubs keep call sites.
    private val cleanedPublication = CleanedPublication(
        provider = provider,
        streamRegistry = streamRegistry,
        currentInpaintingMode = { currentInpaintingMode },
    )
    // T909 Phase 12: single-page HTTP+render phase moved to
    // translation/pipeline/SinglePageHttpRenderPhase.kt. Engine reads are taken
    // through [engines] at call time so in-flight calls observe engine rebuilds
    // exactly as the pre-move in-class getters did.
    private val singlePageHttpRenderPhase = SinglePageHttpRenderPhase(
        translationPreferences = translationPreferences,
        provider = provider,
        streamRegistry = streamRegistry,
        engines = engines,
        cleanedPublication = cleanedPublication,
        expectedBatchFingerprints = { fromLang, toLang -> batchExpectedFingerprints(fromLang, toLang) },
        retryInpaintDownscaledFn = { manga, chapter, source, pageKey, streams, decoded, pageTranslation ->
            retryInpaintDownscaled(manga, chapter, source, pageKey, streams, decoded, pageTranslation)
        },
    )
    // T909 Phase 14: permit-held ONNX phase + resume/native-stage helpers moved
    // to translation/pipeline/SinglePageOnnxPhase.kt (OnnxPhaseResult moves with
    // it; bitmap recycle/ownership points moved verbatim). Engine reads go
    // through [engines]; the engine-rebuild mutex is shared so rebuild ordering
    // against native-quarantine admission is unchanged.
    private val singlePageOnnxPhase = SinglePageOnnxPhase(
        context = context,
        translationPreferences = translationPreferences,
        provider = provider,
        downloadProvider = downloadProvider,
        streamRegistry = streamRegistry,
        engines = engines,
        cleanedPublication = cleanedPublication,
        pageStoreWriter = pageStoreWriter,
        activeStoreResolverProvider = { activeStoreResolver },
        engineRebuildMutex = engineRebuildMutex,
    )


    private suspend fun deleteRetiredCleanedFile(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) {
        cleanedPublication.deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
    }

    /**
     * Prepared-page boundary (ticket 02): runs the native phase (decode →
     * detect/OCR → inpaint → persist cleaned image) under the sole native
     * permit and returns a lightweight [PreparedPage] carrying durable
     * identifiers only. The decoded/cleaned bitmap is recycled before return
     * and never crosses the boundary.
     *
     * Returns null on failure before handoff (decode/recognition/persist
     * failure, missing chapter files, generation loss) so a caller cannot
     * mistake a failed page for a prepared one. Returns a terminal
     * [PreparedPage] (isTerminal=true, cleanedImageName may be null) when the
     * page needs no further translation/render work — textless pages,
     * render-only resume that already completed, or an already-fully-rendered
     * page resumed to a no-op.
     *
     * Stage events fire on [stageListener] at actual execution entry:
     * READING before the fused recognize() / analyze() call, CLEANING before a
     * standalone inpaint() (resume-inpaint-only path).
     */
    override suspend fun prepareSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ): PreparedPage? {
        val leaseStore = resolveActiveStore(manga, chapter, source)
        if (!acquireReaderPageLease(leaseStore, chapter, pageKey)) return null
        try {
            val onnxResult = withNativeLane(
                timeoutMs = ONNX_PHASE_TIMEOUT_MS,
                chapterId = chapter.id,
                chapterName = chapter.name,
                pageKey = pageKey,
                onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
            ) {
                if (!inFlightPageKeys.add(pageKey)) {
                    logcat(LogPriority.WARN) { "TachiyomiAT prepare admission rejected: chapter=${chapter.name} pageKey=$pageKey reason=already in flight" }
                    return@withNativeLane null
                }
                try {
                    val store = resolveActiveStore(manga, chapter, source)
                    val generation = store?.snapshot(pageKey)?.generation
                    if (store != null && generation != null) {
                        store.withGeneration(generation) {
                            translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                        }
                    } else {
                        translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logcat(LogPriority.ERROR, t) { "TachiyomiAT prepare ONNX phase failed: pageKey=$pageKey" }
                    markPageFailed(manga, chapter, source, pageKey, t)
                    throw t
                } finally {
                    inFlightPageKeys.remove(pageKey)
                }
            }

            // Failure before handoff: a null native result with no terminal store
            // state cannot be mistaken for a prepared page. Inspect the store to
            // distinguish a genuine terminal page (render-only resume, textless)
            // from a soft skip / decode failure.
            if (onnxResult == null) {
                return buildTerminalPreparedPage(manga, chapter, source, pageKey)
            }

            // Durability gate: persist the cleaned image BEFORE publishing the
            // prepared reference. If publication fails the page is not prepared.
            val published = persistOnnxCleanedImage(manga, chapter, source, pageKey, onnxResult)
            if (published == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT prepare handoff aborted: cleaned image not durable pageKey=$pageKey"
                }
                return null
            }

            // P0 correctness: the in-memory OCR result (blocks, geometry,
            // ocrStatus=READY) must be made durable BEFORE publishing the prepared
            // reference. Delegates to [publishPreparedPageFromOcr], the production
            // boundary helper that is also exercised by regression tests. The merge
            // carries the post-publish precondition so a stale worker loses to a
            // newer page owner.
            return publishPreparedPageFromOcr(
                store = published.store,
                pageKey = pageKey,
                ocrResult = published.pageTranslation,
                chapterId = chapter.id,
                mangaId = manga.id,
                sourceId = source.id,
                expectedPageVersion = published.commitPrecondition?.pageVersion
                    ?: published.store.snapshot(pageKey).pageVersion,
                expectedGeneration = published.commitPrecondition?.generation
                    ?: published.store.snapshot(pageKey).generation,
                expectedLeaseToken = published.commitPrecondition?.leaseToken,
            )
        } finally {
            releaseReaderPageLease(leaseStore, pageKey)
        }
    }

    /**
     * Inspects the durable store after a null native-phase result and returns a
     * terminal [PreparedPage] when the page is genuinely complete (render-only
     * resume that rendered, textless page, or fully-translated resume skip),
     * or null when the page is in a non-terminal soft-skip / failed state.
     */
    private suspend fun buildTerminalPreparedPage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): PreparedPage? {
        val store = resolveActiveStore(manga, chapter, source) ?: return null
        val page = store.state.value[pageKey] ?: return null
        val snapshot = store.snapshot(pageKey)
        if (!isPreparedPageTerminal(page)) return null
        return PreparedPage(
            pageKey = pageKey,
            chapterId = chapter.id,
            mangaId = manga.id,
            sourceId = source.id,
            cleanedImageName = page.cleanedImageName,
            generation = snapshot.generation,
            pageVersion = snapshot.pageVersion,
            blockFingerprints = snapshot.blockFingerprints,
            isTerminal = true,
        )
    }

    /**
     * Prepared-page boundary (ticket 02): loads the durable cleaned image
     * produced by [prepareSinglePage] and runs translate + render outside the
     * native permit so a caller's other native page may overlap this page's
     * remote translation.
     *
     * Returns a typed completion outcome when translate/render work was
     * performed or attempted (including textless terminal no-ops). Returns
     * null ONLY when the prepared reference no longer matches the durable
     * store (generation / pageVersion / fingerprint mismatch, missing page
     * entry, or missing cleaned image) — a stale/race outcome the caller
     * treats as "try again" rather than a failure.
     *
     * A genuine translate/render failure or timeout is thrown (after durable
     * failure writes via [markPageFailed] / [markPageTimedOut]), matching the
     * legacy [runSinglePageBoundary] propagation so callers can attribute it
     * correctly instead of mistaking it for a race loss.
     *
     * Stage events fire TRANSLATING before the provider request and RENDERING
     * before compositing the translated result.
     */
    override suspend fun translatePreparedPage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        prepared: PreparedPage,
        stageListener: TranslationStageListener?,
    ): ChunkCompletionOutcome? {
        if (prepared.isTerminal) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT translatePreparedPage: terminal skip pageKey=${prepared.pageKey} " +
                    "cleaned=${prepared.cleanedImageName}"
            }
            return ChunkCompletionOutcome.Completed()
        }
        val store = resolveActiveStore(manga, chapter, source) ?: return null
        // prepareSinglePage owns the reader lease only through the native
        // handoff. Re-admit the translate/render half here so a batch cannot
        // acquire the page in the handoff gap and then race the prepared
        // reference's writes.
        if (!acquireReaderPageLease(store, chapter, prepared.pageKey)) return null
        try {
            val snapshot = store.snapshot(prepared.pageKey)
            // Stale-reference rejection: generation, pageVersion, and the OCR block
            // fingerprints must all match. A mismatch means another caller won the
            // race or the page was re-OCR'd — the caller may retry, not fail.
            if (snapshot.generation != prepared.generation ||
                snapshot.pageVersion != prepared.pageVersion ||
                (prepared.blockFingerprints.isNotEmpty() && snapshot.blockFingerprints != prepared.blockFingerprints)
            ) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT translatePreparedPage: stale prepared reference pageKey=${prepared.pageKey} " +
                        "preparedGen=${prepared.generation} currentGen=${snapshot.generation} " +
                        "preparedVer=${prepared.pageVersion} currentVer=${snapshot.pageVersion} " +
                        "fingerprintMatch=${snapshot.blockFingerprints == prepared.blockFingerprints}"
                }
                return null
            }
            val pageState = store.state.value[prepared.pageKey] ?: return null
            val cleanedImageName = prepared.cleanedImageName ?: pageState.cleanedImageName
            if (cleanedImageName == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT translatePreparedPage: no cleaned image for pageKey=${prepared.pageKey}"
                }
                return null
            }

            val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
            val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
            val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
            syntheticTranslation.status = Translation.State.TRANSLATING
            // The translate lane resolves its store through resolveActiveStore
            // above and is a consumer of the prepared reference. It does not own or
            // mutate chapter-level translation state — the dead
            // currentChapterTranslation pointer was removed entirely (R3 cleanup).

            val pageTranslation = pageState.detachedCopy()
            pageTranslation.cleanedBitmap = null

            // The prepared-page boundary does not re-decode the source on the
            // translation side: the cleaned image is the durable artifact. An empty
            // streams list steers the render-retry path away from source re-decode.
            val streams = emptyList<Pair<String, () -> InputStream>>()
            val origW = if (pageTranslation.originalImgWidth > 0f) pageTranslation.originalImgWidth.toInt() else (pageTranslation.imgWidth.toInt().coerceAtLeast(1))
            val origH = if (pageTranslation.originalImgHeight > 0f) pageTranslation.originalImgHeight.toInt() else (pageTranslation.imgHeight.toInt().coerceAtLeast(1))
            val dummyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
            val decoded = DecodedPage(
                bitmap = dummyBitmap,
                sampleSize = pageTranslation.decodeSampleSize.coerceAtLeast(1),
                originalWidth = origW,
                originalHeight = origH,
                decodeDecision = DecodeDecision(
                    kind = DecodeDecisionKind.FULL,
                    sampleSize = pageTranslation.decodeSampleSize.coerceAtLeast(1),
                    rawBitmapBytes = 0L,
                    sampledBitmapBytes = 0L,
                    sourcePixels = 0L,
                    sampledPixels = 0L,
                    snapshot = eu.kanade.translation.util.TranslationMemoryBudget.snapshot(),
                ),
                sourceBytesSize = 0L,
            )
            val ctx = OnnxPhaseResult(
                pageTranslation = pageTranslation,
                store = store,
                fromLang = fromLang,
                syntheticTranslation = syntheticTranslation,
                streams = streams,
                decoded = decoded,
                commitPrecondition = snapshot.toPrecondition(),
            )
            return try {
                val completed = withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                    translateSinglePageHttpRender(manga, chapter, source, prepared.pageKey, ctx, stageListener)
                }
                if (completed == null) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT translatePreparedPage timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                            "pageKey=${prepared.pageKey} chapter=${chapter.name}"
                    }
                    markPageTimedOut(manga, chapter, source, prepared.pageKey)
                    throw java.io.IOException("translatePreparedPage timed out for ${prepared.pageKey}")
                } else {
                    completed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // A genuine failure (unreadable cleaned image, timeout) — propagate
                // so the caller attributes it as a failure, not a race loss.
                throw e
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT translatePreparedPage failed: pageKey=${prepared.pageKey}"
                }
                markPageFailed(manga, chapter, source, prepared.pageKey, t)
                throw t
            }
        } finally {
            releaseReaderPageLease(store, prepared.pageKey)
        }
    }

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
    ): eu.kanade.translation.batch.ReconciliationResult? {
        if (orderedStreams.isEmpty()) return null
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
                withNativeLane(
                    timeoutMs = ONNX_PHASE_TIMEOUT_MS,
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    pageKey = "<engine-setup>",
                    onTimeout = {
                        store.invalidateGeneration("engine setup timeout chapter=${chapter.name}")
                    },
                ) {
                    engineRebuildMutex.withLock {
                        ensureEnginesBuiltFor(fromLang, toLang)
                    }
                } ?: run {
                    // Phase 3: no batch page lease outlives its run, whatever exit
                    // path the batch takes.
                    store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                    return@withGeneration null
                }

                val ensureCompanionDir: suspend () -> UniFile? = {
                    provider.getCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
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
                val renderMutexes = ConcurrentHashMap<String, Mutex>()
                val aborted = AtomicBoolean(false)
                val expectedBatchFingerprints = batchExpectedFingerprints(fromLang, toLang)

                // Resume context is a natural-order frontier, never a chapter-wide
                // unordered snapshot. Pages after the first missing/failed predecessor
                // remain durable and reusable, but cannot become context until traversal
                // reaches them; a non-textless terminal gap blocks later AI admission.
                val contextFrontier = BatchContextFrontier(resolvedNaturalPageIndexes)
                var rollingContext = contextFrontier.rollingContext

                fun recordContextPage(
                    pageKey: String,
                    page: PageTranslation,
                    terminalFailure: Boolean = false,
                ) {
                    contextFrontier.record(pageKey, page, terminalFailure)
                    rollingContext = contextFrontier.rollingContext
                }

                // Source identity is a direct input to detection and inpaint.
                // Hash the downloaded bytes before planning so replacing a
                // page under the same natural key cannot reuse old artifacts.
                // This is an I/O-only preflight; no detector/OCR/inpaint or
                // translator work is invoked for a matching completed page.
                val sourceFingerprints = coroutineScope {
                    orderedStreams.map { (pageKey, streamFn) ->
                        async(Dispatchers.IO) {
                            pageKey to (computeSourceFingerprint(streamFn) ?: UNKNOWN_SOURCE_FINGERPRINT)
                        }
                    }.awaitAll().toMap()
                }

                fun stampBatchProvenance(page: PageTranslation, stage: BatchStage?): PageTranslation = page.apply {
                    when (stage) {
                        BatchStage.OCR -> {
                            detectionFingerprint = expectedBatchFingerprints.detection
                            ocrFingerprint = expectedBatchFingerprints.ocr
                        }
                        BatchStage.INPAINT -> inpaintFingerprint = expectedBatchFingerprints.inpaint
                        BatchStage.TRANSLATION -> {
                            translationFingerprint = expectedBatchFingerprints.translation
                            translationOrigin = PageWriteOrigin.BATCH.name
                        }
                        BatchStage.LAYOUT -> layoutFingerprint = expectedBatchFingerprints.layout
                        else -> {}
                    }
                }

                // T909 Phase 20.2: the batch write gate moved to
                // pipeline/batch/BatchWriteGate.kt. It receives the SAME identity-map
                // and durable-failure-set instances the shell holds; the same-name
                // local delegates below keep the not-yet-moved closures' call sites.
                val batchWriteGate = BatchWriteGate(
                    store = store,
                    batchWriteIdentities = batchWriteIdentities,
                    durableFailurePageKeys = durableFailurePageKeys,
                    expectedBatchFingerprints = expectedBatchFingerprints,
                    stampBatchProvenance = ::stampBatchProvenance,
                    releaseBatchPageLeaseFn = ::releaseBatchPageLease,
                    persistPageWithOomRecoveryFn = ::persistPageWithOomRecovery,
                )

                // Plan the complete chapter once, in the same natural order
                // passed to the coordinator.  Native resume gates consume this
                // snapshot; they never derive work from lastPageRead or the
                // reader viewport.
                fun buildBatchPagePlans() = PageWorkPlanner.planChapter(
                    orderedStreams.map { (pageKey, _) ->
                        BatchPlannerInput(
                            pageKey = pageKey,
                            page = store.state.value[pageKey],
                            expectedFingerprints = expectedBatchFingerprints,
                            sourceFingerprint = sourceFingerprints[pageKey],
                            durableFailure = store.durableFailure(pageKey),
                        )
                    },
                ).pages.associateBy { it.pageKey }

                val batchPagePlans = buildBatchPagePlans()

                fun translationFailureFence(pageKey: String): Boolean =
                    batchPagePlans[pageKey]
                        ?.stages
                        ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                        ?.decision in setOf(
                        eu.kanade.translation.model.StageDecision.FAILED,
                        eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                        eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                    )

                // Only seed from pages whose current translation plan still proves that
                // their persisted result is reusable/terminal. A stale page snapshot must
                // not become a context predecessor merely because its old status is READY.
                contextFrontier.seed(
                    pages = store.state.value,
                    eligible = { pageKey, _ ->
                        batchPagePlans[pageKey]
                            ?.stages
                            ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                            ?.decision in setOf(
                            eu.kanade.translation.model.StageDecision.REUSE,
                            eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE,
                        )
                    },
                    terminalFailure = { pageKey, page ->
                        val durableRetryable = store.durableFailure(pageKey)?.status ==
                            eu.kanade.translation.artifact.ArtifactStageStatus.FAILED_RETRYABLE
                        val plannedTerminal = batchPagePlans[pageKey]
                            ?.stages
                            ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                            ?.decision in setOf(
                            eu.kanade.translation.model.StageDecision.FAILED,
                            eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                        )
                        (page.translationStatus == StageStatus.FAILED && !durableRetryable) ||
                            (plannedTerminal && !page.isTextlessTerminal)
                    },
                )
                rollingContext = contextFrontier.rollingContext

                fun plannedTranslationDecision(pageKey: String) =
                    batchPagePlans[pageKey]?.stages?.firstOrNull { it.stage == BatchStage.TRANSLATION }?.decision

                fun recordReusableContextPage(pageKey: String, page: PageTranslation) {
                    if (!isAi ||
                        plannedTranslationDecision(pageKey) !in setOf(
                            eu.kanade.translation.model.StageDecision.REUSE,
                            eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE,
                        )
                    ) {
                        return
                    }
                    if (page.ocrStatus in setOf(StageStatus.READY, StageStatus.TEXTLESS) &&
                        page.translationStatus in setOf(
                            StageStatus.READY,
                            StageStatus.SKIPPED,
                        )
                    ) {
                        // A reused page becomes context only when natural traversal reaches it.
                        // Pages after a missing predecessor remain retained by the frontier until
                        // that predecessor resolves, so they cannot leak into an earlier request.
                        recordContextPage(pageKey, page)
                    }
                }

                fun plannedTranslationNeedsWork(pageKey: String): Boolean =
                    plannedTranslationDecision(pageKey) == eu.kanade.translation.model.StageDecision.RUN ||
                        batchPagePlans[pageKey]
                            ?.stages
                            ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                            ?.let { decision ->
                                decision.decision == eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE &&
                                    decision.retryEligible
                            } == true

                fun plannedRenderNeedsWork(pageKey: String): Boolean =
                    batchPagePlans[pageKey]?.let { plan ->
                        val translation = plan.stages.first { it.stage == BatchStage.TRANSLATION }
                        val ocr = plan.stages.first { it.stage == BatchStage.OCR }
                        if (translation.decision in setOf(
                                eu.kanade.translation.model.StageDecision.FAILED,
                                eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                                eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                            ) ||
                            ocr.decision in setOf(
                                eu.kanade.translation.model.StageDecision.FAILED,
                                eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                                eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                            ) ||
                            translation.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                            translation.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE
                        ) {
                            false
                        } else {
                            plan.stages.any { decision ->
                                decision.stage in setOf(BatchStage.TRANSLATION, BatchStage.INPAINT, BatchStage.LAYOUT) &&
                                    (
                                        decision.decision == eu.kanade.translation.model.StageDecision.RUN ||
                                            decision.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                                            decision.reason == eu.kanade.translation.model.StageReasonCode.DEPENDENCY_INCOMPLETE
                                        )
                            }
                        }
                    } == true

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

                suspend fun resumeGate(page: PageTranslation?): BatchResumeGate {
                    val planned = page?.sourceFileName?.let(batchPagePlans::get)
                    if (planned != null) {
                        val ocr = planned.stages.first { it.stage == BatchStage.OCR }
                        val inpaint = planned.stages.first { it.stage == BatchStage.INPAINT }
                        val ocrNeedsWork = ocr.decision == eu.kanade.translation.model.StageDecision.RUN ||
                            ocr.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY ||
                            ocr.decision == eu.kanade.translation.model.StageDecision.FAILED
                        if (inpaint.decision == eu.kanade.translation.model.StageDecision.REUSE &&
                            page.cleanedImageName != null
                        ) {
                            val physicallyPresent = withContext(Dispatchers.IO) {
                                provider.findPageCleanedImage(
                                    manga.title,
                                    source,
                                    chapter.name,
                                    chapter.scanlator,
                                    page.cleanedImageName!!,
                                )?.let { it.exists() && it.length() > 0L } == true
                            }
                            if (!physicallyPresent) {
                                logcat(LogPriority.WARN) {
                                    "TachiyomiAT planned resume invalidated metadata-only cleaned image: " +
                                        "pageKey=${page.sourceFileName} cleaned=${page.cleanedImageName}"
                                }
                                return if (!ocrNeedsWork && page.hasCurrentInpaintMask) {
                                    BatchResumeGate.INPAINT_ONLY
                                } else {
                                    BatchResumeGate.FULL
                                }
                            }
                        }
                        return when {
                            ocrNeedsWork ->
                                BatchResumeGate.FULL
                            inpaint.decision == eu.kanade.translation.model.StageDecision.RUN ->
                                BatchResumeGate.INPAINT_ONLY
                            else -> BatchResumeGate.SKIP_ALL
                        }
                    }
                    // Null inpaintingModeUsed = legacy page persisted before this field; treat
                    // as a match so existing chapters are not mass re-translated on first open.
                    val desiredMode = inpaintingModeFromPref().name
                    val inpaintModeMatches = page?.inpaintingModeUsed == null || page.inpaintingModeUsed == desiredMode
                    val decision = BatchResumeGateDecider.decide(
                        page,
                        cleanedFileValid = true,
                        inpaintModeMatches = inpaintModeMatches,
                    )
                    if (decision == BatchResumeGateDecider.Decision.SKIP_ALL && page?.cleanedImageName != null) {
                        val physicallyPresent = withContext(Dispatchers.IO) {
                            provider.findPageCleanedImage(
                                manga.title,
                                source,
                                chapter.name,
                                chapter.scanlator,
                                page.cleanedImageName!!,
                            )?.let { it.exists() && it.length() > 0L } == true
                        }
                        if (!physicallyPresent) {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT resume invalidated metadata-only cleaned image: pageKey=${page.sourceFileName} cleaned=${page.cleanedImageName}"
                            }
                            return if (page.hasCurrentInpaintMask) {
                                BatchResumeGate.INPAINT_ONLY
                            } else {
                                BatchResumeGate.FULL
                            }
                        }
                    }
                    if (!inpaintModeMatches) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT resume re-inpainting for mode change: pageKey=${page?.sourceFileName} " +
                                "was=${page?.inpaintingModeUsed} now=$desiredMode"
                        }
                    }
                    return when (decision) {
                        BatchResumeGateDecider.Decision.SKIP_ALL -> BatchResumeGate.SKIP_ALL
                        BatchResumeGateDecider.Decision.INPAINT_ONLY -> BatchResumeGate.INPAINT_ONLY
                        BatchResumeGateDecider.Decision.FULL -> BatchResumeGate.FULL
                    }
                }

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

                suspend fun tryRender(pageKey: String) {
                    val mutex = renderMutexes.computeIfAbsent(pageKey) { Mutex() }
                    mutex.withLock {
                        val page = translationRegistry[pageKey] ?: return@withLock
                        if (page.ocrStatus == StageStatus.FAILED ||
                            page.translationStatus == StageStatus.FAILED ||
                            page.inpaintStatus == StageStatus.FAILED ||
                            page.renderStatus == StageStatus.FAILED
                        ) {
                            abortBatchCandidate(pageKey, page.activeError ?: "terminal stage failure")
                            return@withLock
                        }
                        if (page.isTextlessTerminal) {
                            tracker?.markTranslateSkipped(pageKey)
                            tracker?.markRenderSkipped(pageKey)
                            heldBitmapRegistry.recycleHeld(pageKey)
                            translationRegistry.remove(pageKey)
                            releaseBatchLease(pageKey)
                            return@withLock
                        }
                        if (page.renderStatus == StageStatus.READY && !plannedRenderNeedsWork(pageKey)) {
                            heldBitmapRegistry.recycleHeld(pageKey)
                            translationRegistry.remove(pageKey)
                            releaseBatchLease(pageKey)
                            tracker?.markRenderDone(pageKey)
                            return@withLock
                        }
                        val status = page.translationStatus
                        if (status != StageStatus.READY && status != StageStatus.PARTIAL) {
                            if (status == StageStatus.FAILED) {
                                heldBitmapRegistry.recycleHeld(pageKey)
                                translationRegistry.remove(pageKey)
                                releaseBatchLease(pageKey)
                            }
                            return@withLock
                        }
                        val inpaintStatus = page.inpaintStatus
                        if (inpaintStatus != StageStatus.READY && inpaintStatus != StageStatus.PARTIAL && inpaintStatus != StageStatus.TEXTLESS) {
                            if (inpaintStatus == StageStatus.FAILED) {
                                heldBitmapRegistry.recycleHeld(pageKey)
                                translationRegistry.remove(pageKey)
                                releaseBatchLease(pageKey)
                            }
                            // Inpaint isn't ready yet, wait for inpaintWorker to call tryRender
                            return@withLock
                        }
                        // READY/PARTIAL -> render. Consume the held bitmap on the happy path
                        // (no disk reload); spill/SKIP_ALL pages reload .cleaned.jpg instead.
                        val held = bitmapRegistry.remove(pageKey)
                        if (held != null) {
                            heldBitmapBytes.addAndGet(-held.byteCount.toLong())
                            countSlots.release()
                        }
                        val bitmap = held ?: page.cleanedImageName?.let { loadPersistedCleanedBitmap(manga, chapter, source, it) }
                        if (bitmap == null && inpaintStatus != StageStatus.TEXTLESS) {
                            page.inpaintStatus = StageStatus.FAILED
                            page.renderStatus = StageStatus.FAILED
                            page.recordAttemptFailure()
                            page.errorMessage = "Cleaned image is missing or unreadable; retry inpainting"
                            tracker?.markInpaintFailed(pageKey, page.errorMessage!!)
                            tracker?.markRenderFailed(pageKey, page.errorMessage!!)
                            val failurePersisted = persistBatchPageWithOomRecovery(pageKey, page)
                            abortBatchCandidate(pageKey, page.errorMessage!!)
                            if (failurePersisted is ChapterTranslationStore.PatchResult.Rejected) {
                                throw BatchPersistenceRejectedException(
                                    pageKey = pageKey,
                                    stage = BatchDiagnosticStage.RENDER,
                                )
                            }
                            return@withLock
                        }
                        var renderPersisted = false
                        try {
                            tracker?.markRenderRunning(pageKey)
                            val running = guardedBatchUpdate(pageKey, "batch render running", BatchStage.LAYOUT) {
                                (it ?: page).apply {
                                    renderStatus = StageStatus.RUNNING
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                            if (running is ChapterTranslationStore.PatchResult.Rejected) {
                                logcat(LogPriority.WARN) {
                                    "TachiyomiAT batch render running rejected (stale writer): " +
                                        "pageKey=$pageKey reason=${running.reason}"
                                }
                                abortBatchCandidate(pageKey, "render admission rejected: ${running.reason}")
                                throw BatchPersistenceRejectedException(
                                    pageKey = pageKey,
                                    stage = BatchDiagnosticStage.RENDER,
                                )
                            }
                            val renderInput = store.snapshot(pageKey)
                            RenderColorEstimator.recomputeFor(bitmap, page.blocks)
                            page.renderStatus = StageStatus.READY
                            page.updatedAt = System.currentTimeMillis()
                            val patch = RenderStagePatch(
                                pageKey = pageKey,
                                generation = renderInput.generation,
                                expectedPageVersion = renderInput.pageVersion,
                                expectedLeaseToken = renderInput.leaseToken,
                                expectedCleanedImageName = renderInput.page?.cleanedImageName ?: "",
                                expectedInpaintRevision = renderInput.page?.inpaintRevision ?: 0,
                                expectedOcrBlockFingerprints = renderInput.page?.ocrBlockFingerprints().orEmpty(),
                                expectedCandidateGenerationId = renderInput.candidateGenerationId,
                                expectedDependencyFingerprint = renderInput.dependencyFingerprint,
                                expectedArtifactPageVersion = renderInput.artifactPageVersion,
                                layoutFingerprint = expectedBatchFingerprints.layout,
                                blocks = page.blocks.mapIndexed { index, block ->
                                    RenderBlockPatch(
                                        blockIndex = index,
                                        expectedBlockFingerprint = renderInput.page?.blocks?.getOrNull(index)?.stableFingerprint() ?: "",
                                        textColor = block.textColor,
                                        strokeColor = block.strokeColor,
                                        strokeWidth = block.strokeWidth,
                                    )
                                },
                                renderStatus = StageStatus.READY,
                            )
                            val renderResult = store.mergeRender(patch)
                            renderPersisted = renderResult is StagePatchResult.Accepted
                            if (renderResult is StagePatchResult.Rejected) {
                                abortBatchCandidate(pageKey, "render commit rejected: ${renderResult.reason}")
                                throw BatchPersistenceRejectedException(
                                    pageKey = pageKey,
                                    stage = BatchDiagnosticStage.RENDER,
                                )
                            }
                            if (renderPersisted) {
                                // A newer committed display bundle may have just
                                // promoted; the file it superseded is now deletable.
                                deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
                            }
                            tracker?.markRenderDone(pageKey)
                        } catch (e: BatchPersistenceRejectedException) {
                            throw e
                        } catch (e: LayoutFailureException) {
                            page.renderStatus = StageStatus.FAILED
                            page.recordAttemptFailure()
                            val msg = if (e.blockIds.isNotEmpty()) "${e.message} (blocks: ${e.blockIds.joinToString()})" else e.message
                            page.errorMessage = msg
                            tracker?.markRenderFailed(pageKey, msg ?: "Layout failed")
                            logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render layout failed: $pageKey blocks=${e.blockIds}" }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            page.renderStatus = StageStatus.FAILED
                            page.recordAttemptFailure()
                            page.errorMessage = e.message
                            tracker?.markRenderFailed(pageKey, e.message ?: e::class.java.simpleName)
                            logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render failed: $pageKey" }
                        } finally {
                            try {
                                bitmap?.recycle()
                            } catch (_: Exception) {}
                            page.cleanedBitmap = null
                            if (!renderPersisted) {
                                val failurePersisted = persistBatchPageWithOomRecovery(pageKey, page)
                                abortBatchCandidate(pageKey, page.activeError ?: "render did not commit")
                                if (failurePersisted is ChapterTranslationStore.PatchResult.Rejected) {
                                    throw BatchPersistenceRejectedException(
                                        pageKey = pageKey,
                                        stage = BatchDiagnosticStage.RENDER,
                                    )
                                }
                            } else {
                                translationRegistry.remove(pageKey)
                                releaseBatchLease(pageKey)
                            }
                        }
                    }
                }

                val chunkCounter = AtomicLong(0L)

                /**
                 * Translates one AI envelope and commits its completed pages in
                 * natural order. The rolling context is the bounded window of
                 * recent source=>target pairs (voice/terminology continuity);
                 * a page whose output is a structural refusal fails alone — it
                 * never cascades to later pages of the envelope.
                 */
                suspend fun translateChunkAi(
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
                    chunk.pages.keys.forEach { pk ->
                        val p = translationRegistry[pk] ?: return@forEach
                        val running = guardedBatchUpdate(pk, "batch translation running", BatchStage.TRANSLATION) {
                            (it ?: p).apply {
                                translationStatus = StageStatus.RUNNING
                                errorMessage = null
                                updatedAt = System.currentTimeMillis()
                            }
                        }
                        if (running is ChapterTranslationStore.PatchResult.Rejected) {
                            admissionFailure = "translation admission rejected for $pk: ${running.reason}"
                        }
                        tracker?.markTranslateRunning(pk)
                        tracker?.markAiRunning(pk)
                    }
                    admissionFailure?.let { reason ->
                        val failure = protocolFailure(reason)
                        val anchor = firstUnresolved(emptySet()) ?: return ChunkCompletionOutcome.Failed(
                            failure = failure,
                            reason = reason,
                        )
                        val page = translationRegistry[anchor]
                        if (page != null) {
                            persistAiFailureOrThrow(
                                pageKey = anchor,
                                page = page,
                                failure = failure,
                                retryable = false,
                                partialCandidate = false,
                                envelopeId = null,
                                missingBlockIds = emptySet(),
                            )
                        }
                        tracker?.markTranslateFailed(anchor, reason)
                        tracker?.markAiFailed(anchor, reason)
                        return ChunkCompletionOutcome.Failed(
                            anchorPageKey = anchor,
                            terminalPageKeys = setOf(anchor),
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
                            is eu.kanade.translation.translator.AiChunkOutcome.Complete -> null
                            is eu.kanade.translation.translator.AiChunkOutcome.Paused,
                            is eu.kanade.translation.translator.AiChunkOutcome.Terminal,
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
                                is eu.kanade.translation.translator.AiChunkOutcome.Complete ->
                                    BatchEnvelopeLifecycle.SUCCEEDED
                                is eu.kanade.translation.translator.AiChunkOutcome.Paused,
                                is eu.kanade.translation.translator.AiChunkOutcome.Terminal,
                                -> BatchEnvelopeLifecycle.FAILED
                            },
                            pageKeys = chunk.pages.keys,
                            expectedItemCount = chunk.blockCount,
                            receivedItemCount = committedPages.values.sumOf { page ->
                                page.blocks.count { block -> block.translation.isNotBlank() }
                            },
                            reason = when (adaptiveOutcome) {
                                is eu.kanade.translation.translator.AiChunkOutcome.Complete ->
                                    BatchDiagnosticReason.SUCCESS
                                is eu.kanade.translation.translator.AiChunkOutcome.Paused ->
                                    BatchDiagnosticReason.TRANSIENT_FAILURE
                                is eu.kanade.translation.translator.AiChunkOutcome.Terminal ->
                                    BatchDiagnosticReason.TERMINAL_FAILURE
                            },
                        )
                        when (adaptiveOutcome) {
                            is eu.kanade.translation.translator.AiChunkOutcome.Complete -> {
                                return ChunkCompletionOutcome.Completed(committedPages.keys)
                            }
                            is eu.kanade.translation.translator.AiChunkOutcome.Paused -> {
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
                            is eu.kanade.translation.translator.AiChunkOutcome.Terminal -> {
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
                            eu.kanade.translation.translator.classifyProviderFailure(e)
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
                // The batch schedule is driven by [SequentialBatchCoordinator] (one
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
                val computeClass = TranslatorComputeClass.forTranslator(textTranslator)

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
                        withNativeLane(
                            timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                            chapterId = chapter.id,
                            chapterName = chapter.name,
                            pageKey = pageKey,
                            onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                        ) {
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
                        try {
                            withNativeLane(
                                timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                                chapterId = chapter.id,
                                chapterName = chapter.name,
                                pageKey = pageKey,
                                onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                            ) {
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
                        } catch (rejected: BatchPersistenceRejectedException) {
                            tracker?.markInpaintFailed(pageKey, rejected.message ?: "Inpaint persistence rejected")
                            abortBatchCandidate(pageKey, rejected.message ?: "Inpaint persistence rejected")
                            throw rejected
                        }
                        // Persist the cleaned bitmap off the native permit (matches the prior
                        // producer's post-inpaint publication step) and hand it to render.
                        val cleaned = target.cleanedBitmap
                        if (cleaned != null) {
                            val companionDir = ensureCompanionDir()
                            val published = persistCleanedBitmap(
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
                        val priorPageBlocksStandardTranslation = !isAi &&
                            plannedTranslation?.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE
                        val completedAiPageAfterPriorGap = isAi &&
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                            plannedTranslation?.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE &&
                            p.translationStatus in setOf(StageStatus.READY, StageStatus.SKIPPED) &&
                            (
                                expectedBatchFingerprints.translation == null ||
                                    p.translationFingerprint == expectedBatchFingerprints.translation
                                )
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
                            try {
                                tracker?.markAiRunning(pageKey)
                                tracker?.markTranslateRunning(pageKey)
                                textTranslator.translatePage(pageKey, p)
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

                // Render join: per-page join of the translation result with its inpaint/render
                // prerequisites. tryRender is idempotent (READY short-circuit) so it is safe to
                // call both at chunk-completion (AI) and here; the per-page render mutex keeps
                // it serialized. This is the join the coordinator awaits for each page.
                val nativeRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
                val translationRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

                fun signalFor(
                    signals: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
                    pageKey: String,
                ): CompletableDeferred<Unit> = signals.getOrPut(pageKey) { CompletableDeferred() }

                val renderJoin = object : RenderJoinWorker {
                    override fun onNativeBranchDone(pageKey: String) {
                        signalFor(nativeRenderSignals, pageKey).complete(Unit)
                    }

                    override fun onTranslationBranchDone(pageKey: String) {
                        signalFor(translationRenderSignals, pageKey).complete(Unit)
                    }

                    override suspend fun awaitAndRender(pageKey: String) {
                        signalFor(nativeRenderSignals, pageKey).await()
                        signalFor(translationRenderSignals, pageKey).await()
                        // A resumed retryable/terminal translation candidate owns no
                        // display publication. Its committed bundle remains the reader
                        // authority until an eligible retry succeeds, so never route a
                        // failed candidate through the normal render path.
                        if (!translationFailureFence(pageKey)) {
                            tryRender(pageKey)
                        }
                        nativeRenderSignals.remove(pageKey)
                        translationRenderSignals.remove(pageKey)
                    }

                    override suspend fun awaitAndSettle(pageKey: String) {
                        signalFor(nativeRenderSignals, pageKey).await()
                        signalFor(translationRenderSignals, pageKey).await()
                        // A paused/terminal anchor retains the prior committed
                        // display. Do not invoke tryRender on its provisional
                        // candidate or overwrite that display pointer.
                        nativeRenderSignals.remove(pageKey)
                        translationRenderSignals.remove(pageKey)
                    }
                }

                val coordinator = SequentialBatchCoordinator(
                    nativeWorker = nativeWorker,
                    translatorWorker = translatorWorker,
                    renderJoin = renderJoin,
                )

                var pass1Outcome: BatchPass1Outcome? = null
                try {
                    coroutineScope {
                        val orderedPages = orderedStreams.mapIndexed { index, (pageKey, _) ->
                            pageKey to (resolvedNaturalPageIndexes[pageKey] ?: index)
                        }

                        pass1Outcome = coordinator.runPass1(orderedPages, computeClass)
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

                val reconciliation = BatchProgressReconciler.reconcile(
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
                    store.flush()
                }
                store.reconcileArtifactRetention()
                onBatchClosed?.invoke(manga, chapter, source, store)
            }
        }
    }

    private suspend fun markBatchTranslationFailed(
        store: ChapterTranslationStore,
        pageKey: String,
        pageTranslation: PageTranslation,
        reason: String,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition,
    ): ChapterTranslationStore.PatchResult {
        pageTranslation.translationStatus = StageStatus.FAILED
        pageTranslation.errorMessage = reason
        // Translate is typically the first terminal stage, so it owns the attempt charge.
        // recordAttemptFailure is idempotent if a prior stage already failed.
        pageTranslation.recordAttemptFailure()
        return store.updatePageGuarded(pageKey, expectedPrecondition, "batch translation failure") {
            (it ?: pageTranslation).apply {
                translationStatus = StageStatus.FAILED
                errorMessage = reason
                attemptCount = pageTranslation.attemptCount
                retryCount = pageTranslation.retryCount
                updatedAt = System.currentTimeMillis()
            }
        }
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

    // T909 Phase 20.2: BatchWriteIdentity moved to pipeline/batch/BatchWriteGate.kt
    // (internal top-level, same module reachability).

    // T909 Phase 10: rebuild gate body moved to pipeline/EngineLane.kt.
    private suspend fun ensureEnginesBuiltFor(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ) {
        engines.ensureEnginesBuiltFor(fromLang, toLang)
    }

    // T909 Phase 14: permit-held ONNX phase body moved to
    // translation/pipeline/SinglePageOnnxPhase.kt (OnnxPhaseResult, the resume
    // paths, and the batch native stages moved with it). Same-signature stub
    // keeps the call sites; bitmap recycle/ownership points moved verbatim.
    private suspend fun translateSinglePageOnnx(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        readerStreamFn: (() -> InputStream)? = null,
        force: Boolean = true,
        stageListener: TranslationStageListener? = null,
    ): OnnxPhaseResult? =
        singlePageOnnxPhase.translateSinglePageOnnx(manga, chapter, source, pageKey, readerStreamFn, force, stageListener)

    // T909 Phase 12: single-page HTTP+render phase body moved to
    // translation/pipeline/SinglePageHttpRenderPhase.kt (outcome typing stays
    // ChunkCompletionOutcome; the PARTIAL retry predicate moved with it).
    // Same-signature stub keeps the call sites.
    private suspend fun translateSinglePageHttpRender(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        ctx: OnnxPhaseResult,
        stageListener: TranslationStageListener? = null,
    ): ChunkCompletionOutcome =
        singlePageHttpRenderPhase.translateSinglePageHttpRender(manga, chapter, source, pageKey, ctx, stageListener)

    // T909 Phase 7: PageTranslation.copyForResume moved to pipeline/CleanedPublication.kt
    // (imported top-level extension — call sites below resolve through it).

    private suspend fun loadPersistedCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        cleanedImageName: String,
    ): Bitmap? = cleanedPublication.loadPersistedCleanedBitmap(manga, chapter, source, cleanedImageName)

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
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    ): ChapterTranslationStore.PageSnapshot? = cleanedPublication.persistCleanedBitmap(
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

    private suspend fun persistOnnxCleanedImage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        result: OnnxPhaseResult,
    ): OnnxPhaseResult? = cleanedPublication.persistOnnxCleanedImage(manga, chapter, source, pageKey, result)

    private suspend fun retryInpaintDownscaled(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streams: List<Pair<String, () -> InputStream>>,
        decoded: DecodedPage,
        pageTranslation: PageTranslation,
    ): PageTranslation =
        singlePageOnnxPhase.retryInpaintDownscaled(manga, chapter, source, pageKey, streams, decoded, pageTranslation)

    private suspend fun analyzePage(
        fileName: String,
        bitmap: Bitmap,
        decoded: DecodedPage,
        store: ChapterTranslationStore,
        batchFingerprints: BatchExpectedFingerprints,
    ): PageTranslation =
        singlePageOnnxPhase.analyzePage(fileName, bitmap, decoded, store, batchFingerprints)

    private suspend fun inpaintPage(
        fileName: String,
        bitmap: Bitmap,
        pageTranslation: PageTranslation,
        batchFingerprint: String?,
        guardedWrite: suspend (String, (PageTranslation?) -> PageTranslation) -> ChapterTranslationStore.PatchResult,
    ): PageTranslation =
        singlePageOnnxPhase.inpaintPage(fileName, bitmap, pageTranslation, batchFingerprint, guardedWrite)

    // T909 Phase 1: memory governance bodies moved to translation/pipeline/MemoryGovernance.kt.
    fun forceReleaseNativeBuffers() {
        MemoryGovernance.forceReleaseNativeBuffers { recognitionEngine }
    }

    private fun preflightAnalyzeGate(bitmap: Bitmap, fileName: String) {
        MemoryGovernance.preflightAnalyzeGate({ recognitionEngine }, bitmap, fileName)
    }

    private fun preflightInpaintGate(bitmap: Bitmap, fileName: String) {
        MemoryGovernance.preflightInpaintGate({ recognitionEngine }, bitmap, fileName)
    }

    private fun reclaimTranslationMemory(reason: String, trimImageCache: Boolean) {
        MemoryGovernance.reclaimTranslationMemory(context, { recognitionEngine }, reason, trimImageCache)
    }

    private fun logDecodeDecision(
        fileName: String,
        width: Int,
        height: Int,
        beforeReclaim: DecodeDecision?,
        afterReclaim: DecodeDecision,
    ) {
        MemoryGovernance.logDecodeDecision(fileName, width, height, beforeReclaim, afterReclaim)
    }

    private fun handleCriticalTranslationOom(stage: String, oom: OutOfMemoryError) {
        MemoryGovernance.handleCriticalTranslationOom(this::forceReleaseNativeBuffers, stage, oom)
    }

    private suspend fun persistPageWithOomRecovery(
        store: ChapterTranslationStore,
        fileName: String,
        pageTranslation: PageTranslation,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    ): ChapterTranslationStore.PatchResult =
        pageStoreWriter.persistPageWithOomRecovery(store, fileName, pageTranslation, expectedPrecondition)

    // T909 Phase 1: decode/fingerprint bodies moved to translation/pipeline/PageDecode.kt.
    private fun decodePageBitmapAtSize(fileName: String, sampleSize: Int, streams: List<Pair<String, () -> InputStream>>): Bitmap? =
        PageDecode.decodePageBitmapAtSize(fileName, sampleSize, streams)

    private suspend fun decodePageBitmapForTranslation(fileName: String, streamFn: () -> InputStream): DecodedPage? =
        PageDecode.decodePageBitmapForTranslation(context, { recognitionEngine }, fileName, streamFn)

    private suspend fun computeSourceFingerprint(streamFn: () -> InputStream): String? =
        PageDecode.computeSourceFingerprint(streamFn)

    private fun batchExpectedFingerprints(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): BatchExpectedFingerprints =
        PageDecode.batchExpectedFingerprints(
            currentTranslatorSignature,
            currentOcrModel,
            currentReadingOrder,
            currentInpaintingMode,
            fromLang,
            toLang,
        )


    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> =
        eu.kanade.translation.util.getChapterPages(context, chapterPath)
}
