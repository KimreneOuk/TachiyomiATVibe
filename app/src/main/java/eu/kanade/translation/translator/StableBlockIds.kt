package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock

/** Assigns the persisted, reading-order-independent IDs used by batch requests. */
object StableBlockIds {
    private val localIdRegex = Regex("(?:p\\d+_)?b(\\d+)")

    fun assign(page: PageTranslation, naturalPageIndex: Int) {
        val sorted = page.blocks.withIndex().sortedWith(
            compareBy<IndexedValue<TranslationBlock>> { it.value.y }
                .thenBy { it.value.x }
                .thenBy { it.value.width }
                .thenBy { it.value.height }
                .thenBy { it.index },
        )
        val used = HashSet<Int>()
        var next = 0
        sorted.forEach { indexed ->
            val persisted = indexed.value.blockId
                ?.let(localIdRegex::matchEntire)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val stableIndex = if (persisted != null && used.add(persisted)) {
                persisted
            } else {
                while (!used.add(next)) next++
                next
            }
            next = maxOf(next, stableIndex + 1)
            indexed.value.blockId = BatchTranslationProtocol.blockId(naturalPageIndex, stableIndex)
        }
    }
}
