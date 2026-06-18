# TachiyomiAT Translation Consistency Audit & Refactor Plan

## Goal

Stabilize auto-translation so that the currently viewed page `n` is always translated first, then bounded prefetch translates `n + 1`, `n + 2`, etc., without randomly skipping pages, deadlocking the queue, leaking memory, or showing original images when translated output should exist.

This is an Android app, so the refactor must prioritize:

* Low memory usage
* Predictable lifecycle cleanup
* Current-page-first UX
* Safe cancellation/retry behavior
* No uncontrolled parallel OCR/inpaint/render work

---

## Current Diagnosis

The problem is probably not simply “the queue is dead.”

More likely causes:

1. A page is skipped before entering the queue because `shouldSkipAutoScheduling` says it does not need work.
2. A page has OCR/text translation data but no rendered/cleaned image, so the scheduler thinks it is handled while the reader still has nothing translated to display.
3. Background/cancel/memory cleanup clears streams or jobs, leaving partially processed pages in confusing states.
4. Page keys may be unstable across reader/source/download states.
5. Rarely, a stuck native/ONNX/HTTP call can block the single translator permit or require watchdog cleanup.

---

## Highest Priority Bug to Audit

### Problem

Current skip logic includes `hasRecognizedTranslation`.

That means this state can be skipped:

```text
ocrStatus = READY
translationStatus = READY
blocks.isNotEmpty()
renderedImageName = null
cleanedImageName = null
```

But the reader display needs `renderedImageName` or `cleanedImageName` to show translated output.

So the app can reach:

```text
Text was translated internally
↓
No rendered image exists
↓
Auto scheduler skips page
↓
User still sees original image
```

### Expected Fix

For rendered-image mode, only these should be terminal successful states:

```kotlin
renderedImageName != null || cleanedImageName != null
```

Change auto skip logic from:

```kotlin
val PageTranslation.shouldSkipAutoScheduling: Boolean
    get() = hasRenderedResult ||
        isStageRunning ||
        hasExhaustedRetries ||
        isTextlessTerminal ||
        hasRecognizedTranslation
```

to:

```kotlin
val PageTranslation.shouldSkipAutoScheduling: Boolean
    get() = hasRenderedResult ||
        isStageRunning ||
        hasExhaustedRetries ||
        isTextlessTerminal
```

Do not treat `hasRecognizedTranslation` as done unless the UI can display text overlays without requiring rendered images.

---

## Audit Phase 1 — Confirm Scheduling Behavior

### Files

* `ReaderViewModel.kt`
* `TranslationManager.kt`
* `PageTranslation` lifecycle/extension file

### Verify

Auto-translation must always use:

```text
current visible ReaderPage
↓
currentIndex inside current chapter.pages
↓
currentIndex, currentIndex + 1, currentIndex + 2...
```

It must not start from page 1 unless page 1 is the current page.

### Checkpoints

* `handleAutoTranslation(currentPage)` uses the passed `ReaderPage`, not global page number text.
* `currentIndex = pages.indexOfFirst { it === currentPage }`.
* Prefetch is clamped to current chapter only.
* Memory headroom blocks only prefetch pages, not the currently visible page.
* Right-to-left, left-to-right, vertical, webtoon, long-strip, and long-strip-with-gap modes all still map to logical page order correctly.
* The current visible page is chosen consistently in long-strip/webtoon mode, preferably by largest visible area or viewport center.

### Acceptance Test

Open a chapter at page 12 with auto-translate enabled.

Expected queue order:

```text
12 → 13 → 14 → 15
```

Not:

```text
1 → 2 → 3
```

---

## Audit Phase 2 — Fix Page Completion Semantics

### Files

* `PageTranslation.kt`
* Page lifecycle extension file
* `ReaderViewModel.observeLiveTranslationStore`
* `TranslationManager.translatePagesSequential`

### Define Terminal States

A page is display-ready only if:

```kotlin
renderedImageName != null || cleanedImageName != null
```

A page is text-ready but not display-ready if:

```kotlin
ocrStatus == READY &&
translationStatus == READY &&
blocks.isNotEmpty() &&
renderedImageName == null &&
cleanedImageName == null
```

This state should not be skipped by auto scheduling.

### Add or Clarify Lifecycle

Preferred lifecycle model:

