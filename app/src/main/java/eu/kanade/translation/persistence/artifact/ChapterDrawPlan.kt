package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable

/**
 * Versioned, immutable per-page draw-plan DTO and its separately
 * invalidatable color/style sub-result. These DTOs deliberately do not
 * serialize `BlockLayout`, `TranslationBlock`, or mask objects. Paint-only
 * values live in [ColorStylePreparation], and all coordinates stay in
 * source-image space.
 *
 * Serialized only through the shared [ArtifactDocumentJson] instance
 *
 */

/** Source-image-space axis-aligned float rectangle (canonical JSON shape). */
@Serializable
data class DrawPlanRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun validationError(): String? =
        if (left > right || top > bottom) "inverted rect" else null
}

/** Exact font/paint identity the overlay draws with (mirrors TranslationOverlayView). */
@Serializable
data class DrawPlanFontIdentity(
    /** e.g. `font/animeace.ttf`. */
    val assetName: String,
    val assetSha256: String,
    /** e.g. `BOLD`. */
    val typefaceStyle: String,
    /** e.g. `ANTI_ALIAS|SUBPIXEL_TEXT`. */
    val paintFlags: String,
) {
    fun validationError(): String? = when {
        assetName.isBlank() -> "blank assetName"
        !assetSha256.isSha256Hex() -> "assetSha256 is not sha256 hex"
        typefaceStyle.isBlank() -> "blank typefaceStyle"
        paintFlags.isBlank() -> "blank paintFlags"
        else -> null
    }
}

/** Durable reference into the page's OCR mask geometry (replaces page-local ids). */
@Serializable
data class DrawPlanMaskComponentRef(
    val maskGeometryContentHash: String,
    val componentId: Int,
) {
    fun validationError(): String? = when {
        !maskGeometryContentHash.isSha256Hex() -> "maskGeometryContentHash is not sha256 hex"
        componentId < 0 -> "negative componentId"
        else -> null
    }
}

/** Persisted measured line (legacy stacked/vertical mode output). */
@Serializable
data class DrawPlanPositionedLine(
    val text: String,
    val leftPx: Int,
    val topPx: Int,
    val layoutWidthPx: Int,
    val layoutHeightPx: Int,
)

/** Horizontal alignment of the drawn block. */
enum class DrawPlanAlign { CENTER, LEFT, RIGHT }

/** One persisted draw-plan block. */
@Serializable
data class DrawPlanBlock(
    /** OCR canonical block id. */
    val stableBlockId: String,
    /** Planner input ordinal (`blockId` alone is nullable/duplicated). */
    val inputIndex: Int,
    val chosenText: String,
    val isVertical: Boolean,
    val originX: Float,
    val originY: Float,
    val safeW: Float,
    val safeH: Float,
    val fontSizePx: Float,
    /** Produced by the planner; layout-affecting, never paint-only. */
    val strokeWidth: Float,
    val drawAlign: DrawPlanAlign,
    /** Source-image space. */
    val clipRect: DrawPlanRect? = null,
    /** Legacy stacked/vertical mode lines. */
    val lines: List<String>? = null,
    val positionedLines: List<DrawPlanPositionedLine>? = null,
    /** Source-image space. */
    val cellRect: DrawPlanRect? = null,
    val maskComponentRef: DrawPlanMaskComponentRef? = null,
    val maskUsable: Boolean = false,
) {
    fun validationError(): String? = when {
        stableBlockId.isBlank() -> "blank stableBlockId"
        inputIndex < 0 -> "negative inputIndex"
        !fontSizePx.isFinite() || fontSizePx < 0f -> "invalid fontSizePx"
        !strokeWidth.isFinite() || strokeWidth < 0f -> "invalid strokeWidth"
        clipRect?.validationError() != null -> "malformed clipRect"
        cellRect?.validationError() != null -> "malformed cellRect"
        maskComponentRef?.validationError() != null -> "malformed maskComponentRef"
        else -> null
    }
}

/**
 * The per-page persisted layout draw plan (schemas contract §1.6). Field
 * declaration order is the canonical byte order.
 */
