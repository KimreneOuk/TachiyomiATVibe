package eu.kanade.translation.persistence

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.StageFingerprints
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * P3B reader fallback: a reader reopen without a writer store restores saved
 * OCR work from the page's verified recovery checkpoint (the P3C hydration's
 * provenance verification, consumed publication-free through the opener's
 * durable read). Newer candidate/committed work wins; an unverifiable
 * checkpoint never fails the read and never fabricates content.
 */
class ReaderCheckpointFallbackTest {

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun manifestFile(): File = File(mangaDir, "Chapter 1.manifest.json")

    private fun block(text: String) = TranslationBlock(
        text = "src-$text",
        translation = text,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(block("hello-$pageKey")),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    /** Writes one checkpointed (CLOSEd) OCR page through the real publication path. */
    private suspend fun writeCheckpointedChapter() {
        val store = ChapterTranslationStore.lazy(
            artifactParentResolver = { root().createFile("Chapter 1.json")!! },
            artifactParent = root(),
            artifactFileName = "Chapter 1.json",
        )
        store.preRegisterPages(listOf("p0.jpg"))
        val lease = store.tryAcquirePageStageLease("p0.jpg", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot("p0.jpg")
        store.mergeOcr(
            OcrStagePatch(
                pageKey = "p0.jpg",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage("p0.jpg"),
                expectedLeaseToken = lease.token,
            ),
            description = "reader fallback fixture merge",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot("p0.jpg")
        store.checkpointOcr(
            pageKey = "p0.jpg",
            generation = after.generation,
            expectedPageVersion = after.pageVersion,
            expectedLeaseToken = lease.token,
            expectedCandidateGenerationId = after.candidateGenerationId.shouldNotBeNull(),
            expectedArtifactPageVersion = after.artifactPageVersion,
            expectedDependencyFingerprint = after.dependencyFingerprint.shouldNotBeNull(),
            sourceSha256 = hex64("source-p0.jpg"),
            sourceOrientation = "PORTRAIT",
        ).shouldBeInstanceOf<CheckpointOcrResult.Committed>()
        store.releasePageStageLease("p0.jpg", PageWriteOrigin.BATCH)
        store.closeAndFlush()
    }

    private suspend fun readChapter() = ChapterStoreOpener.readDurableChapter(
        root(),
        "Chapter 1.json",
        privateStorageRoot = null,
        privateStorageIdentity = null,
    )

    private fun treeSnapshot(): Map<String, ByteArray> {
        val snapshot = mutableMapOf<String, ByteArray>()
        fun walk(dir: File, prefix: String) {
            dir.listFiles()?.forEach { child ->
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    snapshot["dir:$path"] = ByteArray(0)
                    walk(child, path)
                } else {
                    snapshot[path] = child.readBytes()
                }
            }
        }
        walk(mangaDir, "")
        return snapshot
    }

    @Test
    fun `reader reopen restores saved OCR from the verified checkpoint without a writer store`() = runTest {
        writeCheckpointedChapter()
        val manifest = ArtifactDocumentJson.decodeFromString(
            ChapterArtifactManifest.serializer(),
            manifestFile().readText(),
        )
        // Fixture precondition: the CLOSEd candidate left NO content-backed
        // snapshot pointer — the checkpoint is the only OCR authority.
        val record = manifest.pages.getValue("p0.jpg")
        record.candidate shouldBe null
        record.committed shouldBe null
        val before = treeSnapshot()

        val read = readChapter()

        val state = (read as DurableChapterRead.Supported).state
        val restored = state.pages.getValue("p0.jpg")
        restored.blocks.map { it.translation } shouldBe listOf("hello-p0.jpg")        // The restored OCR also seeds the display projection (no committed
        // bundle exists to outrank it).
        state.display.getValue("p0.jpg").blocks.map { it.translation } shouldBe listOf("hello-p0.jpg")
        // Publication-free: the durable read wrote, deleted, and renamed nothing.
        treeSnapshot() shouldBe before
    }

    @Test
    fun `an unverifiable checkpoint identity is never restored`() = runTest {
        writeCheckpointedChapter()
        val manifest = ArtifactDocumentJson.decodeFromString(
            ChapterArtifactManifest.serializer(),
            manifestFile().readText(),
        )
        // Corrupt the recorded source identity: the checkpoint can no longer
        // prove it belongs to the observed source bytes.
        manifestFile().writeText(
            ArtifactDocumentJson.encodeToString(
                ChapterArtifactManifest.serializer(),
                manifest.copy(sourceShaByPageKey = mapOf("p0.jpg" to hex64("tampered"))),
            ),
        )
        val before = treeSnapshot()

        val read = readChapter()

        val state = (read as DurableChapterRead.Supported).state
        state.pages.getValue("p0.jpg").blocks shouldBe emptyList()
        treeSnapshot() shouldBe before
    }

    @Test
    fun `a candidate-less chapter without checkpoints still reads as missing content`() = runTest {
        writeCheckpointedChapter()
        val manifest = ArtifactDocumentJson.decodeFromString(
            ChapterArtifactManifest.serializer(),
            manifestFile().readText(),
        )
        manifestFile().writeText(
            ArtifactDocumentJson.encodeToString(
                ChapterArtifactManifest.serializer(),
                manifest.copy(ocrCheckpoints = emptyMap()),
            ),
        )

        val read = readChapter()

        val state = (read as DurableChapterRead.Supported).state
        state.pages.getValue("p0.jpg").blocks shouldBe emptyList()
        state.pages.keys shouldNotBe emptySet<String>()
    }
}
