package eu.kanade.translation

import eu.kanade.translation.coordinator.*
import eu.kanade.translation.persistence.*
import eu.kanade.translation.processing.*

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Covers the manager's reader auto-delete protection entry points.
 * `TranslationUiProjectionTest` covers the pure predicate; here the real
 * manager entry points that gate reader auto-delete are exercised over all
 * three protection unions: the live queue (incl. PAUSED), the in-memory /
 * durable pending requests, and the translator's persisted queue.
 *
 * The fixture injects only the manager runtime fields these methods own
 * because constructing TranslationManager normally also boots Android/DI
 * translation engines (mirrors TranslationManagerDownloadFailureRecoveryTest).
 */
class TranslationManagerAutoDeleteProtectionTest {

    @Test
    fun `paused queue entries pending requests and the persisted queue all protect a chapter`() {
        val source = mockk<HttpSource>(relaxed = true) {
            every { id } returns 1L
        }
        val manga = mockk<Manga>(relaxed = true) {
            every { id } returns 2L
            every { this@mockk.source } returns 1L
        }
        val chapter = mockk<Chapter>(relaxed = true) {
            every { id } returns 10L
        }
        val paused = Translation(source, manga, chapter).also {
            it.status = Translation.State.PAUSED
        }
        val queue = MutableStateFlow(listOf(paused))
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        every { store.load() } returns setOf(40L)
        val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
        every { translator.queueState } returns queue
        every { translator.queuedTranslationOrNull(any()) } answers {
            queue.value.firstOrNull { it.chapter.id == firstArg<Long>() }
        }
        every { translator.persistedQueueChapterIds() } returns setOf(30L)
        val manager = uninitializedManager(
            store,
            translator,
            seed = mapOf(
                20L to TranslationRequestState(20L, TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
            ),
        )

        // PAUSED is a durable-but-inactive queue state that still owns files.
        manager.isChapterTranslationProtected(10L) shouldBe true
        // The immediate in-memory request acknowledgement protects too.
        manager.isChapterTranslationProtected(20L) shouldBe true
        // A chapter that only survives in the persisted queue after a crash.
        manager.isChapterTranslationProtected(30L) shouldBe true
        // A request that only survives in the durable store after process death.
        manager.isChapterTranslationProtected(40L) shouldBe true
        // Control: an untouched chapter stays deletable.
        manager.isChapterTranslationProtected(99L) shouldBe false

        manager.protectedChapterIds() shouldBe setOf(10L, 20L, 30L, 40L)
    }

    /** Mirrors TranslationManagerDownloadFailureRecoveryTest's test factory fixture. */
    private fun uninitializedManager(
        pendingRequestStore: TranslationPendingRequestStore,
        translator: BatchTranslationCoordinator,
        seed: Map<Long, TranslationRequestState> = emptyMap(),
    ): TranslationManager {
        return TranslationManager.createForTesting(
            context = mockk<Context>(relaxed = true),
            provider = mockk(relaxed = true),
            sourceManager = mockk(relaxed = true),
            translationPreferences = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            processor = mockk(relaxed = true),
            batchCoordinator = translator,
            pendingRequestStore = pendingRequestStore,
            pendingRequests = MutableStateFlow(seed),
        )
    }
}
