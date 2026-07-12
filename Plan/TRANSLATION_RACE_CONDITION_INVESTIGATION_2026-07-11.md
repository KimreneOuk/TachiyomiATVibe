# Translation Module: Race Condition & Edge Case Investigation

**Date:** 2026-07-11
**Branch:** Quality-Improvement
**Phase:** Investigation only. No code changes.
**Method:** 5-round critique loop (main agent defends + verifies, subagents find gaps + refine).

---

## Executive Summary

Investigated race conditions, edge cases, and user-interaction hazards in the translation module. Found **4 user-reported bugs** (all verified against live code) plus **1 newly discovered native-crash bug (HIGH severity)**. Several hypothesized edge cases were **refuted** by tracing — the codebase already contains explicit defenses. A defense plan is provided for the implementation phase, scoped by priority.

The most severe finding is a **SIGSEGV-native crash path** (`forceReleaseNativeBuffers` releasing in-use ONNX buffers from the memory-pressure thread without synchronization) that is independent of the user-reported symptoms but reachable on low-end devices.

---

## Verified Bugs

### Bug 1 — Stop translate shows "translating" up to 90s after stop
**Severity:** HIGH (UX)

**Flow:**
1. User clicks stop on a single page.
2. `ReaderViewModel.cancelSinglePageTranslation` (ReaderViewModel.kt:1972) → `TranslationScheduler.cancelPageTranslation` (TranslationScheduler.kt:491-499) → `job?.cancel()`.
3. `Job.cancel()` only lands at the next **suspension point**. The worker may be mid-`OrtSession.run` (detector/inpainter/OCR) — native, non-suspend, uncancellable for up to `ONNX_PHASE_TIMEOUT_MS` = 90s (TranslationPipeline.kt:145).
4. Store page stays `ocrStatus = StageStatus.RUNNING` until the worker's `finally` (TranslationScheduler.kt:411-429) runs `markPageCancelled` (line 440-464) on a `NonCancellable` child — reachable only after native code returns.
5. `markPageCancelled` guards `hasRenderedResult || isStageFailed` (line 446) so it won't clobber a finished page — but it can't run until the worker unwinds.

**Net:** user clicks stop, UI shows "translating" for up to 90s.

---

### Bug 2 — Stop + reconfigure + re-click translate does nothing
**Severity:** HIGH

**Root cause verified:** `closeEngines()` (TranslationPipeline.kt:449-465) early-returns when the permit is held:

```
fun closeEngines() {
    if (!translatorPermit.tryAcquire()) {   // line 450 — fails when worker holds permit
        enginesClosed = true
        return                               // line 452 — inFlightPageKeys.clear() at line 459 UNREACHABLE
    }
    try {
        enginesClosed = true
        inFlightPageKeys.clear()             // line 459 — only runs in this branch
        ...
    } finally { translatorPermit.release() }
}
```

When a worker is mid-ONNX holding the permit, `tryAcquire()` fails → early return → `inFlightPageKeys` is **not cleared**. The next manual translate for the same page:

1. `ensureEnginesBuiltFor` sees `enginesClosed = true` → rebuilds engines (good, config change picked up).
2. `withLeakProofPermit` → `permit.acquire()` blocks up to 90s until the watchdog force-releases the old worker's permit.
3. If the watchdog's `onForceRelease` (TranslationPipeline.kt:649, `inFlightPageKeys.remove(pageKey)`) failed to fire or threw (caught at line 289-294), the key is retained → `inFlightPageKeys.add(pageKey)` at line 651 returns `false` → **silent no-op**.

Documented exact symptom: TranslationPipeline.kt:247-254 ("pipeline stops working entirely").

**Secondary contributor:** single-page `cancelPageTranslation` is fire-and-forget (no join). The chapter-level variant `cancelPageTranslations` (line 516-546) has a bounded 2s join for a different cross-chapter reason; per-page cancel does not.

---

### Bug 3 — Wrong page number displayed
**Severity:** MEDIUM (display-only — the correct page translates; only the number shown is wrong)

**Root cause:** Two conflicting regexes derive a page number from the filename string (`pageKey`). The translation engine keys everything on the `pageKey` string (correct); only the display reverse-engineers a number.

