package eu.kanade.translation.ocr

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException

class PaddleOcrV6BatchExecutorTest {

    @Test
    fun `B1 B4 and B8 make one call per microbatch including final partial batch`() {
        val crops = (0 until 10).toList()
        for ((requested, expectedCalls) in listOf(
            1 to listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            4 to listOf(4, 4, 2),
            8 to listOf(8, 2),
        )) {
            val session = FakeSession()
            val execution = executor(session).execute(
                crops = crops,
                widthBucket = 640,
                maxBatch = requested,
                writeSample = ::writeMarker,
            )

            session.calls.map { it.shape[0].toInt() } shouldContainExactly expectedCalls
            execution.telemetry.actualBatchSizes shouldContainExactly expectedCalls
            execution.telemetry.sessionRunCount shouldBe expectedCalls.size
            execution.results.map { it.first } shouldContainExactly crops.map { marker ->
                when (marker % 3) {
                    0 -> "a"
                    1 -> "b"
                    else -> "c"
                }
            }
        }
    }

    @Test
    fun `provider failure downgrades B8 to B4 to B1 without changing row order`() {
        val session = FakeSession(failuresByBatch = mutableMapOf(8 to 1, 4 to 1))
        val crops = (0 until 8).toList()
        val execution = executor(session).execute(
            crops = crops,
            widthBucket = 640,
            maxBatch = 8,
            writeSample = ::writeMarker,
        )

        session.calls.map { it.shape[0].toInt() } shouldContainExactly
            listOf(8, 4, 1, 1, 1, 1, 1, 1, 1, 1)
        execution.telemetry.sessionRunCount shouldBe 10
        execution.telemetry.actualBatchSizes shouldContainExactly listOf(
            8,
            4,
            1, 1, 1, 1, 1, 1, 1, 1,
        )
        execution.telemetry.downgradeReasons shouldContainExactly listOf(
            "provider_failure",
            "provider_failure",
        )
        execution.results.map { it.first } shouldContainExactly listOf(
            "a", "b", "c", "a", "b", "c", "a", "b",
        )
    }

    @Test
    fun `failed final run releases input and output leases`() {
        val session = FakeSession(failCopy = true)
        val pool = newPool()
        val executor = PaddleOcrV6BatchExecutor(
            session = session,
            bufferPool = pool,
            dictionary = DICTIONARY,
        )

        assertThrows<PaddleOcrV6BatchExecutionException> {
            executor.execute(
                crops = listOf(0),
                widthBucket = 640,
                maxBatch = 1,
                writeSample = ::writeMarker,
            )
        }

        val snapshot = pool.snapshot()
        snapshot.activeInputBuffers shouldBe 0
        snapshot.activeOutputBuffers shouldBe 0
        snapshot.retainedInputBuffers shouldBe 1
        session.calls.size shouldBe 1
    }

    @Test
    fun `lease release is idempotent for cancellation-style cleanup`() {
        val pool = newPool()
        val lease = pool.acquireInput(batchSize = 1, widthBucket = 640)
        lease.close()
        lease.close()

        val snapshot = pool.snapshot()
        snapshot.activeInputBuffers shouldBe 0
        snapshot.retainedInputBuffers shouldBe 1
    }

    @Test
    fun `cancellation propagates and releases the in-flight input lease`() {
        val session = FakeSession(cancelOnRun = true)
        val pool = newPool()
        val executor = PaddleOcrV6BatchExecutor(
            session = session,
            bufferPool = pool,
            dictionary = DICTIONARY,
        )

        assertThrows<CancellationException> {
            executor.execute(
                crops = listOf(0),
                widthBucket = 640,
                maxBatch = 1,
                writeSample = ::writeMarker,
            )
        }
        pool.snapshot().activeInputBuffers shouldBe 0
    }

    @Test
    fun `input allocation failure downgrades B8 to B4 and reports the failure`() {
        val b4InputElements = 4 * 3 * 48 * 640
        val allocator = PaddleOcrV6BatchBufferPool.FloatBufferAllocator { capacity ->
            if (capacity > b4InputElements && capacity < 1_000_000) {
                throw OutOfMemoryError("test B8 input allocation")
            }
            FloatBuffer.allocate(capacity)
        }
        val session = FakeSession()
        val pool = PaddleOcrV6BatchBufferPool(
            maxBatchSize = 8,
            maxWidth = 1600,
            dictionarySize = DICTIONARY.size,
            allocator = allocator,
        )
        val execution = PaddleOcrV6BatchExecutor(
            session = session,
            bufferPool = pool,
            dictionary = DICTIONARY,
        ).execute(
            crops = (0 until 8).toList(),
            widthBucket = 640,
            maxBatch = 8,
            writeSample = ::writeMarker,
        )

        session.calls.map { it.shape[0].toInt() } shouldContainExactly listOf(4, 4)
        execution.telemetry.allocationFailures shouldBe 1
        execution.telemetry.downgradeReason shouldBe "allocation_failure"
    }

    @Test
    fun `latency failure retries the same rows at a smaller batch`() {
        var now = 0L
        val session = FakeSession(onRun = { batch ->
            now += if (batch == 8) 100_000_000L else 1_000_000L
        })
        val execution = PaddleOcrV6BatchExecutor(
            session = session,
            bufferPool = newPool(),
            dictionary = DICTIONARY,
            latencyBudgetMs = 50.0,
            clockNanos = { now },
        ).execute(
            crops = (0 until 8).toList(),
            widthBucket = 640,
            maxBatch = 8,
            writeSample = ::writeMarker,
        )

        session.calls.map { it.shape[0].toInt() } shouldContainExactly listOf(8, 4, 4)
        execution.telemetry.actualBatchSizes shouldContainExactly listOf(8, 4, 4)
        execution.telemetry.downgradeReason shouldBe "latency_failure"
        execution.results.size shouldBe 8
    }

