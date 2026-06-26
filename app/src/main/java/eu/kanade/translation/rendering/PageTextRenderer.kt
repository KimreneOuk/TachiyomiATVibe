package eu.kanade.translation.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.max

class PageTextRenderer(context: Context) {

    private val boldTypeface: Typeface = ResourcesCompat.getFont(context, R.font.animeace)?.let {
        Typeface.create(it, Typeface.BOLD)
    } ?: Typeface.DEFAULT_BOLD

    private val fillPaint: Paint = Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
        style = Paint.Style.FILL
        typeface = boldTypeface
        textAlign = Paint.Align.CENTER
    }

    private val strokePaint: Paint = Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
        style = Paint.Style.STROKE
        typeface = boldTypeface
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * TachiyomiAT: [TextMeasurer] backed by a real [android.graphics.Paint]. Kept
     * allocation-light — [measurePaint] is reused across all measurements in one
     * render pass (the legacy `cjkWrap` allocated a throwaway Paint per wrap; this
     * hoists one out instead). Created ONCE per renderer and reused by the planner
     * and the draw helpers.
     */
    private inner class PaintTextMeasurer : TextMeasurer {
        private val measurePaint = Paint().apply {
            typeface = boldTypeface
            isAntiAlias = true
        }

        override fun measureTextWidth(text: String, fontSizePx: Float): Float {
            measurePaint.textSize = fontSizePx
            return measurePaint.measureText(text)
        }

        override fun lineHeight(fontSizePx: Float): Float {
            measurePaint.textSize = fontSizePx
            val fm = measurePaint.fontMetrics
            return fm.descent - fm.ascent
        }
    }

    private val measurer = PaintTextMeasurer()

    private val VERTICAL_PUNCTUATION_MAP = mapOf(
        'ー' to '︱', '―' to '︱', '─' to '︱', '-' to '︱',
        '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄',
        '（' to '︵', '）' to '︶', '(' to '︵', ')' to '︶',
        '【' to '︻', '】' to '︼', '〔' to '︹', '〕' to '︺',
        '［' to '﹇', '］' to '﹈', '[' to '﹇', ']' to '﹈',
        '{' to '︷', '}' to '︸', '｛' to '︷', '｝' to '︸',
    )

    /**
     * Draws the translated [blocks] onto [bitmap] and returns the bitmap that
     * actually holds the rendered pixels.
     *
     * TachiyomiAT: when [bitmap] is immutable (the default for BitmapFactory
     * output, which [decodePageBitmap] returns), a mutable copy is created to
     * draw on — Canvas(bitmap) otherwise throws "Immutable bitmap passed to
     * Canvas constructor". Callers MUST compress/save the RETURNED bitmap, not
     * the one they passed in, otherwise they save the un-drawn-on original.
     *
     * TachiyomiAT: by default only a block's *translation* is drawn. A block
     * whose translation is blank (the model returned nothing for it) now renders
     * as nothing — it does NOT fall back to `block.text`. That old fallback is
     * what made a page with some real translations + some untranslated blocks
     * render the original OCR text in place of the missing translations while
     * still counting as READY, i.e. exactly the "mixed source + translated text"
     * symptom. Pass [renderSourceText] = true ONLY for an explicit draft/debug
     * mode that wants to overlay the source on top of the cleaned image; the
     * translate path never does.
     *
     * Layout is delegated to [TextLayoutPlanner] (pure, neighbour-aware); this
     * method is now only responsible for DRAWING the resolved [BlockLayout] list
     * onto the canvas. See the neighbour-aware layout contract in
     * `docs/TRANSLATION_MODULE.md`.
     */
    fun render(
        bitmap: Bitmap,
        blocks: List<TranslationBlock>,
        sampleSize: Int = 1,
        renderSourceText: Boolean = false,
    ): Bitmap {
        // TachiyomiAT: Canvas(bitmap) throws "Immutable bitmap passed to Canvas
        // constructor" if the bitmap isn't mutable. Bitmaps returned by
        // BitmapFactory.decodeStream are immutable by default (the caller,
        // decodePageBitmap, doesn't set inMutable=true), so the render stage
        // crashed here and left the page showing the ORIGINAL untranslated image.
        // Copy to a mutable bitmap only when necessary — when the caller already
        // supplies a mutable bitmap (e.g. a cleaned/inpainted working copy) the
        // copy is skipped to avoid the extra allocation.
        val target = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(target)

        val layouts = TextLayoutPlanner.plan(
            blocks = blocks,
            pageWidth = bitmap.width.toFloat(),
            pageHeight = bitmap.height.toFloat(),
            sampleSize = sampleSize,
            renderSourceText = renderSourceText,
            measurer = measurer,
        )

        for (layout in layouts) {
            val textColor = layout.block.textColor.toInt()
            var strokeColor = layout.block.strokeColor.toInt()
            var strokeWidth = layout.strokeWidth

            // TachiyomiAT: hard outline invariant — every block MUST have a
            // contrasting outline regardless of persisted/merged values.
            val tr = (textColor shr 16 and 0xFF)
            val tg = (textColor shr 8 and 0xFF)
            val tb = (textColor and 0xFF)
            val luma = (tr * 299 + tg * 587 + tb * 114) / 1000
            val correctStroke = if (luma < 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            if (strokeColor != correctStroke) strokeColor = correctStroke
            if (strokeWidth <= 0f) strokeWidth = max(2f, layout.fontSizePx * 0.06f)

            fillPaint.color = textColor
            fillPaint.textSize = layout.fontSizePx
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidth
            strokePaint.textSize = layout.fontSizePx

            // Clip only when the planner could not place this block without an
            // overlap — the structural no-overlap guarantee. save()/restore() are
            // scoped so the clip never leaks to sibling blocks.
            val saved = if (layout.clipRect != null) {
                val sc = canvas.save()
                canvas.clipRect(layout.clipRect.toRectF())
                sc
            } else {
                -1
            }
            try {
                if (layout.isVertical) {
                    drawVertical(
                        canvas,
                        layout.text,
                        layout.fontSizePx,
                        layout.originX,
                        layout.originY,
                        layout.safeH,
                    )
                } else {
                    drawHorizontal(
                        canvas,
                        layout.text,
                        layout.fontSizePx,
                        layout.safeW,
                        layout.originX,
                        layout.originY,
                        layout.drawAlignLeft,
                    )
                }
            } finally {
                if (layout.clipRect != null) canvas.restoreToCount(saved)
            }
        }
        return target
    }

    private fun FloatRect.toRectF(): RectF = RectF(left, top, right, bottom)

    private fun drawHorizontal(
        canvas: Canvas,
        text: String,
        fontSizePx: Float,
        safeW: Float,
        originX: Float,
        originY: Float,
        drawAlignLeft: Boolean,
    ) {
        val lines = TextLayoutPlanner.cjkWrap(text, fontSizePx, safeW, measurer)
        if (lines.isEmpty()) return

        val fm = fillPaint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val totalHeight = lines.size * lineHeight

        var lineY = originY - totalHeight / 2f - fm.ascent

        // drawAlignLeft anchors each line at the clip's left edge; otherwise lines
        // are centred on originX (the box centre). Paint.textAlign handles both.
        fillPaint.textAlign = if (drawAlignLeft) Paint.Align.LEFT else Paint.Align.CENTER
        strokePaint.textAlign = fillPaint.textAlign

        for (line in lines) {
            if (line.isNotEmpty()) {
                canvas.drawText(line, originX, lineY, strokePaint)
                canvas.drawText(line, originX, lineY, fillPaint)
            }
            lineY += lineHeight
        }
    }

    private fun drawVertical(
        canvas: Canvas,
        text: String,
        fontSizePx: Float,
        originX: Float,
        originY: Float,
        safeH: Float,
    ) {
        val charStep = fontSizePx * 1.05f
        val colStep = fontSizePx * 1.25f
        val chars = text.replace("\r", "").replace("\n", "").replace(" ", "")
        if (chars.isEmpty()) return

        val maxCharsPerCol = max(1, (safeH / charStep).toInt())
        val columns = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in chars) {
            val mapped = VERTICAL_PUNCTUATION_MAP[ch] ?: ch
            if (current.length >= maxCharsPerCol) {
                columns.add(current.toString())
                current = StringBuilder()
            }
            current.append(mapped)
        }
        if (current.isNotEmpty()) columns.add(current.toString())
        if (columns.isEmpty()) return

        fillPaint.textAlign = Paint.Align.CENTER
        strokePaint.textAlign = Paint.Align.CENTER
        val fm = fillPaint.fontMetrics

        val totalW = columns.size * colStep
        val colsRight = originX + totalW / 2f

        for ((colIdx, col) in columns.withIndex()) {
            val colCX = colsRight - colIdx * colStep - colStep / 2f
            val colH = col.length * charStep
            val colYStart = originY - colH / 2f

            for ((charIdx, ch) in col.withIndex()) {
                val charY = colYStart + charIdx * charStep
                val baselineY = charY - fm.ascent
                canvas.drawText(ch.toString(), colCX, baselineY, strokePaint)
                canvas.drawText(ch.toString(), colCX, baselineY, fillPaint)
            }
        }
    }

    companion object {
        /**
         * Fraction of non-whitespace characters that are CJK. Delegated to
         * [TextLayoutPlanner.cjkRatio] so the planner and the renderer share one
         * source of truth; retained here for the existing
         * `PageTextRendererDirectionTest`.
         */
        @JvmStatic
        internal fun cjkRatio(text: String): Float = TextLayoutPlanner.cjkRatio(text)

        /**
         * Vertical layout only when CJK chars make up the MAJORITY (>50%) of the
         * non-whitespace text. Delegated to [TextLayoutPlanner.shouldRenderVertical].
         */
        @JvmStatic
        internal fun shouldRenderVertical(text: String): Boolean =
            TextLayoutPlanner.shouldRenderVertical(text)
    }
}
