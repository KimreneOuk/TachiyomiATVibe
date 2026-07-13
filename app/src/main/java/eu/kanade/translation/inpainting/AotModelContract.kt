package eu.kanade.translation.inpainting

/** Pure validation for the two independently loaded AOT graph contracts. */
internal object AotModelContract {

    enum class Kind { FIXED_512, DYNAMIC }

    data class Contract(
        val inputNames: Set<String>,
        val outputCount: Int,
        val imageShape: LongArray,
        val maskShape: LongArray,
        val outputShape: LongArray,
    )

    fun validate(kind: Kind, contract: Contract) {
        require(contract.inputNames == setOf("image", "mask")) {
            "AOT inputs must be exactly [image, mask], actual=${contract.inputNames}"
        }
        require(contract.outputCount == 1) {
            "AOT must expose exactly one output, actual=${contract.outputCount}"
        }
        val dynamicBatch = kind == Kind.DYNAMIC
        requireNchw(contract.imageShape, channels = 3, name = "image", dynamicBatch = dynamicBatch)
        requireNchw(contract.maskShape, channels = 1, name = "mask", dynamicBatch = dynamicBatch)
        requireNchw(contract.outputShape, channels = 3, name = "output", dynamicBatch = dynamicBatch)
        when (kind) {
            Kind.FIXED_512 -> {
                requireSpatial(contract.imageShape, 512, 512, "image")
                requireSpatial(contract.maskShape, 512, 512, "mask")
                requireSpatial(contract.outputShape, 512, 512, "output")
            }
            Kind.DYNAMIC -> {
                requireDynamicSpatial(contract.imageShape, "image")
                requireDynamicSpatial(contract.maskShape, "mask")
                requireDynamicSpatial(contract.outputShape, "output")
            }
        }
    }

    private fun requireNchw(
        shape: LongArray,
        channels: Long,
        name: String,
        dynamicBatch: Boolean,
    ) {
        val batchMatches = if (dynamicBatch) shape.getOrNull(0)?.let { it == 1L || it <= 0L } == true else shape.getOrNull(0) == 1L
        require(shape.size == 4 && batchMatches && shape[1] == channels) {
            "$name must be NCHW [${if (dynamicBatch) "B" else "1"},$channels,H,W], actual=${shape.contentToString()}"
        }
    }

    private fun requireSpatial(shape: LongArray, height: Long, width: Long, name: String) {
        require(shape[2] == height && shape[3] == width) {
            "$name must be fixed ${height}x$width, actual=${shape.contentToString()}"
        }
    }

    private fun requireDynamicSpatial(shape: LongArray, name: String) {
        require(shape[2] <= 0L && shape[3] <= 0L) {
            "$name must have dynamic H/W, actual=${shape.contentToString()}"
        }
    }
}
