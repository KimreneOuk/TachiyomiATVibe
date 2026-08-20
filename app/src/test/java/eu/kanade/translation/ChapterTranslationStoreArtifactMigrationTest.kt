package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterGlossary
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Production-path coverage for the artifact manifest hook wired into
 * [ChapterTranslationStore.open]: a legacy chapter opened through the store
 * gains a sibling manifest and versioned glossary sidecar; a later legacy
 * mutation and persist forces a resync to the newest authoritative bytes.
 *
 * The cleaned-image validation seam ([ChapterTranslationStore.artifactImageProbe])
 * is pointed at a header-parsing PNG probe because android.graphics is not
 * available in JVM unit tests; production keeps the BitmapFactory
 * bounds-only probe.
 */
class ChapterTranslationStoreArtifactMigrationTest {

    @TempDir
    lateinit var mangaDir: File

    @AfterEach
    fun restoreProductionProbe() {
        ChapterTranslationStore.artifactImageProbe = eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
    }

    /** Complete PNG fixture; the production path never validates fabricated text/header-only bytes. */
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

    /** JVM stand-in for the BitmapFactory bounds probe: parses the PNG IHDR. */
    private fun installPngHeaderProbe() {
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { input ->
            val bytes = input.readPrefix(24)
            if (bytes.size < 24) return@CleanedImageProbe null
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            if (!bytes.copyOfRange(0, 8).contentEquals(signature)) return@CleanedImageProbe null
            if (String(bytes.copyOfRange(12, 16)) != "IHDR") return@CleanedImageProbe null
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

    private fun block(translation: String = "hello") = TranslationBlock(
        text = "source",
        translation = translation,
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

    private fun writeLegacyChapter(cleanedBytes: ByteArray = pngBytes(100, 100)) {
        File(mangaDir, "Chapter 1_images").mkdirs()
        File(mangaDir, "Chapter 1_images/page.cleaned.abc.jpg").writeBytes(cleanedBytes)
        File(mangaDir, "Chapter 1.glossary.json").writeText("""{"sensei":"teacher"}""")
        File(mangaDir, "Chapter 1.json").writeText(
            Json.encodeToString(mapOf("page.jpg" to displayablePage())),
        )
    }

    private fun translationFile(): UniFile =
        com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir).findFile("Chapter 1.json")!!

    private fun readManifest(): ChapterArtifactManifest =
        Json.decodeFromStream<ChapterArtifactManifest>(
            File(mangaDir, "Chapter 1.manifest.json").inputStream(),
        )

    @Test
    fun `opening a legacy chapter migrates the artifact manifest non-destructively`() {
        installPngHeaderProbe()
        writeLegacyChapter()
        val legacyBytes = File(mangaDir, "Chapter 1.json").readBytes()

        val store = ChapterTranslationStore.open(translationFile())

        store.state.value.getValue("page.jpg").cleanedImageName shouldBe "page.cleaned.abc.jpg"
        val manifest = readManifest()
        manifest.chapterKey shouldBe "Chapter 1"
        val record = manifest.pages.getValue("page.jpg")
        val committed = record.committed.shouldNotBeNull()
        committed.provisional shouldBe true
        committed.displayBase.legacyLayout shouldBe true
        committed.displayBase.fileName shouldBe "page.cleaned.abc.jpg"
        committed.displayBase.validated shouldBe true
        record.displayState shouldBe PageDisplayState.DISPLAY_READY
        manifest.legacySource shouldNotBe null

        val glossary = Json.decodeFromStream<ChapterGlossary>(
            File(mangaDir, "Chapter 1_artifacts/glossary/chapter.glossary.1.json").inputStream(),
        )
        glossary.kind shouldBe ChapterGlossary.KIND_VOCABULARY_HINTS
        glossary.entries shouldBe mapOf("sensei" to "teacher")

        File(mangaDir, "Chapter 1.json").readBytes() shouldBe legacyBytes
    }

    @Test
    fun `legacy store mutation and persist forces a resync on reopen`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        // First open performs the initial migration.
        ChapterTranslationStore.open(translationFile()).closeAndFlush()
        val firstManifest = readManifest()

        // Production mutation through the still-authoritative flat store:
        // a manual edit on an existing block.
        val store = ChapterTranslationStore.open(translationFile())
        store.updatePage("page.jpg") { page ->
            page!!.apply { blocks[0].userEditedAt = 4242L }
        }
        store.flush()
        store.closeAndFlush()
        val mutatedBytes = File(mangaDir, "Chapter 1.json").readBytes()

        // Reopen: the changed authoritative identity must trigger a resync.
        ChapterTranslationStore.open(translationFile())

        val resynced = readManifest()
        resynced.legacySource shouldNotBe firstManifest.legacySource
        val committed = resynced.pages.getValue("page.jpg").committed.shouldNotBeNull()
        committed.hasManualEdits shouldBe true
        // The resynced manifest describes the newest legacy bytes, not the
        // stale first-migration snapshot.
        mutatedBytes.size shouldBe resynced.legacySource!!.lengthBytes.toInt()
        // Idempotence: reopening again without further mutation is stable.
        val stable = readManifest()
        ChapterTranslationStore.open(translationFile())
        readManifest() shouldBe stable
    }

    @Test
    fun `reopening without legacy changes takes the manifest fast path`() {
        installPngHeaderProbe()
        writeLegacyChapter()
        ChapterTranslationStore.open(translationFile())
        val manifestBytes = File(mangaDir, "Chapter 1.manifest.json").readBytes()

        ChapterTranslationStore.open(translationFile())

        File(mangaDir, "Chapter 1.manifest.json").readBytes() shouldBe manifestBytes
        File(mangaDir, "Chapter 1.manifest.json.bak").exists() shouldBe false
    }

    @Test
    fun `non-empty undecodable cleaned bytes are never committed`() {
        installPngHeaderProbe()
        writeLegacyChapter(cleanedBytes = "definitely not an image".toByteArray())

        ChapterTranslationStore.open(translationFile())

        val record = readManifest().pages.getValue("page.jpg")
        record.committed.shouldBeNull()
        record.legacyVisible.shouldBeNull()
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
        record.inpaint shouldNotBe null
    }

    @Test
    fun `wrong-dimension cleaned image is never committed`() {
        installPngHeaderProbe()
        // Page records 100x100; the file decodes as 64x64.
        writeLegacyChapter(cleanedBytes = pngBytes(64, 64))

        ChapterTranslationStore.open(translationFile())

        val record = readManifest().pages.getValue("page.jpg")
        record.committed.shouldBeNull()
        record.legacyVisible.shouldBeNull()
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
        record.inpaint shouldNotBe null
    }

    @Test
    fun `chapter without a legacy translation file gains no manifest`() {
        installPngHeaderProbe()
        val root: UniFile = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        ChapterTranslationStore.lazy { root.createFile("Chapter 2.json")!! }
        File(mangaDir, "Chapter 2.manifest.json").exists() shouldBe false
    }

    @Test
    fun `missing companion image maps to failed-no-result with corrupt inpaint`() {
        installPngHeaderProbe()
        File(mangaDir, "Chapter 1_images").mkdirs()
        File(mangaDir, "Chapter 1.json").writeText(
            Json.encodeToString(mapOf("page.jpg" to displayablePage())),
        )

        ChapterTranslationStore.open(translationFile())

        val record = readManifest().pages.getValue("page.jpg")
        record.committed shouldBe null
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
        record.inpaint shouldNotBe null
    }

    @Test
    fun `corrupt legacy json still opens the store and records an empty migration`() {
        installPngHeaderProbe()
        File(mangaDir, "Chapter 1.json").writeText("{ not json")

        val store = ChapterTranslationStore.open(translationFile())

        store.state.value.isEmpty() shouldBe true
        val manifest = readManifest()
        manifest.pages.isEmpty() shouldBe true
        manifest.migratedFromLegacyAtEpochMs shouldNotBe null
        File(mangaDir, "Chapter 1.json").readText() shouldBe "{ not json"
    }
}
