package eu.kanade.translation.model

import eu.kanade.translation.ChapterTranslationStore
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationProgressSnapshotTest {

    @Test
    fun `empty snapshot has zero counts`() {
        val snapshot = TranslationProgressSnapshot.empty(42L)

        snapshot.chapterId shouldBe 42L
        snapshot.donePages shouldBe 0
        snapshot.totalPages shouldBe 0
        snapshot.fraction shouldBe 0f
    }

    @Test
    fun `pre registered pages show full total as queued`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        store.preRegisterPages(listOf("003.jpg", "001.jpg", "002.jpg"))

        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 7L,
            state = Translation.State.TRANSLATING,
            pageMap = store.state.value,
        )

        snapshot.totalPages shouldBe 3
        snapshot.queuedCount shouldBe 3
        snapshot.donePages shouldBe 0
        snapshot.activePage shouldBe 1
        snapshot.pages.map { it.index } shouldContainExactly listOf(1, 2, 3)
    }

    @Test
    fun `stages map from page statuses`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = linkedMapOf(
                "001.jpg" to PageTranslation(ocrStatus = StageStatus.RUNNING),
                "002.jpg" to PageTranslation(
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.RUNNING,
                ),
                "003.jpg" to PageTranslation(
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.READY,
                    inpaintStatus = StageStatus.RUNNING,
                ),
                "004.jpg" to PageTranslation(
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.READY,
                    inpaintStatus = StageStatus.READY,
                    renderStatus = StageStatus.RUNNING,
                ),
                "005.jpg" to PageTranslation(
                    renderedImageName = "005.rendered.png",
                    renderQuality = RenderQuality.FULL,
                ),
                "006.jpg" to PageTranslation(
                    ocrStatus = StageStatus.FAILED,
                    errorMessage = "context budget",
                ),
            ),
        )

        snapshot.pages.map { it.stage } shouldContainExactly listOf(
            TranslationProgressStage.OCR,
            TranslationProgressStage.TRANSLATE,
            TranslationProgressStage.INPAINT,
            TranslationProgressStage.RENDER,
            TranslationProgressStage.DONE,
            TranslationProgressStage.FAILED,
        )
        snapshot.failedCount shouldBe 1
        snapshot.donePages shouldBe 2
        snapshot.activePage shouldBe 1
        snapshot.activeStage shouldBe TranslationProgressStage.OCR
    }
}
