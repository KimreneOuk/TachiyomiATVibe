package eu.kanade.translation.model

import kotlin.math.abs

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

        private fun mergeBlocks(r1: TranslationBlock, r2: TranslationBlock): TranslationBlock {
            return mergeGroup(listOf(r1, r2))
        }

        // Checks if two block overlap each other and are in same orientation
        private fun shouldMerge(r1: TranslationBlock, r2: TranslationBlock): Boolean {
            return abs(r1.angle - r2.angle) < 10 && r1.x < (r2.x + r2.width) && (r1.x + r1.width) > r2.x &&
                r1.y < (r2.y + r2.height) && (r1.y + r1.height) > r2.y
        }

        private fun mergeGroup(group: List<TranslationBlock>): TranslationBlock {
            val ordered = readingOrder(group)
            val x = ordered.minOf { it.x }
            val y = ordered.minOf { it.y }
            val right = ordered.maxOf { it.x + it.width }
            val bottom = ordered.maxOf { it.y + it.height }
            val best = ordered.maxBy { it.score }
            val first = ordered.first()

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
                parentX = 0f,
                parentY = 0f,
                parentWidth = 0f,
                parentHeight = 0f,
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

        private fun isVerticalColumn(block: TranslationBlock): Boolean {
            return block.direction == "TTB" || block.height > block.width * 1.8f
        }

        private fun centerX(block: TranslationBlock): Float = block.x + block.width / 2f
    }
}
