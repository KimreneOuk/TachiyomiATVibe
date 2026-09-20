package eu.kanade.translation.rendering

import eu.kanade.translation.artifact.StageFingerprints
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 *  WP8 ( at the rendering boundary):
 *  - [DrawPlanFingerprint.layoutCompatibilityFingerprint] is a THIN wrapper —
 *    byte-identical to a direct [StageFingerprints.layoutCompatibilityFingerprint]
 *    call with the rendering-owned constants filled in;
 *  - every FP-07 field flip changes the fingerprint (invalidation matrix
 *    row 9);
 *  - repeated computation over equal inputs is identical;
 *  - SSIV pan/zoom/holder-size/orientation can never be inputs — neither the
 *    wrapped builder nor this wrapper declares a parameter for them (the
 *    delegation assertion below proves no hidden input is added).
 */
class DrawPlanFingerprintTest {

    /** Mutable copy of the wrapper inputs for the sensitivity matrix. */
    private data class Inputs(
        val translationArtifactId: String = "ocr-artifact-1",
        val cleanedImageArtifactIdOrOriginalSourceId: String = "cleaned-1",
        val layoutEngineVersion: String = "engine-7",
        val fontIdentity: String = "animeace-bold",
        val fontScalePreferences: String = "1.0",
        val stylePreferences: String = "default",
        val outputDimensions: String = "1200x1800",
        val fontAssetSha256: String = ASSET_SHA,
        val decodeSampleSize: Int = 1,
        val sourcePageWidth: Float = 1200f,
        val sourcePageHeight: Float = 1800f,
        val layoutPlannerVersion: Int = DrawPlanFingerprint.LAYOUT_PLANNER_VERSION,
        val strokePolicyVersion: Int = DrawPlanFingerprint.STROKE_POLICY_VERSION,
        val platformShapingKey: String = "sdk34-UPSIDE_DOWN_CAKE",
    )

    private fun fingerprint(inputs: Inputs = Inputs()): String =
        DrawPlanFingerprint.layoutCompatibilityFingerprint(
            translationArtifactId = inputs.translationArtifactId,
            cleanedImageArtifactIdOrOriginalSourceId = inputs.cleanedImageArtifactIdOrOriginalSourceId,
            layoutEngineVersion = inputs.layoutEngineVersion,
            fontIdentity = inputs.fontIdentity,
            fontScalePreferences = inputs.fontScalePreferences,
            stylePreferences = inputs.stylePreferences,
            outputDimensions = inputs.outputDimensions,
            fontAssetSha256 = inputs.fontAssetSha256,
            decodeSampleSize = inputs.decodeSampleSize,
            sourcePageWidth = inputs.sourcePageWidth,
            sourcePageHeight = inputs.sourcePageHeight,
            layoutPlannerVersion = inputs.layoutPlannerVersion,
            strokePolicyVersion = inputs.strokePolicyVersion,
            platformShapingKey = inputs.platformShapingKey,
        )

    @Test
    fun `wrapper is thin - identical to the direct StageFingerprints call`() {
        val inputs = Inputs()
        fingerprint(inputs) shouldBe StageFingerprints.layoutCompatibilityFingerprint(
            translationArtifactId = inputs.translationArtifactId,
            cleanedImageArtifactIdOrOriginalSourceId = inputs.cleanedImageArtifactIdOrOriginalSourceId,
            layoutEngineVersion = inputs.layoutEngineVersion,
            fontIdentity = inputs.fontIdentity,
            fontScalePreferences = inputs.fontScalePreferences,
            stylePreferences = inputs.stylePreferences,
            outputDimensions = inputs.outputDimensions,
            fontAssetName = DrawPlanFingerprint.FONT_ASSET_NAME,
            fontAssetSha256 = inputs.fontAssetSha256,
            typefaceStyle = DrawPlanFingerprint.TYPEFACE_STYLE,
            paintMeasurementFlags = DrawPlanFingerprint.PAINT_MEASUREMENT_FLAGS,
            layoutPlannerVersion = inputs.layoutPlannerVersion,
            platformShapingKey = inputs.platformShapingKey,
            strokePolicyVersion = inputs.strokePolicyVersion,
            decodeSampleSize = inputs.decodeSampleSize,
            sourcePageWidth = inputs.sourcePageWidth,
            sourcePageHeight = inputs.sourcePageHeight,
        )
    }

    @Test
    fun `every FP-07 field change flips the fingerprint`() {
        val base = fingerprint()
        val mutations: List<Pair<String, (Inputs) -> Inputs>> = listOf(
            "translationArtifactId" to { it.copy(translationArtifactId = "ocr-artifact-2") },
            "cleanedImageArtifactIdOrOriginalSourceId" to { it.copy(cleanedImageArtifactIdOrOriginalSourceId = "cleaned-2") },
            "layoutEngineVersion" to { it.copy(layoutEngineVersion = "engine-8") },
            "fontIdentity" to { it.copy(fontIdentity = "comicbook-bold") },
            "fontScalePreferences" to { it.copy(fontScalePreferences = "1.3") },
            "stylePreferences" to { it.copy(stylePreferences = "contrast") },
            "outputDimensions" to { it.copy(outputDimensions = "600x900") },
            "fontAssetSha256" to { it.copy(fontAssetSha256 = "bb".repeat(32)) },
            "decodeSampleSize" to { it.copy(decodeSampleSize = 2) },
            "sourcePageWidth" to { it.copy(sourcePageWidth = 1200.5f) },
            "sourcePageHeight" to { it.copy(sourcePageHeight = 1800.5f) },
            "layoutPlannerVersion" to { it.copy(layoutPlannerVersion = DrawPlanFingerprint.LAYOUT_PLANNER_VERSION + 1) },
            "strokePolicyVersion" to { it.copy(strokePolicyVersion = DrawPlanFingerprint.STROKE_POLICY_VERSION + 1) },
            "platformShapingKey" to { it.copy(platformShapingKey = "sdk35-VANILLA_ICE_CREAM") },
        )
        mutations.forEach { (name, mutate) ->
            val flipped = fingerprint(mutate(Inputs()))
            if (flipped == base) {
                throw AssertionError("FP-07 field '$name' did NOT flip the fingerprint")
            }
        }
    }

