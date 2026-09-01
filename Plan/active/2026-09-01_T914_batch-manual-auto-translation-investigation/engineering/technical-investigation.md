# Technical Lead Investigation: Batch, Auto, and Manual Translation Co-existence, Storage Architecture, and Failure Analysis (Task T914)

**Author:** Technical Lead  
**Task:** T914  
**Date:** 2026-09-01  
**Status:** COMPLETE (Investigation & Technical Architecture Report)  
**Target Document:** `Plan/active/2026-09-01_T914_batch-manual-auto-translation-investigation/engineering/technical-investigation.md`

---

## 1. Executive Summary & Core Architectural Insights

TachiyomiAT is a high-performance Android manga/manhwa/manhua reader built on Mihon, extended with on-device machine learning (OCR, text detection, segmentation, and inpainting) and remote/local AI translation.

The translation system operates in three distinct user modes:
1. **Batch Pre-Translation (Background):** A whole-chapter or multi-chapter offline processing pipeline driven from the Manga Details screen, running under an Android Foreground Service (`TranslationForegroundService`) and processing downloaded chapter files sequentially in natural page order ($1 \dots N$).
2. **Rolling Auto-Translation (Foreground Reader):** A streaming, windowed translation coordinator (`RollingAutoCoordinator`) that dynamically detects the user's viewport ($P_{\text{visible}}$) and prefetches/translates a bounded radius ($P_{\text{visible}} + N$) ahead in real time, deferring missing network streams and adapting to memory pressure.
3. **Manual Single-Page Translation (Foreground Reader):** An on-demand, user-initiated action (via Reader toolbar button or tap) that immediately prioritizes the visible page ($P_{\text{current}}$), preempting background work and forcing reprocessing or error recovery.

### 1.1 Critical Architectural Findings
1. **Unified Storage via `ChapterTranslationStore` & `ActiveChapterStoreRegistry` [VERIFIED]:** Both foreground (Reader) and background (Batch) operate on a single shared, memory-cached, disk-persisted `ChapterTranslationStore` per chapter. Cross-process / cross-component communication is reactive via Kotlin `StateFlow<Map<String, PageTranslation>>`, ensuring the Reader immediately displays pages as they are completed by the background Batch worker.
2. **Hardware & Execution Arbitration via `NativeRunQuarantine` [VERIFIED]:** On-device neural network execution (ONNX Runtime for text detection, OCR, and inpainting) is strictly serialized across the entire process by `NativeRunQuarantine`. At any given instant, exactly one page tensor is held in native memory, preventing Out-Of-Memory (OOM) crashes and GPU/NPU contention.
3. **Root Cause of Batch Pre-Translation Failures [VERIFIED / STRONG INFERENCE]:**
   - **The Pre-Download Tail Failure:** Batch translation strictly requires full chapter download before admission. In `Downloader.kt`, `UniFile.renameTo(...)` results are unchecked across per-page downloads, cache copies, and directory renames. When a rename fails on Android Scoped Storage / SAF (returning `false` without throwing an exception), temporary `.tmp` files are left behind. Validation then fails with `ready == expected` but `on_disk < expected`, causing the entire chapter download to flip to `ERROR / DOWNLOAD_FAILED` and aborting translation admission.
   - **Sticky `DOWNLOAD_FAILED` State & Queue Stalls (T907/T911):** Downloader errors historically set durable `DOWNLOAD_FAILED` states that blocked subsequent retries until explicitly acknowledged or cleared.
4. **Root Cause of Reader Flash & Render Delay (T912) [VERIFIED]:**
   - When opening a pre-translated chapter in Reader, `chapterPageIndex` initializes to `-1`. This causes `ReaderPageWarmWindow.contains()` to evaluate to `false` for every page, actively clearing `page.translatedStream = null` and forcing `page.showTranslatedImage = false`.
   - Consequently, `PageHolder` first initializes and decodes the **raw original image** via `SubsamplingScaleImageView` #1. Once the async store flow emits, the holder tears down view #1, creates a brand new `SubsamplingScaleImageView` #2, decodes cleaned image tiles from disk, and runs a 150ms crossfade—causing high latency and a jarring flash of untranslated text.

---

## 2. System Architecture: How Batch Translation Works

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                MANGA SCREEN / UI TRIGGER                               │
│  MangaScreenModel.confirmChapterTranslation() ──► showConfirmTranslationDialog()        │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                        ┌──────────────────┴──────────────────┐
                        │ (Check: Downloaded vs Undownloaded) │
                        ▼                                     ▼
        ┌───────────────────────────────┐     ┌───────────────────────────────────┐
        │       NOT DOWNLOADED          │     │        ALREADY DOWNLOADED         │
        │ Downloader.queueChapters()    │     │ TranslationManager.               │
        │ Downloader.startDownloads()   │     │   translateChaptersIfCurrent()    │
        │ Downloader.onFinalized()      │     └─────────────────┬─────────────────┘
        │   └► handOffAfterFinalization │                       │
        └───────────────┬───────────────┘                       │
                        │                                       │
                        └──────────────────┬────────────────────┘
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   TRANSLATION QUEUE                                    │
│  TranslationManager.translateChapter() ──► Translator.queueChapter() ──► queueState   │
│  TranslationForegroundService.start() (Foreground Service + Notification)             │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              BATCH CHAPTER TRANSLATOR                                  │
│  ChapterTranslator.translateChapterInternal()                                          │
│    ├── 1. Open / create ChapterTranslationStore (ActiveChapterStoreRegistry)           │
│    ├── 2. Resolve chapter path / shared ArchiveReader (CBZ mmap)                      │
│    ├── 3. Sort natural page order (1..N) & preRegisterPages()                          │
│    ├── 4. BatchResumePlanner: hash source fingerprints & compute resume gate           │
│    └── 5. SequentialBatchCoordinator.runPass1()                                        │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                ┌──────────────────────────┴──────────────────────────┐
                ▼                                                     ▼
