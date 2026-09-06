package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.translator.contextual.EnvelopePlannerBlock
import eu.kanade.translation.translator.contextual.EnvelopePlannerPage
import eu.kanade.translation.translator.contextual.EnvelopePlanResult
import eu.kanade.translation.translator.contextual.GlobalEnvelopePlanner
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * T924-ST-11 / SC-20: the ONE-transaction envelope plan publication.
 * Pins: plan sidecar + manifest pointer commit together; the SC-10
 * fingerprint is recomputed before any byte is written and a mismatch is
 * rejected; a byte-identical re-publication is idempotent (same
 * content-addressed name, same pointer); and a pointer without a valid
 * sidecar reads as NOT USABLE (T924-ST-30, never partially trusted).
 */
class EnvelopePlanPublicationTest {

    private val io = FakeChapterDocumentIo()
    private val layout = ChapterArtifactLayout("Chapter 1")
    private val artifact = ChapterArtifactStore(AtomicChapterDocuments(io), layout)

    private fun bootstrapManifest() {
        artifact.publishManifest(ChapterArtifactManifest(chapterKey = "Chapter 1"))
    }

    private fun manifest(): ChapterArtifactManifest =
        artifact.readManifest().shouldNotBeNull()

    /** A valid plan DTO over one pending page, fingerprinted by the pure planner. */
    private fun plan(tag: String = "p1"): EnvelopePlan {
        val result = GlobalEnvelopePlanner.plan(
            pages = listOf(
                EnvelopePlannerPage(
                    pageKey = tag,
                    naturalPageIndex = 0,
                    contentFingerprint = "f".repeat(64),
                    blocks = listOf(
                        EnvelopePlannerBlock(stableBlockId = "${tag}_b0", sourceText = "てすと"),
                    ),
                ),
            ),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 42L,
        )
        return (result as EnvelopePlanResult.Success).plan
    }

    @Test
    fun `plan publishes sidecar and pointer in one transaction`() {
        bootstrapManifest()
        val plan = plan()
        val outcome = EnvelopePlanPublication.publish(artifact, manifest(), plan, nowEpochMs = 7L)

        outcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val pointer = outcome.manifest.envelopePlan.shouldNotBeNull()
        pointer.contentFingerprint shouldBe plan.planFingerprint
        pointer.schemaVersion shouldBe EnvelopePlan.SCHEMA_VERSION

        // The sidecar bytes are durable and the validated read path accepts them.
        val read = EnvelopePlanPublication.readValidatedPlan(artifact, outcome.manifest)
        read.shouldBeInstanceOf<EnvelopePlanPublication.EnvelopePlanRead.Usable>()
            .plan shouldBe plan
        // One publication: the caller's view equals the durable manifest.
        manifest().envelopePlan shouldBe pointer
    }

    @Test
    fun `a recomputed plan fingerprint mismatch is rejected before any byte is written`() {
        bootstrapManifest()
        // A valid plan first, so the rejection proves the PRIOR pointer stays
        // authoritative (not merely that no pointer appeared).
        val published = plan()
        EnvelopePlanPublication.publish(artifact, manifest(), published, nowEpochMs = 7L)
            .shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val priorPointer = manifest().envelopePlan.shouldNotBeNull()

        val tampered = published.copy(planFingerprint = "a".repeat(64))
        val outcome = EnvelopePlanPublication.publish(artifact, manifest(), tampered, 99L)
        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "envelope plan fingerprint mismatch"

        // Prior manifest stays authoritative.
        manifest().envelopePlan shouldBe priorPointer
    }

    @Test
    fun `byte-identical republication is idempotent at the same content address`() {
        bootstrapManifest()
        val plan = plan()
        EnvelopePlanPublication.publish(artifact, manifest(), plan, nowEpochMs = 7L)
            .shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val firstPointer = manifest().envelopePlan.shouldNotBeNull()

        // Superseding attempt with the SAME content: same name, same pointer.
        val again = plan()
        again.planFingerprint shouldBe plan.planFingerprint
        val outcome = EnvelopePlanPublication.publish(artifact, manifest(), again, nowEpochMs = 99L)
        outcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        manifest().envelopePlan shouldBe firstPointer
    }

    @Test
    fun `a pointer without a valid sidecar reads as not usable`() {
        bootstrapManifest()
        val plan = plan()
        EnvelopePlanPublication.publish(artifact, manifest(), plan, nowEpochMs = 7L)
            .shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        // Corrupt the sidecar bytes (crash / torn write simulation).
        val pointer = manifest().envelopePlan.shouldNotBeNull()
        io.write(pointer.fileName, "{corrupt".encodeToByteArray())

        val read = EnvelopePlanPublication.readValidatedPlan(artifact, manifest())
        read.shouldBeInstanceOf<EnvelopePlanPublication.EnvelopePlanRead.NotUsable>()
    }
}
