package eu.kanade.translation.model

import com.hippo.unifile.UniFile
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 *  Phase 5 (spec §2.1 batch rules, §6.2 commit 5) — RED tests for
 * terminal-only, honest batch progress totals.
 *
 * Named defects pinned here (new value fields on [TranslationProgressSnapshot],
 * wired by the tracker/store paths in commit 6):
 *
 *  1. `terminalPages` — pages in exactly one current-pass terminal category
 *     (translated/reused-valid, textless, failed, partial, cancelled). A
 *     queued/running/uncommitted page is never terminal.
 *  2. `terminalSuccessPages` — the readable-success subset, kept DISTINCT from
 *     failed/partial/cancelled work, so no surface renders failures as "done".
 *  3. `cancelledPages` — a batch abort settles its unfinished pages as
 *     cancelled terminal work, not fake failures and not silent pending.
 *  4. `expectedPageCountTrusted` — a chapter snapshot whose page set is not
 *     the trusted source total (partial download) must be labeled unknown: the
 *     hero drops the percentage/complete look and never renders 3/3 as 100%
 *     while source pages are missing. A registered batch's own work set stays
 *     trusted and keeps the numeric hero.
 *
 * Queue position copy ("Queued (2nd of 3) — waiting for earlier batches") is
 * already truthful and pinned by TranslationQueuePositionAndPhasesTest.
 */
class P5TerminalProgressTest {

    // ------------------------------------------------------------------
    // Reflective readers (named-RED while the value fields are missing)
    // ------------------------------------------------------------------

    private fun intField(snapshot: TranslationProgressSnapshot, name: String, defect: String): Int =
        getter(snapshot, name, defect).invoke(snapshot) as Int

    private fun boolField(snapshot: TranslationProgressSnapshot, name: String, defect: String): Boolean =
        getter(snapshot, name, defect).invoke(snapshot) as Boolean

