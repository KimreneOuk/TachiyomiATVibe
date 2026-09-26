package eu.kanade.translation.engines.inpainting.bubble

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleCleanerMathTest {

    @Test
    fun `scaledMorphology floors feather at 2 and dilation at 1 for tiny region`() {
        BubbleCleanerMath.scaledMorphology(minDim = 10, featherRadius = 6, dilationIterations = 3) shouldBe (2 to 1)
    }

    @Test
    fun `scaledMorphology caps by config on large region`() {
        BubbleCleanerMath.scaledMorphology(minDim = 1000, featherRadius = 6, dilationIterations = 3) shouldBe (6 to 3)
    }

    @Test
    fun `scaledMorphology scales with minDim at mid range`() {
        // feather = min(6, max(2, 240/12=20)) = 6; dilation = min(3, max(1, 240/20=12)) = 3
        BubbleCleanerMath.scaledMorphology(minDim = 240, featherRadius = 6, dilationIterations = 3) shouldBe (6 to 3)
        // feather = min(20, max(2, 60/12=5)) = 5; dilation = min(20, max(1, 60/20=3)) = 3
        BubbleCleanerMath.scaledMorphology(minDim = 60, featherRadius = 20, dilationIterations = 20) shouldBe (5 to 3)
    }

    @Test
    fun `scaledTextMaskPad returns max of config pad and scaled cap`() {
        BubbleCleanerMath.scaledTextMaskPad(minDim = 16, textMaskPad = 2) shouldBe 2 // min(8, 16/8=2)=2 → max(2,2)=2
        BubbleCleanerMath.scaledTextMaskPad(minDim = 100, textMaskPad = 2) shouldBe 8 // min(8, 100/8=12)=8 → max(2,8)=8
        BubbleCleanerMath.scaledTextMaskPad(minDim = 100, textMaskPad = 12) shouldBe 12 // max(12, 8)=12
    }

    @Test
    fun `percentileGray returns 0th bin when all mass at zero`() {
        val hist = IntArray(256)
        hist[0] = 10
        BubbleCleanerMath.percentileGray(hist, 10, 0.0f) shouldBe 0
        BubbleCleanerMath.percentileGray(hist, 10, 0.5f) shouldBe 0
        BubbleCleanerMath.percentileGray(hist, 10, 1.0f) shouldBe 0
    }

    @Test
    fun `percentileGray median for uniform spread`() {
        val hist = IntArray(256)
        for (v in 0..4) hist[v] = 1 // 5 values, count=5
        BubbleCleanerMath.percentileGray(hist, 5, 0.5f) shouldBe 2 // target=(5*0.5)=3 (rounded, coerced to [1,5]); seen>=3 at v=2
        BubbleCleanerMath.percentileGray(hist, 5, 1.0f) shouldBe 4 // target=5; seen>=5 at v=4
    }

    @Test
    fun `percentileGray full mass at last bin returns last bin`() {
        val hist = IntArray(256)
        hist[255] = 10
        BubbleCleanerMath.percentileGray(hist, 10, 0.5f) shouldBe 255
        BubbleCleanerMath.percentileGray(hist, 10, 1.0f) shouldBe 255
    }

    @Test
    fun `rectSum computes summed-area table rectangle sum`() {
        // 3x3 integral with stride 4; fill increasing values.
        val stride = 4
        val integral = LongArray(stride * stride)
        for (i in integral.indices) integral[i] = i.toLong()

        // rect (1,1)-(3,3): d=integral[3*4+3]=15, b=integral[1*4+3]=7, c=integral[3*4+1]=13, a=integral[1*4+1]=5
        // sum = 15 - 7 - 13 + 5 = 0
        BubbleCleanerMath.rectSum(integral, stride, x1 = 1, y1 = 1, x2 = 3, y2 = 3) shouldBe 0L
    }

    @Test
    fun `unionMasks ORs two byte masks into 0_1 result of min size`() {
        val a = byteArrayOf(0, 1, 0, 0)
        val b = byteArrayOf(0, 0, 1, 0, 9)
        BubbleCleanerMath.unionMasks(a, b).map { it.toInt() } shouldBe listOf(0, 1, 1, 0)
    }

    @Test
    fun `isDominantLightBackground true for near-white rectangle`() {
        val width = 3
        val gray = IntArray(width * 3) { 245 }
        BubbleCleanerMath.isDominantLightBackground(gray, x1 = 0, y1 = 0, x2 = 3, y2 = 3, width = width) shouldBe true
    }

    @Test
    fun `isDominantLightBackground false for dark rectangle`() {
        val width = 3
        val gray = IntArray(width * 3) { 30 }
        BubbleCleanerMath.isDominantLightBackground(gray, x1 = 0, y1 = 0, x2 = 3, y2 = 3, width = width) shouldBe false
    }

    @Test
    fun `classifyBackground returns dark_flat for low variance dark`() {
        BubbleCleanerMath.classifyBackground(stats(darkPixelRatio = 0.6f, grayMean = 40f, grayStd = 10f, edgeDensity = 0.03f, nearWhiteRatio = 0.0f)) shouldBe "dark_flat"
    }

    @Test
    fun `classifyBackground returns dark_lightly_varying for mid variance dark`() {
        BubbleCleanerMath.classifyBackground(stats(darkPixelRatio = 0.6f, grayMean = 40f, grayStd = 25f, edgeDensity = 0.10f, nearWhiteRatio = 0.0f)) shouldBe "dark_lightly_varying"
    }

    @Test
    fun `classifyBackground returns dark_textured for high variance dark`() {
        BubbleCleanerMath.classifyBackground(stats(darkPixelRatio = 0.6f, grayMean = 40f, grayStd = 50f, edgeDensity = 0.20f, nearWhiteRatio = 0.0f)) shouldBe "dark_textured"
    }

    @Test
    fun `classifyBackground returns flat_white for near-white low variance`() {
        BubbleCleanerMath.classifyBackground(stats(darkPixelRatio = 0.0f, grayMean = 245f, grayStd = 10f, edgeDensity = 0.10f, nearWhiteRatio = 0.9f)) shouldBe "flat_white"
    }

    @Test
    fun `classifyBackground returns flat_colored for low variance non-white`() {
        BubbleCleanerMath.classifyBackground(stats(darkPixelRatio = 0.0f, grayMean = 200f, grayStd = 10f, edgeDensity = 0.10f, nearWhiteRatio = 0.5f)) shouldBe "flat_colored"
    }

    @Test
    fun `classifyBackground returns lightly_varying for mid variance light`() {
        BubbleCleanerMath.classifyBackground(stats(darkPixelRatio = 0.0f, grayMean = 200f, grayStd = 25f, edgeDensity = 0.05f, nearWhiteRatio = 0.5f)) shouldBe "lightly_varying"
    }

    @Test
    fun `classifyBackground returns textured for high variance light`() {
        BubbleCleanerMath.classifyBackground(stats(darkPixelRatio = 0.0f, grayMean = 200f, grayStd = 50f, edgeDensity = 0.20f, nearWhiteRatio = 0.5f)) shouldBe "textured"
    }

    @Test
    fun `shouldUseSolidFlatFill true for flat_white`() {
        BubbleCleanerMath.shouldUseSolidFlatFill("flat_white", stats(grayStd = 50f, edgeDensity = 0.5f)) shouldBe true
    }

    @Test
    fun `shouldUseSolidFlatFill true for flat_colored with very low variance`() {
        BubbleCleanerMath.shouldUseSolidFlatFill("flat_colored", stats(grayStd = 5f, edgeDensity = 0.02f)) shouldBe true
    }

    @Test
    fun `shouldUseSolidFlatFill false for flat_colored with higher variance`() {
        BubbleCleanerMath.shouldUseSolidFlatFill("flat_colored", stats(grayStd = 15f, edgeDensity = 0.10f)) shouldBe false
    }

    @Test
    fun `shouldUseSolidFlatFill false for textured`() {
        BubbleCleanerMath.shouldUseSolidFlatFill("textured", stats(grayStd = 5f, edgeDensity = 0.02f)) shouldBe false
    }

    private fun stats(
        medianColor: Int = 0,
        grayMean: Float = 128f,
        grayStd: Float = 30f,
        nearWhiteRatio: Float = 0.0f,
        darkPixelRatio: Float = 0.0f,
        edgeDensity: Float = 0.10f,
        sampleCount: Int = 100,
    ) = SmartBubbleTextCleaner.BackgroundStats(
        medianColor = medianColor,
        grayMean = grayMean,
        grayStd = grayStd,
        nearWhiteRatio = nearWhiteRatio,
        darkPixelRatio = darkPixelRatio,
        edgeDensity = edgeDensity,
        sampleCount = sampleCount,
    )
}
