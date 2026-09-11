package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T924 LI-1: the flagged-lane COMPLETED completion projection. The flagged
 * [ChapterProfileBatchCoordinator] commits translations WITHOUT an in-pass
 * render — pages end translation-terminal with `renderStatus == PENDING` and
 * no cleaned image — so the legacy done-predicate (`hasRenderedResult`)
 * projected every healthy flagged COMPLETED chapter as fully stranded →
 * chapter ERROR → tracker/queue/manga-screen ERROR, with retry re-entering
 * the zero-work COMPLETE fast path and erroring again.
 *
 * Levels tested: [BatchProgressReconciler.reconcileFlaggedCompleted] directly,
 * plus the pure post-pass selector
 * [BatchChapterTranslator.postPassReconciliation] the shell's single post-pass
 * site consults (`translateBatchTraced`). The full shell itself is NOT driven
 * here (the production harness has no flagged-lane wiring); the selector is
 * the complete decision surface extracted from it.
 */
class BatchPostPassProjectionTest {

    /** A flagged-lane terminal page: translation committed, NEVER rendered. */
    private fun flaggedCommittedPage(key: String) = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(block()),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.PENDING,
        cleanedImageName = null,
    )

    private fun flaggedPartialPage(key: String) = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(block()),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.PARTIAL,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.PENDING,
        cleanedImageName = null,
    )

    private fun textlessPage(key: String) = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        renderStatus = StageStatus.SKIPPED,
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

    @Test
    fun `flagged completed outcome with translatable unrendered pages projects TRANSLATED with zero stranded`() {
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = mapOf(
                "p0" to flaggedCommittedPage("p0"),
                "p1" to textlessPage("p1"),
            ),
            orderedKeys = listOf("p0", "p1", "p2"),
            activeGeneration = 0L,
        )

        // Every expected page counts done — including p2, missing from the
        // live map entirely: the run's COMPLETE record covers it.
        result.chapterStatus shouldBe Translation.State.TRANSLATED
        result.strandedPages shouldBe emptyMap()
        result.failedCount shouldBe 0
        result.doneCount shouldBe 3
        result.partialCount shouldBe 0
    }

    @Test
    fun `flagged completed outcome with a PARTIAL page projects READY_WITH_WARNINGS`() {
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = mapOf(
                "p0" to flaggedCommittedPage("p0"),
                "p1" to flaggedPartialPage("p1"),
            ),
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
        )

        result.chapterStatus shouldBe Translation.State.READY_WITH_WARNINGS
        result.strandedPages shouldBe emptyMap()
        result.failedCount shouldBe 0
        result.doneCount shouldBe 1
        result.partialCount shouldBe 1
    }

    @Test
    fun `the legacy projection still strands flagged-shaped unrendered pages (bug anchor)`() {
        // Characterizes the LI-1 bug the new projection fixes: feeding the
        // flagged COMPLETED page shapes through the legacy reconcile reports
        // ERROR with every translatable page stranded.
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to flaggedCommittedPage("p0")),
            orderedKeys = listOf("p0"),
            activeGeneration = 0L,
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.strandedPages.keys shouldBe setOf("p0")
    }

    @Test
    fun `the post-pass selector picks the flagged projection only for flagged COMPLETED`() {
        val pageMap = mapOf("p0" to flaggedCommittedPage("p0"))
        val orderedKeys = listOf("p0")

        // Flagged lane + COMPLETED: the flagged projection (no stranded).
        val flagged = BatchChapterTranslator.postPassReconciliation(
            flaggedLane = true,
            status = BatchPass1Status.COMPLETED,
            pageMap = pageMap,
            orderedKeys = orderedKeys,
            activeGeneration = 0L,
        )
        flagged.chapterStatus shouldBe Translation.State.TRANSLATED
        flagged.strandedPages shouldBe emptyMap()

        // Legacy lane (FF-01 OFF), COMPLETED: the existing reconcile unchanged.
        val legacy = BatchChapterTranslator.postPassReconciliation(
            flaggedLane = false,
            status = BatchPass1Status.COMPLETED,
            pageMap = pageMap,
            orderedKeys = orderedKeys,
            activeGeneration = 0L,
        )
        legacy.chapterStatus shouldBe Translation.State.ERROR
        legacy.strandedPages.keys shouldBe setOf("p0")

        // A flagged lane outcome that is not COMPLETED never uses this
        // projection (the shell's pause branch keeps the existing reconcile).
        val nonCompleted = BatchChapterTranslator.postPassReconciliation(
            flaggedLane = true,
            status = BatchPass1Status.PAUSED,
            pageMap = pageMap,
            orderedKeys = orderedKeys,
            activeGeneration = 0L,
        )
        nonCompleted.chapterStatus shouldBe Translation.State.ERROR
    }
}
