package eu.kanade.translation

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class ChapterTranslatorQueueRestoreTest {

    @Test
    fun `restore merge retains concurrent queue additions in durable order`() {
        val restored = mapOf(10L to "restored-10")
        val live = mapOf(20L to "concurrent-20")

        mergeRestoredQueueEntries(
            durableIds = listOf(10L, 20L),
            restoredById = restored,
            liveById = live,
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
}
