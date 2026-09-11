package eu.kanade.translation.pipeline.batch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T924 F3 / wave-2 review R3 + gap 8, extended by Phase 4 Wave A: the FF-01
 * dispatch gate must consult engine-category parity WITH the flag.
 *
 * Wave A truth table (flag ON):
 *  - STANDARD engine            → STANDARD_PIPELINE (the same flagged
 *    coordinator, per-page standard tail — the Director design: both engines
 *    do the same OCR, the standard engine continues batch translation without
 *    the glossary).
 *  - AI_MODEL + contextual      → PROFILE_PIPELINE (unchanged AI lane).
 *  - AI_MODEL + non-contextual  → LEGACY_SEQUENTIAL (degenerate AI config
 *    keeps the verbatim legacy coordinator, F3).
 * Flag OFF → LEGACY_SEQUENTIAL for every engine.
 *
 * `contextualAiParity` mirrors the legacy lane's `isAi` gate computed in
 * `BatchChapterTranslator` (`translationEngineCategory == AI_MODEL &&
 * textTranslator is ContextualTextTranslator`); `engineCategoryIsStandard`
 * mirrors `translationEngineCategory == STANDARD`. The dispatch site passes
 * those same values, so the truth table below is the production decision.
 */
class ProfilePipelineDispatchGateTest {

    @Test
    fun `standard engine with flag ON dispatches the standard pipeline lane`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = true,
            engineCategoryIsStandard = true,
            contextualAiParity = false,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE
        // A standard engine is never contextual; even a pathological
        // contextual=true read must stay on the standard lane.
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = true,
            engineCategoryIsStandard = true,
            contextualAiParity = true,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE
    }

    @Test
    fun `contextual AI engine with flag ON constructs the flagged coordinator`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = true,
            engineCategoryIsStandard = false,
            contextualAiParity = true,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
    }

    @Test
    fun `non-contextual AI engine with flag ON stays on the legacy coordinator`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = true,
            engineCategoryIsStandard = false,
            contextualAiParity = false,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
    }

    @Test
    fun `flag OFF always selects the legacy coordinator regardless of engine parity`() {
        for (standard in listOf(false, true)) {
            for (contextual in listOf(false, true)) {
                BatchChapterTranslator.profilePipelineDispatchKind(
                    flagOn = false,
                    engineCategoryIsStandard = standard,
                    contextualAiParity = contextual,
                ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
            }
        }
    }

    @Test
    fun `gate is exactly the Wave A truth table over the FF-01a dispatch mapping`() {
        // Parity anchor: flag OFF dominates, then the STANDARD category, then
        // the contextual AI parity — every other combination is legacy.
        for (flag in listOf(false, true)) {
            for (standard in listOf(false, true)) {
                for (contextual in listOf(false, true)) {
                    val expected = when {
                        !flag -> ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
                        standard -> ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE
                        contextual -> ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
                        else -> ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
                    }
                    BatchChapterTranslator.profilePipelineDispatchKind(
                        flag,
                        standard,
                        contextual,
                    ) shouldBe expected
                }
            }
        }
    }

    @Test
    fun `CPC dispatchKind carries the same mapping with legacy-compatible defaults`() {
        // The OFF mapping stays byte-identical for callers that only know the
        // flag (the pre-Wave A signature shape).
        ChapterProfileBatchCoordinator.dispatchKind(translationBatchProfilePipeline = false) shouldBe
            ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
        ChapterProfileBatchCoordinator.dispatchKind(
            translationBatchProfilePipeline = true,
            engineCategoryIsStandard = true,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.STANDARD_PIPELINE
        ChapterProfileBatchCoordinator.dispatchKind(
            translationBatchProfilePipeline = true,
            contextualAiParity = true,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
        ChapterProfileBatchCoordinator.dispatchKind(translationBatchProfilePipeline = true) shouldBe
            ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
    }
}
