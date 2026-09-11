# T924 Zero-Legacy Wave — D1 Evidence Report

Worktree: `TachiyomiAT-t924-impl` (branch `t924/batch-profile-pipeline`, HEAD `77ba628` + uncommitted D1 changes)
Scope: remove the FF-01 A/B flag, unify dispatch on the surviving two lanes,
rework resume to the zero-legacy semantics, delete `SequentialBatchCoordinator`,
rework the test pack, full sweep green.
Route (Director-approved): Fix → A/B → default ON → **delete legacy** (this wave).

## Result

`./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`:
**242 suites / 1763 tests / 0 failures / 0 errors.**
Baseline (pre-D1): 244 suites / 1794 tests / 0 failures.
Delta: −2 suites, −31 tests, 0 failures — accounted below.

## Task-by-task changes

### TASK 1 — FF-01 flag removal

- `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt` —
  `translationBatchProfilePipeline` preference property deleted (−9 lines).
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt` —
  FF-01 developer switch removed from the Experiments group.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt` —
  shell's flag read deleted; shell-level OFF+COMPLETE
  `resumeCompletedOutcome` fast path deleted with its dead helper.

### TASK 2 — unified dispatch (no flag input)

- `BatchChapterTranslator.kt:1124` `profilePipelineDispatchKind(engineCategoryIsStandard)` —
  flag input dropped; STANDARD → `STANDARD_PIPELINE`, else `PROFILE_PIPELINE`.
  The single dispatch call site is `BatchChapterTranslator.kt:658`.
- `ChapterProfileBatchCoordinator.kt:3178` `dispatchKind` — flag parameter dropped;
  `BatchCoordinatorKind.LEGACY_SEQUENTIAL` deleted.
- `dispatchedFlaggedLane`, `awaitLeaseHandback` deleted with the legacy schedule.
- `ChapterProfileBatchCoordinator.kt:3029` — `standardPageTerminalAtTranslate`
  promoted to `internal` companion fun: the standard tail's lease grant races the
  reader, so the shell re-evaluates the terminal predicate UNDER the lease
  (`BatchChapterTranslator.kt:~695-720`) and skips re-pay without preempting.
- Shell teardown finally (`BatchChapterTranslator.kt:~1020-1046`):
  `cancelPageStageWork` per write-identity page + `releaseAllPageLeases` + flush.
- PAUSED branch (`BatchChapterTranslator.kt:~969-980`) uses the pause-aware
  reconciler; the COMPLETED branch (`:~993-1013`) uses
  `reconcileFlaggedCompleted` (both lanes end runs translation-terminal without
  in-pass render).

### TASK 3 — resume rework

- `decideResume` / `resumeCompletedOutcome` DELETED. Resume is the
  coordinator's `resumeFinalizeOrComplete` (ST-14/LI-2, untouched) for both lanes.
- `ChapterProfileBatchCoordinator.kt:3229` — `frozenRunConfig` hardcodes
  `flagProfilePipeline = true` in the frozen snapshot; the field keeps its schema
  position (`:3199` KDoc) and still participates in the run-config fingerprint
  (`runConfigFingerprint`, pinned by `StandardPipelineCoordinatorTest:615`).
