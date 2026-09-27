package eu.kanade.translation.engines.vision.segmentation

import java.util.Collections

class MaskGeometry private constructor(
    val width: Int,
    val height: Int,
    val spans: List<RowSpan>,
    val components: List<Component>,
    private val rowOffsets: IntArray,
    private val componentBySpan: IntArray,
) {
    data class RowSpan(val y: Int, val start: Int, val endExclusive: Int)

    /**
     * one connected component. [stableKey] is LAZY so the  layout
     * path never materializes a coordinate string — renderer identity uses the
     * compact `(planGeometryId, componentId)` pair instead. Legacy callers/tests
     * that read [stableKey] observe exactly the same value as before.
     */
    class Component(val id: Int, val spans: List<RowSpan>) {
        val stableKey: String by lazy { key(spans) }
    }

    /** LAZY for the same reason as [Component.stableKey]; value identical to before. */
    val stableKey: String by lazy { key(spans) }

    fun containsPoint(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        var low = rowOffsets[y]
        var high = rowOffsets[y + 1] - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val span = spans[middle]
            when {
                x < span.start -> high = middle - 1
                x >= span.endExclusive -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    fun containsRectangle(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (left < 0 || top < 0 || right > width || bottom > height || right <= left || bottom <= top) return false
        for (y in top until bottom) {
            val index = firstSpanEndingAfter(y, left)
            if (index >= rowOffsets[y + 1]) return false
            val span = spans[index]
            if (span.start > left || span.endExclusive < right) return false
        }
        return true
    }

    /** Checks if [left, top, right, bottom) lies fully inside the specified component. */
    fun containsRectangleInComponent(left: Int, top: Int, right: Int, bottom: Int, componentId: Int): Boolean {
        if (left < 0 || top < 0 || right > width || bottom > height || right <= left || bottom <= top) return false
        if (componentId !in components.indices) return false
        for (y in top until bottom) {
            val index = firstSpanEndingAfter(y, left)
            if (index >= rowOffsets[y + 1]) return false
            val span = spans[index]
            if (span.start > left || span.endExclusive < right || componentBySpan[index] != componentId) return false
        }
        return true
    }

    /** Returns [minX, minY, maxX, maxY] bounding box of [componentId], or null if invalid/empty. */
    fun componentBoundingBox(componentId: Int): IntArray? {
        if (componentId !in components.indices) return null
        val comp = components[componentId]
        if (comp.spans.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (span in comp.spans) {
            if (span.start < minX) minX = span.start
            if (span.endExclusive > maxX) maxX = span.endExclusive
            if (span.y < minY) minY = span.y
            if (span.y + 1 > maxY) maxY = span.y + 1
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    /** Intersects [componentId] with [left, top, right, bottom) and returns the bounding box of surviving pixels. */
    fun intersectComponentWithRect(componentId: Int, left: Int, top: Int, right: Int, bottom: Int): IntArray? {
        if (componentId !in components.indices || right <= left || bottom <= top) return null
        val comp = components[componentId]
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var hit = false
        for (span in comp.spans) {
            if (span.y in top until bottom) {
                val start = maxOf(span.start, left)
                val end = minOf(span.endExclusive, right)
                if (end > start) {
                    hit = true
                    if (start < minX) minX = start
                    if (end > maxX) maxX = end
                    if (span.y < minY) minY = span.y
                    if (span.y + 1 > maxY) maxY = span.y + 1
                }
            }
        }
        return if (hit) intArrayOf(minX, minY, maxX, maxY) else null
    }

    fun componentForRectangle(left: Int, top: Int, right: Int, bottom: Int): Int? {
        if (right <= left || bottom <= top || components.isEmpty()) return null
        val overlapByComponent = IntArray(components.size)
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            var index = firstSpanEndingAfter(y, left)
            val rowEnd = rowOffsets[y + 1]
            while (index < rowEnd && spans[index].start < right) {
                val span = spans[index]
                val overlap = minOf(span.endExclusive, right) - maxOf(span.start, left)
                if (overlap > 0) overlapByComponent[componentBySpan[index]] += overlap
                index++
            }
        }
        var best = 0
        var bestId: Int? = null
        var tied = false
        overlapByComponent.forEachIndexed { id, overlap ->
            when {
                overlap > best -> {
                    best = overlap
                    bestId = id
                    tied = false
                }
                overlap > 0 && overlap == best -> tied = true
            }
        }
        return if (tied) null else bestId
    }

    /**
     * Deterministic component assignment for the
     * layout path — a block is never left cell-less by ambiguity. Resolution:
     *  1. the max-overlap component when the maximum is unique and positive;
     *  2. on an exact overlap tie: among the TIED components, the one whose
     *     integer bounds center is nearest the rectangle's center;
     *  3. a further distance tie: the LOWER component id;
     *  4. zero overlap with every component: the nearest bounds center over
     *     all components (rules 2/3 apply).
     *
     * [componentForRectangle] keeps its existing null-on-tie contract for its
     * other callers; only the  assignment path uses this variant.
     */
    fun componentForRectangleDeterministic(left: Int, top: Int, right: Int, bottom: Int): Int? {
        if (right <= left || bottom <= top || components.isEmpty()) return null
        val overlapByComponent = IntArray(components.size)
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            var index = firstSpanEndingAfter(y, left)
            val rowEnd = rowOffsets[y + 1]
            while (index < rowEnd && spans[index].start < right) {
                val span = spans[index]
                val overlap = minOf(span.endExclusive, right) - maxOf(span.start, left)
                if (overlap > 0) overlapByComponent[componentBySpan[index]] += overlap
                index++
            }
        }
        var bestOverlap = 0
        var bestByOverlap = -1
        var tied = false
        overlapByComponent.forEachIndexed { id, overlap ->
            when {
                overlap > bestOverlap -> {
                    bestOverlap = overlap
                    bestByOverlap = id
                    tied = false
                }
                overlap > 0 && overlap == bestOverlap -> tied = true
            }
        }
        if (bestOverlap > 0 && !tied) return bestByOverlap

        // Tie on overlap (or zero overlap everywhere): among the tied
        // components — all of them when every overlap is zero — take the
        // nearest integer bounds center, then the lower component id
        // (ascending iteration + strict `<`).
        val tiedOverlap = bestOverlap
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        var nearestId = -1
        var nearestDistance = Float.MAX_VALUE
        components.forEachIndexed { id, component ->
            if (overlapByComponent[id] != tiedOverlap) return@forEachIndexed
            var boundsLeft = Int.MAX_VALUE
            var boundsTop = Int.MAX_VALUE
            var boundsRight = Int.MIN_VALUE
            var boundsBottom = Int.MIN_VALUE
            for (span in component.spans) {
                if (span.start < boundsLeft) boundsLeft = span.start
                if (span.endExclusive > boundsRight) boundsRight = span.endExclusive
                if (span.y < boundsTop) boundsTop = span.y
                if (span.y + 1 > boundsBottom) boundsBottom = span.y + 1
            }
            val dx = (boundsLeft + boundsRight) / 2f - centerX
            val dy = (boundsTop + boundsBottom) / 2f - centerY
            val distance = dx * dx + dy * dy
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestId = id
            }
        }
        return nearestId
    }

    fun isRectangleSafe(left: Int, top: Int, right: Int, bottom: Int, inset: Int): Boolean {
        require(inset >= 0) { "Inset must be non-negative" }
        val safeLeft = left.toLong() - inset
        val safeTop = top.toLong() - inset
        val safeRight = right.toLong() + inset
        val safeBottom = bottom.toLong() + inset
        if (safeLeft < Int.MIN_VALUE ||
            safeTop < Int.MIN_VALUE ||
            safeRight > Int.MAX_VALUE ||
            safeBottom > Int.MAX_VALUE
        ) {
            return false
        }
        return containsRectangle(safeLeft.toInt(), safeTop.toInt(), safeRight.toInt(), safeBottom.toInt())
    }

    private fun firstSpanEndingAfter(y: Int, x: Int): Int {
        var low = rowOffsets[y]
        var high = rowOffsets[y + 1]
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (spans[middle].endExclusive <= x) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        internal const val MAX_DIMENSION = 1_000_000
        internal const val MAX_SPANS = 100_000
        internal const val MAX_COMPONENTS_PER_MASK = 64

        internal fun requireValidDimensions(width: Int, height: Int) {
            require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "Invalid mask geometry dimensions" }
        }

        /**
         * budgeted ordered RLE → geometry conversion for the
         * layout path. Two-pass, no sorting, never [BubbleMaskRle.decode], never a
         * `width*height` allocation, and no coordinate-string key before caps.
         * Internal (not public) because [MaskConversionBudgets] is a module type;
         * the planner and tests are in-module.
         */
        internal fun fromOrderedRle(mask: BubbleMaskRle, budgets: MaskConversionBudgets): OrderedMaskResult {
            // Page RLE-int budget is checked before any scanning/allocation; the
            // core re-checks idempotently.
            if (budgets.rleIntsScanned + mask.runs.size > budgets.maxRleIntsScannedPerPage) {
                return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.BUDGET_EXCEEDED)
            }
            return fromOrderedRleCore(
                width = mask.width,
                height = mask.height,
                bounds = mask.bounds.toIntArray(),
                runs = mask.runs.toIntArray(),
                budgets = budgets,
            )
        }

        /**
         * Primitive core of [fromOrderedRle] so tests can exercise invalid and
         * overflow paths directly. [bounds] is `(left, top, right, bottom)`;
         * the geometry itself spans the full `width×height`. [runs] are ordered
         * non-overlapping `(start, length)` pairs (guaranteed by [BubbleMaskRle]
         * for production input; contract violations throw [IllegalArgumentException]).
         */
        internal fun fromOrderedRleCore(
            width: Int,
            height: Int,
            bounds: IntArray,
            runs: IntArray,
            budgets: MaskConversionBudgets,
        ): OrderedMaskResult {
            if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
                return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.INVALID_DIMENSIONS)
            }
            require(bounds.size == 4) { "bounds must be (left, top, right, bottom)" }
            require(runs.size % 2 == 0) { "RLE runs must be (start, length) pairs" }

            // Page RLE-int budget is checked BEFORE scanning; nothing is counted
            // on a would-exceed abort.
            if (budgets.rleIntsScanned + runs.size > budgets.maxRleIntsScannedPerPage) {
                return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.BUDGET_EXCEEDED)
            }
            budgets.rleIntsScanned += runs.size

            if (runs.isEmpty()) {
                return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.EMPTY_MASK)
            }

            val pixelCount = width.toLong() * height.toLong()

            // ---- Pass A: arithmetic row split + span counting, no span/component objects.
            var spanCount = 0
            var lastRow = -1L
            var lastEnd = -1L
            var previousEnd = 0L
            var index = 0
            while (index < runs.size) {
                val start = runs[index].toLong()
                val length = runs[index + 1].toLong()
                require(length > 0) { "RLE run length must be positive" }
                require(start >= previousEnd) { "RLE runs must be ordered and non-overlapping" }
                val end = start + length
                previousEnd = end
                if (end > pixelCount || end > Int.MAX_VALUE) {
                    // The run's pixel extent leaves the page's representable space.
                    return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.ARITHMETIC_OVERFLOW)
                }
                var pieceStart = start
                while (pieceStart < end) {
                    val row = pieceStart / width
                    val rowEnd = (row + 1) * width
                    val pieceEnd = if (end < rowEnd) end else rowEnd
                    if (row == lastRow && pieceStart == lastEnd) {
                        // Continuation of the previous span on the same row (adjacent
                        // RLE runs): merge so semantics match fromSpans normalization.
                        lastEnd = pieceEnd
                    } else {
                        // Stop BEFORE the count would exceed either cap (== allowed).
                        if (spanCount + 1 > MAX_SPANS ||
                            budgets.derivedSpans + spanCount + 1 > budgets.maxDerivedSpansPerPage
                        ) {
                            return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.BUDGET_EXCEEDED)
                        }
                        spanCount++
                        lastRow = row
                        lastEnd = pieceEnd
                    }
                    pieceStart = pieceEnd
                }
                index += 2
            }
            budgets.derivedSpans += spanCount

            // ---- Pass B: bounded primitive storage in row-major/start order.
            val spanRows = IntArray(spanCount)
            val spanStarts = IntArray(spanCount)
            val spanEnds = IntArray(spanCount)
            var filled = 0
            var lastRowInt = -1
            var lastEndInt = -1
            var lastIndex = -1
            index = 0
            while (index < runs.size) {
                val start = runs[index]
                val length = runs[index + 1]
                val end = start + length // Pass A proved this fits in an Int.
                var pieceStart = start
                while (pieceStart < end) {
                    val row = pieceStart / width
                    // Long math: near the top of the Int range (row+1)*width can
                    // exceed Int.MAX_VALUE even though every stored value fits.
                    val rowEnd = (row.toLong() + 1) * width
                    val pieceEnd = minOf(end.toLong(), rowEnd).toInt()
                    // Spans carry ROW-RELATIVE x coordinates; the merge bookkeeping
                    // above stays in page space.
                    val rowBase = row * width
                    if (row == lastRowInt && pieceStart == lastEndInt) {
                        spanEnds[lastIndex] = pieceEnd - rowBase
                        lastEndInt = pieceEnd
                    } else {
                        spanRows[filled] = row
                        spanStarts[filled] = pieceStart - rowBase
                        spanEnds[filled] = pieceEnd - rowBase
                        lastRowInt = row
                        lastEndInt = pieceEnd
                        lastIndex = filled
                        filled++
                    }
                    pieceStart = pieceEnd
                }
                index += 2
            }
            check(filled == spanCount) { "Ordered span derivation drifted between passes" }

            // Row offsets built by counting — no sorting.
            val rowOffsets = IntArray(height + 1)
            for (s in 0 until filled) rowOffsets[spanRows[s] + 1]++
            for (y in 1..height) rowOffsets[y] += rowOffsets[y - 1]

            // Union adjacent-row overlaps with the existing two-pointer sweep,
            // charging every comparison against the page work budget.
            val parents = IntArray(filled) { it }
            val ranks = ByteArray(filled)
            fun root(of: Int): Int {
                var result = of
                while (parents[result] != result) result = parents[result]
                var current = of
                while (parents[current] != current) {
                    val next = parents[current]
                    parents[current] = result
                    current = next
                }
                return result
            }

            fun union(a: Int, b: Int) {
                val rootA = root(a)
                val rootB = root(b)
                if (rootA == rootB) return
                when {
                    ranks[rootA] < ranks[rootB] -> parents[rootA] = rootB
                    ranks[rootA] > ranks[rootB] -> parents[rootB] = rootA
                    else -> {
                        parents[rootB] = rootA
                        ranks[rootA]++
                    }
                }
            }

            for (y in 1 until height) {
                var previous = rowOffsets[y - 1]
                val previousEnd = rowOffsets[y]
                var current = rowOffsets[y]
                val currentEnd = rowOffsets[y + 1]
                while (previous < previousEnd && current < currentEnd) {
                    if (budgets.sweepComparisons >= budgets.maxSweepComparisonsPerPage) {
                        // Discard the primitive arrays and fall back.
                        return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.BUDGET_EXCEEDED)
                    }
                    budgets.sweepComparisons++
                    val aStart = spanStarts[previous]
                    val aEnd = spanEnds[previous]
                    val bStart = spanStarts[current]
                    val bEnd = spanEnds[current]
                    when {
                        aEnd <= bStart -> previous++
                        bEnd <= aStart -> current++
                        else -> {
                            union(previous, current)
                            if (aEnd < bEnd) previous++ else current++
                        }
                    }
                }
            }

            // Count union roots BEFORE creating any Component, component span
            // list, string key, or renderer Path.
            var roots = 0
            for (s in 0 until filled) if (parents[s] == s) roots++
            if (roots > MAX_COMPONENTS_PER_MASK || budgets.componentsBuilt + roots > budgets.maxComponentsPerPage) {
                return OrderedMaskResult.Fallback(OrderedMaskFallbackReason.BUDGET_EXCEEDED)
            }
            budgets.componentsBuilt += roots

            // Only now materialize RowSpan objects — already row-major/start
            // ordered, so no sorting pass exists on this path.
            val spans = ArrayList<RowSpan>(filled)
            for (s in 0 until filled) spans += RowSpan(spanRows[s], spanStarts[s], spanEnds[s])
            val immutableSpans = immutableCopy(spans)

            // Component ids by first appearance (row-major) of the component's
            // earliest span — deterministic and string-free. These ids
            // intentionally may differ from fromSpans' key-sorted ids; nothing
            // may depend on cross-path id equality.
            val rootToComponent = HashMap<Int, Int>(roots * 2)
            val componentBySpan = IntArray(filled)
            var nextComponentId = 0
            for (s in 0 until filled) {
                val componentRoot = root(s)
                val id = rootToComponent.getOrPut(componentRoot) { nextComponentId++ }
                componentBySpan[s] = id
            }
            val componentSpanLists = Array(nextComponentId) { ArrayList<RowSpan>() }
            for (s in 0 until filled) componentSpanLists[componentBySpan[s]] += spans[s]
            val components = componentSpanLists.mapIndexed { id, list -> Component(id, immutableCopy(list)) }

            return OrderedMaskResult.Success(
                MaskGeometry(width, height, immutableSpans, immutableCopy(components), rowOffsets, componentBySpan),
            )
        }

        fun fromSpans(width: Int, height: Int, spans: List<RowSpan>, maxSpans: Int = MAX_SPANS): MaskGeometry {
            requireValidDimensions(width, height)
            require(maxSpans > 0 && spans.isNotEmpty() && spans.size <= maxSpans) { "Invalid mask geometry" }
            val ordered = spans.sortedWith(compareBy<RowSpan> { it.y }.thenBy { it.start })
            val normalized = ArrayList<RowSpan>(ordered.size)
            ordered.forEach { span ->
                require(
                    span.y in 0 until height &&
                        span.start in 0 until width &&
                        span.endExclusive in (span.start + 1)..width,
                ) { "Invalid mask span" }
                val previous = normalized.lastOrNull()
                when {
                    previous == null || previous.y < span.y -> normalized += span
                    previous.endExclusive > span.start -> throw IllegalArgumentException("Mask spans must not overlap")
                    previous.endExclusive == span.start -> normalized[normalized.lastIndex] =
                        previous.copy(endExclusive = span.endExclusive)
                    else -> normalized += span
                }
            }
            require(normalized.size <= maxSpans) { "Invalid mask geometry" }
            val immutableSpans = immutableCopy(normalized)
            val rowOffsets = buildRowOffsets(height, immutableSpans)
            val componentData = buildComponents(immutableSpans, rowOffsets)
            return MaskGeometry(
                width,
                height,
                immutableSpans,
                componentData.components,
                rowOffsets,
                componentData.componentBySpan,
            )
        }

        private data class ComponentData(val components: List<Component>, val componentBySpan: IntArray)

        private fun buildRowOffsets(height: Int, spans: List<RowSpan>): IntArray {
            val offsets = IntArray(height + 1)
            spans.forEach { offsets[it.y + 1]++ }
            for (y in 1..height) offsets[y] += offsets[y - 1]
            return offsets
        }

        private fun buildComponents(spans: List<RowSpan>, rowOffsets: IntArray): ComponentData {
            val parents = IntArray(spans.size) { it }
            val ranks = ByteArray(spans.size)
            fun root(index: Int): Int {
                var result = index
                while (parents[result] != result) result = parents[result]
                var current = index
                while (parents[current] != current) {
                    val next = parents[current]
                    parents[current] = result
                    current = next
                }
                return result
            }
            fun union(a: Int, b: Int) {
                val rootA = root(a)
                val rootB = root(b)
                if (rootA == rootB) return
                when {
                    ranks[rootA] < ranks[rootB] -> parents[rootA] = rootB
                    ranks[rootA] > ranks[rootB] -> parents[rootB] = rootA
                    else -> {
                        parents[rootB] = rootA
                        ranks[rootA]++
                    }
                }
            }

            for (y in 1 until rowOffsets.lastIndex) {
                var previous = rowOffsets[y - 1]
                val previousEnd = rowOffsets[y]
                var current = rowOffsets[y]
                val currentEnd = rowOffsets[y + 1]
                while (previous < previousEnd && current < currentEnd) {
                    val a = spans[previous]
                    val b = spans[current]
                    when {
                        a.endExclusive <= b.start -> previous++
                        b.endExclusive <= a.start -> current++
                        else -> {
                            union(previous, current)
                            if (a.endExclusive < b.endExclusive) previous++ else current++
                        }
                    }
                }
            }

            val groups = linkedMapOf<Int, MutableList<Int>>()
            spans.indices.forEach { groups.getOrPut(root(it)) { mutableListOf() } += it }
            val orderedGroups = groups.values.sortedBy { indices -> key(indices.map(spans::get)) }
            val componentBySpan = IntArray(spans.size)
            val components = orderedGroups.mapIndexed { id, indices ->
                indices.forEach { componentBySpan[it] = id }
                val componentSpans = immutableCopy(indices.map(spans::get))
                Component(id, componentSpans)
            }
            return ComponentData(immutableCopy(components), componentBySpan)
        }

        private fun <T> immutableCopy(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        private fun key(spans: List<RowSpan>): String = spans.joinToString(";") {
            "${it.y}:${it.start}-${it.endExclusive}"
        }
    }
}

