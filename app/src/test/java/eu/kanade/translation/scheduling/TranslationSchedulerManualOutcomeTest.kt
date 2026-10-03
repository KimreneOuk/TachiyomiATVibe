package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageFeedbackState
import eu.kanade.tachiyomi.ui.reader.viewer.readerManualOutcomeFeedback
import eu.kanade.translation.pipeline.execution.SinglePageOutcome
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class TranslationSchedulerManualOutcomeTest {

    @Test
    fun `recorded failed outcome remains visible to reader feedback`() {
        val scheduler = TranslationScheduler(mockk<TranslationExecutor>(relaxed = true), { null })
        try {
            val failure = SinglePageOutcome.Failed("0001.jpg", "no stream available")
            scheduler.recordManualOutcome(42L, "0001.jpg", failure)

            scheduler.manualOutcomeFor(42L, "0001.jpg") shouldBe failure
            val feedback = readerManualOutcomeFeedback(
                chapterId = 42L,
                pageKey = "0001.jpg",
                attemptActive = false,
                lookup = { chapterId, pageKey -> scheduler.manualOutcomeFor(chapterId, pageKey) },
                nativeStall = null,
                durable = null,
            )
            feedback.shouldNotBeNull()
            (feedback as ReaderPageFeedbackState.ManualTruth).truth.severity shouldBe
                eu.kanade.translation.presentation.UiSeverity.ERROR
        } finally {
            scheduler.close()
        }
    }
}
