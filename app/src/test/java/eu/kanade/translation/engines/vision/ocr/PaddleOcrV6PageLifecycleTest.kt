package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatch
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchPlanner
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrLeafWork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException

class PaddleOcrV6PageLifecycleTest {

    @Test
    fun `cancellation before submission publishes no partial checkpoint and retries deterministically`() {
        assertCancelledAndRetried(FaultPoint.BEFORE_SUBMISSION)
    }

    @Test
    fun `cancellation during ORT execution publishes no partial checkpoint and retries deterministically`() {
        assertCancelledAndRetried(FaultPoint.DURING_ORT)
    }

    @Test
    fun `cancellation after execution publishes no partial checkpoint and retries deterministically`() {
        assertCancelledAndRetried(FaultPoint.AFTER_EXECUTION)
    }

    @Test
    fun `cancellation during result mapping publishes no partial checkpoint and retries deterministically`() {
        assertCancelledAndRetried(FaultPoint.DURING_MAPPING)
    }

    @Test
    fun `provider failure downgrades a page from B8 to B4 to B1 without reordering`() {
        val fixture = PaddleOcrB1FixtureFactory.uniformEight()
        val run = PageRun(fixture, PaddleOcrBatchSize.B8, FaultPoint.PROVIDER_DOWNGRADE)
        val outcome = run.execute()

        assertNull(outcome.error)
        assertNotNull(outcome.checkpoint)
        assertEquals(
            listOf(8, 4, 1, 1, 1, 1, 1, 1, 1, 1),
            run.session.calls.map { it.inputShape[0].toInt() },
        )
        assertEquals(
            listOf("provider_failure", "provider_failure"),
            run.telemetry.single().downgradeReasons,
        )
        assertEquals(fixture.expectedRows, outcome.checkpoint!!.map { it.result })
        assertEquals(fixture.leaves.map { it.identity }, outcome.checkpoint!!.map { it.leaf.identity })
        fixture.assertOwnershipReleased()
    }

    @Test
    fun `terminal provider failure publishes nothing and retry resumes the same generation`() {
        assertFailedAndRetried(FaultPoint.PROVIDER_TERMINAL)
    }

    @Test
    fun `terminal allocation failure publishes nothing and retry resumes the same generation`() {
        assertFailedAndRetried(FaultPoint.ALLOCATION_FAILURE)
    }

    @Test
    fun `old-generation batch cannot publish into a new page generation`() {
        val fixture = PaddleOcrB1FixtureFactory.uniformEight()
        val oldPlanner = PaddleOcrBatchPlanner<PaddleB1Crop>(fixture.page, PaddleOcrBatchSize.B1)
        val newPlanner = PaddleOcrBatchPlanner<PaddleB1Crop>(fixture.page.copy(generation = fixture.page.generation + 1), PaddleOcrBatchSize.B1)
        val oldBatch = oldPlanner.admit(fixture.leaves.first())!!

        assertThrows(IllegalArgumentException::class.java) {
            newPlanner.complete(oldBatch, listOf("stale")) { _, _ -> }
        }
        assertFalse(newPlanner.isDrained)
        assertEquals(0, newPlanner.activeBatchCount)

        oldPlanner.cancel()
        newPlanner.cancel()
        fixture.leaves.drop(1).forEach { it.releaseCropOwnership() }
        fixture.assertOwnershipReleased()
    }

    private fun assertCancelledAndRetried(fault: FaultPoint) {
        val failedFixture = PaddleOcrB1FixtureFactory.fallbackHeavy()
        val failed = PageRun(failedFixture, PaddleOcrBatchSize.B4, fault).execute()

        assertTrue(failed.error is CancellationException, "fault=$fault error=${failed.error}")
        assertNull(failed.checkpoint)
        failedFixture.assertOwnershipReleased()

        val retryFixture = PaddleOcrB1FixtureFactory.fallbackHeavy()
        val retried = PageRun(retryFixture, PaddleOcrBatchSize.B4, FaultPoint.NONE).execute()

        assertNull(retried.error)
        assertNotNull(retried.checkpoint)
        assertEquals(retryFixture.expectedRows, retried.checkpoint!!.map { it.result })
        assertEquals(retryFixture.leaves.map { it.identity }, retried.checkpoint!!.map { it.leaf.identity })
        retryFixture.assertOwnershipReleased()
    }

