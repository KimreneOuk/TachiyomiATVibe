package eu.kanade.translation.pipeline.batch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T924 F3 / wave-2 review R3 + gap 8: the FF-01 dispatch gate must consult
 * engine-category parity WITH the flag, because the flagged coordinator is
 * preflight-only today. A non-AI (or non-contextual) translator with FF-01 ON
 * must keep running the verbatim legacy coordinator — a flagged run would stop
 * after OCR preflight and never translate (risk R3).
 *
 * `contextualAiParity` mirrors the legacy lane's `isAi` gate computed in
 * `BatchChapterTranslator` (`translationEngineCategory == AI_MODEL &&
 * textTranslator is ContextualTextTranslator`); the dispatch site passes that
 * same value, so the truth table below is the production decision.
 */
class ProfilePipelineDispatchGateTest {

    @Test
    fun `non-AI engine with flag ON stays on the legacy coordinator`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = true,
            contextualAiParity = false,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
    }

    @Test
    fun `contextual AI engine with flag ON constructs the flagged coordinator`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = true,
            contextualAiParity = true,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
    }

    @Test
    fun `flag OFF always selects the legacy coordinator regardless of engine parity`() {
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = false,
            contextualAiParity = true,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
        BatchChapterTranslator.profilePipelineDispatchKind(
            flagOn = false,
            contextualAiParity = false,
        ) shouldBe ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
    }

    @Test
    fun `gate is exactly flag AND parity over the FF-01a dispatch mapping`() {
        // Parity anchor: the gate composes the (already pinned) FF-01a flag
        // mapping, never replaces it — flag ON + parity is the ONLY
        // PROFILE_PIPELINE row of the truth table.
        for (flag in listOf(false, true)) {
            for (parity in listOf(false, true)) {
                val expected = if (flag && parity) {
                    ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE
                } else {
                    ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL
                }
                BatchChapterTranslator.profilePipelineDispatchKind(flag, parity) shouldBe expected
            }
        }
    }
}
