package eu.kanade.translation.util

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import tachiyomi.domain.translation.TranslationReadingOrder
import kotlin.math.abs

object TranslationBlockSorter {
    /**
     * Sorts translation blocks into a reading order.
     * Panel-aware: Groups by panelIndex (if OWNED) in ascending order.
     * Unassigned blocks fall into a single trailing group.
     * Within each group, it sorts Top-to-Bottom, then Left-to-Right or Right-to-Left.
     */
    fun sort(
        blocks: List<TranslationBlock>, 
        fromLang: TextRecognizerLanguage,
        readingOrder: TranslationReadingOrder = TranslationReadingOrder.AUTO
    ): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val panelGroups = mutableMapOf<Int, MutableList<TranslationBlock>>()
        val unassigned = mutableListOf<TranslationBlock>()

        for (block in blocks) {
            if (block.panelAssignment == "owned" && block.panelIndex != null) {
                panelGroups.getOrPut(block.panelIndex) { mutableListOf() }.add(block)
            } else {
                unassigned.add(block)
            }
        }

        val result = mutableListOf<TranslationBlock>()
        
        panelGroups.keys.sorted().forEach { panelIdx ->
            result.addAll(coordinateSort(panelGroups[panelIdx]!!, fromLang, readingOrder))
        }
        
        result.addAll(coordinateSort(unassigned, fromLang, readingOrder))
        
        return result
    }

    private fun coordinateSort(
        blocks: List<TranslationBlock>, 
        fromLang: TextRecognizerLanguage,
        readingOrder: TranslationReadingOrder
    ): List<TranslationBlock> {
        if (blocks.isEmpty()) return emptyList()

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
        val isRtl = when (readingOrder) {
            TranslationReadingOrder.RTL_MANGA -> true
            TranslationReadingOrder.LTR_COMIC -> false
            TranslationReadingOrder.AUTO -> fromLang == TextRecognizerLanguage.JAPANESE
        }
        rows.forEach { row ->
            row.sortWith { a, b ->
                if (isRtl) b.x.compareTo(a.x) else a.x.compareTo(b.x)
            }
        }

        return rows.flatten()
    }
}
