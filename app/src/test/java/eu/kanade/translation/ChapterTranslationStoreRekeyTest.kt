package eu.kanade.translation

import eu.kanade.translation.storage.*

import eu.kanade.translation.model.PageTranslation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChapterTranslationStoreRekeyTest {

    private fun store(vararg keys: String): ChapterTranslationStore = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = keys.associateWith { PageTranslation(sourceFileName = it) },
    )

    @Test
    fun `URL keys move to downloaded filenames and update source names`() = runTest {
        val store = store("page-a.jpg", "page-b.jpg")

        store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg"),
        ) shouldBe listOf("page-a.jpg" to "000.jpg", "page-b.jpg" to "001.jpg")

        store.state.value.keys shouldBe setOf("000.jpg", "001.jpg")
        store.state.value.values.map { it.sourceFileName } shouldBe listOf("000.jpg", "001.jpg")
    }

    @Test
    fun `count mismatch leaves store untouched`() = runTest {
        val store = store("page-a.jpg", "page-b.jpg", "page-c.jpg")
        val before = store.state.value

        store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg", "page-c.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg", "002.jpg", "002__001.jpg", "002__002.jpg"),
        ) shouldBe emptyList()

        store.state.value shouldBe before
    }

    @Test
    fun `already downloaded keys are idempotent`() = runTest {
        val store = store("000.jpg", "001.jpg")
        val before = store.state.value

        store.rekeyPages(
            onlineKeys = listOf("page-a.jpg", "page-b.jpg"),
            onDiskKeys = listOf("000.jpg", "001.jpg"),
        ) shouldBe emptyList()

        store.state.value shouldBe before
    }
}
