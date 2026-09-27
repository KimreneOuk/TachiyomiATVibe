package eu.kanade.translation.presentation

import eu.kanade.translation.model.BatchRebuildProgress
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The reader bottom bar uses the same phase truth as the progress sheet.
 * During a resume rebuild it shows rebuild/restore copy instead of a frozen
 * "Batch X/Y" count, while preserving the existing wording and visibility.
 */
class ReaderBarTruthTest {

    @Test
    fun `reader bar hides batch framing outside a batch session`() {
        TranslationUiTruth.readerBarLine(
            snapshot = snapshot(totalPages = 1, donePages = 0),
            isBatchSession = false,
        ).shouldBeNull()
    }

    private fun snapshot(
        state: Translation.State = Translation.State.TRANSLATING,
        batchPhase: TranslationBatchPhase = TranslationBatchPhase.FIRST_PASS,
        donePages: Int = 0,
        totalPages: Int = 0,
        request: TranslationRequestState? = null,
        pauseReason: String? = null,
        rebuild: BatchRebuildProgress? = null,
    ): TranslationProgressSnapshot = TranslationProgressSnapshot.empty(1L, state)
        .copy(
            batchPhase = batchPhase,
            donePages = donePages,
            totalPages = totalPages,
            totalStages = totalPages * 4,
            requestState = request,
            pauseReason = pauseReason,
            rebuildProgress = rebuild,
            expectedPageCountTrusted = true,
        )

    // ------------------------------ rebuild/restore replace the frozen count --

