package eu.kanade.translation.engines.inpainting.litert

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Binding regression tests derived from the packaged artifacts' actual
 * metadata (binary dissection + SignatureDef string tables):
 *
 * AOT (aot_512_regen_gap_fp16.tflite): subgraph input 0 = mask
 * [1,512,512,1] (tensor serving_default_mask:0), subgraph input 1 = image
 * [1,512,512,3] (tensor serving_default_image:0); signature `serving_default`
 * exposes aliases image/mask and output inpainted.
 *
 * LaMa (manga_lama_fused_fp16.tflite): signature `serving_default` inputs
 * input_image/input_mask, output output_0 (graph tensor PartitionedCall:0).
 *
 * The engine must resolve names against SHAPES and fail closed on any
 * mismatch — index-based binding would silently invert the AOT tensors.
 */
class LiteRtSignatureBindingTest {

    private val imageShape = listOf(1, 512, 512, 3)
    private val maskShape = listOf(1, 512, 512, 1)

    private val aot = AotLiteRtTensorAdapter()
    private val lama = LamaLiteRtTensorAdapter()

    /** Lookup modeled on AOT's real signature: image→[1,512,512,3], mask→[1,512,512,1]. */
    private val aotSignatureLookup: (String) -> LiteRtTensorInfo? = { name ->
        when (name) {
            "image", "serving_default_image:0" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
            "mask", "serving_default_mask:0" -> LiteRtTensorInfo(isFloat = true, dimensions = maskShape)
            "inpainted", "PartitionedCall:0" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
            else -> null
        }
    }

    @Test
    fun `aot mask-first subgraph layout does not invert name resolution`() {
        // Reproduces the binary dissection: even though the mask tensor is
        // subgraph input 0, resolving by signature name must return the 3-channel
        // tensor for the image contract and the 1-channel tensor for the mask.
        val image = LiteRtSignatureBinding.resolveName(aot.imageInputNameCandidates, imageShape, aotSignatureLookup)
        val mask = LiteRtSignatureBinding.resolveName(aot.maskInputNameCandidates, maskShape, aotSignatureLookup)
        image shouldBe "image"
        mask shouldBe "mask"
    }

    @Test
    fun `aot image contract rejects the physically-first mask tensor shape`() {
        // If a build exposes ONLY the mask-first names, the image contract must
        // NOT bind to the 1-channel tensor — it fails closed with null.
        val maskOnlyLookup: (String) -> LiteRtTensorInfo? = { name ->
            when (name) {
                "image", "serving_default_image:0" -> LiteRtTensorInfo(isFloat = true, dimensions = maskShape)
                else -> null
            }
        }
        LiteRtSignatureBinding.resolveName(aot.imageInputNameCandidates, imageShape, maskOnlyLookup) shouldBe null
    }

    @Test
    fun `tensor-name fallback resolves when signature aliases are absent`() {
        val tensorNameOnlyLookup: (String) -> LiteRtTensorInfo? = { name ->
            when (name) {
                "serving_default_image:0" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
                "serving_default_mask:0" -> LiteRtTensorInfo(isFloat = true, dimensions = maskShape)
                "PartitionedCall:0" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
                else -> null
            }
        }
        LiteRtSignatureBinding.resolveName(aot.imageInputNameCandidates, imageShape, tensorNameOnlyLookup) shouldBe "serving_default_image:0"
        LiteRtSignatureBinding.resolveName(aot.maskInputNameCandidates, maskShape, tensorNameOnlyLookup) shouldBe "serving_default_mask:0"
        LiteRtSignatureBinding.resolveName(aot.outputNameCandidates, imageShape, tensorNameOnlyLookup) shouldBe "PartitionedCall:0"
    }

    @Test
    fun `non-float candidate is rejected`() {
        val intLookup: (String) -> LiteRtTensorInfo? = { name ->
            when (name) {
                "image" -> LiteRtTensorInfo(isFloat = false, dimensions = imageShape)
                "mask" -> LiteRtTensorInfo(isFloat = true, dimensions = maskShape)
                else -> null
            }
        }
        LiteRtSignatureBinding.resolveName(aot.imageInputNameCandidates, imageShape, intLookup) shouldBe null
        LiteRtSignatureBinding.resolveName(aot.maskInputNameCandidates, maskShape, intLookup) shouldBe "mask"
    }

    @Test
    fun `shape mismatch falls through to later candidate`() {
        val invertedLookup: (String) -> LiteRtTensorInfo? = { name ->
            when (name) {
                // "image" carries the WRONG (mask) shape in this hypothetical
                // package; the tensor-name spelling carries the right one.
                "image" -> LiteRtTensorInfo(isFloat = true, dimensions = maskShape)
                "serving_default_image:0" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
                else -> null
            }
        }
        LiteRtSignatureBinding.resolveName(aot.imageInputNameCandidates, imageShape, invertedLookup) shouldBe "serving_default_image:0"
    }

    @Test
    fun `unknown names fail closed with null`() {
        LiteRtSignatureBinding.resolveName(aot.imageInputNameCandidates, imageShape) { null } shouldBe null
        LiteRtSignatureBinding.resolveName(aot.maskInputNameCandidates, maskShape) { null } shouldBe null
        LiteRtSignatureBinding.resolveName(aot.outputNameCandidates, imageShape) { null } shouldBe null
    }

    @Test
    fun `lama signature contract resolves with its own names`() {
        // Real LaMa SignatureDef: inputs input_image/input_mask, output output_0.
        val lamaLookup: (String) -> LiteRtTensorInfo? = { name ->
            when (name) {
                "input_image" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
                "input_mask" -> LiteRtTensorInfo(isFloat = true, dimensions = maskShape)
                "output_0" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
                else -> null
            }
        }
        LiteRtSignatureBinding.resolveName(lama.imageInputNameCandidates, imageShape, lamaLookup) shouldBe "input_image"
        LiteRtSignatureBinding.resolveName(lama.maskInputNameCandidates, maskShape, lamaLookup) shouldBe "input_mask"
        LiteRtSignatureBinding.resolveName(lama.outputNameCandidates, imageShape, lamaLookup) shouldBe "output_0"
    }

    @Test
    fun `adapter candidate lists match the packaged artifacts`() {
        aot.imageInputNameCandidates shouldBe listOf("image", "serving_default_image:0")
        aot.maskInputNameCandidates shouldBe listOf("mask", "serving_default_mask:0")
        aot.outputNameCandidates shouldBe listOf("inpainted", "PartitionedCall:0")
        lama.imageInputNameCandidates shouldBe listOf("input_image", "serving_default_input_image:0")
        lama.maskInputNameCandidates shouldBe listOf("input_mask", "serving_default_input_mask:0")
        lama.outputNameCandidates shouldBe listOf("output_0", "PartitionedCall:0")
    }

    @Test
    fun `cross-model lookup does not satisfy the wrong adapter`() {
        // The AOT lookup must NOT satisfy LaMa's candidate names and vice versa.
        val lamaImage = LiteRtSignatureBinding.resolveName(lama.imageInputNameCandidates, imageShape, aotSignatureLookup)
        val aotImage = LiteRtSignatureBinding.resolveName(aot.imageInputNameCandidates, imageShape, { name ->
            when (name) {
                "input_image" -> LiteRtTensorInfo(isFloat = true, dimensions = imageShape)
                else -> null
            }
        })
        lamaImage shouldBe null
        aotImage shouldBe null
    }
}
