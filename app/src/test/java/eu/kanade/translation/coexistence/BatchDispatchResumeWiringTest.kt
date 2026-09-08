package eu.kanade.translation.coexistence

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
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.LegacyChapterSnapshot
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.pipeline.batch.BatchPass1Status
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import eu.kanade.translation.pipeline.batch.NativeLaneWorker
import eu.kanade.translation.pipeline.batch.OcrReadyPageRef
import eu.kanade.translation.pipeline.batch.PageKey
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * T924 wave-2 F1 / gap 1 — the DISPATCH-LEVEL flag-off-mid-run obligation
 * (gate §2.3 row 3.8): the production SHELL's resume path
 * (`BatchChapterTranslator.runBatchPass1`) consults
 * [ChapterProfileBatchCoordinator.resumeCompletedOutcome] BEFORE constructing
 * any coordinator. Every earlier FF-01e test pins the pure decision only
 * (`OcrPreflightFlagOffMidRunTest`, `ChapterTranslatorQueueRestoreTest`,
 * `Stage7FinalizeCoordinatorTest`); nothing proved the consultation itself —
 * a refactor could silently drop it.
 *
 * Both tests drive the REAL production shell through
 * `ChapterTranslator.translateChapterInternal` (TranslationCoexistenceHarness)
 * over an artifact store whose manifest carries a durable `activeRun` record.
 * The harness preferences never seed `translation_batch_profile_pipeline`, so
 * the flag reads its production default OFF (`TranslationPreferences.kt`):
 * `currentFlagOn = false` in both runs — exactly the flag-off-mid-run state.
 */
class BatchDispatchResumeWiringTest {

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    // ------------------------------------------------------------------
    // Case 1 — FF-01e.2a at dispatch: flag OFF over a durable COMPLETE run
    // finishes the chapter without running ANY schedule.
    // ------------------------------------------------------------------

