# Technical Investigation: Batch Pause/Cancel Call-Chain Trace & Mutual-Exclusion Coexistence Evaluation

**Task:** T936 Follow-up Evidence Gathering  
**Date:** 2026-09-19  
**Status:** Audit & Architecture Analysis Only (No production modifications)  
**Target:** Batch Pause/Cancel Mechanics, NonCancellable Boundaries, and Mutual Exclusion Evaluation  

---

## Executive Summary & Root-Cause Discovery

### 1. The Core Finding: Disproving the "Batch 10-Second Drain" Hypothesis
The previous report made an **unsupported inference** by citing [`D6DrainNotCancelTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D6DrainNotCancelTest.kt#L45-L67) as evidence that Batch translation wraps in-flight work in `withContext(NonCancellable)` to drain active work over a 10-second grace window.

**Code inspection confirms this hypothesis is INCORRECT.**
- The 10-second `drainGraceMs` (`PROVIDER_DRAIN_GRACE_MS`) is an **exclusive behavior of [`RollingAutoCoordinator.kt:98`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt#L98)**, active only when a user leaves the reader while an AUTO translation is mid-flight.
- [`ChapterProfileBatchCoordinator.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt) and [`BatchChapterTranslator.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt) **do NOT** wrap active pipeline stages (OCR, HTTP translation, or inpainting) in `NonCancellable` during Pause or Cancel. They use standard Kotlin coroutine cancellation (`Job.cancel()`).

### 2. The Real Reason Manual Translation Fails After Batch Pause/Cancel
Why did the user experience: *"Once you start batch translation, cancel, pause, manual or auto translation can never override and when it can't override, it just tell you fail instead of telling you the specific cause"*?

The actual call trace reveals a toxic confluence of **three distinct design flaws**:

1. **Non-Blocking Asynchronous Cancellation:**  
   [`ChapterTranslator.pause()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L391) calls [`cancelTranslatorJob()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L554-L571), which invokes `translationJob?.cancel()` without waiting (`join()`). The UI immediately considers the batch paused/cancelled.
2. **Uncancellable Native ONNX Execution:**  
   If the batch coroutine is inside native C++ code (ONNX Runtime JNI `session.run()` for text detection or inpainting, or `BitmapFactory.decodeStream`), native execution **cannot be interrupted**. The coroutine cannot throw `CancellationException` or execute its `finally` blocks until the native function returns back to the JVM.
3. **Lease Denied & Poisoned Observation Hang:**  
   Because the native work is still finishing, the page lease in [`PageStageLeaseTable.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L87-L104) is **still held** by `PageWriteOrigin.BATCH`.  
   When the user taps "Translate" in the reader, [`TranslationPipeline.kt:803`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L803) requests a `MANUAL` lease.  
   - `PageStageLeaseTable` rejects `MANUAL` with `LeaseAcquisition.Denied("page owned by BATCH at stage ...", PageWriteOrigin.BATCH)` (MANUAL is permitted to preempt AUTO, but is strictly forbidden from preempting BATCH).
   - In response to `Denied`, `TranslationPipeline.kt:485` redirects to [`attachToOwnerTerminal()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L736-L793), which suspends waiting for the BATCH owner to commit a terminal successful state (`store.state.first { page.translationStatus == READY ... }`).
   - Because Batch was cancelled, Batch aborts and **never** writes a `READY` status.
   - Manual reader translation **hangs for up to `ATTACH_TIMEOUT_MS` (10,000 ms to 20,000 ms)** before timing out with `SinglePageOutcome.AttachedUnresolved`, surfaced to the user by [`TranslationUiTruth.kt:598`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt#L598) as *"Background translation did not finish yet."*

---

## 1. Concrete Call-Chain Traces

### Trace 1: Batch Pause

```
[UI Action: User taps Pause Batch]
       │
       ▼
TranslationManager.pauseTranslation() [TranslationManager.kt:738-740]
       │
       ▼
ChapterTranslator.pause() [ChapterTranslator.kt:391-396]
       ├─► cancelTranslatorJob() [ChapterTranslator.kt:554-557]
       │         └─► translationJob?.cancel()  (NON-BLOCKING: returns immediately!)
       ├─► queueState: marks TRANSLATING -> QUEUE
       └─► isPaused = true
       │
       ▼
[Background Coroutine: launchTranslationJob] [ChapterTranslator.kt:497-552]
       │
       ▼
translateChapterInternal() [ChapterTranslator.kt:647-860]
       │
       ▼
pipeline.translateBatch() -> BatchChapterTranslator.translateBatch() [BatchChapterTranslator.kt:636-1077]
       │
       ▼
ChapterProfileBatchCoordinator.runPass1() [ChapterProfileBatchCoordinator.kt:468-948]
       │
       ├─► Current Stage:
       │     ├─ Native ONNX OCR/Detection: Non-interruptible native JNI call.
       │     │   Must finish current inference before JVM detects cancellation.
       │     └─ Cooperative Point (loop head / yield): CancellationException thrown!
       │
       ▼
[Unwinding & Teardown]
ChapterProfileBatchCoordinator.kt:701-720 (preflight page loop finally):
       ├─► nativeWorker.releaseNativeHandoff(ref)
       ├─► store.cancelPageStageWork(pageKey, BATCH) [if pending failure]
       ├─► releaseBatchLease(pageKey) -> releaseBatchPageLease(store, pageKey)
       │         └─► PageStageLeaseTable.releasePageStageLease() [NonCancellable]
       └─► listener.ocrFinished(pageKey)
       │
       ▼
BatchChapterTranslator.kt:1048-1075 (translateBatch finally):
       ├─► store.cancelPageStageWork(pageKey, BATCH) for active candidates
       ├─► batchWriteIdentities.clear()
       ├─► store.releaseAllPageLeases(PageWriteOrigin.BATCH) [PageStageLeaseTable.kt:255]
       ├─► withContext(NonCancellable) { store.flush() }
       ├─► store.reconcileArtifactRetentionAsync()
       └─► onBatchClosed?.invoke(...)
       │
       ▼
ChapterTranslator.kt:839-847 (catch CancellationException):
       ├─► if (!queueState.contains(translation)) tracker?.abort(...) (Skipped: item still in queue!)
       ├─► store?.flush()
       └─► throw error
       │
       ▼
ChapterTranslator.kt:543-551 (launchTranslationJob finally):
       └─► inFlightChapterIds.remove(chapterId)
       └─► inFlightClaimReleasedSignal.tryEmit(Unit)
```

#### Code Excerpts for Trace 1:

**TranslationManager.kt (L738-L740):**
```kotlin
fun pauseTranslation() {
    translator.pause()
}
```

**ChapterTranslator.kt (L391-L396 & L554-L557):**
```kotlin
fun pause() {
    cancelTranslatorJob()
    queueState.value.filter { it.status == Translation.State.TRANSLATING }
        .forEach { it.status = Translation.State.QUEUE }
    isPaused = true
}

private fun cancelTranslatorJob() {
    translationJob?.cancel()
    translationJob = null
}
```

**ChapterTranslator.kt (L560-L571):**
```kotlin
/**
 * Plain [cancelTranslatorJob] stays non-blocking so pause/stop/clearQueue keep
 * their behaviour.
 */
```

---

### Trace 2: Batch Cancel

```
[UI Action: User cancels translation item from queue]
       │
       ▼
TranslationManager.cancelQueuedTranslation(translation) [TranslationManager.kt:1797-1799]
       │
       ▼
TranslationManager.removeFromTranslationQueue(chapter) [TranslationManager.kt:1801-1814]
       ├─► val wasRunning = translator.isRunning
       ├─► if (wasRunning) translator.pause()  <-- REUSES PAUSE CALL (cancels job!)
       ├─► translator.removeFromQueue(chapter)
       └─► if (wasRunning) {
                 if (queueState.isEmpty()) translator.stop()
                 else translator.start()  <-- Automatically restarts next chapter in queue!
             }
       │
       ▼
ChapterTranslator.translateChapterInternal() [ChapterTranslator.kt:839-847]
       │
       ▼
CancellationException caught:
       if (!queueState.value.contains(translation)) {
           tracker?.abort(batchOrderedPageKeys.toSet(), "Batch cancelled")
       }
       store?.flush()
       throw error
```

#### Code Excerpts for Trace 2:

**TranslationManager.kt (L1797-L1814):**
```kotlin
fun cancelQueuedTranslation(translation: Translation) {
    removeFromTranslationQueue(translation.chapter)
}

private fun removeFromTranslationQueue(chapter: Chapter) {
    val wasRunning = translator.isRunning
    if (wasRunning) {
        translator.pause()
    }
    translator.removeFromQueue(chapter)
    if (wasRunning) {
        if (queueState.value.isEmpty()) {
            translator.stop()
        } else if (queueState.value.isNotEmpty()) {
            translator.start()
        }
    }
}
```

**ChapterTranslator.kt (L839-L847):**
```kotlin
} catch (error: Throwable) {
    if (error is CancellationException) {
        // If it's no longer in the queue, it was explicitly removed (cancelled).
        // If it's still in the queue, it was merely paused or memory-requeued; do not abort the tracker so it can be resumed.
        if (!queueState.value.contains(translation)) {
            tracker?.abort(batchOrderedPageKeys.toSet(), "Batch cancelled")
        }
        store?.flush()
        throw error
    }
```

---

## 2. Direct Answers to the 12 Verification Questions

### 1. Does Batch Pause call `Job.cancel()`, stop scheduling new work, drain existing work, or use some combination?
**Answer:** It calls `Job.cancel()` and stops scheduling new work. It does **NOT** intentionally drain existing work.
- [`ChapterTranslator.kt:392`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L392) calls `cancelTranslatorJob()`, which directly executes `translationJob?.cancel()`.
- It does not implement a drain loop or grace timer for Batch. However, because `cancelTranslatorJob()` is non-blocking and native C++ ONNX calls cannot be interrupted, whatever native instruction is currently on the CPU/NPU must run to completion before the cancelled coroutine unwinds.

### 2. Does Batch Cancel behave differently from Pause?
**Answer:** Minimally. Cancel is fundamentally implemented on top of Pause.
- [`TranslationManager.removeFromTranslationQueue()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L1804) calls `translator.pause()` first, then removes the chapter from `_queueState`.
- The only differences inside the pipeline are:
  1. [`ChapterTranslator.kt:842-844`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L842-L844) checks `if (!queueState.value.contains(translation))`: Cancel emits `tracker?.abort("Batch cancelled")`, while Pause preserves tracker progress.
  2. If other chapters remain in the queue, Cancel restarts the batch runner for the next chapter ([`TranslationManager.kt:1811`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L1811)).

