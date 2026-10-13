package eu.kanade.translation.engines.inpainting.aot

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.RegionBackend
import eu.kanade.translation.engines.inpainting.RegionDispatch
import eu.kanade.translation.model.BubbleMaskRle
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.ArrayDeque

/**
 * End-to-end coverage of the segmentation-only bubble policy in
 * [NeuralInpaintingCoordinator.inpaintRegions]:
 *  - a nonrectangular YOLO11-seg mask reaches the neural delegate unchanged in
 *    shape through crop / tile selection (only contour-eroded);
 *  - pixels beyond the feather ramp outside the mask stay untouched;
 *  - a mixed page with one bubble lacking segmentation keeps that bubble's
 *    pixels and reports the page degraded ("neural_partial_masks");
 *  - a page with no segmentation at all never invents a mask and reports
 *    "skipped_no_segmentation".
 */
class NeuralInpaintingCoordinatorBubbleTest {

    private val paperColor = 0xFFF8F8F8.toInt()
    private val fillColor = 0xFF22CC44.toInt()

    @Test
    fun `nonrectangular segmentation reaches delegate through crop and pixels outside the mask stay pixel-identical`() {
        val size = 64
        val rawPlus = plusMask(size, size)
        val bitmap = FakeBitmap(size, size, paperColor)

        val delegateCalls = mutableListOf<CapturedTile>()
        val coordinator = NeuralInpaintingCoordinator()
        coordinator.bubbleNeuralFill = { _, localMask, crop, side ->
            delegateCalls.add(CapturedTile(localMask.copyOf(), crop.copyOf(), side))
            IntArray(side * side) { fillColor }
        }

        val result = coordinator.inpaintRegions(
            image = bitmap.mock,
            boxes = listOf(intArrayOf(10, 10, 54, 54), intArrayOf(20, 20, 44, 44)),
            labels = listOf(0, 1),
            dispatch = RegionDispatch(RegionBackend.NEURAL, RegionBackend.SMOOTH_DIFFUSION),
            blocks = listOf(block(mask = rle(size, size, rawPlus))),
        )

        delegateCalls.size shouldBe 1
        val tile = delegateCalls.single()
        tile.side shouldBe size
        tile.crop shouldBe intArrayOf(0, 0, size, size)

        val mask = tile.localMask
        mask.size shouldBe size * size
        // The eroded mask is a strict subset of the supplied segmentation and
        // keeps its nonrectangular plus shape.
        var maskedCount = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (mask[y * size + x] != 0.toByte()) {
                    maskedCount++
                    rawPlus[y * size + x] shouldBe true
                }
            }
        }
        // Erosion strictly shrinks the supplied segmentation (the disc test in
        // erodeBinaryMask keeps rounded corners, so the exact kept count is
        // geometry-dependent — assert the subset and strict-shrink invariants).
        val rawCount = rawPlus.count { it }
        (maskedCount in 1 until rawCount) shouldBe true
        // Contour tips are eroded away to protect the bubble stroke border.
        mask[31 * size + 12] shouldBe 0.toByte()
        mask[31 * size + 15] shouldBe 1.toByte()
        // A vertical-arm-only row is much narrower than the horizontal-arm row.
        rowWidth(mask, size, 20) shouldBe 6
        rowWidth(mask, size, 31) shouldBe 48 - 15 + 1

        // Inward-only feather: the composite may touch a pixel only when the
        // ERODED mask marks it with at least 2 px of interior depth. Every
        // pixel outside the eroded mask — hence outside the ORIGINAL supplied
        // segmentation, including the stroke-adjacent eroded margin — must be
        // pixel-identical.
        val insideDepth = insideDistances(mask, size, size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val index = y * size + x
                val pixel = bitmap.backing[index]
                if (mask[index] != 0.toByte() && insideDepth[index] >= 2) {
                    pixel shouldNotBe paperColor
                } else {
                    pixel shouldBe paperColor
                }
            }
        }

        coordinator.lastBubbleRoute shouldBe "neural"
        coordinator.lastRunDegraded shouldBe false
        result shouldBe bitmap.mock
    }

    @Test
    fun `mixed page keeps the uncovered bubble untouched and reports degraded partial masks`() {
        val size = 64
        val rawPlus = plusMask(size, size)
        // Wipe the right half of the segmentation: the second bubble's area has
        // no coverage at all.
        for (y in 0 until size) {
            for (x in 32 until size) rawPlus[y * size + x] = false
        }
        val bitmap = FakeBitmap(size, size, paperColor)

        val delegateCalls = mutableListOf<CapturedTile>()
        val coordinator = NeuralInpaintingCoordinator()
        coordinator.bubbleNeuralFill = { _, localMask, crop, side ->
            delegateCalls.add(CapturedTile(localMask.copyOf(), crop.copyOf(), side))
            IntArray(side * side) { fillColor }
        }

        coordinator.inpaintRegions(
            image = bitmap.mock,
            boxes = listOf(
                intArrayOf(10, 10, 42, 42),
                intArrayOf(34, 34, 62, 62),
                intArrayOf(14, 14, 38, 38),
                intArrayOf(38, 38, 58, 58),
            ),
            labels = listOf(0, 0, 1, 1),
            dispatch = RegionDispatch(RegionBackend.NEURAL, RegionBackend.SMOOTH_DIFFUSION),
            blocks = listOf(
                block(mask = rle(size, size, rawPlus)),
                block(mask = null),
            ),
        )

        delegateCalls.size shouldBe 1
        val mask = delegateCalls.single().localMask
        for (y in 36 until 60) {
            for (x in 36 until 60) {
                mask[y * size + x] shouldBe 0.toByte()
            }
        }
        // The uncovered bubble's pixels are untouched; only the covered
        // bubble's feathered neighbourhood changed.
        bitmap.backing[60 * size + 50] shouldBe paperColor
        coordinator.lastBubbleRoute shouldBe "neural_partial_masks"
        coordinator.lastRunDegraded shouldBe true
    }

    @Test
    fun `bubble without any segmentation keeps pixels and reports skipped_no_segmentation`() {
        val size = 64
        val bitmap = FakeBitmap(size, size, paperColor)

        var delegateCalls = 0
        val coordinator = NeuralInpaintingCoordinator()
        coordinator.bubbleNeuralFill = { _, _, _, side ->
            delegateCalls++
            IntArray(side * side) { fillColor }
        }

        coordinator.inpaintRegions(
            image = bitmap.mock,
            boxes = listOf(intArrayOf(10, 10, 54, 54), intArrayOf(20, 20, 44, 44)),
            labels = listOf(0, 1),
            dispatch = RegionDispatch(RegionBackend.NEURAL, RegionBackend.SMOOTH_DIFFUSION),
            blocks = listOf(block(mask = null)),
        )

        delegateCalls shouldBe 0
        coordinator.lastBubbleRoute shouldBe "skipped_no_segmentation"
        coordinator.lastRunDegraded shouldBe true
        for (pixel in bitmap.backing) pixel shouldBe paperColor
    }

    private fun block(mask: BubbleMaskRle?): TranslationBlock = TranslationBlock(
        text = "hello",
        width = 24f,
        height = 24f,
        x = 20f,
        y = 20f,
        symHeight = 24f,
        symWidth = 24f,
        angle = 0f,
        segmentationMask = mask,
    )

    /** Plus-shaped mask: horizontal bar rows 24..39, vertical bar columns 24..39. */
    private fun plusMask(width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inHorizontalBar = y in 24..39 && x in 10..53
                val inVerticalBar = x in 24..39 && y in 10..53
                mask[y * width + x] = inHorizontalBar || inVerticalBar
            }
        }
        return mask
    }

    private fun rle(width: Int, height: Int, mask: BooleanArray): BubbleMaskRle {
        val runs = mutableListOf<Int>()
        var index = 0
        while (index < mask.size) {
            if (mask[index]) {
                val start = index
                while (index < mask.size && mask[index]) index++
                runs.add(start)
                runs.add(index - start)
            } else {
                index++
            }
        }
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x]) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        return BubbleMaskRle(
            width = width,
            height = height,
            bounds = listOf(minX, minY, maxX + 1, maxY + 1),
            runs = runs,
            score = 1f,
        )
    }

    private fun rowWidth(mask: ByteArray, width: Int, y: Int): Int {
        var count = 0
        for (x in 0 until width) {
            if (mask[y * width + x] != 0.toByte()) count++
        }
        return count
    }

    /** 4-connected BFS: for masked pixels, the pixel distance to the mask boundary (edge pixel = 1). */
    private fun insideDistances(mask: ByteArray, width: Int, height: Int): IntArray {
        val distances = IntArray(width * height) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (i in mask.indices) {
            if (mask[i] == 0.toByte()) {
                distances[i] = 0
                queue.add(i)
            }
        }
        while (queue.isNotEmpty()) {
            val index = queue.poll()
            val x = index % width
            val y = index / width
            val next = distances[index] + 1
            for (direction in 0 until 4) {
                val nx = x + DX[direction]
                val ny = y + DY[direction]
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val neighbor = ny * width + nx
                if (distances[neighbor] > next) {
                    distances[neighbor] = next
                    queue.add(neighbor)
                }
            }
        }
        return distances
    }

    private class CapturedTile(
        val localMask: ByteArray,
        val crop: IntArray,
        val side: Int,
    )

    /** Bitmap mock backed by a plain pixel array so composite results are verifiable. */
    private class FakeBitmap(val width: Int, val height: Int, initialColor: Int) {
        val backing = IntArray(width * height) { initialColor }

        val mock: Bitmap = mockk()

        init {
            every { mock.width } returns width
            every { mock.height } returns height
            every { mock.copy(any(), any()) } returns mock
            every { mock.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
                val pixels = firstArg<IntArray>()
                val offset = arg<Int>(1)
                val stride = arg<Int>(2)
                val x = arg<Int>(3)
                val y = arg<Int>(4)
                val w = arg<Int>(5)
                val h = arg<Int>(6)
                for (row in 0 until h) {
                    System.arraycopy(backing, (y + row) * width + x, pixels, offset + row * stride, w)
                }
            }
            every { mock.setPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
                val pixels = firstArg<IntArray>()
                val offset = arg<Int>(1)
                val stride = arg<Int>(2)
                val x = arg<Int>(3)
                val y = arg<Int>(4)
                val w = arg<Int>(5)
                val h = arg<Int>(6)
                for (row in 0 until h) {
                    System.arraycopy(pixels, offset + row * stride, backing, (y + row) * width + x, w)
                }
            }
        }
    }

    private companion object {
        val DX = intArrayOf(1, -1, 0, 0)
        val DY = intArrayOf(0, 0, 1, -1)
    }
}
