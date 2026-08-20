package eu.kanade.translation.artifact

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class StageFingerprintsTest {

    @Test
    fun `detection fingerprint is deterministic and input-sensitive`() {
        val base = StageFingerprints.detection(
            sourceHash = "s1",
            detectorModelHash = "d1",
            segmenterModelHash = "g1",
            nativeProtocolVersion = 1,
            thresholds = "t1",
            maskPostprocessVersion = 1,
            panelAssignmentVersion = 1,
            readingOrderVersion = 1,
        )
        val same = StageFingerprints.detection(
            sourceHash = "s1",
            detectorModelHash = "d1",
            segmenterModelHash = "g1",
            nativeProtocolVersion = 1,
            thresholds = "t1",
            maskPostprocessVersion = 1,
            panelAssignmentVersion = 1,
            readingOrderVersion = 1,
        )
        base shouldBe same
        StageFingerprints.detection(
            sourceHash = "s2",
            detectorModelHash = "d1",
            segmenterModelHash = "g1",
            nativeProtocolVersion = 1,
            thresholds = "t1",
            maskPostprocessVersion = 1,
            panelAssignmentVersion = 1,
            readingOrderVersion = 1,
        ) shouldNotBe base
    }

    @Test
    fun `fingerprints of different stages never collide`() {
        val detection = StageFingerprints.detection(
            sourceHash = "x",
            detectorModelHash = "x",
            segmenterModelHash = "x",
            nativeProtocolVersion = 1,
            thresholds = "x",
            maskPostprocessVersion = 1,
            panelAssignmentVersion = 1,
            readingOrderVersion = 1,
        )
        val inpaint = StageFingerprints.inpaint(
            sourceHash = "x",
            maskArtifactId = "x",
            inpaintEngineVersion = "x",
            inpaintModelHash = "x",
            inpaintMode = "x",
            inpaintSettings = "x",
            cleanupRevision = 1,
        )
        detection shouldNotBe inpaint
    }

    @Test
    fun `length-prefixed fields resist delimiter collisions`() {
        val a = StageFingerprints.inpaint(
            sourceHash = "ab",
            maskArtifactId = "c",
            inpaintEngineVersion = "d",
            inpaintModelHash = "e",
            inpaintMode = "f",
            inpaintSettings = "g",
            cleanupRevision = 1,
        )
        val b = StageFingerprints.inpaint(
            sourceHash = "a",
            maskArtifactId = "bc",
            inpaintEngineVersion = "d",
            inpaintModelHash = "e",
            inpaintMode = "f",
            inpaintSettings = "g",
            cleanupRevision = 1,
        )
        a shouldNotBe b
    }

    @Test
    fun `translation fingerprint includes context checkpoint and glossary version`() {
        fun fp(checkpoint: String, glossary: String) = StageFingerprints.translation(
            orderedOcrBlockIdsAndTextHashes = listOf("id1"),
            sourceLanguage = "ja",
            targetLanguage = "en",
            provider = "p",
            model = "m",
            modelSettings = "s",
            promptProtocolVersion = 1,
            contextInputCheckpointHash = checkpoint,
            glossaryVersion = glossary,
            profileLedgerVersion = "v1",
            batchRelationshipAmbiguityPriorValue = "MALE_FEMALE",
            ambiguityPriorSchemaVersion = 1,
        )
        fp("cp1", "g1") shouldBe fp("cp1", "g1")
        fp("cp2", "g1") shouldNotBe fp("cp1", "g1")
        fp("cp1", "g2") shouldNotBe fp("cp1", "g1")
    }

    @Test
    fun `glossary version is order-independent and content-sensitive`() {
        val one = StageFingerprints.glossaryVersion(mapOf("a" to "1", "b" to "2"))
        val reordered = StageFingerprints.glossaryVersion(mapOf("b" to "2", "a" to "1"))
        val changed = StageFingerprints.glossaryVersion(mapOf("a" to "1", "b" to "3"))
        one shouldBe reordered
        one shouldNotBe changed
    }

    @Test
    fun `glossary key-value composites cannot collide across splits`() {
        // Under a comma/equals composite join these two maps produced the
        // identical string "a=b,c=d"; per-element encoding must separate them.
        val one = StageFingerprints.glossaryVersion(mapOf("a" to "b,c=d"))
        val two = StageFingerprints.glossaryVersion(mapOf("a" to "b", "c" to "d"))
        one shouldNotBe two
    }

    @Test
    fun `ordered-list composites cannot collide across element splits`() {
        fun fp(ids: List<String>) = StageFingerprints.translation(
            orderedOcrBlockIdsAndTextHashes = ids,
            sourceLanguage = "ja",
            targetLanguage = "en",
            provider = "p",
            model = "m",
            modelSettings = "s",
            promptProtocolVersion = 1,
            contextInputCheckpointHash = "cp",
            glossaryVersion = "g",
            profileLedgerVersion = "v",
            batchRelationshipAmbiguityPriorValue = "NEUTRAL",
            ambiguityPriorSchemaVersion = 1,
        )
        // A comma join would make both lists the string "a,b,c".
        fp(listOf("a,b", "c")) shouldNotBe fp(listOf("a", "b,c"))
        fp(listOf("a", "b")) shouldBe fp(listOf("a", "b"))
    }

    @Test
    fun `committed bundle fingerprint covers base and stage identities`() {
        val source = SourceIdentity(pageKey = "p", sha256 = "h", width = 1, height = 1, orientation = "n")
        val base = DisplayBaseReference(DisplayBaseKind.CLEANED_IMAGE, fileName = "a.jpg")
        val one = StageFingerprints.committedBundle(source, base, "t1", "l1")
        val two = StageFingerprints.committedBundle(source, base, "t2", "l1")
        one shouldBe StageFingerprints.committedBundle(source, base, "t1", "l1")
        one shouldNotBe two
    }
}
