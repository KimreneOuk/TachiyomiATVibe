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
        snapshot.batchPhase shouldBe TranslationBatchPhase.IDLE
    }

    @Test
    fun `page index resolver handles split and ordinary filenames`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = linkedMapOf(
                "009__002.jpg" to PageTranslation(),
                "page-09.png" to PageTranslation(),
                "009.jpg" to PageTranslation(),
            ),
        )

        snapshot.pages.map { it.index } shouldContainExactly listOf(9, 9, 9)
    }

    @Test
    fun `real page index map takes precedence over filename parsing`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = mapOf("009__002.jpg" to PageTranslation()),
            indexResolver = mapOf("009__002.jpg" to 17),
        )

        snapshot.pages.single().index shouldBe 17
    }

    @Test
    fun `only the permit holder is shown as running`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = linkedMapOf(
                "001.jpg" to PageTranslation(ocrStatus = StageStatus.RUNNING),
                "002.jpg" to PageTranslation(ocrStatus = StageStatus.RUNNING),
            ),
            permitHolderPageKey = "002.jpg",
        )

        snapshot.pages.map { it.stage } shouldContainExactly listOf(
            TranslationProgressStage.QUEUED,
            TranslationProgressStage.OCR,
        )
        snapshot.activePageKey shouldBe "002.jpg"
        snapshot.activeStage shouldBe TranslationProgressStage.OCR
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
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.SKIPPED,
                    inpaintStatus = StageStatus.SKIPPED,
                    renderStatus = StageStatus.SKIPPED,
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
        snapshot.doneStages shouldBe 14
        snapshot.totalStages shouldBe 24
        snapshot.fraction shouldBe (14f / 24f)
    }

    @Test
    fun `per-stage counts reflect individual stage readiness`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = linkedMapOf(
                "001.jpg" to PageTranslation(
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.READY,
                    inpaintStatus = StageStatus.READY,
                    renderStatus = StageStatus.READY,
                ),
                "002.jpg" to PageTranslation(
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.RUNNING,
                    inpaintStatus = StageStatus.PENDING,
                    renderStatus = StageStatus.PENDING,
                ),
            ),
        )

        val ocr = snapshot.perStage[eu.kanade.translation.batch.BatchPhase.OCR]
        ocr?.done shouldBe 2
        ocr?.total shouldBe 2

        val translate = snapshot.perStage[eu.kanade.translation.batch.BatchPhase.TRANSLATE]
        translate?.done shouldBe 1
        translate?.total shouldBe 2

        val inpaint = snapshot.perStage[eu.kanade.translation.batch.BatchPhase.INPAINT]
        inpaint?.done shouldBe 1
        inpaint?.total shouldBe 2

        val render = snapshot.perStage[eu.kanade.translation.batch.BatchPhase.RENDER]
        render?.done shouldBe 1
        render?.total shouldBe 2
    }

    @Test
    fun `partial pages are counted`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = linkedMapOf(
                "001.jpg" to PageTranslation(translationStatus = StageStatus.PARTIAL),
                "002.jpg" to PageTranslation(translationStatus = StageStatus.READY),
            ),
        )

        snapshot.partialPages shouldBe 1
    }

    @Test
    fun `computed translating snapshot starts in first pass`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = mapOf("001.jpg" to PageTranslation()),
        )

        snapshot.batchPhase shouldBe TranslationBatchPhase.FIRST_PASS
    }

    @Test
    fun `grouped failures collect error messages`() {
        val snapshot = TranslationProgressSnapshot.compute(
            chapterId = 1L,
            state = Translation.State.TRANSLATING,
            pageMap = linkedMapOf(
                "001.jpg" to PageTranslation(ocrStatus = StageStatus.FAILED, errorMessage = "OOM"),
                "002.jpg" to PageTranslation(translationStatus = StageStatus.FAILED, errorMessage = "HTTP 429"),
                "003.jpg" to PageTranslation(ocrStatus = StageStatus.FAILED, errorMessage = "OOM"),
            ),
        )

        snapshot.groupedFailures["OOM"] shouldContainExactly listOf("001.jpg", "003.jpg")
        snapshot.groupedFailures["HTTP 429"] shouldContainExactly listOf("002.jpg")
    }
}