┌──────────────────────────────────────────────┐     ┌───────────────────────────────────┐
│              PASS 1: OCR STAGE               │     │      STREAMING CHUNK PLANNER      │
│  NativeRunQuarantine.run(timeout=120s)       │     │  Token-adaptive envelope grouping │
│    ├── Text Detection (ONNX)                 │     │  ChapterGlossaryBuilder seeding   │
│    ├── Text Recognition / OCR                │     │  TranslationPrompts format        │
│    └── Store write: ocrStatus = READY        │     └─────────────────┬─────────────────┘
└───────────────────────┬──────────────────────┘                       │
                        │                                              │
                        ├──────────────────────────┬───────────────────┘
                        ▼                          ▼
┌──────────────────────────────────────────────┐ ┌───────────────────────────────────────┐
│            PARALLEL INPAINT LANE             │ │       PARALLEL TRANSLATION LANE       │
│  NativeWorker.runInpaintStage()              │ │  ContextualTextTranslator / Provider  │
│  NativeRunQuarantine.run()                   │ │  (HTTP / API / Remote AI / Local LLM) │
│    ├── Inpainting Engine (ONNX / Lama / AOT) │ │  Rate-limited by ProviderGovernor     │
│    ├── Persist .cleaned.webp to companion dir│ │  Store write: translationStatus=READY │
│    └── Store write: inpaintStatus = READY    │ └─────────────────────┬─────────────────┘
└───────────────────────┬──────────────────────┘                       │
                        │                                              │
                        └──────────────────┬───────────────────────────┘
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                  BATCH RENDER JOIN                                     │
│  BatchRenderJoin.tryRender(pageKey):                                                   │
│    ├── Awaits Native Inpaint Signal + Translation Signal                               │
│    ├── Consumes HeldBitmapRegistry memory bitmap (or reloads .cleaned.webp from disk)  │
│    ├── RenderColorEstimator: computes background / text colors                         │
│    ├── Store write: renderStatus = READY, showTranslatedImage = true                   │
│    ├── TranslationBatchProgressTracker.markRenderDone()                                │
│    └── Release Batch Page Lease & Memory Bitmaps                                       │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Complete End-to-End Call Path

