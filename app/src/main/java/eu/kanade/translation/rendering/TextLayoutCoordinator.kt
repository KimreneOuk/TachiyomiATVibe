package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import timber.log.Timber
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
    private var generation = 0L
    private var boundKey: TextLayoutCacheKey? = null

    /**
     * True while the coordinator's bound state is the clear/empty state (never
     * bound, or last bind was clear-shaped). Lets repeated `clear()` calls stay
     * the cheap no-op they were before the offload.
     */
    val isCleared: Boolean get() = boundKey == null

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
                    Timber.w(t, "Overlay text layout planning failed")
                    return@execute
                }
                mainExecutor.execute {
                    if (bindGeneration != generation) return@execute
                    cache.put(key, prepared)
                    onPrepared(prepared)
                }
            }
        } catch (e: RejectedExecutionException) {
            Timber.w(e, "Overlay text layout planning rejected")
        }
        return TextLayoutBindResult.Planning
    }

    /**
     * Invalidates in-flight deliveries without changing bound visuals (used on
     * view detach so a detached view can never receive an apply).
     */
    fun cancelPending() {
        generation++
    }
}
