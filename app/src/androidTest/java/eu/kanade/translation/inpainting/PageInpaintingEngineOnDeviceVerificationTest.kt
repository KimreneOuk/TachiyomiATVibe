package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.PageInpaintingEngine
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator
import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
import eu.kanade.translation.model.BubbleMaskRle
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the ROUTER through the real [PageInpaintingEngine]:
 *  - QUALITY + LiteRT must route a supplied NONRECTANGULAR bubble segmentation
 *    through the neural delegate, leave every pixel OUTSIDE the supplied
 *    segmentation pixel-identical, and stamp a clean (non-degraded)
 *    "QUALITY:LAMA_LITERT";
 *  - FAST must fill the same bubble classically ("FAST" stamp, median route);
 *  - a MIXED page (one bubble without segmentation) must preserve the
 *    uncovered bubble and mark the stamp degraded.
 *
 * Free-text batching is observable only through the "[inpaint]" log line
 * (the delegate is wired internally), so it is intentionally not asserted
 * here — see the logcat output of the QUALITY runs for
 * bubble_route / free_text_delegate evidence.
 */
@RunWith(AndroidJUnit4::class)
class PageInpaintingEngineOnDeviceVerificationTest {

    private val paperColor = Color.WHITE
    private val size = 256

    @Test
    fun qualityLiteRTRoutesNonrectangularSegmentationWithoutTouchingOutsidePixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val litert = LiteRTMangaInpaintingEngine(context)
        assertTrue("LiteRT engine should be available on device", litert.isAvailable())
        val coordinator = NeuralInpaintingCoordinator()
        val engine = PageInpaintingEngine(
            mode = InpaintingMode.QUALITY,
            inpainter = coordinator,
            litertInpainter = litert,
            qualityFallbackPref = { false },
        )

        val original = bubblePageBitmap()
        val plus = plusMask()
        val page = bubblePage(plus)

        val result = engine.inpaint(original, page)

        assertNotNull(result)
        assertEquals(StageStatus.READY, page.inpaintStatus)
        assertEquals("QUALITY:LAMA_LITERT", page.inpaintingModeUsed)
        assertFalse(coordinator.lastRunDegraded)
        assertEquals("neural", coordinator.lastBubbleRoute)

