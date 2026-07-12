package eu.kanade.translation.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the legacy explicit-overlap merge helper. Recognition output should
 * preserve detector boxes and must not call this helper implicitly.
 */
class PageTranslationHelperTest {

    private fun block(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        angle: Float = 0f,
        direction: String = "LTR",
        parentX: Float = 0f,
        parentY: Float = 0f,
        parentWidth: Float = 0f,
        parentHeight: Float = 0f,
    ) = TranslationBlock(
        text = text,
        translation = "",
        width = w,
        height = h,
        x = x,
        y = y,
        symHeight = 1f,
        symWidth = 1f,
        angle = angle,
        direction = direction,
        parentX = parentX,
        parentY = parentY,
        parentWidth = parentWidth,
        parentHeight = parentHeight,
    )

    @Test
    fun `two overlapping same-orientation blocks are merged into one`() {
        val merged = PageTranslationHelper.mergeOverlap(
            arrayListOf(
                block(x = 0f, y = 0f, w = 20f, h = 10f, text = "first"),
                block(x = 10f, y = 0f, w = 20f, h = 10f, text = "second"),
            ),
        )

        merged shouldHaveSize 1
        // Union box: x=0, width spans to the rightmost edge (10+20=30).
        merged[0].x shouldBe 0f
        merged[0].width shouldBe 30f
        merged[0].y shouldBe 0f
        merged[0].height shouldBe 10f
        // Text is joined in encounter order with a newline.
        merged[0].text shouldBe "first\nsecond"
    }

    @Test
    fun `non-overlapping blocks stay separate`() {
        val merged = PageTranslationHelper.mergeOverlap(
            arrayListOf(
                block(x = 0f, y = 0f, w = 10f, h = 10f, text = "a"),
                block(x = 100f, y = 100f, w = 10f, h = 10f, text = "b"),
            ),
        )

        merged shouldHaveSize 2
        merged.map { it.text } shouldBe listOf("a", "b")
    }

    @Test
    fun `overlapping blocks with different orientation stay separate`() {
        // The guard is |angle1 - angle2| < 10 degrees.
        val merged = PageTranslationHelper.mergeOverlap(
            arrayListOf(
                block(x = 0f, y = 0f, w = 20f, h = 10f, text = "horizontal", angle = 0f),
                block(x = 5f, y = 0f, w = 20f, h = 10f, text = "rotated", angle = 45f),
            ),
        )

        merged shouldHaveSize 2
    }

    @Test
    fun `a chain of overlapping blocks collapses transitively`() {
        // Each pair overlaps the next; the result is one block spanning all.
        val merged = PageTranslationHelper.mergeOverlap(
            arrayListOf(
                block(x = 0f, y = 0f, w = 20f, h = 10f, text = "1"),
                block(x = 10f, y = 0f, w = 20f, h = 10f, text = "2"),
                block(x = 25f, y = 0f, w = 20f, h = 10f, text = "3"),
            ),
        )

        merged shouldHaveSize 1
        merged[0].text shouldBe "1\n2\n3"
    }

    @Test
    fun `empty input returns empty`() {
        PageTranslationHelper.mergeOverlap(arrayListOf()) shouldHaveSize 0
    }

    @Test
    fun `merge does not mutate the input blocks`() {
        val original = arrayListOf(
            block(x = 0f, y = 0f, w = 20f, h = 10f, text = "first"),
            block(x = 10f, y = 0f, w = 20f, h = 10f, text = "second"),
        )

        PageTranslationHelper.mergeOverlap(original)

        original[0].text shouldBe "first"
        original[1].text shouldBe "second"
    }

}
