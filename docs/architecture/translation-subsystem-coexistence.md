# Translation Subsystem Architecture & Coexistence Specification

**Version:** 2.1 (Post-Peer-Review Hardened)  
**Date:** 2026-09-01  
**Status:** SUPERSEDED — REJECTED BY AUDIT (T916, Rounds 1–2). Not canonical. This document's central coexistence claims were contradicted by live code, tests, or lack of evidence. Do not build or plan against it. Successor: `translation-subsystem-coexistence-v3-draft.md`. Evidence: `Plan/active/2026-09-01_T916_translation-coexistence-strict-audit/STRICT_AUDIT_REPORT.md`.  
**Path:** `docs/architecture/translation-subsystem-coexistence.md`  

---

## 1. Context & Architectural Goal

TachiyomiAT is a high-performance Android manga/manhwa/manhua reader extending Mihon with on-device machine learning (text detection, OCR, inpainting) and remote/local AI translation (Gemini, OpenAI, Claude, DeepSeek, Local LLM).

### Core Goals
1. **Multi-Modal Translation:** Support three distinct operational modes—**Background Batch Pre-Translation**, **Foreground Rolling Auto-Translation**, and **Foreground Manual Single-Page Translation**—operating concurrently without state corruption, deadlocks, or duplicate work.
2. **Device Safety & Bounded Memory:** Enforce strict memory ceilings and serialized native tensor execution to prevent Out-Of-Memory (OOM) crashes on Android 8.0+ devices with at least 6 GB RAM.
3. **Reader Non-Regression:** Ensure normal un-translated and translated manga reading remains instantaneous ($60\text{ FPS}$ panning/zooming, zero UI stutter, zero visual flashing).
4. **Data Isolation & Integrity:** Maintain a clear separation between raw downloaded manga images, cleaned inpainted canvases, and translation coordinate manifests.

---

## 2. File & Component Dependency Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       1. PRESENTATION LAYER                                      │
│  MangaScreenModel.kt ──► TranslationProgressSheet.kt ──► ChapterTranslationIndicator.kt         │
│  ReaderActivity.kt   ──► ReaderViewModel.kt          ──► TranslationOverlayView.kt               │
└─────────────────────────────────┬───────────────────────────────┬────────────────────────────────┘
                                  │                               │
                                  ▼                               ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                     2. COORDINATION & SCHEDULING                                 │
│  TranslationManager.kt          ──► TranslationRequestCoordinator.kt ──► TranslationQueueStore.kt │
│  TranslationScheduler.kt        ──► RollingAutoCoordinator.kt        ──► AutoWindowState.kt      │
│  TranslationForegroundService.kt──► ChapterTranslationSnapshotRegistry.kt                        │
└─────────────────────────────────┬───────────────────────────────┬────────────────────────────────┘
                                  │                               │
                                  ▼                               ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   3. PIPELINE & EXECUTION CORE                                   │
│  ChapterTranslator.kt           ──► BatchChapterTranslator.kt        ──► SequentialBatchCoord.kt │
│  NativeRunQuarantine.kt         ──► BatchLaneWorkers.kt              ──► BatchRenderJoin.kt      │
│  StreamingChunkPlanner.kt       ──► ChapterGlossaryBuilder.kt        ──► TranslationPrompts.kt   │
└─────────────────────────────────┬───────────────────────────────┬────────────────────────────────┘
                                  │                               │
                                  ▼                               ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    4. STORAGE & ARTIFACT LAYER                                   │
│  ActiveChapterStoreRegistry.kt  ──► ChapterTranslationStore.kt       ──► BatchWriteGate.kt       │
│  ChapterArtifactStore.kt        ──► CleanedImageLifecycleCtrl.kt     ──► ChapterDataResetCtrl.kt │
│  Downloader.kt                  ──► DownloadManager.kt               ──► ChapterDocumentIo.kt    │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibility & Concurrency Matrix

