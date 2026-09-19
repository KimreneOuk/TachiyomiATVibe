# Ticket 07 Review — Wire B1→B4 batch path into the normal translation flow behind the device gate

- Reviewer: Reviewer agent (independent audit; no implementer evidence trusted without reproduction)
- State under review: branch `main`, HEAD `57a8e0e`
  (`b96ce1c` "feat(translation): activate Paddle OCR microbatches" on merge `ba3c729`,
  plus fixup `57a8e0e` — 1-line duplicate `routeOverride` parameter removal in
  `OnnxRuntimeProvider.kt`)
- Worktree: main checkout, clean at review start and end (only pre-existing
  untracked plan/handoff files; none staged by the implementer)
- Verdict: **PASS — no retry needed** (6 non-blocking observations)
- Method: full 12-file combined diff read line-by-line; `PaddleOcrDevicePolicy`,
  `PaddleOcrBatchActivationPolicy`, `PaddleOcrRollingP95Policy`,
  `PaddlePageOcrCoordinator`, `RoiPageRecognitionEngine` init/analyze/close read in
  full; all gradle runs below executed by me.

## Gate 1 — Production default remains B1: PASS

- Preference default `PaddleOcrRecognitionBatch.B1`
  (`TranslationPreferences.paddleOcrRecognitionBatch()`, getEnum default).
- `PaddleOcrDevicePolicy.resolveActivation` short-circuits B1 BEFORE every gate
  (lines 42-49): `activeBatchSize=B1`, `reason=b1_default`,
  `forceCpuB1EmergencyFallback=false`. B1 can never produce an emergency.
- Release build: `PADDLE_BATCHING_STAGED=false` → the new BuildConfig cap branch
  is not taken; requested batch flows raw → identical resolution to `ba3c729`.
  `providerConfiguration = null` (unchanged else-branch) → legacy session path.
  Coordinator created with `validatedBatchSize = activation.activeBatchSize = B1`.
  Release is byte-identical to today.
- Engine `close()` resets governor + reason (`not_paddle`).

## Gate 2 — Activation truth table (independently verified in code + tests): PASS

Order of checks in `resolveActivation` is decisive: **B1 → staged → provider-null
→ isConfirmed (opt-in branch inside, with its own thermal check) → confirmed
(thermal)**.

| Request | Build | Profile | Provider | Thermal | Resolved |
|---|---|---|---|---|---|
| B1 | any | any | any | any | **B1**, `b1_default`, no force |
| B2/B4 | debug (staged) | unconfirmed | selected | OK | **requested**, `debug_provisional_optin`, no force |
| B2/B4 | debug (staged) | unconfirmed | selected | >2 | **CPU B1 force**, `thermal_guard` |
| B2/B4 | debug (staged) | any | null | any | **CPU B1 force**, `provider_not_selected` |
| B2/B4 | release (staged=false) | any | any | any | **CPU B1 force**, `staged_flag_off` |
| B2/B4 | staged | confirmed | selected | OK | **requested**, `confirmed` |
| B2/B4 | staged | confirmed | selected | >2 | **CPU B1 force**, `thermal_guard` |

- Debug opt-in requires staged=true AND a selected provider — both checks precede
  the opt-in branch. Thermal guard re-checked inside the opt-in branch.
- Concern C (silent cap): the BuildConfig cap applies ONLY when
  `PADDLE_BATCHING_STAGED` is true. Release B4 opt-in is NOT capped — the raw B4
  request reaches `resolveActivation` and is refused honestly
  (`staged_flag_off` → CPU B1 force). Verified in code; release staged=false.
- BuildConfig matrix confirmed in `app/build.gradle.kts`: default 1/false,
  debug 4/true, benchmark 4/true.
- New tests (4 in `PaddleOcrDevicePolicyTest`) pin opt-in, release-unconfirmed,
  debug-thermal, and the preference→activation mapping
  (B1→`b1_default`, B2/B4→`debug_provisional_optin`).

## Gate 3 — Governor is real, consumed production code: PASS

- `PaddleOcrRollingP95HysteresisDowngradePolicy` owned by
  `RoiPageRecognitionEngine` (`paddleBatchGovernor`), created at Paddle engine
  init with `initialBatchSize = maximumBatchSize = activation.activeBatchSize`
  (cap = gate-approved size; `require(initial <= maximum)` in the policy).
