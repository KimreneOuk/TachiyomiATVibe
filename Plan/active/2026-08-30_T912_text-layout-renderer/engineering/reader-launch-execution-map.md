# T912 support investigation — Reader-launch execution map and ANR root cause

Worktree: `t912-investigation-wt` (branch `codex/text-layout-renderer`). Read-only investigation.
Evidence fixtures: `engineering/fixtures/anr-evidence/{anr_all.txt,full_log.txt}` (4 ANR traces, same blocked stack; 1 logcat reproduction).
Line numbers below are worktree HEAD. The device build's stack shows slightly older line numbers in the
same files (e.g. TranslationManager.kt:616/543/564 vs 931/855/879) — same functions, chain identical.

## 1. Execution timeline

### BEFORE — user taps chapter → VM init (mostly IO thread)

| Step | Where | Thread |
|---|---|---|
| ReaderActivity.onCreate; inflate; `viewModel.init(manga, chapter)` inside `lifecycleScope.launchNonCancellable` | ReaderActivity.kt:156, 174-192 | launched on Main, immediately hops to IO |
| `init` wraps body in `withIOContext`; loads manga row, waits `sourceManager.isInitialized`, builds ChapterLoader | ReaderViewModel.kt:912-924 | IO |
| `chapterList` lazy init: `runBlocking { getChaptersByMangaId.await(...) }` + filter (incl. `isChapterDownloaded` disk checks per chapter) | ReaderViewModel.kt:465-467, 472-504 | IO (documented main-only prohibition, ReaderViewModel.kt:459-463) |
| `loadChapter(loader, chapter)` — cancels previous chapter's translation work | ReaderViewModel.kt:926, 956-958 → ReaderViewModel.kt:1952; manager side runBlocking at TranslationManager.kt:832-839 | IO |
| `loader.loadChapter(chapter)` — page list from download dir / network (disk I/O) | ReaderViewModel.kt:960 | IO |

### DURING — reader open → first page visible. **THE ANR IS HERE**

| Step | Where | Thread |
|---|---|---|
| Build `ViewerChapters` (curr/prev/next) | ReaderViewModel.kt:962-967 | IO |
| `withUIContext { mutableState.update { ... } }` — `withUIContext` = `withContext(Dispatchers.Main)` | ReaderViewModel.kt:969-994; core/common/src/main/kotlin/tachiyomi/core/common/util/lang/CoroutinesExtensions.kt:55-58 | **Main** |
| Inside that Main block: `cancelQueuedDownloads` | ReaderViewModel.kt:975 | Main |
| **`translationManager.getChapterTranslationStatus(...)`** | ReaderViewModel.kt:977-986 (call at 979) | **Main — blocking** |
| → `progressProjector.getChapterTranslationStatus` | TranslationManager.kt:873-885 → 846-871 → BatchProgressProjector.kt:118-138 | Main |
| → queue check `getQueuedTranslationOrNull` (in-memory) and active-store shortcut `activeStores.get` + `store.display.value` (in-memory) | BatchProgressProjector.kt:125-135 | Main, cheap |
| → **`persistedChapterStatus(...)`** | BatchProgressProjector.kt:136 → TranslationManager.kt:924-931 → 916-922 | Main |
| → **`runBlocking(Dispatchers.IO) { resolveDurableChapterStatus(...) }`** — parks main | DurableChapterStatusResolver.kt:88-97 (cache miss at :88, runBlocking at :89) | **Main parks; work on IO** |
| → `findTranslationDocument`: `provider.findTranslationFile` = 3 chained `UniFile.findFile` (translations root → source dir → manga dir → chapter `.json`), each a SAF/FUSE directory listing | DurableChapterStatusResolver.kt:132-142; TranslationProvider.kt:67-70, 81-84 | IO worker (binder IPC each) |
| → `probeArtifactManifest(parent, fileName)` — another `findFile` + manifest read | DurableChapterStatusResolver.kt:115; ChapterTranslationStore.kt:1997-1998; ChapterArtifactManifestReader.kt:37-41 | IO worker |
| → `withProbeStore` → `getOrCreateProbe` (no probe cached on first entry) → `ChapterTranslationStore.openArtifact(parent, fileName)` | DurableChapterStatusResolver.kt:165-190 (open at 174-178); ActiveChapterStoreRegistry.kt:122-140 | IO worker |
| → `openInternal`: read legacy `.json` bytes + SHA-256 identity; `findFile` manifest/companion `_images`/glossary; glossary read; `loadOrMigrate`; **per-page `readPageSnapshot` for every committed record (line 220) and every candidate record (line 227)**; optional `verifyLegacyArtifactHealth` image probes | LegacyChapterMigrationSource.kt:34-108 (85), 128-240 (149, 159-174, 176, 194-217, **218-223, 224-231**), 267-295 | IO worker — **the >5 s work** |
| → `artifactStatus()` on the opened store — CPU-only over in-memory `state`/`display`/manifest | StoreStatusProjector.kt:57-125 | IO worker |
| → probe released and **closed** (`releaseProbe` + `closeAndFlush`) | DurableChapterStatusResolver.kt:183-189; ActiveChapterStoreRegistry.kt:147-153 | IO worker |
| result cached (only non-null), state returned → `mutableState.update` publishes `viewerChapters` | DurableChapterStatusResolver.kt:101; ReaderViewModel.kt:988-993 | Main (resumes) |
| Meanwhile ReaderActivity collectors fire: `updateViewer()` creates Pager/Webtoon viewer + adapter, adds view | ReaderActivity.kt:209-221, 707-731 | Main — **queued behind the block above** |

