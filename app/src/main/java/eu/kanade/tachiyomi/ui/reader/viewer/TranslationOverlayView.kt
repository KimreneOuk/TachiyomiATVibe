package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.rendering.BlockLayout
import eu.kanade.translation.rendering.ComponentClipCache
import eu.kanade.translation.rendering.TextAlign
import eu.kanade.translation.rendering.TextLayoutPlanner
import eu.kanade.translation.rendering.TextMeasurer
import kotlin.math.max

/**
 * Draws translated text over the cleaned pager image. Coordinates remain in the
 * source image space until draw time, so SSIV owns all pan/zoom/orientation
 * transforms and only one background bitmap is decoded.
 */
internal class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val typeface: Typeface = ResourcesCompat.getFont(context, R.font.animeace)?.let {
        Typeface.create(it, Typeface.BOLD)
    } ?: Typeface.DEFAULT_BOLD
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface =
            this@TranslationOverlayView.typeface
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = this@TranslationOverlayView.typeface
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val measurer = object : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float {
            fill.textSize = fontSizePx
            return fill.measureText(text)
        }
        override fun lineHeight(fontSizePx: Float): Float {
            fill.textSize = fontSizePx
            val metrics = fill.fontMetrics
            return metrics.descent - metrics.ascent
        }
    }

    private var imageView: SubsamplingScaleImageView? = null
    private var blocks: List<TranslationBlock> = emptyList()
    private var pageWidth = 0
    private var pageHeight = 0
    private var preparedLayouts = emptyList<PreparedOverlayLayout>()
    private var framePending = false
    private val frameCallback = Choreographer.FrameCallback {
        framePending = false
        invalidate()
    }

    fun bind(imageView: SubsamplingScaleImageView?, blocks: List<TranslationBlock>, pageWidth: Int, pageHeight: Int) {
        this.imageView = imageView
        this.blocks = blocks
        this.pageWidth = pageWidth
        this.pageHeight = pageHeight
        val layouts = TextLayoutPlanner.plan(blocks, pageWidth.toFloat(), pageHeight.toFloat(), 1, false, measurer)
        prepareLayouts(layouts, pageWidth, pageHeight)
        invalidate()
    }

    /**
     * Build bounded component paths at bind time. Missing/invalid component
     * metadata degrades to the available cell/legacy clips and still draws,
     * preserving the planner's never-drop contract.
     */
    private fun prepareLayouts(layouts: List<BlockLayout>, pageWidth: Int, pageHeight: Int) {
        val clipCache = ComponentClipCache<Path>(MAX_CACHED_COMPONENTS, MAX_CACHED_SPANS) { component ->
            Path().apply {
                fillType = Path.FillType.WINDING
                for (span in component.spans) {
                    addRect(
                        span.start.toFloat(),
                        span.y.toFloat(),
                        span.endExclusive.toFloat(),
                        span.y + 1f,
                        Path.Direction.CW,
                    )
                }
            }
        }
        preparedLayouts = layouts.map { layout ->
            val geometry = layout.maskGeometry
            val componentPath = if (
                geometry != null &&
                geometry.width == pageWidth &&
                geometry.height == pageHeight &&
                layout.planGeometryId != null &&
                layout.maskComponentId != null
            ) {
                clipCache.resolve(layout.planGeometryId, layout.maskComponentId, geometry)
            } else {
                null
            }
            PreparedOverlayLayout(layout, componentPath)
        }
    }

    fun clear() = bind(null, emptyList(), 0, 0)

    /** Called from SSIV state callbacks. Coalesce pan/zoom updates to one redraw per frame. */
    fun onImageTransformChanged() {
        if (!framePending) {
            framePending = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePending = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ssiv = imageView ?: return
        if (!ssiv.isReady || blocks.isEmpty() || pageWidth <= 0 || pageHeight <= 0) return
        val topLeft = ssiv.sourceToViewCoord(0f, 0f) ?: return
        val bottomRight = ssiv.sourceToViewCoord(pageWidth.toFloat(), pageHeight.toFloat()) ?: return
        val scaleX = (bottomRight.x - topLeft.x) / pageWidth
        val scaleY = (bottomRight.y - topLeft.y) / pageHeight
        if (scaleX <= 0f || scaleY <= 0f) return

        canvas.save()
        canvas.translate(topLeft.x, topLeft.y)
        canvas.scale(scaleX, scaleY)
        for (prepared in preparedLayouts) drawLayout(canvas, prepared)
        canvas.restore()
    }

    private fun drawVerticalLayout(canvas: Canvas, layout: eu.kanade.translation.rendering.BlockLayout) {
        val chars = layout.text.filterNot { it == '\r' || it == '\n' || it == ' ' }
        if (chars.isEmpty()) return
        val charStep = layout.fontSizePx * VERTICAL_CHAR_STEP
        val colStep = layout.fontSizePx * VERTICAL_COL_STEP
        val charsPerColumn = max(1, (layout.safeH / charStep).toInt())
        val columnCount = (chars.length + charsPerColumn - 1) / charsPerColumn
        val totalWidth = columnCount * colStep
        val right = layout.originX + totalWidth / 2f
        val metrics = fill.fontMetrics
        fill.textAlign = Paint.Align.CENTER
        stroke.textAlign = Paint.Align.CENTER
        for (columnIndex in 0 until columnCount) {
            val start = columnIndex * charsPerColumn
            val end = minOf(chars.length, start + charsPerColumn)
            val columnX = right - columnIndex * colStep - colStep / 2f
            val columnHeight = (end - start) * charStep
            var y = layout.originY - columnHeight / 2f
            for (index in start until end) {
                val glyph = VERTICAL_PUNCTUATION_MAP[chars[index]] ?: chars[index]
                val baseline = y - metrics.ascent
                canvas.drawText(glyph.toString(), columnX, baseline, stroke)
                canvas.drawText(glyph.toString(), columnX, baseline, fill)
                y += charStep
            }
        }
    }

    private fun drawLayout(canvas: Canvas, prepared: PreparedOverlayLayout) {
        val layout = prepared.layout
        val textColor = layout.block.textColor.toInt()
        val luma =
            ((textColor shr 16 and 0xFF) * 299 + (textColor shr 8 and 0xFF) * 587 + (textColor and 0xFF) * 114) / 1000
        fill.color = textColor
        fill.textSize = layout.fontSizePx
        stroke.color = if (luma < 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        stroke.strokeWidth = max(2f, layout.strokeWidth)
        stroke.textSize = layout.fontSizePx
        // Every layout form consumes the same structural clip intersection:
        // component path -> independent cell -> legacy collision clip.
        val saved = canvas.save()
        try {
            prepared.componentPath?.let(canvas::clipPath)
            layout.cellRect?.let { cell -> canvas.clipRect(cell.left, cell.top, cell.right, cell.bottom) }
            layout.clipRect?.let { clip -> canvas.clipRect(clip.left, clip.top, clip.right, clip.bottom) }
            val positioned = layout.positionedLines
            if (positioned != null) {
                drawPositionedLayout(canvas, layout, positioned)
            } else if (layout.isVertical) {
                drawVerticalLayout(canvas, layout)
            } else {
                val lines = layout.lines
                val fm = fill.fontMetrics
                var y = layout.originY - lines.size * (fm.descent - fm.ascent) / 2f - fm.ascent
                fill.textAlign =
                    when (layout.drawAlign) {
                        TextAlign.LEFT -> Paint.Align.LEFT
                        TextAlign.RIGHT -> Paint.Align.RIGHT
                        TextAlign.CENTER -> Paint.Align.CENTER
                    }
                stroke.textAlign = fill.textAlign
                lines.forEach { line ->
                    canvas.drawText(line, layout.originX, y, stroke)
                    canvas.drawText(line, layout.originX, y, fill)
                    y += fm.descent - fm.ascent
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /**
     * T912 quality repair: draws the planner's positioned lines with the same
     * convention [PageTextRenderer]'s one-line StaticLayouts produce. Clips are
     * applied by [drawLayout] once per layout, then each line is translated to
     * its integer placement and drawn stroke-then-fill
     * top-anchored at (leftPx, topPx), i.e. baseline = top - ascent, x = left,
     * LEFT-aligned. Per-frame allocation stays limited to the draw calls
     * themselves (no StaticLayout in the overlay).
     */
    private fun drawPositionedLayout(
        canvas: Canvas,
        layout: eu.kanade.translation.rendering.BlockLayout,
        lines: List<eu.kanade.translation.rendering.PositionedLine>,
    ) {
        fill.textAlign = Paint.Align.LEFT
        stroke.textAlign = Paint.Align.LEFT
        val ascent = fill.fontMetrics.ascent
        for (line in lines) {
            if (line.text.isEmpty()) continue
            val lineSave = canvas.save()
            canvas.translate(line.leftPx.toFloat(), line.topPx.toFloat())
            val baseline = -ascent
            canvas.drawText(line.text, 0f, baseline, stroke)
            canvas.drawText(line.text, 0f, baseline, fill)
            canvas.restoreToCount(lineSave)
        }
    }

    /** Android-test seam: bind already-planned layouts without an SSIV. */
    internal fun bindLayoutsForTest(layouts: List<BlockLayout>, pageWidth: Int, pageHeight: Int) {
        this.pageWidth = pageWidth
        this.pageHeight = pageHeight
        prepareLayouts(layouts, pageWidth, pageHeight)
    }

    /** Android-test seam: exercises the exact production prepared draw path. */
    internal fun drawLayoutsForTest(canvas: Canvas) {
        for (prepared in preparedLayouts) drawLayout(canvas, prepared)
    }

    private data class PreparedOverlayLayout(val layout: BlockLayout, val componentPath: Path?)

    private companion object {
        private const val MAX_CACHED_COMPONENTS = 64
        private const val MAX_CACHED_SPANS = 100_000
        private const val VERTICAL_CHAR_STEP = 1.05f
        private const val VERTICAL_COL_STEP = 1.25f
        private val VERTICAL_PUNCTUATION_MAP = mapOf(
            'ー' to '︱', '―' to '︱', '─' to '︱', '-' to '︱',
            '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄',
            '（' to '︵', '）' to '︶', '(' to '︵', ')' to '︶',
            '【' to '︻', '】' to '︼', '〔' to '︹', '〕' to '︺',
            '［' to '﹇', '］' to '﹈', '[' to '﹇', ']' to '﹈',
            '{' to '︷', '}' to '︸', '｛' to '︷', '｝' to '︸',
        )
    }
}
