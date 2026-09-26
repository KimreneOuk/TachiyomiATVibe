package eu.kanade.translation.model

import eu.kanade.translation.engines.vision.ocr.BoxGeometry

class PageTranslationHelper {

    companion object {
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
         * where each is drawn centred in its own rect —
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
            // Highest score first wins overlaps; stable sort keeps reading order on ties.
            val ordered = blocks.sortedByDescending { it.score }
            val kept = ArrayList<TranslationBlock>(blocks.size)
            for (candidate in ordered) {
                val candidateBox = candidate.toIntBox()
                if (candidateBox == null || !candidateBox.isValid()) {
                    // Degenerate box — keep rather than drop on a check we can't evaluate.
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
            // Re-filter by the original order so survivors keep detector reading order.
            return blocks.filter { it in kept }
        }

        private fun TranslationBlock.toIntBox(): IntArray? =
            if (width <= 0f || height <= 0f) {
                null
            } else {
                intArrayOf(x.toInt(), y.toInt(), (x + width).toInt(), (y + height).toInt())
            }

        private fun IntArray.isValid(): Boolean =
            size >= 4 && this[2] > this[0] && this[3] > this[1]
    }
}
