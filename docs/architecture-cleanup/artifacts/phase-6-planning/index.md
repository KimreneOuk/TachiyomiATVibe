---
title: "Phase 6 audit and implementation plan - simpler batch orchestration"
kind: spec
status: 0
comments: none
---

# Authority and source baseline

This audit maps **Phase 6** of the TachiyomiATVibe architecture cleanup per the master roadmap (`docs/architecture-cleanup/artifacts/translation-simplicity-investigation/phases/index.md`). It synthesizes the thorough analysis of the Phase 6 batch orchestration auditor (`agent f0170e5d`) across all 26 files in `app/src/main/java/eu/kanade/translation/pipeline/batch/` (~560KB, ~10.7k lines).

Phase 6 consolidates batch-only configuration, per-run state, scheduling, and contextual planning around clear owners, while removing redundant processing layers supplied by Phase 5 and dead legacy branches.

---

## 1. Batch Inventory & Structural Complexity

### 1.1 The Hub-and-Spoke Forwarding Problem
- `BatchChapterTranslator.kt` (1,277 lines) acts as a shell constructing `BatchLaneWorkers`, `OverlapScheduler`, and `ChapterProfileBatchCoordinator`. It passes 24 constructor parameters, **21 of which are function-typed forwarders**.
- `ChapterProfileBatchCoordinator.kt` (1,636 lines) acts as a massive forwarding hub passing context structs (`PreflightWorkerContext` with 18 callbacks, `EnvelopeDispatcherContext` with 14 callbacks, `FinalizeWorkerContext` with 9, `StandardLaneWorkerContext` with 8) with ~40 forwarding methods.
- Per-run state is fragmented across 7 pairwise-passed maps and flags (`translationRegistry`, `batchWriteIdentities`, `durableFailurePageKeys`, scheduler deferral sets, `BatchPageTraceRegistry.deferredReasons`, `renderMutexes`, `HeldBitmapRegistry`).

### 1.2 Unreachable and Dead Code Identified
1. **Dead AI streaming machinery in `BatchLaneWorkers.kt` (lines 931–1119)**:
   - `StreamingChunkPlanner`, `pendingAiEmissions`, `contextBlockedPages`, and the entire `isAi` branch of `translate()` are unreachable because AI translation routes exclusively through `EnvelopeDispatcher` → `ProfileEnvelopeExecutor`. `translateOutcome` is only called for the STANDARD lane (`isAi == false`).
2. **Unreachable in-pass render body in `BatchRenderJoin.kt` (lines 120–365)**:
   - Under the no-render batch redesign, `tryRender` is only reached from `SKIP_ALL` shortcuts where renderStatus is already `READY`. The full mergeRender, `RenderColorEstimator`, and layout span machinery are unexercised. Live duties are only `publishPersistedLayoutForCompletedPage` and `SKIP_ALL` cleanup.
3. **Dormant analysis vestiges**:
   - `analysisChunkRunner` parameter (`@Suppress("UNUSED_PARAMETER")`), `AnalysisChunkExecutor`, and `AnalysisEngineTransport` have no production callers.

### 1.3 Duplicated Decision Sites
- **Terminal/source-identity predicates duplicated in 6 places**: `standardPageTerminalAtTranslate`, `sourceIdentityMatches`, `pageAuthoritativelyDone`, `t924PageTerminalAtFinalize`, `currentSourceIdentityMatches`, and fresh-snapshot re-check.
- **Display-complete render stamp duplicated in 2 places**: `OverlapScheduler.stampRenderTerminalOrphans` vs `RecoveryWorker.stampRenderTerminalIfDisplayComplete` (recovery-owned display-tail drain already heals and supersedes the scheduler sweep).
- **Traced lease acquisition repeated in 6 places** with `pageTraceRegistry?.withLeaseWait(...) ?: tryAcquire...`.
- **Durable failure writers implemented independently across 5 classes**.
- **Context frontier tracked in 2 parallel places** (shell's `BatchContextFrontier` vs executor's `buildFrontier`).

---

## 2. Coexistence with Phase 4 Queue & Provider Sub-limits

Batch and Reader scheduling are structurally distinct and must **never** be merged:
1. **Reader Queue (`ReaderTranslationQueue`)**:
   - Session-bound demand queue, `CURRENT_PAGE` (foreground) and `PREFETCH_NEXT` (background), explicit backlog 8, max 2 automatic workers, serial `LOCAL_COMPUTE` permit.
2. **Batch Orchestration**:
   - Chapter-wide, chunked envelope/standard execution.
3. **The 4 Explicit Coexistence Seams**:
   - **Store Page Leases**: BATCH attaches, never preempts MANUAL/AUTO; yields on contention (`BatchContentionRejectedException`).
   - **Native Lane Admission**: Cooperates under `DevicePagePermitGate` / `EngineLane` with `yield()` courtesy between pages.
   - **Provider Governor Sub-limit**: Batch traffic runs through `SharedBatchRequestSublimitGate` (nested bucket 2: 15 RPM, `maxInFlight = 1`), while Reader INTERACTIVE requests skip the sub-limit and take priority.
   - **Bitmap Memory Budget**: Shared process-wide `CrossOriginBitmapBudget` 2-slot decode permit.

