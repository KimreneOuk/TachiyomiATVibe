package eu.kanade.translation.ui

import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.scheduling.AutoDeferralReason
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.coexistence.CoexistenceBarrier
import eu.kanade.translation.coexistence.TranslationCoexistenceHarness
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * T917 Phase 5 (spec §5.1.1, §6.2 commit 3) — RED tests for the pure
 * Appendix-A state → surface mapper and the bounded manual-outcome exposure.
 *
 * The production mapper does not exist yet, so every assertion resolves it
 * reflectively and names the missing contract when absent (the same named-RED
 * pattern the harness uses for missing injection seams). Inputs are ONLY
 * existing typed values ([SinglePageOutcome], [PageDisplayProjection],
 * [AutoSlotState]); the output is read reflectively as a truth record with:
 *
 *  - `label`               semantic English-source label (localized by surfaces)
 *  - `severity`            INFO / PROGRESS / WARNING / ERROR
 *  - `retryMode`           NONE / AUTOMATIC / EXPLICIT / MANUAL_REQUIRED
 *  - `retryAtEpochMs`      pause epoch when known
 *  - `actions`             RETRY / CANCEL / DETAILS / REVIEW subset
 *  - `terminalSuccess`     may count as terminal translated success
 *  - `contentDescription`  accessibility description
 *
 * Mapper contract pinned here (spec §1.2 truth precedence, Appendix A rows):
 * manual — `TranslationUiTruth.forManualOutcome(outcome, durable, partial,
 * exhausted, cancelled)`; auto — `TranslationUiTruth.forAutoSlot(slot,
 * retryAtEpochMs)`. A stale/unconfirmable success may never project
 * "Translated" (§1.2: no mapper may produce success from a stale callback).
 */
class P5OutcomeProjectionTest {

    // ------------------------------------------------------------------
    // Reflective seams (named-RED when the production contract is missing)
    // ------------------------------------------------------------------

    private fun truth(): Any = try {
        val clazz = Class.forName("eu.kanade.translation.ui.TranslationUiTruth")
        clazz.getDeclaredField("INSTANCE").get(null)
    } catch (missing: ReflectiveOperationException) {
        throw AssertionError(
            "T917 P5 RED defect (outcome projection, commit 3/4): the pure Appendix-A " +
                "state→surface mapper eu.kanade.translation.ui.TranslationUiTruth does not " +
                "exist — typed outcomes, durable projections, and auto slots have no " +
                "unified truthful UI projection yet (spec §1.2, §6.2 commit 4)",
            missing,
        )
    }

    private fun mapper(method: String, argCount: Int): Method {
        val found = truth()::class.java.declaredMethods.firstOrNull {
            it.name == method && it.parameterTypes.size == argCount
        }
        return found ?: throw AssertionError(
            "T917 P5 RED defect (outcome projection): TranslationUiTruth.$method/$argCount " +
                "is missing — the Appendix-A mapper contract pinned by this test is not " +
                "implemented",
        )
    }

    private fun projectManual(
        outcome: SinglePageOutcome?,
        durable: PageDisplayProjection?,
        partial: Boolean = false,
        exhausted: Boolean = false,
        cancelled: Boolean = false,
    ): Any? = mapper("forManualOutcome", 5).apply { isAccessible = true }.invoke(
        truth(),
        outcome,
        durable,
        partial,
        exhausted,
        cancelled,
    )

    private fun projectAuto(slot: AutoSlotState, retryAtEpochMs: Long? = null): Any? =
        mapper("forAutoSlot", 2).apply { isAccessible = true }.invoke(truth(), slot, retryAtEpochMs)

    private fun prop(value: Any?, name: String): Any? {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        return value?.javaClass?.getMethod(getter)?.invoke(value)
    }

    private fun str(value: Any?, prop: String): String = prop(value, prop) as String

    private fun epoch(value: Any?): Long? = prop(value, "retryAtEpochMs") as Long?

