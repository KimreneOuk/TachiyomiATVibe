package eu.kanade.translation.pipeline

import eu.kanade.translation.diagnostics.TelemetryTrace
import java.util.Locale

/**
 * Telemetry formatting and logging helpers for the native vision pipeline,
 * manual dispatch, OCR leaf crops, and render tail decomposition.
 */
object NativeVisionTelemetry {
    const val TARGET_MS = 2000

    fun logBudgetSummary(
        pageKey: String,
        targetMs: Int = TARGET_MS,
        detMs: Double,
        segMs: Double,
        ocrMs: Double,
        inpaintMs: Double,
        leafCount: Int,
        boxCount: Int,
        provider: String,
        totalNativeMs: Double = detMs + segMs + ocrMs + inpaintMs,
    ) {
        val budgetMet = totalNativeMs <= targetMs
        TelemetryTrace.log(
            domain = "native_vision",
            event = "budget_summary",
            "pageKey" to pageKey,
            "targetMs" to targetMs,
            "totalNativeMs" to String.format(Locale.US, "%.2f", totalNativeMs),
            "budgetMet" to budgetMet,
            "detMs" to String.format(Locale.US, "%.2f", detMs),
            "segMs" to String.format(Locale.US, "%.2f", segMs),
            "ocrMs" to String.format(Locale.US, "%.2f", ocrMs),
            "inpaintMs" to String.format(Locale.US, "%.2f", inpaintMs),
            "leafCount" to leafCount,
            "boxCount" to boxCount,
            "provider" to provider,
        )
    }

    fun logCropSummary(
        pageKey: String,
        leafCount: Int,
        batchSize: Int,
        avgInferMs: Double,
        provider: String,
    ) {
        TelemetryTrace.log(
            domain = "ocr",
            event = "crop_summary",
            "pageKey" to pageKey,
            "leafCount" to leafCount,
            "batchSize" to batchSize,
            "avgInferMs" to String.format(Locale.US, "%.2f", avgInferMs),
            "provider" to provider,
        )
    }

    fun logRenderTailBreakdown(
        pageKey: String,
        permitWaitMs: Double,
        colorEstimateMs: Double,
        commitMs: Double,
        totalTailMs: Double = permitWaitMs + colorEstimateMs + commitMs,
    ) {
        TelemetryTrace.log(
            domain = "render_tail",
            event = "render_tail_breakdown",
            "pageKey" to pageKey,
            "permitWaitMs" to String.format(Locale.US, "%.2f", permitWaitMs),
            "colorEstimateMs" to String.format(Locale.US, "%.2f", colorEstimateMs),
            "commitMs" to String.format(Locale.US, "%.2f", commitMs),
            "totalTailMs" to String.format(Locale.US, "%.2f", totalTailMs),
        )
    }

    fun logTapToDispatch(
        pageKey: String,
        delayMs: Double,
    ) {
        TelemetryTrace.log(
            domain = "manual",
            event = "tap_to_dispatch",
            "pageKey" to pageKey,
            "delayMs" to String.format(Locale.US, "%.2f", delayMs),
        )
    }

    fun logDisplayAttach(
        pageKey: String,
        streamAttached: Boolean,
        showTranslatedImage: Boolean,
        displayImageName: Boolean,
    ) {
        TelemetryTrace.log(
            domain = "manual",
            event = "display_attach",
            "pageKey" to pageKey,
            "streamAttached" to streamAttached,
            "showTranslatedImage" to showTranslatedImage,
            "displayImageName" to displayImageName,
        )
    }
}
