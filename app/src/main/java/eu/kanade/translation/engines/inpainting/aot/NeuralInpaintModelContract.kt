package eu.kanade.translation.engines.inpainting.aot

internal interface NeuralInpaintModelContract {
    val modelId: String
    val isSingleInputTensor: Boolean
    val inputTensorName: String
}
