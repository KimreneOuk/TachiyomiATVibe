---
title: "Translation Step 1 — test manifest v2 (queue-absorption delete plan, A/B/C), plan only"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

# Translation Step 1 — Test Manifest v2 (PLAN ONLY)

**Status:** plan only. No Gradle runs, no repo edits, no production path changes performed. Source baseline: **`4de121bf`**. No pass/fail claims; nothing was executed. **v2** supersedes the v1 manifest per reviewer correction: the Step-1 target for the auto-concurrency island is **DELETE after `ReaderTranslationQueue` absorbs its behavior** — NOT relocation.

**Counting method (corrected in v2):** `@Test` occurrences matched precisely, excluding `@TestInstance`/`@TestMethodOrder`/`@TestFactory` (v1's three affected-UI numbers were inflated by those annotations); verified **zero** `@Test` occurrences on comment lines, so no count is inflated by doc references.

**Ownership split (binding):** reader queue (queue production + `ReaderTranslationQueueTest` authoring) = reader-queue agent; concurrency/UI-outcome deletion sequencing, boundary architecture test, fingerprint-cache bridge, orphan gates = Step1 owner (this agent). Queue-agent mapping details **freeze** the C-absorb count (§4.2).

**Restricted C bucket (hard rule, unchanged):** C contains only (a) micro-helper mirrors with named replacements, (b) tests retired because the implementation they pinned is absorbed/purged — with the retirement condition evaluated **after replacement lands**, not baseline liveness alone (v2), and (c) orphan tests after a compile gate proves the production island is gone. No brittleness-based deletion of any callback test; batch behavior tests stay, but **v2 withdraws the "zero-touch `pipeline/batch/**`" promise**: batch test files may be edited for the user-requested fingerprint-cache suspension bridge (§4.4, Gate G3′).

## 1. Inventory (measured, precise)

| Population | Files | `@Test` methods |
| --- | --- | --- |
| `eu/kanade/translation` tests (`app/src/test` + `testDebug`) | 319 (10 `@Test`-free fixtures) | **2142** (precise; 0 comment inflation) |
| Affected UI (14 files) | 14 | **75** (v1 said 80; −5 annotation inflation: DrawerTest 7→5, MultiSelectBatch 5→4, CancelledBatchReconciliation 4→2) |
| **Step-1 scope** | **333** | **2217** |

Affected-UI exact (file → tests): MangaScreenModelTranslationDrawerTest 5, MangaScreenModelMultiSelectBatchTest 4, MangaScreenModelCancelledBatchReconciliationTest 2, `ChapterTranslationSnapshotRegistryTest` 7, `EnqueueTranslationDownloadsTest` 3, `ReaderAutoTranslationUiStateTest` 9, `ReaderAutoTranslationLifecycleTest` 12, `ReaderTranslationOverlayBindingTest` 3, `ReaderPageTranslationKeyTest` 2, `InsertPageTest` 1, `TranslationQueuePositionAndPhasesTest` 8, `TranslationProgressSheetSubtitleTest` 3, `TranslationCompletionOracleTest` 12, `ChapterTranslationIndicatorRoutingTest` 4.

## 2. A — KEEP

Default for everything not in §3/§4. v2 removes the auto-island files from A (they are §4.2 C-absorb candidates) and keeps: all batch behavior tests, MR-05-relevant callback/forwarding tests, `PreparedPageBoundaryTest` (4), `PreparedPageRuntimeBoundaryTest` (9), `TranslationManagerAutoArbitrationTest`/`ReaderManualPreemptsAutoLeaseTest` are **not** A — they are §4.2. ChapterTranslator tests stay A **except** the optional §4.1 C2 decouple method; ChapterTranslator production is untouched.

**FastA** (exact; baseline state, with two files flagged for the §4.3 conditional transition):

| Group | Contents (tests) | Baseline |
| --- | --- | --- |
| Store fences | 12 classes as v2-listed in [v1 §2] unchanged: `ChapterTranslationStoreJournalCaptureTest` 23, `…ArtifactOpenProbeTest` 14, `ChapterCommitPointContractTest` 4, `PageSnapshotLostUpdateTest` 2, `StoreWriteDrainLockOrderTest` 1, `StoreOpenConcurrencyTest` 1, `StoreArtifactRetentionCloseTest` 1, `StagedJournalCreditOwnershipTest` 2, `ChapterTranslationStorePatchPageGraceTest` 2, `ReadOnlyPageViewTest` 1, `MutationClassificationTest` 1, `ChapterResetPreflightTest` 2 | 54 / 12 |
| Journal recover | `persistence/journal/*` whole package (7 files: Writer 30, Recovery 29, Snapshot 11, Compaction 8, ArtifactResolver 7, LagCrash 2, CaptureBarrier 1) | 88 / 7 |
| Layout math | `engines/rendering/*` whole package **minus the two §4.3-flagged files** (`AdaptiveBandPlannerTest` 9, `MaskTextRegionPlannerTest` 13) + explicit `InpaintBoxGeometryTest` 25, `BoxGeometryTest` 12 | 203 / 19 |
| Display projection | `translation/presentation/*` 96 (9 files), `eu.kanade.presentation.manga.components/*` 27 (4 files), `translation/model/*` 46 (8 files), `persistence/internal/StoreStatusProjectorRunRecordTest` 7, `pipeline/batch/progress/BatchPostPassProjectionTest` 3, `progress/DisplayReadyStageCountTest` 5, `workflow/ProjectorRebuildTruthTest` 5 | 189 / 25 |
| Reader/batch outcomes | `workflow/`: ChapterTranslatorTerminalExits 3, ChapterTranslatorQueueRestore 4, ChapterTranslatorBatchStartGuard 3, AdmissionCoreDelta 4, AdmissionConcurrency 2 = 16 (5 files); `scheduling/`: TranslationSchedulerManualOutcome 1 (**path: `scheduling/`, not workflow — v2 fix**), CancelSyncStoreWrite 6, CancelBatchLeaseSkip 5 = 12 (3 files); `coexistence/` 45 (17 files, as v1 list); `pipeline/batch/` 42 (8 files, as v1 list) | 115 / 33 |

**FastA baseline total: 649 tests / 96 files** (54+88+203+189+115; 12+7+19+25+33). If the §4.3 purge never happens, the two flagged layout files stay A and FastA is 671/98.

Selector pipeline (unchanged tiers from v1): Tier 1 package wildcards `eu.kanade.translation.persistence.journal.*`, `eu.kanade.translation.engines.rendering.*`, `eu.kanade.translation.presentation.*`, `eu.kanade.presentation.manga.components.*`, `eu.kanade.translation.model.*`; Tier 2 explicit class selectors for fences + outcomes; Tier 3 optional `@Tag("step1-fasta")` (user-executed build edit, `ProjectExtensions.kt:113`).

## 3. B — FIX (v2: seam-first, virtualization allowed where gates control the nondeterminism)

v1's blanket "real IO ⇒ cannot virtualize" is withdrawn. Revised rule: **inject dispatcher/scope seams + a bounded join policy first; then queue tests and pure cancellation cases may virtualize; only the physical probes below must keep real threads.** UI fixture conversion is **deferred beyond Step 1, not banned**.

| Host (exact) | Seam prescription (exact) | After seams |
| --- | --- | --- |
| `TranslationManagerReaderTeardownTest.kt:74,130,155,204` (real `SupervisorJob()+Dispatchers.IO` scopes), `TranslationManagerStartupReconciliationTest`, `TranslationManagerPendingAcknowledgementTest`, `TranslationManagerDeleteResetOrderingTest`, `TranslationManagerDownloadNotificationsTest`, `TranslationRequestGenerationFenceTest`, `ChapterTranslatorTerminalExitsTest`, `EvictionOffMainTest` (already `StandardTestDispatcher` Main) | Manager scope is already test-injected; add named `ioDispatcher` / `nativeDispatcher` / `diskDispatcher` constructor params to `TranslationManager`/pipeline seams (defaulting to `Dispatchers.IO`) so tests inject `StandardTestDispatcher` queues; adopt a **2 s `join(timeout)` policy** replacing unbounded joins | Queue-ordering and pure cancellation cases **may virtualize** under `runTest` |
| `ChapterTranslationStatusOffMainThreadTest` (latch probe, `Dispatchers.IO` ×2) | none | **KEEP REAL THREAD permanently** — the probe only passes if the caller truly suspends on a real executor |
| Native-exit physical probes (ownership artifact evidence #2/#3: occupancy until actual native exit, dispose-exactly-once) | none | **KEEP REAL THREAD** — physical lifetime property |
| Coexistence suite (`TranslationCoexistenceHarness` IO ×5; EngineEpochStopRace, P5HonestOutcomeTyping, PipelineStallWatchdog, RenderPermitReacquisition) | Harness-internal only; no virtualization of the shell | **KEEP REAL THREAD** for shell choreography; individual pure-cancellation methods may move to virtualized equivalents over time |
| `TranslationSchedulerTraceTest` (`delay(10)` poll, measured lease_wait, `@Timeout(60)`) | injectable trace sink seam (already global-swap based; constructor seam is the MR-parallel work) | **KEEP REAL THREAD** until the sink seam lands; lease_wait measurement stays real |
| `MangaScreenModelTranslationDrawerTest` (sleep@417), `MangaScreenModelMultiSelectBatchTest` (sleep@513), `MangaScreenModelCancelledBatchReconciliationTest` | none in Step 1 | **DEFERRED** — wide UI fixture conversion (real-main surrogate + ArchTaskExecutor) is a post-Step-1 work item, not an absolute ban |
| `RollingAutoCoordinatorTest.kt` — the whole file is §4.2 C-absorb; its Unconfined/Default dispatcher discipline (`RollingAutoCoordinator.kt:118` seam) becomes the **style template** for `ReaderTranslationQueueTest` | reuse seam pattern | queue tests gate on suspension points, no sleeps (the file's own documented discipline) |

**B count: 8 host groups; virtualization unlocked for queue/pure-cancellation cases after seams; 2 probe classes permanently real; UI conversion deferred.**

## 4. C — DELETE/REPLACE (v2)

### 4.1 Micro-helper mirrors (2 methods; replacements merge into EXISTING files — no new micro-test files)

| Target | Replacement location (existing file) | Preserved invariant |
| --- | --- | --- |
| `BatchParallelWindowBudgetTest.standard lane checkpoint…` (lines 149-170) | **+1 method in existing `pipeline/batch/StandardPipelineCoordinatorTest`**: drive the real standard-lane with a gap fixture → `BatchPass1Status.PAUSED`; full-corpus control → COMPLETE (`StandardLaneWorker.kt:338-351`) | X6: `hasGaps \|\| corpusFingerprint == null ⇒ PAUSED`, never PARTIAL-corpus COMPLETE |
| `BatchParallelWindowBudgetTest.S11 wave lookahead…` (lines 84-146) | **+1 optional method in existing `workflow/ChapterTranslatorQueueRestoreTest`** (pure-selection decouple of `ChapterTranslator.kt:418-450`; production ChapterTranslator itself untouched) | same-source lookahead: active + next same-source queued; group-by-source take(1)→take(2) |

### 4.2 C-absorb — auto-concurrency island retired AFTER `ReaderTranslationQueue` absorbs behavior (count FROZEN after queue-agent mapping)

Condition order (v2 correction): replacement parity first, implementation retirement second, test deletion last. Baseline liveness does **not** protect these files, and baseline non-liveness never justified deletion.

| Test file (exact path under `app/src/test/java/eu/kanade/`) | Tests | Action | Target in `scheduling/ReaderTranslationQueueTest` | Preserved invariant |
| --- | --- | --- | --- | --- |
| `translation/scheduling/RollingAutoCoordinatorTest.kt` | 31 | DELETE after replacement | queue admission, bounded N/N+1 demand, convergence, memory/source deferral, preparation failure, cancellation and committed-display precedence | Preserve safety/retry/display invariants; arbitrary configurable N-ahead and separate prepare-channel semantics are explicitly superseded. Do not recreate the auto coordinator in the queue. |
| `translation/scheduling/RollingAutoCoordinatorTraceTest.kt` | 5 | DELETE after replacement | queue trace-correlation section | One page operation trace and exactly one terminal; no duplicate paid/native work on promotion. Separate auto prepare/translate traces are superseded. |
| `translation/scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt` | 1 | DELETE after replacement | duplicate-registration/session fencing | Duplicate tap/viewport registrations share one compatible operation; stale session callbacks cannot create work. |
| `translation/scheduling/AutoWindowStateTest.kt` | 29 | DELETE after replacement | N/N+1 clamping, promotion/prune tests and existing PageDisplayProjection readiness tests | AutoSlotState hierarchy is deleted, not moved/recreated. Stage/display readiness comes from existing page models; arbitrary ahead counters are retired. |
| `tachiyomi/ui/reader/ReaderAutoTranslationUiStateTest.kt` | 9 | MERGE useful cases, then DELETE | Existing ReaderTranslationOverlayBindingTest and P5OutcomeProjectionTest | UI reflects current page projection, shared typed outcome and auto toggle; no independent slot readiness/severity or old ahead-window DTO. |
| `tachiyomi/ui/reader/ReaderAutoTranslationLifecycleTest.kt` | 12 | MERGE useful cases, then DELETE | ReaderTranslationQueueTest plus existing reader teardown/overlay fixtures | Speculative work is restricted to N/N+1; explicit manual work is separate demand; teardown rejects stale sessions/stream callbacks. N+1 need not be visible to prefetch. |
| `translation/workflow/TranslationManagerAutoArbitrationTest.kt` | 2 | MERGE, then DELETE | queue promotion, demand cancellation and existing manager/session tests | CURRENT_PAGE priority wins queued speculative admission; compatible same-page work is promoted without cancel/restart. |
| `translation/coexistence/ReaderManualPreemptsAutoLeaseTest.kt` | 1 | REPLACE superseded assertion, then DELETE | queue same-page promotion + native ownership regression | **Zero lease revocations/reacquisitions** for compatible promotion, same Job, zero repeated native/provider calls. The old revoke-AUTO-lease assertion is intentionally retired. |

**C-absorb totals (freeze-pending): 90 tests / 8 files.** The count freezes when the reader-queue agent publishes the per-file parity checklist; only then do deletions proceed file-by-file.

### 4.3 C-orphan — conditional compile gate (evidence verified at baseline)

| Test file | Tests | Baseline production wiring (verified) | Gate to delete |
| --- | --- | --- | --- |
| `app/src/test/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlannerTest.kt` | 9 | `AdaptiveBandPlanner` referenced only by the statically disconnected rendering island | Delete with the exact island after active layout baseline, reference checks and compile/regression gates. |
| `app/src/test/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlannerTest.kt` | 13 | `MaskTextRegionPlanner` referenced only by the same island | Same paired-purge gate; keep active layout/geometry tests. |
| `app/src/test/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecoveryTest.kt` | 4 | Helper has no production caller; four calls only in this test | Delete with the orphan helper; preserve tests of active backend fallbacks. |

**C-orphan totals: 26 tests / 3 files**, deletion strictly gated on compile+reference evidence, never on test content.

### 4.4 Fingerprint-cache suspension bridge (user-requested; batch tests MAY be touched)

`pipeline/batch/SourceFingerprintCacheTest` (new, §5) implements MR-06B's suspending identity-keyed resolver. **Existing batch tests may be edited for imports/callback adaptation to the suspension bridge; test bodies asserting batch behavior stay unchanged.** This explicitly replaces v1's "zero-touch batch" gate.

## 5. New test files (exactly 4, user-defined paths)

| New file (exact path under `app/src/test/java/eu/kanade/`) | Owner | Methods | Covers |
| --- | --- | --- | --- |
| `translation/scheduling/ReaderTranslationQueueTest.kt` | reader-queue agent | **freeze-pending on the §4.2 parity checklist (plan target 12)** | absorbed auto-island invariants |
| `translation/pipeline/SinglePageNativeOwnershipTest.kt` | MR-00 ownership plan (drafted in Step 1, sequenced MR-00) | 4 | claim/abandon exactly-once; dispose exactly-once after native exit; no nested admission; late handoff launches no new paid call |
| `translation/architecture/TranslationPackageBoundaryTest.kt` | Step1 owner | scenarios rather than fixed method count | **engines/** and **presentation/** cannot import/reference executable pipeline implementations; include alias/wildcard/FQ positive detection and comment/string negative fixtures. |
| `translation/pipeline/batch/SourceFingerprintCacheTest.kt` | Step1 owner (MR-06B) | 5 | suspending identity-keyed resolution; coalesced misses; no hashing inside `computeIfAbsent`/monitor; cancelled subscriber never loses another caller's result; provisional evidence never authorizes reuse |

**New-file totals: +4 files.** Test-method counts are provisional implementation choices, not a coverage target; ownership/queue race and projection scenarios in the primary documents must all be covered. UI-specific preserved cases may live in existing UI projection fixtures rather than ReaderTranslationQueueTest**.**

### Net accounting (baseline → post-plan)

| Bucket | Files | Methods |
| --- | --- | --- |
| Mirrors (§4.1) | 0 net (2 methods removed from `BatchParallelWindowBudgetTest`, +2 methods added to existing files) | 0 net |
| C-absorb (§4.2) | −8 (freeze-pending) | −90 pending parity-freeze |
| C-orphan (§4.3) | −3 (conditional gate) | −26 conditional |
| New (§5) | +4 | Scenario coverage required; method count not pinned |
| **Frozen-now subtotal (excl. pending/conditional)** | **0** | **0** |
| **Projected after replacement/purge gates (−11+4 files)** | **−7** | No method-count reduction target |

The file-count forecast is path-derived. The 90 auto-specific baseline tests include both preserved invariants and deliberately superseded policy, so method counts cannot serve as a parity oracle. All deletions remain conditional on their replacement/purge gates.

## 6. Gates

- **G1 Parity-before-delete:** every §4.2 file deletes only after its preserved safety/outcome invariant has replacement coverage in the named queue/ownership/UI fixture; record explicitly superseded behavior separately.
- **G2 Contract fidelity:** package-only moves preserve bodies. Changed runner signatures require fake/driver adaptation, and deliberately changed product behavior requires new assertions; preserved boundary invariants must remain covered. Do not force old preemption or auto-slot assertions to survive unchanged.
- **G3′ Batch bridge scope:** `pipeline/batch/**` edits are limited to the §4.4 suspension bridge (imports/callbacks); batch behavior assertions unchanged; any wider batch edit leaves this plan's scope.
- **G4 Execution (when authorized):** FastA selector green isolated + full-suite ×3 CI matrix; failures triaged per host, never retried away.
- **G5 Probe discipline:** OffMainThread and native-exit probes stay real-thread; virtualized conversions require the §3 seam-first recipe.
- **G6 Evidence standard:** future C additions require implementation-retirement or island-purge evidence, never brittleness language.
- **No flakiness guarantee is made or implied anywhere in this plan.**

## 7. Priority

1. **P1 — §4.1 mirror-merge methods** into `StandardPipelineCoordinatorTest` + `ChapterTranslatorQueueRestoreTest` (unblocks the only unconditional deletions).
2. **P2 — Queue-agent parity checklist** → freezes §4.2/§5 counts; then file-by-file absorb deletions.
3. **P3 — §3 seam-first conversions** (io/native/disk dispatcher + 2 s join policy) to unlock queue/cancellation virtualization.
4. **P4 — §4.3 orphan purge window** (compile-gated) and `SinglePageNativeOwnershipTest`/`SourceFingerprintCacheTest`/`TranslationPackageBoundaryTest` authoring.
5. **P5 — Tag pipeline Tier 3** (user-executed) after the tree stabilizes.

### Revision log

- **v2:** (1) island target corrected to DELETE-after-absorption (not relocation); the six affected classes re-homed from A to §4.2 with per-file invariants and post-replacement retirement condition. (2) Removed fictional queue/wave test files; ReaderTranslationQueueTest is the distinct reader queue (not ChapterTranslator wave policy); ChapterTranslator untouched except optional pure-selection decouple method. (3) Mirror replacements merged into existing StandardPipelineCoordinatorTest/ChapterTranslatorQueueRestoreTest — no new micro-test files. (4) Exactly 4 new files at user-defined paths incl. TranslationPackageBoundaryTest and SourceFingerprintCacheTest; net accounting split frozen/pending. (5) Three orphan candidates added with verified baseline wiring and compile gates. (6) Batch zero-touch promise withdrawn for the user-requested suspension bridge (G3′). (7) Count corrections: affected-UI 80→75 (annotation inflation), display-projection group 26→25 files, TranslationSchedulerManualOutcomeTest path workflow→scheduling; comment-`@Test` inflation verified zero. (8) B bucket seam-first recipe (io/native/disk dispatchers + 2 s join policy), virtualization for queue/pure-cancellation cases, permanent-real probes limited to OffMainThread + native-exit, UI conversion deferred not banned.
