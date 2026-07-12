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
