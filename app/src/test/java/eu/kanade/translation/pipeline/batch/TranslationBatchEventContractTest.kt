package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.Translation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Locks the post-CP9 surface of [TranslationBatchEvent]. CP9 removed the dead
 * `BatchStarted` / `BatchResumed` event types (no emitter in `app/src`, only ever
 * handled by the reducer's defensive `else -> previous` branch). These tests make a
 * future reintroduction fail loudly and prove the remaining constructors still compile
 * against the sealed class.
 */
class TranslationBatchEventContractTest {

    @Test
    fun `sealed subclass set is exactly the post-removal surface`() {
        val names = TranslationBatchEvent::class.sealedSubclasses.map { it.simpleName }.toSet()

        names shouldContainExactly setOf(
            "PagePhase",
            "AiPageProgress",
            "BatchAborted",
            "BatchFinished",
            "BatchPaused",
            //   the coordinator's rebuild-window events (envelope
            // plan build / commit).
            "EnvelopePlanProgress",
            "EnvelopePlanCommitted",
        )
    }

    @Test
    fun `EnvelopePlanProgress carries the rebuild adoption counter`() {
        val event = TranslationBatchEvent.EnvelopePlanProgress(done = 34, total = 206)

        event.done shouldBe 34
        event.total shouldBe 206
    }

    @Test
    fun `EnvelopePlanCommitted is a singleton event`() {
        TranslationBatchEvent.EnvelopePlanCommitted shouldBe TranslationBatchEvent.EnvelopePlanCommitted
    }

    @Test
    fun `PagePhase constructs with required fields and compiles as a subtype`() {
        val event = TranslationBatchEvent.PagePhase(
            pageKey = "001.jpg",
            pageIndex = 0,
            phase = BatchPhase.OCR,
            status = PhaseStatus.RUNNING,
        )
        // Defaults: elapsedMs = 0L, heapMiB = null, reason = null.
        event.elapsedMs shouldBe 0L
        event.heapMiB shouldBe null
        event.reason shouldBe null
        event shouldBe TranslationBatchEvent.PagePhase("001.jpg", 0, BatchPhase.OCR, PhaseStatus.RUNNING)
    }

    @Test
    fun `AiPageProgress constructs every observable provider state`() {
        val event = TranslationBatchEvent.AiPageProgress(
            pageKey = "001.jpg",
            pageIndex = 0,
            state = AiPageProgressState.BUFFERED,
            reason = "barrier",
        )

        event.state shouldBe AiPageProgressState.BUFFERED
        event.reason shouldBe "barrier"
    }

    @Test
    fun `BatchAborted constructs with reason and failed page keys`() {
        val event = TranslationBatchEvent.BatchAborted(
            reason = "oom",
            failedPageKeys = setOf("003.jpg"),
        )
        event.reason shouldBe "oom"
        event.failedPageKeys shouldBe setOf("003.jpg")
    }

    @Test
    fun `BatchFinished constructs with state and four counts`() {
        val event = TranslationBatchEvent.BatchFinished(
            state = Translation.State.TRANSLATED,
            donePages = 8,
            failedPages = 1,
            partialPages = 1,
            totalPages = 10,
        )
        event.state shouldBe Translation.State.TRANSLATED
        event.donePages shouldBe 8
        event.failedPages shouldBe 1
        event.partialPages shouldBe 1
        event.totalPages shouldBe 10
    }
}
