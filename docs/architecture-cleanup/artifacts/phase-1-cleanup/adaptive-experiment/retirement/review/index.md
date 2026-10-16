---
title: "P1.4 code review - retire unused adaptive experiment machinery"
kind: review
comments: none
---

# Executive Summary

This independent code review evaluates the uncommitted changes for **Phase 1 Slice 4 (P1.4)** on branch `architecture-cleanup` against commit `1c1d247e` (P1.3b).

- **Verdict:** **ACCEPTED (APPROVED FOR COMMIT)**.
- **Diff & line accounting:**
  - Tracked changes: **+10 / -858 lines** across 5 paths (2 modified, 3 deleted).
  - New untracked test file: `app/src/test/java/eu/kanade/translation/pipeline/adaptive/DevicePagePermitGateTest.kt` (**+233 lines**).
  - Worktree net impact: **-615 lines**.
  - Total filesystem paths: 6 (4 main paths + 1 deleted test path + 1 new test path; 5 logical files).
- **Source inventory impact:**
  - `app/src/main/java/eu/kanade/translation`: **284 source files** (282 .kt, 2 .java; net -2 files via deletions of `AdaptiveKnobController.kt` and `AdaptiveKnobSignalAdapter.kt`).
  - `app/src/test/java/eu/kanade/translation`: **315 ordinary unit test files** (315 .kt; net 0 file count change via rename/replacement of test suite).
- **Integrity checks:**
  - User report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` SHA256 verified byte-identical (`EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`).
- **Test execution & verification:**
  - Pre-baseline: 19 classes / 61 tests per variant (DevDebug and StandardDebug).
  - Post-change: 19 classes / 54 tests per variant (DevDebug and StandardDebug; 0 failures, 0 errors, 0 skips).
  - Coexistence regression surface: 18 classes / 47 tests byte-identical and 100% green.
  - The 5 remaining Phase 0 baseline failure methods remain tracked and untouched.

# 1. Detailed Diff & Contract Analysis

### 1.1 Intentional Retirement of Dormant Adaptive Machinery

- **Deleted components:**
  - `AdaptiveKnobController.kt` (170 lines): Controller, `DeviceActiveStage`, `DeviceStageObservation`, `KnobSignals`, `KnobSignalSnapshot`.
  - `AdaptiveKnobSignalAdapter.kt` (167 lines): Experiment-only global observer adapter subscribing to pipeline stage events.
- **Context & rationale:** As decided by the parent ticket, this represents an intentional retirement of unused, staged optimization machinery (E20a/E20b) rather than zero-caller dead files. The computed concurrency values were never applied by production callers to vary admission capacity, which remained pinned to 1.
- **Symbol cleanup:** Verified zero executable references remain across the entire codebase (`app/src/main`, `app/src/test`, resources, and keep rules). The sole occurrence in the repository is a non-executable documentation comment in `TranslationCoexistenceHarness.kt:224` (`// AdaptiveKnobSignalAdapter.forContext reads...`), which is outside the slice bounds and scheduled for later documentation cleanup.

### 1.2 Preserved Admission Invariants (`DevicePagePermitGate.kt`)

- **Capacity constant co-location:** Added `companion object { const val DEFAULT_CAPACITY = 1 }` directly in `DevicePagePermitGate.kt`.
- **Constructor default:** Updated constructor parameter `private val capacity: () -> Int = { DEFAULT_CAPACITY }`, eliminating the dependency on the deleted controller's `DEFAULT_FLOOR`.
- **Operational bodies byte-identical:**
  - Permit acquisition logic (`acquire`, `withPermit`).
  - Priority handling (`DevicePermitPriority.FOREGROUND` vs `BACKGROUND`).
  - Waiter queueing, timeout handling (`DEFAULT_FOREGROUND_WAIT_CAP_MS`), and `NonCancellable` cancellation cleanup.
  - Remote release and reacquisition protocol (`Permit.markReleased()`, `releaseLocked`).
- **KDoc cleanup:** Removed obsolete references to staged E20a experiments while accurately describing the stage-aware permit protocol around the serialized native lane.

### 1.3 Pipeline Construction & Cleanup (`TranslationPipeline.kt`)

- **Fields & disposal removed:** Removed `adaptiveKnobController` and `adaptiveKnobSignalAdapter` instances, their imports, and the corresponding `adapter.close()` call in `TranslationPipeline.close()`.
- **Quarantine & lane ownership:** Confirmed `NativeRunQuarantine` is constructed and owned by `TranslationPipeline` and passed to `EngineLane`, validating the corrected audit description.

### 1.4 Preserved Telemetry, Normalization & Memory Systems

