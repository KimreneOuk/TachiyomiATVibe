package eu.kanade.translation.model

import eu.kanade.translation.scheduling.TranslationLifecyclePolicy
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class PageTranslationStateTest {

    @Test
    fun `translated text without rendered image needs render and is schedulable`() {
        val page = translatedPage()

        page.lifecycle shouldBe PageLifecycle.NeedsRender
        page.shouldSkipAutoScheduling shouldBe false
    }

    @Test
    fun `translated text with only cleaned image still needs render and is schedulable`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
        }

        page.lifecycle shouldBe PageLifecycle.NeedsRender
        page.shouldSkipAutoScheduling shouldBe false
        page.displayImageName shouldBe null
    }

    @Test
    fun `rendered text page from old inpaint revision is schedulable`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintRevision = 0
        }

        page.lifecycle shouldBe PageLifecycle.NeedsRender
        page.shouldSkipAutoScheduling shouldBe false
        page.displayImageName shouldBe null
    }

    @Test
    fun `rendered text page from current inpaint revision is done`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.READY
        }

        page.lifecycle shouldBe PageLifecycle.Done
        page.shouldSkipAutoScheduling shouldBe true
        page.displayImageName shouldBe "001.cleaned.png"
    }

    @Test
    fun `downsampled text page with current inpaint revision is done`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            decodeSampleSize = 2
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.READY
        }

        page.lifecycle shouldBe PageLifecycle.Done
        page.shouldSkipAutoScheduling shouldBe true
        page.displayImageName shouldBe "001.cleaned.png"
    }

    @Test
    fun `downsampled text page does not need a separate render quality marker`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            decodeSampleSize = 2
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.READY
        }

        page.lifecycle shouldBe PageLifecycle.Done
        page.shouldSkipAutoScheduling shouldBe true
        page.displayImageName shouldBe "001.cleaned.png"
    }

    @Test
    fun `forced retry resets failed page without a result`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            renderStatus = StageStatus.FAILED,
            cleanedImageName = "001.cleaned.webp",
            decodeSampleSize = 2,
            retryCount = StageStatus.MAX_STAGE_RETRIES,
            errorMessage = "old failure",
        )

        page.prepareForcedRetry()

        page.ocrStatus shouldBe StageStatus.RUNNING
        page.translationStatus shouldBe StageStatus.PENDING
        page.inpaintStatus shouldBe StageStatus.PENDING
        page.renderStatus shouldBe StageStatus.PENDING
        page.retryCount shouldBe 0
        page.errorMessage shouldBe null
        page.cleanedImageName shouldBe null
    }

    @Test
    fun `forced retry clears trusted rendered output so retry cannot show stale result`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            retryCount = StageStatus.MAX_STAGE_RETRIES
            errorMessage = "old failure"
        }

        page.prepareForcedRetry()

        page.ocrStatus shouldBe StageStatus.RUNNING
        page.retryCount shouldBe 0
        page.errorMessage shouldBe null
        page.cleanedImageName shouldBe null
        page.cleanedImageName shouldBe null
        page.displayImageName shouldBe null
    }

    @Test
    fun `cancelled page is schedulable and does not count as retry exhaustion`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            errorMessage = "Translation cancelled",
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }

        page.lifecycle shouldBe PageLifecycle.Cancelled
        page.hasExhaustedRetries shouldBe false
        page.shouldSkipAutoScheduling shouldBe false
    }

    @Test
    fun `retry exhaustion blocks auto scheduling for failed pages`() {
        // TachiyomiAT: exhaustion now keys on attemptCount (distinct attempts),
        // not retryCount (per-stage increments). One failed attempt is NOT
        // exhausted even though a single reader-path cascade can increment
        // retryCount twice (inpaint then render) — that double-count was the
        // "cannot reprocess" bug.
        val oneFailedAttempt = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            retryCount = 2,
        ).apply { attemptCount = 1 }
        val exhausted = PageTranslation(
            ocrStatus = StageStatus.FAILED,
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }

        oneFailedAttempt.hasExhaustedRetries shouldBe false
        oneFailedAttempt.shouldSkipAutoScheduling shouldBe false
        exhausted.hasExhaustedRetries shouldBe true
        exhausted.shouldSkipAutoScheduling shouldBe true
    }

    // ── per-attempt retry semantics (the "cannot reprocess" fix) ──────────────

    @Test
    fun `recordAttemptFailure charges the attempt exactly once per attempt`() {
        // Simulates the inpaint→render cascade on the reader path: inpaint fails
        // (first terminal stage), then the render-block path also calls
        // recordAttemptFailure. attemptCount must be 1, not 2 — the whole point
        // of the fix. Callers set the stage FAILED before calling (matching real
        // pipeline usage), so the helper cannot guard on isStageFailed.
        val page = PageTranslation(
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
        )

        // First terminal stage: inpaint fails.
        page.inpaintStatus = StageStatus.FAILED
        page.recordAttemptFailure()
        page.attemptCount shouldBe 1
        page.retryCount shouldBe 1
        page.attemptCharged shouldBe true

        // Downstream render-block path also records (a consequence of the same
        // inpaint failure). Must be a no-op — the attempt was already charged.
        page.renderStatus = StageStatus.FAILED
        page.recordAttemptFailure()
        page.attemptCount shouldBe 1
        page.retryCount shouldBe 1
    }

    @Test
    fun `recordAttemptFailure is a no-op until resetAttemptCharge starts a fresh attempt`() {
        // A second, DISTINCT attempt must be counted: resetAttemptCharge clears
        // the per-attempt flag so the next failure charges again. This models
        // auto-translate retrying once after a heap reclaim.
        val page = PageTranslation(
            ocrStatus = StageStatus.FAILED,
        ).apply {
            attemptCount = 1
            attemptCharged = true
        }

        // Same attempt, another stage fails: no charge.
        page.inpaintStatus = StageStatus.FAILED
        page.recordAttemptFailure()
        page.attemptCount shouldBe 1

        // Fresh attempt: reset, then a failure charges again.
        page.resetAttemptCharge()
        page.recordAttemptFailure()
        page.attemptCount shouldBe 2
    }

    @Test
    fun `a single inpaint failure does not exhaust retries`() {
        // The headline regression: one transient inpaint failure must leave the
        // page retryable by auto-translate. Before the fix this was retryCount=2
        // (double-counted) → hasExhaustedRetries true → permanently dropped.
        // NB: must carry a non-empty blocks list — otherwise the page reads as
        // isTextlessTerminal (empty blocks + READY OCR + non-pending inpaint),
        // which independently sets shouldSkipAutoScheduling and would mask the
        // exhaustion predicate under test.
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "源", translation = "",
                    width = 10f, height = 10f, x = 0f, y = 0f,
                    symHeight = 1f, symWidth = 1f, angle = 0f,
                ),
            ),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.FAILED,
            renderStatus = StageStatus.FAILED,
            errorMessage = "Inpainting unavailable (low memory)",
        ).apply { attemptCount = 1 }

        page.isStageFailed shouldBe true
        page.hasExhaustedRetries shouldBe false
        page.shouldSkipAutoScheduling shouldBe false
        page.shouldSurfaceError shouldBe true
    }

    @Test
    fun `exhaustion requires distinct failed attempts not one cascade`() {
        // Two DISTINCT failed attempts (e.g. auto-translate retried once after a
        // heap reclaim, and the page failed again) → exhausted.
        val page = PageTranslation(
            ocrStatus = StageStatus.FAILED,
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }

        page.hasExhaustedRetries shouldBe true
    }

    @Test
    fun `prepareForcedRetry resets the attempt counter so a manual retry re-admits an exhausted page`() {
        // A page auto-translate gave up on. The manual per-page button resolves
        // force=true (a stage is FAILED), prepareForcedRetry runs, and the page
        // is re-admitted — the core fix for "cannot reprocess / retranslate".
        val exhausted = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            inpaintStatus = StageStatus.FAILED,
            renderStatus = StageStatus.FAILED,
            retryCount = 5,
            errorMessage = "Inpainting unavailable",
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }
        check(exhausted.hasExhaustedRetries)

        exhausted.prepareForcedRetry()

        exhausted.attemptCount shouldBe 0
        exhausted.retryCount shouldBe 0
        exhausted.ocrStatus shouldBe StageStatus.RUNNING
        exhausted.inpaintStatus shouldBe StageStatus.PENDING
        exhausted.renderStatus shouldBe StageStatus.PENDING
        exhausted.errorMessage shouldBe null
        exhausted.hasExhaustedRetries shouldBe false
        // NB: shouldSkipAutoScheduling is TRUE after the reset because ocrStatus
        // flips to RUNNING (the page is now in flight), NOT because of exhaustion.
        // The point of the fix is that exhaustion no longer blocks it — once the
        // RUNNING attempt completes (to a result or another failure), the page is
        // schedulable again. Verify the exhaustion flag specifically:
        TranslationLifecyclePolicy.reasons(exhausted).exhausted shouldBe false
    }

    @Test
    fun `attemptCount is not serialized so exhaustion does not survive a process restart`() {
        // attemptCount is @Transient: a FAILED page deserialized from disk (a
        // process restart / chapter reopen) starts with attemptCount=0, so a
        // transient failure is NOT permanently treated as exhausted across
        // restarts. This is intentional — see PageTranslation.attemptCount doc.
        val original = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            inpaintStatus = StageStatus.FAILED,
            retryCount = 3,
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }
        val serializer = PageTranslation.serializer()
        val roundTripped = Json.decodeFromString(
            serializer,
            Json.encodeToString(serializer, original),
        )

        roundTripped.attemptCount shouldBe 0
        roundTripped.ocrStatus shouldBe StageStatus.FAILED
        roundTripped.hasExhaustedRetries shouldBe false
    }

    // ── shouldSurfaceError: only real FAILED stages surface the red error ────

    @Test
    fun `shouldSurfaceError is true for a page with a failed stage and no result`() {
        val page = PageTranslation(
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.FAILED,
            errorMessage = "Translation incomplete: 0/2 blocks translated",
        )

        page.shouldSurfaceError shouldBe true
    }

    @Test
    fun `shouldSurfaceError is false for a cancelled page even with an error message`() {
        // The stranded-page sweep / per-page cancel writes "Translation
        // cancelled" / "Page was stranded...". Those are NOT failures and must
        // not paint the red error overlay.
        val page = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            translationStatus = StageStatus.CANCELLED,
            inpaintStatus = StageStatus.CANCELLED,
            renderStatus = StageStatus.CANCELLED,
            errorMessage = "Page was stranded mid-translation; reset as cancelled on chapter reopen",
        )

        page.shouldSurfaceError shouldBe false
    }

    @Test
    fun `shouldSurfaceError is false for a partial page`() {
        // PARTIAL produced output (some blocks); its explanatory message names
        // how many blocks translated but is not a failure to paint red.
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "源", translation = "source",
                    width = 10f, height = 10f, x = 0f, y = 0f,
                    symHeight = 1f, symWidth = 1f, angle = 0f,
                ),
            ),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
            errorMessage = "Translation partial: 1/2 blocks translated",
        )

        page.shouldSurfaceError shouldBe false
    }

    @Test
    fun `shouldSurfaceError is false for a textless terminal page`() {
        // Textless (OCR found nothing) is clean success; it carries no error
        // and must not be treated as a failure.
        val page = PageTranslation(
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
        )

        page.isTextlessTerminal shouldBe true
        page.shouldSurfaceError shouldBe false
    }

    @Test
    fun `shouldSurfaceError is false once a rendered result exists even if a stage failed`() {
        // A page that produced a rendered image shows the image, not a red
        // error — even if some stage is FAILED (e.g. a stale render from a
        // prior run while the current retry failed).
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.READY
            translationStatus = StageStatus.FAILED
            errorMessage = "stale"
        }

        page.shouldSurfaceError shouldBe false
    }

    @Test
    fun `overlay is suppressed until inpaint is ready`() {
        listOf(StageStatus.PENDING, StageStatus.RUNNING, StageStatus.FAILED).forEach { status ->
            val page = translatedPage().apply {
                cleanedImageName = "001.cleaned.jpg"
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                inpaintStatus = status
                renderStatus = StageStatus.READY
            }

            page.shouldShowTranslationOverlay shouldBe false
        }
    }

    @Test
    fun `cleaned image stays hidden before translated overlay is ready`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.PENDING
        }

        page.displayImageName shouldBe null
        page.isCleanedImageReady shouldBe true
        page.shouldShowTranslationOverlay shouldBe false
        page.lifecycle shouldBe PageLifecycle.NeedsRender

        page.renderStatus = StageStatus.READY

        page.shouldShowTranslationOverlay shouldBe true
        page.lifecycle shouldBe PageLifecycle.Done
    }

    @Test
    fun `overlay requires valid translation and render readiness`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.jpg"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.READY
        }

        page.translationStatus = StageStatus.PENDING
        page.shouldShowTranslationOverlay shouldBe false
        page.translationStatus = StageStatus.FAILED
        page.shouldShowTranslationOverlay shouldBe false
        page.translationStatus = StageStatus.PARTIAL
        page.blocks.first().translation = ""
        page.shouldShowTranslationOverlay shouldBe false
        page.blocks.first().translation = "translated"
        page.renderStatus = StageStatus.PENDING
        page.shouldShowTranslationOverlay shouldBe false
    }

    @Test
    fun `stale or missing cleaned output cannot satisfy the overlay gate`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
            renderStatus = StageStatus.READY
        }

        page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION - 1
        page.shouldShowTranslationOverlay shouldBe false
        page.cleanedImageName = null
        page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        page.shouldShowTranslationOverlay shouldBe false
    }

    private fun translatedPage(): PageTranslation {
        return PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "translated",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.PENDING,
        )
    }
}
