package tachiyomi.domain.translation.pools

import android.graphics.Bitmap
import android.graphics.Color
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BitmapPool private constructor(
    private val config: Bitmap.Config,
    private val maxPoolSizePerSize: Int,
    private val maxTotalPoolSize: Int,
) {
    private val pools = ConcurrentHashMap<String, ConcurrentLinkedQueue<Bitmap>>()
    private val totalPoolSize = AtomicInteger(0)
    private val poolLock = ReentrantLock()

    private val maxRetainedBitmapBytes = if (config == Bitmap.Config.ALPHA_8) {
        16L * 1024L * 1024L
    } else {
        32L * 1024L * 1024L
    }

    companion object {
        private val instanceARGB_8888 = BitmapPool(Bitmap.Config.ARGB_8888, 3, 12)
        private val instanceALPHA_8 = BitmapPool(Bitmap.Config.ALPHA_8, 5, 20)

        fun getARGB8888(width: Int, height: Int): Bitmap = instanceARGB_8888.get(width, height)

        fun putARGB8888(bitmap: Bitmap) = instanceARGB_8888.put(bitmap)

        fun getALPHA8(width: Int, height: Int): Bitmap = instanceALPHA_8.get(width, height)

        fun putALPHA8(bitmap: Bitmap) = instanceALPHA_8.put(bitmap)

        fun releaseAll() {
            instanceARGB_8888.releaseAll()
            instanceALPHA_8.releaseAll()
        }
    }

    fun get(width: Int, height: Int): Bitmap {
        val sizeKey = "${width}x${height}"
        val pool = pools.getOrPut(sizeKey) { ConcurrentLinkedQueue() }

        return poolLock.withLock {
            pool.poll()?.takeIf {
                it.width == width && it.height == height
            }?.also {
                totalPoolSize.decrementAndGet()
                it.eraseColor(Color.TRANSPARENT)
            } ?: createBitmap(width, height)
        }
    }

    fun put(bitmap: Bitmap) {
        val sizeKey = "${bitmap.width}x${bitmap.height}"

        poolLock.withLock {
            if (estimatedBytes(bitmap) > maxRetainedBitmapBytes) {
                bitmap.recycle()
                return@withLock
            }

            if (totalPoolSize.get() >= maxTotalPoolSize) {
                bitmap.recycle()
                return@withLock
            }

            val pool = pools.getOrPut(sizeKey) { ConcurrentLinkedQueue() }

            if (pool.size < maxPoolSizePerSize) {
                pool.offer(bitmap)
                totalPoolSize.incrementAndGet()
            } else {
                bitmap.recycle()
            }
        }
    }

    fun releaseAll() {
        poolLock.withLock {
            pools.values.forEach { queue ->
                while (queue.isNotEmpty()) {
                    queue.poll()?.recycle()
                }
            }
            pools.clear()
            totalPoolSize.set(0)
        }
    }

    private fun createBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, config)
    }

    private fun estimatedBytes(bitmap: Bitmap): Long {
        val bytesPerPixel = when (bitmap.config) {
            Bitmap.Config.ALPHA_8 -> 1L
            Bitmap.Config.RGB_565, Bitmap.Config.ARGB_4444 -> 2L
            else -> 4L
        }
        return bitmap.width.toLong() * bitmap.height.toLong() * bytesPerPixel
    }
}
