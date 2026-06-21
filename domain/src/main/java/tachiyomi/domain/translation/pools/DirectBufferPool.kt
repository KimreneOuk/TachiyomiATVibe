package tachiyomi.domain.translation.pools

import java.nio.FloatBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DirectBufferPool(
    private val bufferCapacityBytes: Int,
    private val maxPoolSize: Int = 4,
) {
    private val availableBuffers = ConcurrentLinkedQueue<FloatBuffer>()
    /**
     * TachiyomiAT: in-use tracking MUST use identity equality, not the
     * FloatBuffer's content/position-dependent equals/hashCode.
     *
     * The consumer (MangaOcrEngine.recognize) mutates the buffer's position
     * and limit between acquire and release (clear -> put -> flip). Per the
     * JDK FloatBuffer contract, equals/hashCode depend on the buffer's
     * "remaining elements" — i.e. on position and limit — so a mutated buffer
     * no longer hashes to the bucket it was added under. A
     * ConcurrentHashMap.newKeySet (content-equality) lost track of the buffer,
     * release()'s remove() silently returned false, the buffer was stranded
     * in the in-use set forever, and every subsequent acquire allocated a
     * fresh ByteBuffer.allocateDirect. Across hundreds of recognize() calls
     * this leaked hundreds of MB of direct byte buffers — an on-device heap
     * dump (June 2026 OOM investigation) showed 489 DirectByteBuffer instances
     * retained by 3 pools configured with maxPoolSize=2.
     *
     * IdentityHashMap uses System.identityHashCode + ===, which are stable
     * across position/limit mutation. Wrap in synchronizedSet since
     * IdentityHashMap is not thread-safe; all mutation already happens under
     * bufferLock, but synchronizedSet is defense-in-depth if a future caller
     * bypasses the lock. See DirectBufferPoolTest
     * "mutating buffer position between acquire and release does not leak".
     */
    private val inUseBuffers: MutableSet<FloatBuffer> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    private val totalBuffers = AtomicInteger()
    private val bufferLock = ReentrantLock()

    fun acquire(): FloatBuffer {
        val buffer = availableBuffers.poll()

        return if (buffer != null) {
            bufferLock.withLock {
                inUseBuffers.add(buffer)
            }
            buffer
        } else {
            bufferLock.withLock {
                createNewBuffer().also { newBuffer ->
                    inUseBuffers.add(newBuffer)
                    totalBuffers.incrementAndGet()
                }
            }
        }
    }

    fun release(buffer: FloatBuffer) {
        bufferLock.withLock {
            if (inUseBuffers.remove(buffer)) {
                if (availableBuffers.size < maxPoolSize) {
                    buffer.clear()
                    availableBuffers.offer(buffer)
                } else {
                    totalBuffers.decrementAndGet()
                }
            }
        }
    }

    fun clear() {
        bufferLock.withLock {
            availableBuffers.forEach { cleanDirectBuffer(it) }
            inUseBuffers.forEach { cleanDirectBuffer(it) }
            availableBuffers.clear()
            inUseBuffers.clear()
            totalBuffers.set(0)
        }
    }

    private fun cleanDirectBuffer(buffer: java.nio.Buffer) {
        if (!buffer.isDirect) return
        try {
            val cleanerMethod = try {
                buffer.javaClass.getMethod("cleaner")
            } catch (e: NoSuchMethodException) {
                null
            }

            if (cleanerMethod != null) {
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer)
                if (cleaner != null) {
                    val cleanMethod = cleaner.javaClass.getMethod("clean")
                    cleanMethod.isAccessible = true
                    cleanMethod.invoke(cleaner)
                    return
                }
            }

            val attachmentMethod = try {
                buffer.javaClass.getDeclaredMethod("attachment")
            } catch (e: NoSuchMethodException) {
                try {
                    buffer.javaClass.getDeclaredMethod("att")
                } catch (ex: NoSuchMethodException) {
                    null
                }
            }

            val attachment = if (attachmentMethod != null) {
                attachmentMethod.isAccessible = true
                attachmentMethod.invoke(buffer)
            } else {
                val attField = try {
                    buffer.javaClass.getDeclaredField("att")
                } catch (e: NoSuchFieldException) {
                    try {
                        buffer.javaClass.getDeclaredField("attachment")
                    } catch (ex: NoSuchFieldException) {
                        null
                    }
                }
                if (attField != null) {
                    attField.isAccessible = true
                    attField.get(buffer)
                } else {
                    null
                }
            }

            if (attachment is java.nio.Buffer) {
                cleanDirectBuffer(attachment)
            }
        } catch (t: Throwable) {
            // Ignore reflection exceptions to avoid crashes
        }
    }

    private fun createNewBuffer(): FloatBuffer {
        return java.nio.ByteBuffer.allocateDirect(bufferCapacityBytes)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
    }
}
