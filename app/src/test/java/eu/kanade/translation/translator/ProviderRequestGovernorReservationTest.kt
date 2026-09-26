package eu.kanade.translation.translator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 *  Interactive token reserve behavior.
 *
 * Pure unit tests over the real [ProviderRequestGovernor] with a virtual
 * clock. The reserve: while a bucket holds at least one INTERACTIVE waiter, a
 * BACKGROUND waiter's effective window limits shrink to
 * `requestsPerMinute - 1` requests and `tokensPerMinute * (1 - fraction)`
 * tokens, so a draining batch cannot consume the reader's headroom. Interactive
 * requests always see the full window; an admitted reservation is never
 * revoked; a single oversized request stays admissible.
 *
 * Determinism: the fixture policy uses `interactiveMaxAgeMs = 0`, which makes
 * the governor's starving-background rule select a waiting BACKGROUND waiter
 * over INTERACTIVE ones on every evaluation — the background's decisive
 * evaluation provably happens while the interactive waiter is still in the
 * bucket, with no real-time or interleaving race. All waits run on the virtual
 * clock (advance + yield), so nothing sleeps for real time.
 *
 * Before this reserve existed, test 1 admitted a background request on the
 * full window while an interactive waiter waited, and test 5 found no
 * `interactiveTokenReserveFraction` parameter to validate. Tests 2–4 are non-regression guards pinning shapes the
 * reserve must not change.
 */
class ProviderRequestGovernorReservationTest {

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    /**
     * Virtual clock: `delay` advances the clock and yields, so concurrent
     * waiter loops interleave fairly on the test scheduler without ever
     * sleeping for real time.
     */
    internal class VirtualGovernorClock : ProviderRequestClock {
        var now: Long = 0L
        val delays = mutableListOf<Long>()

        override fun nowEpochMs(): Long = now

        override suspend fun delay(millis: Long) {
            delays += millis
            now += millis
            yield()
        }
    }

    /**
     * Builds the §2.1 policy. The reserve fraction is the LAST constructor
     * parameter; at RED the parameter does not exist and this bridge fails
     * with an assertion naming the missing seam (never a compile-time
     * dependency on commit 4).
     */
    internal fun d6PolicyWithReserve(
        requestsPerMinute: Int = 8,
        tokensPerMinute: Int,
        maxInFlight: Int = 8,
        maxForegroundWaitMs: Long = 30_000L,
        interactiveMaxAgeMs: Long = 0L,
        pollIntervalMs: Long = 25L,
        windowMs: Long = 60_000L,
        fraction: Double = 0.2,
    ): ProviderQuotaPolicy {
        val ctor = ProviderQuotaPolicy::class.java.constructors
            .firstOrNull { it.parameterTypes.size == 10 }
            ?: throw AssertionError(
                "T917 D6 RED defect: interactiveTokenReserveFraction is not configurable — " +
                    "the §2.1 policy reserve seam is missing",
            )
        val args = arrayOf<Any>(
            requestsPerMinute,
            tokensPerMinute,
            0L, // minimumSpacingMs
            maxInFlight,
            maxForegroundWaitMs,
            interactiveMaxAgeMs,
            pollIntervalMs,
            0L, // quotaCooldownMs
            windowMs,
            fraction,
        )
        return try {
            ctor.newInstance(*args) as ProviderQuotaPolicy
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // A policy validation failure (e.g. the fraction range guard) must
            // surface as its real exception so shouldThrow can observe it.
            val target = e.targetException ?: e.cause ?: e
            throw target
        }
    }

    private fun metadata(
        priority: AdmissionPriority,
        input: Int,
        output: Int,
        operation: String,
    ) = ProviderRequestMetadata(
        key = ProviderRequestKey("d6", "model", "scope"),
        estimatedInputTokens = input,
        reservedOutputTokens = output,
        operation = operation,
        priority = priority,
    )

    /**
     * The CURRENT 9-parameter policy for the non-regression guards (2–4):
     * they pin governor shapes the reserve must not change, so they must NOT
     * depend on the §2.1 seam and stay green at RED.
     */
    internal fun plainD6Policy(
        requestsPerMinute: Int = 8,
        tokensPerMinute: Int,
        maxInFlight: Int = 8,
        maxForegroundWaitMs: Long = 30_000L,
        interactiveMaxAgeMs: Long = 0L,
        pollIntervalMs: Long = 25L,
        windowMs: Long = 60_000L,
    ) = ProviderQuotaPolicy(
        requestsPerMinute = requestsPerMinute,
        tokensPerMinute = tokensPerMinute,
        minimumSpacingMs = 0L,
        maxInFlight = maxInFlight,
        maxForegroundWaitMs = maxForegroundWaitMs,
        interactiveMaxAgeMs = interactiveMaxAgeMs,
        pollIntervalMs = pollIntervalMs,
        quotaCooldownMs = 0L,
        windowMs = windowMs,
    )

    // ------------------------------------------------------------------
    // 1. THE reserve: a background request defers on the reduced line
    // ------------------------------------------------------------------

    @Test
    fun `background request defers on the reserve line while an interactive waiter waits`() = runTest {
        val clock = VirtualGovernorClock()
        val governor = ProviderRequestGovernor(
            policy = { d6PolicyWithReserve(tokensPerMinute = 12, maxInFlight = 1) },
            clock = clock,
        )

        // An interactive request is admitted and billed 6 of 12 tokens; its
        // in-flight slot parks the bucket's single inFlight seat.
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            governor.executeValue(metadata(AdmissionPriority.INTERACTIVE, 4, 2, "first")) {
                releaseFirst.await()
                "first-done"
            }
        }

