package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageDisplayState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Durable-manifest format fixtures. Every format family the consumers need —
 * candidate-scoped snapshot/fingerprint, committed/previous display, inventory,
 * and durable failures — round-trips through the
 * shared durable-document Json, ordinary emission stays byte-compatible.
 */
class Schema5FormatPreparationTest {

    private val hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `ordinary emission keeps the durable fields neutral`() {
        val ordinary = ChapterArtifactManifest(chapterKey = "Chapter 1")
        ordinary.schemaVersion shouldBe ChapterArtifactManifest.SCHEMA_VERSION
        ordinary.pages shouldBe emptyMap()
        val encoded = ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), ordinary)
        val decoded = ArtifactDocumentJson.decodeFromString(ChapterArtifactManifest.serializer(), encoded)
        decoded shouldBe ordinary
    }

    // ------------------------------------------------------------------
    // Round-trips of every retained format family.
    // ------------------------------------------------------------------

    @Test
    fun `durable format families round-trip through the shared durable document Json`() {
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
                ),
                "p9" to PageArtifactRecord(
                    pageKey = "p9",
                    pageVersion = 3L,
                ),
            ),
            expectedPageCount = 2,
            expectedPageCountTrusted = true,
            durableFailures = mapOf("p0:TRANSLATION" to failure),
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
        // Inventory family: explicit two-field totals.
        decoded.expectedPageCount shouldBe 2
        decoded.expectedPageCountTrusted shouldBe true
        // Durable failure family.
        decoded.durableFailures.getValue("p0:TRANSLATION").pageKey shouldBe "p0"
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
}
