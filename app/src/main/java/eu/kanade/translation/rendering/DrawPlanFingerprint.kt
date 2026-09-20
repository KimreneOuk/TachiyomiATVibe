package eu.kanade.translation.rendering

import android.os.Build
import eu.kanade.translation.artifact.DrawPlanFontIdentity
import eu.kanade.translation.artifact.StageFingerprints
import java.security.MessageDigest

/**
 *  WP8: thin assembling wrapper over
 * [StageFingerprints.layoutCompatibilityFingerprint] for the rendering side of
 * the persisted-layout track. It owns the single sources of truth for the
 * planner-side fingerprint inputs the renderer actually controls:
 *
 *  - the layout planner algorithm version ([LAYOUT_PLANNER_VERSION]);
 *  - the stroke policy version ([STROKE_POLICY_VERSION] — `computeStrokeWidth`
 *    constants `STROKE_WIDTH_FRACTION`/`MIN_STROKE_PX`; stroke width is
 *    layout-affecting, invalidation matrix row 9);
 *  - the stroke color policy version ([STROKE_COLOR_POLICY_VERSION] — the
 *    overlay luma-threshold contrast rule; paint-derived only);
 *  - the font/paint identity the overlay draws with ([FONT_ASSET_NAME],
 *    [TYPEFACE_STYLE], [PAINT_MEASUREMENT_FLAGS] — mirrors
 *    `TranslationOverlayView`: `R.font.animeace` forced to `Typeface.BOLD`,
 *    paints `ANTI_ALIAS|SUBPIXEL_TEXT`);
 *  - the conservative `platformShapingKey` ([platformShapingKey]) — Director
 *    decision 7.5 recommendation: the key includes the raw SDK int, so plans
 *    from any other platform value re-plan via the async fallback.
 *
 * Presentation transforms (SSIV zoom/pan/holder size/orientation) have NO
 * parameter in [StageFingerprints.layoutCompatibilityFingerprint] and none is
 * added here — they are structurally not fingerprint inputs (final-target §3).
 */
object DrawPlanFingerprint {

    /**
     * Algorithm version of `TextLayoutPlanner` placement/planning. Bump on any
     * planning-behavior change that can alter produced geometry.
     */
    const val LAYOUT_PLANNER_VERSION: Int = 1

    /**
     * Version of the `computeStrokeWidth` constants (`STROKE_WIDTH_FRACTION`,
     * `MIN_STROKE_PX`). Stroke width inflates fit/occupancy/collision bounds —
     * geometry-affecting, so this is a persisted-layout fingerprint input
     * (matrix row 9) and never a color/style input (matrix row 10).
     */
    const val STROKE_POLICY_VERSION: Int = 1

    /**
     * Version of the paint-derived stroke color rule (text luma < 128 → white
     * stroke, else black). Not a geometry input.
     */
    const val STROKE_COLOR_POLICY_VERSION: Int = 1

    /** Overlay font asset (resource `res/font/animeace.ttf`), resource-relative name. */
    const val FONT_ASSET_NAME: String = "font/animeace.ttf"

    /** The overlay forces the bundled font to bold (`Typeface.create(it, Typeface.BOLD)`). */
    const val TYPEFACE_STYLE: String = "BOLD"

    /** Measurement/draw paint flags shared by the overlay fill and stroke paints. */
    const val PAINT_MEASUREMENT_FLAGS: String = "ANTI_ALIAS|SUBPIXEL_TEXT"

    /**
     * Conservative platform shaping key (Director decision 7.5): raw SDK int
     * plus its [Build.VERSION_CODES] bucket name. Plans produced under a
     * different key are never accepted as compatible (async re-plan).
     */
    fun platformShapingKey(): String = sdkShapingBucket(Build.VERSION.SDK_INT)

    /** Pure bucket computation over an SDK int ("sdk35-VANILLA_ICE_CREAM" shape). */
    internal fun sdkShapingBucket(sdkInt: Int): String = "sdk$sdkInt-${sdkBucketName(sdkInt)}"

