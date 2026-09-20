package eu.kanade.presentation.manga.components

import eu.kanade.translation.model.BatchHeroPhase
import eu.kanade.translation.model.BatchHeroProjection
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 *  slice 2: truthful queue position for queued chapters ("Queued (2nd of
 * 3)") and the explicit terminal/admission phases added for R5/R10 — never
 * rendered as a download failure or as 0/0.
 */
class TranslationQueuePositionAndPhasesTest {

    @Test
    fun `ordinal suffix covers the standard english rules`() {
        ordinalSuffix(1) shouldBe "1st"
        ordinalSuffix(2) shouldBe "2nd"
        ordinalSuffix(3) shouldBe "3rd"
        ordinalSuffix(4) shouldBe "4th"
        ordinalSuffix(11) shouldBe "11th"
        ordinalSuffix(12) shouldBe "12th"
        ordinalSuffix(13) shouldBe "13th"
        ordinalSuffix(21) shouldBe "21st"
        ordinalSuffix(100) shouldBe "100th"
    }

    @Test
    fun `queue position label says the truth about waiting its turn`() {
        queuePositionLabel(2, 3) shouldBe "Queued (2nd of 3) — waiting for earlier batches"
        queuePositionLabel(3, 7) shouldBe "Queued (3rd of 7) — waiting for earlier batches"
    }

    @Test
    fun `first-in-line queued chapter keeps the existing resume wording`() {
        val snapshot = queueSnapshot(position = 1, total = 3)

        batchStatusHeaderSubtitle(snapshot) shouldBe "Queued — ready to resume remaining pages"
    }

    @Test
    fun `later queued chapter shows its position and total`() {
        val snapshot = queueSnapshot(position = 2, total = 3)

        batchStatusHeaderSubtitle(snapshot) shouldBe
            "Queued (2nd of 3) — waiting for earlier batches"
    }

    @Test
    fun `queued chapter without position data falls back to the existing wording`() {
        val snapshot = queueSnapshot(position = null, total = null)

        batchStatusHeaderSubtitle(snapshot) shouldBe "Queued — ready to resume remaining pages"
    }

    @Test
    fun `cancelled request phase renders as cancelled - never download failed`() {
        val snapshot = snapshotWithRequest(
            TranslationRequestState(
                chapterId = 5L,
                phase = TranslationRequestPhase.CANCELLED,
                failureKind = TranslationRequestFailureKind.CANCELLED,
            ),
        )

        val subtitle = batchStatusHeaderSubtitle(snapshot)
        subtitle.contains("cancelled", ignoreCase = true) shouldBe true
        subtitle.contains("Download failed", ignoreCase = false) shouldBe false
    }

    @Test
    fun `admission failure phase is distinct from a download failure`() {
        val snapshot = snapshotWithRequest(
            TranslationRequestState(
                chapterId = 5L,
                phase = TranslationRequestPhase.ADMISSION_FAILED,
                failureKind = TranslationRequestFailureKind.CONFIG_INVALID,
                reason = "Invalid translation configuration",
            ),
        )

        val subtitle = batchStatusHeaderSubtitle(snapshot)
        subtitle.contains("could not be queued", ignoreCase = true) shouldBe true
        subtitle.contains("Download failed", ignoreCase = false) shouldBe false
        subtitle.contains("Invalid translation configuration") shouldBe true
    }

    @Test
    fun `hero projection maps the terminal request phases to error states`() {
        val cancelled = BatchHeroProjection.of(
            snapshotWithRequest(
                TranslationRequestState(5L, TranslationRequestPhase.CANCELLED),
            ),
        )
        val admission = BatchHeroProjection.of(
            snapshotWithRequest(
                TranslationRequestState(5L, TranslationRequestPhase.ADMISSION_FAILED),
            ),
        )

        (cancelled as BatchHeroProjection.Phase).phase shouldBe BatchHeroPhase.CANCELLED
        cancelled.isError shouldBe true
        (admission as BatchHeroProjection.Phase).phase shouldBe BatchHeroPhase.ADMISSION_FAILED
        admission.isError shouldBe true
    }

    // -- helpers -------------------------------------------------------------

    private fun queueSnapshot(position: Int?, total: Int?): TranslationProgressSnapshot {
        val empty = TranslationProgressSnapshot.empty(5L, Translation.State.QUEUE)
        return if (position != null && total != null) {
            empty.copy(queuePosition = position, queueTotal = total)
        } else {
            empty
        }
    }

    private fun snapshotWithRequest(
        request: TranslationRequestState,
    ): TranslationProgressSnapshot = TranslationProgressSnapshot.empty(5L)
        .copy(requestState = request)
}
