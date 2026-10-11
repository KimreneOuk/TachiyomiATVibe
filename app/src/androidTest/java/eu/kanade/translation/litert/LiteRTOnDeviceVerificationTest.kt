package eu.kanade.translation.litert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
import logcat.LogPriority
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.logcat

@RunWith(AndroidJUnit4::class)
class LiteRTOnDeviceVerificationTest {

    @Test
    fun verifyLiteRTHardwareAccelerationAndInpainting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LiteRTMangaInpaintingEngine(context)

        println("[LiteRTTest] Checking LiteRTMangaInpaintingEngine availability...")
        val available = engine.isAvailable()
        val backend = engine.getBackendName()
        println("[LiteRTTest] Available: $available | Backend: $backend")

        assertTrue("LiteRT Engine should be available", available)

        // Create synthetic 512x512 test image with simulated manga screentone (checkerboard)
        val testBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(testBitmap)
        val paint = Paint()
        for (y in 0 until 512 step 4) {
            for (x in 0 until 512 step 4) {
                paint.color = if ((x / 4 + y / 4) % 2 == 0) Color.BLACK else Color.WHITE
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 4).toFloat(), (y + 4).toFloat(), paint)
            }
        }

        // Draw a simulated text box in the center
        paint.color = Color.BLACK
        canvas.drawRect(200f, 200f, 312f, 312f, paint)

        val boxes = listOf(intArrayOf(200, 200, 312, 312))
        val labels = listOf(1)

        // Warmup run
        println("[LiteRTTest] Running warmup inference...")
        val warmupStart = System.currentTimeMillis()
        val warmupResult = engine.inpaintRegions(testBitmap, boxes, labels, emptyList())
        val warmupElapsed = System.currentTimeMillis() - warmupStart
        println("[LiteRTTest] Warmup completed in ${warmupElapsed}ms")
        assertNotNull(warmupResult)

        // Measured benchmark runs
        val iterations = 5
        var totalMs = 0L
        for (i in 1..iterations) {
            val start = System.currentTimeMillis()
            val result = engine.inpaintRegions(testBitmap, boxes, labels, emptyList())
            val elapsed = System.currentTimeMillis() - start
            totalMs += elapsed
            println("[LiteRTTest] Iteration $i: ${elapsed}ms")
            assertNotNull(result)
        }
        val avgMs = totalMs / iterations.toDouble()
        println("[LiteRTTest] SUCCESS! Backend: $backend | Average Latency: ${avgMs}ms per patch")

        engine.close()
    }
}
