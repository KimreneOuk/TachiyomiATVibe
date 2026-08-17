package eu.kanade.translation.segmentation

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
    data class Component(val id: Int, val spans: List<RowSpan>, val stableKey: String)

    val stableKey: String = key(spans)

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

        internal fun requireValidDimensions(width: Int, height: Int) {
            require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "Invalid mask geometry dimensions" }
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
                Component(id, componentSpans, key(componentSpans))
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
