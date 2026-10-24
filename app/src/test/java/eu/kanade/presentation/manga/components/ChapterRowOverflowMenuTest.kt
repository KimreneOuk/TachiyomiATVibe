package eu.kanade.presentation.manga.components

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Menu routing for the chapter row's overflow menu. Every state with
 * observable translation work exposes the progress drawer (DETAILS); only
 * chapters with committed data expose a reset (DELETE).
 */
class ChapterRowOverflowMenuTest {

    @Test
    fun `idle chapter offers only translate`() {
        assertEquals(
            listOf(ChapterTranslationAction.START),
            chapterOverflowTranslationActions(Translation.State.NOT_TRANSLATED, hasPendingRequest = false),
        )
    }

    @Test
    fun `pending request offers details and cancel`() {
        assertEquals(
            listOf(ChapterTranslationAction.DETAILS, ChapterTranslationAction.CANCEL),
            chapterOverflowTranslationActions(Translation.State.NOT_TRANSLATED, hasPendingRequest = true),
        )
    }

    @Test
    fun `queued and translating states offer details and cancel`() {
        listOf(Translation.State.QUEUE, Translation.State.TRANSLATING).forEach { state ->
            assertEquals(
                listOf(ChapterTranslationAction.DETAILS, ChapterTranslationAction.CANCEL),
                chapterOverflowTranslationActions(state, hasPendingRequest = false),
                "state $state must offer DETAILS and CANCEL",
            )
        }
    }

    @Test
    fun `paused state offers resume before details and cancel`() {
        assertEquals(
            listOf(ChapterTranslationAction.START, ChapterTranslationAction.DETAILS, ChapterTranslationAction.CANCEL),
            chapterOverflowTranslationActions(Translation.State.PAUSED, hasPendingRequest = false),
        )
    }

    @Test
    fun `states with committed data offer details retranslate and reset`() {
        listOf(
            Translation.State.TRANSLATED,
            Translation.State.READY_WITH_WARNINGS,
            Translation.State.ERROR,
        ).forEach { state ->
            assertEquals(
                listOf(
                    ChapterTranslationAction.DETAILS,
                    ChapterTranslationAction.START,
                    ChapterTranslationAction.DELETE,
                ),
                chapterOverflowTranslationActions(state, hasPendingRequest = false),
                "state $state must offer DETAILS, START and DELETE",
            )
        }
    }

    @Test
    fun `download menu follows download state`() {
        assertEquals(
            listOf(ChapterDownloadAction.START, ChapterDownloadAction.START_NOW),
            chapterOverflowDownloadActions(Download.State.NOT_DOWNLOADED),
        )
        assertEquals(
            listOf(ChapterDownloadAction.START_NOW, ChapterDownloadAction.CANCEL),
            chapterOverflowDownloadActions(Download.State.QUEUE),
        )
        assertEquals(
            listOf(ChapterDownloadAction.DELETE),
            chapterOverflowDownloadActions(Download.State.DOWNLOADED),
        )
        assertEquals(
            listOf(ChapterDownloadAction.START),
            chapterOverflowDownloadActions(Download.State.ERROR),
        )
    }
}
