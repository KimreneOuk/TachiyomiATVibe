package eu.kanade.translation.milestone

import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.ProviderQuotaPolicy
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.providers.OpenAiCompatibleTranslator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ProviderHeadroomTest {

    @Test
    fun `sse response parsing handles chunked streaming deltas and ignores comments`() {
        val ssePayload = """
            : keep-alive heartbeat
            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"Once upon "}}]}

            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"a time "}}]}
            : intermediate comment
            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"in a manga."}}]}

            data: [DONE]
        """.trimIndent()

        val parsed = OpenAiCompatibleTranslator.parseSseResponse(ssePayload)
        parsed shouldBe "Once upon a time in a manga."
    }

    @Test
    fun `sse response parsing supports legacy text format and handles empty frames`() {
        val ssePayload = """
            data: {"choices":[{"text":"Chapter "}]}
            data: {"choices":[{"text":"42"}]}
            data: [DONE]
        """.trimIndent()

        val parsed = OpenAiCompatibleTranslator.parseSseResponse(ssePayload)
        parsed shouldBe "Chapter 42"
    }

    @Test
    fun `provider request governor executes distinct backends concurrently`() {
        runBlocking {
            val governor = ProviderRequestGovernor(
                policy = {
                    ProviderQuotaPolicy(
                        maxInFlight = 1,
                        minimumSpacingMs = 10L,
                    )
                },
            )

            val activeBackends = AtomicInteger(0)
            val maxSimultaneousBackends = AtomicInteger(0)

            val meta1 = ProviderRequestMetadata(
                key = ProviderRequestKey(backend = "lm_studio", model = "qwen-2.5"),
                priority = AdmissionPriority.BACKGROUND,
            )
            val meta2 = ProviderRequestMetadata(
                key = ProviderRequestKey(backend = "gemini", model = "flash"),
                priority = AdmissionPriority.BACKGROUND,
            )

            val job1 = async {
                governor.executeValue(meta1) {
                    val cur = activeBackends.incrementAndGet()
                    maxSimultaneousBackends.updateAndGet { maxOf(it, cur) }
                    delay(100)
                    activeBackends.decrementAndGet()
                    "done1"
                }
            }

            val job2 = async {
                governor.executeValue(meta2) {
                    val cur = activeBackends.incrementAndGet()
                    maxSimultaneousBackends.updateAndGet { maxOf(it, cur) }
                    delay(100)
                    activeBackends.decrementAndGet()
                    "done2"
                }
            }

            job1.await() shouldBe "done1"
            job2.await() shouldBe "done2"

            // Both distinct backends executed concurrently!
            maxSimultaneousBackends.get() shouldBe 2
        }
    }
}
