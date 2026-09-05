package eu.kanade.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.TranslationPreferences

/**
 * T924-FF-00: every T924 flag is a boolean Preference accessor on the real
 * TranslationPreferences mechanism, default OFF, settable through the
 * preference store. No settings-UI switch exists during the flagged stages.
 *
 * InMemoryPreferenceStore is a local-copy store (set mutates the returned
 * Preference instance), so flag ON is proven two ways: set/get on one
 * instance, and a pre-seeded store read through the accessor.
 */
class T924FeatureFlagsTest {

    @Test
    fun `T924 flags default OFF`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())

        prefs.translationBatchProfilePipeline().get() shouldBe false
        prefs.translationBatchPersistedLayout().get() shouldBe false
    }

    @Test
    fun `T924 flags are settable on the preference`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        val profilePipeline = prefs.translationBatchProfilePipeline()
        val persistedLayout = prefs.translationBatchPersistedLayout()

        profilePipeline.set(true)
        persistedLayout.set(true)

        profilePipeline.get() shouldBe true
        persistedLayout.get() shouldBe true
        // The two flags are independent preference keys.
        profilePipeline.set(false)
        persistedLayout.get() shouldBe true
    }

    @Test
    fun `T924 flags read ON from a pre-seeded store`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("translation_batch_profile_pipeline", true, false),
                InMemoryPreferenceStore.InMemoryPreference("translation_batch_persisted_layout", true, false),
            ),
        )
        val prefs = TranslationPreferences(store)

        prefs.translationBatchProfilePipeline().get() shouldBe true
        prefs.translationBatchPersistedLayout().get() shouldBe true
    }
}
