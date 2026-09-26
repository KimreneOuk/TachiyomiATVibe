package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.persistence.artifact.AnalysisChunkCoverage
import eu.kanade.translation.persistence.artifact.AnalysisChunkResult
import eu.kanade.translation.persistence.artifact.AnalysisChunkStatus
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.StagePatchResult
import eu.kanade.translation.pipeline.ocrBlockFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisCoverage
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.analysis.GlossaryEntry
import eu.kanade.translation.translator.analysis.GlossaryEntryKind
import eu.kanade.translation.translator.analysis.GlossarySynthesisOutcome
import eu.kanade.translation.translator.analysis.GlossarySynthesizer
import eu.kanade.translation.translator.analysis.ValidatedEntity
import eu.kanade.translation.translator.analysis.ValidatedTerm
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 *  Stage 5 slices A+B: the coordinator's analysis phase
 * through profile freeze. After a COMPLETE OCR preflight the run publishes
 * ANALYSIS_PLAN, executes the planned chunks through the typed runner,
 * persists validated results via the crash-safe sidecar-then-pointer
 * transaction, then synthesizes the glossary from the durable chunk
 * summaries and freezes the profile  — and STOPS
 * (PAUSED; envelope/translation are Stage 6). Resume reuses the checkpointed
 * preflight (no re-OCR) and skips the persisted chunk prefix (no re-send).
 */
class ChapterAnalysisPhaseCoordinatorTest {

