package eu.kanade.translation.workflow

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the translation-document memo. One reader entry used to walk the SAF
 * tree 5-7 times for the same document (60-210ms per walk on device). The
 * memo must serve positive repeat lookups without re-walking from the
 * manager-owned resolver, and be cleared by the clearDurableStatusCache invalidation
 * protocol. Negative results are never memoized — a document may appear when
 * a translation is first created.
 */
class DurableDocumentMemoTest {

    private val resolverSource: Source = mockk {
        every { id } returns 77L
    }

    private val sourceManager: SourceManager

    init {
        val manager = mockk<SourceManager>()
        every { manager.get(any()) } returns resolverSource
        sourceManager = manager
    }

    private fun documentProvider(walkCounter: () -> Unit): TranslationFileProvider {
        val parent = mockk<com.hippo.unifile.UniFile>(relaxed = true)
        val provider = mockk<TranslationFileProvider>(relaxed = true)
        every { provider.findMangaDir(any(), any()) } answers {
            walkCounter()
            parent
        }
        every { provider.getTranslationFileName(any(), any()) } returns "Chapter 1.json"
        return provider
    }

    private fun resolverWith(provider: TranslationFileProvider): DurableChapterStatusResolver =
        DurableChapterStatusResolver(
            providerProvider = { provider },
            sourceManagerProvider = { sourceManager },
            activeStoresProvider = { ActiveChapterStoreRegistry() },
        )

    @Suppress("UNCHECKED_CAST")
    private fun documentCache(resolver: DurableChapterStatusResolver): ConcurrentHashMap<DurableDocumentKey, TranslationDocument> =
        DurableChapterStatusResolver::class.java.getDeclaredField("durableDocumentCache").apply {
            isAccessible = true
        }.get(resolver) as ConcurrentHashMap<DurableDocumentKey, TranslationDocument>

    @Test
    fun `repeat positive lookups are served from the resolver-owned memo`() {
        var providerWalks = 0
        val provider = documentProvider { providerWalks++ }
        val resolver = resolverWith(provider)
        val cache = documentCache(resolver)

        val first = resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource)
        val second = resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource)

        providerWalks shouldBe 1
        (first === second) shouldBe true
        cache.size shouldBe 1
    }

    @Test
    fun `clearDurableStatusCache drops memoized documents so the next lookup re-walks`() {
        var providerWalks = 0
        val provider = documentProvider { providerWalks++ }
        val resolver = resolverWith(provider)

        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource)
        resolver.clearDurableStatusCache()
        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource)

        providerWalks shouldBe 2
    }

    @Test
    fun `negative lookups are not memoized`() {
        var providerWalks = 0
        val absentProvider = mockk<TranslationFileProvider>()
        every { absentProvider.findMangaDir(any(), any()) } answers {
            providerWalks++
            null
        }
        every { absentProvider.getTranslationFileName(any(), any()) } returns "Chapter 1.json"
        val resolver = resolverWith(absentProvider)
        val cache = documentCache(resolver)

        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource) shouldBe null
        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource) shouldBe null

        providerWalks shouldBe 2
        cache.isEmpty() shouldBe true
    }
}
