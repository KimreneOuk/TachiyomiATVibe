package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.EnvelopePolicySnapshot
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisCoverage
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.analysis.GlossarySynthesizer
import eu.kanade.translation.translator.analysis.GlossarySynthesisOutcome
import eu.kanade.translation.translator.analysis.ValidatedEntity
import eu.kanade.translation.translator.analysis.ValidatedTerm
import eu.kanade.translation.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.providers.AiTranslator
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T934 display-tail drain: COMPLETE means "every page readable", not "every
 * ingredient done". The inpaint lane's render-terminal stamp only fires when
 * the page's translation was ALREADY terminal at inpaint time, so an
 * order-inverted page (inpaint committed before its envelope translation —
 * the decoupled-candidacy norm) reaches translate+inpaint-terminal with
 * renderStatus PENDING and no committed display bundle. FINALIZE must drain
 * that tail before publishing COMPLETE; a page the drain genuinely cannot
 * finish takes the typed terminal and the run completes as a warning.
 *
 * Harness: Stage7FinalizeCoordinatorTest idioms. The fake overlap lane
 * mirrors the real lane's publication substage (cleaned-image reference) but
 * NOT the in-lane render-terminal stamp — exactly the order-inverted shape
 * the drain owns. Unlike Stage 7's, this harness's pages DO reach the
 * committed-display promotion, so @BeforeEach seeds the cleaned companion
 * bytes and stubs the store's image probe (android.graphics is unavailable on
 * the JVM) — the display-base validation the promotion runs on every stamp.
 */
class DisplayTailDrainTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (Stage-7 finalize-test idioms).
    // ------------------------------------------------------------------

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String) = TranslationBlock(
        blockId = "b1",
        text = text,
        translation = "",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(
        pageKey: String,
        text: String,
        preInpainted: Boolean = false,
    ) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(block(text)),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    ).apply {
        if (preInpainted) {
            // The durable order-inverted shape: the cleaned artifact from an
            // earlier inpaint commit that ran BEFORE this page's translation.
            inpaintStatus = StageStatus.READY
            cleanedImageName = "$pageKey.cleaned.jpg"
        }
    }

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactStore =
        ChapterArtifactStore(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        fileCreator = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    // The promotion the drain's stamp write triggers validates the cleaned
    // display base against the REAL companion file on the store's document IO
    // (displayBaseIsValid over the store-injected probe) — the same fixture
    // fidelity the coexistence harness documents for every display-commit
    // fixture (D10's freshStore gap is exactly its absence). Without the
    // seeded companion bytes and a JVM-decodable probe stub, every stamp write
    // is rejected ARTIFACT_PUBLICATION_FAILED and the drain has nothing to
    // drain. The stub answers the pages' source identity (imgWidth=100,
    // imgHeight=160 in ocrPage).

    private var probeBeforeDisplayBaseFixture: CleanedImageProbe? = null

    @BeforeEach
    fun seedDisplayBaseFixtures() {
        val layout = ChapterArtifactLayout("Chapter 1")
        // Superset of every test's page set.
        listOf("p1", "p2", "p3", "p4", "p5").forEach { key ->
            val companion = File(mangaDir, layout.legacyCompanionImageFile("$key.cleaned.jpg"))
            companion.parentFile.mkdirs()
            companion.writeBytes(ByteArray(1))
        }
        probeBeforeDisplayBaseFixture = ChapterTranslationStore.artifactImageProbe
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 160) }
    }

    @AfterEach
    fun restoreDisplayBaseProbe() {
        ChapterTranslationStore.artifactImageProbe =
            probeBeforeDisplayBaseFixture ?: BitmapFactoryCleanedImageProbe
        probeBeforeDisplayBaseFixture = null
    }

    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
        private val preInpainted: Set<String> = emptySet(),
    ) : NativeLaneWorker {
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrPage(pageKey, "source-$pageKey", pageKey in preInpainted),
                    expectedLeaseToken = lease.token,
                ),
                description = "t934 display tail fake preflight ocr",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
            val after = store.snapshot(pageKey)
            return OcrReadyPageRef(
                pageKey = pageKey,
                pageIndex = pageIndex,
                generation = after.generation,
                blockFingerprints = emptyList(),
                leaseToken = lease.token,
                candidateGenerationId = after.candidateGenerationId,
                dependencyFingerprint = after.dependencyFingerprint,
                artifactPageVersion = after.artifactPageVersion,
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {}
    }

    /**
     * Fake native inpaint lane through the scheduler's guarded identity path.
     * Publishes the cleaned-image reference (the real lane's publication
     * substage) but never stamps render-terminal — the in-lane stamp fires
     * only when translation was ALREADY terminal, which is exactly the ride-
     * along whose absence strands order-inverted pages.
     */
    private inner class FakeOverlapInpaintLane(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        private val events: MutableList<String>? = null,
    ) : NativeLaneWorker {
        val inpainted = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null

        override suspend fun runInpaintStage(pageKey: String) {
            runInpaintStage(pageKey, null)
        }

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            inpainted += pageKey
            events?.add("inpaint:$pageKey")
            val identity = identities[pageKey]
                ?: error("overlap scheduler must register the write identity for $pageKey")
            store.updatePageGuarded(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = identity.generation,
                    pageVersion = identity.pageVersion,
                    leaseToken = identity.leaseToken,
                    candidateGenerationId = identity.candidateGenerationId,
                    dependencyFingerprint = identity.dependencyFingerprint,
                    artifactPageVersion = identity.artifactPageVersion,
                ),
                description = "t934 display tail fake overlap inpaint",
            ) { page ->
                page!!.apply {
                    inpaintStatus = StageStatus.READY
                    if (cleanedImageName == null) cleanedImageName = "$pageKey.cleaned.jpg"
                }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
    }

    private inner class FakeAnalyzer : AnalysisChunkRunner {
        override suspend fun executeChunk(
            chunk: PlannedAnalysisChunk,
            identity: AnalysisRunIdentity,
            evidence: AnalysisEvidenceTexts,
        ): AnalysisChunkRunOutcome {
            val firstStoragePage = chunk.contributingPageKeys.first()
            val wirePage = evidence.wirePageKeyByStorageKey[firstStoragePage].shouldNotBeNull()
            val wireBlock = evidence.blockIdsByPage[wirePage].shouldNotBeNull().first()
            val sourceText = evidence.textByBlockId[wireBlock].shouldNotBeNull()
            val response = AnalysisResponseValidator.ValidatedAnalysisResponse(
                chunkId = chunk.chunkId,
                terms = listOf(ValidatedTerm("t001", "剣", "sword", emptyList(), "TERM")),
                entities = listOf(
                    ValidatedEntity("e001", listOf("カイル"), "カイル", "Kail", emptyList(), emptyList()),
                ),
                scenes = emptyList(),
                narrativeSummary = "An opening journey.",
                conflictNotes = emptyList(),
                evidenceRefs = listOf(EvidenceRef(wirePage, wireBlock, hex64(sourceText))),
            )
            return AnalysisChunkRunOutcome.Completed(
                response = response,
                coverage = AnalysisCoverage(AnalysisCoverageKind.COMPLETE, emptyList()),
                droppedAuthorityKeys = emptyList(),
                provenance = AnalyzerProvenance("fake", "fake-model", 1, 1, "sig"),
            )
        }
    }

    private class FakeTranslator(
        private val responder: suspend (callIndex: Int, chunk: TranslationContextChunk) -> ContextualTranslationBatch,
    ) : AiTranslator() {
        val requests = mutableListOf<TranslationContextChunk>()

        override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch {
            requests += chunk
            return responder(requests.size, chunk)
        }

        override suspend fun promptText(prompt: String): String = ""
    }

    private fun responseFor(chunk: TranslationContextChunk): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val results = request.orderedIds.map { id ->
            ContextualTranslationResult(
                id = id,
                targetKey = request.idMap[id],
                text = "translated-$id",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        }
        return ContextualRequestBuilder.toBatch(request, results)
    }

    private fun runRecord(store: ChapterTranslationStore): eu.kanade.translation.artifact.ChapterRunRecord {
        val artifact = artifactStore()
        val pointer = artifact.readManifest().shouldNotBeNull().activeRun.shouldNotBeNull()
        return (artifact.readRunRecord(pointer) as ChapterArtifactStore.RunRecordRead.Usable).record
    }

    private val emptyGlossarySynthesizer = GlossarySynthesizer { _, _, _ ->
        GlossarySynthesisOutcome.Glossary(emptyList())
    }

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: NativeLaneWorker,
        pages: List<PageKey>,
        translator: FakeTranslator,
        overlapScheduler: OverlapScheduler,
        gate: BatchRequestSublimitGate = BatchRequestSublimitGate(),
        maxPagesPerEnvelope: Int = 8,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        ).copy(
            envelopePolicy = EnvelopePolicySnapshot(maxBlocks = 32, maxPages = maxPagesPerEnvelope),
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = FakeAnalyzer(),
        glossarySynthesizer = emptyGlossarySynthesizer,
        textTranslator = translator,
        translationSublimitGate = gate,
        overlapScheduler = overlapScheduler,
        renderJoin = null,
    )

    private fun scheduler(
        store: ChapterTranslationStore,
        pageKeys: List<String>,
        events: MutableList<String>? = null,
    ): Pair<OverlapScheduler, ConcurrentHashMap<String, BatchWriteIdentity>> {
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val scheduler = OverlapScheduler(
            store = store,
            nativeWorker = FakeOverlapInpaintLane(store, identities, events),
            orderedPageKeys = pageKeys,
            batchWriteIdentities = identities,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        return scheduler to identities
    }

    /** Pages that are display-committed: rendered result + promoted bundle. */
    private suspend fun displayReadyCount(store: ChapterTranslationStore, pageKeys: List<String>): Int =
        pageKeys.count { pageKey ->
            val page = store.snapshot(pageKey).page
            page != null && page.hasRenderedResult && store.committedDisplayPage(pageKey) != null
        }

    // ------------------------------------------------------------------
    // Tests.
    // ------------------------------------------------------------------

    @Test
    fun `an order-inverted display tail is committed by finalize and COMPLETE carries every page display-ready`() =
        runTest {
            val store = lazyStore()
            val pageKeys = (1..3).map { "p$it" }
            store.preRegisterPages(pageKeys)
            val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
            // p2 is order-inverted: its inpaint committed BEFORE its envelope
            // translation (seeded inpaint-ready at OCR), so the in-lane stamp
            // can never fire for it — the exact frozen-tail shape of the
            // 2026-09-17 run (52 pages work-complete, never display-ready).
            val (overlapScheduler, _) = scheduler(store, pageKeys)
            val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

            val outcome = coordinator(
                store,
                FakePreflightOcrWorker(store, preInpainted = setOf("p2")),
                pages,
                translator,
                overlapScheduler,
            ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

            outcome.status shouldBe BatchPass1Status.COMPLETED
            outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
            val record = runRecord(store)
            record.state shouldBe ChapterRunState.COMPLETE
            record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE] shouldBe 1
            // The tail was drained to zero: nothing typed-failed, nothing stranded.
            record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_DISPLAY_TAIL_FAILED] shouldBe 0
            record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_STRANDED_RECONCILED] shouldBe 0

            // Every work-complete page is display-committed: translate + clean
            // + the render-terminal stamp (compose tail) + promotion.
            pageKeys.forEach { key ->
                val page = store.snapshot(key).page.shouldNotBeNull()
                page.translationStatus shouldBe StageStatus.READY
                page.inpaintStatus shouldBe StageStatus.READY
                page.renderStatus shouldBe StageStatus.READY
                page.hasRenderedResult shouldBe true
            }
            displayReadyCount(store, pageKeys) shouldBe pageKeys.size
        }

    @Test
    fun `a display tail page the drain cannot finish takes the typed terminal and the run completes as a warning`() =
        runTest {
            val store = lazyStore()
            val pageKeys = listOf("p1", "p2")
            store.preRegisterPages(pageKeys)
            val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
            val (overlapScheduler, _) = scheduler(store, pageKeys)
            val translator = FakeTranslator { callIndex, chunk ->
                // Once p1's translation committed (second envelope = p2), a
                // MANUAL reader lane owns p1's Render stage: the BATCH stamp
                // can never acquire it this run (never preempted).
                if (callIndex == 2) {
                    store.tryAcquirePageStageLease("p1", PageStage.Render, PageWriteOrigin.MANUAL)
                }
                responseFor(chunk)
            }

            val outcome = coordinator(
                store,
                FakePreflightOcrWorker(store, preInpainted = setOf("p1")),
                pages,
                translator,
                overlapScheduler,
                maxPagesPerEnvelope = 1,
            ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

            // The run still completes — the un-composable page is reconciled,
            // never stranded, never a pause.
            outcome.status shouldBe BatchPass1Status.COMPLETED
            val record = runRecord(store)
            record.state shouldBe ChapterRunState.COMPLETE
            record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_DISPLAY_TAIL_FAILED] shouldBe 1
            record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_STRANDED_RECONCILED] shouldBe 0

            // p1: work intact (translation/inpaint terminal), display honestly
            // FAILED with the specific typed carrier + reason.
            val p1 = store.snapshot("p1").page.shouldNotBeNull()
            p1.translationStatus shouldBe StageStatus.READY
            p1.inpaintStatus shouldBe StageStatus.READY
            p1.renderStatus shouldBe StageStatus.FAILED
            // errorMessage is a body var (erased by detachedCopy) whose setter
            // mirrors into the stage-error fields; activeError is the durable
            // read surface that survives store snapshots.
            p1.activeError shouldContain "display commit did not land at FINALIZE"
            val failure = store.durableFailure("p1", ArtifactStage.LAYOUT).shouldNotBeNull()
            failure.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
            failure.category shouldBe FailureCategory.TRANSIENT
            failure.lastFailureMessage shouldContain "display commit did not land at FINALIZE"

            // p2 (healthy) is display-committed; the reconciled outcome carries
            // the run as Ready (Warnings), not a clean TRANSLATED.
            val p2 = store.snapshot("p2").page.shouldNotBeNull()
            p2.hasRenderedResult shouldBe true
            displayReadyCount(store, pageKeys) shouldBe 1
            val reconciliation = BatchProgressReconciler.reconcileFlaggedCompleted(
                pageMap = store.state.value,
                orderedKeys = pageKeys,
                activeGeneration = store.currentGeneration,
            )
            reconciliation.chapterStatus shouldBe Translation.State.READY_WITH_WARNINGS
        }

    @Test
    fun `the commit settle nudge drains a slot-deferred page before the next window opens`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val events = mutableListOf<String>()
        val (overlapScheduler, _) = scheduler(store, pageKeys, events)
        // One page per envelope: p1's write slot is held for the whole first
        // dispatch→commit, so window 1's drain defers it. The commit settle
        // nudge must wake the lane so p1 inpaints BEFORE the second window
        // opens (the executor holds its pages' slots until the commit settles,
        // so the settle is the earliest a freed page is claimable).
        val recordingGate = object : BatchRequestSublimitGate() {
            private var dispatches = 0
            override suspend fun <T> executeBatch(
                metadata: ProviderRequestMetadata,
                block: suspend () -> T,
            ): T {
                events += "dispatch-start:${++dispatches}"
                return super.executeBatch(metadata, block)
            }
        }
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            translator,
            overlapScheduler,
            gate = recordingGate,
            maxPagesPerEnvelope = 1,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED

        // All three inpaints happened on the overlap lane; p1's (slot-deferred
        // during window 1) ran on the commit-settle nudge, BEFORE window 2.
        events shouldContain "inpaint:p1"
        events shouldContain "dispatch-start:2"
        events.indexOf("inpaint:p1").shouldBeLessThan(events.indexOf("dispatch-start:2"))
        overlapScheduler.counters.snapshot()["overlapInpaintsExecuted"] shouldBe 3L
        overlapScheduler.counters.snapshot()["serialInpaintsExecuted"] shouldBe 0L
        displayReadyCount(store, pageKeys) shouldBe pageKeys.size
    }
}
