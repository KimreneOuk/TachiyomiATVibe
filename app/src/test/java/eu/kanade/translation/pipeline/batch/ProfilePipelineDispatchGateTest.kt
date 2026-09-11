package eu.kanade.translation.pipeline.batch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T924 zero-legacy (D1): the FF-01 A/B flag is gone — the dispatch gate is
 * ENGINE-CATEGORY only.
 *
 * Truth table:
 *  - STANDARD engine → STANDARD_PIPELINE (the same coordinator, per-page
 *    standard tail — the Director design: both engines do the same OCR, the
 *    standard engine continues batch translation without the glossary).
 *  - AI_MODEL (contextual or not) → PROFILE_PIPELINE. The degenerate
 *    non-contextual AI config no longer falls back to a legacy coordinator:
 *    it takes the coordinator's typed CONFIGURATION pause at the envelope
 *    seam.
 *
 * `engineCategoryIsStandard` mirrors `translationEngineCategory == STANDARD`
 * computed in `BatchChapterTranslator`; the dispatch site passes that same
 * value, so the truth table below is the production decision.
 */
class ProfilePipelineDispatchGateTest {

    @Test
    fun `standard engine dispatches the standard pipeline lane`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            engineCategoryIsStandard = true,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE
    }

    @Test
    fun `AI engine category dispatches the profile pipeline lane`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            engineCategoryIsStandard = false,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
    }

    @Test
    fun `gate is exactly the zero-legacy truth table over the engine category`() {
        // Parity anchor: the engine category is the ONLY input — no flag, no
        // contextual-AI parity, no legacy escape hatch.
        for (standard in listOf(false, true)) {
            val expected = if (standard) {
                ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE
            } else {
                ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
            }
            BatchChapterTranslator.profilePipelineDispatchKind(
                engineCategoryIsStandard = standard,
            ) shouldBe expected
            // The coordinator companion carries the same mapping (with its
            // default argument), so the shell seam and the coordinator can
            // never drift.
            ChapterProfileBatchCoordinator.dispatchKind(
                engineCategoryIsStandard = standard,
            ) shouldBe expected
        }
    }

    @Test
    fun `CPC dispatchKind default (non-standard) is the profile pipeline`() {
        // Callers that only know "not STANDARD" (the degenerate AI case
        // included) get PROFILE_PIPELINE — never a legacy coordinator.
        ChapterProfileBatchCoordinator.dispatchKind() shouldBe
            ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
    }
}
