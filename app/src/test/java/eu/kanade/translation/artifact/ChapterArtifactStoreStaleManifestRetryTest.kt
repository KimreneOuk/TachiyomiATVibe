package eu.kanade.translation.artifact

import eu.kanade.translation.artifact.loadArtifact

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.security.MessageDigest

/**
 * T924 LI-4: a one-shot stale-manifest retry inside [ChapterArtifactEngine] for
 * the first-publication seams the flagged Batch lane hits. When a chapter
 * with more than 8 pages opens, the ARTIFACTS path launches the background
 * background artifact-health republisher, which republishes a VERIFIED manifest AFTER the
 * façade cached the pre-verification copy — so a dispatch started in that
 * window presents a stale manifest to its FIRST durable publication
 * (`checkpointOcr` / `publishActiveRun`) and was CAS-rejected, surfacing a
 * spurious CHECKPOINT_REJECTED preflight failure or a PAUSED run on a healthy
 * chapter.
 *
 * T934: the same race aborted the whole batch RESUME at the remaining
 * first-publication seams — `openCandidate` (the "artifact candidate open
 * rejected" log), the stage-patch candidate transactions (`persistLiveCandidate`
 * / `promoteLiveCandidate`, the "stage patch rejected …
 * ARTIFACT_PUBLICATION_FAILED" façade mapping), and the run-pointer teardown
 * (`retireActiveRun`). The tests below extend the LI-4 contract to each of
 * them: a stale-manifest rejection triggers exactly ONE fresh-read retry that
 * commits; a fresh state that drifted further rejects with its REAL reason
 * (no second retry); every non-stale rejection reason is returned as-is.
 *
 * Contract after the fix: on a stale-manifest CAS rejection specifically, the
 * store re-reads the durable manifest ONCE, rebuilds the intended mutation
 * against the FRESH manifest, and retries once — the concurrent publication's
 * changes are preserved, never reverted. The caller-visible outcome stays
 * "Committed or Rejected"; a retry that also fails returns Rejected as today.
 */
