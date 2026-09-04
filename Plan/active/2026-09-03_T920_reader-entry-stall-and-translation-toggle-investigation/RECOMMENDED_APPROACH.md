# Task T920 Detailed Recommended Approach

## 1. Problem Definition & Objectives

The investigation confirmed two critical defects and two performance bottlenecks in TachiyomiAT:
1. **Auto-Translation Drawer Toggle Freeze / ANR / Crash**:
   - Toggling off auto-translation in the reader drawer invokes `runBlocking` on the Main (UI) thread in `TranslationScheduler.markChapterCancelledSync`, blocking on `ChapterTranslationStore.mutex` while background workers perform atomic disk writes over SAF.
2. **200-Page Translated Chapter Entry Stall & Eviction**:
   - Entering a chapter with 200 translated pages triggers eager synchronous loading of 400 JSON page snapshot files and 1,600 SAF Binder IPC queries in `LegacyChapterMigrationSource.openInternal`. Storage timeouts, memory exhaustion, or transaction failures trigger `ReaderActivity.setInitialChapterError` -> `finish()`, evicting the user back to the manga screen.
3. **UI Thread Text Planning Stutter**:
   - `TranslationOverlayView.bind` runs `TextLayoutPlanner.plan` synchronously on the Main thread, dropping frames (15–60ms jank).
4. **Rapid-Scroll Thrashing & Layout Collapse**:
   - A rigid warm window (radius 4) strips `translatedStream` immediately during scrolling, causing dual-image decode thrashing. Initial placeholder height collapses to 0 when `recycler.height == 0`.

---

## 2. Expected Results & Success Metrics

| Metric / Scenario | Current Baseline | Expected Result with Recommended Approach |
|---|---|---|
| **Auto-Translate Toggle Off Latency** | 5,000ms+ (UI freeze, ANR, or crash) | **< 16ms (Instant UI response on same frame, 0ms lock wait on Main)** |
| **200-Page Chapter Entry Latency** | 25s–40s+ (frequent stall & eviction via `finish()`) | **< 80ms (Instant entry via manifest-only load, zero page JSON reads on open)** |
| **Chapter Entry Success Rate** | ~0% for 200-page translated chapters (exits to manga) | **100% reliable entry with zero SAF transaction overflow** |
| **Text Layout Planning UI Cost** | 15ms–60ms dropped frame jank per page on Main thread | **0ms Main thread cost (offloaded to `Dispatchers.Default` + pre-planned LRU cache)** |
| **Rapid Scroll Behavior (Flinging 50 Pages)** | Stream flapping, dual-SSIV decode thrashing, layout jumps | **Zero stream stripping within hysteresis window (±10), zero height collapse, debounced auto-scheduling** |
| **Memory Footprint on 200 Pages** | Spikes over 300MB heap from holding 200 full JSON graphs | **Bounded under 40MB heap (manifest records only; snapshots loaded lazily)** |

---

## 3. Step-by-Step Implementation Approach

The architecture is divided into three sequential phases.

### Phase 1: Emergency Stability Hotfix (Toggle ANR & Chapter Entry Eviction)

#### Step 1.1: Non-Blocking Two-Phase Cancellation
1. **Instant In-Memory State Flip (`ChapterTranslationStore`)**:
   - Add `fastCancelInFlightStagesInMemory()` on `ChapterTranslationStore`:
     - Inspects `_state.value`.
     - Immediately clones and flips pages with `isStageRunning == true && !hasRenderedResult` to `CANCELLED` with an updated timestamp.
     - Updates `_state.value` and `_display.value` synchronously on the Main thread without acquiring `store.mutex` and without performing disk I/O.
     - **Result**: The UI flow emits immediately; spinning indicators and dim overlays disappear on the exact frame the switch is clicked.
2. **Eliminate `runBlocking` on Main (`TranslationScheduler`)**:
   - Modify `markChapterCancelledSync` to a suspending function `markChapterCancelledAsync(chapterId: Long)` running on `Dispatchers.IO`.
   - In `cancelAutoTranslations(chapterId: Long?)`:
     - Call `cancelActiveJobs()` immediately to cancel coroutine jobs.
     - Launch `markChapterCancelledAsync` inside `schedulerScope.launch(Dispatchers.IO)` with full `try-catch` shielding.
   - In `ReaderViewModel.kt:591–609`:
     - Call `store.fastCancelInFlightStagesInMemory()` first.
     - Invoke `translationScheduler.cancelAutoTranslations(it)` without blocking the Main thread.

#### Step 1.2: Manifest-Only Chapter Open & Lazy Snapshot Hydration
1. **Eliminate Eager 400x Snapshot Read Loop (`LegacyChapterMigrationSource`)**:
   - In `LegacyChapterMigrationSource.openInternal` (lines 218–231), remove the eager `manifest.pages.mapNotNull { artifactStore.readPageSnapshot(...) }` loops.
   - Synthesize `initialPages` directly from `manifest.pages`:
     - `PageArtifactRecord` in the manifest already stores `displayBase.fileName` (the cleaned image), display state, and stage completion.
     - Map each record to a lightweight `PageTranslation` instance holding `cleanedImageName` and stage statuses without reading any snapshot JSON file.
   - **Result**: Chapter open reads only the single `manifest.json` file (~50ms) instead of 400 files and 1,600 SAF Binder IPC queries.
