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

fun TranslationBlock.detachedCopy(): TranslationBlock = copy(
    segmentationMask = segmentationMask?.copy(
        bounds = segmentationMask.bounds.toList(),
        runs = segmentationMask.runs.toList(),
    ),
)

/** Stable across processes and independent of data-class/list hash implementations. */
fun TranslationBlock.stableFingerprint(): String {
    val canonical = buildString {
        appendField(text)
        appendField(translation)
        appendField(width.toRawBits())
        appendField(height.toRawBits())
        appendField(x.toRawBits())
        appendField(y.toRawBits())
        appendField(symHeight.toRawBits())
        appendField(symWidth.toRawBits())
        appendField(angle.toRawBits())
        appendField(label)
        appendField(score.toRawBits())
        appendField(parentX.toRawBits())
        appendField(parentY.toRawBits())
        appendField(parentWidth.toRawBits())
        appendField(parentHeight.toRawBits())
        appendField(textColor)
        appendField(strokeColor)
        appendField(strokeWidth.toRawBits())
        appendField(direction)
        appendField(panelIndex)
        appendField(panelAssignment)
        appendField(panelContainment.toRawBits())
        appendField(bubbleIndex)
        appendField(userEditedAt)
        segmentationMask?.let { mask ->
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

fun PageTranslation.blockFingerprints(): List<String> = blocks.map { it.stableFingerprint() }

private fun StringBuilder.appendField(value: Any?) {
    val text = value?.toString() ?: "<null>"
    append(text.length).append(':').append(text).append('|')
}
