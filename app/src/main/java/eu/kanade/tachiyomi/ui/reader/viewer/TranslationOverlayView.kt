package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.rendering.BlockLayout
import eu.kanade.translation.rendering.ComponentClipCache
import eu.kanade.translation.rendering.DrawPlanFingerprint
import eu.kanade.translation.rendering.PersistedLayoutReaderBridge
import eu.kanade.translation.rendering.PersistedLayoutRuntime
import eu.kanade.translation.rendering.ReaderTextLayoutCache
import eu.kanade.translation.rendering.TextAlign
import eu.kanade.translation.rendering.TextLayoutBindResult
import eu.kanade.translation.rendering.TextLayoutCoordinator
import eu.kanade.translation.rendering.TextLayoutPlanner
import eu.kanade.translation.rendering.TextMeasurer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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

    //  3.1: measurement state for the BACKGROUND planner. Uses its own Paint
    // (a copy of [fill], so font and flags — and therefore every measurement —
    // are identical to the inline path) because `fill` is mutated by the Main
    // draw path and must never race with planning. Confined to
    // [Companion.planningExecutor], which is single-threaded for exactly this
    // reason: `Paint.textSize` mutation is not safe for concurrent use.
    private val planningMeasurer = object : TextMeasurer {
        private val planningPaint = Paint(fill)
        override fun measureTextWidth(text: String, fontSizePx: Float): Float {
            planningPaint.textSize = fontSizePx
            return planningPaint.measureText(text)
        }
        override fun lineHeight(fontSizePx: Float): Float {
            planningPaint.textSize = fontSizePx
            val metrics = planningPaint.fontMetrics
            return metrics.descent - metrics.ascent
        }
    }

    //  WP9 (wave-2 review gap 6): production font digest — read the exact
    // bundled font bytes ONCE at this Android entry point and pin their
    // SHA-256 process-wide, so the Batch-side LAYOUT_PREPARE publisher and
    // every reader-side hydration verify the same font identity
    // (PersistedLayoutRuntime.productionFontSha256). Installing the loader is
    // idempotent; the digest itself is computed lazily once and cached.
    init {
        if (!PersistedLayoutRuntime.fontSourceInstalled) {
            PersistedLayoutRuntime.fontSha256Loader = {
                DrawPlanFingerprint.fontAssetSha256(
                    context.resources.openRawResource(R.font.animeace).use { it.readBytes() },
                )
            }
            PersistedLayoutRuntime.fontSourceInstalled = true
        }
    }

    //  3.1: bind identity + background planning. bind() never runs the
    // planner synchronously on the calling thread; identical rebinds stay the
    // cheap early-return and cache hits apply prepared layouts synchronously with zero planner
    // work (see [TextLayoutCoordinator]).
    private val layoutCoordinator = TextLayoutCoordinator(
        cache = sharedLayoutCache,
        backgroundExecutor = planningExecutor,
        mainExecutor = mainExecutor,
        plan = { blocks, width, height ->
            val layouts = TextLayoutPlanner.plan(blocks, width.toFloat(), height.toFloat(), 1, false, planningMeasurer)
            buildPreparedLayouts(layouts, width, height)
        },
        onPrepared = ::applyPreparedLayouts,
        //  WP9: consult the persisted-layout hydration
        // source BEFORE the async planner. Null (feature flag off, no source
        // installed, missing/corrupt/incompatible/lossy plan) falls back to
        // the planner above — the fallback is mandatory.
        // Stage 7: the pageKeyed chapter source is consulted first; a
        // null page key (legacy bind path,  OFF, no installation) keeps
        // the byte-identical legacy behavior.
        hydrate = { blocks, width, height ->
            val hydrated = PersistedLayoutReaderBridge.hydrate(boundPageKey, blocks, width, height)
                ?: PersistedLayoutReaderBridge.hydrate(blocks, width, height)
            hydrated?.let { buildPreparedLayouts(it, width, height) }
        },
    )

    private fun applyPreparedLayouts(prepared: List<PreparedOverlayLayout>) {
        preparedLayouts = prepared
        invalidate()
    }

    private var imageView: SubsamplingScaleImageView? = null
    private var blocks: List<TranslationBlock> = emptyList()
    private var pageWidth = 0
    private var pageHeight = 0

    //  Stage 7: the translation page key of the current binding, set
    // by the pageKeyed [bind] overload. The background hydrate lambda reads it
    // to resolve the chapter's persisted plan. Null (legacy 4-arg bind path)
    // keeps the byte-identical planner-only behavior.
    @Volatile
    private var boundPageKey: String? = null
    private var preparedLayouts = emptyList<PreparedOverlayLayout>()
    private var framePending = false
    private val frameCallback = Choreographer.FrameCallback {
        framePending = false
        invalidate()
    }

    /**
     *  3.1: never runs the layout planner on the calling (Main) thread.
     * Identical rebinds are the same cheap early-return as before; a bounded
     * cache hit applies prepared layouts synchronously; a miss schedules
     * planning on a background thread and applies the result on Main when it
     * arrives (dropped if this view was re-bound/recycled/detached meanwhile).
     * Until then the view draws the safe empty case, exactly as it does for
     * pages without translations — stale layouts for DIFFERENT content are
     * always dropped immediately so foreign text can never appear over a page
     * while its own layout is in flight.
     */
    fun bind(imageView: SubsamplingScaleImageView?, blocks: List<TranslationBlock>, pageWidth: Int, pageHeight: Int) {
        bind(imageView, blocks, pageWidth, pageHeight, pageKey = null)
    }

    /**
     *  Stage 7: pageKeyed bind — enables the persisted-layout
     * hydration consult for this binding ([PersistedLayoutReaderBridge]
     * chapter source,  at the install site). A null [pageKey]
     * behaves exactly like the legacy bind.
     */
    fun bind(
        imageView: SubsamplingScaleImageView?,
        blocks: List<TranslationBlock>,
        pageWidth: Int,
        pageHeight: Int,
        pageKey: String?,
    ) {
        val imageViewChanged = this.imageView !== imageView
        this.imageView = imageView
        this.blocks = blocks
        this.pageWidth = pageWidth
        this.pageHeight = pageHeight
        this.boundPageKey = pageKey?.takeIf { it.isNotEmpty() && blocks.isNotEmpty() }
        when (val result = layoutCoordinator.bind(blocks, pageWidth, pageHeight)) {
            is TextLayoutBindResult.Ready -> {
                preparedLayouts = result.prepared
                invalidate()
            }
            TextLayoutBindResult.Cleared, TextLayoutBindResult.Planning -> {
                preparedLayouts = emptyList()
                invalidate()
            }
            TextLayoutBindResult.Unchanged -> {
                // Same content and dimensions as the current binding: layouts
                // shown are already correct (cheap no-op, as before). Only a
                // new transform source needs a redraw.
                if (imageViewChanged) invalidate()
            }
        }
    }

    /**
     * Build bounded component paths at bind time. Missing/invalid component
     * metadata degrades to the available cell/legacy clips and still draws,
     * preserving the planner's never-drop contract. Runs on the background
     * planning thread during async binds — `Path` construction is not
     * looper-bound, and the returned paths are only READ by the Main-thread
     * draw path.
     */
    private fun buildPreparedLayouts(layouts: List<BlockLayout>, pageWidth: Int, pageHeight: Int): List<PreparedOverlayLayout> {
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
        return layouts.map { layout ->
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
        //  3.1: a detached view must never receive a background planning
        // result; bumping the generation drops any in-flight delivery.
        layoutCoordinator.cancelPending()
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
     *  quality repair: draws the planner's positioned lines with the same
     * direct-draw convention: each planned line is placed independently. Clips are
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
        preparedLayouts = buildPreparedLayouts(layouts, pageWidth, pageHeight)
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

        /**
         *  3.1: strict global bound on cached prepared page layouts. Covers
         * the reader's warm window (attach 2 / evict 5 pager, attach 4 / evict
         * 10 webtoon) plus a little scroll-back, shared across all overlay
         * instances so total memory stays bounded regardless of holder count.
         * Entries hold immutable layouts + paths (~tens of KB per page), so the
         * ceiling is well under a megabyte.
         */
        private const val MAX_CACHED_PAGE_LAYOUTS = 12

        /**
         * Prepared layouts are only read on Main and only stored from the
         * Main-thread delivery callback, so the shared cache needs no locks.
         */
        private val sharedLayoutCache = ReaderTextLayoutCache<List<PreparedOverlayLayout>>(MAX_CACHED_PAGE_LAYOUTS)

        /**
         * Single-threaded ON PURPOSE: [planningMeasurer]-style measurement uses
         * one `Paint` whose `textSize` is mutated per call, which is not safe
         * for concurrent use. Confining all planning to this thread keeps the
         * planner (itself stateless and reentrant) deterministic. Below-normal
         * priority so planning never competes with input/rendering threads.
         */
        private val planningExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "TranslationOverlayLayoutPlanner").apply {
                    isDaemon = true
                    priority = (Thread.NORM_PRIORITY + Thread.MIN_PRIORITY) / 2
                }
            }
        }

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }

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
