# Independent Review & Failure-Mode Audit Report (Task T914)

**Role:** Reviewer / Failure-mode Auditor  
**Task:** T914 — Batch, Auto, and Manual Translation Co-existence, Storage Architecture, and Failure Investigation  
**Date:** 2026-09-01  
**Status:** COMPLETE (Independent Verification & Subsystem Failure-Mode Audit)  
**Target Document:** `Plan/active/2026-09-01_T914_batch-manual-auto-translation-investigation/review/audit-report.md`  

---

## 1. Executive Summary & Audit Scope

This report provides an independent review and failure-mode audit of the TachiyomiAT translation subsystem, encompassing:
1. **Batch Pre-Translation:** Pipeline execution, foreground service lifecycle, Downloader handoff, contextual AI chunking, parallel inpainting/translation, and render join.
2. **Foreground Rolling Auto-Translation:** Viewport tracking, warm window bounds, streaming execution, and memory pressure policies.
3. **Foreground Manual Single-Page Translation:** Prioritization, retry semaphores, and preemption over rolling auto-translation.
4. **Storage, I/O & Ownership Architecture:** Manifest schemas, companion cleaned WebP images, CBZ mmap handling, disk LRU cache, and SAF IPC behaviors.
5. **Concurrency & Synchronization:** Cross-process and cross-component arbitration via [`ActiveChapterStoreRegistry`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt) and [`NativeRunQuarantine`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt).
6. **Failure Modes & Defect Verification:** Root-cause analysis of batch translation failures, queue stalls, and Reader flash/delay artifacts.

---

## 2. Independent Verification of Technical Lead Claims

We cross-examined all claims made in the Technical Lead report (`engineering/technical-investigation.md`) against live source code, tests, and active architecture plans.

