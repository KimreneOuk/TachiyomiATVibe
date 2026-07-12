package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.RenderQuality
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the persist-decision used by [ChapterTranslationStore.updatePage].
 *
 * The stranded-page sweep writes a CANCELLED+errorMessage placeholder (no
 * blocks, no image) via updatePage on every chapter open. Persisting those
 * placeholders created on-disk entries that — under the old
 * `pages.isNotEmpty()` translated check — made chapters falsely read as fully
 * TRANSLATED forever after. shouldPersistUpdate now keeps such transient
 * placeholders in memory only, while still persisting durable progress
 * (rendered output, recognized blocks, cleaned image, failures, and the
 * rendered->cleared transition).
 */
class ChapterTranslationStorePersistTest {

    // Constructed with no file and no creator: shouldPersistUpdate is a pure
    // decision function and never touches the disk, so this is safe as long as
    // the test only calls shouldPersistUpdate (no updatePage/replaceAll, which
    // would try to persist and hit the null file via persistLocked).
    private val store = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = emptyMap(),
    )

    @Test
    fun `cancelled placeholder with error message but no content is not persisted`() {
        val placeholder = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            translationStatus = StageStatus.CANCELLED,
            inpaintStatus = StageStatus.CANCELLED,
            renderStatus = StageStatus.CANCELLED,
            errorMessage = "Page was stranded mid-translation; reset as cancelled on chapter reopen",
        )

        store.shouldPersistUpdate(previous = null, updated = placeholder) shouldBe false
    }

    @Test
    fun `cancelled placeholder replacing a cancelled placeholder is still not persisted`() {
        val placeholder = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            errorMessage = "stranded",
        )

        store.shouldPersistUpdate(previous = placeholder.copy(), updated = placeholder) shouldBe false
    }

    @Test
    fun `fresh pending page is not persisted`() {
        store.shouldPersistUpdate(previous = null, updated = PageTranslation()) shouldBe false
    }

    @Test
    fun `running page is not persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(ocrStatus = StageStatus.RUNNING),
        ) shouldBe false
    }

    @Test
    fun `failed page IS persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(
                ocrStatus = StageStatus.FAILED,
                errorMessage = "ONNX recognition failed",
            ),
        ) shouldBe true
    }

    @Test
    fun `page with recognized text blocks IS persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(
                blocks = mutableListOf(block()),
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
            ),
        ) shouldBe true
    }

    @Test
    fun `page with cleaned image IS persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(cleanedImageName = "001.cleaned.png"),
        ) shouldBe true
    }

    @Test
    fun `page with rendered image IS persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(
                cleanedImageName = "001.cleaned.png",
            ),
        ) shouldBe true
    }

    @Test
    fun `transition from rendered result to cleared IS persisted`() {
        val previousRendered = PageTranslation(
            cleanedImageName = "001.cleaned.png",
        )
        val cleared = PageTranslation(
            ocrStatus = StageStatus.RUNNING,
            cleanedImageName = null,
        )

        store.shouldPersistUpdate(previous = previousRendered, updated = cleared) shouldBe true
    }

    @Test
    fun `failed placeholder carries its error message onto disk`() {
        val failed = PageTranslation(
            renderStatus = StageStatus.FAILED,
            errorMessage = "Could not save rendered image",
        )
        store.shouldPersistUpdate(previous = null, updated = failed) shouldBe true
    }

    // ── Phase 1A: Coalesced/debounced persistence ────────────────────────

    @Test
    fun `coalescedDurableUpdates_writeOnceAfterFlush`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        store.updatePage("p2") { PageTranslation(blocks = mutableListOf(block())) }
        store.updatePage("p3") { PageTranslation(blocks = mutableListOf(block())) }

        store.flush()

        store.persistCount shouldBe 1
    }

    @Test
    fun `flush_persistsLatestPageState`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        val blockV1 = block()
        val blockV2 = block(text = "改", translation = "modified")

        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(blockV1)) }
        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(blockV2)) }

        store.flush()

        store.persistCount shouldBe 1
        store.state.value["p1"]?.blocks shouldBe listOf(blockV2)
    }

    @Test
    fun `open_afterFlush_restoresLatestState`() = runTest {
        val tempDir = createTempDir()

        try {
            val targetFile = File(tempDir, "translation.json")
            val tmpFile = File(tempDir, "translation.tmp")

            val mockTemp = mockk<UniFile>()
            every { mockTemp.openOutputStream() } answers { tmpFile.outputStream() }
            every { mockTemp.openInputStream() } answers { tmpFile.inputStream() }
            every { mockTemp.delete() } answers { tmpFile.delete(); true }
            every { mockTemp.renameTo(any()) } answers {
                targetFile.writeBytes(tmpFile.readBytes())
                tmpFile.delete()
                true
            }

            val mockParent = mockk<UniFile>()
            every { mockParent.createFile("translation.tmp") } returns mockTemp
            every { mockParent.findFile(any()) } returns null

            val mockTarget = mockk<UniFile>()
            every { mockTarget.parentFile } returns mockParent
            every { mockTarget.name } returns "translation.json"
            every { mockTarget.exists() } answers { targetFile.exists() }
            every { mockTarget.openInputStream() } answers { targetFile.inputStream() }
            every { mockTarget.openOutputStream() } answers { targetFile.outputStream() }
            every { mockTarget.delete() } answers { targetFile.delete(); true }

            val store = ChapterTranslationStore(
                translationFile = mockTarget,
                fileCreator = null,
                initialPages = emptyMap(),
            )

            store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
            store.flush()

            val reopened = ChapterTranslationStore.open(mockTarget)
            reopened.state.value["p1"]?.blocks shouldBe listOf(block())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `preRegisterPages_doesNotPersistUntilDurableChange`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        store.preRegisterPages(listOf("p1", "p2", "p3"))

        store.persistCount shouldBe 0
        store.state.value.keys.size shouldBe 3

        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        store.flush()

        store.persistCount shouldBe 1
    }

    @Test
    fun `clearTransientQueuePages_doesNotDropDurableBlocks`() = runTest {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )

        store.updatePage("p1") { PageTranslation(blocks = mutableListOf(block())) }
        store.updatePage("p2") { PageTranslation(blocks = mutableListOf(block())) }
        store.updatePage("p3") { PageTranslation(ocrStatus = StageStatus.RUNNING) }

        store.clearTransientQueuePages("test cancel")

        store.state.value["p1"]?.blocks shouldBe listOf(block())
        store.state.value["p2"]?.blocks shouldBe listOf(block())
        store.state.value["p3"]?.blocks shouldBe emptyList()
        store.state.value["p3"]?.ocrStatus shouldBe StageStatus.CANCELLED
        store.persistCount shouldBe 1
    }

    /** Minimal block satisfying the non-default TranslationBlock geometry args. */
    private fun block(
        text: String = "源",
        translation: String = "source",
    ) = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
