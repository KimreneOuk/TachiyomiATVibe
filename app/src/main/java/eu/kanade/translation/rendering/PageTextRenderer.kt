package eu.kanade.translation.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.translation.segmentation.MaskGeometry
import kotlin.math.max

/** Android-only drawing boundary. Planning and source/view transforms remain outside this class. */
internal class PageTextRenderer(typeface: Typeface) {
    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { this.typeface = typeface }
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val measurer = object : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float {
            fill.textSize = fontSizePx
            return fill.measureText(text)
        }
        override fun lineHeight(fontSizePx: Float): Float {
            fill.textSize = fontSizePx
            return fill.fontMetrics.let { it.descent - it.ascent }
        }
    }

    private var pageWidth = 0
    private var pageHeight = 0
    private var prepared = emptyList<PreparedLayout>()

    fun bind(layouts: List<BlockLayout>, pageWidth: Int, pageHeight: Int) {
        clear()
        if (pageWidth <= 0 || pageHeight <= 0) return
        this.pageWidth = pageWidth
        this.pageHeight = pageHeight
        val clipCache = LinkedHashMap<String, Path>()
        var cachedSpans = 0
        prepared = layouts.mapNotNull { layout ->
            val geometry = layout.maskGeometry
            val componentPath: Path? = when {
                geometry == null -> null
                geometry.width != pageWidth || geometry.height != pageHeight -> return@mapNotNull null
                layout.maskComponentId == null || layout.maskComponentId !in geometry.components.indices -> return@mapNotNull null
                else -> {
                    val component = geometry.components[layout.maskComponentId!!]
                    val cacheKey = "${geometry.width}x${geometry.height}:${component.stableKey}"
                    clipCache[cacheKey] ?: run {
                        if (clipCache.size >= MAX_CACHED_COMPONENTS ||
                            cachedSpans + component.spans.size > MAX_CACHED_SPANS
                        ) {
                            return@mapNotNull null
                        }
                        componentPath(component).also {
                            clipCache[cacheKey] = it
                            cachedSpans += component.spans.size
                        }
                    }
                }
            }
            PreparedLayout(layout, componentPath, buildShaped(layout))
        }
    }

    fun clear() {
        prepared = emptyList()
        pageWidth = 0
        pageHeight = 0
    }

    fun draw(canvas: Canvas) {
        if (pageWidth <= 0 || pageHeight <= 0) return
        prepared.forEach { preparedLayout ->
            val save = canvas.save()
            try {
                preparedLayout.clip?.let(canvas::clipPath)
                val clip = preparedLayout.layout.clipRect
                if (clip != null) canvas.clipRect(clip.left, clip.top, clip.right, clip.bottom)
                drawLayout(canvas, preparedLayout)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    private fun componentPath(component: MaskGeometry.Component): Path = Path().apply {
        fillType = Path.FillType.WINDING
        component.spans.forEach { span ->
            addRect(
                span.start.toFloat(),
                span.y.toFloat(),
                span.endExclusive.toFloat(),
                span.y + 1f,
                Path.Direction.CW,
            )
        }
    }

    private fun configure(layout: BlockLayout) {
        val textColor = layout.block.textColor.toInt()
        val luma =
            ((textColor shr 16 and 0xFF) * 299 + (textColor shr 8 and 0xFF) * 587 + (textColor and 0xFF) * 114) / 1000
        fill.color = textColor
        fill.textSize = layout.fontSizePx
        stroke.color = if (luma < 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        stroke.strokeWidth = max(2f, layout.strokeWidth)
        stroke.textSize = layout.fontSizePx
    }

    /** Builds the shaped, reusable render data for one layout at bind time so draw() allocates nothing. */
    private fun buildShaped(layout: BlockLayout): Shaped {
        configure(layout)
        return if (layout.isVertical) {
            Shaped.Vertical(
                prepareVertical(layout),
            )
        } else {
            Shaped.Horizontal(prepareHorizontal(layout))
        }
    }

    private fun drawLayout(canvas: Canvas, prepared: PreparedLayout) {
        configure(prepared.layout)
        when (val shaped = prepared.shaped) {
            is Shaped.Horizontal -> drawHorizontal(canvas, shaped.data)
            is Shaped.Vertical -> drawVertical(canvas, shaped.data)
        }
    }

    private fun prepareHorizontal(layout: BlockLayout): HorizontalData {
        val text = layout.lines.joinToString("\n")
        val widestLine = layout.lines.maxOfOrNull { fill.measureText(it) } ?: 0f
        val width = max(1, kotlin.math.ceil(max(layout.safeW, widestLine).toDouble()).toInt())
        if (text.isEmpty()) return HorizontalData(null, null, layout, width)
        val alignment = alignmentFor(layout.drawAlign)
        val fillLayout = buildStatic(text, fill, width, alignment)
        val strokeLayout = buildStatic(text, stroke, width, alignment)
        return HorizontalData(fillLayout, strokeLayout, layout, width)
    }

    private fun drawHorizontal(canvas: Canvas, data: HorizontalData) {
        val fillLayout = data.fillLayout ?: return
        val strokeLayout = data.strokeLayout ?: return
        val layout = data.layout
        val left = when (layout.drawAlign) {
            TextAlign.LEFT -> layout.originX
            TextAlign.RIGHT -> layout.originX - data.width
            TextAlign.CENTER -> layout.originX - data.width / 2f
        }
        val top = layout.originY - fillLayout.height / 2f
        val save = canvas.save()
        canvas.translate(left, top)
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun prepareVertical(layout: BlockLayout): VerticalData {
        val clusters = TextLayoutPlanner.graphemeClusters(layout.text).filterNot {
            it == "\r" ||
                it == "\n" ||
                it.all(Char::isWhitespace)
        }
        val charStep = layout.fontSizePx * 1.05f
        val colStep = layout.fontSizePx * 1.25f
        val perColumn = max(1, (layout.safeH / charStep).toInt())
        val columns = if (clusters.isEmpty()) 0 else (clusters.size + perColumn - 1) / perColumn
        val rendered = clusters.map { TextLayoutPlanner.verticalGlyph(it) }
        val orientations = rendered.map { TextLayoutPlanner.verticalOrientation(it) }
        fill.textAlign = Paint.Align.CENTER
        stroke.textAlign = Paint.Align.CENTER
        val metrics = fill.fontMetrics
        return VerticalData(layout, rendered, orientations, charStep, colStep, perColumn, columns, metrics.ascent)
    }

    private fun drawVertical(canvas: Canvas, data: VerticalData) {
        if (data.clusters.isEmpty()) return
        val layout = data.layout
        val right = layout.originX + data.columns * data.colStep / 2f
        for (column in 0 until data.columns) {
            val start = column * data.perColumn
            val end = minOf(data.clusters.size, start + data.perColumn)
            val x = right - column * data.colStep - data.colStep / 2f
            var y = layout.originY - (end - start) * data.charStep / 2f
            for (index in start until end) {
                val cluster = data.clusters[index]
                val baseline = y - data.ascent
                if (data.orientations[index] == VerticalOrientation.ROTATED) {
                    val save = canvas.save()
                    canvas.rotate(90f, x, baseline)
                    canvas.drawText(cluster, x, baseline, stroke)
                    canvas.drawText(cluster, x, baseline, fill)
                    canvas.restoreToCount(save)
                } else {
                    canvas.drawText(cluster, x, baseline, stroke)
                    canvas.drawText(cluster, x, baseline, fill)
                }
                y += data.charStep
            }
        }
    }

    private fun alignmentFor(align: TextAlign): Layout.Alignment = when (align) {
        TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
        TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
    }

    private fun buildStatic(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    private sealed class Shaped {
        class Horizontal(val data: HorizontalData) : Shaped()
        class Vertical(val data: VerticalData) : Shaped()
    }

    private data class HorizontalData(
        val fillLayout: StaticLayout?,
        val strokeLayout: StaticLayout?,
        val layout: BlockLayout,
        val width: Int,
    )

    private data class VerticalData(
        val layout: BlockLayout,
        val clusters: List<String>,
        val orientations: List<VerticalOrientation>,
        val charStep: Float,
        val colStep: Float,
        val perColumn: Int,
        val columns: Int,
        val ascent: Float,
    )

    private data class PreparedLayout(val layout: BlockLayout, val clip: Path?, val shaped: Shaped)

    private companion object {
        private const val MAX_CACHED_COMPONENTS = 64
        private const val MAX_CACHED_SPANS = 100_000
    }
}
