package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.EnvelopePolicySnapshot
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisCoverage
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.analysis.ValidatedEntity
import eu.kanade.translation.translator.analysis.ValidatedTerm
import eu.kanade.translation.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.providers.AiTranslator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T924 Stage 7 (D4, ST-14): the drained TRANSLATE tail — FINALIZE (serial
 * inpaint drain through the overlap scheduler, stranded-page reconciliation,
 * flush, retention), the run's FIRST/ONLY COMPLETE publication, and the
 * wave-2 F1 decideResume production semantics (OFF+COMPLETE ⇒ TreatAsFinished).
 */
class Stage7FinalizeCoordinatorTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (dispatch-test idioms, verbatim shapes).
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

    private fun ocrPage(pageKey: String, text: String) = PageTranslation(
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
    )

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

    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrPages += pageKey
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrPage(pageKey, "source-$pageKey"),
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 stage7 fake preflight ocr",
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

    /** Fake native inpaint lane through the scheduler's guarded identity path. */
    private inner class FakeOverlapInpaintLane(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
    ) : NativeLaneWorker {
        val inpainted = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null

        override suspend fun runInpaintStage(pageKey: String) {
            runInpaintStage(pageKey, null)
        }

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            inpainted += pageKey
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
                description = "t924 stage7 fake overlap inpaint",
            ) { page ->
                page!!.apply { inpaintStatus = StageStatus.READY }
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
                provenance = eu.kanade.translation.artifact.AnalyzerProvenance("fake", "fake-model", 1, 1, "sig"),
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

    private fun responseFor(
        chunk: TranslationContextChunk,
        omit: Set<String> = emptySet(),
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val results = request.orderedIds.filterNot(omit::contains).map { id ->
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

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: NativeLaneWorker,
        pages: List<PageKey>,
        runner: AnalysisChunkRunner,
        translator: FakeTranslator,
        overlapScheduler: OverlapScheduler,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
            flagProfilePipeline = true,
        ).copy(
            envelopePolicy = EnvelopePolicySnapshot(maxBlocks = 32, maxPages = 8),
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        flagProfilePipeline = true,
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = runner,
        textTranslator = translator,
        translationSublimitGate = BatchRequestSublimitGate(),
        overlapScheduler = overlapScheduler,
        renderJoin = null,
    )

    // ------------------------------------------------------------------
    // Tests.
    // ------------------------------------------------------------------

    @Test
    fun `drained run finalizes and publishes COMPLETE exactly once with the overlap inpaint drained`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val overlapLane = FakeOverlapInpaintLane(store, identities)
        val overlapScheduler = OverlapScheduler(
            store = store,
            nativeWorker = overlapLane,
            orderedPageKeys = pageKeys,
            batchWriteIdentities = identities,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            overlapScheduler,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Stage-7 terminal: the run COMPLETES (legacy completion semantics;
        // DISPLAY_READY redefinition is gate-7.8-gated OFF).
        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        outcome.needsTranslation shouldBe emptyList()
        ChapterProfileBatchCoordinator.GATE_7_8_DISPLAY_READY_COMPLETION_ENABLED shouldBe false

        // Every page: translation committed (envelope) + inpaint committed
        // (serial drain through the overlap scheduler's lane).
        pageKeys.forEach { key ->
            val page = store.snapshot(key).page.shouldNotBeNull()
            page.translationStatus shouldBe StageStatus.READY
            page.inpaintStatus shouldBe StageStatus.READY
        }
        overlapLane.inpainted shouldContainExactly pageKeys
        overlapScheduler.counters.snapshot()["serialInpaintsExecuted"] shouldBe 3L

        // The run record is CLOSED as COMPLETE with the finalize counters —
        // the FIRST and ONLY COMPLETE publication of the run. (The gate-6.5
        // overlap counters ride the FINALIZE record — the 32-key
        // phaseCounters bound; the live snapshot is asserted above.)
        val record = runRecord(store)
        record.state shouldBe ChapterRunState.COMPLETE
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_FINALIZE] shouldBe 1
        record.phaseCounters["pagesTranslated"] shouldBe 3

        // Wave-2 F1: the OFF+COMPLETE resume decision tree now applies.
        ChapterProfileBatchCoordinator.decideResume(record, currentFlagOn = false)
            .shouldBeInstanceOf<ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.TreatAsFinished>()
        ChapterProfileBatchCoordinator.decideResume(record, currentFlagOn = true)
            .shouldBeInstanceOf<ChapterProfileBatchCoordinator.FlaggedRunResumeDecision.RunFlaggedPath>()
        val finishedOutcome = ChapterProfileBatchCoordinator.resumeCompletedOutcome(
            record = record,
            currentFlagOn = false,
            orderedPageKeys = pageKeys.toSet(),
        ).shouldNotBeNull()
        finishedOutcome.status shouldBe BatchPass1Status.COMPLETED
        ChapterProfileBatchCoordinator.resumeCompletedOutcome(
            record = null,
            currentFlagOn = false,
            orderedPageKeys = pageKeys.toSet(),
        ) shouldBe null
    }

    @Test
    fun `a block that never returns is a typed resumable pause, never a drained COMPLETE (ST-12 gap)`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val overlapScheduler = OverlapScheduler(
            store = store,
            nativeWorker = FakeOverlapInpaintLane(store, identities),
            orderedPageKeys = pageKeys,
            batchWriteIdentities = identities,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        // p2's block never comes back: after the full repair budget the page
        // is still partially covered — an UNRESOLVED GAP (ST-12). The run
        // PAUSES for resume; it never drains into FINALIZE with untranslated
        // planned work, and the gap page is never marked stranded.
        val translator = FakeTranslator { _, chunk -> responseFor(chunk, omit = setOf("p2_b1")) }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            overlapScheduler,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.failure.shouldNotBeNull()
        val record = runRecord(store)
        record.state shouldBe ChapterRunState.TRANSLATE

        // The ambiguous-partial response is discarded whole: NOTHING commits
        // (p1/p3's valid blocks ride a response the classifier rejects), and
        // all pages stay PENDING — resumable, never silently failed.
        store.snapshot("p1").page.shouldNotBeNull().translationStatus shouldBe StageStatus.PENDING
        store.snapshot("p3").page.shouldNotBeNull().translationStatus shouldBe StageStatus.PENDING
        store.snapshot("p2").page.shouldNotBeNull().translationStatus shouldBe StageStatus.PENDING
        store.durableFailuresSnapshot().containsKey("p2:TRANSLATION") shouldBe false
    }
}
