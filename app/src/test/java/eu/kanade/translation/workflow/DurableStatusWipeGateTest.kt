package eu.kanade.translation.workflow

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.CleanedImageProbe
import eu.kanade.translation.persistence.artifact.ProbedImage
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Pins the two no-wipe gates of `DurableChapterStatusResolver.withProbeStore`:
 * the whole-cache wipe fires only when a
 * probe pass actually CREATES the store — the one moment an open can advance
 * durable truth via the one-way rescue. A held (reused) probe performed no
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

    @AfterEach
    fun restoreProductionProbe() {
        ChapterTranslationStore.artifactImageProbe = eu.kanade.translation.persistence.artifact.BitmapFactoryCleanedImageProbe
    }

    private fun sentinel() = DurableChapterKey(null, "wipe-sentinel", null, "wipe-sentinel", -1L)

    /** JVM stand-in for the BitmapFactory bounds probe: parses the PNG IHDR. */
    private fun installPngHeaderProbe() {
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { input ->
            val bytes = input.readPrefix(24)
            if (bytes.size < 24) return@CleanedImageProbe null
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            if (!bytes.copyOfRange(0, 8).contentEquals(signature)) return@CleanedImageProbe null
            if (String(bytes.copyOfRange(12, 16), StandardCharsets.US_ASCII) != "IHDR") {
                return@CleanedImageProbe null
            }
            fun be32(offset: Int): Int =
                ((bytes[offset].toInt() and 0xff) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                    (bytes[offset + 3].toInt() and 0xff)
            val width = be32(16)
            val height = be32(20)
            if (width <= 0 || height <= 0) return@CleanedImageProbe null
            ProbedImage(width, height)
        }
    }

    private fun InputStream.readPrefix(limit: Int): ByteArray {
        val output = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val read = read(output, offset, limit - offset)
            if (read < 0) break
            offset += read
        }
        return output.copyOf(offset)
    }

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

    private fun translationFile(): UniFile =
        com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir).findFile("Chapter 1.json")
            ?: com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
                .findFile("Chapter 1.json.migrated")!!

    private fun provider(): TranslationFileProvider {
        // Post-migration production shape: the flat "Chapter 1.json" is gone
        // (renamed .migrated on disk), so the document resolves name-only
        // from the manga dir and opens through openArtifact.
        val parent = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        val provider = mockk<TranslationFileProvider>()
        every { provider.findTranslationFile(any(), any(), any(), any()) } returns null
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

    @Test
    fun `a created probe pass wipes the durable status cache while a held probe does not`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        // Materialize the artifact manifest: with only the legacy flat file
        // on disk, status resolution takes the legacy-decode branch and never
        // reaches the probe path at all.
        ChapterTranslationStore.open(translationFile()).closeAndFlush()
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        // Created probe: the store open can advance durable truth (one-way
        // rescue), so pre-existing cached statuses must not survive the pass.
        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)
        resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)
        cache.containsKey(sentinel()) shouldBe false

        // Held probe (the concurrent-second-pass shape): no creation, no
        // open, no wipe. Pass 1's probe was released and evicted in its
        // finally, so the hold itself must create the store it lends.
        val document = resolver.findTranslationDocument("Chapter 1", null, "Manga", source)!!
        val held = registry.getOrCreateProbe(document.registryKey) {
            ChapterTranslationStore.openArtifact(document.parent, document.fileName)
        }!!
        held.created shouldBe true
        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)
        resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)
        cache.containsKey(sentinel()) shouldBe true

        registry.releaseProbe(document.registryKey, held.store)
    }

    @Test
    fun `an active store short-circuits the probe path without wiping`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        val registry = ActiveChapterStoreRegistry()
        val resolver = resolver(provider(), registry)
        val cache = durableStatusCache(resolver)

        registry.register(42L, ChapterTranslationStore.open(translationFile()))
        cache[sentinel()] = DurableStatus(Translation.State.NOT_TRANSLATED)

        resolver.persistedChapterStatus(42L, "Chapter 1", null, "Manga", 77L)

        cache.containsKey(sentinel()) shouldBe true
    }
}
