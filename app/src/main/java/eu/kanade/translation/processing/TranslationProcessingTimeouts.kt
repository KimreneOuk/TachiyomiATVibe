package eu.kanade.translation.processing

/** Time limits for page operations owned by [TranslationProcessor]. */
internal object TranslationProcessingTimeouts {
    const val SINGLE_PAGE_TIMEOUT_MS = 120_000L
    const val ONNX_PHASE_TIMEOUT_MS = 90_000L
    const val NATIVE_STALL_THRESHOLD_MS = ONNX_PHASE_TIMEOUT_MS

    /**
     * Extra single-page budget when colorization is on: the 20-step diffusion
     * pass can exceed the whole translate+inpaint budget on CPU demotion.
     */
    const val COLORING_PHASE_TIMEOUT_MS = 240_000L
}
