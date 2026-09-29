package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.translationQueueAdmissionFailureKind
import eu.kanade.translation.persistence.queue.TranslationPendingRequestStore
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import eu.kanade.translation.workflow.ChapterTranslator
import eu.kanade.translation.workflow.TranslationManager
import eu.kanade.translation.workflow.TranslationSessionCoordinator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.lang.reflect.Field

/**
 * A translation-queue admission rejection is never labeled
 * DOWNLOAD_FAILED. The durable phase is ADMISSION_FAILED with a typed kind
 * that distinguishes a non-HTTP source from an invalid translation
 * configuration from a generic admission rejection.
 */
class TranslationManagerQueueAdmissionFailureKindTest {

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { source } returns 1L
        every { title } returns "fixture"
    }
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns 10L
        every { name } returns "chapter"
        every { scanlator } returns null
    }

    @Test
    fun `classification helper distinguishes source config and generic rejections`() {
        translationQueueAdmissionFailureKind(
            sourceIsHttp = false,
            configValid = true,
        ) shouldBe TranslationRequestFailureKind.SOURCE_UNSUPPORTED
        translationQueueAdmissionFailureKind(
            sourceIsHttp = true,
            configValid = false,
        ) shouldBe TranslationRequestFailureKind.CONFIG_INVALID
        translationQueueAdmissionFailureKind(
            sourceIsHttp = true,
            configValid = true,
        ) shouldBe TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED
    }

    @Test
    fun `admission failure on a downloaded chapter is ADMISSION_FAILED - never DOWNLOAD_FAILED`() {
        val httpSource = mockk<HttpSource>(relaxed = true) { every { id } returns 1L }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(any()) } returns httpSource

        val manager = fixture(
            sourceManager = sourceManager,
            configValid = true,
        )
        manager.queueTranslationAfterDownload(manga, chapter)

        // queueChapter never inserts into the queue -> admission failed.
        manager.translateChapter(manga, chapter)

        val state = manager.pendingTranslationRequests.value[10L]
        state?.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
        state?.failureKind shouldBe TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED
        // An admission failure must not be projected as a download failure.
        state?.phase shouldNotBe TranslationRequestPhase.DOWNLOAD_FAILED
    }

    @Test
    fun `invalid translation configuration is classified as CONFIG_INVALID`() {
        val httpSource = mockk<HttpSource>(relaxed = true) { every { id } returns 1L }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(any()) } returns httpSource

        val manager = fixture(
            sourceManager = sourceManager,
            configValid = false,
        )
        manager.queueTranslationAfterDownload(manga, chapter)

        manager.translateChapter(manga, chapter)

        val state = manager.pendingTranslationRequests.value[10L]
        state?.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
        state?.failureKind shouldBe TranslationRequestFailureKind.CONFIG_INVALID
    }

    @Test
    fun `non-HTTP source is classified as SOURCE_UNSUPPORTED`() {
        val plainSource = mockk<Source>(relaxed = true) { every { id } returns 1L }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(any()) } returns plainSource

        val manager = fixture(
            sourceManager = sourceManager,
            configValid = true,
        )
        manager.queueTranslationAfterDownload(manga, chapter)

        manager.translateChapter(manga, chapter)

        val state = manager.pendingTranslationRequests.value[10L]
        state?.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
        state?.failureKind shouldBe TranslationRequestFailureKind.SOURCE_UNSUPPORTED
    }

    private fun shouldNotBeDownloadFailed(): (TranslationRequestState?) -> Unit =
        { state -> state?.phase shouldBe eu.kanade.translation.model.TranslationRequestPhase.ADMISSION_FAILED }

    private fun fixture(
        sourceManager: SourceManager,
        configValid: Boolean,
    ): TranslationManager {
        val scheduler = TranslationScheduler(
            executor = mockk<TranslationExecutor>(relaxed = true),
            storeResolver = TranslationStoreResolver { null },
            immediateStoreResolver = { null },
        )
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(emptyList())
        every { translator.isRunning } returns false
        every { translator.isQueueConfigValid() } returns configValid
        return TranslationManager.createForTesting(
            context = mockk<Context>(relaxed = true),
            provider = mockk(relaxed = true),
            sourceManager = sourceManager,
            translationPreferences = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            pipeline = mockk(relaxed = true),
            translator = translator,
            pendingRequestStore = mockk<TranslationPendingRequestStore>(relaxed = true),
            scheduler = scheduler,
            sessionCoordinator = TranslationSessionCoordinator(),
        )
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
