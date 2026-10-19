package eu.kanade.translation.workflow

import com.hippo.unifile.FakeUniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the no-wipe gates of the P3D durable read paths.
 *
 * Durable reads are publication-free: they create no store, write no bytes,
 * and can never advance durable truth through journal recovery or seeding.
 * The whole-cache wipe therefore fires on NO read path — only an external
 * [DurableChapterStatusResolver.clearDurableStatusCache] after a real durable
 * mutation may clear. An active store short-circuits before any I/O and
 * equally must not thrash the cache.
 *
 * The sentinel key is one the resolver can never produce for this chapter;
 * it must survive every read pass.
 */
class DurableStatusWipeGateTest {

    @TempDir
    lateinit var mangaDir: File

    private val source: Source = mockk {
        every { id } returns 77L
    }

    private val sourceManager: SourceManager

    init {
        val manager = mockk<SourceManager>()
        every { manager.get(any()) } returns source
        sourceManager = manager
    }

    private fun sentinel() = DurableChapterKey(null, "wipe-sentinel", null, "wipe-sentinel", -1L)

    private fun displayablePage() = PageTranslation(
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "hello",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun artifactParent() = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactFileName() = "Chapter 1.json"

    private fun provider(): TranslationFileProvider {
        val parent = artifactParent()
        val provider = mockk<TranslationFileProvider>()
        every { provider.findMangaDir(any(), any()) } returns parent
        every { provider.getTranslationFileName(any(), any()) } answers { "${firstArg<String>()}.json" }
        return provider
    }

    private fun resolver(
        provider: TranslationFileProvider,
        registry: ActiveChapterStoreRegistry,
    ): DurableChapterStatusResolver = DurableChapterStatusResolver(
        providerProvider = { provider },
        sourceManagerProvider = { sourceManager },
        activeStoresProvider = { registry },
    )

    @Suppress("UNCHECKED_CAST")
    private fun durableStatusCache(resolver: DurableChapterStatusResolver): ConcurrentHashMap<DurableChapterKey, DurableStatus> =
        DurableChapterStatusResolver::class.java.getDeclaredField("durableStatusCache").apply {
            isAccessible = true
        }.get(resolver) as ConcurrentHashMap<DurableChapterKey, DurableStatus>

    /** Materializes one committed page so the durable read resolves a real status. */
    private suspend fun writeArtifactChapter() {
        val store = ChapterTranslationStore.openArtifactSuspend(artifactParent(), artifactFileName())
        store.updatePage("page.jpg") { displayablePage() }
        store.closeAndFlush()
    }

    @Test
    fun `a publication-free durable read never wipes the durable status cache`() = runTest {
        writeArtifactChapter()
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)
        val state = resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)

        // The read resolves real durable truth without creating anything, so
        // the sentinel — and the rest of the cache — survives the pass.
        state shouldBe Translation.State.READY_WITH_WARNINGS
        cache.containsKey(sentinel()) shouldBe true
    }

    @Test
    fun `an active store short-circuits the durable read without wiping`() = runTest {
        writeArtifactChapter()
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        registry.register(42L, ChapterTranslationStore.openArtifactSuspend(artifactParent(), artifactFileName()))
        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)

        resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)

        cache.containsKey(sentinel()) shouldBe true
    }

    @Test
    fun `a missing document resolves null without wiping or caching`() = runTest {
        // No artifact manifest exists in the manga directory.
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)
        val state = resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)

        state shouldBe null
        cache.containsKey(sentinel()) shouldBe true
        cache.containsKey(DurableChapterKey(42L, "Chapter 1", null, "Manga", 77L)) shouldBe false
    }

    @Test
    fun `an unsupported future schema resolves null and preserves the bytes`() = runTest {
        val manifestName = "Chapter 1.json"
        val futureManifest = """{"schemaVersion":99,"chapterKey":"Chapter 1","pages":{}}"""
        File(mangaDir, manifestName).writeText(futureManifest)
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)
        val state = resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)

        // Unsupported differs from missing: ABSENT for planning, bytes untouched.
        state shouldBe null
        cache.containsKey(sentinel()) shouldBe true
        File(mangaDir, manifestName).readText() shouldBe futureManifest
    }
}
