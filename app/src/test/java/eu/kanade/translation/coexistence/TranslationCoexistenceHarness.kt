@file:Suppress("ClassName")


package eu.kanade.translation.coexistence
import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ArtifactSeed
import eu.kanade.translation.persistence.AtomicChapterDocuments
import eu.kanade.translation.persistence.ChapterArtifactEngine
import eu.kanade.translation.persistence.ChapterArtifactLayout
import eu.kanade.translation.persistence.FakeChapterDocumentIo
import eu.kanade.translation.persistence.loadArtifact
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.scheduling.InMemorySharedPreferences
import eu.kanade.translation.scheduling.TranslationQueueStore
import eu.kanade.translation.pipeline.AttemptAuthority
import eu.kanade.translation.pipeline.CleanedOwner
import eu.kanade.translation.pipeline.CleanedPublication
import eu.kanade.translation.pipeline.EngineLane
import eu.kanade.translation.pipeline.OnnxPhaseResult
import eu.kanade.translation.pipeline.PageDecode
import eu.kanade.translation.pipeline.PageStoreWriter
import eu.kanade.translation.pipeline.SinglePageHttpRenderPhase
import eu.kanade.translation.pipeline.SinglePageOnnxPhase
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.adaptive.DevicePagePermitGate
import eu.kanade.translation.pipeline.execution.NativeRunQuarantine
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.memory.MemoryGovernance
import eu.kanade.translation.scheduling.ReaderPageWork
import eu.kanade.translation.scheduling.ReaderSessionToken
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationSession
import eu.kanade.translation.scheduling.TranslationStoreResolver
import eu.kanade.translation.pipeline.toPrecondition
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
import kotlinx.coroutines.delay
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
 * ChapterTranslationStore (memory-only) → ChapterTranslator —
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
    val streamRegistry: TranslationStreamRegistry,
    val activeStores: ActiveChapterStoreRegistry,
    val managerScope: CoroutineScope,
    internal val cleanedPublicationMock: CleanedPublication,
    internal val engineLane: EngineLane,
    internal val transportStarted: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
    // Cross-instance transport evidence and the engine-drain
    // scope. transportInstances includes the primary fakeTransport (instance 1).
    internal val transportShared: SharedTransportState,
    internal val transportInstances: List<FakeTransportTranslator>,
    private val engineDrainScope: CoroutineScope,
) {

    /** Unique process-local identity so concurrent or sequential harnesses never alias chapter state. */
    val CHAPTER_ID: Long get() = chapterId

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
            val provider = mockk<eu.kanade.translation.persistence.TranslationFileProvider>(relaxed = true)
            val downloadProvider = mockk<DownloadProvider>(relaxed = true)
            val sourceManager = mockk<SourceManager>(relaxed = true)
            val streamRegistry = TranslationStreamRegistry()

            val store = storeOverride ?: ChapterTranslationStore(
                artifactParentResolver = null,
                initialPages = if (preRegisterInStore) {
                    pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) }
                } else {
                    emptyMap()
                },
            )

            val fakeRecognition = FakeRecognitionEngine(barrier)

            // Per-page lane serialization (see FakeTransportTranslator doc).
            val transportStarted = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            // Cross-instance evidence and the rebuilt-instance
            // factory the EngineLane epoch retry uses. The primary fake shares
            // the state, so call/serving/close-order evidence is observable
            // across every translator instance the graph produces.
            val transportShared = SharedTransportState()
            val transportInstances = CopyOnWriteArrayList<FakeTransportTranslator>()
            fun newTransport(): FakeTransportTranslator =
                FakeTransportTranslator(
                    barrier,
                    waitForNativeStage = { _ -> },
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
                currentNeuralInpaintModel = { engineLane.currentNeuralInpaintModel },
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
                cleanedPublicationMock.persistOnnxCleanedImage(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                realCleanedPublication.persistOnnxCleanedImage(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7))
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
                retryInpaintDownscaledFn = { manga, chapter, source, pageKey, streams, decoded, pageTranslation, _, _ ->
                    onnxPhase.retryInpaintDownscaled(manga, chapter, source, pageKey, streams, decoded, pageTranslation)
                },
            )

            // ---- Pipeline: real class with its runtime graph injected ------
            val devicePagePermitGate = DevicePagePermitGate()
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
                    devicePagePermitGate = devicePagePermitGate,
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
                    // Listener and store-resolution hooks.
                    "onPageStuck" to (null as ((chapterId: Long?, pageKey: String) -> Unit)?),
                    "activeStoreResolver" to storeResolverHook,
                ),
            )
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
                // Queue-bound mode: manual taps resolve their stream factory
                // through the same registry the reader controller populates.
                manualStreamProvider = { mangaArg, chapterArg, sourceArg, pageKey ->
                    streamRegistry.peek(mangaArg, chapterArg, sourceArg, pageKey)
                },
                readerSessionRejectionReason = { chapterId ->
                    when (val admission = sessionCoordinator.requestReaderSession(ReaderSessionIntent(chapterId))) {
                        is SessionAdmission.Rejected -> admission.reason.name
                        else -> null
                    }
                },
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
                batchCoordinator = mockk(relaxed = true),
            )

            // Route reader taps through the production scheduler.
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
                streamRegistry = streamRegistry,
                activeStores = activeStores,
                managerScope = managerScope,
                cleanedPublicationMock = cleanedPublicationMock,
                engineLane = engineLane,
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

        fun artifactAuthorityStore(
            pageKeys: List<String>,
            preRegisterInStore: Boolean = true,
        ): ChapterTranslationStore {
            val documentIo = FakeChapterDocumentIo()
            val layout = ChapterArtifactLayout("T941 Harness Chapter ${nextArtifactChapterKey.getAndIncrement()}")
            val artifact = ChapterArtifactEngine(
                AtomicChapterDocuments(documentIo),
                layout,
            )
            var manifest = artifact
                .loadArtifact(ArtifactSeed(createdAtEpochMs = 1L))
                .manifest
            manifest = manifest.copy(
                cutoverAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            )
            check(artifact.publishManifest(manifest)) { "harness: authority flip publish failed" }
            return ChapterTranslationStore(
                artifactParentResolver = null,
                initialPages = if (preRegisterInStore) {
                    pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) }
                } else {
                    emptyMap()
                },
                artifactStore = artifact,
                initialArtifactManifest = checkNotNull(artifact.readManifest()),
            )
        }

        private fun seed(key: String, value: Any): InMemoryPreferenceStore.InMemoryPreference<Any> =
            InMemoryPreferenceStore.InMemoryPreference(key, value, value)


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

    /**
     * Binds the queue session for [chapterId] and submits [pageKeys] as
     * AUTOMATIC viewport demand (exactly N, N+1 at [pageKeys.size]). Returns
     * the token so tests can drive queue-native cancellation/outcome entries.
     * Re-seamed in Phase 4 S4: replaces the retired prepared-page boundary as
     * the automatic (drain-not-cancel) execution path.
     */
    fun submitViewport(
        pageKeys: List<String>,
        chapterId: Long = CHAPTER_ID,
        computeClass: TranslatorComputeClass = TranslatorComputeClass.REMOTE_IO,
    ): ReaderSessionToken {
        val session = TranslationSession(
            key = "harness-queue-session-$chapterId",
            manga = manga,
            chapter = chapterFor(chapterId),
            source = source,
            store = store,
        )
        val token = scheduler.bindReaderQueueSession(chapterId, session, computeClass)
        checkNotNull(token) { "harness: queue session bind was rejected for chapterId=$chapterId" }
        scheduler.updateReaderViewport(token, 0, pageKeys.size) { index ->
            pageKeys.getOrNull(index)?.let { pageKey ->
                ReaderPageWork(pageKey, index) { ByteArrayInputStream("page-$pageKey".toByteArray()) }
            }
        }
        return token
    }

    /**
     * Awaits (event-driven) the manual attempt's terminal — the queue-bound
     * equivalent of the removed scheduler job map. The returned handle
     * completes when the queue publishes a typed outcome for the page;
     * cancelling it does not stop the queue-owned worker (stop work through
     * the scheduler's cancel entries instead).
     */
    fun capturedManualJob(pageKey: String, chapterId: Long = CHAPTER_ID): Job {
        val terminal = CompletableDeferred<Unit>()
        val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val watcher = watcherScope.launch {
            withTimeout(AWAIT_TIMEOUT_MS) {
                while (scheduler.manualOutcomeFor(chapterId, pageKey) == null) {
                    delay(25)
                }
            }
            terminal.complete(Unit)
        }
        terminal.invokeOnCompletion {
            watcher.cancel()
            watcherScope.cancel()
        }
        return terminal
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
            // Runner-path test setup:
            // the single-page runner's translate/render half builds a
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

    private fun installManualPublishShim() {
        coEvery {
            cleanedPublicationMock.enrollCleanedPersistence(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val pageKey = arg<String>(3)
            val page = arg<PageTranslation>(5)
            if (!arg<CleanedOwner>(6).isAvailable) return@coAnswers false
            val attempt = arg<AttemptAuthority>(7)
            page.cleanedImageName = "$pageKey.cleaned.jpg"
            page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            page.inpaintingModeUsed = engineLane.currentInpaintingMode.name
            page.inpaintStatus = StageStatus.READY
            val result = attempt.write("publish cleaned image (harness I/O shim)") { current ->
                current.apply {
                    cleanedImageName = page.cleanedImageName
                    inpaintRevision = page.inpaintRevision
                    inpaintingModeUsed = page.inpaintingModeUsed
                    inpaintStatus = StageStatus.READY
                    errorMessage = null
                }
            }
            val accepted = result is ChapterTranslationStore.PatchResult.Accepted
            // Disk fake owns no lazy borrower.
            arg<CleanedOwner>(6).registerReceipt(CompletableDeferred(accepted))
            accepted
        }

        coEvery {
            cleanedPublicationMock.persistOnnxCleanedImage(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val result = arg<OnnxPhaseResult>(4)
            val attempt = arg<AttemptAuthority?>(6)
            if (attempt != null) {
                val enrolled = cleanedPublicationMock.enrollCleanedPersistence(
                    arg(0), arg(1), arg(2), arg(3), result.store, result.pageTranslation,
                    arg(5), attempt, arg(7),
                )
                if (enrolled) result.copy(commitPrecondition = attempt.precondition) else null
            } else {
                persistManualCleanedResult(result)
            }
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
        return result.copy(commitPrecondition = legacyStorePrecondition)
    }

    /** Tears the graph down without leaving scopes or pool state behind. */
    fun close() {
        runCatching { cancelAndJoinRunningJobs() }
        if (graphicsShimsInstalled) runCatching { uninstallGraphicsShims() }
        runCatching { unstubChapterPages() }
        runCatching { scheduler.close() }
        runCatching { pipeline.close() }
        runCatching { engineDrainScope.cancel() }
        runCatching { managerScope.cancel() }
        runCatching { BitmapPool.releaseAll() }
    }

    /** Compact state context for reader-path failure assertions. */
    fun failureDiagnostics(pageKeys: List<String>): String {
        val pages = pageKeys.associateWith { pageKey ->
            store.state.value[pageKey]?.let { page ->
                "ocr=${page.ocrStatus}(${page.ocrError}), " +
                    "translation=${page.translationStatus}(${page.translationError}), " +
                    "inpaint=${page.inpaintStatus}(${page.inpaintError}), " +
                    "render=${page.renderStatus}(${page.renderError})"
            } ?: "missing"
        }
        return "chapterId=$CHAPTER_ID, pages=$pages"
    }

    /**
     * Paid-call count for [pageKey] across every transport
     * instance (the primary fake plus any translator the epoch retry rebuilt).
     */
    fun transportCallsFor(pageKey: String): Int =
        transportInstances.sumOf { it.callsFor(pageKey) }
}
