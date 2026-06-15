package eu.kanade.translation.detection

data class Detection(
    val bbox: IntArray,
    val label: Int,
    val score: Float,
    val className: String,
)