2. **On-Demand Snapshot Loading (`ChapterTranslationStore`)**:
   - Add `getOrLoadPageSnapshot(pageKey: String): PageTranslation?`:
     - If `pages[pageKey]` already has text blocks, return it immediately from memory.
     - Otherwise, read the individual page snapshot file (`committed.pageSnapshotFileName`) asynchronously via `withContext(Dispatchers.IO)`, cache it in memory, and return it.
   - Wire `DownloadPageLoader` and `TranslationOverlayView` to query the lazy store.

---

### Phase 2: Rapid-Scroll Stabilization & Storage IPC Optimization

#### Step 2.1: Dual-Radius Window Hysteresis
1. **Update `ReaderPageWarmWindow`**:
   - Define two distinct thresholds for Webtoon reading mode:
     - `ATTACH_RADIUS = 4` (pages within `currentIndex ± 4` have their `translatedStream` attached and background text planning kicked off).
     - `EVICTION_RADIUS = 10` (pages are **only** stripped of their stream and evicted when scrolling past `currentIndex ± 10`).
2. **Update `ReaderViewModel.updateTranslationWorkingSet`**:
   - Apply the hysteresis logic: if a page is already warm (`translatedStream != null`) and is within `currentIndex ± 10`, preserve it!
   - **Result**: Flinging back and forth across 4–8 pages never destroys streams or triggers redundant raw/cleaned image re-decodes.

#### Step 2.2: Rapid-Scroll Auto-Translate Debounce
1. **Update `ReaderViewModel.onPageSelected`**:
   - When scrolling rapidly, page indicator UI state updates synchronously.
   - Auto-translate scheduling (`handleAutoTranslation`) is debounced by 150ms using a cancellable coroutine job.
   - **Result**: Fast flings do not spam transient worker jobs or thrash the scheduler.

#### Step 2.3: SAF Directory Handle Memoization
1. **Update `UniFileChapterDocumentIo.resolve`**:
   - Add a bounded LRU cache (`LruCache<String, UniFile>(32)`) for resolved directory handles (`_artifacts/pages/page_xxx`).
   - Reduces SAF Binder IPC queries by 75% for all subsequent page operations.

---

### Phase 3: Background Text Layout Planning & Fluid Rendering

#### Step 3.1: Offload `TextLayoutPlanner` to Background Threads
1. **Background `PaintTextMeasurer`**:
   - Create a thread-safe `PaintTextMeasurer` usable on `Dispatchers.Default`.
2. **Pre-Planning LRU Cache (`ReaderTextLayoutCache`)**:
   - Create an in-memory cache for `PreparedOverlayLayout` keyed by `(pageKey, width, height)`.
   - In `ReaderViewModel.updateTranslationWorkingSet`, pre-compute layouts for pages in the warm window on `Dispatchers.Default`.
3. **Zero-Wait UI Bind (`TranslationOverlayView`)**:
   - In `TranslationOverlayView.bind()`, consume pre-planned layouts from the cache with 0ms UI delay.
   - If not yet cached, launch a background coroutine on `Dispatchers.Default` and update the view upon completion, never stalling Main.

#### Step 3.2: Estimated Aspect-Ratio Placeholder
1. **Update `WebtoonPageHolder`**:
   - When `recycler.height == 0`, fallback to screen height (`displayMetrics.heightPixels`) instead of collapsing to 0.
   - For translated pages, read `imgWidth` and `imgHeight` from the manifest to pre-set `frame.layoutParams.height = screenWidth * (imgHeight / imgWidth)`.
   - **Result**: Zero layout jump or scroll dislocation when images finish decoding.

---

## 4. Verification & Testing Strategy

1. **Unit & Isolation Tests**:
   - `TranslationSchedulerCancelTest`: Verify `cancelAutoTranslations` executes without blocking the calling thread and that `fastCancelInFlightStagesInMemory` flips in-flight pages synchronously.
   - `ChapterTranslationStoreLazyOpenTest`: Verify `LegacyChapterMigrationSource.openInternal` constructs a valid store with 200 pages from a manifest without opening snapshot files.
   - `ReaderPageWarmWindowHysteresisTest`: Assert hysteresis contracts (attach at 4, retain at 7, evict at 11).
2. **Regression & Performance Validation**:
   - Benchmark chapter entry time on a simulated 200-page chapter: assert `< 150ms`.
   - Measure Main-thread block time during auto-translation toggle: assert `< 16ms` (0 frames dropped).
   - Verify standard manga (PagerViewer) has zero regression while Webtoon enjoys hysteresis and lazy loading.
