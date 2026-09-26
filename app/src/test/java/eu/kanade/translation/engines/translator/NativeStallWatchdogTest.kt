package eu.kanade.translation.engines.translator

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 *  Phase 4  — pure occupancy watchdog contract.
 *
 * The RED version deliberately uses a reflection bridge so this commit stays
 * compile-green before the production seam exists. Its fake clock resumes
 * delayed continuations only when the test advances virtual time: no sleeps,
 * polling, or wall-clock dependence.
 */
class NativeStallWatchdogTest {

    private class VirtualClock {
        var now = 0L
        private data class Pending(val dueAt: Long, val continuation: Continuation<Any?>)
        private val pending = mutableListOf<Pending>()

        fun clockProxy(): Any {
            val clockType = runCatching {
                Class.forName("eu.kanade.translation.engines.translator.NativeStallWatchdog\$Clock")
            }.getOrElse {
                throw AssertionError(
                    "T917 D8 RED defect: NativeStallWatchdog.Clock is missing — " +
                        "the virtual-time watchdog seam is not implemented",
                )
            }
            return Proxy.newProxyInstance(
                clockType.classLoader,
                arrayOf(clockType),
            ) { _, method, args ->
                when (method.name) {
                    "nowEpochMs" -> now
                    "delay" -> {
                        val millis = (args!![0] as Long)

                        @Suppress("UNCHECKED_CAST")
                        val continuation = args[1] as Continuation<Any?>
                        pending += Pending(now + millis, continuation)
                        COROUTINE_SUSPENDED
                    }
                    else -> error("unexpected watchdog clock method ${method.name}")
                }
            }
        }

        fun advanceBy(millis: Long) {
            now += millis
            val due = pending.filter { it.dueAt <= now }
            pending.removeAll(due.toSet())
            due.forEach { it.continuation.resumeWith(Result.success(Unit)) }
        }
    }

    private data class WatchdogHandle(
        val watchdog: Any,
        val state: kotlinx.coroutines.flow.StateFlow<Any?>,
        val occupied: (Long, String, Long) -> Unit,
        val released: (Long) -> Unit,
    )

    private fun createWatchdog(scope: CoroutineScope, thresholdMs: Long, clock: VirtualClock): WatchdogHandle {
        val cls = runCatching {
            Class.forName("eu.kanade.translation.engines.translator.NativeStallWatchdog")
        }.getOrElse {
            throw AssertionError(
                "T917 D8 RED defect: NativeStallWatchdog is missing — occupancy watchdog is not implemented",
            )
        }
        val ctor = cls.constructors.firstOrNull { it.parameterTypes.size == 3 }
            ?: throw AssertionError(
                "T917 D8 RED defect: NativeStallWatchdog has no injectable virtual clock constructor",
            )
        val watchdog = ctor.newInstance(scope, thresholdMs, clock.clockProxy())
        val state = cls.methods.firstOrNull { it.name == "getState" }
            ?.invoke(watchdog) as? kotlinx.coroutines.flow.StateFlow<Any?>
            ?: throw AssertionError(
                "T917 D8 RED defect: watchdog does not expose a StateFlow state",
            )
        fun method(name: String): java.lang.reflect.Method = cls.methods.firstOrNull { it.name == name }
            ?.also { it.isAccessible = true }
            ?: throw AssertionError("T917 D8 RED defect: watchdog is missing $name occupancy seam")
        return WatchdogHandle(
            watchdog = watchdog,
            state = state,
            occupied = { token, pageKey, startedAt -> method("onLaneOccupied").invoke(watchdog, token, pageKey, startedAt) },
            released = { token -> method("onLaneReleased").invoke(watchdog, token) },
        )
    }

    @Test
    fun `occupied lane does not emit before threshold and emits token and page at threshold`() = runTest {
        val clock = VirtualClock()
        val handle = createWatchdog(backgroundScope, thresholdMs = 100L, clock)
        handle.occupied(7L, "p7", 12L)
        runCurrent()
        handle.state.value shouldBe null

        clock.advanceBy(99L)
        runCurrent()
        handle.state.value shouldBe null

        clock.advanceBy(1L)
        runCurrent()
        val stalled = handle.state.value
            ?: throw AssertionError("T917 D8 RED defect: lane occupancy did not emit at the threshold")
        stalled.javaClass.getMethod("getToken").invoke(stalled) shouldBe 7L
        stalled.javaClass.getMethod("getPageKey").invoke(stalled) shouldBe "p7"
        stalled.javaClass.getMethod("getStartedAtEpochMs").invoke(stalled) shouldBe 12L
        stalled.javaClass.getMethod("getStalledAtEpochMs").invoke(stalled) shouldBe 100L
    }

    @Test
    fun `release before threshold cancels emission and release after emission clears`() = runTest {
        val clock = VirtualClock()
        val handle = createWatchdog(backgroundScope, thresholdMs = 100L, clock)
        handle.occupied(1L, "early", 0L)
        handle.released(1L)
        clock.advanceBy(100L)
        runCurrent()
        handle.state.value shouldBe null

        handle.occupied(2L, "late", clock.now)
        clock.advanceBy(100L)
        runCurrent()
        if (handle.state.value != null) {
            throw AssertionError("T917 D8 RED defect: second occupancy retained the prior stalled state")
        }
        handle.released(2L)
        runCurrent()
        handle.state.value shouldBe null
    }

    @Test
    fun `second occupancy replaces stalled state and never accumulates`() = runTest {
        val clock = VirtualClock()
        val handle = createWatchdog(backgroundScope, thresholdMs = 50L, clock)
        handle.occupied(1L, "first", 0L)
        runCurrent()
        clock.advanceBy(50L)
        runCurrent()
        val first = handle.state.value
            ?: throw AssertionError("T917 D8 RED defect: first occupancy did not emit at threshold")
        first.javaClass.getMethod("getPageKey").invoke(first) shouldBe "first"

        handle.occupied(2L, "second", clock.now)
        runCurrent()
        handle.state.value shouldBe null
        clock.advanceBy(50L)
        runCurrent()
        val replacement = handle.state.value
            ?: throw AssertionError("T917 D8 RED defect: second occupancy did not replace the prior watchdog")
        replacement.javaClass.getMethod("getToken").invoke(replacement) shouldBe 2L
        replacement.javaClass.getMethod("getPageKey").invoke(replacement) shouldBe "second"
    }
}
