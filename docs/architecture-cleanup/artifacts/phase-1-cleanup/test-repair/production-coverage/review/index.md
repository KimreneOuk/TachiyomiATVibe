---
title: "P1.3b code review - replace local test mirrors with production coverage"
kind: review
comments: none
---

# Executive Summary

This independent code review evaluates the uncommitted changes for **Phase 1 Slice 3b (P1.3b)** on branch `architecture-cleanup` against commit `444b5abf` (P1.3a).

- **Verdict:** **ACCEPTED (CLEAN / READY FOR COMMIT)**.
- **Diff summary:** Exactly 4 manifest paths modified, **+266 / -120 lines (net +146)**; 0 new files, 0 deleted files.
  - `ChapterTranslator.kt`: +43 / -32 (net +11)
  - `BatchParallelWindowBudgetTest.kt`: +0 / -88 (net -88)
  - `StandardPipelineCoordinatorTest.kt`: +131 / -0 (net +131)
  - `ChapterTranslatorQueueRestoreTest.kt`: +92 / -0 (net +92)
- **Source inventory impact:**
  - `app/src/main/java/eu/kanade/translation`: **287 source files** (285 .kt, 2 .java; unchanged).
  - `app/src/test/java/eu/kanade/translation`: **315 ordinary unit test sources** (unchanged count).
