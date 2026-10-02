package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.translation.engines.rendering.BlockLayout
import eu.kanade.translation.engines.rendering.FloatRect
import eu.kanade.translation.engines.rendering.HardClip
import eu.kanade.translation.engines.rendering.PositionedLine
import eu.kanade.translation.engines.rendering.TextAlign
import eu.kanade.translation.model.MaskGeometry
import eu.kanade.translation.model.TranslationBlock
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

    @Test
    fun concaveMaskHoleAndThickStrokeHaveZeroAlphaOutsideAssignedComponent() {
        val concaveSpans = concaveSpans()
        val concaveGeometry = MaskGeometry.fromSpans(64, 64, concaveSpans)
        val bitmap = render(
            simpleLayout(
                geometry = concaveGeometry,
                componentId = 0,
                text = "MMMM",
                fontSize = 20f,
                strokeWidth = 8f,
            ),
        )

        assertOnlyComponentHasInk(bitmap, concaveGeometry, 0)
    }

    @Test
    fun componentClipIntersectsCellRect() {
        val concaveGeometry = MaskGeometry.fromSpans(64, 64, concaveSpans())
        val bitmap = render(
            simpleLayout(
                geometry = concaveGeometry,
                componentId = 0,
                text = "MMMM",
                fontSize = 20f,
                strokeWidth = 8f,
                cellRect = FloatRect(8f, 8f, 32f, 56f),
            ),
        )

        assertTrue(bitmap.nonTransparentPixels() > 0)
        for (y in 8 until 56) {
            for (x in 32 until 56) {
                assertEquals("right-half alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }
        for (y in 24 until 40) {
            for (x in 24 until 32) {
                assertEquals("hole alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }
    }

    @Test
    fun disconnectedMaskClipsToAssignedComponentOnly() {
        val disconnected = buildList {
            for (y in 4 until 60) {
                add(MaskGeometry.RowSpan(y, 4, 30))
                add(MaskGeometry.RowSpan(y, 34, 60))
            }
        }
        val disconnectedGeometry = MaskGeometry.fromSpans(64, 64, disconnected)
        val bitmap = render(simpleLayout(disconnectedGeometry, 0, "MMMMMMMM", 24f, 8f))

        var assignedInk = 0
        for (y in 4 until 60) {
            for (x in 4 until 30) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) assignedInk++
            }
        }
        assertTrue("assigned component must retain visible ink", assignedInk > 0)
        for (y in 0 until 64) {
            for (x in 34 until 60) {
                assertEquals("disconnected component alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }
    }

    @Test
    fun twoPositionedLayoutsSharingOneComponentKeepDisjointCellFootprintsWithAlphaGap() {
        val fullGeometry = MaskGeometry.fromSpans(64, 64, (0 until 64).map { MaskGeometry.RowSpan(it, 0, 64) })
        val left = positionedLayout(
            fullGeometry,
            listOf(PositionedLine("MMM", 2, 24, 28, 16, FloatRect(0f, 20f, 32f, 44f))),
            cellRect = FloatRect(0f, 0f, 31f, 64f),
        )
        val right = positionedLayout(
            fullGeometry,
            listOf(PositionedLine("MMM", 35, 24, 28, 16, FloatRect(33f, 20f, 65f, 44f))),
            cellRect = FloatRect(33f, 0f, 64f, 64f),
        )
        val bitmap = render(left, right)

        for (y in 0 until 64) {
            for (x in 31..33) {
                assertEquals("dead column alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }
        val leftBounds = bitmap.alphaBounds(0, 31)
        val rightBounds = bitmap.alphaBounds(33, 64)
        assertTrue(leftBounds.width() > 0)
        assertTrue(rightBounds.width() > 0)
        assertTrue("no alpha gap: ${leftBounds.right} vs ${rightBounds.left}", rightBounds.left - leftBounds.right >= 1)
    }

    @Test
    fun invalidComponentMetadataStillDrawsWithinAvailableCellAndLegacyClips() {
        val validGeometry = MaskGeometry.fromSpans(32, 32, (0 until 32).map { MaskGeometry.RowSpan(it, 0, 32) })
        val layout = simpleLayout(
            geometry = validGeometry,
            componentId = 0,
            text = "MMMM",
            fontSize = 20f,
            strokeWidth = 8f,
            cellRect = FloatRect(8f, 8f, 32f, 32f),
            clipRect = FloatRect(0f, 0f, 24f, 24f),
        ).copy(originX = 16f, originY = 16f)
        val bitmap = render(layout, pageWidth = 64, pageHeight = 64)

        assertTrue("invalid component metadata must not suppress overlay text", bitmap.nonTransparentPixels() > 0)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                if (x !in 8 until 24 || y !in 8 until 24) {
                    assertEquals("fallback clip alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
                }
            }
        }
    }

    private fun assertOverlayClip(layout: BlockLayout) {
        val bitmap = render(layout)

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

    private fun simpleLayout(
        geometry: MaskGeometry?,
        componentId: Int?,
        text: String,
        fontSize: Float,
        strokeWidth: Float,
        cellRect: FloatRect? = null,
        clipRect: FloatRect? = null,
    ) = BlockLayout(
        block = TranslationBlock(text = text, translation = text, width = 52f, height = 52f, x = 6f, y = 6f, symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f),
        text = text,
        isVertical = false,
        originX = 32f,
        originY = 32f,
        safeW = 56f,
        safeH = 56f,
        fontSizePx = fontSize,
        strokeWidth = strokeWidth,
        drawAlign = TextAlign.CENTER,
        clipRect = clipRect,
        lines = listOf(text),
        maskGeometry = geometry,
        planGeometryId = if (geometry == null) null else 0,
        maskComponentId = componentId,
        cellRect = cellRect,
    )

    private fun positionedLayout(
        geometry: MaskGeometry,
        lines: List<PositionedLine>,
        cellRect: FloatRect,
    ) = simpleLayout(geometry, 0, lines.joinToString(" ") { it.text }, 12f, 4f, cellRect).copy(
        positionedLines = lines,
        lines = lines.map { it.text },
        conservativeOccupancy = lines.map { it.conservativeOccupancy },
    )

    private fun render(vararg layouts: BlockLayout, pageWidth: Int = 64, pageHeight: Int = 64): Bitmap {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = TranslationOverlayView(context)
        view.bindLayoutsForTest(layouts.toList(), pageWidth, pageHeight)
        return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { view.drawLayoutsForTest(Canvas(it)) }
    }

    private fun concaveSpans() = buildList {
        for (y in 8 until 56) {
            if (y !in 24 until 40) {
                add(MaskGeometry.RowSpan(y, 8, 56))
            } else {
                add(MaskGeometry.RowSpan(y, 8, 24))
                add(MaskGeometry.RowSpan(y, 40, 56))
            }
        }
    }

    private fun assertOnlyComponentHasInk(bitmap: Bitmap, geometry: MaskGeometry, componentId: Int) {
        val component = geometry.components[componentId]
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val inComponent = component.spans.any { it.y == y && x in it.start until it.endExclusive }
                if (!inComponent) assertEquals("outside component alpha at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }
        assertTrue(bitmap.nonTransparentPixels() > 0)
    }

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
