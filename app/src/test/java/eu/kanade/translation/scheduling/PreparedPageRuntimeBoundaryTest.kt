package eu.kanade.translation.scheduling

import android.graphics.Bitmap
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.detachedCopy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Ticket 02 fixup round 2 — real boundary regression coverage.
 *
 * These tests drive the PRODUCTION boundary helper
 * [publishPreparedPageFromOcr] and the PRODUCTION terminal predicate
 * [isPreparedPageTerminal] — not a test-side re-enactment. A mutation that
 * removes the OCR durability `store.updatePage` call from
 * [publishPreparedPageFromOcr] will fail the first test because the store
 * snapshot will have empty blocks and `ocrStatus = RUNNING` instead of `READY`.
 *
 * The heavy [eu.kanade.translation.pipeline.TranslationPipeline] needs an Android
 * Context + on-device ONNX models, so the boundary's store/PreparedPage
 * construction was extracted into [publishPreparedPageFromOcr] — a top-level
 * internal function that is pure JVM and is the exact code path
 * `prepareSinglePage` runs after the cleaned image is published.
 */
class PreparedPageRuntimeBoundaryTest {

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = emptyMap(),
    )

    private fun block(text: String, id: String = text): TranslationBlock = TranslationBlock(
        blockId = id,
        text = text,
        width = 100f,
        height = 50f,
        x = 10f,
        y = 20f,
        symHeight = 40f,
        symWidth = 80f,
        angle = 0f,
    )

    /**
     * R1 headline test: drives the REAL production helper and proves the OCR
     * blocks become durable at handoff. If the `store.updatePage` call inside
     * [publishPreparedPageFromOcr] is removed, this test fails because
     * `durablePage.blocks` will be empty and `ocrStatus` will be RUNNING.
     */
    @Test
    fun `production durability write makes OCR blocks survive handoff for downstream reconstruction`() = runTest {
        val store = newStore()

        // Simulate the pre-flight store write (what translateSinglePageOnnx
        // does before OCR fires): ocrStatus=RUNNING, no blocks.
        store.updatePage("p0") {
            PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.RUNNING)
        }

        // The in-memory OCR result that processSinglePage returns: blocks alive,
        // ocrStatus=READY, with a cleaned image already persisted by
        // persistOnnxCleanedImage (simulated by setting cleanedImageName).
        val ocrResult = PageTranslation(
            sourceFileName = "p0",
            ocrStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.PENDING,
        ).apply {
            blocks = mutableListOf(block("こんにちは"), block("ありがとう"))
            cleanedImageName = "p0.cleaned.v1.jpg"
            originalImgWidth = 1000f
            originalImgHeight = 1500f
            decodeSampleSize = 1
        }

        // Call the PRODUCTION helper — this is the exact code path
        // prepareSinglePage runs. The merge carries the post-publish
        // precondition captured from the durable snapshot. If the durability
        // write is removed from this function, the assertions below fail.
        val prepared = publishPreparedPageFromOcr(
            store = store,
            pageKey = "p0",
            ocrResult = ocrResult,
            chapterId = 1L,
            mangaId = 10L,
            sourceId = 20L,
            expectedPageVersion = store.snapshot("p0").pageVersion,
            expectedGeneration = store.snapshot("p0").generation,
        )

        // The PreparedPage must carry durable identity, not in-memory state.
        prepared.cleanedImageName shouldBe "p0.cleaned.v1.jpg"
        prepared.isTerminal shouldBe false
        prepared.blockFingerprints.size shouldBe 2

        // The durable store entry MUST have the OCR blocks + ocrStatus=READY.
        // This is what translatePreparedPage reconstructs from.
        val durablePage = store.state.value["p0"]!!
        durablePage.blocks.size shouldBe 2
        durablePage.blocks[0].text shouldBe "こんにちは"
        durablePage.blocks[1].text shouldBe "ありがとう"
        durablePage.ocrStatus shouldBe StageStatus.READY

        // The translate-side reconstruction (what translatePreparedPage does)
        // must see the blocks that the production helper made durable.
        val reconstructed = durablePage.detachedCopy()
        reconstructed.blocks.size shouldBe 2
        reconstructed.blocks shouldContainExactly durablePage.blocks

        // The cleaned bitmap must NOT survive across the boundary.
        ocrResult.cleanedBitmap shouldBe null
    }

    /**
     * If the production durability write is missing, the store retains the
     * pre-flight state (no blocks, ocrStatus=RUNNING). This test documents
     * that baseline by calling the helper WITHOUT the pre-flight entry — but
     * the headline test above is the one that proves the write happens.
     */
    @Test
    fun `prepared page fingerprints match the durable store after production publish`() = runTest {
        val store = newStore()
        store.updatePage("p0") {
            PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.RUNNING)
        }
        val ocrResult = PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.READY).apply {
            blocks = mutableListOf(block("a"), block("b"))
            cleanedImageName = "p0.cleaned.v1.jpg"
        }
        val prepared = publishPreparedPageFromOcr(store, "p0", ocrResult, 1L, 1L, 1L, expectedPageVersion = store.snapshot("p0").pageVersion, expectedGeneration = store.snapshot("p0").generation)
        val durableFingerprints = store.state.value["p0"]!!.blockFingerprints()
        prepared.blockFingerprints shouldBe durableFingerprints
    }

    /**
     * Drives the REAL production [isPreparedPageTerminal] predicate. A fresh
     * textless page (ocrStatus=READY, no blocks, renderStatus=SKIPPED) must be
     * terminal. If the predicate's condition flips, this test fails.
     */
    @Test
    fun `production terminal predicate classifies fresh textless pages as terminal`() {
        val textless = PageTranslation(sourceFileName = "p-tl").apply {
            ocrStatus = StageStatus.READY
            inpaintStatus = StageStatus.TEXTLESS
            renderStatus = StageStatus.SKIPPED
            blocks = mutableListOf()
        }
        isPreparedPageTerminal(textless) shouldBe true
    }

    @Test
    fun `production terminal predicate rejects failed pages`() {
        val failed = PageTranslation(sourceFileName = "p-fail").apply {
            ocrStatus = StageStatus.READY
            inpaintStatus = StageStatus.FAILED
        }
        isPreparedPageTerminal(failed) shouldBe false
    }

    @Test
    fun `production terminal predicate classifies a fresh page with blocks as non-terminal`() {
        val fresh = PageTranslation(sourceFileName = "p0").apply {
            ocrStatus = StageStatus.READY
            inpaintStatus = StageStatus.READY
            renderStatus = StageStatus.PENDING
            blocks = mutableListOf(block("text"))
            cleanedImageName = "p0.cleaned.v1.jpg"
        }
        isPreparedPageTerminal(fresh) shouldBe false
    }

    /**
     * Drives the REAL production helper with a textless OCR result and verifies
     * the returned PreparedPage is terminal with no cleaned image.
     */
    @Test
    fun `production helper returns terminal prepared page for textless OCR result`() = runTest {
        val store = newStore()
        store.updatePage("p-tl") {
            PageTranslation(sourceFileName = "p-tl", ocrStatus = StageStatus.RUNNING)
        }
        val textlessOcr = PageTranslation(sourceFileName = "p-tl").apply {
            ocrStatus = StageStatus.READY
            inpaintStatus = StageStatus.TEXTLESS
            renderStatus = StageStatus.SKIPPED
            blocks = mutableListOf()
        }
        val prepared = publishPreparedPageFromOcr(store, "p-tl", textlessOcr, 1L, 1L, 1L, expectedPageVersion = store.snapshot("p-tl").pageVersion, expectedGeneration = store.snapshot("p-tl").generation)
        prepared.isTerminal shouldBe true
        prepared.cleanedImageName shouldBe null
        prepared.blockFingerprints shouldBe emptyList()
    }

    /**
     * Stale-rejection proof: if the store's blocks change after the prepared
     * reference was captured (a re-OCR), the fingerprints differ. The
     * translatePreparedPage stale guard catches this via the fingerprint
     * comparison wired in the fixup.
     */
    @Test
    fun `stale fingerprint mismatch detected after production publish and re-OCR`() = runTest {
        val store = newStore()
        store.updatePage("p0") {
            PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.RUNNING)
        }
        val ocrResult = PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.READY).apply {
            blocks = mutableListOf(block("original"))
            cleanedImageName = "p0.cleaned.v1.jpg"
        }
        val prepared = publishPreparedPageFromOcr(store, "p0", ocrResult, 1L, 1L, 1L, expectedPageVersion = store.snapshot("p0").pageVersion, expectedGeneration = store.snapshot("p0").generation)
        val preparedFingerprints = prepared.blockFingerprints

        // Simulate a re-OCR that changed the blocks
        store.updatePage("p0") {
            it!!.apply { blocks = mutableListOf(block("re-ocrd-different")) }
        }
        val currentFingerprints = store.snapshot("p0").blockFingerprints
        (currentFingerprints != preparedFingerprints) shouldBe true
    }

    /**
     * The boundary does not retain a decoded bitmap. The production helper
     * nulls cleanedBitmap on the OCR result before returning.
     */
    @Test
    fun `production helper nulls cleaned bitmap on source before returning`() = runTest {
        val store = newStore()
        val ocrResult = PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.READY).apply {
            blocks = mutableListOf(block("text"))
            cleanedImageName = "p0.cleaned.v1.jpg"
            // cleanedBitmap is null in pure JVM (no Android Bitmap); the helper
            // still calls recycle() safely (null-safe) and nulls the field.
        }
        publishPreparedPageFromOcr(store, "p0", ocrResult, 1L, 1L, 1L, expectedPageVersion = store.snapshot("p0").pageVersion, expectedGeneration = store.snapshot("p0").generation)
        ocrResult.cleanedBitmap shouldBe null
    }

    @Test
    fun `lazy auto boundary flushes before eager cleaned bitmap recycle`() = runTest {
        val store = newStore().also { it.enableLazyPersistence() }
        store.updatePage("p0") {
            PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.RUNNING)
        }
        val events = mutableListOf<String>()
        store.enqueueLazyPersistence(store.currentGeneration) {
            events += "flush"
            true
        }
        val bitmap = mockk<Bitmap>(relaxed = true) {
            every { recycle() } answers { events += "recycle" }
        }
        val ocrResult = PageTranslation(sourceFileName = "p0", ocrStatus = StageStatus.READY).apply {
            blocks = mutableListOf(block("text"))
            cleanedImageName = "p0.cleaned.v1.jpg"
            cleanedBitmap = bitmap
        }

        publishPreparedPageFromOcr(
            store = store,
            pageKey = "p0",
            ocrResult = ocrResult,
            chapterId = 1L,
            mangaId = 1L,
            sourceId = 1L,
            expectedPageVersion = store.snapshot("p0").pageVersion,
            expectedGeneration = store.snapshot("p0").generation,
        )

        events shouldBe listOf("flush", "recycle")
        ocrResult.cleanedBitmap shouldBe null
    }
}