| # | Location | Regex | Meaning |
|---|----------|-------|---------|
| 1 | `TranslationProgressSnapshot.resolvePageIndex` (TranslationProgressSnapshot.kt:146) | `(\d+)(?!.*\d)` | last digit run anywhere |
| 2 | `TranslationBatchProgressTracker.resolvePageIndex` (TranslationBatchProgressTracker.kt:302) | `(\d+)(?!.*\d)` | identical to #1 |
| 3 | `ReaderViewModel.resolvePageIndex` (ReaderViewModel.kt:289) | `(\d+)$` | digit run at END only |

Failure cases:

| pageKey | #1 / #2 | #3 (reader queue) |
|---------|---------|-------------------|
| `009.jpg` | 9 | **fallback** (`.jpg` suffix) |
| `009__002.jpg` (split-tall slice) | **2** | fallback |
| `page-09.png` | 9 | fallback |

`activePage` (TranslationProgressSnapshot.kt:125) = first RUNNING row sorted by the regex-derived number → can display a different page than is actually being processed.

The reader's own bottom-bar label (`translationCurrentPage`, ReaderViewModel.kt:2297) **already uses the real** `pageIndex + 1` and is correct. The bug is in the snapshot/tracker/queue-sheet paths.

---

### Bug 4 — Manual translate lights up page n+3
**Severity:** MEDIUM (translation behavior is correct; UI confusion)

**Root cause:** Auto-translate prefetch window.

- Manual `translateSinglePage` (ReaderViewModel.kt:1817) is page-accurate — translates the exact `ReaderPage` object passed.
- If **auto-translate is ON**, `handleAutoTranslation` (ReaderViewModel.kt:1109-1110) enqueues a window `currentIndex..lastIndex` of size 1–5 (default up to 5):
  ```kotlin
  val windowSize = translationPreferences.autoTranslatePrefetchCount().get().coerceIn(1, 5)
  val lastIndex = (currentIndex + windowSize - 1).coerceAtMost(pages.lastIndex)
  ```
- `translatorPermit = Semaphore(1)` (TranslationPipeline.kt:183) serializes all ONNX work. After page n completes, n+1, n+2, n+3 run in order.
- The scheduler flips `RUNNING` on each enqueued page (`markAutoPageStarting`, TranslationScheduler.kt:305-341). User perceives "I clicked n but n+3 lit up."

Not a manual-translate bug — it is auto-prefetch UI confusion.

---

### Bug 5 (NEW) — `forceReleaseNativeBuffers` SIGSEGV
**Severity:** HIGH (native crash, uncatchable)

**Root cause verified:** `RoiPageRecognitionEngine.forceReleaseNativeBuffers` (RoiPageRecognitionEngine.kt:725-731) calls `detector?.forceReleaseNativeBuffers()` etc. with **no** `nativeGuard` acquisition:

```kotlin
override fun forceReleaseNativeBuffers() {
    try { detector?.forceReleaseNativeBuffers() } catch (_: Exception) {}
    try { roiOcrEngine?.forceReleaseNativeBuffers() } catch (_: Exception) {}
    try { paddleDet?.forceReleaseNativeBuffers() } catch (_: Exception) {}
    try { inpainting?.forceReleaseNativeBuffers() } catch (_: Exception) {}
    try { panelDetector?.forceReleaseNativeBuffers() } catch (_: Exception) {}
}
```

Workers hold `nativeGuard` across `OrtSession.run`:
- `analyze` at RoiPageRecognitionEngine.kt:273 (`nativeGuard.withLock { localDetector.detect(bitmap); OCR loop }`).
- `inpaint` at RoiPageRecognitionEngine.kt:492.

Sub-engine impls have **no internal synchronization**:
- `OnnxPageTextDetector.forceReleaseNativeBuffers` (OnnxPageTextDetector.kt:125-127) = `inputBufferPool.clear()`.
- `AOTInpainting.forceReleaseNativeBuffers` (AOTInpainting.kt:795-800) = pool clears + scratch clears.

