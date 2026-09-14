package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TachiyomiAT bug 5 regression coverage: a failed SAF enumeration or an
 * unavailable source list must not replace the download index with an empty
 * one, and an empty persisted index must not suppress the next renewal. Both
 * failure shapes previously left every chapter reported as "not downloaded"
 * while Downloader.queueChapters' live checks still found the files, making
 * the batch-translate confirm path silently enqueue nothing.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DownloadCacheRenewalGuardTest {

    @TempDir
    lateinit var tempDir: File

    private val sourceId = 7309872737163460316L
    private val mangaDirName = "[Kojiraen] 19cm ga Hairu made2"
    private val sourceDirName = "NHentai (ALL)"

    private lateinit var provider: DownloadProvider
    private lateinit var sourceManager: SourceManager
    private lateinit var extensionManager: ExtensionManager
    private lateinit var storageManager: StorageManager
    private lateinit var context: Context
    private lateinit var source: HttpSource
    private lateinit var root: UniFile
    private lateinit var sourceDir: UniFile
    private lateinit var mangaDir: UniFile
    private lateinit var chapterArchive: UniFile

    @BeforeEach
    fun setUp() {
        source = mockk<HttpSource> {
            every { id } returns sourceId
            every { name } returns "NHentai"
            every { lang } returns "all"
        }
        chapterArchive = mockk {
            every { name } returns "kojiraen_Chapter.cbz"
            every { isFile } returns true
            every { isDirectory } returns false
            every { uri } returns mockk(relaxed = true)
        }
        mangaDir = mockk {
            every { name } returns mangaDirName
            every { isDirectory } returns true
            every { listFiles() } returns arrayOf(chapterArchive)
            every { uri } returns mockk(relaxed = true)
        }
        sourceDir = mockk {
            every { name } returns sourceDirName
            every { isDirectory } returns true
            every { listFiles() } returns arrayOf(mangaDir)
            every { uri } returns mockk(relaxed = true)
        }
        root = mockk {
            every { listFiles() } returns arrayOf(sourceDir)
            every { uri } returns mockk(relaxed = true)
        }

        provider = mockk {
            every { getSourceDirName(any()) } returns sourceDirName
            every { getMangaDirName(any()) } returns mangaDirName
            every { getValidChapterDirNames("Chapter", "kojiraen") } returns
                listOf("kojiraen_Chapter", "kojiraen_Chapter.cbz")
        }
        sourceManager = mockk {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOnlineSources() } returns listOf(source)
            every { getStubSources() } returns emptyList()
            every { getOrStub(sourceId) } returns source
        }
        extensionManager = mockk {
            every { isInitialized } returns MutableStateFlow(true)
        }
        storageManager = mockk {
            every { getDownloadsDirectory() } returns root
            every { changes } returns MutableSharedFlow()
        }
        context = mockk {
            every { cacheDir } returns tempDir
        }
    }

    private fun newCache(): DownloadCache =
        DownloadCache(context, provider, sourceManager, extensionManager, storageManager)

    private fun awaitRenewal(cache: DownloadCache, timeoutMs: Long = 10_000) {
        val field = DownloadCache::class.java.getDeclaredField("renewalJob")
        field.isAccessible = true
        runBlocking {
            withTimeout(timeoutMs) {
                var job = field.get(cache) as? Job
                while (job == null) {
                    delay(5)
                    job = field.get(cache) as? Job
                }
                job.join()
            }
        }
    }

    private fun awaitCondition(
        timeoutMs: Long = 15_000,
        message: () -> String = { "Condition not met within ${timeoutMs}ms" },
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        runBlocking {
            withTimeout(timeoutMs) {
                while (!condition()) {
                    assertTrue(System.currentTimeMillis() < deadline, message)
                    delay(10)
                }
            }
        }
    }

    private fun awaitDiskCacheFile(): File {
        val file = File(tempDir, "dl_index_cache_v3")
        awaitCondition(15_000, { "disk cache file was never written" }) {
            file.exists() && file.length() > 0L
        }
        return file
    }

    private fun pollDownloaded(cache: DownloadCache, timeoutMs: Long = 15_000): Boolean {
        if (cache.isChapterDownloaded("Chapter", "kojiraen", mangaDirName, sourceId, skipCache = false)) {
            return true
        }
        awaitRenewal(cache, timeoutMs)
        return cache.isChapterDownloaded("Chapter", "kojiraen", mangaDirName, sourceId, skipCache = false)
    }

    @Test
    fun `failed root listing keeps previous index and does not report chapters missing`() {
        val cache = newCache()
        assertTrue(pollDownloaded(cache), "indexed chapter should be reported downloaded")

        // SAF enumeration breaks on the next renewal.
        every { root.listFiles() } returns null
        cache.invalidateCache()
        awaitRenewal(cache)

        assertTrue(
            cache.isChapterDownloaded("Chapter", "kojiraen", mangaDirName, sourceId, skipCache = false),
            "previous index must survive a failed enumeration",
        )
    }

    @Test
    fun `unavailable source list keeps previous index`() {
        val cache = newCache()
        assertTrue(pollDownloaded(cache), "indexed chapter should be reported downloaded")

        every { sourceManager.getOnlineSources() } returns emptyList()
        every { sourceManager.getStubSources() } returns emptyList()
        cache.invalidateCache()
        awaitRenewal(cache)

        assertTrue(
            cache.isChapterDownloaded("Chapter", "kojiraen", mangaDirName, sourceId, skipCache = false),
            "previous index must survive an unavailable source list",
        )
    }

    @Test
    fun `empty persisted index does not suppress renewal on next launch`() {
        // First app "session": storage lists nothing, so a truthful empty index
        // is committed to disk.
        val emptyRoot = mockk<UniFile> {
            every { listFiles() } returns emptyArray()
            every { uri } returns mockk(relaxed = true)
        }
        every { storageManager.getDownloadsDirectory() } returns emptyRoot
        val firstSession = newCache()
        assertFalse(
            pollDownloaded(firstSession, timeoutMs = 10_000),
            "empty storage must not report chapters downloaded",
        )
        val diskFile = awaitDiskCacheFile()
        val emptyBytes = diskFile.readBytes()

        // Storage recovers; the persisted empty index must not keep the next
        // session suppressed for the full renewal interval.
        every { storageManager.getDownloadsDirectory() } returns root
        val secondSession = newCache()
        assertTrue(
            pollDownloaded(secondSession),
            "recovered storage must be re-indexed despite an empty persisted index",
        )
        awaitCondition(15_000, { "populated index was never persisted over the empty one" }) {
            diskFile.exists() && diskFile.length() > emptyBytes.size
        }
    }

    @Test
    fun `skipCache consults the live provider instead of the index`() {
        // Live provider finds the archive even though the in-memory index is empty.
        every { provider.findChapterDir("Chapter", "kojiraen", mangaDirName, source) } returns chapterArchive
        val cache = newCache()

        assertTrue(
            cache.isChapterDownloaded("Chapter", "kojiraen", mangaDirName, sourceId, skipCache = true),
            "skipCache must bypass the index via the provider lookup",
        )

        every { provider.findChapterDir("Chapter", "kojiraen", mangaDirName, source) } returns null
        assertFalse(
            cache.isChapterDownloaded("Chapter", "kojiraen", mangaDirName, sourceId, skipCache = true),
        )
    }
}