@Serializable
data class PageLayoutDrawPlan(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    /** Planner algorithm version; fingerprint input. */
    val layoutPlannerVersion: Int,
    val fontIdentity: DrawPlanFontIdentity,
    /** Platform text-shaping compatibility value (final-target-migration §1.2). */
    val platformShapingKey: String,
    /** Source-image pixels; coordinates are ALWAYS source-image space. */
    val pageWidth: Float,
    val pageHeight: Float,
    /** The planner consumes it (`scale = 1/sampleSize`). */
    val decodeSampleSize: Int,
    /** `computeStrokeWidth` constants version (layout-affecting). */
    val strokePolicyVersion: Int,
    /** Luma-threshold contrast rule version (paint-derived). */
    val strokeColorPolicyVersion: Int,
    val blocks: List<DrawPlanBlock>,
) {
    /** Returns null when this document is semantically usable. */
    fun validationError(): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion: $schemaVersion"
        if (kind != KIND) return "wrong kind: $kind"
        if (layoutPlannerVersion < 1) return "non-positive layoutPlannerVersion"
        fontIdentity.validationError()?.let { return "malformed fontIdentity: $it" }
        if (platformShapingKey.isBlank()) return "blank platformShapingKey"
        if (!pageWidth.isFinite() || pageWidth <= 0f) return "invalid pageWidth"
        if (!pageHeight.isFinite() || pageHeight <= 0f) return "invalid pageHeight"
        if (decodeSampleSize < 1) return "non-positive decodeSampleSize"
        if (strokePolicyVersion < 1) return "non-positive strokePolicyVersion"
        if (strokeColorPolicyVersion < 1) return "non-positive strokeColorPolicyVersion"
        if (blocks.size > MAX_BLOCKS) return "too many blocks: ${blocks.size}"
        blocks.forEach { block ->
            block.validationError()?.let { return "malformed block ${block.stableBlockId}: $it" }
        }
        return null
    }

    val isSemanticallyValid: Boolean
        get() = validationError() == null

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "PAGE_LAYOUT_DRAW_PLAN"

        /** 02 schema bound (T, tunable). */
        const val MAX_BLOCKS = 256
    }
}

/** Which pixels the color preparation consumed. */
enum class ColorImageSourceKind { CLEANED_IMAGE, ORIGINAL_SOURCE }

/** Identity of the cleaned pixels consumed by color estimation. */
@Serializable
data class CleanedImageReference(
    val fileName: String,
    val inpaintRevision: Int,
) {
    fun validationError(): String? = when {
        fileName.isBlank() -> "blank fileName"
        inpaintRevision < 1 -> "non-positive inpaintRevision"
        else -> null
    }
}

/** One block's prepared paint colors (luma-derived stroke, overlay rule). */
@Serializable
data class ColorStyleEntry(
    val stableBlockId: String,
    val inputIndex: Int,
    /** ARGB long. */
    val textColor: Long,
    /** ARGB long; luma(textColor) < 128 → white else black. */
    val derivedStrokeColor: Long,
) {
    fun validationError(): String? = when {
        stableBlockId.isBlank() -> "blank stableBlockId"
        inputIndex < 0 -> "negative inputIndex"
        else -> null
    }
}

/**
 * The per-page color/style sub-result (schemas contract §1.7). Split from
 * geometry: color changes must not reflow (matrix row 10).
 */
@Serializable
data class ColorStylePreparation(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    /** `RenderColorEstimator` algorithm version. */
    val colorEstimatorVersion: Int,
    val imageSource: ColorImageSourceKind = ColorImageSourceKind.CLEANED_IMAGE,
    /** Required iff [imageSource] is CLEANED_IMAGE. */
    val cleanedImageRef: CleanedImageReference? = null,
    val pageWidth: Float,
    val pageHeight: Float,
    val blocks: List<ColorStyleEntry>,
) {
    /** Returns null when this document is semantically usable. */
    fun validationError(): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion: $schemaVersion"
        if (kind != KIND) return "wrong kind: $kind"
        if (colorEstimatorVersion < 1) return "non-positive colorEstimatorVersion"
        if (imageSource == ColorImageSourceKind.CLEANED_IMAGE) {
            if (cleanedImageRef == null) return "missing cleanedImageRef"
            cleanedImageRef.validationError()?.let { return "malformed cleanedImageRef: $it" }
        } else if (cleanedImageRef != null) {
            return "cleanedImageRef present for ORIGINAL_SOURCE"
        }
        if (!pageWidth.isFinite() || pageWidth <= 0f) return "invalid pageWidth"
        if (!pageHeight.isFinite() || pageHeight <= 0f) return "invalid pageHeight"
        if (blocks.size > MAX_BLOCKS) return "too many blocks: ${blocks.size}"
        blocks.forEach { block ->
            block.validationError()?.let { return "malformed block ${block.stableBlockId}: $it" }
        }
        return null
    }

    val isSemanticallyValid: Boolean
        get() = validationError() == null

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "COLOR_STYLE_PREPARATION"

        /** 02 schema bound (T, tunable). */
        const val MAX_BLOCKS = 256
    }
}
