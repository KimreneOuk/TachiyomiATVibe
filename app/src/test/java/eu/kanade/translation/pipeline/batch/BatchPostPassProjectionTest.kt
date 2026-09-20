package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 *   the flagged-lane COMPLETED completion projection
 * ([BatchProgressReconciler.reconcileFlaggedCompleted]). Both surviving
 * pipelines commit translations WITHOUT an in-pass render — pages end
 * translation-terminal with `renderStatus == PENDING` and no cleaned image —
 * so the legacy done-predicate (`hasRenderedResult`) would project every
 * healthy COMPLETED chapter as fully stranded → chapter ERROR.
 *
 *  zero-legacy: the former pure selector
 * `BatchChapterTranslator.postPassReconciliation` was collapsed — dispatch is
 * always a flagged lane now, so the shell's single post-pass site calls
 * [BatchProgressReconciler.reconcileFlaggedCompleted] directly and the
 * selector tests were deleted with it. The legacy
 * [BatchProgressReconciler.reconcile] stays covered here only as the
 * bug anchor (and by the stop-branch behavior tests).
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
        // Characterizes the  bug the flagged projection fixes: feeding the
        // COMPLETED page shapes through the legacy reconcile reports ERROR
        // with every translatable page stranded — which is why the shell's
        // post-pass site must never use it for a COMPLETED run.
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to flaggedCommittedPage("p0")),
            orderedKeys = listOf("p0"),
            activeGeneration = 0L,
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.strandedPages.keys shouldBe setOf("p0")
    }
}
