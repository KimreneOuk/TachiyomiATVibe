package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.StageFingerprints
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * T924 S4 pure-planner gates for the global OCR corpus manifest
 * (T924-FP-03; WP3): deterministic assembly, permutation invariance,
 * never-guess ordering, and the pure gap/missing-page detector matrix.
 */
class OcrCorpusManifestTest {

    private fun entry(
        pageKey: String,
        naturalPageIndex: Int?,
        trusted: Boolean = true,
        contentSeed: Char = 'a',
    ) = OcrCorpusPageEntry(
        pageKey = pageKey,
        naturalPageIndex = naturalPageIndex,
        contentFingerprint = StageFingerprints.pageOcrContentFingerprint(
            pageKey = pageKey,
            naturalPageIndex = naturalPageIndex,
            sourceSha256 = contentSeed.toString().repeat(64),
            sourceWidth = 800,
            sourceHeight = 1200,
            sourceOrientation = "PORTRAIT",
            detectionFingerprint = ("d").repeat(64),
            ocrFingerprint = ("o").repeat(64),
            textless = false,
            inpaintMaskRevision = 10,
            blocks = emptyList(),
            inpaintMaskBoxes = emptyList(),
        ),
        trusted = trusted,
    )

    @Test
    fun `assembles natural order regardless of input order`() {
        val input = listOf(
            entry("0002.jpg", 2),
            entry("0000.jpg", 0),
            entry("0003.jpg", 3),
            entry("0001.jpg", 1),
        )
        val forward = OcrCorpusManifest.assemble(input, expectedPageCount = 4, expectedPageCountTrusted = true)
        val reversed = OcrCorpusManifest.assemble(input.reversed(), expectedPageCount = 4, expectedPageCountTrusted = true)

        forward.naturalOrderProven shouldBe true
        forward.orderedPages.map { it.pageKey } shouldBe listOf("0000.jpg", "0001.jpg", "0002.jpg", "0003.jpg")
        forward.corpusFingerprint shouldBe reversed.corpusFingerprint
        forward.gaps.isComplete shouldBe true
    }

    @Test
    fun `corpus fingerprint equals the StageFingerprints oracle over ordered pairs`() {
        val input = listOf(
            entry("0001.jpg", 1),
            entry("0000.jpg", 0),
        )
        val manifest = OcrCorpusManifest.assemble(input, expectedPageCount = 2, expectedPageCountTrusted = true)
        val oracle = StageFingerprints.ocrCorpusFingerprint(
            pages = listOf(
                "0000.jpg" to manifest.orderedPages.first { it.pageKey == "0000.jpg" }.contentFingerprint,
                "0001.jpg" to manifest.orderedPages.first { it.pageKey == "0001.jpg" }.contentFingerprint,
            ),
            expectedPageCount = 2,
            expectedPageCountTrusted = true,
            naturalOrderProven = true,
        )
        manifest.corpusFingerprint shouldBe oracle
    }

    @Test
    fun `unproven order degrades to sorted pageKey order and changes the fingerprint`() {
        val input = listOf(
            entry("0002.jpg", null),
            entry("0001.jpg", null),
            entry("0003.jpg", null),
        )
        val manifest = OcrCorpusManifest.assemble(input, expectedPageCount = 3, expectedPageCountTrusted = true)

        manifest.naturalOrderProven shouldBe false
        manifest.gaps.orderDegraded shouldBe true
        manifest.orderedPages.map { it.pageKey } shouldBe listOf("0001.jpg", "0002.jpg", "0003.jpg")
        manifest.gaps.gapFreePrefixLength shouldBe 0
        manifest.gaps.missingNaturalIndexes shouldBe emptyList()

        val provenEquivalent = OcrCorpusManifest.assemble(
            listOf(
                entry("0002.jpg", 1),
                entry("0001.jpg", 0),
                entry("0003.jpg", 2),
            ),
            expectedPageCount = 3,
            expectedPageCountTrusted = true,
        )
        manifest.corpusFingerprint shouldNotBe provenEquivalent.corpusFingerprint
    }

    @Test
    fun `missing middle page is detected and truncates the gap-free prefix`() {
        val input = listOf(
            entry("0000.jpg", 0),
            entry("0001.jpg", 1),
            entry("0003.jpg", 3),
        )
        val manifest = OcrCorpusManifest.assemble(input, expectedPageCount = 4, expectedPageCountTrusted = true)

        manifest.gaps.missingNaturalIndexes shouldBe listOf(2)
        manifest.gaps.gapFreePrefixLength shouldBe 2
        manifest.gaps.presentPageCount shouldBe 3
        manifest.gaps.isComplete shouldBe false
    }

    @Test
    fun `expected count greater than present reports the missing tail`() {
        val input = listOf(entry("0000.jpg", 0))
        val manifest = OcrCorpusManifest.assemble(input, expectedPageCount = 3, expectedPageCountTrusted = true)

        manifest.gaps.missingNaturalIndexes shouldBe listOf(1, 2)
        manifest.gaps.gapFreePrefixLength shouldBe 1
        manifest.gaps.isComplete shouldBe false
    }

