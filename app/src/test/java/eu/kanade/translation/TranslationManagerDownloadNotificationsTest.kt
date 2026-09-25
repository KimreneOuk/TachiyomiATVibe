package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.orchestration.TranslationManager
import eu.kanade.translation.storage.TranslationPendingRequestStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 *  slice 2 (R5): download-side lifecycle events must transition an
 * attached pending request to an explicit terminal phase with a typed failure
 * kind instead of leaving it WAITING forever — and must be a no-op (no phase
 * write at all) when no pending request exists for the chapter, so ordinary
 * downloads are unaffected. The seam under test is the same one the
 * downloader's cancel/remove/clear/stop paths call.
 */
class TranslationManagerDownloadNotificationsTest {

    private val chapterId = 10L
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns chapterId
        every { name } returns "chapter"
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { title } returns "fixture"
    }

    private val preferences = InMemorySharedPreferences()

    private fun managerWithWaitingRequest(): TranslationManager {
        val manager = uninitializedManager()
        manager.queueTranslationAfterDownload(manga, chapter)
        manager.pendingTranslationRequests.value[chapterId]?.phase shouldBe
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        return manager
    }

    @Test
    fun `download cancelled transitions the request to CANCELLED with a typed kind`() {
        val manager = managerWithWaitingRequest()

        manager.onDownloadCancelledForTranslation(chapterId)

        val state = manager.pendingTranslationRequests.value[chapterId]
        state?.phase shouldBe TranslationRequestPhase.CANCELLED
        state?.failureKind shouldBe TranslationRequestFailureKind.CANCELLED
        // The terminal state must survive a restart (durable, not memory-only).
        store().record(chapterId)?.phase shouldBe TranslationRequestPhase.CANCELLED
        store().record(chapterId)?.failureKind shouldBe TranslationRequestFailureKind.CANCELLED
    }

    @Test
    fun `queue cleared transitions the request to CANCELLED with QUEUE_CLEARED`() {
        val manager = managerWithWaitingRequest()

        manager.onDownloadQueueClearedForTranslation(chapterId)

        val state = manager.pendingTranslationRequests.value[chapterId]
        state?.phase shouldBe TranslationRequestPhase.CANCELLED
        state?.failureKind shouldBe TranslationRequestFailureKind.QUEUE_CLEARED
        store().record(chapterId)?.failureKind shouldBe TranslationRequestFailureKind.QUEUE_CLEARED
    }

    @Test
    fun `downloader stopped offline transitions the request to DOWNLOAD_FAILED with DOWNLOADER_STOPPED`() {
        val manager = managerWithWaitingRequest()

        manager.onDownloadStoppedForTranslation(chapterId, "No network connection")

        val state = manager.pendingTranslationRequests.value[chapterId]
        state?.phase shouldBe TranslationRequestPhase.DOWNLOAD_FAILED
        state?.failureKind shouldBe TranslationRequestFailureKind.DOWNLOADER_STOPPED
        state?.reason shouldBe "No network connection"
        store().record(chapterId)?.failureKind shouldBe TranslationRequestFailureKind.DOWNLOADER_STOPPED
    }

    @Test
    fun `storage failure persists the STORAGE kind`() {
        val manager = managerWithWaitingRequest()

        manager.markTranslationDownloadFailed(chapterId, "Insufficient storage", TranslationRequestFailureKind.STORAGE)

        val state = manager.pendingTranslationRequests.value[chapterId]
        state?.phase shouldBe TranslationRequestPhase.DOWNLOAD_FAILED
        state?.failureKind shouldBe TranslationRequestFailureKind.STORAGE
    }

    @Test
    fun `notifications are a no-op without a pending request - no phase write`() {
        val manager = uninitializedManager()

        manager.onDownloadCancelledForTranslation(chapterId)
        manager.onDownloadQueueClearedForTranslation(chapterId)
        manager.onDownloadStoppedForTranslation(chapterId, "No network connection")

        manager.pendingTranslationRequests.value.isEmpty() shouldBe true
        store().load() shouldBe emptySet()
        store().record(chapterId).shouldBeNull()
    }

    @Test
    fun `notification for a stale cancelled request stays terminal - never revives it`() {
        val manager = managerWithWaitingRequest()
        manager.cancelTranslationRequest(chapterId)

        manager.onDownloadCancelledForTranslation(chapterId)
        manager.onDownloadStoppedForTranslation(chapterId, "No network connection")

        manager.pendingTranslationRequests.value.isEmpty() shouldBe true
        store().record(chapterId).shouldBeNull()
    }

    @Test
    fun `generation advances after each download-side cancellation`() {
        val manager = uninitializedManager()
        val generations = mutableListOf<Long>()

        repeat(3) {
            manager.queueTranslationAfterDownload(manga, chapter)
            generations += manager.pendingRequestGeneration(chapterId)!!
            manager.onDownloadCancelledForTranslation(chapterId)
        }

        generations[0] shouldNotBe generations[1]
        generations[1] shouldNotBe generations[2]
        generations.toSet().size shouldBe 3
    }

    private fun store(): TranslationPendingRequestStore =
        TranslationPendingRequestStore(
            mockk { every { getSharedPreferences(any(), any()) } returns preferences },
        )

    /**
     * A real in-memory store instance shared with the manager, so durability
     * is asserted against actual persisted keys.
     */
    private fun uninitializedManager(): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "context", mockk<Context>(relaxed = true))
        setField(manager, "pendingRequestStore", store())
        val pendingState = MutableStateFlow<Map<Long, TranslationRequestState>>(emptyMap())
        setField(manager, "pendingTranslationRequestsState", pendingState)
        setField(manager, "pendingTranslationRequests", pendingState.asStateFlow())
        setField(manager, "pendingRequestWriteVersions", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "pendingRequestMutationLock", Any())
        setField(manager, "storeScope", CoroutineScope(Dispatchers.IO))
        setField(manager, "pendingRequestGenerationCounters", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "downloadAttachGenerations", ConcurrentHashMap<Long, Long>())
        setField(manager, "pendingGroupIdSequence", AtomicLong(0))
        return manager
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
    }
}
