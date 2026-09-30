package eu.kanade.translation.pipeline.adaptive

import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs
import kotlin.math.ceil

/** Work denominators already available at the existing trace-stage boundaries. */
object DeviceStageNormalization {
    fun sourcePixels(width: Int, height: Int): Long =
        if (width <= 0 || height <= 0) 0L else width.toLong() * height.toLong()

    fun textVolume(blocks: Iterable<TranslationBlock>): Long =
        blocks.sumOf { it.text.length.toLong() }

    /** Sum the recognized text-region box areas; invalid boxes contribute zero. */
    fun regionArea(blocks: Iterable<TranslationBlock>): Long = blocks.sumOf { block ->
        val area = abs(block.width.toDouble() * block.height.toDouble())
        if (area.isFinite() && area > 0.0) ceil(area).toLong() else 0L
    }
}
