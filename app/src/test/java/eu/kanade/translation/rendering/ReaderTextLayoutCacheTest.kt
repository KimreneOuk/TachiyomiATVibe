package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins [ReaderTextLayoutCache] — the strict bounded LRU that lets the overlay
 * apply prepared layouts synchronously on a rebind ( 3.1). The bound must
 * hold absolutely, and keys must discriminate on blocks content AND page
 * dimensions.
 */
class ReaderTextLayoutCacheTest {

    private fun block(translation: String) = TranslationBlock(
        text = "",
        translation = translation,
        width = 100f,
        height = 40f,
        x = 10f,
        y = 10f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun key(translation: String = "Hi", width: Int = 800, height: Int = 1200) =
        TextLayoutCacheKey(listOf(block(translation)), width, height)

    @Test
    fun `miss returns null before any put`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        cache.get(key()) shouldBe null
        cache.size shouldBe 0
    }

    @Test
    fun `hit returns the stored value`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        val k = key()
        cache.put(k, "prepared")
        cache.get(k) shouldBe "prepared"
        cache.size shouldBe 1
    }

    @Test
    fun `equal content in a fresh list instance hits`() {
        // Rebinds may carry freshly deserialized-but-equal block lists; the key
        // is content-based, so they must hit rather than re-plan.
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        cache.put(key(), "prepared")
        cache.get(key()) shouldBe "prepared"
    }

    @Test
    fun `eviction keeps the entry bound strict`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 2)
        cache.put(key(translation = "A"), "A")
        cache.put(key(translation = "B"), "B")
        cache.put(key(translation = "C"), "C")
        cache.size shouldBe 2
        cache.get(key(translation = "A")) shouldBe null
        cache.get(key(translation = "B")) shouldBe "B"
        cache.get(key(translation = "C")) shouldBe "C"
    }

    @Test
    fun `access refreshes recency so a hit entry survives the next put`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 2)
        val a = key(translation = "A")
        val b = key(translation = "B")
        cache.put(a, "A")
        cache.put(b, "B")
        cache.get(a) // A becomes most-recent
        cache.put(key(translation = "C"), "C")
        cache.get(a) shouldBe "A"
        cache.get(b) shouldBe null
    }

    @Test
    fun `page width change discriminates keys`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        cache.put(key(width = 800), "tall-ish")
        cache.get(key(width = 900)) shouldBe null
        cache.get(key(width = 800)) shouldBe "tall-ish"
    }

    @Test
    fun `page height change discriminates keys`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        cache.put(key(height = 1200), "portrait")
        cache.get(key(height = 800)) shouldBe null
        cache.get(key(height = 1200)) shouldBe "portrait"
    }

    @Test
    fun `block content change discriminates keys`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        cache.put(key(translation = "Hello"), "old")
        cache.get(key(translation = "Hello!")) shouldBe null
        cache.get(key(translation = "Hello")) shouldBe "old"
    }

    @Test
    fun `block count change discriminates keys`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        cache.put(TextLayoutCacheKey(listOf(block("A")), 800, 1200), "one")
        cache.get(TextLayoutCacheKey(listOf(block("A"), block("B")), 800, 1200)) shouldBe null
    }

    @Test
    fun `clear empties the cache`() {
        val cache = ReaderTextLayoutCache<String>(maxEntries = 4)
        cache.put(key(), "prepared")
        cache.clear()
        cache.size shouldBe 0
        cache.get(key()) shouldBe null
    }
}