    private fun sdkBucketName(sdkInt: Int): String {
        // Ordered (min SDK int, Build.VERSION_CODES constant name). The FIRST
        // entry whose lower bound exceeds [sdkInt] ends the search; unknown
        // future ints keep the highest known name (the raw int in the key
        // already keeps every distinct SDK distinct — conservative).
        val buckets = intArrayOf(
            1, 3, 4, 5, 8, 9, 11, 14, 16, 19, 21, 22, 23, 24, 25, 26, 27, 28,
            29, 30, 31, 32, 33, 34, 35, 36,
        )
        val names = arrayOf(
            "BASE", "CUPCAKE", "DONUT", "ECLAIR", "FROYO", "GINGERBREAD", "HONEYCOMB",
            "ICE_CREAM_SANDWICH", "JELLY_BEAN", "KITKAT", "LOLLIPOP", "LOLLIPOP_MR1",
            "M", "N", "N_MR1", "O", "O_MR1", "P",
            "Q", "R", "S", "S_V2", "TIRAMISU", "UPSIDE_DOWN_CAKE",
            "VANILLA_ICE_CREAM", "BAKLAVA",
        )
        var index = 0
        while (index + 1 < buckets.size && sdkInt >= buckets[index + 1]) index++
        return names[index]
    }

    /**
     * SHA-256 lowercase hex over the exact bundled font asset bytes. Callers
     * read `res/font/animeace.ttf` once (Android-bound) and pass the bytes;
     * the digest itself is pure and unit-testable.
     */
    fun fontAssetSha256(fontAssetBytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(fontAssetBytes)
        .joinToString("") { "%02x".format(it) }

    /**
     * The exact font/paint identity DTO persisted in every [eu.kanade.translation.artifact.PageLayoutDrawPlan].
     */
    fun drawPlanFontIdentity(assetSha256: String): DrawPlanFontIdentity = DrawPlanFontIdentity(
        assetName = FONT_ASSET_NAME,
        assetSha256 = assetSha256,
        typefaceStyle = TYPEFACE_STYLE,
        paintFlags = PAINT_MEASUREMENT_FLAGS,
    )

    /**
     * 07 layout compatibility fingerprint. First seven parameters are
     * the existing `StageFingerprints.layout` inputs (unchanged order); the
     * remaining values are the FP-07 additions with the rendering-owned ones
     * supplied from this object's constants. `platformShapingKey` defaults to
     * [platformShapingKey] (device build); tests pass explicit keys.
     */
    fun layoutCompatibilityFingerprint(
        translationArtifactId: String,
        cleanedImageArtifactIdOrOriginalSourceId: String,
        layoutEngineVersion: String,
        fontIdentity: String,
        fontScalePreferences: String,
        stylePreferences: String,
        outputDimensions: String,
        fontAssetSha256: String,
        decodeSampleSize: Int,
        sourcePageWidth: Float,
        sourcePageHeight: Float,
        layoutPlannerVersion: Int = LAYOUT_PLANNER_VERSION,
        strokePolicyVersion: Int = STROKE_POLICY_VERSION,
        platformShapingKey: String = platformShapingKey(),
    ): String = StageFingerprints.layoutCompatibilityFingerprint(
        translationArtifactId = translationArtifactId,
        cleanedImageArtifactIdOrOriginalSourceId = cleanedImageArtifactIdOrOriginalSourceId,
        layoutEngineVersion = layoutEngineVersion,
        fontIdentity = fontIdentity,
        fontScalePreferences = fontScalePreferences,
        stylePreferences = stylePreferences,
        outputDimensions = outputDimensions,
        fontAssetName = FONT_ASSET_NAME,
        fontAssetSha256 = fontAssetSha256,
        typefaceStyle = TYPEFACE_STYLE,
        paintMeasurementFlags = PAINT_MEASUREMENT_FLAGS,
        layoutPlannerVersion = layoutPlannerVersion,
        platformShapingKey = platformShapingKey,
        strokePolicyVersion = strokePolicyVersion,
        decodeSampleSize = decodeSampleSize,
        sourcePageWidth = sourcePageWidth,
        sourcePageHeight = sourcePageHeight,
    )
}
