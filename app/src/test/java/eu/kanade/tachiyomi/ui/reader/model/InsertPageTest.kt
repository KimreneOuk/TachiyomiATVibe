package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.translation.model.PageTranslation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class InsertPageTest {

    @Test
    fun `syncFromParent refreshes copied translation streams and metadata`() {
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
            translation = PageTranslation(renderedImageName = "001.rendered.png")
            showTranslatedImage = true
            translationStorageKey = "001"
        }
        val insert = InsertPage(parent)

        val nextOriginal = { ByteArrayInputStream(byteArrayOf(3)) }
        parent.originalStream = nextOriginal
        parent.translatedStream = null
        parent.showTranslatedImage = false
        parent.sourceFileName = "001-updated.png"
        parent.translationStorageKey = "001-updated"
        parent.translation = null

        insert.syncFromParent()

        insert.originalStream shouldBe nextOriginal
        insert.translatedStream shouldBe null
        insert.showTranslatedImage shouldBe false
        insert.sourceFileName shouldBe "001-updated.png"
        insert.translationStorageKey shouldBe "001-updated"
        insert.translation shouldBe null
    }
}
