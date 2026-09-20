package eu.kanade.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.TranslationPreferences

/**
 * T924-FF-00: every surviving T924 flag is a boolean Preference accessor on
 * the real TranslationPreferences mechanism, default OFF, settable through
 * the preference store.
 *
 * T924 zero-legacy (D1): FF-01 (`translation_batch_profile_pipeline`)
 * completed its A/B lifecycle and was REMOVED — the profile pipeline is the
 * only pipeline and there is no flag to test. The leftover pref key in a
 * device DataStore is a harmless orphan. FF-02 keeps its lifecycle coverage
 * here.
 */
class TranslationFeatureFlagsTest {

    @Test
    fun `T924 flags default OFF`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())

        prefs.translationBatchPersistedLayout().get() shouldBe false
    }

    @Test
    fun `T924 flags are settable on the preference`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        val persistedLayout = prefs.translationBatchPersistedLayout()

        persistedLayout.set(true)

        persistedLayout.get() shouldBe true
    }
}
