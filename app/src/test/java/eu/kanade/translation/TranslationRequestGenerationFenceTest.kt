package eu.kanade.translation

import eu.kanade.translation.orchestration.*

import eu.kanade.translation.storage.*

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.orchestration.TranslationSessionCoordinator
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 *  slice 2 (R7): request generations fence downloader completion callbacks
 * and in-flight probe mutations. A stale callback — cancelled, re-requested,
 * or cleared request — is dropped with a log line; it never admits work and
 * never recreates a request. The fixture mirrors
 * TranslationManagerDownloadFailureRecoveryTest's uninitialized manager with a
 * real in-memory pending-request store.
 */
class TranslationRequestGenerationFenceTest {

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

    private val preferences = InMemorySharedPreferences()
    private val laneJob = SupervisorJob()
    private val scope = CoroutineScope(laneJob + Dispatchers.IO)
    private val admitted = AtomicBoolean(false)
    private val queueStateFlow = MutableStateFlow(emptyList<Translation>())

    private lateinit var manager: TranslationManager
    private lateinit var store: TranslationPendingRequestStore

    @BeforeEach
    fun setUp() {
        preferences.entries.clear()
        store = TranslationPendingRequestStore(mockContext())
        admitted.set(false)
        queueStateFlow.value = emptyList()
        val scheduler = TranslationScheduler(
            executor = mockk<TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            immediateStoreResolver = { null },
        )
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queueStateFlow
        every { translator.isRunning } returns false
        every { translator.isQueueConfigValid() } returns true
        // Simulate a successful admission: queueChapter inserts the QUEUE entry
        // so the manager's membership check clears the pending request.
        every { translator.queueChapter(any(), any()) } answers {
            admitted.set(true)
            queueStateFlow.value =
                queueStateFlow.value + Translation(source, manga, secondArg<Chapter>())
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(any()) } returns source
        manager = uninitializedManager(store, scheduler, translator, sourceManager)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `cancel-then-late-completion-callback does not admit or recreate the request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!
        manager.queueTranslationAfterDownload(manga, chapter)
        manager.pendingTranslationRequests.value[10L]?.phase shouldBe
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD

        manager.cancelTranslationRequest(10L)
        manager.startTranslationAfterDownloadIfRequested(manga, chapter)

        admitted.get() shouldBe false
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        // Drain the async STARTING-ack persistence lane before asserting
        // durable state: its defensive re-remove after the cancel runs
        // `store.remove` a second time, so the tombstone value legitimately
        // depends on whether that landed yet.
        withTimeout(5_000) {
            laneJob.complete()
            laneJob.join()
        }
        store.record(10L).shouldBeNull()
        // The cancelled generation must never be reusable by a later request.
        store.generation(10L) shouldBeGreaterThan generation
    }

    @Test
    fun `re-requested generation fences the previous request's late callback`() = runBlocking<Unit> {
        // Request 1: acknowledged and attached to the download.
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val firstGeneration = manager.pendingRequestGeneration(10L)!!
        manager.queueTranslationAfterDownload(manga, chapter)

        // Request 2: the user cancels and re-requests before the download completes.
        manager.cancelTranslationRequest(10L)
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val secondGeneration = manager.pendingRequestGeneration(10L)!!
        secondGeneration shouldNotBe firstGeneration

        // The late completion callback still carries the FIRST attach generation.
        manager.startTranslationAfterDownloadIfRequested(manga, chapter)

        admitted.get() shouldBe false
        // The new request is untouched (still STARTING) — never recreated or advanced.
        manager.pendingTranslationRequests.value[10L]?.phase shouldBe
            TranslationRequestPhase.STARTING
        manager.pendingTranslationRequests.value[10L]?.generation shouldBe secondGeneration
    }

