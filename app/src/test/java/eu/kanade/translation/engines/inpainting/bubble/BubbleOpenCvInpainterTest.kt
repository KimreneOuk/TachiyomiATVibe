package eu.kanade.translation.engines.inpainting.bubble

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleOpenCvInpainterTest {

    private fun mask(width: Int, height: Int, vararg boxes: IntArray): ByteArray {
        val result = ByteArray(width * height)
        for (box in boxes) {
            for (y in box[1] until box[3]) {
                for (x in box[0] until box[2]) {
                    result[y * width + x] = 1
                }
            }
        }
        return result
    }

    @Test
    fun `far-apart bubbles form separate clusters`() {
        val clusters = BubbleOpenCvInpainter.componentClusters(
            mask(200, 200, intArrayOf(10, 10, 50, 50), intArrayOf(150, 150, 190, 190)),
            width = 200,
            height = 200,
        )

        clusters.map { it.toList() } shouldBe listOf(listOf(10, 10, 50, 50), listOf(150, 150, 190, 190))
    }

    @Test
    fun `bubbles within the gap merge into one cluster`() {
        val clusters = BubbleOpenCvInpainter.componentClusters(
            mask(200, 100, intArrayOf(0, 10, 50, 50), intArrayOf(100, 10, 150, 50)),
            width = 200,
            height = 100,
            gap = 64,
        )

        clusters.size shouldBe 1
        clusters[0].toList() shouldBe listOf(0, 10, 150, 50)
    }

    @Test
    fun `diagonal adjacency within gap merges and empty mask yields no clusters`() {
        BubbleOpenCvInpainter.componentClusters(ByteArray(100 * 100), 100, 100) shouldBe emptyList()
        val clusters = BubbleOpenCvInpainter.componentClusters(
            mask(100, 100, intArrayOf(5, 5, 20, 20), intArrayOf(25, 25, 40, 40)),
            100,
            100,
            gap = 8,
        )

        clusters.size shouldBe 1
    }
}
