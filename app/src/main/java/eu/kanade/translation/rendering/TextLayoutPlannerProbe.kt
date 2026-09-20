package eu.kanade.translation.rendering

import java.util.concurrent.atomic.AtomicLong

/**
 *  gate 7.5 instrumentation: process-wide counter of ASYNC planner
 * invocations. A hydrated bind (valid persisted plan) must keep this counter
 * untouched — restart/LRU rehydration with zero planner calls is the gate-7.5
 * pass condition (Pager AND Webtoon device rows). Purely observational: the
 * planner's own logic, determinism, and call shape are untouched; JVM tests
 * reset it via [resetForTest] to stay order-independent.
 */
object TextLayoutPlannerProbe {

    private val invocations = AtomicLong(0)

    /** Total async planner entries observed process-wide since the last reset. */
    val invocationCount: Long get() = invocations.get()

    /** One planner entry (a full page plan attempt, hydrated or not). */
    fun recordInvocation() {
        invocations.incrementAndGet()
    }

    /** JVM test seam: reset the counter (production never calls this). */
    fun resetForTest() {
        invocations.set(0)
    }
}
