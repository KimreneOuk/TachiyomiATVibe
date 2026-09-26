package eu.kanade.translation.pipeline.batch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only stage trace used to assert batch invocation counts.
 *
 * The trace deliberately records only stage names and page keys. It never keeps
 * source text, prompts, translations, or context, so it is safe to reuse when
 * later tests assert exact invocation and display-transition counts.
 */
internal enum class BatchTestStage {
    SOURCE_DECODE,
    DETECTION,
    OCR,
    INPAINT,
    TRANSLATION,
    LAYOUT,
    STORE_EMISSION,
    READER_DISPLAY,
}

internal data class BatchStageInvocation(
    val stage: BatchTestStage,
    val pageKey: String,
)

internal class BatchStageInvocationCounters {
    private val counts = ConcurrentHashMap<BatchTestStage, AtomicInteger>()
    private val invocations = CopyOnWriteArrayList<BatchStageInvocation>()

    fun record(stage: BatchTestStage, pageKey: String) {
        counts.computeIfAbsent(stage) { AtomicInteger() }.incrementAndGet()
        invocations.add(BatchStageInvocation(stage, pageKey))
    }

    fun recordStoreEmission(pageKey: String) = record(BatchTestStage.STORE_EMISSION, pageKey)

    fun recordReaderDisplay(pageKey: String) = record(BatchTestStage.READER_DISPLAY, pageKey)

    fun count(stage: BatchTestStage): Int = counts[stage]?.get() ?: 0

    fun counts(): Map<BatchTestStage, Int> = BatchTestStage.entries.associateWith(::count)

    fun invocations(): List<BatchStageInvocation> = invocations.toList()
}
