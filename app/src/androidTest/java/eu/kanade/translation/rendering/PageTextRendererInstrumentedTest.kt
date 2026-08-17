package eu.kanade.translation.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTextRendererInstrumentedTest {
    @Test
    fun concaveMaskHoleAndThickStrokeHaveZeroAlphaOutsideAssignedComponent() {
        val spans = buildList {
            for (y in 8 until 56) {
                if (y !in 24 until 40) {
                    add(MaskGeometry.RowSpan(y, 8, 56))
                } else {
                    add(MaskGeometry.RowSpan(y, 8, 24))
                    add(MaskGeometry.RowSpan(y, 40, 56))
                }
            }
        }
        assertExactMask(spans, 0, "MMMM", 20f, 8f)
    }

    @Test
    fun disconnectedMaskClipsToAssignedComponentOnly() {
        val spans = buildList {
            for (y in 4 until 60) {
                add(MaskGeometry.RowSpan(y, 4, 30))
                add(MaskGeometry.RowSpan(y, 34, 60))
            }
        }
        val bitmap = render(spans, componentId = 0, text = "MMMMMMMM", font = 24f, stroke = 8f)
        var rightAlpha = 0
        for (y in 0 until 64) for (x in 34 until 60) rightAlpha += Color.alpha(bitmap.getPixel(x, y))
        assertEquals(0, rightAlpha)
    }

    @Test
    fun invalidDimensionsFailClosed() {
        val geometry = MaskGeometry.fromSpans(32, 32, listOf(MaskGeometry.RowSpan(0, 0, 32)))
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        renderer.bind(listOf(layout(geometry, 0, "M", 20f, 4f)), 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap))
        assertEquals(0, bitmap.nonTransparentPixels())
    }

    @Test
    fun fractionalCanvasTransformStillHasZeroSourcePixelsOutsideMask() {
        val spans = (10 until 54).map { MaskGeometry.RowSpan(it, 10, 54) }
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        val geometry = MaskGeometry.fromSpans(64, 64, spans)
        renderer.bind(listOf(layout(geometry, 0, "MMMM", 20f, 8f)), 64, 64)
        val bitmap = Bitmap.createBitmap(130, 130, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0.35f, 0.65f)
        canvas.scale(2f, 2f)
        renderer.draw(canvas)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val sourceX = (x - 0.35f) / 2f
                val sourceY = (y - 0.65f) / 2f
                if (sourceX < 9.5f || sourceX >= 54.5f || sourceY < 9.5f || sourceY >= 54.5f) {
                    assertEquals("alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
                }
            }
        }
    }

    @Test
    fun maskedMovedLayoutSamplesCleanedPixelsUnderFinalFootprint() {
        val cleaned = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(32f, 0f, 64f, 32f, android.graphics.Paint().apply { color = Color.BLACK })
        }
        val block = TranslationBlock(
            text = "M", translation = "M", width = 16f, height = 16f, x = 4f, y = 8f,
            symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f,
        ).apply { textColor = 0xFF000000L }
        val geometry = MaskGeometry.fromSpans(
            64,
            32,
            (0 until 32).map { MaskGeometry.RowSpan(it, 0, 64) },
        )
        val moved = layout(geometry, 0, "M", 16f, 2f).copy(
            block = block,
            originX = 48f,
            conservativeFootprint = FloatRect(40f, 8f, 56f, 24f),
        )

        val resolved = RenderColorEstimator.resolveLayoutColors(cleaned, listOf(moved))

        assertEquals(0xFFFFFFFFL, resolved.single().textColor)
        assertEquals(0xFF000000L, block.textColor)
    }

    @Test
    fun rapidRebindAndClearCannotDrawStaleState() {
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        val first = layout(null, null, "FIRST", 18f, 2f)
        renderer.bind(listOf(first), 64, 64)
        renderer.bind(emptyList(), 64, 64)
        val rebound = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(rebound))
        assertEquals(0, rebound.nonTransparentPixels())

        renderer.bind(listOf(first), 64, 64)
        renderer.clear()
        val cleared = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(cleared))
        assertEquals(0, cleared.nonTransparentPixels())
    }

    @Test
    fun unmaskedVerticalCjkMatchesLegacyPixelsExactly() {
        val vertical = layout(null, null, "日「本ー", 18f, 2f).copy(isVertical = true, lines = emptyList())
        val actual = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        PageTextRenderer(Typeface.DEFAULT_BOLD).apply {
            bind(listOf(vertical), 64, 64)
            draw(Canvas(actual))
        }
        val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        drawLegacyVertical(Canvas(expected), vertical)
        assertTrue(actual.sameAs(expected))
    }

    @Test
    fun mixedVerticalLatinRotationChangesInkOrientationWithinConservativeBounds() {
        val latin = layout(null, null, "I", 22f, 2f).copy(isVertical = true, lines = emptyList())
        val cjk = latin.copy(text = "一")
        val latinBitmap = renderLayout(latin)
        val cjkBitmap = renderLayout(cjk)
        val latinBounds = latinBitmap.alphaBounds()
        val cjkBounds = cjkBitmap.alphaBounds()
        assertTrue(latinBounds.width() > latinBounds.height())
        assertTrue(cjkBounds.width() > cjkBounds.height())
        listOf(latinBounds, cjkBounds).forEach { bounds ->
            assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 64 && bounds.bottom <= 64)
        }
    }

    @Test
    fun unmaskedHorizontalAndVerticalRenderOnSoftwareCanvas() {
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        val horizontal = layout(null, null, "Hello שלום नमस्ते ไทย", 16f, 2f)
        val vertical = horizontal.copy(text = "日本👩‍🚀", isVertical = true, lines = emptyList(), originX = 48f)
        renderer.bind(listOf(horizontal, vertical), 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap))
        assertTrue(bitmap.nonTransparentPixels() > 0)
    }

    private fun assertExactMask(spans: List<MaskGeometry.RowSpan>, componentId: Int, text: String, font: Float, stroke: Float) {
        val bitmap = render(spans, componentId, text, font, stroke)
        val geometry = MaskGeometry.fromSpans(64, 64, spans)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                if (!geometry.components[componentId].spans.any { it.y == y && x in it.start until it.endExclusive }) {
                    assertEquals("outside-mask alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
                }
            }
        }
        assertTrue(bitmap.nonTransparentPixels() > 0)
    }

    private fun render(spans: List<MaskGeometry.RowSpan>, componentId: Int, text: String, font: Float, stroke: Float): Bitmap {
        val geometry = MaskGeometry.fromSpans(64, 64, spans)
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        renderer.bind(listOf(layout(geometry, componentId, text, font, stroke)), 64, 64)
        return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { renderer.draw(Canvas(it)) }
    }

    private fun layout(geometry: MaskGeometry?, componentId: Int?, text: String, font: Float, stroke: Float) = BlockLayout(
        block = TranslationBlock(text = text, translation = text, width = 52f, height = 52f, x = 6f, y = 6f, symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f),
        text = text, isVertical = false, originX = 32f, originY = 32f, safeW = 56f, safeH = 56f,
        fontSizePx = font, strokeWidth = stroke, drawAlign = TextAlign.CENTER, clipRect = null,
        lines = listOf(text), maskGeometry = geometry, maskComponentId = componentId,
        conservativeFootprint = FloatRect(0f, 0f, 64f, 64f),
    )

    private fun renderLayout(layout: BlockLayout): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        PageTextRenderer(Typeface.DEFAULT_BOLD).apply {
            bind(listOf(layout), 64, 64)
            draw(Canvas(it))
        }
    }

    private fun drawLegacyVertical(canvas: Canvas, layout: BlockLayout) {
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = layout.textColor.toInt()
            textSize = layout.fontSizePx
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val stroke = android.graphics.Paint(fill).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeWidth = kotlin.math.max(2f, layout.strokeWidth)
            color = Color.WHITE
        }
        val chars = layout.text.filterNot { it == '\r' || it == '\n' || it == ' ' }
        val charStep = layout.fontSizePx * 1.05f
        val colStep = layout.fontSizePx * 1.25f
        val perColumn = kotlin.math.max(1, (layout.safeH / charStep).toInt())
        val columns = (chars.length + perColumn - 1) / perColumn
        val right = layout.originX + columns * colStep / 2f
        val metrics = fill.fontMetrics
        for (column in 0 until columns) {
            val start = column * perColumn
            val end = minOf(chars.length, start + perColumn)
            val x = right - column * colStep - colStep / 2f
            var y = layout.originY - (end - start) * charStep / 2f
            for (index in start until end) {
                val glyph = TextLayoutPlanner.verticalGlyph(chars[index].toString())
                val baseline = y - metrics.ascent
                canvas.drawText(glyph, x, baseline, stroke)
                canvas.drawText(glyph, x, baseline, fill)
                y += charStep
            }
        }
    }

    private fun Bitmap.alphaBounds(): android.graphics.Rect {
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.alpha(getPixel(x, y)) != 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                }
            }
        }
        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun Bitmap.nonTransparentPixels(): Int {
        var count = 0
        for (y in 0 until height) for (x in 0 until width) if (Color.alpha(getPixel(x, y)) != 0) count++
        return count
    }
}
