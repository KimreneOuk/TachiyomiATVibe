package eu.kanade.translation.pipeline.batch

import eu.kanade.tachiyomi.ui.reader.viewer.selectReaderTranslationOverlayBinding
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.util.ResumeOrdering
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 * Phase 0 characterization coverage. These tests describe the current NPU
 * branch and intentionally do not change scheduler, store, or reader behavior.
 * Defect-shaped assertions name the observed contract mismatch so a later
 * phase can replace them with the intended contract without losing the baseline.
 */
class Phase0BatchTranslationCharacterizationTest {

    @Test
    fun `batch order is natural when last read index is at chapter start`() {
        ResumeOrdering.forwardFirstThenBackfill(pages(5), resumeIndex = 0) shouldBe pages(5)
    }

    @Test
    fun `current batch order rotates after a mid-chapter last read index`() {
        // Characterizes the live ChapterTranslator admission path:
        // lastPageRead=2 produces page 3..N before pages 1..2.
        ResumeOrdering.forwardFirstThenBackfill(pages(5), resumeIndex = 2) shouldBe
            listOf("page-3", "page-4", "page-5", "page-1", "page-2")
    }

    @Test
    fun `live resume gate distinguishes reusable and restart decisions`() {
        val current = translatedPage(cleanedImageName = "page-1.cleaned.jpg").apply {
            inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 2))
        }
        val staleCleaned = current.detachedCopy().apply {
            inpaintRevision = 0
        }
        val legacy = current.detachedCopy().apply {
            inpaintMaskBoxes = emptyList()
            inpaintRevision = 0
            renderStatus = StageStatus.PENDING
        }

        BatchResumeGateDecider.decide(current) shouldBe BatchResumeGateDecider.Decision.SKIP_ALL
        BatchResumeGateDecider.decide(current, cleanedFileValid = false) shouldBe
            BatchResumeGateDecider.Decision.INPAINT_ONLY
        BatchResumeGateDecider.decide(staleCleaned) shouldBe BatchResumeGateDecider.Decision.INPAINT_ONLY
        BatchResumeGateDecider.decide(legacy) shouldBe BatchResumeGateDecider.Decision.FULL
        BatchResumeGateDecider.decide(null) shouldBe BatchResumeGateDecider.Decision.FULL
    }

    @Test
    fun `OCR and translation can look complete while reader display is not ready`() {
        val page = translatedPage(
            cleanedImageName = null,
            inpaintStatus = StageStatus.PENDING,
        )

        page.hasRecognizedTranslation shouldBe true
        page.isTranslationDisplayReady shouldBe false
        page.displayImageName shouldBe null
        selectReaderTranslationOverlayBinding(showTranslatedImage = true, translation = page).blocks shouldBe
            emptyList()
    }

    @Test
    fun `store emissions expose original fallback while replacement status is running`() = runTest {
        val counters = BatchStageInvocationCounters()
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf("page-1" to translatedPage(cleanedImageName = "page-1.cleaned.jpg")),
        )
        val displayReadiness = mutableListOf<Boolean>()
        val overlaySizes = mutableListOf<Int>()

        val collector = backgroundScope.launch {
            store.state
                .drop(1)
                .take(2)
                .collect { state ->
                    val page = state.getValue("page-1")
                    counters.recordStoreEmission("page-1")
                    displayReadiness += page.isTranslationDisplayReady
                    val binding = selectReaderTranslationOverlayBinding(
                        showTranslatedImage = true,
                        translation = page,
                    )
                    overlaySizes += binding.blocks.size
                    if (page.isTranslationDisplayReady) {
                        counters.recordReaderDisplay("page-1")
                    }
                }
        }

        yield()
        store.updatePage("page-1") {
            it!!.apply {
                inpaintStatus = StageStatus.RUNNING
            }
        }
        yield()
        store.updatePage("page-1") {
            it!!.apply {
                inpaintStatus = StageStatus.READY
            }
        }
        collector.join()

        displayReadiness shouldBe listOf(false, true)
        overlaySizes shouldBe listOf(0, 1)
        counters.count(BatchTestStage.STORE_EMISSION) shouldBe 2
        counters.count(BatchTestStage.READER_DISPLAY) shouldBe 1
    }

    private fun pages(count: Int): List<String> = List(count) { "page-${it + 1}" }

    private fun translatedPage(
        cleanedImageName: String?,
        inpaintStatus: String = StageStatus.READY,
    ) = PageTranslation(
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "target",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        cleanedImageName = cleanedImageName,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = inpaintStatus,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        renderStatus = StageStatus.READY,
    )
}
