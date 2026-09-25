package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.orchestration.ChapterTranslator
import eu.kanade.translation.orchestration.TranslationManager
import eu.kanade.translation.orchestration.TranslationSessionCoordinator
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import eu.kanade.translation.storage.TranslationPendingRequestRecord
import eu.kanade.translation.storage.TranslationPendingRequestStore
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 *  DOWNLOAD_FAILED pending requests must be self-clearing. The fixture
 * injects only the manager runtime fields these methods own because
 * constructing TranslationManager normally also boots Android/DI translation
 * engines; every assertion calls the real manager mutation methods and the
 * real pending-request store seam (relaxed) used by production.
 */
class TranslationManagerDownloadFailureRecoveryTest {

    private val source = mockk<HttpSource>(relaxed = true) {
        every { id } returns 1L
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { this@mockk.source } returns 1L
        every { title } returns "fixture"
    }
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns 10L
        every { name } returns "chapter"
        every { scanlator } returns null
    }

    private fun managerWithRequest(
        phase: TranslationRequestPhase,
        reason: String? = null,
    ): Pair<TranslationManager, TranslationPendingRequestStore> {
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        val manager = uninitializedManager(
            store,
            seed = mapOf(10L to TranslationRequestState(10L, phase, reason)),
        )
        return manager to store
    }

    @Test
    fun `requeue after download failure advances the phase out of DOWNLOAD_FAILED`() {
        val (manager, store) = managerWithRequest(TranslationRequestPhase.STARTING)

        manager.markTranslationDownloadFailed(10L, "Chapter download failed")
        manager.pendingTranslationRequests.value[10L]?.phase shouldBe TranslationRequestPhase.DOWNLOAD_FAILED

        manager.queueTranslationAfterDownload(manga, chapter)

        val state = manager.pendingTranslationRequests.value[10L]
        state?.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        state?.reason shouldBe null
        // The durable phase must advance too, or a restart re-projects failure.
        verify {
            store.add(
                match<TranslationPendingRequestRecord> { record ->
                    record.chapterId == 10L &&
                        record.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD &&
                        record.reason == null
                },
            )
        }
    }

    @Test
    fun `manual or auto entry clears a stale DOWNLOAD_FAILED request`() {
        val (manager, store) = managerWithRequest(
            TranslationRequestPhase.DOWNLOAD_FAILED,
            "Chapter download failed",
        )

        manager.clearStaleDownloadFailedRequest(10L)

        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        verify { store.remove(10L) }
    }

    @Test
    fun `manual or auto entry leaves a live pending request untouched`() {
        val (manager, store) = managerWithRequest(TranslationRequestPhase.WAITING_FOR_DOWNLOAD)

        manager.clearStaleDownloadFailedRequest(10L)

        manager.pendingTranslationRequests.value[10L]?.phase shouldBe
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        verify(exactly = 0) { store.remove(10L) }
    }

    @Test
    fun `failed phase persisted across restart is cleared at entry via the durable store`() {
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        // Default fixture: no in-memory request, as after a process restart
        // where only the durable store still projects DOWNLOAD_FAILED.
        val manager = uninitializedManager(store)
        every { store.phase(10L) } returns TranslationRequestPhase.DOWNLOAD_FAILED

        manager.clearStaleDownloadFailedRequest(10L)

        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        verify { store.remove(10L) }
    }

    @Test
    fun `completed download advances a failed request into translation`() = runBlocking<Unit> {
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        val scheduler = TranslationScheduler(
            executor = mockk<TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            immediateStoreResolver = { null },
        )
        val queue = MutableStateFlow<List<Translation>>(emptyList())
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue
        every { translator.isRunning } returns false
        val queued = Translation(source, manga, chapter).also { it.status = Translation.State.QUEUE }
        every { translator.queueChapter(manga, chapter) } answers { queue.value = listOf(queued) }
        val manager = uninitializedManager(
            store,
            scheduler,
            translator,
            seed = mapOf(
                10L to TranslationRequestState(
                    10L,
                    TranslationRequestPhase.DOWNLOAD_FAILED,
                    "Chapter download failed",
                ),
            ),
        )

        mockkObject(TranslationForegroundService.Companion)
        every { TranslationForegroundService.start(any()) } just runs
        try {
            //  slice 2: the completion callback is generation-fenced, so the
            // recovery first re-attaches the request to the download (the same
            // WAITING write the retry path performs).
            manager.queueTranslationAfterDownload(manga, chapter)
            manager.startTranslationAfterDownloadIfRequested(manga, chapter)

            verify { translator.queueChapter(manga, chapter) }
            // Admission into the translation queue clears the pending request,
            // so the failed projection cannot outlive the recovery.
            manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
            verify { store.remove(10L) }
        } finally {
            unmockkObject(TranslationForegroundService.Companion)
            scheduler.close()
        }
    }

    @Test
    fun `a completion callback without an attached generation is dropped`() = runBlocking<Unit> {
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        val translator = mockk<ChapterTranslator>(relaxed = true).also {
            every { it.queueState } returns MutableStateFlow(emptyList())
            every { it.isRunning } returns false
        }
        val manager = uninitializedManager(
            store,
            translator = translator,
            seed = mapOf(10L to TranslationRequestState(10L, TranslationRequestPhase.WAITING_FOR_DOWNLOAD)),
        )

        manager.startTranslationAfterDownloadIfRequested(manga, chapter)

        // The request was never attached to a download (no captured
        // generation), so the late callback must not admit or mutate it.
        verify(exactly = 0) { translator.queueChapter(any(), any()) }
        manager.pendingTranslationRequests.value[10L]?.phase shouldBe
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD
    }

    /** Mirrors TranslationManagerAutoArbitrationTest's uninitialized fixture. */
    private fun uninitializedManager(
        pendingRequestStore: TranslationPendingRequestStore,
        scheduler: TranslationScheduler = TranslationScheduler(
            executor = mockk<TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            immediateStoreResolver = { null },
        ),
        translator: ChapterTranslator = mockk<ChapterTranslator>(relaxed = true).also {
            every { it.queueState } returns MutableStateFlow(emptyList())
            every { it.isRunning } returns false
        },
        seed: Map<Long, TranslationRequestState> = emptyMap(),
    ): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "sessionCoordinator", TranslationSessionCoordinator())
        setField(manager, "scheduler", scheduler)
        setField(manager, "translator", translator)
        setField(manager, "context", mockk<Context>(relaxed = true))
        setField(manager, "pendingRequestStore", pendingRequestStore)
        // Unsafe allocation skips property initializers, so the public
        // projection must be wired to the same flow the manager mutates.
        val pendingState = MutableStateFlow(seed)
        setField(manager, "pendingTranslationRequestsState", pendingState)
        setField(manager, "pendingTranslationRequests", pendingState.asStateFlow())
        setField(manager, "pendingRequestWriteVersions", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "pendingRequestMutationLock", Any())
        //  slice 2: generation/attach/group state the coordinator resolves.
        setField(manager, "pendingRequestGenerationCounters", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "downloadAttachGenerations", ConcurrentHashMap<Long, Long>())
        setField(manager, "pendingGroupIdSequence", AtomicLong(0))
        return manager
    }

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
}
