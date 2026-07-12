package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

class TranslationSchedulerCancellationTest {

    @Test
    fun `cancel flips a stranded page to CANCELLED before returning`() {
        val pageKey = "page-7.jpg"
        val chapterId = 42L
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf(
                pageKey to PageTranslation(
                    sourceFileName = pageKey,
                    ocrStatus = StageStatus.RUNNING,
                ),
            ),
        )
        val scheduler = TranslationScheduler(
            executor = NoOpTranslationExecutor,
            storeResolver = TranslationStoreResolver { store },
            immediateStoreResolver = { id -> store.takeIf { id == chapterId } },
        )

        try {
            scheduler.cancelPageTranslation(chapterId, pageKey) shouldBe false

            withClue(store.state.value[pageKey]) {
                store.state.value[pageKey]?.ocrStatus shouldBe StageStatus.CANCELLED
                store.state.value[pageKey]?.errorMessage shouldBe "Translation cancelled"
            }
        } finally {
            scheduler.close()
        }
    }

    /**
     * Guard for the `hasRenderedResult || isStageFailed` peek in
     * `markPageCancelled`: a page that has already produced its rendered output
     * must NOT be flipped to CANCELLED by a late cancel. Regression guard for a
     * race where a slow cancel arrival could clobber a finished page's terminal
     * state.
     *
     * `hasRenderedResult` is `displayImageName != null`; for a textless page
     * (empty blocks) `displayImageName` is non-null when `cleanedImageName` is
     * set and `decodeSampleSize <= 1`. We construct exactly that finished state.
     */
    @Test
    fun `cancel is a no-op on a page that already has a rendered result`() {
        val pageKey = "page-finished.png"
        val chapterId = 7L
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf(
                pageKey to PageTranslation(
                    sourceFileName = pageKey,
                    cleanedImageName = "page-finished.cleaned.png",
                    decodeSampleSize = 1,
                    ocrStatus = StageStatus.READY,
                    translationStatus = StageStatus.READY,
                    inpaintStatus = StageStatus.READY,
                    renderStatus = StageStatus.READY,
                ),
            ),
        )
        val scheduler = TranslationScheduler(
            executor = NoOpTranslationExecutor,
            storeResolver = TranslationStoreResolver { store },
            immediateStoreResolver = { id -> store.takeIf { id == chapterId } },
        )

        try {
            scheduler.cancelPageTranslation(chapterId, pageKey)

            // Rendered output must be preserved; no stage may be CANCELLED.
            val after = store.state.value[pageKey]
            withClue(after!!) {
                after.cleanedImageName shouldBe "page-finished.cleaned.png"
                after.ocrStatus shouldBe StageStatus.READY
                after.translationStatus shouldBe StageStatus.READY
                after.inpaintStatus shouldBe StageStatus.READY
                after.renderStatus shouldBe StageStatus.READY
                after.errorMessage shouldBe null
            }
        } finally {
            scheduler.close()
        }
    }

    /**
     * Mirror of the rendered-result guard for the FAILED branch: a page that
     * reached a terminal failure must NOT be overwritten with "Translation
     * cancelled", which would erase the real failure message.
     */
    @Test
    fun `cancel is a no-op on a page that already failed a stage`() {
        val pageKey = "page-failed.jpg"
        val chapterId = 9L
        val originalError = "OCR engine crashed"
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf(
                pageKey to PageTranslation(
                    sourceFileName = pageKey,
                    ocrStatus = StageStatus.FAILED,
                    errorMessage = originalError,
                ),
            ),
        )
        val scheduler = TranslationScheduler(
            executor = NoOpTranslationExecutor,
            storeResolver = TranslationStoreResolver { store },
            immediateStoreResolver = { id -> store.takeIf { id == chapterId } },
        )

        try {
            scheduler.cancelPageTranslation(chapterId, pageKey)

            val after = store.state.value[pageKey]
            withClue(after!!) {
                after.ocrStatus shouldBe StageStatus.FAILED
                after.errorMessage shouldBe originalError
            }
        } finally {
            scheduler.close()
        }
    }

    private object NoOpTranslationExecutor : TranslationExecutor {
        override suspend fun translateSinglePage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            force: Boolean,
        ) = Unit

        override suspend fun translateSinglePageFromStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: () -> InputStream,
            force: Boolean,
        ) = Unit
    }
}