    @Test
    fun `current generation callback admits exactly once and clears the request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        manager.queueTranslationAfterDownload(manga, chapter)
        val generation = manager.pendingRequestGeneration(10L)!!
        manager.isTranslationRequestCurrent(10L, generation) shouldBe true

        manager.startTranslationAfterDownloadIfRequested(manga, chapter)

        admitted.get() shouldBe true
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
    }

    @Test
    fun `fenced WAITING write after a cancel is dropped and writes nothing`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!
        manager.cancelTranslationRequest(10L)

        val queued = manager.queueTranslationAfterDownloadIfCurrent(manga, chapter, generation)

        queued shouldBe false
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        store.record(10L).shouldBeNull()
    }

    @Test
    fun `fenced PREPARING write after a cancel is dropped`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!
        manager.cancelTranslationRequest(10L)

        val marked = manager.markTranslationRequestPreparingIfCurrent(10L, generation)

        marked shouldBe false
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        store.record(10L).shouldBeNull()
    }

    @Test
    fun `cancel racing a fenced WAITING write never resurrects the request`() = runBlocking<Unit> {
        repeat(64) {
            manager.acknowledgeTranslationRequests(listOf(chapter))
            val generation = manager.pendingRequestGeneration(10L)!!

            val barrier = CyclicBarrier(2)
            val queued = AtomicBoolean(false)
            val cancelThread = Thread {
                barrier.await()
                manager.cancelTranslationRequest(10L)
            }
            val probeThread = Thread {
                barrier.await()
                queued.set(
                    manager.queueTranslationAfterDownloadIfCurrent(manga, chapter, generation),
                )
            }
            cancelThread.start()
            probeThread.start()
            cancelThread.join(5_000)
            probeThread.join(5_000)

            // Either serialization order is legal; the invariant is that the
            // cancel always wins in the end: the request never survives.
            manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
            store.record(10L).shouldBeNull()
        }
        // The async STARTING commits are all version-fenced; drain the lane.
        withTimeout(5_000) {
            laneJob.complete()
            laneJob.join()
        }
    }

    @Test
    fun `cancel serialized with the completion callback wins - no resurrect or admission`() =
        runBlocking<Unit> {
            manager.acknowledgeTranslationRequests(listOf(chapter))
            manager.queueTranslationAfterDownload(manga, chapter)
            val mutationLock = readField(manager, "pendingRequestMutationLock") as Any

            // Deterministic interleave of the old check/write gap: the
            // callback blocks acquiring the mutation lock while the cancel
            // runs inside it. The atomic fence (check + PREPARING write under
            // the same lock) must then see the removed request and drop the
            // callback entirely.
            val callback = Thread {
                runBlocking { manager.startTranslationAfterDownloadIfRequested(manga, chapter) }
            }
            synchronized(mutationLock) {
                callback.start()
                // Let the callback block on the lock before cancelling.
                val deadline = System.currentTimeMillis() + 5_000
                while (callback.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
                    Thread.yield()
                }
                manager.cancelTranslationRequest(10L)
            }
            withTimeout(5_000) { callback.join(5_000) }

            admitted.get() shouldBe false
            manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
            store.record(10L).shouldBeNull()
        }

    @Test
    fun `downloader callback for a chapter without any request is a no-op`() = runBlocking<Unit> {
        manager.startTranslationAfterDownloadIfRequested(manga, chapter)

        admitted.get() shouldBe false
        manager.pendingTranslationRequests.value.isEmpty() shouldBe true
        store.load() shouldContainExactly emptySet()
    }

    private fun mockContext(): Context = mockk {
        every { getSharedPreferences(any(), any()) } returns preferences
    }

    /** Mirrors TranslationManagerDownloadFailureRecoveryTest's uninitialized fixture. */
    private fun uninitializedManager(
        pendingRequestStore: TranslationPendingRequestStore,
        scheduler: TranslationScheduler,
        translator: ChapterTranslator,
        sourceManager: SourceManager,
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
        setField(manager, "sourceManager", sourceManager)
        setField(manager, "pendingRequestStore", pendingRequestStore)
        val pendingState = MutableStateFlow<Map<Long, TranslationRequestState>>(emptyMap())
        setField(manager, "pendingTranslationRequestsState", pendingState)
        setField(manager, "pendingTranslationRequests", pendingState.asStateFlow())
        setField(manager, "pendingRequestWriteVersions", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "pendingRequestMutationLock", Any())
        setField(manager, "storeScope", scope)
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

    private fun readField(target: Any, fieldName: String): Any {
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
}
