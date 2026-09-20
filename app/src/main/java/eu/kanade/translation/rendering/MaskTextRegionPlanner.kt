package eu.kanade.translation.rendering

import eu.kanade.translation.segmentation.MaskGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * TachiyomiAT  slice 3: pure partitioner for ONE shared segmentation
 * component. Partitions the `(planGeometryId, componentId)` group's members
 * into disjoint half-open cells so every shared-component pixel is owned by at
 * most one block and a deterministic dead zone separates neighbouring cells.
 *
 * Pure JVM: integer slab math + span intersection only — no `android`
 * imports, no dense page arrays, no measurement. The axis, cuts, dead zone,
 * and fit regions follow architecture revision 2 ("Slice 3"):
 *
 *  1. Scan centers are rounded with `floor(value + 0.5)` and clamped to the
 *     integer component bounds.
 *  2. Partitioning is horizontal when the clamped X spread is >= the Y spread,
 *     otherwise vertical.
 *  3. Members are stably sorted by (axis center, orthogonal center, input index).
 *  4. The cut between adjacent members A/B is the integer floor midpoint of
 *     their axis centers, biased ONLY when both parents are valid and their
 *     edges face in center order without overlapping (horizontal: `A.right <=
 *     B.left`); the biased cut is the floor midpoint of those edges clamped to
 *     the closed integer interval between the two centers. Overlapping or
 *     reversed parents never bias.
 *  5. Cuts must be monotonically strictly increasing. When equal centers/cuts
 *     make that impossible, ALL cuts are replaced by deterministic equal-width
 *     slabs across the component bounds in stable order:
 *     member k of n gets `[left + (k*W)/n, left + ((k+1)*W)/n)`.
 *  6. Slabs are half-open with a dead zone: `gapBefore = gap/2`,
 *     `gapAfter = gap - gapBefore`; at cut `c` the earlier cell ends at
 *     `c - gapBefore` exclusive and the later cell begins at `c + gapAfter`
 *     inclusive. The other axis spans the full component bounds. A slab whose
 *     interval is empty is a degenerate (empty) cell.
 *  7. Component spans are intersected with each slab — a row span crossing two
 *     cuts yields up to three cell segments and is never assigned whole.
 *     Segments are COUNTED in a first pass and charged to the page-wide
 *     [CellSpanBudget] BEFORE any cell list is allocated; a group whose
 *     materialization would exceed the budget falls back to bounds-rect mode
 *     cells (same slabs, no span lists).
 *  8. At most [MAX_SHARED_BLOCKS_OPTIMIZED] members per component are
 *     optimized; members beyond the first 8 in stable order get no cell at all
 *     ([Cell.slab] == null, [Cell.optimized] == false).  repair (R4b):
 *     they additionally carry [Cell.overflowSlab] — a disjoint bounds-rect
 *     slab cut from the same partition sequence — so the integration can give
 *     them a hard `cellRect` (no component path) and the hard-cell exemption
 *     applies to them too.
 *  9. The fit region is the bounding box of the cell's spans (span mode) or
 *     the slab itself (bounds mode). A slab without component pixels, or whose
 *     spans do not overlap the member's own OCR rectangle, is resolved by the
 *     explicit empty-cell probes below — never with another block's cell, the
 *     whole component, or merged text:
 *      - probe 1: component ∩ slab ∩ own OCR rectangle (bbox of those spans);
 *      - probe 2: component ∩ slab, block-center-nearest continuous interval
 *        on the cut axis (bbox of the spans in that interval);
 *      - both empty ⇒ [Cell.empty] and the integration emits
 *        `NonDraw(EMPTY_SHARED_CELL)` for that identity.
 */
internal object MaskTextRegionPlanner {

    /** Hard guard: blocks optimized per component (beyond → legacy rectangle). */
    internal const val MAX_SHARED_BLOCKS_OPTIMIZED = 8

    /** Default page-wide cap on derived cell segments (architecture budget table). */
    internal const val DEFAULT_MAX_DERIVED_CELL_SPANS_PER_PAGE = 100_000

