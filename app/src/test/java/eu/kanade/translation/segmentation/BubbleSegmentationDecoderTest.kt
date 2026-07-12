package eu.kanade.translation.segmentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubbleSegmentationDecoderTest {
    @Test
    fun `decodes prototype matmul sigmoid and inverse letterbox`() {
        val predictionCount = 2
        val predictions = FloatArray(37 * predictionCount)
        // Candidate 0: xywh=(320,320,320,160), confidence=.9, coefficient 0=10.
        predictions[0] = 320f; predictions[predictionCount] = 320f
        predictions[2 * predictionCount] = 320f; predictions[3 * predictionCount] = 160f
        predictions[4 * predictionCount] = .9f; predictions[5 * predictionCount] = 10f
        // Candidate 1 is a lower-scoring duplicate and must be NMS-suppressed.
        predictions[1] = 320f; predictions[predictionCount + 1] = 320f
        predictions[2 * predictionCount + 1] = 320f; predictions[3 * predictionCount + 1] = 160f
        predictions[4 * predictionCount + 1] = .6f; predictions[5 * predictionCount + 1] = 10f
        val prototypes = FloatArray(32 * 4 * 4)
        for (i in 0 until 16) prototypes[i] = 1f

        val masks = BubbleSegmentationDecoder.decode(predictions, 37, predictionCount, prototypes, 4, 4, 640, 320)

        assertEquals(1, masks.size)
        assertTrue(masks.single().pixels.count { it != 0.toByte() } > 0)
        assertEquals(listOf(160, 80, 480, 240), masks.single().bounds.toList())
    }

    @Test
    fun `rle round trip preserves exact mask`() {
        val source = BubbleSegmentationDecoder.Mask(ByteArray(12) { if (it in 1..3 || it in 8..10) 1 else 0 }, 4, 3, intArrayOf(0, 0, 4, 3), .8f)
        val encoded = BubbleMaskRle.encode(source)
        assertTrue(source.pixels.contentEquals(encoded.decode()))
    }

    @Test
    fun `rle overlap uses exact interior rather than bounding rectangle`() {
        // A concave mask fills only the two corners inside its 4x2 bound.
        val rle = BubbleMaskRle(width = 4, height = 2, bounds = listOf(0, 0, 4, 2), runs = listOf(0, 1, 7, 1), score = .9f)

        assertEquals(1, rle.overlapPixels(0, 0, 2, 2))
        assertEquals(0, rle.overlapPixels(1, 0, 3, 2))
        assertEquals(2, rle.overlapPixels(0, 0, 4, 2))
    }

    @Test
    fun `rle rasterizes onto caller union buffer`() {
        val rle = BubbleMaskRle(width = 3, height = 2, bounds = listOf(0, 0, 3, 2), runs = listOf(1, 2, 4, 1), score = .9f)
        val union = ByteArray(6) { if (it == 0) 1 else 0 }

        rle.rasterizeOnto(union)

        assertTrue(union.contentEquals(byteArrayOf(1, 1, 1, 0, 1, 0)))
    }
}