**Crash mechanism:** `DirectBufferPool.clear` (domain/.../pools/DirectBufferPool.kt:76-84) iterates **both** `availableBuffers` AND `inUseBuffers`, calling `cleanDirectBuffer` (line 86-143) which reflectively invokes `sun.misc.Cleaner.clean()` — forcibly freeing the underlying native direct-byte-buffer memory. `OnnxPageTextDetector.detect` acquires a pooled `FloatBuffer` (line 69), wraps it in an `OnnxTensor` (line 70), passes it to `localSession.run` (line 76). ORT consumes the direct buffer **in place, no copy** (comment at OnnxPageTextDetector.kt:67-68). The buffer is returned to the pool only in the `finally` at line 108, after `run` returns.

Freeing the native memory under an in-flight `OrtSession.run` → ORT reads freed memory → **SIGSEGV, uncatchable from Kotlin.**

**Trigger:** `ChapterTranslator.onMemoryPressure` (ChapterTranslator.kt:225-241) calls `pipeline.forceReleaseNativeBuffers()` (line 227) **unconditionally on every memory-pressure level**. Android fires `onTrimMemory` during real memory pressure while a worker may be mid-inference. Same path also runs from `ChapterTranslator.stop` (line 209) on reader-backgrounded.

The cooperative `closed` flag does **not** help here — `forceReleaseNativeBuffers` does not check `closed` and does not touch the session; it goes straight at the buffer pool. This is strictly worse than `close()` (line 689-712), which at least `tryLock`s `nativeGuard` and defers if held.

---

## Refuted Claims (do NOT fix)

The following hypothesized edge cases were investigated and **refuted** — the codebase already contains explicit defenses.

| Hypothesis | Verdict | Existing defense |
|------------|---------|------------------|
| Memory-pressure starvation (streams wedged) | REFUTED | Streams for auto-translate come from `readerPage.originalStream`, not the registry. `cancelTranslatorJob` idempotent on repeat. No wedge. |
| FAILED-page retry stuck in `inFlightPageKeys` | REFUTED | Watchdog `onForceRelease` + `closeEngines.clear()` handle every exit path. No permanent stuck key. |
| `sweepStrandedPageStatus` TOCTOU with worker | REFUTED | Sweep is one-shot on chapter load. Watchdog fires at 90s; sweep threshold is 120s. Terminal-recheck prevents double-cancel. |
| StreamRegistry cross-chapter contamination | REFUTED | Store keyed by old chapter ID. `defunct` guard rejects late writes. No cross-contamination. |
| `loadNewChapter` permit stall | DOWNGRADED to MEDIUM | Real but bounded 90s (watchdog), self-healing. Not a permanent deadlock. |

---

## Defense Plan (for implementation phase)

### P0 — correctness / safety (4 changes)

#### P0-1 — `forceReleaseNativeBuffers` nativeGuard guard (Bug 5, highest value)

Add `nativeGuard.tryLock()` in `RoiPageRecognitionEngine.forceReleaseNativeBuffers` (RoiPageRecognitionEngine.kt:725-731). If `tryLock` fails (worker mid-inference), **skip** — the worker frees buffers on its own completion.

Pattern (mirrors existing `close()` at line 698-705):
```kotlin
override fun forceReleaseNativeBuffers() {
    if (!nativeGuard.tryLock()) return
    try {
        try { detector?.forceReleaseNativeBuffers() } catch (_: Exception) {}
        try { roiOcrEngine?.forceReleaseNativeBuffers() } catch (_: Exception) {}
        try { paddleDet?.forceReleaseNativeBuffers() } catch (_: Exception) {}
        try { inpainting?.forceReleaseNativeBuffers() } catch (_: Exception) {}
        try { panelDetector?.forceReleaseNativeBuffers() } catch (_: Exception) {}
    } finally {
        nativeGuard.unlock()
    }
}
```

**Why skip is acceptable:** if the worker holds `nativeGuard`, it is mid-inference and its pooled buffers are in `inUseBuffers`. `clear()` would free them (crash). Skipping leaves them allocated until the worker's `finally` returns them to the pool — the soonest they can be safely freed anyway. The full teardown still happens via `closeEngines` → `RoiPageRecognitionEngine.close` → `freeNativeSessions`, guarded by the same `nativeGuard.tryLock` deferral. Matches the documented "leak-instead-of-SIGSEGV" policy (RoiPageRecognitionEngine.kt:83-85).

