package eu.kanade.translation.coexistence

import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.ProviderQuotaPolicy
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.translator.currentProviderRequestPriority
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.scheduling.TranslationScheduler
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reader-only provider-window behavior.
 *
 * Session mutual exclusion removes the old batch/manual interleaving
 * choreography. The provider governor contract remains: reader work carries
 * INTERACTIVE priority, a request that cannot fit the current window becomes a
 * typed pause, and the scheduler records exactly one outcome.
 */
class ForegroundProviderWindowFairnessTest {

    companion object {
        private const val AWAIT_TIMEOUT_MS = 10_000L
    }

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
            val (input, output) = costs.getValue(pageKey)
            val priority = currentProviderRequestPriority()
            observedPriorities[pageKey] = priority
            val metadata = ProviderRequestMetadata(
                key = ProviderRequestKey("d6", "reader", "window"),
                estimatedInputTokens = input,
                reservedOutputTokens = output,
                operation = pageKey,
                priority = priority,
            )
            governor.executeValue(metadata) {
                admittedEvents.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
                delegate.translate(pages)
                completedEvents.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            }
        }

        override fun close() = delegate.close()

        companion object {
            val costs = mapOf(
                "q0" to (1 to 1),
                "q1" to (4 to 3),
            )
        }
    }

    @Test
    fun `reader-only provider window pauses an over-budget request with typed outcome`() =
        runBlocking<Unit> {
            val store = ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = emptyMap(),
            )
            val harness = TranslationCoexistenceHarness.create(
                pageKeys = listOf("q0", "q1"),
                preRegisterInStore = false,
                storeOverride = store,
            )
            val governor = ProviderRequestGovernor(
                policy = {
                    ProviderQuotaPolicy(
                        requestsPerMinute = 8,
                        tokensPerMinute = 8,
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
            harness.registerReaderStream(harness.CHAPTER_ID, "q0")
            harness.registerReaderStream(harness.CHAPTER_ID, "q1")
            harness.installGraphicsShims()
            try {
                val q0WallMs = System.currentTimeMillis()
                harness.tapManual("q0")
                transport.awaitAdmitted("q0")
                transport.awaitPaidCallCompleted("q0")

                withClue("reader requests must carry interactive priority") {
                    transport.observedPriorities["q0"] shouldBe AdmissionPriority.INTERACTIVE
                }

                harness.tapManual("q1")
                val q1Job = harness.capturedManualJob("q1")
                withTimeout(AWAIT_TIMEOUT_MS) { q1Job.join() }

                withClue("the window-exhausted request performs one governed attempt") {
                    transport.callsFor("q1") shouldBe 1
                }
                val key = harness.CHAPTER_ID.toString() + ":q1"
                val outcome = readManualOutcome(harness.scheduler, key)
                withClue("window exhaustion is recorded as a typed pause") {
                    outcome!!::class.simpleName shouldBe "Paused"
                }
                withClue("the pause carries the current window boundary") {
                    val retryAt = pausedRetryAt(outcome!!)
                    (retryAt!! >= q0WallMs && retryAt <= q0WallMs + 62_000L) shouldBe true
                }
            } finally {
                harness.removeGraphicsShims()
                harness.close()
            }
        }

    private fun readManualOutcome(scheduler: TranslationScheduler, jobKey: String): Any? {
        var cls: Class<*>? = scheduler.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField("manualOutcomes")
                field.isAccessible = true
                val map =
                    @Suppress("UNCHECKED_CAST")
                    (field.get(scheduler) as Map<String, Any>)
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
