# Phase 3 — Manual and Auto trace wiring (implementation)

Task: T922 translation-pipeline performance & UX roadmap.
Worktree: `optimize_translation_pipeline_ux` (all work uncommitted, per task contract).
Status: COMPLETE — focused suite green (1432 tests, 0 failures).

---

## 0. Pre-task (Phase 2 review finding F1) — reason sanitizer

- `TranslationTrace.kt`: new `TranslationTraceReason` enum — 24 bounded reason
  tokens (`window_update`, `window_pending`, `generation_changed`,
  `chapter_changed`, `out_of_window`, `admitted`, `lease_granted`,
  `lease_unavailable`, `source_unavailable`, `memory_pressure`,
  `provider_pause`, `retry_deferred`, `stale_generation`, `cancel_requested`,
  `manual_preempt`, `timeout`, `coordinator_replaced`, `teardown`,
  `runtime_failure`, `session_creation_failure`, `registration_failure`,
  `cpu_fallback`, `device_preference`).
- `TranslationPipelineDiagnostics.kt`: `resolveReason(raw)` collapses anything
  outside the vocabulary (unknown/empty/hostile) to the fixed `invalid` token;
  `route_change` + `schedule_state` formatting now route reasons through it.
- Provenance fields: `stage_end` formatter gained trailing optional
  `registeredProvider=` / `provenProvider=` fields (emitted only when
  non-null, so every Phase 2 exact-line test stays byte-identical);
  `TranslationStageSpan.end` / `TranslationRunTrace.finishStage` forward them.
- Tests (`TranslationPipelineDiagnosticsTest`): hostile reason collapse
  (state + route), hostile errorType override collapse, bounded provider-label
  mapping. Diagnostics suite: 10 tests, 0 failures.

---

## 1. Files changed + hunk summaries

