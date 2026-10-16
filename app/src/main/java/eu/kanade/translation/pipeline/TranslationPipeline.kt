package eu.kanade.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.DeviceStageNormalization
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.inpainting.InpaintStampDecision
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.NativeStallState
import eu.kanade.translation.engines.translator.NativeStallWatchdog
import eu.kanade.translation.engines.translator.retry.AiTranslationRetryPlanner
import eu.kanade.translation.engines.translator.withProviderRequestPriority
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.pipeline.CleanedPublication
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.pipeline.DeferredPagePublications
import eu.kanade.translation.pipeline.EngineLane
import eu.kanade.translation.pipeline.OnnxPhaseResult
import eu.kanade.translation.pipeline.PageDecode
import eu.kanade.translation.pipeline.PageStoreWriter
import eu.kanade.translation.pipeline.SinglePageHttpRenderPhase
import eu.kanade.translation.pipeline.SinglePageOnnxPhase
import eu.kanade.translation.pipeline.adaptive.DevicePagePermitGate
import eu.kanade.translation.pipeline.adaptive.DevicePermitPriority
import eu.kanade.translation.pipeline.batch.BatchChapterTranslator
import eu.kanade.translation.pipeline.batch.NativeLaneRunner
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.execution.NativeRunQuarantine
import eu.kanade.translation.pipeline.execution.PreparedPage
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.pipeline.execution.isPreparedPageTerminal
import eu.kanade.translation.pipeline.execution.publishPreparedPageFromOcr
import eu.kanade.translation.pipeline.memory.MemoryGovernance
import eu.kanade.translation.pipeline.memory.TranslationMemoryBudget
import eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.DecodeDecisionKind
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.toPrecondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class LayoutFailureException(val blockIds: List<String>, message: String) : Exception(message)

private class NativePageAlreadyInFlightException : Exception()