/**
 * why budgeted ordered RLE conversion did not produce a geometry.
 * Returned instead of thrown so layout always receives an explicit result and
 * every input keeps exactly one layout on every fallback path.
 */
enum class OrderedMaskFallbackReason {
    EMPTY_MASK,
    INVALID_DIMENSIONS,
    ARITHMETIC_OVERFLOW,
    BUDGET_EXCEEDED,
}

/** Explicit result of budgeted ordered conversion: a usable geometry or a fallback reason. */
sealed interface OrderedMaskResult {
    data class Success(val geometry: MaskGeometry) : OrderedMaskResult
    data class Fallback(val reason: OrderedMaskFallbackReason) : OrderedMaskResult
}

/**
 * page-scoped budgets and counters for ordered RLE → geometry
 * conversion. ONE instance is shared by every mask on a page so
 * per-page work stays bounded:
 *  - [rleIntsScanned]: RLE integers fingerprinted or conversion-scanned;
 *  - [derivedSpans]: row spans derived across all conversions this page;
 *  - [componentsBuilt]: components actually materialized (never counts a
 *    discarded conversion);
 *  - [sweepComparisons]: union-find two-pointer comparisons performed.
 *
 * All limits are documented tunables; tests may construct with overrides.
 */
internal class MaskConversionBudgets(
    val maxDerivedSpansPerPage: Int = DEFAULT_MAX_DERIVED_SPANS_PER_PAGE,
    val maxRleIntsScannedPerPage: Int = DEFAULT_MAX_RLE_INTS_SCANNED_PER_PAGE,
    val maxComponentsPerPage: Int = DEFAULT_MAX_COMPONENTS_PER_PAGE,
    val maxSweepComparisonsPerPage: Int = DEFAULT_MAX_SWEEP_COMPARISONS_PER_PAGE,
) {
    internal var rleIntsScanned: Int = 0
    internal var derivedSpans: Int = 0
    internal var componentsBuilt: Int = 0
    internal var sweepComparisons: Int = 0

    internal companion object {
        internal const val DEFAULT_MAX_DERIVED_SPANS_PER_PAGE = 100_000
        internal const val DEFAULT_MAX_RLE_INTS_SCANNED_PER_PAGE = 200_000
        internal const val DEFAULT_MAX_COMPONENTS_PER_PAGE = 64
        internal const val DEFAULT_MAX_SWEEP_COMPARISONS_PER_PAGE = 1_000_000
    }
}