---

## 3. Target Batch Architecture & Ownership

```
pipeline/batch/
  BatchChapterTranslator.kt     # Shell: trace, generation, engine setup, teardown (~600 lines target)
  BatchRunConfig.kt             # One frozen per-run config (langs, engine/provider identity, credential sha, chunk profile)
  BatchRunState.kt              # Single per-run mutable state container replacing 7 scattered maps/flags
  BatchRunController.kt         # Slimmed coordinator: run-record publication + phase sequencing + resume entry
  lanes/
    OcrPreflightLane.kt         # PreflightWorker: hydration adoption, corpus fingerprint, gap detection
    StandardTranslateLane.kt    # StandardLaneWorker: per-page standard translate loop, X6 gap invariant
    EnvelopeTranslateLane.kt    # EnvelopeDispatcher + ProfileEnvelopeExecutor: token-fit whole-page splits, replan, CAS commits
  recovery/
    BatchResumePlanner.kt       # Resume planning, gate decisions, alias resolution
    RunResumeGate.kt            # RecoveryWorker: FINALIZE/COMPLETE gates, work-product verification, display-tail drain
    FinalizeDrain.kt            # FinalizeWorker: serial inpaint drain, layout publication sweep, single COMPLETE
  overlap/
    InpaintOverlapScheduler.kt  # Simplified overlap drain (continuous loop vs drain-only pending measurement)
  support/
    BatchWriteGate.kt           # Write identity + owner-proof heal fencing
    BatchRenderPublication.kt   # Slimmed join: persisted layout publication + SKIP_ALL cleanup
    BatchPageTraceRegistry.kt   # Page runs and deferral traces
    BatchMemoryGovernance.kt    # HeldBitmapRegistry + CrossOriginBitmapBudget
    BatchAdmissionProbe.kt      # Routing and eligibility probes
    BatchExecutionContracts.kt  # Shared data types (OcrCorpus replaces AnalysisCorpus)
  progress/                     # Unchanged progress tracker and registry
```

---

## 4. Phase 6 Execution Slice Plan

| Slice | Focus | Deliverables & Acceptance Gate |
|---|---|---|
| **S1** | **BatchRunConfig Consolidation** | Build frozen configuration once per run; eliminate duplicate preference reads and duplicate credential hashing. Gate: Run fingerprint byte-stability tests pass. |
| **S2** | **Retire Dead AI Streaming Code** | Remove dead `StreamingChunkPlanner`, `pendingAiEmissions`, and `isAi` branch from `BatchLaneWorkers.translatorWorker`. Gate: Standard lane and envelope test suites green. |
| **S3** | **Unified Lease Acquire & Fenced Stamp** | Consolidate 6 traced lease-acquire and 3 guarded post-acquire stamp call sites into one helper. Gate: Lease contention and yield tests pass. |
| **S4** | **Consolidate Durable Failure Writers** | Unify 5 scattered failure writing methods under one recovery-owned failure writer. Gate: Failure ledger and recovery tests pass. |
| **S5** | **Pure Terminal Predicate Module** | Extract the 6 duplicate terminal/source-identity checks into a single pure predicate module. Gate: Partial/failed/terminal and finalize gate tests pass. |
| **S6** | **Single Context Frontier per Run** | Unify the shell and executor frontiers into one `BatchContextFrontier` instance. Gate: Contextual ordering and gap tests pass. |
| **S7** | **BatchRenderJoin Slim-down** | Remove the unreachable in-pass render body (~250 lines) after proving zero reachability; retain persisted layout publication and `SKIP_ALL` cleanup. Gate: Persisted layout and reader display tests pass. |
| **S8** | **Overlap Scheduler Decision Slice** | Evaluate FINALIZE overlap telemetry (`overlapInpaintsExecuted` vs `serialInpaintsExecuted`); measure whether the continuous loop/window/nudge machinery is justified or can be simplified to a commit-hook drain. Gate: Throughput/memory metrics recorded; contention tests pass. |
| **S9** | **Worker Inversion & Hub Elimination** | Workers directly constructor-inject capability collaborators (from Phase 5) and run state; eliminate callback structs (`PreflightWorkerContext`, etc.) and forwarding methods in coordinator and lane workers. Gate: Whole-batch integration and interrupted/restart tests pass. |
| **S10** | **Naming & Schema Cleanup** | Rename `analysis/AnalysisCorpus` → `OcrCorpus`; remove dormant `analysisChunkRunner`; clean up deprecated flag ceremony while preserving schema compatibility. Gate: Record schema compatibility tests pass. |
