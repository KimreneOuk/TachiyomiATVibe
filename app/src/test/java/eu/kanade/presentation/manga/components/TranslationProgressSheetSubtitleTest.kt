package eu.kanade.presentation.manga.components

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T911 slice 1 (post-review): the drawer subtitle must never render an unknown
 * translation total as "(0/0)" — the same contract as the hero, applied to the
 * header subtitle's translating fallback.
 */
class TranslationProgressSheetSubtitleTest {

    private fun snapshot(
        state: Translation.State = Translation.State.NOT_TRANSLATED,
        requestPhase: TranslationRequestPhase? = null,
        donePages: Int = 0,
        totalPages: Int = 0,
        totalStages: Int = 0,
        batchPhase: TranslationBatchPhase = TranslationBatchPhase.IDLE,
    ): TranslationProgressSnapshot {
        val base = TranslationProgressSnapshot.empty(1L, state)
            .copy(
                donePages = donePages,
                totalPages = totalPages,
                totalStages = totalStages,
                batchPhase = batchPhase,
            )
        return if (requestPhase == null) {
            base
        } else {
            base.copy(requestState = TranslationRequestState(chapterId = 1L, phase = requestPhase))
        }
    }

    @Test
    fun `translating first pass without registered totals shows the preparing phase instead of 0-0`() {
        val subtitle = batchStatusHeaderSubtitle(
            snapshot(
                state = Translation.State.TRANSLATING,
                batchPhase = TranslationBatchPhase.FIRST_PASS,
            ),
        )

        assertFalse(subtitle.contains("0/0"))
        assertEquals("Preparing translation batch...", subtitle)
    }

    @Test
    fun `real totals keep the numeric page progress subtitle`() {
        val subtitle = batchStatusHeaderSubtitle(
            snapshot(
                state = Translation.State.TRANSLATING,
                batchPhase = TranslationBatchPhase.FIRST_PASS,
                donePages = 12,
                totalPages = 40,
                totalStages = 160,
            ),
        )

        assertTrue(subtitle.contains("12/40"))
        assertFalse(subtitle.contains("0/0"))
    }

    @Test
    fun `waiting request subtitle stays phase aware and numeric free`() {
        val subtitle = batchStatusHeaderSubtitle(
            snapshot(requestPhase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
        )

        assertFalse(subtitle.contains("0/0"))
        assertEquals("Waiting for chapter download before translation", subtitle)
    }
}
