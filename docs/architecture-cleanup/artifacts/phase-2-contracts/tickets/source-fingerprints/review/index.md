---
title: "P2.5 source-fingerprints - independent review final verdict"
kind: review
comments: none
---

# Verdict: ACCEPTED (P2.5 Revision Final Recheck)

Independent review and final recheck of slice [P2.5 source-fingerprints](../results) and [P2.5 revision](../revision) on branch `architecture-cleanup` against base commit `2efde3b5`.

The revisions demanded during initial review have been verified in the working tree and test artifacts:

1. **Fresh Post-Rekey Evidence Selection:** In `BatchChapterTranslator.kt`, `persistedEvidencePageKeys(store, orderedStreams)` is invoked *after* the `rekeyPages` block. The helper reads `store.state.value` at invocation time and traverses in canonical `orderedStreams` sequence, ensuring rekeyed natural keys resolve correctly while pristine chapters resolve nothing.
2. **Deterministic Two-Phase Cache Rendezvous:** In `SourceFingerprintCacheTest.kt`, `concurrent same-key lookups compute once` uses a two-phase rendezvous (`entered.await()` -> launch second caller -> `advanceUntilIdle()` to suspend on mutex -> assert both incomplete with `calls == 1` -> `release.complete(Unit)`).
3. **Scoped Regression Coverage:** `BatchResumeEvidenceSelectionTest.kt` (3 tests) exercises `persistedEvidencePageKeys` after a real `store.rekeyPages` migration (`Moved`), out-of-order persisted map insertion, and pristine state. This cohesive extraction satisfies scoped production-path verification without introducing a heavy, brittle end-to-end `translateBatch` harness.
4. **Clean Byte Hygiene & Formatter Provenance:** Full Spotless inventory across 49 untruncated log lines (`d8244cb7`) confirms zero formatting violations in production or test additions; the only remaining violation is pre-existing baseline debt in `LiteRTMangaInpaintingEngine.kt:160,358`.

## 1. Scope & Working-Tree Diff Verification

Verified against base commit `2efde3b5` (`2efde3b5 refactor: suspend ordered chapter replacement`):

- **Production Paths (6 files in `eu.kanade.translation.pipeline.batch`):**
  1. `BatchChapterTranslator.kt`:
    - Replaced monitor-blocking `LazySourceFingerprints` with suspending `SourceFingerprintCache`.
    - Uses a single coroutine `Mutex()` + `HashMap<String, String>()` serializing misses across keys without JVM thread parking.
    - Throws during compute propagate immediately without installing anything into cache; cancelled compute installs nothing (`ensureActive()` checkpoint post-compute). Only computed `null` installs `UNKNOWN_SOURCE_FINGERPRINT`. Empty strings `""` are retained as-is. Unknown keys return `null` without compute.
    - Extracted `internal fun persistedEvidencePageKeys(store, orderedStreams): List<String>` reading `store.state.value` at invocation time and preserving canonical `orderedStreams` order.
    - Production invokes `sourceFingerprints.resolveAll(persistedEvidencePageKeys(store, orderedStreams))` strictly *after* `store.rekeyPages(...)` and passes the resolved map to `BatchResumePlanner`.
    - Standard lane tail observation (`val observedSourceSha = sourceFingerprints.get(pageKey)`) relocated inside `BatchChapterTranslator.standardTranslateOutcome` prior to `withLeaseWait`, ensuring no disk I/O runs under lease authority.
  2. `BatchLaneWorkers.kt`:
    - Parameter `freshSourceShaByPageKey` widened to `suspend (String) -> String?`.
    - Helper functions `currentSourceIdentityMatches` and `naturalOrderPredecessorTerminal` converted to `suspend`.
  3. `ChapterProfileBatchCoordinator.kt`:
    - Parameter `freshSourceShaByPageKey` widened to `suspend (String) -> String?`.
    - Helper functions `admissionSourceSha` and `freshSourcePairsForPages` converted to `suspend`.
  4. `FinalizeWorker.kt`:
    - Context parameter `freshSourcePairsForPages` widened to `suspend (List<PageKey>) -> List<Pair<String, String>>?`.
  5. `PreflightWorker.kt`:
    - Context parameters `freshSourcePairsForPages` and `admissionSourceSha` widened to `suspend`.
  6. `StandardLaneWorker.kt`:
    - Context parameter `freshSourceShaByPageKey` widened to `suspend (String) -> String?`.
    - Helper function `standardPageTerminalAtTranslate` converted to `suspend`.
- **Test Paths (1 modified, 2 untracked additions):**
  1. `app/src/test/java/eu/kanade/translation/coexistence/LazySourceFingerprintPushThroughTest.kt` (modified):
    - Updated to exercise `SourceFingerprintCache` via `runTest`.
  2. `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchResumeEvidenceSelectionTest.kt` (new, 3 tests):
    - `evidence selection reads current store state after real legacy rekey`
    - `evidence selection returns canonical stream order regardless of persisted insertion order`
    - `pristine chapter selects no evidence keys`
  3. `app/src/test/java/eu/kanade/translation/pipeline/batch/SourceFingerprintCacheTest.kt` (new, 9 tests):
    - Comprehensive unit coverage for cache deduplication, two-phase same-key contention, distinct-key miss serialization, cancellation non-block/non-install, computed-null UNKNOWN mapping, and retry on exception.
