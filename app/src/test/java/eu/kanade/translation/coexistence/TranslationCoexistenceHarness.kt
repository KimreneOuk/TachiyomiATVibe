@file:Suppress("ClassName")

package eu.kanade.translation.coexistence

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.InMemorySharedPreferences
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.artifact.ArtifactSeed
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.loadArtifact
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.OcrStagePatch
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.StagePatchResult
import eu.kanade.translation.persistence.chapter.ocrBlockFingerprints
import eu.kanade.translation.persistence.queue.TranslationQueueStore
import eu.kanade.translation.pipeline.CleanedPublication
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.pipeline.EngineLane
import eu.kanade.translation.pipeline.OnnxPhaseResult
import eu.kanade.translation.pipeline.PageDecode
import eu.kanade.translation.pipeline.PageStoreWriter
import eu.kanade.translation.pipeline.SinglePageHttpRenderPhase
import eu.kanade.translation.pipeline.SinglePageOnnxPhase
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.batch.BatchChapterTranslator
import eu.kanade.translation.pipeline.batch.NativeLaneRunner
import eu.kanade.translation.pipeline.batch.progress.ReconciliationResult
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.pipeline.execution.NativeRunQuarantine
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.pipeline.memory.MemoryGovernance
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import eu.kanade.translation.util.ShortHash
import eu.kanade.translation.util.getChapterPages
import eu.kanade.translation.workflow.ChapterTranslator
import eu.kanade.translation.workflow.ReaderSessionIntent
import eu.kanade.translation.workflow.SessionAdmission
import eu.kanade.translation.workflow.TranslationManager
import eu.kanade.translation.workflow.TranslationSessionCoordinator
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic coexistence harness.
 *
 * Builds the REAL production graph — TranslationManager → TranslationScheduler
 * → TranslationPipeline (real EngineLane, real SinglePageOnnx/HttpRender phases,
 * real BatchChapterTranslator → BatchLaneWorkers → SequentialBatchCoordinator →
 * BatchRenderJoin) → ChapterTranslationStore (memory-only) → ChapterTranslator —
 * using the repository's JVM-only test construction factories and reflection
 * field injection for the graph-specific collaborators.
 * Fakes exist only at sanctioned engine and I/O boundaries.
 *
 * Every reflection-injected field name is listed in ONE place per target class
 * inside [create]; a production rename fails loudly with NoSuchFieldException.
 *
 * Determinism: no sleeps, no polling — CompletableDeferred gates
 * and `StateFlow.first {}` only; all production components run on their real
 * Dispatchers.IO/Default scopes and every await sits inside
 * `runBlocking { withTimeout(...) }`.
 */
