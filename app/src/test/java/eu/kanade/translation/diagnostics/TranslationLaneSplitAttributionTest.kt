package eu.kanade.translation.diagnostics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Deterministic attribution proof for scheduler waits and overlapping work lanes. */
class TranslationLaneSplitAttributionTest {

    private data class StageInterval(
        val stage: TranslationTraceStage,
        val startMs: Long,
        val endMs: Long,
    )

    @Test
    fun `governor and permit waits stay out of work lanes while overlap is unioned`() {
        val intervals = listOf(
            // Wait for the provider governor, then translate on the provider lane.
            StageInterval(TranslationTraceStage.PROVIDER_GOVERNOR_WAIT, 0, 10),
            StageInterval(TranslationTraceStage.TRANSLATE, 10, 40),
            // A prepared-page permit wait is scheduler time; OCR starts after it.
            StageInterval(TranslationTraceStage.PREPARED_QUEUE, 15, 25),
            StageInterval(TranslationTraceStage.OCR, 25, 55),
        )
        val accumulator = TranslationLaneOverlapAccumulator(startNanos = 0)

        intervals.flatMap { interval ->
            val lane = TranslationPipelineDiagnostics.defaultLane(interval.stage)
            listOf(
                Triple(interval.startMs * NANOS_PER_MS, lane, true),
                Triple(interval.endMs * NANOS_PER_MS, lane, false),
            )
        }.sortedBy { it.first }.forEach { (timeNanos, lane, entering) ->
            if (entering) {
                accumulator.enter(lane, timeNanos)
            } else {
                accumulator.exit(lane, timeNanos)
            }
        }

        val snapshot = accumulator.snapshot(55 * NANOS_PER_MS)

        // Scheduler-only waits contribute no busy lane time. Provider [10,40)
        // and native [25,55) work have 15 ms of overlap and 45 ms of union.
        snapshot.nativeBusyMs shouldBe 30L
        snapshot.providerBusyMs shouldBe 30L
        snapshot.renderBusyMs shouldBe 0L
        snapshot.unionActiveMs shouldBe 45L
        snapshot.overlapMs shouldBe 15L
        snapshot.concurrencySavingsMs shouldBe 15L
        snapshot.workMs shouldBe 60L
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
