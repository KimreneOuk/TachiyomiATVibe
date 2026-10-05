package eu.kanade.translation.scheduling

import eu.kanade.translation.pipeline.execution.SinglePageOutcome
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class TranslationSchedulerManualOutcomeTest {

    @Test
    fun `recorded failed outcome remains available by chapter and page`() {
        val scheduler = TranslationScheduler(mockk<TranslationExecutor>(relaxed = true), { null })
        try {
            val failure = SinglePageOutcome.Failed("0001.jpg", "no stream available")
            scheduler.recordManualOutcome(42L, "0001.jpg", failure)

            scheduler.manualOutcomeFor(42L, "0001.jpg") shouldBe failure
        } finally {
            scheduler.close()
        }
    }
}
