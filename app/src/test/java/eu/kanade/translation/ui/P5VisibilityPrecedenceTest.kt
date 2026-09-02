package eu.kanade.translation.ui

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T917 Phase 5 (spec §1.2, §3.1, §6.2 commit 9) — RED tests for the
 * chapter-level visibility budget and stale-state precedence.
 *
 * Named defects pinned here:
 *
 *  1. No pure visibility gate exists: every surface (drawer, notification,
 *     reader, manga screen) currently decides "should I announce this?"
 *     ad hoc, so self-healing scheduler transitions spam failures while
 *     cancellations of paid work can pass in silence.
 *  2. A repeated identical durable failure is re-announced on every
 *     emission instead of being coalesced (spec §3.1 "ordinary UI
 *     coalescing" is explicitly silent).
 *  3. A committed readable display with a failed candidate refresh is
 *     surfaced as a red ERROR over the readable image instead of the
 *     spec's "ready with warnings" truth.
 *  4. A newer durable terminal state can be contradicted by an older
 *     failure callback: the surfaced truth must be the newer durable
 *     terminal, never the stale failure.
 */
class P5VisibilityPrecedenceTest {

    // ------------------------------------------------------------------
    // Reflective access to the (new) pure gate
    // ------------------------------------------------------------------

    private fun callGate(
        previous: TranslationProgressSnapshot?,
        current: TranslationProgressSnapshot?,
    ): Any {
        val defect =
            "T917 P5 RED defect (visibility budget): TranslationUiTruth has no " +
                "chapterSurfaceDecision gate — self-healing transitions, pauses, durable " +
                "failures, partial results, and cancellations of paid work are all decided " +
                "ad hoc per surface, so silence and visibility are inconsistent (spec §3.1)"
        val instance = try {
            Class.forName("eu.kanade.translation.ui.TranslationUiTruth")
                .getDeclaredField("INSTANCE").get(null)
        } catch (missing: ReflectiveOperationException) {
            throw AssertionError(
                "T917 P5 RED defect: TranslationUiTruth went missing — commit 4 regression",
                missing,
            )
        }
        val found = instance::class.java.declaredMethods.firstOrNull {
            it.name == "chapterSurfaceDecision" && it.parameterTypes.size == 2
        }
        return found?.apply { isAccessible = true }?.invoke(instance, previous, current)
            ?: throw AssertionError(defect)
    }

    private fun prop(value: Any?, name: String): Any? =
        value?.javaClass?.getMethod("get" + name.replaceFirstChar { it.uppercase() })?.invoke(value)

    private fun visibilityOf(decision: Any): String = prop(decision, "visibility").toString()

    private fun truthOf(decision: Any): PageUiTruth =
        prop(decision, "truth") as? PageUiTruth
            ?: throw AssertionError(
                "T917 P5 RED defect (visibility budget): chapterSurfaceDecision carries no " +
                    "PageUiTruth — surfaces cannot agree on wording and severity",
            )

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun page(
        key: String,
        stage: TranslationProgressStage,
        displayReady: Boolean = false,
        partial: Boolean = false,
    ): TranslationProgressSnapshot.Page = TranslationProgressSnapshot.Page(
        pageKey = key,
        index = 1,
        stage = stage,
        displayReady = displayReady,
        partial = partial,
    )

    private fun snapshot(
        state: Translation.State,
        failedCount: Int = 0,
        pages: List<TranslationProgressSnapshot.Page> = emptyList(),
        aborted: Boolean = false,
        abortedReason: String? = null,
        nonDurableFailure: Boolean = false,
        nonDurableFailureReason: String? = null,
        pauseReason: String? = null,
    ): TranslationProgressSnapshot = TranslationProgressSnapshot.empty(1L, state).copy(
        failedCount = failedCount,
        pages = pages,
        aborted = aborted,
        abortedReason = abortedReason,
        nonDurableFailure = nonDurableFailure,
        nonDurableFailureReason = nonDurableFailureReason,
        pauseReason = pauseReason,
    )

    // ------------------------------------------------------------------
    // Silence: self-healing and coalescing (spec §3.1)
    // ------------------------------------------------------------------

    @Test
    fun `steady running progress stays silent`() {
        val previous = snapshot(Translation.State.TRANSLATING, failedCount = 0)
        val current = snapshot(Translation.State.TRANSLATING, failedCount = 0)
        val decision = callGate(previous, current)
        withClue("ordinary progress coalescing is implementation maintenance, not an outcome") {
            visibilityOf(decision) shouldBe "SILENCE"
        }
    }

    @Test
    fun `a healed failure recovers in silence`() {
        val previous = snapshot(Translation.State.TRANSLATING, failedCount = 1)
        val current = snapshot(Translation.State.TRANSLATING, failedCount = 0)
        val decision = callGate(previous, current)
        withClue(
            "a self-healing retry that resolved the failure must not be announced as a " +
                "failure (or at all) — the surfaces show steady progress truth",
        ) {
            visibilityOf(decision) shouldBe "SILENCE"
        }
    }

    @Test
    fun `a repeated identical durable failure is coalesced to silence`() {
        val previous = snapshot(Translation.State.ERROR, failedCount = 1)
        val current = snapshot(Translation.State.ERROR, failedCount = 1)
        val decision = callGate(previous, current)
        withClue("an already-surfaced unchanged failure is re-announcing, not new truth") {
            visibilityOf(decision) shouldBe "SILENCE"
        }
    }

    @Test
    fun `a null current snapshot stays silent`() {
        val previous = snapshot(Translation.State.TRANSLATING)
        val decision = callGate(previous, null)
        visibilityOf(decision) shouldBe "SILENCE"
    }

    // ------------------------------------------------------------------
    // Visible: decisions, pauses, durable failures, partials, cancellation
    // ------------------------------------------------------------------

    @Test
    fun `a new durable failure surfaces`() {
        val previous = snapshot(Translation.State.TRANSLATING, failedCount = 0)
        val current = snapshot(Translation.State.ERROR, failedCount = 1)
        val decision = callGate(previous, current)
        withClue("durable failures are never silent (spec §3.1)") {
            visibilityOf(decision) shouldBe "SURFACE"
        }
    }

    @Test
    fun `a candidate failure over a committed display warns instead of erroring`() {
        val previous = snapshot(Translation.State.TRANSLATING, failedCount = 0)
        val current = snapshot(
            Translation.State.ERROR,
            failedCount = 1,
            pages = listOf(
                page("p0", TranslationProgressStage.DONE, displayReady = true),
                page("p1", TranslationProgressStage.FAILED),
            ),
        )
        val decision = callGate(previous, current)
        val truth = truthOf(decision)
        withClue("committed display + failed candidate is 'ready with warnings', never a red error over the readable image") {
            visibilityOf(decision) shouldBe "SURFACE"
            truth.severity shouldBe UiSeverity.WARNING
            truth.contentDescription.lowercase().contains("warning") shouldBe true
            truth.contentDescription.lowercase().contains("ready") shouldBe true
        }
    }

    @Test
    fun `a durable failure without committed display surfaces as error`() {
        val previous = snapshot(Translation.State.TRANSLATING, failedCount = 0)
        val current = snapshot(Translation.State.ERROR, failedCount = 1)
        val truth = truthOf(callGate(previous, current))
        withClue("no committed display means the failure truth is a genuine error") {
            truth.severity shouldBe UiSeverity.ERROR
        }
    }

    @Test
    fun `a persistent pause surfaces with its reason`() {
        val previous = snapshot(Translation.State.TRANSLATING)
        val current = snapshot(Translation.State.PAUSED, pauseReason = "provider rate limited")
        val decision = callGate(previous, current)
        val truth = truthOf(decision)
        withClue("pauses are user outcomes, never silent") {
            visibilityOf(decision) shouldBe "SURFACE"
            truth.contentDescription.lowercase().contains("paused") shouldBe true
        }
    }

    @Test
    fun `a new partial result surfaces`() {
        val previous = snapshot(Translation.State.TRANSLATING)
        val current = snapshot(
            Translation.State.TRANSLATING,
            pages = listOf(page("p0", TranslationProgressStage.DONE, displayReady = true, partial = true)),
        )
        val decision = callGate(previous, current)
        withClue("partial results are visible, never silently counted as complete") {
            visibilityOf(decision) shouldBe "SURFACE"
        }
    }

    @Test
    fun `explicit cancellation of paid work surfaces`() {
        val previous = snapshot(Translation.State.TRANSLATING)
        val current = snapshot(
            Translation.State.ERROR,
            aborted = true,
            abortedReason = "user stop requested",
        )
        val decision = callGate(previous, current)
        val truth = truthOf(decision)
        withClue("cancellation of paid work must be acknowledged, not silent") {
            visibilityOf(decision) shouldBe "SURFACE"
            truth.contentDescription.lowercase().contains("cancelled") shouldBe true
        }
    }

    @Test
    fun `a publication rejection surfaces without success copy`() {
        val previous = snapshot(Translation.State.TRANSLATING)
        val current = snapshot(
            Translation.State.TRANSLATING,
            nonDurableFailure = true,
            nonDurableFailureReason = "Translation could not be saved; retry required",
        )
        val decision = callGate(previous, current)
        val truth = truthOf(decision)
        withClue("the not-saved truth suppresses any success wording (spec §4, condition C)") {
            visibilityOf(decision) shouldBe "SURFACE"
            truth.label shouldBe "Translation not saved — retry required."
        }
    }

    // ------------------------------------------------------------------
    // Stale-state precedence: newer durable state wins (spec §1.2 rule 6)
    // ------------------------------------------------------------------

    @Test
    fun `newer durable terminal supersedes an older failure snapshot`() {
        val previous = snapshot(Translation.State.ERROR, failedCount = 1)
        val current = snapshot(
            Translation.State.TRANSLATED,
            failedCount = 0,
            pages = listOf(page("p0", TranslationProgressStage.DONE, displayReady = true)),
        )
        val decision = callGate(previous, current)
        val truth = truthOf(decision)
        withClue("an old failure callback may never replace a newer durable terminal state") {
            visibilityOf(decision) shouldBe "SURFACE"
            truth.contentDescription shouldBe "Chapter translation ready"
            truth.label.lowercase().contains("failed") shouldBe false
            truth.severity shouldBe UiSeverity.INFO
        }
    }
}
