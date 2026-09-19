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
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.EnvelopePolicySnapshot
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * T924 Stage-6 slice A: the serial envelope dispatch behind FF-01
 * (T924-ST-11/12, TX-21 revalidation + deterministic suffix re-plan,
 * TX-20 provenance commits, DR-A Option 1 retention, crash-resumable
 * progress, one-envelope-in-flight, Batch sub-limit riding).
 */
class ProfileEnvelopeDispatchTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (freeze-coordinator idioms + the typed translator seam).
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
        inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        fileCreator = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    /**
     * Every test builds its own ISOLATED Batch sub-limit gate so tests never
     * contend for the process-wide shared 15-RPM pool (the gate TYPE is the
     * seam; the default shared instance is pinned at the construction sites).
     */
    /** Summary-glossary seam (Director redesign): an empty sheet freezes fine. */
    private val emptyGlossarySynthesizer = GlossarySynthesizer { _, _, _ ->
        GlossarySynthesisOutcome.Glossary(emptyList())
    }

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: NativeLaneWorker,
        pages: List<PageKey>,
        runner: AnalysisChunkRunner,
        translator: FakeTranslator?,
        maxPagesPerEnvelope: Int = 8,
        gate: BatchRequestSublimitGate = BatchRequestSublimitGate(),
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
            envelopePolicy = EnvelopePolicySnapshot(
                maxBlocks = 32,
                maxPages = maxPagesPerEnvelope,
            ),
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = runner,
        glossarySynthesizer = emptyGlossarySynthesizer,
        textTranslator = translator,
        translationSublimitGate = gate,
    )

    /** M1-idiom OCR lane: lease, merge under the token, hand the identity back. */
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
                description = "t924 fake preflight ocr",
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

    /** Typed fake analyzer: one entity + one term per chunk, COMPLETE. */
    private inner class FakeAnalyzer : AnalysisChunkRunner {
        val executedOrdinals = mutableListOf<Int>()

        override suspend fun executeChunk(
            chunk: PlannedAnalysisChunk,
            identity: AnalysisRunIdentity,
            evidence: AnalysisEvidenceTexts,
        ): AnalysisChunkRunOutcome {
            executedOrdinals += chunk.chunkOrdinal
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

    /**
     * The typed AI translator fake: rides the REAL adaptive retry controller
     * and the REAL strict request builder, so responses flow through the
     * production parser discipline before any commit.
     */
    private class FakeTranslator(
        private val responder: suspend (callIndex: Int, chunk: TranslationContextChunk) -> ContextualTranslationBatch,
    ) : AiTranslator() {
        val requests = mutableListOf<TranslationContextChunk>()
        private val inFlight = AtomicInteger()
        var maxObservedInFlight = 0
            private set

        override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch {
            requests += chunk
            val now = inFlight.incrementAndGet()
            if (now > maxObservedInFlight) maxObservedInFlight = now
            try {
                return responder(requests.size, chunk)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun promptText(prompt: String): String = ""
    }

    private fun responseFor(
        chunk: TranslationContextChunk,
        omit: Set<String> = emptySet(),
        text: (String) -> String = { id -> "translated-$id" },
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
                text = text(id),
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        }
        return ContextualRequestBuilder.toBatch(request, results)
    }

    private fun requestIds(chunk: TranslationContextChunk): List<String> =
        ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        ).orderedIds

    private fun runCounters(store: ChapterTranslationStore): Pair<ChapterRunState, Map<String, Int>> {
        val artifact = artifactStore()
        val pointer = artifact.readManifest().shouldNotBeNull().activeRun.shouldNotBeNull()
        val record = (artifact.readRunRecord(pointer) as ChapterArtifactEngine.RunRecordRead.Usable).record
        return record.state to record.phaseCounters
    }

    private suspend fun userEdit(store: ChapterTranslationStore, pageKey: String) {
        val before = store.snapshot(pageKey)
        val page = before.page ?: return
        val block = page.blocks.single()
        store.patchBlock(
            pageKey = pageKey,
            blockIndex = 0,
            expected = ChapterTranslationStore.PatchPrecondition(
                generation = before.generation,
                pageVersion = before.pageVersion,
            ),
            expectedBlockFingerprint = block.stableFingerprint(),
            description = "t924 test user edit",
        ) { it.apply { userEditedAt = 123L; translation = "human" } }
            .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
    }

    // ------------------------------------------------------------------
    // Tests.
    // ------------------------------------------------------------------

    @Test
    fun `full dispatch commits every page through tx20 and pauses with the translate stop reason`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val outcome = coordinator(store, FakePreflightOcrWorker(store), pages, FakeAnalyzer(), translator)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Stage-7 terminal: the drained run FINALIZEs and completes under the
        // legacy completion semantics (gate-7.8 DISPLAY_READY stays OFF).
        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        outcome.needsTranslation shouldBe emptyList()

        // ONE envelope for the 3 single-block pages; ONE call in flight.
        translator.requests.size shouldBe 1
        translator.maxObservedInFlight shouldBe 1
        requestIds(translator.requests.single()) shouldContainExactly listOf("p0_b1", "p1_b1", "p2_b1")

        // Every page committed READY with its translation (TX-20 ladder +
        // provenance fields passed — otherwise the merge would reject).
        pageKeys.forEachIndexed { index, key ->
            val page = store.snapshot(key).page.shouldNotBeNull()
            page.translationStatus shouldBe StageStatus.READY
            page.blocks.single().translation shouldBe "translated-p${index}_b1"
        }

        // The plan sidecar + pointer are durable (SC-20).
        artifactStore().readManifest().shouldNotBeNull().envelopePlan.shouldNotBeNull()

        val (state, counters) = runCounters(store)
        // Stage 7: the drained run closed as COMPLETE.
        state shouldBe ChapterRunState.COMPLETE
        counters["envelopesTotal"] shouldBe 1
        counters["envelopesDone"] shouldBe 1
        counters["pagesTranslated"] shouldBe 3
        counters["envelopeFailures"] shouldBe 0
    }

    @Test
    fun `tx21 drift between envelopes triggers deterministic suffix re-plan and keeps committed history`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..17).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { call, chunk ->
            if (call == 1) {
                // External OCR-level change on a NOT-YET-dispatched page
                // while envelope 1 is in flight.
                mutateOcr(store, "p12", "changed-twelve")
            }
            responseFor(chunk)
        }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON

        // The committed prefix was NEVER touched by the re-plan.
        store.snapshot("p1").page.shouldNotBeNull().blocks.single().translation shouldBe "translated-p0_b1"
        store.snapshot("p8").page.shouldNotBeNull().blocks.single().translation shouldBe "translated-p7_b1"

        // p12 re-entered the plan with its NEW content and was translated.
        store.snapshot("p12").page.shouldNotBeNull().blocks.single().translation shouldBe "translated-p11_b1"
        store.snapshot("p17").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY

        // Exactly one suffix re-plan; all 17 pages translated exactly once.
        val (_, counters) = runCounters(store)
        counters["envelopeReplans"] shouldBe 1
        counters["pagesTranslated"] shouldBe 17
        translator.maxObservedInFlight shouldBe 1
    }

    @Test
    fun `tx21 user-edited block is skipped and never overwritten`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..17).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { call, chunk ->
            if (call == 1) {
                userEdit(store, "p12")
            }
            responseFor(chunk)
        }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON

        // The user edit is authoritative: never overwritten, never re-sent.
        val edited = store.snapshot("p12").page.shouldNotBeNull().blocks.single()
        edited.translation shouldBe "human"
        edited.userEditedAt shouldBe 123L
        runCounters(store).second["pagesTranslated"] shouldBe 16
    }

    @Test
    fun `all blocks user-edited before the resume means no translatable work`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        // First run freezes (no translator: typed CONFIGURATION pause), then
        // the user translates every block manually, then a FRESH run
        // (process-death shape) sees zero translatable work.
        coordinator(store, FakePreflightOcrWorker(store), pages, FakeAnalyzer(), null)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        pageKeys.forEach { key -> userEdit(store, key) }

        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val resumedAnalyzer = FakeAnalyzer()
        val outcome = coordinator(
            resumedStore,
            resumedWorker,
            pages,
            resumedAnalyzer,
            translator,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // ST-11 skip rule: no translatable work never reaches TRANSLATE —
        // typed PAUSED no-work terminal with ZERO provider calls and zero
        // re-OCR / re-analysis.
        resumedWorker.ocrPages shouldBe emptyList()
        resumedAnalyzer.executedOrdinals shouldBe emptyList()
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.ENVELOPE_NO_WORK_REASON
        translator.requests.size shouldBe 0
        runCounters(resumedStore).second[ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_WORK] shouldBe 1
    }

    @Test
    fun `protocol outcome commits fully covered pages and parks omitted block pages without pausing`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..17).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { call, chunk ->
            if (call == 1) {
                responseFor(chunk)
            } else {
                // Valid for exactly ONE block; the rest never arrive even
                // across the controller's allowed retry budget.
                val keep = requestIds(chunk).take(1).toSet()
                responseFor(chunk, omit = requestIds(chunk).toSet() - keep)
            }
        }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // T934 relaxed PROGRESS policy: envelope 2's protocol verdict no
        // longer discards the response — each retry round recovered exactly
        // ONE more block (whole + 2 missing-only), so pages 9-11 (their
        // blocks were returned) COMMIT, pages 12-16 PARK durably, and
        // envelope 3 still dispatches (page 17 commits): the run DRAINS
        // instead of pausing the batch.
        outcome.status shouldBe BatchPass1Status.COMPLETED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        val (_, counters) = runCounters(store)
        counters["pagesTranslated"] shouldBe 12
        counters["pagesParked"] shouldBe 5
        counters["envelopesDone"] shouldBe 3
        counters["envelopeFailures"] shouldBe 0

        (9..11).forEach { index ->
            store.snapshot("p$index").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        }
        store.snapshot("p17").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        (12..16).forEach { index ->
            val key = "p$index"
            val page = store.snapshot(key).page.shouldNotBeNull()
            page.translationStatus shouldBe StageStatus.FAILED
            page.blocks.single().translation shouldBe ""
            // Self-describing evidence: omitted block id + source char length.
            val failure = store.durableFailure(key).shouldNotBeNull()
            failure.stage shouldBe ArtifactStage.TRANSLATION
            failure.category shouldBe FailureCategory.PROTOCOL
            failure.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
            failure.envelopeId.shouldNotBeNull()
            failure.missingBlockIds shouldBe setOf("p${index - 1}_b1")
            failure.missingBlockCharLengths shouldBe mapOf("p${index - 1}_b1" to "source-$key".length)
        }
    }

    @Test
    fun `single protocol envelope commits two covered pages and parks the partially covered page`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // ONE envelope over all 3 pages; page 3's block is silently omitted
        // across the whole retry budget while pages 1-2 come back complete.
        val translator = FakeTranslator { _, chunk ->
            responseFor(chunk, omit = setOf("p2_b1"))
        }

        val outcome = coordinator(store, FakePreflightOcrWorker(store), pages, FakeAnalyzer(), translator)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // NOT a batch pause: the two fully covered pages committed, the
        // stubborn page parked durably, the run drained to COMPLETE.
        outcome.status shouldBe BatchPass1Status.COMPLETED
        val (state, counters) = runCounters(store)
        state shouldBe ChapterRunState.COMPLETE
        counters["pagesTranslated"] shouldBe 2
        counters["pagesParked"] shouldBe 1
        counters["envelopeFailures"] shouldBe 0

        store.snapshot("p1").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        store.snapshot("p2").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        val parked = store.snapshot("p3").page.shouldNotBeNull()
        parked.translationStatus shouldBe StageStatus.FAILED
        parked.blocks.single().translation shouldBe ""

        val failure = store.durableFailure("p3").shouldNotBeNull()
        failure.stage shouldBe ArtifactStage.TRANSLATION
        failure.category shouldBe FailureCategory.PROTOCOL
        failure.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        failure.nextEligibleRetryAtEpochMs shouldBe null
        failure.envelopeId.shouldNotBeNull()
        failure.missingBlockIds shouldBe setOf("p2_b1")
        failure.missingBlockCharLengths shouldBe mapOf("p2_b1" to "source-p3".length)
    }

    @Test
    fun `three consecutive zero coverage protocol envelopes trip the breaker and pause the batch`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..9).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        // Every 3-page envelope comes back with an EMPTY translation set:
        // zero fully-covered pages, every time (systematic provider garbage).
        val translator = FakeTranslator { _, chunk ->
            responseFor(chunk, omit = requestIds(chunk).toSet())
        }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            maxPagesPerEnvelope = 3,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // The zero-commit breaker restores the old pause after 3 consecutive
        // protocol envelopes without a single committed page.
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.failure.shouldNotBeNull().kind shouldBe ProviderFailureKind.PROTOCOL
        outcome.failure.shouldNotBeNull().retryability shouldBe ProviderFailureRetryability.PAUSE
        val (_, counters) = runCounters(store)
        counters["pagesTranslated"] shouldBe 0
        counters["pagesParked"] shouldBe 9
        counters["envelopeFailures"] shouldBe 1
        pageKeys.forEach { key ->
            store.snapshot(key).page.shouldNotBeNull().translationStatus shouldBe StageStatus.FAILED
        }
    }

    @Test
    fun `parked protocol pages replan and translate on the next run`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val firstTranslator = FakeTranslator { _, chunk ->
            responseFor(chunk, omit = setOf("p2_b1"))
        }
        coordinator(store, FakePreflightOcrWorker(store), pages, FakeAnalyzer(), firstTranslator)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        store.snapshot("p3").page.shouldNotBeNull().translationStatus shouldBe StageStatus.FAILED

        // ---- simulated process death: a fresh run over the SAME documents.
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val secondTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val resumed = coordinator(
            resumedStore,
            resumedWorker,
            pages,
            FakeAnalyzer(),
            secondTranslator,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // The parked page is NOT terminal for planning: it re-planned (its
        // block was re-sent — the ONLY block re-sent) and translated; the
        // previously committed pages were never re-sent.
        resumed.status shouldBe BatchPass1Status.COMPLETED
        resumedWorker.ocrPages shouldBe emptyList()
        val sentIds = secondTranslator.requests.flatMap(::requestIds).toSet()
        sentIds shouldBe setOf("p2_b1")
        pageKeys.forEach { key ->
            resumedStore.snapshot(key).page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        }
        runCounters(resumedStore).second["pagesTranslated"] shouldBe 1
    }

    @Test
    fun `terminal transport failure still commits the independently complete page`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..17).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { call, chunk ->
            when (call) {
                1 -> responseFor(chunk)
                2 -> {
                    // Whole request valid for page 9's single block only.
                    val keep = requestIds(chunk).take(1).toSet()
                    responseFor(chunk, omit = requestIds(chunk).toSet() - keep)
                }
                else -> throw ProviderFailureException(
                    ProviderFailure(
                        kind = ProviderFailureKind.AUTHENTICATION,
                        retryability = ProviderFailureRetryability.TERMINAL,
                        safeSummary = "invalid credentials",
                    ),
                )
            }
        }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // MISSING_ONLY retention: page 9 (fully covered before the terminal
        // failure) commits and advances; the rest stay pending; typed pause.
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.failure.shouldNotBeNull().kind shouldBe ProviderFailureKind.AUTHENTICATION
        runCounters(store).second["pagesTranslated"] shouldBe 9
        store.snapshot("p9").page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        store.snapshot("p10").page.shouldNotBeNull().translationStatus shouldBe StageStatus.PENDING
    }

    @Test
    fun `unchanged resume reuses the published envelope plan without republication (ST-11, wave-6 F-W6-2)`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..9).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

        // Run 1: envelope 1 fails terminally BEFORE any page commits — the
        // plan is durable, all pages stay pending.
        val failingTranslator = FakeTranslator { _, _ ->
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.AUTHENTICATION,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "invalid credentials",
                ),
            )
        }
        val first = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            failingTranslator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        first.status shouldBe BatchPass1Status.PAUSED
        runCounters(store).second["pagesTranslated"] shouldBe 0

        val pointer1 = artifactStore().readManifest().shouldNotBeNull().envelopePlan.shouldNotBeNull()
        val planFile = mangaDir.walkTopDown()
            .single { it.isFile && it.name == pointer1.fileName.substringAfterLast('/') }
        val planMtime = planFile.lastModified()
        val planBytes = planFile.readBytes()

        // ---- simulated process death with UNCHANGED state: the pending set
        // and all plan inputs are identical, so the ST-11 reuse branch must
        // skip republication entirely (same pointer, no file write) and the
        // run still drains.
        Thread.sleep(1_100) // exceed 1s-granularity filesystem clocks
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val workingTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val resumed = coordinator(
            resumedStore,
            resumedWorker,
            pages,
            FakeAnalyzer(),
            workingTranslator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        resumed.status shouldBe BatchPass1Status.COMPLETED
        resumed.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        // Zero re-OCR through the reuse path.
        resumedWorker.ocrPages shouldBe emptyList()
        // The published plan was REUSED, never rewritten.
        val pointer2 = artifactStore().readManifest().shouldNotBeNull().envelopePlan.shouldNotBeNull()
        pointer2.contentFingerprint shouldBe pointer1.contentFingerprint
        planFile.lastModified() shouldBe planMtime
        planFile.readBytes() shouldBe planBytes
        runCounters(resumedStore).second["pagesTranslated"] shouldBe 9
    }

    @Test
    fun `structural refusal discards the response and pauses terminal`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { _, chunk ->
            responseFor(chunk, text = { "I cannot provide a translation for this content." })
        }

        val outcome = coordinator(store, FakePreflightOcrWorker(store), pages, FakeAnalyzer(), translator)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.failure.shouldNotBeNull().kind shouldBe ProviderFailureKind.REFUSAL
        outcome.failure.shouldNotBeNull().retryability shouldBe ProviderFailureRetryability.TERMINAL
        runCounters(store).second["pagesTranslated"] shouldBe 0
        store.snapshot("p1").page.shouldNotBeNull().blocks.single().translation shouldBe ""
    }

    @Test
    fun `process death resume never re-translates committed pages and never re-ocres`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..17).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val firstTranslator = FakeTranslator { call, chunk ->
            if (call == 1) {
                responseFor(chunk)
            } else {
                throw ProviderFailureException(
                    ProviderFailure(
                        kind = ProviderFailureKind.AUTHENTICATION,
                        retryability = ProviderFailureRetryability.TERMINAL,
                        safeSummary = "invalid credentials",
                    ),
                )
            }
        }
        coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            firstTranslator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        runCounters(store).second["pagesTranslated"] shouldBe 8

        // ---- simulated process death: fresh stores over the SAME documents.
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val resumedAnalyzer = FakeAnalyzer()
        val secondTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val resumed = coordinator(
            resumedStore,
            resumedWorker,
            pages,
            resumedAnalyzer,
            secondTranslator,
            maxPagesPerEnvelope = 8,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Zero re-OCR (checkpoint reuse), zero re-analysis (frozen-profile
        // reuse), and the committed 8 pages are NEVER re-sent.
        resumedWorker.ocrPages shouldBe emptyList()
        resumedAnalyzer.executedOrdinals shouldBe emptyList()
        resumed.status shouldBe BatchPass1Status.COMPLETED
        resumed.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
        val sentIds = secondTranslator.requests.flatMap(::requestIds).toSet()
        (0..7).forEach { index -> sentIds.contains("p${index}_b1") shouldBe false }
        (8..16).forEach { index -> sentIds.contains("p${index}_b1") shouldBe true }
        runCounters(resumedStore).second["pagesTranslated"] shouldBe 9
        // All 17 pages end READY — 8 from the dead process, 9 from the resume.
        pageKeys.forEach { key ->
            resumedStore.snapshot(key).page.shouldNotBeNull().translationStatus shouldBe StageStatus.READY
        }
    }

    @Test
    fun `translation envelopes ride the batch sub-limit gate`() = runTest {
        val store = lazyStore()
        // 17 single-page envelopes (maxPages=1): one MORE than the 15-RPM
        // Batch sub-limit window allows, so the 16th admission must defer.
        val pageKeys = (1..17).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val translator = FakeTranslator { _, chunk -> responseFor(chunk) }

        val outcome = coordinator(
            store,
            FakePreflightOcrWorker(store),
            pages,
            FakeAnalyzer(),
            translator,
            maxPagesPerEnvelope = 1,
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // The 16th envelope is deferred BY THE GATE before any provider call:
        // exactly 15 envelopes passed, 15 pages committed, typed pause.
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.failure.shouldNotBeNull().kind shouldBe ProviderFailureKind.QUOTA_EXHAUSTED
        runCounters(store).second["pagesTranslated"] shouldBe 15
        translator.requests.size shouldBe 15
    }

    // ------------------------------------------------------------------
    // helpers used inside responder lambdas.
    // ------------------------------------------------------------------

    private suspend fun mutateOcr(store: ChapterTranslationStore, pageKey: String, newText: String) {
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        try {
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrPage(pageKey, newText),
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 test drift mutation",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        } finally {
            store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        }
    }
}