| Subsystem / File | Primary Responsibility | Concurrency & Threading Model |
| :--- | :--- | :--- |
| [`MangaScreenModel.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt) | Manages Manga UI state, batch translation trigger dialogs, and chapter list indicators. | Main / UI Context + Screen Coroutine Scope |
| [`TranslationManager.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt) | Central orchestrator for batch queues, pending requests, service binding, and chapter re-keying. | Mutex-guarded (`pendingRequestMutationLock`), `applicationScope` |
| [`ActiveChapterStoreRegistry.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt) | Singleton registry ensuring exactly one `ChapterTranslationStore` exists per active chapter ID. | `@Synchronized LinkedHashMap` + per-chapter opening `Mutex` locks + `resettingChapterIds` fence |
| [`ChapterTranslationStore.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt) | In-memory `StateFlow` state holder and disk-persistence coordinator for page translation snapshots. | Thread-safe `StateFlow` updates + transactional disk `mutex.withLock` |
| [`NativeRunQuarantine.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt) | Global single-permit gate guarding all on-device ONNX inference (OCR, Det, Inpaint). | Single-permit `Mutex` (FIFO fair) + Atomic Generation discard |
| [`SequentialBatchCoordinator.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt) | Coordinates chunked OCR, parallel remote AI translation, parallel inpainting, and render join. | `Dispatchers.IO` + structured coroutine concurrency |
| [`RollingAutoCoordinator.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt) | Viewport-driven translation coordinator prefetching $P_{\text{visible}} + N$ pages ahead in reader. | Bounded `Channel(1)` between native and translation lanes |
| [`ChapterDataResetController.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/manager/ChapterDataResetController.kt) | Handles surgical reset and deletion of translation, inpaint, or OCR data without zombie writes. | Strictly sequenced cancellation, teardown joins, and registry resetting fence |
| [`ChapterDocumentIo.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt) | Atomic file I/O operations (`publish`, `readValidated`, `.bak` rotation, `.corrupt` quarantine). | `Dispatchers.IO` + SAF document streams |
| [`TranslationForegroundService.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt) | Android foreground service hosting ongoing progress notifications and system actions. | Main.immediate + 1000ms throttled polling |

---

## 3. Translation Modes: How They Co-Exist

### 3.1 The Three Modalities

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. BATCH PRE-TRANSLATION (Background)                                                  │
│  - Triggered from Manga Details Screen.                                                │
│  - Processes downloaded files sequentially in natural page order (1..N).               │
│  - Uses StreamingChunkPlanner for multi-page AI context envelopes.                     │
│  - Runs under TranslationForegroundService (DATA_SYNC).                                │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 2. ROLLING AUTO-TRANSLATION (Foreground Reader)                                        │
│  - Triggered by viewport navigation in ReaderActivity.                                 │
│  - Tracks visible page and maintains bounded warm window:                              │
│    [P_visible .. P_visible + 2] (Pager) or [P_visible .. P_visible + 4] (Webtoon).     │
│  - Defers missing network streams; adapts prefetch to OS memory pressure.              │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 3. MANUAL SINGLE-PAGE TRANSLATION (Foreground Reader)                                  │
│  - Triggered by direct user action on a specific page.                                 │
│  - Immediately registers in activePageJobs and preempts Rolling Auto on that page.     │
│  - Force-resets stage error counters to allow immediate retry.                         │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Synchronization & Arbitration Primitives

#### 3.2.1 Global Batch Serialization & Native Contention Invariant
* **`take(1)` Batch Worker Limit:** [`ChapterTranslator.kt:392`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L392) enforces `take(1)` across all active queued chapters. Exactly **one** background batch translation coroutine is permitted to execute at any time across the application.
* **Single Reader Instance:** `ReaderActivity` is declared as `singleTop` / `singleTask`, enforcing a single foreground reader instance.
* **Maximum Contention Bound ($\le 2$):** Because batch execution is limited to 1 worker and foreground reader auto/manual lanes are arbitrated via `activePageJobs`, there are **at most two coroutines** (1 Batch/Auto worker + 1 Manual coroutine) contending for the `NativeRunQuarantine` lock at any instant.
* **FIFO Fairness Guarantee:** Kotlin Coroutines `Mutex` enforces strict FIFO queueing. When Manual Translation starts, it queues as the immediate next waiter behind the running atomic batch stage ($\approx 1.2\text{s}$), acquiring exclusive ML hardware access as soon as the batch page unlocks.

