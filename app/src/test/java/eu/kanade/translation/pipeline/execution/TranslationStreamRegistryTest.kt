package eu.kanade.translation.pipeline.execution

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class TranslationStreamRegistryTest {

    @Test
    fun `retired cleaned image remains readable while held and deletes on release`() = runTest {
        val registry = TranslationStreamRegistry(cleanedRetirementGraceMs = 60_000L)
        var deleted = false
        val stream = registry.openCleanedImageStream(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            pageKey = "page.jpg",
            imageName = "a.jpg",
        ) { ByteArrayInputStream("A".toByteArray()) }

        registry.retireCleanedImage(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            pageKey = "page.jpg",
            imageName = "a.jpg",
        ) { deleted = true }

        registry.activeCleanedImageReaders(1L, 2L, 3L, "page.jpg", "a.jpg") shouldBe 1
        deleted shouldBe false
        stream.read().toChar() shouldBe 'A'

        stream.close()
        deleted shouldBe true
    }

    @Test
    fun `new committed image is independent while old image waits for bounded lease`() = runTest {
        val registry = TranslationStreamRegistry(cleanedRetirementGraceMs = 60_000L)
        var oldDeleted = false
        val old = registry.openCleanedImageStream(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            pageKey = "page.jpg",
            imageName = "a.jpg",
        ) { ByteArrayInputStream("A".toByteArray()) }

        registry.retireCleanedImage(1L, 2L, 3L, "page.jpg", "a.jpg") { oldDeleted = true }
        val current = registry.openCleanedImageStream(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            pageKey = "page.jpg",
            imageName = "b.jpg",
        ) { ByteArrayInputStream("B".toByteArray()) }

        old.read().toChar() shouldBe 'A'
        current.read().toChar() shouldBe 'B'
        oldDeleted shouldBe false
        current.close()
        old.close()
        oldDeleted shouldBe true
    }

    @Test
    fun `chapter reset defers directory deletion while a cleaned stream is held`() = runTest {
        val registry = TranslationStreamRegistry(cleanedRetirementGraceMs = 60_000L)
        var deleted = false
        val stream = registry.openCleanedImageStream(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            pageKey = "page.jpg",
            imageName = "a.jpg",
        ) { ByteArrayInputStream("A".toByteArray()) }

        registry.retireCleanedImagesForChapter(1L, 2L, 3L) { deleted = true }

        deleted shouldBe false
        stream.read().toChar() shouldBe 'A'
        stream.close()
        deleted shouldBe true
    }
}