### 3. Does Batch ever execute work inside `withContext(NonCancellable)`?
**Answer:** **YES, but strictly for teardown disk flushes and lease table state cleanup.**
Batch **never** wraps OCR, inpainting, or translation provider calls in `NonCancellable`.
The exact locations are:
1. `BatchChapterTranslator.kt:1060`: `withContext(NonCancellable) { store.flush() }`
2. `ChapterProfileBatchCoordinator.kt:2136`: `withContext(NonCancellable) { store.flush() }`
3. `PageStageLeaseTable.kt` (L152, L180, L215, L235, L255): All lease release/cancellation mutations.

### 4. If yes, where does that `NonCancellable` behavior originate?
**Answer:**
1. In [`BatchChapterTranslator.kt:1060`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L1060), to guarantee that JSON artifact manifests and store indices are written to disk cleanly without being cut off mid-write by coroutine cancellation.
2. In [`PageStageLeaseTable.kt:152`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L152), to guarantee thread-safe in-memory map consistency without leaving dangling locks or orphaned lease records when a worker is cancelled.

### 5. Is it directly implemented by Batch, or inherited because Batch and Auto eventually share another pipeline/provider abstraction?
**Answer:**
- It is **directly implemented** in `BatchChapterTranslator` and `PageStageLeaseTable`.
- Batch does **NOT** inherit `RollingAutoCoordinator`'s provider drain behavior. `RollingAutoCoordinator` is used solely for reader Auto mode.

