package eu.kanade.translation

import eu.kanade.translation.pipeline.*

import eu.kanade.translation.storage.*

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.CommittedBundleMetadata
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class ChapterTranslationStorePersistenceTest {

    @TempDir
    lateinit var mangaDir: File

    @AfterEach
    fun restoreProductionProbe() {
        ChapterTranslationStore.artifactImageProbe = eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
    }

    private fun block(translation: String = "target", userEditedAt: Long? = null) = TranslationBlock(
        text = "source",
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        userEditedAt = userEditedAt,
    )

    @Test
    fun `failed persist remains dirty for a later retry`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        store.updatePage("page") { PageTranslation(blocks = mutableListOf(block())) }
        store.flush()
        store.persistCount shouldBe 1

        store.flush()
        store.persistCount shouldBe 2
    }

    // ------------------------------------------------------------------
    // T924 gate 1.5: user-edit authority across checkpoint transitions
    // ------------------------------------------------------------------

    /** The user edit under test: a committed block the reader overrode. */
    private val userEditedAt = 1_757_050_000_000L

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun ocrPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(block(translation = "user override", userEditedAt = userEditedAt)),
        imgWidth = 100f,
        imgHeight = 100f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    /** Display-ready shape of [ocrPage]: the user-edited block is committed. */
    private fun committedPage(pageKey: String) = ocrPage(pageKey).apply {
        translationStatus = StageStatus.READY
        inpaintStatus = StageStatus.READY
        renderStatus = StageStatus.READY
        cleanedImageName = "p1.cleaned.jpg"
    }

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        fileCreator = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    /** The committed bundle must still carry the user's edited block, intact. */
    private fun assertUserEditIntact(
        artifact: ChapterArtifactEngine,
        committed: CommittedBundleMetadata,
    ) {
        val snapshot = artifact
            .readPageSnapshot(committed.pageSnapshotFileName.shouldNotBeNull())
            .shouldNotBeNull()
        val edited = snapshot.blocks.single { it.userEditedAt != null }
        edited.userEditedAt shouldBe userEditedAt
        edited.translation shouldBe "user override"
        edited.text shouldBe "source"
    }

    /**
     * T924 gate 1.5 (contract stage0/feature-flags-stage-gates.md §2.1 row 1.5):
     * a committed page carrying a user-edited block survives checkpointOcr in
     * BOTH forms — the CLOSE branch (active BATCH candidate) and the TX-03.1
     * adopt branch after a restart — with the committed bundle identity
     * (pointer) and the user edit intact. The OCR content fingerprint excludes
     * user edits (T924-FP-01), so the adopt-side drift comparison must accept
     * the user-modified committed bundle, never reject or overwrite it.
     */
    @Test
    fun `user edited committed block survives checkpointOcr close and adopt with bundle identity intact`() = runTest {
        installPngHeaderProbe()
        File(mangaDir, "Chapter 1_images").mkdirs()
        File(mangaDir, "Chapter 1_images/p1.cleaned.jpg").writeBytes(pngBytes(100, 100))
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))

        // ---- The reader commits a display-ready page whose block the user
        // edited afterwards (userEditedAt + override translation). ----
        store.updatePageGuarded(
            pageKey = "p1",
            expected = store.snapshot("p1").toPrecondition(),
            description = "gate 1.5 manual commit",
        ) { committedPage("p1") }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        val committedBefore = artifactStore().readManifest().shouldNotBeNull()
            .pages.getValue("p1").committed.shouldNotBeNull()
        assertUserEditIntact(artifactStore(), committedBefore)

        // ---- CLOSE form: a BATCH candidate closes over the committed page;
        // the committed bundle pointer and the user edit are untouched. ----
        val lease = store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot("p1")
        store.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage("p1"),
                expectedLeaseToken = lease.token,
            ),
            description = "gate 1.5 close merge",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot("p1")
        store.checkpointOcr(
            pageKey = "p1",
            generation = after.generation,
            expectedPageVersion = after.pageVersion,
            expectedLeaseToken = lease.token,
            expectedCandidateGenerationId = after.candidateGenerationId.shouldNotBeNull(),
            expectedArtifactPageVersion = after.artifactPageVersion,
            expectedDependencyFingerprint = after.dependencyFingerprint.shouldNotBeNull(),
            sourceSha256 = hex64("source-p1"),
            sourceOrientation = "PORTRAIT",
        ).shouldBeInstanceOf<CheckpointOcrResult.Committed>()
        store.releasePageStageLease("p1", PageWriteOrigin.BATCH)
        val afterClose = artifactStore().readManifest().shouldNotBeNull()
        afterClose.pages.getValue("p1").committed shouldBe committedBefore
        assertUserEditIntact(artifactStore(), committedBefore)

        // ---- Adopt (TX-03.1) form: after a simulated restart the Batch
        // checkpoint is adopted against the user-edited committed bundle; the
        // drift comparison accepts it (user edits are excluded from the OCR
        // content fingerprint) and the bundle identity stays intact. ----
        val reopened = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val adoptLease = reopened.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val adoptSnapshot = reopened.snapshot("p1")
        reopened.checkpointOcr(
            pageKey = "p1",
            generation = adoptSnapshot.generation,
            expectedPageVersion = adoptSnapshot.pageVersion,
            expectedLeaseToken = adoptLease.token,
            expectedCandidateGenerationId = null,
            expectedArtifactPageVersion = adoptSnapshot.artifactPageVersion,
            expectedDependencyFingerprint = null,
            sourceSha256 = hex64("source-p1"),
            sourceOrientation = "PORTRAIT",
        ).shouldBeInstanceOf<CheckpointOcrResult.Committed>()
        reopened.releasePageStageLease("p1", PageWriteOrigin.BATCH)
        val afterAdopt = artifactStore().readManifest().shouldNotBeNull()
        afterAdopt.pages.getValue("p1").committed shouldBe committedBefore
        afterAdopt.pages.getValue("p1").candidate shouldBe null
        afterAdopt.ocrCheckpoints.keys shouldBe setOf("p1")
        assertUserEditIntact(artifactStore(), committedBefore)
    }

    /**
     * JVM stand-in for the BitmapFactory bounds probe: parses the PNG IHDR
     * (android.graphics is unavailable in JVM unit tests).
     */
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
        var read = 0
        while (read < limit) {
            val chunk = read(output, read, limit - read)
            if (chunk < 0) break
            read += chunk
        }
        return output.copyOf(read)
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
}
