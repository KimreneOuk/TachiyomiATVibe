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
import eu.kanade.translation.batch.ChunkCompletionOutcome
import eu.kanade.translation.batch.SequentialBatchCoordinator
import eu.kanade.translation.batch.TranslationBatchProgressTracker
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
import eu.kanade.translation.pipeline.batch.BatchLaneWorkers
import eu.kanade.translation.pipeline.batch.BatchRenderJoin
import eu.kanade.translation.pipeline.batch.BatchResumeGate
import eu.kanade.translation.pipeline.batch.BatchResumePlanner
import eu.kanade.translation.pipeline.batch.BatchWriteGate
import eu.kanade.translation.pipeline.batch.BatchWriteIdentity
import eu.kanade.translation.pipeline.batch.HeldBitmapRegistry
import eu.kanade.translation.pipeline.batch.NativeLaneRunner
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
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationContextChunkPlanner
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.withProviderRequestPriority
import eu.kanade.translation.util.ShortHash
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import kotlinx.coroutines.CancellationException
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

    // T909 Phase 20.3: BatchResumeGate enum + resume planning moved to
    // pipeline/batch/BatchResumePlanner.kt.

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
                val sourceFingerprints = coroutineScope {
                    orderedStreams.map { (pageKey, streamFn) ->
                        async(Dispatchers.IO) {
                            pageKey to (computeSourceFingerprint(streamFn) ?: UNKNOWN_SOURCE_FINGERPRINT)
                        }
                    }.awaitAll().toMap()
                }

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
                    inpaintingModeFromPref = this::inpaintingModeFromPref,
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
                    releaseBatchPageLeaseFn = ::releaseBatchPageLease,
                    persistPageWithOomRecoveryFn = ::persistPageWithOomRecovery,
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
                    loadPersistedCleanedBitmapFn = this::loadPersistedCleanedBitmap,
                    deleteRetiredCleanedFileFn = this::deleteRetiredCleanedFile,
                    abortBatchCandidateFn = ::abortBatchCandidate,
                )

                suspend fun tryRender(pageKey: String) = renderJoin.tryRender(pageKey)

                val computeClass = TranslatorComputeClass.forTranslator(textTranslator)

                // T909 Phase 20.5: the lane workers moved to
                // pipeline/batch/BatchLaneWorkers.kt (nativeWorker, translatorWorker,
                // translateChunkAi, completeChunklessPage). The closure web became class
                // state; the SAME registry/identity/frontier instances are injected.
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
                    markPageTimedOutFn = this::markPageTimedOut,
                    analyzePageFn = this::analyzePage,
                    decodePageBitmapForTranslationFn = this::decodePageBitmapForTranslation,
                    preflightInpaintGateFn = this::preflightInpaintGate,
                    inpaintPageFn = this::inpaintPage,
                    retryInpaintDownscaledFn = this::retryInpaintDownscaled,
                    persistCleanedBitmapFn = this::persistCleanedBitmap,
                    abortBatchCandidateFn = ::abortBatchCandidate,
                )


                val coordinator = SequentialBatchCoordinator(
                    nativeWorker = batchLaneWorkers.nativeWorker,
                    translatorWorker = batchLaneWorkers.translatorWorker,
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
