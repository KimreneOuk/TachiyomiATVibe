package eu.kanade.translation.ocr.paddle.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddleOcrBatchPlannerTest {

    private val page = PaddleOcrPageGeneration(pageId = "chapter-7/page-12", generation = 3L)
    private val releaseCounts = linkedMapOf<String, Int>()

    @Test
    fun `ten compatible leaves partition as eight plus two at B8`() {
        val batches = collect(
            PaddleOcrBatchPlanner(page, PaddleOcrBatchSize.B8),
            (0 until 10).map { leaf(region = it, line = 0) },
        )

        assertEquals(listOf(8, 2), batches.map { it.size })
        assertEquals(listOf(PaddleOcrWidthBucket.WIDTH_640, PaddleOcrWidthBucket.WIDTH_640), batches.map { it.widthBucket })
        assertEquals((0 until 10).toList(), batches.flatMap { it.leaves }.map { it.parentRegion.index })
    }

    @Test
    fun `ten compatible leaves partition as four four two at B4`() {
        val batches = collect(
            PaddleOcrBatchPlanner(page, PaddleOcrBatchSize.B4),
            (0 until 10).map { leaf(region = it, line = 0) },
        )

        assertEquals(listOf(4, 4, 2), batches.map { it.size })
        assertEquals((0 until 10).toList(), batches.flatMap { it.leaves }.map { it.parentRegion.index })
    }

    @Test
    fun `B1 emits one independent batch for each width bucket`() {
        val batches = collect(
            PaddleOcrBatchPlanner(page, PaddleOcrBatchSize.B1),
            listOf(
                leaf(region = 0, line = 0, bucket = PaddleOcrWidthBucket.WIDTH_640),
                leaf(region = 1, line = 0, bucket = PaddleOcrWidthBucket.WIDTH_1600),
            ),
        )

        assertEquals(listOf(1, 1), batches.map { it.size })
        assertEquals(
            listOf(PaddleOcrWidthBucket.WIDTH_640, PaddleOcrWidthBucket.WIDTH_1600),
            batches.map { it.widthBucket },
        )
    }

    @Test
    fun `different width buckets never share a batch and retain bucket order`() {
        val leaves = buildList {
            repeat(5) { index ->
                add(leaf(region = index, line = 0, bucket = PaddleOcrWidthBucket.WIDTH_640))
                add(leaf(region = 100 + index, line = 0, bucket = PaddleOcrWidthBucket.WIDTH_1600))
            }
        }
        val batches = collect(PaddleOcrBatchPlanner(page, PaddleOcrBatchSize.B4), leaves)

        assertEquals(
            listOf(
                PaddleOcrWidthBucket.WIDTH_640,
                PaddleOcrWidthBucket.WIDTH_1600,
                PaddleOcrWidthBucket.WIDTH_640,
                PaddleOcrWidthBucket.WIDTH_1600,
            ),
            batches.map { it.widthBucket },
        )
        assertEquals(listOf(4, 4, 1, 1), batches.map { it.size })
        batches.forEach { batch ->
            assertTrue(batch.leaves.all { it.widthBucket == batch.widthBucket })
        }
        assertEquals(listOf(0, 1, 2, 3, 4), batches.filter { it.widthBucket == PaddleOcrWidthBucket.WIDTH_640 }
            .flatMap { it.leaves }.map { it.parentRegion.index })
        assertEquals(listOf(100, 101, 102, 103, 104), batches.filter { it.widthBucket == PaddleOcrWidthBucket.WIDTH_1600 }
            .flatMap { it.leaves }.map { it.parentRegion.index })
    }

    @Test
    fun `empty input produces no batch`() {
        val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B8)

        assertTrue(planner.finishPage().isEmpty())
        assertTrue(planner.isFinished)
        assertTrue(planner.isDrained)
    }

    @Test
    fun `final partial batch is emitted at page finish without another page`() {
        val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B8)
        val leaves = (0 until 2).map { leaf(region = it, line = 0) }

        leaves.forEach { assertNull(planner.admit(it)) }
        val partial = planner.finishPage()

        assertEquals(listOf(2), partial.map { it.size })
        assertEquals(page, partial.single().pageGeneration)
        planner.complete(partial.single(), List(2) { "ok" }) { _, _ -> }
        assertTrue(planner.isDrained)
        assertThrows(IllegalStateException::class.java) {
            planner.admit(leaf(region = 2, line = 0))
        }
    }

    @Test
    fun `vertical glyph leaves retain line glyph identity and rotation`() {
        val leaves = (0 until 3).map { glyph ->
            leaf(
                region = 7,
                line = 2,
                glyph = glyph,
                bucket = PaddleOcrWidthBucket.WIDTH_640,
                fallback = PaddleOcrFallbackKind.GLYPH,
                rotation = PaddleOcrRotation.CCW_90,
            )
        }
        val batches = collect(PaddleOcrBatchPlanner(page, PaddleOcrBatchSize.B4), leaves)

        assertEquals(listOf(0, 1, 2), batches.single().leaves.map { it.identity.glyphIndex })
        assertTrue(batches.single().leaves.all { it.rotation == PaddleOcrRotation.CCW_90 })
        assertEquals(listOf(0, 1, 2), batches.single().leaves.map { it.glyphIndex })
    }

    @Test
    fun `fallback leaves preserve final geometry order`() {
        val leaves = listOf(
            leaf(region = 0, line = 0, fallback = PaddleOcrFallbackKind.DETECTOR_LINE),
            leaf(region = 0, line = 1, fallback = PaddleOcrFallbackKind.HEURISTIC_LINE),
            leaf(region = 1, line = null, fallback = PaddleOcrFallbackKind.WHOLE_REGION),
        )
        val batches = collect(PaddleOcrBatchPlanner(page, PaddleOcrBatchSize.B4), leaves)
        val ordered = batches.single().leaves

        assertEquals(
            listOf(
                PaddleOcrFallbackKind.DETECTOR_LINE,
                PaddleOcrFallbackKind.HEURISTIC_LINE,
                PaddleOcrFallbackKind.WHOLE_REGION,
            ),
            ordered.map { it.fallbackKind },
        )
        assertEquals(listOf(0, 1, null), ordered.map { it.lineIndex })
    }

    @Test
    fun `in flight leaves are bounded to two times selected batch size`() {
        val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B4)
        val leaves = (0 until 12).map { leaf(region = it, line = 0) }
        val first = leaves.take(4).mapNotNull { planner.admit(it) }.single()
        val second = leaves.slice(4..7).mapNotNull { planner.admit(it) }.single()

        assertEquals(8, planner.maxInFlightLeavesPerBucket)
        assertEquals(8, planner.inFlightLeafCount(PaddleOcrWidthBucket.WIDTH_640))
        assertThrows(PaddleOcrBatchWindowFullException::class.java) {
            planner.admit(leaves[8])
        }

        planner.complete(first, List(first.size) { Unit }) { _, _ -> }
        assertEquals(4, planner.inFlightLeafCount(PaddleOcrWidthBucket.WIDTH_640))
        val third = leaves.slice(8..11).mapNotNull { planner.admit(it) }.single()
        assertSame(PaddleOcrWidthBucket.WIDTH_640, third.widthBucket)
        planner.complete(second, List(second.size) { Unit }) { _, _ -> }
        planner.complete(third, List(third.size) { Unit }) { _, _ -> }
        assertTrue(planner.finishPage().isEmpty())
        assertTrue(planner.isDrained)
        assertEquals(12, releaseCounts.size)
        assertTrue(releaseCounts.values.all { it == 1 })
    }

    @Test
    fun `mapping releases each crop exactly once and keeps result order`() {
        val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B4)
        val leaves = (0 until 3).map { leaf(region = it, line = 0) }
        leaves.forEach { planner.admit(it) }
        val batch = planner.finishPage().single()
        val mapped = mutableListOf<String>()

        planner.complete(batch, listOf("a", "b", "c")) { work, result ->
            mapped += "${work.parentRegion.index}:$result"
        }

        assertEquals(listOf("0:a", "1:b", "2:c"), mapped)
        leaves.forEach { leaf ->
            assertTrue(leaf.cropOwnership.isReleased)
            assertEquals(1, releaseCounts[leaf.crop])
        }
        assertTrue(batch.isComplete)
    }

    @Test
    fun `wrong page or generation is rejected before admission`() {
        val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B1)
        val wrongPage = leaf(
            page = PaddleOcrPageGeneration(pageId = "chapter-7/page-13", generation = 3L),
            region = 0,
            line = 0,
        )
        val staleGeneration = leaf(
            page = page.copy(generation = 2L),
            region = 1,
            line = 0,
        )

        assertThrows(IllegalArgumentException::class.java) { planner.admit(wrongPage) }
        assertThrows(IllegalArgumentException::class.java) { planner.admit(staleGeneration) }
        assertEquals(0, planner.inFlightLeafCount(PaddleOcrWidthBucket.WIDTH_640))

        val valid = leaf(region = 2, line = 0)
        val batch = planner.admit(valid)
        assertEquals(1, batch?.size)
        planner.complete(batch!!, listOf(Unit)) { _, _ -> }
        planner.finishPage()
    }

    @Test
    fun `duplicate leaf identity is rejected`() {
        val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B4)
        val first = leaf(region = 0, line = 0)
        val duplicate = leaf(region = 0, line = 0)

        assertNull(planner.admit(first))
        assertThrows(IllegalArgumentException::class.java) { planner.admit(duplicate) }
        planner.finishPage().single().let { planner.complete(it, listOf(Unit)) { _, _ -> } }
    }

    @Test
    fun `width alignment exposes only the 640 and 1600 contracts`() {
        assertEquals(PaddleOcrWidthBucket.WIDTH_640, PaddleOcrWidthBucket.forScaledWidth(1))
        assertEquals(PaddleOcrWidthBucket.WIDTH_640, PaddleOcrWidthBucket.forScaledWidth(640))
        assertEquals(PaddleOcrWidthBucket.WIDTH_1600, PaddleOcrWidthBucket.forScaledWidth(641))
        assertEquals(PaddleOcrWidthBucket.WIDTH_1600, PaddleOcrWidthBucket.forScaledWidth(1600))
        assertEquals(PaddleOcrWidthBucket.WIDTH_1600, PaddleOcrWidthBucket.forScaledWidth(2400))
        assertThrows(IllegalArgumentException::class.java) {
            PaddleOcrWidthBucket.forScaledWidth(0)
        }
    }

    private fun collect(
        planner: PaddleOcrBatchPlanner<String>,
        leaves: List<PaddleOcrLeafWork<String>>,
    ): List<PaddleOcrBatch<String>> {
        val batches = mutableListOf<PaddleOcrBatch<String>>()
        leaves.forEach { leaf ->
            planner.admit(leaf)?.let { batch ->
                batches += batch
                planner.complete(batch, List(batch.size) { Unit }) { _, _ -> }
            }
        }
        planner.finishPage().forEach { batch ->
            batches += batch
            planner.complete(batch, List(batch.size) { Unit }) { _, _ -> }
        }
        return batches
    }

    private fun leaf(
        page: PaddleOcrPageGeneration = this.page,
        region: Int,
        line: Int?,
        glyph: Int? = null,
        bucket: PaddleOcrWidthBucket = PaddleOcrWidthBucket.WIDTH_640,
        fallback: PaddleOcrFallbackKind = if (glyph != null) {
            PaddleOcrFallbackKind.GLYPH
        } else if (line != null) {
            PaddleOcrFallbackKind.DETECTOR_LINE
        } else {
            PaddleOcrFallbackKind.WHOLE_REGION
        },
        rotation: PaddleOcrRotation = if (glyph != null) PaddleOcrRotation.CCW_90 else PaddleOcrRotation.NONE,
    ): PaddleOcrLeafWork<String> {
        val crop = "crop-$region-${line ?: "region"}-${glyph ?: "line"}-${releaseCounts.size}"
        releaseCounts[crop] = 0
        return PaddleOcrLeafWork(
            pageGeneration = page,
            parentRegion = PaddleOcrParentRegion(region),
            lineIndex = line,
            glyphIndex = glyph,
            crop = crop,
            cropOwnership = PaddleOcrCropOwnership(crop) {
                releaseCounts[crop] = (releaseCounts[crop] ?: 0) + 1
            },
            rotation = rotation,
            fallbackKind = fallback,
            widthBucket = bucket,
        )
    }
}
