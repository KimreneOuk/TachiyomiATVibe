package eu.kanade.tachiyomi.data.download

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class DownloadedPageIndexTest {

    @Test
    fun `complete page index is ordered and does not probe child metadata or content`() {
        val files = (260 downTo 1).map { pageNumber ->
            val uri = mockk<Uri>()
            mockk<UniFile> {
                every { name } returns pageNumber.toString().padStart(3, '0') + ".jpg"
                every { getUri() } returns uri
            }
        }
        var elapsedNanos = 0L

        val entries = collectDownloadedPageEntries(
            rawFiles = files.toTypedArray(),
            elapsedRealtimeNanos = { elapsedNanos++ },
            isImageName = ::isSupportedImageName,
        )
        val pages = createDownloadedPageList(entries.entries)

        entries.nameReadCalls shouldBe 260
        entries.skippedCount shouldBe 0
        entries.errorCount shouldBe 0
        pages.size shouldBe 260
        pages.map { it.first } shouldContainExactly (1..260).map {
            it.toString().padStart(3, '0') + ".jpg"
        }
        pages.map { it.second.index } shouldContainExactly (0 until 260).toList()
        pages.all { it.second.status == Page.State.READY } shouldBe true
        pages.forEachIndexed { index, (_, page) ->
            page.uri shouldBe files.reversed()[index].uri
        }
        files.forEach { file ->
            verify(exactly = 0) { file.isFile }
            verify(exactly = 0) { file.openInputStream() }
        }
    }

    @Test
    fun `unsupported nameless and unreadable children are skipped without aborting valid pages`() {
        val firstUri = mockk<Uri>()
        val secondUri = mockk<Uri>()
        val first = downloadedFile("010.webp", firstUri)
        val metadata = downloadedFile("details.json", mockk())
        val nameless = downloadedFile(null, mockk())
        val unreadable = mockk<UniFile> {
            every { name } returns "005.png"
            every { getUri() } throws IllegalStateException("stale handle")
        }
        val second = downloadedFile("002.png", secondUri)
        var elapsedNanos = 0L

        val result = collectDownloadedPageEntries(
            rawFiles = arrayOf(first, metadata, nameless, unreadable, second),
            elapsedRealtimeNanos = { elapsedNanos++ },
            isImageName = ::isSupportedImageName,
        )
        val pages = createDownloadedPageList(result.entries)

        pages.map { it.first } shouldContainExactly listOf("002.png", "010.webp")
        pages.map { it.second.uri } shouldContainExactly listOf(secondUri, firstUri)
        pages.map { it.second.index } shouldContainExactly listOf(0, 1)
        result.skippedCount shouldBe 3
        result.errorCount shouldBe 1
        listOf(first, metadata, nameless, unreadable, second).forEach { file ->
            verify(exactly = 0) { file.isFile }
            verify(exactly = 0) { file.openInputStream() }
        }
    }

    private fun downloadedFile(name: String?, uri: Uri): UniFile {
        return mockk {
            every { getName() } returns name
            every { getUri() } returns uri
        }
    }

    private fun isSupportedImageName(name: String): Boolean {
        return name.substringAfterLast('.') in setOf("jpg", "png", "webp")
    }
}