- **Working Tree Hygiene:**
  - 11 status-M files in `git status` verified as empty content diffs (mtime/index artifact from earlier sweeps).
  - Untracked architecture report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` verified unchanged at SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`.

## 2. Review Defect Resolution Assessment

### P2.5-BUG-01: Stale Pre-Rekey Snapshot in Evidence Selection

- **Status:** **RESOLVED**
- **Verification:** Production now calls `persistedEvidencePageKeys(store, orderedStreams)` *after* `rekeyPages`. The helper inspects `store.state.value` at execution time and filters canonical `orderedStreams`.
- **Coverage Evaluation:** The author opted not to construct an elaborate `translateBatch` integration harness, instead extracting `persistedEvidencePageKeys` as an `internal fun` in `BatchChapterTranslator.kt` and validating it via `BatchResumeEvidenceSelectionTest.kt`. Because the helper contains the complete selection logic, reads real `ChapterTranslationStore` instances after actual `rekeyPages` operations, and is called directly in production, this satisfies the scoped coverage requirement without introducing excessive test-fixture coupling.

### P2.5-BUG-02: Flawed Concurrency Test in `SourceFingerprintCacheTest`

- **Status:** **RESOLVED**

## 3. Evidence & Provenance Verification

| Run Stage | Shell ID / Context | Scope & Selectors | Outcome & Verified Totals |
| --- | --- | --- | --- |
| **RED Regression** | Native / Gradle | `BatchResumeEvidenceSelectionTest` | **3 tests, 1 failure** (`expected: <["page_1", "page_2"]> but was: <[]>`). Preserved at `scratch/.../revision/red/dev/xml`. Note: Initial run false-start had inverted `onlineKeys`/`onDiskKeys` resulting in `Noop`; corrected run with `Moved` produced genuine RED. |
| **GREEN Revision** | `f2b2d64c-3c08-4edc-a9a2-454b9916b027` | 11 focused test classes on DevDebug & StandardDebug | **DevDebug: 11 classes, 53 tests, 0 failures, 0 errors, 0 skipped**.<br>**StandardDebug: 11 classes, 53 tests, 0 failures, 0 errors, 0 skipped**.<br>XMLs preserved at `scratch/.../revision/green/{dev,std}/xml`. |
| **Spotless Check (Post-GREEN)** | `f2b2d64c-3c08-4edc-a9a2-454b9916b027` | `:app:spotlessCheck` | Failed (`SPOTLESS_EXIT=1`) on baseline LiteRT debt + single EOF trailing newline in `BatchChapterTranslator.kt`. |
| **Spotless Recheck (Failed Launcher)** | `5a23e02e-98e1-4423-8afd-fac9eea0bc23` | `.\gradlew.bat :app:spotlessCheck` (Git Bash) | Exited 127 (Windows path separator incompatible with bash). No Gradle executed. |
| **Spotless Final (Post-EOF fix)** | `d8244cb7-93b6-476f-b35c-02aa912739bf` | `./gradlew :app:spotlessCheck` | Full 49-line log verified without truncation. Formatter violations strictly confined to baseline `LiteRTMangaInpaintingEngine.kt:160,358`. `BatchChapterTranslator.kt` is clean. |

*Provenance Limitation Note:* The RED run was executed as a one-shot Gradle invocation and retained its XML without capturing a persistent Traycer shell ID or launcher script. The XML output is verified on disk. No test suites were rerun following the cosmetic EOF-only newline correction, which is acceptable under standard formatting hygiene policies.

## 4. Descriptive Corrections Recorded

The following historical inaccuracies from earlier review drafts are formally recorded as corrected:

- **Cache Architecture:** `SourceFingerprintCache` employs a **single** coroutine `Mutex()` + `HashMap<String, String>()` that serializes all cache misses across keys; it is not a per-key concurrent mutex map.
- **Error Semantics:** Exceptions thrown by `compute` propagate unhandled and install nothing into the cache, leaving keys retryable. Only computed `null` installs `UNKNOWN_SOURCE_FINGERPRINT`. Empty strings `""` are stored without mutation.
- **Seam Placement:** Standard lane tail observation was relocated inside `BatchChapterTranslator.standardTranslateOutcome` prior to lease wait, not in `StandardLaneWorker`.
- **Attribution & Package:** Implementation author is `3b81dcb4-fa80-48c4-9e9b-20b49c3a10d0` in package `eu.kanade.translation.pipeline.batch`; target branch is `architecture-cleanup`. No fictional `Evidence` wrapper types exist.
- **Pass 1 Scope:** Pass 1 ran DevDebug only (47 passed, 3 failed); StandardDebug was not executed during Pass 1.

## 5. Final Disposition

Slice P2.5 satisfies all architectural, concurrency, persistence, and verification criteria. The implementation is approved for promotion to `architecture-cleanup`.

<TRAYCER_NEXT_STEPS>
Final independent review completed and verdict recorded. Ready to proceed to next task.

- [] Proceed to Phase 2.6 full app test suite verification and integration finalization.
  </TRAYCER_NEXT_STEPS>
