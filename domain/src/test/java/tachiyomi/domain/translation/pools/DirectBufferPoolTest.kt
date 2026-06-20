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
    fun `buffer is usable for normal put_get round-trips`() {
        val bytes = 4 * Float.SIZE_BYTES
        val pool = DirectBufferPool(bytes, maxPoolSize = 2)
        val buffer = pool.acquire()
        buffer.put(0, 1.5f)
        buffer.put(3, -2.25f)
        buffer.get(0) shouldBe 1.5f
        buffer.get(3) shouldBe -2.25f
        pool.release(buffer)
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
}
