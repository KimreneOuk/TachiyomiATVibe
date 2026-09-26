package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.orchestration.TranslationManager
import eu.kanade.translation.persistence.queue.TranslationPendingRequestRecord
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TranslationManagerPendingAcknowledgementTest {

    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns 10L
    }

    @Test
    fun `starting acknowledgement publishes immediately without dropping other requests`() = runBlocking<Unit> {
        val committed = mutableListOf<Triple<Long, TranslationRequestPhase, String?>>()
        val laneJob = SupervisorJob()
        val existingRequest = TranslationRequestState(7L, TranslationRequestPhase.WAITING_FOR_DOWNLOAD)
        val manager = uninitializedManager(
            pendingRequestStore = recordingStore(committed),
            laneJob = laneJob,
            initialRequests = mapOf(7L to existingRequest),
        )

        try {
            manager.acknowledgeTranslationRequests(listOf(chapter))

            manager.pendingTranslationRequests.value[7L]?.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
            manager.pendingTranslationRequests.value[10L]?.phase shouldBe TranslationRequestPhase.STARTING
            manager.pendingTranslationRequests.value.keys shouldBe setOf(7L, 10L)
            drainPersistenceLane(laneJob)
        } finally {
            laneJob.cancel()
        }
    }

    @Test
    fun `acknowledgement publishes in-memory before the durable commit`() = runBlocking<Unit> {
        val committed = mutableListOf<Triple<Long, TranslationRequestPhase, String?>>()
        val laneJob = SupervisorJob()
        val manager = uninitializedManager(recordingStore(committed), laneJob)
        val mutationLock = readField(manager, "pendingRequestMutationLock") as Any

        try {
            synchronized(mutationLock) {
                // The persistence lane is queued on storeScope but must acquire
                // the same mutation lock; while it is held here the durable
                // commit provably cannot have run yet, so observing STARTING in
                // memory pins the publish-before-commit ordering.
                manager.acknowledgeTranslationRequests(listOf(chapter))
                manager.pendingTranslationRequests.value[10L]?.phase shouldBe
                    TranslationRequestPhase.STARTING
                committed shouldContainExactly emptyList()
            }
            drainPersistenceLane(laneJob)

            committed shouldContainExactly listOf(Triple(10L, TranslationRequestPhase.STARTING, null))
        } finally {
            laneJob.cancel()
        }
    }

    @Test
    fun `cancelling while the starting commit is in flight never resurrects the request`() =
        runBlocking<Unit> {
            val committed = mutableListOf<Triple<Long, TranslationRequestPhase, String?>>()
            val removals = mutableListOf<Long>()
            val laneJob = SupervisorJob()
            val manager = uninitializedManager(recordingStore(committed, removals), laneJob)
            val mutationLock = readField(manager, "pendingRequestMutationLock") as Any

            try {
                synchronized(mutationLock) {
                    // Ordered under the mutation lock: the queued STARTING
                    // commit runs only after the cancellation has advanced the
                    // write version and dropped the request.
                    manager.acknowledgeTranslationRequests(listOf(chapter))
                    manager.cancelTranslationRequest(10L)
                    manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
                }
                drainPersistenceLane(laneJob)

                committed shouldContainExactly emptyList()
                // The cancellation's own durable clear plus the commit's
                // keep-clear for the already-removed request.
                removals shouldContainExactly listOf(10L, 10L)
                manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
            } finally {
                laneJob.cancel()
            }
        }

    @Test
    fun `a newer phase while the starting commit is in flight fences the stale write`() =
        runBlocking<Unit> {
            val committed = mutableListOf<Triple<Long, TranslationRequestPhase, String?>>()
            val laneJob = SupervisorJob()
            val manager = uninitializedManager(recordingStore(committed), laneJob)
            val mutationLock = readField(manager, "pendingRequestMutationLock") as Any

            try {
                synchronized(mutationLock) {
                    // PREPARING advances the write version under the same lock
                    // the STARTING commit must pass through.
                    manager.acknowledgeTranslationRequests(listOf(chapter))
                    manager.markTranslationRequestPreparing(10L)
                    manager.pendingTranslationRequests.value[10L]?.phase shouldBe
                        TranslationRequestPhase.PREPARING
                }
                drainPersistenceLane(laneJob)

                // Only the synchronous PREPARING write reaches the store; the
                // stale STARTING commit is dropped by the version fence.
                committed shouldContainExactly
                    listOf(Triple(10L, TranslationRequestPhase.PREPARING, null))
                manager.pendingTranslationRequests.value[10L]?.phase shouldBe
                    TranslationRequestPhase.PREPARING
            } finally {
                laneJob.cancel()
            }
        }

    private fun recordingStore(
        committed: MutableList<Triple<Long, TranslationRequestPhase, String?>>,
        removals: MutableList<Long> = mutableListOf(),
    ): TranslationPendingRequestStore = mockk<TranslationPendingRequestStore>(relaxed = true).also {
        every { it.load() } returns emptySet()
        every { it.record(any()) } returns null
        every { it.generation(any()) } returns 0L
        every { it.add(any<TranslationPendingRequestRecord>()) } answers {
            val record = firstArg<TranslationPendingRequestRecord>()
            committed += Triple(record.chapterId, record.phase, record.reason)
        }
        every { it.remove(any()) } answers { removals += firstArg<Long>() }
    }

    /** Completes the manager's persistence lane and joins it, bounded. */
    private suspend fun drainPersistenceLane(laneJob: CompletableJob) {
        withTimeout(5_000) {
            laneJob.complete()
            laneJob.join()
        }
    }

    /** Mirrors TranslationManagerDownloadFailureRecoveryTest's uninitialized fixture. */
    private fun uninitializedManager(
        pendingRequestStore: TranslationPendingRequestStore,
        laneJob: CompletableJob = SupervisorJob(),
        initialRequests: Map<Long, TranslationRequestState> = emptyMap(),
    ): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "context", mockk<Context>(relaxed = true))
        setField(manager, "pendingRequestStore", pendingRequestStore)
        val pendingState = MutableStateFlow(initialRequests)
        setField(manager, "pendingTranslationRequestsState", pendingState)
        setField(manager, "pendingTranslationRequests", pendingState.asStateFlow())
        setField(manager, "pendingRequestWriteVersions", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "pendingRequestMutationLock", Any())
        setField(manager, "storeScope", CoroutineScope(laneJob + Dispatchers.IO))
        // Generation, download-attachment, and group state used by the request coordinator.
        setField(manager, "pendingRequestGenerationCounters", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "downloadAttachGenerations", ConcurrentHashMap<Long, Long>())
        setField(manager, "pendingGroupIdSequence", AtomicLong(0))
        return manager
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