- `ChapterProfileBatchCoordinator.kt:2148` `t924PageTerminalAtFinalize` —
  committed terminal statuses (READY/PARTIAL/FAILED/SKIPPED/TEXTLESS) now return
  true BEFORE the generation guard: a COMMITTED stage is durable across
  generations; only OPEN states are generation-owned (restarts never strand or
  durable-fail a prior run's committed work).
- `TranslationPipeline.kt:729` `attachToOwnerTerminal` — denied-lease attach
  predicate expanded: reader lease observes an owner that is translation-terminal
  (`READY`/`PARTIAL`/`SKIPPED`) or failed/textless/rendered. Zero-legacy batches
  never render in-pass, so READY/PARTIAL/SKIPPED are the terminal evidence.

### TASK 4 — SequentialBatchCoordinator deleted

- `app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt`
  deleted (−1326 lines across the main diff). Uniquely-owned symbols resolved;
  shared machinery (lane workers, overlap scheduler, tracker, run records)
  retained.

### TASK 5 — test rework (per-test disposition)

Deleted suites (3):
| Suite | Tests | Reason |
|---|---|---|
| `pipeline/batch/SequentialBatchCoordinatorTest.kt` | 17 | pinned the deleted legacy coordinator |
| `pipeline/batch/OcrPreflightFlagOffMidRunTest.kt` | 5 | pinned FF-01 OFF DropToLegacy/TreatAsFinished mid-run behavior — the flag and the decision tree are gone |
| `pipeline/batch/BatchPhase4TraceWiringTest.kt` | 4 | pinned legacy SBC trace wiring |

Added suite (1):
| Suite | Tests | Purpose |
|---|---|---|
| `pipeline/batch/OcrPreflightQueueRestoreTest.kt` | 2 | re-homes the surviving queue-restore obligations (flag-OFF pins dropped) |

Rewritten (zero-legacy semantics):
- `coexistence/D3ReaderOwnedPageAcrossBatchTest.kt` — legacy in-pass
  defer-and-rescan was SBC machinery; contract re-expressed as reader priority +
  durable resume (never preempted, zero paid calls under the reader lease,
  follow-up run completes the chapter, exactly-once oracle, 2/2 tracker).
- `coexistence/T918CancelledBatchRestartTest.kt` — committed-terminal-across-
  generations finalize; the +1 p1 decode is its legitimately-pending remainder
  inpaint (re-seed deletion exposes it) — assertion pins `decodeP1BeforeRestart + 1`.
- `coexistence/D2ManualBatchInterleavingTest.kt`, `BatchDispatchResumeWiringTest.kt`,
  `T924FeatureFlagsTest.kt`, `ChapterTranslatorQueueRestoreTest.kt`,
  `BatchPostPassProjectionTest.kt`, `Stage7Finalize*`, `ProfilePipelineDispatchGateTest.kt`,
  `StandardPipelineCoordinatorTest.kt`, `StandardLaneMultiPageCompletionTest.kt`,
  `NormalMangaIsolationTest.kt`, `D6ForegroundFairnessTest.kt`,
  `D10PartialDownloadAdmissionTest.kt`, artifact retire/stale-manifest suites,
  `ResetRetiresActiveRunTest.kt`, `StoreStatusProjectorRunRecordTest.kt`,
  `OcrPreflight*`, `ChapterAnalysisPhaseCoordinatorTest.kt`,
  `ChapterProfileFreezeCoordinatorTest.kt`, `ProfileEnvelope*` — mechanical
  fixture edits (frozenRunConfig without flag, coordinator ctor without flag,
  projections keyed on the flagged completion).
- `coexistence/TranslationCoexistenceHarness.kt` — (a) `artifactAuthorityStore`
  seeds cleaned-companion JPEG bytes + a passing `displayBaseProbe` stub:
  production's manual lane writes the companion before a display-ready commit,
  the harness stubs the encoder, and without the seed a manual commit under
  ARTIFACTS authority was rejected on the display-base probe (which silently
  re-paid the page); (b) `batchInpaint` fake sets `page.inpaintStatus = READY`
  (fake fidelity — the real pageInpainter settles the in-memory page; without it
  the tracker marks INPAINT failed).

### Session additions required by the coexistence pack (production changes)

1. **Tracker settle for translation-terminal pages** —
   `TranslationBatchProgressTracker.kt:484-499` (`progressStage`): DONE now also
   requires OR-conditions on `translationStatus ∈ {READY, PARTIAL, SKIPPED}`
   (kept after the display and failed checks). Both surviving lanes commit
   translations WITHOUT an in-pass render, so a healthy page's display stays
   ORIGINAL_ONLY until the reader re-derives it; keying DONE solely on
   `displayReady` left every healthy page QUEUED in the terminal snapshot (the
   deleted legacy render join used to own this settle via `markRenderDone`).
   Applies to the live progress UI too — intended under zero-legacy.
2. **Checkpoint-adoption hydration on preflight reuse** —
   `ChapterProfileBatchCoordinator.kt:~380-420`: the reuse branch now adopts the
   validated checkpoint's OCR snapshot into the live store via the EXISTING
   envelope-lane helper `adoptCheckpointSnapshot` before counting the page as
   reused. Root cause fixed: on a REOPENED store (real restart, or the
   memory-only artifact-authority fixture) the in-memory page record is a
   placeholder (`ocr=PENDING`, no blocks). The translate tail's dependency gate
   (`WAIT_FOR_DEPENDENCY`/`DEPENDENCY_INCOMPLETE`, `dependencyReadyAfterNative`)
   then silently skipped the page's paid translation and the run "completed"
   without paying — D9's resumed-death cycle timed out because the provider
   barrier never arrived. If adoption cannot back the page (unreadable sidecar,
   racing owner), the branch falls through to a fresh OCR run — never plans
   against fabricated content. Diagnosed with temporary production probes
   (removed).
3. `ChapterArtifactStore.kt` `retireActiveRun` KDoc — stale mention of the
   deleted `resumeCompletedOutcome` replaced with a reference to the surviving
   `resumeFinalizeOrComplete`.
4. `ChapterTranslatorQueueRestoreTest.kt:223` — stale expectation corrected:
   after an interrupted preflight the surviving run record is `OCR_PLAN` with
   `ocrPagesDone=1` (the phase pointer advanced with p1's checkpoint — that IS
   the resume evidence), not `RUN_SNAPSHOT`.

## TASK 6 — full sweep

`--tests "eu.kanade.translation.*"`: **242 suites / 1763 tests / 0 failures / 0 errors**
(baseline 244/1794/0). Suite delta: 3 legacy suites deleted, 1 re-homed suite
added (net −2). Test delta: −26 deleted + 2 added + 7 consolidated in rewrites = −31.
Coexistence + ui packages: 94/94 green.

## Grep audit (legacy symbols)

`translation_batch_profile_pipeline | flagProfilePipeline | SequentialBatchCoordinator |
LEGACY_SEQUENTIAL | TreatAsFinished | DropToLegacy | resumeCompletedOutcome | decideResume`
across `app/src/*` (main/test/androidTest/debug/dev/standard): **18 hits in 11 files,
zero in non-test source sets.**

Production (5) — all durable-schema compatibility or the mandated hardcode:
- `artifact/ChapterRunRecord.kt:68` — `flagProfilePipeline: Boolean? = null`
  schema field kept nullable for backward-compatible deserialization of
  already-durable run records (removal would break reads of pre-existing sidecars).
- `pipeline/batch/ChapterProfileBatchCoordinator.kt:305,3130,3199,3229` — schema
  comment; `COUNTER_FLAG = "flagProfilePipeline"` durable counter KEY (kept so
  pre-field records parse; value always 1); KDoc on the frozen snapshot field;
  the TASK-3-mandated `flagProfilePipeline = true` hardcode.

Tests (13) — all KDoc/comments documenting deleted machinery, or schema pins:
- `StandardPipelineCoordinatorTest.kt:615` — LIVE pin that the frozen flag
  participates in `runConfigFingerprint` (durable-schema behavior).
- `StandardPipelineCoexistenceTest.kt:84` — LIVE pin that the frozen snapshot
  is `true` (zero-legacy semantics).
- Remaining 11: comments/KDoc naming the deleted flag/SBC/decision tree as
  deleted (T924FeatureFlagsTest, BatchDispatchResumeWiringTest ×3,
  OcrPreflightQueueRestoreTest ×2, Stage7FinalizeCoordinatorTest,
  ChapterTranslatorQueueRestoreTest, D3 test, harness pipeline-diagram KDoc).

## Deviations from the mission text

1. Tracker `progressStage` changed in production (beyond the listed tasks): the
   terminal-snapshot settle D3 requires does not exist as a phase event to
   replay — `markRenderSkipped` would not settle (`progressStage` never inspects
   a SKIPPED render). The DONE derivation itself had to learn the zero-legacy
   terminal shape. Minimal form: three OR-conditions in one `when` branch.
2. Preflight checkpoint-adoption hydration (above): a genuine production gap in
   the zero-legacy resume path, found by the D9 death-cycle test; fixed with the
   existing envelope-lane adoption idiom, not new machinery.
3. `t924PageTerminalAtFinalize` ordering: committed-terminal check placed BEFORE
   the generation guard (durability of committed stages across generations).

## D2 candidates (found during D1, deliberately not addressed)

1. **Tracker RENDER stage never processes under zero-legacy**: no lane emits
   render events for translatable pages, so `perStage[RENDER].processed` stays 0
   and the processed/total fraction tops out at 4/5 per page even at terminal.
   Candidate: emit `markRenderSkipped` per expected page at the shell's
   COMPLETED path (cosmetic/UI accuracy; the settle itself is handled by the
   progressStage fix).
2. **finalize stranded-write vs COMPLETED projection tension**:
   `drainFinalizeAndComplete`'s stranded sweep can durably mark pages FAILED,
   while the shell's COMPLETED path still projects chapter TRANSLATED via
   `reconcileFlaggedCompleted` (which never manufactures failures). Observed in
   the D9 diagnosis (stranded=1 then COMPLETE before the fix). The two
   projections should share one authority.
3. **Manual-render-under-artifacts probe gap** — pre-existing, baseline-verified,
   not a D1 regression.
4. **`COUNTER_FLAG` orphan** — the durable counter key `flagProfilePipeline`
   always freezes 1; kept for record compatibility, worth retiring with a
   schema version bump.
5. **`awaitPageLeaseRelease`** — possibly dead after `awaitLeaseHandback` deletion.
6. **Unused T918 companion helpers** in the reworked restart test.
7. **Cross-origin OCR merge drops machine translations** — `mergeOcrLocked`
   preserves translationStatus (via `detachedCopy`) but replaces blocks, so
   machine translation text is dropped except user-edited blocks.
8. **Stale-resume-plan re-OCR churn on externally-completed pages** — plan-time
   decisions frozen before the preflight can disagree with live terminal states.

## Verification

- Full sweep: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"` —
  242/1763/0 (BUILD SUCCESSFUL).
- Coexistence + ui: `--tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.ui.*"` — 94/94.
- No temporary diagnostics remain (grep `D9DIAG|D2DIAG|STDSEAM|STOREWRITE|STOREREJECT|ARTPUB|DBG probes in main` → clean; harness `DBG` prints are pre-existing committed fixtures).
