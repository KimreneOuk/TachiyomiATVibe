package eu.kanade.translation

import eu.kanade.translation.coordinator.*
import eu.kanade.translation.persistence.*
import eu.kanade.translation.processing.*

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.Translation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class BatchTranslationCoordinatorQueueRestoreTest {

    @Test
    fun `restore merge retains concurrent queue additions in durable order`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(10L, 20L),
            restoredById = mapOf(10L to "restored-10"),
            liveById = mapOf(20L to "concurrent-20"),
        ) shouldContainExactly listOf("restored-10", "concurrent-20")
    }

    @Test
    fun `restore merge honors removals and drops stale lookup results`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(20L, 20L, 30L),
            restoredById = mapOf(10L to "stale-10", 20L to "restored-20"),
            liveById = emptyMap(),
        ) shouldContainExactly listOf("restored-20")
    }

    @Test
    fun `restore merge prefers a live requeue over stale restored state`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(20L),
            restoredById = mapOf(20L to "stale-paused"),
            liveById = mapOf(20L to "fresh-queue"),
        ) shouldContainExactly listOf("fresh-queue")
    }

    private fun waveSource(): HttpSource = mockk(relaxed = true)

    private fun waveEntry(
        id: Long,
        status: Translation.State,
        source: HttpSource,
    ): Translation = Translation(
        source = source,
        manga = Manga.create(),
        chapter = Chapter.create().copy(id = id, name = "Chapter $id"),
    ).apply { this.status = status }

    @Test
    fun `selectChapterWave admits only the first entry of a fresh queue`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.QUEUE, source),
            waveEntry(2L, Translation.State.QUEUE, source),
            waveEntry(3L, Translation.State.QUEUE, waveSource()),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `selectChapterWave pairs the active chapter with the next queued chapter of its source`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATING, source),
            waveEntry(2L, Translation.State.QUEUE, source),
            waveEntry(3L, Translation.State.QUEUE, source),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `selectChapterWave keeps only the active chapter when the next queued is from another source`() {
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATING, waveSource()),
            waveEntry(2L, Translation.State.QUEUE, waveSource()),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `selectChapterWave keeps only the active chapter when no queued entries remain`() {
        val queue = listOf(waveEntry(1L, Translation.State.TRANSLATING, waveSource()))

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `selectChapterWave excludes terminal and non-participating statuses`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATED, source),
            waveEntry(2L, Translation.State.ERROR, waveSource()),
            waveEntry(3L, Translation.State.PAUSED, waveSource()),
            waveEntry(4L, Translation.State.READY_WITH_WARNINGS, waveSource()),
            waveEntry(5L, Translation.State.NOT_TRANSLATED, waveSource()),
            waveEntry(6L, Translation.State.QUEUE, waveSource()),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(6L)
    }

    @Test
    fun `selectChapterWave admits at most one translating entry even with same-source queued work`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATING, source),
            waveEntry(2L, Translation.State.TRANSLATING, source),
            waveEntry(3L, Translation.State.QUEUE, source),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L, 3L)
    }
}
