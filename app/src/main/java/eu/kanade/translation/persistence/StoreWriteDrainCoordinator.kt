package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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

/** Serializes background image writes and coalesced lazy page snapshots. */
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

    // Queue state is mutated under the store mutex. Every flush shares one
    // mutex, so an explicit flush and the debounced worker cannot overlap.
    internal var drainJob: Job? = null
    internal val pendingLazyMutations = LinkedHashMap<String, PendingLazyMutation>()
    private val pendingLazyTasks = java.util.ArrayDeque<LazyPersistenceTask>()

    suspend fun flush() = flushMutex.withLock {
        while (true) {
            val task = mutex.withLock { pendingLazyTasks.pollFirst() } ?: break
            try {
                val generationIsCurrent = mutex.withLock {
                    store.isLazyGenerationCurrent(task.generation)
                }
                val persisted = if (generationIsCurrent) task.work() else false
                task.completion.complete(persisted)
            } catch (cancelled: CancellationException) {
                task.completion.complete(false)
                throw cancelled
            } catch (failure: Throwable) {
                logcat(LogPriority.ERROR, failure) {
                    "TachiyomiAT lazy persistence task failed: generation=${task.generation}"
                }
                task.completion.complete(false)
            }
        }
        flushPendingLazyMutations()
    }

    /** Flushes accepted work and joins the worker before returning. */
    suspend fun closeAndFlush(afterFlush: suspend () -> Unit = {}) {
        try {
            flush()
            withContext(NonCancellable) { afterFlush() }
        } finally {
            withContext(NonCancellable) { drainScopeJob.cancelAndJoin() }
        }
    }

    /** Waits briefly for the worker, then discards any retained work on eviction. */
    suspend fun markDefunct(markGenerationDefunctLocked: () -> Unit) = withContext(NonCancellable) {
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
            releaseRetainedWorkLocked()
        }
    }

    /** Starts a fire-and-forget close boundary. */
    fun close(afterFlush: suspend () -> Unit = {}) {
        drainScope.launch {
            flush()
            withContext(NonCancellable) { afterFlush() }
        }.invokeOnCompletion {
            drainScope.cancel()
        }
    }

    internal fun hasActiveChildren(): Boolean = drainScopeJob.children.any { it.isActive }

    internal fun isDrainScopeActive(): Boolean = drainScopeJob.isActive

    /** Caller holds the store mutex. */
    internal fun scheduleDrain() {
        if (store.isDefunct || !store.hasArtifactPersistenceTarget() || drainJob?.isActive == true) return
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
                            if (hasPendingWorkLocked()) scheduleDrain()
                        }
                    }
                }
            }
        }
        drainJob = scheduledJob
    }

    /** Caller holds the store mutex after defunct/generation transition. */
    internal fun releaseRetainedWorkLocked() {
        pendingLazyMutations.clear()
        pendingLazyTasks.forEach { it.completion.complete(false) }
        pendingLazyTasks.clear()
    }

    /** Adds one background image write and returns its completion handle. */
    internal suspend fun enqueueLazyPersistence(
        expectedGeneration: Long,
        work: suspend () -> Boolean,
    ): Deferred<Boolean> {
        val completion = CompletableDeferred<Boolean>()
        mutex.withLock {
            val rejected = store.isDefunct ||
                store.generation != expectedGeneration ||
                !store.hasArtifactPersistenceTarget() ||
                !isDrainScopeActive()
            if (rejected) {
                completion.complete(false)
            } else {
                pendingLazyTasks.addLast(
                    LazyPersistenceTask(
                        generation = expectedGeneration,
                        work = work,
                        completion = completion,
                    ),
                )
            }
            scheduleDrain()
        }
        return completion
    }

    private fun hasPendingWorkLocked(): Boolean =
        pendingLazyTasks.isNotEmpty() || pendingLazyMutations.isNotEmpty()

    private suspend fun flushPendingLazyMutations(): Boolean = mutex.withLock {
        if (pendingLazyMutations.isEmpty()) return@withLock true
        val pending = pendingLazyMutations.values.toList()
        pendingLazyMutations.clear()
        var allAccepted = true
        pending.forEach { mutation ->
            if (!store.isLazyGenerationCurrent(mutation.generation)) return@forEach
            if (!store.publicationBridge.persistArtifactMutation(
                    pageKey = mutation.pageKey,
                    previous = mutation.previous,
                    updated = mutation.updated,
                    expected = mutation.expected,
                )
            ) {
                allAccepted = false
                if (store.isLazyGenerationCurrent(mutation.generation)) {
                    pendingLazyMutations[mutation.pageKey] = mutation
                }
            }
        }
        allAccepted
    }
}

internal data class LazyPersistenceTask(
    val generation: Long,
    val work: suspend () -> Boolean,
    val completion: CompletableDeferred<Boolean>,
)

internal data class PendingLazyMutation(
    val pageKey: String,
    val previous: PageTranslation?,
    val updated: PageTranslation,
    val expected: ChapterTranslationStore.PatchPrecondition?,
    val generation: Long,
)
