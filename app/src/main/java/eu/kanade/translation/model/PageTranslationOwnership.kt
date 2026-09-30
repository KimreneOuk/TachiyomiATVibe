package eu.kanade.translation.model

import java.security.MessageDigest

/** Returns a copy that shares no mutable page, block, mask, or detection state. */
fun PageTranslation.detachedCopy(): PageTranslation = copy(
    blocks = blocks.map { it.detachedCopy() }.toMutableList(),
    inpaintMaskBoxes = inpaintMaskBoxes.map { it.copy() },
).also { detached ->
    detached.cleanedBitmap = null
    detached.allTextDetections = allTextDetections.map { detection ->
        detection.copy(bbox = detection.bbox.copyOf())
    }
    detached.attemptCount = attemptCount
    detached.attemptCharged = attemptCharged
}

/** Published values are deeply immutable, so the safest detached copy is identity. */
fun PublishedPageTranslation.detachedCopy(): PublishedPageTranslation = this

fun TranslationBlock.detachedCopy(): TranslationBlock = copy(
    segmentationMask = segmentationMask?.copy(
        bounds = segmentationMask.bounds.toList(),
        runs = segmentationMask.runs.toList(),
    ),
)

/** Stable across processes and independent of data-class/list hash implementations. */
fun TranslationBlockView.stableFingerprint(): String =
    if (this is PublishedTranslationBlock) cachedStableFingerprint else calculateStableFingerprint(this)

internal fun calculateStableFingerprint(block: TranslationBlockView): String {
    val canonical = buildString {
        appendField(block.text)
        appendField(block.translation)
        appendField(block.width.toRawBits())
        appendField(block.height.toRawBits())
        appendField(block.x.toRawBits())
        appendField(block.y.toRawBits())
        appendField(block.symHeight.toRawBits())
        appendField(block.symWidth.toRawBits())
        appendField(block.angle.toRawBits())
        appendField(block.label)
        appendField(block.score.toRawBits())
        appendField(block.parentX.toRawBits())
        appendField(block.parentY.toRawBits())
        appendField(block.parentWidth.toRawBits())
        appendField(block.parentHeight.toRawBits())
        appendField(block.textColor)
        appendField(block.strokeColor)
        appendField(block.strokeWidth.toRawBits())
        appendField(block.direction)
        appendField(block.panelIndex)
        appendField(block.panelAssignment)
        appendField(block.panelContainment.toRawBits())
        appendField(block.bubbleIndex)
        appendField(block.userEditedAt)
        block.segmentationMask?.let { mask ->
            appendField(mask.width)
            appendField(mask.height)
            mask.bounds.forEach(::appendField)
            mask.runs.forEach(::appendField)
            appendField(mask.score.toRawBits())
        } ?: appendField(null)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun PageTranslationView.blockFingerprints(): List<String> = blocks.map { it.stableFingerprint() }

private fun StringBuilder.appendField(value: Any?) {
    val text = value?.toString() ?: "<null>"
    append(text.length).append(':').append(text).append('|')
}
