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
        val clipCache = ComponentClipCache<Path>(MAX_CACHED_COMPONENTS, MAX_CACHED_SPANS) { component ->
            componentPath(component)
        }
        prepared = layouts.mapNotNull { layout ->
            val geometry = layout.maskGeometry
            val componentClip: Path? = when {
                geometry == null -> null
                geometry.width != pageWidth || geometry.height != pageHeight -> return@mapNotNull null
                // Fail closed on incomplete metadata: the pair is required.
                layout.planGeometryId == null -> return@mapNotNull null
                layout.maskComponentId == null || layout.maskComponentId !in geometry.components.indices ->
                    return@mapNotNull null
                else -> clipCache.resolve(layout.planGeometryId!!, layout.maskComponentId!!, geometry)
                    ?: return@mapNotNull null
            }
            val shaped = buildShaped(layout) ?: return@mapNotNull null
            PreparedLayout(layout, componentClip, layout.cellRect, shaped)
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
                // Structural containment composes all three clips (intersection):
                // component path → cell rect → legacy collision clip. Unmasked
                // layouts have null path/cellRect, so their legacy clipRect
                // behaviour is byte-identical.
                preparedLayout.componentClip?.let(canvas::clipPath)
                preparedLayout.cellRect?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
                preparedLayout.layout.clipRect?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
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

    /**
     * Builds the shaped, reusable render data for one layout at bind time so
     * draw() allocates nothing. Null means the layout fails closed and is
     * dropped whole (renderer-side invalid render metadata): e.g. a positioned
     * line the shaper could not keep on exactly one StaticLayout line.
     */
    private fun buildShaped(layout: BlockLayout): Shaped? {
        configure(layout)
        val positioned = layout.positionedLines
        return when {
            // Slice 5: adaptive positioned lines (never vertical text).
            positioned != null -> preparePositioned(positioned)?.let { Shaped.Positioned(it) }
            layout.isVertical -> Shaped.Vertical(prepareVertical(layout))
            else -> Shaped.Horizontal(prepareHorizontal(layout))
        }
    }

    private fun drawLayout(canvas: Canvas, prepared: PreparedLayout) {
        configure(prepared.layout)
        when (val shaped = prepared.shaped) {
            is Shaped.Horizontal -> drawHorizontal(canvas, shaped.data)
            is Shaped.Vertical -> drawVertical(canvas, shaped.data)
            is Shaped.Positioned -> drawPositioned(canvas, shaped.lines)
        }
    }

    /**
     * Slice 5: prepare EXACTLY one StaticLayout pair (fill + stroke) per
     * positioned line, shaped at the planner's integer [PositionedLine.layoutWidthPx]
     * with simple breaking, no hyphenation, no padding, and maxLines(1). A line
     * that does not come back as exactly one line drops the WHOLE prepared
     * layout (fail closed — renderer-side invalid render metadata; the
     * planner-model reason text is a later slice). Empty lines (forced blank
     * lines in the source) reserve their stack slot without StaticLayouts.
     */
    private fun preparePositioned(lines: List<PositionedLine>): List<PositionedLineData>? {
        val prepared = ArrayList<PositionedLineData>(lines.size)
        for (line in lines) {
            if (line.layoutWidthPx < 1) return null
            val fillLayout = if (line.text.isEmpty()) null else buildSingleLineStatic(line.text, fill, line.layoutWidthPx) ?: return null
            val strokeLayout = if (line.text.isEmpty()) null else buildSingleLineStatic(line.text, stroke, line.layoutWidthPx) ?: return null
            prepared += PositionedLineData(fillLayout, strokeLayout, line.leftPx.toFloat(), line.topPx.toFloat())
        }
        return prepared
    }

    /** Builds a maxLines(1), left-aligned StaticLayout; null when it wraps to more than one line. */
    private fun buildSingleLineStatic(text: String, paint: TextPaint, widthPx: Int): StaticLayout? {
        val static = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setMaxLines(1)
            .build()
        return if (static.lineCount == 1) static else null
    }

    /**
     * Draws the prepared positioned lines. Clips (component → cell → legacy)
     * already applied ONCE per layout by [draw]; per line: save, translate to
     * the planner's integer placement, stroke then fill, restore. Allocation-
     * free: every StaticLayout was built in bind().
     */
    private fun drawPositioned(canvas: Canvas, lines: List<PositionedLineData>) {
        for (line in lines) {
            val fillLayout = line.fillLayout ?: continue
            val strokeLayout = line.strokeLayout ?: continue
            val save = canvas.save()
            canvas.translate(line.leftPx, line.topPx)
            strokeLayout.draw(canvas)
            fillLayout.draw(canvas)
            canvas.restoreToCount(save)
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

        /** Slice 5: adaptive pre-positioned lines, exactly one shaped line each. */
        class Positioned(val lines: List<PositionedLineData>) : Shaped()
    }

    /**
     * One prepared positioned line. The layouts are null only for a forced
     * blank line (no ink, stack slot reserved). Built entirely in bind().
     */
    private class PositionedLineData(
        val fillLayout: StaticLayout?,
        val strokeLayout: StaticLayout?,
        val leftPx: Float,
        val topPx: Float,
    )

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

    private data class PreparedLayout(
        val layout: BlockLayout,
        val componentClip: Path?,
        val cellRect: FloatRect?,
        val shaped: Shaped,
    )

    private companion object {
        private const val MAX_CACHED_COMPONENTS = 64
        private const val MAX_CACHED_SPANS = 100_000
    }
}

/**
 * TachiyomiAT: compact, JVM-pure clip-object cache keyed by the per-page
 * `(planGeometryId, componentId)` pair. No coordinate strings are built and
 * there is no `android.graphics` dependency in this class, so JVM tests can
 * drive it with a fake clip type.
 *
 * A hit only counts when the stored entry refers to the SAME geometry instance —
 * a packed key arriving with a different geometry fails closed. Capacity is
 * checked BEFORE [create], so a would-exceed lookup never allocates a clip.
 */
internal class ComponentClipCache<P : Any>(
    private val maxComponents: Int,
    private val maxSpans: Int,
    private val create: (MaskGeometry.Component) -> P,
) {
    private class Entry<C>(val geometry: MaskGeometry, val value: C, val spanCount: Int)

    private val entries = LinkedHashMap<Long, Entry<P>>()
    private var cachedComponents = 0
    private var cachedSpans = 0

    /** Distinct components currently cached. */
    internal val size: Int get() = cachedComponents

    fun resolve(planGeometryId: Int, componentId: Int, geometry: MaskGeometry): P? {
        // Fail closed on an out-of-range component id.
        if (componentId !in geometry.components.indices) return null
        val key = pack(planGeometryId, componentId)
        val cached = entries[key]
        if (cached != null) {
            // Instance identity verification: the pair is only meaningful within
            // the geometry instance that produced it.
            return if (cached.geometry === geometry) cached.value else null
        }
        val component = geometry.components[componentId]
        if (cachedComponents + 1 > maxComponents) return null
        if (cachedSpans + component.spans.size > maxSpans) return null
        val value = create(component)
        entries[key] = Entry(geometry, value, component.spans.size)
        cachedComponents++
        cachedSpans += component.spans.size
        return value
    }

    fun clear() {
        entries.clear()
        cachedComponents = 0
        cachedSpans = 0
    }

    internal companion object {
        internal fun pack(planGeometryId: Int, componentId: Int): Long =
            (planGeometryId.toLong() shl 32) or (componentId.toLong() and 0xFFFF_FFFFL)
    }
}
