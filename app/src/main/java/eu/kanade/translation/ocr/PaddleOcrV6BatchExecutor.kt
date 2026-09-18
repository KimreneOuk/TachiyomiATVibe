package eu.kanade.translation.ocr

import java.nio.FloatBuffer
import java.util.concurrent.CancellationException

/**
 * Executes one recognizer's batches synchronously. The owning engine must
 * serialize calls; the ORT session, buffer leases, and telemetry snapshot are
 * intentionally confined to that caller rather than shared across threads.
 */
internal class PaddleOcrV6BatchExecutor(
    private val session: PaddleOcrV6BatchSession,
    private val bufferPool: PaddleOcrV6BatchBufferPool,
    private val dictionary: List<String>,
    private val latencyBudgetMs: Double? = null,
    private val strictProviderMode: Boolean = false,
    private val clockNanos: () -> Long = System::nanoTime,
) {

    fun <Crop> execute(
        crops: List<Crop>,
        widthBucket: Int,
        maxBatch: Int,
        writeSample: (crop: Crop, destination: FloatBuffer, baseOffset: Int, widthBucket: Int) -> Unit,
    ): PaddleOcrV6BatchExecution {
        if (strictProviderMode && session.providerLabel.isCpuLike()) {
            throw PaddleOcrStrictProviderException(session.providerLabel)
        }
        require(widthBucket == PaddleOcrV6SmallEngine.BUCKET_WIDTH_SMALL ||
            widthBucket == PaddleOcrV6SmallEngine.MAX_RECOGNITION_WIDTH) {
            "Paddle OCR only supports width buckets 640 and 1600, got $widthBucket"
        }
        if (crops.isEmpty()) {
            return PaddleOcrV6BatchExecution(
                results = emptyList(),
                telemetry = telemetry(
                    requestedBatchSize = normalizeBatch(maxBatch),
                    actualBatchSizes = emptyList(),
                    sessionRunCount = 0,
                    provider = session.providerLabel,
                    startedAt = clockNanos(),
                    allocationFailures = 0,
                    downgradeReasons = emptyList(),
                    inputBytes = 0,
                    peakInputBytes = 0,
                    outputBytes = 0,
                    peakOutputBytes = 0,
                ),
            )
        }

        val requestedBatchSize = normalizeBatch(maxBatch)
        val startedAt = clockNanos()
        var currentBatchSize = requestedBatchSize
        var cropOffset = 0
        var sessionRunCount = 0
        var allocationFailures = 0
        var totalInputBytes = 0L
        var peakInputBytes = 0L
        var totalOutputBytes = 0L
        var peakOutputBytes = 0L
        val actualBatchSizes = ArrayList<Int>()
        val downgradeReasons = ArrayList<String>()
        val results = ArrayList<Pair<String, Float>>(crops.size)
        val sampleElements = 3 * PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT * widthBucket

        while (cropOffset < crops.size) {
            val actualBatch = minOf(currentBatchSize, crops.size - cropOffset)
            try {
                val attempt = runAttempt(
                    crops = crops,
                    cropOffset = cropOffset,
                    actualBatch = actualBatch,
                    widthBucket = widthBucket,
                    sampleElements = sampleElements,
                    writeSample = writeSample,
                    sessionRunCount = { sessionRunCount++ },
                )
                totalInputBytes += attempt.inputBytes
                peakInputBytes = maxOf(peakInputBytes, attempt.inputBytes)
                totalOutputBytes += attempt.outputBytes
                peakOutputBytes = maxOf(peakOutputBytes, attempt.outputBytes)
                actualBatchSizes += actualBatch

                val overBudget = latencyBudgetMs != null && attempt.latencyMs > latencyBudgetMs
                if (overBudget && currentBatchSize > MIN_BATCH_SIZE) {
                    downgradeReasons += PaddleOcrV6BatchDowngradeReason.LATENCY_FAILURE.wireValue
                    currentBatchSize = lowerBatch(currentBatchSize)
                    continue
                }

                results += attempt.results
                cropOffset += actualBatch
            } catch (failure: PaddleOcrV6BatchFailure) {
                sessionRunCount = maxOf(sessionRunCount, failure.sessionRunCount)
                totalInputBytes += failure.inputBytes
                peakInputBytes = maxOf(peakInputBytes, failure.inputBytes)
                totalOutputBytes += failure.outputBytes
                peakOutputBytes = maxOf(peakOutputBytes, failure.outputBytes)
                allocationFailures += failure.allocationFailures
                failure.actualBatchSize?.let { actualBatchSizes += it }
                downgradeReasons += failure.reason.wireValue
                if (currentBatchSize == MIN_BATCH_SIZE) {
                    throw failure.toExecutionException(
                        telemetry = telemetry(
                            requestedBatchSize = requestedBatchSize,
                            actualBatchSizes = actualBatchSizes,
                            sessionRunCount = sessionRunCount,
                            provider = session.providerLabel,
                            startedAt = startedAt,
                            allocationFailures = allocationFailures,
                            downgradeReasons = downgradeReasons,
                            inputBytes = totalInputBytes,
                            peakInputBytes = peakInputBytes,
                            outputBytes = totalOutputBytes,
                            peakOutputBytes = peakOutputBytes,
                        ),
                    )
                }
                currentBatchSize = lowerBatch(currentBatchSize)
            }
        }

        return PaddleOcrV6BatchExecution(
            results = results,
            telemetry = telemetry(
                requestedBatchSize = requestedBatchSize,
                actualBatchSizes = actualBatchSizes,
                sessionRunCount = sessionRunCount,
                provider = session.providerLabel,
                startedAt = startedAt,
                allocationFailures = allocationFailures,
                downgradeReasons = downgradeReasons,
                inputBytes = totalInputBytes,
                peakInputBytes = peakInputBytes,
                outputBytes = totalOutputBytes,
                peakOutputBytes = peakOutputBytes,
            ),
        )
    }

    private fun <Crop> runAttempt(
        crops: List<Crop>,
        cropOffset: Int,
        actualBatch: Int,
        widthBucket: Int,
        sampleElements: Int,
        writeSample: (crop: Crop, destination: FloatBuffer, baseOffset: Int, widthBucket: Int) -> Unit,
        sessionRunCount: () -> Unit,
    ): AttemptResult {
        var inputLease: PaddleOcrV6BatchBufferPool.Lease? = null
        var output: PaddleOcrV6BatchOutput? = null
        var outputLease: PaddleOcrV6BatchBufferPool.Lease? = null
        var inputBytes = 0L
        var outputBytes = 0L
        var runs = 0
        var allocationFailures = 0
        val startedAt = clockNanos()
        return try {
            inputLease = try {
                bufferPool.acquireInput(actualBatch, widthBucket)
            } catch (error: PaddleOcrV6BatchBufferPool.BufferAllocationException) {
                allocationFailures++
                throw PaddleOcrV6BatchFailure(
                    reason = PaddleOcrV6BatchDowngradeReason.ALLOCATION_FAILURE,
                    cause = error,
                    sessionRunCount = 0,
                    actualBatchSize = null,
                    inputBytes = 0,
                    outputBytes = 0,
                    allocationFailures = allocationFailures,
                )
            }
            inputBytes = actualBatch.toLong() * sampleElements * Float.SIZE_BYTES
            inputLease.buffer.clear()
            for (index in 0 until actualBatch) {
                writeSample(
                    crops[cropOffset + index],
                    inputLease.buffer,
                    index * sampleElements,
                    widthBucket,
                )
            }
            inputLease.buffer.limit(actualBatch * sampleElements)
            inputLease.buffer.position(0)

            sessionRunCount()
            runs++
            output = try {
                session.run(
                    inputLease.buffer,
                    longArrayOf(
                        actualBatch.toLong(),
                        3L,
                        PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT.toLong(),
                        widthBucket.toLong(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw PaddleOcrV6BatchFailure(
                    reason = PaddleOcrV6BatchDowngradeReason.PROVIDER_FAILURE,
                    cause = error,
                    sessionRunCount = runs,
                    actualBatchSize = actualBatch,
                    inputBytes = inputBytes,
                    outputBytes = outputBytes,
                    allocationFailures = allocationFailures,
                )
            }

            val shape = output.shape
            require(shape.size == 3 && shape[0] == actualBatch.toLong()) {
                "Paddle OCR output batch shape=${shape.contentToString()} expected batch=$actualBatch"
            }
            val elements = shape.fold(1L) { acc, dimension ->
                require(dimension > 0) { "Paddle OCR output dimension=$dimension is invalid" }
                Math.multiplyExact(acc, dimension)
            }
            outputBytes = elements * Float.SIZE_BYTES
            outputLease = try {
                bufferPool.acquireOutput(elements)
            } catch (error: PaddleOcrV6BatchBufferPool.BufferAllocationException) {
                allocationFailures++
                throw PaddleOcrV6BatchFailure(
                    reason = PaddleOcrV6BatchDowngradeReason.ALLOCATION_FAILURE,
                    cause = error,
                    sessionRunCount = runs,
                    actualBatchSize = actualBatch,
                    inputBytes = inputBytes,
                    outputBytes = outputBytes,
                    allocationFailures = allocationFailures,
                )
            }
            try {
                output.copyTo(outputLease.buffer)
                val decoded = PaddleOcrV6BatchCtcDecoder.decode(
                    logits = outputLease.buffer,
                    shape = shape,
                    dictionary = dictionary,
                )
                AttemptResult(
                    results = decoded,
                    latencyMs = (clockNanos() - startedAt) / NANOS_PER_MILLISECOND,
                    inputBytes = inputBytes,
                    outputBytes = outputBytes,
                )
            } catch (error: PaddleOcrV6BatchFailure) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw PaddleOcrV6BatchFailure(
                    reason = PaddleOcrV6BatchDowngradeReason.PROVIDER_FAILURE,
                    cause = error,
                    sessionRunCount = runs,
                    actualBatchSize = actualBatch,
                    inputBytes = inputBytes,
                    outputBytes = outputBytes,
                    allocationFailures = allocationFailures,
                )
            }
        } catch (failure: PaddleOcrV6BatchFailure) {
            throw failure.copy(sessionRunCount = maxOf(failure.sessionRunCount, runs))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw PaddleOcrV6BatchFailure(
                reason = PaddleOcrV6BatchDowngradeReason.PROVIDER_FAILURE,
                cause = error,
                sessionRunCount = runs,
                actualBatchSize = if (runs > 0) actualBatch else null,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                allocationFailures = allocationFailures,
            )
        } finally {
            outputLease?.close()
            output?.close()
            inputLease?.close()
        }
    }

    private fun telemetry(
        requestedBatchSize: Int,
        actualBatchSizes: List<Int>,
        sessionRunCount: Int,
        provider: String,
        startedAt: Long,
        allocationFailures: Int,
        downgradeReasons: List<String>,
        inputBytes: Long,
        peakInputBytes: Long,
        outputBytes: Long,
        peakOutputBytes: Long,
    ): PaddleOcrV6BatchTelemetry = PaddleOcrV6BatchTelemetry(
        requestedBatchSize = requestedBatchSize,
        actualBatchSizes = actualBatchSizes.toList(),
        sessionRunCount = sessionRunCount,
        provider = provider,
        latencyMs = (clockNanos() - startedAt) / NANOS_PER_MILLISECOND,
        allocationFailures = allocationFailures,
        downgradeReason = downgradeReasons.lastOrNull(),
        downgradeReasons = downgradeReasons.toList(),
        inputBytes = inputBytes,
        peakInputBytes = peakInputBytes,
        outputBytes = outputBytes,
        peakOutputBytes = peakOutputBytes,
        dictionaryClassCount = dictionary.size + 2,
    )

    private fun normalizeBatch(maxBatch: Int): Int = when {
        maxBatch >= MAX_BATCH_SIZE -> MAX_BATCH_SIZE
        maxBatch >= MEDIUM_BATCH_SIZE -> MEDIUM_BATCH_SIZE
        else -> MIN_BATCH_SIZE
    }

    private fun lowerBatch(batchSize: Int): Int = when {
        batchSize >= MAX_BATCH_SIZE -> MEDIUM_BATCH_SIZE
        else -> MIN_BATCH_SIZE
    }

    private fun String.isCpuLike(): Boolean = equals("cpu", ignoreCase = true) || isBlank()

    private companion object {
        const val MAX_BATCH_SIZE = 8
        const val MEDIUM_BATCH_SIZE = 4
        const val MIN_BATCH_SIZE = 1
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

internal data class PaddleOcrV6BatchExecution(
    val results: List<Pair<String, Float>>,
    val telemetry: PaddleOcrV6BatchTelemetry,
)

data class PaddleOcrV6BatchTelemetry(
    val requestedBatchSize: Int,
    val actualBatchSizes: List<Int>,
    val sessionRunCount: Int,
    val provider: String,
    val latencyMs: Double,
    val allocationFailures: Int,
    val downgradeReason: String?,
    val downgradeReasons: List<String>,
    val inputBytes: Long,
    val peakInputBytes: Long,
    val outputBytes: Long,
    val peakOutputBytes: Long,
    val dictionaryClassCount: Int,
) {
    val actualBatchSize: Int
        get() = actualBatchSizes.maxOrNull() ?: 0
}

internal enum class PaddleOcrV6BatchDowngradeReason(val wireValue: String) {
    ALLOCATION_FAILURE("allocation_failure"),
    PROVIDER_FAILURE("provider_failure"),
    LATENCY_FAILURE("latency_failure"),
}

internal class PaddleOcrStrictProviderException(
    providerLabel: String,
) : IllegalStateException(
    "Strict Paddle OCR provider mode rejected provider='$providerLabel'; CPU fallback is disabled",
)

internal class PaddleOcrV6BatchExecutionException(
    val telemetry: PaddleOcrV6BatchTelemetry,
    cause: Throwable,
) : RuntimeException("Paddle OCR batch execution failed", cause)

private data class AttemptResult(
    val results: List<Pair<String, Float>>,
    val latencyMs: Double,
    val inputBytes: Long,
    val outputBytes: Long,
)

private data class PaddleOcrV6BatchFailure(
    val reason: PaddleOcrV6BatchDowngradeReason,
    override val cause: Throwable,
    val sessionRunCount: Int,
    val actualBatchSize: Int?,
    val inputBytes: Long,
    val outputBytes: Long,
    val allocationFailures: Int,
) : RuntimeException(cause) {
    fun toExecutionException(telemetry: PaddleOcrV6BatchTelemetry): PaddleOcrV6BatchExecutionException =
        PaddleOcrV6BatchExecutionException(telemetry, this)
}
