package eu.kanade.translation.orchestration

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.chapter.TranslationProvider
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the translation-document memo. One reader entry used to walk the SAF
 * tree 5-7 times for the same document (60-210ms per walk on device). The
 * memo must serve positive repeat lookups without re-walking, survive across
 * resolver instances (the manager rebuilds the resolver per access from its
 * field values), and be cleared by the clearDurableStatusCache invalidation
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

    private fun documentProvider(walkCounter: () -> Unit): TranslationProvider {
        val file = mockk<UniFile>()
        val parent = mockk<UniFile>()
        every { file.parentFile } returns parent
        every { file.name } returns "Chapter 1.json"
        val provider = mockk<TranslationProvider>()
        every { provider.findTranslationFile(any(), any(), any(), any()) } answers {
            walkCounter()
            file
        }
        return provider
    }

    private fun resolverWith(
        provider: TranslationProvider,
        documentCache: ConcurrentHashMap<DurableDocumentKey, TranslationDocument>,
    ): DurableChapterStatusResolver = DurableChapterStatusResolver(
        providerProvider = { provider },
        sourceManagerProvider = { sourceManager },
        activeStoresProvider = { ActiveChapterStoreRegistry() },
        durableStatusCacheProvider = { ConcurrentHashMap() },
        durableDocumentCacheProvider = { documentCache },
    )

    @Test
    fun `repeat positive lookups are served from the memo across resolver instances`() {
        var providerWalks = 0
        val cache = ConcurrentHashMap<DurableDocumentKey, TranslationDocument>()
        val provider = documentProvider { providerWalks++ }

        val first = resolverWith(provider, cache)
            .findTranslationDocument("Chapter 1", null, "Manga", resolverSource)
        val second = resolverWith(provider, cache)
            .findTranslationDocument("Chapter 1", null, "Manga", resolverSource)

        providerWalks shouldBe 1
        (first === second) shouldBe true
        cache.size shouldBe 1
    }

    @Test
    fun `clearDurableStatusCache drops memoized documents so the next lookup re-walks`() {
        var providerWalks = 0
        val cache = ConcurrentHashMap<DurableDocumentKey, TranslationDocument>()
        val provider = documentProvider { providerWalks++ }
        val resolver = resolverWith(provider, cache)

        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource)
        resolver.clearDurableStatusCache()
        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource)

        providerWalks shouldBe 2
    }

    @Test
    fun `negative lookups are not memoized`() {
        var providerWalks = 0
        val cache = ConcurrentHashMap<DurableDocumentKey, TranslationDocument>()
        val absentProvider = mockk<TranslationProvider>()
        every { absentProvider.findTranslationFile(any(), any(), any(), any()) } answers {
            providerWalks++
            null
        }
        every { absentProvider.findMangaDir(any(), any()) } returns null
        val resolver = resolverWith(absentProvider, cache)

        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource) shouldBe null
        resolver.findTranslationDocument("Chapter 1", null, "Manga", resolverSource) shouldBe null

        providerWalks shouldBe 2
        cache.isEmpty() shouldBe true
    }
}
