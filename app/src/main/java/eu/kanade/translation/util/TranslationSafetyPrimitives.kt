package eu.kanade.translation.util

/**
 * Pure, Android-free concurrency-safety primitives used by the pipeline and
 * OCR engine. Their race-sensitive behavior can be tested without constructing
 * the singleton pipeline or an ONNX recognition engine.
 *
 * Each helper encodes ONE invariant. The production call sites forward to these
 * so a future edit that reintroduces the bug fails the test here, not just on a
 * device SIGSEGV.
 *
 * Naming and style mirror [ShortHash]: a small `object` of pure functions in
 * `eu.kanade.translation.util`, tested in the same package under `app/src/test`.
 */
object TranslationSafetyPrimitives {

    /**
     * Drains every [ForceReleasable] child engine's native buffers exactly
     * once as a SIGSEGV guard. It runs only when the caller has established
     * that the native inference lock is free. When the lock is
     * held ([lockHeld] = true), the drain is skipped wholesale — draining
     * child pools while an in-flight ONNX call owns them is a native
     * use-after-free (SIGSEGV, uncatchable). The skip is leak-instead-of-crash;
     * the lock holder releases its own pools in its `finally`, and the next
     * memory-pressure callback retries.
     *
     * Pure: the caller resolves [lockHeld] (e.g. via `Mutex.tryLock`) and owns
     * the unlock. The drain itself only touches [ForceReleasable], which has
     * no Android dependency.
     *
     * Returns [DrainOutcome.Skipped] when [lockHeld] is true (so a test can
     * prove the skip happened without parsing logcat), or [DrainOutcome.Drained]
     * with the count of children that reported [ForceReleasable.forceReleaseNativeBuffers].
     */
    fun drainChildBuffersGuarded(
        lockHeld: Boolean,
        engines: List<ForceReleasable>,
    ): DrainOutcome {
        if (lockHeld) return DrainOutcome.Skipped
        var drained = 0
        for (engine in engines) {
            try {
                engine.forceReleaseNativeBuffers()
                drained++
            } catch (_: Throwable) {
                // Mirrors production: one child throwing must not abort the
                // drain of the others, and must not propagate (memory-pressure
                // callback has no caller to handle it).
            }
        }
        return DrainOutcome.Drained(drained)
    }

    /** Engine whose native buffers can be force-released under memory pressure. */
    fun interface ForceReleasable {
        fun forceReleaseNativeBuffers()
    }

    sealed interface DrainOutcome {
        /** Lock was held; drain skipped to avoid native use-after-free. */
        data object Skipped : DrainOutcome

        /** Lock was free; [count] children were drained. */
        data class Drained(val count: Int) : DrainOutcome
    }
}
