package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.LegacyMigrationHealth
import eu.kanade.translation.artifact.LegacyMigrationMetadata
import eu.kanade.translation.translator.contextual.EnvelopePlanResult
import eu.kanade.translation.translator.contextual.EnvelopePlannerBlock
import eu.kanade.translation.translator.contextual.EnvelopePlannerPage
import eu.kanade.translation.translator.contextual.GlobalEnvelopePlanner
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * 11 / SC-20: the ONE-transaction envelope plan publication.
 * Pins: plan sidecar + manifest pointer commit together; the SC-10
 * fingerprint is recomputed before any byte is written and a mismatch is
 * rejected; a byte-identical re-publication is idempotent (same
 * content-addressed name, same pointer); and a pointer without a valid
 * sidecar reads as NOT USABLE ( never partially trusted).
 */
class EnvelopePlanPublicationTest {

    private val io = FakeChapterDocumentIo()
    private val layout = ChapterArtifactLayout("Chapter 1")
    private val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

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

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
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
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val priorPointer = manifest().envelopePlan.shouldNotBeNull()

        val tampered = published.copy(planFingerprint = "a".repeat(64))
        val outcome = EnvelopePlanPublication.publish(artifact, manifest(), tampered, 99L)
        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "envelope plan fingerprint mismatch"

        // Prior manifest stays authoritative.
        manifest().envelopePlan shouldBe priorPointer
    }

    @Test
    fun `byte-identical republication is idempotent at the same content address`() {
        bootstrapManifest()
        val plan = plan()
        EnvelopePlanPublication.publish(artifact, manifest(), plan, nowEpochMs = 7L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val firstPointer = manifest().envelopePlan.shouldNotBeNull()

        // Superseding attempt with the SAME content: same name, same pointer.
        val again = plan()
        again.planFingerprint shouldBe plan.planFingerprint
        val outcome = EnvelopePlanPublication.publish(artifact, manifest(), again, nowEpochMs = 99L)
        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        manifest().envelopePlan shouldBe firstPointer
    }

    @Test
    fun `a pointer without a valid sidecar reads as not usable`() {
        bootstrapManifest()
        val plan = plan()
        EnvelopePlanPublication.publish(artifact, manifest(), plan, nowEpochMs = 7L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        // Corrupt the sidecar bytes (crash / torn write simulation).
        val pointer = manifest().envelopePlan.shouldNotBeNull()
        io.write(pointer.fileName, "{corrupt".encodeToByteArray())

        val read = EnvelopePlanPublication.readValidatedPlan(artifact, manifest())
        read.shouldBeInstanceOf<EnvelopePlanPublication.EnvelopePlanRead.NotUsable>()
    }

    // ------------------------------------------------------------------
    //  LI-x: the resume rebuild adopts durable checkpoints page by page
    // (each adoption republishing the manifest) and the >8-page open path's
    // background health verify republishes the VERIFIED manifest behind the
    // façade's back — so the caller's plan-publish snapshot is stale by
    // construction and the whole batch aborted with PERSISTENCE_REJECTED.
    // The publication now rebases onto the fresh durable manifest ONCE on a
    // stale-manifest rejection only.
    // ------------------------------------------------------------------

    /**
     * Mirrors the real background artifact-health writer
     * republishes the manifest with the VERIFIED health marker and a bumped
     * timestamp, behind the caller's back.
     */
    private fun bumpBehindCallersBack(nowEpochMs: Long) {
        val current = manifest()
        val metadata = (current.legacyMigration ?: LegacyMigrationMetadata(sourceFileName = "Chapter 1.json"))
            .copy(
                health = LegacyMigrationHealth.VERIFIED,
                lastVerifiedByVersionCode = 63L,
                lastVerifiedAtEpochMs = nowEpochMs,
            )
        check(artifact.publishManifest(current.copy(legacyMigration = metadata, updatedAtEpochMs = nowEpochMs))) {
            "fixture: concurrent verify publication failed"
        }
    }

    @Test
    fun `plan publication rebases onto a drifted durable manifest and preserves the concurrent change`() {
        bootstrapManifest()
        val callerSnapshot = manifest()
        // The rebuild's adoptions / background verify drift the durable
        // manifest AFTER the caller cached its snapshot.
        bumpBehindCallersBack(nowEpochMs = 2L)

        val plan = plan()
        val outcome = EnvelopePlanPublication.publish(artifact, callerSnapshot, plan, nowEpochMs = 3L)

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val durable = manifest()
        // The plan pointer landed on the FRESH manifest…
        durable.envelopePlan.shouldNotBeNull().contentFingerprint shouldBe plan.planFingerprint
        committed.manifest shouldBe durable
        // …and the intervening durable change was NOT reverted.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
        durable.updatedAtEpochMs shouldBe 3L
        EnvelopePlanPublication.readValidatedPlan(artifact, durable)
            .shouldBeInstanceOf<EnvelopePlanPublication.EnvelopePlanRead.Usable>()
    }

    @Test
    fun `a non-stale rejection still fails as-is without a retry`() {
        bootstrapManifest()
        // The manifest is CURRENT; the plan sidecar promotion rename is armed
        // to fail — the resulting rejection is a real publication failure,
        // not the stale-manifest CAS.
        val plan = plan()
        val fileName = artifact.envelopePlanSidecarName(plan.planFingerprint)
        io.ownedRenamesToFail += "$fileName.tmp"

        val outcome = EnvelopePlanPublication.publish(artifact, manifest(), plan, nowEpochMs = 7L)

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "sidecar publication failed"
        // Exactly ONE sidecar write attempt — no rebase-retry on a non-stale
        // rejection — and the manifest stays authoritative and untouched.
        io.writtenNames.count { it.contains(fileName) } shouldBe 1
        manifest().envelopePlan shouldBe null
    }

    @Test
    fun `retry exhaustion surfaces the original stale rejection without looping`() {
        bootstrapManifest()
        val callerSnapshot = manifest()
        bumpBehindCallersBack(nowEpochMs = 2L)

        // Attempt 1 is stale-rejected before any byte is written; the armed
        // sidecar failure then fails the ONE retry — the ORIGINAL stale
        // rejection must surface, with no second retry loop.
        val plan = plan()
        val fileName = artifact.envelopePlanSidecarName(plan.planFingerprint)
        io.ownedRenamesToFail += "$fileName.tmp"

        val outcome = EnvelopePlanPublication.publish(artifact, callerSnapshot, plan, nowEpochMs = 3L)

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldContain "stale manifest snapshot"
        // Exactly ONE sidecar write attempt (the retry; the stale first
        // attempt never reaches the sidecar), and NOTHING was published —
        // the concurrent VERIFIED marker stands, no plan pointer moved.
        io.writtenNames.count { it.contains(fileName) } shouldBe 1
        manifest().envelopePlan shouldBe null
        manifest().legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
    }
}
