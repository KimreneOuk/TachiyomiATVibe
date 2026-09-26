package eu.kanade.translation.persistence.artifact

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
    fun `committed bundle fingerprint covers base and stage identities`() {
        val source = SourceIdentity(pageKey = "p", sha256 = "h", width = 1, height = 1, orientation = "n")
        val base = DisplayBaseReference(DisplayBaseKind.CLEANED_IMAGE, fileName = "a.jpg")
        val one = StageFingerprints.committedBundle(source, base, "t1", "l1")
        val two = StageFingerprints.committedBundle(source, base, "t2", "l1")
        one shouldBe StageFingerprints.committedBundle(source, base, "t1", "l1")
        one shouldNotBe two
    }
}