    private fun getter(snapshot: TranslationProgressSnapshot, name: String, defect: String): Method =
        try {
            snapshot.javaClass.getMethod(
                "get" + name.replaceFirstChar { it.uppercase() },
            )
        } catch (missing: NoSuchMethodException) {
            throw AssertionError(defect, missing)
        }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun block(text: String, translation: String) = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )

    /** Fully committed, display-ready translated page. */
    private fun translatedPage(key: String) = PageTranslation(sourceFileName = key).apply {
        ocrStatus = StageStatus.READY
        translationStatus = StageStatus.READY
        inpaintStatus = StageStatus.READY
        renderStatus = StageStatus.READY
        cleanedImageName = "$key.cleaned.jpg"
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        blocks += block("こんにちは", "Hello")
    }

    /** Partial but readable committed result (some text missing). */
    private fun partialPage(key: String) = translatedPage(key).apply {
        translationStatus = StageStatus.PARTIAL
    }

    private fun failedPage(key: String) = PageTranslation(sourceFileName = key).apply {
        ocrStatus = StageStatus.READY
        translationStatus = StageStatus.FAILED
    }

    /** Successful no-source-text terminal (spec: processed, not translated). */
    private fun textlessPage(key: String) = PageTranslation(sourceFileName = key).apply {
        ocrStatus = StageStatus.READY
        translationStatus = StageStatus.SKIPPED
        renderStatus = StageStatus.SKIPPED
        inpaintStatus = StageStatus.SKIPPED
    }

    // ------------------------------------------------------------------
    // Terminal accounting
    // ------------------------------------------------------------------

    @Test
    fun `terminal totals keep readable success distinct from failed partial and textless work`() {
        val snapshot = TranslationBatchProgressTracker.computeSnapshot(
            chapterId = 1L,
            chapterState = Translation.State.TRANSLATING,
            pageMap = mapOf(
                "p0" to translatedPage("p0"),
                "p1" to failedPage("p1"),
                "p2" to partialPage("p2"),
                "p3" to textlessPage("p3"),
                "p4" to PageTranslation(sourceFileName = "p4"),
            ),
        )

        // Existing truthful pins (compile-safe today).
        withClue("fixture sanity: failures and partials are counted by the existing fields") {
            snapshot.failedCount shouldBe 1
            snapshot.partialPages shouldBe 1
            snapshot.pages.count { it.stage == TranslationProgressStage.DONE } shouldBe 3
        }

        val terminalDefect =
            "T917 P5 RED defect (terminal progress, spec §2.1): TranslationProgressSnapshot " +
                "has no terminalPages count — surfaces cannot show '{terminal}/{trusted total} " +
                "terminal' without conflating success with processed work"
        withClue("terminal = done(3: translated, partial, textless) + failed(1); pending excluded") {
            intField(snapshot, "terminalPages", terminalDefect) shouldBe 4
        }

        val successDefect =
            "T917 P5 RED defect (terminal progress, spec §2.1): TranslationProgressSnapshot " +
                "has no terminalSuccessPages count — failed and partial work is rendered as " +
                "'done' by every numeric surface today (donePages = done + failed)"
        withClue("readable success = translated(1) + textless(1); partial is never clean success") {
            intField(snapshot, "terminalSuccessPages", successDefect) shouldBe 2
        }
    }

    @Test
    fun `a page whose work never committed durably is never terminal success`() {
        // Persistence-rejection shape: the cleaned image persisted, but the
        // final guarded commit was rejected — the store row keeps its
        // pre-translation stage truth (translation PENDING, no committed
        // display). The store stays fail-closed.
        val uncommitted = PageTranslation(sourceFileName = "p0").apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.PENDING
            inpaintStatus = StageStatus.READY
            renderStatus = StageStatus.PENDING
            cleanedImageName = "p0.cleaned.jpg"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        }
        val snapshot = TranslationBatchProgressTracker.computeSnapshot(
            chapterId = 1L,
            chapterState = Translation.State.TRANSLATING,
            pageMap = mapOf("p0" to uncommitted),
        )

        withClue("fail-closed: without a committed display the page is not display-ready") {
            snapshot.displayReadyPages shouldBe 0
            snapshot.pages.single().stage shouldBe TranslationProgressStage.QUEUED
        }

        val defect =
            "T917 P5 RED defect (terminal progress, spec §4.1): an uncommitted page must be " +
                "excluded from terminalSuccessPages — a rejected publication can never " +
                "increment a success total"
        intField(snapshot, "terminalSuccessPages", defect) shouldBe 0
        intField(snapshot, "terminalPages", defect) shouldBe 0
    }

    @Test
    fun `an aborted batch settles its remaining pages as cancelled terminal work`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = emptyMap(),
        )
        val tracker = TranslationBatchProgressTracker(
            chapterId = 1L,
            store = store,
            orderedPageKeys = listOf("p0", "p1", "p2"),
            scope = scope,
        )
        try {
            tracker.abort(remainingPageKeys = setOf("p1", "p2"), reason = "user stop requested")
            val snapshot = tracker.awaitTerminalSnapshot()

            withClue("existing pins: the abort is acknowledged and no fake failure rows appear") {
                snapshot.aborted shouldBe true
                snapshot.pages.filter { it.pageKey != "p0" }
                    .none { it.stage == TranslationProgressStage.FAILED } shouldBe true
            }

            val defect =
                "T917 P5 RED defect (terminal progress, spec §2.1 CANCELLED): the snapshot has " +
                    "no cancelledPages count — a cancelled batch's unfinished pages are neither " +
                    "failed nor settled, so the cancellation fact never reaches progress totals"
            withClue("the two unfinished pages are settled as cancelled terminal work") {
                intField(snapshot, "cancelledPages", defect) shouldBe 2
                intField(snapshot, "terminalPages", defect) shouldBe 2
                intField(snapshot, "terminalSuccessPages", defect) shouldBe 0
            }
        } finally {
            tracker.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Trusted vs unknown totals
    // ------------------------------------------------------------------

    @Test
    fun `an unknown source total is labeled unknown and never renders a percentage`() {
        // Three committed pages of a partially downloaded chapter: the store
        // page set is NOT the trusted source total, so "3/3 · 100%" would be a
        // fabricated complete chapter ( fact).
        val snapshot = TranslationBatchProgressTracker.computeSnapshot(
            chapterId = 1L,
            chapterState = Translation.State.TRANSLATING,
            pageMap = mapOf(
                "p0" to translatedPage("p0"),
                "p1" to translatedPage("p1"),
                "p2" to translatedPage("p2"),
            ),
        )

        val trustDefect =
            "T917 P5 RED defect (terminal progress, spec §2.1/D10): TranslationProgressSnapshot " +
                "has no expectedPageCountTrusted fact — unknown source totals are rendered as " +
                "'{available}/{available} · 100%' complete chapters"
        boolField(snapshot, "expectedPageCountTrusted", trustDefect) shouldBe false

        val hero = BatchHeroProjection.of(snapshot)
        val phase = hero.shouldBeInstanceOf<BatchHeroProjection.Phase>()
        withClue("unknown total must be a named phase, never a numeric percentage") {
            phase.phase.toString() shouldBe "UNKNOWN_TOTAL"
            phase.fraction.shouldBeNull()
        }
    }

    @Test
    fun `a registered batch work set stays trusted and keeps the numeric terminal hero`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = emptyMap(),
        )
        val tracker = TranslationBatchProgressTracker(
            chapterId = 1L,
            store = store,
            orderedPageKeys = listOf("p0", "p1"),
            scope = scope,
        )
        try {
            //  slice 3: the first snapshot is derived from the ordered work
            // keys at construction — the batch's own total is trusted.
            val snapshot = tracker.snapshot.value
            withClue("batch totals come from the registered work set") {
                snapshot.totalPages shouldBe 2
            }

            val trustDefect =
                "T917 P5 RED defect (terminal progress): the tracker snapshot lost the " +
                    "expectedPageCountTrusted fact — a registered batch's own work set is its " +
                    "trusted total and must keep the numeric hero"
            boolField(snapshot, "expectedPageCountTrusted", trustDefect) shouldBe true

            val hero = BatchHeroProjection.of(snapshot)
            hero.shouldBeInstanceOf<BatchHeroProjection.Numeric>()
            withClue("zero terminal work renders 0 of 2 with no fake completion") {
                (hero as BatchHeroProjection.Numeric).totalPages shouldBe 2
                hero.fraction shouldBe 0f
            }
        } finally {
            tracker.close()
            scope.cancel()
        }
    }
}
