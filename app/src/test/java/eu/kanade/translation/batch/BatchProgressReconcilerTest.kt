package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchProgressReconcilerTest {

    @Test
    fun `empty page map is ERROR`() {
        val result = BatchProgressReconciler.reconcile(emptyMap(), emptyList())
        result.chapterStatus shouldBe Translation.State.ERROR
        result.strandedPages shouldBe emptyMap()
    }

    @Test
    fun `empty page map reports each expected page as stranded`() {
        val result = BatchProgressReconciler.reconcile(emptyMap(), listOf("001.jpg", "002.jpg"))

        result.chapterStatus shouldBe Translation.State.ERROR
        result.strandedPages.keys shouldBe setOf("001.jpg", "002.jpg")
        result.failedCount shouldBe 2
    }

    @Test
    fun `all pages rendered is TRANSLATED`() {
        val pages = linkedMapOf(
            "001.jpg" to terminalTextlessPage(),
            "002.jpg" to terminalTextlessPage(),
        )
        val result = BatchProgressReconciler.reconcile(pages, pages.keys.toList())
        result.chapterStatus shouldBe Translation.State.TRANSLATED
        result.doneCount shouldBe 2
        result.failedCount shouldBe 0
        result.strandedPages shouldBe emptyMap()
    }

    @Test
    fun `a failed page makes chapter ERROR`() {
        val pages = linkedMapOf(
            "001.jpg" to terminalTextlessPage(),
            "002.jpg" to PageTranslation(ocrStatus = StageStatus.FAILED, errorMessage = "OOM"),
        )
        val result = BatchProgressReconciler.reconcile(pages, pages.keys.toList())
        result.chapterStatus shouldBe Translation.State.ERROR
        result.failedCount shouldBe 1
    }

    @Test
    fun `stranded RUNNING page without output is flipped to FAILED`() {
        val pages = linkedMapOf(
            "001.jpg" to terminalTextlessPage(),
            "002.jpg" to PageTranslation(ocrStatus = StageStatus.RUNNING),
        )
        val result = BatchProgressReconciler.reconcile(pages, pages.keys.toList())
        result.chapterStatus shouldBe Translation.State.ERROR
        result.strandedPages.containsKey("002.jpg") shouldBe true
        result.failedCount shouldBe 1
    }

    @Test
    fun `textless terminal page is done not failed`() {
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                ocrStatus = StageStatus.READY,
                blocks = mutableListOf(),
                translationStatus = StageStatus.SKIPPED,
                inpaintStatus = StageStatus.SKIPPED,
                renderStatus = StageStatus.SKIPPED,
            ),
        )
        val result = BatchProgressReconciler.reconcile(pages, pages.keys.toList())
        result.chapterStatus shouldBe Translation.State.TRANSLATED
        result.doneCount shouldBe 1
        result.failedCount shouldBe 0
    }

    private fun terminalTextlessPage() = PageTranslation(
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        renderStatus = StageStatus.SKIPPED,
    )

    @Test
    fun `PENDING page without output is stranded`() {
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(ocrStatus = StageStatus.PENDING),
        )
        val result = BatchProgressReconciler.reconcile(pages, pages.keys.toList())
        result.chapterStatus shouldBe Translation.State.ERROR
        result.strandedPages.containsKey("001.jpg") shouldBe true
    }

    @Test
    fun `ordered expected key missing from map is one stranded failure`() {
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("001.jpg" to terminalTextlessPage()),
            orderedKeys = listOf("001.jpg", "002.jpg"),
        )

        result.strandedPages shouldBe mapOf("002.jpg" to "Translation incomplete — expected page is missing")
        result.doneCount shouldBe 1
        result.failedCount shouldBe 1
    }

    @Test
    fun `unexpected key is reported without inflating expected work`() {
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf(
                "001.jpg" to terminalTextlessPage(),
                "unexpected.jpg" to PageTranslation(ocrStatus = StageStatus.FAILED),
            ),
            orderedKeys = listOf("001.jpg"),
        )

        result.chapterStatus shouldBe Translation.State.TRANSLATED
        result.doneCount shouldBe 1
        result.failedCount shouldBe 0
        result.unexpectedPageKeys shouldBe setOf("unexpected.jpg")
    }

    @Test
    fun `partial readable draft and unresolved revision yield warnings but hard failure yields error`() {
        val warning = BatchProgressReconciler.reconcile(
            pageMap = mapOf(
                "001.jpg" to PageTranslation(
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.PARTIAL,
                    blocks = mutableListOf(block(needsRevision = true)),
                ),
            ),
            orderedKeys = listOf("001.jpg"),
        )
        val error = BatchProgressReconciler.reconcile(
            pageMap = mapOf("001.jpg" to PageTranslation(renderStatus = StageStatus.FAILED)),
            orderedKeys = listOf("001.jpg"),
        )

        warning.chapterStatus shouldBe Translation.State.READY_WITH_WARNINGS
        warning.unresolvedRevisionCount shouldBe 1
        error.chapterStatus shouldBe Translation.State.ERROR
    }

    private fun block(needsRevision: Boolean) = eu.kanade.translation.model.TranslationBlock(
        text = "源",
        translation = "draft",
        width = 1f,
        height = 1f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        needsRevision = needsRevision,
    )
}
