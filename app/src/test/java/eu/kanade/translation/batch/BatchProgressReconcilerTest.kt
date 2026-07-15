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
}
