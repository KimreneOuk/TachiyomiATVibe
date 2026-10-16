---
title: "Independent Architecture Review: Cancellation and Resource Ownership (F-02, F-07, F-09) - Revised"
kind: review
comments: none
---

# Executive Summary

This independent review inspects findings **F-02**, **F-07**, and **F-09** of the owner's October 14, 2026 translation architecture audit against codebase baseline `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`.

| Finding | Topic | Verdict | Severity | Confidence | Primary Verified Structural Concern |
| --- | --- | --- | --- | --- | --- |
| **F-02** | Detached inpaint coroutine & native quarantine | **Partially Confirmed** | **High** | High | Orphaned background inpaint on late-result discard; unmanaged disposal of abandoned bitmaps; quarantine occupancy desync |
| **F-07** | Mutable stage data, blocks, and transient bitmap | **Partially Confirmed** | **Medium** | High | Concurrent read/write race on `block.translation` and styles between inpaint and HTTP translate |
| **F-09** | Synchronous bridges (`runBlocking`) | **Partially Confirmed** | **Medium** | High | Blocked IO carrier threads in Manager/Batch/Store; sync cancellation completion ordering load-bearing |

All three findings are **Partially Confirmed**. Prior descriptions oversimplified the runtime consequences: `NativeRunQuarantine` explicitly strips parent Jobs, `launchNonCancellable` runs on `Dispatchers.IO`, and `PublishedPageTranslation` already fences store state. However, distinct structural flaws—including late-result discard, concurrent block access, and thread-blocking bridges—are verified in source.

# Finding F-02: Detached Inpaint Coroutine & Native Quarantine

### Verdict: Partially Confirmed | Severity: High | Confidence: High

### 1. Exact Revision, Files, Symbols, and Lines

