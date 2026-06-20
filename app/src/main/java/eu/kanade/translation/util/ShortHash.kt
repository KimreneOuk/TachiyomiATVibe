package eu.kanade.translation.util

/**
 * Stable, non-reversible digest for change-detection on secret values (e.g.
 * API keys). NOT a cryptographic hash — FNV-1a is fast and collision-resistant
 * enough to detect "did this key change", which is the only use here.
 *
 * Two equal inputs always produce the same digest. The digest is up to 16 hex
 * chars (a 64-bit value); it is not meaningfully reversible to the input, but
 * that is not a security property being relied on — the key itself is already
 * held in plaintext in preferences, so the digest only exists to avoid string
 * comparison of the full secret on the rebuild-gate hot path.
 *
 * Returns the empty string for an empty input so callers can treat "no key
 * configured" uniformly without a special digest value.
 */
object ShortHash {

    private const val FNV_OFFSET = 0xcbf29ce484222325UL
    private const val FNV_PRIME = 0x100000001b3UL

    fun hash(value: String): String {
        if (value.isEmpty()) return ""
        var h = FNV_OFFSET
        for (c in value) {
            h = h xor c.code.toULong()
            h *= FNV_PRIME
        }
        return h.toString(16)
    }
}
