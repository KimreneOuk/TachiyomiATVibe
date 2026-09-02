package eu.kanade.translation.coexistence

import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.pipeline.batch.BatchPass1Outcome
import eu.kanade.translation.pipeline.batch.BatchPass1Status
import eu.kanade.translation.pipeline.batch.TranslationBatchProgressTracker
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.translator.TextTranslatorLanguage
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * T917 Phase 5 — typed-outcome truth at the single-page boundary and the batch
 * progress snapshot (product spec §4, conditions A/B/C).
 *
 * Defects under test (each RED failure names its condition):
 *  - **Condition A (Deviation #7, review/phase4-verification.md §5):** the resume
 *    paths of `translateSinglePageOnnx` return `null` on SUCCESS. This is a NEW
 *    wrong-outcome case introduced by D8's honest-timeout flip — pre-D8 the
 *    accident produced the right `Completed` for resumes; D8's fix mapped the
 *    same null to `Failed(pageKey, "native phase timed out")`. The boundary must
 *    inspect store terminality (the `buildTerminalPreparedPage` discipline)
 *    before mapping null → Failed.
 *  - **Condition B (D8-1):** an HTTP+render timeout (`withTimeoutOrNull` → null
 *    outcome) falls through to `SinglePageOutcome.Completed`. It must be typed
 *    honestly (Failed family, naming the HTTP+render timer).
 *  - **Condition C (P3 finding 5):** `ChunkCompletionOutcome.PersistenceRejected`
 *    and `ChunkCompletionOutcome.Failed` returned as VALUES by the HTTP phase are
 *    mapped only for `Paused`; everything else falls through to `Completed`.
 *  - **Batch (spec §4.1):** the `nonDurableFailure` fact must reach the progress
 *    snapshot as a bounded value field (boolean + nullable reason, no enum).
 *
 * Store truth is never in question here — every test also pins the fail-closed
 * durable state so the outcome typing can never be "fixed" by lying about the
 * store instead.
 */
class P5HonestOutcomeTypingTest {

    private val harnessRef = AtomicReference<TranslationCoexistenceHarness?>(null)

    @AfterEach
    fun tearDown() {
        harnessRef.getAndSet(null)?.let {
            runCatching { it.removeGraphicsShims() }
            runCatching { it.close() }
        }
    }

    // ------------------------------------------------------------------
    // Condition A — resume-null / timeout conflation
    // ------------------------------------------------------------------

    /** p0 resumes at inpaint: OCR + translation committed, cleaned image missing. */
    private fun resumeInpaintP0(): PageTranslation = PageTranslation(sourceFileName = "p0").apply {
        ocrStatus = StageStatus.READY
        translationStatus = StageStatus.READY
        inpaintStatus = StageStatus.PENDING
        renderStatus = StageStatus.PENDING
        blocks += FakeCoexistence.textBlock("hello-p0")
    }

    @Test
    fun `condition A - resume success must not be typed as a native timeout`() = runBlocking<Unit> {
        val store = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = mapOf("p0" to resumeInpaintP0()),
        )
        val h = TranslationCoexistenceHarness.create(listOf("p0", "p1"), storeOverride = store)
        harnessRef.set(h)
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        // The resume tail's cleaned-image publication is the REAL store commit
        // (sanctioned disk/graphics seam; no Bitmap.compress on the JVM).
        coEvery {
            h.cleanedPublicationMock.persistCleanedBitmap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } coAnswers {
            val page = arg<PageTranslation>(0)
            val pageKey = arg<String>(3)
            val publicationStore = arg<ChapterTranslationStore>(5)
            page.cleanedImageName = "$pageKey.cleaned.jpg"
            page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            page.inpaintingModeUsed = "FAST"
            page.inpaintStatus = StageStatus.READY
            page.errorMessage = null
            val precondition = publicationStore.snapshot(pageKey).toPrecondition()
            val published = publicationStore.updatePageGuarded(
                pageKey,
                precondition,
                "publish cleaned image (P5 resume stub)",
            ) { current ->
                (current ?: page).apply {
                    cleanedImageName = page.cleanedImageName
                    inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                    inpaintingModeUsed = page.inpaintingModeUsed
                    inpaintStatus = StageStatus.READY
                    errorMessage = null
                }
            }
            check(published is ChapterTranslationStore.PatchResult.Accepted) {
                "P5 resume stub commit rejected: " +
                    "${(published as? ChapterTranslationStore.PatchResult.Rejected)?.reason}"
            }
            published.snapshot
        }

        h.tapManual("p0")
        val job = h.capturedManualJob("p0")
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }

        // Store truth first: the resume path drains its render tails BEFORE the
        // boundary types the outcome, so the durable page must be terminal.
        withClue("P5 condition A precondition: the resume commit landed durably") {
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                store.state.first { it["p0"]?.hasRenderedResult() == true }
            }
            store.snapshot("p0").page?.renderStatus shouldBe StageStatus.READY
        }

        val outcome = readManualOutcome(h, "10:p0")
        if (outcome !is SinglePageOutcome.Completed) {
            throw AssertionError(
                "T917 P5 RED defect (condition A, phase4 review §5 Deviation #7): a SUCCESSFUL " +
                    "resume (inpaint+render tail committed a durable rendered result) was typed " +
                    "as $outcome instead of SinglePageOutcome.Completed — the boundary's " +
                    "`onnxResult ?: return Failed(\"native phase timed out\")` conflates the " +
                    "resume-null-on-success shape with a native timeout. The store holds " +
                    "renderStatus=READY truth; the typed outcome must match it via the " +
                    "buildTerminalPreparedPage store-terminality discipline.",
            )
        }
    }

    // ------------------------------------------------------------------
    // Condition B — HTTP+render timeout must not fall through to Completed
    // ------------------------------------------------------------------

    @Test
    fun `condition B - HTTP render timeout must not be typed Completed`() = runBlocking<Unit> {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            preRegisterInStore = false,
            // Requesting the seam IS the RED assertion when it is missing
            // (harness converts NoSuchFieldException into the named defect).
            httpRenderTimeoutMs = 150L,
        )
        harnessRef.set(h)
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        // Park the provider transport: the HTTP+render phase suspends inside
        // withTimeoutOrNull and the injected 150 ms result timer fires.
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
        h.tapManual("p0")
        h.barrier.awaitArrivalWithin(
            CoexistenceBarrier.BarrierPoint.PROVIDER_START,
            "p0",
            TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
        )
        val job = h.capturedManualJob("p0")
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }
        h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")

        val outcome = readManualOutcome(h, "10:p0")
        val page = h.store.snapshot("p0").page
        withClue("P5 condition B precondition: the timed-out page is not durably rendered") {
            page?.hasRenderedResult() shouldBe false
        }
        val failure = when (outcome) {
            is SinglePageOutcome.Completed -> AssertionError(
                "T917 P5 RED defect (condition B / D8-1): an HTTP+render result-timer timeout " +
                    "(withTimeoutOrNull → null ChunkCompletionOutcome) fell through the Paused-only " +
                    "mapping to SinglePageOutcome.Completed — the same §7 lie class D8 fixed for " +
                    "the native timer, one level up. It must be typed as a visible non-success " +
                    "naming the HTTP+render timer.",
            )
            is SinglePageOutcome.Failed -> null
            else -> AssertionError(
                "T917 P5 (condition B): HTTP+render timeout produced $outcome; expected the " +
                    "Failed family naming the HTTP+render result timer",
            )
        }
        failure?.let { throw it }
        withClue("P5 condition B: the failure must name the timer that actually fired") {
            (outcome as SinglePageOutcome.Failed).reason.contains("HTTP+render") shouldBe true
        }
    }

    // ------------------------------------------------------------------
    // Condition C — value failures and publication rejections
    // ------------------------------------------------------------------

    @Test
    fun `condition C - publication rejection must surface as typed non-success, not Completed`() = runBlocking<Unit> {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            preRegisterInStore = false,
        )
        harnessRef.set(h)
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        // Park the transport AFTER the paid call so the phase's commit
        // precondition is captured, then bump the page version under it: the
        // guarded final patchPage must reject → ChunkCompletionOutcome.PersistenceRejected.
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p0")
        h.tapManual("p0")
        h.barrier.awaitArrivalWithin(
            CoexistenceBarrier.BarrierPoint.PROVIDER_END,
            "p0",
            TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
        )
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
            h.store.updatePageGuarded(
                "p0",
                h.store.snapshot("p0").toPrecondition(),
                "P5 concurrent writer (test fixture)",
            ) { current -> current ?: PageTranslation(sourceFileName = "p0") }
        }
        h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p0")
        val job = h.capturedManualJob("p0")
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }

        val outcome = readManualOutcome(h, "10:p0")
        withClue("P5 condition C precondition: the rejected publication left no durable display") {
            h.store.snapshot("p0").page?.hasRenderedResult() shouldBe false
        }
        val rejected = outcome as? SinglePageOutcome.Rejected
        if (rejected == null) {
            throw AssertionError(
                "T917 P5 RED defect (condition C, P3 finding 5): a guarded-commit rejection " +
                    "(ChunkCompletionOutcome.PersistenceRejected from the HTTP phase) surfaced as " +
                    "$outcome — the boundary maps only ChunkCompletionOutcome.Paused and returns " +
                    "Completed for every other value. A page whose translation could not be saved " +
                    "must be a visible typed non-success (Rejected with the stable not-saved reason), " +
                    "never Completed.",
            )
        }
        withClue("P5 condition C: the not-saved reason must be stable for the UI mapper") {
            rejected.reason shouldBe "Translation could not be saved; retry required"
            rejected.owner shouldBe null
        }

        // Fail-closed recovery: an explicit forced retry re-reads current store
        // state and commits successfully (store truth is the oracle; the retry
        // job's outcome replaces the bounded manualOutcomes entry asynchronously,
        // so it is deliberately not asserted here).
        h.tapManual("p0", force = true)
        withClue("P5 condition C: a retry after a publication rejection must recover") {
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                h.store.state.first { it["p0"]?.hasRenderedResult() == true }
            }
        }
    }

    @Test
    fun `condition C - typed provider failure value must surface as Failed, not Completed`() = runBlocking<Unit> {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            preRegisterInStore = false,
        )
        harnessRef.set(h)
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        // Swap the engine lane's translator for a terminal-failing fake: the
        // HTTP phase catches the typed ProviderFailureException and returns
        // ChunkCompletionOutcome.Failed as a VALUE (never throws).
        val failingTransport = object : eu.kanade.translation.translator.TextTranslator {
            override val fromLang = TextRecognizerLanguage.JAPANESE
            override val toLang = TextTranslatorLanguage.ENGLISH

            override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
                throw ProviderFailureException(
                    ProviderFailure(
                        kind = ProviderFailureKind.CONFIGURATION,
                        retryability = ProviderFailureRetryability.TERMINAL,
                        safeSummary = "terminal provider failure (P5 fixture)",
                    ),
                )
            }

            override fun close() {}
        }
        TranslationCoexistenceHarness.setField(h.engineLane, "textTranslator", failingTransport)

        h.tapManual("p0")
        val job = h.capturedManualJob("p0")
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }

        val outcome = readManualOutcome(h, "10:p0")
        val page = h.store.snapshot("p0").page
        withClue("P5 condition C precondition: the durable page is a terminal failure, not a display") {
            page?.isStageFailed shouldBe true
            page?.hasRenderedResult() shouldBe false
        }
        val failed = outcome as? SinglePageOutcome.Failed
        if (failed == null) {
            throw AssertionError(
                "T917 P5 RED defect (condition C, P3 finding 5): ChunkCompletionOutcome.Failed " +
                    "returned as a VALUE by the HTTP phase surfaced as $outcome — a terminal page " +
                    "failure must never be typed Completed. Expected SinglePageOutcome.Failed " +
                    "carrying the provider's safe reason.",
            )
        }
        withClue("P5 condition C: the failure must carry the provider's safe reason") {
            failed.reason shouldBe "terminal provider failure (P5 fixture)"
            failed.pageKey shouldBe "p0"
        }
    }

    // ------------------------------------------------------------------
    // Batch — non-durable publication warning reaches the snapshot
    // ------------------------------------------------------------------

    @Test
    fun `batch persistence rejection carries the non-durable warning on the progress snapshot`() = runBlocking<Unit> {
        // Durable truth oracle: the reconciler already keeps the rejected page
        // pending and flags nonDurableFailure (fail-closed store contract).
        val reconciled = eu.kanade.translation.pipeline.batch.BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to PageTranslation(sourceFileName = "p0")),
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 1L,
            pauseOutcome = BatchPass1Outcome(
                needsTranslation = listOf("p0"),
                status = BatchPass1Status.PERSISTENCE_REJECTED,
                anchorPageKey = "p0",
                reason = "Batch persistence publication rejected",
            ),
        )
        withClue("P5 batch precondition: the reconciler keeps the rejected page out of success") {
            reconciled.nonDurableFailure shouldBe true
            reconciled.doneCount shouldBe 0
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = mapOf(
                "p0" to PageTranslation(sourceFileName = "p0"),
                "p1" to PageTranslation(sourceFileName = "p1"),
            ),
        )
        val tracker = TranslationBatchProgressTracker(1L, store, listOf("p0", "p1"), scope)
        try {
            tracker.pause(
                BatchPass1Outcome(
                    needsTranslation = listOf("p0"),
                    status = BatchPass1Status.PERSISTENCE_REJECTED,
                    anchorPageKey = "p0",
                    reason = "Batch persistence publication rejected",
                ),
            )
            val snapshot = tracker.awaitTerminalSnapshot()
            val warning = readBooleanField(snapshot, "nonDurableFailure")
            if (warning != true) {
                throw AssertionError(
                    "T917 P5 RED defect (spec §4.1): the progress snapshot carries no " +
                        "non-durable-publication warning — BatchPass1Status.PERSISTENCE_REJECTED " +
                        "paused the batch but TranslationProgressSnapshot has no bounded " +
                        "nonDurableFailure/nonDurableFailureReason value fields, so the drawer/" +
                        "notification cannot show 'Translation not saved — retry required' and " +
                        "the affected page can be mistaken for success.",
                )
            }
            val reason = readStringField(snapshot, "nonDurableFailureReason")
            if (reason != "Batch persistence publication rejected") {
                throw AssertionError(
                    "T917 P5 RED defect (spec §4.1): snapshot.nonDurableFailureReason was " +
                        "$reason — the safe rejection reason must ride the bounded value field",
                )
            }
            withClue("P5 batch: the rejected page must not count as terminal success") {
                snapshot.donePages shouldBe 0
                snapshot.processedPages shouldBe 0
            }
        } finally {
            tracker.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun PageTranslation.hasRenderedResult(): Boolean = renderStatus == StageStatus.READY

    private fun readManualOutcome(h: TranslationCoexistenceHarness, key: String): Any? {
        var cls: Class<*>? = h.scheduler.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField("manualOutcomes")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return (field.get(h.scheduler) as Map<String, Any>)[key]
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("T917 P5 RED defect: scheduler manualOutcomes seam is missing")
    }

    private fun readBooleanField(target: Any, name: String): Boolean? = try {
        readField(target, name) as? Boolean
    } catch (_: AssertionError) {
        null
    }

    private fun readStringField(target: Any, name: String): String? = try {
        readField(target, name) as? String
    } catch (_: AssertionError) {
        null
    }

    private fun readField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("T917 P5 RED defect: field $name is missing on ${target.javaClass}")
    }
}
