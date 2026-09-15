# T928 slice `ui` — Per-page stage emission → propagation → rendering audit

Audit base: branch `main` @ `9c19ad0`, tracked files only. All paths relative to
`app/src/main/java/` unless prefixed. Every claim is tagged VERIFIED (read at HEAD),
DERIVED (follows from verified code), or SUSPECTED (needs runtime proof).

Audience note: the reader "processing animation" is the per-page stage pill +
dim scrim inside `ReaderPageImageView` (View-based, one per page holder), plus
the Compose surfaces (`AutoTranslationStatus` rail, `BottomReaderBar` batch
line, manga-screen `ChapterTranslationIndicator`, `TranslationProgressSheet`).

---

## (a) The emission → propagation → render chains

There are THREE disjoint production vocabularies and three propagation graphs.
They share only the final pill render.

### A. MANUAL path (per-page translate tap)

**Production — durable store writes only, no event system.**

- First visible signal = the pipeline's first store status write, which is
  `ocrStatus = RUNNING` at "single-page OCR start"
  (`eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt:515-528`). VERIFIED.
- Later stage writes: inpaint resume RUNNING
  (`SinglePageOnnxPhase.kt:490-491`), translate RUNNING
  (`SinglePageOnnxPhase.kt` via HTTP phase — `pipeline/SinglePageHttpRenderPhase.kt:399-416`),
  render RUNNING (`SinglePageHttpRenderPhase.kt:560,641`; `SinglePageOnnxPhase.kt:596,601`).
  VERIFIED.
- Every write funnels through `ChapterTranslationStore.updatePageGuarded` →
  `publishLocked` (`eu/kanade/translation/ChapterTranslationStore.kt:660-690, 1876-1897`).
  Order inside `publishLocked`:
  1. `persistArtifactMutationLocked` (artifact manifest publication — disk I/O)
     runs FIRST (lines 1883-1889). For a page not yet in the manifest
     (first-ever write), a manifest registration write is mandatory
     (lines 1943-1969). VERIFIED.
  2. Only then are the UI StateFlows published: `_state.value = snapshotPages()`
     and `_display.value = displaySnapshotLocked()` (lines 1894-1895). VERIFIED.
  - Mitigation in place: a bare RUNNING/PENDING placeholder is NOT durable
    (`shouldPersistUpdate`, `ChapterTranslationStore.kt:2522-2548`), so
    subsequent transient stage flips skip the persist. But the FIRST write of a
    page always pays the manifest registration (it is not yet in
    `manifest.pages`). VERIFIED.
- NO queued/admission emission exists. The manual tap
  (`ReaderViewModel.translateSinglePage`, `eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:2202-2320`)
  does no UI state update at all; it ends in
  `translationScheduler.translatePage(...)` (line 2282/2307/2317), which also
  performs no store write at admission
  (`scheduling/TranslationScheduler.kt:658-702`). The pipeline's
  `TranslationStageListener` events are NOT wired on the manual path —
  `TranslationScheduler.kt:702` calls
  `executor.translateSinglePage(manga, chapter, source, pageKey, force = force)`
  with no listener. VERIFIED. (Listeners exist only for auto —
  `RollingAutoCoordinator.kt:505,913,928` — and batch uses tracker calls
  directly.) Consequently the reader pill vocabulary has no reachable manual
  Queued: `PageTranslation.toReaderPageFeedback()` can never return `Queued`
  (`ui/reader/viewer/ReaderTranslationFeedback.kt:78-87`, `else -> null`). VERIFIED.

**Propagation.**

- Per-holder channel: `ReaderViewModel.observePageView(page)`
  (`ReaderViewModel.kt:2846-2933`):
  - COLD flow; store resolution
    (`openOrCreateActiveChapterTranslationStoreSuspend`, incl. legacy artifact
    migration with SAF I/O) runs inside `flow { }` on `Dispatchers.IO`
    (lines 2873-2884; the ANR-fix comment at 2866-2872 documents the migration
    I/O). First emission therefore waits for store open + migration. VERIFIED.
  - Source is `store.display` (committed-pointer projection:
    `_display`, conflated `MutableStateFlow`, `ChapterTranslationStore.kt:146,170`;
    snapshot resolves committed bundle else live candidate,
    `displaySnapshotLocked`, lines 2201-2209)
    mapped to the page key with `.distinctUntilChanged()` (2904-2908).
    `PageTranslation` is a data class, so distinct stage flips pass; identical
    rewrites are deduped. VERIFIED.
  - `.flowOn(Dispatchers.IO)` then `.onEach { page.translation = updated; ... }`
    on the collector context (2910-2930), then
    `.map { it.toPageView() }.distinctUntilChanged()` (2931-2932). Stage
    granularity survives this dedup only because `PageView.lifecycle` carries
    `PageLifecycle.Running(stage)` (`model/PageView.kt:35-37`,
    `model/PageTranslationState.kt:191-194`). VERIFIED.
