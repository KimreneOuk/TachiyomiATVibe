package tachiyomi.domain.translation.pools

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

class DirectBufferPoolTest {

    /**
     * These tests pin the DirectBufferPool's REAL contract: buffers are pooled
     * and reused across acquire/release without being cleared, bounded by
     * maxPoolSize. The consumer (MangaOcrEngine) writes only the KV-cache
     * positions it visits and relies on the model's causal attention to ignore
     * the rest — it does NOT require zeroed buffers. (A prior attempt to zero
     * buffers on acquire corrupted OCR: zeroed key/value vectors made the
     * attention softmax spread uniformly across all positions, producing
     * degenerate repetitive output. Do not re-add zeroing.)
     */
    @Test
    fun `a recycled buffer is returned to the pool on release`() {
        val bytes = 4 * Float.SIZE_BYTES
        val pool = DirectBufferPool(bytes, maxPoolSize = 1)

        val first = pool.acquire()
        pool.release(first)
        // The same pooled buffer is handed back out on the next acquire.
        val second = pool.acquire()
        second shouldBe first
        pool.release(second)
    }

    @Test
    fun `release beyond maxPoolSize drops the buffer`() {
        val bytes = 2 * Float.SIZE_BYTES
        val pool = DirectBufferPool(bytes, maxPoolSize = 1)

        val a = pool.acquire()
        val b = pool.acquire()
        pool.release(a) // fits (pool now holds 1)
        pool.release(b) // pool full -> b is dropped, not retained
        // Next acquire returns the retained buffer (a), not b.
        val c = pool.acquire()
        c shouldBe a
    }

    @Test
    fun `recycled buffer retains its capacity across releases`() {
        val bytes = 8 * Float.SIZE_BYTES
        val pool = DirectBufferPool(bytes, maxPoolSize = 2)
        val first = pool.acquire()
        first.capacity() shouldBe 8
        pool.release(first)
        val second = pool.acquire()
        second.capacity() shouldBe 8
        pool.release(second)
    }

    @Test
    fun `clear empties the pool so the next acquire is a fresh buffer`() {
        val bytes = 2 * Float.SIZE_BYTES
        val pool = DirectBufferPool(bytes, maxPoolSize = 2)
        val a = pool.acquire()
        pool.release(a)
        pool.clear()
        // After clear, the next acquire must not be the released buffer.
        val b = pool.acquire()
        (b === a) shouldBe false
        b.capacity() shouldBe 2
    }

    @Test
    fun `recycled buffer is writable and readable after a release-acquire cycle`() {
        // The pool only guarantees a usable buffer on acquire — it does NOT
        // promise data is preserved or cleared across release (release resets
        // position/limit via clear(); the backing bytes are implementation-
        // defined). The consumer (MangaOcrEngine) always overwrites the
        // positions it reads, so neither retention nor zeroing is part of the
        // contract. This test only pins that a recycled buffer remains a
        // functional read/write buffer.
        val bytes = 4 * Float.SIZE_BYTES
        val pool = DirectBufferPool(bytes, maxPoolSize = 1)
        val buf: FloatBuffer = pool.acquire()
        pool.release(buf)
        val reused = pool.acquire()
        reused.put(1, 7.5f)
        reused.get(1) shouldBe 7.5f
        reused.capacity() shouldBe 4
        pool.release(reused)
    }

    /**
     * TachiyomiAT: regression guard for the DirectBufferPool identity-tracking
     * leak (June 2026 OOM investigation).
     *
     * The real consumer (MangaOcrEngine.recognize) does acquire -> clear() ->
     * put(data) -> flip() -> ... -> release. `clear()` / `put()` / `flip()`
     * mutate the FloatBuffer's position and limit, and java.nio.FloatBuffer's
     * equals/hashCode are CONTENT- AND POSITION-DEPENDENT (per the JDK
     * contract: "the hash code depends upon the remaining elements" — which
     * in turn depends on position and limit).
     *
     * If the pool tracks in-use buffers in a structure keyed on FloatBuffer
     * equality (e.g. ConcurrentHashMap.newKeySet, the JDK default), the
     * mutated buffer no longer hashes to the same bucket it was added under,
     * so release()'s remove(buffer) silently fails (returns false). The
     * buffer is stranded in the in-use set forever, the available queue stays
     * empty, and every subsequent acquire allocates a brand-new
     * ByteBuffer.allocateDirect. Across hundreds of recognize() calls this
     * leaked hundreds of MB (an on-device heap dump showed 489 DirectByteBuffer
     * instances retained by 3 pools configured with maxPoolSize=2).
     *
     * This test reproduces the mutation-across-release pattern and asserts the
     * pool NEVER allocates more than maxPoolSize backing buffers, regardless
     * of how the consumer mutates the buffer's position/limit between acquire
     * and release. Track via System.identityHashCode — the pool must use
     * identity equality, not content equality.
     */
    @Test
    fun `mutating buffer position between acquire and release does not leak`() {
        // The pool must use IDENTITY equality for in-use tracking, NOT the
        // FloatBuffer's content/position-dependent equals/hashCode. See the
        // long doc comment above for the leak mechanism.
        val bytes = 16 * Float.SIZE_BYTES
        val pool = DirectBufferPool(bytes, maxPoolSize = 1)

        val first = pool.acquire()
        // Mutate position/limit the way MangaOcrEngine.recognize does — this
        // changes the FloatBuffer's hash, which would make a content-equality
        // set lose track of it.
        first.clear()
        first.put(FloatArray(first.capacity()) { it.toFloat() })
        first.flip()
        pool.release(first)

        // If the pool leaked, this returns a brand-new buffer (different instance).
        val second = pool.acquire()
        (second === first) shouldBe true
        pool.release(second)
    }
}
