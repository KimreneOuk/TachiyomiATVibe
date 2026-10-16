package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.chapter.AttemptArtifactLease
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operation-scoped write authority for one attempt on one page: mutex held only
 * across store calls; field-specific merges; [precondition] advances only on
 * `Accepted` (foreign winner typed `Rejected`, never overwritten). First terminal
 * marker wins (`NonCancellable`); closed/cancelled [callerJob] suppresses [write].
 */
internal class AttemptAuthority(
    private val store: ChapterTranslationStore,
    private val pageKey: String,
    initial: ChapterTranslationStore.PatchPrecondition,
    /** Captured before any quarantine Job strip; cancellation suppresses [write] but not a won marker. */
    val callerJob: Job? = null,
) {
    private val mutex = Mutex()

    /** Binds this attempt's authority to its own lazy-drain artifact progressions. */
    internal val artifactLease = AttemptArtifactLease(store, pageKey)

    /** Fenced identity for the next patch; mutated only under [mutex]. */
    @Volatile
    var precondition: ChapterTranslationStore.PatchPrecondition = initial
        private set

    /** True once a terminal marker won the close race; set only under [mutex]. */
    @Volatile
    var closed: Boolean = false
        private set

    /**
     * One field-specific attempt write against the store's current page.
     * `Rejected` without store access when closed/caller job cancelled; `Accepted`
     * advances [precondition] from the receipt snapshot, `Rejected` freezes it so
     * the loss stays observable. A missing page composes against a fresh empty draft.
     */
    suspend fun write(
        description: String,
        update: (PageTranslation) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult = mutex.withLock {
        if (closed || callerJob?.isCancelled == true) {
            return@withLock rejectedClosed(description)
        }
        when (val result = store.patchPage(pageKey, precondition, description, artifactLease, { current -> update(current ?: PageTranslation(sourceFileName = pageKey)) })) {
            is ChapterTranslationStore.PatchResult.Accepted -> {
                precondition = result.snapshot.toPrecondition()
                result
            }
            is ChapterTranslationStore.PatchResult.Rejected -> result
        }
    }

    /**
     * Terminal marker: closes the gate under [mutex], then writes terminal state
     * `NonCancellable` so exactly one marker mutates the page. Idempotent — a second
     * marker gets `Rejected("attempt already closed")`; a store-fence `Rejected`
     * still closes the attempt, so a foreign winner is never overwritten.
     */
    suspend fun markTimeout(
        update: (PageTranslation) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult = mutex.withLock {
        if (closed) {
            return@withLock ChapterTranslationStore.PatchResult.Rejected(reason = REASON_ALREADY_CLOSED)
        }
        closed = true
        val result = withContext(NonCancellable) {
            store.patchPage(pageKey, precondition, MARK_TIMEOUT_DESCRIPTION, artifactLease, { current -> update(current ?: PageTranslation(sourceFileName = pageKey)) })
        }
        if (result is ChapterTranslationStore.PatchResult.Accepted) {
            // Same receipt advancement as write: the fenced precondition tracks
            // the store through the terminal transition too, so a post-terminal
            // diagnostic read of the authority is never stale.
            precondition = result.snapshot.toPrecondition()
        }
        result
    }

    private fun rejectedClosed(description: String): ChapterTranslationStore.PatchResult =
        ChapterTranslationStore.PatchResult.Rejected(reason = "$REASON_CLOSED ($description)")

    private companion object {
        const val REASON_CLOSED = "attempt closed"
        const val REASON_ALREADY_CLOSED = "attempt already closed"
        const val MARK_TIMEOUT_DESCRIPTION = "mark attempt timed out"
    }
}

/**
 * Where an owned cleaned bitmap was produced. The retry produces a genuinely
 * new background bitmap, so the two provenances are observable in diagnostics.
 */
internal enum class CleanedProvenance {
    /** First production: the admitted same-page inpaint segment's output. */
    INPAINT,

    /** Retry production: the admitted retry segment's scaled output. */
    RETRY,
}

/**
 * Single-slot exclusive owner of one attempt's cleaned bitmap and its SOLE recycler:
 * producers deposit and drop their reference; render/persistence borrow explicitly and
 * close the borrow before the boundary's `finally` calls [release] (idempotent, receipt
 * + zero-borrow gates, after the owning segment joined). Alias transfer consults [owns].
 */
internal class CleanedOwner {

    private class Slot(val bitmap: Bitmap, val provenance: CleanedProvenance)

    @Volatile
    private var slot: Slot? = null

    private val released = AtomicBoolean(false)

    /** Open explicit borrows; [release] suspends on this reaching zero. */
    private val openBorrows = MutableStateFlow(0)

    @Volatile
    private var receipt: CompletableDeferred<Boolean>? = null

    /** True while a produced bitmap is owned and not yet released. */
    val isAvailable: Boolean
        get() = !released.get() && slot != null

    /**
     * Identity check for transfer-aware local cleanups: true only while
     * [bitmap] is the currently owned instance and the owner has not started
     * releasing.
     */
    fun owns(bitmap: Bitmap): Boolean = !released.get() && slot?.bitmap === bitmap

    /**
     * Deposits the first produced cleaned bitmap; the caller drops its local
     * reference after this call — disposal belongs to [release] exclusively.
     * Fails fast on double production (a wiring bug, never recoverable).
     */
    fun produce(bitmap: Bitmap, provenance: CleanedProvenance) {
        check(!released.get()) { "produce after release started" }
        check(slot == null) { "cleaned bitmap already produced for this attempt" }
        slot = Slot(bitmap, provenance)
    }

    /**
     * Slot swap NOW for the retry's genuinely new output; legal only on an
     * empty slot — the retry runs after a null cleaned result. Replacing over
     * an owned bitmap would orphan it past the sole-recycler rule; fails fast.
     */
    fun replace(bitmap: Bitmap, provenance: CleanedProvenance) {
        check(!released.get()) { "replace after release started" }
        check(slot == null) { "replace over an owned cleaned bitmap" }
        slot = Slot(bitmap, provenance)
    }

    /**
     * Counts one exclusive borrow; null when nothing is owned or release
     * started. Every successful borrow MUST be paired with [closeBorrow];
     * [release] never recycles under an open borrow.
     */
    fun borrow(): Bitmap? {
        if (released.get()) return null
        val current = slot ?: return null
        openBorrows.update(Int::inc)
        if (released.get()) {
            // Release started while the borrow was being counted: deny it so
            // release can never wait on a borrow it can no longer observe.
            openBorrows.update(Int::dec)
            return null
        }
        return current.bitmap
    }

    /** Closes one [borrow]; the count is what [release] suspends on. */
    fun closeBorrow() {
        openBorrows.update { current ->
            check(current > 0) { "unpaired closeBorrow" }
            current - 1
        }
    }

    /**
     * Registers the persistence receipt before store enrollment; [release]
     * joins it so recycling waits for the queued lazy writer (suppression's
     * `false` releases; exceptional completion fails closed). Register before
     * enrollment, release at the boundary `finally` - these checks guard that.
     */
    fun registerReceipt(receipt: CompletableDeferred<Boolean>) {
        check(!released.get()) { "registerReceipt after release started" }
        check(this.receipt == null) { "persistence receipt already registered" }
        this.receipt = receipt
    }

    /** True only after store persistence worker succeeds. */
    suspend fun awaitPersistence(): Boolean = receipt?.await() == true

    /**
     * Sole recycler: idempotent, gated on receipt-join plus zero open borrows (both
     * true suspensions), `NonCancellable`; caller must hold the native real-exit
     * guarantee. Receipt policy FAILS CLOSED: exceptional/cancelled receipt is a
     * protocol violation - [release] propagates and does NOT recycle.
     */
    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            receipt?.await()
            openBorrows.first { it == 0 }
            slot?.bitmap?.recycle()
            slot = null
        }
    }
}

/**
 * Single-slot exclusive custody holder for the source bitmap ($S$) across the boundary.
 */
internal class SourceBitmapCustody : AutoCloseable {
    private val slot = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)

    fun register(bitmap: Bitmap) {
        check(slot.compareAndSet(null, bitmap)) { "source bitmap already registered in custody slot" }
    }

    /** Takes ownership out of the boundary slot at first guarded use inside the producing try/finally. */
    fun take(): Bitmap? = slot.getAndSet(null)

    /** Boundary cleanup: safely recycles only if the bitmap was never taken by a producer block. */
    override fun close() {
        slot.getAndSet(null)?.let { bitmap ->
            try {
                bitmap.recycle()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Source bitmap recycle failed during custody close" }
            }
        }
    }
}
