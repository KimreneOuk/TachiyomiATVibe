package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.model.PageTranslation
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.jupiter.api.Test

class ReaderPageTranslationKeyTest {

    @Test
    fun `manual writer and reader observation share the rebound source filename`() = kotlinx.coroutines.runBlocking<Unit> {
        val page = ReaderPage(
            index = 3,
            url = "https://source.invalid/page/3",
            imageUrl = "https://source.invalid/images/3.jpg",
            sourceFileName = "4.jpg",
        ).apply {
            // This is the online fallback captured before the downloaded loader
            // supplied the canonical source filename.
            translationStorageKey = "3.jpg"
        }
        val writerKey = resolveReaderPageTranslationKey(page)
        val store = mapOf(writerKey to PageTranslation(sourceFileName = writerKey))

        val observed = flowOf(store)
            .map { pages -> pages[resolveReaderPageTranslationKey(page)] }
            .first()
            .shouldNotBeNull()

        writerKey shouldBe "4.jpg"
        observed.sourceFileName shouldBe "4.jpg"
    }

    @Test
    fun `writer and observer keys remain equal when page index differs from filename`() {
        val page = ReaderPage(
            index = 5,
            url = "https://source.invalid/page/5",
            imageUrl = "https://source.invalid/images/5.jpg",
            sourceFileName = "6.jpg",
        ).apply {
            translationStorageKey = "5.jpg"
        }

        val writerKey = resolveReaderPageTranslationKey(page)
        val observerKey = resolveReaderPageTranslationKey(page)

        writerKey shouldBe "6.jpg"
        observerKey shouldBe writerKey
        observerKey shouldBe "6.jpg"
    }
}