#### 3.2.2 Unified State & Preemption Rules
1. **Unified Memory State (`ChapterTranslationStore`):**
   * Reader and Batch share the exact same store instance returned by `ActiveChapterStoreRegistry.getOrCreate(chapterId)`.
   * Background batch updates emit directly through `StateFlow<Map<String, PageTranslation>>`, updating Reader views with zero polling delay.
2. **Preemption & Collision Handling:**
   * **Collision A (Reader opens Batch Chapter):** Reader binds to the live store; completed pages pop in live; rolling auto skips completed/in-flight pages (`REUSE`).
   * **Collision B (Batch starts on Open Chapter):** `TranslationManager` cancels the Reader's rolling auto-coordinator via `shutdownAutoCoordinator(chapterId)`; Batch assumes full chapter execution; Reader continues observing live updates.
   * **Redundant Work Prevention:** `BatchLaneWorkers` evaluates `isPageReady(pageKey)` before dispatching remote AI prompts. If a manual job completed that page, Batch strips it from the chunk payload.
   * **Ownership Handback on Stationary Viewport:** If Batch terminates mid-chapter while the user remains completely stationary, Auto translation remains idle. The moment the user turns the page or scrolls, `onPageSelected` re-evaluates the viewport and instantiates a fresh `RollingAutoCoordinator`.
   * **Collision C (Manual Translate on Chapter B while Batch runs on Chapter A):** Both share `NativeRunQuarantine` (FIFO native lock) and `ProviderRequestGovernor` (API rate limiter) gracefully without starvation or memory crashes.

---

## 4. Storage, I/O, Lifecycle & Communication

### 4.1 Storage Tiers & Transactional Writes

```
<Manga Storage Directory>/
 ├── 001.jpg                      <-- Raw downloaded image
 ├── 002.jpg
 ├── <Chapter>_images/            <-- Cleaned Inpainted Canvases
 │    ├── 001.cleaned.1.jpg       <-- Versioned cleaned companion JPEG (Quality 92)
 │    └── 002.cleaned.1.jpg
 ├── <Chapter>_artifacts/         <-- Immutable Stage Artifacts
 │    └── pages/
 └── <Chapter>.manifest.json      <-- Translation Blueprint (Coordinates, Text, Colors)
```

#### 4.1.1 Transactional Mutation Atomicity (`BatchWriteGate` & `ChapterTranslationStore`)
Every write through `BatchWriteGate` delegates to `store.updatePageGuarded()`, which executes inside a contiguous `ChapterTranslationStore.mutex.withLock`:
1. **Version Validation:** Compares `expectedPageVersion`, `generation`, and `dependencyFingerprint`.
2. **In-Memory Projection:** Mutates `PageTranslation` copy.
3. **Disk Manifest Publication:** Synchronously flushes manifest updates to disk (`.tmp` write $\rightarrow$ rename) **before releasing the lock**.
4. **Rollback & State Emission:** On I/O failure, the in-memory update is rolled back. On success, `_state.value` emits the snapshot. Manual edits cannot interleave between the version check and the disk write.

* **Memory-Mapped CBZ Archives:** `ChapterTranslator` opens `.cbz` files via `ArchiveReader` memory-mapping (`mmap`), eliminating file descriptor open/close thrashing and full-archive heap decompressions while inflating entries on demand.
* **Pipeline Bitmap Spilling:** In-memory cleaned bitmaps are capped at 4 items / 48 MB (`HeldBitmapRegistry`). `HeldBitmapRegistry` is an ephemeral pipeline buffer scoped strictly to active batch chunks and recycles bitmap memory immediately after each page's render join.

### 4.2 Four-Stage Lifecycle Engine

