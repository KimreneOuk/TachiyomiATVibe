package eu.kanade.translation.model

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.persistence.ArtifactStage
import eu.kanade.translation.persistence.DisplayBaseKind
import eu.kanade.translation.persistence.DisplayBaseReference
import eu.kanade.translation.persistence.SourceIdentity
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
    fun `committed bundle fingerprint covers base and stage identities`() {
        val source = SourceIdentity(pageKey = "p", sha256 = "h", width = 1, height = 1, orientation = "n")
        val base = DisplayBaseReference(DisplayBaseKind.CLEANED_IMAGE, fileName = "a.jpg")
        val one = StageFingerprints.committedBundle(source, base, "t1", "l1")
        val two = StageFingerprints.committedBundle(source, base, "t2", "l1")
        one shouldBe StageFingerprints.committedBundle(source, base, "t1", "l1")
        one shouldNotBe two
    }

    // ------------------------------------------------------------------
    // P0A pinned fixtures: the canonical/raw hash core is frozen BEFORE
    // later mechanics changes. Unicode length prefixes, UTF-8 digest,
    // `<null>` encoding, field order, raw-float bits and mask content are
    // byte-level contracts — a golden mismatch means the persisted identity
    // encoding changed, which invalidates every durable fingerprint.
    // ------------------------------------------------------------------

    @Test
    fun `configuration fingerprint golden pins the stage-tagged encoding`() {
        // The exact production OCR/detection identity input set
        // (`PageDecode.batchExpectedFingerprints`): stage tag + model +
        // language/reading-order basis, length-prefixed, UTF-8 digested.
        StageFingerprints.configuration(ArtifactStage.OCR, "manga-ocr", "ja") shouldBe
            "26b96ba2e04f666fa69fbb2f39a83f16c633c392c49b8c03bf1b4de1e9021241"
        StageFingerprints.configuration(ArtifactStage.DETECTION, "manga-ocr", "NATURAL") shouldBe
            "c923f23c8736b1bc30e740620f565094efbe34abf064c8a5db141c466aaa962e"
        // Every input is load-bearing: model, basis and stage tag.
        StageFingerprints.configuration(ArtifactStage.OCR, "paddle-v6", "ja") shouldNotBe
            StageFingerprints.configuration(ArtifactStage.OCR, "manga-ocr", "ja")
        StageFingerprints.configuration(ArtifactStage.OCR, "manga-ocr", "ja") shouldNotBe
            StageFingerprints.configuration(ArtifactStage.DETECTION, "manga-ocr", "ja")
    }

    @Test
    fun `page ocr content golden pins null order raw-float mask and unicode contracts`() {
        val golden = StageFingerprints.pageOcrContentFingerprint(
            pageKey = "0001.jpg",
            naturalPageIndex = null,
            sourceSha256 = "a".repeat(64),
            sourceWidth = 1200,
            sourceHeight = 1800,
            sourceOrientation = "PORTRAIT",
            detectionFingerprint = null,
            ocrFingerprint = "c".repeat(64),
            textless = false,
            inpaintMaskRevision = 10,
            blocks = listOf(
                OcrBlockContent("p1_b1", "テスト", 0f, 20f, 100f, 30f, -0.0f, 1),
                OcrBlockContent(null, "line1\nline2", 10.5f, 20f, 100f, 30f, 0.5f, 2),
            ),
            inpaintMaskBoxes = listOf(
                InpaintMaskBox(0, 0, 100, 40, 1),
                InpaintMaskBox(5, 5, 9, 9, 2),
            ),
        )
        golden shouldBe "3bee3c255661af97bf401dbd9a99ff493c04ecf03c37eb864ed58a0e975d3c67"
        // Repeated computation is byte-stable.
        StageFingerprints.pageOcrContentFingerprint(
            pageKey = "0001.jpg",
            naturalPageIndex = null,
            sourceSha256 = "a".repeat(64),
            sourceWidth = 1200,
            sourceHeight = 1800,
            sourceOrientation = "PORTRAIT",
            detectionFingerprint = null,
            ocrFingerprint = "c".repeat(64),
            textless = false,
            inpaintMaskRevision = 10,
            blocks = listOf(
                OcrBlockContent("p1_b1", "テスト", 0f, 20f, 100f, 30f, -0.0f, 1),
                OcrBlockContent(null, "line1\nline2", 10.5f, 20f, 100f, 30f, 0.5f, 2),
            ),
            inpaintMaskBoxes = listOf(
                InpaintMaskBox(0, 0, 100, 40, 1),
                InpaintMaskBox(5, 5, 9, 9, 2),
            ),
        ) shouldBe golden
    }

    @Test
    fun `raw float bits feed the hash so negative zero differs from zero`() {
        // -0.0f equals 0.0f numerically but not in IEEE-754 raw bits; the
        // geometry contract hashes the BITS, so the two identities differ.
        val negativeZero = StageFingerprints.pageOcrContentFingerprint(
            pageKey = "p",
            naturalPageIndex = 0,
            sourceSha256 = "a".repeat(64),
            sourceWidth = 1,
            sourceHeight = 1,
            sourceOrientation = "n",
            detectionFingerprint = null,
            ocrFingerprint = "c".repeat(64),
            textless = false,
            inpaintMaskRevision = 0,
            blocks = listOf(OcrBlockContent("b", "t", -0.0f, 0f, 1f, 1f, 0f, 1)),
            inpaintMaskBoxes = emptyList(),
        )
        val positiveZero = StageFingerprints.pageOcrContentFingerprint(
            pageKey = "p",
            naturalPageIndex = 0,
            sourceSha256 = "a".repeat(64),
            sourceWidth = 1,
            sourceHeight = 1,
            sourceOrientation = "n",
            detectionFingerprint = null,
            ocrFingerprint = "c".repeat(64),
            textless = false,
            inpaintMaskRevision = 0,
            blocks = listOf(OcrBlockContent("b", "t", 0.0f, 0f, 1f, 1f, 0f, 1)),
            inpaintMaskBoxes = emptyList(),
        )
        negativeZero shouldNotBe positiveZero
    }

    @Test
    fun `raw sha256 digest and source excerpt goldens pin the utf-8 byte core`() {
        StageFingerprints.sha256Hex("tachiyomi".encodeToByteArray()) shouldBe
            "78264ef96df7e424d7133c0d482e10530f4aa3ef13c91527efd321e68f6f6270"
        StageFingerprints.sourceExcerptHash("ラーメン") shouldBe
            "d4c69a8b18bc53ffd71d560c3142587cb71e3ee9c9752a879b1e63a89bd0c11e"
        // The digest is over UTF-8 bytes: the multibyte excerpt must equal
        // hashing its exact UTF-8 encoding.
        StageFingerprints.sourceExcerptHash("ラーメン") shouldBe
            StageFingerprints.sha256Hex("ラーメン".encodeToByteArray())
    }
}
