package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.model.TranslationRequestPhase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Characterization tests for the durable pre-queue request store (T906
 * area-3 finding F3: no direct unit coverage). Pins the add/phase/reason/
 * remove round-trip over SharedPreferences, the blank-reason drop, and the
 * `load()` numeric-key filter that keeps reason keys out of the id set.
 */
class TranslationPendingRequestStoreTest {

    private val preferences = InMemorySharedPreferences()

    private fun newStore(): TranslationPendingRequestStore {
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns preferences
        }
        return TranslationPendingRequestStore(context)
    }

    @Test
    fun `add persists the phase and reason and load reports the chapter`() {
        val store = newStore()

        store.add(7L, TranslationRequestPhase.DOWNLOAD_FAILED, "Chapter download failed")

        store.phase(7L) shouldBe TranslationRequestPhase.DOWNLOAD_FAILED
        store.reason(7L) shouldBe "Chapter download failed"
        store.load() shouldContainExactly setOf(7L)
    }

    @Test
    fun `adding a null or blank reason drops a stale reason key`() {
        val store = newStore()
        store.add(7L, TranslationRequestPhase.DOWNLOAD_FAILED, "Chapter download failed")

        store.add(7L, TranslationRequestPhase.WAITING_FOR_DOWNLOAD, null)
        store.reason(7L).shouldBeNull()

        store.add(7L, TranslationRequestPhase.PREPARING, "   ")
        store.reason(7L).shouldBeNull()
        store.phase(7L) shouldBe TranslationRequestPhase.PREPARING
    }

    @Test
    fun `remove drops both the phase and the reason`() {
        val store = newStore()
        store.add(7L, TranslationRequestPhase.DOWNLOAD_FAILED, "Chapter download failed")

        store.remove(7L)

        store.phase(7L).shouldBeNull()
        store.reason(7L).shouldBeNull()
        store.load() shouldContainExactly emptySet()
    }

    @Test
    fun `clear drops every pending request`() {
        val store = newStore()
        store.add(7L, TranslationRequestPhase.STARTING, null)
        store.add(8L, TranslationRequestPhase.PREPARING, null)

        store.clear()

        store.load() shouldContainExactly emptySet()
        store.phase(7L).shouldBeNull()
        store.phase(8L).shouldBeNull()
    }

    @Test
    fun `load keeps only the numeric chapter keys and ignores reason keys`() {
        val store = newStore()
        store.add(7L, TranslationRequestPhase.STARTING, "because")
        preferences.entries["junk"] = "not-a-chapter"

        store.load() shouldContainExactly setOf(7L)
    }
}
