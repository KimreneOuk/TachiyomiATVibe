package eu.kanade.translation

import eu.kanade.translation.orchestration.*

import eu.kanade.translation.storage.*

import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Characterization tests for the manager's paused-affordance glue (T906
 * area-3 finding F5). `projectQueueStatus` and `withDurablePause` are the
 * private pure projections behind observeBatchProgress — the sheet and
 * notification "paused affordance" the UI renders. They are invoked
 * reflectively on an Unsafe-allocated [TranslationManager] because
 * constructing the manager normally boots Android/DI translation engines;
 * the functions themselves touch no manager state.
 */
class TranslationManagerPausedAffordanceTest {

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
    fun `durable pause leaves snapshots without a retryable translation failure untouched`() {
        val paused = TranslationProgressSnapshot.empty(10L, Translation.State.PAUSED)

        // No durable failures at all.
        paused.withDurablePause(storeWithDurableFailures()) shouldBe paused
        // A retryable failure on a different stage does not qualify.
        val ocrFailure = translationFailure().copy(stage = ArtifactStage.OCR)
        paused.withDurablePause(storeWithDurableFailures(ocrFailure)) shouldBe paused
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

    private fun TranslationProgressSnapshot.projectQueueStatus(
        queueStatus: Translation.State?,
    ): TranslationProgressSnapshot = invokeProjection(
        "projectQueueStatus",
        Translation.State::class.java,
        queueStatus,
    )

    private fun TranslationProgressSnapshot.withDurablePause(
        store: ChapterTranslationStore,
    ): TranslationProgressSnapshot = invokeProjection(
        "withDurablePause",
        ChapterTranslationStore::class.java,
        store,
    )

    /** Invokes the private member extension [methodName] on a bare manager instance. */
    private fun TranslationProgressSnapshot.invokeProjection(
        methodName: String,
        parameterType: Class<*>,
        argument: Any?,
    ): TranslationProgressSnapshot {
        val method = TranslationManager::class.java.getDeclaredMethod(
            methodName,
            TranslationProgressSnapshot::class.java,
            parameterType,
        )
        method.isAccessible = true
        return method.invoke(uninitializedManager(), this, argument) as TranslationProgressSnapshot
    }

    private fun uninitializedManager(): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
    }
}
