package eu.kanade.translation.engines.vision.ocr.paddle.batch

import java.util.concurrent.atomic.AtomicBoolean

/** Identifies one page generation; a generation change invalidates older work. */
data class PaddleOcrPageGeneration(
    val pageId: String,
    val generation: Long,
) {
    init {
        require(pageId.isNotBlank()) { "pageId must not be blank" }
        require(generation >= 0L) { "generation must not be negative" }
    }
}

/** Stable parent-region identity within a page generation. */
@JvmInline
value class PaddleOcrParentRegion(val index: Int) {
    init {
        require(index >= 0) { "parent region index must not be negative" }
    }
}

/** Stable identity used to map a recognizer result back to page geometry. */
data class PaddleOcrLeafIdentity(
    val pageGeneration: PaddleOcrPageGeneration,
    val parentRegion: PaddleOcrParentRegion,
    val lineIndex: Int?,
    val glyphIndex: Int?,
) {
    init {
        require(lineIndex == null || lineIndex >= 0) { "line index must not be negative" }
        require(glyphIndex == null || glyphIndex >= 0) { "glyph index must not be negative" }
        require(glyphIndex == null || lineIndex != null) {
            "a glyph index requires a line index"
        }
    }
}

/** The fixed recognizer input widths used by the current Paddle preprocessing contract. */
enum class PaddleOcrWidthBucket(val paddedWidth: Int) {
    WIDTH_640(640),
    WIDTH_1600(1600),
    ;

    companion object {
        /** Mirrors Paddle's current <=640/>640 alignment without touching bitmap geometry. */
        fun forScaledWidth(width: Int): PaddleOcrWidthBucket {
            require(width > 0) { "scaled width must be positive" }
            return if (width <= WIDTH_640.paddedWidth) WIDTH_640 else WIDTH_1600
        }
    }
}

/** Rotation already performed while constructing the final recognizer crop. */
enum class PaddleOcrRotation(val degrees: Int) {
    NONE(0),
    CCW_90(-90),
}

/** Why this final line/glyph/whole-region leaf exists after geometry decisions. */
enum class PaddleOcrFallbackKind {
    DETECTOR_LINE,
    HEURISTIC_LINE,
    WHOLE_REGION,
    GLYPH,
}

/** Idempotent ownership token for a crop held by one leaf. */
class PaddleOcrCropOwnership(
    val token: String,
    private val onRelease: () -> Unit = {},
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    init {
        require(token.isNotBlank()) { "crop ownership token must not be blank" }
    }

    val isReleased: Boolean
        get() = released.get()

    fun release() {
        if (released.compareAndSet(false, true)) {
            onRelease()
        }
    }

    override fun close() {
        release()
    }
}

/** A final Paddle recognition unit; geometry and fallback decisions are already complete. */
data class PaddleOcrLeafWork<CROP>(
    val pageGeneration: PaddleOcrPageGeneration,
    val parentRegion: PaddleOcrParentRegion,
    val lineIndex: Int?,
    val glyphIndex: Int?,
    val crop: CROP,
    val cropOwnership: PaddleOcrCropOwnership,
    val rotation: PaddleOcrRotation,
    val fallbackKind: PaddleOcrFallbackKind,
    val widthBucket: PaddleOcrWidthBucket,
) {
    val identity: PaddleOcrLeafIdentity = PaddleOcrLeafIdentity(
        pageGeneration = pageGeneration,
        parentRegion = parentRegion,
        lineIndex = lineIndex,
        glyphIndex = glyphIndex,
    )

    init {
        when (fallbackKind) {
            PaddleOcrFallbackKind.DETECTOR_LINE,
            PaddleOcrFallbackKind.HEURISTIC_LINE,
            -> require(lineIndex != null && glyphIndex == null) {
                "line fallback leaves require a line index and no glyph index"
            }

            PaddleOcrFallbackKind.WHOLE_REGION -> require(lineIndex == null && glyphIndex == null) {
                "whole-region fallback leaves cannot carry line or glyph indexes"
            }

            PaddleOcrFallbackKind.GLYPH -> require(lineIndex != null && glyphIndex != null) {
                "glyph leaves require both line and glyph indexes"
            }
        }
    }

    fun releaseCropOwnership() {
        cropOwnership.release()
    }
}
