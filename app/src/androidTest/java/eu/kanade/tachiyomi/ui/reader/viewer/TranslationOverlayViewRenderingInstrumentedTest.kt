package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.rendering.BlockLayout
import eu.kanade.translation.rendering.FloatRect
import eu.kanade.translation.rendering.PositionedLine
import eu.kanade.translation.rendering.TextAlign
import eu.kanade.translation.segmentation.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/** Pixel characterizations for the production overlay painter. */
@RunWith(AndroidJUnit4::class)
class TranslationOverlayViewRenderingInstrumentedTest {
    @Test
    fun fractionalCanvasTransformKeepsLegacyLayoutInsideComponent() {
        val geometry = squareGeometry()
        val layout = legacyLayout(geometry, "MMMM", fontSize = 20f, strokeWidth = 8f)
        val bitmap = renderTransformed(layout)

        assertOnlySourceSquareHasInk(bitmap)
    }

    @Test
    fun unmaskedVerticalCjkMatchesLegacyPixelsExactly() {
        val layout = legacyLayout(null, "日「本ー", fontSize = 18f, strokeWidth = 2f).copy(
            isVertical = true,
            lines = emptyList(),
        )
        val actual = render(layout)
        val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        drawExpectedLegacyVertical(Canvas(expected), layout)

        assertTrue(actual.sameAs(expected))
    }

    @Test
    fun mixedVerticalLatinUsesTheCurrentUnrotatedOverlayPainterWithinBounds() {
        val geometry = squareGeometry()
        val layout = legacyLayout(geometry, "日I", fontSize = 18f, strokeWidth = 2f).copy(
            isVertical = true,
            lines = emptyList(),
        )
        val actual = render(layout)
        val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(expected).apply {
            clipRect(10f, 10f, 54f, 54f)
            drawExpectedLegacyVertical(this, layout)
        }

        assertTrue("mixed CJK/Latin overlay output changed", actual.sameAs(expected))
        val bounds = actual.alphaBounds()
        assertTrue(bounds.width() > 0 && bounds.height() > bounds.width())
        assertTrue(bounds.left >= 10 && bounds.top >= 10 && bounds.right <= 54 && bounds.bottom <= 54)
    }

    @Test
    fun unmaskedHorizontalAndVerticalRenderOnSoftwareCanvas() {
        val horizontal = legacyLayout(null, "Hello שלום नमस्ते ไทย", fontSize = 16f, strokeWidth = 2f)
        val vertical = horizontal.copy(text = "日本👩‍🚀", isVertical = true, lines = emptyList(), originX = 48f)

        assertTrue(render(horizontal, vertical).nonTransparentPixels() > 0)
    }

    @Test
    fun positionedLinesStayInsideComponentWithThickStrokeAndComplexGlyphs() {
        val geometry = concaveGeometry()
        val text = "e\u0301!\uD83D\uDC69\u200D\uD83D\uDE80M"
        val line = PositionedLine(text, 10, 10, 48, 16, FloatRect(6f, 6f, 62f, 30f))
        val layout = positionedLayout(geometry, listOf(line), text, fontSize = 12f, strokeWidth = 8f)
        val bitmap = render(layout)

        assertOnlyComponentHasInk(bitmap, geometry)
    }

    @Test
    fun fractionalCanvasTransformKeepsPositionedLinesInsideComponent() {
        val geometry = squareGeometry()
        val line = PositionedLine("MMMM", 12, 16, 40, 18, FloatRect(8f, 12f, 56f, 38f))
        val bitmap = renderTransformed(positionedLayout(geometry, listOf(line), "MMMM", 14f, 4f))

        assertOnlySourceSquareHasInk(bitmap)
    }

    @Test
    fun positionedLinesAreLeftAlignedAtTheirPlannedLeftCoordinate() {
        val geometry = MaskGeometry.fromSpans(64, 64, (0 until 64).map { MaskGeometry.RowSpan(it, 0, 64) })
        val lines = listOf(
            PositionedLine("M", 8, 8, 48, 16, FloatRect(4f, 4f, 60f, 28f)),
            PositionedLine("MMMMM", 8, 40, 48, 16, FloatRect(4f, 36f, 60f, 60f)),
        )
        val bitmap = render(positionedLayout(geometry, lines, "M MMMMM", 12f, 2f))

        assertTrue(firstInkColumn(bitmap, 8 until 24) in 8..12)
        assertTrue(firstInkColumn(bitmap, 40 until 56) in 8..12)
    }

    private fun render(vararg layouts: BlockLayout): Bitmap {
        val view = TranslationOverlayView(InstrumentationRegistry.getInstrumentation().targetContext)
        view.bindLayoutsForTest(layouts.toList(), 64, 64)
        return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { view.drawLayoutsForTest(Canvas(it)) }
    }

