package eu.kanade.translation.coexistence

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.ProviderRequestGovernor
import eu.kanade.translation.translator.ProviderRequestKey
import eu.kanade.translation.translator.ProviderQuotaPolicy
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.ProviderRequestPausedException
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.currentProviderRequestPriority
import eu.kanade.translation.ocr.TextRecognizerLanguage
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T917 Phase 3 — D6 foreground fairness end-to-end (phase3-design §2.1/§2.2).
 *
 * A batch chapter and a manual chapter share ONE provider window through a
 * governed transport (the REAL production graph; only the paid call is
 * admitted through a real [ProviderRequestGovernor]). Oracles:
 *
 * 1. PRIORITY WIRING (§2.1 precondition): the manual path reaches the
 *    governor as INTERACTIVE (withProviderRequestPriority) while the batch
 *    stays BACKGROUND — the reserve's precondition. (The reserve's own
 *    window math is pinned by the pure-unit
 *    [eu.kanade.translation.translator.ProviderRequestGovernorReservationTest].)
 * 2. WINDOW EXHAUSTION (§2.2a): a manual tap that cannot fit the drained
 *    window fails as a TYPED PAUSE recorded in the scheduler's
 *    `manualOutcomes` (carrying the deferral's retry time), never as a raw
 *    transport exception, and never retried in a loop.
 *
 * RED (committed first, phase3-design §6 step 6): assertion 1 pins a shape
 * that already holds (regression guard for commit 4), while assertion 2 fails
 * naming the missing typed-pause outcome. Determinism: no sleeps, no polling,
 * no parking inside the batch lanes — every wait is a CompletableDeferred, a
 * StateFlow predicate, or a bounded withTimeout that converts to a named
 * assertion.
 */
class D6ForegroundFairnessTest {

    companion object {
        private const val AWAIT_TIMEOUT_MS = 10_000L
        private const val MANUAL_CHAPTER_ID = 20L
    }

    /**
     * Wraps the fake transport so every paid call is admitted through the
     * governor exactly as production bounds it. Deferrals surface as
     * [ProviderRequestPausedException] — the SAME exception the production
     * boundary raises (governor `executeValue`), so the pipeline's typed
     * mapping is exercised unmodified.
     */
    private class GovernedTransport(
        private val delegate: TextTranslator,
        private val governor: ProviderRequestGovernor,
    ) : TextTranslator, Closeable {
        override val fromLang: TextRecognizerLanguage get() = delegate.fromLang
        override val toLang: TextTranslatorLanguage get() = delegate.toLang

        val observedPriorities = ConcurrentHashMap<String, AdmissionPriority>()
        private val admittedEvents = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val completedEvents = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val callCounts = ConcurrentHashMap<String, AtomicInteger>()

        suspend fun awaitAdmitted(pageKey: String) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                admittedEvents.getOrPut(pageKey) { CompletableDeferred() }.await()
            }
        }

        suspend fun awaitPaidCallCompleted(pageKey: String) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                completedEvents.getOrPut(pageKey) { CompletableDeferred() }.await()
            }
        }

        fun callsFor(pageKey: String): Int = callCounts[pageKey]?.get() ?: 0

        override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
            val pageKey = pages.keys.single()
            callCounts.getOrPut(pageKey) { AtomicInteger(0) }.incrementAndGet()
            val (input, output) = costs[pageKey] ?: (1 to 1)
            val priority = currentProviderRequestPriority()
            observedPriorities[pageKey] = priority
            val metadata = ProviderRequestMetadata(
                key = ProviderRequestKey("d6", "fair", "window"),
                estimatedInputTokens = input,
                reservedOutputTokens = output,
                operation = pageKey,
                priority = priority,
            )
            try {
                governor.executeValue(metadata) {
                    admittedEvents.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
                    delegate.translate(pages)
                    completedEvents.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
                }
            } catch (e: ProviderRequestPausedException) {
                throw e
            }
        }

        override fun close() = delegate.close()

        companion object {
            /** Per-page envelopes; p0(4)+q0(2)=6 billed, q1(7) overflows. */
            val costs = mapOf(
                "p0" to (2 to 2),
                "q0" to (1 to 1),
                "q1" to (4 to 3),
            )
        }
    }

    @Test
    fun `manual tap rides the shared window and window exhaustion pauses typed`() = runBlocking<Unit> {
        // EMPTY start: the manual path creates its own page records (a store
        // pre-registered with PENDING pages makes the single-page planner
        // resume-skip — documented harness deviation).
        val manualStore = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
        )
        val harness = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0", "p1"),
            extraStores = mapOf(MANUAL_CHAPTER_ID to manualStore),
        )
        val governor = ProviderRequestGovernor(
            policy = {
                ProviderQuotaPolicy(
                    requestsPerMinute = 8,
                    tokensPerMinute = 12,
                    minimumSpacingMs = 0L,
                    maxInFlight = 4,
                    maxForegroundWaitMs = 30_000L,
                    pollIntervalMs = 25L,
                    quotaCooldownMs = 0L,
                    windowMs = 60_000L,
                )
            },
        )
        val transport = GovernedTransport(harness.fakeTransport, governor)
        TranslationCoexistenceHarness.setField(harness.engineLane, "textTranslator", transport)

        harness.stubChapterPages(listOf("p0"))
        harness.registerReaderStream(MANUAL_CHAPTER_ID, "q0")
        harness.registerReaderStream(MANUAL_CHAPTER_ID, "q1")
        harness.installGraphicsShims()
        var batch: TranslationCoexistenceHarness.BatchRun? = null
        try {
            // ---- 1. the batch drains its page into the shared window ----
            // (SINGLE-page batch: a fully-translated second page parks its
            // native inpaint on its own transport signal and deadlocks the
            // fixture's lane serialization until the ~45s ONNX timeout — a
            // fixture fidelity gap D2/D9 never exercised. One page keeps the
            // batch lane clean; the window math below needs only p0.)
            batch = harness.launchBatch(listOf("p0"))
            transport.awaitAdmitted("p0")
            val p0WallMs = System.currentTimeMillis()

            // ---- 2. the manual tap rides the remaining headroom ----------
            // (The page's display promotion cannot be awaited on this
            // fixture: the fake cleaned image has no real bytes, so the
            // render-side validation can never pass — documented D9
            // deviation. The paid-call completion event is the oracle.)
            harness.tapManual("q0", MANUAL_CHAPTER_ID)
            transport.awaitAdmitted("q0")
            transport.awaitPaidCallCompleted("q0")

            withClue(
                "D6 §2.1 wiring: the manual path must reach the governor as INTERACTIVE and the " +
                    "batch as BACKGROUND — the reserve's precondition",
            ) {
                transport.observedPriorities["q0"] shouldBe AdmissionPriority.INTERACTIVE
                transport.observedPriorities["p0"] shouldBe AdmissionPriority.BACKGROUND
            }

            // ---- 3. window exhaustion is a TYPED pause (§2.2a) -----------
            // p0(4) + q0(2) = 6 billed; q1(7) cannot fit the window.
            harness.tapManual("q1", MANUAL_CHAPTER_ID)
            val q1Job = harness.capturedManualJob("q1", MANUAL_CHAPTER_ID)
            withTimeout(AWAIT_TIMEOUT_MS) { q1Job.join() }

            withClue("D6 §2.2: a typed deferral must not retry the paid call in a loop") {
                transport.callsFor("q1") shouldBe 1
            }
            val outcome = readManualOutcome(harness.scheduler, "${MANUAL_CHAPTER_ID}:q1")
            withClue(
                "T917 D6 RED defect: the window-exhausted manual tap produced NO typed outcome — " +
                    "the §2.2a SinglePageOutcome.Paused mapping into scheduler manualOutcomes is " +
                    "missing, so the reader tap dies as a raw transport failure",
            ) {
                if (outcome == null) {
                    throw AssertionError(
                        "T917 D6 RED defect: manualOutcomes has no entry for 20:q1 — the §2.2a " +
                            "typed pause mapping is missing",
                    )
                }
            }
            withClue("T917 D6 §2.2a defect: the manual outcome must be the typed Paused variant") {
                outcome!!::class.simpleName shouldBe "Paused"
            }
            val retryAt = pausedRetryAt(outcome!!)
            withClue(
                "T917 D6 §2.2a defect: the typed pause must carry the deferral's eligible time",
            ) {
                if (retryAt == null) {
                    throw AssertionError(
                        "T917 D6 §2.2a defect: Paused outcome carries no nextEligibleRetryAtEpochMs",
                    )
                }
            }
            withClue(
                "D6 §2.2a: the pause's retry time must point at the current window boundary " +
                    "(the batch's first admission + one window), not at an unbounded epoch",
            ) {
                (retryAt!! >= p0WallMs && retryAt <= p0WallMs + 62_000L) shouldBe true
            }

            // ---- 4. the batch drains cleanly ------------------------------
            withTimeout(AWAIT_TIMEOUT_MS) { batch?.job?.join() }
        } finally {
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }

    // ------------------------------------------------------------------
    // reflection reads (variants that exist only after commit 4)
    // ------------------------------------------------------------------

    private fun readManualOutcome(scheduler: TranslationScheduler, jobKey: String): Any? {
        var cls: Class<*>? = scheduler.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField("manualOutcomes")
                field.isAccessible = true
                val map = @Suppress("UNCHECKED_CAST") (field.get(scheduler) as Map<String, Any>)
                return map[jobKey]
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("scheduler manualOutcomes field not found")
    }

    private fun pausedRetryAt(outcome: Any): Long? = outcome.javaClass.methods
        .firstOrNull { it.name == "getNextEligibleRetryAtEpochMs" }
        ?.apply { isAccessible = true }
        ?.invoke(outcome) as? Long
}
