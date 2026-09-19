# Ticket 06 Review — Snapdragon validation harness + staged activation (final ticket)

- Reviewer: Reviewer agent (independent audit)
- Commit under review: `f744f86` "Validate Paddle batching and stage activation policy"
  (branch `research/paddle-ocr-batching-06`, parent `5340f78`)
- Worktree: `research-paddle-ocr-batching-06` (clean at review start/end)
- Verdict: **PASS** — no retry needed (4 non-blocking observations)
- This closes the Paddle OCR v6 batching story.

## Follow-up (a) resolution — VERIFIED RESOLVED BY ALIGNMENT

- `planWholeRegion` now sets `filterConfidence = false` with a comment citing the
  legacy inline `isUsable()`-only behavior (VerticalLineOcr diff). The
  det-missing/non-tall degraded branch now matches the pre-batch behavior
  exactly; detector-present and heuristic paths are untouched (the flag change
  is confined to the one branch only that route uses).
- New `VerticalLineOcrPlanContractTest` pins the contract in BOTH directions:
  0.49-confidence text survives with `applyConfidence=false` and is filtered
  with `applyConfidence=true`. Not skipped, not merely documented — aligned.

## Production defaults — VERIFIED UNCHANGED

- `app/build.gradle.kts`: default/release `PADDLE_BATCHING_STAGED=false`,
  `PADDLE_BATCHING_REQUESTED_BATCH=1`; only debug and benchmark build types
  override (true/4). Release production behavior: `resolveActivation` takes the
  `requestedBatchSize == B1` branch → reason `b1_default`, `forceCpuB1Emergency
  Fallback=false`, `providerTarget=null` → `providerConfiguration=null` → the
  engine takes the byte-identical legacy `createSessionWithFallback` path, and
  the coordinator runs B1. Production is provably unchanged.
- CPU B1 emergency fallback is explicit and layered:
  `PaddleOcrDevicePolicy.emergency(...)` (staged_flag_off / provider_not_selected
  / combination_not_confirmed / thermal_guard) and
  `PaddleOcrBatchActivationPolicy.emergencyCpuB1()` +
  `PaddleOcrProviderTestConfiguration.cpuB1EmergencyFallback()`. A staged build
  with no confirmed profile cell lands on CPU B1, never on an unapproved
  accelerator claim.
- Engine diff (+24) is wiring-only: optional `providerConfiguration` param
  (null = legacy path) and `strictProviderMode` now correctly ORs the config's
  strict flag. RoiPageRecognitionEngine diff (+16) is activation wiring only.

## Separation of policy layer — VERIFIED (criterion 3)

Five new focused files, none of it in the recognizer engine:
`PaddleOcrBatchActivationPolicy` (42), `PaddleOcrDevicePolicy` (76, incl.
thermal gate maxAllowedSeverity=2), `PaddleOcrDeviceProfile` (63, evidence-map
with CONFIRMED/LIKELY/UNTESTED/FAILED — empty approvals by default so unknown
devices cannot activate), `PaddleOcrRollingP95Policy` (120),
`PaddleOcrProviderTestConfiguration` (75).

## Strict-mode mechanics — VERIFIED REAL (criterion 4)

- `session.disable_cpu_ep_fallback=1` is set on the QNN HTP, QNN GPU, and NNAPI
  option paths (OnnxRuntimeProvider:341/511, 527, 395/537); the new
  `createSessionForPaddleProvider` reaches them via a default-null `routeOverride`
  (production route resolution untouched) and THROWS on accelerator→CPU
  registration before session creation; accelerator sessions are strict by
  construction (`PaddleOcrProviderTestConfiguration` init require).
- Provenance is post-inference: `ModelRoutingEngine.recordSuccessfulInference`
  + `provenanceAfterInference=true` are set only AFTER all matrix iterations
  complete (Runner:326-332); session registration feeds the diagnostic label
  only. Cell evidence CONFIRMED requires `provenanceAfterInference &&
  noCpuFallbackObserved` (actual non-CPU provider + empty downgradeReasons).
- Anti-self-fallback guard: per-iteration `error("strict cell downgraded: …")`
  when `lastBatchTelemetry.downgradeReason != null` or the provider label is
  CPU-like on an accelerator cell (Runner:315-322); the Ticket-04 B1 parity
  guard is retained in the non-matrix path (Runner:141-145).

## Rolling-p95 hysteresis — VERIFIED IMPLEMENTED (criterion 5)

Sliding window (default 20; benchmark uses `max(matrixIterations,3)`),
p95 > 1000 ms = high window, 2 consecutive highs → one-step downgrade
(B8→B4→B2→B1), p95 ≤ 750 ms = low window, 3 lows → one-step recovery, hold band
resets counters, warm-up holds. Deterministic tests cover downgrade AND
recovery (RollingP95PolicyTest:9-28) plus single-spike anti-oscillation
(31-47). Implemented, not described.

## Wake lock + foreground service — VERIFIED (criterion 6)

