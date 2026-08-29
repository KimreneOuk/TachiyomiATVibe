package eu.kanade.translation

import android.content.Context
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Characterization tests for the durable batch-queue membership store
 * (T906 area-3 finding F3: no direct unit coverage). Pins the
 * SharedPreferences round-trip, ordering, and the `load()` parse contract:
 * the positional scan stops at the first missing or unparseable index.
 */
class TranslationQueueStoreTest {

    private val preferences = InMemorySharedPreferences()

    private fun newStore(): TranslationQueueStore {
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns preferences
        }
        return TranslationQueueStore(context)
    }

    @Test
    fun `save and load round trip preserves membership and order`() {
        val store = newStore()

        store.save(listOf(7L, 3L, 9L))

        store.load() shouldContainExactly listOf(7L, 3L, 9L)
    }

    @Test
    fun `load returns an empty list when nothing is persisted`() {
        val store = newStore()

        store.load() shouldContainExactly emptyList()
    }

    @Test
    fun `save replaces the previous queue so removals are reflected`() {
        val store = newStore()

        store.save(listOf(7L, 3L))
        store.save(listOf(3L))

        store.load() shouldContainExactly listOf(3L)
    }

    @Test
    fun `clear removes every persisted entry`() {
        val store = newStore()
        store.save(listOf(7L, 3L, 9L))

        store.clear()

        store.load() shouldContainExactly emptyList()
    }

    @Test
    fun `load stops at the first missing or unparseable positional entry`() {
        val store = newStore()
        store.save(listOf(5L, 6L, 7L))

        // An unparseable entry terminates the scan before later indices.
        preferences.entries["1"] = "not-a-number"
        store.load() shouldContainExactly listOf(5L)

        // A missing positional gap terminates the scan the same way.
        preferences.entries["1"] = "6"
        preferences.entries.remove("2")
        store.load() shouldContainExactly listOf(5L, 6L)
    }
}
