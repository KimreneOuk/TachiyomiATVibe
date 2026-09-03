package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.rendering.BlockLayout
import eu.kanade.translation.rendering.FloatRect
import eu.kanade.translation.rendering.TextAlign
import eu.kanade.translation.segmentation.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reader-recycling regression coverage for prepared overlay drawing state. */
@RunWith(AndroidJUnit4::class)
class TranslationOverlayViewLifecycleInstrumentedTest {
    @Test
    fun rebindAndClearCannotDrawStalePreparedLayouts() {
        val view = TranslationOverlayView(InstrumentationRegistry.getInstrumentation().targetContext)
        val first = layout("FIRST")

        view.bindLayoutsForTest(listOf(first), 64, 64)
        view.bindLayoutsForTest(emptyList(), 64, 64)
        assertEmpty(view)

        view.bindLayoutsForTest(listOf(first), 64, 64)
        view.clear()
        assertEmpty(view)
    }

    @Test
    fun repeatedDrawOfOnePreparedClippedLayoutIsStableWithoutRebinding() {
        val geometry = MaskGeometry.fromSpans(64, 64, (10 until 54).map { MaskGeometry.RowSpan(it, 10, 54) })
        val prepared = layout("REPEAT").copy(
            maskGeometry = geometry,
            planGeometryId = 0,
            maskComponentId = 0,
            cellRect = FloatRect(10f, 10f, 54f, 54f),
        )
        val view = TranslationOverlayView(InstrumentationRegistry.getInstrumentation().targetContext)
        view.bindLayoutsForTest(listOf(prepared), 64, 64)
        val baseline = draw(view)

        assertTrue("prepared draw must produce ink", baseline.nonTransparentPixels() > 0)
        assertOnlySquareHasInk(baseline)
        repeat(16) {
            val repeated = draw(view)
            assertTrue("repeated draw $it changed prepared output", baseline.sameAs(repeated))
            assertOnlySquareHasInk(repeated)
        }
    }

    private fun assertEmpty(view: TranslationOverlayView) {
        assertEquals(0, draw(view).nonTransparentPixels())
    }

    private fun draw(view: TranslationOverlayView) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        view.drawLayoutsForTest(Canvas(it))
    }

    private fun assertOnlySquareHasInk(bitmap: Bitmap) {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (x !in 10 until 54 || y !in 10 until 54) {
                    assertEquals("ink outside prepared hard clip at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
                }
            }
        }
    }

    private fun Bitmap.nonTransparentPixels(): Int {
        var count = 0
        for (y in 0 until height) for (x in 0 until width) if (Color.alpha(getPixel(x, y)) != 0) count++
        return count
    }

    private fun layout(text: String) = BlockLayout(
        block = TranslationBlock(text = text, translation = text, width = 52f, height = 52f, x = 6f, y = 6f, symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f),
        text = text,
        isVertical = false,
        originX = 32f,
        originY = 32f,
        safeW = 56f,
        safeH = 56f,
        fontSizePx = 18f,
        strokeWidth = 2f,
        drawAlign = TextAlign.CENTER,
        clipRect = null,
        lines = listOf(text),
    )
}
