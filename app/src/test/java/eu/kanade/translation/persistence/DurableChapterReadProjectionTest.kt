package eu.kanade.translation.persistence

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.workflow.withDurablePause
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins the P3D publication-free durable read: the five-way read
 * classification (Supported / Unsupported / Missing / Corrupt / Unavailable)
 * and the pure status projection over the verified hydration.
 *
 * Every classified read must perform ZERO writes, deletes, quarantine
 * renames, or backup promotions — the file tree must be byte-identical
 * before and after each read.
 */
class DurableChapterReadProjectionTest {

    @TempDir
    lateinit var mangaDir: File

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    /** The current-format manifest document name for this chapter's stem. */
    private fun manifestFile(): File = File(mangaDir, "Chapter 1.manifest.json")

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

    /** Materializes one committed page through the real write path. */
    private suspend fun writeCommittedChapter() {
        val store = ChapterStoreOpener.openArtifactSuspend(root(), "Chapter 1.json")
        store.updatePage("page.jpg") { displayablePage() }
        store.closeAndFlush()
    }

    /** Recursive path-to-bytes snapshot of the whole manga directory. */
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

    private suspend fun readChapter() = ChapterStoreOpener.readDurableChapter(
        root(),
        "Chapter 1.json",
        privateStorageRoot = null,
        privateStorageIdentity = null,
    )

    private fun hex(seed: String): String = StageFingerprints.sha256Hex(seed.toByteArray())

    @Test
    fun `supported read projects the committed status with zero writes`() = runTest {
        writeCommittedChapter()
        val before = treeSnapshot()

        val read = readChapter()

        val state = (read as DurableChapterRead.Supported).state
        state.manifest.pages.keys shouldBe setOf("page.jpg")
        state.pages.keys shouldBe setOf("page.jpg")
        state.display.keys shouldBe setOf("page.jpg")
        ChapterStatusProjection.artifactStatus(state.statusProjectionData()) { null } shouldBe
            Translation.State.READY_WITH_WARNINGS
        treeSnapshot() shouldBe before
    }

    @Test
    fun `a corrupt primary with a valid backup reads truth without promotion`() = runTest {
        writeCommittedChapter()
        val manifest = manifestFile()
        val backup = File(mangaDir, "Chapter 1.manifest.json.bak")
        backup.writeBytes(manifest.readBytes())
        manifest.writeText("{ broken json")
        val before = treeSnapshot()

        val read = readChapter()

        // The backup supplies read truth; the corrupt primary is preserved
        // untouched and nothing is quarantined or promoted.
        val state = (read as DurableChapterRead.Supported).state
        state.manifest.expectedPageCount shouldBe 1
        ChapterStatusProjection.artifactStatus(state.statusProjectionData()) { null } shouldBe
            Translation.State.READY_WITH_WARNINGS
        manifest.readText() shouldBe "{ broken json"
        treeSnapshot() shouldBe before
    }

    @Test
    fun `a corrupt manifest without a backup classifies corrupt and writes nothing`() = runTest {
        manifestFile().writeText("{ broken json")
        val before = treeSnapshot()

        val read = readChapter()

        val corrupt = read as DurableChapterRead.Corrupt
        (corrupt.cause is SerializationException) shouldBe true
        treeSnapshot() shouldBe before
    }

    @Test
    fun `an absent manifest classifies missing`() = runTest {
        readChapter() shouldBe DurableChapterRead.Missing
    }

