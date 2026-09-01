package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.rendering.BlockLayout
import eu.kanade.translation.rendering.FloatRect
import eu.kanade.translation.rendering.HardClip
import eu.kanade.translation.rendering.PositionedLine
import eu.kanade.translation.rendering.TextAlign
import eu.kanade.translation.segmentation.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel proof for the production overlay's common hard-clip envelope. */
@RunWith(AndroidJUnit4::class)
class TranslationOverlayViewInstrumentedTest {
    private val spans = buildList {
        for (y in 8 until 56) {
            // A stepped/sloped upper-left ceiling: the component is narrow at
            // the top and widens below row 20.
            add(MaskGeometry.RowSpan(y, if (y < 20) 24 else 8, 56))
        }
    }
    private val geometry = MaskGeometry.fromSpans(64, 64, spans)
    private val cell = FloatRect(0f, 0f, 48f, 64f)
    private val legacyClip = FloatRect(0f, 0f, 64f, 50f)

    @Test
    fun legacyLayoutClipsToComponentThenCellThenCollisionRect() {
        assertOverlayClip(layout(positioned = false))
    }

    @Test
    fun positionedLayoutClipsToComponentThenCellThenCollisionRect() {
        assertOverlayClip(layout(positioned = true))
    }

    @Test
    fun verticalLegacyLayoutUsesTheSameHardClipIntersection() {
        assertOverlayClip(
            layout(positioned = false).copy(
                text = "日本",
                isVertical = true,
                lines = emptyList(),
            ),
        )
    }

    private fun assertOverlayClip(layout: BlockLayout) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = TranslationOverlayView(context)
        view.bindLayoutsForTest(listOf(layout), 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        view.drawLayoutsForTest(Canvas(bitmap))

        var insideInk = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val alpha = Color.alpha(bitmap.getPixel(x, y))
                val inComponent = spans.any { it.y == y && x in it.start until it.endExclusive }
                val inIntersection = inComponent && x < 48 && y < 50
                if (!inIntersection) assertEquals("outside clip intersection at $x,$y", 0, alpha)
                if (inIntersection && alpha != 0) insideInk++
            }
        }
        assertTrue("overlay produced no visible ink inside the hard clip", insideInk > 0)
    }

    private fun layout(positioned: Boolean): BlockLayout {
        val text = "MMMM"
        val positionedLines = if (positioned) {
            listOf(PositionedLine(text, 8, 8, 48, 24, FloatRect(4f, 4f, 60f, 36f)))
        } else {
            null
        }
        return BlockLayout(
            block = TranslationBlock(
                text = text,
                translation = text,
                width = 56f,
                height = 48f,
                x = 4f,
                y = 8f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
                label = 1,
                score = 1f,
            ),
            text = text,
            isVertical = false,
            originX = 32f,
            originY = 22f,
            safeW = 56f,
            safeH = 48f,
            fontSizePx = 20f,
            strokeWidth = 8f,
            drawAlign = TextAlign.CENTER,
            clipRect = legacyClip,
            lines = listOf(text),
            maskGeometry = geometry,
            planGeometryId = 0,
            maskComponentId = 0,
            cellRect = cell,
            positionedLines = positionedLines,
            conservativeOccupancy = positionedLines?.map { it.conservativeOccupancy }.orEmpty(),
            hardClip = HardClip(0, 0, cell),
        )
    }
}
