package eu.kanade.translation.presentation

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Batch retry affordance for terminal-aborted or terminal-failed batches.
 *
 * RED defects named here:
 *
 *  1. "cancelled batch strands chapter in translating state with no restart
 *     affordance": the progress sheet's only terminal-aborted content is the
 *     aborted banner — no Retry control exists, so a cancelled batch dead-ends
 *     in the drawer (the indicator routes every tap there and its long-press
 *     CANCEL is a no-op with an empty queue).
 *  2. The Retry truth must follow the batch retry rules: offered ONLY for a
 *     terminal-aborted (or terminal-failed) snapshot AND only when the screen
 *     model actually wired a restart callback; a successfully FINISHED
 *     snapshot must never offer Retry; the label/content description are
 *     truth-named ("Retry translation"), never optimistic.
 *
 * Reflection lets this test report a missing truth method as a named assertion
 * instead of a compilation error.
 */
class TranslationSheetRetryTruthTest {

    /**
     * Unlike the P5 helper this distinguishes "truth method missing" (named
     * RED assertion) from "truth method returned null" (a legitimate result
     * the null-expectation tests must be able to observe).
     */
    private fun callTruth(method: String, arity: Int, defect: String, vararg args: Any?): Any? {
        val instance = try {
            Class.forName("eu.kanade.translation.presentation.TranslationUiTruth")
                .getDeclaredField("INSTANCE").get(null)
        } catch (missing: ReflectiveOperationException) {
            throw AssertionError(
                "T918 RED defect: TranslationUiTruth went missing — P5 commit regression",
                missing,
            )
        }
        val found = instance::class.java.declaredMethods.firstOrNull {
            it.name == method && it.parameterTypes.size == arity
        } ?: throw AssertionError(defect)
        return found.apply { isAccessible = true }.invoke(instance, *args)
    }

    private fun prop(value: Any?, name: String): Any? =
        value?.javaClass?.getMethod("get" + name.replaceFirstChar { it.uppercase() })?.invoke(value)

    /** The real tracker-aborted snapshot shape (BatchAborted reduce outcome). */
    private fun abortedSnapshot() = TranslationProgressSnapshot.empty(1L, Translation.State.ERROR)
        .copy(
            aborted = true,
            abortedReason = "Batch cancelled",
            batchPhase = TranslationBatchPhase.FINISHED,
            cancelledPages = 2,
        )

    @Test
    fun `aborted snapshot with a wired callback offers the truth-named retry control`() {
        val defect =
            "T918 RED defect (no restart affordance): TranslationUiTruth has no " +
                "sheet retry projection — the terminal-aborted progress sheet renders " +
                "only the aborted banner, so a cancelled batch offers no working Retry"
        val truth = callTruth("forSheetRetryAction", 2, defect, abortedSnapshot(), true)

        withClue("the sheet retry truth must exist for a terminal-aborted snapshot with a callback") {
            truth ?: throw AssertionError(defect)
        }
        withClue("the label is truth-named (P5): the control restarts the translation") {
            prop(truth, "label").toString() shouldBe "Retry translation"
        }
        withClue("the a11y content description matches the P5 truth wording") {
            prop(truth, "contentDescription").toString() shouldBe "Retry translation"
        }
    }

    @Test
    fun `aborted snapshot without a wired callback offers no retry control`() {
        val defect =
            "T918 RED defect: the sheet retry truth must be null when no restart " +
                "callback is wired (backwards compatibility for existing call sites)"
        val truth = callTruth(
            "forSheetRetryAction",
            2,
            defect,
            abortedSnapshot(),
            false,
        )
        withClue("no callback wired → no Retry control may be offered") {
            truth shouldBe null
        }
    }