    @Test
    fun `rebuilding phase never renders the frozen batch count`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(batchPhase = TranslationBatchPhase.REBUILDING, donePages = 12, totalPages = 70),
        )!!

        line.kind shouldBe BatchStatusLineKind.REBUILDING
        line.fallback shouldBe "Rebuilding pipeline…"
        line.fallback.contains("12/70") shouldBe false
    }

    @Test
    fun `restoring phase carries the remaining page count`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(
                batchPhase = TranslationBatchPhase.RESTORING,
                rebuild = BatchRebuildProgress(restoredPages = 37, totalPages = 70),
            ),
        )!!

        line.kind shouldBe BatchStatusLineKind.RESTORING
        line.formatArgs shouldBe listOf(33)
        line.fallback shouldBe "Restoring 33 pages…"
    }

    @Test
    fun `restoring phase without a payload still renders restoring copy`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(batchPhase = TranslationBatchPhase.RESTORING),
        )!!

        line.kind shouldBe BatchStatusLineKind.RESTORING
        line.formatArgs shouldBe listOf(0)
        line.fallback shouldBe "Restoring 0 pages…"
    }

    // ------------------------------------ priority chain on the bar -----

    @Test
    fun `request phase outranks the rebuild phase`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(
                batchPhase = TranslationBatchPhase.REBUILDING,
                request = TranslationRequestState(1L, TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
            ),
        )!!

        line.kind shouldBe BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD
        line.fallback shouldBe "Waiting for chapter download"
    }

    @Test
    fun `pause outranks the rebuild phase`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(
                batchPhase = TranslationBatchPhase.REBUILDING,
                pauseReason = "provider unavailable",
            ),
        )!!

        line.kind shouldBe BatchStatusLineKind.PAUSED
        line.fallback shouldBe "Translation paused"
    }

    // ------------------------------------------------- legacy bar behavior ----

    @Test
    fun `running batch keeps the numeric bar copy`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(donePages = 12, totalPages = 70),
        )!!

        line.kind shouldBe BatchStatusLineKind.FIRST_PASS_PAGES
        line.fallback shouldBe "Batch 12/70 pages"
    }

    @Test
    fun `finished batch keeps the numeric bar copy`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(
                state = Translation.State.TRANSLATED,
                batchPhase = TranslationBatchPhase.FINISHED,
                donePages = 70,
                totalPages = 70,
            ),
        )!!

        line.fallback shouldBe "Batch 70/70 pages"
    }

    @Test
    fun `legacy request wording is preserved`() {
        TranslationUiTruth.readerBarLine(
            snapshot(request = TranslationRequestState(1L, TranslationRequestPhase.STARTING)),
        )!!.fallback shouldBe "Translation accepted — preparing"
        TranslationUiTruth.readerBarLine(
            snapshot(request = TranslationRequestState(1L, TranslationRequestPhase.PREPARING)),
        )!!.fallback shouldBe "Preparing translation batch"
        TranslationUiTruth.readerBarLine(
            snapshot(request = TranslationRequestState(1L, TranslationRequestPhase.DOWNLOAD_FAILED)),
        )!!.fallback shouldBe "Download failed — retry to continue"
    }

    @Test
    fun `finalizing without totals falls back to preparing copy`() {
        val line = TranslationUiTruth.readerBarLine(
            snapshot(
                batchPhase = TranslationBatchPhase.FINALIZING,
                totalPages = 0,
            ),
        )!!

        line.kind shouldBe BatchStatusLineKind.REQUEST_PREPARING
        line.fallback shouldBe "Preparing translation batch"
    }

    @Test
    fun `idle queued and building-context snapshots keep the bar hidden`() {
        TranslationUiTruth.readerBarLine(
            snapshot(state = Translation.State.QUEUE, batchPhase = TranslationBatchPhase.IDLE),
        ).shouldBeNull()
        TranslationUiTruth.readerBarLine(
            snapshot(state = Translation.State.NOT_TRANSLATED, batchPhase = TranslationBatchPhase.IDLE),
        ).shouldBeNull()
        // TRANSLATING with an IDLE phase (pre-registration window) was hidden
        // before U; it stays hidden — the bar only surfaces real phases.
        TranslationUiTruth.readerBarLine(
            snapshot(state = Translation.State.TRANSLATING, batchPhase = TranslationBatchPhase.IDLE),
        ).shouldBeNull()
    }

    // ------------------------------ sheet status line shares the same truth ---

    @Test
    fun `status chain covers the rebuild to running to finished transitions`() {
        val base = snapshot(totalPages = 70)

        val rebuilding = TranslationUiTruth.batchStatusLine(base.copy(batchPhase = TranslationBatchPhase.REBUILDING))
        rebuilding.kind shouldBe BatchStatusLineKind.REBUILDING
        rebuilding.fallback shouldBe "Rebuilding pipeline…"

        val restoring = TranslationUiTruth.batchStatusLine(
            base.copy(
                batchPhase = TranslationBatchPhase.RESTORING,
                rebuildProgress = BatchRebuildProgress(restoredPages = 37, totalPages = 70),
            ),
        )
        restoring.kind shouldBe BatchStatusLineKind.RESTORING
        restoring.formatArgs shouldBe listOf(37, 70)
        restoring.fallback shouldBe "Restoring 37 of 70 pages…"

        val running = TranslationUiTruth.batchStatusLine(base.copy(donePages = 12))
        running.kind shouldBe BatchStatusLineKind.FIRST_PASS_PAGES
        running.fallback shouldBe "Translating pages (12/70)"

        val finalizing = TranslationUiTruth.batchStatusLine(
            base.copy(batchPhase = TranslationBatchPhase.FINALIZING),
        )
        finalizing.kind shouldBe BatchStatusLineKind.FINALIZING
        finalizing.fallback shouldBe "Finalizing translated chapter..."

        val finished = TranslationUiTruth.batchStatusLine(
            base.copy(
                state = Translation.State.TRANSLATED,
                batchPhase = TranslationBatchPhase.FINISHED,
            ),
        )
        finished.kind shouldBe BatchStatusLineKind.COMPLETED
        finished.fallback shouldBe "All pages translated and ready to read"
    }

    @Test
    fun `sheet chain request outranks queue pause and rebuild`() {
        val line = TranslationUiTruth.batchStatusLine(
            snapshot(
                state = Translation.State.PAUSED,
                batchPhase = TranslationBatchPhase.RESTORING,
                pauseReason = "provider unavailable",
                request = TranslationRequestState(1L, TranslationRequestPhase.PREPARING),
            ),
        )

        line.kind shouldBe BatchStatusLineKind.REQUEST_PREPARING
        line.fallback shouldBe "Preparing translation batch..."
    }
}
