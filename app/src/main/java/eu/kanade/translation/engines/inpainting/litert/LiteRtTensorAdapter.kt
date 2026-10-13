package eu.kanade.translation.engines.inpainting.litert

import tachiyomi.domain.translation.NeuralInpaintModel
import kotlin.math.roundToInt

/**
 * Per-model tensor contract for the shared LiteRT inpainting runtime.
 *
 * The runtime (session creation, GPU CompiledModel, pad/composite) is model
 * agnostic; each selectable neural model supplies its own encode/decode math
 * through this adapter. Pure Kotlin — unit-testable on the JVM.
 */
internal interface LiteRtTensorAdapter {
    /** Packed assets path of the model this adapter serves. */
    val modelAssetPath: String

    /**
     * Candidate SIGNATURE-LEVEL input names, in resolution order, validated
     * against the exact float NHWC shape before use. Binding happens BY NAME
     * through the signature (never by buffer index — the AOT flatbuffer lists
     * mask as subgraph input 0); the tensor-name spellings are fallbacks for
     * packages that expose graph tensor names instead.
     */
    val imageInputNameCandidates: List<String>
    val maskInputNameCandidates: List<String>
    val outputNameCandidates: List<String>

    /** Honest label for logs/stamps ("Manga LaMa FP16" / "AOT FP16"). */
    val logLabel: String

    /**
     * Encodes ONE padded pixel + its binary mask value ({0,1}) into the NHWC
     * input arrays. [imageOutIndex] is the first of the 3 RGB slots.
     */
    fun encodePixel(pixel: Int, maskValue: Float, imageOut: FloatArray, imageOutIndex: Int, maskOut: FloatArray, maskOutIndex: Int)

    /** Decodes one NHWC output RGB triple into a 0..255 ARGB pixel. */
    fun decodePixel(r: Float, g: Float, b: Float): Int
}

/** Manga LaMa fused FP16: [0,1] RGB, pre-multiplied by (1-mask); output [0,1]. */
internal class LamaLiteRtTensorAdapter : LiteRtTensorAdapter {
    override val modelAssetPath = "models/inpainting/manga_lama_fused_fp16.tflite"
    // SignatureDef (verified in the packaged flatbuffer): serving_default →
    // inputs input_image/input_mask, output output_0 (graph tensor
    // PartitionedCall:0).
    override val imageInputNameCandidates = listOf("input_image", "serving_default_input_image:0")
    override val maskInputNameCandidates = listOf("input_mask", "serving_default_input_mask:0")
    override val outputNameCandidates = listOf("output_0", "PartitionedCall:0")
    override val logLabel = "Manga LaMa FP16"

    override fun encodePixel(pixel: Int, maskValue: Float, imageOut: FloatArray, imageOutIndex: Int, maskOut: FloatArray, maskOutIndex: Int) {
        val keep = 1.0f - maskValue
        imageOut[imageOutIndex] = ((pixel shr 16) and 0xFF) / 255.0f * keep
        imageOut[imageOutIndex + 1] = ((pixel shr 8) and 0xFF) / 255.0f * keep
        imageOut[imageOutIndex + 2] = (pixel and 0xFF) / 255.0f * keep
        maskOut[maskOutIndex] = maskValue
    }

    override fun decodePixel(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0.0f, 1.0f) * 255.0f).toInt()
        val gi = (g.coerceIn(0.0f, 1.0f) * 255.0f).toInt()
        val bi = (b.coerceIn(0.0f, 1.0f) * 255.0f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

/**
 * Optimized AOT 512 FP16 (aot-512-regen-gap, LiteRT GPU): SIGNED normalized
 * [-1,1] contract from the upstream manga-image-translator inpainting_lama_mpe
 * inference — encode px/127.5 - 1 FIRST, THEN multiply by (1-mask) so masked
 * holes feed exactly 0.0f (the model's mean gray, IEEE −0.0 accepted), never
 * -1. Decode (v+1)*127.5, round, clamp. Output range [-1,1].
 *
 * Physical layout (binary dissection): subgraph input 0 is the MASK
 * [1,512,512,1] and input 1 is the IMAGE [1,512,512,3] — index-ordered
 * buffers would be inverted. The serving_default signature exposes the
 * aliases image/mask and output inpainted (graph tensor PartitionedCall:0);
 * signature names come first in the candidate lists.
 */
internal class AotLiteRtTensorAdapter : LiteRtTensorAdapter {
    override val modelAssetPath = "models/inpainting/aot_512_regen_gap_fp16.tflite"
    override val imageInputNameCandidates = listOf("image", "serving_default_image:0")
    override val maskInputNameCandidates = listOf("mask", "serving_default_mask:0")
    override val outputNameCandidates = listOf("inpainted", "PartitionedCall:0")
    override val logLabel = "AOT FP16"

    override fun encodePixel(pixel: Int, maskValue: Float, imageOut: FloatArray, imageOutIndex: Int, maskOut: FloatArray, maskOutIndex: Int) {
        val keep = 1.0f - maskValue
        imageOut[imageOutIndex] = (((pixel shr 16) and 0xFF) / 127.5f - 1.0f) * keep
        imageOut[imageOutIndex + 1] = (((pixel shr 8) and 0xFF) / 127.5f - 1.0f) * keep
        imageOut[imageOutIndex + 2] = ((pixel and 0xFF) / 127.5f - 1.0f) * keep
        maskOut[maskOutIndex] = maskValue
    }

    override fun decodePixel(r: Float, g: Float, b: Float): Int {
        val ri = decodeChannel(r)
        val gi = decodeChannel(g)
        val bi = decodeChannel(b)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun decodeChannel(v: Float): Int = ((v + 1.0f) * 127.5f).roundToInt().coerceIn(0, 255)
}

internal fun tensorAdapterFor(model: NeuralInpaintModel): LiteRtTensorAdapter = when (model) {
    NeuralInpaintModel.LAMA_LITERT_GPU -> LamaLiteRtTensorAdapter()
    NeuralInpaintModel.AOT_GAN -> AotLiteRtTensorAdapter()
}
