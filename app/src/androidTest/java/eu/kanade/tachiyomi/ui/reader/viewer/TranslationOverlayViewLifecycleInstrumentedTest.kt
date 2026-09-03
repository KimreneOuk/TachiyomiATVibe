package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.rendering.BlockLayout
import eu.kanade.translation.rendering.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Reader-recycling regression coverage for prepared overlay drawing state. */
@RunWith(AndroidJUnit4::class)
class TranslationOverlayViewLifecycleInstrumentedTest {
    @Test
    fun rebindAndClearCannotDrawStalePreparedLayouts() {
        val view = TranslationOverlayView(InstrumentationRegistry.getInstrumentation().targetContext)
        val first = layout("FIRST")

        view.bindLayoutsForTest(listOf(first), 64, 64)
        view.bindLayoutsForTest(emptyList(), 64, 64)
        assertEmpty(view)

        view.bindLayoutsForTest(listOf(first), 64, 64)
        view.clear()
        assertEmpty(view)
    }

    private fun assertEmpty(view: TranslationOverlayView) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        view.drawLayoutsForTest(Canvas(bitmap))
        var alpha = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) alpha += Color.alpha(bitmap.getPixel(x, y))
        assertEquals(0, alpha)
    }

    private fun layout(text: String) = BlockLayout(
        block = TranslationBlock(text = text, translation = text, width = 52f, height = 52f, x = 6f, y = 6f, symHeight = 1f, symWidth = 1f, angle = 0f, label = 1, score = 1f),
        text = text,
        isVertical = false,
        originX = 32f,
        originY = 32f,
        safeW = 56f,
        safeH = 56f,
        fontSizePx = 18f,
        strokeWidth = 2f,
        drawAlign = TextAlign.CENTER,
        clipRect = null,
        lines = listOf(text),
    )
}
