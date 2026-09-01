package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * T911 slice 1: full chapter-list rebuilds reconstruct every
 * [ChapterList.Item] without progress, and the per-chapter collector can be
 * cancelled (terminal) or suppressed (unchanged canonical snapshot) at exactly
 * that moment. The screen-model registry must retain the last live/terminal
 * snapshot so the rebuilt items read it back.
 */
class ChapterTranslationSnapshotRegistryTest {

    private fun chapter(id: Long) = Chapter.create().copy(id = id, name = "Chapter $id")

    private fun item(id: Long) = ChapterList.Item(
        chapter = chapter(id),
        downloadState = Download.State.NOT_DOWNLOADED,
        downloadProgress = 0,
    )

    @Test
    fun `remember stores the latest snapshot per chapter`() {
        val registry = ChapterTranslationSnapshotRegistry()
        val first = TranslationProgressSnapshot.empty(1L)
        val second = TranslationProgressSnapshot.empty(1L).copy(donePages = 3, totalPages = 9)

        registry.remember(1L, first)
        assertSame(first, registry.snapshotFor(1L))
        registry.remember(1L, second)
        assertSame(second, registry.snapshotFor(1L))
    }

    @Test
    fun `a null emission retains the previous snapshot`() {
        val registry = ChapterTranslationSnapshotRegistry()
        val snapshot = TranslationProgressSnapshot.empty(1L)
        registry.remember(1L, snapshot)

        registry.remember(1L, null)

        assertSame(snapshot, registry.snapshotFor(1L))
    }

    @Test
    fun `forget drops the retained snapshot`() {
        val registry = ChapterTranslationSnapshotRegistry()
        registry.remember(1L, TranslationProgressSnapshot.empty(1L))

        registry.forget(1L)

        assertNull(registry.snapshotFor(1L))
        assertEquals(0, registry.size())
    }

    @Test
    fun `a full chapter-list rebuild without a new canonical emission retains the live snapshot`() {
        val registry = ChapterTranslationSnapshotRegistry()
        val live = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATING)
            .copy(donePages = 12, totalPages = 40, totalStages = 160)
        registry.remember(1L, live)

        // Simulates the combined collector re-running on a manga/download-cache/
        // download-queue/translation-queue/pending-request emission: every item is
        // reconstructed with translationProgress = null.
        val rebuilt = listOf(item(1L), item(2L)).carryingTranslationSnapshots(registry)

        assertSame(live, rebuilt.first { it.id == 1L }.translationProgress)
        assertNull(rebuilt.first { it.id == 2L }.translationProgress)
    }

    @Test
    fun `a terminal snapshot survives the collector cancellation followed by a rebuild`() {
        val registry = ChapterTranslationSnapshotRegistry()
        val terminal = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATED)
            .copy(donePages = 40, totalPages = 40, totalStages = 160, batchPhase = eu.kanade.translation.model.TranslationBatchPhase.FINISHED)
        registry.remember(1L, terminal)
        // The collector is cancelled at terminal status; nothing removes the
        // registry entry (only explicit reset/delete does).
        assertEquals(1, registry.size())

        val rebuilt = listOf(item(1L)).carryingTranslationSnapshots(registry)

        assertSame(terminal, rebuilt.single().translationProgress)
    }

    @Test
    fun `an item that already carries a live snapshot is not overwritten`() {
        val registry = ChapterTranslationSnapshotRegistry()
        val stale = TranslationProgressSnapshot.empty(1L)
        val fresher = TranslationProgressSnapshot.empty(1L).copy(donePages = 5, totalPages = 10)
        registry.remember(1L, stale)
        val carrying = ChapterList.Item(
            chapter = chapter(1L),
            downloadState = Download.State.NOT_DOWNLOADED,
            downloadProgress = 0,
            translationProgress = fresher,
        )

        val rebuilt = listOf(carrying).carryingTranslationSnapshots(registry)

        assertSame(fresher, rebuilt.single().translationProgress)
    }

    @Test
    fun `registry stays bounded to one record per chapter`() {
        val registry = ChapterTranslationSnapshotRegistry()
        (1L..64L).forEach { id ->
            registry.remember(id, TranslationProgressSnapshot.empty(id))
        }
        registry.forget(64L)

        assertEquals(63, registry.size())
        assertNull(registry.snapshotFor(null))
    }
}
