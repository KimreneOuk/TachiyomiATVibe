package eu.kanade.translation.ocr

import java.nio.FloatBuffer

object PaddleCtcDecoder {
    private const val BLANK_INDEX = 0

    fun decode(indices: IntArray, dictionary: List<String>): String {
        val spaceIndex = dictionary.size + 1
        val builder = StringBuilder()
        var previous = -1
        for (index in indices) {
            if (index != previous && index != BLANK_INDEX) {
                when (index) {
                    in 1..dictionary.size -> builder.append(dictionary[index - 1])
                    spaceIndex -> builder.append(' ')
                }
            }
            previous = index
        }
        return builder.toString().trim()
    }

    fun argmaxIndices(
        logits: FloatBuffer,
        timeSteps: Int,
        classCount: Int,
    ): IntArray {
        val indices = IntArray(timeSteps)
        for (timeStep in 0 until timeSteps) {
            val offset = timeStep * classCount
            var maxIndex = 0
            var maxValue = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until classCount) {
                val value = logits.get(offset + classIndex)
                if (value > maxValue) {
                    maxValue = value
                    maxIndex = classIndex
                }
            }
            indices[timeStep] = maxIndex
        }
        return indices
    }
}
