package eu.kanade.translation.engines.rendering

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counter of asynchronous planner invocations. A hydrated bind
 * with a valid persisted plan must not increment it. The counter is
 * observational; it does not affect planner logic or ordering. JVM tests
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