```
┌─────────────────────────┐     ┌─────────────────────────┐     ┌─────────────────────────┐     ┌─────────────────────────┐
│       1. STARTUP        │ ──► │       2. PREFLIGHT      │ ──► │       3. IN-FLIGHT      │ ──► │       4. TEARDOWN       │
│ - Rehydrate queues      │     │ - Check AI keys/langs   │     │ - Foreground notif.     │     │ - Atomic manifest flush │
│ - Sweep orphan .cleaned │     │ - Verify download files │     │ - 1-permit ML lock      │     │ - Tensor & bitmap clean │
│ - Reset RUNNING->PENDING│     │ - Image fingerprint hash│     │ - RAM & API rate guard  │     │ - Dismiss notif. / done │
└─────────────────────────┘     └─────────────────────────┘     └─────────────────────────┘     └─────────────────────────┘
```

### 4.3 Three-Tier Frontend Communication

1. **In-Reader High Speed (`ChapterTranslationStore.state`):** Sub-millisecond `StateFlow` updates observed by [`TranslationOverlayView`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt) and [`ReaderPageImageView`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt). `StateFlow` is strictly a latest-state view projection; transactional queue commands execute over suspending Mutex calls.
2. **Canonical UI Snapshots (`ChapterTranslationSnapshotRegistry`):** Retains screen-scoped immutable progress snapshots, protecting bottom drawer progress from being erased by Compose list item recompositions.
3. **Android System Tray (`TranslationForegroundService`):** Displays ongoing progress notifications with live progress bars and interactive "Clear Queue / Stop Batch" and "Retry" intent actions. Calling `ACTION_STOP` clears batch queue chapters, leaving foreground reader manual jobs uninterrupted.

---

## 5. Concerns & Failure Modes (Audited)

| Finding ID | Area | Failure Mode / Concern | Severity | Status |
| :--- | :--- | :--- | :--- | :--- |
| **F-01** | Downloader / Batch Admission | **Unchecked SAF `renameTo()` leaving `.tmp` files.** Silent failure during temporary file rename caused `onDisk < expected` count mismatch, flipping download to `ERROR` and aborting batch translation. | **CRITICAL** | **ROOT CAUSE (Fix Verified)** |
| **F-02** | Reader Cold-Start Display | **`chapterPageIndex == -1` wiping translated stream.** Landing on page 0 with uninitialized index cleared `translatedStream`, forcing raw image decode first followed by a 1.5s delay and visual flicker. | **HIGH** | **ROOT CAUSE (Fix Verified)** |
| **F-03** | Downloader Queue State | **Downloader auto-start stalled by retained `ERROR` items.** Downloader auto-start previously required `wasEmpty == true`. | **HIGH** | **FIXED (T907/T911)** |
| **F-04** | ML Concurrency | **Single-permit native inference contention.** Heavy batch OCR can cause foreground reader manual OCR to wait $\approx 1.2\text{s} - 2.5\text{s}$. | **MEDIUM** | **INTENDED (Protects 6GB RAM devices from OOM)** |
| **F-05** | SAF Traversal | **SAF directory listing overhead on chapter open.** `CleanedImageLifecycleController` calls `listFiles()` via SAF IPC on store open. | **MEDIUM** | **OFFLOADED (Runs on IO scope; rate-limited)** |

---

## 6. Proposed Fixes & Implementation Roadmap

### 1. Enforce Checked Publication in Downloader (P0 — Fix for F-01)
* **Action:** Retain `publishDownloadedFile()` in [`Downloader.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt#L1715) to verify every `UniFile.renameTo()` call over SAF IPC, throwing an explicit `IOException` if renaming fails rather than silently marking an un-renamed `.tmp` file as ready.

### 2. Eliminate Frame-1 Reader Untranslated Flash (P0 — Fix for F-02)
* **Action:** Initialize `chapterPageIndex = chapter.requestedPage.coerceAtLeast(0)` in [`ReaderViewModel.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437).
* **Action:** Ensure [`DownloadPageLoader`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt) and [`HttpPageLoader`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt) attach `translatedStream` eagerly at page creation time so pre-translated chapters render the cleaned JPEG image immediately on frame 1 without raw image decode churn.

