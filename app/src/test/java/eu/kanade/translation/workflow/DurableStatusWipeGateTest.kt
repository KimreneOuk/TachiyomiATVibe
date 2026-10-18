package eu.kanade.translation.workflow

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Pins the two no-wipe gates of `DurableChapterStatusResolver.withProbeStore`:
 * the whole-cache wipe fires only when a
 * probe pass actually CREATES the store — the one moment an open can advance
 * durable truth through journal recovery or seeding. A held (reused) probe performed no
 * open, and an already-active store short-circuits before the probe path;
 * neither may thrash the cache.
 *
 * The sentinel key is one the resolver can never produce for this chapter,
 * because a created probe pass may legitimately re-cache the resolved status
 * after its wipe — the sentinel must vanish on a wipe and survive without
 * one.
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

    /** Complete PNG fixture; the production path never validates fabricated bytes. */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))

        fun writeChunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
            DataOutputStream(output).writeInt(data.size)
            output.write(typeBytes)
            output.write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }
            DataOutputStream(output).writeInt(crc.value.toInt())
        }

        val header = ByteArrayOutputStream().also { headerBytes ->
            DataOutputStream(headerBytes).use { headerOutput ->
                headerOutput.writeInt(width)
                headerOutput.writeInt(height)
                headerOutput.writeByte(8)
                headerOutput.writeByte(6)
                headerOutput.writeByte(0)
                headerOutput.writeByte(0)
                headerOutput.writeByte(0)
            }
        }.toByteArray()
        writeChunk("IHDR", header)

        val scanlines = ByteArray((width * 4 + 1) * height)
        val compressed = ByteArrayOutputStream().also { compressedBytes ->
            DeflaterOutputStream(compressedBytes).use { it.write(scanlines) }
        }.toByteArray()
        writeChunk("IDAT", compressed)
        writeChunk("IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun block() = TranslationBlock(
        text = "source",
        translation = "hello",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun displayablePage() = PageTranslation(
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun writeLegacyChapter() {
        File(mangaDir, "Chapter 1_images").mkdirs()
        File(mangaDir, "Chapter 1_images/page.cleaned.abc.jpg").writeBytes(pngBytes(100, 100))
        File(mangaDir, "Chapter 1.json").writeText(
            buildJsonObject { put("page.jpg", Json.encodeToJsonElement(displayablePage()).jsonObject) }.toString(),
        )
    }

    private fun artifactParent() = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactFileName() = "Chapter 1.json"

    private fun provider(): TranslationFileProvider {
        // The directory/name pair identifies the manifest. The preserved flat
        // file is not read or migrated by this artifact-only resolver.
        val parent = artifactParent()
        val provider = mockk<TranslationFileProvider>()
        every { provider.findMangaDir(any(), any()) } returns parent
        every { provider.getTranslationFileName(any(), any()) } answers { "${firstArg<String>()}.json" }
        every { provider.privateJournalRoot } returns File(mangaDir, "private-journal").apply { mkdirs() }
        every { provider.privateJournalIdentity(any(), any(), any()) } returns "wipe-gate:Chapter 1.json"
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

    @Test
    fun `a created probe pass wipes the durable status cache while a held probe does not`() = runTest {
        writeLegacyChapter()
        // Materialize the artifact manifest beside the preserved flat file;
        // the open path never decodes or renames that file.
        ChapterTranslationStore.openArtifactSuspend(artifactParent(), artifactFileName()).closeAndFlush()
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        // Created probe: recovery or seeding can advance durable truth, so
        // pre-existing cached statuses must not survive the pass.
        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)
        resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)
        cache.containsKey(sentinel()) shouldBe false

        // Held probe (the concurrent-second-pass shape): no creation, no
        // open, no wipe. Pass 1's probe was released and evicted in its
        // finally, so the hold itself must create the store it lends.
        val document = resolver.findTranslationDocument("Chapter 1", null, "Manga", source)!!
        val held = registry.getOrCreateProbe(document.registryKey) {
            ChapterTranslationStore.openArtifactSuspend(document.parent, document.fileName)
        }!!
        held.created shouldBe true
        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)
        resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)
        cache.containsKey(sentinel()) shouldBe true

        registry.releaseProbe(document.registryKey, held.store)
    }

    @Test
    fun `an active store short-circuits the probe path without wiping`() = runTest {
        writeLegacyChapter()
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        registry.register(42L, ChapterTranslationStore.openArtifactSuspend(artifactParent(), artifactFileName()))
        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)

        resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)

        cache.containsKey(sentinel()) shouldBe true
    }
}
