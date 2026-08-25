package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterGlossary
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.CommittedBundleMetadata
import eu.kanade.translation.artifact.DisplayBaseKind
import eu.kanade.translation.artifact.DisplayBaseReference
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.PageArtifactRecord
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
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

    private fun ChapterTranslationStore.PageSnapshot.precondition() =
        ChapterTranslationStore.PatchPrecondition(
            generation = generation,
            pageVersion = pageVersion,
            blockFingerprints = blockFingerprints,
            leaseToken = leaseToken,
            candidateGenerationId = candidateGenerationId,
            dependencyFingerprint = dependencyFingerprint,
            artifactPageVersion = artifactPageVersion,
        )

    private fun writeLegacyChapter(
        cleanedBytes: ByteArray = pngBytes(100, 100),
        pageJson: JsonObject = Json.encodeToJsonElement(displayablePage()).jsonObject,
    ) {
        File(mangaDir, "Chapter 1_images").mkdirs()
        File(mangaDir, "Chapter 1_images/page.cleaned.abc.jpg").writeBytes(cleanedBytes)
        File(mangaDir, "Chapter 1.glossary.json").writeText("""{"sensei":"teacher"}""")
        File(mangaDir, "Chapter 1.json").writeText(
            buildJsonObject { put("page.jpg", pageJson) }.toString(),
        )
    }

    private fun legacyPageWithRemovedBatchContextFields(): JsonObject =
        Json.encodeToJsonElement(displayablePage()).jsonObject.toMutableMap().apply {
            put("batchContextCheckpointHash", JsonPrimitive("legacy-checkpoint"))
            put("batchContextComplete", JsonPrimitive(true))
            put("batchSceneCheckpoint", JsonPrimitive("legacy-scene"))
        }.let(::JsonObject)

    private fun translationFile(): UniFile =
        com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir).findFile("Chapter 1.json")
            ?: com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
                .findFile("Chapter 1.json.migrated")!!

    private fun readManifest(chapterName: String = "Chapter 1"): ChapterArtifactManifest =
        Json.decodeFromStream<ChapterArtifactManifest>(
            File(mangaDir, "$chapterName.manifest.json").inputStream(),
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

        File(mangaDir, "Chapter 1.json").exists() shouldBe false
        File(mangaDir, "Chapter 1.json.migrated").readBytes() shouldBe legacyBytes
    }

    @Test
    fun `opening a legacy chapter with removed batch context fields preserves page progress`() {
        installPngHeaderProbe()
        writeLegacyChapter(pageJson = legacyPageWithRemovedBatchContextFields())
        val legacyBytes = File(mangaDir, "Chapter 1.json").readBytes()

        val store = ChapterTranslationStore.open(translationFile())
        val page = store.state.value.getValue("page.jpg")

        page.blocks.single().translation shouldBe "hello"
        page.ocrStatus shouldBe StageStatus.READY
        page.translationStatus shouldBe StageStatus.READY
        page.inpaintStatus shouldBe StageStatus.READY
        page.renderStatus shouldBe StageStatus.READY
        readManifest().pages.keys shouldBe setOf("page.jpg")
        File(mangaDir, "Chapter 1.json").exists() shouldBe false
        File(mangaDir, "Chapter 1.json.migrated").readBytes() shouldBe legacyBytes
    }

    @Test
    fun `schema one artifact fixture rehydrates with no legacy flat file`() {
        val root = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        val layout = ChapterArtifactLayout("Chapter 1")
        val page = displayablePage().copy(translationStatus = StageStatus.PARTIAL)
        val snapshotFile = layout.committedPageSnapshotFile("page.jpg", "legacy-page.jpg")
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            pages = mapOf(
                "page.jpg" to PageArtifactRecord(
                    pageKey = "page.jpg",
                    pageVersion = 1L,
                    committed = CommittedBundleMetadata(
                        generationId = "legacy-page.jpg",
                        displayBase = DisplayBaseReference(kind = DisplayBaseKind.ORIGINAL_SOURCE),
                        origin = eu.kanade.translation.artifact.ArtifactOrigin.LEGACY,
                        provisional = true,
                        pageSnapshotFileName = snapshotFile,
                    ),
                    displayState = PageDisplayState.ORIGINAL_ONLY,
                ),
            ),
            expectedPageCount = 1,
            expectedPageCountTrusted = false,
            authority = ManifestAuthority.ARTIFACTS,
        )
        File(mangaDir, layout.manifestFileName).writeText(Json.encodeToString(manifest))
        File(mangaDir, snapshotFile).apply {
            parentFile?.mkdirs()
            writeText(Json.encodeToString(page))
        }

        val store = ChapterTranslationStore.openArtifact(root, "Chapter 1.json")

        File(mangaDir, "Chapter 1.json").exists() shouldBe false
        store.state.value.getValue("page.jpg").blocks.single().translation shouldBe "hello"
        store.state.value.getValue("page.jpg").translationStatus shouldBe StageStatus.PARTIAL
        store.display.value.getValue("page.jpg").translationStatus shouldBe StageStatus.PARTIAL
        store.artifactStatus() shouldBe eu.kanade.translation.model.Translation.State.READY_WITH_WARNINGS
    }

    @Test
    fun `pre-registered pages publish one durable baseline with only the changed candidate`() = runTest {
        installPngHeaderProbe()
        val root = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        val store = ChapterTranslationStore.lazy(
            fileCreator = { root.createFile("Chapter 2.json")!! },
            artifactParent = root,
            artifactFileName = "Chapter 2.json",
        )
        store.preRegisterPages(listOf("p1.jpg", "p2.jpg", "p3.jpg"))
        readManifest("Chapter 2").expectedPageCount shouldBe 3
        readManifest("Chapter 2").expectedPageCountTrusted shouldBe true
        store.updatePage("p1.jpg") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.flush()

        val manifest = readManifest("Chapter 2")
        manifest.expectedPageCount shouldBe 3
        manifest.pages.keys shouldBe setOf("p1.jpg", "p2.jpg", "p3.jpg")
        manifest.pages.getValue("p1.jpg").candidate shouldNotBe null
        manifest.pages.getValue("p2.jpg").candidate shouldBe null
        manifest.pages.getValue("p3.jpg").candidate shouldBe null
    }

    @Test
    fun `batch baseline certifies complete artifact pages`() = runTest {
        installPngHeaderProbe()
        val root = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        File(mangaDir, "Chapter 6_images").mkdirs()
        File(mangaDir, "Chapter 6_images/p1.cleaned.jpg").writeBytes(pngBytes(100, 100))
        File(mangaDir, "Chapter 6_images/p2.cleaned.jpg").writeBytes(pngBytes(100, 100))
        val store = ChapterTranslationStore.lazy(
            fileCreator = { error("batch fixture must use artifacts") },
            artifactParent = root,
            artifactFileName = "Chapter 6.json",
        )
        store.preRegisterPages(listOf("p1.jpg", "p2.jpg"))
        store.updatePage("p1.jpg") { displayablePage().copy(cleanedImageName = "p1.cleaned.jpg") }
        store.updatePage("p2.jpg") { displayablePage().copy(cleanedImageName = "p2.cleaned.jpg") }
        store.flush()

        store.artifactStatus() shouldBe eu.kanade.translation.model.Translation.State.TRANSLATED
    }

    @Test
    fun `missing expected pages warn while a durable stage failure remains error`() = runTest {
        val root = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        val store = ChapterTranslationStore.lazy(
            fileCreator = { error("artifact-only store must not create the flat file") },
            artifactParent = root,
            artifactFileName = "Chapter 7.json",
        )
        store.preRegisterPages(listOf("p1.jpg", "p2.jpg"))
        store.updatePage("p1.jpg") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.flush()

        store.artifactStatus() shouldBe eu.kanade.translation.model.Translation.State.READY_WITH_WARNINGS

        store.updatePage("p1.jpg") { PageTranslation(ocrStatus = StageStatus.FAILED) }
        store.flush()

        store.artifactStatus() shouldBe eu.kanade.translation.model.Translation.State.ERROR
        store.closeAndFlush()
    }

    @Test
    fun `artifact-only lazy store writes glossary without materializing flat compatibility file`() = runTest {
        val root = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        val store = ChapterTranslationStore.lazy(
            fileCreator = { error("artifact-only store must not create the flat file") },
            artifactParent = root,
            artifactFileName = "Chapter 2.json",
        )
        store.updatePage("page.jpg") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.updateGlossary(mapOf("sensei" to "teacher"))
        store.flush()

        File(mangaDir, "Chapter 2.json").exists() shouldBe false
        File(mangaDir, "Chapter 2.summary.json").exists() shouldBe false
        File(mangaDir, "Chapter 2.manifest.json").exists() shouldBe true
        readManifest("Chapter 2").expectedPageCount shouldBe 1
        readManifest("Chapter 2").expectedPageCountTrusted shouldBe false
        File(mangaDir, "Chapter 2_artifacts/glossary/chapter.glossary.1.json").exists() shouldBe true
    }

    @Test
    fun `first live mutation cuts over to artifact authority and legacy bytes cannot resync`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        val legacyBytes = File(mangaDir, "Chapter 1.json").readBytes()
        // First open performs the initial migration.
        ChapterTranslationStore.open(translationFile()).closeAndFlush()
        val firstManifest = readManifest()

        // The first production mutation is the Phase 3 authority cutover. The
        // manual edit is written to a candidate snapshot and promoted without
        // rewriting the legacy flat file.
        val store = ChapterTranslationStore.open(translationFile())
        store.updatePage("page.jpg") { page ->
            page!!.apply { blocks[0].userEditedAt = 4242L }
        }
        store.flush()
        store.closeAndFlush()
        val legacyBytesAfterCutover = File(mangaDir, "Chapter 1.json.migrated").readBytes()

        // Reopen: artifact authority ignores the unchanged legacy document and
        // reconstructs the promoted committed snapshot.
        val reopened = ChapterTranslationStore.open(translationFile())

        val cutOver = readManifest()
        cutOver.authority shouldBe eu.kanade.translation.artifact.ManifestAuthority.ARTIFACTS
        cutOver.legacySource shouldBe firstManifest.legacySource
        val committed = cutOver.pages.getValue("page.jpg").committed.shouldNotBeNull()
        committed.hasManualEdits shouldBe true
        reopened.display.value.getValue("page.jpg").blocks.single().userEditedAt shouldBe 4242L
        legacyBytesAfterCutover shouldBe legacyBytes
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
        record.committed.shouldNotBeNull().displayBase.kind shouldBe DisplayBaseKind.ORIGINAL_SOURCE
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
        record.committed.shouldNotBeNull().displayBase.kind shouldBe DisplayBaseKind.ORIGINAL_SOURCE
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
        record.committed.shouldNotBeNull().displayBase.kind shouldBe DisplayBaseKind.ORIGINAL_SOURCE
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

    @Test
    fun `artifact-authoritative reopen keeps committed display while incomplete candidate resumes`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        val first = ChapterTranslationStore.open(translationFile())
        first.updatePage("page.jpg") { page ->
            page!!.copy(
                cleanedImageName = null,
                ocrStatus = StageStatus.RUNNING,
                translationStatus = StageStatus.PENDING,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            )
        }
        first.flush()
        first.closeAndFlush()

        val afterProcessDeath = ChapterTranslationStore.open(translationFile())
        afterProcessDeath.display.value.getValue("page.jpg").cleanedImageName shouldBe "page.cleaned.abc.jpg"
        afterProcessDeath.state.value.getValue("page.jpg").ocrStatus shouldBe StageStatus.RUNNING

        File(mangaDir, "Chapter 1_images/page.cleaned.retried.jpg").writeBytes(pngBytes(100, 100))

        afterProcessDeath.updatePage("page.jpg") { page ->
            page!!.copy(
                cleanedImageName = "page.cleaned.retried.jpg",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                renderStatus = StageStatus.READY,
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            )
        }
        afterProcessDeath.flush()
        afterProcessDeath.closeAndFlush()

        val promotedReopen = ChapterTranslationStore.open(translationFile())
        promotedReopen.display.value.getValue("page.jpg").cleanedImageName shouldBe "page.cleaned.retried.jpg"
        promotedReopen.drainRetiredCleanedImages("page.jpg") shouldBe listOf("page.cleaned.abc.jpg")
    }

    @Test
    fun `artifact candidate cancel and failure reopen retain committed display`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        val first = ChapterTranslationStore.open(translationFile())
        first.updatePage("page.jpg") { page ->
            page!!.copy(
                cleanedImageName = null,
                ocrStatus = StageStatus.RUNNING,
                translationStatus = StageStatus.PENDING,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            )
        }
        first.clearTransientQueuePages("test cancel")
        first.flush()
        first.closeAndFlush()
        val afterCancel = ChapterTranslationStore.open(translationFile())
        afterCancel.display.value.getValue("page.jpg").cleanedImageName shouldBe "page.cleaned.abc.jpg"

        afterCancel.updatePage("page.jpg") { page ->
            page!!.copy(
                cleanedImageName = null,
                ocrStatus = StageStatus.FAILED,
                translationStatus = StageStatus.PENDING,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            )
        }
        afterCancel.flush()
        afterCancel.closeAndFlush()
        val afterFailure = ChapterTranslationStore.open(translationFile())
        afterFailure.display.value.getValue("page.jpg").cleanedImageName shouldBe "page.cleaned.abc.jpg"
        afterFailure.state.value.getValue("page.jpg").ocrStatus shouldBe StageStatus.FAILED
    }

    @Test
    fun `released batch callbacks cannot overwrite a reacquired artifact candidate`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        val store = ChapterTranslationStore.open(translationFile())
        val pageKey = "page.jpg"

        val writerA = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val aSnapshot = store.snapshot(pageKey)
        val aPrecondition = aSnapshot.precondition()
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)

        val writerB = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        writerB.token shouldNotBe writerA.token
        val bSnapshot = store.snapshot(pageKey)
        store.updatePageGuarded(pageKey, bSnapshot.precondition(), "batch B partial candidate") { page ->
            page!!.copy(
                blocks = mutableListOf(block("B")),
                cleanedImageName = null,
                ocrStatus = StageStatus.RUNNING,
                translationStatus = StageStatus.RUNNING,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            )
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        store.display.value.getValue(pageKey).cleanedImageName shouldBe "page.cleaned.abc.jpg"
        store.state.value.getValue(pageKey).blocks.single().translation shouldBe "B"
        val manifestAfterB = File(mangaDir, "Chapter 1.manifest.json").readBytes()

        val lateRender = store.updatePageGuarded(pageKey, aPrecondition, "late A render RUNNING") { page ->
            page!!.copy(renderStatus = StageStatus.RUNNING)
        }
        lateRender.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        val lateTranslation = store.updatePageGuarded(pageKey, aPrecondition, "late A final translation") { page ->
            page!!.copy(
                blocks = mutableListOf(block("late-A")),
                translationStatus = StageStatus.READY,
            )
        }
        lateTranslation.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()

        store.state.value.getValue(pageKey).blocks.single().translation shouldBe "B"
        store.display.value.getValue(pageKey).cleanedImageName shouldBe "page.cleaned.abc.jpg"
        store.committedDisplayPage(pageKey)?.cleanedImageName shouldBe "page.cleaned.abc.jpg"
        File(mangaDir, "Chapter 1.manifest.json").readBytes() shouldBe manifestAfterB

        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        store.closeAndFlush()
        val reopened = ChapterTranslationStore.open(translationFile())
        reopened.display.value.getValue(pageKey).cleanedImageName shouldBe "page.cleaned.abc.jpg"
        reopened.state.value.getValue(pageKey).blocks.single().translation shouldBe "B"
        File(mangaDir, "Chapter 1.manifest.json").readBytes() shouldBe manifestAfterB
        reopened.closeAndFlush()
    }

    @Test
    fun `production promotion retains a held previous cleaned stream until release`() = runTest {
        installPngHeaderProbe()
        writeLegacyChapter()
        val store = ChapterTranslationStore.open(translationFile())
        val imageDir = File(mangaDir, "Chapter 1_images")
        val previous = File(imageDir, "page.cleaned.abc.jpg")
        val next = File(imageDir, "page.cleaned.promoted.jpg").also { it.writeBytes(pngBytes(100, 100)) }
        val registry = TranslationStreamRegistry(cleanedRetirementGraceMs = 60_000L)
        val held = registry.openCleanedImageStream(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            pageKey = "page.jpg",
            imageName = previous.name,
        ) { FileInputStream(previous) }

        store.updatePage("page.jpg") { page ->
            page!!.copy(
                cleanedImageName = next.name,
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                renderStatus = StageStatus.READY,
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            )
        }
        store.flush()
        store.display.value.getValue("page.jpg").cleanedImageName shouldBe next.name

        store.drainRetiredCleanedImages("page.jpg") shouldBe listOf(previous.name)
        registry.retireCleanedImage(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            pageKey = "page.jpg",
            imageName = previous.name,
        ) { previous.delete() }
        held.read() shouldBe 0x89
        previous.exists() shouldBe true
        next.exists() shouldBe true

        held.close()
        previous.exists() shouldBe false
        next.exists() shouldBe true
        store.closeAndFlush()
        ChapterTranslationStore.open(translationFile()).display.value.getValue("page.jpg").cleanedImageName shouldBe next.name
    }

    @Test
    fun `lazy store cuts over before first candidate and reopens from artifact pointers`() = runTest {
        installPngHeaderProbe()
        val root = com.hippo.unifile.FakeUniFile(parent = null, backing = mangaDir)
        val store = ChapterTranslationStore.lazy(
            artifactParent = root,
            artifactFileName = "Chapter 2.json",
            fileCreator = { root.createFile("Chapter 2.json")!! },
        )
        store.updatePage("page.jpg") {
            PageTranslation(
                blocks = mutableListOf(block()),
                imgWidth = 100f,
                imgHeight = 100f,
                ocrStatus = StageStatus.RUNNING,
                translationStatus = StageStatus.PENDING,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            )
        }
        store.flush()
        store.closeAndFlush()

        val flatFile = File(mangaDir, "Chapter 2.json")
        flatFile.exists() shouldBe false
        val candidateManifest = readManifest("Chapter 2")
        candidateManifest.authority shouldBe eu.kanade.translation.artifact.ManifestAuthority.ARTIFACTS
        candidateManifest.expectedPageCount shouldBe 1
        candidateManifest.expectedPageCountTrusted shouldBe false
        candidateManifest.pages.getValue("page.jpg").committed shouldBe null
        candidateManifest.pages.getValue("page.jpg").candidate?.pageSnapshotFileName shouldNotBe null

        val reopened = ChapterTranslationStore.openArtifact(root, "Chapter 2.json")
        reopened.state.value.getValue("page.jpg").ocrStatus shouldBe StageStatus.RUNNING

        File(mangaDir, "Chapter 2_images").mkdirs()
        File(mangaDir, "Chapter 2_images/page.cleaned.retry.jpg").writeBytes(pngBytes(100, 100))
        reopened.updatePage("page.jpg") { page ->
            page!!.copy(
                cleanedImageName = "page.cleaned.retry.jpg",
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                renderStatus = StageStatus.READY,
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            )
        }
        reopened.flush()
        reopened.closeAndFlush()

        val promoted = ChapterTranslationStore.openArtifact(root, "Chapter 2.json")
        promoted.display.value.getValue("page.jpg").cleanedImageName shouldBe "page.cleaned.retry.jpg"
        readManifest("Chapter 2").pages.getValue("page.jpg").committed shouldNotBe null
        flatFile.exists() shouldBe false
    }
}
