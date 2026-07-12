package eu.kanade.translation.scheduling

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class TranslationStreamRegistryTest {

    private fun streamOf(bytes: ByteArray): () -> java.io.InputStream = { ByteArrayInputStream(bytes) }

    @Test
    fun `register and peek returns the same factory`() {
        val registry = TranslationStreamRegistry()
        val fn = streamOf(byteArrayOf(1, 2, 3))

        registry.register(sourceId = 1, mangaId = 10, chapterId = 100, pageKey = "001.jpg", streamFn = fn)

        registry.peek(1, 10, 100, "001.jpg") shouldBe fn
    }

    @Test
    fun `peek for an unregistered page returns null`() {
        val registry = TranslationStreamRegistry()
        registry.peek(1, 10, 100, "missing.jpg") shouldBe null
    }

    @Test
    fun `peek does not evict the entry so a retry can read it again`() {
        val registry = TranslationStreamRegistry()
        registry.register(1, 10, 100, "001.jpg", streamOf(byteArrayOf(9)))

        // Two peeks in a row — the entry must survive both so a failed
        // translation can be retried against the same stream factory.
        val first = registry.peek(1, 10, 100, "001.jpg")
        val second = registry.peek(1, 10, 100, "001.jpg")

        (first != null) shouldBe true
        (second != null) shouldBe true
    }

    @Test
    fun `clearChapter evicts only the matching chapter prefix`() {
        val registry = TranslationStreamRegistry()
        registry.register(1, 10, 100, "001.jpg", streamOf(byteArrayOf(1)))
        registry.register(1, 10, 100, "002.jpg", streamOf(byteArrayOf(2)))
        registry.register(1, 10, 101, "001.jpg", streamOf(byteArrayOf(3))) // different chapter
        registry.register(2, 10, 100, "001.jpg", streamOf(byteArrayOf(4))) // different source

        registry.clearChapter(sourceId = 1, mangaId = 10, chapterId = 100)

        registry.peek(1, 10, 100, "001.jpg") shouldBe null
        registry.peek(1, 10, 100, "002.jpg") shouldBe null
        // Other chapter & other source untouched
        (registry.peek(1, 10, 101, "001.jpg") != null) shouldBe true
        (registry.peek(2, 10, 100, "001.jpg") != null) shouldBe true
    }

    @Test
    fun `clearAll evicts every entry regardless of chapter`() {
        val registry = TranslationStreamRegistry()
        registry.register(1, 10, 100, "001.jpg", streamOf(byteArrayOf(1)))
        registry.register(2, 20, 200, "009.jpg", streamOf(byteArrayOf(9)))

        registry.clearAll()

        registry.peek(1, 10, 100, "001.jpg") shouldBe null
        registry.peek(2, 20, 200, "009.jpg") shouldBe null
    }

    @Test
    fun `clearPage evicts only one page`() {
        val registry = TranslationStreamRegistry()
        registry.register(1, 10, 100, "001.jpg", streamOf(byteArrayOf(1)))
        registry.register(1, 10, 100, "002.jpg", streamOf(byteArrayOf(2)))

        registry.clearPage(1, 10, 100, "001.jpg")

        registry.peek(1, 10, 100, "001.jpg") shouldBe null
        (registry.peek(1, 10, 100, "002.jpg") != null) shouldBe true
    }

    @Test
    fun `clearOutsideWindow keeps only selected page keys for chapter`() {
        val registry = TranslationStreamRegistry()
        registry.register(1, 10, 100, "001.jpg", streamOf(byteArrayOf(1)))
        registry.register(1, 10, 100, "002.jpg", streamOf(byteArrayOf(2)))
        registry.register(1, 10, 100, "003.jpg", streamOf(byteArrayOf(3)))
        registry.register(1, 10, 101, "001.jpg", streamOf(byteArrayOf(4)))

        registry.clearOutsideWindow(1, 10, 100, setOf("002.jpg"))

        registry.peek(1, 10, 100, "001.jpg") shouldBe null
        (registry.peek(1, 10, 100, "002.jpg") != null) shouldBe true
        registry.peek(1, 10, 100, "003.jpg") shouldBe null
        (registry.peek(1, 10, 101, "001.jpg") != null) shouldBe true
    }

    @Test
    fun `register replaces a prior entry for the same page`() {
        val registry = TranslationStreamRegistry()
        val first = streamOf(byteArrayOf(1))
        val second = streamOf(byteArrayOf(2))

        registry.register(1, 10, 100, "001.jpg", first)
        registry.register(1, 10, 100, "001.jpg", second)

        registry.peek(1, 10, 100, "001.jpg") shouldBe second
    }
}
