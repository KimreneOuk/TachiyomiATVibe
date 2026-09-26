package eu.kanade.translation.orchestration

import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.ui.TranslationUiTruth
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** Reader-facing progress rules for queue state and durable retry details. */
class TranslationProgressProjectionPausedAffordanceTest {

    @Test
    fun `queue status projection clears the paused affordance while queued`() {
        val snapshot = pausedSnapshotWithAffordance()

        val projected = snapshot.projectQueueStatus(Translation.State.QUEUE)

        projected.state shouldBe Translation.State.QUEUE
        projected.batchPhase shouldBe TranslationBatchPhase.IDLE
        projected.pauseAnchorPageKey shouldBe null
        projected.pauseReason shouldBe null
        projected.nextEligibleRetryAtEpochMs shouldBe null
    }

    @Test
    fun `queue status projection promotes an idle batch phase while translating`() {
        val snapshot = TranslationProgressSnapshot.empty(10L, Translation.State.PAUSED).copy(
            batchPhase = TranslationBatchPhase.IDLE,
            pauseAnchorPageKey = "p0",
            pauseReason = "boom",
            nextEligibleRetryAtEpochMs = 2_000L,
        )

        val promoted = snapshot.projectQueueStatus(Translation.State.TRANSLATING)

        promoted.state shouldBe Translation.State.TRANSLATING
        promoted.batchPhase shouldBe TranslationBatchPhase.FIRST_PASS
        // Characterized as-is: unlike the QUEUE branch, the TRANSLATING branch
        // does not clear an existing pause affordance.
        promoted.pauseAnchorPageKey shouldBe "p0"

        val explicit = snapshot.copy(batchPhase = TranslationBatchPhase.FINALIZING)
        explicit.projectQueueStatus(Translation.State.TRANSLATING).batchPhase shouldBe
            TranslationBatchPhase.FINALIZING
    }

    @Test
    fun `queue status projection marks paused finished and null is an identity`() {
        val snapshot = pausedSnapshotWithAffordance().copy(state = Translation.State.TRANSLATING)

        val paused = snapshot.projectQueueStatus(Translation.State.PAUSED)
        paused.state shouldBe Translation.State.PAUSED
        paused.batchPhase shouldBe TranslationBatchPhase.FINISHED
        paused.pauseAnchorPageKey shouldBe "p0"

        snapshot.projectQueueStatus(null) shouldBe snapshot
    }

    @Test
    fun `durable pause surfaces the retryable translation failure on a paused snapshot`() {
        val store = storeWithDurableFailures(translationFailure())
        val snapshot = TranslationProgressSnapshot.empty(10L, Translation.State.PAUSED)

        val projected = snapshot.withDurablePause(store)

        projected.pauseAnchorPageKey shouldBe "p0"
        projected.pauseReason shouldBe "translation provider failed"
        projected.nextEligibleRetryAtEpochMs shouldBe 2_000L
    }

    @Test
    fun `durable pause surfaces retryable protocol failures from every stage`() {
        val paused = TranslationProgressSnapshot.empty(10L, Translation.State.PAUSED)

        // No durable failures at all.
        paused.withDurablePause(storeWithDurableFailures()) shouldBe paused
        // OCR checkpoint failures must be visible as page/manifest truth, not
        // the generic provider-unavailable fallback.
        val ocrFailure = translationFailure().copy(
            stage = ArtifactStage.OCR,
            category = FailureCategory.PROTOCOL,
            lastFailureMessage = "page missing: pageKey=001.jpg",
            nextEligibleRetryAtEpochMs = null,
        )
        val projected = paused.withDurablePause(storeWithDurableFailures(ocrFailure))
        projected.pauseReason shouldBe
            "Page manifest mismatch: page missing: pageKey=001.jpg"
        TranslationUiTruth.batchStatusLine(projected).fallback shouldBe
            "Paused — Page manifest mismatch: page missing: pageKey=001.jpg"
        // A terminal translation failure is not retryable.
        val terminalFailure = translationFailure().copy(status = ArtifactStageStatus.FAILED_TERMINAL)
        paused.withDurablePause(storeWithDurableFailures(terminalFailure)) shouldBe paused
        // Non-PAUSED states return unchanged even with a qualifying failure.
        val translating = TranslationProgressSnapshot.empty(10L, Translation.State.TRANSLATING)
        translating.withDurablePause(storeWithDurableFailures(translationFailure())) shouldBe
            translating
    }

    /** A PAUSED snapshot already carrying a visible pause affordance. */
    private fun pausedSnapshotWithAffordance(): TranslationProgressSnapshot =
        TranslationProgressSnapshot.empty(10L, Translation.State.PAUSED).copy(
            batchPhase = TranslationBatchPhase.FINISHED,
            pauseAnchorPageKey = "p0",
            pauseReason = "boom",
            nextEligibleRetryAtEpochMs = 2_000L,
        )

    private fun translationFailure(): DurableFailureMetadata = DurableFailureMetadata(
        pageKey = "p0",
        stage = ArtifactStage.TRANSLATION,
        status = ArtifactStageStatus.FAILED_RETRYABLE,
        category = FailureCategory.TRANSIENT,
        retryCount = 1,
        lastFailureMessage = "translation provider failed",
        lastFailedAtEpochMs = 1_000L,
        nextEligibleRetryAtEpochMs = 2_000L,
    )

    private fun storeWithDurableFailures(
        vararg failures: DurableFailureMetadata,
    ): ChapterTranslationStore = mockk<ChapterTranslationStore> {
        every { durableFailuresSnapshot() } returns failures.associateBy { failure ->
            "${failure.pageKey}:${failure.stage.name}"
        }
    }
}
