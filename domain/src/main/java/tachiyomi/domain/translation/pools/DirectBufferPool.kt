package tachiyomi.domain.translation.pools

import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DirectBufferPool(
    private val bufferCapacityBytes: Int,
    private val maxPoolSize: Int = 4,
) {
    private val availableBuffers = ConcurrentLinkedQueue<FloatBuffer>()
    private val inUseBuffers = ConcurrentHashMap.newKeySet<FloatBuffer>()
    private val totalBuffers = AtomicInteger(0)
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
            availableBuffers.clear()
            inUseBuffers.clear()
            totalBuffers.set(0)
        }
    }

    private fun createNewBuffer(): FloatBuffer {
        return java.nio.ByteBuffer.allocateDirect(bufferCapacityBytes)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
    }
}