Covers both call sites: `ChapterTranslator.kt:209` (reader-backgrounded) and `:227` (memory-pressure).

---

#### P0-2 — Synchronous CANCELLED flip in `cancelPageTranslation` (Bug 1)

Add a synchronous stage flip in `TranslationScheduler.cancelPageTranslation` (TranslationScheduler.kt:491-499), dispatched on `scope.launch`. **Zero persist guards needed** (verified): a cancelled worker cannot reach any post-ONNX `store.updatePage` — `Mutex.withLock` throws `CancellationException` at the suspend point before the lambda runs. The only `NonCancellable` store write is `markPageCancelled` (line 420), and it is idempotent with the sync flip (guards `hasRenderedResult || isStageFailed`).

Sketch:
```kotlin
fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean {
    val jobKey = "$chapterId:$pageKey"
    queuedPageKeys.remove(jobKey)
    queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
    val job = synchronized(activePageJobs) { activePageJobs.remove(jobKey) }
    job?.cancel()
    // P0-2: flip to CANCELLED immediately so the UI stops showing "translating"
    // instead of waiting up to 90s for the worker to unwind out of native ONNX.
    // Safe without persist guards: a cancelled worker cannot reach any
    // post-ONNX store.updatePage (Mutex.withLock throws CancellationException
    // at the suspend point before the lambda runs). The only NonCancellable
    // write (markPageCancelled in the worker's finally) is idempotent with
    // this flip.
    scope.launch {
        val store = storeResolver.resolve(chapterId) ?: return@launch
        store.updatePage(pageKey) { current ->
            if (current == null || current.hasRenderedResult || current.isStageFailed) {
                current ?: return@updatePage PageTranslation(
                    sourceFileName = pageKey,
                    ocrStatus = StageStatus.CANCELLED,
                    errorMessage = "Translation cancelled",
                    updatedAt = System.currentTimeMillis(),
                )
                current
            } else {
                current.apply {
                    cancelInFlightStages()
                    errorMessage = "Translation cancelled"
                    updatedAt = System.currentTimeMillis()
                }
            }
        }
    }
    val autoCancelled = cancelAutoTranslations(chapterId)
    return job != null || autoCancelled
}
```

The `hasRenderedResult || isStageFailed` guard mirrors `markPageCancelled`'s guard (line 446) so a stop click on an already-finished page is a no-op.

**Re-translate-after-cancel is safe:** `translateSinglePageOnnx` (TranslationPipeline.kt:1658-1671) resets `ocrStatus = RUNNING` and calls `prepareForcedRetry()` via a direct `store.updatePage` (not the persist helper), so the sync CANCELLED flip is overwritten when a new translation starts.

---

#### P0-3 — Clear `inFlightPageKeys` before `tryAcquire` in `closeEngines` (Bug 2)

Move `inFlightPageKeys.clear()` to the top of `closeEngines` (TranslationPipeline.kt:449-465), unconditionally. Remove the now-redundant clear at line 459.

```kotlin
fun closeEngines() {
    inFlightPageKeys.clear()   // P0-3: always clear, even when permit can't be acquired
    if (!translatorPermit.tryAcquire()) {
        enginesClosed = true
        return
    }
    try {
        enginesClosed = true
        try { recognitionEngine.close() } catch (_: Exception) {}
        try { textTranslator.close() } catch (_: Exception) {}
    } finally {
        translatorPermit.release()
    }
}
```

**Harmless:** clearing does not release the permit, so it cannot cause the old/new-worker key race — that race is bounded by the single permit (verified). The permit-block portion of Bug 2 is already watchdog-bounded at 90s; this fix closes the dedup-wedge portion.

---

#### P0-4 — try/catch around `onPageStuck` in watchdog (permit-leak deadlock defense)

Wrap `onPageStuck?.invoke(chapterId, pageKey)` at TranslationPipeline.kt:286 in its own try/catch, mirroring `onTimeout` (275-281) and `onForceRelease` (289-295).

