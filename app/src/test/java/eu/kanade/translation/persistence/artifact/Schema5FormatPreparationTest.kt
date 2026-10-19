package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.context.*
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.TranslationProvenancePage
import eu.kanade.translation.model.OcrBlockContent
import eu.kanade.translation.model.EnvelopePlanInputPage

import eu.kanade.translation.model.PageDisplayState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * P3B schema5 format preparation fixtures. The planned sole-manifest format
 * (schema 5) is PREPARED here, not activated: every format family the P4
 * consumer needs — candidate-scoped snapshot/fingerprint, committed/previous
 * display, inventory and durable failures, monotonic page/outcome/tombstone
 * evidence, and CLOSE/REBASE checkpoints — round-trips through the shared
 * durable-document Json, ordinary schema4 emission stays byte-compatible,
 * and the minimal version discriminator classifies unsupported older/newer
 * evidence separately from missing, corrupt, and supported recovery.
 *
 * No ordinary schema5 emission or admission exists yet: P4 owns activation.
 */
class Schema5FormatPreparationTest {

    private val hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun checkpointPointer(fingerprint: String) = SidecarPointer(
        fileName = "Chapter 1_artifacts/ocr/p0/f-$fingerprint.json",
        schemaVersion = PageOcrCheckpoint.SCHEMA_VERSION,
        contentFingerprint = fingerprint,
    )

    // ------------------------------------------------------------------
    // Minimal version discrimination.
    // ------------------------------------------------------------------

    @Test
    fun `the version discriminator classifies older, current, and future schemas separately`() {
        schemaSupport(ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION - 1) shouldBe
            FormatSchemaSupport.UNSUPPORTED_OLDER
        schemaSupport(1) shouldBe FormatSchemaSupport.UNSUPPORTED_OLDER
        for (version in ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION..ChapterArtifactManifest.SCHEMA_VERSION) {
            schemaSupport(version) shouldBe FormatSchemaSupport.SUPPORTED
        }
        schemaSupport(ChapterArtifactManifest.SCHEMA_VERSION + 1) shouldBe
            FormatSchemaSupport.UNSUPPORTED_NEWER
        schemaSupport(99) shouldBe FormatSchemaSupport.UNSUPPORTED_NEWER
    }

