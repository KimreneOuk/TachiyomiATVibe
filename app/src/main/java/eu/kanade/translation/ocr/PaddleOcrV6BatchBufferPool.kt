package eu.kanade.translation.ocr

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * Bounded direct-buffer leases for Paddle recognition microbatches.
 *
 * Input buffers are sized for the requested [batchSize]/[widthBucket]. Output
 * buffers are sized from the actual [B,T,C] row count returned by ORT and are
 * retained for reuse after decoding. The pool deliberately has no page or
 * planner state; one executor owns a pool for one recognizer session.
 */
internal class PaddleOcrV6BatchBufferPool(
    private val maxBatchSize: Int,
    private val maxWidth: Int,
    dictionarySize: Int,
    private val maxInputBuffers: Int = 2,
    private val maxOutputBuffers: Int = 1,
    private val allocator: FloatBufferAllocator = FloatBufferAllocator { capacity ->
        ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    },
) {

    private val maxOutputElements: Long =
        maxBatchSize.toLong() * (maxWidth / OUTPUT_TIME_STEP_STRIDE).toLong() *
            (dictionarySize + OUTPUT_EXTRA_CLASSES).toLong()

    private val availableInputs = ArrayDeque<FloatBuffer>()
    private val availableOutputs = ArrayDeque<FloatBuffer>()
    private var createdInputs = 0
    private var createdOutputs = 0
    private var activeInputs = 0
    private var activeOutputs = 0
    private var allocationFailures = 0
    private var inputAllocationCount = 0
    private var outputAllocationCount = 0

    @Synchronized
    fun acquireInput(batchSize: Int, widthBucket: Int): Lease {
        require(batchSize in 1..maxBatchSize) { "batchSize=$batchSize max=$maxBatchSize" }
        require(widthBucket in 1..maxWidth) { "widthBucket=$widthBucket max=$maxWidth" }
        val requiredElements = batchSize.toLong() * CHANNELS * HEIGHT * widthBucket
        val capacity = requiredElements.toIntOrThrow("input")
        val buffer = findAvailable(availableInputs, capacity)
            ?: run {
                discardAvailableInputsIfIdle()
                allocateInput(capacity)
            }
        activeInputs++
        buffer.clear()
        buffer.limit(capacity)
        return Lease(buffer) {
            releaseInput(buffer)
        }
    }

    @Synchronized
    fun acquireOutput(elements: Long): Lease {
        require(elements > 0) { "output elements must be positive" }
        if (elements > maxOutputElements) {
            throw allocationFailure(
                kind = BufferKind.OUTPUT,
                requestedElements = elements,
                cause = IllegalArgumentException(
                    "output elements=$elements exceed bounded capacity=$maxOutputElements",
                ),
            )
        }
        val capacity = elements.toIntOrThrow("output")
        val buffer = findAvailable(availableOutputs, capacity)
            ?: run {
                discardAvailableOutputsIfIdle()
                allocateOutput(capacity)
            }
        activeOutputs++
        buffer.clear()
        buffer.limit(capacity)
        return Lease(buffer) {
            releaseOutput(buffer)
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        inputAllocations = inputAllocationCount,
        outputAllocations = outputAllocationCount,
        allocationFailures = allocationFailures,
        retainedInputBuffers = availableInputs.size,
        retainedOutputBuffers = availableOutputs.size,
        activeInputBuffers = activeInputs,
        activeOutputBuffers = activeOutputs,
        maxOutputBytes = maxOutputElements * Float.SIZE_BYTES,
    )

    @Synchronized
    fun clear() {
        availableInputs.clear()
        availableOutputs.clear()
        createdInputs = activeInputs
        createdOutputs = activeOutputs
    }

    private fun allocateInput(capacity: Int): FloatBuffer {
        if (createdInputs >= maxInputBuffers) {
            throw allocationFailure(
                kind = BufferKind.INPUT,
                requestedElements = capacity.toLong(),
                cause = IllegalStateException("input buffer limit reached: $maxInputBuffers"),
            )
        }
        return try {
            allocator.allocate(capacity).also {
                require(it.capacity() >= capacity) {
                    "allocator returned capacity=${it.capacity()} requested=$capacity"
                }
                createdInputs++
                inputAllocationCount++
            }
        } catch (error: Throwable) {
            throw allocationFailure(BufferKind.INPUT, capacity.toLong(), error)
        }
    }

    private fun allocateOutput(capacity: Int): FloatBuffer {
        if (createdOutputs >= maxOutputBuffers) {
            throw allocationFailure(
                kind = BufferKind.OUTPUT,
                requestedElements = capacity.toLong(),
                cause = IllegalStateException("output buffer limit reached: $maxOutputBuffers"),
            )
        }
        return try {
            allocator.allocate(capacity).also {
                require(it.capacity() >= capacity) {
                    "allocator returned capacity=${it.capacity()} requested=$capacity"
                }
                createdOutputs++
                outputAllocationCount++
            }
        } catch (error: Throwable) {
            throw allocationFailure(BufferKind.OUTPUT, capacity.toLong(), error)
        }
    }

    private fun releaseInput(buffer: FloatBuffer) {
        synchronized(this) {
            if (activeInputs > 0) activeInputs--
            buffer.clear()
            if (availableInputs.size < maxInputBuffers) {
                availableInputs.addLast(buffer)
            } else {
                createdInputs = (createdInputs - 1).coerceAtLeast(activeInputs)
            }
        }
    }

    private fun releaseOutput(buffer: FloatBuffer) {
        synchronized(this) {
            if (activeOutputs > 0) activeOutputs--
            buffer.clear()
            if (availableOutputs.size < maxOutputBuffers) {
                availableOutputs.addLast(buffer)
            } else {
                createdOutputs = (createdOutputs - 1).coerceAtLeast(activeOutputs)
            }
        }
    }

    private fun discardAvailableInputsIfIdle() {
        if (activeInputs == 0 && availableInputs.isNotEmpty()) {
            availableInputs.clear()
            createdInputs = 0
        }
    }

    private fun discardAvailableOutputsIfIdle() {
        if (activeOutputs == 0 && availableOutputs.isNotEmpty()) {
            availableOutputs.clear()
            createdOutputs = 0
        }
    }

    private fun findAvailable(queue: ArrayDeque<FloatBuffer>, required: Int): FloatBuffer? {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.capacity() >= required) {
                iterator.remove()
                return candidate
            }
        }
        return null
    }

    private fun allocationFailure(
        kind: BufferKind,
        requestedElements: Long,
        cause: Throwable,
    ): BufferAllocationException {
        allocationFailures++
        return BufferAllocationException(kind, requestedElements, cause)
    }

    private fun Long.toIntOrThrow(kind: String): Int {
        require(this <= Int.MAX_VALUE) { "$kind buffer is too large: $this floats" }
        return toInt()
    }

    enum class BufferKind {
        INPUT,
        OUTPUT,
    }

    class BufferAllocationException(
        val kind: BufferKind,
        val requestedElements: Long,
        cause: Throwable,
    ) : RuntimeException(
        "Unable to allocate ${kind.name.lowercase()} buffer for $requestedElements floats",
        cause,
    )

    fun interface FloatBufferAllocator {
        fun allocate(capacity: Int): FloatBuffer
    }

    class Lease internal constructor(
        val buffer: FloatBuffer,
        private val releaseBlock: () -> Unit,
    ) : Closeable {
        private var released = false

        override fun close() {
            if (!released) {
                released = true
                releaseBlock()
            }
        }
    }

    data class Snapshot(
        val inputAllocations: Int,
        val outputAllocations: Int,
        val allocationFailures: Int,
        val retainedInputBuffers: Int,
        val retainedOutputBuffers: Int,
        val activeInputBuffers: Int,
        val activeOutputBuffers: Int,
        val maxOutputBytes: Long,
    )

    private companion object {
        const val CHANNELS = 3L
        const val HEIGHT = 48L

        // The checked-in PP-OCRv6 small model emits width / 8 CTC steps.
        const val OUTPUT_TIME_STEP_STRIDE = 8

        // CTC blank plus the dictionary's explicit space token.
        const val OUTPUT_EXTRA_CLASSES = 2
    }
}
