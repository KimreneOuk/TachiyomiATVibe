package eu.kanade.translation.model

data class Detection(
    override val bbox: IntArray,
    override val label: Int,
    override val score: Float,
    override val className: String,
) : DetectionView {
    override val left: Int get() = bbox[0]
    override val top: Int get() = bbox[1]
    override val right: Int get() = bbox[2]
    override val bottom: Int get() = bbox[3]

    override fun equals(other: Any?): Boolean = detectionValueEquals(this, other)

    override fun hashCode(): Int = detectionValueHashCode(this)

    override fun toString(): String = detectionValueToString(this)
}
