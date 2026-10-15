package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operation-scoped write authority for one translation attempt on one page
 * (plan §4). Every writer inside the operation — branch writers, the timeout
 * adapter, post-join publication — serializes through this authority instead
 * of patching the store directly, so two concurrent branch writers on one
 * page (the same-page overlap shape) can never interleave fenced patches or
 * race the terminal transition.
 *
 * Contract invariants:
 * - `authority mutex → store APIs` only; the mutex is never held across a
 *   native or HTTP wait, and no store callback re-enters the authority.
 * - [precondition] is the fenced write identity; it advances only on an
 *   `Accepted` patch receipt, so a foreign writer winning a page version
 *   makes every later attempt write a typed `Rejected` (terminal-and-ignore,
 *   never an overwrite of the winner).
 * - Writes are FIELD-SPECIFIC merges: the [write]/[markTimeout] `update`
 *   function composes against the store's current page and must never
 *   replace the page from a stale whole-object copy of a branch's working
 *   instance.
 * - Terminal policy is first-terminal-wins and executable: the first
 *   [markTimeout] closes the attempt; a second marker is an idempotent typed
 *   no-op. Once closed — or once [callerJob] is cancelled — [write] is
 *   suppressed without touching the store. The marker itself is
 *   `NonCancellable` so an already-won terminal transition cannot be lost to
 *   caller cancellation.
 *
 * The authority holds no admission, lane or ownership role; production
 * marker content is wired at the cutover slice.
 */