    private fun renderTransformed(layout: BlockLayout): Bitmap {
        val view = TranslationOverlayView(InstrumentationRegistry.getInstrumentation().targetContext)
        view.bindLayoutsForTest(listOf(layout), 64, 64)
        return Bitmap.createBitmap(130, 130, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                translate(0.35f, 0.65f)
                scale(2f, 2f)
                view.drawLayoutsForTest(this)
            }
        }
    }

    private fun legacyLayout(geometry: MaskGeometry?, text: String, fontSize: Float, strokeWidth: Float) = BlockLayout(
        block = TranslationBlock(text = text, translation = text, width = 52f, height = 52f, x = 6f, y = 6f, symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f),
        text = text,
        isVertical = false,
        originX = 32f,
        originY = 32f,
        safeW = 44f,
        safeH = 44f,
        fontSizePx = fontSize,
        strokeWidth = strokeWidth,
        drawAlign = TextAlign.CENTER,
        clipRect = null,
        lines = listOf(text),
        maskGeometry = geometry,
        planGeometryId = geometry?.let { 0 },
        maskComponentId = geometry?.let { 0 },
    )

    private fun positionedLayout(
        geometry: MaskGeometry,
        lines: List<PositionedLine>,
        text: String,
        fontSize: Float,
        strokeWidth: Float,
    ) = legacyLayout(geometry, text, fontSize, strokeWidth).copy(
        lines = lines.map { it.text },
        positionedLines = lines,
        conservativeOccupancy = lines.map { it.conservativeOccupancy },
    )

    private fun squareGeometry() = MaskGeometry.fromSpans(64, 64, (10 until 54).map { MaskGeometry.RowSpan(it, 10, 54) })

    private fun concaveGeometry() = MaskGeometry.fromSpans(64, 64, buildList {
        for (y in 8 until 56) {
            if (y !in 24 until 40) add(MaskGeometry.RowSpan(y, 8, 56)) else {
                add(MaskGeometry.RowSpan(y, 8, 24))
                add(MaskGeometry.RowSpan(y, 40, 56))
            }
        }
    })

    private fun assertOnlyComponentHasInk(bitmap: Bitmap, geometry: MaskGeometry) {
        var ink = 0
        val component = geometry.components.single()
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val alpha = Color.alpha(bitmap.getPixel(x, y))
                if (alpha != 0) {
                    ink++
                    assertTrue("ink outside component at $x,$y", component.spans.any { it.y == y && x in it.start until it.endExclusive })
                }
            }
        }
        assertTrue(ink > 0)
    }

    private fun assertOnlySourceSquareHasInk(bitmap: Bitmap) {
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

    private fun drawExpectedLegacyVertical(canvas: Canvas, layout: BlockLayout) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val typeface = ResourcesCompat.getFont(context, R.font.animeace)?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            color = layout.block.textColor.toInt()
            textSize = layout.fontSizePx
            textAlign = Paint.Align.CENTER
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(2f, layout.strokeWidth)
            color = Color.WHITE
        }
        val chars = layout.text.filterNot { it == '\r' || it == '\n' || it == ' ' }
        val charStep = layout.fontSizePx * 1.05f
        val colStep = layout.fontSizePx * 1.25f
        val charsPerColumn = max(1, (layout.safeH / charStep).toInt())
        val columns = (chars.length + charsPerColumn - 1) / charsPerColumn
        val right = layout.originX + columns * colStep / 2f
        val metrics = fill.fontMetrics
        for (column in 0 until columns) {
            val start = column * charsPerColumn
            val end = minOf(chars.length, start + charsPerColumn)
            val x = right - column * colStep - colStep / 2f
            var y = layout.originY - (end - start) * charStep / 2f
            for (index in start until end) {
                val glyph = verticalGlyph(chars[index])
                val baseline = y - metrics.ascent
                canvas.drawText(glyph.toString(), x, baseline, stroke)
                canvas.drawText(glyph.toString(), x, baseline, fill)
                y += charStep
            }
        }
    }

    private fun verticalGlyph(character: Char) = when (character) {
        'ー', '―', '─', '-' -> '︱'
        '「' -> '﹁'
        '」' -> '﹂'
        '『' -> '﹃'
        '』' -> '﹄'
        '（', '(' -> '︵'
        '）', ')' -> '︶'
        '【' -> '︻'
        '】' -> '︼'
        '〔' -> '︹'
        '〕' -> '︺'
        '［', '[' -> '﹇'
        '］', ']' -> '﹈'
        '{', '｛' -> '︷'
        '}', '｝' -> '︸'
        else -> character
    }

    private fun firstInkColumn(bitmap: Bitmap, rows: IntRange): Int {
        for (x in 0 until bitmap.width) for (y in rows) if (Color.alpha(bitmap.getPixel(x, y)) != 0) return x
        return -1
    }

    private fun Bitmap.alphaBounds(): android.graphics.Rect {
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (y in 0 until height) for (x in 0 until width) if (Color.alpha(getPixel(x, y)) != 0) {
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun Bitmap.nonTransparentPixels(): Int {
        var count = 0
        for (y in 0 until height) for (x in 0 until width) if (Color.alpha(getPixel(x, y)) != 0) count++
        return count
    }
}
