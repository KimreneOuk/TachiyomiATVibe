package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.diagnostics.TranslationIdentityKeys
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceClock
import eu.kanade.translation.diagnostics.TranslationTraceIdGenerator
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceReason
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.translator.ProviderQuotaPolicy
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class BatchPageTraceRegistryTest {

    private class FakeClock(var nowNanos: Long = 0L) : TranslationTraceClock {
        override fun nowNanos(): Long = nowNanos

        fun advanceMs(ms: Long) {
            nowNanos += ms * 1_000_000L
        }
    }

    @Test
    fun `synthetic page trace separates each lease wait from busy lanes`() = runBlocking<Unit> {
        val captured = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldDetailed = TranslationPipelineDiagnostics.detailedTracingEnabled
        val oldIds = TranslationPipelineDiagnostics.idGenerator
        val oldKeys = TranslationPipelineDiagnostics.identityKeys
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> captured += line }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "e16atest")
        TranslationPipelineDiagnostics.identityKeys = TranslationIdentityKeys(ByteArray(32) { 41 })

        try {
            val clock = FakeClock()
            val pageKey = "synthetic-page"
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                mode = TranslationTraceMode.BATCH,
                origin = TranslationTraceMode.BATCH,
                chapterRaw = "synthetic-chapter",
                pages = 1,
                clock = clock,
            )
            val registry = BatchPageTraceRegistry(schedule, mapOf(pageKey to 4), clock)
            registry.startPages(listOf(pageKey))

            registry.withPageRun(pageKey) {
                registry.withLeaseWait(
                    pageKey,
                    TranslationTraceSite.BATCH_OCR,
                    TranslationTraceLeaseKind.OCR,
                ) { clock.advanceMs(17) }
                val nativeToken = schedule.enterLane(TranslationTraceLane.NATIVE)
                try {
                    registry.withStage(
                        pageKey,
                        TranslationTraceStage.OCR,
                        TranslationTraceLane.NATIVE,
                        provider = TranslationTraceProvider.CPU,
                    ) { clock.advanceMs(23) }
                } finally {
                    nativeToken.close()
                }

                registry.beginStage(pageKey, TranslationTraceStage.NATIVE_QUEUE, TranslationTraceLane.SCHEDULER)
                    .also { span ->
                        clock.advanceMs(11)
                        span.end()
                    }
                registry.withLeaseWait(
                    pageKey,
                    TranslationTraceSite.BATCH_STANDARD_TRANSLATION,
                    TranslationTraceLeaseKind.TRANSLATION,
                ) { clock.advanceMs(7) }
                registry.withProviderWindowAdmissionWait(
                    pageKeys = listOf(pageKey),
                    provider = TranslationTraceProvider.REMOTE,
                ) { markAdmitted ->
                    clock.advanceMs(9)
                    markAdmitted()
                }
                registry.beginStage(
                    pageKey,
                    TranslationTraceStage.PROVIDER_GOVERNOR_WAIT,
                    TranslationTraceLane.SCHEDULER,
                    provider = TranslationTraceProvider.REMOTE,
                ).also { span ->
                    clock.advanceMs(4)
                    span.end()
                }
                registry.withProviderWindow(
                    pageKeys = listOf(pageKey),
                ) {
                    val providerToken = schedule.enterLane(TranslationTraceLane.PROVIDER)
                    try {
                        registry.withStage(
                            pageKey,
                            TranslationTraceStage.TRANSLATE,
                            TranslationTraceLane.PROVIDER,
                            provider = TranslationTraceProvider.REMOTE,
                        ) {
                            clock.advanceMs(31)
                            "provider-result"
                        }
                    } finally {
                        providerToken.close()
                    }
                }

                registry.withLeaseWait(
                    pageKey,
                    TranslationTraceSite.BATCH_OVERLAP_INPAINT,
                    TranslationTraceLeaseKind.INPAINT,
                ) { clock.advanceMs(13) }
                val inpaintToken = schedule.enterLane(TranslationTraceLane.NATIVE)
                try {
                    registry.withStage(
                        pageKey,
                        TranslationTraceStage.INPAINT,
                        TranslationTraceLane.NATIVE,
                        provider = TranslationTraceProvider.CPU,
                    ) { clock.advanceMs(19) }
                } finally {
                    inpaintToken.close()
                }

                registry.beginStage(pageKey, TranslationTraceStage.JOURNAL_CREDIT_WAIT, TranslationTraceLane.SCHEDULER)
                    .also { span ->
                        clock.advanceMs(5)
                        span.end()
                    }
                registry.withLeaseWait(
                    pageKey,
                    TranslationTraceSite.BATCH_OVERLAP_RENDER,
                    TranslationTraceLeaseKind.RENDER,
                ) { clock.advanceMs(3) }
                val renderToken = schedule.enterLane(TranslationTraceLane.RENDER)
                try {
                    registry.withStage(
                        pageKey,
                        TranslationTraceStage.LAYOUT,
                        TranslationTraceLane.RENDER,
                        provider = TranslationTraceProvider.ANDROID_CANVAS,
                    ) { clock.advanceMs(5) }
                    registry.withStage(
                        pageKey,
                        TranslationTraceStage.RENDER,
                        TranslationTraceLane.RENDER,
                        provider = TranslationTraceProvider.ANDROID_CANVAS,
                    ) { clock.advanceMs(8) }
                } finally {
                    renderToken.close()
                }
            }

            registry.finishOpenRuns { TranslationTraceOutcome.SUCCESS }
            schedule.end(TranslationTraceOutcome.SUCCESS)
        } finally {
            TranslationPipelineDiagnostics.sink = oldSink
            TranslationPipelineDiagnostics.detailedTracingEnabled = oldDetailed
            TranslationPipelineDiagnostics.idGenerator = oldIds
            TranslationPipelineDiagnostics.identityKeys = oldKeys
        }

        val runStarts = captured.filter { it.contains("event=run_start ") }
        val runEnds = captured.filter { it.contains("event=run_end ") }
        runStarts.size shouldBe 1
        runEnds.size shouldBe 1
        val runId = runStarts.single().substringAfter(" rid=").substringBefore(' ')
        runEnds.single() shouldContain "rid=$runId"
        captured.filter { it.contains("event=stage_end ") && !it.contains("rid=none") }
            .forEach { it shouldContain "rid=$runId" }

        val stageEnds = captured.filter { it.contains("event=stage_end ") }
        fun stage(stage: String, site: String? = null): String = stageEnds.single { line ->
            line.contains("stage=$stage ") && (site == null || line.contains("site=$site "))
        }

        stage("lease_wait", "batch_ocr") shouldContain "leaseKind=ocr"
        stage("lease_wait", "batch_ocr") shouldContain "durationMs=17"
        stage("lease_wait", "batch_standard_translation") shouldContain "leaseKind=translation"
        stage("lease_wait", "batch_standard_translation") shouldContain "durationMs=7"
        stage("lease_wait", "batch_overlap_inpaint") shouldContain "leaseKind=inpaint"
        stage("lease_wait", "batch_overlap_inpaint") shouldContain "durationMs=13"
        stage("lease_wait", "batch_overlap_render") shouldContain "leaseKind=render"
        stage("lease_wait", "batch_overlap_render") shouldContain "durationMs=3"
        stage("native_queue") shouldContain "durationMs=11"
        stage("provider_window_wait") shouldContain "durationMs=9"
        stage("provider_governor_wait") shouldContain "durationMs=4"
        stage("journal_credit_wait") shouldContain "durationMs=5"
        listOf("ocr", "translate", "inpaint", "layout", "render").forEach { stageName ->
            stageEnds.any { it.contains("stage=$stageName ") } shouldBe true
        }

        val scheduleEnd = captured.single { it.contains("event=schedule_end ") }
        scheduleEnd shouldContain "nativeBusyMs=42"
        scheduleEnd shouldContain "providerBusyMs=31"
        scheduleEnd shouldContain "renderBusyMs=13"
        // The synthetic evidence is a single run with three separated lease
        // waits and explicit queue/provider/journal waits; the schedule totals
        // account only admitted native/provider/render work.
        runEnds.map { it.substringAfter("rid=").substringBefore(' ') }.shouldContainExactly(runId)
    }

    @Test
    fun `multi-page envelope provider admission waits and busy time reach each page run`() = runBlocking<Unit> {
        val captured = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldDetailed = TranslationPipelineDiagnostics.detailedTracingEnabled
        val oldIds = TranslationPipelineDiagnostics.idGenerator
        val oldKeys = TranslationPipelineDiagnostics.identityKeys
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> captured += line }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "e16amulti")
        TranslationPipelineDiagnostics.identityKeys = TranslationIdentityKeys(ByteArray(32) { 42 })

        try {
            val traceClock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                mode = TranslationTraceMode.BATCH,
                origin = TranslationTraceMode.BATCH,
                chapterRaw = "multi-envelope",
                pages = 2,
                clock = traceClock,
            )
            val pageKeys = listOf("page-a", "page-b")
            val registry = BatchPageTraceRegistry(schedule, pageKeys.withIndex().associate { it.value to it.index }, traceClock)
            registry.startPages(pageKeys)
            val providerClock = object : eu.kanade.translation.engines.translator.ProviderRequestClock {
                var now = 0L
                override fun nowEpochMs(): Long = now
                override suspend fun delay(millis: Long) {
                    now += millis
                    traceClock.advanceMs(millis)
                }
            }
            val governor = ProviderRequestGovernor(
                policy = {
                    ProviderQuotaPolicy(
                        minimumSpacingMs = 10L,
                        pollIntervalMs = 5L,
                        maxForegroundWaitMs = 100L,
                    )
                },
                clock = providerClock,
            )
            val metadata = ProviderRequestMetadata(
                key = ProviderRequestKey("remote", "model", "credential"),
                operation = "translation_envelope",
            )

            governor.executeValue(metadata) { "warm" }
            registry.withProviderWindow(pageKeys) {
                governor.executeValue(metadata) {
                    traceClock.advanceMs(31)
                    "translated"
                }
            }

            registry.finishOpenRuns { TranslationTraceOutcome.SUCCESS }
            schedule.end(TranslationTraceOutcome.SUCCESS)
        } finally {
            TranslationPipelineDiagnostics.sink = oldSink
            TranslationPipelineDiagnostics.detailedTracingEnabled = oldDetailed
            TranslationPipelineDiagnostics.idGenerator = oldIds
            TranslationPipelineDiagnostics.identityKeys = oldKeys
        }

        val starts = captured.filter { it.contains("event=run_start ") }
        val ends = captured.filter { it.contains("event=run_end ") }
        starts.size shouldBe 2
        ends.size shouldBe 2
        val waits = captured.filter { it.contains("event=stage_end ") && it.contains("stage=provider_governor_wait ") }
        waits.size shouldBe 2
        waits.forEach { it shouldContain "durationMs=10" }
        waits.map { it.substringAfter("rid=").substringBefore(' ') }.toSet().size shouldBe 2
        val translations = captured.filter { it.contains("event=stage_end ") && it.contains("stage=translate ") }
        translations.size shouldBe 2
        translations.forEach { it shouldContain "durationMs=31" }
        val scheduleEnd = captured.single { it.contains("event=schedule_end ") }
        scheduleEnd shouldContain "providerBusyMs=31"
    }

    @Test
    fun `overlap deferral is distinct from a completed skipped page`() = runBlocking<Unit> {
        val captured = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldDetailed = TranslationPipelineDiagnostics.detailedTracingEnabled
        val oldIds = TranslationPipelineDiagnostics.idGenerator
        val oldKeys = TranslationPipelineDiagnostics.identityKeys
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> captured += line }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "e16adefer")
        TranslationPipelineDiagnostics.identityKeys = TranslationIdentityKeys(ByteArray(32) { 43 })

        try {
            val clock = FakeClock()
            val deferredPage = "deferred-page"
            val completedSkipPage = "completed-skip-page"
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                mode = TranslationTraceMode.BATCH,
                origin = TranslationTraceMode.BATCH,
                chapterRaw = "defer-vs-skip",
                pages = 2,
                clock = clock,
            )
            val registry = BatchPageTraceRegistry(
                schedule,
                mapOf(deferredPage to 0, completedSkipPage to 1),
                clock,
            )
            registry.startPages(listOf(deferredPage, completedSkipPage))

            // This is the marker installed by OverlapScheduler at its concrete
            // deferral site. The finalizer fallback deliberately returns SUCCESS
            // for both pages to model their SKIPPED page states.
            registry.markDeferred(deferredPage, TranslationTraceReason.WRITE_SLOT_BUSY)
            registry.finishOpenRuns { TranslationTraceOutcome.SUCCESS }
            schedule.end(TranslationTraceOutcome.PAUSE)
        } finally {
            TranslationPipelineDiagnostics.sink = oldSink
            TranslationPipelineDiagnostics.detailedTracingEnabled = oldDetailed
            TranslationPipelineDiagnostics.idGenerator = oldIds
            TranslationPipelineDiagnostics.identityKeys = oldKeys
        }

        val runEnds = captured.filter { it.contains("event=run_end ") }
        runEnds.size shouldBe 2
        runEnds.single { it.contains("reason=write_slot_busy") } shouldContain "outcome=pause"
        val completedSkip = runEnds.single { !it.contains("reason=") }
        completedSkip shouldContain "outcome=success"
    }
}