internal class AttemptAuthority(
    private val store: ChapterTranslationStore,
    private val pageKey: String,
    initial: ChapterTranslationStore.PatchPrecondition,
    /**
     * Caller job captured before any quarantine Job strip. Cancellation of
     * this job suppresses further [write]s; it does NOT cancel an already
     * won terminal marker.
     */
    val callerJob: Job? = null,
) {
    private val mutex = Mutex()

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
     *
     * Returns `Rejected` without touching the store when the attempt is
     * closed or the caller job was cancelled. On `Accepted` the fenced
     * precondition advances from the store's returned receipt snapshot; on
     * `Rejected` the precondition stays frozen so the loss is observable.
     *
     * When the store reports no current page for [pageKey], the update
     * composes against a fresh empty draft (same seeding convention as the
     * store's own compatibility write seam); a live attempt on a healthy
     * page never reaches that path because earlier stage writes created the
     * page and the generation/page-version fences reject a lost page first.
     */
    suspend fun write(
        description: String,
        update: (PageTranslation) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult = mutex.withLock {
        if (closed || callerJob?.isCancelled == true) {
            return@withLock rejectedClosed(description)
        }
        when (val result = store.patchPage(pageKey, precondition, description, { current -> update(current ?: PageTranslation(sourceFileName = pageKey)) })) {
            is ChapterTranslationStore.PatchResult.Accepted -> {
                precondition = result.snapshot.toPrecondition()
                result
            }
            is ChapterTranslationStore.PatchResult.Rejected -> result
        }
    }

    /**
     * Terminal marker for the attempt: closes the gate FIRST (under [mutex]),
     * then writes the terminal page state `NonCancellable`, so exactly one
     * marker ever mutates the page and a caller cancellation cannot lose it.
     * The marker patch content is a parameter — production terminal state is
     * wired at the cutover slice; adapters pass the store's timed-out stage
     * transition here.
     *
     * Idempotent: after any prior [markTimeout] — by this caller or a racing
     * one — this returns `Rejected("attempt already closed")` without
     * touching the store. The losing marker is terminal-and-ignore; first
     * terminal wins. A `Rejected` from the store fence itself (foreign
     * writer advanced the page) leaves [closed] set: the attempt is closed
     * either way and the foreign winner is never overwritten.
     */
    suspend fun markTimeout(
        update: (PageTranslation) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult = mutex.withLock {
        if (closed) {
            return@withLock ChapterTranslationStore.PatchResult.Rejected(reason = REASON_ALREADY_CLOSED)
        }
        closed = true
        val result = withContext(NonCancellable) {
            store.patchPage(pageKey, precondition, MARK_TIMEOUT_DESCRIPTION, { current -> update(current ?: PageTranslation(sourceFileName = pageKey)) })
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
 * Single-slot exclusive owner of one attempt's cleaned bitmap (plan §5).
 *
 * The owner is the SOLE recycler: the producing segment deposits its output
 * and drops its local reference; render and persistence borrow explicitly and
 * must close the borrow before the boundary's `finally` calls [release].
 * [release] is idempotent, joins the registered persistence receipt (so the
 * queued lazy writer's borrow always completes first — an accepted borrow is
 * never dropped), waits for any still-open explicit borrow, and only then
 * recycles. Native real exit is a caller-ordering obligation: the boundary
 * reaches [release] only after the owning segment joined, so the owner never
 * recycles under live native use.
 *
 * Alias transfer: when the inpainter returns its input bitmap unchanged, the
 * producing segment registers that SAME instance with [produce] and the
 * transfer-aware local cleanup consults [owns] instead of recycling, so the
 * alias identity transfers into the owner exactly once.
 *
 * Host tests verify recycle/exit via MockK bitmap stubs; actual native bitmap
 * disposal remains device-gated and is not claimed by this type.
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
     * Deposits the first produced cleaned bitmap. The caller drops its local
     * reference after this call; disposal belongs to [release] exclusively.
     * Fails fast on double production — a produce/produce sequence is a
     * pipeline wiring bug, never a recoverable state.
     */
    fun produce(bitmap: Bitmap, provenance: CleanedProvenance) {
        check(!released.get()) { "produce after release started" }
        check(slot == null) { "cleaned bitmap already produced for this attempt" }
        slot = Slot(bitmap, provenance)
    }

    /**
     * Executes the identity transfer for the retry's genuinely new output:
     * the slot swap happens NOW, not at release. Legal only on an empty slot
     * — the retry runs after a null cleaned result, so there is no displaced
     * bitmap to dispose; replacing over an owned bitmap would orphan it past
     * the sole-recycler rule and fails fast.
     */
    fun replace(bitmap: Bitmap, provenance: CleanedProvenance) {
        check(!released.get()) { "replace after release started" }
        check(slot == null) { "replace over an owned cleaned bitmap" }
        slot = Slot(bitmap, provenance)
    }

    /**
     * Counts one exclusive borrow of the owned bitmap for render or
     * persistence work; returns null when nothing is owned or release has
     * already started. Every successful borrow MUST be paired with
     * [closeBorrow]; [release] never recycles under an open borrow.
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
     * Registers the caller-held persistence receipt before store enrollment.
     * [release] joins it so recycling happens only after the queued lazy
     * writer actually finished with the bitmap (a normal `false` completion
     * still releases — suppression is a normal outcome; an exceptional or
     * cancelled completion fails release closed, see [release]). Non-
     * suspending; one receipt per attempt.
     *
     * Lifecycle guard: registration belongs BEFORE enrollment and release
     * belongs at the boundary `finally` AFTER enrollment, so this fails fast
     * on registration after release started. That ordering discipline is a
     * boundary responsibility; this check is a guard against violating it,
     * not an atomicity claim about arbitrary concurrent enrollment/release.
     */
    fun registerReceipt(receipt: CompletableDeferred<Boolean>) {
        check(!released.get()) { "registerReceipt after release started" }
        check(this.receipt == null) { "persistence receipt already registered" }
        this.receipt = receipt
    }

    /**
     * Sole recycler: idempotent (the first caller performs the teardown,
     * later callers return immediately), and gated on the persistence
     * receipt-join plus zero open borrows. Both gates are true suspension
     * points (no busy waiting): the receipt is awaited directly and the
     * borrow count is observed with [kotlinx.coroutines.flow.first], which
     * resumes exactly when [closeBorrow] transitions it to zero. Runs
     * `NonCancellable` so a cancelled boundary still disposes the bitmap.
     * The caller must already hold the native real-exit guarantee (the
     * owning segment joined).
     *
     * Receipt failure policy is FAIL CLOSED: the persistence receipt is
     * contracted to complete NORMALLY (`true` = persisted, `false` =
     * suppressed — the S2 store protocol has no other completion). An
     * exceptional or cancelled receipt completion is a protocol violation,
     * NOT proof that the queued borrower physically exited, so [release]
     * propagates the failure and does NOT recycle: the bitmap resource is
     * retained for diagnosis instead of being recycled on an unverified
     * borrower state. Release stays idempotent — later calls return without
     * recycling. A caller exception is never used to fabricate a `false`
     * completion for a queued borrower.
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