- Holder collection (Main.immediate):
  - Pager: `holderScope = SupervisorJob() + Dispatchers.Main.immediate`
    (`ui/reader/viewer/pager/PagerPageHolder.kt:105,176-181`); collectors
    (re)installed in `onAttachedToWindow`; `observePageView(page).onEach { refreshTranslation() }`
    (lines 226-228). VERIFIED.
  - Webtoon: same shape, plus a bind fence
    (`ui/reader/viewer/webtoon/WebtoonPageHolder.kt:284-288`). VERIFIED.
- `refreshTranslation` → `syncTranslationFeedback`
  (`PagerPageHolder.kt:446-497, 246-280`): joins
  (1) manual scheduler outcome `manualSinglePageOutcome` (identity-fenced,
  `ReaderTranslationFeedback.kt:122-152`),
  (2) durable `page.translation.toReaderPageFeedback()`,
  (3) auto slot `autoFeedbackState` — precedence in
  `selectReaderPageFeedback` (`ReaderTranslationFeedback.kt:95-104`):
  durable-active > durable-terminal(Translated/Failed) > auto > durable. VERIFIED.
- Final render hop with deliberate throttling — `ReaderPageImageView.showTranslationFeedback`
  (`ui/reader/viewer/ReaderPageImageView.kt:618-630`) feeds
  `ReaderTranslationFeedbackCoalescer` (`ReaderTranslationFeedback.kt:155-268`):
  - `DEFAULT_MINIMUM_DISPLAY_DURATION_MS = 120L` (line 266): after the first
    displayed stage, every subsequent non-terminal stage goes to `pending`
    (lines 223-231) and is only flushed after 120 ms via
    `pendingDelayMs`/`flush` (234-245) scheduled by `postDelayed`
    (`ReaderPageImageView.kt:655-666`). VERIFIED.
  - Monotonic stage rank; `nextRank <= highestStageRank` is DROPPED
    (line 220, ranks at 270-282). VERIFIED.
  - Terminal (Translated/Failed) bypasses coalescing and latches
    `terminalDisplayed`, blocking all later stage updates until
    `beginAttempt()` (lines 193, 208-214, 632-640). VERIFIED.
  - Translated pill auto-hides after 900 ms
    (`ReaderPageImageView.kt:718-726, 1178`). VERIFIED.
- Chapter-level sibling channel (seeds pill for (re)bound holders):
  `observeLiveTranslationStore` collects `store.state` on `launchIO`
  (`ReaderViewModel.kt:2719-2742`), sets `readerPage.translation = resolvedDisplay`
  (committed-first, line 2802) for every page on EVERY store emission, and
  pushes VM state only when translated-count or running-page-index changed
  (2819-2838). Holder init/bind seeds the pill from this durable snapshot
  (`PagerPageHolder.kt:152-162`; `WebtoonPageHolder.kt:247-260`). VERIFIED.
- The image-swap refresh path: `observePageView`'s onEach sends
  `Event.RefreshTranslationPages` only when `tier2Finished || wantsToShowOverlay`
  (2920-2929) → `eventChannel` (`Channel<Event>()`, UNLIMITED,
  `ReaderViewModel.kt:372-373`) → `ReaderActivity` collector
  (`ReaderActivity.kt:247-249`) → viewer refresh restricted to ATTACHED holders
  whose page is in the set (`viewer/pager/PagerViewer.kt:375-397`;
  `viewer/webtoon/WebtoonViewer.kt:417-440`). VERIFIED.

### B. AUTO path (rolling window)

**Production — two simultaneous vocabularies.**

