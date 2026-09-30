package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.TranslationBlockView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class RectResult(
    val baseX: Float,
    val baseY: Float,
    val baseW: Float,
    val baseH: Float,
    val safeW: Float,
    val safeH: Float,
    val reshaped: Boolean,
    val origLeft: Float,
    val origRight: Float,
)

/**
 * Pure font-fit, rectangle, direction, and wrapping kernels used by the
 * [TextLayoutPlanner] facade. The methods are intentionally kept byte-for-byte
 * equivalent to the former planner implementations; the facade delegates to
 * this object so existing rendering seams remain unchanged.
 */
internal object FontFittingAlgorithms {
    private const val RESHAPE_TALL_RATIO = 2.0f
    private const val RESHAPE_MIN_WIDTH_FACTOR = 1.5f
    private const val RESHAPE_MAX_WIDTH_FACTOR = 3.5f
    private const val VERTICAL_CHAR_STEP = 1.05f
    private const val VERTICAL_COL_STEP = 1.25f
    private const val FIT_MAX_FONT_PX = 72f
    private const val FIT_MIN_FONT_PX = 8f
    private const val FIT_START_WIDTH_FACTOR = 1.5f
    private const val STROKE_WIDTH_FRACTION = 0.12f
    private const val MIN_STROKE_PX = 2f

    internal fun isCJK(ch: Char): Boolean {
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

    internal fun cjkRatio(text: String): Float {
        val total = text.count { !it.isWhitespace() }
        if (total == 0) return 0f
        val cjk = text.count { !it.isWhitespace() && isCJK(it) }
        return cjk.toFloat() / total.toFloat()
    }

    internal fun shouldRenderVertical(text: String): Boolean = cjkRatio(text) > 0.5f

    internal fun computeRects(
        block: TranslationBlockView,
        sampleSize: Int = 1,
        regionOverride: FloatRect? = null,
    ): RectResult {
        val scale = 1f / sampleSize
        val hasParent = regionOverride == null && block.parentWidth > 0f && block.parentHeight > 0f
        val textPad = if (hasParent) {
            max(12f * scale, 0.15f * min(block.parentWidth, block.parentHeight))
        } else {
            max(4f * scale, 0.03f * min(block.width, block.height))
        }
        var baseX = when {
            regionOverride != null -> regionOverride.left
            hasParent -> block.parentX
            else -> block.x
        }
        var baseY = when {
            regionOverride != null -> regionOverride.top
            hasParent -> block.parentY
            else -> block.y
        }
        var baseW = when {
            regionOverride != null -> regionOverride.width()
            hasParent -> block.parentWidth
            else -> block.width
        }
        var baseH = when {
            regionOverride != null -> regionOverride.height()
            hasParent -> block.parentHeight
            else -> block.height
        }
        val origLeft = baseX
        val origRight = baseX + baseW
        var reshaped = false
        if (regionOverride == null && !hasParent && baseH > 0f && baseW > 0f && baseH / baseW > RESHAPE_TALL_RATIO) {
            val area = baseW * baseH
            var newH = sqrt(area.toDouble()).toFloat()
            var newW = newH
            newW = newW.coerceIn(baseW * RESHAPE_MIN_WIDTH_FACTOR, baseW * RESHAPE_MAX_WIDTH_FACTOR)
            newH = area / newW
            // Symmetric re-centre on the original bubble centre (legacy behaviour);
            // placeBlock re-anchors away from a neighbour when one is present.
            baseX = (origLeft + origRight) / 2f - newW / 2f
            baseY += (baseH - newH) / 2f
            baseW = newW
            baseH = newH
            reshaped = true
        }
        val safePad = min(textPad, min(baseW, baseH) / 3f)
        val safeW = max(1f, baseW - safePad * 2f)
        val safeH = max(1f, baseH - safePad * 2f)
        return RectResult(baseX, baseY, baseW, baseH, safeW, safeH, reshaped, origLeft, origRight)
    }

    internal fun cjkWrap(text: String, fontSizePx: Float, maxWidthPx: Float, measurer: TextMeasurer): List<String> {
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
                    if (text[i] == '-' && i > start) {
                        i++
                        break
                    }
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
            val width = measurer.measureTextWidth(candidate, fontSizePx)
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

    internal fun binarySearchFontSize(
        text: String,
        safeW: Float,
        safeH: Float,
        containerW: Float,
        isVertical: Boolean,
        scale: Float,
        measurer: TextMeasurer,
    ): Float {
        val startSize = max(max(containerW, safeH * 0.35f) * FIT_START_WIDTH_FACTOR, FIT_MIN_FONT_PX * scale * 4.5f).toInt()
        var high = min(max(startSize, (FIT_MIN_FONT_PX * scale * 4.5f).toInt()), (FIT_MAX_FONT_PX * scale).toInt())
        var low = max(2, (FIT_MIN_FONT_PX * scale).toInt())
        var best = low.toFloat()
        while (low <= high) {
            val mid = (low + high) / 2
            val midF = mid.toFloat()
            if (isVertical) {
                val charStep = midF * VERTICAL_CHAR_STEP
                val colStep = midF * VERTICAL_COL_STEP
                val chars = strippedLength(text)
                val maxChars = max(1, (safeH / charStep).toInt())
                val numCols = ceil(chars.toFloat() / maxChars).toInt().coerceAtLeast(1)
                val totalW = numCols * colStep
                val maxColH = maxChars * charStep
                if (totalW <= safeW && maxColH <= safeH) {
                    best = midF
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            } else {
                val wrapped = cjkWrap(text, midF, safeW, measurer)
                val lineH = measurer.lineHeight(midF)
                val totalHeight = wrapped.size * lineH
                val maxLineWidth = wrapped.maxOfOrNull { measurer.measureTextWidth(it, midF) } ?: 0f
                if (totalHeight <= safeH && maxLineWidth <= safeW) {
                    best = midF
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
        }
        return best
    }

    internal fun computeStrokeWidth(fontSizePx: Float, scale: Float): Float =
        max(MIN_STROKE_PX * scale, fontSizePx * STROKE_WIDTH_FRACTION)

    internal fun graphemeClusters(text: String): List<String> {
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val clusters = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != java.text.BreakIterator.DONE) {
            clusters.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return clusters
    }

    internal fun verticalGlyph(cluster: String): String = when (cluster) {
        "（" -> "︵"
        "）" -> "︶"
        "[" -> "︵"
        "]" -> "︶"
        "{" -> "︵"
        "}" -> "︶"
        "【" -> "︻"
        "】" -> "︼"
        "《" -> "︽"
        "》" -> "︾"
        "「" -> "﹁"
        "」" -> "﹂"
        "『" -> "﹃"
        "』" -> "﹄"
        "ー" -> "丨"
        "-" -> "丨"
        "…" -> "︙"
        "‥" -> "︰"
        "、" -> "︑"
        "。" -> "︒"
        "," -> "︑"
        "." -> "︒"
        "?" -> "？"
        "!" -> "！"
        else -> cluster
    }

    internal fun verticalOrientation(cluster: String): VerticalOrientation =
        if (cluster.length == 1 && cluster[0].isLetterOrDigit() && cluster[0].code in 0x0020..0x007E) {
            VerticalOrientation.ROTATED
        } else {
            VerticalOrientation.UPRIGHT
        }

    private fun strippedLength(text: String): Int =
        text.count { it != '\r' && it != '\n' && it != ' ' }
}
