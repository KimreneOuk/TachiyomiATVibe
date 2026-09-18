package eu.kanade.translation.benchmark

class BenchmarkStageTimer {

    private val timings = mutableListOf<StageTiming>()

    fun <T> measure(stage: String, block: () -> T): T {
        return measureTimed(stage, block).value
    }

    fun <T> measureTimed(stage: String, block: () -> T): Timed<T> {
        val started = System.nanoTime()
        return try {
            val value = block()
            val durationMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
            timings += StageTiming(stage, durationMs)
            Timed(value, durationMs)
        } catch (error: Throwable) {
            timings += StageTiming(
                stage = stage,
                durationMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND,
            )
            throw error
        }
    }

    fun snapshot(): List<StageTiming> = timings.toList()

    data class Timed<T>(
        val value: T,
        val durationMs: Double,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