```kotlin
sealed interface PageLifecycle {
    data object Pending : PageLifecycle
    data object Queued : PageLifecycle
    data class Running(val stage: PageStage) : PageLifecycle
    data object Done : PageLifecycle
    data object Textless : PageLifecycle
    data object Cancelled : PageLifecycle
    data class FailedRetryable(
        val stage: PageStage,
        val retryCount: Int,
        val reason: String?,
    ) : PageLifecycle
    data class FailedPermanent(
        val stage: PageStage,
        val retryCount: Int,
        val reason: String?,
    ) : PageLifecycle
    data object NeedsRender : PageLifecycle
}
```

`NeedsRender` should represent:

```text
OCR/text translation exists, but no rendered/cleaned output exists.
```

### Scheduler Rule

```kotlin
when (page.lifecycle) {
    Done,
    Textless,
    FailedPermanent -> skip

    Queued,
    is Running -> skip

    Pending,
    Cancelled,
    is FailedRetryable,
    NeedsRender -> schedule
}
```

---

## Audit Phase 3 — Cancellation Must Not Equal Real Failure

### Files

* `TranslationManager.kt`
* `ReaderViewModel.kt`
* `ChapterTranslator.kt`
* Page lifecycle extension file

### Current Risk

Cancellation can currently become `FAILED`.

But Android cancellation can happen because of:

```text
reader closed
chapter changed
app paused
memory pressure
user pressed stop
temporary system interruption
```

That is not the same as:

```text
OCR failed
image decode failed
inpaint failed
render failed
translator failed
```

### Recommended Fix

Add:

```kotlin
const val CANCELLED = "CANCELLED"
```

or represent it only at lifecycle level.

Cancellation should be retryable by auto-translate.

Real failures should increment `retryCount`.

Cancellation should usually not increment `retryCount`, unless the cancellation was caused by timeout/watchdog.

### Acceptance Test

Start translating page 8, background the app, return to the same chapter.

Expected:

```text
Page 8 retries when visible again.
Page 8 is not permanently skipped.
Queue does not stay stuck in RUNNING.
```

---

## Audit Phase 4 — Page Key Stability

### Files

* `ReaderViewModel.resolvePageKey`
* `ChapterTranslator.readerPageStreamKey`
* `ChapterTranslationStore`
* Any JSON persistence code

### Current Risk

Current key fallback may depend on:

```text
sourceFileName
translation.sourceFileName
imageUrl filename
page.url filename
```

If `sourceFileName` appears later or differs between downloaded and streamed pages, store entries may become invisible.

### Recommended Direction

Introduce a canonical page identity:

```kotlin
data class TranslationPageId(
    val sourceId: Long,
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
)
```

Use this for scheduling and job dedup.

Use a separate display/storage filename for files:

```kotlin
data class TranslationPageStorageName(
    val fileName: String,
)
```

Do not mix scheduling identity with storage filename.

### Acceptance Test

Translate page from streamed source, reopen after chapter is downloaded/cache state changes.

Expected:

```text
Same page maps to same translation entry.
No duplicate JSON entries for the same visible page.
```

---

## Audit Phase 5 — Stream Ownership & Memory Cleanup

### Files

* `ChapterTranslator.kt`
* `ReaderViewModel.kt`
* `TranslationManager.kt`

### Current Risk

`readerPageStreams` is a global map of `() -> InputStream` factories. Those factories may capture `ReaderPage`, downloaded byte arrays, or other resources. Clearing them is necessary for memory, but clearing them too aggressively can interrupt retry/resume.

### Recommended Direction

Move stream ownership into a per-chapter `TranslationSession`.

Instead of global process lifetime ownership:

```text
ChapterTranslator.readerPageStreams
```

prefer:

```text
TranslationSession.pageStreams
```

Session owns:

```text
chapter id
page ids
registered streams
store
active jobs
cleanup
```

### Cleanup Rules

* Chapter switch: clear only that chapter session.
* Reader close: clear current session.
* Memory pressure: clear prefetch streams first, preserve current visible page if possible.
* Background pause: avoid hard teardown unless app is truly stopping or memory is low.

### Acceptance Test

Open online chapter, auto-translate current page, background briefly, return.

Expected:

```text
No crash.
No blank translated image.
Current page can retry.
Prefetch may be discarded safely.
```

---

## Audit Phase 6 — Keep Single-Page Processing, Improve Resumability

### Do Not Parallelize Heavy Stages

Keep this rule:

```text
Only one heavy page pipeline at a time.
```

This protects Android memory because OCR/inpaint/render can hold large bitmaps and ONNX tensors.

### Improve Resumability Instead

Pipeline should resume from the latest valid stage:

```text
If OCR exists, do not redo OCR.
If translated blocks exist, do not redo text translation.
If cleaned image exists, do not redo inpaint.
If rendered image is missing, render only.
```