- **Integrity checks:** User report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` SHA256 verified byte-identical (`EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`).
- **Test execution & verification:**
  - Pre-baseline: 19 tests green per variant.
  - Gate (with mirrors present): 26 tests green (DevDebug).
  - Final (mirrors removed): 24 tests green per variant (DevDebug and StandardDebug; 0 failures, 0 errors, 0 skips).
  - Follow-up recheck (`StandardPipelineCoordinatorTest`): 12/12 tests green in both DevDebug and StandardDebug.
  - The 5 remaining Phase 0 baseline failure methods remain untouched and tracked.

# 1. Detailed Diff & Contract Analysis

### 1.1 Wave Selection Parity & Production Linkage (`ChapterTranslator.kt`)

- **Extraction fidelity:** The lambda body previously inlined within `combine(queue.map(Translation::statusFlow))` was extracted into a top-level `internal fun selectChapterWave(queue: List<Translation>): List<Translation>`.
- **Statement-by-statement comparison:** All 25 statements and internal logic comments match byte-for-byte modulo indentation and the necessary trailing `return selected.asSequence()`.
- **Contract preserved:**
  - Filtering candidates by `QUEUE` or `TRANSLATING`.
  - Preferring first active `TRANSLATING` entry to keep workers stable during rearm.
  - Pairing active chapter with the next queued chapter of the same source if present.
  - Grouping by source and capping at 2 chapters per source wave.
- **Production delegation:** `combine(queue.map(Translation::statusFlow))` directly delegates to `selectChapterWave(queue)`. The surrounding flow mechanics (`distinctUntilChanged()`, `transformLatest`, `collect { emit(it) }`) and supervisor lifecycle are unchanged.

### 1.2 Wave Selection Replacement Tests (`ChapterTranslatorQueueRestoreTest.kt`)

- **Replacement coverage:** 6 new unit tests directly invoke `selectChapterWave` with real `Translation` instances, real `Manga`, and real `Chapter` models. `HttpSource` instances are provided via `mockk(relaxed = true)`, accurately modeling source identity through instance identity.
- **Test cases:**
  1. Fresh queue admits only first queued entry (replaces old mirror Case 1).
  2. Pairs active translating chapter with next queued chapter of same source (replaces old mirror Case 2).
  3. Keeps only active translating chapter if next queued chapter is from a different source (replaces old mirror Case 3).
  4. Keeps only active translating chapter when no queued chapters remain (replaces old mirror Case 4).
  5. Excludes terminal/non-participating statuses (`TRANSLATED`, `ERROR`, `PAUSED`, `READY_WITH_WARNINGS`, `NOT_TRANSLATED`) from wave selection.
  6. Admits at most one translating entry even when same-source queued work exists (validating `!in active` behavior).

### 1.3 X6 Gap-Terminal Safety (`StandardPipelineCoordinatorTest.kt`)

- **Direct worker execution:** Test `drained standard lane tail pauses on gaps or missing corpus fingerprint and finalizes only a full corpus` runs the actual `StandardLaneWorker.runPhase` using a real `StandardLaneWorkerContext`, real `lazyStore()`, and real `ChapterArtifactEngine`.
- **Exhaustive 2x2 matrix:** Covers all 4 combinations of `corpusFingerprint` (null vs `"fp-partial"` / `"fp-full"`) and `hasGaps` (true vs false):
  - `(null, gaps=true)` -> `PAUSED`, reason `STOP_REASON`, finalizer calls = 0.
  - `(fp, gaps=true)` -> `PAUSED`, reason `STOP_REASON`, finalizer calls = 0.
  - `(null, gaps=false)` -> `PAUSED`, reason `STOP_REASON`, finalizer calls = 0.
  - `(fp, gaps=false)` -> `COMPLETED`, finalizer calls = 1 (sentinel outcome passed through).
- **Tail verification:** Confirms worker progresses through page processing and reaches the drained tail by inspecting published run records (`TRANSLATE` state with `COUNTER_PAGES_TRANSLATED = 1` and `COUNTER_STOP = 1`).

### 1.4 Mirror Deletion (`BatchParallelWindowBudgetTest.kt`)

- Exactly the two local test mirrors (`S11 wave lookahead...` and `standard lane checkpoint translation preserves X6 gap safety...`) were deleted (-88 lines, +0 lines).
- Both existing bitmap budget tests remain byte-identical.

# 2. Coordinator-Requested Audit Items

### 2.1 Store Closure & Failure-Path Resilience (Hardened)

- **Review observation addressed:** The test has been refactored with an immediate `try { ... } finally { store.closeAndFlush() }` block wrapping all operations after `val store = lazyStore()`.
- **Outcome:** Cleanly closes store instances even in case of assertion failure or early exceptions. Stale diagnostic flake comments from earlier triage have been removed.

### 2.2 Reporting Precision: Captured Callback vs. "Durable" Records

- **Observation:** The results artifact has been clarified in its Evidence Precision section:
  <user_quoted_section>"Evidence precision: the publishRecord/record lambdas are test-local callbacks — the captured records are in-memory evidence that the worker's control flow reached the drained tail... not durable persistence or artifact publication verification; no record is written to disk in this test."</user_quoted_section>
- **Note on residual phrase:** The results artifact still contains one residual mention of "through durable-record and finalizer-spy assertions" in the Interpretation Note paragraph. As directed by the coordinator, this minor text phrasing in the markdown doc is tracked for an artifact-only edit by the implementation agent and does not block code acceptance.

### 2.3 File Line Count Reconciliation

- **Verification:**
  - Base git blob `444b5abf:ChapterTranslator.kt`: **952 lines**.
  - Current working tree `ChapterTranslator.kt`: **963 lines**.
  - Net diff: +43 / -32 lines = **+11 net lines** (952 + 11 = 963).
  - The results artifact now correctly documents `(952->963 lines)`.

# 3. Limitations & Gating Scope

- **Scope boundaries:** Covers Slice P1.3b only. P1.4 has not started and remains held.
- **Physical device testing:** Remains deferred per user choice.
- **Baseline failure ledger:** The 5 remaining Phase 0 baseline failure methods remain tracked and untouched.

# 4. Final Disposition & Verdict

The bounded fixture-finalization follow-up for Phase 1 Slice 3b (P1.3b) successfully hardens resource closure via `try / finally`, eliminates diagnostic noise comments, preserves exact assertion and test semantics, and verifies 12/12 green in independent recheck test suites.

**Slice P1.3b is APPROVED FOR COMMIT**.