class TranslationPipeline private constructor(
    private val context: Context,
    private val provider: TranslationFileProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
    /**  test seam; production defaults preserve the result timer contract. */
    private val stallThresholdMs: Long = NATIVE_STALL_THRESHOLD_MS,
    private val nativeTimeoutMs: Long = ONNX_PHASE_TIMEOUT_MS,
    /**
     * Test seam for the HTTP+render result timer.
     * Production defaults preserve the [SINGLE_PAGE_TIMEOUT_MS] contract; tests
     * inject a short value to exercise the typed timeout path deterministically.
     */
    internal val singlePageTimeoutMs: Long = SINGLE_PAGE_TIMEOUT_MS,
    private val testConstruction: TestConstruction?,
) : TranslationExecutor, java.io.Closeable {

    constructor(
        context: Context,
        provider: TranslationFileProvider,
        downloadProvider: DownloadProvider = Injekt.get(),
        translationPreferences: TranslationPreferences = Injekt.get(),
        streamRegistry: TranslationStreamRegistry = Injekt.get(),
        stallThresholdMs: Long = NATIVE_STALL_THRESHOLD_MS,
        nativeTimeoutMs: Long = ONNX_PHASE_TIMEOUT_MS,
        singlePageTimeoutMs: Long = SINGLE_PAGE_TIMEOUT_MS,
    ) : this(
        context,
        provider,
        downloadProvider,
        translationPreferences,
        streamRegistry,
        stallThresholdMs,
        nativeTimeoutMs,
        singlePageTimeoutMs,
        null,
    )

    @VisibleForTesting
    internal data class TestConstruction(
        val engineRebuildMutex: kotlinx.coroutines.sync.Mutex,
        val inFlightPageKeys: MutableSet<String>,
        val nativeRunScope: CoroutineScope,
        val nativeStallWatchdog: NativeStallWatchdog,
        val nativeRunQuarantine: NativeRunQuarantine,
        val engines: EngineLane,
        val devicePagePermitGate: DevicePagePermitGate? = null,
    )

    override fun close() {
        nativeRunScope.cancel()
    }

    companion object {
        @VisibleForTesting
        internal fun createForTesting(
            context: Context,
            provider: TranslationFileProvider,
            downloadProvider: DownloadProvider,
            translationPreferences: TranslationPreferences,
            streamRegistry: TranslationStreamRegistry,
            stallThresholdMs: Long,
            nativeTimeoutMs: Long,
            singlePageTimeoutMs: Long,
            testConstruction: TestConstruction,
        ): TranslationPipeline = TranslationPipeline(
            context = context,
            provider = provider,
            downloadProvider = downloadProvider,
            translationPreferences = translationPreferences,
            streamRegistry = streamRegistry,
            stallThresholdMs = stallThresholdMs,
            nativeTimeoutMs = nativeTimeoutMs,
            singlePageTimeoutMs = singlePageTimeoutMs,
            testConstruction = testConstruction,
        )

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
         * persist .cleaned). HTTP translation runs outside the permit, while Canvas
         * rendering reacquires it after the request completes. The HTTP/render phase
         * has its own timeout (OkHttp timeouts + render work). Keeping
         * the ONNX phase shorter ensures the permit is released promptly so the next
         * prefetch page's ONNX work can overlap this page's network call.
         */
        const val ONNX_PHASE_TIMEOUT_MS = 90_000L

        /**  occupancy threshold; aligned with the native result timer. */
        const val NATIVE_STALL_THRESHOLD_MS = ONNX_PHASE_TIMEOUT_MS

        const val UNKNOWN_SOURCE_FINGERPRINT = "source-fingerprint-unavailable"

        /**
         * Truthful name for the
         * HTTP+render result timer. Timeout copy must name the timer that
         * fired, never an unrelated duration.
         */
        const val REASON_HTTP_RENDER_TIMER_EXPIRED = "HTTP+render result timer expired; translation failed"
    }

    /** Native admission is owned by [nativeRunQuarantine]. */

    /** Current native lane owner, used by progress reporting. */
    internal fun permitHolderPageKeySnapshot(): String? = engines.permitHolderPageKeySnapshot()

    private val engineRebuildMutex = testConstruction?.engineRebuildMutex ?: kotlinx.coroutines.sync.Mutex()

    /**
     * pageKeys currently mid-flight in [translateSinglePage]. Guards against
     * the same page being queued behind itself (e.g. auto-mode re-enqueue on
     * scroll, or a user double-tapping the per-page button). Thread-safe
     * because native quarantine and [closeEngines] touch this outside
     * the native lane.
     */
    private val inFlightPageKeys = testConstruction?.inFlightPageKeys ?: ConcurrentHashMap.newKeySet<String>()

    // Native work runs in an independent scope so caller cancellation cannot
    // falsely signal native exit. The quarantine owns admission until real exit.
    private val nativeRunScope = testConstruction?.nativeRunScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeStallWatchdog = testConstruction?.nativeStallWatchdog ?: NativeStallWatchdog(
        scope = nativeRunScope,
        thresholdMs = stallThresholdMs,
    )
    private val nativeRunQuarantine = testConstruction?.nativeRunQuarantine ?: NativeRunQuarantine(
        scope = nativeRunScope,
        occupancyObserver = object : NativeRunQuarantine.OccupancyObserver {
            override fun onLaneOccupied(token: Long, pageKey: String, startedAtEpochMs: Long) {
                nativeStallWatchdog.onLaneOccupied(token, pageKey, startedAtEpochMs)
            }

            override fun onLaneReleased(token: Long) {
                nativeStallWatchdog.onLaneReleased(token)
            }
        },
    )

    /** Reader-visible native occupancy state; null means no stall is active. */
    val nativeStall: kotlinx.coroutines.flow.StateFlow<NativeStallState?> = nativeStallWatchdog.state

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
     * factory that creates a [TranslationBatchProgressTracker] for a
     * batch and registers it in the manager's tracker map so the UI can observe
     * it. Mirrors the [activeStoreResolver] pattern. Set by [TranslationManager].
     */
    @Volatile
    var batchTrackerFactory: ((chapterId: Long, store: ChapterTranslationStore, orderedPageKeys: List<String>) -> TranslationBatchProgressTracker?)? = null

    @VisibleForTesting
    internal val devicePagePermitGate = testConstruction?.devicePagePermitGate
        ?: DevicePagePermitGate()

    // EngineLane owns engine construction and native-call drainage. Its short
    // drain grace is retried by epoch, and the drain runs off the main thread.
    internal val engines = testConstruction?.engines ?: EngineLane(
        context = context,
        translationPreferences = translationPreferences,
        nativeRunQuarantine = nativeRunQuarantine,
        inFlightPageKeys = inFlightPageKeys,
        onPageStuck = { onPageStuck },
        drainGraceMs = EngineLane.ENGINE_DRAIN_GRACE_MS,
        drainScope = nativeRunScope,
    )

    /** Warms the configured translation engines. */
    suspend fun warmUp() {
        engines.warmUp()
    }

    @VisibleForTesting
    internal suspend fun <T> withNativeLane(
        timeoutMs: Long,
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        priority: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
        block: suspend () -> T,
    ): T? {
        val scheduleTrace = TranslationTrace.currentRun()?.schedule
        val permit = devicePagePermitGate.acquire(priority) ?: run {
            onTimeout()
            return null
        }
        return try {
            engines.withNativeLane(timeoutMs, chapterId, chapterName, pageKey, onTimeout) {
                // Count active native ownership only after both device-permit and
                // quarantine admission have succeeded. The captured schedule is
                // used because NativeRunQuarantine may execute the block in its
                // independent native scope.
                val nativeLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.NATIVE)
                try {
                    block()
                } finally {
                    nativeLaneToken?.close()
                }
            }
        } finally {
            permit.release()
        }
    }

    private suspend fun drainDeferredPublications(deferred: DeferredPagePublications) {
        val span = TranslationTrace.beginStage(
            TranslationTraceStage.DEFERRED_STORAGE_DRAIN,
            lane = TranslationTraceLane.STORAGE,
        )
        try {
            deferred.drainAll()
            span.end(TranslationTraceOutcome.SUCCESS)
        } catch (t: Throwable) {
            span.end(
                if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                error = t,
            )
            throw t
        }
    }

    // EngineLane owns engine instances; these getters read their active configuration.
    private val currentOcrModel get() = engines.currentOcrModel

    private val currentReadingOrder get() = engines.currentReadingOrder

    private val textTranslator get() = engines.textTranslator

    private val recognitionEngine get() = engines.recognitionEngine

    private val currentInpaintingMode get() = engines.currentInpaintingMode

    private val currentTranslatorSignature get() = engines.currentTranslatorSignature

    private fun inpaintingModeFromPref(): InpaintingMode = engines.inpaintingModeFromPref()

    private fun inpaintingStampDecision(): InpaintStampDecision = engines.inpaintingStampDecision()

    internal fun setInpaintingHardwareOverride(override: InpaintingHardwareOverride) {
        engines.setInpaintingHardwareOverride(override)
    }

    internal fun setReaderPaddleOcrComputeDeviceOverride(device: tachiyomi.domain.translation.PaddleOcrComputeDevice?) {
        engines.setReaderPaddleOcrComputeDeviceOverride(device)
    }

    fun closeEngines() {
        engines.closeEngines()
    }

    /**
     * Listener that lets the translator share the same [ChapterTranslationStore]
     * with the [TranslationManager] (and the reader observing it). If no
     * listener is provided, the translator falls back to opening a local store.
     */
    @Volatile
    var activeStoreResolver: (suspend (Translation) -> ChapterTranslationStore?)? = null

    /** Chapter-close hook for bounded storage cleanup owned by TranslationManager. */
    @Volatile
    var onBatchClosed: (suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit)? = null

    // PageStoreWriter owns source-stream lookup, guarded store writes, and page failure records.
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

    private suspend fun resolveActiveStore(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): ChapterTranslationStore? = pageStoreWriter.resolveActiveStore(manga, chapter, source)

    private suspend fun markPageTimedOut(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        timeoutMs: Long = ONNX_PHASE_TIMEOUT_MS,
        nativeTimer: Boolean = true,
    ) {
        // The store placeholder names the timer that actually
        // fired — native (`withNativeLane`) vs HTTP+render (`withTimeoutOrNull`).
        pageStoreWriter.markPageTimedOut(manga, chapter, source, pageKey, timeoutMs, nativeTimer)
    }

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
     * .cleaned. Provider translation runs outside the permit; Canvas rendering
     * reacquires it after the request. Cooperative cancel checks, color estimation,
     * and page-state persistence follow their respective phases. The reader draws
     * translated text live over the cleaned image.
     * Splitting the permit-held ONNX work from the network-bound HTTP translate lets the next
     * prefetch page's ONNX overlap this page's network call — the same asymmetry
     * benefit the batch path already derives.
     *
     * Scheduling scope: this single-page path bypasses the batch coordinator
     * pass (`runPass1`) by design — it processes
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
        origin: PageWriteOrigin,
    ): SinglePageOutcome {
        //  refuses a new promise while the native lane is visibly stalled.
        // Check before lease admission so this tap performs no writer/native work.
        nativeStall.value?.let { stalled ->
            return SinglePageOutcome.Stalled(stalled.pageKey, stalled.stalledAtEpochMs)
        }
        val leaseStore = resolveActiveStore(manga, chapter, source)
        // Session admission owns the batch/reader boundary; a low-level lease
        // denial is returned as a typed rejection without observing another
        // origin's terminal state.
        val acquisition = withProviderRequestPriority(AdmissionPriority.INTERACTIVE) {
            leaseStore?.let {
                acquireReaderPageLease(
                    store = it,
                    pageKey = pageKey,
                    stage = PageStage.Ocr,
                    origin = origin,
                    site = TranslationTraceSite.READER_SINGLE_PAGE,
                )
            }
        }
        if (acquisition is LeaseAcquisition.Denied) {
            return SinglePageOutcome.Rejected(
                acquisition.owner,
                if (acquisition.owner != null) PageRejectionKind.ALREADY_OWNED else PageRejectionKind.OTHER,
                acquisition.reason,
            )
        }
        try {
            return withProviderRequestPriority(AdmissionPriority.INTERACTIVE) {
                runGrantedSinglePageBoundary(
                    leaseStore = leaseStore,
                    manga = manga,
                    chapter = chapter,
                    source = source,
                    pageKey = pageKey,
                    force = force,
                    stageListener = stageListener,
                    origin = origin,
                )
            }
        } finally {
            releaseReaderPageLease(leaseStore, pageKey, origin)
        }
    }

    /** Runs single-page work while the caller holds the page lease under [origin]. */
    private suspend fun runGrantedSinglePageBoundary(
        leaseStore: ChapterTranslationStore?,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin,
    ): SinglePageOutcome {
        val store = leaseStore ?: resolveActiveStore(manga, chapter, source)
            ?: return SinglePageOutcome.Failed(pageKey, "store unavailable")
        val snapshot = store.snapshot(pageKey)

        if (inFlightPageKeys.contains(pageKey)) {
            logcat(LogPriority.DEBUG) {
                "TachiyomiAT native admission rejected: chapter=${chapter.name} " +
                    "pageKey=$pageKey reason=already in flight (residual)"
            }
            return SinglePageOutcome.Rejected(
                null,
                PageRejectionKind.ALREADY_OWNED,
                "page already translating",
            )
        }

        val traceRun = TranslationTrace.currentRun()
        val nativeQueueSpan = traceRun?.beginStage(TranslationTraceStage.NATIVE_QUEUE)

        val attempt = AttemptAuthority(
            store = store,
            pageKey = pageKey,
            initial = snapshot.toPrecondition(),
            callerJob = coroutineContext[Job],
        )
        val owner = CleanedOwner()
        val sourceCustody = SourceBitmapCustody()
        val deferredPublications = DeferredPagePublications()

        engines.beginTranslatorUse()
        try {
            // Segment 1: Admitted native OCR
            val onnxResult = try {
                try {
                    withNativeLane(
                        timeoutMs = nativeTimeoutMs,
                        chapterId = chapter.id,
                        chapterName = chapter.name,
                        pageKey = pageKey,
                        onTimeout = {
                            deferredPublications.orphaned = true
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT Segment 1 native OCR timed out for $pageKey"
                            }
                        },
                        priority = if (origin == PageWriteOrigin.MANUAL) {
                            DevicePermitPriority.FOREGROUND
                        } else {
                            DevicePermitPriority.BACKGROUND
                        },
                    ) {
                        nativeQueueSpan?.end()
                        if (!inFlightPageKeys.add(pageKey)) {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT native admission rejected: chapter=${chapter.name} pageKey=$pageKey reason=already in flight"
                            }
                            throw NativePageAlreadyInFlightException()
                        }
                        try {
                            val generation = store.snapshot(pageKey).generation
                            store.withGeneration(generation) {
                                translateSinglePageOnnx(
                                    manga = manga,
                                    chapter = chapter,
                                    source = source,
                                    pageKey = pageKey,
                                    readerStreamFn = null,
                                    force = force,
                                    stageListener = stageListener,
                                    deferredPublications = deferredPublications,
                                    sourceCustody = sourceCustody,
                                    attempt = attempt,
                                    owner = owner,
                                    inlineInpaint = false,
                                )
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException || t is NativePageAlreadyInFlightException) throw t
                            logcat(LogPriority.ERROR, t) { "TachiyomiAT ONNX phase failed: pageKey=$pageKey" }
                            markPageFailed(manga, chapter, source, pageKey, t)
                            throw t
                        } finally {
                            inFlightPageKeys.remove(pageKey)
                        }
                    }.also {
                        if (it == null) nativeQueueSpan?.end(TranslationTraceOutcome.PAUSE)
                    }
                } catch (_: NativePageAlreadyInFlightException) {
                    return SinglePageOutcome.Rejected(
                        null,
                        PageRejectionKind.ALREADY_OWNED,
                        "page already translating",
                    )
                }
            } catch (t: Throwable) {
                runCatching { drainDeferredPublications(deferredPublications) }
                    .onFailure { drainError ->
                        logcat(LogPriority.ERROR, drainError) {
                            "TachiyomiAT deferred storage publication failed: pageKey=$pageKey"
                        }
                    }
                throw t
            }

            try {
                drainDeferredPublications(deferredPublications)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) { "TachiyomiAT deferred storage publication failed: pageKey=$pageKey" }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            }

            if (onnxResult == null) {
                val page = store.state.value[pageKey]
                if (page != null && isPreparedPageTerminal(page)) {
                    return SinglePageOutcome.Completed
                }
                attempt.markTimeout { current ->
                    val msg = PageStoreWriter.timeoutFailureMessage(nativeTimer = true)
                    current.apply {
                        ocrStatus = StageStatus.FAILED
                        errorMessage = msg
                        updatedAt = System.currentTimeMillis()
                    }
                }
                return SinglePageOutcome.Failed(pageKey, "native phase timed out")
            }

            if (onnxResult.pageTranslation.blocks.isEmpty() || onnxResult.pageTranslation.ocrStatus == StageStatus.FAILED) {
                if (onnxResult.pageTranslation.ocrStatus == StageStatus.FAILED) {
                    return SinglePageOutcome.Failed(
                        pageKey,
                        onnxResult.pageTranslation.errorMessage ?: "OCR failed",
                    )
                }
                return SinglePageOutcome.Completed
            }

            // Structured fork/join
            val nativeDraft = onnxResult.pageTranslation.detachedCopy()
            val httpDraft = onnxResult.pageTranslation.detachedCopy()
            val httpCtx = onnxResult.copy(pageTranslation = httpDraft)

            val completionOutcome: TranslationCompletionOutcome = coroutineScope {
                val nativeBranch = async {
                    try {
                        val laneResult: InpaintLaneResult? = withNativeLane(
                            timeoutMs = nativeTimeoutMs,
                            chapterId = chapter.id,
                            chapterName = chapter.name,
                            pageKey = pageKey,
                            priority = DevicePermitPriority.FOREGROUND,
                        ) {
                            val inpaintSpan = traceRun?.beginStage(
                                TranslationTraceStage.INPAINT,
                                lane = TranslationTraceLane.NATIVE,
                                provider = TranslationTraceProvider.CPU,
                            )
                            val s = sourceCustody.take()
                            if (s == null) {
                                inpaintSpan?.end(TranslationTraceOutcome.CANCELLED)
                                return@withNativeLane InpaintLaneResult.SoftNull
                            }
                            try {
                                val cleaned = try {
                                    recognitionEngine.inpaint(s, nativeDraft)
                                } finally {
                                    inpaintSpan?.end()
                                }
                                if (cleaned != null) {
                                    owner.produce(cleaned, CleanedProvenance.INPAINT)
                                    InpaintLaneResult.Produced(cleaned)
                                } else {
                                    InpaintLaneResult.SoftNull
                                }
                            } finally {
                                if (!owner.owns(s)) {
                                    try {
                                        s.recycle()
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        if (owner.isAvailable) {
                            cleanedPublication.enrollCleanedPersistence(
                                manga = manga,
                                chapter = chapter,
                                source = source,
                                pageKey = pageKey,
                                store = store,
                                pageTranslation = nativeDraft,
                                owner = owner,
                                attempt = attempt,
                                deferredPublications = deferredPublications,
                            )
                        }
                        when (laneResult) {
                            is InpaintLaneResult.Produced -> NativeBranchOutcome.Success
                            is InpaintLaneResult.SoftNull -> NativeBranchOutcome.SoftNull
                            null -> NativeBranchOutcome.TimedOut
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        logcat(LogPriority.ERROR, t) { "TachiyomiAT native inpaint branch failed: pageKey=$pageKey" }
                        NativeBranchOutcome.Failed(t)
                    }
                }

                try {
                    withTimeout(singlePageTimeoutMs) {
                        val httpOutcome = try {
                            singlePageHttpRenderPhase.translateSinglePageHttpOnly(
                                manga = manga,
                                chapter = chapter,
                                source = source,
                                pageKey = pageKey,
                                ctx = httpCtx,
                                attempt = attempt,
                                stageListener = stageListener,
                                origin = origin,
                            )
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            logcat(LogPriority.ERROR, t) { "TachiyomiAT HTTP branch failed: pageKey=$pageKey" }
                            val httpError = t.message ?: "HTTP translation failed"
                            attempt.write("HTTP translation failure") { current ->
                                current.apply {
                                    translationStatus = StageStatus.FAILED
                                    translationError = httpError
                                    errorMessage = httpError
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                            return@withTimeout TranslationCompletionOutcome.Failed(httpError)
                        }

                        val nativeResult = nativeBranch.await()

                        // C1 & C2: inpaint segment TimedOut
                        if (nativeResult is NativeBranchOutcome.TimedOut) {
                            val translationDurable = httpOutcome is TranslationCompletionOutcome.Completed
                            attempt.markTimeout { current ->
                                val msg = PageStoreWriter.timeoutFailureMessage(nativeTimer = true)
                                current.apply {
                                    if (inpaintStatus != StageStatus.READY) {
                                        inpaintStatus = StageStatus.FAILED
                                        inpaintError = msg
                                    }
                                    if (!translationDurable) {
                                        if (translationStatus != StageStatus.READY && translationStatus != StageStatus.PARTIAL) {
                                            translationStatus = StageStatus.FAILED
                                            translationError = msg
                                        }
                                    }
                                    if (renderStatus != StageStatus.READY) {
                                        renderStatus = StageStatus.FAILED
                                        renderError = msg
                                    }
                                    errorMessage = msg
                                    retryCount += 1
                                    attemptCount += 1
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                            return@withTimeout TranslationCompletionOutcome.Failed("native phase timed out")
                        }

                        // C5: native branch fails (non-timeout exception)
                        if (nativeResult is NativeBranchOutcome.Failed) {
                            val nativeError = nativeResult.throwable.message ?: "native inpaint failed"
                            attempt.write("native inpaint failure") { current ->
                                current.apply {
                                    inpaintStatus = StageStatus.FAILED
                                    inpaintError = nativeError
                                    errorMessage = nativeError
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                            return@withTimeout TranslationCompletionOutcome.Failed(nativeError)
                        }

                        if (httpOutcome !is TranslationCompletionOutcome.Completed) {
                            return@withTimeout httpOutcome
                        }

                        // Post-join: Admitted retry Segment 3 (C6) if needed + renderSinglePageTranslated
                        singlePageHttpRenderPhase.renderSinglePageTranslated(
                            manga = manga,
                            chapter = chapter,
                            source = source,
                            pageKey = pageKey,
                            ctx = httpCtx,
                            owner = owner,
                            attempt = attempt,
                            stageListener = stageListener,
                            origin = origin,
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    val timeoutStore = resolveActiveStore(manga, chapter, source)
                    val timeoutPage = timeoutStore?.state?.value?.get(pageKey)
                    if (timeoutPage != null && isPreparedPageTerminal(timeoutPage)) {
                        TranslationCompletionOutcome.Completed
                    } else {
                        attempt.markTimeout { current ->
                            val msg = PageStoreWriter.timeoutFailureMessage(nativeTimer = false)
                            current.apply {
                                if (translationStatus != StageStatus.READY && translationStatus != StageStatus.PARTIAL) {
                                    translationStatus = StageStatus.FAILED
                                    translationError = msg
                                }
                                if (renderStatus != StageStatus.READY) {
                                    renderStatus = StageStatus.FAILED
                                    renderError = msg
                                }
                                errorMessage = msg
                                retryCount += 1
                                attemptCount += 1
                                updatedAt = System.currentTimeMillis()
                            }
                        }
                        TranslationCompletionOutcome.Failed(REASON_HTTP_RENDER_TIMER_EXPIRED)
                    }
                }
            }

            return when (completionOutcome) {
                is TranslationCompletionOutcome.Paused -> SinglePageOutcome.Paused(completionOutcome.nextEligibleRetryAtEpochMs)
                is TranslationCompletionOutcome.PersistenceRejected ->
                    SinglePageOutcome.Rejected(null, PageRejectionKind.PERSISTENCE_FAILED, completionOutcome.reason)
                is TranslationCompletionOutcome.Failed -> SinglePageOutcome.Failed(pageKey, completionOutcome.reason)
                is TranslationCompletionOutcome.Unexpected -> SinglePageOutcome.Failed(pageKey, completionOutcome.reason)
                is TranslationCompletionOutcome.Completed -> SinglePageOutcome.Completed
            }
        } finally {
            try {
                drainDeferredPublications(deferredPublications)
            } finally {
                try {
                    chapter.id?.let { chapterId ->
                        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
                    }
                } finally {
                    try {
                        sourceCustody.close()
                    } finally {
                        try {
                            owner.release()
                        } finally {
                            try {
                                engines.endTranslatorUse()
                            } finally {
                                try {
                                    recognitionEngine.reclaimPooledMemory()
                                } catch (e: Throwable) {
                                    logcat(LogPriority.WARN, e) {
                                        "Failed to reclaim pooled recognition memory for $pageKey"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private sealed interface InpaintLaneResult {
        data class Produced(val bitmap: Bitmap) : InpaintLaneResult
        object SoftNull : InpaintLaneResult
    }

    private sealed interface NativeBranchOutcome {
        object Success : NativeBranchOutcome
        object SoftNull : NativeBranchOutcome
        object TimedOut : NativeBranchOutcome
        data class Failed(val throwable: Throwable) : NativeBranchOutcome
    }


    /**
     * Lease admission for reader-originated single-page work. Session
     * admission prevents a BATCH-owned page from being reached by a reader;
     * any low-level denial is returned as a typed rejection. A MANUAL request
     * still evicts an in-flight AUTO lease under reader-session policy.
     */
    private suspend fun acquireReaderPageLease(
        store: ChapterTranslationStore,
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
        site: TranslationTraceSite,
    ): LeaseAcquisition = TranslationTrace.withLeaseWait(
        site = site,
        leaseKind = when (stage) {
            PageStage.Ocr -> TranslationTraceLeaseKind.OCR
            PageStage.Translation -> TranslationTraceLeaseKind.TRANSLATION
            PageStage.Inpaint -> TranslationTraceLeaseKind.INPAINT
            PageStage.Render -> TranslationTraceLeaseKind.RENDER
        },
    ) {
        store.tryAcquirePageStageLease(pageKey, stage, origin)
    }

    private suspend fun releaseReaderPageLease(
        store: ChapterTranslationStore?,
        pageKey: String,
        origin: PageWriteOrigin,
    ) {
        if (store == null) return
        store.releasePageStageLease(pageKey, origin)
    }

    /** Releases the batch's page lease at an atomic stage boundary (terminal render/failure). */
    private suspend fun releaseBatchPageLease(store: ChapterTranslationStore, pageKey: String) {
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
    }

    // CleanedPublication owns cleaned-image files and their durable store metadata.
    private val cleanedPublication = CleanedPublication(
        provider = provider,
        streamRegistry = streamRegistry,
        currentInpaintingMode = { currentInpaintingMode },
        currentNeuralInpaintModel = { engines.currentNeuralInpaintModel },
    )

    // SinglePageHttpRenderPhase owns provider translation and display rendering.
    // Engine reads come from EngineLane at call time so rebuilds remain visible.
    private val singlePageHttpRenderPhase = SinglePageHttpRenderPhase(
        translationPreferences = translationPreferences,
        provider = provider,
        streamRegistry = streamRegistry,
        engines = engines,
        devicePagePermitGate = devicePagePermitGate,
        cleanedPublication = cleanedPublication,
        expectedBatchFingerprints = { fromLang, toLang -> batchExpectedFingerprints(fromLang, toLang) },
        retryInpaintDownscaledFn = { manga, chapter, source, pageKey, streams, decoded, pageTranslation, owner, attempt ->
            retryInpaintDownscaledAdmitted(manga, chapter, source, pageKey, streams, decoded, pageTranslation, owner, attempt)
        },
    )

    // SinglePageOnnxPhase owns decoding, recognition, inpainting, and resume
    // state while sharing EngineLane's rebuild mutex and native admission.
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
     * Prepared-page boundary: runs the native phase (decode →
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
        //   only the rolling auto coordinator calls the prepared
        // boundary — its leases are AUTO (never preemptive; MANUAL evicts it).
        // A denied lease lets the coordinator retry without waiting for another owner.
        if (leaseStore != null &&
            acquireReaderPageLease(
                store = leaseStore,
                pageKey = pageKey,
                stage = PageStage.Ocr,
                origin = PageWriteOrigin.AUTO,
                site = TranslationTraceSite.ROLLING_AUTO_PREPARED,
            ) is LeaseAcquisition.Denied
        ) {
            return null
        }
        try {
            // The same storage-tail deferral as
            // [runGrantedSinglePageBoundary] — the permit is released before
            // the resume paths' storage publication runs; orphaned tails run
            // inline; the normal-path drain is fail-closed.
            val deferredPublications = DeferredPagePublications()
            val owner = CleanedOwner()
            val sourceCustody = SourceBitmapCustody()
            val store = resolveActiveStore(manga, chapter, source)
            val attempt = store?.let { AttemptAuthority(it, pageKey, it.snapshot(pageKey).toPrecondition()) }
            // Correlated trace for the rolling-auto prepared
            // boundary. The coordinator installs the run through
            // TranslationTraceElement; outside that context every trace call
            // here is a fail-open no-op.
            val traceRun = TranslationTrace.currentRun()
            val nativeQueueSpan = traceRun?.beginStage(TranslationTraceStage.NATIVE_QUEUE)
            val onnxResult = try {
                withNativeLane(
                    timeoutMs = nativeTimeoutMs,
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    pageKey = pageKey,
                    onTimeout = {
                        deferredPublications.orphaned = true
                        markPageTimedOut(manga, chapter, source, pageKey, nativeTimeoutMs)
                    },
                ) {
                    // Queue behind the native lane settled: the span was begun
                    // at prepare entry, so its duration is the
                    // admission→execution wait (schedule maxQueueMs feed).
                    nativeQueueSpan?.end()
                    if (!inFlightPageKeys.add(pageKey)) {
                        logcat(LogPriority.WARN) { "TachiyomiAT prepare admission rejected: chapter=${chapter.name} pageKey=$pageKey reason=already in flight" }
                        throw NativePageAlreadyInFlightException()
                    }
                    try {
                        val generation = store?.snapshot(pageKey)?.generation
                        if (store != null && generation != null) {
                            store.withGeneration(generation) {
                                translateSinglePageOnnx(
                                    manga = manga,
                                    chapter = chapter,
                                    source = source,
                                    pageKey = pageKey,
                                    readerStreamFn = streamFn,
                                    force = force,
                                    stageListener = stageListener,
                                    deferredPublications = deferredPublications,
                                    sourceCustody = sourceCustody,
                                    attempt = attempt,
                                    owner = owner,
                                    inlineInpaint = true,
                                )
                            }
                        } else {
                            translateSinglePageOnnx(
                                manga = manga,
                                chapter = chapter,
                                source = source,
                                pageKey = pageKey,
                                readerStreamFn = streamFn,
                                force = force,
                                stageListener = stageListener,
                                deferredPublications = deferredPublications,
                                sourceCustody = sourceCustody,
                                attempt = attempt,
                                owner = owner,
                                inlineInpaint = true,
                            )
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
            } catch (t: Throwable) {
                // Exception path: best-effort inline drain of deferred storage
                // tails (the block has already failed the page), then rethrow.
                runCatching { drainDeferredPublications(deferredPublications) }
                    .onFailure { drainError ->
                        logcat(LogPriority.ERROR, drainError) {
                            "TachiyomiAT deferred storage publication failed: pageKey=$pageKey"
                        }
                    }
                throw t
            }
            // Normal path: drain OUTSIDE the permit. Fail-closed.
            try {
                drainDeferredPublications(deferredPublications)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) { "TachiyomiAT deferred storage publication failed: pageKey=$pageKey" }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            }

            // Failure before handoff: a null native result with no terminal store
            // state cannot be mistaken for a prepared page. Inspect the store to
            // distinguish a genuine terminal page (render-only resume, textless)
            // from a soft skip / decode failure.
            if (onnxResult == null) {
                return buildTerminalPreparedPage(manga, chapter, source, pageKey)
            }
            val onnxReadyResult = onnxResult

            // Durability gate: persist the cleaned image BEFORE publishing the
            // prepared reference. If publication fails the page is not prepared.
            // Track persistence on the storage lane. The span settles on every exit,
            // including a throw from persistOnnxCleanedImage.
            val persistSpan = traceRun?.beginStage(
                TranslationTraceStage.CLEANED_PERSIST,
                lane = TranslationTraceLane.STORAGE,
            )
            val published = try {
                val result = persistOnnxCleanedImage(
                    manga = manga,
                    chapter = chapter,
                    source = source,
                    pageKey = pageKey,
                    result = onnxReadyResult,
                    owner = owner,
                    attempt = attempt,
                    deferredPublications = deferredPublications,
                )
                persistSpan?.end(
                    if (result == null) {
                        TranslationTraceOutcome.FAILURE
                    } else {
                        TranslationTraceOutcome.SUCCESS
                    },
                )
                result
            } catch (t: Throwable) {
                persistSpan?.end(TranslationTraceOutcome.FAILURE, error = t)
                throw t
            }
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
                owner = owner,
            )
        } finally {
            releaseReaderPageLease(leaseStore, pageKey, PageWriteOrigin.AUTO)
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
            cleanedImageContentHash = page.cleanedImageContentHash,
            generation = snapshot.generation,
            pageVersion = snapshot.pageVersion,
            blockFingerprints = snapshot.blockFingerprints,
            isTerminal = true,
        )
    }

    /**
     * Prepared-page boundary: loads the durable cleaned image produced by
     * [prepareSinglePage], runs provider translation outside the native permit,
     * and reacquires the permit for Canvas rendering after the request. This lets
     * a caller's other native page overlap this page's remote translation.
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
     * [translateSinglePage] propagation so callers can attribute it
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
    ): TranslationCompletionOutcome? {
        if (prepared.isTerminal) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT translatePreparedPage: terminal skip pageKey=${prepared.pageKey} " +
                    "cleaned=${prepared.cleanedImageName}"
            }
            return TranslationCompletionOutcome.Completed()
        }
        val store = resolveActiveStore(manga, chapter, source) ?: return null
        // prepareSinglePage owns the auto lease only through the native
        // handoff. Re-admit the translate/render half here as AUTO work so a batch
        // cannot acquire the page in the handoff gap and then race the
        // prepared reference's writes. Denied is a stale/race "try again" for
        // the coordinator — no attach wait.
        if (
            acquireReaderPageLease(
                store = store,
                pageKey = prepared.pageKey,
                stage = PageStage.Translation,
                origin = PageWriteOrigin.AUTO,
                site = TranslationTraceSite.ROLLING_AUTO_TRANSLATION,
            ) is LeaseAcquisition.Denied
        ) {
            return null
        }
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
            val cleanedImageContentHash = if (prepared.cleanedImageName != null) {
                prepared.cleanedImageContentHash
            } else {
                pageState.cleanedImageContentHash
            }
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
            // The translate lane resolves the current store but writes page-level
            // results only; queue and chapter status remain owned by orchestration.

            val pageTranslation = pageState.toDraft()
            pageTranslation.cleanedBitmap = null
            pageTranslation.cleanedImageName = cleanedImageName
            pageTranslation.cleanedImageContentHash = cleanedImageContentHash

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
                    snapshot = eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.snapshot(),
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
            val attempt = AttemptAuthority(store, prepared.pageKey, snapshot.toPrecondition())
            val preparedOutcome = try {
                val completed = withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                    val httpOutcome = singlePageHttpRenderPhase.translateSinglePageHttpOnly(
                        manga = manga,
                        chapter = chapter,
                        source = source,
                        pageKey = prepared.pageKey,
                        ctx = ctx,
                        attempt = attempt,
                        stageListener = stageListener,
                        origin = PageWriteOrigin.AUTO,
                    )
                    if (httpOutcome !is TranslationCompletionOutcome.Completed) {
                        httpOutcome
                    } else {
                        val localOwner = CleanedOwner()
                        ctx.pageTranslation.cleanedBitmap?.let { bmp ->
                            localOwner.produce(bmp, CleanedProvenance.INPAINT)
                        }
                        try {
                            singlePageHttpRenderPhase.renderSinglePageTranslated(
                                manga = manga,
                                chapter = chapter,
                                source = source,
                                pageKey = prepared.pageKey,
                                ctx = ctx,
                                owner = localOwner,
                                attempt = attempt,
                                stageListener = stageListener,
                                origin = PageWriteOrigin.AUTO,
                            )
                        } finally {
                            localOwner.release()
                        }
                    }
                }
                if (completed == null) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT translatePreparedPage timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                            "pageKey=${prepared.pageKey} chapter=${chapter.name}"
                    }
                    attempt.markTimeout { current ->
                        val msg = PageStoreWriter.timeoutFailureMessage(nativeTimer = false)
                        current.apply {
                            if (translationStatus != StageStatus.READY && translationStatus != StageStatus.PARTIAL) {
                                translationStatus = StageStatus.FAILED
                                translationError = msg
                            }
                            if (renderStatus != StageStatus.READY) {
                                renderStatus = StageStatus.FAILED
                                renderError = msg
                            }
                            errorMessage = msg
                            retryCount += 1
                            attemptCount += 1
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                    throw java.io.IOException("translatePreparedPage timed out for ${prepared.pageKey}")
                } else {
                    completed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                throw e
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT translatePreparedPage failed: pageKey=${prepared.pageKey}"
                }
                attempt.write("translatePreparedPage failure") { current ->
                    val msg = t.message ?: "Prepared page translation failed"
                    current.apply {
                        translationStatus = StageStatus.FAILED
                        errorMessage = msg
                        updatedAt = System.currentTimeMillis()
                    }
                }
                throw t
            }
            return preparedOutcome
        } finally {
            releaseReaderPageLease(store, prepared.pageKey, PageWriteOrigin.AUTO)
        }
    }

    // BatchChapterTranslator owns chapter-wide sequencing; this class supplies
    // its shared engine, storage, and native-lane collaborators.
    private val batchChapterTranslator = BatchChapterTranslator(
        provider = provider,
        translationPreferences = translationPreferences,
        nativeLane = object : NativeLaneRunner {
            override suspend fun <T> run(
                timeoutMs: Long,
                chapterId: Long?,
                chapterName: String,
                pageKey: String,
                onTimeout: suspend () -> Unit,
                block: suspend () -> T,
            ): T? = withNativeLane(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block = block)
        },
        engineRebuildMutex = engineRebuildMutex,
        ensureEnginesBuiltFor = this::ensureEnginesBuiltFor,
        recognitionEngineFn = { recognitionEngine },
        textTranslatorFn = { textTranslator },
        computeSourceFingerprintFn = this::computeSourceFingerprint,
        batchExpectedFingerprintsFn = this::batchExpectedFingerprints,
        inpaintingModeFromPref = this::inpaintingModeFromPref,
        inpaintingStampDecision = this::inpaintingStampDecision,
        releaseBatchPageLease = this::releaseBatchPageLease,
        persistPageWithOomRecovery = this::persistPageWithOomRecovery,
        loadPersistedCleanedBitmap = this::loadPersistedCleanedBitmap,
        deleteRetiredCleanedFile = this::deleteRetiredCleanedFile,
        markPageTimedOut = this::markPageTimedOut,
        analyzePage = this::analyzePage,
        decodePageBitmapForTranslation = this::decodePageBitmapForTranslation,
        preflightInpaintGate = this::preflightInpaintGate,
        inpaintPage = this::inpaintPage,
        retryInpaintDownscaled = this::retryInpaintDownscaled,
        persistCleanedBitmap = this::persistCleanedBitmap,
        updatePageFromCurrentSnapshotFn = { store, pageKey, description, update ->
            this.updatePageFromCurrentSnapshot(store = store, pageKey = pageKey, description = description, update = update)
        },
        onBatchClosedFn = { onBatchClosed },
    )

    suspend fun translateBatch(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        store: ChapterTranslationStore,
        orderedStreams: List<Pair<String, () -> InputStream>>,
        tracker: TranslationBatchProgressTracker? = null,
        naturalPageIndexes: Map<String, Int> = emptyMap(),
    ): eu.kanade.translation.pipeline.batch.progress.ReconciliationResult? =
        batchChapterTranslator.translateBatch(manga, chapter, source, store, orderedStreams, tracker, naturalPageIndexes)

    // EngineLane serializes engine rebuilds with native admission.
    private suspend fun ensureEnginesBuiltFor(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ) {
        engines.ensureEnginesBuiltFor(fromLang, toLang)
    }

    // Keep this entry point for existing pipeline call sites while the phase
    // owner handles bitmap lifetime and native-stage execution.
    private suspend fun translateSinglePageOnnx(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        readerStreamFn: (() -> InputStream)? = null,
        force: Boolean = true,
        stageListener: TranslationStageListener? = null,
        deferredPublications: DeferredPagePublications? = null,
        sourceCustody: SourceBitmapCustody? = null,
        attempt: AttemptAuthority? = null,
        owner: CleanedOwner? = null,
        inlineInpaint: Boolean = false,
    ): OnnxPhaseResult? =
        singlePageOnnxPhase.translateSinglePageOnnx(
            manga = manga,
            chapter = chapter,
            source = source,
            pageKey = pageKey,
            readerStreamFn = readerStreamFn,
            force = force,
            stageListener = stageListener,
            deferredPublications = deferredPublications,
            sourceCustody = sourceCustody,
            attempt = attempt,
            owner = owner,
            inlineInpaint = inlineInpaint,
        )

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

    private suspend fun retryInpaintDownscaledAdmitted(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streams: List<Pair<String, () -> InputStream>>,
        decoded: DecodedPage,
        pageTranslation: PageTranslation,
        owner: CleanedOwner? = null,
        attempt: AttemptAuthority? = null,
    ): PageTranslation {
        val result = withNativeLane(
            timeoutMs = nativeTimeoutMs,
            chapterId = chapter.id,
            chapterName = chapter.name,
            pageKey = pageKey,
            priority = DevicePermitPriority.FOREGROUND,
        ) {
            val retried = singlePageOnnxPhase.retryInpaintDownscaled(manga, chapter, source, pageKey, streams, decoded, pageTranslation)
            val produced = retried.cleanedBitmap
            if (produced != null && owner != null) {
                owner.replace(produced, CleanedProvenance.RETRY)
            }
            retried
        }
        if (result == null) {
            attempt?.markTimeout { current ->
                current.apply {
                    inpaintStatus = StageStatus.FAILED
                    errorMessage = "Inpainting retry timed out"
                    updatedAt = System.currentTimeMillis()
                }
            }
            val copy = pageTranslation.detachedCopy()
            copy.cleanedBitmap = null
            copy.inpaintStatus = StageStatus.FAILED
            copy.errorMessage = "Inpainting retry timed out"
            return copy
        }
        return result
    }

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

    // MemoryGovernance owns decode budgets and recovery policy.
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

    // PageDecode owns source decoding and stable content fingerprints.
    private fun decodePageBitmapAtSize(fileName: String, sampleSize: Int, streams: List<Pair<String, () -> InputStream>>): Bitmap? =
        PageDecode.decodePageBitmapAtSize(fileName, sampleSize, streams)

    private suspend fun decodePageBitmapForTranslation(fileName: String, streamFn: () -> InputStream): DecodedPage? =
        PageDecode.decodePageBitmapForTranslation(
            context = context,
            recognitionEngine = { recognitionEngine },
            fileName = fileName,
            streamFn = streamFn,
            onSourceBounds = { width, height ->
                val sourcePixels = DeviceStageNormalization.sourcePixels(width, height)
                TranslationTrace.currentRun()?.recordSourcePixels(sourcePixels)
            },
        )

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
