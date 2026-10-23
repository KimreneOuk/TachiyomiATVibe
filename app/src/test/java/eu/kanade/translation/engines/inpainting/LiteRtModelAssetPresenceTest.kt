package eu.kanade.translation.engines.inpainting

import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Extraction cache-hit rule behind the cheap inpainting-model presence probe:
 * a cached extraction only counts when the file survived an app update
 * intact, so a stale or truncated file falls back to the bundled assets.
 */
class LiteRtModelAssetPresenceTest {

    private fun valid(
        extractedLength: Long = 2_000_000L,
        cachedTime: Long = 100L,
        cachedSize: Long = 2_000_000L,
        appUpdateTime: Long = 100L,
    ) = LiteRTMangaInpaintingEngine.isExtractedCacheValid(
        extractedFileExists = true,
        extractedLength = extractedLength,
        cachedTime = cachedTime,
        cachedSize = cachedSize,
        appUpdateTime = appUpdateTime,
    )

    @Test
    fun `intact cached extraction counts as present`() {
        valid() shouldBe true
    }

    @Test
    fun `missing or undersized files never count`() {
        LiteRTMangaInpaintingEngine.isExtractedCacheValid(
            extractedFileExists = false,
            extractedLength = 0L,
            cachedTime = 100L,
            cachedSize = 2_000_000L,
            appUpdateTime = 100L,
        ) shouldBe false
        valid(extractedLength = 1_000_000L, cachedSize = 0L) shouldBe false
        valid(extractedLength = 1_000_001L, cachedSize = 0L) shouldBe true
    }

    @Test
    fun `stale app update or truncated file invalidates the cache`() {
        valid(cachedTime = 99L) shouldBe false
        valid(appUpdateTime = 101L) shouldBe false
        valid(cachedSize = 3_000_000L) shouldBe false
    }
}
