package eu.kanade.translation.scheduling

import eu.kanade.translation.diagnostics.TelemetryTrace

object AutoSmoothnessTelemetry {
    fun logUserArrival(
        pageIndex: Int,
        pageKey: String,
        isReady: Boolean,
        arrivalEpochMs: Long,
        autoEnabled: Boolean,
    ) {
        TelemetryTrace.log(
            "auto",
            "user_arrival",
            "pageIndex" to pageIndex,
            "pageKey" to pageKey,
            "isReady" to isReady,
            "arrivalEpochMs" to arrivalEpochMs,
            "autoEnabled" to autoEnabled,
        )
    }

    fun logDebounceLifecycle(
        action: String,
        targetPage: Int,
        queuedDurationMs: Double,
    ) {
        TelemetryTrace.log(
            "auto",
            "debounce_lifecycle",
            "action" to action,
            "targetPage" to targetPage,
            "queuedDurationMs" to queuedDurationMs,
        )
    }

    fun logArrivalWaitRecorded(
        pageIndex: Int,
        pageKey: String,
        waitMs: Double,
        hadToWait: Boolean,
    ) {
        TelemetryTrace.log(
            "auto",
            "arrival_wait_recorded",
            "pageIndex" to pageIndex,
            "pageKey" to pageKey,
            "waitMs" to waitMs,
            "hadToWait" to hadToWait,
        )
    }

    fun logWindowReconcile(
        visible: Int,
        ahead: Int,
        desired: String,
        unservicedPredecessors: String,
        memoryOk: Boolean,
        gen: Long,
    ) {
        TelemetryTrace.log(
            "auto",
            "window_reconcile",
            "visible" to visible,
            "ahead" to ahead,
            "desired" to desired,
            "unservicedPredecessors" to unservicedPredecessors,
            "memoryOk" to memoryOk,
            "gen" to gen,
        )
    }

    fun logAutoAdmit(
        idx: Int,
        pageKey: String,
        foreground: Boolean,
        gen: Long,
    ) {
        TelemetryTrace.log(
            "auto",
            "auto_admit",
            "idx" to idx,
            "pageKey" to pageKey,
            "foreground" to foreground,
            "gen" to gen,
        )
    }
}
