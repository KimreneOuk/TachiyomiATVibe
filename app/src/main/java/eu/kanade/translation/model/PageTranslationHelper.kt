package eu.kanade.translation.model

import eu.kanade.translation.recognition.BoxGeometry
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

        /**
         * TachiyomiAT: drop overlapping [TranslationBlock]s that describe the
         * same text region, keeping the higher-score one.
         *
         * This is the single geometric dedupe that closes the gaps the
         * recognition engine's own dedupe stages leave open:
         *  - `RoiPageRecognitionEngine.removePostOcrDuplicateBlocks` only drops
         *    a pair when their OCR text is IDENTICAL — two overlapping boxes
         *    OCR'd to different text both survive.
         *  - `suppressCrossLabelDuplicates` only fires when the two detections
         *    share the same parent bubble — overlapping boxes in different
         *    bubbles, or with no bubble parent, both survive.
         *  - `MlKitFullPageRecognitionEngine` does no dedupe at all.
         *
         * The consequence is two overlapping blocks reaching the renderer,
         * where each is drawn centred in its own rect ([PageTextRenderer]) —
         * the reported "translated text rendered on top of itself" artifact.
         *
         * "Same region" is decided purely geometrically via
         * [BoxGeometry.isGeometricDuplicate] with the shared
         * [BoxGeometry.TEXT_DEDUP_THRESHOLDS] (iou 0.62 / containment 0.86 /
         * center 0.12 / size 0.20). It is label-, parent-, and text-agnostic,
         * so it collapses cross-label overlaps, no-parent overlaps, and
         * differing-text overlaps alike. The high IoU/containment thresholds
         * mean two genuinely-distinct bubbles that merely touch (low IoU) are
         * NOT collapsed — only regions that are geometrically the same patch.
         *
         * Drop (not merge): merging concatenates OCR text and changes the
         * translation unit's semantics + reading order, which can span two
         * real bubbles that happen to touch. Dropping the lower-confidence
         * duplicate preserves the winner's text and translation verbatim.
         *
         * Tie-break on equal scores favours the EARLIER block (stable order),
         * so reading order is preserved when scores are indistinguishable.
         *
         * Pure + side-effect-free: does not mutate [blocks]. Returns a new list.
         */
        fun dedupeGeometricOverlaps(blocks: List<TranslationBlock>): List<TranslationBlock> {
            if (blocks.size < 2) return blocks.toList()
            // Sort by score descending so the highest-score block in any overlap
            // pair is seen first and wins; ties keep the original order (stable
            // sort) so equal-score blocks retain reading order.
            val ordered = blocks.sortedByDescending { it.score }
            val kept = ArrayList<TranslationBlock>(blocks.size)
            for (candidate in ordered) {
                val candidateBox = candidate.toIntBox()
                if (candidateBox == null || !candidateBox.isValid()) {
                    // Degenerate box — keep the block rather than drop it on a
                    // geometry check we can't evaluate.
                    kept.add(candidate)
                    continue
                }
                val isDuplicateOfKept = kept.any { keptBlock ->
                    val keptBox = keptBlock.toIntBox()
                    keptBox != null &&
                        keptBox.isValid() &&
                        BoxGeometry.isGeometricDuplicate(candidateBox, keptBox, BoxGeometry.TEXT_DEDUP_THRESHOLDS)
                }
                if (!isDuplicateOfKept) kept.add(candidate)
            }
            // Re-sort into the original reading order (by y then x) so the
            // caller sees the surviving blocks in the same order the detector
            // emitted them, just with the duplicates removed.
            return blocks.filter { it in kept }
        }

        private fun TranslationBlock.toIntBox(): IntArray? =
            if (width <= 0f || height <= 0f) null
            else intArrayOf(x.toInt(), y.toInt(), (x + width).toInt(), (y + height).toInt())

        private fun IntArray.isValid(): Boolean =
            size >= 4 && this[2] > this[0] && this[3] > this[1]
    }
}
