package eu.kanade.translation.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.translation.model.TranslationBlock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android Bitmap coverage for the estimator's persisted OCR-rectangle API. */
@RunWith(AndroidJUnit4::class)
class RenderColorEstimatorInstrumentedTest {
    @Test
    fun recomputeForSamplesTheCurrentBlockOcrRectangle() {
        val cleaned = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(32f, 0f, 64f, 32f, Paint().apply { color = Color.BLACK })
        }
        val block = TranslationBlock(
            text = "M",
            translation = "M",
            width = 16f,
            height = 16f,
            x = 4f,
            y = 8f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
            label = 1,
            score = 1f,
        ).apply { textColor = 0xFF000000L }

        RenderColorEstimator.recomputeFor(cleaned, listOf(block))
        assertEquals(0xFF000000L, block.textColor)

        block.x = 40f
        RenderColorEstimator.recomputeFor(cleaned, listOf(block))
        assertEquals(0xFFFFFFFFL, block.textColor)
    }
}