    @Test
    fun `an unsupported future schema preserves the bytes and counts absent`() = runTest {
        val future = ChapterArtifactManifest(
            schemaVersion = 99,
            chapterKey = "Chapter 1",
            pages = mapOf("page.jpg" to PageArtifactRecord(pageKey = "page.jpg")),
        )
        val encoded = ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), future)
        manifestFile().writeText(encoded)
        val before = treeSnapshot()

        val read = readChapter()

        read shouldBe DurableChapterRead.Unsupported.Newer(99)
        treeSnapshot() shouldBe before
    }

    @Test
    fun `an unsupported older schema classifies separately from newer and missing`() = runTest {
        val older = ChapterArtifactManifest(
            schemaVersion = ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION - 1,
            chapterKey = "Chapter 1",
            pages = mapOf("page.jpg" to PageArtifactRecord(pageKey = "page.jpg")),
        )
        manifestFile().writeText(
            ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), older),
        )
        val before = treeSnapshot()

        val read = readChapter()

        read shouldBe DurableChapterRead.Unsupported.Older(ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION - 1)
        read shouldNotBe DurableChapterRead.Missing
        treeSnapshot() shouldBe before
    }

    @Test
    fun `a retryable durable failure projects the paused affordance from the file-only read`() = runTest {
        val failure = DurableFailureMetadata(
            pageKey = "p0",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.TRANSIENT,
            retryCount = 1,
            lastFailureMessage = "translation provider failed",
            lastFailedAtEpochMs = 1_000L,
            nextEligibleRetryAtEpochMs = 2_000L,
        )
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            durableFailures = mapOf("p0:TRANSLATION" to failure),
        )
        manifestFile().writeText(
            ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), manifest),
        )

        val read = readChapter()
        val state = (read as DurableChapterRead.Supported).state
        ChapterStatusProjection.artifactStatus(state.statusProjectionData()) { null } shouldBe
            Translation.State.PAUSED
        val paused = TranslationProgressSnapshot.empty(
            7L,
            Translation.State.PAUSED,
        ).withDurablePause(state.durableFailures)
        paused.pauseAnchorPageKey shouldBe "p0"
        paused.pauseReason shouldBe "translation provider failed"
        paused.nextEligibleRetryAtEpochMs shouldBe 2_000L
    }

    @Test
    fun `a complete run record over committed pages projects translated`() = runTest {
        val runRecord = ChapterRunRecord(
            runId = "run-1",
            state = ChapterRunState.COMPLETE,
            frozenConfig = RunConfigSnapshot(
                sourceLang = "en",
                targetLang = "ja",
                ocrEngine = "manga-ocr",
                ocrModelHash = hex("ocr"),
                detectorModelHash = hex("det"),
                inpaintMode = "auto",
                providerKey = "gemini:gemini-2.5",
                protocolVersion = 1,
                readingOrderVersion = 1,
            ),
            frozenRunConfigFingerprint = hex("cfg"),
            orderedSourceDigest = hex("src"),
            analysisPolicyFingerprint = hex("ap"),
            envelopePolicyFingerprint = hex("ep"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )
        val manifest = ChapterArtifactManifest(
            chapterKey = "Chapter 1",
            pages = mapOf(
                "001.jpg" to PageArtifactRecord(
                    pageKey = "001.jpg",
                    displayState = PageDisplayState.DISPLAY_READY,
                    committed = CommittedBundleMetadata(
                        generationId = "g1",
                        displayBase = DisplayBaseReference(
                            kind = DisplayBaseKind.CLEANED_IMAGE,
                            fileName = "001.cleaned.jpg",
                            validated = true,
                        ),
                        promotedAtEpochMs = 1L,
                    ),
                ),
            ),
            expectedPageCount = 1,
            expectedPageCountTrusted = true,
            activeRun = SidecarPointer(
                fileName = "runs/run-1.json",
                schemaVersion = ChapterRunRecord.SCHEMA_VERSION,
                contentFingerprint = hex("run"),
            ),
        )
        manifestFile().writeText(
            ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), manifest),
        )
        File(mangaDir, "runs").mkdirs()
        File(mangaDir, "runs/run-1.json").writeText(
            ArtifactDocumentJson.encodeToString(ChapterRunRecord.serializer(), runRecord),
        )
        val before = treeSnapshot()

        val read = readChapter()

        val state = (read as DurableChapterRead.Supported).state
        state.activeRunRecord?.state shouldBe ChapterRunState.COMPLETE
        ChapterStatusProjection.artifactStatus(state.statusProjectionData()) { state.activeRunRecord } shouldBe
            Translation.State.TRANSLATED
        treeSnapshot() shouldBe before
    }
}
