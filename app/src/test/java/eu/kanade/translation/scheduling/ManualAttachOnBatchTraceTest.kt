package eu.kanade.translation.scheduling

import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.diagnostics.TranslationIdentityKeys
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceIdGenerator
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * T922 Phase 4 (plan §6.3 case 4): manual-on-batch attach. A manual intent
 * whose page is owned by an in-flight batch attaches to the owner's terminal
 * commit: the manual run closes as `attached` with ZERO native/provider stage
 * facts attributed to it (the paying work — and its envelope duration — stays
 * owned by the batch schedule), and the manual schedule still terminates
 * exactly once.
 */
class ManualAttachOnBatchTraceTest {

    private val capturedLines = mutableListOf<String>()
    private var oldSink: TranslationTraceSink? = null
    private var oldGate: Boolean? = null
    private var oldIds: TranslationTraceIdGenerator? = null
    private var oldKeys: TranslationIdentityKeys? = null

    @AfterEach
    fun tearDown() {
        restoreCapture()
    }

    private fun setUpTraceCapture() {
        oldSink = TranslationPipelineDiagnostics.sink
        oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        oldIds = TranslationPipelineDiagnostics.idGenerator
        oldKeys = TranslationPipelineDiagnostics.identityKeys
        capturedLines.clear()
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> capturedLines.add(line) }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "t922a")
        TranslationPipelineDiagnostics.identityKeys = TranslationIdentityKeys(ByteArray(32) { 15 })
    }

    private fun restoreCapture() {
        oldSink?.let { TranslationPipelineDiagnostics.sink = it }
        oldGate?.let { TranslationPipelineDiagnostics.detailedTracingEnabled = it }
        oldIds?.let { TranslationPipelineDiagnostics.idGenerator = it }
        oldKeys?.let { TranslationPipelineDiagnostics.identityKeys = it }
        oldSink = null
        oldGate = null
        oldIds = null
        oldKeys = null
    }

    private fun lines(event: String): List<String> = capturedLines.filter { it.contains("event=$event ") }

    private fun field(line: String, key: String): String? =
        line.split(" ").firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")

    private suspend fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    /** §6.3 case 4: the attach terminal carries no native/provider work facts. */
    @Test
    @Timeout(60)
    fun `manual intent attaching to a batch-owned page closes attached with zero lane work`() = runBlocking<Unit> {
        setUpTraceCapture()
        val executor = AttachExecutor()
        val scheduler = TranslationScheduler(executor, { null })
        try {
            val manga = mockk<tachiyomi.domain.manga.model.Manga>(relaxed = true)
            every { manga.id } returns 7L
            val chapter = mockk<tachiyomi.domain.chapter.model.Chapter>(relaxed = true)
            every { chapter.id } returns 7L
            val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>(relaxed = true)
            every { source.id } returns 7L

            scheduler.translatePage(manga, chapter, source, "p0")
            awaitCondition { lines("run_end").isNotEmpty() }
        } finally {
            scheduler.close()
        }

        // One manual schedule + one run, terminating as attached.
        val scheduleStarts = lines("schedule_start")
        scheduleStarts.size shouldBe 1
        field(scheduleStarts.single(), "mode") shouldBe "manual"
        val runStarts = lines("run_start")
        runStarts.size shouldBe 1
        val rid = field(runStarts.single(), "rid")!!
        val runEnds = lines("run_end")
        runEnds.size shouldBe 1
        field(runEnds.single(), "outcome") shouldBe "attached"
        field(runEnds.single(), "rid") shouldBe rid
        field(runEnds.single(), "sid") shouldBe field(scheduleStarts.single(), "sid")
        lines("schedule_end").size shouldBe 1
        field(lines("schedule_end").single(), "outcome") shouldBe "attached"

        // ZERO native/provider work facts on the attached run: no native_queue,
        // translate, ocr, or inpaint stage event is attributed to it — the
        // paying work stays with the batch owner's schedule.
        val laneStageForRun = lines("stage_end").filter { field(it, "rid") == rid }.filter {
            val stage = field(it, "stage")
            stage == "native_queue" || stage == "translate" || stage == "ocr" || stage == "inpaint"
        }
        laneStageForRun.size shouldBe 0
    }
}

/** Executor that models the batch-owned page: the call attaches immediately. */
private class AttachExecutor : TranslationExecutor {
    val attachCount = AtomicInteger(0)

    override suspend fun translateSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin,
    ): SinglePageOutcome {
        attachCount.incrementAndGet()
        return SinglePageOutcome.Attached(PageWriteOrigin.BATCH)
    }

    override suspend fun prepareSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ): PreparedPage? = null

    override suspend fun translatePreparedPage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        prepared: PreparedPage,
        stageListener: TranslationStageListener?,
    ): ChunkCompletionOutcome? = null

    override suspend fun translateSinglePageFromStream(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {
        error("not reached")
    }
}