1. Slot events (fast, in-memory): pipeline stage callbacks
   (`TranslationStageEvent.READING/CLEANING/TRANSLATING/RENDERING`, emitted at
   `SinglePageOnnxPhase.kt:469,492,532,607,1097`; `SinglePageHttpRenderPhase.kt:400,561,642`)
   → `RollingAutoCoordinator.stageListenerFor` maps them to
   `AutoSlotState` and republishes the snapshot
   (`scheduling/RollingAutoCoordinator.kt:1289-1309`). VERIFIED.
2. Durable writes: `TranslationScheduler.markAutoPageStarting` flips
   `ocrStatus = RUNNING` right before the executor call
   (`TranslationScheduler.kt:390, 589-625`) — the same store channel as the
   manual path. VERIFIED.

**Propagation.**

- Coordinator trigger is a CONFLATED channel (`RollingAutoCoordinator.kt:189-192`);
  snapshot state is a conflated `MutableStateFlow` (`:105-106`). VERIFIED.
- Manager passthrough: `TranslationManager.autoSnapshot` = `scheduler.autoSnapshot`
  (`eu/kanade/translation/TranslationManager.kt:1602-1603`;
  `TranslationScheduler.kt:156-165`). VERIFIED.
- `ReaderViewModel.observeAutoSnapshot` (1415-1485): collects on viewModelScope
  (main), identity + owner/window-version fencing, drops snapshots whose
  `visiblePageIndex` mismatches (1438-1440), writes into the conflated VM
  `MutableStateFlow state` (1469-1482). VERIFIED.
- Projection: `state.map { it.autoTranslation }.distinctUntilChanged().stateIn(WhileSubscribed(5_000))`
  = `autoTranslationUiState` (`ReaderViewModel.kt:177-185`); mapping
  `AutoSlotState → ReaderAutoTranslationSlotState` is 1:1
  (`ui/reader/ReaderAutoTranslationUiState.kt:181-190, 106-131`). VERIFIED.
- Holders: `autoTranslationUiState.onEach` → `autoFeedbackState` →
  `syncTranslationFeedback()` (`PagerPageHolder.kt:204-217`;
  `WebtoonPageHolder.kt:262-272`). VERIFIED.
- App-bar rail: `AutoTranslationStatus` consumes the same StateFlow
  (`presentation/reader/appbars/AutoTranslationStatus.kt:63-137`;
  slot color dots at 149-157; wired in `BottomReaderBar.kt:62-66` and
  `ReaderAppBars.kt:216`). VERIFIED.

**Trigger chain (fixed delay).**

- `loadChapter` landing kick (1089-1099) and `onPageSelected` (1240-1253) →
  `handleAutoTranslation(page)`:
  ```kotlin
  autoTranslationScrollJob?.cancel()
  autoTranslationScrollJob = viewModelScope.launchIO {
      kotlinx.coroutines.delay(150L)          // ReaderViewModel.kt:1275
      ...
      handleAutoTranslationOnIo(currentPage)  // updateAutoWindow + reconcile
  }
  ```
  VERIFIED. Every page change cancels and restarts the 150 ms timer. DERIVED:
  rapid scrolling repeatedly resets it, deferring the whole auto window
  (including the Queued slot) indefinitely.

### C. BATCH path

**Production — typed event channel + reducer, plus the same store writes.**

- Lane workers emit per-phase events: `BatchLaneWorkers.kt:471,479,595,639,954,969`
  (OCR/inpaint/translate running/done), `BatchRenderJoin.kt:145,194,310`
  (render). VERIFIED.
- `TranslationBatchProgressTracker`:
  `Channel<TranslationBatchEvent>(UNLIMITED)` (line 39) → serialized reducer
  coroutine on `storeScope` = `SupervisorJob() + Dispatchers.IO`
  (lines 56-73; scope from `TranslationManager.kt:320, 1662-1668`) → conflated
  `_snapshot` StateFlow (54-55). `snapshotFor` merges event phases ONTO
  `store.state.value` (177-226). VERIFIED.
- This is the ONLY producer of `TranslationProgressStage`
  (QUEUED/OCR/INPAINT/TRANSLATE/RENDER/DONE/FAILED,
  `model/TranslationProgressSnapshot.kt:227-238`) via `progressStage`
  (`pipeline/batch/TranslationBatchProgressTracker.kt:482-500`). VERIFIED
  (grep: referenced only by tracker, snapshot model, sheet, truth mapper).

