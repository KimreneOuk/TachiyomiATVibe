package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.FactConflictState
import eu.kanade.translation.artifact.FactProvenance
import eu.kanade.translation.artifact.FactScope
import eu.kanade.translation.artifact.FactType
import eu.kanade.translation.artifact.EvidenceStrength
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.ProfileFact
import eu.kanade.translation.artifact.StageFingerprints
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * T924-TX-22 / ST-10: the ONE-transaction profile freeze publication.
 * Pins: pointer + sidecar commit together; any rejection leaves the PRIOR
 * manifest authoritative; supersede = new pointer + old file untouched;
 * a recomputed-FP-05 mismatch is rejected; and a pointer without a valid
 * sidecar reads as UNFROZEN (T924-ST-30, never partially trusted).
 */
class ProfileFreezePublicationTest {

    private val io = FakeChapterDocumentIo()
    private val layout = ChapterArtifactLayout("Chapter 1")
    private val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun bootstrapManifest() {
        artifact.publishManifest(ChapterArtifactManifest(chapterKey = "Chapter 1"))
    }

    private fun manifest(): ChapterArtifactManifest =
        artifact.readManifest().shouldNotBeNull()

    /** A valid frozen profile DTO with its field-set FP-05 content hash. */
    private fun profile(version: Int, target: String = "Kyle", inputTag: String = "input-1") =
        draft(version, target, inputTag).let { d ->
            d.copy(contentFingerprint = StageFingerprints.profileContentFingerprint(d))
        }

    private fun draft(version: Int, target: String, inputTag: String) =
        ChapterTranslationProfile(
            version = version,
            contentFingerprint = "",
            profileInputFingerprint = hex64(inputTag),
            sourceRunId = "run-1",
            analyzerProvenance = AnalyzerProvenance("fake", "fake-model", 1, 1, "sig"),
            entities = listOf(
                ProfileFact(
                    factId = "f-1",
                    type = FactType.ENTITY_IDENTITY,
                    canonicalSourceForm = "カイル",
                    canonicalTargetForm = target,
                    evidenceStrength = EvidenceStrength.STRONG_CONTEXTUAL,
                    evidenceRefs = listOf(EvidenceRef("p1", "p1_b1", hex64("excerpt"))),
                    scope = FactScope.CANONICAL_CHAPTER_WIDE,
                    provenance = FactProvenance.CHAPTER_ANALYSIS,
                    conflictState = FactConflictState.RESOLVED,
                ),
            ),
            frozenAtEpochMs = 42L,
        )

    @Test
    fun `freeze publishes sidecar and pointer in one transaction`() {
        bootstrapManifest()
        val profile = profile(1)
        val outcome = ProfileFreezePublication.publish(artifact, manifest(), profile, nowEpochMs = 7L)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val pointer = outcome.manifest.profile.shouldNotBeNull()
        pointer.version shouldBe 1
        pointer.contentFingerprint shouldBe profile.contentFingerprint
        pointer.profileInputFingerprint shouldBe profile.profileInputFingerprint
        pointer.schemaVersion shouldBe ChapterTranslationProfile.SCHEMA_VERSION

        // The sidecar bytes are durable and the reuse read path validates them.
        val read = ProfileFreezePublication.readReusableFrozenProfile(
            artifact,
            outcome.manifest,
            expectedInputFingerprint = profile.profileInputFingerprint,
        )
        read.shouldBeInstanceOf<ProfileFreezePublication.FrozenProfileRead.Reusable>()
            .profile shouldBe profile
        // The committed manifest equals the caller's view (one publication).
        manifest().profile shouldBe pointer
    }

    @Test
    fun `a recomputed fp05 mismatch is rejected before any byte is written`() {
        bootstrapManifest()
        val tampered = profile(1).copy(contentFingerprint = hex64("tampered"))
        val outcome = ProfileFreezePublication.publish(artifact, manifest(), tampered, 7L)
        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldContain "content fingerprint mismatch"
        // Nothing was published: the manifest has no profile pointer and no
        // profile sidecar file exists.
        manifest().profile.shouldBeNull()
        io.files.keys.none { it.contains("/profiles/") } shouldBe true
    }