internal class TranslationCoexistenceHarness private constructor(
    private val chapterId: Long,
    val barrier: CoexistenceBarrier,
    val store: ChapterTranslationStore,
    val pipeline: TranslationPipeline,
    val scheduler: TranslationScheduler,
    val translator: ChapterTranslator,
    val manager: TranslationManager,
    val fakeRecognition: FakeRecognitionEngine,
    val fakeTransport: FakeTransportTranslator,
    val trackerRegistry: TranslationBatchTrackerRegistry,
    val schedulerJobMap: CapturingJobMap,
    val streamRegistry: TranslationStreamRegistry,
    val activeStores: ActiveChapterStoreRegistry,
    val trackerScope: CoroutineScope,
    val managerScope: CoroutineScope,
    // Exposed so a test can park cleaned-image
    // publication at a COMMIT barrier mid-commit (the permit-free-commit
    // choreography). The real instance stays wired into the phases for their
    // internal reads.
    internal val cleanedPublicationMock: CleanedPublication,
    // Exposed so a test can swap in a governed transport
    // (paid calls admitted through a real ProviderRequestGovernor).
    internal val engineLane: EngineLane,
    internal val nativeStageDone: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
    internal val transportStarted: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
    // Cross-instance transport evidence and the engine-drain
    // scope. transportInstances includes the primary fakeTransport (instance 1).
    internal val transportShared: SharedTransportState,
    internal val transportInstances: List<FakeTransportTranslator>,
    private val engineDrainScope: CoroutineScope,
) {

    /** Unique process-local identity so concurrent or sequential harnesses never alias chapter state. */
    val CHAPTER_ID: Long get() = chapterId
    val DISABLED_CHAPTER_ID: Long get() = chapterId + 1L

    companion object {
        const val SOURCE_ID = 1L
        const val MANGA_ID = 2L

        private val nextChapterId = AtomicLong(1_000_000L)
        private val nextArtifactChapterKey = AtomicLong(1L)

        /**
         * Bound for every event-driven await. Generous on purpose: cold-cache CI
         * runners can be an order of magnitude slower than a warm developer
         * machine, and this cap only ever fires on a hang.
         */
        const val AWAIT_TIMEOUT_MS = 60_000L

        /** Keep teardown bounded when a canceled job has a non-cancellable tail. */
        private const val JOB_DRAIN_TIMEOUT_MS = 10_000L

        /**
         * Bound for NEGATIVE oracles ("X must NOT happen while Y is parked").
         * Event-driven: the probe returns early the moment the forbidden event
         * fires, so it never adds latency to a failing run.
         */
        const val NEGATIVE_PROBE_MS = 2_000L

        private const val CHAPTER_PAGES_KT = "eu.kanade.translation.util.ChapterPagesKt"

        fun create(
            pageKeys: List<String> = listOf("p0", "p1"),
            preRegisterInStore: Boolean = true,
            storeOverride: ChapterTranslationStore? = null,
            extraStores: Map<Long, ChapterTranslationStore> = emptyMap(),
            // Engine-drain grace for EngineLane.
            // Null leaves the production default in place; a non-null value
            // overrides the grace period for the test.
            drainGraceMs: Long? = null,
            // Optional test-only occupancy and timeout overrides. Reflection
            // keeps these seams out of the production API.
            stallThresholdMs: Long? = null,
            nativeTimeoutMs: Long? = null,
            // Optional test-only HTTP+render result-timer override. Reflection
            // keeps this seam out of the production API.
            httpRenderTimeoutMs: Long? = null,
            // Cleaned-image file names the fake provider
            // reports as physically present on disk (non-empty). Document IO is
            // a sanctioned fake seam; the resume gate's physical-presence check
            // consults it for pages persisted by an earlier batch run.
            cleanedImagesOnDisk: Set<String> = emptySet(),
            //  : drop the fake transport's per-page
            // "native inpaint lands before the paid call returns"
            // serialization. The (only) batch pipeline drains inpaint through
            // the OverlapScheduler — a page is a candidate only AFTER its
            // translation commit — so the first page's translate would wait
            // for its own inpaint, which by design can only run after that
            // same commit: a harness-invented deadlock, not a production
            // ordering.
            transportWaitsForNativeStage: Boolean = false,
        ): TranslationCoexistenceHarness {
            val chapterId = nextChapterId.getAndAdd(2L)
            val barrier = CoexistenceBarrier()

            // ---- collaborators that need no reflection ----------------------
            val context = mockk<Context> {
                every { getSharedPreferences(any(), any()) } returns InMemorySharedPreferences()
            }
            // AdaptiveKnobSignalAdapter.forContext reads the app context during pipeline construction.
            every { context.applicationContext } returns context
            val preferences = harnessPreferences()
            val provider = mockk<eu.kanade.translation.persistence.chapter.TranslationFileProvider>(relaxed = true)
            if (cleanedImagesOnDisk.isNotEmpty()) {
                val onDisk = mockk<UniFile> {
                    every { exists() } returns true
                    every { length() } returns 128L
                }
                every {
                    provider.findPageCleanedImage(any(), any(), any(), any(), any())
                } answers { if (arg<String>(4) in cleanedImagesOnDisk) onDisk else null }
            }
            val downloadProvider = mockk<DownloadProvider>(relaxed = true)
            val sourceManager = mockk<SourceManager>(relaxed = true)
            val streamRegistry = TranslationStreamRegistry()

            // With [preRegisterInStore]=false the store starts EMPTY, matching
            // production's fresh-chapter state where no page record exists
            // until the batch pre-registers (ChapterTranslator.kt:637) or the
            // manual path creates it. Pre-registered PENDING entries would
            // make the single-page planner project WAIT_FOR_DEPENDENCY for
            // every stage (DETECTION plans RUN and blocks them), while the
            // manual path would silently resume-skip before any barrier.
            // An artifact-authority store built by the test
            // (FakeChapterDocumentIo + production fresh-chapter recipe) replaces
            // the memory-only default so durable sidecars (attempt ledger,
            // manifest) are observable across a simulated process death.
            //  : the (only) batch pipeline REFUSES
            // chapters without artifact authority, so every batch-driving
            // suite passes `storeOverride = artifactAuthorityStore(...)` (
            // recipe: FakeChapterDocumentIo + production fresh-chapter
            // recipe). The default stays the reader-only memory store so
            // single-page (manual/auto) suites keep their baseline fixture —
            // the reader per-page path is untouched by.
            val store = storeOverride ?: ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = if (preRegisterInStore) {
                    pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) }
                } else {
                    emptyMap()
                },
            )

            val fakeRecognition = FakeRecognitionEngine(barrier)

            // Per-page lane serialization (see FakeTransportTranslator doc).
            val transportStarted = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            val nativeStageDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            // Cross-instance evidence and the rebuilt-instance
            // factory the EngineLane epoch retry uses. The primary fake shares
            // the state, so call/serving/close-order evidence is observable
            // across every translator instance the graph produces.
            val transportShared = SharedTransportState()
            val transportInstances = CopyOnWriteArrayList<FakeTransportTranslator>()
            fun newTransport(): FakeTransportTranslator =
                FakeTransportTranslator(
                    barrier,
                    waitForNativeStage = if (transportWaitsForNativeStage) {
                        { pageKey -> nativeStageDone[pageKey]?.await() }
                    } else {
                        { _ -> }
                    },
                    signalTransportStarted = { pageKey ->
                        println("DBG signalStart $pageKey")
                        transportStarted.computeIfAbsent(pageKey) { CompletableDeferred() }.complete(Unit)
                    },
                    shared = transportShared,
                ).also { transportInstances += it }
            val fakeTransport = newTransport()

            val nativeRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val nativeStallWatchdog = eu.kanade.translation.engines.translator.NativeStallWatchdog(
                scope = nativeRunScope,
                thresholdMs = stallThresholdMs ?: TranslationPipeline.NATIVE_STALL_THRESHOLD_MS,
            )
            val nativeRunQuarantine = NativeRunQuarantine(
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
            // The engine-drain scope injected into EngineLane
            // (production wires nativeRunScope; the harness owns its own so
            // close() can cancel leftover one-shot drains deterministically).
            val engineDrainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val inFlightPageKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
            val engineRebuildMutex = Mutex()

            // Engine cache state the real EngineLane init would set (EngineLane.kt
            // :161-206) — computed from the REAL preferences so the production
            // rebuild gate (ensureEnginesBuiltFor) is a true no-op.
            val fromLang = TextRecognizerLanguage.fromPref(preferences.translateFromLanguage())
            val toLang = TextTranslatorLanguage.fromPref(preferences.translateToLanguage())
            val ocrModel = OcrModelCatalog.selectedModel(preferences, fromLang)
            val readingOrder = preferences.translationReadingOrder().get()
            val inpaintingMode = if (preferences.translationInpaintingMode().get() == "FAST") {
                InpaintingMode.FAST
            } else {
                InpaintingMode.QUALITY
            }
            val aiEngine = preferences.translationAiEngine().get()
            val translatorSignature = EngineLane.EngineSignature(
                category = preferences.translationEngineCategory().get(),
                standardEngine = preferences.translationStandardEngine().get(),
                aiEngine = aiEngine,
                apiKeyHash = ShortHash.hash(preferences.translationAiApiKey(aiEngine).get()),
                baseUrl = preferences.translationAiBaseUrlLmStudio().get(),
                modelName = preferences.translationAiModel(aiEngine).get(),
                temperature = preferences.translationAiTemperature().get(),
                maxTokens = preferences.translationAiOutputTokens().get(),
                readingOrder = readingOrder,
                fromLang = fromLang,
                toLang = toLang,
            )
            val noPageStuck: () -> ((chapterId: Long?, pageKey: String) -> Unit)? = { null }

            // ---- EngineLane: real class with complete fake-engine state ----
            val engineLane = EngineLane.createForTesting(
                context = context,
                translationPreferences = preferences,
                nativeRunQuarantine = nativeRunQuarantine,
                inFlightPageKeys = inFlightPageKeys,
                onPageStuck = noPageStuck,
                drainGraceMs = drainGraceMs ?: EngineLane.ENGINE_DRAIN_GRACE_MS,
                drainScope = engineDrainScope,
                translatorFactory = { _, _ -> newTransport() },
                state = EngineLane.TestState(
                    fromLang = fromLang,
                    ocrModel = ocrModel,
                    readingOrder = readingOrder,
                    inpaintingMode = inpaintingMode,
                    translator = fakeTransport,
                    recognitionEngine = fakeRecognition,
                    translatorSignature = translatorSignature,
                ),
            )
            installEngineDrainSeams(engineLane, drainGraceMs, engineDrainScope, ::newTransport)

            // Extra chapter stores make the resolver
            // chapter-keyed so a manual tap on ANOTHER chapter shares the same
            // graph (and the same governed provider bucket) as the batch.
            val storeResolverHook: suspend (Translation) -> ChapterTranslationStore? = { translation ->
                extraStores[translation.chapter.id] ?: store
            }

            val pageStoreWriter = PageStoreWriter(
                activeStoreResolver = { storeResolverHook },
                streamRegistry = streamRegistry,
                handleCriticalTranslationOom = { stage, oom ->
                    MemoryGovernance.handleCriticalTranslationOom(
                        { engineLane.recognitionEngine },
                        stage,
                        oom,
                    )
                },
            )

            val expectedFingerprints: (TextRecognizerLanguage, TextTranslatorLanguage) -> BatchExpectedFingerprints =
                { from, to ->
                    PageDecode.batchExpectedFingerprints(
                        engineLane.currentTranslatorSignature,
                        engineLane.currentOcrModel,
                        engineLane.currentReadingOrder,
                        engineLane.currentInpaintingMode,
                        from,
                        to,
                    )
                }

            val realCleanedPublication = CleanedPublication(
                provider = provider,
                streamRegistry = streamRegistry,
                currentInpaintingMode = { engineLane.currentInpaintingMode },
            )

            // Mock only as a DELEGATING replacement HOOK: every method forwards
            // to the real instance unless a test overrides that one method (the
            // manual publish shim overrides persistOnnxCleanedImage; the
            // test overrides persistCleanedBitmap to park it at the COMMIT
            // barrier). Wiring the SAME instance into the pipeline AND the
            // single-page phases means a test seam covers both the boundary's
            // persistOnnxCleanedImage and the resume tail's persistCleanedBitmap.
            val cleanedPublicationMock = mockk<CleanedPublication>(relaxed = true)
            coEvery {
                cleanedPublicationMock.persistCleanedBitmap(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                realCleanedPublication.persistCleanedBitmap(
                    arg(0),
                    arg(1),
                    arg(2),
                    arg(3),
                    arg(4),
                    arg(5),
                    arg(6),
                    arg(7),
                    arg(8),
                    arg(9),
                )
            }
            coEvery {
                cleanedPublicationMock.persistOnnxCleanedImage(any(), any(), any(), any(), any())
            } coAnswers {
                realCleanedPublication.persistOnnxCleanedImage(arg(0), arg(1), arg(2), arg(3), arg(4))
            }
            coEvery {
                cleanedPublicationMock.loadPersistedCleanedBitmap(any(), any(), any(), any())
            } coAnswers {
                realCleanedPublication.loadPersistedCleanedBitmap(arg(0), arg(1), arg(2), arg(3))
            }
            coEvery {
                cleanedPublicationMock.deleteRetiredCleanedFile(any(), any(), any(), any(), any())
            } coAnswers {
                realCleanedPublication.deleteRetiredCleanedFile(arg(0), arg(1), arg(2), arg(3), arg(4))
            }

            val onnxPhase = SinglePageOnnxPhase(
                context = context,
                translationPreferences = preferences,
                provider = provider,
                downloadProvider = downloadProvider,
                streamRegistry = streamRegistry,
                engines = engineLane,
                cleanedPublication = cleanedPublicationMock,
                pageStoreWriter = pageStoreWriter,
                activeStoreResolverProvider = { storeResolverHook },
                engineRebuildMutex = engineRebuildMutex,
            )

            val httpRenderPhase = SinglePageHttpRenderPhase(
                translationPreferences = preferences,
                provider = provider,
                streamRegistry = streamRegistry,
                engines = engineLane,
                cleanedPublication = cleanedPublicationMock,
                expectedBatchFingerprints = expectedFingerprints,
                retryInpaintDownscaledFn = { manga, chapter, source, pageKey, streams, decoded, pageTranslation ->
                    onnxPhase.retryInpaintDownscaled(manga, chapter, source, pageKey, streams, decoded, pageTranslation)
                },
            )

            // ---- batch native/disk collaborator fakes (note §1.2.1) ---------
            val batchDecode: suspend (String, () -> InputStream) -> DecodedPage? = { pageKey, _ ->
                barrier.arrive(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, pageKey)
                FakeCoexistence.decodedPage(pageKey)
            }
            val batchAnalyze: suspend (
                String,
                Bitmap,
                DecodedPage,
                ChapterTranslationStore,
                BatchExpectedFingerprints,
            ) -> PageTranslation = { pageKey, _, _, batchStore, fingerprints ->
                val analyzed = FakeCoexistence.analyzedPage(pageKey).apply {
                    sourceFingerprint = "fp-$pageKey"
                    detectionFingerprint = fingerprints.detection
                    ocrFingerprint = fingerprints.ocr
                    // Decoded geometry, exactly like the real recognition
                    // engines: the batch preflight's OCR checkpoint records
                    // a complete SourceIdentity (sha256 + width + height +
                    // orientation), and `orientationOf` derives it from these.
                    imgWidth = 100f
                    imgHeight = 100f
                    // A fresh OCR result carries the current inpaint revision
                    // (the checkpoint's revision gate rejects stale ones).
                    inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                }
                //  (now unconditional, ): the REAL
                // SinglePageOnnxPhase.analyzePage persists the OCR product
                // through the store BEFORE inpaint (closing the OCR crash
                // window). The batch preflight checkpoints the STORE's
                // per-page OCR state, so the recipe performs the production
                // OCR merge here, under the OCR lease the real native worker
                // is holding (read fresh from the snapshot).
                val before = batchStore.snapshot(pageKey)
                batchStore.mergeOcr(
                    OcrStagePatch(
                        pageKey = pageKey,
                        generation = before.generation,
                        expectedPageVersion = before.pageVersion,
                        expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                        ocrResult = analyzed,
                        expectedLeaseToken = before.leaseToken,
                    ),
                    description = "t924 coexistence preflight ocr",
                ).shouldBeInstanceOf<StagePatchResult.Accepted>()
                analyzed
            }
            val batchInpaint: suspend (
                String,
                Bitmap,
                PageTranslation,
                String?,
                suspend (String, (PageTranslation?) -> PageTranslation) -> ChapterTranslationStore.PatchResult,
            ) -> PageTranslation = { pageKey, _, page, batchFingerprint, _ ->
                //  track I (round 2) CONVERSION: the old
                // `transportStarted[pageKey]?.await()` pinned the pre-decoupling
                // lane order "the page's batch identity check has passed once
                // the transport started; only then may the native stage write
                // the page". Track I removed the translation-status candidacy
                // gate (StageFingerprints.inpaint has no translation input), so
                // the overlap inpaint legitimately runs BEFORE (or without) the
                // page's own transport — awaiting it here deadlocked the native
                // lane (the suspended fake holds the ONE native permit while the
                // event it waits for may never fire: FAILED predecessors,
                // reader-owned pages, parked transports). The scheduler's
                // BATCH write-slot admission (OverlapScheduler slot-free
                // pre-check) now provides the same-page write-exclusivity this
                // wait used to approximate, so the fake inpaint runs
                // unconditionally like the real native worker.
                println("DBG inpaint $pageKey run")
                page.inpaintFingerprint = batchFingerprint
                page.cleanedBitmap = FakeCoexistence.stubBitmap()
                //    fake fidelity: the real page inpainter
                // settles the in-memory page's inpaintStatus to READY on success
                // (the durable store record is publishCleanedThroughStore's
                // job). Without this the worker's done-check fails the phase in
                // the TRACKER only — silently in every test that never asserts
                // the terminal snapshot, and wrongly in those that do.
                page.inpaintStatus = StageStatus.READY
                // Return gate of the fake native inpaint = NATIVE_RELEASE (note §2).
                barrier.arrive(CoexistenceBarrier.BarrierPoint.NATIVE_RELEASE, pageKey)
                page
            }
            val batchPersistCleaned: suspend (
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
            ) -> ChapterTranslationStore.PageSnapshot? = { page, _, _, pageKey, _, batchStore, _, _, _, expected ->
                publishCleanedThroughStore(
                    batchStore,
                    page,
                    pageKey,
                    engineLane.currentInpaintingMode.name,
                    expected,
                    nativeStageDone,
                )
            }
            val renderReload: suspend (Manga, Chapter, HttpSource, String) -> Bitmap? = { _, _, _, cleanedImageName ->
                // First action of the real render path (BatchRenderJoin.kt:154).
                // The production seam receives the CLEANED FILE NAME; the
                // barrier is keyed by page key (publishCleanedThroughStore
                // names the file "$pageKey.cleaned.jpg").
                barrier.arrive(
                    CoexistenceBarrier.BarrierPoint.RENDER,
                    cleanedImageName.removeSuffix(".cleaned.jpg"),
                )
                FakeCoexistence.stubBitmap()
            }

            val batchChapterTranslator = BatchChapterTranslator(
                provider = provider,
                translationPreferences = preferences,
                nativeLane = object : NativeLaneRunner {
                    override suspend fun <T> run(
                        timeoutMs: Long,
                        chapterId: Long?,
                        chapterName: String,
                        pageKey: String,
                        onTimeout: suspend () -> Unit,
                        block: suspend () -> T,
                    ): T? {
                        println("DBG nativeLane run pageKey=$pageKey")
                        return engineLane.withNativeLane(timeoutMs, chapterId, chapterName, pageKey, onTimeout, block)
                    }
                },
                engineRebuildMutex = engineRebuildMutex,
                ensureEnginesBuiltFor = { from, to -> engineLane.ensureEnginesBuiltFor(from, to) },
                recognitionEngineFn = { engineLane.recognitionEngine },
                textTranslatorFn = { engineLane.textTranslator },
                computeSourceFingerprintFn = { streamFn -> PageDecode.computeSourceFingerprint(streamFn) },
                batchExpectedFingerprintsFn = expectedFingerprints,
                inpaintingModeFromPref = { engineLane.inpaintingModeFromPref() },
                releaseBatchPageLease = { batchStore, pageKey ->
                    batchStore.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
                },
                persistPageWithOomRecovery = { batchStore, fileName, pageTranslation, expected ->
                    pageStoreWriter.persistPageWithOomRecovery(batchStore, fileName, pageTranslation, expected)
                },
                loadPersistedCleanedBitmap = renderReload,
                deleteRetiredCleanedFile = { manga, chapter, source, pageKey, batchStore ->
                    realCleanedPublication.deleteRetiredCleanedFile(manga, chapter, source, pageKey, batchStore)
                },
                markPageTimedOut = { manga, chapter, source, pageKey ->
                    pageStoreWriter.markPageTimedOut(manga, chapter, source, pageKey)
                },
                analyzePage = batchAnalyze,
                decodePageBitmapForTranslation = batchDecode,
                preflightInpaintGate = { bitmap, fileName ->
                    MemoryGovernance.preflightInpaintGate({ engineLane.recognitionEngine }, bitmap, fileName)
                },
                inpaintPage = batchInpaint,
                retryInpaintDownscaled = { manga, chapter, source, pageKey, streams, decoded, pageTranslation ->
                    onnxPhase.retryInpaintDownscaled(manga, chapter, source, pageKey, streams, decoded, pageTranslation)
                },
                persistCleanedBitmap = batchPersistCleaned,
                updatePageFromCurrentSnapshotFn = { batchStore, pageKey, description, update ->
                    pageStoreWriter.updatePageFromCurrentSnapshot(batchStore, pageKey, description, null, update)
                },
                onBatchClosedFn = { null },
            )

            // ---- Pipeline: real class with its runtime graph injected ------
            val pipeline = TranslationPipeline.createForTesting(
                context = context,
                provider = provider,
                downloadProvider = downloadProvider,
                translationPreferences = preferences,
                streamRegistry = streamRegistry,
                stallThresholdMs = stallThresholdMs ?: TranslationPipeline.NATIVE_STALL_THRESHOLD_MS,
                nativeTimeoutMs = nativeTimeoutMs ?: TranslationPipeline.ONNX_PHASE_TIMEOUT_MS,
                singlePageTimeoutMs = httpRenderTimeoutMs ?: TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS,
                testConstruction = TranslationPipeline.TestConstruction(
                    engineRebuildMutex = engineRebuildMutex,
                    inFlightPageKeys = inFlightPageKeys,
                    nativeRunScope = nativeRunScope,
                    nativeStallWatchdog = nativeStallWatchdog,
                    nativeRunQuarantine = nativeRunQuarantine,
                    engines = engineLane,
                ),
            )
            setFields(
                pipeline,
                listOf(
                    // Production collaborators injected into the test graph.
                    "pageStoreWriter" to pageStoreWriter,
                    "cleanedPublication" to cleanedPublicationMock,
                    "singlePageHttpRenderPhase" to httpRenderPhase,
                    "singlePageOnnxPhase" to onnxPhase,
                    "batchChapterTranslator" to batchChapterTranslator,
                    // Listener and store-resolution hooks.
                    "onPageStuck" to (null as ((chapterId: Long?, pageKey: String) -> Unit)?),
                    "activeStoreResolver" to storeResolverHook,
                    "onBatchClosed" to (null as (suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit)?),
                ),
            )
            // Set the tracker factory separately so the nullable return type is exact.
            val trackerRegistry = TranslationBatchTrackerRegistry()
            val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            setField(
                pipeline,
                "batchTrackerFactory",
            ) { chapterId: Long, chapterStore: ChapterTranslationStore, orderedPageKeys: List<String> ->
                trackerRegistry.createTracker(chapterId, chapterStore, orderedPageKeys, trackerScope)
            }
            // Optional HTTP+render result timer override; null uses the
            // production timeout.
            if (httpRenderTimeoutMs != null) {
                try {
                    setField(pipeline, "singlePageTimeoutMs", httpRenderTimeoutMs)
                } catch (_: NoSuchFieldException) {
                    throw AssertionError(
                        "T917 P5 RED defect (condition B / D8-1): TranslationPipeline has no " +
                            "injectable HTTP+render result-timer seam (singlePageTimeoutMs) — the " +
                            "withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) deadline cannot be exercised",
                    )
                }
            } else {
                runCatching { setField(pipeline, "singlePageTimeoutMs", TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS) }
            }
            // ---- real scheduler over the real pipeline ----------------------
            val sessionCoordinator = TranslationSessionCoordinator()
            val scheduler = TranslationScheduler(
                executor = pipeline,
                storeResolver = TranslationStoreResolver { chapterId ->
                    extraStores[chapterId] ?: store
                },
                immediateStoreResolver = { extraStores[it] ?: store },
                readerSessionRejectionReason = { chapterId ->
                    when (val admission = sessionCoordinator.requestReaderSession(ReaderSessionIntent(chapterId))) {
                        is SessionAdmission.Rejected -> admission.reason.name
                        else -> null
                    }
                },
            )
            val schedulerJobMap = CapturingJobMap()
            setFields(
                scheduler,
                listOf(
                    // TranslationScheduler.kt:83 — replaced so tests capture the
                    // manual job synchronously at registration (deterministic;
                    // the map entry itself may be removed asynchronously).
                    "activePageJobs" to schedulerJobMap,
                ),
            )

            // ---- real ChapterTranslator over the real pipeline --------------
            val translator = ChapterTranslator(
                context,
                provider,
                downloadProvider,
                sourceManager,
                preferences,
                streamRegistry,
                TranslationQueueStore(context),
                pipeline,
            )

            // ---- Manager surface and reader teardown wiring -----------------
            // The harness taps the manual path
            // through scheduler.translatePage because TranslationManager's
            // `readerTeardown` is a computed property, not a field for
            // reflection injection. The coordinator delegates to this same
            // scheduler entry.
            val activeStores = ActiveChapterStoreRegistry()
            val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = TranslationManager.createForTesting(
                context = context,
                provider = provider,
                sourceManager = sourceManager,
                translationPreferences = preferences,
                downloadProvider = downloadProvider,
                pipeline = pipeline,
                translator = translator,
                pendingRequestStore = mockk(relaxed = true),
                pendingRequests = MutableStateFlow(emptyMap()),
                scheduler = scheduler,
                sessionCoordinator = sessionCoordinator,
                streamRegistry = streamRegistry,
            )
            setField(manager, "activeStores", activeStores)

            val harness = TranslationCoexistenceHarness(
                chapterId = chapterId,
                barrier = barrier,
                store = store,
                pipeline = pipeline,
                scheduler = scheduler,
                translator = translator,
                manager = manager,
                fakeRecognition = fakeRecognition,
                fakeTransport = fakeTransport,
                trackerRegistry = trackerRegistry,
                schedulerJobMap = schedulerJobMap,
                streamRegistry = streamRegistry,
                activeStores = activeStores,
                trackerScope = trackerScope,
                managerScope = managerScope,
                cleanedPublicationMock = cleanedPublicationMock,
                engineLane = engineLane,
                nativeStageDone = nativeStageDone,
                transportStarted = transportStarted,
                transportShared = transportShared,
                transportInstances = transportInstances,
                engineDrainScope = engineDrainScope,
            )
            harness.installManualPublishShim()
            return harness
        }

        // ------------------------------------------------------------------
        // reflection helpers (single class-walk, existing setField precedent)
        // ------------------------------------------------------------------

        internal fun setField(target: Any, fieldName: String, value: Any?) {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                try {
                    val field: Field = cls.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
        }

        /** ALL reflection wiring for one target in one (name, value) list. */
        internal fun setFields(target: Any, values: List<Pair<String, Any?>>) {
            values.forEach { (name, value) -> setField(target, name, value) }
        }

        /**
         * Injects the engine-epoch and borrow-drain test state into the
         * constructed [EngineLane]. Missing optional fields are ignored; an
         * explicitly requested [drainGraceMs] must be available and fails
         * with a named assertion otherwise.
         */
        private fun installEngineDrainSeams(
            engineLane: EngineLane,
            drainGraceMs: Long?,
            drainScope: CoroutineScope,
            transportFactory: () -> FakeTransportTranslator,
        ) {
            fun setIfPresent(name: String, value: Any?): Boolean = try {
                setField(engineLane, name, value)
                true
            } catch (_: NoSuchFieldException) {
                false
            }

            setIfPresent("engineEpoch", java.util.concurrent.atomic.AtomicLong(0))
            setIfPresent("translatorUseCount", java.util.concurrent.atomic.AtomicInteger(0))
            setIfPresent("drainScope", drainScope)
            setIfPresent(
                "translatorFactory",
                { _: TextRecognizerLanguage, _: TextTranslatorLanguage -> transportFactory() },
            )
            if (drainGraceMs != null) {
                if (!setIfPresent("drainGraceMs", drainGraceMs)) {
                    throw AssertionError(
                        "T917 D7 RED defect: drainGraceMs is not configurable — the §1.2 " +
                            "engine-drain seam is missing from EngineLane",
                    )
                }
            } else {
                setIfPresent("drainGraceMs", 5_000L)
            }
        }

        /** Real TranslationPreferences over an in-memory PreferenceStore (STANDARD lane, note §1.2.2). */
        internal fun harnessPreferences(): TranslationPreferences {
            val seeds: MutableMap<String, Any> = mutableMapOf(
                "translation_engine_category" to TranslationEngineCategory.STANDARD,
                "translation_standard_engine" to StandardEngine.MLKIT,
                "translation_ai_engine" to AiEngine.GEMINI,
                "translate_language_from" to "JAPANESE",
                "translate_language_to" to "ENGLISH",
                "translation_ocr_model_japanese" to OcrModel.MLKIT,
                "translation_ai_model_gemini" to "",
                "translation_ai_output_tokens" to "",
                "translation_inpainting_mode" to "FAST",
            )
            val store = InMemoryPreferenceStore(
                seeds.entries.map { (key, value) -> seed(key, value) }.asSequence(),
            )
            return TranslationPreferences(store)
        }

        /**
         * The durable artifact-store recipe for the
         * batch pipeline. Production establishes chapter artifact authority the
         * first time a store with an artifact parent persists; tests build the
         * same ARTIFACTS authority directly (the BatchDispatchResumeWiringTest
         * recipe over in-memory document IO) so the batch preflight's
         * checkpoint transactions are observable in durable sidecars. Pages
         * start as fresh PENDING records; the real shell pre-registers the
         * ordered page set into the manifest at trigger time.
         */
        fun artifactAuthorityStore(
            pageKeys: List<String>,
            preRegisterInStore: Boolean = true,
        ): ChapterTranslationStore {
            val documentIo = FakeChapterDocumentIo()
            val layout = ChapterArtifactLayout("T941 Harness Chapter ${nextArtifactChapterKey.getAndIncrement()}")
            val artifact = ChapterArtifactEngine(
                AtomicChapterDocuments(documentIo),
                layout,
                displayBaseProbe = { eu.kanade.translation.persistence.artifact.ProbedImage(100, 100) },
            )
            var manifest = artifact
                .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
                .manifest
            manifest = manifest.copy(
                cutoverAtEpochMs = 1L,
                migratedFromLegacyAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            )
            check(artifact.publishManifest(manifest)) { "harness: authority flip publish failed" }
            return ChapterTranslationStore(
                translationFile = null as UniFile?,
                fileCreator = null,
                initialPages = if (preRegisterInStore) {
                    pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) }
                } else {
                    emptyMap()
                },
                artifactStore = artifact,
                initialArtifactManifest = checkNotNull(artifact.readManifest()),
            )
        }

        /**
         * The STANDARD_PIPELINE lane recipe — the real
         * shell (ChapterTranslator → TranslationPipeline → BatchChapterTranslator
         * → ChapterProfileBatchCoordinator with the injected standard seam)
         * driven with the harness's STANDARD engine seeds. The
         * standard translator IS the engine the shell resolves through the
         * normal EngineLane construction path (`textTranslatorFn =
         * { engineLane.textTranslator }` = [FakeTransportTranslator]); no
         * production change. The fake transport drops the legacy per-page
         * native-stage wait — see [create]'s `transportWaitsForNativeStage`.
         * The store is a durable ARTIFACTS-authority store ([artifactAuthorityStore])
         * because the batch preflight refuses chapters without artifact
         * authority.
         */
        fun createStandard(
            pageKeys: List<String>,
            storeOverride: ChapterTranslationStore? = null,
        ): TranslationCoexistenceHarness = create(
            pageKeys = pageKeys,
            storeOverride = storeOverride ?: artifactAuthorityStore(pageKeys),
            transportWaitsForNativeStage = false,
        )

        private fun seed(key: String, value: Any): InMemoryPreferenceStore.InMemoryPreference<Any> =
            InMemoryPreferenceStore.InMemoryPreference(key, value, value)

        /**
         * Models "cleaned image durable" through the REAL guarded store write —
         * the production persistCleanedBitmap commit without Bitmap.compress.
         *
         * The precondition is read fresh at patch time instead of using the
         * worker's captured
         * [expected]. Production captures it before a ~100ms JPEG encode, so
         * the concurrent standard-lane translation commit lands inside that
         * window and the identity map is re-read downstream; the fake has zero
         * I/O latency, which would turn that benign production window into a
         * deterministic stale-precondition rejection and poison every
         * choreography.
         */
        private suspend fun publishCleanedThroughStore(
            batchStore: ChapterTranslationStore,
            page: PageTranslation,
            pageKey: String,
            inpaintingModeName: String,
            expected: ChapterTranslationStore.PatchPrecondition?,
            nativeStageDone: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
        ): ChapterTranslationStore.PageSnapshot? {
            page.cleanedImageName = "$pageKey.cleaned.jpg"
            page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            page.inpaintingModeUsed = inpaintingModeName
            page.inpaintStatus = StageStatus.READY
            page.errorMessage = null
            // updatePageGuarded (the guarded write every batch stage writer uses)
            // — patchPage's manifest-fingerprint clause rejects memory-only
            // stores where the snapshot fingerprint is page-derived.
            val precondition = batchStore.snapshot(pageKey).toPrecondition()
            val result = batchStore.updatePageGuarded(pageKey, precondition, "publish cleaned image") { current ->
                (current ?: page).apply {
                    cleanedImageName = page.cleanedImageName
                    inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                    inpaintingModeUsed = inpaintingModeName
                    inpaintStatus = StageStatus.READY
                    errorMessage = null
                }
            }
            // Per-page lane ordering point: the transport (paid call) waits for
            // this, so the cleaned publication is strictly durable before the
            // translation commit for the same page — the two same-page writers
            // never overlap, which instant fakes would otherwise make a coin
            // flip (see FakeTransportTranslator doc).
            nativeStageDone[pageKey]?.complete(Unit)
            return when (result) {
                is ChapterTranslationStore.PatchResult.Accepted -> result.snapshot
                is ChapterTranslationStore.PatchResult.Rejected -> null
            }
        }
    }

    // ----------------------------------------------------------------------
    // fixtures shared by the coexistence tests
    // ----------------------------------------------------------------------

    val manga: Manga = mockk {
        every { id } returns MANGA_ID
        every { title } returns "fixture"
        every { source } returns SOURCE_ID
    }

    private val chapterMocks = ConcurrentHashMap<Long, Chapter>()

    fun chapterFor(chapterId: Long): Chapter = chapterMocks.computeIfAbsent(chapterId) {
        mockk {
            every { id } returns chapterId
            every { name } returns "chapter-$chapterId"
            every { scanlator } returns null
        }
    }

    val source: HttpSource = mockk {
        every { id } returns SOURCE_ID
    }

    private var batchJobStub: Job? = null
    private val teardownJobsLock = Any()
    private val activeTeardownJobs = ConcurrentHashMap.newKeySet<Job>()

    @Volatile
    private var graphicsShimsInstalled = false
    private var pageDecodeShimInstalled = false
    private var colorEstimatorShimInstalled = false
    private var bitmapShimInstalled = false

    /**
     * Registers a reader page stream so the single-page boundary resolves its
     * source without disk (real TranslationStreamRegistry).
     */
    fun registerReaderStream(chapterId: Long, pageKey: String) {
        streamRegistry.register(SOURCE_ID, MANGA_ID, chapterId, pageKey) {
            ByteArrayInputStream("page-$pageKey".toByteArray())
        }
    }

    /**
     * Launches the real manual page path through the same scheduler entry used
     * by the reader teardown coordinator.
     */
    fun tapManual(pageKey: String, chapterId: Long = CHAPTER_ID, force: Boolean = false) {
        scheduler.translatePage(manga, chapterFor(chapterId), source, pageKey, force = force)
    }

    /** Awaits (event-driven) the manual job captured at scheduler registration. */
    suspend fun capturedManualJob(pageKey: String, chapterId: Long = CHAPTER_ID): Job =
        runBlocking {
            withTimeout(AWAIT_TIMEOUT_MS) {
                schedulerJobMap.captured["$chapterId:$pageKey"]?.await()
                    ?: error("manual job for $chapterId:$pageKey was never registered")
            }.also { job ->
                job.invokeOnCompletion { cause ->
                    if (cause != null) println("DBG manual job failed: $cause")
                }
            }
        }

    /** A launched batch run: the real translation entry + its reconciliation. */
    class BatchRun(
        val translation: Translation,
        val job: Job,
        val reconciliation: CompletableDeferred<ReconciliationResult?>,
    )

    /**
     * Drives the REAL ChapterTranslator.translateChapterInternal (injected active
     * translationJob per ChapterTranslatorTerminalExitsTest.kt:174-182) so the
     * full batch shell runs: pre-registration, tracker, translateBatch,
     * reconciliation, tracker finish.
     *
     * [sourcePageCount]/[sourceCountKnown] carry the
     * trigger's admission-probe cross-check on the batch's [Translation]
     * (sourceCountKnown=true with a null count = the offline-unknown total).
     * Optional source-count metadata is injected reflectively because it is not
     * part of the production API. When absent, totals remain unknown.
     */
    fun launchBatch(
        pageKeys: List<String>? = null,
        sourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ): BatchRun {
        // Per-page lane-serialization entries (see FakeTransportTranslator doc)
        // must exist BEFORE the batch's lanes run. On an empty-start store the
        // keys are not observable yet, so tests pass them explicitly.
        (pageKeys ?: store.state.value.keys.toList()).forEach { pageKey ->
            transportStarted.computeIfAbsent(pageKey) { CompletableDeferred() }
            nativeStageDone.computeIfAbsent(pageKey) { CompletableDeferred() }
        }
        val translation = Translation(source, manga, chapterFor(CHAPTER_ID))
        if (sourceCountKnown || sourcePageCount != null) {
            try {
                setField(translation, "probedSourcePageCount", sourcePageCount)
                setField(translation, "sourceCountKnown", sourceCountKnown)
            } catch (_: NoSuchFieldException) {
                // Source-count metadata is optional; without it, the batch
                // runs with an unknown source total.
            }
        }
        val reconciliation = CompletableDeferred<ReconciliationResult?>()
        val activeJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { awaitCancellation() }
        batchJobStub = activeJob
        setField(translator, "translationJob", activeJob)
        // Start the real batch coroutine on the caller before returning. The
        // harness's first observation is an event from inside the batch; an
        // ordinary IO launch can leave that observation queued behind other
        // fixture collectors long enough to make a healthy run look absent.
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            try {
                reconciliation.complete(translator.translateChapterInternal(translation))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    reconciliation.cancel()
                } else {
                    reconciliation.completeExceptionally(t)
                }
            }
        }
        trackJobForTeardown(job)
        return BatchRun(translation, job, reconciliation)
    }

    /** Register test-owned work that can still touch this harness after an assertion exits. */
    fun trackJobForTeardown(job: Job) {
        synchronized(teardownJobsLock) {
            activeTeardownJobs += job
            job.invokeOnCompletion {
                synchronized(teardownJobsLock) { activeTeardownJobs -= job }
            }
        }
    }

    /**
     * Fake chapter page enumeration for JVM tests. The production implementation
     * filters through ImageUtil, which cannot load here. Streams are in memory,
     * and image decoding is replaced at its supported seam.
     */
    fun stubChapterPages(pageKeys: List<String> = listOf("p0", "p1")) {
        mockkStatic(CHAPTER_PAGES_KT)
        every { getChapterPages(any(), any()) } returns pageKeys.map { key ->
            key to { ByteArrayInputStream("page-$key".toByteArray()) }
        }
    }

    fun unstubChapterPages() {
        unmockkStatic(CHAPTER_PAGES_KT)
    }

    /**
     * Single-page Android-graphics shims: the decode seam the
     * single-page path cannot fake through a constructor (mockkObject(PageDecode))
     * and the render color estimator (bitmap.width/getPixels throw on the JVM
     * android.jar). mockkObject makes the whole PageDecode object strict, so the
     * two pure-JVM helpers the real batch/manual paths call through it are
     * configured with callOriginal so JVM test helpers retain their behavior.
     * Install per test; uninstall with
     * [removeGraphicsShims].
     */
    fun installGraphicsShims() {
        check(!graphicsShimsInstalled) { "graphics shims are already installed for this harness" }
        graphicsShimsInstalled = true
        try {
            // Mark each target before mocking so teardown also rolls back if
            // MockK fails partway through installing a global shim.
            pageDecodeShimInstalled = true
            mockkObject(PageDecode)
            coEvery {
                PageDecode.decodePageBitmapForTranslation(any(), any(), any(), any(), any())
            } coAnswers {
                val pageKey = thirdArg<String>()
                barrier.arrive(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, pageKey)
                FakeCoexistence.decodedPage(pageKey)
            }
            coEvery { PageDecode.computeSourceFingerprint(any()) } coAnswers { callOriginal() }
            every {
                PageDecode.batchExpectedFingerprints(any(), any(), any(), any(), any(), any())
            } answers { callOriginal() }

            colorEstimatorShimInstalled = true
            mockkObject(RenderColorEstimator)
            every { RenderColorEstimator.recomputeFor(any(), any()) } returns Unit
            // AUTO prepared-page test setup:
            // the AUTO prepared-page translate half (translatePreparedPage) builds a
            // 1x1 dummy DecodedPage via Bitmap.createBitmap, which the unit-test
            // android.jar throws on. Disk/render IO shim only — no coexistence
            // collaborator is faked.
            bitmapShimInstalled = true
            mockkStatic(android.graphics.Bitmap::class)
            every {
                android.graphics.Bitmap.createBitmap(any(), any(), any())
            } answers { FakeCoexistence.stubBitmap() }
        } catch (failure: Throwable) {
            runCatching { uninstallGraphicsShims() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun removeGraphicsShims() {
        cancelAndJoinRunningJobs()
        if (!graphicsShimsInstalled) return
        uninstallGraphicsShims()
    }

    private fun uninstallGraphicsShims() {
        var failure: Throwable? = null
        fun attempt(installed: Boolean, uninstall: () -> Unit, onSuccess: () -> Unit) {
            if (!installed) return
            try {
                uninstall()
                onSuccess()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        attempt(
            bitmapShimInstalled,
            { unmockkStatic(android.graphics.Bitmap::class) },
            { bitmapShimInstalled = false },
        )
        attempt(
            colorEstimatorShimInstalled,
            { unmockkObject(RenderColorEstimator) },
            { colorEstimatorShimInstalled = false },
        )
        attempt(
            pageDecodeShimInstalled,
            { unmockkObject(PageDecode) },
            { pageDecodeShimInstalled = false },
        )
        graphicsShimsInstalled = bitmapShimInstalled || colorEstimatorShimInstalled || pageDecodeShimInstalled
        failure?.let { throw it }
    }

    /**
     * Batch jobs run in their own SupervisorJob; explicitly tracked test-owned
     * work and scheduler manual, auto-page, and coordinator jobs are also drained
     * before global MockK shims or fixture resources are released.
     */
    private fun cancelAndJoinRunningJobs() {
        // Completion callbacks remove jobs from the live key set. Serialize the
        // copy with those removals so size-based toSet() cannot race its iterator.
        val trackedJobs = synchronized(teardownJobsLock) { activeTeardownJobs.toSet() }
        trackedJobs.forEach { it.cancel() }
        runBlocking {
            val drained = withTimeoutOrNull(JOB_DRAIN_TIMEOUT_MS) {
                scheduler.awaitReaderStop(reason = "Coexistence harness teardown")
                trackedJobs.joinAll()
                true
            } ?: false
            if (!drained) {
                System.err.println(
                    "Timed out draining coexistence harness jobs after ${JOB_DRAIN_TIMEOUT_MS}ms; " +
                        "all captured jobs were canceled before the join.",
                )
            }
        }
    }

    /**
     * Manual-path cleaned-image publication shim (note §1.2.3): the pipeline's
     * cleanedPublication field is replaced by a mockk whose persistOnnxCleanedImage
     * performs the production cleaned-image commit through the real store
     * (publishCleanedThroughStore) without Bitmap.compress.
     */
    private fun installManualPublishShim() {
        coEvery {
            cleanedPublicationMock.persistOnnxCleanedImage(any(), any(), any(), any(), any())
        } coAnswers {
            val result = arg<OnnxPhaseResult>(4)
            persistManualCleanedResult(result)
        }
    }

    private suspend fun persistManualCleanedResult(result: OnnxPhaseResult): OnnxPhaseResult {
        val page = result.pageTranslation
        val pageKey = page.sourceFileName ?: error("publish shim: missing sourceFileName")
        page.cleanedImageName = "$pageKey.cleaned.jpg"
        page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        page.inpaintingModeUsed = engineLane.currentInpaintingMode.name
        page.inpaintStatus = StageStatus.READY
        page.errorMessage = null
        val precondition = result.commitPrecondition ?: result.store.snapshot(pageKey).toPrecondition()
        val published = result.store.updatePageGuarded(pageKey, precondition, "publish cleaned image (harness shim)") { current ->
            (current ?: page).apply {
                cleanedImageName = page.cleanedImageName
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                inpaintingModeUsed = page.inpaintingModeUsed
                inpaintStatus = StageStatus.READY
                errorMessage = null
            }
        }
        check(published is ChapterTranslationStore.PatchResult.Accepted) {
            "publish shim rejected: ${(published as? ChapterTranslationStore.PatchResult.Rejected)?.reason}"
        }
        // The real HTTP+render phase commits through store.patchPage, whose
        // dependency-fingerprint clause is manifest-backed. In the harness's
        // memory-only store the snapshot fingerprint is page-derived and can
        // never match a (nonexistent) manifest candidate, so the shim hands the
        // use a manifest-free precondition; generation, pageVersion and lease
        // token fences stay intact.
        val legacyStorePrecondition = published.snapshot.toPrecondition().copy(dependencyFingerprint = null)
        nativeStageDone[pageKey]?.complete(Unit)
        return result.copy(commitPrecondition = legacyStorePrecondition)
    }

    /** Tears the graph down without leaving scopes or pool state behind. */
    fun close() {
        runCatching { cancelAndJoinRunningJobs() }
        if (graphicsShimsInstalled) runCatching { uninstallGraphicsShims() }
        runCatching { unstubChapterPages() }
        runCatching { scheduler.close() }
        runCatching { pipeline.close() }
        runCatching { batchJobStub?.cancel() }
        runCatching { engineDrainScope.cancel() }
        runCatching { trackerScope.cancel() }
        runCatching { managerScope.cancel() }
        runCatching { BitmapPool.releaseAll() }
    }

    /**
     * Failure context for assertions that expect a completely translated
     * chapter. Keep the durable manifest failures and run counters alongside
     * the live store projection so a cross-test failure identifies its page
     * and typed pipeline reason without relying on logcat output.
     */
    fun failureDiagnostics(pageKeys: List<String>): String {
        val artifact = store.artifactEngine
        val manifest = artifact?.readManifest()
        val pageSnapshots = pageKeys.associateWith { pageKey ->
            val page = store.state.value[pageKey]
            page?.let {
                "ocr=${it.ocrStatus}(${it.ocrError}), " +
                    "translation=${it.translationStatus}(${it.translationError}), " +
                    "inpaint=${it.inpaintStatus}(${it.inpaintError}), " +
                    "render=${it.renderStatus}(${it.renderError})"
            } ?: "missing"
        }
        val artifactPages = pageKeys.associateWith { pageKey ->
            val page = manifest?.pages?.get(pageKey)
            page?.let {
                "ocr=${it.ocr?.status}:${it.ocr?.skipReason}, " +
                    "translation=${it.translation?.status}:${it.translation?.skipReason}, " +
                    "inpaint=${it.inpaint?.status}:${it.inpaint?.skipReason}, " +
                    "layout=${it.layout?.status}:${it.layout?.skipReason}"
            } ?: "missing"
        }
        val guardedWriteState = runBlocking {
            val states = LinkedHashMap<String, String>()
            for (pageKey in pageKeys) {
                val snapshot = store.snapshot(pageKey)
                states[pageKey] = "generation=${snapshot.generation}, pageVersion=${snapshot.pageVersion}, " +
                    "leaseToken=${snapshot.leaseToken}, candidateGenerationId=${snapshot.candidateGenerationId}, " +
                    "dependencyFingerprint=${snapshot.dependencyFingerprint}, " +
                    "artifactPageVersion=${snapshot.artifactPageVersion}"
            }
            states
        }
        val chapterKey = store.artifactChapterKey()
        val activeWriters = ActiveChapterStoreRegistry.allActiveWriters().filter { writer ->
            writer.chapterId == CHAPTER_ID || (chapterKey != null && writer.chapterKey == chapterKey)
        }
        val durableFailures = manifest?.durableFailures.orEmpty()
            .filterKeys { failureKey -> pageKeys.any { failureKey.startsWith("$it:") } }
            .mapValues { (_, failure) ->
                "stage=${failure.stage}, status=${failure.status}, category=${failure.category}, " +
                    "retryCount=${failure.retryCount}, reason=${failure.lastFailureMessage}"
            }
        val runSummary = manifest?.activeRun?.let { pointer ->
            when (val read = artifact?.readRunRecord(pointer)) {
                is ChapterArtifactEngine.RunRecordRead.Usable ->
                    "state=${read.record.state}, phaseCounters=${read.record.phaseCounters}"

                null -> "unavailable"
                else -> read.toString()
            }
        } ?: "none"
        val liveTracker = trackerRegistry.getLive(CHAPTER_ID)?.snapshot?.value?.let {
            "state=${it.state}, pageStates=${it.pages.map { page ->
                "${page.pageKey}:${page.stage}:${page.errorMessage}"
            }}, pauseReason=${it.pauseReason}, nonDurableFailureReason=${it.nonDurableFailureReason}"
        } ?: "not live"
        val terminal = trackerRegistry.terminal.value[CHAPTER_ID]?.let {
            "state=${it.state}, pages=${it.pages.map { page ->
                "${page.pageKey}:${page.stage}:${page.errorMessage}"
            }}, perStage=${it.perStage}, groupedFailures=${it.groupedFailures}, " +
                "pauseReason=${it.pauseReason}, abortedReason=${it.abortedReason}, " +
                "nonDurableFailureReason=${it.nonDurableFailureReason}"
        } ?: "not published"
        val batchWriteGateRejection = store.lastBatchWriteGateRejectionDiagnostic ?: "none"
        val guardedWriteRejection = store.lastGuardedWriteRejectionDiagnostic ?: "none"

        return "chapterId=$CHAPTER_ID, chapterKey=$chapterKey, " +
            "rejectingComponent=BatchLaneWorkers.standardTranslateOutcome -> " +
            "BatchWriteGate.guardedBatchUpdate -> ChapterTranslationStore.updatePageGuarded, " +
            "batchWriteGateRejection=$batchWriteGateRejection, " +
            "guardedWriteRejection=$guardedWriteRejection, " +
            "activeWriters=$activeWriters, guardedWriteState=$guardedWriteState, " +
            "pageSnapshots=$pageSnapshots, artifactPages=$artifactPages, " +
            "durableFailures=$durableFailures, run=$runSummary, liveTracker={$liveTracker}, " +
            "tracker={$terminal}"
    }

    /**
     * Paid-call count for [pageKey] across every transport
     * instance (the primary fake plus any translator the epoch retry rebuilt).
     */
    fun transportCallsFor(pageKey: String): Int =
        transportInstances.sumOf { it.callsFor(pageKey) }
}

/**
 * ConcurrentHashMap replacement for TranslationScheduler.activePageJobs that
 * captures every registered job into a CompletableDeferred at registration time.
 * translatePage registers the job synchronously before returning, but removes
 * the entry asynchronously — capturing at put-time removes the observation race
 * without polls.
 */
internal class CapturingJobMap : ConcurrentHashMap<String, Job>() {
    val captured = ConcurrentHashMap<String, CompletableDeferred<Job>>()

    override fun put(key: String, value: Job): Job? {
        captured.computeIfAbsent(key) { CompletableDeferred() }.complete(value)
        return super.put(key, value)
    }
}