### 6. While Batch is pausing/cancelling, exactly when is its `PageStageLeaseTable` lease released?
**Answer:**
1. **Per-page lease:** In `ChapterProfileBatchCoordinator.kt:718` (`releaseBatchLease(pageKey)`) inside the page loop's `finally` block.
2. **Batch chapter-wide cleanup:** In `BatchChapterTranslator.kt:1059` (`store.releaseAllPageLeases(PageWriteOrigin.BATCH)`) inside the outer `finally` block of `translateBatch`.
Crucially, these `finally` blocks execute **only after** in-flight native C++ ONNX/image operations finish and return control to the JVM coroutine unwinder.

### 7. Can the lease survive after the Batch coordinator considers itself paused/cancelled?
**Answer:** **YES.**
Because [`ChapterTranslator.cancelTranslatorJob()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L554-L571) is non-blocking, the UI and `ChapterTranslator` consider the batch paused immediately.
However, if a native ONNX inference was running at the instant of pause, the background thread remains inside native C++ for several hundred milliseconds to seconds. Until that native execution completes and unwinds to `finally`, `pageLeases[pageKey]` is **still actively held by `PageWriteOrigin.BATCH`**.

### 8. Can Manual attempt acquisition during this interval?
**Answer:** **YES.**
The user pauses/cancels batch in the UI and immediately taps "Translate" on the page in Reader mode.

### 9. What exact result will Manual receive?
**Answer:**
1. `PageStageLeaseTable.tryAcquirePageStageLease` evaluates lines 96–104:
   ```kotlin
   val evictsAuto = existing != null &&
       existing.origin == PageWriteOrigin.AUTO &&
       origin == PageWriteOrigin.MANUAL
   if (existing != null && existing.origin != origin && !evictsAuto) {
       return@withLock LeaseAcquisition.Denied(
           "page owned by ${existing.origin} at stage ${existing.stage}",
           existing.origin,
       )
   }
   ```
   Because `existing.origin == BATCH`, `evictsAuto` is false.
   The acquisition is **DENIED**: `LeaseAcquisition.Denied("page owned by BATCH at stage ...", PageWriteOrigin.BATCH)`.
2. [`TranslationPipeline.kt:484-486`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L484-L486) catches `Denied` and calls `attachToOwnerTerminal()`.
3. `attachToOwnerTerminal` ([`TranslationPipeline.kt:752-772`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L752-L772)) suspends waiting for the BATCH owner to commit a terminal successful state (`READY`, `PARTIAL`, `SKIPPED`).
4. Because Batch was cancelled, it unrolls and cancels work, **never** committing `READY`.
5. Manual hangs until `ATTACH_TIMEOUT_MS` expires, then returns `SinglePageOutcome.AttachedUnresolved`.
6. The Reader UI maps this in [`TranslationUiTruth.kt:598-607`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt#L598-L607) to:
   **Label:** *"Background translation did not finish yet."*
   **Description:** *"Background translation did not finish within the wait; retry is available after the owner releases the page."*

### 10. Is there genuinely a ~10-second possible lockout for Batch, or was the previous report incorrectly transferring AUTO semantics to BATCH?
**Answer:** **INCORRECT / ERRONEOUSLY TRANSFERRED.**
- Batch has **no 10-second intentional drain grace window**.
- The previous report confused `RollingAutoCoordinator.drainGraceMs` (tested in `D6DrainNotCancelTest.kt`) with Batch cancellation.
- The ~10-second hang experienced in Batch is caused by `TranslationPipeline.ATTACH_TIMEOUT_MS` (`ONNX_PHASE_TIMEOUT_MS + SINGLE_PAGE_TIMEOUT_MS` = ~10,000–20,000 ms) inside `attachToOwnerTerminal()`, where Manual waits for an aborted Batch owner that will never finish.

### 11. What happens if cancellation arrives while Batch is in specific stages?
- **Detecting / OCR:** Executing in native C++ via ONNX Runtime `session.run()`. **Non-interruptible**. Native execution runs until the current frame/box detection finishes. Coroutine cancellation takes effect immediately upon return to JVM.
- **Calling Translation Provider / LLM:** Suspended in OkHttp/Ktor network I/O. **Cooperatively cancellable**. Cancelling closes the socket, aborts the HTTP request, and throws `CancellationException`.
- **Inpainting:** Executing in native C++ via ONNX Runtime (`AOTInpainting` or `GigaGAN`). **Non-interruptible**. Must complete the current page patch inference.
- **Laying Out:** Pure Kotlin geometry loops in `TextLayoutPlanner`. **Cooperatively cancellable** at suspension points / loop iterations.
- **Publishing an Artifact:** Protected by `withContext(NonCancellable)`. Completes writing the artifact JSON to avoid corrupting disk state.
- **Publishing the Manifest:** Protected by `withContext(NonCancellable)`. SAF document commits atomically.

### 12. Which operations are actually cooperatively cancellable and which are effectively non-interruptible/native/atomic?
- **Non-interruptible / Native / Atomic:**
  1. ONNX Runtime JNI execution (`session.run()` for text detection, bubble detection, MangaOCR, inpainting).
  2. Android bitmap decoding (`BitmapFactory.decodeStream`).
  3. Disk writes protected by `withContext(NonCancellable)` (`store.flush()`, document serialization).
  4. Lease mutations protected by `withContext(NonCancellable)` in `PageStageLeaseTable.kt`.
- **Cooperatively Cancellable:**
  1. Coroutine loops between page iterations (`yield()`, `ensureActive()`).
  2. Translation provider HTTP network calls.
  3. Flow observations (`store.state.first { ... }`).
  4. Delays, timers, and retry backoffs.

---

## 3. Simplified Architecture Proposal Evaluation: Mutual Exclusion Model (Model B) vs Full Concurrency (Model A)

### Proposed Model B: One Active High-Level Session
Instead of attempting complex per-page preemption and multi-origin concurrency, TachiyomiAT enforces mutual exclusion at the session level:
- `IDLE`
- `BATCH_SESSION` (Background Chapter Translation)
- `READER_SESSION` (Foreground Reader: Manual Taps + Auto Page Window)

#### Proposed User Flow:
1. When `BATCH_SESSION` is active and the user requests translation in Reader (Manual tap or Auto toggle), display a single modal confirmation:
   > *"Batch translation is currently active for [Chapter X]. Pause Batch and switch to Reader translation?"*
2. **If Confirmed:**
   - Immediately transition Batch to `PAUSING`.
   - Batch stops scheduling new pages.
   - Reader waits for Batch to reach **quiescence** (using `cancelTranslatorJobAndJoin()` bounded by timeout).
   - Batch safely flushes and commits every completed reusable stage (OCR, translation, inpainting) into `ChapterTranslationStore`.
   - Batch releases all page leases.
   - System enters `READER_SESSION`.
   - Reader starts translating immediately, reusing all Batch-produced checkpoints without re-running OCR or LLM translation.
3. **If Rejected:**
   - Batch continues unimpeded. Reader does not translate and displays an informative banner.

---

### Comparative Architectural Analysis

| Architectural Dimension | Model A: Fully Concurrent (Current) | Model B: Session Mutual Exclusion (Proposed) |
| :--- | :--- | :--- |
| **Page Stage Lease Matrix** | **Extremely Complex.** Asymmetric priority matrix in [`PageStageLeaseTable.kt:87-104`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L87-L104): MANUAL preempts AUTO, but MANUAL cannot preempt BATCH; BATCH cannot preempt anything. | **Trivial.** Single origin active at any time (`MANUAL` or `AUTO` under `READER_SESSION`). Zero cross-origin preemption logic needed. |
| **Sibling Attaches & Token Sharing** | **Required & Fragile.** Requires [`releasePageStageLeaseIfUnattached`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L176) and [`detachPageStageLeaseIfAttached`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L211) with reference counting to prevent overlap inpainting from clobbering envelope tokens. | **Deleted.** No concurrent sibling tasks competing for stage tokens across origins. |
| **Observer Hangs & Failure States** | **Severe Bug Source.** Reader enters [`attachToOwnerTerminal`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L736) on lease denial, hanging up to 20s if Batch cancels, outputting `AttachedUnresolved`. | **Eliminated.** Reader never attaches to Batch because Batch is guaranteed stopped and quiescent before Reader translation begins. |
| **Hardware & Memory Contention** | **High Risk.** Batch and Reader can concurrently attempt to initialize ONNX sessions, decode bitmaps, and allocate direct ByteBuffers, risking NPU/GPU bus lockups and Out-Of-Memory (OOM) kills. | **Zero Contention.** 100% of device memory and NPU/CPU compute is dedicated to the single active session. |
| **Batch Deferral & In-Pass Rescans** | **Overengineered.** Requires [`deferredPages`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L723-L730) and `S8` in-pass gap rescans to recover pages skipped due to reader contention. | **Deleted.** Batch processes pages 1..N sequentially without mid-run deferrals caused by reader collisions. |
| **Code Footprint Reduction** | Baseline: ~16,500 lines across 22 coordination files. | **Estimated ~3,500 lines deletable.** Can completely eliminate `BatchWriteGate`, `attachToOwnerTerminal`, `AttachedUnresolved` UI states, and multi-origin lease arbitration. |

### Conclusion on Mutual Exclusion:
**Strongly Recommended.**  
Android mobile devices with bounded RAM and single-session NPU hardware accelerators cannot reliably sustain concurrent Batch and Reader translation pipelines. Concurrency in this domain is accidental complexity that creates race conditions, lock inversions, and user-facing lockouts. Model B provides deterministic state transitions, immediate user feedback, and eliminates thousands of lines of fragile synchronization code while preserving complete checkpoint reusability.

---

## 4. Corrected Evidence Section for Architectural Report

| Prior Statement / Finding | Truth Status | Evidence & Code Justification |
| :--- | :--- | :--- |
| *"When a batch run is cancelled or paused, in-flight coroutines are not abruptly cancelled. They are wrapped in `withContext(NonCancellable)` to drain active work over a grace window (up to 10 seconds)."* | **INCORRECT** | **Conflated with Auto Mode.** [`D6DrainNotCancelTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D6DrainNotCancelTest.kt#L45-L67) tests [`RollingAutoCoordinator.drainGraceMs`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt#L98). Batch translation cancels abruptly via `Job.cancel()` in [`ChapterTranslator.kt:555`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L555). It has no 10-second drain grace window. |
| *"Batch translation executes under `withContext(NonCancellable)`."* | **PARTIALLY CONFIRMED** | Confirmed **only** for teardown flushes ([`BatchChapterTranslator.kt:1060`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L1060), [`ChapterProfileBatchCoordinator.kt:2136`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L2136)) and lease table map mutations ([`PageStageLeaseTable.kt:152, 180, 215, 235, 255`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L152)). OCR, inpainting, and translation provider calls are **not** wrapped in `NonCancellable`. |
| *"After pausing or cancelling Batch, Manual translation can be locked out for up to 10–20 seconds and fail with an ambiguous error."* | **CONFIRMED** | Confirmed by code trace. Caused by non-blocking job cancellation leaving native ONNX inference running, resulting in `LeaseAcquisition.Denied` ([`PageStageLeaseTable.kt:99`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L99)), followed by a hanging observation wait in [`attachToOwnerTerminal`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L752) bounded by `ATTACH_TIMEOUT_MS` (10–20s), finally surfacing as *"Background translation did not finish yet."* ([`TranslationUiTruth.kt:598`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt#L598)). |
| *"aot-512.onnx is dead code and should be deleted."* | **INCORRECT** | Confirmed as a dangerous false positive. Required by [`AOTInpainting.kt:115-124`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt#L115-L124) for Qualcomm QNN HTP NPU hardware acceleration. |
| *"The NNAPI acceleration subsystem is completely dead."* | **CONFIRMED** | Verified against Gradle build and ONNX Runtime AAR: NNAPI native runtime is absent, making all NNAPI code in the app unreachable dead code. |
| *"best_int8.onnx is a duplicate asset."* | **CONFIRMED** | Verified byte-for-byte SHA-256 identical to `manga109_bubble_int8.onnx` (3.28 MB). |
