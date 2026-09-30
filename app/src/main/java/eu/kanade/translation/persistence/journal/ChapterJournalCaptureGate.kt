package eu.kanade.translation.persistence.journal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Store-level capture barrier shared by every epoch for one chapter. Producers enter after taking
 * their journal credit and before the store mutation/capture critical section; a barrier parks new
 * producers without acquiring credits and waits only for producers already inside that section.
 */
internal class ChapterJournalCaptureGate {
    private val barrierMutex = Mutex()
    private val monitor = Any()
    private var acceptingProducers = true
    private var activeProducers = 0
    private var gateOpened = CompletableDeferred<Unit>().also { it.complete(Unit) }
    private var producersDrained = CompletableDeferred<Unit>().also { it.complete(Unit) }

    val activeProducerCount: Int get() = synchronized(monitor) { activeProducers }
    val isClosed: Boolean get() = synchronized(monitor) { !acceptingProducers }

    suspend fun <T> withProducer(block: suspend () -> T): T {
        val permit = enterProducer()
        try {
            return block()
        } finally {
            permit.close()
        }
    }

    /** Run [capture] with producers parked; the caller reopens before writing snapshot bytes. */
    suspend fun <T> withBarrier(capture: suspend () -> T): T = barrierMutex.withLock {
        val drained = synchronized(monitor) {
            check(acceptingProducers) { "capture barrier already closed" }
            acceptingProducers = false
            gateOpened = CompletableDeferred()
            if (activeProducers == 0) {
                null
            } else {
                CompletableDeferred<Unit>().also { producersDrained = it }
            }
        }
        try {
            drained?.await()
            capture()
        } finally {
            synchronized(monitor) {
                acceptingProducers = true
                gateOpened.complete(Unit)
            }
        }
    }

    private suspend fun enterProducer(): Permit {
        while (true) {
            val waitForOpen = synchronized(monitor) {
                if (acceptingProducers) {
                    if (activeProducers++ == 0) producersDrained = CompletableDeferred()
                    null
                } else {
                    gateOpened
                }
            }
            if (waitForOpen == null) return Permit()
            waitForOpen.await()
        }
    }

    private inner class Permit : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            synchronized(monitor) {
                check(activeProducers > 0) { "capture producer count underflow" }
                activeProducers--
                if (activeProducers == 0 && !acceptingProducers) producersDrained.complete(Unit)
            }
        }
    }
}

/** Shared chapter/session view: replacement and defunct stores use one gate across their epochs. */
internal class ChapterJournalCaptureCoordinator(
    // Keeping the identity alive also keeps the weak registry key stable while any store or
    // linger-writer completion callback still owns this coordinator.
    private val chapterIdentityHash: String? = null,
) {
    val gate = ChapterJournalCaptureGate()
    private val snapshotOperationMutex = Mutex()
    private val monitor = Any()
    private val liveWriters = linkedMapOf<ChapterJournalEpochKey, ChapterJournalWriter>()
    private val terminalCoverage = linkedMapOf<ChapterJournalEpochKey, ChapterJournalEpochCoverage>()

    /** Serializes snapshot generation assignment and durable publication without parking producers. */
    suspend fun <T> withSnapshotOperation(block: suspend () -> T): T =
        snapshotOperationMutex.withLock { block() }

    fun register(writer: ChapterJournalWriter) {
        val key = writer.epochCoverage.key
        synchronized(monitor) {
            terminalCoverage.remove(key)
            liveWriters[key] = writer
        }
        // The completion handler intentionally captures this coordinator: a linger writer must
        // keep the chapter live set reachable until no append can occur in its epoch.
        writer.invokeOnTerminal { coverage ->
            synchronized(monitor) {
                if (liveWriters[key] === writer) liveWriters.remove(key)
                terminalCoverage[key] = coverage
            }
        }
    }

    fun writersSnapshot(): List<ChapterJournalWriter> = synchronized(monitor) { liveWriters.values.toList() }

    fun coverageSnapshot(): List<ChapterJournalEpochCoverage> = synchronized(monitor) {
        ChapterJournalCompaction.mergeFrontiers(
            terminalCoverage.values,
            liveWriters.values.map(ChapterJournalWriter::epochCoverage),
        )
    }

    /** Drop small terminal metadata only after the corresponding epoch directory was removed. */
    fun retainTerminalCoverageFor(existingEpochs: Set<ChapterJournalEpochKey>) {
        synchronized(monitor) { terminalCoverage.keys.retainAll(existingEpochs) }
    }
}

/**
 * Stable chapter identities share a process-local capture gate and terminal-coverage registry.
 * Weak registry references let a chapter coordinator disappear after its stores and live writer
 * callbacks release it; a later session reconstructs any missing terminal coverage from disk.
 */
internal object ChapterJournalCaptureCoordinators {
    private val coordinators = java.util.WeakHashMap<String, java.lang.ref.WeakReference<ChapterJournalCaptureCoordinator>>()

    @Synchronized
    fun forChapter(chapterIdentityHash: String): ChapterJournalCaptureCoordinator {
        coordinators[chapterIdentityHash]?.get()?.let { return it }
        return ChapterJournalCaptureCoordinator(chapterIdentityHash).also { coordinator ->
            coordinators[chapterIdentityHash] = java.lang.ref.WeakReference(coordinator)
        }
    }
}
