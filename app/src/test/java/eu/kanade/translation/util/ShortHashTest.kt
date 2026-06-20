package eu.kanade.translation.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Guards the non-reversible FNV-1a digest used to compare API keys for the
 * translator rebuild gate (two equal keys → equal digests; the digest alone
 * must not reveal the key). Extracted from TranslationPipeline so the digest
 * is stable and testable without the singleton pipeline.
 */
class ShortHashTest {

    @Test
    fun `empty input returns the empty string`() {
        ShortHash.hash("") shouldBe ""
    }

    @Test
    fun `equal inputs produce equal digests`() {
        ShortHash.hash("sk-secret-key-123") shouldBe ShortHash.hash("sk-secret-key-123")
    }

    @Test
    fun `different inputs produce different digests`() {
        ShortHash.hash("key-one") shouldNotBe ShortHash.hash("key-two")
    }

    @Test
    fun `digest is a hex string and never the raw key`() {
        val key = "sk-abcd-1234"
        val digest = ShortHash.hash(key)

        // Hex-only chars, and strictly shorter / different from the input.
        digest.matches(Regex("^[0-9a-f]+$")) shouldBe true
        digest shouldNotBe key
    }

    @Test
    fun `digest is stable across calls (deterministic)`() {
        val first = ShortHash.hash("a-sample-api-key")
        // Call again after other inputs to confirm no shared mutable state.
        ShortHash.hash("other")
        ShortHash.hash("more")

        ShortHash.hash("a-sample-api-key") shouldBe first
    }
}
