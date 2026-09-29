package eu.kanade.translation.engines.translator.providers

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

// Conservative until E4 line-count measurements are available; keep configurable
// within the interim 2–4 worker bound.
internal const val ML_KIT_LINE_POOL_SIZE = 2

/**
 * Bounds in-flight on-device line translations across all calls using one ML Kit translator.
 *
 * Each map call starts a fixed number of coroutine workers, while the shared semaphore also
 * enforces the bound when multiple pages are translated concurrently. withPermit returns the
 * worker on success, failure, or cancellation.
 */
internal class LineTranslationWorkerPool(
    val workerCount: Int = ML_KIT_LINE_POOL_SIZE,
) {
    init {
        require(workerCount in MIN_WORKER_COUNT..MAX_WORKER_COUNT) {
            "ML Kit line worker count must be in $MIN_WORKER_COUNT..$MAX_WORKER_COUNT"
        }
    }

    private val permits = Semaphore(workerCount)

    suspend fun <T> withWorker(block: suspend () -> T): T =
        permits.withPermit { block() }

    suspend fun <Input, Output> mapOrdered(
        inputs: List<Input>,
        transform: suspend (Input) -> Output,
    ): List<Output> = coroutineScope {
        if (inputs.isEmpty()) return@coroutineScope emptyList()

        val nextIndex = AtomicInteger()
        val outputs = arrayOfNulls<Any?>(inputs.size)
        val workers = List(minOf(workerCount, inputs.size)) {
            launch {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= inputs.size) break
                    outputs[index] = withWorker { transform(inputs[index]) }
                }
            }
        }
        workers.joinAll()

        @Suppress("UNCHECKED_CAST")
        outputs.map { it as Output }
    }

    companion object {
        const val MIN_WORKER_COUNT = 2
        const val MAX_WORKER_COUNT = 4
    }
}
