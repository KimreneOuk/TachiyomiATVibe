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
    fun componentClipIntersectsCellRect() {
        // Holey/concave component: a 48x48 square with a 16x16 hole.
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
        val geometry = MaskGeometry.fromSpans(64, 64, spans)
        // cellRect covers only the LEFT half of the component.
        val clipped = layout(geometry, 0, "MMMM", 20f, 8f, cellRect = FloatRect(8f, 8f, 32f, 56f))
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        renderer.bind(listOf(clipped), 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap))

        val component = geometry.components[0]
        fun inComponent(x: Int, y: Int) =
            component.spans.any { it.y == y && x in it.start until it.endExclusive }

        // Ink exists inside cellRect ∩ component (left half, outside the hole).
        var insideCellAndComponent = 0
        for (y in 8 until 56) {
            for (x in 8 until 32) {
                if (inComponent(x, y) && Color.alpha(bitmap.getPixel(x, y)) != 0) insideCellAndComponent++
            }
        }
        assertTrue(insideCellAndComponent > 0)

        // ZERO alpha in the component's right half — inside the component path but
        // outside cellRect: proves the rect clip intersects the path.
        for (y in 8 until 56) {
            for (x in 32 until 56) {
                assertEquals("right-half alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }

        // ZERO alpha inside the hole even where cellRect covers it: proves the
        // component path clip still applies within the rect (intersection, not
        // mutual exclusion).
        for (y in 24 until 40) {
            for (x in 24 until 32) {
                assertEquals("hole alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }
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
    fun colorRecomputeSamplesTheCurrentBlockRectangle() {
        val cleaned = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(32f, 0f, 64f, 32f, android.graphics.Paint().apply { color = Color.BLACK })
        }
        val block = TranslationBlock(
            text = "M", translation = "M", width = 16f, height = 16f, x = 4f, y = 8f,
            symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f,
        ).apply { textColor = 0xFF000000L }
        // The current production API recomputes against each block's persisted
        // OCR rectangle. It does not accept a planned BlockLayout or a made-up
        // footprint argument, so keep this characterization test on that API.
        RenderColorEstimator.recomputeFor(cleaned, listOf(block))
        assertEquals(0xFF000000L, block.textColor)

        block.x = 40f
        RenderColorEstimator.recomputeFor(cleaned, listOf(block))
        assertEquals(0xFFFFFFFFL, block.textColor)
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

    // ---- T912 slice 5: adaptive positioned-line shaping -------------------

    @Test
    fun positionedAdaptiveLinesStayInsideComponentWithThickStrokeAndComplexGlyphs() {
        // Hole/concave component, thick stroke, combining mark (e + U+0301),
        // emoji ZWJ, and punctuation overhang: one shaped line per planner
        // placement, zero alpha outside the component and inside the hole.
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
        val geometry = MaskGeometry.fromSpans(64, 64, spans)
        val text = "e\u0301!\uD83D\uDC69\u200D\uD83D\uDE80M"
        val line = PositionedLine(text, leftPx = 10, topPx = 10, layoutWidthPx = 48, layoutHeightPx = 16, conservativeOccupancy = FloatRect(6f, 6f, 62f, 30f))
        val bitmap = renderLayout(
            positionedLayout(geometry, 0, listOf(line), text, font = 12f, stroke = 8f),
        )

        fun inComponent(x: Int, y: Int) =
            geometry.components[0].spans.any { it.y == y && x in it.start until it.endExclusive }

        var ink = 0
        var inkBottom = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val alpha = Color.alpha(bitmap.getPixel(x, y))
                if (alpha != 0) {
                    ink++
                    inkBottom = maxOf(inkBottom, y)
                    assertTrue("ink outside component at $x,$y", inComponent(x, y))
                    if (y in 24 until 40) {
                        assertTrue("ink in hole at $x,$y", x < 24 || x >= 40)
                    }
                }
            }
        }
        assertTrue(ink > 0)
        // One-line shaping: no second line is drawn below the planned line box.
        assertTrue("ink bottom $inkBottom crosses a would-be second line", inkBottom <= 10 + 20)
    }

    @Test
    fun twoAdaptiveLayoutsSharingOneComponentKeepDisjointCellFootprintsWithAlphaGap() {
        val geometry = MaskGeometry.fromSpans(64, 64, (0 until 64).map { MaskGeometry.RowSpan(it, 0, 64) })
        val left = positionedLayout(
            geometry, 0,
            listOf(PositionedLine("MMM", 2, 24, 28, 16, FloatRect(0f, 20f, 32f, 44f))),
            "MMM", 12f, 4f, cellRect = FloatRect(0f, 0f, 31f, 64f),
        )
        val right = positionedLayout(
            geometry, 0,
            listOf(PositionedLine("MMM", 35, 24, 28, 16, FloatRect(33f, 20f, 65f, 44f))),
            "MMM", 12f, 4f, cellRect = FloatRect(33f, 0f, 64f, 64f),
        )
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        renderer.bind(listOf(left, right), 64, 64)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap))

        // Structural disjointness: nothing in the dead column, each footprint
        // inside its own hard cell, and at least a 1px alpha gap between them.
        for (y in 0 until 64) {
            assertEquals("dead column alpha at 31,$y", 0, Color.alpha(bitmap.getPixel(31, y)))
            assertEquals("dead column alpha at 32,$y", 0, Color.alpha(bitmap.getPixel(32, y)))
            assertEquals("dead column alpha at 33,$y", 0, Color.alpha(bitmap.getPixel(33, y)))
        }
        val leftBounds = bitmap.alphaBounds(0, 31)
        val rightBounds = bitmap.alphaBounds(33, 64)
        assertTrue(leftBounds.width() > 0)
        assertTrue(rightBounds.width() > 0)
        assertTrue("no alpha gap: ${leftBounds.right} vs ${rightBounds.left}", rightBounds.left - leftBounds.right >= 1)
    }

    @Test
    fun sourceHyphenAndAtomicOverwideWordShapeExactlyOneLine() {
        val geometry = MaskGeometry.fromSpans(64, 64, (4 until 60).map { MaskGeometry.RowSpan(it, 4, 60) })
        for (text in listOf("NEE-CHAN", "HANAZUMI")) {
            // layoutWidthPx is intentionally NARROWER than the text advance:
            // simple breaking with no hyphenation and maxLines(1) must keep
            // exactly one line (overflow is contained by the structural clip).
            val line = PositionedLine(text, 8, 20, 30, 16, FloatRect(4f, 16f, 60f, 40f))
            val bitmap = renderLayout(positionedLayout(geometry, 0, listOf(line), text, 12f, 4f))
            var ink = 0
            var inkBottom = 0
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    if (Color.alpha(bitmap.getPixel(x, y)) != 0) {
                        ink++
                        inkBottom = maxOf(inkBottom, y)
                        val inComponent = geometry.components[0].spans.any { it.y == y && x in it.start until it.endExclusive }
                        assertTrue("ink outside component at $x,$y", inComponent)
                    }
                }
            }
            assertTrue("$text produced no ink", ink > 0)
            assertTrue("$text drew a second line (ink bottom $inkBottom)", inkBottom <= 20 + 20)
        }
    }

    @Test
    fun fractionalTransformKeepsPositionedLinesInsideComponent() {
        val geometry = MaskGeometry.fromSpans(64, 64, (10 until 54).map { MaskGeometry.RowSpan(it, 10, 54) })
        val line = PositionedLine("MMMM", 12, 16, 40, 18, FloatRect(8f, 12f, 56f, 38f))
        val renderer = PageTextRenderer(Typeface.DEFAULT_BOLD)
        renderer.bind(listOf(positionedLayout(geometry, 0, listOf(line), "MMMM", 14f, 4f)), 64, 64)
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
    fun positionedLinesAreLeftAlignedInsideTheirPlannedWidth() {
        val geometry = MaskGeometry.fromSpans(64, 64, (0 until 64).map { MaskGeometry.RowSpan(it, 0, 64) })
        // Two lines sharing leftPx with very different advances: left-aligned
        // shaping starts both at (approximately) leftPx; centering would push
        // the short line far right.
        val lines = listOf(
            PositionedLine("M", 8, 8, 48, 16, FloatRect(4f, 4f, 60f, 28f)),
            PositionedLine("MMMMM", 8, 40, 48, 16, FloatRect(4f, 36f, 60f, 60f)),
        )
        val bitmap = renderLayout(positionedLayout(geometry, 0, lines, "M MMMMM", 12f, 2f))

        val shortInkLeft = firstInkColumn(bitmap, rows = 8 until 24)
        val longInkLeft = firstInkColumn(bitmap, rows = 40 until 56)
        assertTrue("short line ink left $shortInkLeft", shortInkLeft in 8..12)
        assertTrue("long line ink left $longInkLeft", longInkLeft in 8..12)
    }

    private fun firstInkColumn(bitmap: Bitmap, rows: IntRange): Int {
        for (x in 0 until bitmap.width) {
            for (y in rows) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) return x
            }
        }
        return -1
    }

    private fun positionedLayout(
        geometry: MaskGeometry?,
        componentId: Int?,
        lines: List<PositionedLine>,
        text: String,
        font: Float,
        stroke: Float,
        planGeometryId: Int? = if (geometry == null) null else 0,
        cellRect: FloatRect? = null,
    ) = BlockLayout(
        block = TranslationBlock(text = text, translation = text, width = 52f, height = 52f, x = 6f, y = 6f, symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f),
        text = text, isVertical = false, originX = 32f, originY = 32f, safeW = 56f, safeH = 56f,
        fontSizePx = font, strokeWidth = stroke, drawAlign = TextAlign.CENTER, clipRect = null,
        lines = lines.map { it.text }, positionedLines = lines, maskGeometry = geometry,
        planGeometryId = planGeometryId, maskComponentId = componentId, cellRect = cellRect,
    )

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

    private fun layout(
        geometry: MaskGeometry?,
        componentId: Int?,
        text: String,
        font: Float,
        stroke: Float,
        planGeometryId: Int? = if (geometry == null) null else 0,
        cellRect: FloatRect? = null,
    ) = BlockLayout(
        block = TranslationBlock(text = text, translation = text, width = 52f, height = 52f, x = 6f, y = 6f, symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f),
        text = text, isVertical = false, originX = 32f, originY = 32f, safeW = 56f, safeH = 56f,
        fontSizePx = font, strokeWidth = stroke, drawAlign = TextAlign.CENTER, clipRect = null,
        lines = listOf(text), maskGeometry = geometry, planGeometryId = planGeometryId,
        maskComponentId = componentId, cellRect = cellRect,
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
            color = layout.block.textColor.toInt()
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

    private fun Bitmap.alphaBounds(): android.graphics.Rect = alphaBounds(0, width)

    private fun Bitmap.alphaBounds(minX: Int, maxX: Int): android.graphics.Rect {
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (y in 0 until height) {
            for (x in minX until maxX) {
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
