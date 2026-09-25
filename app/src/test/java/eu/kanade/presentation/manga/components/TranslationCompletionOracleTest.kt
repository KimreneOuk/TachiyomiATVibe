package eu.kanade.presentation.manga.components

import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.ui.BatchStatusLineKind
import eu.kanade.translation.ui.TranslationUiTruth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  completion oracle: a run with failure/attention pages — or a
 * failed/paused/aborted/unsaved outcome — must NEVER render the celebratory
 * "Completed / All pages translated" state. The device trace showed the
 * sheet's green "Completed" pill and "All pages translated" subtitle while
 * the same run recorded outcome=failure and "1 pages need attention": FINISHED
 * (which ERROR and READY_WITH_WARNINGS chapters also map to) was treated as
 * completion regardless of unresolved pages. Every completed surface keys on
 * [TranslationUiTruth.isCompletedOutcome]; the truth line and the sheet
 * subtitle render the attention wording instead.
 */
class TranslationCompletionOracleTest {

    private fun finishedSnapshot(
        state: Translation.State = Translation.State.TRANSLATED,
        failedCount: Int = 0,
        pages: List<TranslationProgressSnapshot.Page> = listOf(successPage()),
        nonDurableFailure: Boolean = false,
        aborted: Boolean = false,
        pauseReason: String? = null,
        groupedFailures: Map<String, List<String>> = emptyMap(),
        batchPhase: TranslationBatchPhase = TranslationBatchPhase.FINISHED,
    ) = TranslationProgressSnapshot.empty(1L, state).copy(
        batchPhase = batchPhase,
        failedCount = failedCount,
        pages = pages,
        nonDurableFailure = nonDurableFailure,
        aborted = aborted,
        pauseReason = pauseReason,
        groupedFailures = groupedFailures,
        expectedPageCountTrusted = true,
    )

    private fun successPage(
        pageKey: String = "001.jpg",
        index: Int = 1,
    ) = TranslationProgressSnapshot.Page(
        pageKey = pageKey,
        index = index,
        stage = TranslationProgressStage.DONE,
        displayReady = true,
        processed = true,
        aiState = AiPageProgressState.SUCCEEDED,
    )

    // ------------------------------------------------------------------
    // A clean finished run classifies as completed.
    // ------------------------------------------------------------------

    @Test
    fun `a clean finished run classifies as completed`() {
        val snapshot = finishedSnapshot()

        assertTrue(TranslationUiTruth.isCompletedOutcome(snapshot))
        assertFalse(TranslationUiTruth.hasPagesNeedingAttention(snapshot))
        assertEquals(0, TranslationUiTruth.pagesNeedingAttentionCount(snapshot))

        val line = TranslationUiTruth.batchStatusLine(snapshot)
        assertEquals(BatchStatusLineKind.COMPLETED, line.kind)
        assertEquals("All pages translated and ready to read", line.fallback)
        assertEquals("All pages translated and ready to read", batchStatusHeaderSubtitle(snapshot))
    }

    // ------------------------------------------------------------------
    // Failure/attention pages: never completed, always the attention state.
    // ------------------------------------------------------------------

    @Test
    fun `a finished run with a failed page never classifies as completed`() {
        val snapshot = finishedSnapshot(
            state = Translation.State.READY_WITH_WARNINGS,
            failedCount = 1,
            pages = listOf(successPage(), failedPage()),
        )

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
        assertTrue(TranslationUiTruth.hasPagesNeedingAttention(snapshot))
        assertEquals(1, TranslationUiTruth.pagesNeedingAttentionCount(snapshot))

        val line = TranslationUiTruth.batchStatusLine(snapshot)
        assertEquals(BatchStatusLineKind.ATTENTION_REQUIRED, line.kind)
        assertEquals("1 pages need attention", line.fallback)
        val subtitle = batchStatusHeaderSubtitle(snapshot)
        assertEquals("1 pages need attention", subtitle)
        assertFalse(subtitle.contains("All pages translated"))
    }

