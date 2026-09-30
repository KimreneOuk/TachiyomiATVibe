package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationScheduleTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceClock
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceReason
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.diagnostics.TranslationTraceStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One trace run per batch page, shared by OCR, provider, inpaint, and render work. */
internal class BatchPageTraceRegistry(
    private val schedule: TranslationScheduleTrace,
    private val pageIndexes: Map<String, Int>,
    private val clock: TranslationTraceClock = TranslationTraceClock.SYSTEM,
) {
    private val runs = ConcurrentHashMap<String, TranslationRunTrace>()
    private val deferredReasons = ConcurrentHashMap<String, TranslationTraceReason>()

    fun startPages(pageKeys: Iterable<String>) {
        pageKeys.distinct().forEach(::runForOrStart)
    }

    fun runFor(pageKey: String): TranslationRunTrace? = runs[pageKey]

    /** Records the overlap scheduler's reason when a page cannot run this pass. */
    fun markDeferred(pageKey: String, reason: TranslationTraceReason) {
        deferredReasons[pageKey] = reason
    }

    /** A later attempt owns the page again, so an earlier temporary deferral is settled. */
    fun clearDeferred(pageKey: String) {
        deferredReasons.remove(pageKey)
    }

    fun enterLane(lane: TranslationTraceLane) = schedule.enterLane(lane)

    fun runForOrStart(pageKey: String): TranslationRunTrace =
        runs.computeIfAbsent(pageKey) {
            TranslationPipelineDiagnostics.startRun(
                schedule = schedule,
                pageRaw = pageKey,
                pageIndex = pageIndexes[pageKey],
                plan = TranslationTracePlan.FRESH,
                clock = clock,
            )
        }

    fun beginStage(
        pageKey: String,
        stage: TranslationTraceStage,
        lane: TranslationTraceLane,
        provider: TranslationTraceProvider = TranslationTraceProvider.NONE,
        site: TranslationTraceSite? = null,
        leaseKind: TranslationTraceLeaseKind? = null,
        normalizationUnits: Long = 0L,
    ) = runs[pageKey]?.beginStage(
        stage = stage,
        lane = lane,
        provider = provider,
        site = site,
        leaseKind = leaseKind,
        normalizationUnits = normalizationUnits,
    ) ?: TranslationTrace.beginStage(
        stage = stage,
        lane = lane,
        provider = provider,
        site = site,
        leaseKind = leaseKind,
        normalizationUnits = normalizationUnits,
    )

    suspend fun <T> withLeaseWait(
        pageKey: String,
        site: TranslationTraceSite,
        leaseKind: TranslationTraceLeaseKind,
        block: suspend () -> T,
    ): T {
        val span = beginStage(
            pageKey = pageKey,
            stage = TranslationTraceStage.LEASE_WAIT,
            lane = TranslationTraceLane.SCHEDULER,
            site = site,
            leaseKind = leaseKind,
        )
        return try {
            block().also { span.end(TranslationTraceOutcome.SUCCESS) }
        } catch (cancelled: CancellationException) {
            span.end(TranslationTraceOutcome.CANCELLED, error = cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            span.end(TranslationTraceOutcome.FAILURE, error = failure)
            throw failure
        }
    }

    suspend fun <T> withStage(
        pageKey: String,
        stage: TranslationTraceStage,
        lane: TranslationTraceLane,
        provider: TranslationTraceProvider = TranslationTraceProvider.NONE,
        resultOutcome: (T) -> TranslationTraceOutcome = { TranslationTraceOutcome.SUCCESS },
        failureOutcome: (Throwable) -> TranslationTraceOutcome = {
            if (it is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE
        },
        block: suspend () -> T,
    ): T {
        val span = beginStage(pageKey, stage, lane, provider)
        return try {
            block().also { span.end(resultOutcome(it)) }
        } catch (failure: Throwable) {
            span.end(failureOutcome(failure), error = failure)
            throw failure
        }
    }

    /** Measures provider-window admission once for each page in the envelope. */
    suspend fun <T> withProviderWindowAdmissionWait(
        pageKeys: Collection<String>,
        provider: TranslationTraceProvider,
        failureOutcome: (Throwable) -> TranslationTraceOutcome = {
            if (it is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE
        },
        block: suspend (markAdmitted: () -> Unit) -> T,
    ): T {
        val spans = pageKeys.distinct().map { pageKey ->
            runForOrStart(pageKey).beginStage(
                stage = TranslationTraceStage.PROVIDER_WINDOW_WAIT,
                lane = TranslationTraceLane.SCHEDULER,
                provider = provider,
            )
        }
        var admitted = false
        fun markAdmitted() {
            if (admitted) return
            admitted = true
            spans.forEach { it.end(TranslationTraceOutcome.SUCCESS) }
        }
        return try {
            block(::markAdmitted).also {
                if (!admitted) spans.forEach { span -> span.end(TranslationTraceOutcome.SKIP) }
            }
        } catch (failure: Throwable) {
            if (!admitted) spans.forEach { span -> span.end(failureOutcome(failure), error = failure) }
            throw failure
        }
    }

    suspend fun <T> withPageRun(pageKey: String, block: suspend () -> T): T {
        val run = runs[pageKey] ?: return block()
        return withContext(TranslationTrace.elementFor(run)) { block() }
    }

    suspend fun <T> withProviderWindow(
        pageKeys: Collection<String>,
        block: suspend () -> T,
    ): T {
        val pageRuns = pageKeys.distinct().mapNotNull { runs[it] }
        val singlePageKey = pageKeys.distinct().singleOrNull()
        return when {
            singlePageKey != null -> withPageRun(singlePageKey, block)
            pageRuns.isNotEmpty() -> withContext(TranslationTrace.elementFor(pageRuns)) { block() }
            else -> block()
        }
    }

    fun finishOpenRuns(outcomeForPage: (String) -> TranslationTraceOutcome) {
        runs.entries.toList().forEach { (pageKey, run) ->
            val reason = deferredReasons.remove(pageKey)
            val outcome = outcomeForPage(pageKey).let { current ->
                if (reason != null && current == TranslationTraceOutcome.SUCCESS) {
                    TranslationTraceOutcome.PAUSE
                } else {
                    current
                }
            }
            run.end(outcome, reason = reason)
            runs.remove(pageKey, run)
        }
        deferredReasons.clear()
    }
}