        // Segmentation-only contract: pixels outside the SUPPLIED plus-shaped
        // segmentation (including the drawn bubble stroke ring) are
        // pixel-identical.
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!plus[y * size + x]) {
                    assertEquals("pixel ($x,$y) outside segmentation changed", original.getPixel(x, y), result!!.getPixel(x, y))
                }
            }
        }
        litert.close()
    }

    @Test
    fun fastModeFillsBubbleClassicallyWithoutTouchingOutsidePixels() {
        val coordinator = NeuralInpaintingCoordinator()
        val engine = PageInpaintingEngine(
            mode = InpaintingMode.FAST,
            inpainter = coordinator,
            litertInpainter = null,
            qualityFallbackPref = { false },
        )

        val original = bubblePageBitmap()
        val plus = plusMask()
        val page = bubblePage(plus)

        val result = engine.inpaint(original, page)

        assertNotNull(result)
        assertEquals(StageStatus.READY, page.inpaintStatus)
        assertEquals("FAST", page.inpaintingModeUsed)
        assertFalse(coordinator.lastRunDegraded)
        assertEquals("median_fill", coordinator.lastBubbleRoute)

        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!plus[y * size + x]) {
                    assertEquals("pixel ($x,$y) outside segmentation changed", original.getPixel(x, y), result!!.getPixel(x, y))
                }
            }
        }
        // The glyphs inside the mask must actually be replaced by the solid
        // paper fill (not left as ink).
        val glyph = result!!.getPixel(48, 128)
        assertTrue("glyph pixel was not filled: $glyph", Color.red(glyph) > 200 && Color.green(glyph) > 200 && Color.blue(glyph) > 200)
    }

    @Test
    fun mixedPagePreservesUncoveredBubbleAndStampsDegraded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val litert = LiteRTMangaInpaintingEngine(context)
        assertTrue(litert.isAvailable())
        val coordinator = NeuralInpaintingCoordinator()
        val engine = PageInpaintingEngine(
            mode = InpaintingMode.QUALITY,
            inpainter = coordinator,
            litertInpainter = litert,
            qualityFallbackPref = { false },
        )

        val original = bubblePageBitmap()
        // Segmentation covers only the LEFT bubble.
        val plus = plusMask()
        for (y in 0 until size) {
            for (x in 160 until size) plus[y * size + x] = false
        }
        val page = bubblePage(plus)
        page.blocks.add(
            TranslationBlock(
                text = "second",
                width = 40f,
                height = 20f,
                x = 180f,
                y = 118f,
                symHeight = 20f,
                symWidth = 40f,
                angle = 0f,
                parentX = 168f,
                parentY = 96f,
                parentWidth = 72f,
                parentHeight = 64f,
            ),
        )

        val result = engine.inpaint(original, page)

        assertNotNull(result)
        assertEquals(StageStatus.READY, page.inpaintStatus)
        assertTrue(
            "stamp must be degraded: ${page.inpaintingModeUsed}",
            page.inpaintingModeUsed!!.startsWith("QUALITY:LAMA_LITERT") && page.inpaintingModeUsed != "QUALITY:LAMA_LITERT",
        )
        assertTrue(coordinator.lastRunDegraded)
        assertEquals("neural_partial_masks", coordinator.lastBubbleRoute)

        // The uncovered right bubble keeps its pixels exactly.
        for (y in 0 until size) {
            for (x in 160 until size) {
                assertEquals("uncovered bubble pixel ($x,$y) changed", original.getPixel(x, y), result!!.getPixel(x, y))
            }
        }
        litert.close()
    }

    /** White page with a plus-shaped bubble outline, glyph strokes and balloon text inside it. */
    private fun bubblePageBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = paperColor }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        // Hand-drawn bubble stroke: circle outline around the plus area.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.BLACK
        canvas.drawCircle(128f, 128f, 110f, paint)
        // Balloon text strokes inside the segmentation.
        paint.style = Paint.Style.FILL
        canvas.drawRect(104f, 104f, 152f, 112f, paint)
        canvas.drawRect(104f, 128f, 152f, 136f, paint)
        canvas.drawRect(104f, 152f, 152f, 160f, paint)
        return bitmap
    }

    /** Plus-shaped bubble-interior segmentation (horizontal + vertical bars). */
    private fun plusMask(): BooleanArray {
        val mask = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                mask[y * size + x] =
                    (y in 96..159 && x in 40..215) || (x in 96..159 && y in 40..215)
            }
        }
        return mask
    }

    private fun bubblePage(plus: BooleanArray): PageTranslation = PageTranslation().apply {
        blocks.add(
            TranslationBlock(
                text = "hello",
                width = 48f,
                height = 56f,
                x = 104f,
                y = 104f,
                symHeight = 56f,
                symWidth = 48f,
                angle = 0f,
                parentX = 40f,
                parentY = 40f,
                parentWidth = 176f,
                parentHeight = 176f,
                segmentationMask = rle(plus),
            ),
        )
    }

    private fun rle(mask: BooleanArray): BubbleMaskRle {
        val runs = mutableListOf<Int>()
        var index = 0
        while (index < mask.size) {
            if (mask[index]) {
                val start = index
                while (index < mask.size && mask[index]) index++
                runs.add(start)
                runs.add(index - start)
            } else {
                index++
            }
        }
        var minX = size
        var minY = size
        var maxX = 0
        var maxY = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (mask[y * size + x]) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        return BubbleMaskRle(
            width = size,
            height = size,
            bounds = listOf(minX, minY, maxX + 1, maxY + 1),
            runs = runs,
            score = 1f,
        )
    }
}