**Propagation.**

- `BatchProgressProjector.observeBatchProgress`
  (`manager/BatchProgressProjector.kt:179-202`):
  `pendingTranslationRequests` + queue-entry `statusFlow` → `flatMapLatest` →
  live `tracker.snapshot` if registered, else terminal cache, else
  `combine(store.state, store.display) { snapshotFromStore }` (223-297) →
  `.projectQueueStatus().withQueuePosition().distinctUntilChanged()` (298-303). VERIFIED.
- Manga screen: `MangaScreenModel.observeTranslationProgress`
  (`ui/manga/MangaScreenModel.kt:568-595`): `launchIO` →
  `.distinctUntilChanged()` → `.flowWithLifecycle(lifecycle)` →
  `withUIContext { updateTranslationProgress }` (590-592); snapshots retained
  in a keyed registry so list rebuilds don't erase them (560-566, 604-620). VERIFIED.
- Reader: `observeLiveTranslationStore` also collects
  `translationManager.observeBatchProgress(chapterId)` on `launchIO`
  (`ReaderViewModel.kt:2689-2693`) into `state.translationBatchProgress`. VERIFIED.

**Render.**

- `TranslationProgressSheet` (`presentation/manga/components/TranslationProgressSheet.kt`):
  fraction bar animated with `animateFloatAsState(tween(400ms))` (116-120);
  per-stage cards read `snapshot.perStage[BatchPhase.*]` + `activeStages`
  (`LivePipelineGrid`, 626-679); pulsing status pill (`LiveStatusPill`,
  535-623, 800 ms infinite transition). VERIFIED.
- `ChapterTranslationIndicator` (`presentation/manga/components/ChapterTranslationIndicator.kt:227-314`):
  determinate ring + percent label from `snapshot.fraction`
  (= doneStages/totalStages, `TranslationProgressSnapshot.kt:105`). VERIFIED.
- Reader bottom bar batch line + settings sheet section
  (`BottomReaderBar.kt:68-90`; `presentation/reader/TranslationSettingsSheet.kt:89-114,192-210`;
  state collected at `ReaderActivity.kt:478-480,563`). VERIFIED.

---

## (b) Root causes per symptom

### b.1 LATE APPEARANCE (pill/stage shows up late)

| # | Cause | Evidence | Severity |
|---|-------|----------|----------|
| L1 | **Manual path has no admission signal.** The tap produces zero UI feedback until the pipeline's first durable stage write (`ocrStatus = RUNNING`), which happens after lease admission, decode, and (DERIVED, sched-slice) model load inside the ONNX phase. No Queued state exists in the manual vocabulary. | `ReaderTranslationFeedback.kt:78-87` (no Queued branch); `TranslationScheduler.kt:658-702` (no write at admission; no stage listener); `SinglePageOnnxPhase.kt:515-528` (first write). | HIGH |
| L2 | **First stage emission is gated behind artifact-manifest I/O.** `publishLocked` persists (first-registration manifest write) BEFORE setting `_state`/`_display`. | `ChapterTranslationStore.kt:1883-1889` (persist first), `1943-1969` (mandatory registration for new pages), `1894-1895` (UI publish after). | HIGH |
| L3 | **Holder channel's first emission waits for store open + legacy migration (SAF I/O) on Dispatchers.IO** because `observePageView` resolves the store inside the cold flow. | `ReaderViewModel.kt:2866-2884`. | MEDIUM-HIGH |
| L4 | **Auto path pays a fixed 150 ms debounce on every page change**, and rapid scroll CANCELS and restarts it (`autoTranslationScrollJob?.cancel()`), so during fast scrolling the window (and its Queued slots) never gets submitted. | `ReaderViewModel.kt:1272-1279`. | MEDIUM |
| L5 | **Coalescer adds up to 120 ms delay for every stage after the first** (pending + `postDelayed` flush). | `ReaderTranslationFeedback.kt:223-245`; `ReaderPageImageView.kt:655-666`. | LOW-MEDIUM (by design) |
| L6 | **First-registration cost on the store open path** also delays the chapter-level seed collector (`observeLiveTranslationStore` opens the same store before collecting, 2706-2717), so freshly bound holders can show NO pill even though work is RUNNING. | `ReaderViewModel.kt:2706-2717`; seed at `PagerPageHolder.kt:162` reading possibly-null `page.translation` (VERIFIED that null → no pill, `ReaderTranslationFeedback.kt:19-27,87`). | MEDIUM |