    /**
     * Page-scoped counter for derived cell spans. The caller owns ONE instance
     * per page plan; [MaskTextRegionPlanner.partition] counts a group's
     * segments in a first pass and consumes the counter only when the whole
     * group fits. A failed consumption never charges the budget.
     */
    class CellSpanBudget(val maxDerivedCellSpansPerPage: Int = DEFAULT_MAX_DERIVED_CELL_SPANS_PER_PAGE) {
        internal var used: Int = 0
            private set

        /** Consumes [count] when it keeps the page total within the cap. */
        fun tryConsume(count: Int): Boolean {
            if (count < 0 || used + count > maxDerivedCellSpansPerPage) return false
            used += count
            return true
        }
    }

    /**
     * One block inside a shared component group. Centers are page floats; the
     * planner derives the partition axis itself, so both centers are supplied.
     * [parentRect] is the block's detected parent bubble box (or null) and
     * [parentValid] mirrors the caller's parent-validity flag; only a valid,
     * non-overlapping, facing parent pair may bias a cut.
     */
    data class Member(
        val inputIndex: Int,
        val centerX: Float,
        val centerY: Float,
        val ocrRect: FloatRect,
        val parentRect: FloatRect? = null,
        val parentValid: Boolean = false,
    )

    /**
     * Integer component bounds `[left, top, right, bottom)` plus the
     * component's row-major spans. `spans == null` selects BOUNDS-RECT MODE
     * (mask conversion fell back): the same cut logic runs on the bounds
     * rectangle, no span lists are produced, and the cell is empty only when
     * its slab is degenerate.
     */
    data class ComponentRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val spans: List<MaskGeometry.RowSpan>? = null,
    )

    /**
     * Result entry for one member, in input order. [slab] is the hard disjoint
     * half-open cell (`null` for members beyond [MAX_SHARED_BLOCKS_OPTIMIZED]);
     * [fitRegion] is the preferred placement subregion inside the slab (bounded
     * by it, `null` for an empty cell); [spans] is the bounded cell segment
     * list (empty in bounds-rect mode); [empty] marks a cell with no usable
     * component pixels; [optimized] is false only for beyond-cap members.
     *
     *  repair (R4b): beyond-cap members carry [overflowSlab] — a disjoint
     * BOUNDS-RECT slab cut from the SAME partition sequence (cuts span all
     * members), so the integration can give them a hard `cellRect` without a
     * component path. Null when their own slab would be degenerate.
     */
    data class Cell(
        val inputIndex: Int,
        val slab: FloatRect?,
        val fitRegion: FloatRect?,
        val spans: List<MaskGeometry.RowSpan>,
        val empty: Boolean,
        val optimized: Boolean,
        val overflowSlab: FloatRect? = null,
    )

    /**
     * COLLISION_GAP per architecture revision 2:
     * `ceil(clamp(0.001 * pageShortSide, 2*scale, 4*scale))` — a small,
     * resolution-aware separation kept between neighbouring shared cells.
     */
    internal fun collisionGapPx(pageShortSide: Float, scale: Float): Int =
        ceil((0.001f * pageShortSide).coerceIn(2f * scale, 4f * scale)).toInt()

    /**
     * Partition [members] over [component] into disjoint cells. Returns one
     * [Cell] per member in INPUT order (not stable-sort order). [gap] is the
     * caller-computed [collisionGapPx] dead-zone width; [budget] is the
     * page-wide cell-span counter.
     */
    fun partition(
        component: ComponentRegion,
        members: List<Member>,
        gap: Int,
        budget: CellSpanBudget,
    ): List<Cell> {
        if (members.isEmpty()) return emptyList()
        val horizontal = isHorizontalAxis(component, members)
        val axisLow = if (horizontal) component.left else component.top
        val axisHigh = if (horizontal) component.right else component.bottom
        val orthoLow = if (horizontal) component.top else component.left
        val orthoHigh = if (horizontal) component.bottom else component.right

        // 1. Rounded, clamped integer scan centers.
        val scans = members.map { member ->
            val rawAxis = if (horizontal) member.centerX else member.centerY
            val rawOrtho = if (horizontal) member.centerY else member.centerX
            Scan(member, clampRound(rawAxis, axisLow, axisHigh), clampRound(rawOrtho, orthoLow, orthoHigh))
        }
        // 3. Stable order: axis center, orthogonal center, input index.
        val sorted = scans.sortedWith(
            compareBy({ it.axisCenter }, { it.orthoCenter }, { it.member.inputIndex }),
        )
        val optimizedCount = min(members.size, MAX_SHARED_BLOCKS_OPTIMIZED)
        val optimized = sorted.subList(0, optimizedCount)
        //  repair (R4b): members beyond the cap keep a bounds-rect slab
        // cut from the SAME partition sequence (see [Cell.overflowSlab]).
        val overflowInputs = HashSet<Int>(sorted.size - optimizedCount)
        for (i in optimizedCount until sorted.size) overflowInputs += sorted[i].member.inputIndex
        val cellsByInput = HashMap<Int, Cell>(members.size)
        // 8. Members beyond the cap are NOT optimized — no cell at all.
        for (i in optimizedCount until sorted.size) {
            val member = sorted[i].member
            cellsByInput[member.inputIndex] =
                Cell(member.inputIndex, null, null, emptyList(), empty = false, optimized = false)
        }

        // 4. Center-midpoint cuts with the facing-parent bias. The cut sequence
        // spans ALL stable-ordered members so overflow slabs stay disjoint from
        // the optimized cells (for groups within the cap this is unchanged).
        val cuts = ArrayList<Int>(sorted.size - 1)
        for (i in 0 until sorted.size - 1) {
            cuts += cutBetween(sorted[i], sorted[i + 1], horizontal)
        }
        // 5. Strictly monotonic cuts, else deterministic equal-width slabs.
        val monotonic = cuts.zipWithNext().all { (previous, next) -> previous < next }
        val slabs: List<Slab> = if (monotonic) {
            val gapBefore = gap / 2
            val gapAfter = gap - gapBefore
            sorted.mapIndexed { k, scan ->
                val start = if (k == 0) axisLow else cuts[k - 1] + gapAfter
                val end = if (k == sorted.size - 1) axisHigh else cuts[k] - gapBefore
                Slab(scan.member, start, end)
            }
        } else {
            val width = axisHigh - axisLow
            sorted.mapIndexed { k, scan ->
                Slab(
                    scan.member,
                    axisLow + Math.floorDiv(k * width, sorted.size),
                    axisLow + Math.floorDiv((k + 1) * width, sorted.size),
                )
            }
        }

        // 7. First pass: count derived segments for the whole group, then
        // enforce the page-wide cap BEFORE materializing any cell list. A group
        // that would exceed the budget falls back to bounds-rect mode cells.
        val componentSpans = component.spans
        var useSpans = componentSpans != null
        if (useSpans) {
            var derived = 0
            for (span in componentSpans!!) {
                if (span.y < orthoLow || span.y >= orthoHigh) continue
                for (slab in slabs) {
                    if (slab.start >= slab.endExclusive) continue
                    if (if (horizontal) {
                            span.start < slab.endExclusive && slab.start < span.endExclusive
                        } else {
                            span.y >= slab.start && span.y < slab.endExclusive
                        }
                    ) {
                        derived++
                    }
                }
            }
            useSpans = budget.tryConsume(derived)
        }

        val cellSpans = HashMap<Int, MutableList<MaskGeometry.RowSpan>>(slabs.size)
        if (useSpans) {
            val perSlab = List(slabs.size) { mutableListOf<MaskGeometry.RowSpan>() }
            for (span in componentSpans!!) {
                if (span.y < orthoLow || span.y >= orthoHigh) continue
                for ((k, slab) in slabs.withIndex()) {
                    if (slab.start >= slab.endExclusive) continue
                    if (horizontal) {
                        val start = max(span.start, slab.start)
                        val end = min(span.endExclusive, slab.endExclusive)
                        if (start < end) perSlab[k] += MaskGeometry.RowSpan(span.y, start, end)
                    } else {
                        // Vertical slab: the cut is on Y — a row belongs to the
                        // slab WHOLE (its x interval is untouched).
                        if (span.y >= slab.start && span.y < slab.endExclusive) {
                            perSlab[k] += MaskGeometry.RowSpan(span.y, span.start, span.endExclusive)
                        }
                    }
                }
            }
            slabs.forEachIndexed { k, slab -> cellSpans[slab.member.inputIndex] = perSlab[k] }
        }

        // 6/9/10. Slab rect, fit region, empty flag, one entry per member.
        for (slab in slabs) {
            val member = slab.member
            val rect = slabRect(horizontal, slab, orthoLow, orthoHigh)
            val degenerate = slab.start >= slab.endExclusive
            if (member.inputIndex in overflowInputs) {
                //  repair (R4b): beyond-cap member — no cell, but a
                // disjoint bounds-rect slab from the same cut sequence when it
                // has positive area.
                cellsByInput[member.inputIndex] = Cell(
                    member.inputIndex,
                    slab = null,
                    fitRegion = null,
                    spans = emptyList(),
                    empty = false,
                    optimized = false,
                    overflowSlab = if (degenerate) null else rect,
                )
                continue
            }
            if (!useSpans) {
                // Bounds-rect mode: the slab IS the cell; spans stay empty and
                // only a degenerate slab is an empty cell.
                cellsByInput[member.inputIndex] = Cell(
                    member.inputIndex,
                    slab = rect,
                    fitRegion = if (degenerate) null else rect,
                    spans = emptyList(),
                    empty = degenerate,
                    optimized = true,
                )
                continue
            }
            val spans = cellSpans[member.inputIndex] ?: emptyList()
            if (spans.isEmpty()) {
                // Probes 1 and 2 are both slab-bounded, so a slab that owns no
                // component pixels can never be rescued: explicit empty cell.
                cellsByInput[member.inputIndex] =
                    Cell(member.inputIndex, rect, null, spans, empty = true, optimized = true)
                continue
            }
            val fitRegion = if (spans.any { spanIntersectsRect(it, member.ocrRect) }) {
                // Content bounds of the cell's own pixels.
                boundingBox(spans)
            } else {
                // Probe 2: block-center-nearest continuous interval on the cut
                // axis (probe 1 — component ∩ slab ∩ OCR — is empty here).
                boundingBox(spansInNearestInterval(spans, member, horizontal))
            }
            cellsByInput[member.inputIndex] =
                Cell(member.inputIndex, rect, fitRegion, spans, empty = false, optimized = true)
        }

        return members.map { cellsByInput.getValue(it.inputIndex) }
    }

    /** Horizontal partitioning when the clamped X spread >= the Y spread. */
    private fun isHorizontalAxis(component: ComponentRegion, members: List<Member>): Boolean {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (member in members) {
            val x = clampRound(member.centerX, component.left, component.right)
            val y = clampRound(member.centerY, component.top, component.bottom)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return (maxX - minX) >= (maxY - minY)
    }

    /**
     * Cut between adjacent members A (earlier) and B: integer floor midpoint of
     * the axis centers, biased to the parent-edge midpoint ONLY when both
     * parents are valid and face in center order without overlapping; the
     * biased value is clamped to the closed integer interval between centers.
     */
    private fun cutBetween(a: Scan, b: Scan, horizontal: Boolean): Int {
        var cut = Math.floorDiv(a.axisCenter + b.axisCenter, 2)
        val parentA = a.member.parentRect
        val parentB = b.member.parentRect
        if (a.member.parentValid && parentA != null && b.member.parentValid && parentB != null) {
            val facing = if (horizontal) parentA.right <= parentB.left else parentA.bottom <= parentB.top
            if (facing) {
                val biased = if (horizontal) {
                    floor((parentA.right + parentB.left) / 2f).toInt()
                } else {
                    floor((parentA.bottom + parentB.top) / 2f).toInt()
                }
                cut = biased.coerceIn(minOf(a.axisCenter, b.axisCenter), maxOf(a.axisCenter, b.axisCenter))
            }
        }
        return cut
    }

    /** Half-open slab as a [FloatRect]; the exclusive end IS the boundary. */
    private fun slabRect(horizontal: Boolean, slab: Slab, orthoLow: Int, orthoHigh: Int): FloatRect =
        if (horizontal) {
            FloatRect(slab.start.toFloat(), orthoLow.toFloat(), slab.endExclusive.toFloat(), orthoHigh.toFloat())
        } else {
            FloatRect(orthoLow.toFloat(), slab.start.toFloat(), orthoHigh.toFloat(), slab.endExclusive.toFloat())
        }

    private fun spanIntersectsRect(span: MaskGeometry.RowSpan, rect: FloatRect): Boolean =
        span.y + 1 > rect.top && span.y < rect.bottom &&
            span.endExclusive > rect.left && span.start < rect.right

    private fun boundingBox(spans: List<MaskGeometry.RowSpan>): FloatRect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (span in spans) {
            if (span.start < left) left = span.start
            if (span.endExclusive > right) right = span.endExclusive
            if (span.y < top) top = span.y
            if (span.y + 1 > bottom) bottom = span.y + 1
        }
        return FloatRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /**
     * Empty-cell probe 2: project the cell's spans onto the cut axis, merge
     * them into continuous intervals, and take the spans of the interval whose
     * center is nearest the block center (ties: smaller interval start, then
     * end). Never returns spans outside the cell's own slab.
     */
    private fun spansInNearestInterval(
        spans: List<MaskGeometry.RowSpan>,
        member: Member,
        horizontal: Boolean,
    ): List<MaskGeometry.RowSpan> {
        val intervals = spans
            .map { span ->
                if (horizontal) AxisInterval(span.start, span.endExclusive) else AxisInterval(span.y, span.y + 1)
            }
            .sortedWith(compareBy({ it.start }, { it.endExclusive }))
        val merged = ArrayList<AxisInterval>(intervals.size)
        for (interval in intervals) {
            val last = merged.lastOrNull()
            if (last != null && interval.start <= last.endExclusive) {
                if (interval.endExclusive > last.endExclusive) {
                    merged[merged.lastIndex] = AxisInterval(last.start, interval.endExclusive)
                }
            } else {
                merged += interval
            }
        }
        val center = if (horizontal) member.centerX else member.centerY
        // Nearest interval center; ties resolve to the smaller interval start
        // (merged intervals are disjoint and sorted, so this is deterministic).
        var nearest = merged.first()
        var nearestDistance = abs((nearest.start + nearest.endExclusive) / 2f - center)
        for (i in 1 until merged.size) {
            val candidate = merged[i]
            val distance = abs((candidate.start + candidate.endExclusive) / 2f - center)
            if (distance < nearestDistance || (distance == nearestDistance && candidate.start < nearest.start)) {
                nearest = candidate
                nearestDistance = distance
            }
        }
        return spans.filter { span ->
            val start = if (horizontal) span.start else span.y
            val end = if (horizontal) span.endExclusive else span.y + 1
            start < nearest.endExclusive && nearest.start < end
        }
    }

    /** `floor(value + 0.5)` clamped to the integer component interval. */
    private fun clampRound(value: Float, low: Int, high: Int): Int =
        floor(value + 0.5f).toInt().coerceIn(low, high)

    private data class Scan(val member: Member, val axisCenter: Int, val orthoCenter: Int)

    private data class Slab(val member: Member, val start: Int, val endExclusive: Int)

    private data class AxisInterval(val start: Int, val endExclusive: Int)
}
