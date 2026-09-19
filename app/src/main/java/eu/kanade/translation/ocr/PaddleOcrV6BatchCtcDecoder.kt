package eu.kanade.translation.ocr

import java.nio.FloatBuffer

/** Decodes each [B,T,C] row independently without changing CTC semantics. */
internal object PaddleOcrV6BatchCtcDecoder {

    fun decode(
        logits: FloatBuffer,
        shape: LongArray,
        dictionary: List<String>,
    ): List<Pair<String, Float>> {
        require(shape.size == 3) { "Paddle OCR output must be [B,T,C], got ${shape.contentToString()}" }
        val batchSize = shape[0].toIntChecked("batch")
        val timeSteps = shape[1].toIntChecked("time steps")
        val classCount = shape[2].toIntChecked("classes")
        val rowElements = timeSteps.toLong() * classCount
        val required = batchSize.toLong() * rowElements
        require(required <= logits.limit().toLong()) {
            "Paddle OCR output logits=${logits.limit()} expected=$required"
        }

        return ArrayList<Pair<String, Float>>(batchSize).apply {
            for (batchIndex in 0 until batchSize) {
                val rowStart = (batchIndex.toLong() * rowElements).toInt()
                val row = logits.duplicate().apply {
                    position(rowStart)
                    limit(rowStart + rowElements.toInt())
                }.slice()
                val (indices, maxValues) = PaddleCtcDecoder.argmaxWithProbs(
                    row,
                    timeSteps = timeSteps,
                    classCount = classCount,
                )
                add(PaddleCtcDecoder.decodeWithConf(indices, maxValues, dictionary))
            }
        }
    }

    private fun Long.toIntChecked(label: String): Int {
        require(this in 1..Int.MAX_VALUE) { "Paddle OCR $label=$this is invalid" }
        return toInt()
    }
}
