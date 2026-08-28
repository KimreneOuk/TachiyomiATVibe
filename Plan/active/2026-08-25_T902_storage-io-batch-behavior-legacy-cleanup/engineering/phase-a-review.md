# T902 Phase A — Independent Review

Reviewer: T902 Phase A Reviewer (independent).
Scope: uncommitted working-tree diff for MangaScreenModel.kt, DownloadCache.kt,
ActiveChapterStoreRegistry.kt (+ test), ChapterTranslationStore.kt,
TranslationManager.kt, TranslationManagerArtifactReadTest.kt,
DownloadCacheRenewalGuardTest.kt. Docs/AGENTS.md/Plan excluded (owned by main lead).
All file:line references are the current working tree unless stated otherwise.

## Final verdict: APPROVE (after fixes)

Initially APPROVE-WITH-FIXES; both required fixes (F1, F2) were applied by the
implementer and independently re-verified on 2026-08-25 — see section 4. The
Phase A diff is approved for commit as it stands in the working tree.

Historical context of the two former blockers:

- **F1 (test breakage)** — Phase A broke `BatchTranslateBlockMergeTest` in suite
  runs via a leaked NPE from a partially-constructed `TranslationManager` in
  `TranslationManagerReaderTeardownTest`. The implementer's
  "pre-existing/unrelated" claim was refuted by direct experiment (evidence in
  section 2).
- **F2 (stale status cache)** — the new `durableStatusCache` was not invalidated
  by the chapter/page reset flows, so a reset chapter could keep showing its old
  TRANSLATED/READY_WITH_WARNINGS badge until an unrelated queue or store-lifecycle
  event fired.

Everything else verified sound: A1 off-main probe with full behavior parity, A2
status/reader resolution order and fail-safe guards, LEGACY fallback, registry
aliasing, A3 quarantine safety, F6 registry routing, and the new tests.

---

## 1. Checklist verdicts

### 1.1 A1 — UI-thread probe removal — PASS

- No synchronous disk/SAF probe remains on the UI thread in
  `confirmChapterTranslation`: the `isChapterDownloaded(..., skipCache = true)`
  partition runs inside `withIOContext` within `screenModelScope.launch`
  (MangaScreenModel.kt:952-969). `withIOContext` import present
  (MangaScreenModel.kt:72).
