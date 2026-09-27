package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards [OcrBlockDeduper.dedupeGeometricOverlaps] — the single
 * geometric dedupe that closes the gaps the recognition engine's own dedupe
 * stages leave open (cross-label overlaps, no-parent overlaps, differing-text
 * overlaps). Each case mirrors a real way two blocks end up overlapping and
 * rendering on top of each other.
 */
class OcrBlockDeduperTest {

    private fun block(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        score: Float = 1f,
        label: Int = 1,
    ) = TranslationBlock(
        text = text,
        translation = "",
        width = w,
        height = h,
        x = x,
        y = y,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        label = label,
        score = score,
    )

    @Test
    fun `empty input returns empty`() {
        OcrBlockDeduper.dedupeGeometricOverlaps(emptyList()) shouldHaveSize 0
    }

    @Test
    fun `single block is returned unchanged`() {
        val blocks = listOf(block(x = 0f, y = 0f, w = 50f, h = 20f, text = "only", score = 0.9f))

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(blocks)

        out shouldContainExactly blocks
    }

    @Test
    fun `two non-overlapping blocks are both kept`() {
        // Adjacent boxes — no IoU, no containment, centres far apart.
        val a = block(x = 0f, y = 0f, w = 40f, h = 20f, text = "A", score = 0.9f)
        val b = block(x = 100f, y = 100f, w = 40f, h = 20f, text = "B", score = 0.5f)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(a, b))

        out shouldContainExactly listOf(a, b)
    }

    @Test
    fun `two touching bubbles with low overlap are both kept`() {
        // Two real bubbles that merely touch at an edge — small intersection,
        // low IoU, centres far apart. Must NOT be collapsed into one.
        val a = block(x = 0f, y = 0f, w = 50f, h = 50f, text = "A", score = 0.9f)
        val b = block(x = 48f, y = 0f, w = 50f, h = 50f, text = "B", score = 0.5f)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(a, b))

        out shouldHaveSize 2
    }

    @Test
    fun `overlapping blocks with different text drop the lower-score one`() {
        // Near-identical boxes (same region) but OCR'd to different text — the
        // exact case removePostOcrDuplicateBlocks misses. The higher-score block
        // survives with its own text/translation intact.
        val high = block(x = 10f, y = 10f, w = 80f, h = 40f, text = "correct", score = 0.9f)
        val low = block(x = 12f, y = 11f, w = 80f, h = 40f, text = "wrong", score = 0.5f)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(high, low))

        out shouldHaveSize 1
        out.first().text shouldBe "correct"
        out.first().score shouldBe 0.9f
    }

    @Test
    fun `overlapping blocks with identical text drop one`() {
        val a = block(x = 10f, y = 10f, w = 80f, h = 40f, text = "same", score = 0.5f)
        val b = block(x = 11f, y = 10f, w = 80f, h = 40f, text = "same", score = 0.9f)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(a, b))

        out shouldHaveSize 1
        out.first().score shouldBe 0.9f
    }

    @Test
    fun `cross-label overlap drops the lower-score block`() {
        // label 1 (text-in-bubble) vs label 2 (detector-only) of the same region
        // — suppressCrossLabelDuplicates would require a shared parent bubble;
        // here neither has one, but the geometric dedupe still collapses them.
        val a = block(x = 10f, y = 10f, w = 80f, h = 40f, text = "A", score = 0.9f, label = 1)
        val b = block(x = 10f, y = 10f, w = 82f, h = 40f, text = "B", score = 0.5f, label = 2)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(a, b))

        out shouldHaveSize 1
        out.first().label shouldBe 1
    }

    @Test
    fun `nested blocks drop the smaller one`() {
        // Small box fully inside a large box → containment > threshold → drop small.
        val big = block(x = 0f, y = 0f, w = 200f, h = 100f, text = "big", score = 0.5f)
        val small = block(x = 80f, y = 40f, w = 40f, h = 20f, text = "small", score = 0.9f)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(big, small))

        // Containment of small inside big = 1.0 > 0.86 → duplicate. Higher score
        // (small, 0.9) wins over big (0.5).
        out shouldHaveSize 1
        out.first().text shouldBe "small"
    }

    @Test
    fun `preserves original reading order of survivors`() {
        // Three blocks in reading order: A and C overlap (same region), B is
        // separate. A has higher score so wins over C. The returned list must
        // preserve the detector emission order (A, B), not the score-sort order.
        val a = block(x = 10f, y = 10f, w = 80f, h = 40f, text = "A", score = 0.9f)
        val b = block(x = 10f, y = 100f, w = 80f, h = 40f, text = "B", score = 0.5f)
        val c = block(x = 11f, y = 11f, w = 80f, h = 40f, text = "C", score = 0.3f)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(a, b, c))

        out shouldHaveSize 2
        out[0].text shouldBe "A"
        out[1].text shouldBe "B"
    }

    @Test
    fun `does not mutate the input list`() {
        val original = listOf(
            block(x = 10f, y = 10f, w = 80f, h = 40f, text = "A", score = 0.9f),
            block(x = 11f, y = 11f, w = 80f, h = 40f, text = "B", score = 0.5f),
        )

        OcrBlockDeduper.dedupeGeometricOverlaps(original)

        original shouldHaveSize 2
        original[0].text shouldBe "A"
        original[1].text shouldBe "B"
    }

    @Test
    fun `degenerate boxes are kept rather than dropped`() {
        // Zero-area box can't be evaluated geometrically — keep it so the
        // caller sees it rather than silently losing a block.
        val good = block(x = 10f, y = 10f, w = 80f, h = 40f, text = "good", score = 0.9f)
        val degenerate = block(x = 10f, y = 10f, w = 0f, h = 40f, text = "degenerate", score = 0.1f)

        val out = OcrBlockDeduper.dedupeGeometricOverlaps(listOf(good, degenerate))

        out shouldHaveSize 2
    }
}
