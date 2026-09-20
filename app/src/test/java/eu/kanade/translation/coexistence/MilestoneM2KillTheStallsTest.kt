package eu.kanade.translation.coexistence

import eu.kanade.translation.ChapterTranslator
import eu.kanade.translation.model.Translation
import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.ProviderAdmissionDecision
import eu.kanade.translation.translator.ProviderQuotaPolicy
import eu.kanade.translation.translator.ProviderRequestGovernor
import eu.kanade.translation.translator.ProviderRequestKey
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.SystemProviderRequestClock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

class MilestoneM2KillTheStallsTest {

    @Test
    fun `S7 queue steering reorders queued chapters behind active translating chapter`() {
        val ch1 = mockTranslation(1L, Translation.State.TRANSLATING)
        val ch2 = mockTranslation(2L, Translation.State.QUEUE)
        val ch3 = mockTranslation(3L, Translation.State.QUEUE)
        val ch4 = mockTranslation(4L, Translation.State.QUEUE)

        val realTranslator = createTranslatorWithQueue(listOf(ch1, ch2, ch3, ch4))
        realTranslator.prioritizeChapter(4L)

        val updated = realTranslator.queueState.value.map { it.chapter.id }
        updated shouldBe listOf(1L, 4L, 2L, 3L)
    }

    @Test
    fun `S7 queue steering moves target to head when no chapter is actively translating`() {
        val ch1 = mockTranslation(1L, Translation.State.QUEUE)
        val ch2 = mockTranslation(2L, Translation.State.QUEUE)
        val ch3 = mockTranslation(3L, Translation.State.QUEUE)

        val realTranslator = createTranslatorWithQueue(listOf(ch1, ch2, ch3))
        realTranslator.prioritizeChapter(3L)

        val updated = realTranslator.queueState.value.map { it.chapter.id }
        updated shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `S7 queue steering is a no-op when target is actively translating or already at head`() {
        val ch1 = mockTranslation(1L, Translation.State.TRANSLATING)
        val ch2 = mockTranslation(2L, Translation.State.QUEUE)

        val realTranslator = createTranslatorWithQueue(listOf(ch1, ch2))
        realTranslator.prioritizeChapter(1L) // Translating chapter
        realTranslator.queueState.value.map { it.chapter.id } shouldBe listOf(1L, 2L)

        realTranslator.prioritizeChapter(2L) // Already right behind active
        realTranslator.queueState.value.map { it.chapter.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `S4 provider governor eventization wakes up waiting request on release`() = runBlocking {
        val governor = ProviderRequestGovernor(
            policy = {
                ProviderQuotaPolicy(
                    requestsPerMinute = 10,
                    tokensPerMinute = 100_000,
                    maxInFlight = 1,
                    pollIntervalMs = 5_000L, // long poll interval to prove wakeup occurs via signal, not poll timer
                    maxForegroundWaitMs = 10_000L,
                )
            },
            clock = SystemProviderRequestClock,
        )

        val key = ProviderRequestKey("test-provider", "test-model")
        val meta1 = ProviderRequestMetadata(key = key, estimatedInputTokens = 100, reservedOutputTokens = 100)
        val meta2 = ProviderRequestMetadata(key = key, estimatedInputTokens = 100, reservedOutputTokens = 100, priority = AdmissionPriority.INTERACTIVE)

        val decision1 = governor.admit(meta1) as ProviderAdmissionDecision.Admitted
        val permit1 = decision1.permit

        val admittedSecond = AtomicBoolean(false)
        val waitStart = System.currentTimeMillis()

        val job = async(Dispatchers.Default) {
            val decision2 = governor.admit(meta2)
            if (decision2 is ProviderAdmissionDecision.Admitted) {
                admittedSecond.set(true)
                governor.releaseAdmitted(decision2.permit, meta2)
            }
        }

        delay(50) // Let job enqueue and enter wait
        admittedSecond.get() shouldBe false

        // Release first permit: should trigger releaseWakeupSignal and wake up second job immediately
        governor.releaseAdmitted(permit1, meta1)
        job.await()

        val elapsed = System.currentTimeMillis() - waitStart
        admittedSecond.get() shouldBe true
        // Proven: elapsed is far below the 5,000ms poll interval!
        (elapsed < 2_000L) shouldBe true
    }

    private fun mockTranslation(id: Long, state: Translation.State): Translation {
        val chapter = mockk<Chapter>(relaxed = true) {
            every { this@mockk.id } returns id
            every { name } returns "Chapter $id"
        }
        val translation = mockk<Translation>(relaxed = true) {
            every { this@mockk.chapter } returns chapter
            every { status } returns state
        }
        return translation
    }

    private fun createTranslatorWithQueue(initial: List<Translation>): ChapterTranslator {
        val mockContext = mockk<android.content.Context>(relaxed = true)
        val mockProvider = mockk<eu.kanade.translation.data.TranslationProvider>(relaxed = true)
        val mockDownloadProvider = mockk<eu.kanade.tachiyomi.data.download.DownloadProvider>(relaxed = true)
        val mockSourceManager = mockk<tachiyomi.domain.source.service.SourceManager>(relaxed = true)
        val mockPreferences = mockk<tachiyomi.domain.translation.TranslationPreferences>(relaxed = true)
        val mockStreamRegistry = mockk<eu.kanade.translation.scheduling.TranslationStreamRegistry>(relaxed = true)
        val mockQueueStore = mockk<eu.kanade.translation.storage.TranslationQueueStore>(relaxed = true)
        val mockPipeline = mockk<eu.kanade.translation.TranslationPipeline>(relaxed = true)

        val translator = ChapterTranslator(
            context = mockContext,
            provider = mockProvider,
            downloadProvider = mockDownloadProvider,
            sourceManager = mockSourceManager,
            translationPreferences = mockPreferences,
            streamRegistry = mockStreamRegistry,
            queueStore = mockQueueStore,
            pipeline = mockPipeline,
        )

        val field: Field = ChapterTranslator::class.java.getDeclaredField("_queueState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(translator) as MutableStateFlow<List<Translation>>
        stateFlow.value = initial
        return translator
    }
}
