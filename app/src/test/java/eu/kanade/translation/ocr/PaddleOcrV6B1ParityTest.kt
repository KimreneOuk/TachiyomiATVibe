package eu.kanade.translation.ocr

import eu.kanade.translation.ocr.paddle.batch.PaddleOcrBatchPlanner
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrLeafWork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddleOcrV6B1ParityTest {

    @Test
    fun `detector-present fixture is exact per-crop versus B1 parity`() {
        val fixture = PaddleOcrB1FixtureFactory.detectorPresent()

        assertExactB1Parity(fixture)
        assertPageMapping(fixture)
    }

    @Test
    fun `detector-absent fixture is exact per-crop versus B1 parity`() {
        val fixture = PaddleOcrB1FixtureFactory.detectorAbsent()

        assertExactB1Parity(fixture)
        assertPageMapping(fixture)
    }

    @Test
    fun `fallback-heavy fixture is exact per-crop versus B1 parity`() {
        assertExactB1Parity(PaddleOcrB1FixtureFactory.fallbackHeavy())
    }

    @Test
    fun `empty page does not submit an ORT call or publish rows`() {
        val session = PaddleB1FakeSession(emptyList())

        val execution = executePaddleB1(
            crops = emptyList(),
            widthBucket = 640,
            session = session,
        )

        assertTrue(execution.results.isEmpty())
        assertTrue(session.calls.isEmpty())
        assertEquals(0, execution.telemetry.sessionRunCount)
    }

    private fun assertExactB1Parity(fixture: PaddleB1PageFixture) {
        val reference = fixture.leaves.associate { leaf ->
            val session = PaddleB1FakeSession(fixture.leaves.map { it.crop })
            leaf.crop.id to recognizePaddleWithConfPath(leaf.crop, session)
        }
        val batched = fixture.leaves.groupBy { it.widthBucket.paddedWidth }.flatMap { (bucket, leaves) ->
            val session = PaddleB1FakeSession(fixture.leaves.map { it.crop })
            val execution = executePaddleB1(
                crops = leaves.map { it.crop },
                widthBucket = bucket,
                session = session,
                maxBatch = 1,
            )
            assertEquals(leaves.size, session.calls.size)
            session.calls.forEach { call ->
                assertEquals(
                    listOf(1L, 3L, PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT.toLong(), bucket.toLong()),
                    call.inputShape.toList(),
                )
                assertEquals(listOf(1L, 4L, PADDLE_B1_FAKE_CLASS_COUNT.toLong()), call.outputShape.toList())
            }
            leaves.zip(execution.results).map { (leaf, result) -> leaf.crop.id to result }
        }.toMap()

        assertEquals(fixture.expectedRows.size, reference.size)
        assertEquals(reference.keys.toList(), fixture.leaves.map { it.crop.id })
        fixture.leaves.forEach { leaf ->
            val expected = reference.getValue(leaf.crop.id)
            val actual = batched.getValue(leaf.crop.id)
            assertEquals(expected.value.first, actual.first, leaf.identityLabel())
            assertEquals(expected.value.second.toRawBits(), actual.second.toRawBits(), leaf.identityLabel())
            assertEquals(expected.outputShape.toList(), listOf(1L, 4L, PADDLE_B1_FAKE_CLASS_COUNT.toLong()), leaf.identityLabel())
            assertEquals(leaf.crop.payload.expected.first, actual.first, leaf.identityLabel())
            assertEquals(leaf.crop.payload.expected.second.toRawBits(), actual.second.toRawBits(), leaf.identityLabel())
        }
    }

    private fun assertPageMapping(fixture: PaddleB1PageFixture) {
        val planner = PaddleOcrBatchPlanner<PaddleB1Crop>(fixture.page, PaddleOcrBatchSize.B4)
        val mapped = linkedMapOf<String, MappedLeaf>()
        val specsByCrop = fixture.specs.associateBy { it.crop.id }
        fun execute(batch: eu.kanade.translation.ocr.paddle.batch.PaddleOcrBatch<PaddleB1Crop>) {
            val session = PaddleB1FakeSession(fixture.leaves.map { it.crop })
            val execution = executePaddleB1(
                crops = batch.leaves.map { it.crop },
                widthBucket = batch.widthBucket.paddedWidth,
                session = session,
                maxBatch = PaddleOcrBatchSize.B4.value,
            )
            planner.complete(batch, execution.results) { leaf, result ->
                check(mapped.put(leaf.crop.id, MappedLeaf(leaf, result, specsByCrop.getValue(leaf.crop.id).stage)) == null) {
                    "duplicate result for ${leaf.identity}"
                }
            }
        }

        fixture.leaves.forEach { leaf -> planner.admit(leaf)?.let(::execute) }
        planner.finishPage().forEach(::execute)

        assertTrue(planner.isDrained)
        assertEquals(fixture.leaves.size, mapped.size)
        assertEquals(5, fixture.expectedRegionCount)
        assertEquals(5, mapped.values.map { it.leaf.parentRegion }.distinct().size)
        assertEquals(fixture.filteredRegionIndexes, listOf(3))
        assertTrue(mapped.values.none { it.leaf.parentRegion.index in fixture.filteredRegionIndexes })

        val ordered = fixture.leaves.map { mapped.getValue(it.crop.id) }
        assertEquals(fixture.leaves.map { it.identity }, ordered.map { it.leaf.identity })
        assertEquals(fixture.leaves.map { it.rotation }, ordered.map { it.leaf.rotation })
        assertEquals(fixture.leaves.map { it.parentRegion }, ordered.map { it.leaf.parentRegion })
        assertEquals(fixture.leaves.map { it.fallbackKind }, ordered.map { it.leaf.fallbackKind })
        assertEquals(fixture.specs.map { it.stage }, ordered.map { it.stage })
        ordered.forEach { mappedLeaf ->
            assertEquals(mappedLeaf.leaf.crop.payload.expected.first, mappedLeaf.result.first, mappedLeaf.leaf.identityLabel())
            assertEquals(
                mappedLeaf.leaf.crop.payload.expected.second.toRawBits(),
                mappedLeaf.result.second.toRawBits(),
                mappedLeaf.leaf.identityLabel(),
            )
        }
        fixture.assertOwnershipReleased()
    }

    private data class MappedLeaf(
        val leaf: PaddleOcrLeafWork<PaddleB1Crop>,
        val result: Pair<String, Float>,
        val stage: String,
    )

    private fun PaddleOcrLeafWork<PaddleB1Crop>.identityLabel(): String =
        "page=${pageGeneration.pageId} region=${parentRegion.index} leaf=${crop.id} stage=${fallbackKind.name}"

    private companion object {
        val PADDLE_B1_FAKE_CLASS_COUNT = PADDLE_B1_DICTIONARY.size + 2
    }
}
