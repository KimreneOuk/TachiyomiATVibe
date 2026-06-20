package eu.kanade.translation.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
        textAlign = Paint.Align.CENTER
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * Draws the translated [blocks] onto [bitmap] and returns the bitmap that
     * actually holds the rendered pixels.
     *
     * TachiyomiAT: when [bitmap] is immutable (the default for BitmapFactory
     * output, which [decodePageBitmap] returns), a mutable copy is created to
     * draw on — Canvas(bitmap) otherwise throws "Immutable bitmap passed to
     * Canvas constructor". Callers MUST compress/save the RETURNED bitmap, not
     * the one they passed in, otherwise they save the un-drawn-on original.
     */
    fun render(bitmap: Bitmap, blocks: List<TranslationBlock>): Bitmap {
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
        for (block in blocks) {
            val text = block.translation.ifBlank { block.text }
            if (text.isBlank()) continue

            val (baseX, baseY, baseW, baseH, safeW, safeH) = computeRects(block)
            if (safeW < 1f || safeH < 1f) continue

            val isVertical = block.direction == "TTB" && text.any(::isCJK)
            val fontSizePx = binarySearchFontSize(
                text = text,
                safeW = safeW,
                safeH = safeH,
                containerW = baseW,
                isVertical = isVertical,
            )

            val strokeWidth = computeStrokeWidth(block, fontSizePx)
            val textColor = block.textColor.toInt()
            val strokeColor = block.strokeColor.toInt()

            fillPaint.color = textColor
            fillPaint.textSize = fontSizePx

            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidth
            strokePaint.textSize = fontSizePx

            val containerCX = baseX + baseW / 2f
            val containerCY = baseY + baseH / 2f

            if (isVertical) {
                drawVertical(canvas, text, fontSizePx, containerCX, containerCY, safeH)
            } else {
                drawHorizontal(canvas, text, fontSizePx, safeW, containerCX, containerCY)
            }
        }
        return target
    }

    private fun drawHorizontal(
        canvas: Canvas,
        text: String,
        fontSizePx: Float,
        safeW: Float,
        containerCX: Float,
        containerCY: Float,
    ) {
        val lines = cjkWrap(text, fontSizePx, safeW)
        if (lines.isEmpty()) return

        val fm = fillPaint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val totalHeight = lines.size * lineHeight

        var lineY = containerCY - totalHeight / 2f - fm.ascent

        for (line in lines) {
            if (line.isNotEmpty()) {
                canvas.drawText(line, containerCX, lineY, strokePaint)
                canvas.drawText(line, containerCX, lineY, fillPaint)
            }
            lineY += lineHeight
        }
    }

    private fun drawVertical(
        canvas: Canvas,
        text: String,
        fontSizePx: Float,
        containerCX: Float,
        containerCY: Float,
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

        val fm = fillPaint.fontMetrics

        val totalW = columns.size * colStep
        val colsRight = containerCX + totalW / 2f

        for ((colIdx, col) in columns.withIndex()) {
            val colCX = colsRight - colIdx * colStep - colStep / 2f
            val colH = col.length * charStep
            val colYStart = containerCY - colH / 2f

            for ((charIdx, ch) in col.withIndex()) {
                val charY = colYStart + charIdx * charStep
                val baselineY = charY - fm.ascent
                canvas.drawText(ch.toString(), colCX, baselineY, strokePaint)
                canvas.drawText(ch.toString(), colCX, baselineY, fillPaint)
            }
        }
    }

    private fun cjkWrap(text: String, fontSizePx: Float, maxWidthPx: Float): List<String> {
        val measurePaint = Paint().apply {
            typeface = boldTypeface
            isAntiAlias = true
            textSize = fontSizePx
        }

        val tokens = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                tokens.add("\n")
                i++
            } else if (ch.isWhitespace()) {
                tokens.add(" ")
                i++
            } else if (isCJK(ch)) {
                tokens.add(ch.toString())
                i++
            } else {
                val start = i
                while (i < text.length && !isCJK(text[i]) && !text[i].isWhitespace() && text[i] != '\n') {
                    i++
                }
                tokens.add(text.substring(start, i))
            }
        }

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (token in tokens) {
            if (token == "\n") {
                lines.add(current.toString())
                current = StringBuilder()
                continue
            }
            val candidate = current.toString() + token
            val width = measurePaint.measureText(candidate)
            if (width > maxWidthPx && current.isNotEmpty()) {
                lines.add(current.toString().trimEnd())
                current = StringBuilder(if (token == " ") "" else token)
            } else {
                current.append(token)
            }
        }
        if (current.isNotEmpty()) {
            lines.add(current.toString().trimEnd())
        }
        return if (lines.isEmpty()) listOf(text) else lines
    }

    private fun binarySearchFontSize(
        text: String,
        safeW: Float,
        safeH: Float,
        containerW: Float,
        isVertical: Boolean,
    ): Float {
        val startSize = max(containerW * 1.5f, 36f).toInt()
        var high = min(max(startSize, 36), 72)
        var low = 8
        var best = low.toFloat()

        val testPaint = Paint().apply {
            typeface = boldTypeface
            isAntiAlias = true
        }

        while (low <= high) {
            val mid = (low + high) / 2
            testPaint.textSize = mid.toFloat()

            if (isVertical) {
                val charStep = mid * 1.05f
                val colStep = mid * 1.25f
                val chars = text.replace("\r", "").replace("\n", "").replace(" ", "")
                val maxChars = max(1, (safeH / charStep).toInt())
                val numCols = (chars.length + maxChars - 1) / maxChars.coerceAtLeast(1)
                val totalW = numCols * colStep
                val maxColH = maxChars * charStep
                if (totalW <= safeW && maxColH <= safeH) {
                    best = mid.toFloat()
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            } else {
                val wrapped = cjkWrap(text, mid.toFloat(), safeW)
                val fm = testPaint.fontMetrics
                val lineHeight = fm.descent - fm.ascent
                val totalHeight = wrapped.size * lineHeight
                val maxLineWidth = wrapped.maxOfOrNull { testPaint.measureText(it) } ?: 0f
                if (totalHeight <= safeH && maxLineWidth <= safeW) {
                    best = mid.toFloat()
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
        }

        return best
    }

    private fun computeStrokeWidth(block: TranslationBlock, fontSizePx: Float): Float {
        if (block.strokeWidth > 0f) {
            val startSizeEstimate = max(block.width * 1.5f, 36f)
            val scaled = if (startSizeEstimate > 0f && fontSizePx < startSizeEstimate) {
                block.strokeWidth * (fontSizePx / startSizeEstimate)
            } else {
                block.strokeWidth
            }
            return max(1.0f, scaled)
        }
        return max(1.5f, fontSizePx * 0.07f)
    }

    companion object {
        private val VERTICAL_PUNCTUATION_MAP = mapOf(
            'ー' to '︱', '―' to '︱', '─' to '︱', '-' to '︱',
            '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄',
            '（' to '︵', '）' to '︶', '(' to '︵', ')' to '︶',
            '【' to '︻', '】' to '︼', '〔' to '︹', '〕' to '︺',
            '［' to '﹇', '］' to '﹈', '[' to '﹇', ']' to '﹈',
            '{' to '︷', '}' to '︸', '｛' to '︷', '｝' to '︸',
        )

        private fun isCJK(ch: Char): Boolean {
            val cp = ch.code
            return (cp in 0x4E00..0x9FFF) ||
                (cp in 0x3400..0x4DBF) ||
                (cp in 0x20000..0x2A6DF) ||
                (cp in 0x2A700..0x2B73F) ||
                (cp in 0x2B740..0x2B81F) ||
                (cp in 0xF900..0xFAFF) ||
                (cp in 0x2F800..0x2FA1F) ||
                (cp in 0x3000..0x303F) ||
                (cp in 0x3040..0x309F) ||
                (cp in 0x30A0..0x30FF) ||
                (cp in 0x31F0..0x31FF) ||
                (cp in 0xAC00..0xD7AF) ||
                (cp in 0xFF00..0xFFEF) ||
                (cp in 0xFE30..0xFE4F)
        }

        internal fun computeRects(block: TranslationBlock): RectResult {
            val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
            val textPad = if (hasParent) {
                max(12f, 0.15f * min(block.parentWidth, block.parentHeight))
            } else {
                max(4f, 0.03f * min(block.width, block.height))
            }
            var baseX = if (hasParent) block.parentX else block.x
            var baseY = if (hasParent) block.parentY else block.y
            var baseW = if (hasParent) block.parentWidth else block.width
            var baseH = if (hasParent) block.parentHeight else block.height
            if (!hasParent && baseH > 0f && baseW > 0f && baseH / baseW > 2.0f) {
                val area = baseW * baseH
                var newH = sqrt(area.toDouble()).toFloat()
                var newW = newH
                newW = newW.coerceIn(baseW * 1.5f, baseW * 3.5f)
                newH = area / newW
                baseX += (baseW - newW) / 2f
                baseY += (baseH - newH) / 2f
                baseW = newW
                baseH = newH
            }
            val safePad = min(textPad, min(baseW, baseH) / 3f)
            val safeW = max(1f, baseW - safePad * 2f)
            val safeH = max(1f, baseH - safePad * 2f)
            return RectResult(baseX, baseY, baseW, baseH, safeW, safeH)
        }
    }

    internal data class RectResult(
        val baseX: Float,
        val baseY: Float,
        val baseW: Float,
        val baseH: Float,
        val safeW: Float,
        val safeH: Float,
    )
}
