package eu.kanade.translation

import eu.kanade.translation.storage.*

import android.content.Context
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.comparables.shouldBeGreaterThan
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

    // T911 slice 2: durable record (generation/group/timestamps/typed failure)

    @Test
    fun `legacy pending entry without new fields parses with defaults`() {
        // Simulate a pre-slice-2 entry: phase + reason keys only.
        preferences.entries["9"] = TranslationRequestPhase.WAITING_FOR_DOWNLOAD.name
        preferences.entries["9.reason"] = "Chapter download failed"
        val store = newStore()

        val record = store.record(9L).shouldNotBeNull()

        record.chapterId shouldBe 9L
        record.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        record.reason shouldBe "Chapter download failed"
        // Migration rule: no generation, no group, no timestamps, no failure kind.
        record.generation shouldBe 0L
        record.groupId.shouldBeNull()
        record.failureKind shouldBe TranslationRequestFailureKind.NONE
        record.createdAtEpochMs shouldBe 0L
        record.updatedAtEpochMs shouldBe 0L
        store.generation(9L) shouldBe 0L
        store.load() shouldContainExactly setOf(9L)
    }

    @Test
    fun `rich record round-trips every new field`() {
        val store = newStore()
        val written = TranslationPendingRequestRecord(
            chapterId = 11L,
            phase = TranslationRequestPhase.DOWNLOAD_FAILED,
            reason = "Interrupted",
            generation = 4L,
            groupId = "batch-2",
            failureKind = TranslationRequestFailureKind.INTERRUPTED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
        )

        store.add(written)
        val read = store.record(11L)

        read shouldBe written
        store.generation(11L) shouldBe 4L
    }

    @Test
    fun `legacy phase write preserves generation group and failure kind`() {
        val store = newStore()
        store.add(
            TranslationPendingRequestRecord(
                chapterId = 12L,
                phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                generation = 6L,
                groupId = "batch-9",
                failureKind = TranslationRequestFailureKind.DOWNLOAD_FAILED,
                createdAtEpochMs = 50L,
                updatedAtEpochMs = 60L,
            ),
        )

        store.add(12L, TranslationRequestPhase.DOWNLOAD_FAILED, "Chapter download failed")

        val record = store.record(12L)!!
        record.phase shouldBe TranslationRequestPhase.DOWNLOAD_FAILED
        record.reason shouldBe "Chapter download failed"
        record.generation shouldBe 6L
        record.groupId shouldBe "batch-9"
        record.failureKind shouldBe TranslationRequestFailureKind.DOWNLOAD_FAILED
        record.createdAtEpochMs shouldBe 50L
        // The legacy shim refreshes the update timestamp.
        record.updatedAtEpochMs shouldBeGreaterThan 50L
    }

    @Test
    fun `remove keeps a bumped generation tombstone for the next request`() {
        val store = newStore()
        store.add(
            TranslationPendingRequestRecord(
                chapterId = 13L,
                phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                generation = 3L,
            ),
        )

        store.remove(13L)

        store.record(13L).shouldBeNull()
        store.load() shouldContainExactly emptySet()
        // Tombstone: a re-request must never reuse generation 3.
        store.generation(13L) shouldBe 4L
        // The tombstone key is not projected as a pending chapter.
        preferences.entries.keys.none { it == "13" } shouldBe true
    }

    @Test
    fun `legacy entry remains loadable after a new-format write and remove cycle`() {
        val store = newStore()
        store.add(14L, TranslationRequestPhase.STARTING, null)
        store.add(14L, TranslationRequestPhase.WAITING_FOR_DOWNLOAD, null)
        store.remove(14L)
        // An old-format reader only looks at the numeric phase key: it must be gone.
        preferences.entries.containsKey("14") shouldBe false
        store.phase(14L).shouldBeNull()
    }
}
