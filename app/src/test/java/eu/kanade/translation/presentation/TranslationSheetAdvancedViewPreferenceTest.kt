package eu.kanade.translation.presentation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences

/**
 * The batch progress sheet's Simple/Advanced depth toggle is a
 * persisted translation preference — default OFF (the simplified default
 * view) and settable so the chosen depth survives sheet reopenings (a fresh
 * preferences facade over the same store must read the toggled value back).
 *
 * Fixture note: [InMemoryPreferenceStore.getBoolean] wraps a per-call
 * snapshot of the stored value in a new [InMemoryPreferenceStore.InMemoryPreference],
 * so a write through a facade is only visible to later readers once it is
 * installed as the store's seeded contents — the fake-store stand-in for the
 * flush into SharedPreferences that AndroidPreferenceStore performs (the same
 * seeding pattern as StrictConfigFromPrefTest). Production persistence needs
 * no test: AndroidPreferenceStore preferences read and write the shared
 * SharedPreferences on every access.
 */
class TranslationSheetAdvancedViewPreferenceTest {

    @Test
    fun `advanced view defaults to off - the simplified default`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())

        prefs.translationProgressSheetAdvancedView().get() shouldBe false
    }

    @Test
    fun `advanced view toggle persists across sheet reopenings`() {
        val firstOpening = TranslationPreferences(InMemoryPreferenceStore())
        val sheetFlag = firstOpening.translationProgressSheetAdvancedView()
        sheetFlag.set(true)

        val reopened = TranslationPreferences(persistedStoreWith(sheetFlag))
        reopened.translationProgressSheetAdvancedView().get() shouldBe true
    }

    @Test
    fun `advanced view can be toggled back off`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        val sheetFlag = prefs.translationProgressSheetAdvancedView()
        sheetFlag.set(true)

        TranslationPreferences(persistedStoreWith(sheetFlag))
            .translationProgressSheetAdvancedView().get() shouldBe true

        sheetFlag.set(false)

        TranslationPreferences(persistedStoreWith(sheetFlag))
            .translationProgressSheetAdvancedView().get() shouldBe false
    }

    /**
     * Returns a store whose durable contents hold [flag]'s current value under
     * [Preference.key] — simulating the write having been flushed to disk.
     */
    private fun persistedStoreWith(flag: Preference<Boolean>): InMemoryPreferenceStore =
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    flag.key(),
                    flag.get(),
                    flag.defaultValue(),
                ),
            ),
        )
}
