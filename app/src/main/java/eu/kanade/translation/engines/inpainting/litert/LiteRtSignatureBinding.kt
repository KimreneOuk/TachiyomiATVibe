package eu.kanade.translation.engines.inpainting.litert

/**
 * Signature-level tensor metadata as seen by the resolver: float-ness plus
 * NHWC dimensions (null when the model exposes dynamic/unknown layout).
 * Pure value type — JVM unit-testable.
 */
internal data class LiteRtTensorInfo(val isFloat: Boolean, val dimensions: List<Int>?)

/**
 * Signature-level tensor resolution for the shared LiteRT inpainting runtime.
 *
 * WHY THIS EXISTS: the AOT flatbuffer lists its physical subgraph inputs
 * mask-first (tensor index 0 = mask [1,512,512,1], index 1 = image
 * [1,512,512,3]), while its `serving_default` SIGNATURE exposes the aliases
 * "image"/"mask" and output "inpainted". LaMa's signature uses
 * "input_image"/"input_mask"/"output_0". Index-ordered buffers would silently
 * bind the wrong tensors for AOT, so binding happens ONLY through
 * signature-level names validated against the exact float shape — never by
 * index, never best-effort.
 *
 * The lookup returns null when a candidate name does not exist in the model's
 * signature (the engine maps LiteRT lookup failures to null); a non-null
 * result must be float AND match [expectedShape] exactly, otherwise the
 * candidate is rejected and resolution continues. Null overall = FAIL CLOSED
 * (caller must not initialize the model).
 */
internal object LiteRtSignatureBinding {

    /**
     * Resolves the FIRST candidate whose tensor is float with the exact
     * [expectedShape]. Returns null when no candidate validates.
     */
    fun resolveName(
        candidates: List<String>,
        expectedShape: List<Int>,
        lookup: (String) -> LiteRtTensorInfo?,
    ): String? {
        for (name in candidates) {
            val info = lookup(name) ?: continue
            if (!info.isFloat) continue
            if (info.dimensions != expectedShape) continue
            return name
        }
        return null
    }
}
