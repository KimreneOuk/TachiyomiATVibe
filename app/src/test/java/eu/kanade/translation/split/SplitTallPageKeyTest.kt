package eu.kanade.translation.split

import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins [SplitTallPageKey] — the pure coordinate bookkeeping for the split-tall
 * translation merge. This is the highest-risk part of the feature (slice grouping
 * + the y-offset / boundary-clip back-map), so it is verified independently of
 * the device-bound pipeline integration.
 */
class SplitTallPageKeyTest {

    @Test
    fun `parses a split slice with extension`() {
        val p = SplitTallPageKey.parse("001__002.jpg")
        p shouldBe SplitTallPageKey.Part("001__002.jpg", "001", 2)
    }

    @Test
    fun `parses the first slice and slices without extension`() {
        SplitTallPageKey.parse("001__001.jpg")?.index shouldBe 1
        SplitTallPageKey.parse("012__003")?.prefix shouldBe "012"
        SplitTallPageKey.parse("012__003")?.index shouldBe 3
    }

    @Test
    fun `parses any supported image extension case-insensitively`() {
        SplitTallPageKey.parse("001__002.JPEG")?.index shouldBe 2
        SplitTallPageKey.parse("001__002.png")?.index shouldBe 2
        SplitTallPageKey.parse("001__002.webp")?.index shouldBe 2
    }

    @Test
    fun `rejects non-split keys`() {
        SplitTallPageKey.parse("001.jpg") shouldBe null
        SplitTallPageKey.parse("001_001.jpg") shouldBe null // single underscore, not double
        SplitTallPageKey.parse("page__001.jpg") shouldBe null // prefix must be digits
        SplitTallPageKey.parse("001__001.gif") shouldBe null // unsupported extension
        SplitTallPageKey.parse("") shouldBe null
    }

    @Test
    fun `isSplitPart flag mirrors parse`() {
        SplitTallPageKey.isSplitPart("001__002.jpg") shouldBe true
        SplitTallPageKey.isSplitPart("001.jpg") shouldBe false
    }

    @Test
    fun `groups consecutive slices by prefix and preserves order`() {
        val groups = SplitTallPageKey.group(
            listOf("001__001.jpg", "001__002.jpg", "002.jpg", "003__001.jpg"),
        )
        groups shouldHaveSize 3
        groups[0].isSplit shouldBe true
        groups[0].parts.map { it.index } shouldBe listOf(1, 2)
        groups[0].representative shouldBe "001__001.jpg"
        groups[1].isSplit shouldBe false
        groups[1].parts.map { it.pageKey } shouldBe listOf("002.jpg")
        groups[2].isSplit shouldBe false
        groups[2].representative shouldBe "003__001.jpg"
    }

    @Test
    fun `sorts a group by slice index even when input is unordered`() {
        val groups = SplitTallPageKey.group(
            listOf("005__003.jpg", "005__001.jpg", "005__002.jpg"),
        )
        groups shouldHaveSize 1
        groups[0].parts.map { it.index } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `a lone __001 with no siblings is not a split group`() {
        val groups = SplitTallPageKey.group(listOf("007__001.jpg"))
        groups shouldHaveSize 1
        groups[0].isSplit shouldBe false
    }

    @Test
    fun `back-maps a block fully inside a slice`() {
        val block = TranslationBlock(text = "hi", width = 10f, height = 20f, x = 5f, y = 110f, symHeight = 1f, symWidth = 1f, angle = 0f)
        // Slice covers merged y [100, 200]; block at [110,130] is fully inside.
        val out = SplitTallPageKey.backMapBlock(block, sliceYTop = 100f, sliceHeight = 100f)
        out!!.y shouldBe 10f
        out.height shouldBe 20f
        out.x shouldBe 5f // x untouched
        out.text shouldBe "hi"
    }

    @Test
    fun `back-maps a block straddling the slice top boundary`() {
        val block = TranslationBlock(text = "t", width = 10f, height = 30f, x = 0f, y = 90f, symHeight = 1f, symWidth = 1f, angle = 0f)
        // Block spans merged [90,120]; slice is [100,200] -> clip to [100,120].
        val out = SplitTallPageKey.backMapBlock(block, sliceYTop = 100f, sliceHeight = 100f)
        out!!.y shouldBe 0f
        out.height shouldBe 20f
    }

    @Test
    fun `back-maps a block straddling the slice bottom boundary`() {
        val block = TranslationBlock(text = "t", width = 10f, height = 50f, x = 0f, y = 180f, symHeight = 1f, symWidth = 1f, angle = 0f)
        // Block spans merged [180,230]; slice is [100,200] -> clip to [180,200].
        val out = SplitTallPageKey.backMapBlock(block, sliceYTop = 100f, sliceHeight = 100f)
        out!!.y shouldBe 80f
        out.height shouldBe 20f
    }

    @Test
    fun `drops a block entirely outside the slice`() {
        val above = TranslationBlock(text = "a", width = 10f, height = 10f, x = 0f, y = 50f, symHeight = 1f, symWidth = 1f, angle = 0f)
        val below = TranslationBlock(text = "b", width = 10f, height = 10f, x = 0f, y = 300f, symHeight = 1f, symWidth = 1f, angle = 0f)
        SplitTallPageKey.backMapBlock(above, sliceYTop = 100f, sliceHeight = 100f) shouldBe null
        SplitTallPageKey.backMapBlock(below, sliceYTop = 100f, sliceHeight = 100f) shouldBe null
    }

    @Test
    fun `transforms and clips the parent rect the same way`() {
        val block = TranslationBlock(
            text = "t", width = 10f, height = 30f, x = 0f, y = 190f,
            symHeight = 1f, symWidth = 1f, angle = 0f,
            parentX = 0f, parentY = 180f, parentWidth = 50f, parentHeight = 40f,
        )
        // Parent spans merged [180,220]; slice is [100,200] -> clip to [180,200] (height 20).
        val out = SplitTallPageKey.backMapBlock(block, sliceYTop = 100f, sliceHeight = 100f)
        out!!.parentY shouldBe 80f
        out.parentHeight shouldBe 20f
    }
}