| File | ~Δ | Hunks |
|---|---|---|
| `diagnostics/TranslationTrace.kt` | +150 | `TranslationTraceReason`; `TranslationRunTrace.planRef` + internal `updatePlan(plan)` (run_end carries the resolved plan); `noteRunTerminal(outcome)` + `hasNoFailedRuns()` on `TranslationScheduleTrace` (bounded: one counter); `TranslationTrace.beginStage` now returns a **fresh** fail-open no-op span instead of the shared `NO_OP` singleton (the singleton's close-state was permanently consumed by the first outside-a-run `end()` — latent Phase 2 bug surfaced by the coexistence tests); `createNoOp` factory. |
| `diagnostics/TranslationPipelineDiagnostics.kt` | +30 | reason vocabulary set, `resolveReason`, `providerFromLabel` (cpu/xnnpack/qnn_htp/qnn_gpu/nnapi/remote/local → bounded token, else NONE), provenance suffix fields. |
| `scheduling/TranslationScheduler.kt` (Wire A) | +91/−13 | `translatePage()`: schedule+run created BEFORE `scope.launch(elementFor(run))`; `lease_wait` span begun at request time, ended at coroutine-body start (measures request→coroutine-start scheduler queue; queue-class → feeds schedule maxQueueMs); terminal ownership moved OUTSIDE the coroutine to `job.invokeOnCompletion` (cancel-before-body coverage): `traceOutcome` AtomicReference + `mapManualTraceOutcome` (Completed→success, Paused→pause, Failed/Stalled→failure, Attached family→attached, Rejected→skip, null→teardown_exception, cancelled→cancelled); legacy failure log migrated (raw pageKey/chapter/manga/source removed → bounded `errorType=` only). `requestAutoWindow` marked `@Deprecated` (§10.7) — internals untouched. |
| `TranslationPipeline.kt` (Wire B) | +78 | `runGrantedSinglePageBoundary`: NATIVE_QUEUE span begun at boundary entry, ended when queue settles; NATIVE lane token with explicit closes on all four exit paths (residual-reject early return, normal `.also`, `NativePageAlreadyInFlight`, outer catch); `cleaned_persist` (storage lane) around `persistOnnxCleanedImage` with FAILURE outcome on null; PROVIDER lane token around the HTTP/render phase (normal + catch closes). `prepareSinglePage`: same NATIVE queue span + lane token + `cleaned_persist`. `translatePreparedPage`: PROVIDER lane token around the whole HTTP/render section (explicit closes on every catch + normal path; `return preparedOutcome` made explicit). |
| `pipeline/SinglePageOnnxPhase.kt` (Wire C) | +50 | `decodePageBitmapForTranslation` wrapped as `source_decode` (covers fresh decode AND inpaint-resume re-decode; intervals accumulate); resume-plan resolution onto the run (`fresh` when `runOcr`, `render_only` when no OCR/inpaint/translate work, `resume` otherwise, `skip` on the early return) via `updatePlan`; `[translation_page]` log now emits `traceTotalMs=` from the correlated run total when a run is installed, keeping the legacy `elapsedMs=` only outside a trace. |
| `recognition/RoiPageRecognitionEngine.kt` (Wire D) | +102/−8 | `detect`/`segment`/`ocr` spans with per-engine ACTUAL provider labels (`executionProviderLabel` → `providerFromLabel`), models (`PAGE_DETECTOR`, `BUBBLE_SEGMENTER`, `MANGA_OCR`/`PADDLE_OCR` by type) and items; `openRecognitionSpan` tracking + outer catch settles whichever engine stage was open (no stage_start leaks); `inpaint` span with `provenProvider=providerFromLabel(inpainting.lastAcceptedRoute)` + `model=aot_gan` (AOT file untouched — provenance captured from exposed labels at the caller, per contract G); both legacy `[translation_perf]` logs: ambiguous global `route=${HardwareDiscoveryEngine.activeRoute.name}` REMOVED (per-engine providers and execution-proven `inpaintRoute=` retained); unused `HardwareDiscoveryEngine` import removed (HardwareDiscoveryEngine.kt itself untouched). |
| `pipeline/SinglePageHttpRenderPhase.kt` (Wire E) | +114 | `provider_governor_wait` opened at phase entry (translator borrow = admission gate), settled by the FIRST `translateOnce` invocation (queue-class → maxQueueMs), else settled in `finally`; `translate` span with `provider=remote|local` via `TranslatorComputeClass.forTranslator`, model=none; typed span outcomes on `ProviderFailureException` (pause/failure), generic Exception (cancel/pause/failure), success; `recordRetry()` per PARTIAL retry and per epoch retry (surfaces as run_end `retries=`); `layout` (color estimation) + `render` spans on both render paths (retry-path spans hoisted above the try so skip/failure/cancel all settle them); `store_commit` (storage lane, FAILURE on patch rejection); `store_flush` (storage lane) in the finally. |
| `scheduling/RollingAutoCoordinator.kt` (Wire F) | +299/−41 | see §2/§3 below. |

Director-owned dirty files re-verified **byte-for-byte** against the Phase-2
baseline patch (`C:/Users/User/Documents/T922_baseline_backup_2026-09-04/baseline_tracked.patch`,
sha256 `c0be540a29f9…`): OnnxRuntimeProvider.kt, AOTInpainting.kt,
PaddleOcrV6DetEngine.kt, HardwareDiscoveryEngine.kt, QnnDiagnostics.kt,
CleanedPublication.kt, ReaderTranslationFeedback.kt, libs.versions.toml —
per-file diffs IDENTICAL to baseline; ModelRoutingEngine.kt /
QnnContextCacheManager.kt (Director-untracked) untouched. `git status` before
each edit group per hard rules.

---

## 2. Wire F detail (RollingAutoCoordinator)

- Schedule lifecycle: ONE schedule per rolling-Auto session, created in
  `updateWindow` (chapterRaw = identity.chapterId → HMAC token; pages =
  window size). Repeated viewport updates emit COALESCED
  `schedule_state state=queued reason=window_update` on the same schedule.
  A chapter/session identity change closes the old schedule
  (`coordinator_replaced`) before a new one starts; re-arm after cancel
  re-creates it (the old one is terminally closed).
- Admission: run (`startRun`, plan=fresh) + `schedule_state admitted` +
  NATIVE lane token at native admission; `prepareSinglePage` wrapped in
  `withContext(TranslationTraceElement)` (same job/dispatcher → no
  redispatch; the sanctioned §4.4 propagation mechanism). Memory/source
  deferrals emit `schedule_state deferred reason=memory_pressure |
  source_unavailable`.
- Handoff: PREPARED_QUEUE span begun just before `preparedChannel.send`,
  ended at consumer pickup (measures true send→pickup queue wait;
  queue-class → maxQueueMs). `PreparedWork` gained `trace` +
  `preparedQueueSpan`.
- Consumer: drain-not-cancel translate block wrapped in
  `NonCancellable + TranslationTraceElement` (deep HTTP/render code
  correlates); PROVIDER lane token spans the translate; `drainingRun`
  marks the sweep exemption so a drained in-flight call reports its real
  outcome; outcome mapping is a pure `mapTranslatedTraceOutcome`
  (Completed→success, null→stale_handoff, Paused→pause, Failed/Unexpected→
  failure, PersistenceRejected→**evicted when a MANUAL owner now holds the
  page lease (T917 preemption), else persistence_rejected** via the
  non-suspending `store.pageLeaseOwner`).
- Cancel sweep (`sweepTracesLocked`, under lifecycleLock, non-suspending):
  every admitted-but-unclosed run gets exactly one terminal; the draining
  run is exempt; the schedule closes `cancelled` — or `success` when the
  window is torn down after a fully successful run set
  (`hasNoFailedRuns()`, bounded one-counter signal).

---

## 3. Terminal-ownership coverage matrix (amendment §10.2)

Verified at the two review-flagged holes plus every other exit:

| Exit path | Terminal | Emitted where | Test |
|---|---|---|---|
| Manual: body never starts (scope closed) | `cancelled` | scheduler `invokeOnCompletion` | `cancel before dispatch still closes the run exactly once` |
| Manual: cancelled mid-flight | `cancelled` | scheduler catch → invokeOnCompletion | `cancelled manual intent closes exactly once as cancelled` |
| Manual: executor outcome | success/pause/failure/attached/skip/teardown_exception | `mapManualTraceOutcome` | manual success test |
| Auto: prepare failed (null) | `failure` | reconcilePass null branch | — (wired; fault injectable) |
| Auto: terminal prepared page (textless/render-only resume/skip) | `skip` | reconcilePass terminal branch | — (wired) |
| Auto: send parked when cancelled | `cancelled_during_send` | reconcilePass Cancellation catch (`sendingPrepared` flag) | cancel test (capacity=2, 4th run) |
| Auto: generation death before/inside prepare | `cancelled` (or `coordinator_replaced`) | `sweepTracesLocked` on `cancelLocked` | cancel test |
| Auto: stale pickup (pre-try `continue`, reviewed hole) | `stale_handoff` | consumer pickup branch | — (wired; idempotent double-close proven) |
| Auto: stale while parked on compute gate | `stale_handoff` | consumer else-branch mapper | — (wired) |
| Auto: stale translate result (null) | `stale_handoff` | mapper | — (wired) |
| Auto: drained call after cancel | REAL outcome (success) | consumer mapper (drain-not-cancel) | cancel test: exactly 1 success among cancels |
| Auto: drain-grace expiry | `timeout` (rethrow unchanged) | `TimeoutCancellationException` catch BEFORE Cancellation catch | `drain grace expiry closes the run as timeout` |
| Auto: consumer cancelled outside drain | `cancelled` | CancellationException catch | cancel test |
| Auto: translate threw | `failure` | Throwable catch | — (wired) |
| Auto: persistence rejected by MANUAL owner | `evicted` | mapper + `pageLeaseOwner` | `manual lease theft discriminates evicted terminal` |
| Auto: persistence rejected otherwise | `persistence_rejected` | mapper | — (wired) |
| Auto: success | `success` | consumer mapper | overlap test |
| Schedule teardown after all-success | `success` | sweep | overlap test (`schedule_end … outcome=success`) |
| Schedule cancel with work cut short | `cancelled` | sweep | cancel test |
| Schedule identity change | `coordinator_replaced` | `updateWindow` → `cancelLocked(COORDINATOR_REPLACED)` | — (wired) |

Repeated-terminal safety: every `run.end`/`schedule.end`/span `end` is
CAS-idempotent; tests assert exactly one `run_end` per `run_start` and one
`schedule_end` per schedule even when sweeps and consumers race.

---

## 4. Legacy-Auto reachability inventory (contract A / §10.7)

`TranslationScheduler.requestAutoWindow()` is **PROVEN unreachable** in the
reader flow:

- Definition: `TranslationScheduler.kt` (`fun requestAutoWindow(...)`).
- Sole delegator: `TranslationManager.requestAutoWindow` (TranslationManager.kt
  :1508 → scheduler :1524).
- Repo-wide grep for both symbols: zero call sites (only the definitions and
  a historical comment in `TranslationExecutor.kt:36`).
- Therefore: marked `@Deprecated` with a §10.7 KDoc, internals unchanged, NO
  trace wiring (per contract "do NOT delete"); `warningsAsErrors` is opt-in
  in this build so the deprecation cannot break compilation.
- The legacy Auto stream path (`translateSinglePageFromStream`) is part of the
  same unreachable surface; untouched.

---

## 5. Legacy-log migration inventory (§10.4)

| Site | Before | After |
|---|---|---|
| Scheduler manual failure (raw pageKey/chapter/manga/source) | `"…failed: pageKey=$pageKey chapter=… manga=… source=…"` | `"…failed: errorType=<bounded>"` (+ throwable on default logcat tag for native stacks) |
| Onnx `[translation_page]` total | ambiguous local `elapsedMs` | `traceTotalMs=` (correlated run total) when traced; legacy total kept otherwise |
| Recognition `[translation_perf]` recognition | `route=<device preference>` global | removed (per-engine providers retained) |
| Recognition `[translation_perf]` inpainting | `route=<device preference>` global | removed (`inpaintRoute=` execution-proven retained) |
| Coordinator D9 ledger warn (raw pageKey) | `pageKey=${work.prepared.pageKey}` | `pageIndex=${work.pageIndex}` |
| Coordinator translate/prepare failure logs | `pageIndex=` already | unchanged |
| Coordinator stale-handoff log (~528) | `pageIndex=` already | unchanged (no raw content) |

Retained raw identifiers (pageKey in pipeline debug/warn logs, chapter names
in provider-failure warn logs) live on the default logcat tag, OUTSIDE the
`TachiyomiAT.Translation` privacy boundary, and are covered by the
correlated trace tokens when tracing is on.

---

## 6. Provider provenance available (recorded, §10.8)

- `detect`/`segment`/`ocr`: `provider` + `registeredProvider` from each
  engine's `executionProviderLabel` (session-creation fact).
- `inpaint`: `provenProvider` from AOT `lastAcceptedRoute` (execution-proven;
  values `fixed_qnn_htp`, `fixed_nnapi`, `dynamic`… mapped through
  `providerFromLabel`), `model=aot_gan`. Captured at the CALLER
  (RoiPageRecognitionEngine) — Director-owned `AOTInpainting.kt` untouched.
- `translate`: `provider=remote|local` via `TranslatorComputeClass.forTranslator`, `model=none`.
- NOT available until Phase 5: `requestedProvider` (device preference) —
  deliberately NOT emitted (OnnxRuntimeProvider/ModelRoutingEngine refactors
  are out of scope this phase).

---

## 7. Tests added + output

New JVM suites (JUnit 5 + kotest + mockk, no Robolectric, capturing trace
sink with pinned IDs/keys):

1. `scheduling/TranslationSchedulerTraceTest.kt` — case 1: manual schedule/run
   correlation, measured `lease_wait`, success terminal; cancelled intent →
   exactly one `cancelled` terminal; cancel-before-dispatch → exactly one
   terminal from `invokeOnCompletion`.
2. `scheduling/RollingAutoCoordinatorTraceTest.kt` — case 2 (REMOTE overlap,
   one schedule, 2 correlated runs with distinct rids, prepared_queue stage
   correlated to rid); cancel coverage (buffered runs → `cancelled`, parked
   send → `cancelled_during_send`, drained call → real `success`, exactly one
   terminal per run, `schedule_end cancelled`); case 5 trace half (MANUAL
   lease theft → `evicted`); drain-grace expiry → `timeout`; case 6 parity
   (gate OFF vs ON: identical prepare/translate/maxConcurrent invocation
   counts; no detailed events while terminal summaries still emit).
3. `diagnostics/TranslationPipelineDiagnosticsTest.kt` — +3 sanitizer/
   provenance tests (pre-task F1).

Output: `:app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"
--tests "eu.kanade.tachiyomi.ui.reader.viewer.ReaderTranslationFeedbackTest"`
→ **192 suites / 1432 tests / 0 failures, BUILD SUCCESSFUL**. All existing
tests in touched areas pass unchanged, including the Phase 2 exact-line
formatter tests and the T917 coordinator/lane tests.

---

## 8. Deviations (documented per contract)

1. **`updatePlan`**: run_start cannot know the resume plan (created at
   request/admission time; plan resolved later inside the onnx phase).
   Added internal `TranslationRunTrace.updatePlan(plan)`; run_start carries
   the initial `fresh`, run_end carries the resolved
   `fresh|resume|render_only|skip`. Non-suspending, last-write-wins.
2. **Fresh no-op spans**: `TranslationTrace.beginStage` outside a run now
   allocates a fresh no-op span instead of returning the shared
   `TranslationStageSpan.NO_OP` singleton (whose close-state the first
   caller permanently consumed — latent Phase 2 bug, surfaced when the
   coexistence pipeline tests ran before `TranslationTraceTest`). Semantics
   unchanged (fail-open, no emission, `end()` returns true).
3. **Schedule terminal on natural shutdown**: a fully-successful window torn
   down by `shutdown()` emits `schedule_end success` (one-counter
   `noteRunTerminal` signal); any cancelled/failed run keeps the lifecycle
   outcome (`cancelled`). Avoids a misleading `cancelled` summary for
   ordinary reader-exit teardown.
4. **`withContext(TranslationTraceElement)`** introduces a coroutine
   boundary around prepare/translate wraps; with the same dispatcher it
   performs no thread switch, and both wrap sites already suspend
   immediately after — lane semantics unchanged (the §4.4-sanctioned
   mechanism).
5. **RENDER stage sum includes LAYOUT**: the render attempt in
   SinglePageHttpRenderPhase contains the layout/color pass as a
   sub-interval; both buckets are emitted, render therefore dominates.
   Noted for Phase 4 analysis.
6. **Legacy Auto**: `@Deprecated` marker only (proven unreachable, §4).

## 9. Risks / known limitations

- Swept runs' PREPARED_QUEUE span interval is not settled before the run
  terminal (the span lives on `PreparedWork`, not the run): the queue time
  of cancelled-buffered runs is absent from `run_end` sums. Bounded and
  fail-open; terminals are unaffected.
- OCR span end is registered only on the happy path; a mid-OCR throw is
  settled by the analyze-level `openRecognitionSpan` catch (typed
  `failure`), so no stage_start leaks, but per-sub-engine attribution for
  that interval is coarse.
- `native_queue` stage end-to-end coverage is wired at the
  TranslationPipeline boundary but not exercised by JVM coordinator tests
  (the fake executor replaces the pipeline); it will be observable in the
  Phase 4 device validation.
- The drain-grace timeout path rethrows exactly as before (control flow
  unchanged), so the consumer still exits on timeout — pre-existing T917
  behavior, intentionally not modified in this phase.
