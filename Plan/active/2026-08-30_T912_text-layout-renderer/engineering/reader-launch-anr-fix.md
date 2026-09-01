# T912 fix — reader-launch ANR: chapter translation status moved off the main thread

Fix for the ANR chain documented in `reader-launch-execution-map.md` (same folder).
Branch `codex/text-layout-renderer`, one commit on top of `45c8f03`.
Scope: threading only — no returned-state, cache, or call-site logic changes.

## Root cause (recap)

`DurableChapterStatusResolver.persistedChapterStatus` wrapped the durable
resolution in `runBlocking(Dispatchers.IO)`. The durable leg reopens the
chapter artifact store over SAF/UniFile and reads committed + candidate page
snapshots (~60–130 ms per page, O(pages) binder round-trips). The reader
reached it synchronously on Main inside `ReaderViewModel.loadChapter`'s
`withUIContext` block, and the manga screen reached it once per downloaded
chapter while building the chapter list — parking Main for 5–9 s on the
68-page translated chapter (4 identical ANR traces; process uptime 8 s ⇒
guaranteed cold `durableStatusCache`).

## What changed

Production (5 functions converted to suspend; threading only):

| File:line (post-fix) | Change |
|---|---|
| `DurableChapterStatusResolver.kt:90` | `persistedChapterStatus` → `suspend`; `runBlocking(Dispatchers.IO)` → `withContext(Dispatchers.IO)` at :99. Cache read stays first and synchronous; cache write still non-null-only. |
| `BatchProgressProjector.kt:79` | constructor `persistedChapterStatus` lambda type → `suspend (…) -> Translation.State?`. |
| `BatchProgressProjector.kt:124` | `getChapterTranslationStatus` → `suspend`. Priority order untouched (queued → active-store display → durable → NOT_TRANSLATED). `observeChapterTranslationStatus` needed no edit: its calls sit in `combine` transforms, which are suspend. |
| `TranslationManager.kt:876, 908, 935` | `getChapterTranslationStatus`, `isChapterTranslated` (currently no production callers; kept API-compatible), and private `persistedChapterStatus` → `suspend`. The projector-construction lambda (:854) picks up suspend-ness from the new parameter type. `reconstructDurableTerminalSnapshot` was already suspend — unchanged. |
| `ReaderViewModel.kt:976` | `loadChapter`: the `translationStatus` computation was hoisted OUT of the `withUIContext` block; the Main block now only assigns the precomputed value. All three `loadChapter` callers already run on IO (`init`: `withIOContext`; `loadNewChapter`: `launchIO`; `loadAdjacent`: `withIOContext`). |
| `MangaScreenModel.kt:672` | `toChapterListItems` → `suspend`. Both production callers are IO coroutines (`launchIO` at :230, `collectLatest` inside `launchIO` at :183). Call structure otherwise identical (still evaluated inside `updateSuccessState`, preserving the Success-only gating of the original inline call). |

Tests:

- `TranslationManagerArtifactReadTest.kt` — two tests that called the now-suspend
  `getChapterTranslationStatus` from plain test bodies now use `runBlocking`
  (real-I/O semantics preserved; deliberately not `runTest`, to avoid adding
  TestScope uncaught-exception tripwires to a shared JVM).
- `MangaScreenModelTranslationDrawerTest.kt` / `MangaScreenModelMultiSelectBatchTest.kt`
  — MockK `every` → `coEvery` for the now-suspend stub; `rebuildChapters()` wraps
  the suspend call in `runBlocking`.
- NEW `app/src/test/java/eu/kanade/translation/manager/ChapterTranslationStatusOffMainThreadTest.kt`
  — regression guard, latch-based, no sleep-based flakiness:
  1. `persistedChapterStatus` does not park the caller while the durable lookup
     runs (probe task on a single-thread "main" surrogate must execute mid-lookup;
     the old `runBlocking` code fails this) + null outcome still not cached.
  2. The projector's durable leg runs off the caller thread and its state is
     passed through.
  3. Queued translation still wins without consulting the durable resolver.
  4. Null durable outcome still falls back to `NOT_TRANSLATED`.