    /**
     * The COMPLETE record is durably real: published through the store's
     * production sidecar transaction (`publishActiveRun`), not an in-memory
     * synthetic — the shell's `activeRunRecordOrNull` must READ it back.
     */
    @Test
    fun `flag-off dispatch over a durable COMPLETE run finishes the chapter without running any schedule`() {
        val pageKeys = listOf("p0", "p1")

        // Durable artifact store over in-memory documents (D5/D9 fresh-chapter
        // recipe), carrying a durably published COMPLETE run record.
        val artifact = ChapterArtifactStore(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("Chapter 1"),
        )
        var manifest = artifact
            .loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L))
            .manifest
        manifest = manifest.copy(
            authority = ManifestAuthority.ARTIFACTS,
            cutoverAtEpochMs = 1L,
            migratedFromLegacyAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        check(artifact.publishManifest(manifest)) { "fixture: authority flip publish failed" }
        val durable = artifact.readManifest().shouldNotBeNull()

        val frozen = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
            flagProfilePipeline = true,
        )
        val completeRecord = ChapterRunRecord(
            runId = "run-dispatch-complete-1",
            state = ChapterRunState.COMPLETE,
            frozenConfig = frozen,
            frozenRunConfigFingerprint = ChapterProfileBatchCoordinator.runConfigFingerprint(frozen),
            orderedSourceDigest = hex64("ordered-source"),
            analysisPolicyFingerprint = hex64("analysis-policy"),
            envelopePolicyFingerprint = hex64("envelope-policy"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val publication = artifact.publishActiveRun(
            manifest = durable,
            record = completeRecord,
            contentFingerprint = hex64("complete-run-record"),
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        // The chapter's pages are terminal textless — what a finished chapter's
        // store holds, so the post-pass reconciliation sees a done chapter
        // instead of manufacturing stranded-page failures.
        val store = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = pageKeys.associateWith { key -> finishedTextlessPage(key) },
            artifactStore = artifact,
            initialArtifactManifest = publication.manifest,
        )

        val harness = TranslationCoexistenceHarness.create(pageKeys, storeOverride = store)
        try {
            harness.stubChapterPages(pageKeys)
            val batch = harness.launchBatch(pageKeys)
            val reconciliation = runBlocking {
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    batch.reconciliation.await().shouldNotBeNull()
                }
            }

            // ZERO schedule work: no decode, no native lane, no provider call —
            // the shell returned the TreatAsFinished COMPLETED outcome before
            // constructing any coordinator.
            harness.barrier.arrivals.value shouldBe emptyList()
            harness.transportCallsFor("p0") shouldBe 0
            harness.transportCallsFor("p1") shouldBe 0

            // The shell finished the chapter end-to-end (no stranded pages, no
            // error) — "treated as finished", not "failed short".
            reconciliation.chapterStatus shouldBe Translation.State.TRANSLATED
            reconciliation.doneCount shouldBe pageKeys.size
            reconciliation.strandedPages shouldBe emptyMap()

            // The durable COMPLETE record still owns the chapter, untouched.
            val manifestAfter = artifact.readManifest().shouldNotBeNull()
            val pointerAfter = manifestAfter.activeRun.shouldNotBeNull()
            pointerAfter shouldBe publication.manifest.activeRun
            (
                artifact.readRunRecord(pointerAfter)
                    as ChapterArtifactStore.RunRecordRead.Usable
                ).record shouldBe completeRecord
        } finally {
            harness.close()
        }
    }

    private fun finishedTextlessPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        renderStatus = StageStatus.SKIPPED,
        sourceFingerprint = hex64("source-$pageKey"),
    )

    /**
     * Translation-committed but never displayable: the inpaint failed, so the
     * reader has nothing to show for this page. The flagged run's COMPLETE
     * records translation-terminal alone — exactly the F-4 hole.
     */
    private fun translatedUnrenderedPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(
            eu.kanade.translation.model.TranslationBlock(
                blockId = "b1",
                text = "source",
                translation = "translated",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.FAILED,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
    )

    /**
     * Shared durable fixture: an ARTIFACTS-authority store whose manifest
     * carries a durably published COMPLETE run record (real sidecar
     * publication), with the given page states in memory.
     */
    private fun completeRecordStore(
        pageKeys: List<String>,
        pages: Map<String, PageTranslation>,
    ): Triple<ChapterArtifactStore, ChapterArtifactStore.TransactionOutcome.Committed, ChapterTranslationStore> {
        val artifact = ChapterArtifactStore(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("Chapter 1"),
        )
        var manifest = artifact
            .loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L))
            .manifest
        manifest = manifest.copy(
            authority = ManifestAuthority.ARTIFACTS,
            cutoverAtEpochMs = 1L,
            migratedFromLegacyAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        check(artifact.publishManifest(manifest)) { "fixture: authority flip publish failed" }
        val durable = artifact.readManifest().shouldNotBeNull()

        val frozen = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
            flagProfilePipeline = true,
        )
        val completeRecord = ChapterRunRecord(
            runId = "run-dispatch-complete-1",
            state = ChapterRunState.COMPLETE,
            frozenConfig = frozen,
            frozenRunConfigFingerprint = ChapterProfileBatchCoordinator.runConfigFingerprint(frozen),
            orderedSourceDigest = hex64("ordered-source"),
            analysisPolicyFingerprint = hex64("analysis-policy"),
            envelopePolicyFingerprint = hex64("envelope-policy"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val publication = artifact.publishActiveRun(
            manifest = durable,
            record = completeRecord,
            contentFingerprint = hex64("complete-run-record"),
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        val store = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = pages,
            artifactStore = artifact,
            initialArtifactManifest = publication.manifest,
        )
        return Triple(artifact, publication, store)
    }

    /**
     * F-4 (wave-7c review) at dispatch level: a recorded COMPLETE is NOT
     * enough to retire the chapter — FF-01e.2a authorizes TreatAsFinished
     * only when the final per-page displays committed. One translation-
     * committed page with a FAILED inpaint has nothing to display: the
     * dispatch must drop to the legacy schedule so that page re-runs, not
     * return a typed COMPLETED that forever hides it.
     */
    @Test
    fun `flag-off dispatch over a COMPLETE run with an unrendered page does not treat the chapter as finished`() {
        val pageKeys = listOf("p0", "p1")
        val (artifact, publication, store) = completeRecordStore(
            pageKeys,
            mapOf(
                "p0" to finishedTextlessPage("p0"),
                "p1" to translatedUnrenderedPage("p1"),
            ),
        )

        val harness = TranslationCoexistenceHarness.create(pageKeys, storeOverride = store)
        try {
            harness.stubChapterPages(pageKeys)
            val batch = harness.launchBatch(pageKeys)

            // The legacy schedule must actually START the unrendered page's
            // work. Use the same deterministic "schedule is EXECUTING" signal
            // as Case 2 — the fake transport's start signal, fired at the
            // identity check before any wait (the contextual native fakes are
            // not reliable observation points from the standard lane). A
            // TreatAsFinished short-circuit returns before ANY coordinator
            // exists, so this await alone disproves it. Whether the legacy
            // planner then re-inpaints or re-translates the page is its own
            // decision, outside this dispatch-gate test's scope.
            runBlocking {
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    harness.transportStarted["p1"].shouldNotBeNull().await()
                }
            }

            batch.job.cancel()
            runBlocking {
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.job.join() }
            }

            // The durable COMPLETE record still owns the chapter, untouched.
            val manifestAfter = artifact.readManifest().shouldNotBeNull()
            manifestAfter.activeRun shouldBe publication.manifest.activeRun
        } finally {
            harness.close()
        }
    }

    // ------------------------------------------------------------------
    // Case 2 — FF-01e.2b at dispatch: flag OFF over an interrupted (non-
    // COMPLETE) flagged run drops to the legacy schedule, which STARTS.
    // ------------------------------------------------------------------

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

    private fun frozenConfig() = ChapterProfileBatchCoordinator.frozenRunConfig(
        sourceLang = "ja",
        targetLang = "en",
        ocrEngine = "FakeOcrEngine",
        inpaintMode = "OFF",
        providerKey = "fake:provider",
        flagProfilePipeline = true,
    )

    private fun ocrPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(
            eu.kanade.translation.model.TranslationBlock(
                blockId = "b1",
                text = "source",
                translation = "",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
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

    /** Kills the flagged pass at [failAt] so an interrupted, resumable state exists. */
    private inner class InterruptingOcrWorker(
        private val store: ChapterTranslationStore,
        private val failAt: String,
    ) : NativeLaneWorker {
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            if (pageKey == failAt) throw IllegalStateException("simulated mid-preflight death")
            val lease = store.tryAcquirePageStageLease(pageKey, eu.kanade.translation.model.PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrPage(pageKey),
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 dispatch-resume interrupted ocr",
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

    @Test
    fun `flag-off dispatch over an interrupted flagged run starts the legacy schedule`() = runBlocking {
        val pageKeys = listOf("p1", "p2")
        val store = lazyStore()
        store.preRegisterPages(pageKeys)
        val pages: List<PageKey> = pageKeys.mapIndexed { index, key -> key to index }
        val coordinator = ChapterProfileBatchCoordinator(
            store = store,
            // Die on the FIRST page so NO OCR is persisted: the legacy restart
            // must run real OCR (analyze → NATIVE_RELEASE), a guaranteed first
            // barrier point (persisted OCR would make legacy correctly skip it).
            nativeWorker = InterruptingOcrWorker(store, failAt = "p1"),
            frozenConfig = frozenConfig(),
            orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
            flagProfilePipeline = true,
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO).status shouldBe
            BatchPass1Status.FAILED

        val durableBefore = artifactStore().readManifest().shouldNotBeNull()
        val recordBefore = (
            artifactStore().readRunRecord(durableBefore.activeRun.shouldNotBeNull())
                as ChapterArtifactStore.RunRecordRead.Usable
            ).record
        // The record is genuinely resumable, not COMPLETE: TreatAsFinished
        // must NOT fire at the dispatch.
        (recordBefore.state == ChapterRunState.COMPLETE) shouldBe false

        val harness = TranslationCoexistenceHarness.create(pageKeys, storeOverride = store)
        try {
            harness.stubChapterPages(pageKeys)
            // The standard lane's legacy path never reaches the contextual
            // barrier fakes; its deterministic "schedule is EXECUTING" signal
            // is the fake transport's own start signal (fired at identity
            // check, before any wait). Awaiting it proves the dispatch did
            // not short-circuit and did not error: the legacy coordinator is
            // running real per-page work.
            val batch = harness.launchBatch(pageKeys)
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                harness.transportStarted["p1"].shouldNotBeNull().await()
            }

            // The legacy start left the new-path sidecars untouched (FF-01c):
            // the interrupted record is byte-equal and still owns the chapter.
            val durableAfter = artifactStore().readManifest().shouldNotBeNull()
            durableAfter.activeRun shouldBe durableBefore.activeRun
            (
                artifactStore().readRunRecord(durableAfter.activeRun.shouldNotBeNull())
                    as ChapterArtifactStore.RunRecordRead.Usable
                ).record shouldBe recordBefore

            batch.job.cancel()
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.job.join() }
        } finally {
            harness.close()
        }
        Unit
    }
}
