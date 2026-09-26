package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.RollingAutoCoordinator
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationSession
import eu.kanade.translation.scheduling.TranslationStoreResolver
import eu.kanade.translation.workflow.ChapterTranslator
import eu.kanade.translation.workflow.TranslationManager
import eu.kanade.translation.workflow.TranslationSessionCoordinator
import eu.kanade.translation.workflow.TranslationSessionState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Production-path arbitration coverage. The fixture injects only the manager
 * runtime fields that these methods own because constructing TranslationManager
 * normally also boots Android/DI translation engines; every assertion below
 * calls the real manager update/reconcile/translate methods and real scheduler
 * ownership gates.
 */
class TranslationManagerAutoArbitrationTest {

    @Test
    fun `manager treats a paused chapter as durable but inactive`() = runBlocking<Unit> {
        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { source.id } returns 1L
        every { manga.id } returns 2L
        every { manga.source } returns 1L
        every { chapter.id } returns 10L
        val scheduler = TranslationScheduler(
            executor = mockk<eu.kanade.translation.pipeline.execution.TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            immediateStoreResolver = { null },
        )
        val queue = MutableStateFlow<List<Translation>>(emptyList())
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue
        every { translator.isRunning } returns false
        val paused = Translation(source, manga, chapter).also {
            it.status = Translation.State.PAUSED
        }
        queue.value = listOf(paused)
        val manager = uninitializedManager(scheduler, translator)

        try {
            manager.isTranslating() shouldBe false
            manager.isAnyBatchTranslationActive shouldBe false
            manager.isBatchTranslationActive(10L) shouldBe false
            Unit
        } finally {
            scheduler.close()
        }
    }

    /**
     *  Same-chapter auto is suppressed for the WHOLE chapter-batch lifetime,
     * not just at the
     * translateChapter shutdown instant. While the queue entry is active,
     * reader-window updates must NOT re-arm the coordinator, and
     * reconcileAutoWindow must not resurrect it. Re-arm succeeds again only
     * after the queue drains. This test enforces the batch-active gate on the
     * re-arm path.
     */
    @Test
    fun `manager suppresses same-chapter auto while the chapter batch is queued`() = runBlocking<Unit> {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf("p0" to eu.kanade.translation.model.PageTranslation(sourceFileName = "")),
        )
        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { source.id } returns 1L
        every { manga.id } returns 2L
        every { manga.source } returns 1L
        every { manga.title } returns "fixture"
        every { chapter.id } returns 10L
        every { chapter.name } returns "chapter"
        every { chapter.scanlator } returns null
        val session = TranslationSession("manager-arbitration", manga, chapter, source, store)
        val identity = AutoChapterIdentity(10L, "manager-arbitration")
        val executor = mockk<eu.kanade.translation.pipeline.execution.TranslationExecutor>(relaxed = true)
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { store },
            immediateStoreResolver = { store },
        )
        val queue = MutableStateFlow<List<Translation>>(emptyList())
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue
        every { translator.isRunning } returns false
        every { translator.start() } returns true
        val activeBatch = Translation(source, manga, chapter).also { it.status = Translation.State.QUEUE }
        every { translator.queueChapter(manga, chapter) } answers { queue.value = listOf(activeBatch) }
        val manager = uninitializedManager(scheduler, translator)

        mockkObject(TranslationForegroundService.Companion)
        every { TranslationForegroundService.start(any()) } just runs

        /** Negative oracle: does the auto window come back within the probe window? */
        suspend fun autoWindowReArmed(): Boolean = try {
            withTimeout(NULL_STATE_PROBE_MS) { scheduler.autoSnapshot.first { it != null } }
            true
        } catch (_: TimeoutCancellationException) {
            false
        }

        try {
            manager.updateAutoWindow(
                identity = identity,
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                pageCount = 1,
                session = session,
                pageResolver = { RollingAutoCoordinator.PageWorkItem("p0", null) },
                computeClass = eu.kanade.translation.engines.translator.TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it?.identity == identity } }

            // The reader window owns the session until reader teardown. End
            // that lifecycle explicitly before admitting the batch; the phase
            // 3 gate no longer lets a batch queue behind a live reader owner.
            manager.sessionCoordinator.finishSession(TranslationSessionState.READER_SESSION)

            // Queueing a batch retires the old coordinator...
            manager.translateChapter(manga, chapter)
            manager.reconcileAutoWindow()
            withTimeout(5_000) { scheduler.autoSnapshot.first { it == null } }

            // ...and the  contract keeps it retired for the batch lifetime:
            // same-chapter reader-window updates must NOT re-arm while the
            // queue entry is active.
            manager.updateAutoWindow(
                identity,
                0,
                0,
                1,
                session,
                { RollingAutoCoordinator.PageWorkItem("p0", null) },
                eu.kanade.translation.engines.translator.TranslatorComputeClass.REMOTE_IO,
            )
            val reArmedWhileQueued = autoWindowReArmed()
            withClue(
                "D4: same-chapter auto re-armed while the chapter batch was still queued " +
                    "(batch-lifetime suppression guard missing from the re-arm path)",
            ) {
                reArmedWhileQueued shouldBe false
            }

            // A reconcile signal must not resurrect the window either.
            manager.reconcileAutoWindow()
            val resurrectedByReconcile = autoWindowReArmed()
            withClue("D4: reconcileAutoWindow resurrected same-chapter auto while the batch was queued") {
                resurrectedByReconcile shouldBe false
            }

            // The gate is a batch-LIFETIME gate: once the queue drains, the
            // reader window arms again.
            queue.value = emptyList()
            manager.sessionCoordinator.finishSession(TranslationSessionState.BATCH_SESSION)
            manager.updateAutoWindow(
                identity,
                0,
                0,
                1,
                session,
                { RollingAutoCoordinator.PageWorkItem("p0", null) },
                eu.kanade.translation.engines.translator.TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it?.identity == identity } }
        } finally {
            unmockkObject(TranslationForegroundService.Companion)
            scheduler.close()
        }
    }

    private fun uninitializedManager(
        scheduler: TranslationScheduler,
        translator: ChapterTranslator,
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
        setField(manager, "pendingRequestStore", mockk<TranslationPendingRequestStore>(relaxed = true))
        setField(
            manager,
            "pendingTranslationRequestsState",
            MutableStateFlow<Map<Long, TranslationRequestState>>(emptyMap()),
        )
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

    private companion object {
        /**
         * Bound for the negative  oracle ("auto window must stay null"). The
         * probe returns early the instant a forbidden re-arm lands, so a failing
         * (RED) run adds no latency.
         */
        const val NULL_STATE_PROBE_MS = 2_000L
    }
}