    @Test
    fun `version not monotonic is rejected`() {
        bootstrapManifest()
        ProfileFreezePublication.publish(artifact, manifest(), profile(2), 7L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldContain "version not monotonic"
        ProfileFreezePublication.publish(artifact, manifest(), profile(1), 7L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        // v1 is now frozen; v1 again is NOT monotonic.
        ProfileFreezePublication.publish(artifact, manifest(), profile(1), 8L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldContain "version not monotonic"
    }

    @Test
    fun `rejection leaves the prior manifest authoritative`() {
        bootstrapManifest()
        val v1 = profile(1)
        ProfileFreezePublication.publish(artifact, manifest(), v1, 7L)
        val priorPointer = manifest().profile.shouldNotBeNull()
        val priorFileBytes = io.files[priorPointer.fileName].shouldNotBeNull()

        // A v2 whose content fingerprint does not match its content.
        val badV2 = draft(2, target = "Kaijl", inputTag = "input-1")
            .copy(contentFingerprint = hex64("not-the-real-hash"))
        ProfileFreezePublication.publish(artifact, manifest(), badV2, 8L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()

        val current = manifest().profile.shouldNotBeNull()
        current shouldBe priorPointer
        io.files[current.fileName] shouldBe priorFileBytes
    }

    @Test
    fun `supersede is a new pointer and the old file stays untouched`() {
        bootstrapManifest()
        val v1 = profile(1, target = "Kyle")
        ProfileFreezePublication.publish(artifact, manifest(), v1, 7L)
        val v1Pointer = manifest().profile.shouldNotBeNull()
        val v1Bytes = io.files[v1Pointer.fileName].shouldNotBeNull()

        val v2 = profile(2, target = "Kaijl") // different content, same inputs
        ProfileFreezePublication.publish(artifact, manifest(), v2, 8L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        val v2Pointer = manifest().profile.shouldNotBeNull()
        v2Pointer.version shouldBe 2
        v2Pointer.contentFingerprint shouldNotBe v1Pointer.contentFingerprint
        // New content-addressed file; the prior bytes survive verbatim.
        io.files[v2Pointer.fileName].shouldNotBeNull()
        io.files[v1Pointer.fileName] shouldBe v1Bytes
    }

    @Test
    fun `manifest publication failure leaves prior manifest and an orphan sidecar`() {
        bootstrapManifest()
        ProfileFreezePublication.publish(artifact, manifest(), profile(1), 7L)
        val prior = manifest().profile.shouldNotBeNull()

        io.writeNamesToFail.add(layout.manifestFileName)
        ProfileFreezePublication.publish(artifact, manifest(), profile(2, target = "Kaijl"), 8L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldContain "prior manifest remains authoritative"
        manifest().profile shouldBe prior
    }

    @Test
    fun `pointer without a valid sidecar reads as unfrozen`() {
        bootstrapManifest()
        val profile = profile(1)
        ProfileFreezePublication.publish(artifact, manifest(), profile, 7L)
        val pointer = manifest().profile.shouldNotBeNull()
        val fileName = pointer.fileName

        fun reusable() = ProfileFreezePublication.readReusableFrozenProfile(
            artifact,
            manifest(),
            expectedInputFingerprint = profile.profileInputFingerprint,
        )

        reusable().shouldBeInstanceOf<ProfileFreezePublication.FrozenProfileRead.Reusable>()

        // (a) sidecar bytes vanish — unfrozen, never partially trusted.
        io.files.remove(fileName)
        reusable().shouldBeInstanceOf<ProfileFreezePublication.FrozenProfileRead.NotReusable>()

        // (b) sidecar bytes corrupt — unfrozen (quarantined as absent).
        io.files[fileName] = "{not-a-profile".encodeToByteArray()
        reusable().shouldBeInstanceOf<ProfileFreezePublication.FrozenProfileRead.NotReusable>()

        // (c) identity mismatch (input fingerprint changed under the pointer).
        io.files[fileName] = artifactBytes(profile)
        ProfileFreezePublication.readReusableFrozenProfile(
            artifact,
            manifest(),
            expectedInputFingerprint = hex64("other-input"),
        ).shouldBeInstanceOf<ProfileFreezePublication.FrozenProfileRead.NotReusable>()

        // (d) future-schema sidecar: preserved read-only, still unfrozen.
        val future = profile.copy(
            schemaVersion = ChapterTranslationProfile.SCHEMA_VERSION + 1,
        )
        io.files[fileName] = ArtifactDocumentJson
            .encodeToString(ChapterTranslationProfile.serializer(), future)
            .encodeToByteArray()
        val futureRead = ProfileFreezePublication.readReusableFrozenProfile(
            artifact,
            manifest(),
            expectedInputFingerprint = profile.profileInputFingerprint,
        )
        futureRead.shouldBeInstanceOf<ProfileFreezePublication.FrozenProfileRead.NotReusable>()
        // Future bytes were NOT quarantined away by the read (preserved).
        io.files.containsKey(fileName) shouldBe true
    }

    /** Encodes [profile] through the shared canonical Json (T924-SC-06). */
    private fun artifactBytes(profile: ChapterTranslationProfile): ByteArray =
        ArtifactDocumentJson
            .encodeToString(ChapterTranslationProfile.serializer(), profile)
            .encodeToByteArray()
}
