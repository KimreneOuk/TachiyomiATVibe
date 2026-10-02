package eu.kanade.translation.engines.vision.segmentation

import eu.kanade.translation.model.BubbleMaskRle

fun BubbleMaskRle.Companion.encode(mask: BubbleSegmentationDecoder.Mask): BubbleMaskRle {
    val runs = ArrayList<Int>()
    var index = 0
    while (index < mask.pixels.size) {
        if (mask.pixels[index] == 0.toByte()) {
            index++
            continue
        }
        val start = index
        while (index < mask.pixels.size && mask.pixels[index] != 0.toByte()) index++
        runs += start
        runs += index - start
    }
    return BubbleMaskRle(mask.width, mask.height, mask.bounds.toList(), runs, mask.score)
}