For the 68-page translated chapter the resolution reads, over MediaProvider/FUSE: 1 legacy json (if present)
+ manifest + glossary + companion-dir lookups + **68 committed snapshots + 68 candidate snapshots**
(LegacyChapterMigrationSource.kt:218-231 reads the candidate even when a committed snapshot exists),
each `readPageSnapshot` doing `findFile` → `ContentResolver.query` binder round-trips
(ChapterArtifactStore.kt:1163 → ChapterDocumentIo.kt:73/95).

### AFTER — first page visible, page turns, background work

| Step | Where | Thread |
|---|---|---|
| `observeLiveTranslationStore()`: collects `observeBatchProgress` (IO); opens store via **suspend** `openOrCreateActiveChapterTranslationStoreSuspend` — comment at 2617-2619 shows a previous ANR of this same shape was already fixed here; collects `store.state` per page | ReaderViewModel.kt:2587-2627 (2603-2607, 2620-2627), 2642-2726 | IO |
| `observeTranslationState()`: collects manager `statusFlow()` | ReaderViewModel.kt:2486-2507 | IO (viewModelScope.launchIO? — collector body is light) |
| Auto-translate kick for landing page (`handleAutoTranslation` → `handleAutoTranslationOnIo`) | ReaderViewModel.kt:1009-1019, 1190-1197 | IO |
| Page turns: holders `setImage` → `setTranslationBlocks` → `onImageLoaded` → `TranslationOverlayView.bind` → **`TextLayoutPlanner.plan` + `prepareLayouts` synchronous on the calling (main/UI) thread**; pager keeps ~current±1 holders, webtoon keeps visible+prefetch holders — bounded CPU, no file I/O | PagerPageHolder.kt:351, 465; WebtoonPageHolder.kt:363, 479; ReaderPageImageView.kt:130-157; TranslationOverlayView.kt:67-112 | Main (CPU only) |
| **Every `translator.queueState` emission clears the whole durableStatusCache** — one batch/page status change anywhere re-arms the next full durable reopen | TranslationManager.kt:236-238 → DurableChapterStatusResolver.kt:68-70 | applicationScope (IO) |
| `observeChapterTranslationStatus` (BatchProgressProjector.kt:140-165) also calls the blocking `getChapterTranslationStatus` inside `combine` — currently no production caller, but it is a trap if collected on Main | BatchProgressProjector.kt:155, 163 | collector's thread |
| Manga details screen does the same blocking call **per downloaded chapter** while building the chapter list on Main | MangaScreenModel.kt:676-700 (call at 685) | Main — same ANR class, amplified by chapter count |
| T911 `reconstructDurableTerminalSnapshot` re-enters `persistedChapterStatus` per progress observation | TranslationManager.kt:945-958 (call at 952) | IO (via ReaderViewModel.kt:2603) |

## 2. Thread/blocking map — main-thread blocking points in the launch path

