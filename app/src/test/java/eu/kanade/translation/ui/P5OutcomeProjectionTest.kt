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

    private fun Any?.str(prop: String): String =
        javaClass.getMethod("get${prop.replaceFirstChar { it.uppercase() }}").invoke(this) as String

    private fun Any?.epoch(prop: String = "retryAtEpochMs"): Long? =
        javaClass.getMethod("get${prop.replaceFirstChar { it.uppercase() }}").invoke(this) as Long?

    private fun Any?.flag(prop: String): Boolean =
        javaClass.getMethod("get${prop.replaceFirstChar { it.uppercase() }}").invoke(this) as Boolean

    private fun Any?.names(prop: String): Set<String> =
        (javaClass.getMethod("get${prop.replaceFirstChar { it.uppercase() }}").invoke(this) as Set<*>)
            .map { it.toString() }.toSet()

    private fun Any?.word(prop: String): String =
        javaClass.getMethod("get${prop.replaceFirstChar { it.uppercase() }}").invoke(this).toString()

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
            truth.str("label") shouldBe "Translated."
            truth.word("severity") shouldBe "INFO"
            truth.word("retryMode") shouldBe "NONE"
            truth.epoch().shouldBeNull()
            truth.names("actions") shouldBe setOf("DETAILS")
            truth.flag("terminalSuccess") shouldBe true
            truth.str("contentDescription") shouldBe "Page translated and ready to read."
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
            truth.str("label") shouldBe "Translation not saved — retry required."
            truth.word("severity") shouldBe "WARNING"
            truth.word("retryMode") shouldBe "EXPLICIT"
            truth.names("actions") shouldBe setOf("RETRY", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `durable terminal failure outranks a stale completed callback`() {
        val truth = projectManual(
            SinglePageOutcome.Completed,
            PageDisplayProjection(PageDisplayState.FAILED_NO_RESULT, displayReady = false, processed = true),
        )
        withClue("durable failure wins over the late success callback") {
            truth.str("label") shouldBe "Translation failed — retry available."
            truth.word("severity") shouldBe "ERROR"
            truth.word("retryMode") shouldBe "EXPLICIT"
            truth.names("actions") shouldBe setOf("RETRY", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
            truth.str("contentDescription") shouldBe "Page translation failed; retry is available."
        }
    }

    @Test
    fun `paused manual outcome keeps its epoch and explicit retry mode`() {
        val truth = projectManual(SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs = 5_000L), null)
        withClue("a governor pause is explicit-retry copy with its epoch retained") {
            truth.str("label") shouldBe "Paused; explicit retry available."
            truth.word("severity") shouldBe "WARNING"
            truth.word("retryMode") shouldBe "EXPLICIT"
            truth.epoch() shouldBe 5_000L
            truth.names("actions") shouldBe setOf("RETRY", "CANCEL", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `stalled outcome exposes cancel then retry without an automatic claim`() {
        val truth = projectManual(
            SinglePageOutcome.Stalled(pageKey = "p0", stalledSinceEpochMs = 1_000L),
            null,
        )
        withClue("the D8 stall must be visible with safe recovery affordances") {
            truth.str("label") shouldBe "Translation stalled."
            truth.word("severity") shouldBe "ERROR"
            truth.word("retryMode") shouldBe "EXPLICIT"
            truth.names("actions") shouldBe setOf("CANCEL", "RETRY", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
            truth.str("contentDescription") shouldBe
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
            retryable.str("label") shouldBe "Translation failed — retry available."
            retryable.word("severity") shouldBe "ERROR"
            retryable.word("retryMode") shouldBe "EXPLICIT"
            retryable.names("actions") shouldBe setOf("RETRY", "DETAILS")
            retryable.flag("terminalSuccess") shouldBe false
        }
        val d9 = projectManual(
            SinglePageOutcome.Failed(pageKey = "p0", reason = "interrupted"),
            null,
            exhausted = true,
        )
        withClue("the D9 cap is manual-retry-required copy, never automatic") {
            d9.str("label") shouldBe
                "Paused — repeated interruption before completion; manual retry required."
            d9.word("severity") shouldBe "WARNING"
            d9.word("retryMode") shouldBe "MANUAL_REQUIRED"
            d9.epoch().shouldBeNull()
            d9.names("actions") shouldBe setOf("RETRY", "DETAILS")
            d9.str("contentDescription") shouldBe
                "Translation paused after repeated interruption; manual retry required."
        }
    }

    @Test
    fun `attached outcome names the owner and starts no duplicate paid work`() {
        val truth = projectManual(SinglePageOutcome.Attached(PageWriteOrigin.BATCH), null)
        withClue("attach must be visible as owner work, never a manual success") {
            truth.str("label") shouldBe "Translating · batch job."
            truth.word("severity") shouldBe "PROGRESS"
            truth.word("retryMode") shouldBe "NONE"
            truth.names("actions") shouldBe setOf("DETAILS")
            truth.flag("terminalSuccess") shouldBe false
            truth.str("contentDescription") shouldBe
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
            truth.str("label") shouldBe "Background translation did not finish yet."
            truth.word("severity") shouldBe "WARNING"
            truth.word("retryMode") shouldBe "EXPLICIT"
            truth.names("actions") shouldBe setOf("RETRY", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
            truth.str("contentDescription") shouldBe
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
            truth.str("label") shouldBe "Translation not saved — retry required."
            truth.word("severity") shouldBe "WARNING"
            truth.word("retryMode") shouldBe "EXPLICIT"
            truth.names("actions") shouldBe setOf("RETRY", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
            truth.str("contentDescription") shouldBe
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
            truth.str("label") shouldBe "Translation not started — page already translating."
            truth.word("severity") shouldBe "INFO"
            truth.word("retryMode") shouldBe "NONE"
            truth.names("actions") shouldBe setOf("DETAILS")
            truth.flag("terminalSuccess") shouldBe false
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
            truth.str("label") shouldBe "Partial translation — retry available."
            truth.word("severity") shouldBe "WARNING"
            truth.word("retryMode") shouldBe "EXPLICIT"
            truth.names("actions") shouldBe setOf("RETRY", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `textless terminal counts terminal without translated copy`() {
        val truth = projectManual(
            SinglePageOutcome.Completed,
            PageDisplayProjection(PageDisplayState.TEXTLESS_COMPLETE, displayReady = false, processed = true),
        )
        withClue("textless is a terminal no-op, not a translated page") {
            truth.str("label") shouldBe "No translatable text."
            truth.word("severity") shouldBe "INFO"
            truth.word("retryMode") shouldBe "NONE"
            truth.flag("terminalSuccess") shouldBe true
            truth.str("contentDescription") shouldBe "Page processed; no translatable text."
        }
    }

    @Test
    fun `cancelled manual work is acknowledged with an explicit retry`() {
        val truth = projectManual(null, null, cancelled = true)
        withClue("explicit cancellation of paid work must stay visible") {
            truth.str("label") shouldBe "Translation cancelled."
            truth.word("severity") shouldBe "INFO"
            truth.word("retryMode") shouldBe "NONE"
            truth.names("actions") shouldBe setOf("RETRY", "DETAILS")
            truth.flag("terminalSuccess") shouldBe false
            truth.str("contentDescription") shouldBe
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
            truth.str("label") shouldBe "Translated."
            truth.flag("terminalSuccess") shouldBe true
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
            deferred.str("label") shouldBe "Auto paused; retries automatically when available."
            deferred.word("severity") shouldBe "WARNING"
            deferred.word("retryMode") shouldBe "AUTOMATIC"
            deferred.epoch() shouldBe 4_500L
            deferred.flag("terminalSuccess") shouldBe false
            deferred.str("contentDescription") shouldBe
                "Auto translation paused; automatic retry when available."
        }
        val waiting = projectAuto(AutoSlotState.Deferred(AutoDeferralReason.Network))
        withClue("an unknown epoch stays null and automatic") {
            waiting.word("retryMode") shouldBe "AUTOMATIC"
            waiting.epoch().shouldBeNull()
            waiting.flag("terminalSuccess") shouldBe false
        }
    }

    @Test
    fun `auto failed slots separate will retry from action required`() {
        val retryable = projectAuto(AutoSlotState.Failed(retryable = true))
        retryable.str("label") shouldBe "Auto failed — will retry when available."
        retryable.word("retryMode") shouldBe "AUTOMATIC"
        retryable.flag("terminalSuccess") shouldBe false

        val terminal = projectAuto(AutoSlotState.Failed(retryable = false))
        terminal.str("label") shouldBe "Auto failed — action required."
        terminal.word("severity") shouldBe "ERROR"
        terminal.word("retryMode") shouldBe "NONE"
        terminal.names("actions") shouldBe setOf("REVIEW", "DETAILS")
        terminal.flag("terminalSuccess") shouldBe false
        terminal.str("contentDescription") shouldBe "Auto translation failed and needs attention."
    }

    @Test
    fun `auto queued and stage slots never count as ready`() {
        projectAuto(AutoSlotState.Queued).let { queued ->
            queued.str("label") shouldBe "Queued."
            queued.word("severity") shouldBe "PROGRESS"
            queued.flag("terminalSuccess") shouldBe false
            queued.str("contentDescription") shouldBe "Translation queued; work has not started."
        }
        projectAuto(AutoSlotState.ReadingText).let { stage ->
            stage.str("label") shouldBe "Reading text."
            stage.flag("terminalSuccess") shouldBe false
            stage.str("contentDescription") shouldBe "Translation stage: reading text."
        }
        projectAuto(AutoSlotState.Cleaning).str("label") shouldBe "Cleaning bubbles."
        projectAuto(AutoSlotState.Translating).str("label") shouldBe "Translating text."
        projectAuto(AutoSlotState.Rendering).str("label") shouldBe "Finishing page."

        val ready = projectAuto(AutoSlotState.Ready)
        ready.str("label") shouldBe "Translated."
        ready.flag("terminalSuccess") shouldBe true
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

            h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            val job = h.capturedManualJob("p0")
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }

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