`PaddleBenchmarkRunLease` (PARTIAL_WAKE_LOCK + FGS start/stop, idempotent
close) and `PaddleBenchmarkForegroundService` (dataSync, exported=false,
START_NOT_STICKY) live only in `app/src/benchmark/`; the benchmark manifest
gains the WAKE_LOCK/FOREGROUND_SERVICE permissions and service entry — zero
production manifest changes. Bonus: the activity no longer `finish()`es
immediately, which closes the cached-process freeze hazard flagged in the
Ticket-01 review (the 145 s warm-run outlier).

## Honesty audit — VERIFIED (criterion 11) and no Qualcomm inference (criterion 7)

- The single device contact attempt (adb connect refused, 10061) is documented;
  all 32 matrix cells, on-device parity, PSS/thermal/responsiveness, manual
  wait, and 50-page/full-corpus runs are labeled `UNTESTED`; "No Qualcomm
  result is inferred from host measurements and no combination is approved";
  host results are explicitly demoted to compile/JVM-only evidence. The
  promotion gate is spelled out: fresh post-commit device run, exact commit
  SHA, zero accelerator CPU fallback, parity, PSS, thermal, responsiveness.
- The 32-cell matrix (4 providers × B1/B2/B4/B8 × 640/1600) is generated
  explicitly; per-cell results carry provider, strict flag,
  provenance-after-inference, no-CPU-fallback observation, raw downgrade
  reasons, measured batch sizes, p50/p95, PSS delta, thermal start/end, peak
  tensor bytes, and rolling-p95 actions.

## My own verification runs (criterion 8)

- Full 12-suite selector (superset of the handoff's list, including
  SmallEngineTest): **BUILD SUCCESSFUL in 6m48s — TOTAL tests=57 skipped=0
  failures=0 errors=0** (Planner 13, Executor 9, Lifecycle 8, Parity 4,
  Coordinator 4, CTC 5, SmallEngine 5, MangaOcrPreprocess 2, DevicePolicy 3,
  RollingP95 2, PlanContract 1, ProviderConfig 1). The handoff's "51" is a
  one-off undercount of its own selector subset (those suites total 52); the
  substantive claim — 0 failures, 0 skipped — holds everywhere.
- `:app:compileDevBenchmarkKotlin`: **BUILD SUCCESSFUL** (174 tasks).
- Note: the device is unreachable, so all device-dependent claims are UNTESTED
  by the implementer AND unverified by me — consistently labeled.

## Boundaries — VERIFIED (criterion 9)

No model conversion, no cross-page scheduling, no envelope/lease/checkpoint
changes anywhere in the stat. The reviewed executor emergency ladder is
preserved verbatim with an explanatory comment: `lowerBatch` remains
8→4→1 for reviewed sizes (B4→B1 via else), B2 is a benchmark-only
normalization input (Executor diff). The planner change is exactly one line:
`B2(2)` in the enum (existing B1/B4/B8 behavior and tests untouched). All
regression suites green in my run.

## Device command sanity — VERIFIED (criterion 10)

install -r with the `--force-non-staged` pm fallback and the never-uninstall
rule carry over unchanged from the Ticket-01 README sections. Matrix commands
are coherent with the parsed extras: `matrixIterations 20` (coerced 1..20),
`pageLimit 0` with both corpora disabled → the two committed fixtures provide
representatives for both width buckets; `complete.marker` poll + pull as
before; `--ez matrixMode true` matches `EXTRA_MATRIX_MODE`.

## Non-blocking observations

1. Handoff test-count arithmetic: the report says 51; the same selector set
   totals 52 (57 across my 12-suite superset). Cosmetic miscount — every suite
   is green either way, and my numbers are the reproducible ones.
2. The rolling-p95 governor is implemented and exercised inside the matrix
   harness, but the production page loop does not yet consume it. This is
   coherent staging (nothing is CONFIRMED, production is B1 where a downgrade
   governor is moot), but the B4/B8 activation work must wire the governor into
   the page loop — it does not activate itself.
3. Matrix mode labels `provider.actualRegisteredProvider = "per_cell"` and
   `measuredBatchSize = 0` under schema v3 — downstream tooling must treat v3
   as its own schema (the per-cell truth lives in the matrix array).
4. 32 cells × (init + N inferences + close) is a heavy harness by design; the
   wake lock + FGS now cover it, but a full-device matrix run should be
   scheduled with the device thermally idle to keep cells comparable.

## Story-close gate statement

B1 parity, bounded memory, provider no-fallback, and checkpoint integrity gates
all hold across the six tickets. Production remains B1 on the legacy session
path; every path to B2/B4/B8 or an accelerator runs through a confirmed
device-profile cell that today does not exist and can only be created by the
honest on-device matrix this ticket stages. Nothing was approved from host
numbers. The story is correctly closed in the blocked-on-device state it
honestly earned.

Verdict: **PASS — no retry needed.**