    @Test
    fun `a finished run with a partial page never classifies as completed`() {
        val partial = successPage().copy(partial = true)
        val snapshot = finishedSnapshot(pages = listOf(partial, successPage("002.jpg", 2)))

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
        assertTrue(TranslationUiTruth.hasPagesNeedingAttention(snapshot))
        assertEquals(1, TranslationUiTruth.pagesNeedingAttentionCount(snapshot))
    }

    @Test
    fun `a finished run with a failed AI attempt never classifies as completed`() {
        // The AI attempt failed and the run ended before the page produced a
        // display-ready result (a display-ready page keeps the chip's
        // success precedence, matching the sheet's page chips).
        val aiFailed = TranslationProgressSnapshot.Page(
            pageKey = "001.jpg",
            index = 1,
            stage = TranslationProgressStage.TRANSLATE,
            aiState = AiPageProgressState.FAILED,
        )
        val snapshot = finishedSnapshot(pages = listOf(aiFailed))

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
        assertTrue(TranslationUiTruth.hasPagesNeedingAttention(snapshot))
    }

    @Test
    fun `a finished run with failure groups never classifies as completed`() {
        val snapshot = finishedSnapshot(groupedFailures = mapOf("boom" to listOf("001.jpg")))

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
        assertTrue(TranslationUiTruth.hasPagesNeedingAttention(snapshot))
    }

    @Test
    fun `an unsaved non-durable result never classifies as completed`() {
        val snapshot = finishedSnapshot(nonDurableFailure = true)

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
        assertTrue(TranslationUiTruth.hasPagesNeedingAttention(snapshot))
    }

    // ------------------------------------------------------------------
    // Failed/paused/aborted run outcomes: never completed.
    // ------------------------------------------------------------------

    @Test
    fun `a finished run in the ERROR state never classifies as completed`() {
        val snapshot = finishedSnapshot(state = Translation.State.ERROR)

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
        val line = TranslationUiTruth.batchStatusLine(snapshot)
        assertEquals(BatchStatusLineKind.ATTENTION_REQUIRED, line.kind)
    }

    @Test
    fun `a finished paused run never classifies as completed`() {
        val snapshot = finishedSnapshot(
            state = Translation.State.PAUSED,
            pauseReason = "provider unavailable",
        )

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
    }

    @Test
    fun `an aborted run never classifies as completed`() {
        val snapshot = finishedSnapshot(aborted = true)

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
    }

    // ------------------------------------------------------------------
    // The oracle is a completion gate, not an "everything is fine" gate.
    // ------------------------------------------------------------------

    @Test
    fun `a run that has not finished never classifies as completed`() {
        val snapshot = finishedSnapshot(batchPhase = TranslationBatchPhase.FIRST_PASS)

        assertFalse(TranslationUiTruth.isCompletedOutcome(snapshot))
    }

    @Test
    fun `attention count never reads zero while attention exists`() {
        // Attention whose facts live outside the per-page list (unsaved
        // result, no failed counter): the count floors at one so the copy
        // never reads "0 pages need attention".
        val snapshot = finishedSnapshot(nonDurableFailure = true)

        assertEquals(1, TranslationUiTruth.pagesNeedingAttentionCount(snapshot))
    }

    @Test
    fun `attention count reflects the batch failure counter`() {
        val snapshot = finishedSnapshot(failedCount = 3)

        assertEquals(3, TranslationUiTruth.pagesNeedingAttentionCount(snapshot))
    }

    private fun failedPage(
        pageKey: String = "002.jpg",
        index: Int = 2,
    ) = TranslationProgressSnapshot.Page(
        pageKey = pageKey,
        index = index,
        stage = TranslationProgressStage.FAILED,
        aiState = AiPageProgressState.FAILED,
        errorMessage = "ocr failed",
    )
}
