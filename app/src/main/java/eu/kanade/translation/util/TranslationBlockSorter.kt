package eu.kanade.translation.util

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import kotlin.math.abs

object TranslationBlockSorter {
    /**
     * Sorts translation blocks into a reading order (Top-to-Bottom, then Left-to-Right or Right-to-Left)
     * based on the source language. Because complex panel layouts break strict 2D coordinate sorting,
     * this provides a "best effort" sequential nudge for the AI.
     */
    fun sort(blocks: List<TranslationBlock>, fromLang: TextRecognizerLanguage): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val topToBottom = blocks.sortedBy { it.y }

        val rows = mutableListOf<MutableList<TranslationBlock>>()
        var currentRow = mutableListOf<TranslationBlock>()

        for (block in topToBottom) {
            if (currentRow.isEmpty()) {
                currentRow.add(block)
            } else {
                val lastBlock = currentRow.last()
                val avgY = currentRow.map { it.y }.average()

                // Same row if blocks vertically overlap, or Y is near the row's average Y.
                val overlap = maxOf(0f, minOf(lastBlock.y + lastBlock.height, block.y + block.height) - maxOf(lastBlock.y, block.y))
                if (overlap > 0 || abs(block.y - avgY) < block.height / 2) {
                    currentRow.add(block)
                } else {
                    rows.add(currentRow)
                    currentRow = mutableListOf(block)
                }
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        // 3. Sort each row horizontally
        val isRtl = fromLang == TextRecognizerLanguage.JAPANESE
        rows.forEach { row ->
            row.sortWith { a, b ->
                if (isRtl) b.x.compareTo(a.x) else a.x.compareTo(b.x)
            }
        }

        return rows.flatten().toMutableList()
    }
}
