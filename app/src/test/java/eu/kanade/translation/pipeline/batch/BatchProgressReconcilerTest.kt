package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchProgressReconcilerTest {

    @Test
    fun `retryable pause preserves prefix and leaves tail pending without stranded failures`() {
        val prefix = readyPage("p0")
        val anchor = PageTranslation(
            sourceFileName = "p1",
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
            inpaintStatus = StageStatus.READY,
        )
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to prefix, "p1" to anchor),
            orderedKeys = listOf("p0", "p1", "p2"),
            activeGeneration = 0L,
            pauseOutcome = BatchPass1Outcome(
                needsTranslation = listOf("p0", "p1"),
                status = BatchPass1Status.PAUSED,
                anchorPageKey = "p1",
                completedPageKeys = setOf("p0"),
                retryablePageKeys = setOf("p1"),
                nextEligibleRetryAtEpochMs = 5000L,
                reason = "provider quota exhausted",
            ),
        )

        result.chapterStatus shouldBe Translation.State.PAUSED
        result.paused shouldBe true
        result.strandedPages shouldBe emptyMap()
        result.doneCount shouldBe 1
        result.partialCount shouldBe 1
        result.pendingCount shouldBe 1
        result.failedCount shouldBe 0
        result.retryableCount shouldBe 1
        result.nextEligibleRetryAtEpochMs shouldBe 5000L
    }

    @Test
    fun `persistence rejection is a retryable error without durable tail failures`() {
        // T924 field fix (Chapter 21): the old in-memory READY_WITH_WARNINGS
        // classification rendered the chapter as completed with no Retry
        // affordance while the tail pages were never resolved.
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to readyPage("p0")),
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
            pauseOutcome = BatchPass1Outcome(
                needsTranslation = listOf("p0", "p1"),
                status = BatchPass1Status.PERSISTENCE_REJECTED,
                anchorPageKey = "p1",
                completedPageKeys = setOf("p0"),
                persistenceRejectedStage = BatchDiagnosticStage.TRANSLATION,
                reason = "Batch persistence publication rejected",
            ),
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.paused shouldBe false
        result.nonDurableFailure shouldBe true
        result.failedCount shouldBe 0
        result.pendingCount shouldBe 1
        result.retryableCount shouldBe 0
    }

    @Test
    fun `a partial candidate with no rendered result is unresolved work, not a warning`() {
        val partialUnrendered = PageTranslation(
            sourceFileName = "p1",
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
            inpaintStatus = StageStatus.READY,
        )
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to readyPage("p0"), "p1" to partialUnrendered),
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.failedCount shouldBe 1
        result.partialCount shouldBe 1
        result.strandedPages.keys shouldBe setOf("p1")
    }

    private fun readyPage(key: String) = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(block()),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "$key.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun block() = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 1f,
        y = 1f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )
}
