package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.BatchDownloadTraceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.lang.reflect.Field

class DownloaderPagePublicationTest {

    @Test
    fun `failed temporary rename is rejected before page publication`() {
        val file = mockk<UniFile> {
            every { renameTo("001.jpg") } returns false
        }

        shouldThrow<IOException> {
            publishDownloadedFile(file, "001.jpg")
        }
    }

    @Test
    fun `known page handle fills an empty SAF listing and satisfies strict validation`() {
        val downloader = emptyDownloader()
        val pageFile = mockk<UniFile> {
            every { name } returns "001.jpg"
            every { exists() } returns true
            every { isFile } returns true
        }
        val tmpDir = mockk<UniFile> {
            every { listFiles() } returns emptyArray()
            every { findFile(any()) } returns null
        }
        val published = mapOf(0 to Downloader.PublishedPageFiles(listOf(pageFile)))

        val chapterFiles = downloader.collectChapterFiles(tmpDir, published)
        chapterFiles.listingAvailable shouldBe true
        chapterFiles.files shouldContainExactly listOf(pageFile)

        val chapter = mockk<Chapter>(relaxed = true) {
            every { id } returns 10L
        }
        val download = Download(mockk<HttpSource>(relaxed = true), mockk<Manga>(relaxed = true), chapter).apply {
            pages = listOf(Page(0, "chapter", "image").apply { status = Page.State.READY })
        }
        val validation = downloader.validateDownload(
            download = download,
            tmpDir = tmpDir,
            traceContext = BatchDownloadTraceContext(10L) { null },
            publishedFiles = published,
        )

        validation.expected shouldBe 1
        validation.ready shouldBe 1
        validation.onDisk shouldBe 1
        validation.success shouldBe true
    }

    @Test
    fun `unavailable listing is not accepted without a verified page handle`() {
        val downloader = emptyDownloader()
        val tmpDir = mockk<UniFile> {
            every { listFiles() } returns null
        }
        val chapter = mockk<Chapter>(relaxed = true) {
            every { id } returns 11L
        }
        val download = Download(mockk<HttpSource>(relaxed = true), mockk<Manga>(relaxed = true), chapter).apply {
            pages = listOf(Page(0, "chapter", "image").apply { status = Page.State.READY })
        }

        val validation = downloader.validateDownload(
            download = download,
            tmpDir = tmpDir,
            traceContext = BatchDownloadTraceContext(11L) { null },
        )

        validation.onDisk shouldBe null
        validation.success shouldBe false
    }

    private fun emptyDownloader(): Downloader {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, Downloader::class.java) as Downloader
    }
}