| # | Blocking point | file:line | Waits on | Scales with |
|---|---|---|---|---|
| 1 | **`runBlocking(Dispatchers.IO)` around `resolveDurableChapterStatus`** (the ANR) | DurableChapterStatusResolver.kt:89, entered from ReaderViewModel.kt:979 via BatchProgressProjector.kt:136 | Full durable resolution: 3+ SAF `findFile` listings, manifest probe, probe-store `openArtifact` = manifest + glossary + **2 page-snapshot reads per page** (committed + candidate) + optional health-verify image probes, each snapshot read a `findFile` + `ContentResolver.query` binder IPC (anr_all.txt:310-343) | **O(pages)** binder round-trips ≈ 60–130 ms per page observed (full_log.txt:574-758, pages 009-032 in ~1.9 s) ⇒ 68 pages ≈ 5-9 s cold; +O(chapters) for manga-dir listings |
| 2 | `chapterList` lazy `runBlocking { getChaptersByMangaId.await }` + per-chapter `isChapterDownloaded` | ReaderViewModel.kt:465-504 | RoomDB query + download-dir disk checks | O(chapters); IO-only today (documented) |
| 3 | `runBlocking(Dispatchers.IO)` in `setMangaReadingMode` (DB write + state update) | ReaderViewModel.kt:1628-1654 | Room write | O(1); not on entry path (bottom-sheet action only) |
| 4 | `cancelRunningChapterForReplace` double `runBlocking` | TranslationManager.kt:832-839 | scheduler/store ops | O(1); replace path (T913-adjacent area) |
| 5 | `TextLayoutPlanner.plan` + path preparation inside overlay bind | TranslationOverlayView.kt:72-73 via ReaderPageImageView.kt:148-153 | CPU only (layout + Path build) | O(blocks) × ~3 bound holders; not the ANR |
| 6 | Manga screen `getChapterTranslationStatus` per downloaded chapter | MangaScreenModel.kt:685 | same as #1 | O(downloaded chapters) × O(pages) |

