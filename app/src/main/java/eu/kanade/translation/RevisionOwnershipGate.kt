package eu.kanade.translation

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small, testable ownership gate for chapter-scoped revision jobs.
 *
 * A pending reservation is visible as active before the caller cancels Auto or
 * constructs the revision job. The manager performs those transitions while
 * holding [withLock], so an Auto update cannot pass its arbitration check in
 * the registration gap.
 */
internal class RevisionOwnershipGate {

    @PublishedApi
    internal val lock = Any()
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val pendingReservations = mutableSetOf<Long>()
    private val cancelledReservations = mutableSetOf<Long>()

    internal inline fun <T> withLock(block: () -> T): T = synchronized(lock, block)

    fun isActive(chapterId: Long): Boolean = withLock { isActiveLocked(chapterId) }

    fun isActiveLocked(chapterId: Long): Boolean =
        activeJobs.containsKey(chapterId) || chapterId in pendingReservations

    fun reserveLocked(chapterId: Long): Boolean {
        if (isActiveLocked(chapterId)) return false
        cancelledReservations.remove(chapterId)
        pendingReservations += chapterId
        return true
    }

    fun registerLocked(chapterId: Long, job: Job): Boolean {
        if (
            chapterId !in pendingReservations ||
            chapterId in cancelledReservations ||
            job.isCancelled ||
            job.isCompleted
        ) {
            pendingReservations.remove(chapterId)
            cancelledReservations.remove(chapterId)
            job.cancel()
            return false
        }
        activeJobs[chapterId] = job
        pendingReservations.remove(chapterId)
        job.start()
        return true
    }

    fun release(chapterId: Long, job: Job? = null) {
        withLock {
            if (job == null) {
                activeJobs.remove(chapterId)
                pendingReservations.remove(chapterId)
                cancelledReservations.remove(chapterId)
            } else if (activeJobs[chapterId] === job) {
                activeJobs.remove(chapterId)
                pendingReservations.remove(chapterId)
                cancelledReservations.remove(chapterId)
            }
        }
    }

    fun cancel(chapterId: Long) {
        withLock {
            activeJobs[chapterId]?.cancel()
            if (chapterId in pendingReservations) {
                cancelledReservations += chapterId
            }
        }
    }

    fun ownedChapterIds(): List<Long> = withLock {
        mutableSetOf<Long>().apply {
            addAll(activeJobs.keys)
            addAll(pendingReservations)
        }.toList()
    }
}

/**
 * Production transaction seam used by [TranslationManager.startRevision]. It
 * keeps tracker cleanup coupled to reservation lifecycle even when a lazy job
 * is rejected or cancelled before its body starts.
 */
internal class RevisionOwnershipTransaction(
    private val gate: RevisionOwnershipGate,
    private val chapterId: Long,
    private val disposeTracker: () -> Unit,
) {
    private val bodyStarted = AtomicBoolean(false)
    private val terminalRequested = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)
    private val setupSettled = AtomicBoolean(false)

    val isRegistered: Boolean get() = registered.get()

    fun markBodyStarted() {
        bodyStarted.set(true)
    }

    fun markTerminalRequested() {
        terminalRequested.set(true)
    }

    fun register(job: Job): Boolean {
        job.invokeOnCompletion {
            if (!terminalRequested.get() && (!bodyStarted.get() || job.isCompleted)) {
                disposeTracker()
            }
            gate.release(chapterId, job)
        }
        val accepted = gate.withLock { gate.registerLocked(chapterId, job) }
        if (!accepted) {
            setupSettled.set(true)
            disposeTracker()
        } else {
            registered.set(true)
        }
        return accepted
    }

    fun abortSetup() {
        if (registered.get()) return
        if (setupSettled.compareAndSet(false, true)) {
            disposeTracker()
            gate.release(chapterId)
        }
    }
}
