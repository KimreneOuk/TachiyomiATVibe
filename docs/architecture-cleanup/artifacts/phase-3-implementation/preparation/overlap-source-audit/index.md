---
title: "Phase 3 same-page inpaint and HTTP translation overlap source audit"
kind: review
comments: none
---

# Executive Summary

This read-only audit traces the concrete code paths governing same-page OCR, inpainting, external HTTP translation, and final rendering in `tachiyomiATVibe-inpainting-modes-redesign` at baseline `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`.

The audit specifically examines the user requirement to **preserve same-page inpaint + external translation overlap after OCR, with final rendering waiting on both**, while maintaining the **memory-first** bitmap ownership model.

# 1. Provider Inputs & Background/Bitmap Dependence Before Final Render

### Exact Source Citations

- [`SinglePageHttpRenderPhase.kt:211-275`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L211-L275): Entry point of `translateSinglePageHttpRender`.
- [`SinglePageHttpRenderPhase.kt:400-445`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L400-L445): Context construction in `prepareTranslationContext` and invocation in `translateOnce`.
- [`TextTranslator.kt:13-24`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/translator/TextTranslator.kt#L13-L24): Interface definition for `TextTranslator`.
- [`TranslationContextChunkPlanner.kt:244-265`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/translator/contextual/TranslationContextChunkPlanner.kt#L244-L265): Interface definition for `ContextualTextTranslator`.
- [`RenderColorEstimator.kt:270-320`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/rendering/RenderColorEstimator.kt#L270-L320): Implementation of `recomputeFor(cleanedBitmap, blocks)`.

### Concrete Findings

1. **HTTP Translation Provider Inputs**:
  - The interface `TextTranslator.translatePage(pageKey, page)` takes the full mutable `PageTranslation` instance.
  - However, in current active single-page execution:
    - Contextual translators implement `ContextualTextTranslator.translateContextual(chunk)`, which receives `TranslationContextChunk` built in `prepareTranslationContext` from `targetPage.blocks.count { it.text.isNotBlank() }`, `block.text`, and rolling history tokens.
    - Plain translators implement `TextTranslator.translatePage(pageKey, targetPage)` and iterate over `targetPage.blocks` to map text.
  - None of the active translator implementations read `Bitmap`, `cleanedBitmap`, `segmentationMask`, or background pixel values.
2. **Inpainting Inputs**:
  - `PageInpaintingEngine.inpaint` builds `PageInpaintingPlanner.build(pageTranslation)` ([`PageInpaintingEngine.kt:35`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt#L35)), which consumes `pageTranslation.inpaintMaskBoxes` and `pageTranslation.blocks` (for detector text labels). It does not inspect `block.translation`.
3. **Background/Bitmap Dependence is Isolated to Final Render**:
  - `RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)` ([`SinglePageHttpRenderPhase.kt:757`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L757)) samples background colors from `cleanedBitmap` to decide `block.textColor` (contrast snap) before overlay drawing.
  - Text overlay rendering requires both `cleanedBitmap` (the Canvas background) and `block.translation` (the text to layout and draw).
  - **Conclusion**: OCR produces the common prerequisite for both: text blocks (for HTTP) and text boxes/regions (for inpaint). Prior to final color recomputation and rendering, the translation pipeline requires no bitmap pixels, and inpainting requires no translated text.

# 2. Shared `PageTranslation` and `TranslationBlock` Mutations

### Exact Source Citations

- [`PageTranslation.kt:302-3`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt#L302-L350)[`70`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt#L302-L370): Definition of `TranslationBlock` (including `segmentationMask: BubbleMaskRle?` and `var userEditedAt: Long?`).
- [`BubbleMaskRle.kt:7-40`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/BubbleMaskRle.kt#L7-L40): Definition of `BubbleMaskRle`.
- [`PageTranslationView.kt:425-436`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslationView.kt#L425-L436): `BubbleMaskRle.immutableCopy()` and `Collections.unmodifiableList` defensively wrapping `bounds` and `runs`.
- [`PageInpaintingEngine.kt:44-45, 107-161`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt#L44-L161): Inpainting page-level status, error, and timestamp mutations.
- [`RoiPageRecognitionEngine.kt:986-1010`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt#L986-L1010): Engine-level inpainting status mutations.
- [`SinglePageHttpRenderPhase.kt:495-585`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L495-L585): Block sorting, provider mutation, validation, and partial retries.

### Concrete Findings

1. **Mutations during Inpainting**:
  - Inpainting mutates **page-level properties** on the shared `PageTranslation` instance:
    - Sets `pageTranslation.inpaintStatus = StageStatus.RUNNING` and `updatedAt = System.currentTimeMillis()` ([`PageInpaintingEngine.kt:44-45`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt#L44-L45)).
    - On completion, `markReady` sets `pageTranslation.inpaintStatus = StageStatus.READY`, `inpaintingModeUsed`, and `updatedAt` ([`PageInpaintingEngine.kt:153-161`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt#L153-L161)).
    - On failure, sets `inpaintStatus = StageStatus.FAILED`, `errorMessage`, and calls `pageTranslation.recordAttemptFailure()` ([`PageInpaintingEngine.kt:137-142`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt#L137-L142)).
    - `RoiPageRecognitionEngine.kt:991-994` similarly sets `inpaintStatus = StageStatus.FAILED` if engine is closed.
  - Inpainting passes `pageTranslation.blocks` to `inpainter.inpaintRegions(...)` ([`PageInpaintingEngine.kt:107, 118, 130`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt#L107)).
2. **Mutations during HTTP Translation**:
  - `TranslationBlockSorter.sort(pageTranslation.blocks, ...)` reorders the `blocks` list.
  - Translators mutate `block.translation` in place.
  - Sets `pageTranslation.translationStatus = StageStatus.RUNNING`, `READY`, `PARTIAL`, or `FAILED`.
  - Mutates `pageTranslation.updatedAt`, `errorMessage`, `translationError`.
3. **Immutability and Copying Realities**:
  - **`BubbleMaskRle` is NOT intrinsically strictly immutable**: its properties `val bounds: List<Int>` and `val runs: List<Int>` are typed as `List<Int>`, which in Kotlin can wrap mutable `ArrayList` instances. In [`PageTranslationView.kt:425-436`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslationView.kt#L425-L436), the codebase explicitly provides `BubbleMaskRle.immutableCopy()` using `Collections.unmodifiableList(ArrayList(values))` to guarantee true immutability for published views.
  - `TranslationBlock`** contains complex references**: beyond primitives, `TranslationBlock` contains `segmentationMask: BubbleMaskRle?` and `var userEditedAt: Long?`.
  - **Sharing a single `PageTranslation` instance during concurrent inpaint and HTTP is unsafe**: Both concurrent branches perform unsynchronized writes to `pageTranslation` fields (`inpaintStatus`, `translationStatus`, `updatedAt`, `errorMessage`, `blocks`).
  - **Remedy / Obligation**: Overlap requires separating HTTP draft work from the master page state. If a private draft is used, blocks must be deep-copied or use defensive unmodifiable wrappers for masks (`BubbleMaskRle.immutableCopy()`), and page-level stage statuses must be merged atomically under attempt authority rather than mutated concurrently on a shared raw `PageTranslation` instance.

# 3. Native Admission / Quarantine Boundaries

### Exact Source Citations

- [`NativeRunQuarantine.kt:48-120`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/execution/NativeRunQuarantine.kt#L48-L120): `admission = Mutex()` and timeout/cancellation quarantine loop.
- [`NativeRunQuarantine.kt:72`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/execution/NativeRunQuarantine.kt#L72): `val callerContext = currentCoroutineContext().minusKey(Job)` strips the caller `Job`.
- [`EngineLane.kt:163-195`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt#L163-L195): `withNativeLane` permit holder and quarantine dispatch.
- [`TranslationPipeline.kt:801-803, 1343`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L801-L803): Reader retry adapter call site.
- [`BatchLaneWorkers.kt:683, 732`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt#L683): Batch lane retry execution inside batch inpaint permit.

### Concrete Findings

1. **Native Lane Single-Occupancy Invariant**:
  - `NativeRunQuarantine.admission` is a non-reentrant coroutine `Mutex()`. Only one native execution can be admitted across the whole app at any instant.
2. **HTTP Translation Must Run Outside Native Admission**:
  - HTTP translation performs remote network I/O.
  - If HTTP translation were to run inside `withNativeLane`, the global native permit would be blocked across network waits, starving all other OCR and inpainting operations.
3. **Quarantine Job Stripping & External Work**:
  - Inside `NativeRunQuarantine.run`, line 72 explicitly strips the caller `Job` from the context when launching `invocation = scope.async(callerContext)`.
  - Therefore, external HTTP translation **must NOT be launched from inside the quarantine block**. Launching unowned external work from a Job-stripped quarantine would disconnect it from caller cancellation and leak coroutine ownership.
4. **Retry Admission**:
  - Reader retry (`SinglePageHttpRenderPhase.kt:803` via `TranslationPipeline.kt:801-803`) invokes `retryInpaintDownscaled`.
  - Batch retry (`BatchLaneWorkers.kt:732`) runs already inside the batch inpaint permit.
  - To avoid unquarantined native execution or self-deadlock, reader retry must be admitted to `withNativeLane` in the reader wiring lambda (`TranslationPipeline.kt:801-802`), while keeping the shared delegate (`TranslationPipeline.kt:1343`) unadmitted.

# 4. Preserved Provider Cancellation, Paid-Call, and Drain Semantics

### Exact Source Citations

- [`SinglePageHttpRenderPhase.kt:346-368`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L346-L368): `runLedgerWrapped` durable attempt ledger tracking.
- [`SinglePageHttpRenderPhase.kt:415-430`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L415-L430): Governor wait span and provider lane token tracking.
- [`SinglePageHttpRenderPhase.kt:615-625`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L615-L625): Strict `CancellationException` propagation.
- [`TranslationPipeline.kt:645-660`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L645-L660): `drainDeferredPublications` fail-closed drain loop.
- [`DeferredPagePublications.kt:40-46`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/execution/DeferredPagePublications.kt#L40-L46): Orphan-inline tail execution on timeout.

### Concrete Findings

1. **Durable Attempt Ledger Integrity**:
  - `store.recordAttemptStart(...)` runs before dispatching the paid HTTP call.
  - If the call is cancelled (`CancellationException`), `runLedgerWrapped` rethrows **without** calling `store.resolveAttempt(pageKey)`. The uncompleted attempt record must remain in the store for startup reconciliation.
  - If the call completes (success or non-cancellation error), `store.resolveAttempt(pageKey)` is called in finally.
  - Any overlap structure must preserve this boundary.
2. **Provider Governor & Trace Spans**:
  - `PROVIDER_GOVERNOR_WAIT` span begins at phase entry and must settle exactly when the first translator network call begins.
  - `enterLane(TranslationTraceLane.PROVIDER)` token must enclose only the active network duration.
3. **Cancellation Transparency**:
  - If the parent operation is cancelled while inpaint and HTTP are overlapping, cancellation must propagate to both tasks.
  - An inpainting cancellation must await real native exit in quarantine (`awaitExitAndLogLate`), while an HTTP cancellation aborts the OkHttp call and preserves the attempt ledger record.

# 5. Host-Test Seams for True Overlap and Join Proof

### Exact Source Citations

- [`CoexistenceBarrier.kt:33-85`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/test/java/eu/kanade/translation/coexistence/CoexistenceBarrier.kt#L33-L85): Coexistence barrier test harness.
- [`FakeEngines.kt:97-150`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/test/java/eu/kanade/translation/coexistence/FakeEngines.kt#L97-L150): `FakeRecognitionEngine` implementation.

### Concrete Findings

1. **Actual Seams in `CoexistenceBarrier.kt`**:
  - The current `BarrierPoint` enum contains:
 `NATIVE_ACQUIRE`, `NATIVE_RELEASE`, `PROVIDER_START`, `PROVIDER_END`, `RENDER`, `COMMIT`, `ENGINE_CLOSE`, `RECONCILE`.
  - **There is currently NO `NATIVE_INPAINT` enum point**.
  - In `FakeRecognitionEngine.kt:108-118`:
    - `analyze()` arrives at `CoexistenceBarrier.BarrierPoint.NATIVE_RELEASE`.
    - `inpaint()` merely calls `inpaintCalls.incrementAndGet()` and returns a stub bitmap without arriving at any barrier.
2. **Narrow Test Addition Required for Sleep-Free Overlap Proof**:
  - To prove true inpaint-vs-provider rendezvous deterministically without sleeps, a narrow seam addition is required:
    - Add `INPAINT_START` / `INPAINT_END` to `BarrierPoint` in test code.
    - Have `FakeRecognitionEngine.inpaint()` arrive at `INPAINT_START` and wait for test relea`se`.
  - With this minimal addition, tests can park both inpaint and provider concurrently at their respective barrier gates (`INPAINT_START` and `PROVIDER_START`) and prove overlap and render join without arbitrary timing sleeps.

# 6. Summary of Proof Obligations for Plan Revision

1. **Coroutine Parentage & Structured Scope**: External HTTP translation must be launched from a structured caller scope outside native admission, never from inside the Job-stripped quarantine.
2. **PageTranslation Thread Safety**: The concurrent branches cannot safely share a single mutable `PageTranslation` instance. Statuses and drafts must be isolated and merged under attempt authority.
3. **Defensive Mask Copies**: `BubbleMaskRle` lists must be treated defensively (using `immutableCopy()`), not assumed intrinsically immutable.
4. **Cleaned Bitmap Ownership**: Bitmap handoff from inpaint must be registered with `CleanedOwner` before rendering borrows it.
