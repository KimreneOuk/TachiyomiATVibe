package eu.kanade.translation.translator

import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards [InactivityFlusher] — the deterministic 250ms inactivity flush
 * (Checkpoint 2 §3). Uses fakes so virtual time advances without real delays,
 * and asserts no concurrent provider calls and preserved limits.
 */
class InactivityFlusherTest {

    @Test
    fun `flushes after 250ms of inactivity via virtual time`() = runTest {
        val flushCount = AtomicInteger(0)
        val fake = FakeTime()
        val flusher = InactivityFlusher(
            inactivityMs = 250L,
            timeSource = fake.timeSource,
            sleeper = fake.sleeper,
            onFlush = { chunk -> flusherOnFlush(chunk, flushCount) },
        )

        flusher.start(this) { null }
        // Advance past 250ms of inactivity in the fake clock.
        fake.advanceAndYield(this, 300L)
        flusher.stop()

        flushCount.get() shouldBe 1
    }

    @Test
    fun `recordActivity resets the inactivity deadline`() = runTest {
        val flushCount = AtomicInteger(0)
        val fake = FakeTime()
        val flusher = InactivityFlusher(
            inactivityMs = 250L,
            timeSource = fake.timeSource,
            sleeper = fake.sleeper,
            onFlush = { chunk -> flusherOnFlush(chunk, flushCount) },
        )

        flusher.start(this) { null }
        // Activity at 200ms < 250ms deadline resets the timer; no flush yet.
        fake.advanceAndYield(this, 200L)
        flusher.recordActivity()
        fake.advanceAndYield(this, 200L)
        // Only 200ms since the last activity; still no flush.
        flushCount.get() shouldBe 0
        // Now idle past the deadline.
        fake.advanceAndYield(this, 260L)
        flusher.stop()

        flushCount.get() shouldBe 1
    }

    @Test
    fun `never issues concurrent provider calls`() = runTest {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val fake = FakeTime()
        val flusher = InactivityFlusher(
            inactivityMs = 100L,
            timeSource = fake.timeSource,
            sleeper = fake.sleeper,
            onFlush = { _ ->
                val cur = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { existing -> maxOf(existing, cur) }
                // Simulate a slow provider request so concurrency is observable.
                fake.advanceAndYield(this, 50L)
                inFlight.decrementAndGet()
            },
        )

        flusher.start(this) { null }
        // Let several flush cycles run.
        repeat(5) { fake.advanceAndYield(this, 120L) }
        flusher.stop()

        maxInFlight.get() shouldBe 1
    }

    @Test
    fun `token and page limits preserved by delegating to the planner`() = runTest {
        // The flusher builds the remaining chunk from the planner, which already
        // enforces token/page limits. Verify the flushed chunk respects the cap.
        val planner = StreamingChunkPlanner(8192, TranslationContextChunkPlanner.Profile.DEFAULT)
        // Feed a page that overflows so the buffer holds an in-budget chunk.
        val big = "\u65e5".repeat(3000)
        val page = eu.kanade.translation.model.PageTranslation(
            blocks = mutableListOf(
                blockOf(big),
                blockOf(big),
            ),
        )
        planner.accept("p0", page)
        val fake = FakeTime()
        var flushed: TranslationContextChunk? = null
        val flusher = InactivityFlusher(
            inactivityMs = 50L,
            timeSource = fake.timeSource,
            sleeper = fake.sleeper,
            onFlush = { chunk -> flushed = chunk },
        )
        flusher.start(this) { planner.flushRemaining().finalChunk }
        fake.advanceAndYield(this, 80L)
        flusher.stop()

        flushed.shouldNotBeNull()
        // The chunk must stay within the context budget.
        val f = flushed!!
        (f.estimatedPromptTokens + f.maxOutputTokens + TranslationContextChunkPlanner.SAFETY_MARGIN <=
            TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS) shouldBe true
    }

    private fun flusherOnFlush(chunk: TranslationContextChunk?, count: AtomicInteger) {
        // A null chunk means the buffer was empty at flush time; the flush still
        // fired (the event we are counting), so count it unconditionally.
        count.incrementAndGet()
    }

    private fun blockOf(text: String) = eu.kanade.translation.model.TranslationBlock(
        text = text, translation = "",
        width = 10f, height = 10f, x = 0f, y = 0f,
        symHeight = 1f, symWidth = 1f, angle = 0f,
    )
}

/**
 * Fake clock + sleeper for virtual-time testing. The sleeper simulates the
 * requested delay by advancing the fake clock and yielding to let the flusher's
 * coroutines observe the new time, without real wall-clock delays.
 */
private class FakeTime {
    @Volatile
    private var nowMs: Long = 0L

    val timeSource: InactivityFlusher.TimeSource = InactivityFlusher.TimeSource { nowMs }

    /**
     * A sleeper that records requested sleeps; tests drive time forward via
     * [advanceAndYield]. Sleep returns immediately so the flusher loop re-checks
     * the deadline; the caller then advances the clock.
     */
    val sleeper: InactivityFlusher.Sleeper = InactivityFlusher.Sleeper { _ ->
        // Yield once so other coroutines (the flusher loop) can run.
        kotlinx.coroutines.yield()
    }

    /**
     * Advance the fake clock by [deltaMs] and yield so the flusher loop observes
     * the new time and fires any due flushes.
     */
    suspend fun advanceAndYield(scope: kotlinx.coroutines.test.TestScope, deltaMs: Long) {
        nowMs += deltaMs
        // Let the background flusher loop run a few times to observe the clock.
        repeat(3) { kotlinx.coroutines.yield() }
    }
}
