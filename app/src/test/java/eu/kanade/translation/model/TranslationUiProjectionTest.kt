package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationUiProjectionTest {

    @Test
    fun `immediate request wins over stale persisted translation state`() {
        val request = TranslationRequestState(7L, TranslationRequestPhase.STARTING)

        TranslationUiProjection.chapterState(
            queuedState = null,
            persistedState = Translation.State.TRANSLATED,
            requestState = request,
            downloaded = true,
        ) shouldBe Translation.State.NOT_TRANSLATED
    }

    @Test
    fun `live queue wins over stale download cache state`() {
        TranslationUiProjection.chapterState(
            queuedState = Translation.State.TRANSLATING,
            persistedState = Translation.State.NOT_TRANSLATED,
            requestState = null,
            downloaded = false,
        ) shouldBe Translation.State.TRANSLATING
    }

    @Test
    fun `waiting requests and paused queue entries protect reader files`() {
        TranslationUiProjection.protectsChapterFromDeletion(
            queuedState = null,
            hasPendingRequest = true,
        ) shouldBe true
        TranslationUiProjection.protectsChapterFromDeletion(
            queuedState = Translation.State.PAUSED,
            hasPendingRequest = false,
        ) shouldBe true
        TranslationUiProjection.protectsChapterFromDeletion(
            queuedState = Translation.State.TRANSLATED,
            hasPendingRequest = false,
        ) shouldBe false
    }
}
