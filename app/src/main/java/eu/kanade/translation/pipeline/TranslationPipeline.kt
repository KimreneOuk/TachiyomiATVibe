package eu.kanade.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.DeviceStageNormalization
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.inpainting.InpaintStampDecision
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.NativeStallState
import eu.kanade.translation.engines.translator.NativeStallWatchdog
import eu.kanade.translation.engines.translator.withProviderRequestPriority
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.LeaseAcquisition
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.persistence.mergeOcrFields
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
import eu.kanade.translation.pipeline.adaptive.DevicePriorityHandle
import eu.kanade.translation.pipeline.batch.BatchChapterTranslator
import eu.kanade.translation.pipeline.batch.NativeLaneRunner
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.execution.NativeRunQuarantine
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.pipeline.execution.isPreparedPageTerminal
import eu.kanade.translation.pipeline.memory.MemoryGovernance
import eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.toPrecondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

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
         * Max wall-clock a single page may hold the sole native lane, bounding a
         * hung/stalled call without starving other pages; generous but finite.
         */
        const val SINGLE_PAGE_TIMEOUT_MS = 120_000L

        /**
         * Local retry cap when a translation returns PARTIAL: each retry re-requests
         * only untranslated blocks, mirroring the batch adaptive retry. Does NOT bump
         * persistent retryCount (PARTIAL must not burn the permanent budget).
         */
        const val SINGLE_PAGE_PARTIAL_MAX_RETRIES = 2

        /**
         * Tighter timeout for the permit-held ONNX phase so the permit releases
         * promptly and the next prefetch page's ONNX work overlaps this page's
         * network call; HTTP/render has its own timeouts.
         */
        const val ONNX_PHASE_TIMEOUT_MS = 90_000L

        /**  occupancy threshold; aligned with the native result timer. */
        const val NATIVE_STALL_THRESHOLD_MS = ONNX_PHASE_TIMEOUT_MS

        const val UNKNOWN_SOURCE_FINGERPRINT = "source-fingerprint-unavailable"

        /** Truthful timer name: timeout copy must name the timer that fired. */
        const val REASON_HTTP_RENDER_TIMER_EXPIRED = "HTTP+render result timer expired; translation failed"
    }

    /** Native admission is owned by [nativeRunQuarantine]. */

    /** Current native lane owner, used by progress reporting. */
    internal fun permitHolderPageKeySnapshot(): String? = engines.permitHolderPageKeySnapshot()

    private val engineRebuildMutex = testConstruction?.engineRebuildMutex ?: kotlinx.coroutines.sync.Mutex()

    /**
     * pageKeys mid-flight in [translateSinglePage]; guards a page queuing behind
     * itself (auto re-enqueue on scroll, double-tap). Thread-safe: native quarantine
     * and [closeEngines] touch it outside the native lane.
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
     * Notified by [withNativeLane] when a page overruns its deadline, after real
     * native exit. Wired by [TranslationManager] to evict the dead job from
     * `activePageJobs`, else the `existing.isActive` dedup silently drops retries.
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
        handle: DevicePriorityHandle? = null,
        block: suspend () -> T,
    ): T? {
        // Live reader priority: the caller's context handle wins over the
        // static priority (prefetch stays BACKGROUND despite MANUAL lease
        // origin; promotion of the same waiting entry lands in place).
        val effectiveHandle = handle ?: currentCoroutineContext()[DevicePriorityHandle]
        val scheduleTrace = TranslationTrace.currentRun()?.schedule
        val permit = devicePagePermitGate.acquire(priority, effectiveHandle) ?: run {
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

    private suspend fun markAttemptFailed(attempt: AttemptAuthority?, error: Throwable) {
        attempt?.write("single-page failure") { current ->
            // Preserve cleaned data without refreshing its fence.
            if (current.cleanedImageName == null) {
                val message = when (error) {
                    is OutOfMemoryError -> "Out of memory: ${error.message ?: "low memory"}"
                    else -> error.message ?: error.javaClass.simpleName
                }
                current.apply {
                    ocrStatus = StageStatus.FAILED
                    inpaintStatus = StageStatus.FAILED
                    translationStatus = StageStatus.PENDING
                    renderStatus = StageStatus.PENDING
                    ocrError = message
                    inpaintError = message
                    translationError = message
                    retryCount += 1
                    attemptCount += 1
                    updatedAt = System.currentTimeMillis()
                }
            } else {
                current
            }
        }
    }

    /**
     * Translates one page: ONNX (under the permit) does decode/recognize/persist;
     * provider translation runs outside the permit, rendering reacquires it — the
     * next prefetch page's ONNX overlaps this page's network call. Bypasses the batch
     * pass by design; its no-inpaint-before-OCR invariant applies to batch only.
     */
    override suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin,
        readerStreamFn: (() -> InputStream)?,
        readerPriority: (() -> AdmissionPriority)?,
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
        val acquisition = withProviderRequestPriority(readerPriority?.invoke() ?: AdmissionPriority.INTERACTIVE) {
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
            return withProviderRequestPriority(readerPriority?.invoke() ?: AdmissionPriority.INTERACTIVE) {
                runGrantedSinglePageBoundary(
                    leaseStore = leaseStore,
                    manga = manga,
                    chapter = chapter,
                    source = source,
                    pageKey = pageKey,
                    force = force,
                    stageListener = stageListener,
                    origin = origin,
                    readerStreamFn = readerStreamFn,
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
        readerStreamFn: (() -> InputStream)?,
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
                            attempt.markTimeout { current ->
                                current.apply {
                                    if (!isPreparedPageTerminal(this)) {
                                        ocrStatus = StageStatus.FAILED
                                        // Reader projection reads this while native OCR remains quarantined.
                                        translationError = PageStoreWriter.timeoutFailureMessage(nativeTimer = true)
                                        errorMessage = PageStoreWriter.timeoutFailureMessage(nativeTimer = true)
                                        updatedAt = System.currentTimeMillis()
                                    }
                                }
                            }
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
                                    readerStreamFn = readerStreamFn,
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
                            markAttemptFailed(attempt, t)
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
                markAttemptFailed(attempt, t)
                throw t
            }

            if (onnxResult == null) {
                val page = store.state.value[pageKey]
                // A resume null is a SUCCESS shape when the drained tail committed
                // the native stages (render READY); only a non-terminal page is a
                // genuine native timeout.
                val resumeRenderCommitted = page != null &&
                    page.ocrStatus == StageStatus.READY &&
                    page.renderStatus == StageStatus.READY &&
                    page.inpaintStatus != StageStatus.FAILED &&
                    (page.translationStatus == StageStatus.READY || page.translationStatus == StageStatus.PARTIAL)
                if (page != null && (isPreparedPageTerminal(page) || resumeRenderCommitted)) {
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

            val ocrPublication = attempt.write("single-page OCR result") { current ->
                mergeOcrFields(current, onnxResult.pageTranslation)
            }
            if (ocrPublication is ChapterTranslationStore.PatchResult.Rejected) {
                return SinglePageOutcome.Rejected(null, PageRejectionKind.PERSISTENCE_FAILED, ocrPublication.reason)
            }

            if (onnxResult.pageTranslation.ocrStatus == StageStatus.FAILED) {
                return SinglePageOutcome.Failed(
                    pageKey,
                    onnxResult.pageTranslation.errorMessage ?: "OCR failed",
                )
            }
            if (onnxResult.pageTranslation.blocks.isEmpty() &&
                onnxResult.pageTranslation.inpaintMaskBoxes.isEmpty()
            ) {
                return SinglePageOutcome.Completed
            }

            // Structured fork/join
            val nativeDraft = onnxResult.pageTranslation.detachedCopy()
            val httpDraft = onnxResult.pageTranslation.detachedCopy()
            val httpCtx = onnxResult.copy(pageTranslation = httpDraft)

            val completionOutcome: TranslationCompletionOutcome = try {
                withTimeout(singlePageTimeoutMs) {
                    val nativeBranch = async {
                        runNativeInpaintBranch(
                            traceRun = traceRun,
                            manga = manga,
                            chapter = chapter,
                            source = source,
                            pageKey = pageKey,
                            store = store,
                            nativeDraft = nativeDraft,
                            sourceCustody = sourceCustody,
                            owner = owner,
                            attempt = attempt,
                            deferredPublications = deferredPublications,
                            stageListener = stageListener,
                        )
                    }

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
                        return@withTimeout TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = httpError)
                    }

                    val nativeResult = nativeBranch.await()

                    resolveNativeCompletion(
                        nativeResult = nativeResult,
                        httpOutcome = httpOutcome,
                        nativeDraft = nativeDraft,
                        manga = manga,
                        chapter = chapter,
                        source = source,
                        pageKey = pageKey,
                        httpCtx = httpCtx,
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
                    TranslationCompletionOutcome.Completed()
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
                    TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = REASON_HTTP_RENDER_TIMER_EXPIRED)
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
        } catch (e: CancellationException) {
            if (attempt.callerJob?.isCancelled == true || !coroutineContext.isActive) {
                deferredPublications.orphaned = true
                try {
                    attempt.markCancelled()
                } catch (markerError: Throwable) {
                    logcat(LogPriority.ERROR, markerError) { "Attempt cancellation marker failed: pageKey=$pageKey" }
                }
            }
            throw e
        } finally {
            releaseSinglePageResources(manga, chapter, source, pageKey, deferredPublications, sourceCustody, owner)
        }
    }

    private suspend fun releaseSinglePageResources(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        deferredPublications: DeferredPagePublications,
        sourceCustody: SourceBitmapCustody,
        owner: CleanedOwner,
    ) {
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

    private sealed interface InpaintLaneResult {
        object Produced : InpaintLaneResult
        object SoftNull : InpaintLaneResult
    }

    private sealed interface NativeBranchOutcome {
        object Success : NativeBranchOutcome
        object SoftNull : NativeBranchOutcome
        object TimedOut : NativeBranchOutcome
        data class Failed(val throwable: Throwable) : NativeBranchOutcome
    }

    /** Segment-2 child of the shared single-page T3: native inpaint lane + enrollment. */
    private suspend fun runNativeInpaintBranch(
        traceRun: TranslationRunTrace?,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
        nativeDraft: PageTranslation,
        sourceCustody: SourceBitmapCustody,
        owner: CleanedOwner,
        attempt: AttemptAuthority,
        deferredPublications: DeferredPagePublications,
        stageListener: TranslationStageListener?,
    ): NativeBranchOutcome {
        try {
            val laneResult: InpaintLaneResult? = withNativeLane(
                timeoutMs = nativeTimeoutMs,
                chapterId = chapter.id,
                chapterName = chapter.name,
                pageKey = pageKey,
                onTimeout = {
                    deferredPublications.orphaned = true
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT Segment 2 native inpaint timed out for $pageKey"
                    }
                },
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
                    stageListener?.onStageEntered(pageKey, TranslationStageEvent.CLEANING)
                    val cleaned = try {
                        recognitionEngine.inpaint(s, nativeDraft)
                    } finally {
                        inpaintSpan?.end()
                    }
                    if (cleaned != null) {
                        owner.produce(cleaned, CleanedProvenance.INPAINT)
                        InpaintLaneResult.Produced
                    } else {
                        InpaintLaneResult.SoftNull
                    }
                } finally {
                    if (!owner.owns(s)) {
                        try {
                            s.recycle()
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) {
                                "TachiyomiAT segment 2 source bitmap recycle failed: pageKey=$pageKey"
                            }
                        }
                    }
                }
            }
            if (laneResult != null && owner.isAvailable) {
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
            return when (laneResult) {
                is InpaintLaneResult.Produced -> NativeBranchOutcome.Success
                is InpaintLaneResult.SoftNull -> NativeBranchOutcome.SoftNull
                null -> NativeBranchOutcome.TimedOut
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logcat(LogPriority.ERROR, t) { "TachiyomiAT native inpaint branch failed: pageKey=$pageKey" }
            return NativeBranchOutcome.Failed(t)
        }
    }

    /** Post-join native-outcome resolution: terminal markers, failure writes, render. */
    private suspend fun resolveNativeCompletion(
        nativeResult: NativeBranchOutcome,
        httpOutcome: TranslationCompletionOutcome,
        nativeDraft: PageTranslation,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        httpCtx: OnnxPhaseResult,
        owner: CleanedOwner,
        attempt: AttemptAuthority,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin,
    ): TranslationCompletionOutcome {
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
            return TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = "native phase timed out")
        }

        if (nativeResult is NativeBranchOutcome.SoftNull && nativeDraft.blocks.isEmpty()) {
            val message = "Masked textless inpainting produced no cleaned image"
            attempt.write("masked textless inpaint failure") { current ->
                current.apply {
                    inpaintStatus = StageStatus.FAILED
                    inpaintError = message
                    errorMessage = message
                    updatedAt = System.currentTimeMillis()
                }
            }
            return TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = message)
        }

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
            return TranslationCompletionOutcome.Failed(anchorPageKey = pageKey, reason = nativeError)
        }

        if (httpOutcome !is TranslationCompletionOutcome.Completed) {
            return httpOutcome
        }

        return singlePageHttpRenderPhase.renderSinglePageTranslated(
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
        owner: CleanedOwner? = null,
        attempt: AttemptAuthority? = null,
        deferredPublications: DeferredPagePublications? = null,
    ): OnnxPhaseResult? = cleanedPublication.persistOnnxCleanedImage(
        manga,
        chapter,
        source,
        pageKey,
        result,
        owner,
        attempt,
        deferredPublications,
    )

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
        val timeoutMessage = "Inpainting retry timed out"
        val result = withNativeLane(
            timeoutMs = nativeTimeoutMs,
            chapterId = chapter.id,
            chapterName = chapter.name,
            pageKey = pageKey,
            onTimeout = {
                val marker = attempt?.markTimeout { current ->
                    current.apply {
                        inpaintStatus = StageStatus.FAILED
                        errorMessage = timeoutMessage
                        updatedAt = System.currentTimeMillis()
                    }
                }
                if (marker is ChapterTranslationStore.PatchResult.Rejected) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT retry timeout marker rejected: pageKey=$pageKey reason=${marker.reason}"
                    }
                }
            },
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
            val copy = pageTranslation.detachedCopy()
            copy.cleanedBitmap = null
            copy.inpaintStatus = StageStatus.FAILED
            copy.errorMessage = timeoutMessage
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
