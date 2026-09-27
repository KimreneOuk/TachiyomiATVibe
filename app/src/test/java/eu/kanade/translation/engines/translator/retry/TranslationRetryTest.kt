package eu.kanade.translation.engines.translator.retry
import eu.kanade.translation.engines.translator.providers.GeminiApiException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.IOException

class TranslationRetryTest {

    @Test
    fun `success on first attempt returns without retry`() = runTest {
        var calls = 0
        val result = withTranslationRetry(maxAttempts = 3, baseDelayMs = 1, logTag = "t") {
            calls++
            "ok"
        }
        result shouldBe "ok"
        calls shouldBe 1
    }

    @Test
    fun `transient IOException retried then succeeds`() = runTest {
        var calls = 0
        val result = withTranslationRetry(maxAttempts = 3, baseDelayMs = 1, logTag = "t") {
            calls++
            if (calls < 2) throw IOException("boom")
            "ok"
        }
        result shouldBe "ok"
        calls shouldBe 2
    }

    @Test
    fun `rate limit message retried then succeeds`() = runTest {
        var calls = 0
        val result = withTranslationRetry(maxAttempts = 3, baseDelayMs = 1, logTag = "t") {
            calls++
            if (calls < 2) throw RuntimeException("429 Too Many Requests")
            "ok"
        }
        result shouldBe "ok"
        calls shouldBe 2
    }

    @Test
    fun `Gemini 429 retries the unchanged request using its retry hint`() = runTest {
        var calls = 0
        val result = withTranslationRetry(maxAttempts = 2, baseDelayMs = 60_000, logTag = "gemini") {
            calls++
            if (calls == 1) throw GeminiApiException(429, 0, 429, "RESOURCE_EXHAUSTED")
            "ok"
        }

        result shouldBe "ok"
        calls shouldBe 2
    }

    @Test
    fun `exhaustion throws last transient error`() = runTest {
        var calls = 0
        val thrown = runCatching {
            withTranslationRetry(maxAttempts = 3, baseDelayMs = 1, logTag = "t") {
                calls++
                throw IOException("503 Service Unavailable")
            }
        }.exceptionOrNull() ?: fail("expected IOException")
        thrown.shouldBeInstanceOf<IOException>()
        thrown.message shouldBe "503 Service Unavailable"
        calls shouldBe 3
    }

    @Test
    fun `non-transient error rethrows immediately`() = runTest {
        var calls = 0
        val thrown = runCatching {
            withTranslationRetry(maxAttempts = 3, baseDelayMs = 1, logTag = "t") {
                calls++
                throw IllegalArgumentException("bad arg")
            }
        }.exceptionOrNull() ?: fail("expected IllegalArgumentException")
        thrown.shouldBeInstanceOf<IllegalArgumentException>()
        thrown.message shouldBe "bad arg"
        calls shouldBe 1
    }

    @Test
    fun `CancellationException propagates without retry`() = runTest {
        var calls = 0
        val thrown = runCatching {
            withTranslationRetry(maxAttempts = 3, baseDelayMs = 1, logTag = "t") {
                calls++
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull() ?: fail("expected CancellationException")
        thrown.shouldBeInstanceOf<CancellationException>()
        thrown.message shouldBe "cancelled"
        calls shouldBe 1
    }
}