```kotlin
val watchdog = permitWatchdogScope.launch {
    delay(timeoutMs)
    try {
        onTimeout()
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "TachiyomiAT permit-watchdog onTimeout threw: pageKey=$pageKey" }
    }
    logcat(LogPriority.ERROR) {
        "TachiyomiAT permit-watchdog FORCE-RELEASED after ${timeoutMs}ms (worker stuck in uncancellable code): pageKey=$pageKey chapterId=$chapterId"
    }
    // P0-4: guard onPageStuck so a throw here cannot skip onForceRelease + releaseOnce.
    try {
        onPageStuck?.invoke(chapterId, pageKey)
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "TachiyomiAT permit-watchdog onPageStuck threw: pageKey=$pageKey" }
    }
    try {
        onForceRelease()
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "TachiyomiAT permit-watchdog onForceRelease threw: pageKey=$pageKey" }
    }
    releaseOnce()
}
```

If `onPageStuck` throws, `onForceRelease` (clears `inFlightPageKeys`) and `releaseOnce()` (releases the permit) are currently skipped → permanent translation deadlock. 4-line fix.

---

### P1 — display correctness (2 changes)

#### P1-5 — Page number via real index resolver (Bug 3)

**Approach:** hybrid optional resolver. Do NOT add `index` to `PageTranslation` (index is a page-layout property, not a translation property — persisting risks staleness on re-packed CBZ + silent-`0` load for old files).

1. Add optional `indexResolver: Map<String, Int>?` param to:
   - `TranslationProgressSnapshot.compute` (TranslationProgressSnapshot.kt:75).
   - `TranslationBatchProgressTracker.computeSnapshot` (TranslationBatchProgressTracker.kt:196).
   When present: `index = indexResolver[pageKey] ?: (insertionOrder + 1)`. When absent: `insertionOrder + 1`.
2. **Delete all 3 regex copies** (TranslationProgressSnapshot.kt:146, TranslationBatchProgressTracker.kt:302, ReaderViewModel.kt:286-291).
3. Thread the resolver from:
   - `ChapterTranslator.kt:452-457` (`orderedStreams.mapIndexed { i, p -> p.first to i }.toMap()`) → `batchTrackerFactory` signature (TranslationPipeline.kt:226) → `createBatchTracker` (TranslationManager.kt:384) → tracker field.
   - `ReaderViewModel` (`viewerChapters.currChapter.pages` → `pageKey→index` map) into `buildQueuedPageInfo` (ReaderViewModel.kt:271).
4. Accept graceful degradation (insertion order) for the no-tracker fallback at TranslationManager.kt:426-430. Strictly better than current regex.

---

#### P1-6 — Distinguish "translating now" vs "queued" (Bug 4)

Distinguish the permit-holding page (actively rendering) from prefetched/queued pages in the UI. Requires the scheduler to expose permit-owner identity. Snapshot `activePage` (TranslationProgressSnapshot.kt:125) currently picks first RUNNING by regex — change to pick permit-holder only; prefetched pages show QUEUED.

---

### P2 — config consistency (1 change, scoped down)

#### P2-7 — Set `enginesClosed` on signature change (NOT a cancelling observer)

**Original P2-7 (cancelling observer) rejected:** `cancelAllPageTranslations(cancelBatchQueue = true)` evicts the batch queue via `internalClearQueue` → `emptyList()` (ChapterTranslator.kt:596). A user mid-200-page batch who changes an API key would lose their queue position (rendered pages persist, but the user must manually re-queue and restart). Severe regression.

**Replacement:** On `EngineSignature` change, set `enginesClosed = true` only — no job cancellation, no queue eviction. `ensureEnginesBuiltFor` (TranslationPipeline.kt:1493-1527) **already** lazily rebuilds on every config change per-page. Verified: the observer would be redundant for the disable-then-reconfigure-then-enable flow because `translationEnabled` is absent from `EngineSignature` (`computeTranslatorSignature`, TranslationPipeline.kt:360-379 — reads category, engine, key, model, temp, max-tokens, analytical-mode, reading-order, languages; not the master toggle).

Delivers the user's actual goal ("subsequent pages use the new config") with zero disruption. Accepts the documented mixed-config behavior for the single in-flight page (TranslationPipeline.kt:1783-1786). If strict consistency is later required, the correct primitive is a cooperative cancel-check inside the batch loop between pages (where it can actually take effect), not a global job cancellation that cannot interrupt native code.

---

### P3 — hygiene (low priority)

