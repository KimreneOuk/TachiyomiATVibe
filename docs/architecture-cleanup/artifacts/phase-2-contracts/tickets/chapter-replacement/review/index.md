---
title: "P2.4 chapter-replacement - independent review verdict"
kind: review
comments: none
---

# Verdict: ACCEPTED

Independent review of slice [P2.4 chapter-replacement](../results) on branch `architecture-cleanup` against base commit `aef57913`.

The implementation fulfills all slice and contract requirements: `TranslationManager.cancelRunningChapterForReplace` has been cleanly converted to a suspending function, eliminating both blocking `runBlocking` wrappers while preserving strict sequential execution (`cancel` -> `clear` -> `remove`). The suspension semantics and ordering gates are deterministically validated by `CompletableDeferred` unit tests.

The complete formatting chronology, full Spotless violation inventories across all runs (including `47dfcb26`, `86fa1399`, and final `a5aff896`), exact CRLF working-copy byte restorations, and executed test XML results across both flavors have been fully verified.

## 1. Scope & Accounting Verification

Audited the working-tree diff against base `aef57913`:

- **Meaningful Changed Source Files (4 files total, net +122 / -13 lines via `git diff --numstat`):**
  1. `app/src/main/java/eu/kanade/translation/workflow/TranslationManager.kt` (+4 / -7):
    - Converted `cancelRunningChapterForReplace` from `fun` to `suspend fun`.
    - Removed both `runBlocking` wrappers.
    - Directly awaits sequential teardown legs:
    1. `scheduler.cancelPageTranslations(chapterId)`
    2. `activeStores.get(chapterId)?.clearTransientQueuePages("Replaced by another chapter's batch")`
    3. `removeFromTranslationQueue(chapter)`
      - Early-return guard for absent chapter (`?: return`) preserved unchanged.
  2. `app/src/test/java/eu/kanade/translation/workflow/TranslationManagerDeleteResetOrderingTest.kt` (+110 / -0):
    - Added 3 gate-controlled unit tests using `CompletableDeferred` barriers.
    - Added reflective helper `field(...)` (no production seams/harness introduced).
    - Trailing blank line properly formatted.
  3. `app/src/main/java/eu/kanade/translation/presentation/TranslationUiTruth.kt` (+7 / -5):
    - Formatted `else when (...)` into `else { when (...) { ... } }` block to satisfy Spotless without semantic changes.
  4. `app/src/test/java/eu/kanade/translation/architecture/TranslationPackageBoundaryTest.kt` (+1 / -1):
    - Import ordering corrected (`java.io.File` placed after `org.junit.jupiter.*`) to satisfy Spotless.
- **Working-Tree Hygiene & CRLF Restoration (11 files total):**
  - **10 files in P2.4 finalization sweep:** `TranslationScheduler.kt` + 9 committed Phase 2 test files (`P5HonestOutcomeTypingTest`, `RenderPermitReacquisitionTest`, `P5OutcomeProjectionTest`, `CancelBatchLeaseSkipTest`, `CancelSyncStoreWriteTest`, `RollingAutoCoordinatorRegistrationRaceTest`, `RollingAutoCoordinatorTest`, `RollingAutoCoordinatorTraceTest`, `TranslationSchedulerTraceTest`).
  - **1 file restored earlier:** `TranslationPipeline.kt`.
  - All 11 files verified byte-identical to HEAD blobs with zero diff against HEAD (`git diff` length 0).
- **Encoding & Hygiene Integrity:**
  - All 4 modified source files verified clean of literal NUL bytes, UTF-8 BOMs, and replacement characters (`U+FFFD`).
  - Architecture report hash preserved: `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` SHA256 verified unchanged (`EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`).

## 2. Suspending Contract & Ordering Gate Analysis

1. **Suspending Teardown Semantics:**
  - Removal of `runBlocking` eliminates synchronous thread-blocking inside coroutine-driven workflow execution.
  - The method strictly awaits:
  1. `scheduler.cancelPageTranslations(chapterId)`
  2. `activeStores.get(chapterId)?.clearTransientQueuePages("Replaced by another chapter's batch")`
  3. `removeFromTranslationQueue(chapter)`
    - No fire-and-forget concurrency, no synchronous compatibility shim, and no store-internals rewrite.