Net: on the manual path the user sees a dead page from tap → first stage write
(typically dominated by lease wait + decode + ONNX model warm-up, per sched
slice), then suddenly "Reading text". On auto, add the 150 ms debounce. On
batch, the request acknowledgement is immediate (`requestState`,
`BatchProgressProjector.kt:183-199`; rendered by `BottomReaderBar.kt:71-83`
and sheet pill 598-611) — batch is the BEST of the three here.

### b.2 SKIPPED STAGES

| # | Cause | Evidence | Severity |
|---|-------|----------|----------|
| S1 | **Coalescer minimum-display policy silently drops fast stages.** A stage displayed < 120 ms ago keeps the slot `pending`; if the next stage arrives before the flush, the pending value is OVERWRITTEN (single `pending` field) and the intermediate stage is never rendered. This is the primary "misses some stages" mechanism on the auto path. | `ReaderTranslationFeedback.kt:158-160, 223-245`. | HIGH |
| S2 | **Stage-precedence collapse when stages overlap.** All projections pick ONE running stage by fixed priority render > inpaint > translate > ocr; a page that is e.g. translating while its render join starts shows only "Finishing"/RENDER — TRANSLATE never displayed for that page. | `PageTranslationState.kt:191-194` (lifecycle), `ReaderTranslationFeedback.kt:81-84` (toReaderPageFeedback), `TranslationBatchProgressTracker.kt:495-498` (progressStage). | MEDIUM |
| S3 | **StateFlow conflation at every hop** (store `_state`/`_display`, tracker `_snapshot`, manager `autoSnapshot`, VM `state`, `autoTranslationUiState`) means intermediate stage values can be coalesced away whenever producers outrun collection. Plus the chapter-level collector only pushes Compose state on progress/index change (2826-2838), and `autoTranslationUiState` is `distinctUntilChanged` (180). | `ChapterTranslationStore.kt:117,146`; `TranslationBatchProgressTracker.kt:54-55`; `RollingAutoCoordinator.kt:105`; `ReaderViewModel.kt:173-185`. | MEDIUM (inherent to StateFlow UI) |
| S4 | **Terminal latch + committed-first precedence suppress refresh animations.** Once Translated/Failed is displayed, `terminalDisplayed` blocks stage updates; and for a committed page under retry, both `store.display` and the chapter collector resolve to the COMMITTED bundle, whose statuses are terminal — so `toReaderPageFeedback()` returns `Translated` and NO refresh stage ever renders, even though `PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT` exists. | `ReaderTranslationFeedback.kt:193, 208-214, 95-104`; `ChapterTranslationStore.kt:2201-2209, 2227-2229`; `ReaderViewModel.kt:2766, 2802`; `PageDisplayProjection.kt:89-101` (state computed but unused by the pill). | MEDIUM-HIGH |
| S5 | **Manual-path rank gate drops regressions silently** — correct for stale callbacks, but combined with S1 any out-of-order/late emission is dropped without rendering. | `ReaderTranslationFeedback.kt:216-221`. | LOW |
| S6 | **Duplicate-tap dedup gives no feedback**: `translatePage` returns early for an already-active job (no UI change), so a second tap shows nothing rather than "already running". | `TranslationScheduler.kt:667-671`. | LOW |

### b.3 INCONSISTENCY across paths / surfaces

