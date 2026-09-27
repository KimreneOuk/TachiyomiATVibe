package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.queue.TranslationPendingRequestRecord
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import eu.kanade.translation.workflow.ChapterTranslator
import eu.kanade.translation.workflow.TranslationManager
import eu.kanade.translation.workflow.TranslationSessionCoordinator
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The startup reconciler resolves every pending request
 * against its owners exactly once after both queues restore:
 * - pending + translation-queue member -> queue wins, clear pending;
 * - pending + download-queue member -> normalize WAITING;
 * - pending + valid downloaded files (no owner) -> admit translation once;
 * - pending + neither -> explicit FAILED (interrupted), never deleted;
 * - chapter/source missing -> purge stale ownership.
 * Idempotent (a second pass is a no-op) and race-guarded by the request
 * generation fence.
 */
class TranslationManagerStartupReconciliationTest {

    private val httpSource = mockk<HttpSource>(relaxed = true) { every { id } returns 1L }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { this@mockk.source } returns 1L
        every { title } returns "fixture"
    }

    private fun chapter(id: Long) = mockk<Chapter>(relaxed = true) {
        every { this@mockk.id } returns id
        every { name } returns "chapter $id"
        every { scanlator } returns null
    }

    private val preferences = InMemorySharedPreferences()

    private fun newManager(translator: ChapterTranslator): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "sessionCoordinator", TranslationSessionCoordinator())
        setField(
            manager,
            "scheduler",
            eu.kanade.translation.scheduling.TranslationScheduler(
                executor = mockk<eu.kanade.translation.pipeline.execution.TranslationExecutor>(relaxed = true),
                storeResolver = eu.kanade.translation.scheduling.TranslationStoreResolver { null },
                immediateStoreResolver = { null },
            ),
        )
        setField(manager, "context", mockk<Context>(relaxed = true))
        setField(manager, "translator", translator)
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
        setField(
            manager,
            "storeScope",
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        )
        setField(manager, "pendingRequestGenerationCounters", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "downloadAttachGenerations", ConcurrentHashMap<Long, Long>())
        setField(manager, "pendingGroupIdSequence", AtomicLong(0))
        return manager
    }

    /** Chapters admitted by the translator, recorded by the fake queue callback. */
    private val admittedChapters = mutableListOf<Chapter>()

    private fun translatorWithQueue(initial: List<Translation> = emptyList()): ChapterTranslator {
        admittedChapters.clear()
        val translator = mockk<ChapterTranslator>(relaxed = true)
        val queue = MutableStateFlow(initial)
        every { translator.queueState } returns queue
        every { translator.isRunning } returns false
        every { translator.isQueueConfigValid() } returns true
        every { translator.queueChapter(any(), any()) } answers {
            val chapter = secondArg<Chapter>()
            admittedChapters += chapter
            queue.value = queue.value + Translation(httpSource, manga, chapter)
        }
        return translator
    }

    private fun seedPending(vararg chapterIds: Long) {
        val store = TranslationPendingRequestStore(
            mockk { every { getSharedPreferences(any(), any()) } returns preferences },
        )
        chapterIds.forEach { id ->
            store.add(
                TranslationPendingRequestRecord(
                    chapterId = id,
                    phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                    generation = 1L,
                ),
            )
        }
    }

    private fun queuedTranslation(id: Long, status: Translation.State): Translation =
        Translation(httpSource, manga, chapter(id)).also { it.status = status }

    @Test
    fun `pending plus translation-queue member - queue wins and pending is cleared`() = runBlocking<Unit> {
        seedPending(10L)
        val manager = newManager(
            translatorWithQueue(listOf(queuedTranslation(10L, Translation.State.QUEUE))),
        )

        manager.reconcilePendingRequestsForStartup(
            downloadQueueChapterIds = emptySet(),
            resolveTranslation = { Translation(httpSource, manga, chapter(it)) },
            hasDownloadedFiles = { true },
        )

        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        store().record(10L).shouldBeNull()
    }

    @Test
    fun `pending plus download-queue member - normalized to WAITING`() = runBlocking<Unit> {
        seedPending(10L)
        val store = store()
        store.add(
            TranslationPendingRequestRecord(
                chapterId = 10L,
                phase = TranslationRequestPhase.PREPARING,
                generation = 3L,
            ),
        )
        val manager = newManager(translatorWithQueue())

        manager.reconcilePendingRequestsForStartup(
            downloadQueueChapterIds = setOf(10L),
            resolveTranslation = { Translation(httpSource, manga, chapter(it)) },
            hasDownloadedFiles = { false },
        )

        val record = store.record(10L)!!
        record.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        record.generation shouldBe 3L
        manager.pendingTranslationRequests.value[10L]?.phase shouldBe
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD
    }

    @Test
    fun `pending plus valid files with no owner - admitted exactly once without auto-start`() = runBlocking<Unit> {
        seedPending(10L)
        val translator = translatorWithQueue()
        val manager = newManager(translator)

        manager.reconcilePendingRequestsForStartup(
            downloadQueueChapterIds = emptySet(),
            resolveTranslation = { Translation(httpSource, manga, chapter(it)) },
            hasDownloadedFiles = { true },
        )

        // Admitted once: the request entered the translation queue and the
        // redundant pending intent was cleared.
        assertEquals(listOf(10L), admittedChapters.map { it.id })
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        store().record(10L).shouldBeNull()
        // Restored work never auto-resumes OCR/LLM.
        verify(exactly = 0) { translator.start() }
    }

    @Test
    fun `pending with neither queue nor files - explicit interrupted failure not deleted`() = runBlocking<Unit> {
        seedPending(10L)
        val manager = newManager(translatorWithQueue())

        manager.reconcilePendingRequestsForStartup(
            downloadQueueChapterIds = emptySet(),
            resolveTranslation = { Translation(httpSource, manga, chapter(it)) },
            hasDownloadedFiles = { false },
        )

        val record = store().record(10L).shouldNotBeNull()
        record.phase shouldBe TranslationRequestPhase.DOWNLOAD_FAILED
        record.failureKind shouldBe TranslationRequestFailureKind.INTERRUPTED
        manager.pendingTranslationRequests.value[10L]?.failureKind shouldBe
            TranslationRequestFailureKind.INTERRUPTED
    }

    @Test
    fun `missing chapter or source - stale ownership purged`() = runBlocking<Unit> {
        seedPending(10L)
        val manager = newManager(translatorWithQueue())

        manager.reconcilePendingRequestsForStartup(
            downloadQueueChapterIds = emptySet(),
            resolveTranslation = { null },
            hasDownloadedFiles = { false },
        )

        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        store().record(10L).shouldBeNull()
    }

    @Test
    fun `second pass is a no-op - idempotent`() = runBlocking<Unit> {
        seedPending(10L, 11L)
        val translator = translatorWithQueue()
        val manager = newManager(translator)
        suspend fun runPass() {
            manager.reconcilePendingRequestsForStartup(
                downloadQueueChapterIds = setOf(11L),
                resolveTranslation = { id ->
                    if (id == 10L) Translation(httpSource, manga, chapter(id)) else null
                },
                hasDownloadedFiles = { it.chapter.id == 10L },
            )
        }

        runPass()
        val afterFirstPass = manager.pendingTranslationRequests.value.toMap()
        runPass()
        val afterSecondPass = manager.pendingTranslationRequests.value.toMap()

        afterSecondPass shouldBe afterFirstPass
        // 10L: queue wins -> cleared; 11L: missing -> purged.
        afterSecondPass.containsKey(10L) shouldBe false
        afterSecondPass.containsKey(11L) shouldBe false
        assertEquals(listOf(10L), admittedChapters.map { it.id })
    }

    @Test
    fun `cancel landing during the pass cannot be overridden by the admission`() = runBlocking<Unit> {
        seedPending(10L)
        val translator = translatorWithQueue()
        val manager = newManager(translator)

        manager.reconcilePendingRequestsForStartup(
            downloadQueueChapterIds = emptySet(),
            resolveTranslation = { id ->
                // Simulate a user cancel landing between decision and mutation.
                manager.cancelTranslationRequest(id)
                Translation(httpSource, manga, chapter(id))
            },
            hasDownloadedFiles = { true },
        )

        // The generation fence sees the cancelled request and drops the admission.
        verify(exactly = 0) { translator.queueChapter(any(), any()) }
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        store().record(10L).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Restore-admitted requests must never auto-start work. The
    // reconciler marks the chapters it admits (process-local, one-shot) and
    // the gated admission path enqueues them PAUSED instead of starting.
    // -------------------------------------------------------------------------

    @Test
    fun `startup reconciler marks both restored admission branches`() = runBlocking<Unit> {
        seedPending(10L, 11L)
        val translator = translatorWithQueue()
        val manager = newManager(translator)

        manager.reconcilePendingRequestsForStartup(
            downloadQueueChapterIds = setOf(10L),
            resolveTranslation = { Translation(httpSource, manga, chapter(it)) },
            hasDownloadedFiles = { it.chapter.id == 11L },
        )

        val marks = (getField(manager, "restoreAdmittedChapterIds") as? Set<Long>)
            ?: emptySet<Long>()
        // 10L: restored WAITING request (download handoff later in-process);
        // 11L: admitted straight into the translation queue. Both one-shot.
        // Set equality — the ConcurrentHashMap key set has no stable order.
        marks shouldBe setOf(10L, 11L)
    }

    @Test
    fun `gated admission enqueues a restore-admitted chapter paused without auto start`() = runBlocking<Unit> {
        seedPending(10L)
        val translator = translatorWithQueue()
        val queue = MutableStateFlow<List<Translation>>(emptyList())
        every { translator.queueState } returns queue
        // Mirror the real queueChapter admission: a QUEUE-status entry.
        every { translator.queueChapter(any(), any(), any(), any()) } answers {
            if (queue.value.none { it.chapter.id == secondArg<Chapter>().id }) {
                queue.value = queue.value +
                    Translation(httpSource, manga, secondArg<Chapter>())
                        .also { it.status = Translation.State.QUEUE }
            }
        }
        val manager = newManager(translator)

        manager.translateChapter(manga, chapter(10L), autoStart = false)

        val entry = queue.value.single { it.chapter.id == 10L }
        // Paused, not a fake-active spinner; no OCR/LLM without a user action.
        entry.status shouldBe Translation.State.PAUSED
        verify(exactly = 0) { translator.start() }
    }

    @Test
    fun `explicit translate re-arms a PAUSED queue entry and starts`() = runBlocking<Unit> {
        val translator = translatorWithQueue(listOf(queuedTranslation(10L, Translation.State.PAUSED)))
        // The chapter is already a queue member: queueChapter is a no-op.
        every { translator.queueChapter(any(), any(), any(), any()) } answers { }
        val manager = newManager(translator)

        mockkObject(TranslationForegroundService.Companion)
        every { TranslationForegroundService.start(any()) } just runs
        try {
            manager.translateChapter(manga, chapter(10L))
        } finally {
            unmockkObject(TranslationForegroundService.Companion)
        }

        val entry = manager.queueState.value.single { it.chapter.id == 10L }
        entry.status shouldBe Translation.State.QUEUE
        verify(exactly = 1) { translator.start() }
    }

    @Test
    fun `explicit translate re-arms an ERROR queue entry and starts`() = runBlocking<Unit> {
        val translator = translatorWithQueue(listOf(queuedTranslation(10L, Translation.State.ERROR)))
        every { translator.queueChapter(any(), any(), any(), any()) } answers { }
        val manager = newManager(translator)

        mockkObject(TranslationForegroundService.Companion)
        every { TranslationForegroundService.start(any()) } just runs
        try {
            manager.translateChapter(manga, chapter(10L))
        } finally {
            unmockkObject(TranslationForegroundService.Companion)
        }

        val entry = manager.queueState.value.single { it.chapter.id == 10L }
        entry.status shouldBe Translation.State.QUEUE
        verify(exactly = 1) { translator.start() }
    }

    private fun getField(target: Any, fieldName: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
    }

    private fun store(): TranslationPendingRequestStore =
        TranslationPendingRequestStore(
            mockk { every { getSharedPreferences(any(), any()) } returns preferences },
        )

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