- **P3-9:** `@Volatile` on `consecutiveOomCount` (TranslationPipeline.kt:467) and `currentChapterTranslation` (line 468). Read at line 1214 outside the permit → stale read possible. Self-correcting but inconsistent with the file's other `@Volatile` fields.

---

## Files To Touch (when implementing)

### P0
- `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt` — `forceReleaseNativeBuffers` (~line 725)
- `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt` — `cancelPageTranslation` (~line 491)
- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` — `closeEngines` (~line 449), watchdog `onPageStuck` guard (~line 286)
- `app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt` — reference only (`cancelInFlightStages`, `isStageCancelled` already exist)

### P1
- `app/src/main/java/eu/kanade/translation/model/TranslationProgressSnapshot.kt` — `compute` signature, delete `resolvePageIndex`
- `app/src/main/java/eu/kanade/translation/batch/TranslationBatchProgressTracker.kt` — constructor + `computeSnapshot`, delete `resolvePageIndex`
- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` — `batchTrackerFactory` signature (~line 226)
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt` — `createBatchTracker` (~line 384), `observeBatchProgress` fallback (~line 426)
- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt` — (~line 452, build resolver from `orderedStreams`)
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` — `buildQueuedPageInfo` (~line 271), delete `resolvePageIndex` (~line 286)

### P2
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` — (~line 172, add signature-change observer that sets `enginesClosed` only)

### P3
- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` — lines 467-468

---

## Validation Approach

No unit tests exist for these concurrency paths. Each fix verified by:

| Fix | Validation |
|-----|------------|
| P0-1 | Assert `nativeGuard.tryLock()` path in `forceReleaseNativeBuffers`. Manual emulator repro: stress memory pressure mid-translation, confirm no SIGSEGV. |
| P0-2 | Logcat trace `pageKey` stage transitions through cancel. Confirm RUNNING→CANCELLED in <1s (not 90s). Confirm re-translate-after-cancel works. |
| P0-3 | Assert `inFlightPageKeys` empty after `closeEngines` regardless of permit state. Manual repro: stop-while-translating, confirm re-click starts new translate. |
| P0-4 | Hard to repro (requires `onPageStuck` to throw). Verify by inspection: try/catch present, mirrors siblings. |
| P1-5 | Add test cases for `009__002.jpg`, `page-09.png`, `009.jpg` in `TranslationProgressSnapshotTest.kt`. Confirm real-index map produces 9/9/9, not 2/fallback/fallback. |
| P2-7 | Confirm mid-batch config change does NOT evict queue; confirm subsequent pages use new engine via logcat signature mismatch. |

---

## Risks / Assumptions

- **P0-2 assumption:** `Mutex.withLock` throws `CancellationException` at the suspend point — verified for kotlinx.coroutines 1.x. If the project uses a version where `Mutex.lock` does NOT check cancellation (unlikely), persist guards become necessary.
- **P1-5 assumption:** `orderedStreams` at ChapterTranslator.kt:452 is in real page order (not reordered by `ResumeOrdering` in a way that breaks the index mapping). Verify before trusting the resolver.
- **P2-7 assumption:** `enginesClosed = true` forces rebuild via TranslationPipeline.kt:1524. Verify the boolean is read in `ensureEnginesBuiltFor`'s decision (line 1500/1519).

---

## Method Note

This report survived 5 rounds of critique in plan mode:
1. Round 1 — three parallel agents: traced manual flow, page-index tracking, concurrency audit. Found the 4 user bugs + 20+ edge cases.
2. Round 1.5 — main agent verified critical claims against live code (closeEngines early-return, regex mismatch, forceReleaseNativeBuffers).
3. Round 2 — two parallel agents attacked the proposed fixes + verified missed edge cases. Killed Fix 2 (useless join) and Fix 4 (regressive sync flip); refuted 4 of 6 "missed" claims.
4. Round 3 — two parallel agents resolved Fix B design (zero persist guards needed — `Mutex.withLock` defends for free) and attacked P2-7 (cancelling observer → batch-queue eviction regression; cheaper `enginesClosed` alternative).
5. Convergence — all surviving fixes verified correct against live code; all refuted claims traced to existing defenses.

**No code changes made. Report-only as requested.**
