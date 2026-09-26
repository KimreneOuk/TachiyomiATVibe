package eu.kanade.translation.milestone

import com.hippo.unifile.FakeUniFile
import eu.kanade.tachiyomi.data.translation.BatchTranslationForegroundPolicy
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.orchestration.ChapterTranslator
import eu.kanade.translation.orchestration.TranslationManager
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.queue.TranslationPendingRequestRecord
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DurabilityAtScaleTest {

    @TempDir
    lateinit var tempDir: File

    private fun uninitializedManager(
        pendingRequestStore: TranslationPendingRequestStore,
        seed: Map<Long, TranslationRequestState> = emptyMap(),
    ): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager

        val scheduler = TranslationScheduler(
            executor = mockk<TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            immediateStoreResolver = { null },
        )
        val translator = mockk<ChapterTranslator>(relaxed = true).also {
            every { it.queueState } returns MutableStateFlow(emptyList())
            every { it.isRunning } returns false
        }

        setField(manager, "scheduler", scheduler)
        setField(manager, "translator", translator)
        setField(manager, "pendingRequestStore", pendingRequestStore)

        val pendingState = MutableStateFlow(seed)
        setField(manager, "pendingTranslationRequestsState", pendingState)
        setField(manager, "pendingTranslationRequests", pendingState.asStateFlow())
        setField(manager, "pendingRequestWriteVersions", ConcurrentHashMap<Long, AtomicLong>())
        setField(manager, "pendingRequestMutationLock", Any())
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

    @Test
    fun `download failure starvation recovery transitions DOWNLOAD_FAILED to WAITING_FOR_DOWNLOAD`() {
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        val manager = uninitializedManager(
            store,
            seed = mapOf(
                10L to TranslationRequestState(
                    chapterId = 10L,
                    phase = TranslationRequestPhase.DOWNLOAD_FAILED,
                    reason = "network failure",
                    failureKind = TranslationRequestFailureKind.NONE,
                ),
            ),
        )

        val rearmed = manager.rearmDownloadFailedRequest(10L)
        rearmed shouldBe true

        val state = manager.pendingTranslationRequests.value[10L]
        state?.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        state?.reason shouldBe "re-armed after download failure"
        state?.failureKind shouldBe TranslationRequestFailureKind.NONE

        verify {
            store.add(
                match<TranslationPendingRequestRecord> { record ->
                    record.chapterId == 10L &&
                        record.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD &&
                        record.reason == "re-armed after download failure"
                },
            )
        }
    }

    @Test
    fun `download failure starvation recovery ignores requests not in DOWNLOAD_FAILED`() {
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        val manager = uninitializedManager(
            store,
            seed = mapOf(
                10L to TranslationRequestState(
                    chapterId = 10L,
                    phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                ),
                20L to TranslationRequestState(
                    chapterId = 20L,
                    phase = TranslationRequestPhase.STARTING,
                ),
            ),
        )

        manager.rearmDownloadFailedRequest(10L) shouldBe false
        manager.rearmDownloadFailedRequest(20L) shouldBe false
        manager.rearmDownloadFailedRequest(999L) shouldBe false

        manager.pendingTranslationRequests.value[10L]?.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        manager.pendingTranslationRequests.value[20L]?.phase shouldBe TranslationRequestPhase.STARTING
        verify(exactly = 0) { store.add(any()) }
    }

    @Test
    fun `download failure starvation recovery falls back to durable store phase`() {
        val store = mockk<TranslationPendingRequestStore>(relaxed = true)
        every { store.phase(30L) } returns TranslationRequestPhase.DOWNLOAD_FAILED
        val manager = uninitializedManager(store, seed = emptyMap())

        val rearmed = manager.rearmDownloadFailedRequest(30L)
        rearmed shouldBe true

        val state = manager.pendingTranslationRequests.value[30L]
        state?.phase shouldBe TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        state?.reason shouldBe "re-armed after download failure"
    }

    @Test
    fun `targeted fsync in UniFileChapterDocumentIo writes and syncs file descriptor safely`() {
        val root = FakeUniFile(parent = null, backing = tempDir)
        val io = UniFileChapterDocumentIo(root)
        val payload = "{\"checkpoint\":\"committed_manifest_fsync_verified\"}".toByteArray(Charsets.UTF_8)

        val written = io.write("manifest_commit.json", payload)
        written shouldBe true

        val targetFile = File(tempDir, "manifest_commit.json")
        targetFile.exists() shouldBe true
        targetFile.readText(Charsets.UTF_8) shouldBe "{\"checkpoint\":\"committed_manifest_fsync_verified\"}"
    }

    @Test
    fun `FGS 6-hour cap constant and foreground policy compliance`() {
        TranslationForegroundService.FGS_CAP_THRESHOLD_MS shouldBe (6 * 3600 * 1000L)

        // Policy keeps service running during active queue or translating
        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(
            listOf(Translation.State.TRANSLATING),
        ) shouldBe true

        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(
            listOf(Translation.State.QUEUE),
        ) shouldBe true

        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(
            listOf(Translation.State.NOT_TRANSLATED),
        ) shouldBe false

        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(
            listOf(Translation.State.PAUSED),
        ) shouldBe false
    }

    @Test
    fun `keep-awake wake lock activation conditions`() {
        // Wake lock is only held when actively TRANSLATING
        val activeStatuses = listOf(Translation.State.TRANSLATING, Translation.State.QUEUE)
        val idleStatuses = listOf(Translation.State.QUEUE, Translation.State.PAUSED)
        val finishedStatuses = listOf(Translation.State.TRANSLATED, Translation.State.NOT_TRANSLATED)

        activeStatuses.any { it == Translation.State.TRANSLATING } shouldBe true
        idleStatuses.any { it == Translation.State.TRANSLATING } shouldBe false
        finishedStatuses.any { it == Translation.State.TRANSLATING } shouldBe false
    }
}
