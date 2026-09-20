package eu.kanade.translation.pipeline

import eu.kanade.translation.*
import eu.kanade.translation.orchestration.*
import eu.kanade.translation.storage.*

import eu.kanade.translation.orchestration.*

import eu.kanade.translation.storage.*

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.pipeline.batch.TranslationBatchProgressTracker
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.CleanedPublication
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.pipeline.DeferredPagePublications
import eu.kanade.translation.pipeline.EngineLane
import eu.kanade.translation.pipeline.batch.BatchChapterTranslator
import eu.kanade.translation.pipeline.batch.BatchResumeGate
import eu.kanade.translation.pipeline.batch.BatchResumePlanner
import eu.kanade.translation.pipeline.batch.BatchWriteGate
import eu.kanade.translation.pipeline.batch.BatchWriteIdentity
import eu.kanade.translation.pipeline.batch.HeldBitmapRegistry
import eu.kanade.translation.pipeline.batch.NativeLaneRunner
import eu.kanade.translation.pipeline.MemoryGovernance
import eu.kanade.translation.pipeline.OnnxPhaseResult
import eu.kanade.translation.pipeline.PageDecode
import eu.kanade.translation.pipeline.PageStoreWriter
import eu.kanade.translation.pipeline.SinglePageHttpRenderPhase
import eu.kanade.translation.pipeline.SinglePageOnnxPhase
import eu.kanade.translation.pipeline.copyForResume
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.scheduling.NativeRunQuarantine
import eu.kanade.translation.scheduling.PreparedPage
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationStageListener
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.scheduling.isPreparedPageTerminal
import eu.kanade.translation.scheduling.publishPreparedPageFromOcr
import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.NativeStallState
import eu.kanade.translation.translator.NativeStallWatchdog
import eu.kanade.translation.translator.retry.AiTranslationRetryPlanner
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.withProviderRequestPriority
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import kotlinx.coroutines.CancellationException
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

//  Phase 20.2: ProviderFailure.toFailureCategory moved to
// pipeline/batch/BatchWriteGate.kt (its only caller, persistAiFailure).

