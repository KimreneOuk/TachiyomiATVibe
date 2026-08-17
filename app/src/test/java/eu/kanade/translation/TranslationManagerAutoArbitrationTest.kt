package eu.kanade.translation

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.RollingAutoCoordinator
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.AiEngine

/**
 * Production-path arbitration coverage. The fixture injects only the manager
 * runtime fields that these methods own because constructing TranslationManager
 * normally also boots Android/DI translation engines; every assertion below
 * calls the real manager update/reconcile/translate methods and real scheduler
 * ownership gates.
 */
class TranslationManagerAutoArbitrationTest {

    @Test
    fun `scheduler cancellation failure releases pending revision reservation`() {
        val failingScheduler = mockk<TranslationScheduler>()
        every { failingScheduler.cancelAutoTranslations(10L) } throws IllegalStateException("cancel failed")
        val queue = MutableStateFlow<List<Translation>>(emptyList())
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue
        val ownership = RevisionOwnershipGate()
        val manager = uninitializedManager(failingScheduler, translator, ownership)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { manga.source } returns 1L
        every { chapter.id } returns 10L

        assertThrows(IllegalStateException::class.java) {
            manager.startRevision(
                manga = manga,
                chapter = chapter,
                scope = mockk(relaxed = true),
                reviewerEngine = AiEngine.GEMINI,
                reviewerModel = "fixture",
            )
        }

        ownership.isActive(10L) shouldBe false
        ownership.withLock { ownership.reserveLocked(10L) shouldBe true }
        ownership.release(10L)
    }

    @Test
    fun `manager batch and revision ownership suppress reconcile and rearm after release`() = runBlocking {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf("p0" to eu.kanade.translation.model.PageTranslation(sourceFileName = "")),
        )
        val source = mockk<HttpSource>(relaxed = true)
        val manga = mockk<Manga>(relaxed = true)
        val chapter = mockk<Chapter>(relaxed = true)
        every { source.id } returns 1L
        every { manga.id } returns 2L
        every { manga.source } returns 1L
        every { manga.title } returns "fixture"
        every { chapter.id } returns 10L
        every { chapter.name } returns "chapter"
        every { chapter.scanlator } returns null
        val session = TranslationSession("manager-arbitration", manga, chapter, source, store)
        val identity = AutoChapterIdentity(10L, "manager-arbitration")
        val executor = mockk<eu.kanade.translation.scheduling.TranslationExecutor>(relaxed = true)
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { store },
            immediateStoreResolver = { store },
        )
        val queue = MutableStateFlow<List<Translation>>(emptyList())
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns queue
        every { translator.isRunning } returns false
        every { translator.start() } returns true
        val activeBatch = Translation(source, manga, chapter).also { it.status = Translation.State.QUEUE }
        every { translator.queueChapter(manga, chapter) } answers { queue.value = listOf(activeBatch) }
        val ownership = RevisionOwnershipGate()
        val manager = uninitializedManager(scheduler, translator, ownership)

        try {
            manager.updateAutoWindow(
                identity = identity,
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                pageCount = 1,
                session = session,
                pageResolver = { RollingAutoCoordinator.PageWorkItem("p0", null) },
                computeClass = eu.kanade.translation.translator.TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it?.identity == identity } }

            // The real manager batch acquisition shuts down Auto and queues a
            // live Translation.State. A recovery reconcile and update cannot
            // resurrect the coordinator while that ownership remains active.
            manager.translateChapter(manga, chapter)
            manager.reconcileAutoWindow()
            manager.updateAutoWindow(
                identity,
                0,
                0,
                1,
                session,
                { RollingAutoCoordinator.PageWorkItem("p0", null) },
                eu.kanade.translation.translator.TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it == null } }

            queue.value = emptyList()
            manager.updateAutoWindow(
                identity,
                0,
                0,
                1,
                session,
                { RollingAutoCoordinator.PageWorkItem("p0", null) },
                eu.kanade.translation.translator.TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it?.identity == identity } }

            ownership.withLock { ownership.reserveLocked(10L) shouldBe true }
            manager.reconcileAutoWindow()
            scheduler.autoSnapshot.value?.identity shouldBe identity
            manager.updateAutoWindow(
                identity,
                0,
                0,
                1,
                session,
                { RollingAutoCoordinator.PageWorkItem("p0", null) },
                eu.kanade.translation.translator.TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it == null } }

            ownership.release(10L)
            manager.updateAutoWindow(
                identity,
                0,
                0,
                1,
                session,
                { RollingAutoCoordinator.PageWorkItem("p0", null) },
                eu.kanade.translation.translator.TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it?.identity == identity } }
        } finally {
            ownership.release(10L)
            scheduler.close()
        }
    }

    private fun uninitializedManager(
        scheduler: TranslationScheduler,
        translator: ChapterTranslator,
        ownership: RevisionOwnershipGate,
    ): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafeInstance = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateMethod.invoke(unsafeInstance, TranslationManager::class.java) as TranslationManager

        setPrivateField(manager, "scheduler", scheduler)
        setPrivateField(manager, "translator", translator)
        setPrivateField(manager, "revisionOwnership", ownership)
        return manager
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
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
