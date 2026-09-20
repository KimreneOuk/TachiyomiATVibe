package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.EnvelopePolicySnapshot
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.ocrBlockFingerprints
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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T924-ST-14 resume (wave-7c review F-2): a re-dispatch over a durable record
 * already past TRANSLATE never steps the run record BACKWARD to RUN_SNAPSHOT.
 * A FINALIZE record resumes the idempotent finalize drain (zero re-OCR,
 * re-analysis, re-translation); a COMPLETE record is an idempotent finished
 * outcome with zero work and no new record publication.
 */
class Stage7FinalizeResumeCoordinatorTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (Stage7FinalizeCoordinatorTest idioms).
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
                description = "t924 stage7 resume fake preflight ocr",
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
                description = "t924 stage7 resume fake overlap inpaint",
            ) { page ->
                page!!.apply { inpaintStatus = StageStatus.READY }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
    }

    /**
     * The crash seam: performs the REAL guarded inpaint, but the moment the
     * durable run record reads FINALIZE it dies with [CancellationException] —
     * a process death AFTER the FINALIZE record is durable but BEFORE the
     * COMPLETE publication (the drainSerial inpaint is the first finalize-
     * window work under the test dispatcher).
     */
    private inner class FinalizeWindowKillLane(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
    ) : NativeLaneWorker {
        val inpainted = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null

        override suspend fun runInpaintStage(pageKey: String) {
            runInpaintStage(pageKey, null)
        }

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            if (durableRunRecord(store)?.state == ChapterRunState.FINALIZE) {
                throw CancellationException("t924 test: process death in the finalize window")
            }
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
                description = "t924 stage7 resume kill lane inpaint",
            ) { page ->
                page!!.apply { inpaintStatus = StageStatus.READY }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
    }

    private inner class CountingAnalyzer : AnalysisChunkRunner {
        val chunks = AtomicInteger(0)

        override suspend fun executeChunk(
            chunk: PlannedAnalysisChunk,
            identity: AnalysisRunIdentity,
            evidence: AnalysisEvidenceTexts,
        ): AnalysisChunkRunOutcome {
            chunks.incrementAndGet()
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

    private fun durableRunRecord(store: ChapterTranslationStore): ChapterRunRecord? {
        val artifact = artifactStore()
        val pointer = artifact.readManifest().shouldNotBeNull().activeRun ?: return null
        return (artifact.readRunRecord(pointer) as? ChapterArtifactEngine.RunRecordRead.Usable)?.record
    }

    private fun activeRunPointer(store: ChapterTranslationStore): SidecarPointer =
        artifactStore().readManifest().shouldNotBeNull().activeRun.shouldNotBeNull()

    /** Summary-glossary seam (Director redesign): an empty sheet freezes fine. */
    private val emptyGlossarySynthesizer = GlossarySynthesizer { _, _, _ ->
        GlossarySynthesisOutcome.Glossary(emptyList())
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
        ).copy(
            envelopePolicy = EnvelopePolicySnapshot(maxBlocks = 32, maxPages = 8),
        ),
        orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        analysisChunkRunner = runner,
        glossarySynthesizer = emptyGlossarySynthesizer,
        textTranslator = translator,
        translationSublimitGate = BatchRequestSublimitGate(),
        overlapScheduler = overlapScheduler,
        renderJoin = null,
    )

    private fun newScheduler(
        store: ChapterTranslationStore,
        pageKeys: List<String>,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        lane: NativeLaneWorker,
    ): OverlapScheduler = OverlapScheduler(
        store = store,
        nativeWorker = lane,
        orderedPageKeys = pageKeys,
        batchWriteIdentities = identities,
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
    )

    // ------------------------------------------------------------------
    // Tests.
    // ------------------------------------------------------------------

    @Test
    fun `process death after the FINALIZE record resumes the idempotent drain without re-running work (ST-14 F-2)`() =
        runTest {
            val store = lazyStore()
            val pageKeys = (1..3).map { "p$it" }
            store.preRegisterPages(pageKeys)
            val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

            // ---- Pass 1: dies in the finalize window (FINALIZE durable, ----
            // ---- COMPLETE never published).                             ----
            val killIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val killLane = FinalizeWindowKillLane(store, killIdentities)
            val firstTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }
            val killOutcome = runCatching {
                coordinator(
                    store,
                    FakePreflightOcrWorker(store),
                    pages,
                    CountingAnalyzer(),
                    firstTranslator,
                    newScheduler(store, pageKeys, killIdentities, killLane),
                ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
            }
            killOutcome.exceptionOrNull().shouldBeInstanceOf<CancellationException>()

            // The durable record is FINALIZE under the original run id —
            // never regressed, never re-snapshotted.
            val killedRecord = durableRunRecord(store).shouldNotBeNull()
            killedRecord.state shouldBe ChapterRunState.FINALIZE
            val killedRunId = killedRecord.runId
            killLane.inpainted shouldBe emptyList()

            // ---- Pass 2: a SECOND coordinator over the SAME store. The ----
            // ---- ST-14 resume must re-run ONLY the finalize drain.     ----
            val resumeIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val resumeLane = FakeOverlapInpaintLane(store, resumeIdentities)
            val resumeOcrWorker = FakePreflightOcrWorker(store)
            val resumeAnalyzer = CountingAnalyzer()
            val resumeTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }
            val resumeOutcome = coordinator(
                store,
                resumeOcrWorker,
                pages,
                resumeAnalyzer,
                resumeTranslator,
                newScheduler(store, pageKeys, resumeIdentities, resumeLane),
            ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

            // The run closes COMPLETE — same run id, no backward move.
            resumeOutcome.status shouldBe BatchPass1Status.COMPLETED
            resumeOutcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
            val resumedRecord = durableRunRecord(store).shouldNotBeNull()
            resumedRecord.state shouldBe ChapterRunState.COMPLETE
            resumedRecord.runId shouldBe killedRunId
            resumedRecord.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE] shouldBe 1

            // Zero paid re-work: no re-OCR, no re-analysis, no re-translation.
            resumeOcrWorker.ocrPages shouldBe emptyList()
            resumeAnalyzer.chunks.get() shouldBe 0
            resumeTranslator.requests shouldBe emptyList()

            // The finalize drain re-inpainted exactly the pages the killed
            // pass left pending (already-committed pages are never re-run).
            resumeLane.inpainted shouldContainExactly pageKeys
            pageKeys.forEach { key ->
                val page = store.snapshot(key).page.shouldNotBeNull()
                page.translationStatus shouldBe StageStatus.READY
                page.inpaintStatus shouldBe StageStatus.READY
            }
        }

    @Test
    fun `a durable COMPLETE record under flag ON re-dispatches as a finished outcome with zero work`() =
        runTest {
            val store = lazyStore()
            val pageKeys = (1..3).map { "p$it" }
            store.preRegisterPages(pageKeys)
            val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

            // ---- Pass 1: the full happy path through COMPLETE. ----
            val firstIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val firstOutcome = coordinator(
                store,
                FakePreflightOcrWorker(store),
                pages,
                CountingAnalyzer(),
                FakeTranslator { _, chunk -> responseFor(chunk) },
                newScheduler(store, pageKeys, firstIdentities, FakeOverlapInpaintLane(store, firstIdentities)),
            ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
            firstOutcome.status shouldBe BatchPass1Status.COMPLETED
            val completeRecord = durableRunRecord(store).shouldNotBeNull()
            completeRecord.state shouldBe ChapterRunState.COMPLETE
            val pointerAfterPass1 = activeRunPointer(store)

            // ---- Pass 2: the flag-ON re-dispatch over the SAME store. ----
            val resumeIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val resumeOcrWorker = FakePreflightOcrWorker(store)
            val resumeAnalyzer = CountingAnalyzer()
            val resumeTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }
            val resumeOutcome = coordinator(
                store,
                resumeOcrWorker,
                pages,
                resumeAnalyzer,
                resumeTranslator,
                newScheduler(store, pageKeys, resumeIdentities, FakeOverlapInpaintLane(store, resumeIdentities)),
            ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

            // Idempotent finished outcome, zero work, NO new publication.
            resumeOutcome.status shouldBe BatchPass1Status.COMPLETED
            resumeOutcome.reason shouldBe ChapterProfileBatchCoordinator.RESUME_COMPLETE_REASON
            resumeOutcome.completedPageKeys.shouldNotBeNull() shouldBe pageKeys.toSet()
            resumeOcrWorker.ocrPages shouldBe emptyList()
            resumeAnalyzer.chunks.get() shouldBe 0
            resumeTranslator.requests shouldBe emptyList()
            activeRunPointer(store) shouldBe pointerAfterPass1
            durableRunRecord(store).shouldNotBeNull().state shouldBe ChapterRunState.COMPLETE
        }

    @Test
    fun `a recorded COMPLETE lacking per-page display evidence is superseded by a fresh run (LI-2)`() =
        runTest {
            val store = lazyStore()
            val pageKeys = (1..3).map { "p$it" }
            store.preRegisterPages(pageKeys)
            val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }

            // ---- Pass 1: the full happy path through COMPLETE. ----
            val firstIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val firstTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }
            val firstOutcome = coordinator(
                store,
                FakePreflightOcrWorker(store),
                pages,
                CountingAnalyzer(),
                firstTranslator,
                newScheduler(store, pageKeys, firstIdentities, FakeOverlapInpaintLane(store, firstIdentities)),
            ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
            firstOutcome.status shouldBe BatchPass1Status.COMPLETED

            // Healthy flagged COMPLETE: the translated page snapshots stay
            // durably addressable through the OPEN candidate pointers — there
            // is no committed bundle yet (promotion requires a rendered
            // result). This is the evidence the LI-2 COMPLETE gate accepts.
            val manifestAfterPass1 = artifactStore().readManifest().shouldNotBeNull()
            pageKeys.forEach { key ->
                manifestAfterPass1.pages.getValue(key).candidate.shouldNotBeNull()
            }

            // ---- The LI-2 hole: a user reset demotes the committed displays ----
            // ---- (the real reset primitives: live page + manifest pointer) ----
            // ---- while the COMPLETE run record keeps owning the chapter.   ----
            pageKeys.forEach { key ->
                store.updatePageFromCurrentSnapshot(key, "t924 li2 reset") { page ->
                    page?.copy(
                        blocks = page.blocks.map { it.copy(translation = "") }.toMutableList(),
                        translationStatus = StageStatus.PENDING,
                        renderStatus = StageStatus.PENDING,
                    ) ?: PageTranslation.EMPTY
                }
                store.demoteCommittedDisplay(key, "t924 li2 reset")
            }
            store.flush()
            val manifestAfterReset = artifactStore().readManifest().shouldNotBeNull()
            pageKeys.forEach { key ->
                // The reset keeps the candidate POINTER but persists the
                // cleared PENDING page OVER its snapshot, so the recorded
                // COMPLETE no longer has any page whose translated result is
                // readable — the exact LI-2 divergence.
                val record = manifestAfterReset.pages.getValue(key)
                record.committed shouldBe null
                val snapshot = record.candidate?.pageSnapshotFileName
                    ?.let { artifactStore().readPageSnapshot(it) }
                (snapshot?.hasRecognizedTranslation ?: false) shouldBe false
            }
            (
                artifactStore().readRunRecord(manifestAfterReset.activeRun.shouldNotBeNull())
                    as ChapterArtifactEngine.RunRecordRead.Usable
                ).record.state shouldBe ChapterRunState.COMPLETE

            // ---- Pass 2: the flag-ON re-dispatch must NOT return the ----
            // ---- zero-work finished outcome; it starts a fresh run.  ----
            val resumeIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val resumeOcrWorker = FakePreflightOcrWorker(store)
            val resumeAnalyzer = CountingAnalyzer()
            val resumeTranslator = FakeTranslator { _, chunk -> responseFor(chunk) }
            val resumeOutcome = coordinator(
                store,
                resumeOcrWorker,
                pages,
                resumeAnalyzer,
                resumeTranslator,
                newScheduler(store, pageKeys, resumeIdentities, FakeOverlapInpaintLane(store, resumeIdentities)),
            ).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

            // NOT the zero-work COMPLETE: the fresh run re-plans the demoted
            // pages, re-translates them (fresh paid work, same envelope plan
            // as the first pass), and re-closes with the normal completion
            // reason under a NEW record publication.
            resumeOutcome.reason shouldNotBe ChapterProfileBatchCoordinator.RESUME_COMPLETE_REASON
            resumeOutcome.status shouldBe BatchPass1Status.COMPLETED
            resumeOutcome.reason shouldBe ChapterProfileBatchCoordinator.TRANSLATE_COMPLETE_REASON
            resumeTranslator.requests.size shouldBe firstTranslator.requests.size
            activeRunPointer(store) shouldNotBe manifestAfterReset.activeRun
            durableRunRecord(store).shouldNotBeNull().state shouldBe ChapterRunState.COMPLETE
        }
}
