package eu.kanade.translation.engines.rendering

import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Process-wide runtime support for persisted layouts. It stays Android-free
 * so hydration and publication decisions can be tested on the JVM.
 *
 *  - [flagEnabled] reads
 *    [TranslationPreferences.translationBatchPersistedLayout],
 *    default OFF). Tests and JVM rigs pin it via [persistedLayoutFlagOverride];
 *    production resolves the registered preference lazily through DI. The read
 *    is guarded: a bare JVM context (unit tests without a registered preference
 *    store) observes the safe default OFF instead of crashing.
 *  - [productionFontSha256] is the SHA-256 of the bundled
 *    `res/font/animeace.ttf`. The bytes are read once at
 *    an Android entry point (`TranslationOverlayView` init) through
 *    [fontSha256Loader]; the digest is then cached for the process so the
 *    Batch-side publisher and every reader-side hydration compare the same
 *    value. Until some Android entry point has pinned it, [productionFontSha256]
 *    returns null and layout publication is skipped (fail-safe: a plan whose
 *    font identity can never be verified must not be published).
 */
object PersistedLayoutRuntime {

    /** Test/JVM seam: when non-null, overrides the  preference read. */
    @Volatile
    var persistedLayoutFlagOverride: Boolean? = null

    /**
     * Android entry points install a loader that reads the bundled font bytes
     * once and returns their SHA-256 (via [DrawPlanFingerprint.fontAssetSha256]).
     * May throw or return null; a null/throwing loader only disables layout
     * publication, never reader display.
     */
    @Volatile
    var fontSha256Loader: (() -> String?)? = null

    /** True once an Android entry point installed [fontSha256Loader]. */
    @Volatile
    var fontSourceInstalled: Boolean = false

    @Volatile
    private var pinnedFontSha256: String? = null

    /**  (`translation_batch_persisted_layout`): ON only when explicitly enabled. */
    fun flagEnabled(): Boolean =
        persistedLayoutFlagOverride ?: runCatching {
            Injekt.get<TranslationPreferences>().translationBatchPersistedLayout().get()
        }.getOrDefault(false)

    /**
     * The pinned production font digest, computing it once through
     * [fontSha256Loader] on first need. Null until an Android entry point
     * supplied the loader (JVM tests pass explicit digests instead).
     */
    fun productionFontSha256(): String? {
        pinnedFontSha256?.let { return it }
        val computed = runCatching { fontSha256Loader?.invoke() }.getOrNull() ?: return null
        pinnedFontSha256 = computed
        return computed
    }

    /** Test seam: pins an explicit digest without any Android resource read. */
    fun pinFontSha256(sha256: String) {
        pinnedFontSha256 = sha256
        fontSourceInstalled = true
    }

    /** Test seam: resets all pinned state (JVM suites must be order-independent). */
    fun resetForTest() {
        persistedLayoutFlagOverride = null
        fontSha256Loader = null
        fontSourceInstalled = false
        pinnedFontSha256 = null
    }
}
