package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.TranslationBlock
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
    private var layouts = emptyList<eu.kanade.translation.rendering.BlockLayout>()
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
        this.layouts = TextLayoutPlanner.plan(blocks, pageWidth.toFloat(), pageHeight.toFloat(), 1, false, measurer)
        invalidate()
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
        layouts.forEach { layout -> drawLayout(canvas, layout) }
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

    private fun drawLayout(canvas: Canvas, layout: eu.kanade.translation.rendering.BlockLayout) {
        val textColor = layout.block.textColor.toInt()
        val luma =
            ((textColor shr 16 and 0xFF) * 299 + (textColor shr 8 and 0xFF) * 587 + (textColor and 0xFF) * 114) / 1000
        fill.color = textColor
        fill.textSize = layout.fontSizePx
        stroke.color = if (luma < 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        stroke.strokeWidth = max(2f, layout.strokeWidth)
        stroke.textSize = layout.fontSizePx
        // T912 quality repair: positioned lines consume the planner's layout
        // model (cell containment + per-line placement). Every other layout
        // keeps the EXACT legacy rendering below.
        val positioned = layout.positionedLines
        if (positioned != null) {
            drawPositionedLayout(canvas, layout, positioned)
            return
        }
        val saved = layout.clipRect?.let { clip ->
            canvas.save().also { canvas.clipRect(RectF(clip.left, clip.top, clip.right, clip.bottom)) }
        }
        try {
            if (layout.isVertical) {
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
            if (saved != null) canvas.restoreToCount(saved)
        }
    }

    /**
     * T912 quality repair: draws the planner's positioned lines with the same
     * convention [PageTextRenderer]'s one-line StaticLayouts produce. Clips are
     * applied ONCE per layout — the cell rect first (structural bound; the
     * overlay has no component path), then the legacy clip rect — then each
     * line is translated to its integer placement and drawn stroke-then-fill
     * top-anchored at (leftPx, topPx), i.e. baseline = top - ascent, x = left,
     * LEFT-aligned. Per-frame allocation stays limited to the draw calls
     * themselves (no StaticLayout in the overlay).
     */
    private fun drawPositionedLayout(
        canvas: Canvas,
        layout: eu.kanade.translation.rendering.BlockLayout,
        lines: List<eu.kanade.translation.rendering.PositionedLine>,
    ) {
        val saved = canvas.save()
        try {
            layout.cellRect?.let { cell -> canvas.clipRect(cell.left, cell.top, cell.right, cell.bottom) }
            layout.clipRect?.let { clip -> canvas.clipRect(clip.left, clip.top, clip.right, clip.bottom) }
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
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private companion object {
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