- Behavior parity preserved verbatim vs HEAD (diff-verified against
  `git show HEAD`): awaiting→`queueTranslationAfterDownload` + `downloadChapters`
  (970-975); `downloaded.size > 1` → `translateChapters` (977-980); single →
  log + `translateChapterPreflight` with the same two `ChapterQueuePreflight`
  branches and the same `Dialog.RunningTranslationConflict` surfacing (986-993);
  `launchTranslateChapter` unchanged (1012-1016, itself async via
  `launchNonCancellable`, same as HEAD's call shape).
- Scope/lifecycle: `screenModelScope` — job canceled with the screen model, no
  leak; dialogs still set from the main dispatcher after IO resumption.
- Double-tap: `pendingTranslationGroup = emptyList()` still executes
  synchronously before the launch (951), so the pending group cannot be
  re-enqueued. A rapid single-item double-tap could interleave the two coroutines,
  but the second `translateChapter` evicts the first's still-QUEUE entry via
  `evictStaleQueuedChapters` (TranslationManager.kt:346-355) — same self-healing
  as at HEAD, marginally wider window. Non-blocking.

### 1.2 A2 — artifact-aware durable reads — PASS with F2 (cache) and notes

Status resolution order preserved: queue → live active stores → durable
(TranslationManager.kt:380-401, `getQueuedTranslationOrNull` → `activeStores.get`
display-ready branch → `persistedChapterStatus`).

ARTIFACTS fast path is fail-safe (`resolveDurableChapterStatus` 462-486,
`artifactSummaryStatus` 488-501):
- Certifies only when `summary.expectedPageCount == manifest.pages.size`
  AND ≥1 manifest page display-ready AND terminal outcome valid; otherwise
  falls through to the registry-opened store's live display projection
  (`statusFromReadablePages`), which returns null (→ NOT_TRANSLATED) when no
  page is display-ready and READY_WITH_WARNINGS when a summary is absent or the
  count mismatches — matching the old flat-path semantics.
- Partial batch: no terminal summary exists mid-batch (summary published at
  batch end), and in-queue chapters resolve QUEUE before the durable read, so
  an optimistic/stale summary cannot certify mid-batch.
- Settings change: durable artifacts/summary are unaffected by settings changes,
  so status is unchanged — same as before.
- Unreadable manifest (`manifest == null, exists = true`) does NOT fall back to
  stale flat JSON — it opens the store, which owns `.bak`/quarantine recovery
  (ChapterTranslationStore.kt branch at TranslationManager.kt:479-486). Good.
- Known parity weakness (pre-existing, not new): after PARTIAL page resets the
  page count is unchanged and ≥1 page can remain display-ready, so a stale
  terminal summary can still certify TRANSLATED — the OLD flat path had the
  identical any-readable-page + count-match weakness. Note for Phase B summary
  work; `reconcileBatchProgress` (1463-1476) only flushes an open store.

**F2 — cache invalidation gap (required fix).** The cache
(TranslationManager.kt:85, 440-460) is cleared only by: queueState emissions
(143-145), `registerActiveTranslationStore` (737-741), and
`unregisterActiveTranslationStore` (743-747). The reset flows
`resetChapterData` (1239-1277), `resetTranslationData` (1279-1351),
`resetInpaintData` (1353-1417), and `resetOcrData` (1419-1461) durably demote
pages but never clear the cache; when the chapter is NOT in the translation
queue, `removeFromTranslationQueue` (1495-1508 → ChapterTranslator.kt:617-619)
emits nothing. Reproduction path: chapter TRANSLATED (cached) → user resets
chapter data with no reader open and no queue entry → the reset opens AND
registers a store via `openExistingChapterTranslationStore` (1262) but the live
branch skips (no display-ready pages, 394) → `persistedChapterStatus` returns
the cached TRANSLATED → the next list rebuild triggered by a NON-queue event
(`downloadCache.changes` / `downloadManager.queueState` / DB chapters,
MangaScreenModel.kt:181-198 → `toChapterListItems` 645) shows the stale badge
again. `deleteTranslation` is covered (its `unregisterActiveTranslationStore`
clears unconditionally, 1154). Fix: clear `durableStatusCache` in the reset
paths (whole-map `clear()` matches the existing conservative invalidation).

LEGACY fallback — PASS: populated flat files still decode
(`decodeLegacyChapterStatus` 503-520; test `legacy authority chapter still
resolves from the flat file`, TranslationManagerArtifactReadTest.kt:143-151);
chapters with no manifest and no file return null → NOT_TRANSLATED exactly as
before (file.exists() gate at 504).

Registry — PASS with notes:
- File-key/chapter-key aliasing is race-free: both open paths share the
  per-file mutex (`ActiveChapterStoreRegistry.kt:54-97`), first opener wins,
  later chapter-keyed opens attach the same instance; proven by
  `chapter and file keyed opens share one store` (ActiveChapterStoreRegistryTest.kt:41-67,
  createCount == 1, identical instances).
- `remove(chapterId)` cleans both maps (`entries.removeIf { it.value === removed }`,
  registry 100-105). No duplicate instances possible through manager paths: every
  `getOrCreate` in TranslationManager passes the file key when the file exists
  (632, 695, 816-818); the only null-file-key open creates a memory-only lazy
  store when no file exists (823-831), which cannot alias. Residual theoretical
  gap: `getOrCreate(chapterId)` with null fileKey uses the `"chapter:$id"`
  mutex, so a concurrent file-keyed open of the same (nonexistent-at-check-time)
  file could in principle produce two instances — not reachable through current
  production callers. Document-only.
- Store retention (non-blocking): stores opened by status probes (summary fast
  path failed — i.e., interrupted/partial chapters) stay registered until a
  reader-stop or batch eviction (awaitReaderStop 215-226,
  cancelAllPageTranslations 1580-1604). Not a hard leak (completed chapters take
  the summary fast path; fresh chapters never open a store), but a long manga
  browsing session with many partial chapters accumulates rehydrated page maps
  in memory. Suggest a follow-up: evict probe-opened stores after status
  resolution or bound the registry (fits Phase B scope).
- File-only stores (`getOrCreateFile`) have no removal API; reachable only via
  `getChapterTranslation(file)` when `chapter.id == null`
  (DownloadPageLoader.kt:53 fallback) and `openChapterTranslationStore`
  (729-735, no production callers). Minor.

Reader path — PASS: `getChapterTranslationForReader` is suspend and does all
I/O in `withContext(Dispatchers.IO)` (586-607); live-store fast path parity kept
(`takeIf { it.isNotEmpty() }`); manifest-present non-LEGACY → registry
rehydration (artifact pages after restart); LEGACY/no-manifest → flat decode
with quarantine. `DownloadPageLoader.getPages` (DownloadPageLoader.kt:42-58)
already runs in a suspend context, so the signature change compiles without
modifying that file (verified by full app test-compile during this review).

No new main-thread runBlocking: A1's coroutine suspends to IO; the runBlocking
bridges in `persistedChapterStatus` (449) and `getChapterTranslation(file)`
(611) are pre-existing; `openChapterTranslationStore` gained a runBlocking
wrapper but has no production callers. Existing bridges unchanged in reach.

### 1.3 A3 — quarantine instead of delete — PASS

`quarantineCorruptTranslationFile` (657-668) fires only from decode failure
handlers (652, 518), deletes only a PREVIOUS `.corrupt` target, renames the
original to `<name>.corrupt`, logs ERROR — source data always preserved under
the quarantine name; the reader/status callers still receive an empty map
(646-655, 503-520). Repeated failures cannot loop: after the rename the
original no longer exists (`decodeLegacyChapterTranslation` 646 returns empty,
`decodeLegacyChapterStatus` 504 returns null). Verified end-to-end by
`corrupt flat file is quarantined without deletion`
(TranslationManagerArtifactReadTest.kt:153-165), which exercises both the
status and reader entry points on the same chapter.

### 1.4 F6 — registry-routed reset/preflight — PASS

All five previously-direct opens now route through
`openExistingChapterTranslationStore` / registry:
`chapterResetPreflight` (1182-1188), `resetChapterData` (1262-1274),
`resetTranslationData` (1313-1345), `resetInpaintData` (1375-1395),
`resetOcrData` (1430-1439), plus `rekeyTranslationForCompletedDownload`
(690-696) and `openChapterTranslationStore` (729-735). No competing direct
`ChapterTranslationStore.open` remains in TranslationManager. The remaining
direct opens (ChapterTranslator.kt:409, TranslationPipeline.kt:2876) are the
resolver-null fallbacks; production wiring always supplies the registry-backed
resolver (TranslationManager.kt:112-121), so they are unreachable in production —
matches the implementer's disclosure. Reset semantics unchanged except that the
store now stays registered (see F2 for the interaction).

### 1.5 Tests — PASS with strengthening suggestions

- Restart recovery IS asserted with a fresh manager, fresh registry, and a
  closed store: `artifact authority status and reader reads survive a fresh
  manager` (TranslationManagerArtifactReadTest.kt:118-141) — status TRANSLATED
  via the summary fast path and reader pages via artifact rehydration.
  Weakness (non-blocking): the flat file in that fixture still contains the
  same `"hello"` translation, so the reader assertion would also pass against
  the flat decode; asserting the artifact-only mutation
  (`blocks.single().userEditedAt == 42L`) or using an empty flat file (the
  canonical D1 shape) would make the test discriminate.
- Registry aliasing concurrency: ActiveChapterStoreRegistryTest.kt:16-67.
- DownloadCache guards: DownloadCacheRenewalGuardTest.kt (failed enumeration,
  unavailable sources, empty persisted index, skipCache live-provider) — 4 tests.
- Missing coverage (non-blocking): cache invalidation behavior (would have
  caught F2); a test asserting F1's partial-construction path.

### 1.6 spotless / compile — PASS

Implementer's gate runs (spotlessCheck, compile, domain tests) accepted;
independently, my own `:app:testStandardDebugUnitTest` runs compiled the
working tree (main + test sources) successfully. `git diff --check` clean per
implementer report.

---

## 2. MANDATORY ADJUDICATION — BatchTranslateBlockMergeTest

**Verdict: Phase A broke it. The implementer's "pre-existing/unrelated"
classification is incorrect.**

Hard evidence (all from this review session, 2026-08-25):

1. **Working tree, isolated class run** (`--tests eu.kanade.translation.batch.BatchTranslateBlockMergeTest`):
   BUILD SUCCESSFUL — passes alone (order-dependent failure).
2. **Working tree, full translation suite** (`--tests "eu.kanade.translation.*"`):
   934 tests, 2 failed — the known `AotReportBubbleFillTest` pixel mismatch AND
   `BatchTranslateBlockMergeTest.legacy blockId-keyed merge collapses every
   region to the last translation()` failing with
   `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`.
   (app/build/test-results/testStandardDebugUnitTest/TEST-eu.kanade.translation.batch.BatchTranslateBlockMergeTest.xml)
3. **Failure cause (from the XML's suppressed exception):**
   `java.lang.NullPointerException: Cannot invoke
   "java.util.concurrent.ConcurrentHashMap.clear()" because
   "this.durableStatusCache" is null`
   at `TranslationManager.unregisterActiveTranslationStore` (TranslationManager.kt:747)
   ← `cancelAllPageTranslations` (1600)
   ← `TranslationManager$stopReaderTranslations$1` (196).
   Both the field (`durableStatusCache`, line 85) and the dereference (line 747)
   are Phase A additions — the line cannot NPE at HEAD.
4. **Leak source:** `TranslationManagerReaderTeardownTest.uninitializedManager`
   (TranslationManagerReaderTeardownTest.kt:211-228) constructs the manager via
   `sun.misc.Unsafe.allocateInstance` and sets only scheduler/translator/
   activeStores/applicationScope/readerTeardownMutex — not the new
   `durableStatusCache`. Its test calls `manager.stopReaderTranslations(...)`
   (line 81), whose `applicationScope.launch` coroutine NPEs asynchronously
   after the test method completes; kotlinx-coroutines-test attributes the
   uncaught exception to the NEXT `runTest` in the JVM — order-dependent, which
   is why the block-merge test (and only it, after the teardown test in the
   class ordering) fails while its own logic is correct.
5. **HEAD baseline (stash experiment):** `git stash push -u` of exactly the
   Phase A files (5 modified + 3 test files incl. both new untracked tests),
   then the identical `--tests "eu.kanade.translation.*"` run at HEAD
   (7c78a46): ONLY `AotReportBubbleFillTest` failed;
   `TEST-...BatchTranslateBlockMergeTest.xml` reports `tests="2" failures="0"`.
   `git stash pop` restored the working tree (verified via git status).

Note on the 2026-08-24 baseline (checkpoints.md, 986 tests, only
AotReportBubbleFillTest failing): that baseline predates HEAD 7c78a46
(committed 2026-08-25 10:21 +0700) and does not describe HEAD's suite; the
direct stash experiment above is the authoritative comparison.

**Required fix (F1):** initialize `durableStatusCache` in the
allocateInstance-based test helpers — `TranslationManagerReaderTeardownTest`
at minimum (the only other helper reaching the new line;
`TranslationManagerAutoArbitrationTest` doesn't set applicationScope so it
cannot reach the async path; `CancelSyncStoreWriteTest` builds only a scheduler;
`TranslationManagerArtifactReadTest` already sets the field). Alternative:
null-safe `durableStatusCache?.clear()` in production — works but weakens the
construction invariant; the repo's established pattern is
tests-initialize-touched-fields. After the fix, rerun
`--tests "eu.kanade.translation.*"` plus the full gate to confirm only the
known AOT failure remains.

---

## 3. Findings summary

| # | Severity | Finding | Action |
|---|----------|---------|--------|
| F1 | Blocking | Phase A broke BatchTranslateBlockMergeTest in suite runs (leaked NPE from uninitialized `durableStatusCache` in ReaderTeardownTest's Unsafe-constructed manager) | Implementer: fix test helper (or null-safe clear), rerun suites |
| F2 | Blocking (user-visible) | Reset flows don't invalidate `durableStatusCache`; stale TRANSLATED/READY_WITH_WARNINGS badge after reset until an unrelated invalidation event | Implementer: clear cache in reset paths |
| O1 | Low | Status-probe-opened stores (partial chapters without valid summary) retained until reader-stop/batch eviction; unbounded within a long manga-browsing session | Follow-up ticket (Phase B): evict probe-opened stores or bound registry |
| O2 | Low | `getOrCreateFile` stores have no removal API; reachable only via rare null-chapter-id fallbacks | Note for future registry work |
| O3 | Low | Theoretical two-instance window between null-fileKey chapter mutex and file-key mutex for the same file | Document; not reachable via current callers |
| O4 | Low | Restart test doesn't discriminate artifact vs flat read (same "hello" in both); canonical empty-flat D1 shape untested | Suggest asserting `userEditedAt == 42L` |
| O5 | Low (parity) | Stale terminal summary can still certify TRANSLATED after partial page resets — identical to old flat-path behavior | Phase B summary-refresh consideration |

## 4. Re-verification after fixes (2026-08-25) — COMPLETE

**Result: F1 and F2 verified fixed; O4 suggestion also adopted. Final verdict:
APPROVE.**

### Fix verification (read in the working tree by me)

- **F1 fixed** — `TranslationManagerReaderTeardownTest.uninitializedManager` now
  sets the new field:
  `setField(manager, "durableStatusCache", ConcurrentHashMap<Any, Any>())`
  (TranslationManagerReaderTeardownTest.kt:228, import added at line 25). The
  async `stopReaderTranslations` → `unregisterActiveTranslationStore` path can
  no longer NPE in that fixture.
- **F2 fixed** — `durableStatusCache.clear()` added at the head of all four
  reset flows, each placed after the `chapter.id ?: return` guard and before
  any durable mutation: `resetChapterData` (TranslationManager.kt:1246),
  `resetTranslationData` (1282), `resetInpaintData` (1357), `resetOcrData`
  (1424). Total sites now 144/740/747/1246/1282/1357/1424 — matches the
  expected invalidation surface exactly.
- **O4 adopted** — the restart test now asserts the artifact-only mutation:
  `pages["page.jpg"]?.blocks?.single()?.userEditedAt shouldBe 42L`
  (TranslationManagerArtifactReadTest.kt:141; value set only via the artifact
  store at 124, absent from the flat JSON fixture), making the reader-read test
  discriminate artifact rehydration from flat-file decode.
- **No scope creep** — diff delta vs my first review is exactly:
  +1 import and +1 setField line in ReaderTeardownTest,
  +4 `durableStatusCache.clear()` lines in TranslationManager,
  +1 assertion line in TranslationManagerArtifactReadTest. Nothing else moved.

### Independent validation (my own runs)

- `:app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"` (fresh
  execution, timestamp 2026-08-25T06:11:31): **994 tests, exactly 1 failure —
  the known `AotReportBubbleFillTest` pixel mismatch**;
  `BatchTranslateBlockMergeTest`: tests="2" failures="0" errors="0".
  This matches the implementer's reported full-gate result (994 / 1 known) and
  the pre-Phase-A baseline shape (only the AOT failure).
- Implementer's `spotlessCheck` / `:domain:testReleaseUnitTest` /
  `git diff --check` passes accepted (consistent with the same commands run
  clean during the first review round).

### Residual items (non-blocking, carried forward)

- O1 (probe-opened store retention until reader-stop/batch eviction) —
  recommended as a Phase B follow-up ticket.
- O2, O3, O5 — documented above; no action required for Phase A.

**The commit gate may be opened for the Phase A diff.**
