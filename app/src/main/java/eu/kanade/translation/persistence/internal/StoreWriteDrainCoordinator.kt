package eu.kanade.translation.persistence.internal

import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Drains accepted lazy image writes into the journal-backed store.
 *
 * D1-A requires each accepted image write to be handed to the journal writer
 * before eviction can discard the store. The coordinator owns the queued work,
 * debounce timing, and drain execution; the store owns the journal-capture
 * interleave and acquires its capture permit before the store mutex. Defunct
 * ordering is: bounded 2-second join, mark the generation defunct, release
 * retained pending lazy-write credits and complete queued task handles under
 * the store mutex, then request the DEFUNCT marker. This prevents accepted
 * work from being stranded or credited after the terminal frame.
 */
internal class StoreWriteDrainCoordinator(
    private val store: ChapterTranslationStore,
    dispatcher: CoroutineDispatcher,
) {
    private val drainScopeJob = SupervisorJob()
    private val drainScope = CoroutineScope(drainScopeJob + dispatcher)

    internal companion object {
        internal const val PERSIST_JOIN_TIMEOUT_MS = 2_000L
        internal const val PERSIST_DEBOUNCE_MS = 250L
    }

    private val mutex get() = store.mutex
    private val flushMutex = Mutex()

    // Mutated only while holding store.mutex. The delayed worker re-enters the
    // same mutex before touching either collection or the owning store.
    internal var drainJob: Job? = null
    internal val pendingLazyMutations = LinkedHashMap<String, ChapterTranslationStore.PendingLazyMutation>()
    internal val pendingLazyTasks = java.util.ArrayDeque<ChapterTranslationStore.LazyPersistenceTask>()

    /**
     * Runs lazy image writes first, then transfers their accepted page state
     * under the journal capture permit and store mutex. A generation change
     * rejects the image write before it touches storage.
     */
    suspend fun flush() = flushMutex.withLock {
        while (true) {
            val task = mutex.withLock { store.takeLazyPersistenceTaskLocked() }
            if (task == null) break
            try {
                val generationIsCurrent = mutex.withLock {
                    store.isLazyGenerationCurrent(task.generation)
                }
                if (generationIsCurrent) {
                    val completed = try {
                        task.work()
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        logcat(LogPriority.ERROR, failure) {
                            "TachiyomiAT lazy persistence task failed: generation=${task.generation}"
                        }
                        false
                    }
                    task.result.complete(completed)
                } else {
                    task.result.complete(false)
                }
            } catch (cancelled: CancellationException) {
                task.result.complete(false)
                throw cancelled
            } catch (failure: Throwable) {
                logcat(LogPriority.ERROR, failure) {
                    "TachiyomiAT lazy persistence task failed: generation=${task.generation}"
                }
                task.result.complete(false)
            }
        }
        store.flushPendingLazyMutationsWithJournalCapture()
    }

    /** Flushes accepted work and joins every child before returning. */
    suspend fun closeAndFlush(afterFlush: suspend () -> Unit = {}) {
        try {
            flush()
            withContext(NonCancellable) { afterFlush() }
        } finally {
            withContext(NonCancellable) {
                drainScopeJob.cancelAndJoin()
            }
        }
    }

    /**
     * Joins the current debounced drain for at most two seconds, then performs
     * the eviction handoff under store.mutex. Callbacks mutate the store before
     * and after pending credits are released, preserving DEFUNCT frame order.
     */
    suspend fun markDefunct(
        markGenerationDefunctLocked: () -> Unit,
        requestDefunctMarkerLocked: () -> Unit,
    ) = withContext(NonCancellable) {
        val pendingDrain = mutex.withLock { drainJob }
        pendingDrain?.let { pending ->
            if (withTimeoutOrNull(PERSIST_JOIN_TIMEOUT_MS) { pending.join() } == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT store drain did not finish within ${PERSIST_JOIN_TIMEOUT_MS} ms before eviction"
                }
            }
            pending.cancel()
        }
        mutex.withLock {
            if (drainJob === pendingDrain) drainJob = null
            markGenerationDefunctLocked()
            releaseRetainedCreditsLocked()
            requestDefunctMarkerLocked()
        }
    }

    /** Starts a fire-and-forget close boundary without waiting for children. */
    fun close(afterFlush: suspend () -> Unit = {}) {
        drainScope.launch {
            flush()
            withContext(NonCancellable) { afterFlush() }
        }.invokeOnCompletion {
            drainScope.cancel()
        }
    }

    internal fun hasActiveChildren(): Boolean = drainScopeJob.children.any { it.isActive }

    /** Caller holds store.mutex; this handoff never acquires another state lock. */
    internal fun scheduleDrain() {
        if (store.isDefunct) return
        if (!store.hasArtifactPersistenceTarget()) return
        if (drainJob?.isActive == true) return
        val scheduledJob = drainScope.launch {
            val runningJob = currentCoroutineContext()[Job]
            try {
                delay(PERSIST_DEBOUNCE_MS)
                flush()
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (drainJob === runningJob) {
                            drainJob = null
                            if (store.hasPendingLazyPersistenceLocked()) {
                                scheduleDrain()
                            }
                        }
                    }
                }
            }
        }
        drainJob = scheduledJob
    }

    /** Caller holds store.mutex after defunct/generation transition. */
    internal fun releaseRetainedCreditsLocked() {
        pendingLazyMutations.values.forEach { it.journalCredit?.releaseIfRetained() }
        pendingLazyMutations.clear()
        pendingLazyTasks.forEach { it.result.complete(false) }
        pendingLazyTasks.clear()
    }
}
