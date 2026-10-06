package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderOverride
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderResolution

/** Explicitly selects the existing detector session route without changing its production default. */
internal sealed interface PaddleOcrDetProviderSelection {
    data class Explicit(val configuration: PaddleOcrProviderOverride) : PaddleOcrDetProviderSelection

    data class Resolved(val resolution: PaddleOcrProviderResolution) : PaddleOcrDetProviderSelection

    data object Automatic : PaddleOcrDetProviderSelection
}

internal fun selectPaddleOcrDetProvider(
    configuration: PaddleOcrProviderOverride?,
    resolution: PaddleOcrProviderResolution?,
): PaddleOcrDetProviderSelection = when {
    configuration != null -> PaddleOcrDetProviderSelection.Explicit(configuration)
    resolution != null -> PaddleOcrDetProviderSelection.Resolved(resolution)
    else -> PaddleOcrDetProviderSelection.Automatic
}
