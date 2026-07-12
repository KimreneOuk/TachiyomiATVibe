package eu.kanade.translation.util

/**
 * TachiyomiAT: pure, Android-free concurrency-safety primitives extracted from
 * `TranslationPipeline` and `RoiPageRecognitionEngine` so the three load-bearing
 * race-condition invariants (Wave 1 P0-1 / P0-3 / P0-4) are unit-testable
 * without constructing the singleton pipeline or an ONNX recognition engine.
 *
 * Each helper encodes ONE invariant. The production call sites forward to these
 * so a future edit that reintroduces the bug fails the test here, not just on a
 * device SIGSEGV / deadlocked permit / swallowed watchdog callback.
 *
 * Naming and style mirror [ShortHash]: a small `object` of pure functions in
 * `eu.kanade.translation.util`, tested in the same package under `app/src/test`.
 */
object TranslationSafetyPrimitives {

    /**
     * P0-1 (SIGSEGV guard). Drains every [ForceReleasable] child engine's
     * native buffers exactly once, but ONLY when the caller has already
     * established that the native inference lock is free. When the lock is
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

    /**
     * P0-3 (clear-at-top). Clears the dedup key set BEFORE the caller attempts
     * to acquire the translator permit. The invariant: even if the permit is
     * currently held by an abandoning worker (so [acquirePermit] below returns
     * false and closeEngines returns early), future retries must not inherit
     * the abandoned worker's stale keys — otherwise the dedup gate
     * (`if (!keys.add(pageKey)) return`) silently drops every retry of every
     * page that worker touched, and translation stops working entirely.
     *
     * Pure: takes the key set by reference and an [acquirePermit] callback that
     * returns true if the permit was acquired (and the caller should proceed
     * with teardown), false if it was held. The clear runs unconditionally
     * before the callback — the load-bearing property.
     *
     * Returns [PermitOutcome.PermitHeld] when [acquirePermit] returned false
     * (keys still cleared), or [PermitOutcome.PermitAcquired] otherwise.
     */
    fun clearKeysBeforePermitAcquire(
        keys: MutableSet<String>,
        acquirePermit: () -> Boolean,
    ): PermitOutcome {
        keys.clear()
        return if (acquirePermit()) PermitOutcome.PermitAcquired else PermitOutcome.PermitHeld
    }

    /**
     * P0-4 (watchdog callback isolation). Runs each [WatchdogStep] in order,
     * catching every Throwable from each so a throwing step NEVER prevents the
     * later ones from running. This is the invariant that keeps a misbehaving
     * `onPageStuck` listener from swallowing the `onForceRelease` that clears
     * `inFlightPageKeys` and releases the permit — without it, one bad callback
     * deadlocks ALL translation for the rest of the process.
     *
     * Pure: takes the steps as data and returns what ran. Production builds the
     * step list from its `onTimeout` / `onPageStuck` / `onForceRelease`
     * callbacks. A step that throws is recorded in [WatchdogOutcome.failures]
     * but does not abort the chain. The step action is `suspend` so the
     * `onTimeout` callback (which suspends) fits without an extra wrapper.
     */
    suspend fun runGuardedWatchdogChain(
        vararg steps: WatchdogStep,
    ): WatchdogOutcome {
        val failures = mutableListOf<Throwable>()
        for (step in steps) {
            try {
                step.action()
            } catch (t: Throwable) {
                failures += t
            }
        }
        return WatchdogOutcome(ran = steps.size, failures = failures)
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

    sealed interface PermitOutcome {
        /** Keys cleared; permit was acquired (caller proceeds with teardown). */
        data object PermitAcquired : PermitOutcome
        /** Keys cleared; permit was held by an abandoning worker (caller returns). */
        data object PermitHeld : PermitOutcome
    }

    /** One isolated callback in the watchdog force-release chain. */
    class WatchdogStep(val name: String, val action: suspend () -> Unit)

    /** Result of running the watchdog chain; [failures] is non-empty if any step threw. */
    data class WatchdogOutcome(val ran: Int, val failures: List<Throwable>)
}