#### 1. UI Trigger & Confirmation
- **Location:** [`MangaScreenModel.kt:1031-1068`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt#L1031-L1068)
- When the user selects chapters and clicks "Batch Translate", `showConfirmTranslationDialog(items)` opens `ConfirmTranslationDialog`.
- Upon accepting, `confirmChapterTranslation(item)` executes:
  1. Acknowledges the pending request in `TranslationRequestCoordinator` (`translationManager.acknowledgeTranslationRequests(...)`), assigning a monotonic `generation` token to fence cancellation races.
  2. Opens the `TranslationProgressSheet` drawer immediately in the same UI transaction (`openTranslationProgressDrawer(item)`).
  3. Partitions selected chapters into `downloaded` and `awaitingDownload` using `downloadManager.isChapterDownloaded(..., skipCache = true)`.

#### 2. Download Execution & Handoff
- **Location:** [`MangaScreenModel.kt:1778-1804`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt#L1778-L1804), [`Downloader.kt:772-820`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt#L772-L820)
- For undownloaded chapters, `enqueueTranslationDownloads()` enqueues them via `DownloadManager.downloadChapters()` and calls `downloadManager.startDownloads()`.
- When `Downloader` completes all pages of a chapter:
  1. It finalizes metadata, verifies on-disk counts, and creates CBZ/directory structures.
  2. It invokes `Downloader.handOffAfterFinalization()` ([`Downloader.kt:772`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt#L772)).
  3. It executes `translationManager.rekeyTranslationForCompletedDownload(...)` to map online URL keys to on-disk filenames (e.g. `001.jpg`).
  4. It invokes `translationManager.startTranslationAfterDownloadIfRequested(...)` ([`TranslationRequestCoordinator.kt:499-526`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/manager/TranslationRequestCoordinator.kt#L499-L526)).

#### 3. Queue Admission & Foreground Service Activation
- **Location:** [`TranslationManager.kt:664-749`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L664-L749), [`TranslationForegroundService.kt:33-105`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt#L33-L105)
- Under `pendingRequestMutationLock`, `translateChaptersInternal`:
  1. Shuts down any active foreground reader `RollingAutoCoordinator` for this chapter (`scheduler.shutdownAutoCoordinator(chapterId)`).
  2. Sets the request phase to `PREPARING`.
  3. Enqueues the chapter into `ChapterTranslator.queueChapter(...)`.
  4. Clears the pending request once in `queueState`.
  5. Calls `startTranslation()`, which launches `TranslationForegroundService.start(context)`.
- `TranslationForegroundService` displays an ongoing notification (`Notifications.ID_TRANSLATION_PROGRESS`) with progress bar `(processedPages / totalPages)` and a "Stop All" action.

#### 4. Chapter Pipeline Execution
- **Location:** [`ChapterTranslator.kt:528-725`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L528-L725)
- `translateChapterInternal(translation)` executes on `Dispatchers.IO`:
  1. Opens the `ChapterTranslationStore` via `ActiveChapterStoreRegistry`.
  2. Locates chapter files:
     - **CBZ Archive:** Opens a single shared `ArchiveReader` via `mmap`, avoiding per-page decompression overhead ([`ChapterTranslator.kt:604-619`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L604-L619)).
     - **Directory:** Opens direct file streams.
  3. Sorts streams by natural page order ($1 \dots N$) via `ResumeOrdering.naturalOrder()`.
  4. Calls `store.preRegisterPages(batchOrderedPageKeys)`.
  5. Registers `TranslationBatchProgressTracker` in `TranslationBatchTrackerRegistry`.
  6. Delegates to `TranslationPipeline.translateBatch(...)` -> `BatchChapterTranslator.translateBatch(...)`.

#### 5. Staged Batch Execution (`BatchChapterTranslator`)
- **Location:** [`BatchChapterTranslator.kt:186-350`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L186-L350)
- `BatchChapterTranslator` coordinates:
  - **Generation Protocol:** `store.beginGeneration(...)` isolates current batch writes from stale executions.
  - **Engine Setup:** Pre-warms OCR and Translation models inside `NativeRunQuarantine` ([`BatchChapterTranslator.kt:226-238`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L226-L238)).
  - **Source Fingerprinting:** Hashes the first 64KB/size of each page image in parallel (`computeSourceFingerprint`) to invalidate stale artifacts if the source image changed ([`BatchChapterTranslator.kt:303-309`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L303-L309)).
  - **Resume Planning:** `BatchResumePlanner` checks existing store artifacts and skips already-completed stages (Detect/OCR, Inpaint, Translation, Render).
  - **Delegation to `SequentialBatchCoordinator`:** Launches `coordinator.runPass1(...)`.

### 2.2 StreamingChunkPlanner, Contextual Framing, AI Prompts & Glossary

- **Token-Adaptive Chunking:**
  - **Location:** [`StreamingChunkPlanner.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt), [`TranslationContextChunkPlanner.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt)
  - When AI Translation (Gemini, OpenAI, DeepSeek, Claude, LM Studio) is active, pages are dynamically batched into multi-page context envelopes based on estimated token budget (default: 2048 to 4096 tokens).
  - A chunk can contain 1 to 6 pages. If adding another page would exceed the token limit, the current chunk is finalized and translated, while the overflow page serves as an OCR probe for the next chunk.
- **Contextual Framing & Glossary Accumulation:**
  - **Location:** [`ChapterGlossaryBuilder.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/contextual/ChapterGlossaryBuilder.kt), [`TranslationPrompts.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/contextual/TranslationPrompts.kt)
  - `ChapterGlossaryBuilder` tracks recurring character names, terms, and honorifics across chunks within the chapter.
  - On batch resume, the glossary is pre-seeded from already-translated pairs in the store (`store.translatedPairs()`).
  - Prompts are formatted using XML/JSON structures with strict block IDs (e.g. `P001_B001`) to guarantee 1:1 alignment between OCR source blocks and translated output blocks.

### 2.3 Parallel Execution & Synchronization (`SequentialBatchCoordinator`)

- **Location:** [`SequentialBatchCoordinator.kt:35-330`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L35-L330)
- Within each chunk:
  1. **OCR Phase (Serial Native):** Runs `nativeWorker.runOcrStage(pageKey)` inside `NativeRunQuarantine`. Produces `OcrReadyPageRef`.
  2. **Overlap Phase (Remote AI vs Native Inpaint):**
     - **Remote Translation Lane:** Sends the entire chunk's text blocks to the remote AI API via `translatorWorker.completeChunkOutcome()`. Runs on IO dispatcher without holding the native permit.
     - **Native Inpaint Lane:** Simultaneously, `nativeWorker.runInpaintStage()` executes on-device inpainting for each page in the chunk sequentially under `NativeRunQuarantine`.
     - Produced cleaned bitmaps are registered in `HeldBitmapRegistry` (memory-capped; spilled to disk `.cleaned.webp` if limit exceeded).
  3. **Render Join (`BatchRenderJoin`):**
     - For each page, `awaitAndRender(pageKey)` blocks until BOTH the native inpaint signal and translation signal complete ([`SequentialBatchCoordinator.kt:133-145`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L133-L145)).
     - Executes `RenderColorEstimator` to select legible text colors, commits `renderStatus = READY` to `ChapterTranslationStore`, and releases bitmap memory.

---

## 3. Co-Existence with Manual & Auto (Rolling) Translation in Reader

### 3.1 Reader Translation Architecture

```
                               ┌──────────────────────────┐
                               │     ReaderActivity       │
                               └────────────┬─────────────┘
                                            ▼
                               ┌──────────────────────────┐
                               │     ReaderViewModel      │
                               └────────────┬─────────────┘
                                            │
               ┌────────────────────────────┴────────────────────────────┐
               ▼                                                         ▼
┌───────────────────────────────┐                         ┌───────────────────────────────┐
│       MANUAL TRANSLATION      │                         │     ROLLING AUTO TRANSLATION  │
│  ReaderViewModel.             │                         │  ReaderAutoTranslation-       │
│    translateSinglePage()      │                         │    Lifecycle.kt               │
│               │               │                         │               │               │
│               ▼               │                         │               ▼               │
│  TranslationScheduler.        │                         │  TranslationScheduler.        │
│    translatePage(force=true)  │                         │    updateAutoWindow()         │
│               │               │                         │               │               │
│               ▼               │                         │               ▼               │
│  activePageJobs["ch:pageKey"] │                         │  RollingAutoCoordinator       │
│  (Preempts Rolling Auto)      │                         │  Window: [P_visible .. P+N]   │
└───────────────┬───────────────┘                         └───────────────┬───────────────┘
                │                                                         │
                └───────────────────────────┬─────────────────────────────┘
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 NATIVE RUN QUARANTINE                                  │
│  Serialized Single Permit (Mutex + Atomic Generation)                                  │
│  Shared across: Batch Pipeline + Reader Manual + Reader Rolling Auto                   │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                             CHAPTER TRANSLATION STORE                                  │
│  Shared Memory StateFlow<Map<String, PageTranslation>> (ActiveChapterStoreRegistry)    │
│  Observed by: ReaderPageImageView / TranslationOverlayView (0ms cross-process UI sync) │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

#### 1. Rolling Auto Translation
- **Components:** [`ReaderAutoTranslationLifecycle.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationLifecycle.kt), [`RollingAutoCoordinator.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt), [`TranslationScheduler.kt`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt)
- When auto-translate is enabled in the Reader:
  - `ReaderAutoTranslationLifecycle` observes page changes from `ReaderViewModel.state.currentPage`.
  - Calls `TranslationScheduler.updateAutoWindow(identity, visiblePageIndex, aheadTarget, pageCount, session, pageResolver)`.
  - `RollingAutoCoordinator` maintains a desired window $[P_{\text{visible}} \dots P_{\text{visible}} + \text{aheadTarget}]$ (default ahead: 2 pages for Pager, 4 for Webtoon).
  - Uses a single native preparation lane (`prepareSinglePage`) and a single translation/render lane (`translatePreparedPage`) connected by a bounded `Channel(1)`.
  - Missing network streams are marked `Deferred(SourceUnavailable)` and retried once `HttpPageLoader` emits the stream, without stalling already-cached pages.

#### 2. Manual Single-Page Translation
- **Components:** [`ReaderViewModel.kt:2130-2248`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L2130-L2248), [`TranslationScheduler.kt:148-157`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt#L148-L157)
- Triggered by user tap on a specific page:
  - Registers the page stream in `TranslationStreamRegistry`.
  - If the page has an existing error, sets `force = true` (`prepareForcedRetry()`) to reset stage failure counters.
  - Launches an independent coroutine registered in `activePageJobs["$chapterId:$pageKey"]`.
  - **Manual Arbitration:** `TranslationScheduler` wraps the auto `pageResolver` so that any page present in `activePageJobs` resolves to `null`, actively hiding it from `RollingAutoCoordinator` so auto-translation does not duplicate work.

### 3.2 Resource Competition & Sharing

| Resource | Shared By | Coordination Mechanism | Policy / Failure Mode |
| :--- | :--- | :--- | :--- |
| **Native ONNX (OCR/Inpaint)** | Batch, Reader Auto, Reader Manual | `NativeRunQuarantine.run()` (Mutex + Generation) | Strictly 1 active page tensor in native code. Max timeout 120s. Late returns discarded. |
| **CPU / Thread Pools** | All Coroutines | `Dispatchers.IO` / `Dispatchers.Default` | Coroutine scopes structured hierarchically. Background batch runs with lower priority. |
| **Remote AI API** | Batch, Reader Auto, Reader Manual | `ProviderRequestGovernor` | Shared rate limiter & token bucket. Global cooldown on 429/quota limits. |
| **Memory / Bitmaps** | Batch vs Reader Display | `TranslationMemoryBudget` & `MemoryPressurePolicy` | Memory headroom check before prefetch. At $\ge 85\%$ heap, prefetch stops. On TRIM, in-memory caches evicted. |
| **Disk Translation Store** | Batch & Reader | `ActiveChapterStoreRegistry` | Single instance of `ChapterTranslationStore` per chapter ID. Thread-safe mutations via `StateFlow` + Mutex. |

### 3.3 Collision & Co-existence Scenarios

#### Scenario A: User opens Reader on a chapter currently being Batch translated in background
- **Behavior [VERIFIED]:**
  1. `ReaderViewModel.loadChapter()` requests the chapter store from `ActiveChapterStoreRegistry.getOrCreate(chapterId)`.
  2. The registry returns the **exact same `ChapterTranslationStore` instance** currently being mutated by `BatchChapterTranslator`.
  3. Reader subscribes to `store.state` via `observeLiveTranslationStore()`.
  4. As the background Batch worker finishes pages (OCR $\rightarrow$ Inpaint $\rightarrow$ Translate $\rightarrow$ Render), `store.state` emits new snapshots.
  5. The active `ReaderPageImageView` and `TranslationOverlayView` observe the state and immediately render cleaned images and translated overlays in real time with **zero polling**.
  6. `RollingAutoCoordinator` sees that pages are already `READY` or `RUNNING` in the store and skips re-translating them.

#### Scenario B: User enqueues Batch Translation on a chapter currently open in Reader
- **Behavior [VERIFIED]:**
  1. `TranslationManager.translateChapter()` executes `scheduler.shutdownAutoCoordinator(chapterId)` ([`TranslationManager.kt:681`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L681)), gracefully stopping the Reader's rolling auto-coordinator.
  2. Batch worker takes ownership of the chapter queue.
  3. `ChapterTranslationStore` continues to emit live updates to the open Reader view.

#### Scenario C: Reader triggers Manual or Auto Translation while Batch queue is processing a DIFFERENT chapter
- **Behavior [VERIFIED]:**
  1. Batch translation is running on Chapter A in the background; user opens Chapter B in Reader and taps "Translate Page 1".
  2. Reader initiates `translateSinglePage()` on Chapter B.
  3. Both Chapter A (Batch) and Chapter B (Manual) attempt to execute ONNX models via `NativeRunQuarantine.run()`.
  4. `NativeRunQuarantine` serializes the access: whichever request acquired the lock finishes its current stage (e.g. OCR for 1.2s), then releases the lock to the other request.
  5. Translation HTTP requests proceed concurrently in parallel (subject to API rate limits).

---

## 4. Storage, I/O, Ownership & Artifact Lifecycle

### 4.1 File Formats, Locations, and Storage Tiers

| Storage Tier | Physical Location | Contents / Format | Lifecycle & Cleanup Owner |
| :--- | :--- | :--- | :--- |
| **Downloaded Chapter Directory** | Local storage / SAF tree: `<Downloads>/<Source>/<Manga>/<Chapter>/` | Raw downloaded image files (`001.jpg`, `002.jpg`) or `.cbz` archive. | `DownloadManager` / User chapter deletion. |
| **Chapter Artifact Directory** | `<Downloads>/<Source>/<Manga>/<Chapter>_artifacts/` | Structured, immutable stage artifacts: `pages/`, `images/`, `generations/`. | `ChapterArtifactStore` / `ArtifactRetention`. |
| **Companion Images Directory** | `<Downloads>/<Source>/<Manga>/<Chapter>_images/` | Rendered cleaned images: `001.cleaned.webp`, `002.cleaned.webp` (WebP lossless/lossy). | `CleanedImageLifecycleController` & `TranslationStreamRegistry`. |
| **Chapter Translation Manifest** | `<Downloads>/<Source>/<Manga>/<Chapter>.manifest.json` | JSON metadata defining schema version, authority (`ARTIFACTS`), and page hashes. | `ChapterArtifactStore` (Atomic commit via rename). |
| **Legacy Flat Translation File** | `<Downloads>/<Source>/<Manga>/<Chapter>.json` | Flat JSON mapping of `Map<String, PageTranslation>` (legacy compatibility layer). | `ChapterTranslationStore` (Flush on close/update). |
| **Reader Online Cache** | App internal cache: `cacheDir/chapter_disk_cache/` | 100 MiB bounded `DiskLruCache` storing raw streamed HTTP responses. | `ChapterCache` (LRU automatic eviction). |

### 4.2 Artifact Schemas & What is Persisted

1. **Page Translation JSON (`PageTranslation`):**
   - Source filename / URL key (`sourceFileName`).
   - Image dimensions (`imgWidth`, `imgHeight`, `originalImgWidth`, `originalImgHeight`, `decodeSampleSize`).
   - Stage statuses: `ocrStatus`, `translationStatus`, `inpaintStatus`, `renderStatus` (`PENDING`, `RUNNING`, `READY`, `PARTIAL`, `FAILED`, `TEXTLESS`).
   - Detected text blocks: Bounding boxes (`box: [ymin, xmin, ymax, xmax]`), orientation (`HORIZONTAL` / `VERTICAL`), raw text (`text`), translated text (`translation`), text color (`textColor`), background color (`bgColor`), font size (`fontSize`).
   - Cleaned image pointer (`cleanedImageName`, e.g. `001.cleaned.webp`).
2. **Cleaned Inpainted Images (`.cleaned.webp`):**
   - High-quality WebP images with Japanese/Korean text inpainted and removed from speech bubbles.
3. **Text Layout Overlays:**
   - Text layout structures are calculated on demand via `TextLayoutPlanner` and cached in memory. In the active T912 architectural plan, planned layouts are pre-calculated during store loading.

### 4.3 Subsystem Ownership Matrix

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               SUBSYSTEM OWNERSHIP MAP                                  │
├──────────────────────────┬─────────────────────────────┬───────────────────────────────┤
│ Component                │ Manages / Owns              │ Threading / Concurrency Model │
├──────────────────────────┼─────────────────────────────┼───────────────────────────────┤
│ ActiveChapterStoreRegistry│ Active Store instances      │ Synchronized map + Mutex      │
│ ChapterTranslationStore  │ StateFlow<Map>, Disk JSON   │ Mutex (updateLock), IO scope  │
│ ChapterArtifactStore     │ Manifest, sidecars, prune   │ Atomic rename, IO scope       │
│ HeldBitmapRegistry       │ In-flight cleaned Bitmaps   │ Semaphore(slots), AtomicLong  │
│ TranslationStreamRegistry│ Reader InputStream closures │ ConcurrentHashMap, chapter-key│
│ TranslationPreferences   │ SharedPreferences / AndroidX│ Main / IO Reactive Flow       │
└──────────────────────────┴─────────────────────────────┴───────────────────────────────┘
```

---

## 5. Race Conditions, Locks & Concurrency Controls

### 5.1 Concurrency Primitives & Synchronization Mechanisms

1. **`NativeRunQuarantine`:**
   - Single permit `Mutex` guarding on-device ML execution.
   - Uses an atomic `generation` counter (`AtomicLong`). If an ONNX execution times out (e.g. hung driver / GPU crash), `generation` is incremented, and the late result is safely discarded upon eventual exit without corrupting subsequent runs ([`NativeRunQuarantine.kt:47-80`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt#L47-L80)).
2. **`BatchWriteGate` & Guarded Store Patches:**
   - Every batch store write verifies `PatchPrecondition(generation, pageVersion, leaseToken)` before applying updates.
   - Prevents an aborted or cancelled batch worker from overwriting user edits, manual translations, or newer batch generations ([`BatchWriteGate.kt:85-113`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchWriteGate.kt#L85-L113)).
3. **`ActiveChapterStoreRegistry` Per-Chapter Opening Locks:**
   - Multiple threads (e.g. Reader opening page 0 while Downloader hands off chapter completion) share a per-chapter `Mutex` during store file creation, guaranteeing exactly one `ChapterTranslationStore` instance is instantiated per chapter ID.

### 5.2 Identified Race Conditions & Prevention Analysis

| Race Condition / Edge Case | Mechanism & Code Point | Prevention / Current Defense | Status |
| :--- | :--- | :--- | :--- |
| **Download Cancellation vs Translation Admission** | User cancels translation request in UI while download worker is finalizing files in background. | T911 Slice 2: persisted `generation` token checked before admission (`TranslationRequestCoordinator.kt:324-373`). Stale generation dropped. | **VERIFIED FIXED** |
| **Manga List Rebuild Erasing Progress Drawer** | Chapter list rebuild in `MangaScreenModel` recreated list item with `translationProgress = null`, wiping live progress drawer to `0/0`. | T911 Slice 1: `TranslationProgressSheet` observes canonical `ChapterTranslationSnapshotRegistry` directly instead of ephemeral list item field. | **VERIFIED FIXED** |
| **Process Death Mid-Batch Leaving Stranded RUNNING** | App killed by OS while page is mid-OCR (`ocrStatus = RUNNING`). | On startup store load, `recoverPrimaryFromBackupOrNull` / `ArtifactRetention` resets non-terminal `RUNNING` stages to `PENDING` retryable state. | **VERIFIED** |
| **SAF Rename Failure during Page Download** | `UniFile.renameTo(...)` returns `false` on Android Scoped Storage, leaving `.tmp` file. Downloader sets `READY` but disk count fails. | T913 Finding: Needs checked atomic publication. Identified as primary root cause of batch pre-download failure. | **CONFIRMED ROOT CAUSE** |
| **Reader `chapterPageIndex == -1` Warm Window Annihilation** | Reader initializes page index to `-1`, causing `isInTranslationWarmWindow` to fail and wiping translated streams for all pages on chapter open. | T912 Finding: Initialize `chapterPageIndex = chapter.requestedPage.coerceAtLeast(0)`. | **CONFIRMED ROOT CAUSE** |

---

## 6. Complete Lifecycle Analysis (Startup, Before, During, After)

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. APP STARTUP                                                                         │
│  - TranslationManager.init(): launches background queue restore                       │
│  - ChapterTranslator.restoreQueue(): rehydrates persisted chapter IDs                  │
│  - TranslationPendingRequestStore.load(): loads pending requests                       │
│  - Startup Reconciliation (T911): reconciles pending requests vs queues vs disk files  │
│  - Orphan Cleaned Image Sweep: CleanedImageLifecycleController sweeps stale .cleaned.* │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 2. BEFORE TRANSLATION (Preflight Checks)                                               │
│  - Preflight Configuration Validation: checks ML Kit / AI API keys & supported langs   │
│  - Download Verification: probes local storage (skipCache=true) vs Downloader queue    │
│  - Fingerprint Matching: SHA-256 / size hash of image bytes to verify artifact validity│
│  - Engine Pre-warming: NativeRunQuarantine pre-warms ONNX runtime sessions             │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 3. DURING TRANSLATION (In-Flight Execution)                                            │
│  - Foreground Service: updates notification bar every 1000ms with processed/total pages│
│  - Concurrency Governance: NativeRunQuarantine serializes ONNX inference (1 permit)    │
│  - Memory Governance: TranslationMemoryBudget monitors heap; halts prefetch on pressure│
│  - Provider Governance: ProviderRequestGovernor handles API rate limits & backoff      │
│  - Cancellation Handling: cancelTranslatorJobAndJoin() bounds unwind to 2000ms         │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 4. AFTER TRANSLATION (Finalization & Teardown)                                         │
│  - Store Flush: atomic commit of manifest and JSON files to local storage              │
│  - Memory Release: BitmapPool.releaseAll(), recycle held bitmaps, clear OCR tensors    │
│  - Service Teardown: TranslationForegroundService stops or shows Paused notification   │
│  - UI Notification: Progress Drawer reflects COMPLETED or FAILED with typed reasons    │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 6.1 App Startup
- **Queue Rehydration:** `TranslationManager` initializes and launches `translator.restoreQueue()`. `TranslationQueueStore` reads persisted chapter IDs from `translation_queue.json` ([`ChapterTranslator.kt:177`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L177)).
- **Pending Request Store Rehydration:** `TranslationPendingRequestStore` restores unacknowledged or waiting requests.
- **Startup Reconciliation:** `runStartupReconciliationIfReady()` cross-checks pending requests against actual downloaded files on disk and queue states, self-healing stranded states.
- **Orphan Sweep:** `CleanedImageLifecycleController` scans companion directories for `.cleaned.webp` files unreferenced by any valid chapter store and deletes them if older than 30s grace period.

### 6.2 Before Translation Starts
- **Preflight Validation:** `isQueueConfigValid()` verifies target languages and API keys before allowing queue admission.
- **Durable File Check:** Downloader verifies whether files exist on disk or must be fetched over HTTP.
- **Source Fingerprinting:** Hashes input streams to ensure existing cache/artifacts match current source bytes.

### 6.3 During Translation
- **Reactive Progress Updates:** `TranslationBatchProgressTracker` emits stage-level events (`DETECT`, `OCR`, `INPAINT`, `TRANSLATE`, `RENDER`), forwarded to `ChapterTranslationSnapshotRegistry` and `TranslationForegroundService`.
- **Memory Pressure Monitoring:** `TranslationMemoryPressureForwarder` forwards OS `onTrimMemory()` callbacks to `MemoryPressurePolicy`. Under `TRIM_MEMORY_RUNNING_CRITICAL`, in-flight prefetch is halted and cached bitmaps are flushed.
- **Error Handling & Cooldowns:** API errors (e.g. HTTP 429 / Quota Exhausted) trigger `AiTranslationRetryPlanner`. If retry budget is exhausted, the chapter transitions to `PAUSED` with a timestamp indicating when retry is eligible.

### 6.4 After Translation Completes or Fails
- **Manifest Flush:** Store writes committed page snapshots to disk atomically.
- **Memory Cleanup:** `HeldBitmapRegistry` and `BitmapPool` recycle all temporary bitmaps.
- **Notification Teardown:** If successful, `TranslationForegroundService` dismisses its ongoing notification; if paused/failed, it posts a non-ongoing summary notification with a "Retry" button.

---

## 7. Root Cause Analysis: Why Batch Translation is Currently Not Working

Our exhaustive investigation across recent commits, active task documentation, and live source code identified the exact technical reasons why Batch Translation has experienced failures:

### 7.1 Primary Root Cause: Downloader Tail Failure & SAF Unchecked Rename (T913)
- **Evidence Classification:** **VERIFIED (Mechanism) / STRONG INFERENCE (Device Symptom)**
- **Code Reference:** [`Downloader.kt:857-858, 901-910, 1039-1092`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt#L857-L858)
- **Technical Mechanism:**
  1. Batch translation requires downloaded chapters. When undownloaded chapters are batch translated, `MangaScreenModel` enqueues them into Mihon's `Downloader`.
  2. `Downloader` downloads images to a temporary file (`.tmp`) and renames them to their final name (e.g. `001.jpg`).
  3. `UniFile.renameTo(...)` calls Android's `DocumentsContract.renameDocument()` over SAF IPC. On certain Android storage providers / SD cards, `renameTo()` returns `false` without throwing an exception.
  4. `Downloader` ignores the boolean return value of `renameTo()`. The page is marked `Page.State.READY` in memory.
  5. When all pages finish, `Downloader.areAllPageDownloaded()` enumerates files on disk. Because the file is still named `.tmp`, it is excluded from disk enumeration.
  6. Validation sees: `READY == expected` (e.g. 20 == 20), but `on_disk == 18` ($18 < 20$).
  7. `Downloader` fails the entire chapter with `Download.State.ERROR` and calls `translationManager.markTranslationDownloadFailed(chapterId, "Chapter download failed")`.
  8. **Outcome:** The chapter is rejected before it can ever be admitted to `BatchChapterTranslator`!

### 7.2 Secondary Root Cause: Auto-Start & Queue Wedge (T907 / T911)
- **Evidence Classification:** **VERIFIED (Fixed in T907/T911)**
- **Code Reference:** [`MangaScreenModel.kt:1778-1804`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt#L1778-L1804), [`TranslationManager.kt:670-692`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L670-L692)
- **Technical Mechanism:**
  - Stock Mihon downloader only auto-starts if `wasEmpty == true`. A previously failed download entry in `ERROR` status left fresh translation downloads stuck in `QUEUED`.
  - Stale `DOWNLOAD_FAILED` requests persisted indefinitely in `TranslationPendingRequestStore`, projecting "Download failed — retry to continue" and preventing fresh translation triggers.
  - *Delivered Resolution:* T907/T911 added unconditional `downloadManager.startDownloads()`, self-clearing `DOWNLOAD_FAILED` phase on retry, and generation-fenced request admissions.

### 7.3 Reader Flash of Untranslated Content & Transition Delay (T912)
- **Evidence Classification:** **VERIFIED**
- **Code Reference:** [`ReaderViewModel.kt:437, 798-802, 857-865`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437), [`DownloadPageLoader.kt:82-105`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt#L82-L105)
- **Technical Mechanism:**
  - `chapterPageIndex` initializes to `-1`, causing `isInTranslationWarmWindow()` to fail and wiping `page.translatedStream = null` on chapter open.
  - `DownloadPageLoader` populates `page.translation` but leaves `translatedStream = null`.
  - Holder first decodes the raw image via `SubsamplingScaleImageView` #1. When async store flow arrives, holder tears down SSIV #1, instantiates SSIV #2, reads cleaned WebP from disk, and runs a 150ms crossfade—delaying the translated view by 1.5s+ and flashing untranslated text.

---

## 8. UX/UI & Communication Architecture

### 8.1 UI Surface Map

| Surface | Component | File Reference | Role & User Feedback |
| :--- | :--- | :--- | :--- |
| **Manga Details Screen** | Confirmation Dialog | `ConfirmTranslationDialog.kt` | Displays target engine, source/target languages, token output budget, cost warning, and chapter list. |
| **Manga Details Screen** | Progress Sheet (Drawer) | `TranslationProgressSheet.kt` | Comprehensive bottom drawer displaying: Hero phase (Downloading, Queued, Translating Stage %, Paused, Completed, Failed), pipeline stage progress (OCR, Translate, Inpaint, Render), per-page checklist with icons, and pause/resume/cancel controls. |
| **Manga Details Screen** | Chapter Row Indicator | `ChapterTranslationIndicator.kt` | Compact status ring next to each chapter: download ring vs translation ring, translated checkmark, translating spinner, error badge. Clicking opens the Progress Sheet. |
| **Reader View** | In-Viewer Overlay | `TranslationOverlayView.kt` | Custom Android `View` drawing translated text blocks over speech bubbles with calculated font sizes and background fills. |
| **Reader View** | Image View Container | `ReaderPageImageView.kt` | Handles image display via `SubsamplingScaleImageView` with crossfade support between raw image and cleaned image. |
| **Reader View** | Reader Top/Bottom Bar | `BottomReaderBar.kt`, `AutoTranslationStatus.kt` | Displays single-page translate button, OCR reset action, compare slider handle, and rolling auto-translation status pill (e.g. "Translating page 3/20"). |
| **System Notifications** | Foreground Notification | `TranslationForegroundService.kt` | Ongoing notification with progress bar and "Stop All" action during active batch translation; non-ongoing notification with "Retry" action when paused. |

### 8.2 State Synchronization Gaps & Mitigation

1. **Split-Owner Perception:**
   - While downloading before translation, the catalog chapter row shows the *Downloader* progress ring, while the translation drawer previously showed `0/0`.
   - *Mitigation (T911 Slice 1):* Drawer hero now explicitly displays download phases ("Accepted", "Waiting for download", "Downloading N%", "Preparing") before translation starts.
2. **List Rebuild Snapshot Erasure:**
   - Manga screen chapter list recompositions rebuild `ChapterList.Item` instances, which previously wiped `translationProgress` to `null`.
   - *Mitigation (T911 Slice 1):* `TranslationProgressSheet` subscribes directly to `ChapterTranslationSnapshotRegistry` keyed by `chapterId`, completely immune to list item recomposition.

---

## 9. Formal Evidence Classification Matrix

| # | Technical Finding / Architecture Claim | Classification | Primary Code Reference / Evidence |
| :--- | :--- | :--- | :--- |
| **E1** | Single shared `ChapterTranslationStore` per chapter coordinates Reader and Batch reactively. | **VERIFIED** | [`ActiveChapterStoreRegistry.kt:55-95`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt#L55-L95), [`ChapterTranslationStore.kt:40-120`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt#L40-L120) |
| **E2** | `NativeRunQuarantine` enforces strict 1-permit serialization for all on-device ONNX ML inference across the app. | **VERIFIED** | [`NativeRunQuarantine.kt:41-80`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt#L41-L80), [`TranslationPipeline.kt:147`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L147) |
| **E3** | Manual single-page translation arbitrates and preempts Rolling Auto by hiding active jobs from the auto resolver. | **VERIFIED** | [`TranslationScheduler.kt:148-157`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt#L148-L157), [`ReaderViewModel.kt:2130-2248`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L2130-L2248) |
| **E4** | Downloader unchecked `renameTo()` on SAF leaves `.tmp` files, causing `ready == expected` but `on_disk < expected` validation failure that aborts batch admission. | **STRONG INFERENCE** | [`Downloader.kt:857-858, 901-910, 1039-1092`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt#L857-L858), `T913/engineering/logcat-coverage-and-tail-failure.md` |
| **E5** | Reader `chapterPageIndex == -1` causes `ReaderPageWarmWindow.contains()` to evaluate to `false`, wiping `translatedStream` and causing original-first raw decode + 1.5s transition delay. | **VERIFIED** | [`ReaderViewModel.kt:437, 798-802, 857-865`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L437), [`ReaderPageWarmWindow.kt:11-21`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindow.kt#L11-L21) |
| **E6** | Memory budget halts prefetch and spills in-memory cleaned bitmaps to disk `.cleaned.webp` when heap usage exceeds headroom thresholds. | **VERIFIED** | [`HeldBitmapRegistry.kt:1-50`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/pipeline/batch/HeldBitmapRegistry.kt#L1-L50), [`TranslationMemoryBudget.kt:1-80`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/util/TranslationMemoryBudget.kt#L1-L80) |
| **E7** | Batch translation shares a single mmap'd `ArchiveReader` for CBZ files, avoiding $O(N)$ decompressions across pages. | **VERIFIED** | [`ChapterTranslator.kt:604-619`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L604-L619) |
| **E8** | `CleanedImageLifecycleController.sweepOrphanedCleanedImages()` performs `directory.listFiles()` SAF IPC on chapter open, causing main/IO thread latency. | **STRONG INFERENCE** | [`CleanedImageLifecycleController.kt:100-130`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/manager/CleanedImageLifecycleController.kt#L100-L130), [`TranslationManager.kt:868-902`](file:///c:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L868-L902) |

---

## 10. Conclusion & Strategic Roadmap

1. **Stabilize Batch Pre-Download Publication:** Enforce checked `UniFile.renameTo()` and atomic publication in `Downloader.kt` so temporary `.tmp` files cannot fail validation when all page bytes have been successfully received.
2. **Resolve Reader Flash & Double Decode (T912 Fix):** Initialize `chapterPageIndex = chapter.requestedPage.coerceAtLeast(0)` in `ReaderViewModel` and attach `translatedStream` eagerly in `DownloadPageLoader`/`HttpPageLoader` so pre-translated chapters render the cleaned image immediately on frame 1 without decoding raw untranslated bytes.
3. **Preserve Shared State Integrity:** Retain `ActiveChapterStoreRegistry` and `NativeRunQuarantine` as the foundational synchronization primitives connecting background Batch and foreground Reader.