    private fun assertFailedAndRetried(fault: FaultPoint) {
        val failedFixture = PaddleOcrB1FixtureFactory.uniformEight()
        val failed = PageRun(failedFixture, PaddleOcrBatchSize.B8, fault).execute()

        assertTrue(failed.error is PaddleOcrV6BatchExecutionException, "fault=$fault error=${failed.error}")
        assertNull(failed.checkpoint)
        failedFixture.assertOwnershipReleased()
        assertTrue(failedFixture.leaves.all { it.cropOwnership.isReleased })

        val retryFixture = PaddleOcrB1FixtureFactory.uniformEight()
        val retried = PageRun(retryFixture, PaddleOcrBatchSize.B8, FaultPoint.NONE).execute()

        assertNull(retried.error)
        assertNotNull(retried.checkpoint)
        assertEquals(retryFixture.expectedRows, retried.checkpoint!!.map { it.result })
        assertEquals(retryFixture.leaves.map { it.identity }, retried.checkpoint!!.map { it.leaf.identity })
        retryFixture.assertOwnershipReleased()
    }

    private enum class FaultPoint {
        NONE,
        BEFORE_SUBMISSION,
        DURING_ORT,
        AFTER_EXECUTION,
        DURING_MAPPING,
        PROVIDER_DOWNGRADE,
        PROVIDER_TERMINAL,
        ALLOCATION_FAILURE,
    }

    private data class PageRunOutcome(
        val checkpoint: List<MappedLeaf>?,
        val error: Throwable?,
    )

    private class PageRun(
        private val fixture: PaddleB1PageFixture,
        private val batchSize: PaddleOcrBatchSize,
        private val fault: FaultPoint,
    ) {
        val session = PaddleB1FakeSession(
            crops = fixture.leaves.map { it.crop },
            failuresByBatch = when (fault) {
                FaultPoint.PROVIDER_DOWNGRADE -> mutableMapOf(8 to 1, 4 to 1)
                FaultPoint.PROVIDER_TERMINAL -> mutableMapOf(8 to 100, 4 to 100, 1 to 100)
                else -> mutableMapOf()
            },
            cancelOnMarker = if (fault == FaultPoint.DURING_ORT) targetCropId().marker else null,
            cancelAfterExecutionMarker = if (fault == FaultPoint.AFTER_EXECUTION) targetCropId().marker else null,
        )
        val telemetry = mutableListOf<PaddleOcrV6BatchTelemetry>()

        private val pool = paddleB1Pool(
            allocator = PaddleOcrV6BatchBufferPool.FloatBufferAllocator { capacity ->
                if (fault == FaultPoint.ALLOCATION_FAILURE) {
                    throw OutOfMemoryError("injected allocation failure")
                }
                FloatBuffer.allocate(capacity)
            },
        )

        fun execute(): PageRunOutcome {
            val planner = PaddleOcrBatchPlanner<PaddleB1Crop>(fixture.page, batchSize)
            val mapped = linkedMapOf<String, MappedLeaf>()
            val admitted = linkedSetOf<String>()
            return try {
                fixture.leaves.forEach { leaf ->
                    admitted += leaf.crop.id
                    planner.admit(leaf)?.let { batch -> submit(planner, batch, mapped) }
                }
                planner.finishPage().forEach { batch -> submit(planner, batch, mapped) }
                check(planner.isDrained) { "page planner did not drain" }
                val checkpoint = fixture.leaves.map { mapped.getValue(it.crop.id) }
                PageRunOutcome(checkpoint, null)
            } catch (error: Throwable) {
                planner.cancel()
                fixture.leaves.filter { it.crop.id !in admitted }.forEach { it.releaseCropOwnership() }
                PageRunOutcome(null, error)
            }
        }

        private fun submit(
            planner: PaddleOcrBatchPlanner<PaddleB1Crop>,
            batch: PaddleOcrBatch<PaddleB1Crop>,
            mapped: MutableMap<String, MappedLeaf>,
        ) {
            val execution = paddleB1Executor(session, pool).execute(
                crops = batch.leaves.map { it.crop },
                widthBucket = batch.widthBucket.paddedWidth,
                maxBatch = batchSize.value,
                writeSample = { crop, destination, baseOffset, widthBucket ->
                    if (fault == FaultPoint.BEFORE_SUBMISSION && crop.id == targetCropId().id) {
                        throw CancellationException("injected cancellation before submission")
                    }
                    writePaddleB1Marker(crop, destination, baseOffset, widthBucket)
                },
            )
            telemetry += execution.telemetry
            planner.complete(batch, execution.results) { leaf, result ->
                if (fault == FaultPoint.DURING_MAPPING && leaf.crop.id == targetCropId().id) {
                    throw CancellationException("injected cancellation during result mapping")
                }
                check(mapped.put(leaf.crop.id, MappedLeaf(leaf, result)) == null) {
                    "duplicate result for ${leaf.identity}"
                }
            }
        }

        private fun targetCropId(): PaddleB1Crop = fixture.leaves.first().crop
    }

    private data class MappedLeaf(
        val leaf: PaddleOcrLeafWork<PaddleB1Crop>,
        val result: Pair<String, Float>,
    )
}
