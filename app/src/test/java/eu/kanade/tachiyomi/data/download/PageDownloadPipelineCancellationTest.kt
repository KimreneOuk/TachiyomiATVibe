package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.BatchDownloadTraceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class PageDownloadPipelineCancellationTest {

    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns 42L
        every { name } returns "Chapter 42"
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 7L
        every { title } returns "Fixture Manga"
    }
    private val page = Page(0, "http://fixture/page/0", "http://fixture/image/0.jpg")

    @Test
    fun `cancellation during network stream aborts response and deletes temporary file`() = runTest {
        val responseClosed = AtomicBoolean(false)
        val tempFileDeleted = AtomicBoolean(false)
        var callerJob: Job? = null

        val blockingSource = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                callerJob?.cancel(CancellationException("Simulated download cancellation"))
                throw CancellationException("Cancelled during stream read")
            }
        }.buffer()

        val responseBody = object : ResponseBody() {
            override fun contentType() = "image/jpeg".toMediaTypeOrNull()
            override fun contentLength() = 1024L
            override fun source(): BufferedSource = blockingSource
            override fun close() {
                responseClosed.set(true)
            }
        }

        val response = mockk<Response>(relaxed = true) {
            every { body } returns responseBody
            every { close() } answers { responseBody.close() }
        }

        val source = mockk<HttpSource>(relaxed = true) {
            coEvery { getImage(page) } returns response
        }
        val download = Download(source, manga, chapter).apply {
            pages = listOf(page)
        }

        val tempFile = mockk<UniFile>(relaxed = true) {
            every { name } returns "001.tmp"
            every { openOutputStream() } returns ByteArrayOutputStream()
            every { delete() } answers {
                tempFileDeleted.set(true)
                true
            }
        }
        val tmpDir = mockk<UniFile>(relaxed = true) {
            every { findFile(any()) } returns null
            every { listFiles() } returns emptyArray()
            every { createFile(any()) } returns tempFile
        }

        val pipeline = createPipeline()
        val deferred = async {
            callerJob = currentCoroutineContext().job
            pipeline.getOrDownloadImage(
                page = page,
                download = download,
                tmpDir = tmpDir,
                traceContext = BatchDownloadTraceContext(42L) { null },
            )
        }

        shouldThrow<CancellationException> {
            deferred.await()
        }

        responseClosed.get() shouldBe true
        tempFileDeleted.get() shouldBe true
    }

    @Test
    fun `network io failure deletes temporary file and marks page error`() = runTest {
        val tempFileDeleted = AtomicBoolean(false)
        val failingSource = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                throw IOException("Network connection reset")
            }
        }.buffer()

        val responseBody = object : ResponseBody() {
            override fun contentType() = "image/jpeg".toMediaTypeOrNull()
            override fun contentLength() = 512L
            override fun source(): BufferedSource = failingSource
        }
        val response = mockk<Response>(relaxed = true) {
            every { body } returns responseBody
        }

        val source = mockk<HttpSource>(relaxed = true) {
            coEvery { getImage(page) } returns response
        }
        val download = Download(source, manga, chapter).apply {
            pages = listOf(page)
        }

        val tempFile = mockk<UniFile>(relaxed = true) {
            every { name } returns "001.tmp"
            every { openOutputStream() } returns ByteArrayOutputStream()
            every { delete() } answers {
                tempFileDeleted.set(true)
                true
            }
        }
        val tmpDir = mockk<UniFile>(relaxed = true) {
            every { findFile(any()) } returns null
            every { listFiles() } returns emptyArray()
            every { createFile(any()) } returns tempFile
        }

        val pipeline = createPipeline()
        val result = pipeline.getOrDownloadImage(
            page = page,
            download = download,
            tmpDir = tmpDir,
            traceContext = BatchDownloadTraceContext(42L) { null },
        )

        result shouldBe null
        page.status shouldBe Page.State.ERROR
        tempFileDeleted.get() shouldBe true
    }

    @Test
    fun `successful download closes response and leaves published file`() = runTest {
        val responseClosed = AtomicBoolean(false)
        val published = AtomicBoolean(false)
        val imagePayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val sourceBuffer = Buffer().write(imagePayload)

        val responseBody = object : ResponseBody() {
            override fun contentType() = "image/jpeg".toMediaTypeOrNull()
            override fun contentLength() = imagePayload.size.toLong()
            override fun source(): BufferedSource = sourceBuffer
            override fun close() {
                responseClosed.set(true)
            }
        }
        val response = mockk<Response>(relaxed = true) {
            every { body } returns responseBody
            every { close() } answers { responseBody.close() }
        }

        val source = mockk<HttpSource>(relaxed = true) {
            coEvery { getImage(page) } returns response
        }
        val download = Download(source, manga, chapter).apply {
            pages = listOf(page)
        }

        val finalFile = mockk<UniFile>(relaxed = true) {
            every { name } returns "001.jpg"
            every { exists() } returns true
            every { isFile } returns true
            every { length() } returns imagePayload.size.toLong()
        }
        val tmpDir = mockk<UniFile>(relaxed = true)
        val tempFile = mockk<UniFile>(relaxed = true) {
            every { name } returns "001.tmp"
            every { parentFile } returns tmpDir
            every { openOutputStream() } returns ByteArrayOutputStream()
            every { renameTo("001.jpg") } answers {
                published.set(true)
                true
            }
            every { openInputStream() } answers { ByteArrayInputStream(imagePayload) }
        }
        every { tmpDir.findFile("001.tmp") } returns null
        every { tmpDir.findFile("001.jpg") } answers { if (published.get()) finalFile else null }
        every { tmpDir.listFiles() } answers { if (published.get()) arrayOf(finalFile) else emptyArray() }
        every { tmpDir.createFile(any()) } returns tempFile

        val pipeline = createPipeline()
        val result = pipeline.getOrDownloadImage(
            page = page,
            download = download,
            tmpDir = tmpDir,
            traceContext = BatchDownloadTraceContext(42L) { null },
        )

        result shouldBe Downloader.PublishedPageFiles(listOf(finalFile))
        page.status shouldBe Page.State.READY
        responseClosed.get() shouldBe true
    }

    private fun createPipeline(): PageDownloadPipeline {
        val context = mockk<Context>(relaxed = true)
        val nonExistentFile = File("non_existent_cache_file")
        val chapterCache = mockk<ChapterCache>(relaxed = true) {
            every { getImageFile(any()) } returns nonExistentFile
            every { isImageInCache(any()) } returns false
        }
        val splitPreference = mockk<Preference<Boolean>>(relaxed = true) {
            every { get() } returns false
        }
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true) {
            every { splitTallImages() } returns splitPreference
        }
        val notifier = mockk<DownloadNotifier>(relaxed = true)
        return PageDownloadPipeline(context, chapterCache, downloadPreferences, notifier)
    }
}
