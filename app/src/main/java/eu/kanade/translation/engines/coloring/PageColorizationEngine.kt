package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap

/**
 * A colorization backend for the pipeline's COLOR stage. Implementations are
 * lazily initialized, re-initializable after [close] (closeEngines fires on
 * user stop while translation restarts in the same session), and single-thread
 * per instance (implementations synchronize their run path).
 */
interface PageColorizationEngine : AutoCloseable {

    /** True when the runtime is ready (assets present + session built). */
    fun isAvailable(): Boolean

    /** Stamp describing the live backend+artifact mix, for coloring provenance. */
    fun currentStamp(): String?

    /**
     * Colorizes a B&W manga page. [seed] is honored only by stochastic backends
     * (deterministic engines ignore it). Returns the result at the source
     * dimensions; throws on runtime failure — the caller owns the
     * never-fail-the-page contract and [isAvailable] answers availability.
     */
    fun colorize(source: Bitmap, seed: Long): Bitmap
}
