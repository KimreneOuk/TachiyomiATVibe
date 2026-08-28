package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * T907: the translation-driven enqueue bridge must guarantee the downloader
 * runs even when the download queue was not empty before the enqueue — stock
 * auto-start only fires on an empty queue, so a stale or restored entry
 * (including a retained ERROR download) used to leave fresh QUEUED chapters
 * stalled. MangaScreenModel itself is not unit-testable at this tier, so the
 * guarantee lives behind the [enqueueTranslationDownloads] seam.
 */
class EnqueueTranslationDownloadsTest {

    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val manga = mockk<Manga>(relaxed = true)
    private val source = mockk<HttpSource>(relaxed = true)

    private fun chapter(id: Long) = mockk<Chapter>(relaxed = true) {
        every { this@mockk.id } returns id
    }

    private fun queuedDownload(chapter: Chapter, status: Download.State): Download =
        Download(source, manga, chapter).also { it.status = status }

    @Test
    fun `starts the downloader when the queue holds a stale non-error entry`() {
        val stale = chapter(1L)
        val fresh = chapter(2L)
        every { downloadManager.getQueuedDownloadOrNull(1L) } returns
            queuedDownload(stale, Download.State.QUEUE)
        every { downloadManager.getQueuedDownloadOrNull(2L) } returns null

        enqueueTranslationDownloads(downloadManager, manga, listOf(stale, fresh))

        verify { downloadManager.downloadChapters(manga, listOf(stale, fresh)) }
        verify { downloadManager.startDownloads() }
        // A stale non-error entry is not an explicit retry; it must not be
        // re-fronted, but it must not block the start guarantee either.
        verify(exactly = 0) { downloadManager.startDownloadNow(any()) }
    }

    @Test
    fun `re-fronts a retained ERROR entry and still starts the downloader`() {
        val failed = chapter(7L)
        val fresh = chapter(8L)
        every { downloadManager.getQueuedDownloadOrNull(7L) } returns
            queuedDownload(failed, Download.State.ERROR)
        every { downloadManager.getQueuedDownloadOrNull(8L) } returns null

        enqueueTranslationDownloads(downloadManager, manga, listOf(failed, fresh))

        verify { downloadManager.downloadChapters(manga, listOf(failed, fresh)) }
        verify { downloadManager.startDownloadNow(7L) }
        verify { downloadManager.startDownloads() }
    }

    @Test
    fun `empty chapter list is a no-op`() {
        enqueueTranslationDownloads(downloadManager, manga, emptyList())

        verify(exactly = 0) { downloadManager.downloadChapters(any(), any()) }
        verify(exactly = 0) { downloadManager.startDownloads() }
    }
}
