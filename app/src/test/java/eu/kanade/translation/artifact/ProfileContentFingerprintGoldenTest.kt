package eu.kanade.translation.artifact

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

/**
 * T924-FP-05 / T924-SC-10 golden freeze fixture (Stage-5 slice B, gate
 * "golden freeze fixtures hash-stable across processes"). The fixture under
 * `test/resources/t924/golden/` pins:
 *
 *  1. the FP-05 content fingerprint of a fully populated frozen profile is
 *     STABLE across processes/constructors — the decoded fixture's hash
 *     equals BOTH the golden literal AND the fixture's own
 *     `contentFingerprint` field (self-verifying golden);
 *  2. the contract-mandated rule: a VERSION-ONLY bump yields the SAME
 *     content fingerprint (version is operational ordering only);
 *  3. any hashed content change yields a DIFFERENT fingerprint;
 *  4. the canonical re-encode round trip is byte-stable (T924-SC-06).
 */
class ProfileContentFingerprintGoldenTest {

    private val fixtureName = "/t924/golden/t924-profile-golden-v1.json"

    /**
     * The pinned FP-05 golden hash over the fixture's canonical bytes with
     * the operational fields (`version`, `frozenAtEpochMs`, `sourceRunId`)
     * zeroed and `contentFingerprint` blanked. Recomputed across processes.
     */
    private val goldenContentFingerprint =
        "345def24547780844a5189d777ff09c0b8da8e40910b324ea78e67730ff63386"

    private fun loadFixture(): Pair<String, ChapterTranslationProfile> {
        val text = javaClass.getResourceAsStream(fixtureName)!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val profile = ArtifactDocumentJson.decodeFromString(
            ChapterTranslationProfile.serializer(),
            text,
        )
        return text to profile
    }

    @Test
    fun `golden fixture is semantically valid and canonical-byte-stable`() {
        val (text, profile) = loadFixture()
        profile.validationError().shouldBeNull()
        // Canonical re-encode round trip is byte-identical (the fixture was
        // written in canonical form; the golden hash depends on those bytes).
        ArtifactDocumentJson.encodeToString(
            ChapterTranslationProfile.serializer(),
            profile,
        ) shouldBe text
    }

    @Test
    fun `fp05 golden hash is stable and equals the fixture content fingerprint`() {
        val (_, profile) = loadFixture()
        val computed = StageFingerprints.profileContentFingerprint(profile)
        computed shouldBe goldenContentFingerprint
        computed shouldBe profile.contentFingerprint
    }

    @Test
    fun `version-only bump yields the same content fingerprint`() {
        val (_, profile) = loadFixture()
        val bumped = profile.copy(version = profile.version + 1)
        StageFingerprints.profileContentFingerprint(bumped) shouldBe
            StageFingerprints.profileContentFingerprint(profile)
    }

    @Test
    fun `operational-field-only changes never move the fingerprint`() {
        val (_, profile) = loadFixture()
        val reoperationalized = profile.copy(
            version = 99,
            frozenAtEpochMs = profile.frozenAtEpochMs + 7,
            sourceRunId = "run-other-run",
        )
        StageFingerprints.profileContentFingerprint(reoperationalized) shouldBe
            StageFingerprints.profileContentFingerprint(profile)
    }

    @Test
    fun `any hashed content change moves the fingerprint`() {
        val (_, profile) = loadFixture()
        val aliasChanged = profile.copy(
            entities = profile.entities.mapIndexed { index, fact ->
                if (index == 0) fact.copy(aliases = fact.aliases + "Rei") else fact
            },
        )
        StageFingerprints.profileContentFingerprint(aliasChanged) shouldNotBe
            StageFingerprints.profileContentFingerprint(profile)
    }
}
