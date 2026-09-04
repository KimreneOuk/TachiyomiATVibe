# Root-cause deduction (code-only, continuing T920)

**Date**: 2026-09-03
**Method**: Codebase deduction only, continuing from the T920 investigation (`2026-09-03_T920_reader-entry-stall-and-translation-toggle-investigation`). No device interaction per Director instruction.

## Verdict

Of all root causes identified by T920, exactly one remains live on this branch:
**`TranslationOverlayView.bind` still runs `TextLayoutPlanner.plan` (3,794-line planner: font bisection, line breaking, polygon clipping) plus `prepareLayouts` (Android `Path` construction) synchronously on the Main thread for every page bind**
(`TranslationOverlayView.kt:80-82`). At reader entry the first 2-4 visible holders bind at once, so entry jank is multiplied; it recurs on every scroll page change and is worst under rapid scroll. This is T920 Phase 3 Step 3.1 and it is the remaining work item.

## Already fixed on `optimize_reader_lazy_loading` (verified in code, with commits)

| T920 finding | Status | Evidence |
|---|---|---|
| 58.7s store open (retention sweep on critical path) | Fixed (removal, not deferral) | The background deferral still convoyed: `openStore.migrate`=57,136ms with only ~420ms of real work (22:22 device trace). Commit `722e955` removed the sweep from `loadOrMigrate` entirely. Second sweep path found and removed the same evening: `StorePersistenceScheduler.closeAndFlush` → `reconcileArtifactRetentionLocked` (see addendum). |
| Legacy image header probing on migrated chapters | Fixed | `TranslationManager.openExistingChapterTranslationStore`: `isArtifactAuthoritative` routes to `openArtifact` before the legacy `open(document.file)` branch (commit `5584e0a`) |
| Eager 400x snapshot deserialization | Fixed | `LegacyChapterMigrationSource.openInternal`: `maxEagerSnapshots = 4` (≤8-page chapters: all), rest synthesized + on-demand hydration (commit `e487259`); health verification backgrounded for large chapters (commit `de943a9`) |
| `DownloadManager.buildPageList` per-page SAF IPC | Fixed | Commit `f0d9f46`: one `listFiles()`, name+URI materialized once, in-memory sort/filter (reviewed, tests added) |
| Toggle-off ANR (`runBlocking` on Main) | Fixed | `cancelAutoTranslations`: `fastCancelInFlightStagesInMemory()` in-memory flip, then `scope.launch { markChapterCancelledAsync }` on IO with try/catch; `ReaderViewModel` call sites use `cancelAllPageTranslationsOffMain`; remaining sync sites live only inside `ReaderTeardownCoordinator` on the manager IO scope |
| Warm-window rapid-scroll thrashing | Fixed | `ReaderPageWarmWindow`: dual-radius hysteresis already present — webtoon attach 4 / evict 10; default attach 2 / evict 5 |
| Webtoon initial height collapse to 0 | Fixed | `WebtoonPageHolder.kt:96-97`: `recycler.height.takeIf { it > 0 } ?: displayMetrics.heightPixels` |

## Residual entry-path cost after all fixes (expected, bounded)

One `chapterDir.listFiles()` children query + in-memory descriptor build (f0d9f46), `ChapterLoader` publish, `WebtoonViewer.setChapters` adapter work, first bitmap decode — plus the live Main-thread overlay planning above, which is the only unbounded CPU item left at entry.

## Next implementation slice

Background text-layout planning with a bounded cache (T920 Phase 3.1):
- Never run `TextLayoutPlanner.plan`/`prepareLayouts` on the calling (Main) thread on cache miss; apply prepared layouts on Main when ready and invalidate.
- Bounded LRU cache keyed by (blocks identity, pageWidth, pageHeight).
- Thread-safe measurer or single-threaded planner confinement.
- Preserve the planner's never-drop drawing contract; keep existing overlay draw migration gates green (commits `0365c62`, `f4d8a0b`).

Validation for this slice is code+tests+build only; device timing validation remains deferred until the Director authorizes device access again.

## Addendum (2026-09-03 evening, live device traces): the true stall anatomy

Director authorized device testing (`192.168.100.223:34075`). Stage instrumentation (`ReaderEntryTrace`, commit `5b0e224`) plus a streaming logcat capture produced these findings.

### Root cause #1 — retention sweep in `loadOrMigrate` (the 57-60s stall) — FIXED

Trace proof: `openStore.migrate` = 57,136ms while `migrate.eagerSnapshots` (its only real work) began 2ms after `retentionBackground` ended. The "background deferral" still serialized every open behind the `ChapterArtifactStore` monitor. Fix: commit `722e955` — sweep removed from the open path entirely. Post-fix device measurement (chapter 6521, 260 pages): `vm.init` end-to-end **0.98-1.34s** (was 60.2s), openStore 350-620ms, findDocument 50-90ms.

### Root cause #2 — retention sweep in `closeAndFlush` (the ~15s "first open of the next chapter" stall) — FIXED same evening

After fix #1 the Director still saw long stalls — on a **different chapter (6517, 68 pages), first open in the process**: `vm.init` = 17,845ms, of which the app pipeline was only ~2.7s (`getPages` 1,399ms + status 730ms + tail 0.5s). The park was `getChapterTranslationStatus` → `persistedChapterStatus` (cache miss) → `withProbeStore` → **finally `closeAndFlush()` on the reader's own coroutine** → `reconcileArtifactRetentionLocked()` → the same recursive SAF crawl, proportional to page count (68 pages ≈ 15s; 260 pages ≈ 57s — consistent).

Why the Director's observations made sense:
- **"Re-click the same chapter and it loads instantly"** = `durableStatusCache` hit skips the probe store entirely.
- **"6521 opens fast but this is still slow"** = 6521's active store was still registered (`activeStores.get` hit → status from memory, no probe, no sweep); 6517 was the first open of *that* chapter in the process → probe path → sweep.

Fix: sweep removed from `closeAndFlush` (same rationale as `722e955`); explicit `reconcileArtifactRetention()` remains for callers that want it at a boundary.

### Sweep call sites remaining (out of reader-entry blast radius; owned by the retention follow-up)

- `StorePersistenceScheduler.close()` — async on `persistScope` (non-blocking, but SAF churn + potential monitor contention if store instances are shared).
- `LegacyArtifactRescue` rescue materialization (one-time legacy→ARTIFACTS cutover per chapter, on the open path for not-yet-migrated chapters).
- `BatchChapterTranslator` chapter-completion boundary (worker coroutine, fine).

### Follow-up (T920 Recommendation 1, unchanged)

Event-driven retention (delete-by-path on artifact invalidation) so orphaned artifacts don't accumulate; sweep-on-reader-exit with a per-process guard as the fallback option.

### Instrumentation note

`ReaderEntryTrace` now emits a main-thread heartbeat (`main-heartbeat ... delayMs=` only when main runs >1s late; `maxMainDelayMs` in every `end` line) and probes the previously un-instrumented suspects: `vm.translationStatus`, `vm.stateUpdate`, `vm.openActiveStore`, `probe.status`, `probe.closeAndFlush`. Aggregate INFO only — no titles, filenames, or URIs.