Cache behavior (why #1 is not amortized):

- `durableStatusCache` is in-memory only (TranslationManager.kt:174) — cold on every process start. Device trace: `Process uptime: 8s` (anr_all.txt:66) ⇒ guaranteed miss on first entry.
- Only non-null results are cached (DurableChapterStatusResolver.kt:98-101).
- The whole map is cleared on every `queueState` emission (TranslationManager.kt:236-238) — the logcat shows a restored queue entry (`full_log.txt:778`), so queue traffic can wipe entries repeatedly.
- The probe store is closed after each durable resolution (DurableChapterStatusResolver.kt:186-188), so even repeated misses pay the full reopen; only the state cache prevents reopening, and (1)-(3) defeat it.

## 3. Root cause — confirmation of the ANR chain

Confirmed, with the device-side I/O captured red-handed:

- Main thread (tid=1) parked in `runBlocking` at DurableChapterStatusResolver.kt:89, entered exactly via
  ReaderViewModel.kt:979 → TranslationManager.getChapterTranslationStatus → BatchProgressProjector.kt:136
  (anr_all.txt:77-106; all 4 traces identical; ANR subject "Waited 5000ms for FocusEvent" at :52, :21163, :38531).
- The coroutine main was waiting on was caught mid-flight on `DefaultDispatcher-worker-1`
  (anr_all.txt:174-343): `LegacyChapterMigrationSource.migrateArtifactManifest` → `readPageSnapshot`
  (ChapterArtifactStore.kt:1163) → `UniFileChapterDocumentIo.resolve/read` → `TreeDocumentFile.findFile`
  → `ContentResolver.query` → binder ioctl. Later traces repeat inside `migrateArtifactManifest`
  (anr_all.txt:21416, :38702, :52374). One worker had already burned ~1.1 s CPU at 8 s uptime (schedstat :177).
- I/O volume for the 68-page chapter: manifest + glossary + companion lookups + **68 committed + 68
  candidate snapshot reads** (LegacyChapterMigrationSource.kt:218-231), each snapshot a FUSE open +
  directory-resolve binder IPC; logcat paces them at ~60–130 ms per candidate (full_log.txt:574-758) ⇒
  multi-second total, exceeding the 5 s input-dispatch budget with main parked.
- **Predates T912.** `git log -S "translationManager.getChapterTranslationStatus"` on ReaderViewModel.kt →
  only `8031fab` (initial commit). The runBlocking durable resolver arrived via the T909 phase-13 move
  (`48e5cf7`); T911 slices (`66fa2c9`, `18e9f24`) added the projector/reconstruction around it. T912 commits
  (`b994bc6`, `e992d74`, `45c8f03`, …) touched only TextLayoutPlanner.kt, TranslationOverlayView.kt,
  MaskTextRegionPlanner.kt, MaskGeometry.kt + tests/docs.
- **Renderer not involved at chapter-entry time.** TextLayoutPlanner/TranslationOverlayView appear in none
  of the 4 main stacks; main never returned from `loadChapter`'s status query, so no page was ever bound —
  the overlay path (TranslationOverlayView.kt:67) was never reached. T912 is exonerated for this ANR.
- Precedent: the same bug class was already fixed once at the adjacent call site — ReaderViewModel.kt:2617-2619
  ("ANR fix: suspend variant … must not runBlocking") for `openOrCreateActiveChapterTranslationStoreSuspend`.
  The status query at :979 is the surviving instance of that pattern.

## 4. Fix options (ranked, smallest safe first)

**A. Convert the durable-status chain to suspend and hoist the call out of the Main block (recommended).**
- DurableChapterStatusResolver.kt:89-97: drop `runBlocking(Dispatchers.IO)`; call
  `resolveDurableChapterStatus(...)` directly; mark `persistedChapterStatus` suspend (resolver already
  has suspend members, `withProbeStore`).
- Propagate suspend: TranslationManager.kt:924-931 (`persistedChapterStatus`), :873-885
  (`getChapterTranslationStatus`), :902-908 (`isChapterTranslated`), and BatchProgressProjector.kt:118-138.
  In-memory steps (queue check :125, active-store shortcut :127-135) stay sync inside the suspend body.
- ReaderViewModel.kt:969-994: compute `translationStatus` before `withUIContext` (the caller contexts are
  IO: init via withIOContext :914, loadNewChapter via launchIO :1031, loadAdjacent via withIOContext :1058),
  then only assign it inside the Main update block.
- MangaScreenModel.kt:685: precompute statuses for downloaded chapters inside the existing IO flow
  (or make `toChapterListItems` suspend) — same fix, second entry point.
- Risk: low-medium — signature churn across 4 files + MangaScreenModel; no behavioral change; the durable
  test suite already exercises resolver/projector seams (lambdas at TranslationManager.kt:846-871 injectable).
- Tests: projector priority test (queue → active store → durable → NOT_TRANSLATED) pinned against the
  suspend API; a Main-dispatcher test asserting no main-thread parking (below).

**B. Tripwire (add on top of A).** In the (now suspend) `persistedChapterStatus`, keep an assertion/log
that the caller context is not Main (or use StrictMode penaltyLog in debug builds) so a future caller
cannot reintroduce the pattern. Risk: negligible. Test: debug-build assertion test.

**C. Perf follow-up: stop paying 2× page-snapshot reads per status probe (store-open cost).**
- LegacyChapterMigrationSource.kt:224-231: skip the candidate `readPageSnapshot` when a committed snapshot
  was already loaded for the page (translated chapters currently read both).
- Consider a "status-only" open that reads the manifest page records without loading snapshots
  (`artifactStatus()` at StoreStatusProjector.kt:57-125 needs `manifest.pages` + state; snapshots are
  needed only for `hasRenderedResult`-style fields, derivable from manifest records where present).
- Alternatively keep the probe store warm in `ActiveChapterStoreRegistry.probeStores` instead of closing
  after each probe (DurableChapterStatusResolver.kt:186-188) — registry is already bounded.
- Risk: medium — touches migration/open semantics guarded by the durable/persistence test suites; do after A.
- Ownership: LegacyChapterMigrationSource.kt / ChapterArtifactStore.kt are shared T909/T911 infrastructure,
  not T912; **no overlap with T913** (`codex/t913-batch-download-logcat` touched only
  `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt` + its plan docs, commit `48b42bc`).
  T913-adjacent surfaces to coordinate on: TranslationManager queue areas — the cache-clearing collector
  (TranslationManager.kt:236-238) and `cancelRunningChapterForReplace` (:827-841) live in queue/download
  publication code; if T913 changes queueState emission cadence, that changes how often option-A's cache is
  cleared. ReaderViewModel.kt:977-994 and the resolver/projector files are free to change on this branch.

**D. (Not recommended alone) serve optimistic state from cache and resolve async.** Returning
NOT_TRANSLATED/stale before the durable answer would show a translated chapter as untranslated and delay
overlay setup; A+C give correct state off-main without a UI lie.

## 5. Verification plan

1. Unit (projector/suspend conversion): in the durable-status tests, inject a `persistedChapterStatus`
   lambda that suspends on a `CompletableDeferred` completed from a background executor; drive
   `TranslationManager.getChapterTranslationStatus` under `Dispatchers.setMain` + `runTest` with the main
   thread replaced by a watchdog — assert completion well under the 5 s ANR budget and that main never
   blocks (with A unfixed this test deadlocks/times out; it pins the regression).
2. Unit: pin `BatchProgressProjector.getChapterTranslationStatus` priority (queue entry → active-store
   displayReady → durable → NOT_TRANSLATED) against the new suspend signature, incl. null-durable results
   not being cached (DurableChapterStatusResolver.kt:98-101).
3. Manual device check (the 68-page chapter, SAF-backed storage as in fixtures):
   - `adb shell am start -W ...ReaderActivity` — report TotalTime before/after (expect launch well under 5 s
     vs the current multi-second freeze).
   - `adb logcat -s MediaProvider` during entry: FUSE opens should no longer gate the first frame (option A
     moves them off main; option C should also cut the candidate-read count roughly in half).
   - Re-enter the chapter after a queue status change (cache-clear path) and confirm no freeze.
   - Zero `data_app_anr` dropbox entries for ReaderActivity across a session of chapter entry/exit.
4. Debug-build StrictMode (`penaltyLog`) catching any residual main-thread disk/SAF access in the launch path.