    @Test
    fun `successfully finished snapshot never offers a retry control`() {
        val defect =
            "T918 RED defect: a FINISHED (non-aborted, non-failed) snapshot must " +
                "never offer Retry — completion is not a failure state"
        val finished = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATED)
            .copy(
                donePages = 2,
                totalPages = 2,
                totalStages = 8,
                batchPhase = TranslationBatchPhase.FINISHED,
                expectedPageCountTrusted = true,
            )
        val truth = callTruth("forSheetRetryAction", 2, defect, finished, true)
        withClue("finished snapshot + wired callback → still no Retry control") {
            truth shouldBe null
        }
    }

    @Test
    fun `terminal failed snapshot with a wired callback offers the retry control`() {
        val defect =
            "T918 RED defect: a terminal ERROR batch (failed batch, not aborted) is " +
                "distinguishable in the snapshot (FINISHED phase + ERROR state) and must " +
                "offer the same Retry affordance"
        val failed = TranslationProgressSnapshot.empty(1L, Translation.State.ERROR)
            .copy(
                batchPhase = TranslationBatchPhase.FINISHED,
                failedCount = 2,
                totalPages = 2,
                totalStages = 8,
                expectedPageCountTrusted = true,
            )
        val truth = callTruth("forSheetRetryAction", 2, defect, failed, true)
        withClue("terminal-failed snapshot + callback → Retry control offered") {
            truth ?: throw AssertionError(defect)
        }
        prop(truth, "label").toString() shouldBe "Retry translation"
    }

    @Test
    fun `durable reconstruction of an error chapter offers the retry control after restart`() {
        //  restart-retry defect: after an app restart the sheet's snapshot
        // comes from the durable reconstruction (compute with an ERROR chapter
        // state), which used to default batchPhase to IDLE — the truth rule
        // (ERROR + FINISHED) then never passed and the Retry button vanished.
        val defect =
            "T924 restart-retry defect: a durably reconstructed ERROR chapter " +
                "must project ERROR + FINISHED so the sheet keeps offering Retry " +
                "after an app restart"
        val reconstructed = TranslationBatchProgressTracker.computeSnapshot(
            chapterId = 1L,
            chapterState = Translation.State.ERROR,
            pageMap = emptyMap(),
        )
        withClue("compute must treat a durable ERROR chapter as a finished run") {
            reconstructed.state shouldBe Translation.State.ERROR
            reconstructed.batchPhase shouldBe TranslationBatchPhase.FINISHED
        }
        val truth = callTruth("forSheetRetryAction", 2, defect, reconstructed, true)
        withClue("reconstructed terminal-failed snapshot + callback → Retry offered") {
            truth ?: throw AssertionError(defect)
        }
    }

    @Test
    fun `durable reconstruction of a warnings chapter with unresolved pages offers retry`() {
        //  field defect (Chapter 21): a persistence-rejected run ended
        // READY_WITH_WARNINGS — the badge read as completed and the sheet
        // offered nothing, because the truth rule only accepted ERROR.
        val defect =
            "T924 field defect: a durable READY_WITH_WARNINGS chapter that ENDED " +
                "must project FINISHED and keep offering Retry after an app restart"
        val reconstructed = TranslationBatchProgressTracker.computeSnapshot(
            chapterId = 1L,
            chapterState = Translation.State.READY_WITH_WARNINGS,
            pageMap = emptyMap(),
        )
        withClue("compute must treat a durable READY_WITH_WARNINGS chapter as a finished run") {
            reconstructed.state shouldBe Translation.State.READY_WITH_WARNINGS
            reconstructed.batchPhase shouldBe TranslationBatchPhase.FINISHED
        }
        val truth = callTruth("forSheetRetryAction", 2, defect, reconstructed, true)
        withClue("durable warnings snapshot + callback → Retry offered") {
            truth ?: throw AssertionError(defect)
        }
    }

    @Test
    fun `a fully finished translated chapter never offers retry`() {
        val finished = TranslationBatchProgressTracker.computeSnapshot(
            chapterId = 1L,
            chapterState = Translation.State.TRANSLATED,
            pageMap = emptyMap(),
        )
        finished.batchPhase shouldBe TranslationBatchPhase.IDLE
        val truth = callTruth(
            "forSheetRetryAction",
            2,
            "translated chapter must never offer Retry",
            finished,
            true,
        )
        truth shouldBe null
    }
}