class TranslationPipeline(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
    /**  test seam; production defaults preserve the result timer contract. */
    private val stallThresholdMs: Long = NATIVE_STALL_THRESHOLD_MS,
    private val nativeTimeoutMs: Long = ONNX_PHASE_TIMEOUT_MS,
    /**
     *  Phase 5 (condition B test seam): the HTTP+render result timer.
     * Production defaults preserve the [SINGLE_PAGE_TIMEOUT_MS] contract; tests
     * inject a short value to exercise the typed timeout path deterministically.
     */
    internal val singlePageTimeoutMs: Long = SINGLE_PAGE_TIMEOUT_MS,
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

        /**  occupancy threshold; aligned with the native result timer. */
        const val NATIVE_STALL_THRESHOLD_MS = ONNX_PHASE_TIMEOUT_MS

        //  Phase 20.1: HELD_BITMAP_MAX_COUNT / HELD_BITMAP_BYTE_CEILING moved to
        // pipeline/batch/HeldBitmapRegistry.kt with the held-bitmap registry.

        const val UNKNOWN_SOURCE_FINGERPRINT = "source-fingerprint-unavailable"

        /**
         *  Phase 5 (condition C, spec §4.1.3): stable reason for the typed
         * non-success outcome of a page whose translation could not be saved.
         * The pure UI mapper selects the "Translation not saved — retry
         * required" copy from this exact value, so it must not drift.
         */
        const val REASON_TRANSLATION_NOT_SAVED = "Translation could not be saved; retry required"

        /**
         *  Phase 5 (condition B / -1,  §3.2): truthful name for the
         * HTTP+render result timer. Timeout copy must name the timer that
         * fired, never an unrelated duration.
         */
        const val REASON_HTTP_RENDER_TIMER_EXPIRED = "HTTP+render result timer expired; translation failed"
    }

    //  Phase 20.3: BatchResumeGate enum + resume planning moved to
    // pipeline/batch/BatchResumePlanner.kt.

    /** Native admission is owned by [nativeRunQuarantine]. */

    //  Phase 10: PermitHolder/native-permit bookkeeping moved into pipeline/EngineLane.kt.
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
    private val nativeStallWatchdog = NativeStallWatchdog(
        scope = nativeRunScope,
        thresholdMs = stallThresholdMs,
    )
    private val nativeRunQuarantine = NativeRunQuarantine(
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
     * TachiyomiAT: factory that creates a [TranslationBatchProgressTracker] for a
     * batch and registers it in the manager's tracker map so the UI can observe
     * it. Mirrors the [activeStoreResolver] pattern. Set by [TranslationManager].
     */
    @Volatile
    var batchTrackerFactory: ((chapterId: Long, store: ChapterTranslationStore, orderedPageKeys: List<String>) -> TranslationBatchProgressTracker?)? = null

    //  Phase 10: engine cache + native lane moved to pipeline/EngineLane.kt
    // (defensive init semantics preserved: EngineLane's init builds the engines
    // defensively at construction). Same-signature stubs keep call sites.
    //  Phase 4 ( §1.2): the drain grace is the SHORT engine grace (its
    // expiry is recovered by the epoch retry — see EngineLane.ENGINE_DRAIN_GRACE_MS)
    // and the drain runs on nativeRunScope so the stop path (main thread) never
    // blocks on it.
    internal val engines = EngineLane(
        context = context,
        translationPreferences = translationPreferences,
        nativeRunQuarantine = nativeRunQuarantine,
        inFlightPageKeys = inFlightPageKeys,
        onPageStuck = { onPageStuck },
        drainGraceMs = EngineLane.ENGINE_DRAIN_GRACE_MS,
        drainScope = nativeRunScope,
    )

    /**
     * S3 (Milestone M2): Engine warm-up.
     */
    suspend fun warmUp() {
        engines.warmUp()
    }

    private suspend fun <T> withNativeLane(
        timeoutMs: Long,
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        block: suspend () -> T,
    ): T? = engines.withNativeLane(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block)

    //  Phase 10: engine-cache fields live in [engines]; the staying paths
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

    //  Phase 6: stream peek + canonical FAILED placeholder + store-patch/failure-write
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
        timeoutMs: Long = ONNX_PHASE_TIMEOUT_MS,
        nativeTimer: Boolean = true,
    ) {
        //  Phase 5  the store placeholder names the timer that actually
        // fired — native (`withNativeLane`) vs HTTP+render (`withTimeoutOrNull`).
        pageStoreWriter.markPageTimedOut(manga, chapter, source, pageKey, timeoutMs, nativeTimer)
    }

    //  Phase 6: PageSnapshot.toPrecondition moved to pipeline/PageStoreWriter.kt
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
            leaseStore?.let { acquireReaderPageLease(it, chapter, pageKey, origin) }
        }
        if (acquisition is LeaseAcquisition.Denied) {
            return SinglePageOutcome.Rejected(acquisition.owner, acquisition.reason)
        }
        return withProviderRequestPriority(AdmissionPriority.INTERACTIVE) {
            runGrantedSinglePageBoundary(
                leaseStore = leaseStore,
                manga = manga,
                chapter = chapter,
                source = source,
                pageKey = pageKey,
                streamFn = null,
                force = force,
                stageListener = stageListener,
                origin = origin,
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
     *   the whole boundary runs under an origin-typed page lease
     * ([PageWriteOrigin.MANUAL] for reader taps, [PageWriteOrigin.AUTO] for the
     * legacy auto window's resume-render path). Session admission prevents
     * reader and batch sessions from entering concurrently; a low-level lease
     * denial is returned as a typed rejection. MANUAL still evicts an
     * in-flight AUTO lease (fenced fail-closed for the evicted holder).
     */
    private suspend fun runSinglePageBoundary(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
    ): SinglePageOutcome {
        val leaseStore = resolveActiveStore(manga, chapter, source)
        val acquisition = leaseStore?.let { acquireReaderPageLease(it, chapter, pageKey, origin) }
        if (acquisition is LeaseAcquisition.Denied) {
            return SinglePageOutcome.Rejected(acquisition.owner, acquisition.reason)
        }
        return runGrantedSinglePageBoundary(
            leaseStore = leaseStore,
            manga = manga,
            chapter = chapter,
            source = source,
            pageKey = pageKey,
            streamFn = streamFn,
            force = force,
            stageListener = stageListener,
            origin = origin,
        )
    }

    /**
     * The owned-work half of [runSinglePageBoundary]: the caller holds the page
     * lease under [origin] for the whole body and releases it in `finally`.
     */
    private suspend fun runGrantedSinglePageBoundary(
        leaseStore: ChapterTranslationStore?,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin,
    ): SinglePageOutcome {
        // Typed HTTP+render phase completion and its timeout/terminality
        // resolution; declared at boundary scope so the post-finally mapping
        // can see them while the `finally` still owns the lease release.
        var httpOutcome: ChunkCompletionOutcome? = null
        //  Phase 5 (condition B): set when the HTTP+render timer fired
        // while the phase had ALREADY committed a durable terminal result —
        // store truth outranks the timer there, and no timeout placeholder
        // may overwrite terminal truth.
        var httpTimeoutLandedDurableResult = false
        //  Phase 3 (plan §4.4 Manual): boundary-level trace wiring. The
        // run arrives via the TranslationTraceElement installed by the caller
        // (scheduler launch / rolling-coordinator wrap); outside a trace every
        // helper is a NO_OP. Emission is non-suspending and fail-open.
        val traceRun = TranslationTrace.currentRun()
        val traceSchedule = traceRun?.schedule
        val nativeQueueSpan = traceRun?.beginStage(TranslationTraceStage.NATIVE_QUEUE)
        val nativeLaneToken = traceSchedule?.enterLane(TranslationTraceLane.NATIVE)
        try {
            //   a same-page request whose predecessor still owns the
            // stove (e.g. a timed-out-but-parked native call) is rejected
            // BEFORE queueing behind the quarantine. Membership here is the
            // honest occupancy truth: the entry is cleared only when the
            // residual invocation really exits, because the remover runs
            // inside the parked block. The in-block add-check below remains
            // the concurrent-race backstop.
            if (inFlightPageKeys.contains(pageKey)) {
                logcat(LogPriority.DEBUG) {
                    "TachiyomiAT native admission rejected: chapter=${chapter.name} " +
                        "pageKey=$pageKey reason=already in flight (residual)"
                }
                nativeLaneToken?.close()
                return SinglePageOutcome.Rejected(null, "page already translating")
            }
            //   (phase4-design §4.4): storage-tail deferral holder. The
            // ONNX phase enqueues its resume-path cleaned-image persistence,
            // render tails, and the store flush here instead of running them
            // under the native permit; this boundary drains the queue AFTER
            // withNativeLane returns, so the permit is released before storage
            // publication and the next page's native admission is never blocked
            // behind this page's disk commit. On native timeout the boundary
            // orphans the queue FIRST: any residual block invocation runs its
            // tails inline (quarantine exit is awaited before withNativeLane
            // returns), so publication is never silently dropped.
            val deferredPublications = DeferredPagePublications()
            val onnxResult = try {
                try {
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
                        // Queue behind the native lane settled: the span was
                        // begun at boundary entry, so its duration is the
                        // admission→execution wait (schedule maxQueueMs feed).
                        nativeQueueSpan?.end()
                        if (!inFlightPageKeys.add(pageKey)) {
                            logcat(LogPriority.WARN) { "TachiyomiAT native admission rejected: chapter=${chapter.name} pageKey=$pageKey reason=already in flight" }
                            throw NativePageAlreadyInFlightException()
                        }
                        try {
                            val store = resolveActiveStore(manga, chapter, source)
                            val generation = store?.snapshot(pageKey)?.generation
                            if (store != null && generation != null) {
                                store.withGeneration(generation) {
                                    translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener, deferredPublications)
                                }
                            } else {
                                translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener, deferredPublications)
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException || t is NativePageAlreadyInFlightException) throw t
                            logcat(LogPriority.ERROR, t) { "TachiyomiAT ONNX phase failed: pageKey=$pageKey" }
                            markPageFailed(manga, chapter, source, pageKey, t)
                            throw t
                        } finally {
                            inFlightPageKeys.remove(pageKey)
                        }
                    }.also { nativeLaneToken?.close() }
                } catch (_: NativePageAlreadyInFlightException) {
                    nativeLaneToken?.close()
                    return SinglePageOutcome.Rejected(null, "page already translating")
                }
            } catch (t: Throwable) {
                nativeLaneToken?.close()
                // Exception path: the boundary does not continue, so run any
                // deferred storage tails inline (best effort — the block has
                // already failed the page) and rethrow the original failure.
                runCatching { deferredPublications.drainAll() }
                    .onFailure { drainError ->
                        logcat(LogPriority.ERROR, drainError) {
                            "TachiyomiAT deferred storage publication failed: pageKey=$pageKey"
                        }
                    }
                throw t
            }
            // Normal path: drain OUTSIDE the permit. Fail-closed: a deferred
            // publication that throws fails the page — it must never be
            // silently dropped.
            try {
                deferredPublications.drainAll()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) { "TachiyomiAT deferred storage publication failed: pageKey=$pageKey" }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            }
            //  Phase 5 (condition A, phase4 review §5 Deviation #7): the
            // resume paths of [translateSinglePageOnnx] return null on SUCCESS
            // (render-only resume, inpaint+render resume, resume-skip). 's
            // honest-timeout flip mapped that null to Failed — a NEW
            // wrong-outcome case introduced by the flip, not a pre-existing
            // one. The deferred storage tails were drained above, so durable
            // terminality is the arbiter (the same store-inspection discipline
            // [buildTerminalPreparedPage] uses for the AUTO boundary): a
            // terminal durable page here IS the successful resume.
            if (onnxResult == null) {
                val store = resolveActiveStore(manga, chapter, source)
                val page = store?.state?.value?.get(pageKey)
                if (page != null && isPreparedPageTerminal(page)) {
                    return SinglePageOutcome.Completed
                }
                return SinglePageOutcome.Failed(pageKey, "native phase timed out")
            }

            //  Phase 3: cleaned-image persistence boundary (storage lane).
            //  Phase 4 (Phase 3 review F2): the span settles on EVERY exit,
            // including a throw from persistOnnxCleanedImage (cancellation or a
            // store exception on paths that historically propagate) — no
            // stage_start is left dangling.
            val persistSpan = traceRun?.beginStage(
                TranslationTraceStage.CLEANED_PERSIST,
                lane = TranslationTraceLane.STORAGE,
            )
            val publishedResult = try {
                val result = persistOnnxCleanedImage(manga, chapter, source, pageKey, onnxResult)
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
            if (publishedResult == null) {
                return SinglePageOutcome.Failed(pageKey, "native cleaned publication failed")
            }

            //  Phase 3 ( §2.2a): capture the phase's typed completion so a
            // governor deferral surfaces as a typed pause instead of a silent
            // Completed.
            val providerLaneToken = traceSchedule?.enterLane(TranslationTraceLane.PROVIDER)
            try {
                httpOutcome = withTimeoutOrNull(singlePageTimeoutMs) {
                    translateSinglePageHttpRender(manga, chapter, source, pageKey, publishedResult, stageListener, origin)
                } ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT HTTP+render phase timed out after ${singlePageTimeoutMs}ms: " +
                            "pageKey=$pageKey chapter=${chapter.name}"
                    }
                    val timeoutStore = resolveActiveStore(manga, chapter, source)
                    val timeoutPage = timeoutStore?.state?.value?.get(pageKey)
                    if (timeoutPage != null && isPreparedPageTerminal(timeoutPage)) {
                        httpTimeoutLandedDurableResult = true
                    } else {
                        markPageTimedOut(manga, chapter, source, pageKey, singlePageTimeoutMs, nativeTimer = false)
                    }
                    null
                }
            } catch (t: Throwable) {
                providerLaneToken?.close()
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT HTTP+render phase failed: pageKey=$pageKey"
                }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            }
            providerLaneToken?.close()
        } finally {
            releaseReaderPageLease(leaseStore, pageKey, origin)
        }
        //  Phase 5 (conditions B + C, spec §4.1): exhaustive typed-value
        // mapping. Only a genuinely completed durable commit may type
        // Completed; a timeout, a guarded-commit rejection, or a typed value
        // failure is a visible non-success — never a fall-through Completed.
        return when (val outcome = httpOutcome) {
            null ->
                if (httpTimeoutLandedDurableResult) {
                    SinglePageOutcome.Completed
                } else {
                    SinglePageOutcome.Failed(pageKey, REASON_HTTP_RENDER_TIMER_EXPIRED)
                }
            is ChunkCompletionOutcome.Paused -> SinglePageOutcome.Paused(outcome.nextEligibleRetryAtEpochMs)
            is ChunkCompletionOutcome.PersistenceRejected ->
                SinglePageOutcome.Rejected(null, REASON_TRANSLATION_NOT_SAVED)
            is ChunkCompletionOutcome.Failed -> SinglePageOutcome.Failed(pageKey, outcome.reason)
            is ChunkCompletionOutcome.Unexpected -> SinglePageOutcome.Failed(pageKey, outcome.reason)
            is ChunkCompletionOutcome.Completed -> SinglePageOutcome.Completed
        }
    }

    /**
     * Phase 3 lease admission for reader-originated single-page work. Session
     * admission prevents a BATCH-owned page from being reached by a reader;
     * any low-level denial is returned as a typed rejection. A MANUAL request
     * still evicts an in-flight AUTO lease under reader-session policy.
     */
    private suspend fun acquireReaderPageLease(
        store: ChapterTranslationStore,
        chapter: Chapter,
        pageKey: String,
        origin: PageWriteOrigin,
    ): LeaseAcquisition =
        store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, origin)

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

    //  Phase 7: cleaned-image publication bodies moved to
    // translation/pipeline/CleanedPublication.kt (wraps CleanedImagePublisher;
    // currentInpaintingMode injected as a getter). Same-signature stubs keep call sites.
    private val cleanedPublication = CleanedPublication(
        provider = provider,
        streamRegistry = streamRegistry,
        currentInpaintingMode = { currentInpaintingMode },
    )
    //  Phase 12: single-page HTTP+render phase moved to
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
    //  Phase 14: permit-held ONNX phase + resume/native-stage helpers moved
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
        //   only the rolling auto coordinator calls the prepared
        // boundary — its leases are AUTO (never preemptive; MANUAL evicts it).
        // Denied is a "try again" for the coordinator (§1.3): no attach wait.
        if (leaseStore != null &&
            acquireReaderPageLease(leaseStore, chapter, pageKey, PageWriteOrigin.AUTO) is LeaseAcquisition.Denied
        ) {
            return null
        }
        try {
            //   (phase4-design §4.4): same storage-tail deferral as
            // [runGrantedSinglePageBoundary] — the permit is released before
            // the resume paths' storage publication runs; orphaned tails run
            // inline; the normal-path drain is fail-closed.
            val deferredPublications = DeferredPagePublications()
            //  Phase 3: correlated trace for the rolling-Auto prepared
            // boundary. The coordinator installs the run through
            // TranslationTraceElement; outside that context every trace call
            // here is a fail-open no-op.
            val traceRun = TranslationTrace.currentRun()
            val nativeQueueSpan = traceRun?.beginStage(TranslationTraceStage.NATIVE_QUEUE)
            val nativeLaneToken = traceRun?.schedule?.enterLane(TranslationTraceLane.NATIVE)
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
                        val store = resolveActiveStore(manga, chapter, source)
                        val generation = store?.snapshot(pageKey)?.generation
                        if (store != null && generation != null) {
                            store.withGeneration(generation) {
                                translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener, deferredPublications)
                            }
                        } else {
                            translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener, deferredPublications)
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        logcat(LogPriority.ERROR, t) { "TachiyomiAT prepare ONNX phase failed: pageKey=$pageKey" }
                        markPageFailed(manga, chapter, source, pageKey, t)
                        throw t
                    } finally {
                        inFlightPageKeys.remove(pageKey)
                    }
                }.also { nativeLaneToken?.close() }
            } catch (t: Throwable) {
                nativeLaneToken?.close()
                // Exception path: best-effort inline drain of deferred storage
                // tails (the block has already failed the page), then rethrow.
                runCatching { deferredPublications.drainAll() }
                    .onFailure { drainError ->
                        logcat(LogPriority.ERROR, drainError) {
                            "TachiyomiAT deferred storage publication failed: pageKey=$pageKey"
                        }
                    }
                throw t
            }
            // Normal path: drain OUTSIDE the permit. Fail-closed.
            try {
                deferredPublications.drainAll()
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

            // Durability gate: persist the cleaned image BEFORE publishing the
            // prepared reference. If publication fails the page is not prepared.
            //  Phase 3: persistence boundary stage (storage lane).
            //  Phase 4 (Phase 3 review F2): the span settles on EVERY exit,
            // including a throw from persistOnnxCleanedImage.
            val persistSpan = traceRun?.beginStage(
                TranslationTraceStage.CLEANED_PERSIST,
                lane = TranslationTraceLane.STORAGE,
            )
            val published = try {
                val result = persistOnnxCleanedImage(manga, chapter, source, pageKey, onnxResult)
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
        // prepareSinglePage owns the auto lease only through the native
        // handoff. Re-admit the translate/render half here (AUTO per   —
        // only the rolling auto coordinator calls this boundary) so a batch
        // cannot acquire the page in the handoff gap and then race the
        // prepared reference's writes. Denied is a stale/race "try again" for
        // the coordinator — no attach wait.
        if (acquireReaderPageLease(store, chapter, prepared.pageKey, PageWriteOrigin.AUTO) is LeaseAcquisition.Denied) {
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
            //  Phase 3: the provider lane is active for the whole HTTP
            // translate + render section of the prepared path so the schedule
            // accumulator measures it overlapping the next page's native prep.
            val providerLaneToken =
                TranslationTrace.currentRun()?.schedule?.enterLane(TranslationTraceLane.PROVIDER)
            val preparedOutcome = try {
                val completed = withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                    translateSinglePageHttpRender(manga, chapter, source, prepared.pageKey, ctx, stageListener, PageWriteOrigin.AUTO)
                }
                if (completed == null) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT translatePreparedPage timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                            "pageKey=${prepared.pageKey} chapter=${chapter.name}"
                    }
                    // The prepared path runs the HTTP+render phase, so its
                    // timeout placeholder names THAT timer.
                    markPageTimedOut(manga, chapter, source, prepared.pageKey, nativeTimer = false)
                    throw java.io.IOException("translatePreparedPage timed out for ${prepared.pageKey}")
                } else {
                    completed
                }
            } catch (e: CancellationException) {
                providerLaneToken?.close()
                throw e
            } catch (e: java.io.IOException) {
                providerLaneToken?.close()
                // A genuine failure (unreadable cleaned image, timeout) — propagate
                // so the caller attributes it as a failure, not a race loss.
                throw e
            } catch (t: Throwable) {
                providerLaneToken?.close()
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT translatePreparedPage failed: pageKey=${prepared.pageKey}"
                }
                markPageFailed(manga, chapter, source, prepared.pageKey, t)
                throw t
            }
            providerLaneToken?.close()
            return preparedOutcome
        } finally {
            releaseReaderPageLease(store, prepared.pageKey, PageWriteOrigin.AUTO)
        }
    }

    //  Phase 20.6: the staged batch translation (translateBatch shell +
    // persistUnexpectedBatchStageFailure) moved verbatim to
    // translation/pipeline/batch/BatchChapterTranslator.kt. The thin delegator
    // keeps the ChapterTranslator call site; engine/native collaborators are
    // wired here.
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
            ): T? = withNativeLane(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block)
        },
        engineRebuildMutex = engineRebuildMutex,
        ensureEnginesBuiltFor = this::ensureEnginesBuiltFor,
        recognitionEngineFn = { recognitionEngine },
        textTranslatorFn = { textTranslator },
        computeSourceFingerprintFn = this::computeSourceFingerprint,
        batchExpectedFingerprintsFn = this::batchExpectedFingerprints,
        inpaintingModeFromPref = this::inpaintingModeFromPref,
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
    ): eu.kanade.translation.pipeline.batch.ReconciliationResult? =
        batchChapterTranslator.translateBatch(manga, chapter, source, store, orderedStreams, tracker, naturalPageIndexes)

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


    //  Phase 20.2: BatchWriteIdentity moved to pipeline/batch/BatchWriteGate.kt
    // (internal top-level, same module reachability).

    //  Phase 10: rebuild gate body moved to pipeline/EngineLane.kt.
    private suspend fun ensureEnginesBuiltFor(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ) {
        engines.ensureEnginesBuiltFor(fromLang, toLang)
    }

    //  Phase 14: permit-held ONNX phase body moved to
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
        deferredPublications: DeferredPagePublications? = null,
    ): OnnxPhaseResult? =
        singlePageOnnxPhase.translateSinglePageOnnx(
            manga,
            chapter,
            source,
            pageKey,
            readerStreamFn,
            force,
            stageListener,
            deferredPublications,
        )

    //  Phase 12: single-page HTTP+render phase body moved to
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
        origin: PageWriteOrigin = PageWriteOrigin.MANUAL,
    ): ChunkCompletionOutcome =
        singlePageHttpRenderPhase.translateSinglePageHttpRender(manga, chapter, source, pageKey, ctx, stageListener, origin)

    //  Phase 7: PageTranslation.copyForResume moved to pipeline/CleanedPublication.kt
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

    //  Phase 1: memory governance bodies moved to translation/pipeline/MemoryGovernance.kt.
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

    //  Phase 1: decode/fingerprint bodies moved to translation/pipeline/PageDecode.kt.
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
