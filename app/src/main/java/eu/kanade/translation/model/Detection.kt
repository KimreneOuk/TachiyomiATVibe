package eu.kanade.translation.model

data class Detection(
    val bbox: IntArray,
    val label: Int,
    val score: Float,
    val className: String,
)
