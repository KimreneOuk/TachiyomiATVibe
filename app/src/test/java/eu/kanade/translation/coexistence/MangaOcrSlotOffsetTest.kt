package eu.kanade.translation.coexistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaOcrSlotOffsetTest {

    @Test
    fun `N1-5 slot offset formula maps step 2 to slot 1 avoiding slot 0 BOS overwrite`() {
        // In MangaOcrEngine, cache shape is [4, 1, 4, 256, 64] and slot = pos - 1.
        val maxLen = 256
        val headDim = 64
        val cacheShape4 = maxLen * headDim

        fun calculateDstOffset(layer: Int, head: Int, slot: Int): Int =
            (layer * 4 + head) * cacheShape4 + slot * headDim

        calculateDstOffset(0, 0, 0) shouldBe 0
        calculateDstOffset(0, 0, 1) shouldBe 64
        calculateDstOffset(0, 0, 2) shouldBe 128
        calculateDstOffset(1, 2, 1) shouldBe (1 * 4 + 2) * (256 * 64) + 64
    }
}
