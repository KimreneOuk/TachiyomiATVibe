package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.coordinator.staleQueuedChaptersToEvict
import eu.kanade.translation.coordinator.toQueuedChapterView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.processing.TranslationProcessor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.atomic.AtomicInteger

internal class AdmissionTestFixture(
    initialQueue: List<Triple<Long, Translation.State, Long>> = emptyList(),
    pendingGenerations: Map<Long, Long> = emptyMap(),
) : AutoCloseable {
    private val sources = mutableMapOf<Long, HttpSource>()
    private val mangas = mutableMapOf<Long, Manga>()
    private val chapters = mutableMapOf<Long, Chapter>()

    val queue = MutableStateFlow(
        initialQueue.map { (chapterId, status, sourceId) ->
            translation(chapterId, sourceId).also { it.status = status }
        },
    )
    val pendingRequests = MutableStateFlow(
        pendingGenerations.mapValues { (chapterId, generation) ->
            TranslationRequestState(
                chapterId = chapterId,
                phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                generation = generation,
            )
        },
    )
    val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
    val startCount = AtomicInteger()
    var queueHook: (Chapter) -> Unit = {}
    var startHook: () -> Unit = {}

    val manager: TranslationManager

    init {
        every { translator.queueState } returns queue
        every { translator.queuedTranslationOrNull(any()) } answers {
            queue.value.firstOrNull { it.chapter.id == firstArg<Long>() }
        }
        every { translator.rearmQueuedChapter(any()) } answers {
            val queued = queue.value.firstOrNull { it.chapter.id == firstArg<Long>() }
            if (queued?.status == Translation.State.PAUSED || queued?.status == Translation.State.ERROR) {
                queued.status = Translation.State.QUEUE
            }
            queued != null
        }
        every { translator.evictStaleQueuedChapters(any(), any()) } answers {
            val staleIds = staleQueuedChaptersToEvict(
                queue.value.map { it.toQueuedChapterView() },
                firstArg(),
                secondArg(),
            ).map { it.chapterId }.toSet()
            queue.value = queue.value.filterNot { it.chapter.id in staleIds }
        }
        every { translator.isRunning } returns false
        every { translator.isQueueConfigValid() } returns true
        every { translator.queueChapter(any(), any(), any(), any()) } answers {
            val chapter = secondArg<Chapter>()
            queueHook(chapter)
            if (queue.value.none { it.chapter.id == chapter.id }) {
                queue.value = queue.value + translation(chapter.id ?: error("chapter id missing"), 1L).also {
                    it.status = Translation.State.QUEUE
                }
            }
        }
        every { translator.removeFromQueue(any<Chapter>()) } answers {
            val removedId = firstArg<Chapter>().id
            queue.value = queue.value.filterNot { it.chapter.id == removedId }
        }
        every { translator.start() } answers {
            startCount.incrementAndGet()
            startHook()
            true
        }
        every { translator.startWithSessionAdmission() } answers { translator.start() }

        manager = TranslationManager.createForTesting(
            context = mockk<Context>(relaxed = true),
            provider = mockk(relaxed = true),
            sourceManager = mockk<SourceManager>(relaxed = true),
            translationPreferences = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            processor = mockk<TranslationProcessor>(relaxed = true),
            batchCoordinator = translator,
            pendingRequestStore = mockk<TranslationPendingRequestStore>(relaxed = true),
            pendingRequests = pendingRequests,
            sessionCoordinator = TranslationSessionCoordinator(),
        )
    }

    fun chapter(chapterId: Long): Chapter = chapters.getOrPut(chapterId) {
        mockk(relaxed = true) {
            every { id } returns chapterId
            every { name } returns "chapter-" + chapterId
            every { scanlator } returns null
        }
    }

    fun manga(sourceId: Long = 1L): Manga = mangas.getOrPut(sourceId) {
        mockk(relaxed = true) {
            every { id } returns sourceId + 100L
            every { source } returns sourceId
            every { title } returns "manga-" + sourceId
        }
    }

    private fun source(sourceId: Long): HttpSource = sources.getOrPut(sourceId) {
        mockk(relaxed = true) {
            every { id } returns sourceId
        }
    }

    fun translation(chapterId: Long, sourceId: Long = 1L): Translation =
        Translation(
            source = source(sourceId),
            manga = manga(sourceId),
            chapter = chapter(chapterId),
        )

    fun queueSnapshot(): String =
        if (queue.value.isEmpty()) {
            "-"
        } else {
            queue.value.joinToString(",") { entry ->
                entry.chapter.id.toString() + ":" + entry.status.name
            }
        }

    fun pendingSnapshot(): String =
        if (pendingRequests.value.isEmpty()) {
            "-"
        } else {
            pendingRequests.value.toSortedMap().values.joinToString(",") { request ->
                request.chapterId.toString() + ":" + request.phase.name + "#" + request.generation
            }
        }

    fun mutationLock(): Any {
        return manager.requestCoordinator.pendingRequestMutationLock
    }

    override fun close() {
    }
}