        // A second INTERACTIVE request (5 tokens) blocks on the in-flight seat
        // and STAYS in the bucket as the reserve's precondition.
        val interactiveWaiting = async {
            governor.admit(metadata(AdmissionPriority.INTERACTIVE, 3, 2, "waiting"))
        }
        val background = async {
            governor.admit(metadata(AdmissionPriority.BACKGROUND, 2, 2, "batch"))
        }
        // Let both waiters register before the in-flight seat frees.
        repeat(8) { yield() }
        releaseFirst.complete(Unit)

        val backgroundDecision = background.await()

        val deferred = backgroundDecision as? ProviderAdmissionDecision.Deferred
        withClue(
            "D6 §2.1: while an interactive waiter waits, a background request must defer on the " +
                "REDUCED token line (12 * 0.8 = 9): billed 6 + background 4 = 10 > 9. RED defect: " +
                "the full window admits the background (10 <= 12) and the reader's headroom is " +
                "consumed by the batch",
        ) {
            if (deferred == null) {
                throw AssertionError(
                    "D6 §2.1 RED defect: background decision was $backgroundDecision, expected Deferred",
                )
            }
        }
        withClue("D6 §2.1: the defer must point at the window boundary of the billed reservation") {
            deferred!!.nextEligibleRetryAtEpochMs shouldBe 60_000L
        }

        // The interactive waiter rides the FULL window (11 <= 12) and is
        // admitted even though the background is still held at the reserve.
        withClue("D6 §2.1: the reserve must never shrink the interactive window") {
            interactiveWaiting.await().shouldBeInstanceOf<ProviderAdmissionDecision.Admitted>()
        }
        first.await()
        clock.now shouldNotBe 0L
    }

    // ------------------------------------------------------------------
    // 2. interactive requests always see the FULL window (guard)
    // ------------------------------------------------------------------

    @Test
    fun `interactive request is admitted on the full window`() = runTest {
        val governor = ProviderRequestGovernor(
            policy = { plainD6Policy(tokensPerMinute = 12, maxForegroundWaitMs = 60_000L) },
        )
        governor.admit(metadata(AdmissionPriority.INTERACTIVE, 4, 2, "first"))

        // 6 + 5 = 11 <= 12: the interactive request rides the FULL window.
        val decision = governor.admit(
            metadata(AdmissionPriority.INTERACTIVE, 3, 2, "reader"),
        )

        withClue("D6 §2.1: the reserve must never shrink the interactive window") {
            decision.shouldBeInstanceOf<ProviderAdmissionDecision.Admitted>()
        }
    }

    // ------------------------------------------------------------------
    // 3. admitted reservations are not revoked (guard)
    // ------------------------------------------------------------------

    @Test
    fun `already admitted background reservation is not revoked by a later interactive waiter`() = runTest {
        val governor = ProviderRequestGovernor(
            policy = { plainD6Policy(tokensPerMinute = 12, maxForegroundWaitMs = 1_000L) },
        )
        // Background billed first — no interactive waiter existed at admission.
        governor.admit(metadata(AdmissionPriority.BACKGROUND, 4, 2, "batch"))

        // A later interactive request must NOT claw back the reservation: the
        // plain window rules decide (6 + 7 = 13 > 12 -> it simply waits).
        val decision = governor.admit(
            metadata(AdmissionPriority.INTERACTIVE, 4, 3, "reader"),
        )

        withClue(
            "D6 §2.1: no revocation — an admitted reservation stays; the later request waits on " +
                "the window like any other",
        ) {
            decision.shouldBeInstanceOf<ProviderAdmissionDecision.Deferred>()
        }
    }

    // ------------------------------------------------------------------
    // 4. single oversized request stays admissible (guard)
    // ------------------------------------------------------------------

    @Test
    fun `single oversized background request is admitted under the reserve policy`() = runTest {
        val governor = ProviderRequestGovernor(
            policy = { plainD6Policy(tokensPerMinute = 10) },
        )
        // A lone oversized request (cost 50 > tokensPerMinute 10) must admit —
        // a naive unconditional reduction of the limit below the request's own
        // cost would deadlock large envelopes.
        val decision = governor.admit(
            metadata(AdmissionPriority.BACKGROUND, 30, 20, "batch"),
        )

        withClue("D6 §2.1: the reduced limit must never fall below a single request's own cost") {
            decision.shouldBeInstanceOf<ProviderAdmissionDecision.Admitted>()
        }
    }

    // ------------------------------------------------------------------
    // 5. fraction validation
    // ------------------------------------------------------------------

    @Test
    fun `reserve fraction must be within zero exclusive to one inclusive`() {
        withClue("D6 §2.1: a non-positive fraction would disable or invert the reserve") {
            shouldThrow<IllegalArgumentException> {
                d6PolicyWithReserve(tokensPerMinute = 100, fraction = 0.0)
            }
        }
        withClue("D6 §2.1: a fraction above 1 would starve background lanes entirely") {
            shouldThrow<IllegalArgumentException> {
                d6PolicyWithReserve(tokensPerMinute = 100, fraction = 1.5)
            }
        }
        // 1.0 is legal: background traffic then only rides the request line.
        d6PolicyWithReserve(tokensPerMinute = 100, fraction = 1.0)
    }
}
