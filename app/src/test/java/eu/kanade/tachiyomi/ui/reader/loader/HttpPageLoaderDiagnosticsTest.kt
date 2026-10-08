package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.diagnostics.TelemetryTrace
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class HttpPageLoaderDiagnosticsTest {

    private val traces = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        traces.clear()
        TelemetryTrace.setTestSink { traces.add(it) }
    }

    @AfterEach
    fun tearDown() {
        TelemetryTrace.setTestSink(null)
    }

    private fun createTestChapter(id: Long = 42L): ReaderChapter {
        return ReaderChapter(
            ChapterImpl().apply {
                this.id = id
                this.manga_id = 100L
                this.url = "/chapter/42"
                this.name = "Chapter 42"
            },
        )
    }

    @Test
    fun `getPages emits get_pages with cacheHit true on cache hit`() = runBlocking {
        val chapter = createTestChapter(42L)
        val source = mockk<HttpSource>(relaxed = true)
        val chapterCache = mockk<ChapterCache>(relaxed = true)
        every { chapterCache.getPageListFromCache(any()) } returns listOf(
            Page(0, "url0", "https://img.test/0.jpg"),
            Page(1, "url1", "https://img.test/1.jpg"),
        )

        val loader = HttpPageLoader(chapter, source, chapterCache)
        try {
            val pages = loader.getPages()
            assertEquals(2, pages.size)
            assertTrue(
                traces.any {
                    it.contains("domain=loader") &&
                        it.contains("event=get_pages") &&
                        it.contains("cacheHit=true") &&
                        it.contains("pageCount=2") &&
                        it.contains("chapterId=42") &&
                        it.contains("durationMs=")
                },
                "Expected get_pages with cacheHit=true trace",
            )
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `getPages emits get_pages with cacheHit false on cache miss and network fetch`() = runBlocking {
        val chapter = createTestChapter(42L)
        val source = mockk<HttpSource>(relaxed = true)
        val chapterCache = mockk<ChapterCache>(relaxed = true)
        every { chapterCache.getPageListFromCache(any()) } throws IOException("Cache miss")
        coEvery { source.getPageList(any()) } returns listOf(
            Page(0, "url0", "https://img.test/0.jpg"),
            Page(1, "url1", "https://img.test/1.jpg"),
            Page(2, "url2", "https://img.test/2.jpg"),
        )

        val loader = HttpPageLoader(chapter, source, chapterCache)
        try {
            val pages = loader.getPages()
            assertEquals(3, pages.size)
            assertTrue(
                traces.any {
                    it.contains("domain=loader") &&
                        it.contains("event=get_pages") &&
                        it.contains("cacheHit=false") &&
                        it.contains("pageCount=3") &&
                        it.contains("chapterId=42") &&
                        it.contains("durationMs=")
                },
                "Expected get_pages with cacheHit=false trace",
            )
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `internalLoadPage emits image_fetch_done for disk cache hit`(@TempDir tempDir: File) = runBlocking {
        val chapter = createTestChapter(42L)
        val source = mockk<HttpSource>(relaxed = true)
        val chapterCache = mockk<ChapterCache>(relaxed = true)

        val imageFile = File(tempDir, "cached_image.jpg").apply {
            writeBytes(ByteArray(2048) { 1 })
        }
        val url = "https://img.test/cached.jpg"
        val page = ReaderPage(0, "page0", url).apply {
            this.chapter = chapter
        }

        every { chapterCache.isImageInCache(url) } returns true
        every { chapterCache.getImageFile(url) } returns imageFile

        val loader = HttpPageLoader(chapter, source, chapterCache)
        try {
            loader.internalLoadPage(page)
            assertEquals(Page.State.READY, page.status)
            assertTrue(
                traces.any {
                    it.contains("domain=loader") &&
                        it.contains("event=image_fetch_done") &&
                        it.contains("pageIndex=0") &&
                        it.contains("url=$url") &&
                        it.contains("inDiskCache=true") &&
                        it.contains("bytes=2048") &&
                        it.contains("speedKbS=0.0") &&
                        it.contains("durationMs=")
                },
                "Expected image_fetch_done with inDiskCache=true trace",
            )
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `internalLoadPage emits image_fetch_done with speed for network download`(@TempDir tempDir: File) = runBlocking {
        val chapter = createTestChapter(42L)
        val source = mockk<HttpSource>(relaxed = true)
        val chapterCache = mockk<ChapterCache>(relaxed = true)

        val imageFile = File(tempDir, "downloaded_image.jpg").apply {
            writeBytes(ByteArray(4096) { 2 })
        }
        val url = "https://img.test/download.jpg"
        val page = ReaderPage(1, "page1", url).apply {
            this.chapter = chapter
        }

        val mockResponse = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ByteArray(4096).toResponseBody("image/jpeg".toMediaType()))
            .build()

        every { chapterCache.isImageInCache(url) } returns false
        coEvery { source.getImage(page) } returns mockResponse
        every { chapterCache.putImageToCache(url, any()) } answers { }
        every { chapterCache.getImageFile(url) } returns imageFile

        val loader = HttpPageLoader(chapter, source, chapterCache)
        try {
            loader.internalLoadPage(page)
            assertEquals(Page.State.READY, page.status)
            assertTrue(
                traces.any {
                    it.contains("domain=loader") &&
                        it.contains("event=image_fetch_done") &&
                        it.contains("pageIndex=1") &&
                        it.contains("url=$url") &&
                        it.contains("inDiskCache=false") &&
                        it.contains("bytes=4096") &&
                        it.contains("speedKbS=") &&
                        it.contains("durationMs=")
                },
                "Expected image_fetch_done with inDiskCache=false trace",
            )
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `internalLoadPage emits image_fetch_error and sets error state on fetch failure`() = runBlocking {
        val chapter = createTestChapter(42L)
        val source = mockk<HttpSource>(relaxed = true)
        val chapterCache = mockk<ChapterCache>(relaxed = true)

        val url = "https://img.test/fail.jpg"
        val page = ReaderPage(2, "page2", url).apply {
            this.chapter = chapter
        }

        every { chapterCache.isImageInCache(url) } returns false
        coEvery { source.getImage(page) } throws IOException("Connection timed out")

        val loader = HttpPageLoader(chapter, source, chapterCache)
        try {
            loader.internalLoadPage(page)
            assertEquals(Page.State.ERROR, page.status)
            assertTrue(
                traces.any {
                    it.contains("domain=loader") &&
                        it.contains("event=image_fetch_error") &&
                        it.contains("pageIndex=2") &&
                        it.contains("errorType=IOException") &&
                        it.contains("errorMessage=Connection timed out") &&
                        it.contains("durationMs=")
                },
                "Expected image_fetch_error trace",
            )
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `viewer page_stream_process telemetry contract format`() {
        TelemetryTrace.log(
            domain = "viewer",
            event = "page_stream_process",
            "pageIndex" to 3,
            "isAnimated" to false,
            "durationMs" to "12.34",
        )
        assertTrue(
            traces.any {
                it.contains("domain=viewer") &&
                    it.contains("event=page_stream_process") &&
                    it.contains("pageIndex=3") &&
                    it.contains("isAnimated=false") &&
                    it.contains("durationMs=12.34")
            },
            "Expected viewer page_stream_process trace",
        )
    }

    @Test
    fun `reader chapter_load_done telemetry contract format`() {
        TelemetryTrace.log(
            domain = "reader",
            event = "chapter_load_done",
            "chapterId" to 42L,
            "chapterName" to "Chapter 42",
            "pageCount" to 15,
            "durationMs" to "56.78",
        )
        assertTrue(
            traces.any {
                it.contains("domain=reader") &&
                    it.contains("event=chapter_load_done") &&
                    it.contains("chapterId=42") &&
                    it.contains("chapterName=Chapter 42") &&
                    it.contains("pageCount=15") &&
                    it.contains("durationMs=56.78")
            },
            "Expected reader chapter_load_done trace",
        )
    }
}
