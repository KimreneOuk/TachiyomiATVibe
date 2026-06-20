package eu.kanade.translation.inpainting

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Regression guard for the "gray rectangle over the whole bounding box" symptom.
 *
 * Before Phase 3a, SmartBubbleTextCleaner filled every masked pixel with a
 * single flat ring-median color. On a grayscale manga panel or a tinted
 * bubble, that median was ~128 gray, so the cleaned text region became a
 * uniform gray patch regardless of the local artwork. [buildLocalBackground]
 * replaced that with a per-pixel local average of surrounding background
 * pixels, which matches the local artwork instead.
 *
 * These tests exercise the pure pixel→color logic directly (the public
 * cleanBubbleGroup/cleanRegions API operates on android.graphics.Bitmap, which
 * plain JVM unit tests can't load). They assert the fill is NOT uniform gray
 * and DOES track the local background.
 */
class SmartBubbleTextCleanerTest {

    private val cleaner = SmartBubbleTextCleaner()

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun gray(v: Int): Int = argb(v, v, v)

    /** A grayscale horizontal-gradient background: value = x, dark→light L→R. */
    private fun grayscaleGradient(width: Int, height: Int): IntArray {
        val px = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                px[y * width + x] = gray(x.coerceIn(0, 255))
            }
        }
        return px
    }

    @Test
    fun `background pixels are returned unchanged`() {
        val w = 40
        val h = 20
        val pixels = grayscaleGradient(w, h)
        // No mask at all — every pixel is background.
        val mask = ByteArray(w * h)
        val bg = cleaner.buildLocalBackground(pixels, mask, w, h, medianColor = gray(128))

        bg.shouldHaveSize(w * h)
        for (i in pixels.indices) bg[i] shouldBe pixels[i]
    }

    @Test
    fun `masked region is not a uniform gray rectangle on a grayscale gradient`() {
        // The pre-3a bug: a flat median (128) painted over the hole regardless
        // of the gradient, yielding a uniform gray block. The local fill must
        // instead track the dark→light gradient, so the masked pixels are NOT
        // all equal and NOT all ~128.
        val w = 60
        val h = 30
        val pixels = grayscaleGradient(w, h)
        val mask = ByteArray(w * h)
        // A text-shaped hole in the middle.
        for (y in 10 until 20) {
            for (x in 20 until 40) {
                mask[y * w + x] = 1
            }
        }

        val bg = cleaner.buildLocalBackground(pixels, mask, w, h, medianColor = gray(128))

        val holeValues = (10 until 20).flatMap { y -> (20 until 40).map { x -> bg[y * w + x] } }
        val holeGray = holeValues.map { (it and 0xFF).toDouble() }

        // NOT uniform: a real local fill across a gradient produces a spread.
        val distinctCount = holeGray.toSet().size
        distinctCount.toDouble() shouldBeGreaterThan 3.0

        // NOT flat 128-gray: the mean of a gradient fill (x in 20..39 → gray
        // ~20..39) is well below 128. The old median fill would have mean ~128.
        val mean = holeGray.average()
        mean shouldBeGreaterThan 0.0
        abs(mean - 128.0) shouldBeGreaterThan 20.0
    }

    @Test
    fun `local fill tracks the local background gradient left dark right light`() {
        // Stronger directional check: the left column of the hole should be
        // darker than the right column, because the background gradient is
        // dark→light and the local fill averages nearby background.
        val w = 60
        val h = 30
        val pixels = grayscaleGradient(w, h)
        val mask = ByteArray(w * h)
        for (y in 10 until 20) {
            for (x in 20 until 40) {
                mask[y * w + x] = 1
            }
        }

        val bg = cleaner.buildLocalBackground(pixels, mask, w, h, medianColor = gray(128))

        val leftColMean = (10 until 20).map { y -> (bg[y * w + 21] and 0xFF).toDouble() }.average()
        val rightColMean = (10 until 20).map { y -> (bg[y * w + 38] and 0xFF).toDouble() }.average()
        rightColMean shouldBeGreaterThan leftColMean
    }

    @Test
    fun `fully masked context falls back to the median rather than leaving text`() {
        // Degenerate case: the mask covers the ENTIRE context, so no background
        // neighbor exists to average. The fill must fall back to the supplied
        // median so the pixel is still covered (not left as original text).
        val w = 10
        val h = 10
        val pixels = IntArray(w * h) { gray(0) } // all black "text"
        val mask = ByteArray(w * h) { 1 } // fully masked
        val median = gray(200)

        val bg = cleaner.buildLocalBackground(pixels, mask, w, h, medianColor = median)

        for (i in pixels.indices) {
            bg[i] shouldBe median
        }
    }

    @Test
    fun `large masked region uses directional background instead of flat median`() {
        val w = 90
        val h = 20
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            when {
                x < 10 -> gray(40)
                x >= 80 -> gray(80)
                else -> gray(0)
            }
        }
        val mask = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 10 until 80) {
                mask[y * w + x] = 1
            }
        }

        val bg = cleaner.buildLocalBackground(pixels, mask, w, h, medianColor = gray(200))

        val center = bg[10 * w + 45] and 0xFF
        center shouldNotBe 200
        center shouldBe 60
    }

    @Test
    fun `solid flat fill does not smear nearby line samples into white bubble`() {
        val w = 80
        val h = 48
        val pixels = IntArray(w * h) { gray(255) }
        val mask = ByteArray(w * h)

        for (y in 14 until 34) {
            for (x in 24 until 56) {
                mask[y * w + x] = 1
            }
        }

        // Simulate leftover OCR/text/border samples near the hole. Directional
        // reconstruction can pull these into long horizontal/vertical bands;
        // a confidently flat bubble should ignore them and use the sampled fill.
        for (x in 20 until 60) {
            pixels[13 * w + x] = gray(145)
            pixels[34 * w + x] = gray(145)
        }
        for (y in 12 until 36) {
            pixels[y * w + 23] = gray(150)
            pixels[y * w + 56] = gray(150)
        }

        val bg = cleaner.buildLocalBackground(
            pixels = pixels,
            mask = mask,
            width = w,
            height = h,
            medianColor = gray(255),
            preferFlatFill = true,
        )

        for (y in 14 until 34) {
            for (x in 24 until 56) {
                bg[y * w + x] shouldBe gray(255)
            }
        }
        bg[13 * w + 30] shouldBe gray(145)
        bg[20 * w + 23] shouldBe gray(150)
    }

    @Test
    fun `local contrast mask keeps black text strokes and rejects screentone dots`() {
        val w = 80
        val h = 50
        val pixels = IntArray(w * h) { gray(180) }
        val textPixels = mutableSetOf<Int>()

        for (y in 0 until h step 4) {
            for (x in 0 until w step 4) {
                pixels[y * w + x] = gray(135)
            }
        }
        fun drawRect(x1: Int, y1: Int, x2: Int, y2: Int) {
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    pixels[y * w + x] = gray(20)
                    textPixels.add(y * w + x)
                }
            }
        }
        drawRect(24, 10, 29, 40)
        drawRect(45, 10, 50, 40)
        drawRect(24, 23, 50, 28)

        val mask = cleaner.buildLocalContrastTextMask(
            pixels = pixels,
            boxes = listOf(intArrayOf(18, 5, 56, 45)),
            width = w,
            height = h,
        )

        val coveredText = textPixels.count { mask[it] != 0.toByte() }.toDouble() / textPixels.size
        val maskedDots = (0 until h step 4).sumOf { y ->
            (0 until w step 4).count { x -> (y * w + x) !in textPixels && mask[y * w + x] != 0.toByte() }
        }

        (coveredText > 0.85) shouldBe true
        (maskedDots < 8) shouldBe true
    }

    @Test
    fun `local contrast mask rejects long thin band artifacts`() {
        val w = 90
        val h = 70
        val pixels = IntArray(w * h) { gray(250) }
        val textPixels = mutableSetOf<Int>()

        for (y in listOf(18, 31, 44, 57)) {
            for (x in 18 until 72) {
                pixels[y * w + x] = gray(150)
            }
        }
        val bandRows = setOf(18, 31, 44, 57)
        for (y in 12 until 62) {
            pixels[y * w + 16] = gray(150)
            pixels[y * w + 74] = gray(150)
        }
        fun drawRect(x1: Int, y1: Int, x2: Int, y2: Int) {
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    pixels[y * w + x] = gray(20)
                    if (y !in bandRows) {
                        textPixels.add(y * w + x)
                    }
                }
            }
        }
        drawRect(38, 16, 43, 58)
        drawRect(28, 16, 53, 21)

        val mask = cleaner.buildLocalContrastTextMask(
            pixels = pixels,
            boxes = listOf(intArrayOf(14, 10, 76, 64)),
            width = w,
            height = h,
        )

        val coveredText = textPixels.count { mask[it] != 0.toByte() }.toDouble() / textPixels.size
        val maskedHorizontalBand = (18 until 72).count { x -> mask[31 * w + x] != 0.toByte() }
        val maskedVerticalBand = (12 until 62).count { y -> mask[y * w + 74] != 0.toByte() }

        (coveredText > 0.85) shouldBe true
        (maskedHorizontalBand < 8) shouldBe true
        (maskedVerticalBand < 8) shouldBe true
    }

    @Test
    fun `local contrast mask catches light outline and dark core`() {
        val w = 60
        val h = 40
        val pixels = IntArray(w * h) { gray(150) }
        val outlinePixels = mutableSetOf<Int>()
        val corePixels = mutableSetOf<Int>()

        for (y in 10 until 30) {
            for (x in 20 until 40) {
                if (x < 23 || x >= 37 || y < 13 || y >= 27) {
                    pixels[y * w + x] = gray(245)
                    outlinePixels.add(y * w + x)
                }
            }
        }
        for (y in 15 until 25) {
            for (x in 27 until 33) {
                pixels[y * w + x] = gray(20)
                corePixels.add(y * w + x)
            }
        }

        val mask = cleaner.buildLocalContrastTextMask(
            pixels = pixels,
            boxes = listOf(intArrayOf(16, 6, 44, 34)),
            width = w,
            height = h,
        )

        val outlineCovered = outlinePixels.count { mask[it] != 0.toByte() }.toDouble() / outlinePixels.size
        val coreCovered = corePixels.count { mask[it] != 0.toByte() }.toDouble() / corePixels.size

        (outlineCovered > 0.75) shouldBe true
        (coreCovered > 0.90) shouldBe true
    }

    @Test
    fun `masked pixel on a tinted colored background uses local color not gray`() {
        // A colored (non-gray) background must NOT collapse to gray. Pre-3a the
        // median could still land near gray on some tinted art; the local fill
        // preserves local hue.
        val w = 30
        val h = 30
        val pixels = IntArray(w * h) { argb(200, 40, 120) } // a magenta-ish flat color
        val mask = ByteArray(w * h)
        for (y in 10 until 20) {
            for (x in 10 until 20) {
                mask[y * w + x] = 1
            }
        }

        val bg = cleaner.buildLocalBackground(pixels, mask, w, h, medianColor = gray(128))

        val center = bg[15 * w + 15]
        val r = (center shr 16 and 0xFF).toDouble()
        val g = (center shr 8 and 0xFF).toDouble()
        val b = (center and 0xFF).toDouble()
        // Should be close to the surrounding magenta, definitely not 128/128/128.
        r shouldBeGreaterThan 150.0
        g shouldNotBe 128.0
        b shouldBeGreaterThan 80.0
    }

    @Test
    fun `getWorkingBuffers does not cache when size exceeds limit`() {
        val cleaner = SmartBubbleTextCleaner()
        val getWorkingBuffersMethod = cleaner.javaClass.getDeclaredMethod("getWorkingBuffers", Int::class.java)
        getWorkingBuffersMethod.isAccessible = true

        // Acquire buffers below the limit
        val result1 = getWorkingBuffersMethod.invoke(cleaner, 100) as Pair<*, *>
        val buffer1a = result1.first as IntArray
        val buffer1b = result1.second as IntArray

        // Acquire buffers below the limit again with same size -> should be the same arrays
        val result2 = getWorkingBuffersMethod.invoke(cleaner, 100) as Pair<*, *>
        (result2.first === buffer1a) shouldBe true
        (result2.second === buffer1b) shouldBe true

        // Acquire buffers above the limit (1_000_000)
        val result3 = getWorkingBuffersMethod.invoke(cleaner, 1_000_001) as Pair<*, *>
        val buffer3a = result3.first as IntArray
        val buffer3b = result3.second as IntArray

        // Acquire buffers above the limit again with same size -> should be DIFFERENT arrays (not cached)
        val result4 = getWorkingBuffersMethod.invoke(cleaner, 1_000_001) as Pair<*, *>
        val buffer4a = result4.first as IntArray
        val buffer4b = result4.second as IntArray
        (buffer3a === buffer4a) shouldBe false
        (buffer3b === buffer4b) shouldBe false

        // Check that the cached buffers in the cleaner are still the smaller ones
        val buffer1Field = cleaner.javaClass.getDeclaredField("workingBuffer1")
        buffer1Field.isAccessible = true
        val cached1 = buffer1Field.get(cleaner) as IntArray
        cached1.size shouldBe 100
    }
}

