package eu.kanade.translation.translator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AiModelFetcherTest {

    @Test
    fun `normalizeBaseUrl trims whitespace and trailing slashes`() {
        AiModelFetcher.normalizeBaseUrl("  http://192.168.1.10:1234/v1///  ") shouldBe
            "http://192.168.1.10:1234/v1"
    }

    @Test
    fun `normalizeBaseUrl does not append v1`() {
        AiModelFetcher.normalizeBaseUrl("http://192.168.1.10:1234") shouldBe
            "http://192.168.1.10:1234"
    }
}