    private fun flag(value: Any?, prop: String): Boolean = prop(value, prop) as Boolean

    private fun names(value: Any?, prop: String): Set<String> =
        (prop(value, prop) as Set<*>).map { it.toString() }.toSet()

    private fun word(value: Any?, prop: String): String = prop(value, prop).toString()

    // ------------------------------------------------------------------
    // Manual outcomes (page chip / reader overlay column)
    // ------------------------------------------------------------------

    @Test
    fun `completed manual outcome with committed display projects translated truth`() {
        val truth = projectManual(
            SinglePageOutcome.Completed,
            PageDisplayProjection(PageDisplayState.DISPLAY_READY, displayReady = true, processed = true),
        )
        withClue("committed Completed must project the translated truth") {
            str(truth, "label") shouldBe "Translated."
            word(truth, "severity") shouldBe "INFO"
            word(truth, "retryMode") shouldBe "NONE"
            epoch(truth).shouldBeNull()
            names(truth, "actions") shouldBe setOf("DETAILS")
            flag(truth, "terminalSuccess") shouldBe true
            str(truth, "contentDescription") shouldBe "Page translated and ready to read."
        }
    }

    @Test
    fun `stale completed outcome without durable display may not project success`() {
        // §1.2: no mapper may produce success from a stale callback. A Completed
        // whose durable projection shows no committed result is an unconfirmable
        // success — it must surface the not-saved truth, never "Translated".
        val truth = projectManual(
            SinglePageOutcome.Completed,
            PageDisplayProjection(PageDisplayState.ORIGINAL_ONLY, displayReady = false, processed = false),
        )
        withClue("unconfirmable success must be a visible non-success") {
            str(truth, "label") shouldBe "Translation not saved — retry required."
            word(truth, "severity") shouldBe "WARNING"
            word(truth, "retryMode") shouldBe "EXPLICIT"
            names(truth, "actions") shouldBe setOf("RETRY", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `durable terminal failure outranks a stale completed callback`() {
        val truth = projectManual(
            SinglePageOutcome.Completed,
            PageDisplayProjection(PageDisplayState.FAILED_NO_RESULT, displayReady = false, processed = true),
        )
        withClue("durable failure wins over the late success callback") {
            str(truth, "label") shouldBe "Translation failed — retry available."
            word(truth, "severity") shouldBe "ERROR"
            word(truth, "retryMode") shouldBe "EXPLICIT"
            names(truth, "actions") shouldBe setOf("RETRY", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe "Page translation failed; retry is available."
        }
    }

    @Test
    fun `paused manual outcome keeps its epoch and explicit retry mode`() {
        val truth = projectManual(SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs = 5_000L), null)
        withClue("a governor pause is explicit-retry copy with its epoch retained") {
            str(truth, "label") shouldBe "Paused; explicit retry available."
            word(truth, "severity") shouldBe "WARNING"
            word(truth, "retryMode") shouldBe "EXPLICIT"
            epoch(truth) shouldBe 5_000L
            names(truth, "actions") shouldBe setOf("RETRY", "CANCEL", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `stalled outcome exposes cancel then retry without an automatic claim`() {
        val truth = projectManual(
            SinglePageOutcome.Stalled(pageKey = "p0", stalledSinceEpochMs = 1_000L),
            null,
        )
        withClue("the D8 stall must be visible with safe recovery affordances") {
            str(truth, "label") shouldBe "Translation stalled."
            word(truth, "severity") shouldBe "ERROR"
            word(truth, "retryMode") shouldBe "EXPLICIT"
            names(truth, "actions") shouldBe setOf("CANCEL", "RETRY", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe
                "Translation stalled; the ONNX/native result timer expired while work remains occupied."
        }
    }

    @Test
    fun `retryable failure offers explicit retry and exhausted failure requires manual retry`() {
        val retryable = projectManual(
            SinglePageOutcome.Failed(pageKey = "p0", reason = "provider refused"),
            null,
            exhausted = false,
        )
        withClue("retryable failure keeps explicit retry copy") {
            str(retryable, "label") shouldBe "Translation failed — retry available."
            word(retryable, "severity") shouldBe "ERROR"
            word(retryable, "retryMode") shouldBe "EXPLICIT"
            names(retryable, "actions") shouldBe setOf("RETRY", "DETAILS")
            flag(retryable, "terminalSuccess") shouldBe false
        }
        val d9 = projectManual(
            SinglePageOutcome.Failed(pageKey = "p0", reason = "interrupted"),
            null,
            exhausted = true,
        )
        withClue("the D9 cap is manual-retry-required copy, never automatic") {
            str(d9, "label") shouldBe
                "Paused — repeated interruption before completion; manual retry required."
            word(d9, "severity") shouldBe "WARNING"
            word(d9, "retryMode") shouldBe "MANUAL_REQUIRED"
            epoch(d9).shouldBeNull()
            names(d9, "actions") shouldBe setOf("RETRY", "DETAILS")
            str(d9, "contentDescription") shouldBe
                "Translation paused after repeated interruption; manual retry required."
        }
    }

    @Test
    fun `attached outcome names the owner and starts no duplicate paid work`() {
        val truth = projectManual(SinglePageOutcome.Attached(PageWriteOrigin.BATCH), null)
        withClue("attach must be visible as owner work, never a manual success") {
            str(truth, "label") shouldBe "Translating · batch job."
            word(truth, "severity") shouldBe "PROGRESS"
            word(truth, "retryMode") shouldBe "NONE"
            names(truth, "actions") shouldBe setOf("DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe
                "Waiting for the batch translation job; no duplicate request started."
        }
    }

    @Test
    fun `attached unresolved outcome is visible and never success`() {
        val truth = projectManual(
            SinglePageOutcome.AttachedUnresolved(PageWriteOrigin.AUTO, "attach bound hit"),
            null,
        )
        withClue("attach-unresolved must be a visible non-success") {
            str(truth, "label") shouldBe "Background translation did not finish yet."
            word(truth, "severity") shouldBe "WARNING"
            word(truth, "retryMode") shouldBe "EXPLICIT"
            names(truth, "actions") shouldBe setOf("RETRY", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe
                "Background translation did not finish within the wait; retry is available after the owner releases the page."
        }
    }

    @Test
    fun `persistence rejection projects the not saved truth and never success`() {
        val truth = projectManual(
            SinglePageOutcome.Rejected(null, "Translation could not be saved; retry required"),
            // Even a committed-looking durable record must not turn this into
            // success copy: the CURRENT typed outcome is newer than the store.
            PageDisplayProjection(PageDisplayState.DISPLAY_READY, displayReady = true, processed = true),
        )
        withClue("PERSISTENCE_REJECTED must stay a typed non-success (spec §4.1.3)") {
            str(truth, "label") shouldBe "Translation not saved — retry required."
            word(truth, "severity") shouldBe "WARNING"
            word(truth, "retryMode") shouldBe "EXPLICIT"
            names(truth, "actions") shouldBe setOf("RETRY", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe
                "Translation was produced but could not be saved; no durable result is available. Retry is required."
        }
    }

    @Test
    fun `duplicate rejection projects not started with its reason`() {
        val truth = projectManual(
            SinglePageOutcome.Rejected(null, "page already translating"),
            null,
        )
        withClue("an admission rejection names its reason without retry actions") {
            str(truth, "label") shouldBe "Translation not started — page already translating."
            word(truth, "severity") shouldBe "INFO"
            word(truth, "retryMode") shouldBe "NONE"
            names(truth, "actions") shouldBe setOf("DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `partial refresh with committed display warns without success copy`() {
        val truth = projectManual(
            SinglePageOutcome.Completed,
            PageDisplayProjection(PageDisplayState.DISPLAY_READY, displayReady = true, processed = true),
            partial = true,
        )
        withClue("partial committed work keeps the readable result but warns") {
            str(truth, "label") shouldBe "Partial translation — retry available."
            word(truth, "severity") shouldBe "WARNING"
            word(truth, "retryMode") shouldBe "EXPLICIT"
            names(truth, "actions") shouldBe setOf("RETRY", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `textless terminal counts terminal without translated copy`() {
        val truth = projectManual(
            SinglePageOutcome.Completed,
            PageDisplayProjection(PageDisplayState.TEXTLESS_COMPLETE, displayReady = false, processed = true),
        )
        withClue("textless is a terminal no-op, not a translated page") {
            str(truth, "label") shouldBe "No translatable text."
            word(truth, "severity") shouldBe "INFO"
            word(truth, "retryMode") shouldBe "NONE"
            flag(truth, "terminalSuccess") shouldBe true
            str(truth, "contentDescription") shouldBe "Page processed; no translatable text."
        }
    }

    @Test
    fun `cancelled manual work is acknowledged with an explicit retry`() {
        val truth = projectManual(null, null, cancelled = true)
        withClue("explicit cancellation of paid work must stay visible") {
            str(truth, "label") shouldBe "Translation cancelled."
            word(truth, "severity") shouldBe "INFO"
            word(truth, "retryMode") shouldBe "NONE"
            names(truth, "actions") shouldBe setOf("RETRY", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe
                "Translation cancelled; saved translated pages were kept."
        }
    }

    @Test
    fun `no outcome and no durable truth stays silent`() {
        projectManual(null, null).shouldBeNull()
    }

    @Test
    fun `durable display alone projects translated truth without any outcome`() {
        val truth = projectManual(
            null,
            PageDisplayProjection(PageDisplayState.DISPLAY_READY, displayReady = true, processed = true),
        )
        withClue("§1.2.4: current durable display is authority on its own") {
            str(truth, "label") shouldBe "Translated."
            flag(truth, "terminalSuccess") shouldBe true
        }
    }

    // ------------------------------------------------------------------
    // Rolling auto slots (auto page/status column)
    // ------------------------------------------------------------------

    @Test
    fun `auto deferred slot keeps its retry epoch and never reads as ready`() {
        val deferred = projectAuto(
            AutoSlotState.Deferred(AutoDeferralReason.Memory),
            retryAtEpochMs = 4_500L,
        )
        withClue("the coordinator's retry epoch must survive into the projection") {
            str(deferred, "label") shouldBe "Auto paused; retries automatically when available."
            word(deferred, "severity") shouldBe "WARNING"
            word(deferred, "retryMode") shouldBe "AUTOMATIC"
            epoch(deferred) shouldBe 4_500L
            flag(deferred, "terminalSuccess") shouldBe false
            str(deferred, "contentDescription") shouldBe
                "Auto translation paused; automatic retry when available."
        }
        val waiting = projectAuto(AutoSlotState.Deferred(AutoDeferralReason.Network))
        withClue("an unknown epoch stays null and automatic") {
            word(waiting, "retryMode") shouldBe "AUTOMATIC"
            epoch(waiting).shouldBeNull()
            flag(waiting, "terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `auto failed slots separate will retry from action required`() {
        val retryable = projectAuto(AutoSlotState.Failed(retryable = true))
        str(retryable, "label") shouldBe "Auto failed — will retry when available."
        word(retryable, "retryMode") shouldBe "AUTOMATIC"
        flag(retryable, "terminalSuccess") shouldBe false

        val terminal = projectAuto(AutoSlotState.Failed(retryable = false))
        str(terminal, "label") shouldBe "Auto failed — action required."
        word(terminal, "severity") shouldBe "ERROR"
        word(terminal, "retryMode") shouldBe "NONE"
        names(terminal, "actions") shouldBe setOf("REVIEW", "DETAILS")
        flag(terminal, "terminalSuccess") shouldBe false
        str(terminal, "contentDescription") shouldBe "Auto translation failed and needs attention."
    }

    @Test
    fun `auto queued and stage slots never count as ready`() {
        projectAuto(AutoSlotState.Queued).let { queued ->
            str(queued, "label") shouldBe "Queued."
            word(queued, "severity") shouldBe "PROGRESS"
            flag(queued, "terminalSuccess") shouldBe false
            str(queued, "contentDescription") shouldBe "Translation queued; work has not started."
        }
        projectAuto(AutoSlotState.ReadingText).let { stage ->
            str(stage, "label") shouldBe "Reading text."
            flag(stage, "terminalSuccess") shouldBe false
            str(stage, "contentDescription") shouldBe "Translation stage: reading text."
        }
        str(projectAuto(AutoSlotState.Cleaning), "label") shouldBe "Cleaning bubbles."
        str(projectAuto(AutoSlotState.Translating), "label") shouldBe "Translating text."
        str(projectAuto(AutoSlotState.Rendering), "label") shouldBe "Finishing page."

        val ready = projectAuto(AutoSlotState.Ready)
        str(ready, "label") shouldBe "Translated."
        flag(ready, "terminalSuccess") shouldBe true
    }

    // ------------------------------------------------------------------
    // Scheduler exposure (spec §5.2.2) — read-only, bounded, identity-keyed
    // ------------------------------------------------------------------

    private fun schedulerAccessor(): Method {
        val found = TranslationScheduler::class.java.methods.firstOrNull { it.name == "manualOutcomeFor" }
        return found ?: throw AssertionError(
            "T917 P5 RED defect (outcome exposure, spec §5.2.2): TranslationScheduler has no " +
                "read-only manualOutcomeFor accessor — the bounded manualOutcomes map stays " +
                "private and no UI surface can show the last typed manual outcome",
        )
    }

    private fun schedulerCapField(): java.lang.reflect.Field {
        val candidate = (
            TranslationScheduler.Companion::class.java.declaredFields +
                TranslationScheduler::class.java.declaredFields
            ).firstOrNull { it.name == "MANUAL_OUTCOME_MAP_CAP" }
        return candidate ?: throw AssertionError(
            "T917 P5 RED defect (outcome exposure): the manual outcome map cap constant " +
                "MANUAL_OUTCOME_MAP_CAP disappeared — bounded retention is a spec invariant",
        )
    }

    @Test
    fun `scheduler exposes the last bounded manual outcome read-only`() = runBlocking<Unit> {
        val capField = schedulerCapField()
        capField.isAccessible = true
        withClue("the outcome map must keep its bounded cap of 32 entries") {
            (capField.get(null) as Int) shouldBe 32
        }

        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            preRegisterInStore = false,
            httpRenderTimeoutMs = 150L,
        )
        try {
            h.installGraphicsShims()
            h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
            val accessor = schedulerAccessor()
            h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            h.tapManual("p0")

            // While the intent is still running, no outcome exists yet — the
            // accessor must not fabricate one, and another chapter's key must
            // never observe this page's outcome (identity fence).
            accessor.invoke(h.scheduler, 11L, "p0").shouldBeNull()
            accessor.invoke(h.scheduler, TranslationCoexistenceHarness.CHAPTER_ID, "p0").shouldBeNull()

            // Join BEFORE releasing: the injected 150ms result timer must fire
            // while the transport is parked, deterministically typing the
            // non-success (a release-first race could complete the phase).
            val job = h.capturedManualJob("p0")
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }
            h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")

            val outcome = accessor.invoke(h.scheduler, TranslationCoexistenceHarness.CHAPTER_ID, "p0")
            withClue("the last typed outcome must be readable through the public accessor") {
                (outcome is SinglePageOutcome.Failed) shouldBe true
                (outcome as SinglePageOutcome.Failed).reason.contains("HTTP+render") shouldBe true
            }
            accessor.invoke(h.scheduler, 11L, "p0").shouldBeNull()
        } finally {
            h.removeGraphicsShims()
            h.close()
        }
    }
}
