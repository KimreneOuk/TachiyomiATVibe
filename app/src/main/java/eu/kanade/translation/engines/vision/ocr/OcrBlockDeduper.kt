package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.model.TranslationBlock

object OcrBlockDeduper {

    /**
     * Drop overlapping [TranslationBlock]s that describe the same text region,
     * keeping the higher-score block.
     *
     * This closes gaps left by the recognition engines' own dedupe stages:
     * identical-text checks miss overlaps with different OCR text, bubble-scoped
     * checks miss blocks without a shared parent, and full-page ML Kit has no
     * dedupe stage. Without this pass, overlapping blocks reach the renderer and
     * their translated text is drawn on top of itself.
     *
     * "Same region" is decided by [BoxGeometry.isGeometricDuplicate] and the
     * shared [BoxGeometry.TEXT_DEDUP_THRESHOLDS]. It is label-, parent-, and
     * text-agnostic. The high overlap thresholds leave distinct bubbles that
     * merely touch intact.
     *
     * Blocks are dropped rather than merged because merging changes the
     * translation unit and reading order. Equal scores keep the earlier block.
     * This function does not mutate [blocks] and returns a new list.
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
