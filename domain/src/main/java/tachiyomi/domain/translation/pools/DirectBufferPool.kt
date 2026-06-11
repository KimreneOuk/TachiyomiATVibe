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
    private val inUseBuffers = ConcurrentHashMap<Thread, MutableSet<FloatBuffer>>()
    private val totalBuffers = AtomicInteger(0)
    private val bufferLock = ReentrantLock()

    fun acquire(): FloatBuffer {
        val thread = Thread.currentThread()
        bufferLock.withLock {
            inUseBuffers.getOrPut(thread) { ConcurrentHashMap.newKeySet() }
        }

        val buffer = availableBuffers.poll()

        return if (buffer != null) {
            bufferLock.withLock {
                inUseBuffers[thread]?.add(buffer)
            }
            buffer
        } else {
            bufferLock.withLock {
                createNewBuffer().also { newBuffer ->
                    inUseBuffers[thread]?.add(newBuffer)
                    totalBuffers.incrementAndGet()
                }
            }
        }
    }

    fun release(buffer: FloatBuffer) {
        val thread = Thread.currentThread()
        bufferLock.withLock {
            val threadBuffers = inUseBuffers[thread] ?: return@withLock

            if (threadBuffers.remove(buffer)) {
                if (availableBuffers.size < maxPoolSize) {
                    buffer.clear()
                    availableBuffers.offer(buffer)
                } else {
                    totalBuffers.decrementAndGet()
                }

                if (threadBuffers.isEmpty()) {
                    inUseBuffers.remove(thread)
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