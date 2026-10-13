package eu.kanade.translation.engines.inpainting.litert

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Contract tests for the per-model LiteRT tensor adapters.
 *
 * AOT oracle (from the conversion parity brief): white(255)@mask0 → +1.0;
 * black(0)@mask0 → -1.0; any pixel@mask1 → 0.0 (encode FIRST px/127.5-1, THEN
 * *(1-mask)); decode (v+1)*127.5 round+clamp. The ORDER property is pinned by
 * the half-mask case, where wrong order gives a different number.
 */
class LiteRtTensorAdapterTest {

    private val aot = AotLiteRtTensorAdapter()
    private val lama = LamaLiteRtTensorAdapter()

    private fun aotEncodeChannels(pixel: Int, mask: Float): Triple<Float, Float, Float> {
        val imageOut = FloatArray(3)
        val maskOut = FloatArray(1)
        aot.encodePixel(pixel, mask, imageOut, 0, maskOut, 0)
        return Triple(imageOut[0], imageOut[1], imageOut[2]).also {
            maskOut[0] shouldBe mask
        }
    }

    @Test
    fun `aot white keep encodes to plus one`() {
        val (r, g, b) = aotEncodeChannels(0xFFFFFFFF.toInt(), 0.0f)
        r shouldBe 1.0f
        g shouldBe 1.0f
        b shouldBe 1.0f
    }

    @Test
    fun `aot black keep encodes to minus one`() {
        val (r, g, b) = aotEncodeChannels(0xFF000000.toInt(), 0.0f)
        r shouldBe -1.0f
        g shouldBe -1.0f
        b shouldBe -1.0f
    }

    @Test
    fun `aot masked hole feeds exactly zero regardless of pixel color`() {
        // (-1.0f) * 0.0f is IEEE -0.0 — numerically identical to 0.0f and
        // explicitly accepted by the conversion contract ("incl. -0.0f"), so
        // the assertion is on magnitude.
        listOf(
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF123456.toInt(),
        ).forEach { pixel ->
            val (r, g, b) = aotEncodeChannels(pixel, 1.0f)
            kotlin.math.abs(r) shouldBe 0.0f
            kotlin.math.abs(g) shouldBe 0.0f
            kotlin.math.abs(b) shouldBe 0.0f
        }
    }

    @Test
    fun `aot encode order is normalize then mask not mask then normalize`() {
        // px=255, mask=0.5: correct (encode→mask) = (255/127.5-1)*0.5 = 0.5;
        // wrong order would give (255*0.5)/127.5-1 = 0.0.
        val (r, _, _) = aotEncodeChannels(0xFFFFFFFF.toInt(), 0.5f)
        r shouldBe 0.5f
    }

    @Test
    fun `aot mid gray encodes near zero`() {
        // 128/127.5-1 = 1/255 ≈ 0.00392; 127/127.5-1 = -1/255 ≈ -0.00392.
        val (rUp, _, _) = aotEncodeChannels(0xFF808080.toInt(), 0.0f)
        rUp shouldBe (128.0f / 127.5f - 1.0f)
        val (rDown, _, _) = aotEncodeChannels(0xFF7F7F7F.toInt(), 0.0f)
        rDown shouldBe (127.0f / 127.5f - 1.0f)
    }

    @Test
    fun `aot decode signed range with rounding and clamping`() {
        // +1 → 255; -1 → 0; 0 → 127.5 rounds to 128 (spec: round).
        aot.decodePixel(1.0f, -1.0f, 0.0f) shouldBe 0xFFFF0080.toInt()
        // Out-of-range clamps: 1.5 → 255, -1.5 → 0.
        aot.decodePixel(1.5f, -1.5f, 0.0f) shouldBe 0xFFFF0080.toInt()
        aot.decodePixel(-1.5f, 1.5f, 0.0f) shouldBe 0xFF00FF80.toInt()
        // Round-trip: encode(255 keep) = +1, decode(+1) = 255.
        val imageOut = FloatArray(3)
        aot.encodePixel(0xFFFFFFFF.toInt(), 0.0f, imageOut, 0, FloatArray(1), 0)
        val decoded = aot.decodePixel(imageOut[0], imageOut[1], imageOut[2])
        decoded shouldBe 0xFFFFFFFF.toInt()
    }

    @Test
    fun `lama adapter keeps its zero-to-one contract unchanged`() {
        val imageOut = FloatArray(3)
        val maskOut = FloatArray(1)
        lama.encodePixel(0xFFFFFFFF.toInt(), 0.0f, imageOut, 0, maskOut, 0)
        imageOut[0] shouldBe 1.0f
        lama.encodePixel(0xFF000000.toInt(), 0.0f, imageOut, 0, maskOut, 0)
        imageOut[0] shouldBe 0.0f
        lama.encodePixel(0xFF808080.toInt(), 1.0f, imageOut, 0, maskOut, 0)
        imageOut[0] shouldBe 0.0f
        // Decode clamps [0,1] → 0..255.
        lama.decodePixel(1.0f, 0.0f, 0.5f) shouldBe 0xFFFF007F.toInt()
    }

    @Test
    fun `factory binds adapters per selectable model with distinct tflite assets`() {
        tensorAdapterFor(tachiyomi.domain.translation.NeuralInpaintModel.LAMA_LITERT_GPU)::class shouldBe LamaLiteRtTensorAdapter::class
        tensorAdapterFor(tachiyomi.domain.translation.NeuralInpaintModel.AOT_GAN)::class shouldBe AotLiteRtTensorAdapter::class
        val aot = tensorAdapterFor(tachiyomi.domain.translation.NeuralInpaintModel.AOT_GAN)
        aot.modelAssetPath shouldBe "models/inpainting/aot_512_regen_gap_fp16.tflite"
        aot.imageInputNameCandidates.first() shouldBe "image"
        aot.maskInputNameCandidates.first() shouldBe "mask"
        aot.outputNameCandidates.first() shouldBe "inpainted"
        aot.modelAssetPath.endsWith(".onnx") shouldBe false
    }

}