    @Test
    fun `font and stroke-policy constants flip the fingerprint through the wrapper defaults`() {
        // The wrapper pins font name/style/paint flags internally — changing
        // the constants must change the fingerprint (they are FP-07 fields).
        val withDefaults = DrawPlanFingerprint.layoutCompatibilityFingerprint(
            translationArtifactId = "a",
            cleanedImageArtifactIdOrOriginalSourceId = "b",
            layoutEngineVersion = "v",
            fontIdentity = "f",
            fontScalePreferences = "s",
            stylePreferences = "st",
            outputDimensions = "d",
            fontAssetSha256 = ASSET_SHA,
            decodeSampleSize = 1,
            sourcePageWidth = 100f,
            sourcePageHeight = 100f,
            platformShapingKey = "sdk34-UPSIDE_DOWN_CAKE",
        )
        StageFingerprints.layoutCompatibilityFingerprint(
            translationArtifactId = "a",
            cleanedImageArtifactIdOrOriginalSourceId = "b",
            layoutEngineVersion = "v",
            fontIdentity = "f",
            fontScalePreferences = "s",
            stylePreferences = "st",
            outputDimensions = "d",
            fontAssetName = "font/other.ttf",
            fontAssetSha256 = ASSET_SHA,
            typefaceStyle = "NORMAL",
            paintMeasurementFlags = "ANTI_ALIAS",
            layoutPlannerVersion = DrawPlanFingerprint.LAYOUT_PLANNER_VERSION,
            platformShapingKey = "sdk34-UPSIDE_DOWN_CAKE",
            strokePolicyVersion = DrawPlanFingerprint.STROKE_POLICY_VERSION,
            decodeSampleSize = 1,
            sourcePageWidth = 100f,
            sourcePageHeight = 100f,
        ) shouldNotBe withDefaults
    }

    @Test
    fun `determinism - repeated computation over equal inputs is identical`() {
        repeat(32) {
            fingerprint() shouldBe fingerprint()
        }
        // Two independently built equal inputs agree.
        fingerprint(Inputs()) shouldBe fingerprint(Inputs(fontAssetSha256 = ASSET_SHA))
    }

    @Test
    fun `sdk shaping bucket is conservative - every distinct int yields a distinct key`() {
        val sdkRange = 26..36
        val keys = sdkRange.map { DrawPlanFingerprint.sdkShapingBucket(it) }
        keys.toSet().size shouldBe sdkRange.count()
        // Known Build.VERSION_CODES bucket names at the boundaries.
        DrawPlanFingerprint.sdkShapingBucket(26) shouldBe "sdk26-O"
        DrawPlanFingerprint.sdkShapingBucket(27) shouldBe "sdk27-O_MR1"
        DrawPlanFingerprint.sdkShapingBucket(34) shouldBe "sdk34-UPSIDE_DOWN_CAKE"
        DrawPlanFingerprint.sdkShapingBucket(35) shouldBe "sdk35-VANILLA_ICE_CREAM"
        DrawPlanFingerprint.sdkShapingBucket(36) shouldBe "sdk36-BAKLAVA"
        // Unknown future SDK keeps the highest known name but its own int, so
        // the key (and therefore compatibility) never collides.
        DrawPlanFingerprint.sdkShapingBucket(99) shouldBe "sdk99-BAKLAVA"
        DrawPlanFingerprint.sdkShapingBucket(99) shouldNotBe DrawPlanFingerprint.sdkShapingBucket(36)
    }

    @Test
    fun `font asset digest is lowercase sha256 hex and content-sensitive`() {
        val digest = DrawPlanFingerprint.fontAssetSha256("animeace".toByteArray())
        digest.length shouldBe 64
        digest.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
        digest shouldBe DrawPlanFingerprint.fontAssetSha256("animeace".toByteArray())
        DrawPlanFingerprint.fontAssetSha256("comicbook".toByteArray()) shouldNotBe digest
    }

    @Test
    fun `color estimator and draw plan versions are stable ids`() {
        RenderColorEstimator.COLOR_ESTIMATOR_VERSION shouldBe 1
        DrawPlanFingerprint.LAYOUT_PLANNER_VERSION shouldBe 1
        DrawPlanFingerprint.STROKE_POLICY_VERSION shouldBe 1
        DrawPlanFingerprint.STROKE_COLOR_POLICY_VERSION shouldBe 1
        DrawPlanFingerprint.FONT_ASSET_NAME shouldBe "font/animeace.ttf"
        DrawPlanFingerprint.TYPEFACE_STYLE shouldBe "BOLD"
        DrawPlanFingerprint.PAINT_MEASUREMENT_FLAGS shouldBe "ANTI_ALIAS|SUBPIXEL_TEXT"
    }

    private companion object {
        val ASSET_SHA = "11".repeat(32)
    }
}