    @TempDir
    lateinit var mangaDir: File

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

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: FakePreflightOcrWorker,
        pages: List<PageKey>,
        runner: AnalysisChunkRunner?,
        sourceShaOverride: Map<String, String> = emptyMap(),
        synthesizer: GlossarySynthesizer = FakeGlossarySynthesizer(),
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        ),
        orderedSourcePairs = pages.map { (pageKey, _) ->
            pageKey to (sourceShaOverride[pageKey] ?: hex64("source-$pageKey"))
        },
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = runner,
        glossarySynthesizer = synthesizer,
    )

    /** M1-idiom OCR lane: lease, merge under the token, hand the identity back. */
    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
        private val textByPage: Map<String, String> = emptyMap(),
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrPages += pageKey
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            val resultPage = ocrPage(pageKey, textByPage[pageKey] ?: "source-$pageKey")
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = resultPage,
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

    /**
     * Typed fake analyzer. `extracts` false returns a clean-but-empty
     * MISSING_ONLY response; `pauseAfter` makes the run stop at the next
     * chunk. Responses are built from the REAL evidence universe the
     * coordinator handed over (wire identities + source texts).
     */
    private inner class FakeAnalyzer(
        private val extracts: Boolean = true,
        var pauseAfter: Int = Int.MAX_VALUE,
    ) : AnalysisChunkRunner {
        val executedOrdinals = mutableListOf<Int>()
        val chunkIdsByOrdinal = mutableMapOf<Int, String>()

        override suspend fun executeChunk(
            chunk: PlannedAnalysisChunk,
            identity: AnalysisRunIdentity,
            evidence: AnalysisEvidenceTexts,
        ): AnalysisChunkRunOutcome {
            executedOrdinals += chunk.chunkOrdinal
            chunkIdsByOrdinal[chunk.chunkOrdinal] = chunk.chunkId
            if (chunk.chunkOrdinal > pauseAfter) {
                return AnalysisChunkRunOutcome.Paused(
                    failure = ProviderFailure(
                        kind = ProviderFailureKind.PROTOCOL,
                        retryability = ProviderFailureRetryability.PAUSE,
                        retryAfterAtEpochMs = 60_000L,
                        safeSummary = "analysis transport rate limited",
                    ),
                    reason = "T924 analysis paused at chunk ${chunk.chunkId}: rate limited",
                )
            }
            val firstStoragePage = chunk.contributingPageKeys.first()
            val wirePage = evidence.wirePageKeyByStorageKey[firstStoragePage].shouldNotBeNull()
            val wireBlock = evidence.blockIdsByPage[wirePage].shouldNotBeNull().first()
            val sourceText = evidence.textByBlockId[wireBlock].shouldNotBeNull()
            val response = AnalysisResponseValidator.ValidatedAnalysisResponse(
                chunkId = chunk.chunkId,
                terms = if (extracts) {
                    listOf(ValidatedTerm("t001", "剣", "sword", emptyList(), "TERM"))
                } else {
                    emptyList()
                },
                entities = if (extracts) {
                    listOf(
                        ValidatedEntity("e001", listOf("カイル"), "カイル", "Kail", emptyList(), emptyList()),
                    )
                } else {
                    emptyList()
                },
                scenes = emptyList(),
                narrativeSummary = if (extracts) "An opening journey." else null,
                conflictNotes = emptyList(),
                evidenceRefs = listOf(EvidenceRef(wirePage, wireBlock, hex64(sourceText))),
            )
            return AnalysisChunkRunOutcome.Completed(
                response = response,
                coverage = AnalysisCoverage(
                    kind = if (extracts) AnalysisCoverageKind.COMPLETE else AnalysisCoverageKind.MISSING_ONLY,
                    reasons = emptyList(),
                ),
                droppedAuthorityKeys = emptyList(),
                provenance = AnalyzerProvenance(
                    providerId = "fake",
                    modelId = "fake-model",
                    promptVersion = 1,
                    analysisSchemaVersion = 1,
                    credentialFingerprint = "sig",
                ),
            )
        }
    }

    /**
     * Summary-glossary fake (Director redesign): records each summary list
     * handed to the one-shot synthesis (one call per reconcile) and answers
     * with a fixed character/place identity sheet.
     */
    private inner class FakeGlossarySynthesizer(
        private val entries: List<GlossaryEntry> = listOf(
            GlossaryEntry(GlossaryEntryKind.CHARACTER, "カイル", "Kail", listOf("kyle")),
            GlossaryEntry(GlossaryEntryKind.PLACE, "王都", "royal capital"),
        ),
    ) : GlossarySynthesizer {
        val receivedSummaries = mutableListOf<List<String>>()

        override suspend fun synthesize(
            sourceLanguage: String,
            targetLanguage: String,
            summaries: List<String>,
        ): GlossarySynthesisOutcome {
            receivedSummaries += summaries
            return GlossarySynthesisOutcome.Glossary(entries)
        }
    }

    private fun runRecord(store: ChapterTranslationStore): ChapterRunRecord {
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        val pointer = manifest.activeRun.shouldNotBeNull()
        return (artifactStore().readRunRecord(pointer) as ChapterArtifactEngine.RunRecordRead.Usable).record
    }

    private fun readChunk(pointer: SidecarPointer): AnalysisChunkResult =
        when (
            val read = artifactStore().readSidecarDocument(
                pointer = pointer,
                serializer = AnalysisChunkResult.serializer(),
                currentSchemaVersion = AnalysisChunkResult.SCHEMA_VERSION,
                expectedKind = AnalysisChunkResult.KIND,
                schemaVersionOf = { it.schemaVersion },
                kindOf = { it.kind },
                isValid = { it.isSemanticallyValid },
            )
        ) {
            is SidecarRead.Usable<*> -> read.document as AnalysisChunkResult
            else -> throw IllegalStateException("chunk sidecar unreadable: $read")
        }

    @Test
    fun `analysis executes after complete preflight and stops after chunks`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val worker = FakePreflightOcrWorker(store)
        val analyzer = FakeAnalyzer()
        val synthesizer = FakeGlossarySynthesizer()
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

        val outcome = coordinator(store, worker, pages, analyzer, synthesizer = synthesizer)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Slice B: the durable chunk set flows into reconcile + freeze.
        // Stage-6 slice A: the run CONTINUES into the envelope phase and ends
        // PAUSED at the typed TRANSLATE CONFIGURATION gate (no transport
        // wired in this test) — never COMPLETED.
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON
        outcome.needsTranslation shouldBe emptyList()
        outcome.completedPageKeys shouldBe pageKeys.toSet()

        // One chunk (3 pages fit one window), executed exactly once.
        analyzer.executedOrdinals shouldContainExactly listOf(0)
        worker.ocrPages shouldContainExactly pageKeys

        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.analysisChunks.shouldHaveSize(1)
        val chunk = readChunk(manifest.analysisChunks.single())
        chunk.chunkOrdinal shouldBe 0
        chunk.chunkId shouldBe analyzer.chunkIdsByOrdinal.getValue(0)
        chunk.status shouldBe AnalysisChunkStatus.VALID
        // Wave-4 F-W4-3: the DR-A coverage classification is durable.
        chunk.coverage shouldBe AnalysisChunkCoverage.COMPLETE
        // Evidence page keys were translated to PERSISTED page keys at
        // publication — never wire `p<N>` identities in durable bytes.
        chunk.evidenceRefs.single().pageKey shouldBe "p1"
        chunk.terms.single().canonicalTarget shouldBe "sword"

        val record = runRecord(store)
        record.state shouldBe ChapterRunState.TRANSLATE
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_ANALYSIS_PLAN] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_PENDING] shouldBe 0
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_RECONCILED] shouldBe 1
        record.ocrCorpusFingerprint.shouldNotBeNull()
        //  the frozen profile pointer rides the PROFILE_FROZEN record.
        record.profilePointer.shouldNotBeNull().version shouldBe 1
        // Summary-glossary redesign: ONE synthesis call received exactly the
        // durable chunk summaries (the persisted chunk records' structured
        // fields are dead weight for the profile — the sheet is synthesized).
        synthesizer.receivedSummaries shouldContainExactly listOf(listOf("An opening journey."))
    }

    @Test
    fun `missing_only chunk commits its complete subset and never blocks the chapter`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val worker = FakePreflightOcrWorker(store)
        val analyzer = FakeAnalyzer(extracts = false)
        val synthesizer = FakeGlossarySynthesizer()
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

        val outcome = coordinator(store, worker, pages, analyzer, synthesizer = synthesizer)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // DR-A Option 1: the independently complete empty subset committed;
        // the un-extracted remainder is pending and NEVER blocks the chapter.
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON
        artifactStore().readManifest().shouldNotBeNull().analysisChunks.shouldHaveSize(1)
        // Wave-4 F-W4-3: an empty-but-valid MISSING_ONLY chunk is durable as
        // MISSING_ONLY, so slice-B reconcile treats it as pending, not canon.
        readChunk(artifactStore().readManifest().shouldNotBeNull().analysisChunks.single())
            .coverage shouldBe AnalysisChunkCoverage.MISSING_ONLY
        val record = runRecord(store)
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_PENDING] shouldBe 1
        // Summary-glossary redesign: the summary-less chunk contributes
        // nothing — synthesis received an EMPTY summary list and the profile
        // still freezes (translation proceeds without the sheet).
        record.state shouldBe ChapterRunState.TRANSLATE
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_RECONCILED] shouldBe 0
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_CHUNKS_PENDING] shouldBe 0
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_PROFILE_FROZEN] shouldBe 1
        synthesizer.receivedSummaries shouldContainExactly listOf(emptyList())
    }

    @Test
    fun `resume reuses checkpoints and never re-sends the persisted chunk prefix`() = runTest {
        val store = lazyStore()
        // 17 pages -> two chunk windows (max 16 core pages): chunk 0 = 16
        // pages, chunk 1 = the last page with one overlap context page.
        val pageKeys = (1..17).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

        // Pass 1: chunk 0 commits, chunk 1 pauses on a typed provider failure.
        val analyzer1 = FakeAnalyzer(pauseAfter = 0)
        val paused = coordinator(store, FakePreflightOcrWorker(store), pages, analyzer1)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        paused.status shouldBe BatchPass1Status.PAUSED
        paused.failure.shouldNotBeNull().safeSummary shouldBe "analysis transport rate limited"
        paused.nextEligibleRetryAtEpochMs shouldBe 60_000L
        analyzer1.executedOrdinals shouldContainExactly listOf(0, 1)
        val interruptedRecord = runRecord(store)
        interruptedRecord.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL] shouldBe 2
        interruptedRecord.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE] shouldBe 1
        interruptedRecord.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_FAILURES] shouldBe 1
        artifactStore().readManifest().shouldNotBeNull().analysisChunks.shouldHaveSize(1)

        // ---- simulated process death: fresh stores over the SAME documents.
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)
        val analyzer2 = FakeAnalyzer()

        val resumed = coordinator(resumedStore, resumedWorker, pages, analyzer2)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // The checkpointed preflight was FULLY reused: zero re-OCR. The
        // persisted prefix (ordinal 0) was never re-sent.
        resumedWorker.ocrPages shouldBe emptyList()
        analyzer2.executedOrdinals shouldContainExactly listOf(1)
        resumed.status shouldBe BatchPass1Status.PAUSED
        resumed.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_NO_TRANSPORT_REASON

        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.analysisChunks.shouldHaveSize(2)
        readChunk(manifest.analysisChunks[0]).chunkOrdinal shouldBe 0
        readChunk(manifest.analysisChunks[1]).chunkOrdinal shouldBe 1
        // Chunk 1's overlap context page keyed by persisted identity
        // (overlap precedes the core page: the last page of chunk 0).
        readChunk(manifest.analysisChunks[1]).contextOverlapPageKeys shouldBe listOf("p16")
        val finalRecord = runRecord(resumedStore)
        finalRecord.runId shouldBe interruptedRecord.runId
        finalRecord.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_DONE] shouldBe 2
        finalRecord.state shouldBe ChapterRunState.TRANSLATE
        finalRecord.profilePointer.shouldNotBeNull().version shouldBe 1
    }

    @Test
    fun `cross-run corpus change pauses typed instead of skipping a stale chunk prefix`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

        // Pass 1: one chunk commits over the 3-page corpus.
        val analyzer1 = FakeAnalyzer()
        coordinator(store, FakePreflightOcrWorker(store), pages, analyzer1)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        val persistedChunkId = readChunk(artifactStore().readManifest().shouldNotBeNull().analysisChunks.single())
            .chunkId

        // ---- simulated process death + re-download: page 2's source changed.
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore, textByPage = mapOf("p2" to "source-v2-p2"))
        val analyzer2 = FakeAnalyzer()

        val resumed = coordinator(
            resumedStore,
            resumedWorker,
            pages,
            analyzer2,
            sourceShaOverride = mapOf("p2" to hex64("source-v2-p2")),
        ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Only the changed page re-OCR'd (checkpoint identity reuse for the
        // rest). The re-planned corpus differs from the persisted chunk's, so
        // the resume is a TYPED pause (wave-4 F-W4-1) — never a silent prefix
        // skip that would append onto a mixed-plan chunk list.
        resumedWorker.ocrPages shouldContainExactly listOf("p2")
        analyzer2.executedOrdinals shouldBe emptyList()
        resumed.status shouldBe BatchPass1Status.PAUSED
        resumed.reason shouldContain "analysis prefix stale"

        // The durable chunk list is untouched by the rejected resume.
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.analysisChunks.shouldHaveSize(1)
        readChunk(manifest.analysisChunks.single()).chunkId shouldBe persistedChunkId
    }

    @Test
    fun `terminal refusal pauses with the typed failure and persists nothing`() = runTest {
        val store = lazyStore()
        val pageKeys = (1..3).map { "p$it" }
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val refusal = AnalysisChunkRunner { chunk, _, _ ->
            AnalysisChunkRunOutcome.Refused(
                failure = ProviderFailure(
                    kind = ProviderFailureKind.REFUSAL,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "analysis refusal",
                ),
                reason = "T924 analysis refused chunk ${chunk.chunkId}: policy refusal",
            )
        }

        val outcome = coordinator(store, FakePreflightOcrWorker(store), pages, refusal)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.failure.shouldNotBeNull().kind shouldBe ProviderFailureKind.REFUSAL
        artifactStore().readManifest().shouldNotBeNull().analysisChunks.shouldHaveSize(0)
        runRecord(store).phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_FAILURES] shouldBe 1
    }
}
