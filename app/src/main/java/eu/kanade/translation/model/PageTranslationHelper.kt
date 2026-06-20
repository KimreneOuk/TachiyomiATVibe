package eu.kanade.translation.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PageTranslationHelper {

    companion object {
        /**
         * Merges Text block which overlap
         * this doesn't mutate the original blocks
         */
        fun mergeOverlap(blocks: ArrayList<TranslationBlock>): ArrayList<TranslationBlock> {
            val result = ArrayList<TranslationBlock>()
            val toProcess = blocks.toMutableList()
            while (toProcess.isNotEmpty()) {
                val current = toProcess.removeAt(0)
                val index = result.indexOfLast { shouldMerge(current, it) }
                if (index != -1) {
                    val mergedRectangle = mergeBlocks(result[index], current)
                    result[index] = mergedRectangle
                } else {
                    result.add(current)
                }
            }
            return result
        }

        /**
         * Coalesces OCR fragments that should be translated/rendered as one region.
         *
         * ONNX normally emits one text box per bubble, but fallback ML Kit often
         * emits one box per vertical manga column. Rendering those narrow columns
         * independently makes their English translations collide. Keep this pure
         * and geometry-only so both recognition engines can share the same guard.
         */
        fun mergeRelatedBlocks(blocks: List<TranslationBlock>): MutableList<TranslationBlock> {
            if (blocks.size < 2) return blocks.toMutableList()

            val remaining = blocks.toMutableList()
            val merged = mutableListOf<TranslationBlock>()
            while (remaining.isNotEmpty()) {
                val group = mutableListOf(remaining.removeAt(0))
                var changed: Boolean
                do {
                    changed = false
                    val iterator = remaining.listIterator()
                    while (iterator.hasNext()) {
                        val candidate = iterator.next()
                        if (group.any { shouldMergeRelated(it, candidate) }) {
                            group.add(candidate)
                            iterator.remove()
                            changed = true
                        }
                    }
                } while (changed)

                merged.add(if (group.size == 1) group.first() else mergeGroup(group))
            }
            return merged
        }

        private fun mergeBlocks(r1: TranslationBlock, r2: TranslationBlock): TranslationBlock {
            return mergeGroup(listOf(r1, r2))
        }

        // Checks if two block overlap each other and are in same orientation
        private fun shouldMerge(r1: TranslationBlock, r2: TranslationBlock): Boolean {
            return abs(r1.angle - r2.angle) < 10 && r1.x < (r2.x + r2.width) && (r1.x + r1.width) > r2.x &&
                r1.y < (r2.y + r2.height) && (r1.y + r1.height) > r2.y
        }

        private fun shouldMergeRelated(r1: TranslationBlock, r2: TranslationBlock): Boolean {
            if (abs(r1.angle - r2.angle) >= 10) return false
            if (sameParentBubble(r1, r2)) return true
            return areAdjacentVerticalColumns(r1, r2)
        }

        private fun sameParentBubble(r1: TranslationBlock, r2: TranslationBlock): Boolean {
            if (!hasParent(r1) || !hasParent(r2)) return false
            val a = parentBox(r1)
            val b = parentBox(r2)
            val inter = intersection(a, b)
            val smaller = min(area(a), area(b))
            return smaller > 0f && inter / smaller > 0.88f
        }

        private fun areAdjacentVerticalColumns(r1: TranslationBlock, r2: TranslationBlock): Boolean {
            if (!isVerticalColumn(r1) || !isVerticalColumn(r2)) return false
            val overlapY = min(r1.y + r1.height, r2.y + r2.height) - max(r1.y, r2.y)
            if (overlapY <= 0f) return false
            val overlapRatio = overlapY / max(1f, min(r1.height, r2.height))
            if (overlapRatio < 0.45f) return false

            val gap = horizontalGap(r1, r2)
            val maxGap = max(12f, min(r1.width, r2.width) * 0.9f)
            if (gap > maxGap) return false

            val centerDeltaY = abs(centerY(r1) - centerY(r2))
            val maxCenterDeltaY = max(40f, min(r1.height, r2.height) * 0.35f)
            return centerDeltaY <= maxCenterDeltaY
        }

        private fun mergeGroup(group: List<TranslationBlock>): TranslationBlock {
            val ordered = readingOrder(group)
            val x = ordered.minOf { it.x }
            val y = ordered.minOf { it.y }
            val right = ordered.maxOf { it.x + it.width }
            val bottom = ordered.maxOf { it.y + it.height }
            val best = ordered.maxBy { it.score }
            val first = ordered.first()
            val parent = commonParent(ordered)

            return TranslationBlock(
                text = ordered.joinToString("\n") { it.text },
                translation = ordered.joinToString("\n") { it.translation },
                width = right - x,
                height = bottom - y,
                x = x,
                y = y,
                symWidth = ordered.minOf { it.symWidth },
                symHeight = ordered.minOf { it.symHeight },
                angle = ordered.map { it.angle }.average().toFloat(),
                label = best.label,
                score = best.score,
                parentX = parent?.get(0) ?: 0f,
                parentY = parent?.get(1) ?: 0f,
                parentWidth = parent?.let { it[2] - it[0] } ?: 0f,
                parentHeight = parent?.let { it[3] - it[1] } ?: 0f,
                textColor = first.textColor,
                strokeColor = first.strokeColor,
                strokeWidth = ordered.maxOf { it.strokeWidth },
                direction = dominantDirection(ordered),
            )
        }

        private fun readingOrder(group: List<TranslationBlock>): List<TranslationBlock> {
            return if (group.all(::isVerticalColumn)) {
                group.sortedWith(compareByDescending<TranslationBlock> { centerX(it) }.thenBy { it.y })
            } else {
                group.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            }
        }

        private fun dominantDirection(group: List<TranslationBlock>): String {
            return group
                .groupingBy { it.direction }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: group.first().direction
        }

        private fun commonParent(group: List<TranslationBlock>): FloatArray? {
            val parented = group.filter(::hasParent)
            if (parented.size != group.size) return null
            val first = parentBox(parented.first())
            return if (parented.drop(1).all { sameParentBubble(parented.first(), it) }) first else null
        }

        private fun isVerticalColumn(block: TranslationBlock): Boolean {
            return block.direction == "TTB" || block.height > block.width * 1.8f
        }

        private fun hasParent(block: TranslationBlock): Boolean {
            return block.parentWidth > 0f && block.parentHeight > 0f
        }

        private fun parentBox(block: TranslationBlock): FloatArray {
            return floatArrayOf(
                block.parentX,
                block.parentY,
                block.parentX + block.parentWidth,
                block.parentY + block.parentHeight,
            )
        }

        private fun area(box: FloatArray): Float {
            return max(0f, box[2] - box[0]) * max(0f, box[3] - box[1])
        }

        private fun intersection(a: FloatArray, b: FloatArray): Float {
            val ix1 = max(a[0], b[0])
            val iy1 = max(a[1], b[1])
            val ix2 = min(a[2], b[2])
            val iy2 = min(a[3], b[3])
            return max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        }

        private fun horizontalGap(r1: TranslationBlock, r2: TranslationBlock): Float {
            return when {
                r1.x + r1.width < r2.x -> r2.x - (r1.x + r1.width)
                r2.x + r2.width < r1.x -> r1.x - (r2.x + r2.width)
                else -> 0f
            }
        }

        private fun centerX(block: TranslationBlock): Float = block.x + block.width / 2f

        private fun centerY(block: TranslationBlock): Float = block.y + block.height / 2f
    }
}
