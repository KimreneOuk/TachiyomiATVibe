package eu.kanade.translation.presentation

import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.persistence.PageWriteOrigin
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Tests for the pure
 * Appendix-A state â†’ surface mapper and the bounded manual-outcome exposure.
 *
 * Reflection keeps the assertions focused on the truth record and reports a
 * missing contract clearly instead of failing at compile time. Inputs are only
 * existing typed values ([SinglePageOutcome], [PageDisplayProjection]); the
 * output is read reflectively as a truth record with:
 *
 *  - `label`               semantic English-source label (localized by surfaces)
 *  - `severity`            INFO / PROGRESS / WARNING / ERROR
 *  - `retryMode`           NONE / AUTOMATIC / EXPLICIT / MANUAL_REQUIRED
 *  - `retryAtEpochMs`      pause epoch when known
 *  - `actions`             RETRY / CANCEL / DETAILS / REVIEW subset
 *  - `terminalSuccess`     may count as terminal translated success
 *  - `contentDescription`  accessibility description
 *
 * Mapper behavior covered here:
 * manual — `TranslationUiTruth.forManualOutcome(outcome, durable, partial,
 * exhausted, cancelled)`. Since the Phase 4 S3 cutover the reader has no
 * auto-slot inputs: per-page truth comes from `forManualOutcome` over the
 * queue's typed outcomes plus the display projection. A stale/unconfirmable
 * success may never project "Translated" (Â§1.2: no mapper may produce success
 * from a stale callback).
 */
class P5OutcomeProjectionTest {

    // ------------------------------------------------------------------
    // Reflective access keeps the tests independent of mapper visibility.
    // ------------------------------------------------------------------

    private fun truth(): Any = try {
        val clazz = Class.forName("eu.kanade.translation.presentation.TranslationUiTruth")
        clazz.getDeclaredField("INSTANCE").get(null)
    } catch (missing: ReflectiveOperationException) {
        throw AssertionError(
            "T917 P5 RED defect (outcome projection, commit 3/4): the pure Appendix-A " +
                "stateâ†’surface mapper eu.kanade.translation.presentation.TranslationUiTruth does not " +
                "exist — typed outcomes, durable projections, and auto slots have no " +
                "unified truthful UI projection yet (spec Â§1.2, Â§6.2 commit 4)",
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
        // Â§1.2: no mapper may produce success from a stale callback. A Completed
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
        withClue("batch ownership must surface the session switch truth, never a manual success") {
            str(truth, "label") shouldBe "Batch translation is pausing; confirm switch to reader."
            word(truth, "severity") shouldBe "WARNING"
            word(truth, "retryMode") shouldBe "NONE"
            names(truth, "actions") shouldBe setOf("REVIEW", "DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe
                "Batch translation is pausing before reader translation can start; confirm the switch."
        }
    }

    @Test
    fun `persistence rejection projects the not saved truth and never success`() {
        val truth = projectManual(
            SinglePageOutcome.Rejected(
                null,
                PageRejectionKind.PERSISTENCE_FAILED,
                "Translation could not be saved; retry required",
            ),
            // Even a committed-looking durable record must not turn this into
            // success copy: the CURRENT typed outcome is newer than the store.
            PageDisplayProjection(PageDisplayState.DISPLAY_READY, displayReady = true, processed = true),
        )
        withClue("PERSISTENCE_REJECTED must stay a typed non-success (spec Â§4.1.3)") {
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
    fun `duplicate rejection projects fixed safe copy without diagnostic text`() {
        val truth = projectManual(
            SinglePageOutcome.Rejected(
                null,
                PageRejectionKind.ALREADY_OWNED,
                "page already translating",
            ),
            null,
        )
        withClue("an admission rejection projects fixed safe copy without retry actions") {
            str(truth, "label") shouldBe "Translation not started — a translation job is already active."
            word(truth, "severity") shouldBe "INFO"
            word(truth, "retryMode") shouldBe "NONE"
            names(truth, "actions") shouldBe setOf("DETAILS")
            flag(truth, "terminalSuccess") shouldBe false
            str(truth, "contentDescription") shouldBe
                "Translation was not started because a translation job is already active."
        }
    }

    @Test
    fun `same kind with divergent diagnostic detail projects identical truth`() {
        val withDetail = projectManual(
            SinglePageOutcome.Rejected(null, PageRejectionKind.ALREADY_OWNED, "internal lease token 7f3a"),
            null,
        )
        val withoutDetail = projectManual(
            SinglePageOutcome.Rejected(null, PageRejectionKind.ALREADY_OWNED, null),
            null,
        )
        withClue("diagnostic detail is internal-only: UI truth depends on the category alone") {
            str(withDetail, "label") shouldBe str(withoutDetail, "label")
            str(withDetail, "contentDescription") shouldBe str(withoutDetail, "contentDescription")
            word(withDetail, "severity") shouldBe word(withoutDetail, "severity")
            word(withDetail, "retryMode") shouldBe word(withoutDetail, "retryMode")
            names(withDetail, "actions") shouldBe names(withoutDetail, "actions")
            str(withDetail, "label").contains("internal") shouldBe false
            str(withDetail, "contentDescription").contains("7f3a") shouldBe false
        }
    }

    @Test
    fun `session denial and ownerless rejection project their fixed categories`() {
        val session = projectManual(
            SinglePageOutcome.Rejected(null, PageRejectionKind.SESSION_DENIED, "reader session rejected: BATCH_ACTIVE"),
            null,
        )
        withClue("session denial uses fixed session copy, never interpolated detail") {
            str(session, "label") shouldBe "Translation not started — the reader session is busy."
            word(session, "severity") shouldBe "INFO"
            names(session, "actions") shouldBe setOf("DETAILS")
            str(session, "contentDescription") shouldBe
                "Translation was not started because the reader session is busy."
            str(session, "label").contains("BATCH_ACTIVE") shouldBe false
        }
        val other = projectManual(
            SinglePageOutcome.Rejected(null, PageRejectionKind.OTHER, "unknown lease denial cause"),
            null,
        )
        withClue("owner-less generic denial uses the generic fixed copy") {
            str(other, "label") shouldBe "Translation not started."
            word(other, "severity") shouldBe "INFO"
            word(other, "retryMode") shouldBe "NONE"
            names(other, "actions") shouldBe setOf("DETAILS")
            str(other, "contentDescription") shouldBe "Translation was not started."
            str(other, "label").contains("unknown") shouldBe false
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
        withClue("Â§1.2.4: current durable display is authority on its own") {
            str(truth, "label") shouldBe "Translated."
            flag(truth, "terminalSuccess") shouldBe true
        }
    }

    // ------------------------------------------------------------------
}