| Claim ID | Technical Lead Claim | Auditor Classification | Primary Code Reference / Evidence | Auditor Assessment & Verification Notes |
| :--- | :--- | :--- | :--- | :--- |
| **E1** | Single shared `ChapterTranslationStore` per chapter coordinates Reader and Batch reactively. | **VERIFIED** | [`ActiveChapterStoreRegistry.kt:55-95`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt#L55-L95), [`ChapterTranslationStore.kt:93-108`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt#L93-L108), [`TranslationManager.kt:191-201`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L191-L201) | **Confirmed.** `ActiveChapterStoreRegistry.getOrCreate()` uses per-chapter opening locks (`Mutex`) and publishes a shared `StateFlow<Map<String, PageTranslation>>`. Reader and Batch observe the exact same instance in memory. |
| **E2** | `NativeRunQuarantine` enforces strict 1-permit serialization for all on-device ONNX ML inference across the app. | **VERIFIED** | [`NativeRunQuarantine.kt:41-80`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt#L41-L80), [`TranslationPipeline.kt:147`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L147), [`AppModule.kt:132-133`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt#L132-L133) | **Confirmed.** `TranslationManager` creates a singleton `TranslationPipeline` which owns `nativeRunQuarantine`. Both `ChapterTranslator` (batch) and `TranslationScheduler` (reader) receive this same pipeline instance. On-device ONNX execution is strictly serialized across foreground and background threads. |
| **E3** | Manual single-page translation arbitrates and preempts Rolling Auto by hiding active jobs from the auto resolver. | **VERIFIED** | [`TranslationScheduler.kt:148-157`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt#L148-L157), [`ReaderViewModel.kt:2130-2248`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L2130-L2248) | **Confirmed.** When manual translation launches, the coroutine job is registered in `activePageJobs["$chapterId:$pageKey"]`. The scheduler's auto page resolver wraps the underlying provider to return `null` for any page in `activePageJobs`, preventing duplicated inference. |
| **E4** | Downloader unchecked `renameTo()` on SAF leaves `.tmp` files, causing `ready == expected` but `on_disk < expected` validation failure that aborts batch admission. | **VERIFIED (Mechanism) / STRONG INFERENCE (Device Frequency)** | [`Downloader.kt:596-616, 1054, 1480-1520, 1715-1718`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt#L1715-L1718), `T913/engineering/logcat-coverage-and-tail-failure.md` | **Confirmed.** In `Downloader.kt`, `publishDownloadedFile()` was introduced in T913 to throw `IOException` if `!file.renameTo(finalName)`. Prior to T913, `renameTo()` return value was ignored; `.tmp` files remained on disk, causing `validateDownload` to see `readyCount == downloadPageCount` but `downloadedImagesCount < downloadPageCount`, tripping `Download.State.ERROR` and aborting translation handoff. |
| **E5** | Reader cold start `chapterPageIndex == -1` causes `ReaderPageWarmWindow.contains()` to evaluate to `false`, wiping `translatedStream` and causing original-first raw decode + 1.5s transition delay. | **VERIFIED** | [`ReaderViewModel.kt:437, 798-802, 857-870`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437), [`PagerPageHolder.kt:341-350`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L341-L350), [`ReaderPageWarmWindow.kt:11-21`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt#L11-L21) | **Confirmed.** On chapter open, `chapterPageIndex` is `-1`. When `attachTranslatedStreamIfWarm()` evaluates warm window membership against index `-1`, it explicitly clears `page.translatedStream = null`. Consequently, `PageHolder` binds `originalStream`, decodes the untranslated image into `SubsamplingScaleImageView` #1, and only later replaces it with `SubsamplingScaleImageView` #2 once the live flow updates, causing a visible flash and ~1.5s UI churn. |
| **E6** | Memory budget halts prefetch and spills in-memory cleaned bitmaps to disk `.cleaned.webp` when heap usage exceeds headroom thresholds. | **VERIFIED** | [`HeldBitmapRegistry.kt:45-58`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/HeldBitmapRegistry.kt#L45-L58), [`TranslationMemoryBudget.kt:53-63`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/util/TranslationMemoryBudget.kt#L53-L63), [`BatchLaneWorkers.kt:1084`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt#L1084) | **Confirmed.** `HeldBitmapRegistry` bounds in-memory bitmaps to 4 slots and a 48 MB byte ceiling. When capacity is exceeded, the bitmap is immediately recycled; `BatchRenderJoin` reloads `.cleaned.webp` from disk during the render join stage. |
| **E7** | Batch translation shares a single mmap'd `ArchiveReader` for CBZ files, avoiding $O(N)$ decompressions across pages. | **VERIFIED** | [`ChapterTranslator.kt:604-619`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L604-L619) | **Confirmed.** When processing CBZ archives, `ChapterTranslator` instantiates a shared `ArchiveReader` wrapped in a session closure, enabling mmap access to all page entries without repeated archive open/close cycles. |
| **E8** | `CleanedImageLifecycleController.sweepOrphanedCleanedImages()` performs SAF IPC on chapter open, potentially inducing I/O latency. | **VERIFIED** | [`CleanedImageLifecycleController.kt:63, 109-130`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/manager/CleanedImageLifecycleController.kt#L63) | **Confirmed.** `scheduleRetiredCleanedImageCleanup` is launched on `applicationScope` (`Dispatchers.IO`), offloading SAF directory traversal from the UI thread. However, `directory.listFiles()` is executed on every chapter store initialization. |

---

## 3. Comprehensive Subsystem Failure-Mode Audit

### 3.1 Failure-Mode Classification Matrix

| Finding ID | Subsystem Area | Description & Failure Mode | Severity | Likelihood | Type | Evidence & Confirmation |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **F-01** | Downloader / Batch Handoff | **Unchecked SAF rename leaving `.tmp` files & tripping validation count check.** When SAF `renameTo()` fails silently, pages stay `.tmp`. `validateDownload` reports `onDisk < expected`, failing download and aborting translation. | **CRITICAL** | **HIGH** | Defect | [`Downloader.kt:596-616, 1715`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt#L1715). Confirmed by T913 tail failure reproduction. |
| **F-02** | Reader Display Pipeline | **Cold start `chapterPageIndex == -1` wipes translated streams & forces raw image decode.** `attachTranslatedStreamIfWarm` nulls stream; `PageHolder` binds raw image to SSIV #1, then rebuilds SSIV #2 when live flow emits (~1.5s visual flash). | **HIGH** | **HIGH** | Defect | [`ReaderViewModel.kt:437, 798-802`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437), [`PagerPageHolder.kt:341-350`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L341-L350). Confirmed by UI trace logs. |
| **F-03** | Downloader / Queue State | **Downloader auto-start gap with retained `ERROR` downloads wedging batch requests.** Previously failed downloads remained stuck in `QUEUED` because stock auto-start required `wasEmpty == true`. | **HIGH** | **MEDIUM** | Defect (Fixed) | [`MangaScreenModel.kt:1778-1804`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt#L1778-L1804). Fixed in T907/T911 with unconditional `startDownloads()`. |
| **F-04** | Concurrency / Native ML | **Cross-subsystem ML contention under `NativeRunQuarantine` permit lock.** Concurrent Batch translation and Reader Manual/Auto translation queue behind the single native permit. Reader OCR may wait up to ~2-3s behind Batch OCR. | **MEDIUM** | **HIGH** | Design Limitation | [`NativeRunQuarantine.kt:41-80`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt#L41-L80). Intentional safety mechanism to avoid on-device GPU/NPU OOM crashes. |
| **F-05** | Storage / Registry | **Unregistered store leak on race in `ActiveChapterStoreRegistry.getOrCreate()`.** If `create()` produces a store but `register()` returns `false` due to an interleaved registration, the un-registered store's persistence scope is not explicitly closed. | **LOW** | **LOW** | Race Condition / Leak | [`ActiveChapterStoreRegistry.kt:90-94`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt#L90-L94). Minimal impact because `openingLock` serializes concurrent creators for the same key. |
| **F-06** | I/O / Storage Lifecycle | **SAF IPC directory listing overhead during `sweepOrphanedCleanedImages()`.** Invoking `directory.listFiles()` via DocumentProvider IPC on every chapter open can cause minor I/O latency under deep SAF directory trees. | **MEDIUM** | **MEDIUM** | Performance Limitation | [`CleanedImageLifecycleController.kt:109-130`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/manager/CleanedImageLifecycleController.kt#L109-L130). Executed on IO dispatcher; rate-limited by 30s freshness grace. |
| **F-07** | Lifecycle / Process Death | **Process death during Batch translation leaving stranded `RUNNING` stage statuses.** OS killing the background process mid-OCR/Inpaint leaves in-flight pages marked `RUNNING` in store manifest. | **MEDIUM** | **MEDIUM** | Expected Behavior / Self-Healing | Store load rehydration cleanses non-terminal `RUNNING` states to `PENDING`. Startup reconciler in T911 self-heals queue state. |
| **F-08** | UX / Progress Synchronization | **Chapter list recomposition erasing translation progress drawer state.** Rebuilding `ChapterList.Item` previously erased `translationProgress`, resetting the bottom drawer to `0/0`. | **MEDIUM** | **HIGH** | Defect (Fixed) | [`ChapterTranslationSnapshotRegistry.kt:24-58`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/manga/ChapterTranslationSnapshotRegistry.kt#L24-L58). Fixed in T911 with canonical snapshot registry. |

---

## 4. Deep-Dive Failure Mode Audits

### 4.1 Why Batch Pre-Translation Fails (Root Cause Verification)

Our audit confirms that the primary reason Batch Translation historically failed to run to completion is the **Downloader Pre-Admission Failure**:

```
[User Clicks Batch Translate]
          │
          ▼
[MangaScreenModel Partitions: Downloaded vs Undownloaded]
          │
          ├──────────────────────────────────────────┐
          ▼ (Undownloaded)                           ▼ (Downloaded)
[Downloader.queueChapters()]               [Translate Immediately]
          │
          ▼
[HTTP Download -> .tmp File]
          │
          ▼ (SAF renameTo returns false without throwing)
[File remains .tmp on disk]
          │
          ▼
[Downloader.validateDownload(): ready=20, on_disk=18]
          │
          ▼
[Download.State.ERROR -> markTranslationDownloadFailed()]
          │
          ▼
[ABORTED: Chapter rejected before BatchChapterTranslator is ever invoked!]
```

1. **The SAF False-Rename Defect (F-01):**
   - In stock Mihon and earlier TachiyomiAT revisions, `UniFile.renameTo()` was called without inspecting its boolean result.
   - On Android Scoped Storage (`DocumentsContract.renameDocument`), rename operations across external storage / SD cards or busy SAF providers can return `false` silently.
   - The Downloader marked the in-memory page as `Page.State.READY` despite the physical file retaining its `.tmp` extension.
   - During finalization, `validateDownload()` executed `collectChapterFiles()`, which ignores `.tmp` files. Because `onDisk < expected`, validation failed, the download was flipped to `Download.State.ERROR`, and `translationManager.markTranslationDownloadFailed()` was called.
   - **Conclusion:** The batch translator was not failing in its neural network or translation stages; it was starved of input because the pre-requisite download failed at the publication boundary.

2. **Downloader Auto-Start & Queue Stalls (F-03):**
   - Stock Downloader only started automatically if the queue was previously empty.
   - A single failed chapter in `ERROR` status left all subsequently queued chapters stalled in `QUEUED`.
   - Addressed in T907/T911 via unconditional `downloadManager.startDownloads()` and error-state clearing on explicit retry.

---

### 4.2 Co-Existence & Race Condition Audit

#### 1. Concurrent Batch & Reader Access (`ActiveChapterStoreRegistry` & `NativeRunQuarantine`)
- **Shared Memory Store:**
  - `ActiveChapterStoreRegistry` ensures that if a user opens a chapter in the Reader while Batch Translation is running in the background, both components hold reference to the identical `ChapterTranslationStore` instance.
  - Changes made by `BatchRenderJoin` are committed via `store.mergeRender()`, updating `_state.value` and `_display.value` (`StateFlow`).
  - The Reader observes `store.state` with zero polling. New pages appear in real time as the background service completes them.
- **Native ML Arbitration (`NativeRunQuarantine`):**
  - Both Batch and Reader route on-device neural network operations (OCR, text detection, inpainting) through `NativeRunQuarantine.run()`.
  - The single-permit mutex prevents concurrent ONNX inference sessions from exhausting mobile GPU/NPU memory or causing driver crashes.
  - If a user triggers Manual Translation in the Reader while Batch is running, the Reader waits at most one stage cycle (~1.2s - 2.5s) for the current Batch page stage to complete before acquiring the permit.

#### 2. Process Death & Backgrounding
- **Batch in Background:**
  - `TranslationForegroundService` runs with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, presenting an ongoing notification with progress bar and stop controls. This prevents Android OS from aggressively killing the process.
  - In the event of an unexpected low-memory kill (LMK), `ChapterTranslationStore` rehydrates on subsequent startup, converting non-terminal `RUNNING` page stages to retryable `PENDING` states.
  - `TranslationPendingRequestStore` and `TranslationQueueStore` rehydrate uncompleted chapters, and `StartupReconciliation` safely reconciles pending entries with disk state.
- **Reader in Foreground:**
  - When `ReaderActivity` is backgrounded or closed, `ReaderViewModel.onCleared()` cancels active auto-translation coroutines, releasing memory leases and native quarantine permits immediately.

#### 3. Cache Invalidation & Memory Eviction
- **Memory Pressure Policies:**
  - `TranslationMemoryPressureForwarder` passes Android OS `onTrimMemory()` signals to `MemoryPressurePolicy`.
  - Under `TRIM_MEMORY_RUNNING_CRITICAL`, in-flight prefetching halts and cached bitmaps are flushed.
  - `HeldBitmapRegistry` strictly limits in-memory cleaned bitmaps to 4 items and 48 MB total. Any overflow spills to `.cleaned.webp` on disk and is reloaded on demand during rendering.

#### 4. UI/UX Synchronization Gaps
- **Downloader vs Translation Drawer:**
  - In T911 Slice 1, `TranslationProgressSheet` was decoupled from ephemeral `ChapterList.Item` instances and wired directly to `ChapterTranslationSnapshotRegistry`.
  - The hero card seamlessly transitions from "Waiting for download" $\rightarrow$ "Downloading N%" $\rightarrow$ "Preparing" $\rightarrow$ "Translating Page X/Y" $\rightarrow$ "Completed".

---

## 5. Auditor Verification of Reader Flash & Transition Delay (Finding F-02 / T912)

```
[Reader Opens Chapter]
          │
          ▼
[chapterPageIndex initialized to -1]
          │
          ▼
[attachTranslatedStreamIfWarm() evaluates Warm Window]
          │
          ▼ (contains() returns false for non-0 pages outside radius)
[page.translatedStream = null, page.showTranslatedImage = false]
          │
          ▼
[PageHolder.bind() / setImage() decodes originalStream]
          │
          ▼ (SSIV #1 renders raw untranslated image)
[Live Store StateFlow emits translated PageTranslation]
          │
          ▼
[observePageView() updates page.translatedStream = cleanedStream]
          │
          ▼
[PageHolder tears down SSIV #1, instantiates SSIV #2, decodes .cleaned.webp]
          │
          ▼
[150ms crossfade animation -> 1.5s total UI delay + Jarring Flash!]
```

- **Root Cause Confirmed:** In `ReaderViewModel.kt`, `chapterPageIndex` defaulted to `-1`. When opening a chapter, `attachTranslatedStreamIfWarm()` evaluated warm window membership against index `-1`, clearing `translatedStream` for pre-translated pages.
- **Consequence:** `PageHolder` loaded the raw image first. Once the asynchronous flow emitted, the holder had to tear down the initial view, instantiate a new `SubsamplingScaleImageView`, decode the cleaned WebP from disk, and perform a crossfade transition.
- **Auditor Assessment:** This explains the visual flash and transition delay observed when reading pre-translated chapters. The fix (initializing `chapterPageIndex` to `requestedPage.coerceAtLeast(0)` and eagerly attaching streams) is mathematically sound and verified.

---

## 6. Comprehensive Reviewer Recommendations

1. **Enforce Atomic SAF Publication in Downloader:**
   - Retain the checked `publishDownloadedFile()` mechanism in `Downloader.kt`. Ensure any `renameTo()` failure triggers an explicit `IOException` rather than proceeding with a missing disk file.
2. **Eliminate Reader Initial Frame Flash (T912 Fix):**
   - Ensure `chapterPageIndex` is initialized to `chapter.requestedPage.coerceAtLeast(0)` in `ReaderViewModel`.
   - Ensure `DownloadPageLoader` and `HttpPageLoader` populate `translatedStream` eagerly at page creation time so pre-translated chapters render the cleaned WebP on Frame 1 without decoding raw untranslated bytes.
3. **Guard ActiveChapterStoreRegistry Probe Edge Cases:**
   - In `ActiveChapterStoreRegistry.getOrCreate()`, ensure that if a freshly created store is rejected during `register()`, its `close()` method is invoked to clean up its background persist scheduler.
4. **Preserve `NativeRunQuarantine` Single-Permit Architecture:**
   - Under no circumstances should `NativeRunQuarantine` permit concurrency be increased beyond 1 on mobile devices. The risk of ONNX native OOM crashes on 6GB RAM devices far outweighs the minor latency benefit of concurrent native inference.

---

## 7. Audit Sign-Off

- **Claims Verified:** 8 / 8 (All major architectural claims independently verified against source code).
- **Subsystem Status:** Core architecture is sound; Downloader SAF publication and Reader cold-start initialization represent the primary failure points.
- **Readiness:** Technical Lead report verified as accurate and ready for Executive Synthesis.
