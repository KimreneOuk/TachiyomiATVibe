package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskGeometry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SCRATCH laptop mock rig — NOT committed, NOT a regression test.
 *
 * Runs the real production planner ([TextLayoutPlanner.planPage]) against the
 * REAL cached detection data of the Director's problem page (Konoka to Kossori 3
 * page 15: a fused 10-block cloud sharing one segmentation mask), emits SVG
 * previews of the planned result over the exact source image, and prints
 * containment/shift diagnostics. A second pass experiments with the proposed
 * contained-reflow rescue ([AdaptiveBandPlanner.fitAdaptiveBands] at multiple
 * anchors) so the strategy can be verified on the laptop without rebuilding or
 * installing the APK.
 *
 * Output (browser-viewable): fixtures under rig-out as SVG + diagnostics on stdout.
 * SVG text glyphs are browser-rendered approximations; all geometry (line
 * boxes, anchors, spans, cells) comes from the real planner and measurer.
 *
 * Fixture source (read-only device extraction, cached once):
 *   fixtures/page15-committed.json — committed page artifact (blocks + masks)
 *   fixtures/page15-source.jpg     — exact 1280x1780 cleaned page image
 */
class Page15MockRig {

    /** Fixture dir discovered relative to the module working directory. */
    private fun findFixtureDir(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(
                dir,
                "Plan/active/2026-08-30_T912_text-layout-renderer/engineering/fixtures",
            )
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private val fixtureDir: File = findFixtureDir() ?: File("fixtures-not-found")
    private val outDir = File(fixtureDir, "rig-out")

    private fun fixturesPresent(): Boolean =
        File(fixtureDir, "page15-committed.json").exists() && File(fixtureDir, "page15-source.jpg").exists()

    /** Desktop-approximation of the overlay's bold outline+fill paint metrics. */
    private class AwtMeasurer : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * 0.56f * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private val measurer = AwtMeasurer()

    /** Font walk-down step per tier under the corrected containment predicate. */
    private val FONT_NUDGE_STEP = 0.5f

    /** Bounded total band evaluations per block across the whole tier ladder. */
    private val MAX_FONT_NUDGES = 16

    // ---- fixture parsing -------------------------------------------------

    private fun parseBlock(b: kotlinx.serialization.json.JsonObject): TranslationBlock {
        fun s(name: String): String = b[name]!!.jsonPrimitive.content
        fun sOrNull(name: String): String? =
            b[name]?.let { if (it.toString() == "null") null else it.jsonPrimitive.content }
        fun f(name: String): Float = b[name]!!.jsonPrimitive.content.toFloat()
        fun i(name: String): Int = b[name]!!.jsonPrimitive.content.toDouble().roundToInt()
        fun iOrNull(name: String): Int? =
            b[name]?.let { if (it.toString() == "null") null else it.jsonPrimitive.content.toDouble().roundToInt() }
        val mask = b["segmentationMask"]?.let { m ->
            if (m.toString() == "null") {
                null
            } else {
                val mo = m.jsonObject
                BubbleMaskRle(
                    width = mo["width"]!!.jsonPrimitive.content.toInt(),
                    height = mo["height"]!!.jsonPrimitive.content.toInt(),
                    bounds = mo["bounds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() },
                    runs = mo["runs"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() },
                    score = mo["score"]?.let { it.jsonPrimitive.content.toFloat() } ?: 0.95f,
                )
            }
        }
        return TranslationBlock(
            blockId = sOrNull("blockId"),
            text = s("text"),
            translation = s("translation"),
            width = f("width"),
            height = f("height"),
            x = f("x"),
            y = f("y"),
            symHeight = f("symHeight"),
            symWidth = f("symWidth"),
            angle = f("angle"),
            label = i("label"),
            score = f("score"),
            parentX = f("parentX"),
            parentY = f("parentY"),
            parentWidth = f("parentWidth"),
            parentHeight = f("parentHeight"),
            direction = s("direction"),
            panelIndex = iOrNull("panelIndex"),
            panelAssignment = s("panelAssignment"),
            panelContainment = f("panelContainment"),
            bubbleIndex = iOrNull("bubbleIndex"),
            segmentationMask = mask,
        )
    }

    // ---- containment diagnostics (measured-advance envelopes) -----------

    private data class Envelope(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(other: Envelope): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    /** One continuous span must own every integer row of [env] across [left, right). */
    private fun spanCoveredRows(spans: List<MaskGeometry.RowSpan>, env: Envelope): Boolean {
        if (spans.isEmpty()) return false
        val left = floor(env.left).toInt()
        val right = ceil(env.right).toInt()
        val top = floor(env.top).toInt()
        val bottom = ceil(env.bottom).toInt()
        if (left >= right || top >= bottom) return false
        var index = 0
        var y = top
        while (y < bottom) {
            while (index < spans.size && spans[index].y < y) index++
            var covered = false
            var row = index
            while (row < spans.size && spans[row].y == y) {
                val span = spans[row]
                if (span.start <= left && span.endExclusive >= right) {
                    covered = true
                    break
                }
                row++
            }
            if (!covered) return false
            y++
        }
        return true
    }

    private fun inkGuard(strokeWidth: Float): Float =
        ceil(max(2f, strokeWidth) / 2f + TextLayoutTuning.aaGuard(1f))

    /**
     * Corrected envelope model: measured advance + line height, inflated by
     * stroke/2 + AA guard. Does NOT reuse layoutWidthPx (which already embeds
     * the shaping guard) — this is the model from visible-fit-strategy.md §1.
     */
    private fun positionedEnvelope(layout: BlockLayout, line: PositionedLine): Envelope {
        val advance = measurer.measureTextWidth(line.text, layout.fontSizePx)
        val height = measurer.lineHeight(layout.fontSizePx)
        val g = inkGuard(layout.strokeWidth)
        return Envelope(
            line.leftPx - g,
            line.topPx - g,
            line.leftPx + advance + g,
            line.topPx + height + g,
        )
    }

    /** @return containedLines to totalLines for positioned layouts, or null for legacy. */
    private fun positionedContainment(layout: BlockLayout, spans: List<MaskGeometry.RowSpan>): Pair<Int, Int>? {
        val lines = layout.positionedLines ?: return null
        var contained = 0
        for (line in lines) {
            if (line.text.isEmpty()) {
                contained++
                continue
            }
            if (spanCoveredRows(spans, positionedEnvelope(layout, line))) contained++
        }
        return contained to lines.size
    }

    /** Legacy painted extent approximation: the safe rect the planner centered on origin. */
    private fun legacyExtent(layout: BlockLayout): Envelope = Envelope(
        layout.originX - layout.safeW / 2f,
        layout.originY - layout.safeH / 2f,
        layout.originX + layout.safeW / 2f,
        layout.originY + layout.safeH / 2f,
    )

    private fun legacyContained(layout: BlockLayout, spans: List<MaskGeometry.RowSpan>): Boolean =
        spanCoveredRows(spans, legacyExtent(layout))

    private fun assignedSpans(layout: BlockLayout): List<MaskGeometry.RowSpan>? {
        val geometry = layout.maskGeometry ?: return null
        val id = layout.maskComponentId ?: return null
        if (id < 0 || id >= geometry.components.size) return null
        return geometry.components[id].spans
    }

    private fun ocrCenter(b: TranslationBlock): Pair<Float, Float> = Pair(b.x + b.width / 2f, b.y + b.height / 2f)

    // ---- SVG emission ------------------------------------------------------

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun svgText(x: Float, baselineY: Float, text: String, fontPx: Float, fill: String, stroke: String, strokeWidth: Float): String {
        // textLength pins the browser's glyphs to the planner's measured
        // advance, so the preview is geometrically faithful (no glyph overflow
        // beyond the validated envelope).
        val advance = measurer.measureTextWidth(text, fontPx)
        return "<text x=\"${x.roundToInt()}\" y=\"${baselineY.roundToInt()}\" font-family=\"sans-serif\" " +
            "font-weight=\"bold\" font-size=\"${fontPx.roundToInt()}\" fill=\"$fill\" stroke=\"$stroke\" " +
            "stroke-width=\"$strokeWidth\" paint-order=\"stroke\" stroke-linejoin=\"round\" " +
            "textLength=\"${advance.roundToInt()}\" lengthAdjust=\"spacingAndGlyphs\">${xmlEscape(text)}</text>"
    }

    private fun svgRect(r: Envelope, stroke: String, width: Float, dash: String?): String =
        "<rect x=\"${r.left.roundToInt()}\" y=\"${r.top.roundToInt()}\" width=\"${(r.right - r.left).roundToInt()}\" " +
            "height=\"${(r.bottom - r.top).roundToInt()}\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"$width\"" +
            (if (dash != null) " stroke-dasharray=\"$dash\"" else "") + "/>"

    private fun spansPath(spans: List<MaskGeometry.RowSpan>): String = buildString {
        append("<path fill=\"#00a000\" fill-opacity=\"0.18\" d=\"")
        for (span in spans) {
            append("M").append(span.start).append(" ").append(span.y)
            append("h").append(span.endExclusive - span.start).append("v1h").append(-(span.endExclusive - span.start)).append("z")
        }
        append("\"/>")
    }

    /** Assembles the SVG document; [imageName] is a sibling file or null for synthetic pages. */
    private fun buildSvg(pageW: Int, pageH: Int, body: String, viewBox: String?, imageName: String?): String {
        if (imageName != null) {
            val imageCopy = File(outDir, imageName)
            if (!imageCopy.exists()) {
                File(fixtureDir, imageName).copyTo(imageCopy, overwrite = true)
            }
        }
        val vb = viewBox ?: "0 0 $pageW $pageH"
        val image = if (imageName != null) {
            "<image x=\"0\" y=\"0\" width=\"$pageW\" height=\"$pageH\" xlink:href=\"$imageName\"/>"
        } else {
            "<rect x=\"0\" y=\"0\" width=\"$pageW\" height=\"$pageH\" fill=\"#ffffff\"/>"
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "viewBox=\"$vb\" width=\"$pageW\" height=\"$pageH\">" +
            image + body +
            "</svg>"
    }

    // ---- main ------------------------------------------------------------

    @Test
    fun run() {
        org.junit.jupiter.api.Assumptions.assumeTrue(fixturesPresent(), "page15 fixtures not present")
        outDir.mkdirs()
        val page = Json.parseToJsonElement(File(fixtureDir, "page15-committed.json").readText()).jsonObject
        val pageW = page["imgWidth"]!!.jsonPrimitive.content.toFloat()
        val pageH = page["imgHeight"]!!.jsonPrimitive.content.toFloat()
        val blocks = page["blocks"]!!.jsonArray.map { parseBlock(it.jsonObject) }
        println("== page15 fixture: ${blocks.size} blocks, page ${pageW.toInt()}x${pageH.toInt()}")

        val plan = TextLayoutPlanner.planPage(blocks, pageW, pageH, 1, false, measurer)

        // Probe: replan with every masked block removed — isolates whether an
        // unmasked block's font delta is caused by masked-block interference.
        run {
            val unmaskedOnly = blocks.filter { it.segmentationMask == null }
            val probe = TextLayoutPlanner.planPage(unmaskedOnly, pageW, pageH, 1, false, measurer)
            probe.resultsInInputOrder.forEachIndexed { index, result ->
                val b = result.block
                if (b.translation.startsWith("Sexual intercourse")) {
                    val l = (result.outcome as? LayoutOutcome.Draw)?.layout
                    println("== probe (masked removed) 'Sexual intercourse…' font=${l?.fontSizePx}")
                }
                index
            }
        }

        // Partition/conversion diagnostics per unique mask
        println()
        println("== mask conversion diagnostics")
        val seen = HashSet<BubbleMaskRle>()
        for (b in blocks) {
            val m = b.segmentationMask ?: continue
            if (!seen.add(m)) continue
            when (val r = eu.kanade.translation.segmentation.MaskGeometry.fromOrderedRle(m, eu.kanade.translation.segmentation.MaskConversionBudgets())) {
                is eu.kanade.translation.segmentation.OrderedMaskResult.Success ->
                    println("   mask runs=${m.runs.size / 2} bounds=${m.bounds} -> components=${r.geometry.components.size}")
                is eu.kanade.translation.segmentation.OrderedMaskResult.Fallback ->
                    println("   mask runs=${m.runs.size / 2} bounds=${m.bounds} -> FALLBACK ${r.reason}")
            }
        }

        println()
        println(" i  out   font  origin(x,y)     shift parShift  clip  cell  comp  pos?  contain    text")
        var containedBlocks = 0
        var partialBlocks = 0
        plan.resultsInInputOrder.forEachIndexed { index, result ->
            val b = result.block
            val layout = (result.outcome as? LayoutOutcome.Draw)?.layout
            if (layout == null) {
                val reason = (result.outcome as? LayoutOutcome.NonDraw)?.reason
                println("%2d  NONDRAW(%s) ocr=(%4.0f,%4.0f) %s".format(index, reason, b.x, b.y, b.translation.take(30)))
                return@forEachIndexed
            }
            val spans = assignedSpans(layout)
            var contain = "-"
            if (spans != null) {
                val measured = positionedContainment(layout, spans)
                if (measured != null) {
                    contain = "${measured.first}/${measured.second}"
                    if (measured.first == measured.second) containedBlocks++ else partialBlocks++
                } else {
                    contain = if (legacyContained(layout, spans)) "legY" else "legN"
                }
            }
            val parCx = b.parentX + b.parentWidth / 2f
            val parCy = b.parentY + b.parentHeight / 2f
            val parShift = if (b.parentWidth > 0f) hypot(layout.originX - parCx, layout.originY - parCy) else 0f
            val shift = hypot(layout.originX - ocrCenter(b).first, layout.originY - ocrCenter(b).second)
            println(
                ("%2d  DRAW %5.1f  (%5.0f,%5.0f)  %5.0f   %5.0f  %-5s %-5s %-4s %-4s  %-9s  %s").format(
                    index,
                    layout.fontSizePx,
                    layout.originX,
                    layout.originY,
                    shift,
                    parShift,
                    if (layout.clipRect != null) "clip" else "-",
                    if (layout.cellRect != null) "cell" else "-",
                    if (layout.maskComponentId != null) "y" else "n",
                    if (layout.positionedLines != null) "pos" else "leg",
                    contain,
                    b.translation.take(28),
                ),
            )
        }
        println()
        println("== masked positioned fully contained: $containedBlocks, partial: $partialBlocks")

        renderCurrent(plan, pageW.toInt(), pageH.toInt())
        val stats = evaluateStrategy(plan, pageW, pageH, emitSvg = true, imageName = "page15-source.jpg")
        println(
            "== page15 strategy stats: masked=${stats.masked} contained=${stats.contained} noFit=${stats.noFit} " +
                "maxAbsDY=${stats.maxAbsDY} scoots=${stats.scoots} unresolved=${stats.unresolved}",
        )
    }

    // ---- current-plan preview ----------------------------------------------

    private fun paintLayoutSvg(layout: BlockLayout): String = buildString {
        val textColor = layout.block.textColor
        val fill = String.format("#%06x", (textColor and 0xFFFFFFL).toInt())
        val luma = (((textColor shr 16) and 0xFFL) * 299 + ((textColor shr 8) and 0xFFL) * 587 + (textColor and 0xFFL) * 114) / 1000
        val stroke = if (luma < 128) "#ffffff" else "#000000"
        val strokeW = max(2f, layout.strokeWidth)
        val lines = layout.positionedLines
        if (lines != null) {
            // SVG baseline: mock ascent factor 0.95 (browser-approximate glyphs)
            for (line in lines) {
                if (line.text.isEmpty()) continue
                append(svgText(line.leftPx.toFloat(), line.topPx + layout.fontSizePx * 0.95f, line.text, layout.fontSizePx, fill, stroke, strokeW))
            }
            return@buildString
        }
        val textLines = layout.lines
        val lineH = measurer.lineHeight(layout.fontSizePx)
        var yTop = layout.originY - textLines.size * lineH / 2f
        for (text in textLines) {
            if (text.isEmpty()) continue
            val w = measurer.measureTextWidth(text, layout.fontSizePx)
            val x = when (layout.drawAlign) {
                TextAlign.CENTER -> layout.originX - w / 2f
                TextAlign.LEFT -> layout.originX - layout.safeW / 2f
                TextAlign.RIGHT -> layout.originX + layout.safeW / 2f - w
            }
            append(svgText(x, yTop + layout.fontSizePx * 0.95f, text, layout.fontSizePx, fill, stroke, strokeW))
            yTop += lineH
        }
    }

    private fun renderCurrent(plan: PageLayoutPlan, pageW: Int, pageH: Int) {
        val body = buildString {
            for (layout in plan.drawableInRenderOrder) {
                val spans = assignedSpans(layout) ?: continue
                append(spansPath(spans))
            }
            for (layout in plan.drawableInRenderOrder) {
                val b = layout.block
                append(svgRect(Envelope(b.x, b.y, b.x + b.width, b.y + b.height), "#00beff", 1.4f, "6 4"))
                layout.cellRect?.let { cell -> append(svgRect(Envelope(cell.left, cell.top, cell.right, cell.bottom), "#ff2828", 1.6f, null)) }
                layout.clipRect?.let { clip -> append(svgRect(Envelope(clip.left, clip.top, clip.right, clip.bottom), "#ff9600", 1.6f, "4 4")) }
            }
            for (layout in plan.drawableInRenderOrder) append(paintLayoutSvg(layout))
        }
        File(outDir, "current-plan.svg").writeText(buildSvg(pageW, pageH, body, null, "page15-source.jpg"))
        File(outDir, "current-plan-cloud.svg").writeText(buildSvg(pageW, pageH, body, "40 40 1180 640", "page15-source.jpg"))
        println("== wrote ${File(outDir, "current-plan.svg").absolutePath}")
    }

    // ---- strategy experiment: contained reflow rescue ----------------------

    private data class ReflowRow(
        val layout: BlockLayout,
        val spans: List<MaskGeometry.RowSpan>,
        val slab: FloatRect,
        val entryContain: String,
        val tiered: TieredFit?,
        var scootX: Float = 0f,
        var scootY: Float = 0f,
    )

    /**
     * Containment context for a masked block: prefer the planner's assigned
     * component + cell; for blocks that LOST their cell (beyond-8 cap, ties),
     * derive the component straight from the block's own mask (largest OCR
     * overlap) with the component bbox as the slab — the ceiling is the mask,
     * the cell is not required.
     */
    private fun deriveContext(layout: BlockLayout): Pair<List<MaskGeometry.RowSpan>, FloatRect>? {
        val assigned = assignedSpans(layout)
        if (assigned != null && layout.cellRect != null) return Pair(assigned, layout.cellRect!!)
        val mask = layout.block.segmentationMask ?: return null
        val result = eu.kanade.translation.segmentation.MaskGeometry.fromOrderedRle(
            mask,
            eu.kanade.translation.segmentation.MaskConversionBudgets(),
        )
        val geometry = (result as? eu.kanade.translation.segmentation.OrderedMaskResult.Success)?.geometry
            ?: return null
        val ocrRect = FloatRect(
            layout.block.x,
            layout.block.y,
            layout.block.x + layout.block.width,
            layout.block.y + layout.block.height,
        )
        var best = listOf<MaskGeometry.RowSpan>()
        var bestOverlap = 0
        for (component in geometry.components) {
            val overlap = clipSpansToRect(component.spans, ocrRect).sumOf { it.endExclusive - it.start }
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = component.spans
            }
        }
        if (best.isEmpty()) return null
        val page = FloatRect(0f, 0f, mask.width.toFloat(), mask.height.toFloat())
        return Pair(best, contentRect(best, page) ?: page)
    }

    private fun fitExtent(fit: AdaptiveResult, dx: Float, dy: Float): Envelope {
        val g = inkGuard(TextLayoutPlanner.computeStrokeWidth(fit.fontPx, 1f))
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (line in fit.lines) {
            if (line.text.isEmpty()) continue
            val adv = measurer.measureTextWidth(line.text, fit.fontPx)
            val h = measurer.lineHeight(fit.fontPx)
            l = min(l, line.leftPx - g + dx)
            t = min(t, line.topPx - g + dy)
            r = max(r, line.leftPx + adv + g + dx)
            b = max(b, line.topPx + h + g + dy)
        }
        return Envelope(l, t, r, b)
    }

    private fun fitContained(fit: AdaptiveResult, spans: List<MaskGeometry.RowSpan>, dx: Float = 0f, dy: Float = 0f): Boolean {
        val g = inkGuard(TextLayoutPlanner.computeStrokeWidth(fit.fontPx, 1f))
        for (line in fit.lines) {
            if (line.text.isEmpty()) continue
            val adv = measurer.measureTextWidth(line.text, fit.fontPx)
            val h = measurer.lineHeight(fit.fontPx)
            val full = Envelope(line.leftPx - g + dx, line.topPx - g + dy, line.leftPx + adv + g + dx, line.topPx + h + g + dy)
            if (!spanCoveredRows(spans, full)) return false
        }
        return true
    }

    private data class StrategyStats(
        val masked: Int,
        val contained: Int,
        val noFit: Int,
        val maxAbsDY: Float,
        val scoots: Int,
        val unresolved: Int,
    )

    private fun evaluateStrategy(
        plan: PageLayoutPlan,
        pageW: Float,
        pageH: Float,
        emitSvg: Boolean,
        imageName: String?,
    ): StrategyStats {
        val gap = MaskTextRegionPlanner.collisionGapPx(minOf(pageW, pageH), 1f)
        val rows = mutableListOf<ReflowRow>()
        for (layout in plan.drawableInRenderOrder) {
            if (layout.block.segmentationMask == null) continue
            val (spans, slab) = deriveContext(layout) ?: continue
            val measured = positionedContainment(layout, spans)
            val entryContain = when {
                measured != null -> "${measured.first}/${measured.second}" + if (measured.first == measured.second) " OK" else " BAD"
                else -> if (legacyContained(layout, spans)) "legY OK" else "legN BAD"
            }
            rows += ReflowRow(
                layout,
                spans,
                slab,
                entryContain,
                containedReflowTiered(
                    layout.block,
                    layout,
                    spans,
                    slab,
                    gap,
                    allowCellContentTier = layout.maskComponentId != null && layout.cellRect != null,
                ),
            )
        }

        println()
        println("== iteration 5 — OCR box is home; contact => minimal scoot; mask = ceiling")
        var statsContained = 0
        var statsNoFit = 0
        var statsMaxAbsDY = 0f
        for (row in rows) {
            val b = row.layout.block
            val tiered = row.tiered
            if (tiered == null) {
                statsNoFit++
                println("   block@(%4.0f,%4.0f) entry=%4.1f %-9s -> NO CONTAINED FIT IN ANY TIER".format(b.x, b.y, row.layout.fontSizePx, row.entryContain))
                continue
            }
            val fit = tiered.fit
            val dx = fit.anchorX - ocrCenter(b).first
            val dy = fit.anchorY - ocrCenter(b).second
            statsMaxAbsDY = max(statsMaxAbsDY, abs(dy))
            if (fitContained(fit, row.spans)) statsContained++
            println(
                ("   block@(%4.0f,%4.0f) entry=%4.1f natural=%4.1f reflow=%5.1f dX=%+5.0f dY=%+5.0f tier=%-12s contained=%s").format(
                    b.x,
                    b.y,
                    row.layout.fontSizePx,
                    naturalOcrFont(b),
                    fit.fontPx,
                    dx,
                    dy,
                    tiered.tier,
                    if (fitContained(fit, row.spans)) "OK" else "BAD",
                ),
            )
        }

        // Contact detection + minimal bounded scoot between final text extents.
        val extentOf = { row: ReflowRow ->
            row.tiered?.fit?.let { fitExtent(it, row.scootX, row.scootY) }
        }
        val scootCap = { row: ReflowRow ->
            0.5f * min(row.layout.block.width, row.layout.block.height)
        }
        var statsScoots = 0
        var statsUnresolved = 0
        println()
        println("== text-box contacts (overlap => minimal scoot, cap = half OCR short side)")
        for (i in rows.indices) {
            for (j in i + 1 until rows.size) {
                val rowA = rows[i]
                val rowB = rows[j]
                val a = extentOf(rowA) ?: continue
                val b = extentOf(rowB) ?: continue
                if (!a.overlaps(b)) continue
                val label =
                    "   contact blocks@(%4.0f,%4.0f)+(%4.0f,%4.0f)".format(
                        rowA.layout.block.x,
                        rowA.layout.block.y,
                        rowB.layout.block.x,
                        rowB.layout.block.y,
                    )
                var resolved = false
                // move the later row first (deterministic), then the earlier as fallback
                for (mover in listOf(rowB, rowA)) {
                    val mFit = mover.tiered?.fit ?: continue
                    val mEnv = extentOf(mover) ?: continue
                    val other = if (mover === rowB) a else b
                    val candidates = listOf(
                        "right" to Pair(other.right - mEnv.left + 1f, 0f),
                        "left" to Pair(-(mEnv.right - other.left) - 1f, 0f),
                        "down" to Pair(0f, other.bottom - mEnv.top + 1f),
                        "up" to Pair(0f, -(mEnv.bottom - other.top) - 1f),
                    ).sortedBy { abs(it.second.first) + abs(it.second.second) }
                    for ((dir, d) in candidates) {
                        val dist = abs(d.first) + abs(d.second)
                        if (dist > scootCap(mover)) continue
                        val newEnv = fitExtent(mFit, mover.scootX + d.first, mover.scootY + d.second)
                        if (newEnv.overlaps(other)) continue
                        if (!fitContained(mFit, mover.spans, mover.scootX + d.first, mover.scootY + d.second)) continue
                        mover.scootX += d.first
                        mover.scootY += d.second
                        statsScoots++
                        println("$label -> scoot $dir ${dist}px (cap ${scootCap(mover)}px)")
                        resolved = true
                        break
                    }
                    if (resolved) break
                }
                if (!resolved) {
                    statsUnresolved++
                    println("$label -> UNRESOLVED within cap (accept overlap)")
                }
            }
        }

        val body = buildString {
            for (row in rows) {
                val tiered = row.tiered ?: continue
                val fit = tiered.fit
                val b = row.layout.block
                append(spansPath(row.spans))
                append(svgRect(Envelope(b.x, b.y, b.x + b.width, b.y + b.height), "#00beff", 1.4f, "6 4"))
                append(svgRect(Envelope(tiered.region.left, tiered.region.top, tiered.region.right, tiered.region.bottom), "#ffe000", 1.6f, "4 3"))
                val fill = String.format("#%06x", (row.layout.block.textColor and 0xFFFFFFL).toInt())
                val strokeW = TextLayoutPlanner.computeStrokeWidth(fit.fontPx, 1f)
                for (line in fit.lines) {
                    if (line.text.isEmpty()) continue
                    append(
                        svgText(
                            line.leftPx + row.scootX,
                            line.topPx + fit.fontPx * 0.95f + row.scootY,
                            line.text,
                            fit.fontPx,
                            fill,
                            "#ffffff",
                            strokeW,
                        ),
                    )
                }
            }
        }
        if (emitSvg) {
            File(outDir, "strategy-reflow.svg").writeText(buildSvg(pageW.toInt(), pageH.toInt(), body, null, imageName))
            File(outDir, "strategy-reflow-cloud.svg").writeText(buildSvg(pageW.toInt(), pageH.toInt(), body, "40 40 1180 640", imageName))
            println("== wrote ${File(outDir, "strategy-reflow.svg").absolutePath}")
        }
        return StrategyStats(rows.size, statsContained, statsNoFit, statsMaxAbsDY, statsScoots, statsUnresolved)
    }

    // ---- generality stress: randomized synthetic clouds ---------------------

    private data class Lobe(val cx: Int, val cy: Int, val rx: Int, val ry: Int)

    /** Union-of-ellipses row-major RLE mask (randomized cloud generator). */
    private fun ellipseCloudMask(w: Int, h: Int, lobes: List<Lobe>): BubbleMaskRle {
        val runs = ArrayList<Int>()
        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = 0
        var maxB = 0
        for (y in 0 until h) {
            val intervals = ArrayList<Pair<Int, Int>>()
            for (lobe in lobes) {
                val dy = y - lobe.cy
                if (dy < -lobe.ry || dy > lobe.ry) continue
                val t = 1f - (dy.toFloat() * dy) / (lobe.ry.toFloat() * lobe.ry)
                if (t <= 0f) continue
                val half = (lobe.rx * kotlin.math.sqrt(t)).toInt()
                if (half <= 0) continue
                val a = (lobe.cx - half).coerceAtLeast(0)
                val b = (lobe.cx + half + 1).coerceAtMost(w)
                if (b - a > 0) intervals += a to b
            }
            if (intervals.isEmpty()) continue
            intervals.sortBy { it.first }
            var cur = intervals[0]
            fun flush() {
                runs += y * w + cur.first
                runs += cur.second - cur.first
                minL = minOf(minL, cur.first)
                maxR = maxOf(maxR, cur.second)
                minT = minOf(minT, y)
                maxB = maxOf(maxB, y + 1)
            }
            for (k in 1 until intervals.size) {
                val next = intervals[k]
                cur = if (next.first <= cur.second) cur.first to maxOf(cur.second, next.second) else {
                    flush()
                    next
                }
            }
            flush()
        }
        return BubbleMaskRle(w, h, listOf(minL, minT, maxR, maxB), runs, 0.95f)
    }

    /** Wall-clock cost of the production planner on page 15 and worst-case dense pages. */
    @Test
    fun planningCost() {
        org.junit.jupiter.api.Assumptions.assumeTrue(fixturesPresent(), "page15 fixtures not present")
        val page = Json.parseToJsonElement(File(fixtureDir, "page15-committed.json").readText()).jsonObject
        val pageW = page["imgWidth"]!!.jsonPrimitive.content.toFloat()
        val pageH = page["imgHeight"]!!.jsonPrimitive.content.toFloat()
        val blocks = page["blocks"]!!.jsonArray.map { parseBlock(it.jsonObject) }

        fun timePlan(name: String, bs: List<TranslationBlock>, w: Float, h: Float, reps: Int = 3) {
            var best = Long.MAX_VALUE
            repeat(reps) {
                val t0 = System.nanoTime()
                TextLayoutPlanner.planPage(bs, w, h, 1, false, measurer)
                val ms = (System.nanoTime() - t0) / 1_000_000L
                if (ms < best) best = ms
            }
            println("COST $name: best ${best}ms (${bs.size} blocks)")
        }

        timePlan("page15-real", blocks, pageW, pageH)
        // Worst case: EVERY block carries the fused cloud mask -> maximal masked collisions.
        val cloudMask = blocks.firstNotNullOf { it.segmentationMask }
        val allMasked = blocks.map { it.copy(segmentationMask = cloudMask) }
        timePlan("page15-all-masked", allMasked, pageW, pageH)
        // Dense: cloud mask + doubled blocks (two per OCR box, forced collisions).
        val doubled = blocks.map { it.copy(segmentationMask = cloudMask) } +
            blocks.map { it.copy(blockId = "dup${it.blockId}", segmentationMask = cloudMask, score = 0.4f) }
        timePlan("page15-dense-x2", doubled, pageW, pageH)
    }

    @Test
    fun randomizedStress() {
        // Fully synthetic (fixed seed): needs no fixture files.
        val rng = java.util.Random(20260901L)
        val words = listOf(
            "teacher", "student", "bubble", "cloud", "mask", "layout", "manga", "webtoon", "quietly",
            "suddenly", "however", "because", "police", "station", "honest", "caught", "obvious",
            "forbidden", "relationship", "completely", "reason", "right", "from", "start", "tiny",
            "huge", "round", "neck", "lobe", "text", "developer", "position", "adult", "place",
        )
        fun sentence(): String =
            (0 until 6 + rng.nextInt(14)).joinToString(" ") { words[rng.nextInt(words.size)] }
                .replaceFirstChar { it.titlecase() } + listOf("?", "!", ".", "?!")[rng.nextInt(4)]

        var totMasked = 0
        var totContained = 0
        var totNoFit = 0
        var totScoots = 0
        var totUnresolved = 0
        var totMaxAbsDY = 0f
        val cases = 20
        for (case in 0 until cases) {
            val tall = rng.nextInt(100) < 30
            val w = if (tall) 800 + rng.nextInt(300) else 1000 + rng.nextInt(400)
            val h = if (tall) 2400 + rng.nextInt(800) else 1400 + rng.nextInt(400)
            val lobeCount = 2 + rng.nextInt(4)
            val cx0 = 150 + rng.nextInt(((w / 2) - 250).coerceAtLeast(120))
            val cy0 = 140 + rng.nextInt(((h / 3) - 150).coerceAtLeast(120))
            val lobes = (0 until lobeCount).map {
                Lobe(
                    cx = (cx0 + it * (70 + rng.nextInt(80)) + rng.nextInt(40)).coerceIn(120, w - 120),
                    cy = cy0 + rng.nextInt(60) - (it % 2) * 30,
                    rx = 70 + rng.nextInt(70),
                    ry = 80 + rng.nextInt(90),
                )
            }
            val mask = ellipseCloudMask(w, h, lobes)
            val blocks = ArrayList<TranslationBlock>()
            for (lobe in lobes) {
                val members = 1 + if (rng.nextInt(3) == 0) 1 else 0
                repeat(members) {
                    val bw = (lobe.rx * (0.9f + rng.nextFloat() * 0.5f)).toInt().coerceAtLeast(40)
                    val bh = (lobe.ry * (1.0f + rng.nextFloat() * 0.6f)).toInt().coerceAtLeast(80)
                    val bx = (lobe.cx - bw / 2 + rng.nextInt(21) - 10).coerceIn(4, w - bw - 4)
                    val by = (lobe.cy - bh / 2 + rng.nextInt(31) - 15).coerceIn(4, h - bh - 4)
                    blocks += TranslationBlock(
                        blockId = null,
                        text = "rnd",
                        translation = sentence(),
                        width = bw.toFloat(),
                        height = bh.toFloat(),
                        x = bx.toFloat(),
                        y = by.toFloat(),
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                        score = 0.8f + rng.nextFloat() * 0.2f,
                        direction = "TTB",
                        segmentationMask = mask,
                    )
                }
            }
            repeat(rng.nextInt(3)) {
                val bw = 60 + rng.nextInt(80)
                val bh = 100 + rng.nextInt(140)
                val bx = (rng.nextInt((w - bw).coerceAtLeast(1))).coerceIn(4, w - bw - 4)
                val by = (h / 2 + rng.nextInt((h / 3).coerceAtLeast(1))).coerceIn(4, h - bh - 4)
                blocks += TranslationBlock(
                    blockId = null,
                    text = "rnd",
                    translation = sentence(),
                    width = bw.toFloat(),
                    height = bh.toFloat(),
                    x = bx.toFloat(),
                    y = by.toFloat(),
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                    score = 0.8f + rng.nextFloat() * 0.2f,
                    direction = "TTB",
                    segmentationMask = null,
                )
            }
            val plan = TextLayoutPlanner.planPage(blocks, w.toFloat(), h.toFloat(), 1, false, measurer)
            println()
            println("###### case $case: ${w}x${h}${if (tall) " TALL" else ""} lobes=$lobeCount masked=${blocks.count { it.segmentationMask != null }} total=${blocks.size}")
            val st = evaluateStrategy(plan, w.toFloat(), h.toFloat(), emitSvg = false, imageName = null)
            totMasked += st.masked
            totContained += st.contained
            totNoFit += st.noFit
            totScoots += st.scoots
            totUnresolved += st.unresolved
            totMaxAbsDY = max(totMaxAbsDY, st.maxAbsDY)
        }
        println()
        println("== RANDOMIZED STRESS SUMMARY over $cases cases: masked=$totMasked contained=$totContained noFit=$totNoFit maxAbsDY=$totMaxAbsDY scoots=$totScoots unresolved=$totUnresolved")
    }

    /** One tier of the OCR-box-first ladder: a fit region plus the result that fits it. */
    private data class TieredFit(
        val fit: AdaptiveResult,
        val tier: String,
        val region: FloatRect,
    )

    /** Row spans of [spans] clipped to [rect] (input is row-major sorted). */
    private fun clipSpansToRect(spans: List<MaskGeometry.RowSpan>, rect: FloatRect): List<MaskGeometry.RowSpan> {
        val top = floor(rect.top).toInt()
        val bottom = ceil(rect.bottom).toInt()
        val left = floor(rect.left).toInt()
        val right = ceil(rect.right).toInt()
        val out = ArrayList<MaskGeometry.RowSpan>()
        for (s in spans) {
            if (s.y < top) continue
            if (s.y >= bottom) break
            val start = max(s.start, left)
            val end = min(s.endExclusive, right)
            if (end > start) out += MaskGeometry.RowSpan(s.y, start, end)
        }
        return out
    }

    /** Center-preserving growth of [rect] by [factor], clamped to [bounds]. */
    private fun growRect(rect: FloatRect, factor: Float, bounds: FloatRect): FloatRect {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val w = rect.width() * factor / 2f
        val h = rect.height() * factor / 2f
        return FloatRect(
            max(cx - w, bounds.left),
            max(cy - h, bounds.top),
            min(cx + w, bounds.right),
            min(cy + h, bounds.bottom),
        )
    }

    /** Bounding box of [spans] intersected with [slab] (content region of the cell). */
    private fun contentRect(spans: List<MaskGeometry.RowSpan>, slab: FloatRect): FloatRect? {
        val clipped = clipSpansToRect(spans, slab)
        if (clipped.isEmpty()) return null
        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = 0
        var maxB = 0
        for (s in clipped) {
            minL = minOf(minL, s.start)
            maxR = maxOf(maxR, s.endExclusive)
            minT = minOf(minT, s.y)
            maxB = maxOf(maxB, s.y + 1)
        }
        return FloatRect(minL.toFloat(), minT.toFloat(), maxR.toFloat(), maxB.toFloat())
    }

    /** Greedy-wrap height of [text] at [font] within width [maxW]. */
    private fun wrapHeight(text: String, font: Float, maxW: Float): Float {
        val spaceW = measurer.measureTextWidth(" ", font)
        var lines = 1
        var cur = 0f
        for (word in text.split(' ')) {
            val w = measurer.measureTextWidth(word, font)
            val adv = if (cur == 0f) w else cur + spaceW + w
            if (adv <= maxW) {
                cur = adv
            } else {
                lines++
                cur = w
            }
        }
        return lines * measurer.lineHeight(font)
    }

    /**
     * "Natural" size: the largest font that fits the text in the UNMASKED OCR
     * box by reflow alone. The strategy may recover up to this size even when
     * the current planner crushed the entry font.
     */
    private fun naturalOcrFont(block: TranslationBlock): Float {
        var lo = 8f
        var hi = 64f
        repeat(8) {
            val mid = (lo + hi) / 2f
            if (wrapHeight(block.translation, mid, block.width) <= block.height) lo = mid else hi = mid
        }
        return lo
    }

    /** Vertical-only growth of [rect] about its own center. The column keeps
     *  the OCR box's x-range, so text never wanders out of its own box. */
    private fun growTall(rect: FloatRect, factor: Float): FloatRect {
        val cy = (rect.top + rect.bottom) / 2f
        val h = rect.height() * factor / 2f
        return FloatRect(rect.left, cy - h, rect.right, cy + h)
    }

    /**
     * Director iteration 9: the text column stays inside the OCR box's
     * x-range; tiers grow VERTICALLY only (room to reflow taller), and the
     * font never exceeds the box's natural reflow fit — as large as it can
     * be WITHIN ITS OWN BOX. The mask remains a pure ceiling. The widest
     * fully-contained font across tiers wins; ties keep the earlier tier.
     */
    private fun containedReflowTiered(
        block: TranslationBlock,
        entry: BlockLayout,
        spans: List<MaskGeometry.RowSpan>,
        slab: FloatRect,
        gap: Int,
        allowCellContentTier: Boolean,
    ): TieredFit? {
        val ocr = ocrCenter(block)
        val ocrRect = FloatRect(block.x, block.y, block.x + block.width, block.y + block.height)
        val tiers = mutableListOf(
            "ocr-box" to ocrRect,
            "tall-x1.25" to growTall(ocrRect, 1.25f),
            "tall-x1.5" to growTall(ocrRect, 1.5f),
            "tall-x2.0" to growTall(ocrRect, 2.0f),
        )
        if (allowCellContentTier) {
            contentRect(spans, slab)?.let { tiers.add("cell-content" to it) }
        }
        val maxFont = naturalOcrFont(block)
        var best: TieredFit? = null
        for ((name, region) in tiers) {
            if (region.width() < 4f || region.height() < 4f) continue
            val regionSpans = clipSpansToRect(spans, region)
            if (regionSpans.isEmpty()) continue
            // The band fitter's internal row check can accept a top font whose
            // guarded ink crosses the real contour; walk the font down under
            // the corrected predicate until contained or the floor is reached.
            var ceiling = maxFont
            var attempts = 0
            while (attempts < MAX_FONT_NUDGES) {
                val fit = AdaptiveBandPlanner.fitAdaptiveBands(
                    text = entry.text,
                    cellSpans = regionSpans,
                    slab = region,
                    scale = 1f,
                    collisionGapPx = gap,
                    measurer = measurer,
                    minFontPx = 8f,
                    maxFontPx = ceiling,
                    blockCenterX = ocr.first,
                    blockCenterY = ocr.second,
                ) ?: break
                attempts++
                val tiered = TieredFit(fit, name, region)
                if (fullyContained(fit, spans)) {
                    val current = best
                    if (current == null || fit.fontPx > current.fit.fontPx) best = tiered
                    break // contained here; a smaller font in the same tier cannot win
                }
                val next = fit.fontPx - FONT_NUDGE_STEP
                if (next < 8f) break
                ceiling = next
            }
        }
        return best
    }

    private fun fullyContained(fit: AdaptiveResult, spans: List<MaskGeometry.RowSpan>): Boolean {
        val stroke = TextLayoutPlanner.computeStrokeWidth(fit.fontPx, 1f)
        val g = inkGuard(stroke)
        for (line in fit.lines) {
            if (line.text.isEmpty()) continue
            val adv = measurer.measureTextWidth(line.text, fit.fontPx)
            val h = measurer.lineHeight(fit.fontPx)
            val full = Envelope(line.leftPx - g, line.topPx - g, line.leftPx + adv + g, line.topPx + h + g)
            if (!spanCoveredRows(spans, full)) return false
        }
        return true
    }
}
