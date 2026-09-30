package eu.kanade.translation.pipeline.adaptive

import android.content.Context
import android.os.Build
import android.os.PowerManager
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.diagnostics.TranslationTraceStageObserver
import eu.kanade.translation.diagnostics.TranslationTraceStageObserverRegistry
import eu.kanade.translation.pipeline.memory.TranslationMemoryBudget
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only adapter from the existing E3 stage spans and E16a journal signals
 * into the controller's small [KnobSignals] port. Stage spans are observed in
 * memory; no new trace category or log schema is introduced.
 */
class AdaptiveKnobSignalAdapter(
    private val controller: AdaptiveKnobController,
    private val hasMemoryHeadroom: () -> Boolean,
    private val thermalStatus: () -> Int,
) : KnobSignals, TranslationTraceStageObserver, Closeable {
    private val durabilitySpans = AtomicInteger(0)
    private val durabilityEvents = AtomicLong(0L)
    private val stageStarts = ConcurrentHashMap<TranslationStageSpan, StageStart>()
    private val activePageStageCounts = ConcurrentHashMap<String, Int>()
    private val registration = TranslationTraceStageObserverRegistry.register(this)

    override fun snapshot(): KnobSignalSnapshot = KnobSignalSnapshot(
        hasMemoryHeadroom = runCatching(hasMemoryHeadroom).getOrDefault(false),
        thermalStatus = runCatching(thermalStatus).getOrDefault(THERMAL_STATUS_UNKNOWN),
        activeDurabilityStalls = durabilitySpans.get(),
        durabilityEventSequence = durabilityEvents.get(),
    )

    override fun onStageStarted(span: TranslationStageSpan) {
        if (isDurabilityStage(span.stage)) {
            durabilitySpans.incrementAndGet()
            durabilityEvents.incrementAndGet()
            return
        }
        if (deviceStage(span.stage, span.lane, span.provider) == null) return
        val runId = span.runId ?: return
        activePageStageCounts.merge(runId, 1, Int::plus)
        stageStarts[span] = StageStart(
            snapshot = snapshot(),
            runId = runId,
            activeDevicePages = activePageStageCounts.size,
        )
    }

    override fun onStageCompleted(
        span: TranslationStageSpan,
        durationNanos: Long,
        outcome: TranslationTraceOutcome,
        error: Throwable?,
        normalizationUnits: Long,
    ) {
        if (isDurabilityStage(span.stage)) {
            durabilitySpans.updateAndGet { (it - 1).coerceAtLeast(0) }
            durabilityEvents.incrementAndGet()
            return
        }

        if (error is OutOfMemoryError) controller.onOutOfMemory()

        val stage = deviceStage(span.stage, span.lane, span.provider) ?: return
        val started = stageStarts.remove(span) ?: return
        activePageStageCounts.computeIfPresent(started.runId) { _, count ->
            if (count <= 1) null else count - 1
        }
        val finished = snapshot()
        val thermalThrottleOnset = !isThermalThrottle(started.snapshot.thermalStatus) &&
            isThermalThrottle(finished.thermalStatus)
        val durabilityInterference = started.snapshot.activeDurabilityStalls > 0 ||
            finished.activeDurabilityStalls > 0 ||
            started.snapshot.durabilityEventSequence != finished.durabilityEventSequence

        controller.observe(
            DeviceStageObservation(
                stage = stage,
                durationNanos = durationNanos,
                normalizationUnits = normalizationUnits,
                activeDevicePages = started.activeDevicePages,
                successful = outcome == TranslationTraceOutcome.SUCCESS,
                thermalThrottleOnset = thermalThrottleOnset,
                durabilityInterference = durabilityInterference,
                memoryHeadroomAvailable = started.snapshot.hasMemoryHeadroom && finished.hasMemoryHeadroom,
            ),
        )
    }

    override fun close() {
        registration.close()
        stageStarts.clear()
        activePageStageCounts.clear()
    }

    private fun isThermalThrottle(status: Int): Boolean = status >= THERMAL_THROTTLE_ONSET

    private fun isDurabilityStage(stage: TranslationTraceStage): Boolean =
        stage == TranslationTraceStage.JOURNAL_CREDIT_WAIT ||
            stage == TranslationTraceStage.JOURNAL_TERMINAL_LAG ||
            stage == TranslationTraceStage.JOURNAL_TERMINAL_PAYLOAD

    private fun deviceStage(
        stage: TranslationTraceStage,
        lane: TranslationTraceLane,
        provider: TranslationTraceProvider,
    ): DeviceActiveStage? = when (stage) {
        TranslationTraceStage.SOURCE_DECODE ->
            DeviceActiveStage.SOURCE_DECODE.takeIf { lane == TranslationTraceLane.NATIVE }

        TranslationTraceStage.DETECT,
        TranslationTraceStage.SEGMENT,
        -> DeviceActiveStage.DETECT.takeIf { lane == TranslationTraceLane.NATIVE }

        TranslationTraceStage.OCR -> DeviceActiveStage.OCR.takeIf { lane == TranslationTraceLane.NATIVE }
        TranslationTraceStage.INPAINT -> DeviceActiveStage.INPAINT.takeIf { lane == TranslationTraceLane.NATIVE }
        TranslationTraceStage.TRANSLATE ->
            DeviceActiveStage.TRANSLATE.takeIf {
                lane == TranslationTraceLane.PROVIDER && provider == TranslationTraceProvider.LOCAL
            }

        TranslationTraceStage.LAYOUT,
        TranslationTraceStage.RENDER,
        -> DeviceActiveStage.RENDER.takeIf { lane == TranslationTraceLane.RENDER }

        else -> null
    }

    private data class StageStart(
        val snapshot: KnobSignalSnapshot,
        val runId: String,
        val activeDevicePages: Int,
    )

    companion object {
        /** Build the production adapter from currently available device signals. */
        fun forContext(context: Context, controller: AdaptiveKnobController): AdaptiveKnobSignalAdapter {
            val appContext = context.applicationContext
            val powerManager = runCatching {
                appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            }.getOrNull()
            return AdaptiveKnobSignalAdapter(
                controller = controller,
                hasMemoryHeadroom = TranslationMemoryBudget::hasHeadroomForPrefetch,
                thermalStatus = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        powerManager?.currentThermalStatus ?: THERMAL_STATUS_UNKNOWN
                    } else {
                        THERMAL_STATUS_UNKNOWN
                    }
                },
            )
        }

        const val THERMAL_STATUS_UNKNOWN = 0
        const val THERMAL_THROTTLE_ONSET = PowerManager.THERMAL_STATUS_MODERATE
    }
}