    @Test
    fun `trusted and untrusted pages are counted separately`() {
        val input = listOf(
            entry("0000.jpg", 0, trusted = true),
            entry("0001.jpg", 1, trusted = false),
            entry("0002.jpg", 2, trusted = false),
        )
        val manifest = OcrCorpusManifest.assemble(input, expectedPageCount = 3, expectedPageCountTrusted = true)

        manifest.trustedPageCount shouldBe 1
        manifest.untrustedPageCount shouldBe 2
        // Trust is not completeness: counts only.
        manifest.gaps.isComplete shouldBe true
    }

    @Test
    fun `duplicate page keys are recorded and deduped deterministically`() {
        val first = entry("0000.jpg", 0)
        val duplicate = entry("0000.jpg", 0, trusted = false, contentSeed = 'b')
        val forward = OcrCorpusManifest.assemble(listOf(first, duplicate), expectedPageCount = 1, expectedPageCountTrusted = true)
        val swapped = OcrCorpusManifest.assemble(listOf(duplicate, first), expectedPageCount = 1, expectedPageCountTrusted = true)

        forward.gaps.duplicatePageKeys shouldBe listOf("0000.jpg")
        forward.gaps.isComplete shouldBe false
        forward.gaps.presentPageCount shouldBe 1
        // Deterministic retention: canonical (pageKey) order picks the same entry.
        forward.corpusFingerprint shouldBe swapped.corpusFingerprint
        forward.naturalOrderProven shouldBe false
    }

    @Test
    fun `duplicate natural indexes degrade the order`() {
        val input = listOf(
            entry("0000.jpg", 0),
            entry("0001.jpg", 0),
        )
        val manifest = OcrCorpusManifest.assemble(input, expectedPageCount = 2, expectedPageCountTrusted = true)

        manifest.naturalOrderProven shouldBe false
        manifest.gaps.duplicateNaturalIndexes shouldBe listOf(0)
        manifest.orderedPages.map { it.pageKey } shouldBe listOf("0000.jpg", "0001.jpg")
        manifest.gaps.isComplete shouldBe false
    }

    @Test
    fun `beyond-expected natural index is reported`() {
        val input = listOf(
            entry("0000.jpg", 0),
            entry("0009.jpg", 9),
        )
        val manifest = OcrCorpusManifest.assemble(input, expectedPageCount = 1, expectedPageCountTrusted = true)

        manifest.gaps.beyondExpectedIndexes shouldBe listOf(9)
        manifest.gaps.isComplete shouldBe false
    }

    @Test
    fun `empty corpus with zero expected pages is complete and deterministic`() {
        val manifest = OcrCorpusManifest.assemble(emptyList(), expectedPageCount = 0, expectedPageCountTrusted = true)

        manifest.gaps.isComplete shouldBe true
        manifest.naturalOrderProven shouldBe true
        manifest.gaps.presentPageCount shouldBe 0
        manifest.corpusFingerprint shouldBe
            OcrCorpusManifest.assemble(emptyList(), expectedPageCount = 0, expectedPageCountTrusted = true)
                .corpusFingerprint
    }

    @Test
    fun `expected count trusted flag reaches the fingerprint`() {
        val input = listOf(entry("0000.jpg", 0))
        val trusted = OcrCorpusManifest.assemble(input, expectedPageCount = 1, expectedPageCountTrusted = true)
        val untrusted = OcrCorpusManifest.assemble(input, expectedPageCount = 1, expectedPageCountTrusted = false)
        trusted.corpusFingerprint shouldNotBe untrusted.corpusFingerprint
    }

    @Test
    fun `contributing fingerprint is canonical over the subset`() {
        val manifest = OcrCorpusManifest.assemble(
            listOf(
                entry("0000.jpg", 0),
                entry("0001.jpg", 1),
                entry("0002.jpg", 2),
            ),
            expectedPageCount = 3,
            expectedPageCountTrusted = true,
        )
        // Argument order never matters: the subset is taken in canonical
        // manifest order (same design as the corpus fingerprint itself).
        val forward = manifest.contributingFingerprint(listOf("0000.jpg", "0001.jpg"))
        forward shouldBe manifest.contributingFingerprint(listOf("0001.jpg", "0000.jpg"))
        // Different subsets produce different fingerprints.
        manifest.contributingFingerprint(listOf("0000.jpg", "0001.jpg")) shouldNotBe
            manifest.contributingFingerprint(listOf("0001.jpg", "0002.jpg"))
        manifest.contributingFingerprint(listOf("0000.jpg", "0001.jpg")) shouldNotBe
            manifest.corpusFingerprint
    }

    @Test
    fun `permuted input order never changes the manifest (100 seeded iterations)`() {
        val base = listOf(
            entry("0000.jpg", 0),
            entry("0001.jpg", 1, trusted = false),
            entry("0002.jpg", 2),
            entry("0003.jpg", 3, trusted = false),
            entry("0004.jpg", 4),
        )
        val expected = OcrCorpusManifest.assemble(base, expectedPageCount = 5, expectedPageCountTrusted = true)
        var seed = 0x5EED_0001L
        repeat(100) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val shuffled = base.shuffled(java.util.Random(seed))
            val manifest = OcrCorpusManifest.assemble(shuffled, expectedPageCount = 5, expectedPageCountTrusted = true)
            manifest.corpusFingerprint shouldBe expected.corpusFingerprint
            manifest.orderedPages.map { it.pageKey } shouldBe expected.orderedPages.map { it.pageKey }
            manifest.gaps shouldBe expected.gaps
        }
    }
}
