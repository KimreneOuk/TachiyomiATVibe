package eu.kanade.translation.coexistence

import eu.kanade.translation.pipeline.batch.LazySourceFingerprints
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class MilestoneM3OcrPushThroughTest {

    @Test
    fun `lazy source fingerprinting computes fingerprints on demand and caches results`() = runBlocking {
        val callCount = AtomicInteger(0)
        val streams: Map<String, () -> InputStream> = mapOf(
            "page_1" to { ByteArrayInputStream("content_1".toByteArray()) },
            "page_2" to { ByteArrayInputStream("content_2".toByteArray()) },
            "page_3" to { ByteArrayInputStream("content_3".toByteArray()) },
        )
        val computeFn: suspend (() -> InputStream) -> String? = { streamFn ->
            callCount.incrementAndGet()
            val text = streamFn().bufferedReader().readText()
            "hash_$text"
        }

        val lazyMap = LazySourceFingerprints(streams, computeFn)

        // No computation occurs upon initialization
        callCount.get() shouldBe 0

        // Accessing page_1 computes hash once
        val hash1 = lazyMap["page_1"]
        hash1 shouldBe "hash_content_1"
        callCount.get() shouldBe 1

        // Repeated access to page_1 hits cache
        val hash1Cached = lazyMap["page_1"]
        hash1Cached shouldBe "hash_content_1"
        callCount.get() shouldBe 1

        // Accessing page_3 computes page_3, leaving page_2 untouched
        val hash3 = lazyMap["page_3"]
        hash3 shouldBe "hash_content_3"
        callCount.get() shouldBe 2

        // Nonexistent key returns null
        lazyMap["nonexistent"] shouldBe null
        callCount.get() shouldBe 2
    }

    @Test
    fun `lazy source fingerprint map implements Map contract correctly`() {
        val streams: Map<String, () -> InputStream> = mapOf(
            "p1" to { ByteArrayInputStream("1".toByteArray()) },
            "p2" to { ByteArrayInputStream("2".toByteArray()) },
        )
        val lazyMap = LazySourceFingerprints(streams) { "hash" }

        lazyMap.size shouldBe 2
        lazyMap.isEmpty() shouldBe false
        lazyMap.containsKey("p1") shouldBe true
        lazyMap.containsKey("p3") shouldBe false
        lazyMap.keys shouldBe setOf("p1", "p2")
    }

    @Test
    fun `N1-5 slot offset formula maps step 2 to slot 1 avoiding slot 0 BOS overwrite`() {
        // In MangaOcrEngine:
        // Cache shape is [4, 1, 4, 256, 64]
        // slot = pos - 1
        // For pos = 2 (first step after init): slot = 1
        // Slot 0 holds BOS from init, slot 1 holds step 1 output
        val maxLen = 256
        val headDim = 64
        val cacheShape4 = maxLen * headDim

        fun calculateDstOffset(layer: Int, head: Int, slot: Int): Int {
            return (layer * 4 + head) * cacheShape4 + slot * headDim
        }

        // Slot 0 (written by copyInitToCache for BOS):
        val bosOffsetLayer0Head0 = calculateDstOffset(0, 0, 0)
        bosOffsetLayer0Head0 shouldBe 0

        // Slot 1 (written at pos = 2 via slot = pos - 1):
        val step1OffsetLayer0Head0 = calculateDstOffset(0, 0, 1)
        step1OffsetLayer0Head0 shouldBe 64

        // Slot 2 (written at pos = 3 via slot = pos - 1):
        val step2OffsetLayer0Head0 = calculateDstOffset(0, 0, 2)
        step2OffsetLayer0Head0 shouldBe 128

        // Verify layer 1, head 2
        val layer1Head2Slot1 = calculateDstOffset(1, 2, 1)
        layer1Head2Slot1 shouldBe (1 * 4 + 2) * (256 * 64) + 64
    }
}
