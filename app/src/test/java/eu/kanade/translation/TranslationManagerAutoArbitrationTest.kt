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
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.lang.reflect.Field

/**
 * Production-path arbitration coverage. The fixture injects only the manager
 * runtime fields that these methods own because constructing TranslationManager
 * normally also boots Android/DI translation engines; every assertion below
 * calls the real manager update/reconcile/translate methods and real scheduler
 * ownership gates.
 */
class TranslationManagerAutoArbitrationTest {

    @Test
    fun `manager keeps auto window active while the chapter batch is queued`() = runBlocking {
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
        val manager = uninitializedManager(scheduler, translator)

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

            // Queueing a batch retires the old coordinator, but subsequent
            // reader-window updates must re-arm it while the batch is active.
            manager.translateChapter(manga, chapter)
            manager.reconcileAutoWindow()
            withTimeout(5_000) { scheduler.autoSnapshot.first { it == null } }
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

            manager.reconcileAutoWindow()
            scheduler.autoSnapshot.value?.identity shouldBe identity

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
            scheduler.close()
        }
    }

    private fun uninitializedManager(
        scheduler: TranslationScheduler,
        translator: ChapterTranslator,
    ): TranslationManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "scheduler", scheduler)
        setField(manager, "translator", translator)
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