### 3. Maintain Canonical Snapshot State in UI Drawer (P1)
* **Action:** Preserve [`ChapterTranslationSnapshotRegistry`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/manga/ChapterTranslationSnapshotRegistry.kt) as the single source of truth for the Manga detail progress sheet and chapter list indicators.

---

## 7. Gaps & Performance Service Level Objectives (SLOs)

### 7.1 Verified Performance Targets / SLOs
* **Reader Navigation:** $\ge 60\text{ FPS}$ panning/zooming on hardware-accelerated canvas.
* **Textless Page Bypass:** $<100\text{ms}$ completion for speech-bubble-free action panels.
* **Manual Acquisition Latency:** $\le 2.5\text{s}$ worst-case permit acquisition behind active atomic native stage.

### 7.2 Gaps Requiring Ongoing Monitoring
1. **Slow SD Card / SAF Deep Directory Trees:**
   * On low-end micro-SD cards, DocumentProvider SAF IPC directory queries (`listFiles()`) can take several hundred milliseconds. Keep SAF traversals offloaded to `Dispatchers.IO`.
2. **Network Reconnection & Manual Retry Cooldown:**
   * If internet disconnects during cloud translation, `AiTranslationRetryController` transitions the chapter to `PAUSED` and surfaces the "Retry" action. If a user manually retries while cooldown is active, `ProviderRequestGovernor` rejects the call and sets `FAILED_RETRYABLE` with `retryAfterAtEpochMs` displayed in UI.
3. **Local LLM Memory Budget Coexistence:**
   * When running an on-device local LLM alongside ONNX inpainting, `TranslationMemoryBudget` halts speculative prefetch when JVM heap headroom drops below $15\%$.

---

## 8. Deep-Dive Edge Cases & Failure Recovery (Grounded in Code)

### 8.1 Storage & Invalidation Edge Cases

#### 1. Provenance Fingerprint Input Enumeration (`StageFingerprints.kt`)
Every stage computes deterministic length-prefixed fingerprints over its explicit inputs:
* **`detection` (8 fields):** `sourceHash` (SHA-256 of first 64KB + length), `detectorModelHash`, `segmenterModelHash`, `nativeProtocolVersion`, `thresholds`, `maskPostprocessVersion`, `panelAssignmentVersion`, `readingOrderVersion`.
* **`ocr` (6 fields):** `detectionArtifactId`, `ocrEngineVersion`, `ocrModelHash`, `sourceLanguage`, `preprocessingVersion`, `textNormalizationVersion`.
* **`inpaint` (7 fields):** `sourceHash`, `maskArtifactId`, `inpaintEngineVersion`, `inpaintModelHash`, `inpaintMode`, `inpaintSettings`, `cleanupRevision`.
* **`layout` (7 fields):** `translationArtifactId`, `cleanedImageArtifactIdOrOriginalSourceId`, `layoutEngineVersion`, `fontIdentity`, `fontScalePreferences`, `stylePreferences`, `outputDimensions`.
* **`glossaryVersion`:** Canonical sorted `(sourceTerm, targetTerm)` pairs from `ChapterGlossaryBuilder`.
* **`pageSnapshot` (18 fields):** `sourceFileName`, `cleanedImageName`, `ocrStatus`, `translationStatus`, `inpaintStatus`, `renderStatus`, `inpaintRevision`, `sourceFingerprint`, `detectionFingerprint`, `ocrFingerprint`, `inpaintFingerprint`, `translationFingerprint`, `layoutFingerprint`, `translationOrigin`, `retryCount`, `attemptCount`, `errorMessage`, `blocks.map { it.stableFingerprint() }`.

