package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Outcome of [TextLayoutCoordinator.bind] — tells the caller exactly what view
 * state transition to perform without exposing any Android types, keeping the
 * whole coordinator JVM-unit-testable.
 */
internal sealed class TextLayoutBindResult<out T : Any> {
    /** Identical (already-bound) request: the caller's visuals are already correct. */
    data object Unchanged : TextLayoutBindResult<Nothing>()

    /** Empty/invalid request: the caller must draw nothing (the safe empty case). */
    data object Cleared : TextLayoutBindResult<Nothing>()

    /** Cache hit: layouts are ready NOW and must be applied synchronously. */
    data class Ready<out T : Any>(val prepared: T) : TextLayoutBindResult<T>()

    /** Cache miss: planning was scheduled in the background; drop stale visuals now. */
    data object Planning : TextLayoutBindResult<Nothing>()
}

/**
 * TachiyomiAT T920 3.1: moves overlay text-layout planning OFF the thread that
 * calls `TranslationOverlayView.bind` (the Main thread). The coordinator owns
 * the bind identity/generation bookkeeping:
 *
 *  - identical rebinds short-circuit as [TextLayoutBindResult.Unchanged] (the
 *    same cheap early-return the view performed before this existed);
 *  - cache hits return [TextLayoutBindResult.Ready] synchronously with zero
 *    planner work;
 *  - misses schedule [plan] on [backgroundExecutor] and deliver the prepared
 *    value through [onPrepared] ON [mainExecutor] — the calling thread never
 *    runs the planner;
 *  - every state change bumps a generation counter, and a delivery only applies
 *    if its generation is still current, so a recycled or re-bound view can
 *    never apply stale results.
 *
 * Single-thread confined: like [ReaderTextLayoutCache], all public methods must
 * run on the same thread (the Main thread in production) because the generation
 * counter and bound key are unsynchronized. The [plan] hook runs on
 * [backgroundExecutor] and must confine any mutable measurement state to that
 * executor.
 */
internal class TextLayoutCoordinator<T : Any>(
    private val cache: ReaderTextLayoutCache<T>,
    private val backgroundExecutor: Executor,
    private val mainExecutor: Executor,
    private val plan: (blocks: List<TranslationBlock>, pageWidth: Int, pageHeight: Int) -> T,
    private val onPrepared: (T) -> Unit,
) {
    // Read from the planner thread as a fast-path superseded check; the
    // authoritative check runs on Main. @Volatile formalizes that cross-thread
    // read: without it a stale read only wastes a plan (dropped on Main), but
    // the volatile guarantees the skip is seen promptly.
    @Volatile
    private var generation = 0L
    private var boundKey: TextLayoutCacheKey? = null

    fun bind(
        blocks: List<TranslationBlock>,
        pageWidth: Int,
        pageHeight: Int,
    ): TextLayoutBindResult<T> {
        if (blocks.isEmpty() || pageWidth <= 0 || pageHeight <= 0) {
            if (boundKey == null && blocks.isEmpty()) return TextLayoutBindResult.Unchanged
            generation++
            boundKey = null
            return TextLayoutBindResult.Cleared
        }
        val key = TextLayoutCacheKey(blocks, pageWidth, pageHeight)
        if (key == boundKey) return TextLayoutBindResult.Unchanged
        generation++
        boundKey = key
        cache.get(key)?.let { return TextLayoutBindResult.Ready(it) }

        // Capture the generation AFTER the bump: only a delivery matching THIS
        // bind may apply. Any later bind/clear/detach invalidates it.
        val bindGeneration = generation
        try {
            backgroundExecutor.execute {
                // Superseded before we even started: skip the planner entirely.
                if (bindGeneration != generation) return@execute
                val prepared = try {
                    plan(blocks, pageWidth, pageHeight)
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN) { "Overlay text layout planning failed: $t" }
                    return@execute
                }
                mainExecutor.execute {
                    if (bindGeneration != generation) return@execute
                    cache.put(key, prepared)
                    onPrepared(prepared)
                }
            }
        } catch (e: RejectedExecutionException) {
            logcat(LogPriority.WARN) { "Overlay text layout planning rejected: $e" }
        }
        return TextLayoutBindResult.Planning
    }

    /**
     * Invalidates in-flight deliveries and drops the bound identity (used on
     * view detach). Both halves matter: the generation bump makes a detached
     * view drop any late delivery on Main, and clearing [boundKey] guarantees
     * that a subsequent IDENTICAL rebind re-plans (or re-hits the cache)
     * instead of being short-circuited as [TextLayoutBindResult.Unchanged]
     * while the only plan that would have served it was just discarded — that
     * short-circuit would leave the overlay blank indefinitely.
     */
    fun cancelPending() {
        generation++
        boundKey = null
    }
}