### Stage Resume Logic

For each page:

```text
No OCR blocks → decode + OCR
OCR blocks but no translated text → translate
Translated blocks but no cleaned image → inpaint
Cleaned image but no rendered image → render
Rendered image exists → done
```

### Acceptance Test

Force kill/cancel after text translation but before render.

On retry:

```text
Should not redo OCR/text translation if existing blocks are valid.
Should continue to inpaint/render.
Final rendered image should appear.
```

---

## Audit Phase 7 — TranslationManager Queue Health

### Files

* `TranslationManager.kt`
* `ChapterTranslator.kt`

### Verify

* `queuedPageKeys` is removed in every success/failure/cancel path.
* `activePageJobs` is removed in every success/failure/cancel path.
* Watchdog timeout evicts stale active jobs.
* Sequential queue re-checks page state before execution.
* Cancelled jobs do not leave pages permanently RUNNING.
* Manual translate can override retry exhaustion.

### Add Debug Diagnostics

For every auto request, log:

```text
chapterId
pageKey
pageIndex
reason: scheduled / skipped
skip reason
current lifecycle
hasRenderedResult
hasRecognizedTranslation
retryCount
isStageRunning
source stream available
imageUrl available
```

Example:

```kotlin
logcat(LogPriority.DEBUG) {
    "AutoTranslate decision: page=$pageKey index=$index decision=$decision " +
    "lifecycle=${pt?.lifecycle} rendered=${pt?.hasRenderedResult} " +
    "recognized=${pt?.hasRecognizedTranslation} retry=${pt?.retryCount}"
}
```

### Acceptance Test

When an image does not translate, logs must clearly show one of:

```text
scheduled
skipped: already running
skipped: rendered result exists
skipped: textless
skipped: retries exhausted
soft skip: no stream yet
failed: decode/OCR/inpaint/render error
```

No silent skips.

---

## Suggested Refactor Structure

### New Classes

```text
TranslationSession
TranslationScheduler
TranslationPageId
TranslationLifecyclePolicy
TranslationPipeline
TranslationStreamRegistry
```

### Responsibilities

#### ReaderViewModel

Should only:

```text
detect current page
request translation window
observe store state
refresh UI
```

Should not own:

```text
dedup policy
retry policy
stream registry
stage lifecycle
pipeline decisions
```

#### TranslationScheduler

Owns:

```text
current-page-first window
prefetch depth
memory-gated lookahead
skip/schedule decisions
dedup before queue
```

#### TranslationSession

Owns:

```text
chapter store
page streams
session cleanup
active chapter identity
```

#### TranslationManager

Owns:

```text
job execution
activePageJobs
queuedPageKeys
sequential ordering
cancellation
```

#### ChapterTranslator / TranslationPipeline

Owns:

```text
decode
OCR
translation
inpaint
render
persist
stage resume
memory-safe single-page processing
```

---

## Immediate Patch Order

### Patch 1 — Fix Auto Skip

Remove `hasRecognizedTranslation` from `shouldSkipAutoScheduling`.

This is the fastest and most likely fix.

### Patch 2 — Add Decision Logging

Add explicit schedule/skip logs in `handleAutoTranslation` and `translatePagesSequential`.

### Patch 3 — Add `Cancelled` Lifecycle

Stop treating normal cancellation as real OCR/inpaint failure.

### Patch 4 — Add `NeedsRender`

Represent partial success:

```text
OCR/text done, render output missing
```

and make it schedulable.

### Patch 5 — Stabilize Page Keys

Separate page identity from storage filename.

### Patch 6 — Extract TranslationScheduler

Move window building and skip policy out of `ReaderViewModel`.

### Patch 7 — Extract TranslationSession

Move store + stream ownership into a per-chapter session.

### Patch 8 — Implement Stage Resume

Do not redo completed stages when retrying.

---

## Final Acceptance Checklist

Auto-translation is considered fixed when:

* Opening page `n` translates page `n` first.
* Prefetch translates `n + 1`, `n + 2`, etc. only when memory allows.
* Current page is never skipped due to low prefetch memory.
* A page with translated text but no rendered image retries and eventually renders.
* Cancel/background does not permanently poison a page.
* Failed pages retry up to the configured limit.
* Manual translate can force retry beyond auto limit.
* Online/streamed pages soft-skip when bytes are unavailable, without burning retry count.
* No duplicate translation of the same page during rapid scrolling.
* No page stays RUNNING forever after timeout/cancel.
* No random old store entry disappears because page key changed.
* Memory pressure clears prefetch resources without breaking current-page retry.
