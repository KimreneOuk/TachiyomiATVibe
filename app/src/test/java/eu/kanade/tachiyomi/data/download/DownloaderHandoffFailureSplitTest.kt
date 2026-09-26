package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.translation.InMemorySharedPreferences
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.orchestration.TranslationManager
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 *  slice 3 (R8 / contract item 3): the downloader separates download
 * finalization from translation rekey/handoff. A failure AFTER the files
 * finalize must leave the download `DOWNLOADED` and give the translation
 * intent the real typed failure — while a finalize-stage failure keeps the
 * existing download-failure semantics exactly.
 */
class DownloaderHandoffFailureSplitTest {

    private val chapterId = 10L
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns chapterId
        every { name } returns "Chapter 1"
        every { scanlator } returns null
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { title } returns "fixture"
    }
    private val source = mockk<HttpSource>(relaxed = true)

    private fun newDownload(): Download = Download(source, manga, chapter).apply {
        status = Download.State.DOWNLOADED
    }

    private fun downloader(translationManager: TranslationManager): Downloader {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val downloader = allocateInstance.invoke(unsafe, Downloader::class.java) as Downloader
        setField(downloader, "translationManager", translationManager)
        setField(downloader, "provider", mockk<DownloadProvider>(relaxed = true))
        setField(downloader, "context", mockk<Context>(relaxed = true))
        setField(downloader, "notifier\$delegate", lazy { mockk<DownloadNotifier>(relaxed = true) })
        return downloader
    }

    private fun mockManager(): TranslationManager = mockk(relaxed = true)

    private fun setField(target: Any, fieldName: String, value: Any) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
    }

    // ------------------------------------------------------------- boundary --

    @Test
    fun `rekey failure after finalization keeps the download DOWNLOADED and records the typed failure`() = runBlocking<Unit> {
        val manager = mockManager()
        coEvery {
            manager.rekeyTranslationForCompletedDownload(any(), any(), any(), any(), any())
        } throws IOException("rekey boom")
        val downloader = downloader(manager)
        val download = newDownload()

        downloader.handOffAfterFinalization(
            download = download,
            pageList = listOf(Page(0, "u0", "iu0")),
            onDiskKeys = listOf("000.jpg"),
        )

        // The download's terminal status is settled — a post-finalization
        // failure must not flip it back to ERROR.
        download.status shouldBe Download.State.DOWNLOADED
        verify {
            manager.markTranslationHandoffFailed(
                chapterId,
                match { reason -> reason.contains("rekey boom") },
            )
        }
        coVerify(exactly = 0) { manager.startTranslationAfterDownloadIfRequested(any(), any()) }
    }

    @Test
    fun `handoff failure after finalization keeps the download DOWNLOADED and records the typed failure`() = runBlocking<Unit> {
        val manager = mockManager()
        coEvery {
            manager.startTranslationAfterDownloadIfRequested(any(), any())
        } throws RuntimeException("handoff boom")
        val downloader = downloader(manager)
        val download = newDownload()

        downloader.handOffAfterFinalization(
            download = download,
            pageList = listOf(Page(0, "u0", "iu0")),
            onDiskKeys = listOf("000.jpg"),
        )

        download.status shouldBe Download.State.DOWNLOADED
        // The rekey ran; only the handoff failed.
        coVerify(exactly = 1) { manager.rekeyTranslationForCompletedDownload(any(), any(), any(), any(), any()) }
        verify {
            manager.markTranslationHandoffFailed(
                chapterId,
                match { reason -> reason.contains("handoff boom") },
            )
        }
    }

    @Test
    fun `happy path rekeys then hands off with the download DOWNLOADED and no failure`() = runBlocking<Unit> {
        val manager = mockManager()
        val downloader = downloader(manager)
        val download = newDownload()

        downloader.handOffAfterFinalization(
            download = download,
            pageList = listOf(Page(0, "u0", "iu0")),
            onDiskKeys = listOf("000.jpg"),
        )

        download.status shouldBe Download.State.DOWNLOADED
        coVerify(atLeast = 1) { manager.pendingRequestGeneration(chapterId) }
        coVerifyOrder {
            manager.rekeyTranslationForCompletedDownload(any(), any(), any(), any(), any())
            manager.startTranslationAfterDownloadIfRequested(any(), any())
        }
        verify(exactly = 0) { manager.markTranslationHandoffFailed(any(), any()) }
    }

    @Test
    fun `cancellation still propagates from the post-finalization boundary`() = runBlocking<Unit> {
        val manager = mockManager()
        coEvery {
            manager.rekeyTranslationForCompletedDownload(any(), any(), any(), any(), any())
        } throws CancellationException("downloader job cancelled")
        val downloader = downloader(manager)
        val download = newDownload()

        shouldThrow<CancellationException> {
            downloader.handOffAfterFinalization(
                download = download,
                pageList = listOf(Page(0, "u0", "iu0")),
                onDiskKeys = listOf("000.jpg"),
            )
        }

        download.status shouldBe Download.State.DOWNLOADED
        verify(exactly = 0) { manager.markTranslationHandoffFailed(any(), any()) }
    }

    @Test
    fun `handoff is skipped entirely when no on-disk keys exist`() = runBlocking<Unit> {
        val manager = mockManager()
        val downloader = downloader(manager)
        val download = newDownload()

        downloader.handOffAfterFinalization(
            download = download,
            pageList = emptyList(),
            onDiskKeys = emptyList(),
        )

        download.status shouldBe Download.State.DOWNLOADED
        coVerify(exactly = 0) { manager.rekeyTranslationForCompletedDownload(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { manager.startTranslationAfterDownloadIfRequested(any(), any()) }
        verify(exactly = 0) { manager.markTranslationHandoffFailed(any(), any()) }
    }

    // ----------------------- finalize-stage boundary (behavior unchanged) ----

    @Test
    fun `finalize-stage validation failure keeps the existing download failure semantics`() = runBlocking<Unit> {
        val manager = mockManager()
        val downloader = downloader(manager)
        mockkObject(DiskUtil) {
            every { DiskUtil.getAvailableStorageSpace(any<UniFile>()) } returns -1L

            val provider = downloader.providerFieldValue()
            val mangaDir = mockk<UniFile>(relaxed = true)
            val tmpDir = mockk<UniFile>(relaxed = true) {
                every { listFiles() } returns null
            }
            every { provider.getMangaDir(any<String>(), any()) } returns mangaDir
            every { provider.getChapterDirName(any<String>(), any()) } returns "Chapter 1"
            every { mangaDir.createDirectory("Chapter 1_tmp") } returns tmpDir

            val download = Download(source, manga, chapter).apply {
                // One declared page whose image can never complete: page-list
                // validation fails after the (no-op) download loop.
                pages = listOf(Page(index = 0))
            }

            downloader.downloadChapter(download)

            download.status shouldBe Download.State.ERROR
            verify { manager.markTranslationDownloadFailed(chapterId, "Chapter download failed") }
            coVerify(exactly = 0) { manager.rekeyTranslationForCompletedDownload(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { manager.startTranslationAfterDownloadIfRequested(any(), any()) }
        }
    }

    private fun readField(target: Any, name: String): Any {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun Downloader.providerFieldValue(): DownloadProvider =
        readField(this, "provider") as DownloadProvider

    // ------------------------------------------- typed failure on the intent --

    /**
     * The typed translation-intent failure uses the R10 admission-failure
     * typing (phase ADMISSION_FAILED, kind QUEUE_ADMISSION_FAILED) — the files
     * are fine, so it is never a download failure. Exercised on a real
     * in-memory manager like the slice 2 notification tests.
     */
    @Test
    fun `markTranslationHandoffFailed writes ADMISSION_FAILED with QUEUE_ADMISSION_FAILED durably`() {
        val preferences = InMemorySharedPreferences()
        val manager = uninitializedManager(preferences)
        manager.queueTranslationAfterDownload(manga, chapter)
        manager.pendingTranslationRequests.value.getValue(chapterId).phase shouldBe
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD

        manager.markTranslationHandoffFailed(chapterId, "Translation could not start after the chapter download: rekey boom")

        val state = manager.pendingTranslationRequests.value.getValue(chapterId)
        state.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
        state.failureKind shouldBe TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED
        state.reason!!.contains("rekey boom") shouldBe true
        TranslationPendingRequestStore(mockk { every { getSharedPreferences(any(), any()) } returns preferences })
            .record(chapterId)?.failureKind shouldBe TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED
    }

    @Test
    fun `markTranslationHandoffFailed is a no-op without a pending request`() {
        val manager = uninitializedManager(InMemorySharedPreferences())

        manager.markTranslationHandoffFailed(chapterId, "boom")

        manager.pendingTranslationRequests.value.isEmpty() shouldBe true
    }

    private fun uninitializedManager(preferences: InMemorySharedPreferences): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "context", mockk<Context>(relaxed = true))
        setField(
            manager,
            "pendingRequestStore",
            TranslationPendingRequestStore(
                mockk { every { getSharedPreferences(any(), any()) } returns preferences },
            ),
        )
        val pendingState = MutableStateFlow<Map<Long, TranslationRequestState>>(emptyMap())
        setField(manager, "pendingTranslationRequestsState", pendingState)
        setField(manager, "pendingTranslationRequests", pendingState.asStateFlow())
        setField(manager, "pendingRequestWriteVersions", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "pendingRequestMutationLock", Any())
        setField(manager, "storeScope", CoroutineScope(Dispatchers.IO))
        setField(manager, "pendingRequestGenerationCounters", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "downloadAttachGenerations", ConcurrentHashMap<Long, Long>())
        setField(manager, "pendingGroupIdSequence", AtomicLong(0))
        return manager
    }
}