- Per-microbatch latencies: coordinator seam extended —
  `PaddlePageBatchTrace.batchLatencyMs` captured per batch from
  `engine.lastBatchTelemetry?.latencyMs` (non-finite/negative guarded, fallback =
  admission-wait ms, so `record()`'s require never trips), recorded by
  `recordPaddleBatchLatencies` in the production `analyze()` path
  (`.also` after `recognizePage`, under `nativeGuard`).
- DOWNGRADE is page-boundary only: the coordinator swap happens after the whole
  page completed (`governor.activeBatchSize != coordinator.validatedBatchSize` →
  new `PaddlePageOcrCoordinator` for the NEXT page). Mid-page mutation impossible.
- RECOVER capped at the user-requested/gate-approved size: `recover()` clamps
  `next` to `maximumBatchSize`; test proves B4→B2→B1 ladder and recovery capped
  at B4 (never B8). At B1 the governor is provably inert (lower(B1)=B1; recover
  clamps back; no DOWNGRADE/RECOVER decisions emitted).
- Provider is NEVER changed by the governor: the swap reuses the same engine
  session; the governor has no provider authority. No CPU fallback from governor.
- One-line log on non-HOLD decisions, format exactly as handed off:
  `[paddle_batch_governor] action=... from= to= p95Ms= cap= reason=`.
- Honesty telemetry: stage summary now carries `batched= batchSize= governorReason=`
  (per-page summary, bounded).

## Gate 4 — Coordinator page-scoped fences intact at B4: PASS

- Diff touches only: `validatedBatchSize` visibility (private→internal),
  trace gains `batchLatencyMs`, dispatcher gains `executorLatencyMs` lambda,
  rollout-policy doc comment. Nothing else.
- Page scoping verified by full-file read: per-page dispatcher+planner keyed to
  `pageGeneration`, ownership tokens
  `pageId:generation:regionIndex:phase:leafIndex`, drain/equality checks,
  `isClosed` checks, single `batchCallMutex` per engine session (ticket-03
  serialization follow-up still holds — one coordinator alive at a time, swap
  only after page completion, pages serialized by `nativeGuard`).
- `validatedBatchSize` flows to planner construction and the recognizeBatch
  request. No cross-page batching anywhere.
- No test weakened: `PaddlePageOcrCoordinatorTest` extended only
  (+`batchLatencyMs >= 0.0` assertion); `PaddleOcrDevicePolicyTest` +81 lines and
  `PaddleOcrRollingP95PolicyTest` +29 lines are pure additions.

## Gate 5 — Boundaries: PASS (2 notes)

- 12 files total; ZERO changes to MangaOCR/MLKit, checkpoint/lease/quarantine,
  benchmark sources, manifests, or gradle files. No new files at all — policy
  logic lives in the existing focused batch-policy files.
- `RoiPageRecognitionEngine`: +59/−9 (net +50), all wiring (governor field/init,
  record+log+swap function, telemetry tokens, close reset). Slightly over the
  brief's "~40 lines" budget — see observation 1.
- Comments minimal and purposeful; `git diff --check ba3c729..57a8e0e` clean.
- Fixup `57a8e0e` verified NECESSARY: I reproduced the compile failure at
  `ba3c729` (`Conflicting declarations: routeOverride`,
  `OnnxRuntimeProvider.kt:460/463`) — the merge commit does not compile without
  it. The reviewed state (HEAD) compiles.

## Concern A — Committed-tree compile + worktree identity: PASS

- `:app:compileDevDebugKotlin` and `:app:compileDevReleaseKotlin` from the clean
  committed tree at HEAD: **BUILD SUCCESSFUL** (one invocation, 5m47s, exit 0).
- Identity: `git status` clean (no modifications); `git diff 57a8e0e -- <paths>`
  empty → committed tree == working tree for all Ticket 07 files. No
  staged-vs-worktree divergence exists.

## Concern B — The failing tests are NOT caused by Ticket 07: PASS (verified empirically)

- HEAD, isolated (`--tests` 3 classes): **all 3 pass** (exit 0).
- HEAD, full unfiltered suite (implementer's context):
  **2144 tests, 1 failed, 65 skipped** — the one failure is
  `StandardPipelineCoexistenceTest` ("expected:<COMPLETE> but was:<TRANSLATE>").
- Base `ba3c729` + the minimal 1-line compile fix (the earliest runnable
  pre-Ticket-07 tree; `ba3c729` alone does not compile), full unfiltered suite:
  **2139 tests, 3 failed, 65 skipped** — `BatchDispatchResumeWiringTest`,
  `StandardPipelineCoexistenceTest`, AND `StandardLaneMultiPageCompletionTest`
  (one the implementer did not even claim).
- Conclusion: this coexistence/standard-lane family is load-flaky on Windows and
  fails identically (more often) WITHOUT Ticket 07. The failing tests are
  disjoint from all 12 changed files. Nothing is caused or worsened by
  `b96ce1c`. The +5 test delta (2144−2139) is exactly the 5 new policy tests.

## Gate 6 — My own test runs: PASS

Focused 13-suite selector (standby contract list + new policy tests):
**89 tests, 0 failures, 0 errors, 0 skipped** —

| Suite | Tests |
|---|---|
| PaddleOcrBatchPlannerTest | 13 |
| PaddleOcrV6BatchExecutorTest | 9 |
| PaddleOcrV6B1ParityTest | 4 |
| PaddleOcrV6PageLifecycleTest | 8 |
| PaddlePageOcrCoordinatorTest | 4 |
| VerticalLineOcrPlanContractTest | 1 |
| PaddleOcrSessionFactoryTest | 5 |
| HardwareDiscoveryEngineTest | 12 |
| OnnxRuntimeProviderProvenanceTest | 7 |
| OcrModelCatalogTest | 6 |
| TranslationSettingsSummaryTest | 10 |
| PaddleOcrDevicePolicyTest (3 old + 4 new) | 7 |
| PaddleOcrRollingP95PolicyTest (2 old + 1 new) | 3 |

Full devDebug suite at HEAD: 2144 completed / 1 failed (flaky family) / 65 skipped.

## Non-blocking observations

1. **Engine budget**: +59/−9 vs the brief's "~40 lines max". Cohesive wiring
   (record/log/swap), no logic dumping into policy; acceptable, noted for the
   record.
2. **Debug-default semantic change**: at `ba3c729`, debug builds resolved the
   default to an emergency (BuildConfig request B4 → `provider_not_selected` →
   forced CPU B1); now the default B1 preference resolves `b1_default` naturally.
   Same active size and CPU provider; release untouched; arguably more honest.
   Record in the task README.
3. **`batched=` token semantics**: the stage-summary token changed meaning from
   "horizontal-text engine batching" to "Paddle coordinator batching (size>1)".
   Non-Paddle engines now always log `batched=false batchSize=1
   governorReason=not_paddle`. Log-only observability change.
4. **Latency attribution**: `batchLatencyMs` reads last-write-only
   `engine.lastBatchTelemetry` immediately after the serialized batch call under
   the coordinator mutex — accurate under the current single-page confinement;
   must be revisited if page-level OCR parallelism is ever introduced.
5. **Flaky test family**: `StandardPipelineCoexistenceTest`,
   `BatchDispatchResumeWiringTest`, `StandardLaneMultiPageCompletionTest`
   (and per the implementer `DisplayTailDrainTest`) are load-sensitive on
   Windows. Recommend a follow-up stabilization ticket outside this story.
6. **Tooling trap for future runs**: any `--tests` filter containing the
   substring "Standard" triggers the `contains("Standard")` google-services
   plugin application (`app/build.gradle.kts:15`) and breaks devDebug unit-test
   runs unless `app/google-services.json` exists. This bit my own first base-run
   attempt; worth a line in the task README.

## UNTESTED (agrees with handoff)

On-device B2/B4 latency/memory/thermal behavior, governor behavior on real
hardware, and `debug_provisional_optin` against real QNN accelerators remain
untested — device-dependent claims from both the implementer and this review.

## Gate statements

- B1 default byte-parity (release): PASS.
- Gate truth table incl. honest emergency reasons and no silent release cap: PASS.
- Governor real, page-boundary-only, capped, provider-neutral: PASS.
- Page-scoped fences and bounded in-flight intact at B4; no test weakened: PASS.
- Boundaries (MangaOCR/MLKit/checkpoint/lease/quarantine untouched): PASS.

Verdict: **PASS — no retry needed.**
