package eu.kanade.translation.artifact

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
 * T924 LI-4: a one-shot stale-manifest retry inside [ChapterArtifactStore] for
 * the two first-publication seams the flagged Batch lane hits. When a chapter
 * with more than 8 pages opens, the ARTIFACTS path launches the background
 * `verifyLegacyArtifactHealth`, which republishes a VERIFIED manifest AFTER the
 * façade cached the pre-verification copy — so a dispatch started in that
 * window presents a stale manifest to its FIRST durable publication
 * (`checkpointOcr` / `publishActiveRun`) and was CAS-rejected, surfacing a
 * spurious CHECKPOINT_REJECTED preflight failure or a PAUSED run on a healthy
 * chapter.
 *
 * Contract after the fix: on a stale-manifest CAS rejection specifically, the
 * store re-reads the durable manifest ONCE, rebuilds the intended mutation
 * against the FRESH manifest, and retries once — the concurrent publication's
 * changes are preserved, never reverted. The caller-visible outcome stays
 * "Committed or Rejected"; a retry that also fails returns Rejected as today.
 */
class ChapterArtifactStoreStaleManifestRetryTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Mirrors the real LI-4 writer: `verifyLegacyArtifactHealth` republishes
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
        artifact: ChapterArtifactStore,
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
        val artifact = ChapterArtifactStore(documents, layout)
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
                flagProfilePipeline = true,
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

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
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

    private fun legacySnapshot(page: PageTranslation) = LegacyChapterSnapshot(
        pages = mapOf("page.jpg" to LegacyPageFacts(page, CleanedFileState.VALID)),
        glossary = emptyMap(),
        legacyIdentity = identity("v1"),
        sourceFileName = "Chapter 1.json",
        glossaryFileName = null,
        glossaryIdentity = null,
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
    )

    private class CheckpointFixture(
        val store: ChapterArtifactStore,
        val callerCopy: ChapterArtifactManifest,
        val generationId: String,
        val ocrSnapshot: PageTranslation,
        val io: FakeChapterDocumentIo,
    )

    private fun checkpointFixtureWithConcurrentVerify(): CheckpointFixture {
        val io = FakeChapterDocumentIo()
        val store = ChapterArtifactStore(
            AtomicChapterDocuments(io),
            layout,
            object : CleanedImageProbe {
                override fun probe(input: InputStream): ProbedImage? =
                    if (input.read() == 1) null else ProbedImage(100, 100)
            },
        )
        val ocrSnapshot = ocrPage()
        val migrated = store.loadOrMigrate(legacySnapshot(ocrSnapshot)).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 500L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
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
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

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

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
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
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

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
        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "stale page version"
        // The concurrent writer's checkpoint pointer is untouched, and the
        // stale caller's checkpoint sidecar never reached disk.
        val durable = fx.store.readManifest().shouldNotBeNull()
        durable.ocrCheckpoints.getValue("page.jpg").contentFingerprint shouldBe hex64("ocr-content")
        fx.io.files.containsKey(layout.ocrCheckpointFile("page.jpg", hex64("stale-ocr-content"))) shouldBe false
    }
}