- `MangaScreenModelTranslationDrawerTest` — added the same
  `evictCachedScreenModelScope()` guard `MangaScreenModelMultiSelectBatchTest`
  already uses (T911 slice 2 pattern). Rationale: the full-suite gate failed with
  `initializationError` (boot stuck in `State.Loading`) at clean HEAD `45c8f03` —
  reproduced in a throwaway baseline worktree before any local change — because
  voyager's JVM-global cached `screenModelScope` gets cancelled by an earlier
  fixture under full-suite ordering. The guard makes the gate deterministic.

## Why it is behavior-preserving

- Same values: every function keeps its exact decision order — queue check →
  active-store `display` projection → durable resolution → `NOT_TRANSLATED`
  fallback; durable null results still bypass the cache.
- Same cache: `durableStatusCache` reads stay first/synchronous; writes stay
  non-null-only; invalidation sites (`clearDurableStatusCache`, queue-state
  clears) untouched.
- Same call sites: `loadChapter` assigns the same `translationState` in the same
  state update (only computed earlier on the IO context it already ran on);
  `toChapterListItems` keeps its exact call structure, including evaluation
  inside `updateSuccessState`. `cancelQueuedDownloads` (downloads) and the
  status query (translations) are independent, so their relative order does not
  feed back into either result.
- Suspension replaces blocking 1:1: `withContext(Dispatchers.IO)` runs the same
  `resolveDurableChapterStatus` body on the same IO pool; only "park the caller"
  became "suspend the caller".

## How each of the four ANR stacks is now impossible

All four device traces show tid=1 parked in `runBlocking` at
`DurableChapterStatusResolver.persistedChapterStatus` (entered from
`ReaderViewModel.loadChapter` → `TranslationManager.getChapterTranslationStatus`
→ `BatchProgressProjector.getChapterTranslationStatus` → manager
`persistedChapterStatus`):

1. The `runBlocking` no longer exists — the resolver suspends instead of
   blocking, so no caller can be parked at that frame.
2. `ReaderViewModel.loadChapter` no longer issues the query inside
   `withUIContext`; Main never enters the status chain during chapter entry.
3. Every intermediate frame (`TranslationManager.getChapterTranslationStatus`,
   `BatchProgressProjector.getChapterTranslationStatus`) is now `suspend`, so
   the chain cannot even compile as a synchronous call from Main — a future
   caller must opt into a coroutine, and if it uses a Main dispatcher the
   work still happens on `Dispatchers.IO`.
4. The manga-screen variant (`MangaScreenModel` per downloaded chapter) runs on
   the `launchIO` collector only; the synchronous path from a Main context is
   gone there too. The untouched T913/T912-renderer surfaces are unaffected.

## Verification

- Focused suites (fast signal):
  `eu.kanade.translation.manager.*`, `TranslationManagerArtifactReadTest`,
  `MangaScreenModelTranslationDrawerTest`, `MangaScreenModelMultiSelectBatchTest`
  → 28 tests, all green (includes the 4 new regression tests).
- Full gate: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest`
  → **BUILD SUCCESSFUL**, **1329 tests, 0 failures, 0 errors, 0 skipped**.
- Baseline check: full gate at clean HEAD `45c8f03` (throwaway worktree, removed
  afterwards) → failed with the same pre-existing DrawerTest full-suite flake,
  confirming the flake (and its fix above) were independent of this change.

## Remaining risks / notes

- `isChapterTranslated` is suspend now but has no production callers; kept for
  API compatibility.
- Cost, not correctness: the durable status probe still reopens the store and
  reads both committed and candidate snapshots per page (investigation option C
  — halve snapshot reads / status-only open / warm probe registry). It is now
  off the UI thread, but a 68-page chapter still pays ~5–9 s of background I/O
  on cold entry before the durable state is known (queue/active-store results
  return instantly). ReaderViewModel hoists it before the Main block, so first
  frame is no longer gated by it.
- `loadChapter` computes the status on IO slightly before the Main block runs;
  a status change landing exactly in between is corrected by
  `observeTranslationState()`'s live collector, same as before.
- Follow-up option B from the investigation (StrictMode/debug tripwire against
  new synchronous durable callers) was not added; the suspend conversion itself
  now makes that call shape uncompilable.