2. **Deterministically Gated Tests:**
  - `cancelRunningChapterForReplace awaits cancel and clear before removing the queue entry`:
    - Employs `CompletableDeferred` barriers for start/release of both cancellation and transient-page clearing.
    - Externally asserts that neither clear nor remove begins while cancellation is parked.
    - Externally asserts that remove does not begin while clear is parked.
    - Confirms exact sequence: `["cancelPageTranslations", "clearTransientQueuePages", "removeFromQueue"]`.
    - Proves suspension is truly awaited rather than executing out-of-order or detached.
  - `returns without side effects for an absent queue entry`: confirms clean early return with 0 teardown invocations when chapter is absent from queue.
  - `without an active store still cancels then removes`: confirms graceful fallback to `["cancelPageTranslations", "removeFromQueue"]` when no active store is registered.
3. **MangaScreenModel Caller & UI Test Omission Justification:**
  - Production caller `MangaScreenModel.confirmReplaceRunningChapter` invokes this method from `screenModelScope.launchNonCancellable { ... }`.
  - The wrapper internally launches on `Dispatchers.IO` with no explicit `Dispatchers.IO` argument passed at the call site.
  - The call site already operates in a coroutine scope and compiles cleanly without changes.
  - Omission of an instrumentation/UI dialog test remains fully justified: standing up a full Compose UI / dialog-harness test for `RunningTranslationConflict` would require extensive new mock infrastructure excluded by the ticket scope. Module compilation across both flavors validates caller typing.

## 3. Spotless & Formatting Verification

Full chronology and full inventory audits across shells:

- **Shell `47dfcb26`:**
  - Full log inspection revealed 3 flagged files:
    1. `LiteRTMangaInpaintingEngine.kt:160,357` (pre-existing baseline debt).
    2. `TranslationUiTruth.kt:501` (`else when` wrapping).
    3. `TranslationScheduler.kt:1-895` (CRLF whole-file drift).
- **Shell `86fa1399` (First Finalization):**
  - Full log inspection revealed 3 flagged files:
    1. `LiteRTMangaInpaintingEngine.kt:160,357` (baseline debt).
    2. `TranslationPackageBoundaryTest.kt:3` (import ordering).
    3. `TranslationManagerDeleteResetOrderingTest.kt:302` (blank line missing).
- **Shell `a5aff896` (Final Verified State):**
  - Executed `:app:spotlessKotlinCheck` exiting with `SPOTLESS_EXIT=1`.
  - Full inventory confirmed: **strictly 1 file flagged** - `LiteRTMangaInpaintingEngine.kt:160,358` (the authorized baseline debt).
  - All Phase 2 touched files and slice edits are 100% compliant with spotless formatting rules.

## 4. Test & Verification Evidence

Verified executed XML test results preserved under `scratch/phase-2-contracts/chapter-replacement`:

- **Pre-Baseline (`a0fb5bbf`):** 10/10 tests passing per flavor (ordering 3, conflict 7).
- **Post-Change (`fb2ddde7`):** 13/13 tests passing per flavor (ordering 6, conflict 7).
- **Finalization Runs (`86fa1399` & `a5aff896`):**
  - Preserved in both `finalization/{dev,std}/xml` and `finalization/{dev,std}/xml-fix`.
  - Both flavors (`devDebug` and `standardDebug`) executed 33/33 tests passing with 0 failures, 0 errors, and 0 skipped:
    - `eu.kanade.translation.presentation.P5OutcomeProjectionTest`: 20/20 pass.
    - `eu.kanade.translation.workflow.ChapterQueueConflictDetectionTest`: 7/7 pass.
    - `eu.kanade.translation.workflow.TranslationManagerDeleteResetOrderingTest`: 6/6 pass.

| Phase | Shell | Flavor | Suites Executed | Tests Passed | Failures | Errors | Skipped | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pre-Baseline | `a0fb5bbf` | dev / std | Ordering (3), Conflict (7) | 10 / 10 | 0 | 0 | 0 | **PASS** |
| Post-Change | `fb2ddde7` | dev / std | Ordering (6), Conflict (7) | 13 / 13 | 0 | 0 | 0 | **PASS** |
| Finalization (`xml`) | `86fa1399` | dev / std | P5Outcome (20), Conflict (7), Ordering (6) | 33 / 33 | 0 | 0 | 0 | **PASS** |
| Finalization (`xml-fix`) | `a5aff896` | dev / std | P5Outcome (20), Conflict (7), Ordering (6) | 33 / 33 | 0 | 0 | 0 | **PASS** |

## 5. Review Conclusion

Slice P2.4 (`chapter-replacement`) is fully verified, structurally sound, non-regressive, clean of formatting and encoding defects, and **ACCEPTED**.

Ready for coordinator commit on branch `architecture-cleanup` and progression to P2.5 (`source-fingerprints`).