- **DeviceStageNormalization:** Retained intact with its 5 external production consumer files plus its declaring file (6 production files total):
  1. `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt`
  2. `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt`
  3. `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt`
  4. `app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt`
  5. `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchRenderJoin.kt`
along with `DeviceStageNormalization.kt` itself.
- **Diagnostics & Observers:** `TranslationTraceStageObserverRegistry`, stage spans (`TranslationStageSpan`), and diagnostic stage taxonomy remain active and unmodified.
- **Memory Admission:** `TranslationMemoryBudget` and stage normalization setters remain untouched.

# 2. Test Suite Replacement Analysis (`DevicePagePermitGateTest.kt`)

The former mixed suite `AdaptiveKnobControllerTest.kt` (14 tests, 507 lines) was cleanly replaced by `DevicePagePermitGateTest.kt` (7 tests, 233 lines).

### 2.1 Retired Tests (7 Tests)

The 7 deleted tests exercised exclusively the retired calculation formulas, controller warm-up, thermal/memory backoff, and adapter signal mapping:

1. `floor samples exclude the cold start and revise a median window`
2. `higher concurrency can back off but never revises the floor anchor`
3. `thermal and memory pressure back off without contaminating anchors`
4. `durability interference does not move the device knob`
5. `out of memory resets the computed knob to floor`
6. `trace adapter ignores remote work and rejects a durability-contaminated sample`
7. `durability span completing after run terminal clears its active signal`

### 2.2 Retained Live Tests (7 Tests)

The 7 retained tests exercise live gate behavior, prioritization, lifecycle, and trace contracts:

1. `pipeline native attachment immediately acquires an idle floor permit`: Verifies live pipeline `withNativeLane` acquires permit, asserts `capacityLimit == DevicePagePermitGate.DEFAULT_CAPACITY`, active count 1, and release on exit. Uses explicit `try / finally` to cancel/join workers and close `harness`.
2. `source bounds update normalization without resetting trace duration`: Validates `TranslationTraceStageObserverRegistry` span timing with `FakeClock` and `DeviceStageNormalization.sourcePixels`. Uses explicit `try / finally` to end run and unregister the observer.
3. `foreground permit jumps queued background work`: Verifies `FOREGROUND` priority jumps ahead of queued `BACKGROUND` waiter using structured coroutines and explicit permit releases.
4. `foreground wait cap expires without leaking its queued waiter`: Verifies waiter timeout expiration at 10ms without leaking queue state.
5. `cancelled waiter is removed and permit release remains usable`: Verifies coroutine cancellation cleanly unregisters waiter and subsequent permits acquire normally.
6. `remote work releases and later reacquires a device permit`: Verifies stage-aware release during remote provider work and subsequent reacquisition for render.
7. `background-only traffic keeps fifo order at pinned floor`: Adapted from old test to use the fixed `DevicePagePermitGate()` at capacity 1, strictly asserting FIFO order (1, 2) and permit release without controller warm-up assertions.

Resource cleanup is scoped accurately to the patterns used: the two tests managing shared or external registrations (`TranslationCoexistenceHarness` and `TranslationTraceStageObserverRegistry`) use explicit `try / finally` blocks; the remaining gate-only coroutine tests rely on structured coroutine teardown and sequential permit releases.

# 3. Verification & Evidence Review

1. **Test Results:**
  - Pre-baseline: 19 classes / 61 tests per variant (all green).
  - Post-change: 19 classes / 54 tests per variant (all green, 0 failures, 0 errors, 0 skips).
  - Coexistence suite: 18 classes / 47 tests verified byte-identical and green across both variants.
  - Adaptive suite: Renamed from `AdaptiveKnobControllerTest` (14 tests) to `DevicePagePermitGateTest` (7 tests), net -7 tests matching the retired experiment tests.
2. **File Counts:**
  - Production translation files: 284 (282 .kt + 2 .java).
  - Ordinary unit test translation files: 315 (.kt).
3. **Diff Check:**
  - Tracked diff: +10 / -858 lines across 5 files.
  - Untracked new test: +233 lines.
  - Total net: -615 lines.
4. **Failure Ledger:**
  - The 5 remaining Phase 0 baseline failure methods remain tracked and untouched.

# 4. Conclusion & Verdict

Phase 1 Slice 4 (P1.4) successfully and cleanly retires the unused adaptive knob controller and signal adapter while fully preserving the live `DevicePagePermitGate` admission invariants, priority queuing, and diagnostic stage contracts. All regression suites are green.

**Slice P1.4 is APPROVED FOR COMMIT.** GLM executor is clear to commit P1.4.