- [`SinglePageOnnxPhase.kt:1182-1225`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt#L1182-L1225) (`processSinglePage`): Spawns an unparented async coroutine:
    ```kotlin
    inpaintJob = CoroutineScope(Dispatchers.Default).async { ... }
    ```
- [`SinglePageOnnxPhase.kt:125`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt#L1251)[`9`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt#L1259): `coroutineContext.ensureActive()`. Note that because of `NativeRunQuarantine`'s context stripping (detailed below), caller cancellation is not automatically visible at this suspension point.
- [`TranslationPipeline.kt:614-633, 913-933`](file:///app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L614-L633): `withNativeLane` encloses `translateSinglePageOnnx`. As soon as `SinglePageOnnxPhase.processSinglePage` returns `Pair(pageTranslation, inpaintJob)`, the native block completes. `NativeRunQuarantine` releases its admission permit while `inpaintJob` continues executing native inpainting in the background.
- [`TranslationPipeline.kt:955-965`](file:///app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L955-L965) (`prepareSinglePageOnnx`): Auto prepare awaits `onnxResult.inpaintJob.await()` outside `withNativeLane` with no `try/finally` cancellation block.
- [`SinglePageHttpRenderPhase.kt:661-666, 1067-1075`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L661-L666): Manual mode awaits `ctx.inpaintJob.await()`. Cancellation in `finally` attempts `ctx.inpaintJob?.cancel()`.

### 2. Counterevidence and Pipeline Concurrency Nuance

- **Not universally uncancellable**: In Manual mode, [`SinglePageHttpRenderPhase.kt:1068`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L1068) calls `if (it.isActive) it.cancel()` in its `finally` block.
- **Pipeline Overlap vs. Serial Preparation**:
  - **Auto mode**: The inline producer in `RollingAutoCoordinator.kt:902` awaits `prepareSinglePage` sequentially for one page at a time. Inside `prepareSinglePageOnnx` (`TranslationPipeline.kt:956`), it awaits `inpaintJob.await()` *before* publishing the `PreparedPage` and advancing. Therefore, page N+1 native preparation does *not* overlap page N inpainting. The intended Auto overlap is between the preparation of page N+1 and the remote translation (`translatePreparedPage`) of page N.
  - **Manual mode**: Overlaps the remote HTTP translator round-trip of page N with local inpainting of page N.
  - Enforcing synchronous completion via a local `coroutineScope { async {}.await() }` inside `SinglePageOnnxPhase` would force completion before return, breaking Manual mode's intended provider/native overlap.
- **Engine Teardown Defense**: [`RoiPageRecognitionEngine.kt:156, 564, 989, 1314`](file:///app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt#L156): `nativeGuard` (Mutex) serializes `analyze()` and `inpaint()`. On engine `close()`, `tryLock()` detects if `inpaint` is running and skips immediate session freeing, leaving fallback freeing to the `finally` block of `inpaint` (`:1012`).

### 3. Source-Supported Traces vs. Unverified Assertions

- **Source-Supported Trace: Late-Result Discard**: When caller cancellation occurs while a page is inside `withNativeLane`, `NativeRunQuarantine.run` drains `invocation` via `awaitExitAndLogLate(NonCancellable)`. The `Pair(pageTranslation, inpaintJob)` returned by `processSinglePage` is discarded on the floor. Because `inpaintJob` was spawned on an independent `CoroutineScope`, it is neither parented to the caller nor reachable by the caller. It continues running in the background untracked.
- **Source-Supported Trace: Unmanaged Bitmap Disposal on Await Cancellation**: In `SinglePageHttpRenderPhase.kt:662`, if caller cancellation occurs while suspended inside `ctx.inpaintJob.await()`, the returned bitmap reference is aborted before being stored in `pageTranslation.cleanedBitmap`. In `finally` (`:1072`), `pageTranslation.cleanedBitmap` is null, so an unconsumed output bitmap produced by an abandoned inpaint run has no deterministic disposal path.
- **Unverified Assertions Corrected**:
  - Caller cancellation mid-OCR does not trigger `coroutineContext.ensureActive()` at line 1259 in production flow because `NativeRunQuarantine` explicitly strips the parent Job via `minusKey(Job)`.
  - While unmanaged disposal exists, permanent memory leaks of 10–48 MB are not unconditionally guaranteed (e.g., inpaint may fail or be cancelled before bitmap allocation), and runtime crash absence is unproved without dynamic device execution.

### 4. Minimal Correction & Preserved Invariants

- **Design Gate & Architecture Boundary**:
  - Do not simply attach `inpaintJob` to the current quarantine Job or caller Job: parenting to quarantine would delay native lane release and serialize HTTP handoff; parenting to caller cannot ensure safe native exit.
  - The refactor must define separate contracts for:
    1. *Request abandonment*: Caller unwinds cleanly without waiting for native completion.
    2. *Native registration & admission*: Native inpainting retains occupancy visibility in `NativeRunQuarantine` until native execution actually finishes.
    3. *Result ownership & disposal*: Ownership of the output bitmap is explicitly tracked so that an abandoned result is deterministically recycled.
  - Wrap Auto prepare's `inpaintJob.await()` in `TranslationPipeline.kt:955` with `try/finally` to ensure explicit cancellation and bitmap recycling on cancellation.
- **Preserved Invariants**:
  - Preserve asynchronous handoff from `SinglePageOnnxPhase` to allow Manual mode HTTP/native overlap.
  - Preserve `NativeRunQuarantine`'s non-cancellable native drain guarantee.

### 5. Test Anchors & Acceptance Criteria

- **Existing Anchors**: [`NativeRunQuarantineTest.kt`](file:///app/src/test/java/eu/kanade/translation/pipeline/execution/NativeRunQuarantineTest.kt), [`PreparedPageBoundaryTest.kt`](file:///app/src/test/java/eu/kanade/translation/scheduling/PreparedPageBoundaryTest.kt), [`TranslationPipelineConcurrencyTest.kt`](file:///app/src/test/java/eu/kanade/translation/scheduling/TranslationPipelineConcurrencyTest.kt).
- **Test Gaps**: Missing tests for cancellation during `withNativeLane` verifying deferred handle retention or deterministic disposal; missing test for Auto prepare cancellation during `inpaintJob.await()`.
- **Acceptance Criteria**:
  - Late cancellation during native lane execution does not leave orphaned background jobs running without explicit lifecycle tracking.
  - Abandoned inpaint outputs are deterministically recycled without relying on garbage collection.

# Finding F-07: Mutable Stage Data & Bitmap Ownership

### Verdict: Partially Confirmed | Severity: Medium | Confidence: High

### 1. Exact Revision, Files, Symbols, and Lines

- [`TextTranslator.kt:16-21`](file:///app/src/main/java/eu/kanade/translation/engines/translator/TextTranslator.kt#L16-L21): `suspend fun translate(pages: MutableMap<String, PageTranslation>)` requires a mutable map and mutates `block.translation` in place.
- [`SinglePage`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt#L1181)[`HttpRenderPhase.kt:420-435`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L420-L435): While `inpaintJob` is executing `recomputeFor` on `Dispatchers.Default`, `runTranslate(targetPage)` concurrently executes provider translation, which writes `block.translation` (e.g. `MLKitTranslator.kt:53`, `OpenAiCompatibleTranslator.kt:344` via `applyBatchToChunk`).
- [`PageInpaintingEngine.kt:39`](file:///app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt#L39): `markReady` mutates `pageTranslation.inpaintStatus` and `updatedAt`.
- [`RoiPageRecognitionEngine.kt:636, 966`](file:///app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt#L636): Mutates `allTextDetections` and `inpaintStatus`/`errorMessage` directly on the passed `pageTranslation`.

### 2. Counterevidence and Existing Snapshot Protections

- **Published Store Snapshots Are Immutably Fenced**: [`PageTranslationView.kt:100-140`](file:///app/src/main/java/eu/kanade/translation/model/PageTranslationView.kt#L100-L140): When committing to `ChapterTranslationStore`, pages are converted to `PublishedPageTranslation` with scalar-only `PublishedTranslationBlock` and unmodifiable collections. Store states are protected from pipeline draft mutations.
- **Batch Translation Isolation**: [`ProfileEnvelopeExecutor.kt:1180-1205`](file:///app/src/main/java/eu/kanade/translation/pipeline/batch/envelope/ProfileEnvelopeExecutor.kt#L1180-L1205) explicitly generates `detachedCopy()` for both `PageTranslation` and `TranslationBlock` before constructing provider chunks.
- **Cleaned Bitmap Disposal Paths Exist**:
  - In [`PreparedPageBoundary.kt:70-76`](file:///app/src/main/java/eu/kanade/translation/pipeline/execution/PreparedPageBoundary.kt#L70-L76): `ocrResult.cleanedBitmap?.recycle()` is invoked after store flush.
  - In [`SinglePageHttpRenderPhase.kt:1072`](file:///app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L1072): `cleanedBitmap?.recycle()` is invoked in `finally`.
  - In [`CleanedPublication.kt:270`](file:///app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt#L270): `cleaned.recycle()` is called on publication failure.

### 3. Source-Supported Traces vs. Unverified Assertions

- **Source-Supported Direct Race**: In Manual mode, `inpaintJob` calls `RenderColorEstimator.recomputeFor`, which reads `block.translation.isBlank()` at line 298. Concurrently on the HTTP lane, provider execution writes `block.translation = ...` on the exact same `TranslationBlock` instances. This is an active shared-memory read/write data race across threads without synchronization.
- **Unverified Assertions Corrected**:
  - Wholesale immutability across the pipeline cannot be asserted as definitively causing massive GC without comparative profiling and allocation benchmarks. However, allocation trade-offs must be evaluated before replacing working copies with immutable structures in vision/geometry loops.
  - Naive deep copying of blocks alone is insufficient: if blocks are cloned without an explicit merge mechanism, the colors computed by `RenderColorEstimator` are discarded.

### 4. Minimal Correction & Preserved Invariants

- **Incremental Stage Outcome Contract**:
  - Separate inpainting outcomes from input drafts: `inpaintJob` should return an `InpaintStageResult` carrying the cleaned bitmap and computed color styles (`Map<Int, BlockColorStyle>`).
  - Merge the computed color styles into the page blocks only after both provider translation and inpainting have completed, eliminating concurrent mutation of `TranslationBlock`.
  - Refactor `TextTranslator.translate` from taking a `MutableMap<String, PageTranslation>` to accepting read-only block representations and returning typed translation pairs.
- **Preserved Invariants**:
  - Retain `PublishedPageTranslation` at store commit boundaries.
  - Retain `detachedCopy()` in batch execution.

### 5. Test Anchors & Acceptance Criteria

- **Existing Anchors**: [`PublishedPageStoreStateTest.kt`](file:///app/src/test/java/eu/kanade/translation/persistence/chapter/PublishedPageStoreStateTest.kt), [`ReadOnlyPageViewTest.kt`](file:///app/src/test/java/eu/kanade/translation/persistence/chapter/ReadOnlyPageViewTest.kt).
- **Test Gaps**: No concurrency test specifically exercises simultaneous execution of `RenderColorEstimator.recomputeFor` and contextual HTTP translation on shared block lists.
- **Acceptance Criteria**:
  - Zero shared mutable block access between concurrent inpaint and HTTP translation tasks.
  - Color estimation results are deterministically merged without reading mutating provider fields.

# Finding F-09: Synchronous Bridges (`runBlocking`)

### Verdict: Partially Confirmed | Severity: Medium | Confidence: High

### 1. Exact Revision, Files, Symbols, and Lines

All production occurrences of `runBlocking` in the codebase were traced:

1. [`TranslationManager.kt:1070, 1074`](file:///app/src/main/java/eu/kanade/translation/workflow/TranslationManager.kt#L1070-L1074) (`cancelRunningChapterForReplace`):
     ```kotlin
      runBlocking { scheduler.cancelPageTranslations(chapterId) }
      runBlocking { store.clearTransientQueuePages(...) }
     ```
  - **Real Caller**: [`MangaScreenModel.kt:1311`](file:///app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt#L1311) inside `confirmReplaceRunningChapter`.
  - **Dispatcher Context**: `CoroutinesExtensions.kt:52` defines `launchNonCancellable = launchIO { withContext(NonCancellable, block) }`. `confirmReplaceRunningChapter` calls `screenModelScope.launchNonCancellable`, meaning this runs on **`Dispatchers.IO`**, NOT `Dispatchers.Main`.
  - **Timeout**: `Scheduler.JOIN_TIMEOUT_MS = 2_000L` at [`TranslationScheduler.kt:74`](file:///app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt#L74).
  - **Hazard**: Blocks an IO carrier thread for up to 2 seconds while awaiting job cancellation. It is not an ANR / Main freeze, but blocking dispatcher carrier threads violates coroutine guidelines and risks thread starvation.
2. [`BatchChapterTranslator.kt:1224`](file:///app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L1224) (`LazySourceFingerprints.get`):
     ```kotlin
      return cache.computeIfAbsent(key) {
          kotlinx.coroutines.runBlocking(Dispatchers.IO) {
              computeFn(streamFn) ?: UNKNOWN_SOURCE_FINGERPRINT
          }
      }
     ```
  - **Real Caller**: Batch preflight and envelope planning looking up source fingerprints.
  - **Hazard**: Synchronously blocks the calling thread inside a `ConcurrentHashMap` compute block on `Dispatchers.IO`.
3. [`ChapterTranslationStore.kt:4116`](file:///app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L4116) (`ensureArtifactStoreLocked`):
     ```kotlin
      val manifest = runBlocking(Dispatchers.IO) {
          withArtifactOpenLock(parent, fileName) { store.load().manifest }
      }
     ```
  - **Real Callers**: Live mutation entry points [`verifyMutationAdmissionLocked:1793`](file:///app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L1793), [`commitCleanedImagePublicationDirect:3530`](file:///app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L3530), and [`checkpointOcr:3797`](file:///app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L3797).
  - **Hazard**: When a lazy store transitions to durable mode on first write, it executes `runBlocking(Dispatchers.IO)` while internal synchronization is active.
4. [`ChapterTranslationStore.kt:4795`](file:///app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L4795) (`openArtifact`):
     ```kotlin
      internal fun openArtifact(...): ChapterTranslationStore = runBlocking(Dispatchers.IO) { ... }
     ```
  - **Context**: Retained explicitly as a synchronous compatibility seam for JVM unit tests; production open paths use `openArtifactSuspend`.
5. [`TranslationManager.kt:1220`](file:///app/src/main/java/eu/kanade/translation/workflow/TranslationManager.kt#L1220) (`getChapterTranslation`):
  - **Real Caller**: [`DownloadPageLoader.kt:76`](file:///app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt#L76). Only reached as a fallback when `dbChapter.id == null`. Runs on a background IO loader coroutine.
6. [`TranslationScheduler.kt:584, 637`](file:///app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt#L584):
  - Line 584 (`markChapterCancelledSync`) is invoked via `cancelAllPageTranslationsOffMain`, which explicitly shifts to `Dispatchers.Default` via `ReaderTeardownCoordinator.kt:151`.
  - Line 637 (`cancelPageTranslation`) is called from the reader page cancel button.

### 2. Counterevidence and Nuance

- **Synchronous Completion Ordering Is Load-Bearing**: The synchronous cancellation bridges exist because cancellation must complete before subsequent chapter operations begin. Converting cancellation calls to fire-and-forget (`launch`) would reintroduce race conditions where a cancelled chapter's late in-flight writes clobber a newly selected chapter.
- **`fastCancelInFlightStagesInMemory`**: [`ChapterTranslationStore.kt:2285`](file:///app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L2285) updates in-memory display state without acquiring the store mutex or performing disk I/O. However, claiming a strict "<16 ms" bound is unmeasured in real device conditions and should be characterized as non-blocking in-memory state manipulation.

### 3. Minimal Correction & Preserved Invariants

- **Minimal Correction**:
  1. Change `TranslationManager.cancelRunningChapterForReplace(chapterId: Long)` to a `suspend fun`. Its caller in `MangaScreenModel.kt:1311` is already in a coroutine and can suspend directly, avoiding thread blocking on `Dispatchers.IO`.
  2. In `BatchChapterTranslator.kt:1224`, resolve fingerprints asynchronously ahead of time or make the fingerprint map compute function suspending.
  3. In `ChapterTranslationStore.kt:4116`, make `ensureArtifactStoreLocked` a suspending function `ensureArtifactStoreSuspend` and propagate suspension to callers.
  4. Preserve synchronous completion ordering: callers must await cancellation completion before starting replacement work.

### 4. Test Anchors & Acceptance Criteria

- **Existing Anchors**: [`CancelSyncStoreWriteTest.kt`](file:///app/src/test/java/eu/kanade/translation/scheduling/CancelSyncStoreWriteTest.kt), [`CancelBatchLeaseSkipTest.kt`](file:///app/src/test/java/eu/kanade/translation/scheduling/CancelBatchLeaseSkipTest.kt), [`ChapterTranslationStatusOffMainThreadTest.kt`](file:///app/src/test/java/eu/kanade/translation/workflow/ChapterTranslationStatusOffMainThreadTest.kt).
- **Test Gaps**: Missing tests verifying that `cancelRunningChapterForReplace` and `LazySourceFingerprints` operate without blocking dispatcher carrier threads.
- **Acceptance Criteria**:
  - `cancelRunningChapterForReplace` suspends without blocking carrier threads.
  - Chapter replacement strictly enforces cancellation completion before starting new translations.

# Revised Action Plan for Maintainability Refactor

1. **Priority 1 (Cancellation Contract & Resource Disposal)**:
  - Design explicit result ownership and registration for native inpainting that survives request abandonment without leaking allocations or delaying native drainage.
  - Wrap Auto prepare's `inpaintJob.await()` in `TranslationPipeline.kt` with `try/finally` cancellation and bitmap disposal.
2. **Priority 2 (Carrier Thread Unblocking)**:
  - Convert `cancelRunningChapterForReplace` to a `suspend fun`.
  - Refactor `LazySourceFingerprints` and `ensureArtifactStoreLocked` to suspending paths, eliminating nested `runBlocking` calls on `Dispatchers.IO`.
3. **Priority 3 (Stage Contract Decoupling)**:
  - Refactor inpainting color style calculation to return typed stage outcomes merged after await, eliminating the concurrent `block.translation` read/write race with HTTP translation.
  - Refactor `TextTranslator.translate` from in-place map mutation to typed outcome mapping, measuring allocation trade-offs before introducing immutable collections.