class ChapterArtifactEngineStaleManifestRetryTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Mirrors the real LI-4 writer: the artifact-health republisher republishes
     * the manifest with the VERIFIED health marker and a bumped timestamp,
     * behind the caller's back.
     */
    private fun verifyMarkerPublication(
        manifest: ChapterArtifactManifest,
        nowEpochMs: Long,
    ): ChapterArtifactManifest {
        val metadata = (manifest.legacyMigration ?: LegacyMigrationMetadata(sourceFileName = "Chapter 1.json"))
            .copy(
                health = LegacyMigrationHealth.VERIFIED,
                lastVerifiedByVersionCode = 63L,
                lastVerifiedAtEpochMs = nowEpochMs,
            )
        return manifest.copy(legacyMigration = metadata, updatedAtEpochMs = nowEpochMs)
    }

    /** Publishes [updated] as the durable manifest, simulating the concurrent writer. */
    private fun bumpBehindCallersBack(
        artifact: ChapterArtifactEngine,
        current: ChapterArtifactManifest,
        nowEpochMs: Long,
    ) {
        check(artifact.publishManifest(verifyMarkerPublication(current, nowEpochMs))) {
            "fixture: concurrent verify publication failed"
        }
    }

    // ------------------------------------------------------------------
    // T3: publishActiveRun commits through the one-shot retry and keeps
    // the concurrent publication's changes.
    // ------------------------------------------------------------------

    @Test
    fun `publishActiveRun with a stale snapshot retries once and preserves the concurrent verify marker`() {
        val documents = AtomicChapterDocuments(FakeChapterDocumentIo())
        val artifact = ChapterArtifactEngine(documents, layout)
        var manifest = artifact
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        manifest = manifest.copy(
            cutoverAtEpochMs = 1L,
            migratedFromLegacyAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        check(artifact.publishManifest(manifest)) { "fixture: authority flip publish failed" }

        // The façade caches the pre-verification copy…
        val callerCopy = artifact.readManifest().shouldNotBeNull()
        // …and the background verify republishes a VERIFIED manifest.
        bumpBehindCallersBack(artifact, callerCopy, nowEpochMs = 2L)

        val record = ChapterRunRecord(
            runId = "run-li4-publish-1",
            state = ChapterRunState.RUN_SNAPSHOT,
            frozenConfig = eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator.frozenRunConfig(
                sourceLang = "ja",
                targetLang = "en",
                ocrEngine = "FakeOcrEngine",
                inpaintMode = "OFF",
                providerKey = "fake:provider",
            ),
            frozenRunConfigFingerprint = hex64("frozen-config"),
            orderedSourceDigest = hex64("ordered-source"),
            analysisPolicyFingerprint = hex64("analysis-policy"),
            envelopePolicyFingerprint = hex64("envelope-policy"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

        val outcome = artifact.publishActiveRun(
            manifest = callerCopy,
            record = record,
            contentFingerprint = hex64("li4-run-record"),
            nowEpochMs = 3L,
        )

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val durable = artifact.readManifest().shouldNotBeNull()
        // The run-record pointer landed on the BUMPED manifest…
        durable.activeRun.shouldNotBeNull().contentFingerprint shouldBe hex64("li4-run-record")
        committed.manifest shouldBe durable
        // …and the concurrent publication's changes were NOT reverted.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
        durable.updatedAtEpochMs shouldBe 3L
    }

    // ------------------------------------------------------------------
    // T4: checkpointOcr commits through the one-shot retry on the same
    // race, with the candidate close and checkpoint pointer intact.
    // ------------------------------------------------------------------

    private fun block(text: String = "source", blockId: String? = "b1") = TranslationBlock(
        blockId = blockId,
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

    private fun ocrPage(text: String = "source") = PageTranslation(
        sourceFileName = "page.jpg",
        blocks = mutableListOf(block(text)),
        imgWidth = 100f,
        imgHeight = 100f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-bytes"),
        detectionFingerprint = hex64("detection"),
        ocrFingerprint = hex64("ocr"),
        inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun identity(tag: String) = LegacySourceIdentity(
        sha256 = "sha-$tag",
        lengthBytes = tag.length.toLong(),
        lastModifiedMs = 1L,
    )

    private fun legacySnapshot(page: PageTranslation) = ArtifactSeed(
        pages = mapOf("page.jpg" to ArtifactPageFacts(page, CleanedFileState.VALID)),
        glossary = emptyMap(),
        legacyIdentity = identity("v1"),
        sourceFileName = "Chapter 1.json",
        glossaryFileName = null,
        glossaryIdentity = null,
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
    )

    private class CheckpointFixture(
        val store: ChapterArtifactEngine,
        val callerCopy: ChapterArtifactManifest,
        val generationId: String,
        val ocrSnapshot: PageTranslation,
        val io: FakeChapterDocumentIo,
    )

    private fun checkpointFixtureWithConcurrentVerify(): CheckpointFixture {
        val io = FakeChapterDocumentIo()
        val store = ChapterArtifactEngine(
            AtomicChapterDocuments(io),
            layout,
            object : CleanedImageProbe {
                override fun probe(input: InputStream): ProbedImage? =
                    if (input.read() == 1) null else ProbedImage(100, 100)
            },
        )
        val ocrSnapshot = ocrPage()
        val migrated = store.loadArtifact(legacySnapshot(ocrSnapshot)).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 500L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val persisted = store.persistLiveCandidate(
            manifest = opened.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = opened.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            sourceIdentity = SourceIdentity(
                pageKey = "page.jpg",
                sha256 = hex64("source-bytes"),
                width = 100,
                height = 100,
                orientation = "PORTRAIT",
            ),
            nowEpochMs = 501L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        // The façade cached the pre-verification manifest; the background
        // verify then republished the VERIFIED marker behind its back.
        val callerCopy = persisted.manifest
        bumpBehindCallersBack(store, callerCopy, nowEpochMs = 5_000L)
        return CheckpointFixture(store, callerCopy, generationId, ocrSnapshot, io)
    }

    /** Builds a well-formed checkpoint DTO for [CheckpointFixture.ocrSnapshot]. */

    private fun checkpointFor(fx: CheckpointFixture): PageOcrCheckpoint {
        val snapshotFingerprint = StageFingerprints.pageSnapshot(fx.ocrSnapshot)
        return PageOcrCheckpoint(
            pageKey = "page.jpg",
            naturalPageIndex = null,
            sourceIdentity = SourceIdentity(
                pageKey = "page.jpg",
                sha256 = hex64("source-bytes"),
                width = 100,
                height = 100,
                orientation = "PORTRAIT",
            ),
            detectionFingerprint = fx.ocrSnapshot.detectionFingerprint,
            ocrFingerprint = fx.ocrSnapshot.ocrFingerprint!!,
            ocrContentFingerprint = hex64("ocr-content"),
            ocrPageSnapshotPointer = SidecarPointer(
                fileName = fx.store.ocrStageSnapshotName("page.jpg", snapshotFingerprint),
                schemaVersion = 1,
                contentFingerprint = snapshotFingerprint,
            ),
            inpaintMaskRevision = fx.ocrSnapshot.inpaintRevision,
            priorCommittedDisplay = null,
            producedByOrigin = ArtifactOrigin.BATCH,
            producerGenerationId = fx.generationId,
            checkpointedAtEpochMs = 502L,
        )
    }

    @Test
    fun `checkpointOcr with a stale snapshot retries once and preserves the concurrent verify marker`() {
        val fx = checkpointFixtureWithConcurrentVerify()
        val checkpoint = checkpointFor(fx)
        val callerPageVersion = fx.callerCopy.pages.getValue("page.jpg").pageVersion

        val outcome = fx.store.checkpointOcr(
            manifest = fx.callerCopy,
            pageKey = "page.jpg",
            expectedPageVersion = callerPageVersion,
            expectedDependencyFingerprint = "deps-v1",
            ocrSnapshot = fx.ocrSnapshot,
            checkpoint = checkpoint,
            mode = OcrCheckpointMode.CLOSE,
            nowEpochMs = 502L,
        )

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val durable = fx.store.readManifest().shouldNotBeNull()
        // The checkpoint landed: candidate closed, pointer installed…
        durable.ocrCheckpoints.getValue("page.jpg").contentFingerprint shouldBe hex64("ocr-content")
        durable.pages.getValue("page.jpg").candidate shouldBe null
        durable.pages.getValue("page.jpg").pageVersion shouldBe callerPageVersion + 1
        committed.manifest shouldBe durable
        // …and the concurrent publication's changes were NOT reverted.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
    }

    @Test
    fun `stale snapshot whose fresh state also drifted still rejects without a second retry`() {
        val fx = checkpointFixtureWithConcurrentVerify()

        // A concurrent writer checkpoints the page against the FRESH manifest
        // while the stale caller was still mid-flight: the candidate closes and
        // the page version advances beyond anything the caller saw.
        val concurrentCheckpoint = checkpointFor(fx)
        fx.store.checkpointOcr(
            manifest = fx.store.readManifest().shouldNotBeNull(),
            pageKey = "page.jpg",
            expectedPageVersion = fx.store.readManifest().shouldNotBeNull()
                .pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            ocrSnapshot = fx.ocrSnapshot,
            checkpoint = concurrentCheckpoint,
            mode = OcrCheckpointMode.CLOSE,
            nowEpochMs = 5_001L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        // The stale caller now presents its pre-verify snapshot with its own
        // (older) OCR content for the closed candidate generation.
        val staleCallerCheckpoint = checkpointFor(fx)
            .copy(ocrContentFingerprint = hex64("stale-ocr-content"))
        val outcome = fx.store.checkpointOcr(
            manifest = fx.callerCopy,
            pageKey = "page.jpg",
            expectedPageVersion = fx.callerCopy.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            ocrSnapshot = fx.ocrSnapshot,
            checkpoint = staleCallerCheckpoint,
            mode = OcrCheckpointMode.CLOSE,
            nowEpochMs = 5_002L,
        )

        // The retry re-validated the WHOLE transaction against the fresh
        // manifest and rejected on the real drift (page version), not the
        // stale CAS — exactly one retry, no second one.
        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "stale page version"
        // The concurrent writer's checkpoint pointer is untouched, and the
        // stale caller's checkpoint sidecar never reached disk.
        val durable = fx.store.readManifest().shouldNotBeNull()
        durable.ocrCheckpoints.getValue("page.jpg").contentFingerprint shouldBe hex64("ocr-content")
        fx.io.files.containsKey(layout.ocrCheckpointFile("page.jpg", hex64("stale-ocr-content"))) shouldBe false
    }

    // ------------------------------------------------------------------
    // T934: the batch-resume seams join the same one-shot retry.
    // ------------------------------------------------------------------

    /** Authority-flip fixture: ARTIFACTS manifest, no candidate yet. */
    private fun authorityFlipFixture(): ChapterArtifactEngine {
        val artifact = ChapterArtifactEngine(AtomicChapterDocuments(FakeChapterDocumentIo()), layout)
        var manifest = artifact
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        manifest = manifest.copy(
            cutoverAtEpochMs = 1L,
            migratedFromLegacyAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        check(artifact.publishManifest(manifest)) { "fixture: authority flip publish failed" }
        return artifact
    }

    private fun li4RunRecord(runId: String) = ChapterRunRecord(
        runId = runId,
        state = ChapterRunState.RUN_SNAPSHOT,
        frozenConfig = eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        ),
        frozenRunConfigFingerprint = hex64("frozen-config"),
        orderedSourceDigest = hex64("ordered-source"),
        analysisPolicyFingerprint = hex64("analysis-policy"),
        envelopePolicyFingerprint = hex64("envelope-policy"),
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    @Test
    fun `openCandidate with a stale snapshot retries once and preserves the concurrent verify marker`() {
        val artifact = authorityFlipFixture()
        val callerCopy = artifact.readManifest().shouldNotBeNull()
        bumpBehindCallersBack(artifact, callerCopy, nowEpochMs = 2L)

        val outcome = artifact.openCandidate(
            manifest = callerCopy,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 3L,
        )

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.generationId.shouldNotBeNull()
        val durable = artifact.readManifest().shouldNotBeNull()
        // The candidate landed on the BUMPED manifest…
        durable.pages.getValue("page.jpg").candidate.shouldNotBeNull()
            .dependencyFingerprint shouldBe "deps-v1"
        committed.manifest shouldBe durable
        // …and the concurrent publication's changes were NOT reverted.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
        durable.updatedAtEpochMs shouldBe 3L
    }

    @Test
    fun `openCandidate whose fresh state also drifted still rejects without a second retry`() {
        val artifact = authorityFlipFixture()
        val callerCopy = artifact.readManifest().shouldNotBeNull()
        // A concurrent writer opens the candidate against the FRESH manifest
        // while the stale caller was mid-flight: page version 0 -> 1.
        artifact.openCandidate(
            artifact.readManifest().shouldNotBeNull(),
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 2L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val concurrentGenerationId = artifact.readManifest().shouldNotBeNull()
            .pages.getValue("page.jpg").candidate.shouldNotBeNull().generationId

        val outcome = artifact.openCandidate(
            manifest = callerCopy,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 3L,
        )

        // The retry re-validated the WHOLE transaction against the fresh
        // manifest and rejected on the real drift (page version) — exactly
        // one retry, no second one, and the concurrent candidate stands.
        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "stale page version"
        artifact.readManifest().shouldNotBeNull().pages.getValue("page.jpg")
            .candidate.shouldNotBeNull().generationId shouldBe concurrentGenerationId
    }

    @Test
    fun `openCandidate with a genuinely wrong page version still rejects as-is with no retry`() {
        val artifact = authorityFlipFixture()
        val current = artifact.readManifest().shouldNotBeNull()

        val outcome = artifact.openCandidate(
            manifest = current,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 7L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 3L,
        )

        // The manifest is NOT stale, so the rejection is the caller's real
        // identity drift, returned exactly as before the retry existed.
        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "stale page version"
        artifact.readManifest() shouldBe current
    }

    @Test
    fun `persistLiveCandidate with a stale snapshot retries once and preserves the concurrent verify marker`() {
        val fx = checkpointFixtureWithConcurrentVerify()
        val callerPageVersion = fx.callerCopy.pages.getValue("page.jpg").pageVersion

        val outcome = fx.store.persistLiveCandidate(
            manifest = fx.callerCopy,
            pageKey = "page.jpg",
            generationId = fx.generationId,
            expectedPageVersion = callerPageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = fx.ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 5_001L,
        )

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val durable = fx.store.readManifest().shouldNotBeNull()
        // The snapshot pointer advanced on the BUMPED manifest…
        durable.pages.getValue("page.jpg").pageVersion shouldBe callerPageVersion + 1
        committed.manifest shouldBe durable
        // …and the concurrent publication's changes were NOT reverted.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
    }

    @Test
    fun `persistLiveCandidate with a fresh manifest and a wrong generation still rejects as-is`() {
        val fx = checkpointFixtureWithConcurrentVerify()

        val outcome = fx.store.persistLiveCandidate(
            manifest = fx.store.readManifest().shouldNotBeNull(),
            pageKey = "page.jpg",
            generationId = "g-other",
            expectedPageVersion = fx.store.readManifest().shouldNotBeNull()
                .pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = fx.ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 5_001L,
        )

        // Non-stale rejection reason: returned as-is, no retry, manifest
        // unchanged.
        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "candidate mismatch"
    }

    @Test
    fun `promoteLiveCandidate with a stale snapshot retries once and preserves the concurrent verify marker`() {
        val fx = checkpointFixtureWithConcurrentVerify()
        val callerPageVersion = fx.callerCopy.pages.getValue("page.jpg").pageVersion

        val outcome = fx.store.promoteLiveCandidate(
            manifest = fx.callerCopy,
            pageKey = "page.jpg",
            generationId = fx.generationId,
            expectedPageVersion = callerPageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = fx.ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 5_001L,
        )

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val durable = fx.store.readManifest().shouldNotBeNull()
        // The candidate closed and the committed pointer landed on the BUMPED
        // manifest…
        durable.pages.getValue("page.jpg").candidate shouldBe null
        durable.pages.getValue("page.jpg").committed.shouldNotBeNull()
            .generationId shouldBe fx.generationId
        committed.manifest shouldBe durable
        // …and the concurrent publication's changes were NOT reverted.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
    }

    @Test
    fun `promoteLiveCandidate with a fresh manifest and drifted dependencies still rejects as-is`() {
        val fx = checkpointFixtureWithConcurrentVerify()

        val outcome = fx.store.promoteLiveCandidate(
            manifest = fx.store.readManifest().shouldNotBeNull(),
            pageKey = "page.jpg",
            generationId = fx.generationId,
            expectedPageVersion = fx.store.readManifest().shouldNotBeNull()
                .pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-stale",
            pageSnapshot = fx.ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 5_001L,
        )

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "dependency fingerprint changed"
    }

    @Test
    fun `retireActiveRun with a stale snapshot retries once and preserves the concurrent verify marker`() {
        val artifact = authorityFlipFixture()
        val record = li4RunRecord("run-li4-retire-1")
        check(
            artifact.publishActiveRun(
                manifest = artifact.readManifest().shouldNotBeNull(),
                record = record,
                contentFingerprint = hex64("li4-retire-run"),
                nowEpochMs = 2L,
            ) is ChapterArtifactEngine.TransactionOutcome.Committed,
        ) { "fixture: run record publication failed" }
        val callerCopy = artifact.readManifest().shouldNotBeNull()
        bumpBehindCallersBack(artifact, callerCopy, nowEpochMs = 3L)

        val outcome = artifact.retireActiveRun(callerCopy, "batch resume teardown", nowEpochMs = 4L)

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val durable = artifact.readManifest().shouldNotBeNull()
        // The pointer retired on the BUMPED manifest…
        durable.activeRun shouldBe null
        committed.manifest shouldBe durable
        // …and the concurrent publication's changes were NOT reverted.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
    }
}