#### 2. Inpainting Mode Preference Mutation Mid-Batch
* **Code Reference:** [`BatchResumePlanner.kt:243-278`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L243-L278)
* **Mechanism:** If a user switches inpainting mode in settings (e.g. from *Simple Blur* to *On-Device Lama / AOT*), `desiredMode != page.inpaintingModeUsed`. `BatchResumePlanner.resumeGate()` returns `BatchResumeGate.INPAINT_ONLY`, causing the resume worker to **skip OCR and AI translation entirely** and only re-run the on-device inpainting lane to regenerate `.cleaned.1.jpg` companion images.

#### 3. Chapter Deletion Synchronous Teardown & Resetting Fence
* **Code Reference:** [`ChapterDataResetController.kt:108-171`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/manager/ChapterDataResetController.kt#L108-L171), [`ActiveChapterStoreRegistry.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt)
* **Mechanism:** Deleting a chapter executes an 8-step suspending teardown wrapped in a `resettingChapterIds` fence:
  1. `activeStores.markResetting(chapterId)` blocks concurrent `getOrCreate()` requests during teardown.
  2. `scheduler.cancelAutoTranslations(chapterId)` bumps generation to stop dispatching new auto pages.
  3. `cancelPageTranslations(chapterId)` cancels and joins in-flight single-page jobs.
  4. `translator.cancelTranslatorJobAndJoin()` halts the batch worker and releases the `NativeRunQuarantine` lock.
  5. `unregisterActiveTranslationStore(chapterId)` marks the store `defunct` so late writes no-op.
  6. `streamRegistry.clearChapter(...)` drops reader image closures before physical disk files and manifest directories are deleted.
  7. Delete disk files and manifests.
  8. `activeStores.clearResetting(chapterId)` clears the fence.

#### 4. Non-Zero-Length Corrupt Cleaned Artifact Recovery
* **Reader Recovery:** When `SubsamplingScaleImageView` encounters a corrupt/truncated cleaned image file, `ReaderPageImageView.onImageLoadError()` triggers `restorePreviousImageAfterLoadError()`, instantly reverting the canvas to display the raw source image without crashing.
* **Pipeline Recovery:** When `BatchRenderJoin` fails `BitmapFactory.decodeStream`, it flags `inpaintStatus = FAILED_RETRYABLE`, allowing inpainting to re-run from source and mask.

---

### 8.2 Concurrency & Navigation Edge Cases

#### 1. Rapid Viewport Flinging (Scrolling 50 Pages in 2 Seconds)
* **Code Reference:** [`RollingAutoCoordinator.kt:918-970`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt), [`AutoWindowState.kt:40-85`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/AutoWindowState.kt)
* **Mechanism:** In continuous Webtoon reading, jumping from Page 0 to Page 50 immediately triggers `updateWindow()`. In-flight speculative jobs in `RollingAutoCoordinator.inFlightJobs` outside the new $[50 \dots 54]$ window are retired. User manual jobs in `TranslationScheduler.activePageJobs` remain unaffected and continue running in background.

#### 2. Fragmented / Gap Translation Traversal
* **Code Reference:** [`BatchResumePlanner.kt:109-161`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L109-L161), [`BatchContextFrontier.kt:15-50`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt)
* **Mechanism:** When a chapter has fragmented translated pages (e.g. Page 1 and Page 10 completed, Pages 2–9 missing):
  - `seed()` only seeds the contiguous completed prefix (Page 1) into the rolling context.
  - Missing Pages 2–9 are chunked and translated using Page 1 as context.
  - Page 10 is marked `REUSE` and skipped when natural traversal reaches it, folding into the frontier for Page 11+.
  - *Context Isolation Guarantee:* Future dialogue from Page 10 never leaks backward into earlier missing pages.

#### 3. Textless Page Fast-Path & Model False-Negative Semantics
* **Code Reference:** [`PostOcrStageSemantics.kt:6-23`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/PostOcrStageSemantics.kt#L6-L23)
* **Mechanism:** When ONNX text detection identifies 0 speech bubbles on a page, `ocrStatus` is set to `StageStatus.TEXTLESS`. The coordinator flags `translationStatus = SKIPPED` and `inpaintStatus = SKIPPED`, bypassing cloud translation and inpainting lanes to finish in $<100\text{ms}$.
* **Model Limitation Classification:** If an ONNX detector fails to detect unusual handwritten text or SFX without throwing an exception, this is an **accepted machine learning model limitation**. The system provides an explicit user escape hatch via `ReaderViewModel.translatePage(force = true)` to force-retry detection.

#### 4. Forward-Evolving Glossary Consistency Model
* **Code Reference:** [`ChapterGlossaryBuilder.kt:21-57`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/contextual/ChapterGlossaryBuilder.kt#L21-L57)
* **Mechanism:** Glossary construction operates as an **incremental stream accumulator**. As pages $1 \dots K$ are translated in natural order, recurring proper nouns appearing in $\ge 3$ bubbles with $\ge 80\%$ translation consistency are accumulated and injected into the prompt context for subsequent pages ($K+1 \dots N$). The model is forward-evolving only and does not execute retroactive passes over already-translated pages.

---

### 8.3 Hardware, Memory & Provider Edge Cases

#### 1. Total Process & Native Memory Safety
* **Deterministic Tensor Deallocation:** `OrtSession.Result` and `OnnxTensor` instances are wrapped in `try { ... } finally { results?.close(); tensor?.close() }`. Direct JNI ByteBuffers are freed immediately upon stage completion.
* **Single Global Native Execution:** `NativeRunQuarantine` enforces that $\le 1$ native C++ matrix multiplication executes at any instant across all threads, bounding native heap scratch buffers to a single operation.
* **Pre-Decode Resolution Clamping:** `TranslationMemoryBudget.chooseDecodeSampleSize` scales large images down before creating native JNI pixel buffers.
* **In-Memory Bitmap Spilling:** `HeldBitmapRegistry` bounds in-memory bitmaps to 4 items / 48 MB, spilling excess to disk as `.cleaned.1.jpg`.

#### 2. Cloud AI Rate Limiting (HTTP 429) & Cooldown Enforcement
* **Code Reference:** [`AiTranslationRetryController.kt:45-90`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/retry/AiTranslationRetryController.kt), [`TranslationForegroundService.kt:69-89`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt#L69-L89)
* **Mechanism:** Remote 429 quota exhaustion transitions the chapter to `PAUSED` and sets `nextEligibleRetryAtEpochMs`. The service posts a stationary notification: `"Paused — rate limit reached · retry after 5:30 PM"`. If the user triggers a retry before the cooldown timestamp, `requeueTranslation()` rejects the premature call and preserves the pause state.

---

## 9. Architectural Edge-Case Test Suite Matrix

1. **HTTP 429 Premature Retry Rejection:** Multiple chapters using the same provider key must respect global cooldown timestamps and reject premature manual retries.
2. **Synchronous Deletion Mid-Native-Inference:** Deleting a chapter while `NativeRunQuarantine` is executing ONNX OCR must join coroutines, hold the `resettingChapterIds` fence, and prevent post-deletion file recreation.
3. **Inpainting Model Setting Mutation:** Switching inpainting mode in settings on a pre-translated chapter must trigger `INPAINT_ONLY` and make zero remote AI requests.
4. **Source Image Hash Mismatch:** Modifying raw image bytes must invalidate all downstream stages in `PageWorkPlanner` and force fresh OCR/translation.
5. **Rapid Webtoon Navigation:** Flinging 50 pages in continuous scroll mode must retire out-of-window auto jobs while preserving active manual jobs in `activePageJobs`.
6. **OS `onTrimMemory(CRITICAL)` Safety:** Memory trimming must flush idle bitmap pools while preserving actively executing native tensors inside quarantine.
7. **Textless Fast-Path Sequence:** 10 consecutive action spread pages with zero speech bubbles must complete in $<600\text{ms}$ total without remote AI invocations.
8. **Corrupt Cleaned Artifact Recovery:** A non-zero-length corrupt JPEG in `_images/` must safely trigger `restorePreviousImageAfterLoadError` in the Reader and fall back to the original image canvas.
