package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.translation.model.PageTranslation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class InsertPageTest {

    @Test
    fun `insert page copies parent translation streams and metadata`() {
        val initialOriginal = { ByteArrayInputStream(byteArrayOf(1)) }
        val initialTranslated = { ByteArrayInputStream(byteArrayOf(2)) }
        val chapter = ReaderChapter(ChapterImpl())
        val parent = ReaderPage(
            index = 0,
            originalStream = initialOriginal,
            translatedStream = initialTranslated,
            sourceFileName = "001.png",
        ).apply {
            this.chapter = chapter
            translation = PageTranslation(cleanedImageName = "001.cleaned.png")
            showTranslatedImage = true
            translationStorageKey = "001"
        }
        val insert = InsertPage(parent)

        insert.originalStream shouldBe initialOriginal
        insert.translatedStream shouldBe initialTranslated
        insert.showTranslatedImage shouldBe true
        insert.sourceFileName shouldBe "001.png"
        insert.translationStorageKey shouldBe "001"
        insert.translation shouldBe parent.translation
    }
}