    @Test
    fun `strict provider mode rejects CPU before the fake session can run`() {
        val session = FakeSession(providerLabel = "cpu")
        val executor = PaddleOcrV6BatchExecutor(
            session = session,
            bufferPool = newPool(),
            dictionary = DICTIONARY,
            strictProviderMode = true,
        )

        assertThrows<PaddleOcrStrictProviderException> {
            executor.execute(
                crops = listOf(0),
                widthBucket = 640,
                maxBatch = 1,
                writeSample = ::writeMarker,
            )
        }
        session.calls.size shouldBe 0
    }

    @Test
    fun `output bytes use actual B T C shape and pooled leases are released`() {
        val session = FakeSession(timeSteps = 80, classCount = 7)
        val pool = PaddleOcrV6BatchBufferPool(
            maxBatchSize = 8,
            maxWidth = 1600,
            dictionarySize = 5,
            allocator = PaddleOcrV6BatchBufferPool.FloatBufferAllocator { capacity ->
                FloatBuffer.allocate(capacity)
            },
        )
        val executor = PaddleOcrV6BatchExecutor(
            session = session,
            bufferPool = pool,
            dictionary = List(5) { it.toString() },
        )
        executor.execute(
            crops = (0 until 4).toList(),
            widthBucket = 640,
            maxBatch = 4,
            writeSample = ::writeMarker,
        )
        executor.execute(
            crops = (0 until 4).toList(),
            widthBucket = 640,
            maxBatch = 4,
            writeSample = ::writeMarker,
        )

        val snapshot = pool.snapshot()
        snapshot.inputAllocations shouldBe 1
        snapshot.outputAllocations shouldBe 1
        snapshot.activeInputBuffers shouldBe 0
        snapshot.activeOutputBuffers shouldBe 0
        snapshot.maxOutputBytes shouldBe 8L * 200L * 7L * 4L
        session.lastTelemetryOutputBytes shouldBe 4L * 80L * 7L * 4L
    }

    private fun executor(session: FakeSession): PaddleOcrV6BatchExecutor =
        PaddleOcrV6BatchExecutor(
            session = session,
            bufferPool = newPool(),
            dictionary = DICTIONARY,
        )

    private fun newPool(): PaddleOcrV6BatchBufferPool = PaddleOcrV6BatchBufferPool(
        maxBatchSize = 8,
        maxWidth = 1600,
        dictionarySize = DICTIONARY.size,
        allocator = PaddleOcrV6BatchBufferPool.FloatBufferAllocator { capacity ->
            FloatBuffer.allocate(capacity)
        },
    )

    private fun writeMarker(
        crop: Int,
        destination: FloatBuffer,
        baseOffset: Int,
        @Suppress("UNUSED_PARAMETER") widthBucket: Int,
    ) {
        destination.put(baseOffset, crop.toFloat())
    }

    private class FakeSession(
        override val providerLabel: String = "qnn_htp",
        private val failuresByBatch: MutableMap<Int, Int> = mutableMapOf(),
        private val onRun: (batchSize: Int) -> Unit = {},
        private val timeSteps: Int = 2,
        private val classCount: Int = DICTIONARY.size + 2,
        private val failCopy: Boolean = false,
        private val cancelOnRun: Boolean = false,
    ) : PaddleOcrV6BatchSession {
        val calls = ArrayList<Call>()
        var lastTelemetryOutputBytes = 0L

        override fun run(input: FloatBuffer, shape: LongArray): PaddleOcrV6BatchOutput {
            val batchSize = shape[0].toInt()
            calls += Call(shape.copyOf())
            val remainingFailures = failuresByBatch[batchSize] ?: 0
            if (remainingFailures > 0) {
                failuresByBatch[batchSize] = remainingFailures - 1
                throw IllegalStateException("fake provider failure for B$batchSize")
            }
            if (cancelOnRun) throw CancellationException("fake cancellation")
            onRun(batchSize)
            val logits = FloatArray(batchSize * timeSteps * classCount)
            for (batchIndex in 0 until batchSize) {
                val marker = input.get(batchIndex * 3 * 48 * shape[3].toInt()).toInt()
                val classIndex = 1 + marker.mod(3)
                logits[batchIndex * timeSteps * classCount + classIndex] = 1f
            }
            val outputShape = longArrayOf(batchSize.toLong(), timeSteps.toLong(), classCount.toLong())
            lastTelemetryOutputBytes = logits.size.toLong() * Float.SIZE_BYTES
            return FakeOutput(outputShape, FloatBuffer.wrap(logits), failCopy)
        }
    }

    private data class Call(val shape: LongArray)

    private class FakeOutput(
        override val shape: LongArray,
        private val source: FloatBuffer,
        private val failCopy: Boolean,
    ) : PaddleOcrV6BatchOutput {
        override fun copyTo(destination: FloatBuffer) {
            if (failCopy) throw IllegalStateException("fake output copy failure")
            val copy = source.duplicate()
            copy.clear()
            destination.clear()
            destination.put(copy)
            destination.flip()
        }

        override fun close() = Unit
    }

    private companion object {
        val DICTIONARY = listOf("a", "b", "c")
    }
}