    @Test
    fun `ordinary schema4 emission keeps the prepared fields neutral`() {
        val ordinary = ChapterArtifactManifest(chapterKey = "Chapter 1")
        ordinary.schemaVersion shouldBe ChapterArtifactManifest.SCHEMA_VERSION
        ordinary.expectedPageKeys shouldBe emptySet()
        ordinary.pages shouldBe emptyMap()
        val encoded = ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), ordinary)
        val decoded = ArtifactDocumentJson.decodeFromString(ChapterArtifactManifest.serializer(), encoded)
        decoded shouldBe ordinary
        // Neutrality in bytes too: the prepared inventory field carries its
        // empty default (defaults are encoded) and no tombstone exists.
        val json = ArtifactDocumentJson.parseToJsonElement(encoded).jsonObject
        json["expectedPageKeys"].toString() shouldBe "[]"
    }

    // ------------------------------------------------------------------
    // Round-trips of every schema5 format family.
    // ------------------------------------------------------------------

    @Test
    fun `schema5 format families round-trip through the shared durable document Json`() {
        val candidateFingerprint = hex
        val committed = CommittedBundleMetadata(
            generationId = "g2",
            bundleFingerprint = hex,
            displayBase = DisplayBaseReference(
                kind = DisplayBaseKind.CLEANED_IMAGE,
                fileName = "p0.cleaned.jpg",
                validated = true,
            ),
            translationFingerprint = hex,
            origin = ArtifactOrigin.BATCH,
            hasManualEdits = true,
            promotedAtEpochMs = 20L,
            pageSnapshotFileName = "Chapter 1_artifacts/pages/p0/committed-g2.json",
        )
        val previous = committed.copy(generationId = "g1", promotedAtEpochMs = 10L)
        val candidate = CandidateGenerationMetadata(
            generationId = "g3",
            origin = ArtifactOrigin.READER_ADHOC,
            dependencyFingerprint = hex,
            createdAtEpochMs = 30L,
            priorDisplayState = PageDisplayState.DISPLAY_READY,
            pageSnapshotFileName = "Chapter 1_artifacts/pages/p0/candidate-g3.json",
            pageSnapshotFingerprint = candidateFingerprint,
        )
        val failure = DurableFailureMetadata(
            pageKey = "p0",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.TRANSIENT,
            retryCount = 1,
            lastFailureMessage = "provider failed",
            lastFailedAtEpochMs = 40L,
            nextEligibleRetryAtEpochMs = 50L,
        )
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            pages = mapOf(
                "p0" to PageArtifactRecord(
                    pageKey = "p0",
                    pageVersion = 7L,
                    displayState = PageDisplayState.DISPLAY_READY,
                    committed = committed,
                    previousCommitted = previous,
                    candidate = candidate,
                    tombstone = null,
                ),
                "p9" to PageArtifactRecord(
                    pageKey = "p9",
                    pageVersion = 3L,
                    tombstone = PageTombstoneEvidence(
                        outcome = PageTombstoneOutcome.TOMBSTONED,
                        pageVersion = 3L,
                        recordedAtEpochMs = 60L,
                    ),
                ),
            ),
            expectedPageCount = 2,
            expectedPageCountTrusted = true,
            expectedPageKeys = setOf("p0", "p9"),
            durableFailures = mapOf("p0:TRANSLATION" to failure),
            // CLOSE checkpoint pointer; the REBASE successor reuses the same
            // pointer shape (origin neutrality lives in the sidecar).
            ocrCheckpoints = mapOf("p0" to checkpointPointer(hex)),
            sourceShaByPageKey = mapOf("p0" to hex),
        )

        val encoded = ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), manifest)
        val decoded = ArtifactDocumentJson.decodeFromString(ChapterArtifactManifest.serializer(), encoded)

        decoded shouldBe manifest
        val restoredPage = decoded.pages.getValue("p0")
        // Candidate-scoped snapshot/fingerprint family.
        restoredPage.candidate?.pageSnapshotFingerprint shouldBe candidateFingerprint
        // Committed/previous display family.
        restoredPage.committed?.pageSnapshotFileName shouldBe committed.pageSnapshotFileName
        restoredPage.previousCommitted?.generationId shouldBe "g1"
        // Inventory family: explicit two-field totals plus the prepared keys.
        decoded.expectedPageCount shouldBe 2
        decoded.expectedPageCountTrusted shouldBe true
        decoded.expectedPageKeys shouldBe setOf("p0", "p9")
        // Durable failure family.
        decoded.durableFailures.getValue("p0:TRANSLATION").pageKey shouldBe "p0"
        // Monotonic tombstone evidence family.
        decoded.pages.getValue("p9").tombstone?.outcome shouldBe PageTombstoneOutcome.TOMBSTONED
        // CLOSE/REBASE checkpoint family.
        decoded.ocrCheckpoints.getValue("p0").isWellFormed() shouldBe true
        OcrCheckpointMode.entries.toSet() shouldBe setOf(OcrCheckpointMode.CLOSE, OcrCheckpointMode.REBASE)
    }

    @Test
    fun `unknown future fields are ignored and preserved bytes stay decodable`() {
        val manifest = ChapterArtifactManifest(
            schemaVersion = ChapterArtifactManifest.SCHEMA_VERSION,
            chapterKey = "Chapter 1",
            pages = mapOf("p0" to PageArtifactRecord(pageKey = "p0")),
        )
        val encoded = ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), manifest)
        val withFutureField = encoded.replace(
            "{\"pageKey\":\"p0\"",
            "{\"schema5FutureField\":true,\"pageKey\":\"p0\"",
        )
        val decoded = ArtifactDocumentJson.decodeFromString(
            ChapterArtifactManifest.serializer(),
            withFutureField,
        )
        decoded shouldBe manifest
    }

    @Test
    fun `tombstone evidence is monotonic and refuses contradictory re-records`() {
        val original = PageTombstoneEvidence(
            outcome = PageTombstoneOutcome.TOMBSTONED,
            pageVersion = 5L,
            recordedAtEpochMs = 1L,
        )
        // First evidence always installs.
        original.supersedes(null) shouldBe true
        // A newer version supersedes; a lower version never does.
        original.copy(pageVersion = 6L).supersedes(original) shouldBe true
        original.copy(pageVersion = 4L).supersedes(original) shouldBe false
        // Same-version identical re-record is idempotent.
        original.copy(recordedAtEpochMs = 2L).supersedes(original) shouldBe true
        // A same-version contradiction (different outcome) is refused.
        original.copy(outcome = PageTombstoneOutcome.INVALID).supersedes(original) shouldBe false
    }

    @Test
    fun `a tombstoned page is distinct from a live page record`() {
        val live = PageArtifactRecord(pageKey = "p0", pageVersion = 2L)
        val tombstoned = live.copy(
            tombstone = PageTombstoneEvidence(PageTombstoneOutcome.TOMBSTONED, pageVersion = 2L),
        )
        tombstoned.tombstone shouldNotBe null
        live.tombstone shouldBe null
        ArtifactDocumentJson.decodeFromString(
            PageArtifactRecord.serializer(),
            ArtifactDocumentJson.encodeToString(PageArtifactRecord.serializer(), tombstoned),
        ) shouldBe tombstoned
    }
}
