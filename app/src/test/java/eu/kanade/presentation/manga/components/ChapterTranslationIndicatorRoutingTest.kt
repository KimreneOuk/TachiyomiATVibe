package eu.kanade.presentation.manga.components

import eu.kanade.translation.model.Translation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 *  slice 1: tap routing for the chapter translation indicator. Every state
 * with observable translation work must route a tap to the progress drawer
 * (DETAILS); only a chapter with no work and no pending request starts a new
 * batch from a tap. Long-press actions (cancel/retry/menu) stay as secondary
 * affordances and are not part of this table.
 */
class ChapterTranslationIndicatorRoutingTest {

    @Test
    fun `pending request routes tap to the progress drawer`() {
        assertEquals(
            ChapterTranslationAction.DETAILS,
            translationIndicatorTapAction(Translation.State.NOT_TRANSLATED, hasPendingRequest = true),
        )
    }

    @Test
    fun `idle chapter without work starts a new batch`() {
        assertEquals(
            ChapterTranslationAction.START,
            translationIndicatorTapAction(Translation.State.NOT_TRANSLATED, hasPendingRequest = false),
        )
    }

    @Test
    fun `queued translating and paused states route tap to the progress drawer`() {
        listOf(
            Translation.State.QUEUE,
            Translation.State.TRANSLATING,
            Translation.State.PAUSED,
        ).forEach { state ->
            assertEquals(
                ChapterTranslationAction.DETAILS,
                translationIndicatorTapAction(state, hasPendingRequest = false),
                "state $state must route tap to DETAILS",
            )
        }
    }

    @Test
    fun `translated warning and error states route tap to the progress drawer`() {
        listOf(
            Translation.State.TRANSLATED,
            Translation.State.READY_WITH_WARNINGS,
            Translation.State.ERROR,
        ).forEach { state ->
            assertEquals(
                ChapterTranslationAction.DETAILS,
                translationIndicatorTapAction(state, hasPendingRequest = false),
                "state $state must route tap to DETAILS",
            )
        }
    }
}
