package eu.kanade.translation.split

import eu.kanade.translation.model.TranslationBlock

/**
 * TachiyomiAT: pure helpers for the split-tall-image translation merge.
 *
 * A tall page downloaded with `split_tall_images` enabled is stored by
 * `ImageUtil.splitTallImage` as `NNN__001.jpg`, `NNN__002.jpg`, … — consecutive
 * vertical JPEG slices of one original page (`NNN` = zero-padded page number,
 * `MMM` = 1-based slice index). Translating each slice independently breaks text
 * that spans a slice boundary and re-translates the duplicated overlap band, so
 * the slices must be merged into one image for detect→OCR→inpaint→render and the
 * result back-mapped to each slice.
 *
 * This object holds ONLY the coordinate bookkeeping — kept pure (no Bitmap /
 * Canvas / ONNX) so it is unit-testable. That bookkeeping (slice grouping + the
 * y-offset / boundary-clip back-map) is the highest-risk part of the feature, so
 * it is verified independently of the (device-bound) pipeline integration.
 */
object SplitTallPageKey {

    /** One parsed slice of a split-tall page: [prefix] is "001", [index] is 1-based. */
    data class Part(val pageKey: String, val prefix: String, val index: Int)

    /**
     * Sibling slices of one original page, ordered top-to-bottom ([parts][0] is
     * the `__001` top slice). [isSplit] is true only for genuine multi-slice
     * groups; a lone `__001` with no siblings (or any non-split page) is a
     * single-element group with [isSplit] == false.
     */
    data class Group(val parts: List<Part>) {
        val representative: String get() = parts.first().pageKey
        val isSplit: Boolean get() = parts.size > 1
    }

    // "001__002" with an optional image extension. The prefix must be digits
    // (the zero-padded page number) and the suffix the 1-based slice index.
    private val SPLIT_RE =
        Regex("""^(\d+)__(\d+)(?:\.(?:jpg|jpeg|png|webp))?$""", RegexOption.IGNORE_CASE)

    /** Parses [pageKey] into a [Part], or null if it is not a split-tall slice. */
    fun parse(pageKey: String): Part? {
        val m = SPLIT_RE.matchEntire(pageKey) ?: return null
        return Part(pageKey, m.groupValues[1], m.groupValues[2].toInt())
    }

    fun isSplitPart(pageKey: String): Boolean = parse(pageKey) != null

    /**
     * Groups an ordered page list into split-tall [Group]s, preserving first-seen
     * order. Slices sharing a prefix are merged into one group and sorted by
     * ascending index (top slice first); every non-split page becomes its own
     * single-element group so callers can treat all pages uniformly. Callers that
     * only care about real splits filter [Group.isSplit].
     */
    fun group(orderedPageKeys: List<String>): List<Group> {
        // LinkedHashMap preserves first-seen group order. Non-split pages use a
        // sentinel prefix so two unrelated pages never collapse into one group.
        val byPrefix = LinkedHashMap<String, MutableList<Part>>()
        for (key in orderedPageKeys) {
            val part = parse(key)
            if (part == null) {
                byPrefix.getOrPut("\u0000$key") { mutableListOf() }.add(Part(key, key, 0))
            } else {
                byPrefix.getOrPut(part.prefix) { mutableListOf() }.add(part)
            }
        }
        return byPrefix.values.map { parts ->
            // Sort genuine split groups by slice index; singleton sentinels stay as-is.
            val sorted = if (parts.first().prefix == parts.first().pageKey) parts else parts.sortedBy { it.index }
            Group(sorted)
        }
    }

    /**
     * Back-maps a [block] detected on the MERGED image into one slice's local
     * coordinates. [sliceYTop] is the slice's top y-offset within the merged
     * image; [sliceHeight] is the slice's pixel height. The block's y/height are
     * translated by `-sliceYTop` and clipped to the slice bounds; a block lying
     * entirely outside the slice returns null (it belongs to a different slice).
     * The parent rect (bubble group) is transformed + clipped the same way so the
     * renderer's neighbour-aware layout stays within the slice. Text, colors and
     * glyph metrics are preserved verbatim.
     */
    fun backMapBlock(block: TranslationBlock, sliceYTop: Float, sliceHeight: Float): TranslationBlock? {
        val sliceBottom = sliceYTop + sliceHeight
        val interTop = maxOf(block.y, sliceYTop)
        val interBottom = minOf(block.y + block.height, sliceBottom)
        if (interTop >= interBottom) return null

        // The parent rect (bubble group) follows the same transform + clip so the
        // renderer's neighbour-aware layout stays within the slice. parentY/
        // parentHeight are val on the model, so the result is built via copy().
        var parentY = block.parentY
        var parentHeight = block.parentHeight
        if (block.parentHeight > 0f) {
            val pInterTop = maxOf(block.parentY, sliceYTop)
            val pInterBottom = minOf(block.parentY + block.parentHeight, sliceBottom)
            if (pInterBottom > pInterTop) {
                parentY = pInterTop - sliceYTop
                parentHeight = pInterBottom - pInterTop
            }
        }
        return block.copy(
            y = interTop - sliceYTop,
            height = interBottom - interTop,
            parentY = parentY,
            parentHeight = parentHeight,
        )
    }
}
