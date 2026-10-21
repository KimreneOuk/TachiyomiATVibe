package eu.kanade.translation

import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random

class AdmissionCoreDeltaTest {
    @Test
    fun singlePathEvictsOnlyStaleSameSourceQueuedEntries() {
        val fixture = AdmissionTestFixture(
            initialQueue = listOf(
                Triple(8L, Translation.State.QUEUE, 1L),
                Triple(9L, Translation.State.TRANSLATING, 1L),
                Triple(7L, Translation.State.QUEUE, 2L),
            ),
        )
        try {
            fixture.manager.translateChapter(fixture.manga(), fixture.chapter(10L))

            fixture.queueSnapshot() shouldBe "9:TRANSLATING,7:QUEUE,10:QUEUE"
            fixture.startCount.get() shouldBe 1
        } finally {
            fixture.close()
        }
    }

    @Test
    fun restoreAdmittedChapterStaysPausedAndDoesNotStart() {
        val fixture = AdmissionTestFixture()
        try {
            fixture.manager.translateChapter(fixture.manga(), fixture.chapter(11L), autoStart = false)

            fixture.queueSnapshot() shouldBe "11:PAUSED"
            fixture.startCount.get() shouldBe 0
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleGenerationIsDroppedOnBothSingleAndListPaths() {
        val fixture = AdmissionTestFixture(
            initialQueue = listOf(Triple(50L, Translation.State.PAUSED, 1L)),
            pendingGenerations = mapOf(50L to 9L, 51L to 11L),
        )
        try {
            fixture.manager.translateChapter(
                fixture.manga(),
                fixture.chapter(50L),
                expectedRequestGeneration = 8L,
            )
            val listAccepted = fixture.manager.translateChaptersIfCurrent(
                fixture.manga(),
                listOf(fixture.chapter(51L)),
                expectedGenerations = mapOf(51L to 10L),
            )

            listAccepted shouldBe false
            fixture.queueSnapshot() shouldBe "50:PAUSED"
            fixture.pendingSnapshot() shouldBe "50:WAITING_FOR_DOWNLOAD#9,51:WAITING_FOR_DOWNLOAD#11"
            fixture.startCount.get() shouldBe 0
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preExtractionCharacterizationMatchesGoldenFixture() {
        val actual = characterizationRows().joinToString(separator = "\n", postfix = "\n")
        val expected = checkNotNull(
            javaClass.getResourceAsStream(
                "/eu/kanade/translation/admission/admission-characterization-v1.tsv",
            ),
        ) { "Admission characterization golden is missing" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        actual shouldBe expected
    }

    private fun characterizationRows(): List<String> {
        val rows = mutableListOf<String>()
        rows += "template.single|source=\"T911 dropped stale translate admission for chapter " +
            "$" + "chapterId \" +\n    \"(expectedGeneration=" + "$" + "expectedRequestGeneration)\""
        rows += "template.list|source=\"T911 dropped stale translate admission for chapter " +
            "$" + "chapterId \" +\n    \"(expectedGeneration=" + "$" + "expected currentGeneration=" +
            "$" + "{current?.generation})\""

        run {
            val fixture = AdmissionTestFixture()
            try {
                fixture.manager.translateChapter(fixture.manga(), fixture.chapter(10L))
                rows += row(
                    name = "single-new",
                    input = "chapter=10;expectedGeneration=none;initialQueue=-;initialRequests=-",
                    fixture = fixture,
                    rearmed = "-",
                    logs = "-",
                )
            } finally {
                fixture.close()
            }
        }

        run {
            val fixture = AdmissionTestFixture(
                initialQueue = listOf(Triple(20L, Translation.State.PAUSED, 1L)),
                pendingGenerations = mapOf(20L to 5L),
            )
            try {
                fixture.manager.translateChapter(
                    fixture.manga(),
                    fixture.chapter(20L),
                    expectedRequestGeneration = 5L,
                )
                rows += row(
                    name = "single-explicit-rearm",
                    input = "chapter=20;expectedGeneration=5;initialQueue=20:PAUSED;initialRequests=20#5",
                    fixture = fixture,
                    rearmed = "20",
                    logs = "-",
                )
            } finally {
                fixture.close()
            }
        }

        run {
            val fixture = AdmissionTestFixture()
            try {
                fixture.manager.translateChapter(fixture.manga(), fixture.chapter(30L), autoStart = false)
                rows += row(
                    name = "single-restore-admitted",
                    input = "chapter=30;expectedGeneration=none;autoStart=false;initialQueue=-;initialRequests=-",
                    fixture = fixture,
                    rearmed = "-",
                    logs = "-",
                )
            } finally {
                fixture.close()
            }
        }

        run {
            val fixture = AdmissionTestFixture(
                initialQueue = listOf(Triple(40L, Translation.State.PAUSED, 1L)),
                pendingGenerations = mapOf(40L to 9L),
            )
            try {
                fixture.manager.translateChapter(
                    fixture.manga(),
                    fixture.chapter(40L),
                    expectedRequestGeneration = 8L,
                )
                rows += row(
                    name = "single-stale-generation",
                    input = "chapter=40;expectedGeneration=8;currentGeneration=9;initialQueue=40:PAUSED",
                    fixture = fixture,
                    rearmed = "-",
                    logs = singleLog(40L, 8L),
                )
            } finally {
                fixture.close()
            }
        }

        run {
            val fixture = AdmissionTestFixture(
                initialQueue = listOf(Triple(60L, Translation.State.PAUSED, 1L)),
                pendingGenerations = mapOf(60L to 7L, 61L to 3L),
            )
            try {
                val accepted = fixture.manager.translateChaptersIfCurrent(
                    fixture.manga(),
                    listOf(fixture.chapter(60L), fixture.chapter(61L)),
                    expectedGenerations = mapOf(60L to 8L, 61L to 3L),
                )
                rows += row(
                    name = "list-mixed-generation",
                    input = "chapters=60,61;expectedGenerations={60=8,61=3};currentGenerations={60=7,61=3};initialQueue=60:PAUSED",
                    fixture = fixture,
                    rearmed = "-",
                    logs = listLog(60L, 8L, 7L),
                    result = accepted.toString(),
                )
            } finally {
                fixture.close()
            }
        }

        rows += seededBatchRows()
        return rows
    }

    private fun seededBatchRows(): List<String> {
        val random = Random(0xE8L)
        val candidateIds = (100L..107L).toList()
        return (0 until 8).map { caseIndex ->
            val shuffledIds = candidateIds.toMutableList()
            Collections.shuffle(shuffledIds, random)
            val selectedIds = shuffledIds.take(2 + random.nextInt(4))
            val generations = selectedIds.associateWith { 1000L + it }
            val expected = selectedIds.associateWith { id ->
                val current = generations.getValue(id)
                if (random.nextBoolean()) current else current + 1L
            }
            val initialQueue = selectedIds.take(2).mapIndexed { index, id ->
                Triple(id, if (index == 0) Translation.State.PAUSED else Translation.State.ERROR, 1L)
            }
            val fixture = AdmissionTestFixture(initialQueue, generations)
            try {
                val accepted = fixture.manager.translateChaptersIfCurrent(
                    fixture.manga(),
                    selectedIds.map(fixture::chapter),
                    expectedGenerations = expected,
                )
                val initiallyRearmable = initialQueue
                    .filter { it.second == Translation.State.PAUSED || it.second == Translation.State.ERROR }
                    .map { it.first }
                    .filter { id -> fixture.queue.value.any { it.chapter.id == id && it.status == Translation.State.QUEUE } }
                    .joinToString(",")
                    .ifEmpty { "-" }
                val logs = selectedIds
                    .filter { expected.getValue(it) != generations.getValue(it) }
                    .joinToString(";") { id -> listLog(id, expected.getValue(id), generations.getValue(id)) }
                    .ifEmpty { "-" }
                row(
                    name = "seeded-list-" + caseIndex,
                    input = "seed=0xE8;order=" + selectedIds.joinToString(",") +
                        ";expected=" + generationMap(expected) +
                        ";current=" + generationMap(generations) +
                        ";initialQueue=" + queueInput(initialQueue),
                    fixture = fixture,
                    rearmed = initiallyRearmable,
                    logs = logs,
                    result = accepted.toString(),
                )
            } finally {
                fixture.close()
            }
        }
    }

    private fun row(
        name: String,
        input: String,
        fixture: AdmissionTestFixture,
        rearmed: String,
        logs: String,
        result: String = "-",
    ): String = name +
        "|input=" + input +
        "|result=" + result +
        "|queue=" + fixture.queueSnapshot() +
        "|pending=" + fixture.pendingSnapshot() +
        "|rearmed=" + rearmed +
        "|starts=" + fixture.startCount.get() +
        "|logs=" + logs

    private fun generationMap(values: Map<Long, Long>): String =
        values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { entry ->
            entry.key.toString() + "=" + entry.value
        }

    private fun queueInput(values: List<Triple<Long, Translation.State, Long>>): String =
        values.joinToString(",") { entry -> entry.first.toString() + ":" + entry.second.name }
            .ifEmpty { "-" }

    private fun singleLog(chapterId: Long, expected: Long): String =
        "T911 dropped stale translate admission for chapter " + chapterId +
            " (expectedGeneration=" + expected + ")"

    private fun listLog(chapterId: Long, expected: Long, current: Long?): String =
        "T911 dropped stale translate admission for chapter " + chapterId +
            " (expectedGeneration=" + expected + " currentGeneration=" + current + ")"
}