| # | Cause | Evidence | Severity |
|---|-------|----------|----------|
| I1 | **Three production vocabularies for the same concept**: `AutoSlotState`/`ReaderAutoTranslationSlotState` (8 states, event-pushed), durable `StageStatus` quartet on `PageTranslation` (store-poll semantics, no Queued), `TranslationProgressStage`+`BatchPhase` (batch tracker only). Plus display-side `PageDisplayState` (7) and `PageView.OverlayState` (3). The pill can therefore show Queued for auto pages but never for manual pages; per-stage COUNTS exist only in the batch snapshot; the sheet shows OCR/AI/Inpaint/Render cards while the pill shows Reading/Cleaning/Translating/Finishing — same pipeline, two labels. | `ReaderAutoTranslationUiState.kt:90-99`; `ReaderTranslationFeedback.kt:27-44, 309-321`; `TranslationProgressSnapshot.kt:227-238`; `PageView.kt:11-15`; sheet strings `TranslationProgressSheet.kt:975-988`. | HIGH |
| I2 | **Different transport per path**: auto = push events via stage listener → coordinator → 3 chained StateFlows; manual = no events, purely StateFlow emission of durable writes (and per-stage labels derived post-hoc); batch = UNLIMITED channel + reducer coroutine on IO + projector combine. Latency and granularity therefore differ per path by construction. | Sections (a).A/B/C. | HIGH |
| I3 | **Restore/rebind behavior differs from live behavior.** A page whose holder is recreated mid-run seeds its pill from `page.translation` (chapter-collector value). If the collector hasn't emitted yet (fresh chapter open, IO store open), the pill is absent; once the store opens and the page has a committed bundle, the pill jumps straight to "Translated" with no stage animation. `Event.RefreshTranslationPages` also refreshes only ATTACHED holders, so offscreen pages rely wholly on rebind seeding. | `PagerPageHolder.kt:152-162`; `WebtoonPageHolder.kt:247-260`; `PagerViewer.kt:385-397`; `WebtoonViewer.kt:420-440`; `ReaderViewModel.kt:2911-2929`. | MEDIUM |
| I4 | **Pill source flip-flop**: `selectReaderPageFeedback` prefers the durable attempt while active and auto slots otherwise; during overlap (auto window + manual tap on the same page) the pill can switch between vocabularies mid-run (e.g. auto "Cleaning" → durable "Translating" → auto "Queued" on a different page's emission), which reads as erratic animation. | `ReaderTranslationFeedback.kt:95-104`; `PagerPageHolder.kt:246-280`. | MEDIUM |
| I5 | **Pager vs webtoon seeding divergence**: pager seeds at init from durable state and keeps `autoFeedbackState` across re-attach (cleared only on detach, 286-296); webtoon clears `autoFeedbackState` on every bind (257) — the same scenario can render different pills per viewer. | `PagerPageHolder.kt:131-163, 286-296`; `WebtoonPageHolder.kt:247-260`. | LOW |
| I6 | **Batch sheet/indicator fraction semantics differ from pill stages**: sheet fraction = doneStages/totalStages across 5 BatchPhases (`TranslationProgressSnapshot.kt:105, 413`), indicator percent = same fraction, pill = single stage — a page mid-OCR shows ring progress > 0 from OTHER pages, which users read as the current page's animation. DERIVED from the shared snapshot. | `TranslationProgressTracker.kt:403-413`; `ChapterTranslationIndicator.kt:251-262, 300-312`. | LOW-MEDIUM |

**Fixed delays / cadence inventory (all VERIFIED):**

- 150 ms auto-window debounce: `ReaderViewModel.kt:1275`.
- 120 ms pill stage minimum display: `ReaderTranslationFeedback.kt:266`.
- 900 ms Translated pill auto-hide: `ReaderPageImageView.kt:1178`.
- 250 ms scrim fade in/out: `ReaderPageImageView.kt:674, 693-697`.
- 800 ms sheet pulse loop: `TranslationProgressSheet.kt:551-560`.
- 400 ms fraction tween: `TranslationProgressSheet.kt:116-120`.
- 250 ms persistence debounce (affects durability, not emission — store
  StateFlows publish immediately in `publishLocked`): `store/StorePersistenceScheduler.kt:33,149`.
- No polling loops drive any stage UI; everything is event/StateFlow pushed
  (only legacy `ChapterTranslator.kt:462` claim-retry loop and provider
  retry backoffs exist upstream). VERIFIED.

**Suppression during scroll / memory pressure:**

- Fast scroll: the auto debounce cancel/restart (L4/S6 above) defers auto
  stages; pager/webtoon holders are detached/recycled by the RecyclerView and
  their collectors cancelled (`PagerPageHolder.kt:285-296`;
  `WebtoonPageHolder.kt:305-335`), so a page scrolled past loses its pill
  until rebind seed. VERIFIED.
- `onPause` cancels ALL translation (`ReaderActivity.kt:266-274`) — pill
  stages stop by design in background.
- Manga-screen batch updates are lifecycle-gated
  (`flowWithLifecycle`, `MangaScreenModel.kt:590`) — dropped while the screen
  is stopped, latest snapshot re-delivered on resume. VERIFIED.
- No memory-pressure-specific stage suppression found in the UI layer. VERIFIED
  (absence of evidence within `ui/reader`, `presentation/reader`).

---

## (c) Ranked recommendations (impact / risk)

1. **Emit a Queued/admission state for manual and batch pages before any
   work starts** (highest impact, low risk).
   - Manual: on `translateSinglePage` acceptance, write a placeholder
     (`ocrStatus = PENDING`) or extend `manualOutcomes` with a
     `SinglePageOutcome.Admitted` so the pill shows "Queued" immediately;
     map it in `toReaderPageFeedback`/`selectReaderPageFeedback`.
     Anchor points: `ReaderViewModel.kt:2202-2320`,
     `TranslationScheduler.kt:658-702`, `ReaderTranslationFeedback.kt:78-87`.
   - Auto already emits Queued from the coordinator; the debounce (item 3)
     delays it, not the vocabulary.

2. **Publish the store StateFlows before (or independently of) durable
   artifact I/O for transient stage updates** (high impact, medium risk).
   In `publishLocked` (`ChapterTranslationStore.kt:1876-1897`), set
   `_state`/`_display` before `persistArtifactMutationLocked` for
   non-durable updates (`shouldPersistUpdate == false` — placeholders have no
   committed content, so no reader can reference unpersisted files); keep the
   current order for durable results. This removes the manifest-write latency
   from the FIRST visible stage.

3. **Replace the 150 ms auto debounce with a small, non-resetting policy**
   (medium impact, low risk). Keep a short delay only for chapter changes;
   while a valid window exists for the same chapter, forward page changes
   immediately (the coordinator trigger is already CONFLATED and built for
   rapid navigation, `RollingAutoCoordinator.kt:189-199`). Anchor:
   `ReaderViewModel.kt:1265-1279`.

4. **Render a REFRESHING truth on committed pages under retry** (medium
   impact, low risk). The projection already computes
   `PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT`
   (`PageDisplayProjection.kt:93`) but `toReaderPageFeedback` and the
   coalescer terminal latch never surface it
   (`ReaderTranslationFeedback.kt:78-87, 193`). Feed the display-state into
   the pill join so refreshes animate instead of showing stale "Translated".

5. **Unify the stage vocabulary across paths** (high impact long-term,
   medium effort). One enum (Queued/Reading/Cleaning/Translating/Finishing/
   Done/Failed/Deferred) produced once per page (store write or stage
   listener) and mapped at the edges (pill labels, sheet cards, indicator).
   This deletes the `selectReaderPageFeedback` source-flip class of bugs
   (I4) and the manual/auto label mismatch (I1). Interim cheaper option:
   reuse `TranslationProgressStage` in `toReaderPageFeedback` so manual and
   batch share names.

6. **Keep the 120 ms minimum-display policy but never overwrite a pending
   stage without rendering it when it is the LAST transition before a gap**
   — practically: when a new stage arrives while `pending` is occupied,
   flush the pending one immediately if its remaining delay is < 1 frame
   (≤16 ms). Prevents S1's dropped middle stages without changing feel.
   Anchor: `ReaderTranslationFeedback.kt:223-245`.

7. **Give duplicate-tap and already-running cases explicit feedback**
   (low impact, low risk): on the `translatePage` dedup early-return
   (`TranslationScheduler.kt:667-671`), still emit the running truth so the
   button/pill reflect "already translating" instead of a silent no-op.

8. **Optional: seed holders from the store synchronously at bind** by making
   the manager expose a non-suspending `peekStore(chapterId)` for the seed
   path (registry hit only), so a rebound holder shows the current stage
   immediately instead of waiting for the IO store open (L6/I3). Low risk —
   read-only registry lookup with graceful miss.

### Cross-check for the io/sched slices

- The UI-visible "first stage" is the first `updatePageGuarded` accept; its
  latency is dominated by whatever precedes it in the ONNX phase (lease wait,
  decode, model warm-up) — sched slice owns that quantification.
- Every durable stage write also performs (or schedules) artifact I/O inside
  the store mutex (`publishLocked` → `persistArtifactMutationLocked`); the io
  slice should treat `publishLocked` as a per-stage-write I/O checkpoint in
  the pipeline cost model.
