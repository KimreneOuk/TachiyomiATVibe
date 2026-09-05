# Phase 3 (Manual and Auto trace wiring) — Independent Review

**Role:** Reviewer / Failure-mode auditor (T922)
**Date:** 2026-09-04
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`, HEAD `7a95f9c9` — unchanged from baseline capture)
**Reviewed artifact:** the uncommitted Phase 3 diff (6 production files) + live changes in the untracked `diagnostics` package + 2 new JVM test suites; plus the pre-task F1 sanitizer work in `diagnostics`.
**Governing contract:** plan `engineering/translation-pipeline-fix-and-observability-plan.md` §4.2–4.4, §6.3; mandatory amendments §10 (10.2, 10.3, 10.4, 10.5, 10.7); Phase 2 review follow-ups F1/F4.
**Inputs:** role file, task README, plan, `PHASE3_MANUAL_AUTO_WIRING_IMPLEMENTATION.md`, `PHASE2_TRACE_FOUNDATION_REVIEW.md`, `PHASE1_STABILITY_REVIEW.md`, `BASELINE_CAPTURE_2026-09-04.md` + baseline patch, live source/tests.

## Verdict: APPROVED WITH NOTES

Every audited contract clause is verified against primary evidence. Scope discipline is byte-for-byte proven. The two review-flagged terminal holes (cancel-before-dispatch, stale consumer pickup) plus channel-send cancellation, eviction, timeout, coordinator replacement, and drained-run teardown are all closed with exactly-one-terminal semantics that a real-wiring test suite exercises. No scheduling behavior changed (parity test proves identical invocation counts gate-off vs gate-on). Findings below are one MEDIUM low-likelihood race plus LOW/informational notes; none blocks Phase 3.

---

## 1. Scope integrity — VERIFIED, zero deviation

Independently re-run by reviewer (not taken from the implementer's report):

| Check | Evidence | Result |
|---|---|---|
| 14 Director-owned dirty tracked files | Fresh `git diff --binary` over exactly the 14 baseline paths → SHA-256 `c0be540a29f9…ab467`, **identical** to `baseline_tracked.patch` (same 56,290-byte patch hash) | ZERO new hunks |
| 5 protected untracked files | SHA-256 of all five re-hashed in the worktree match `BASELINE_CAPTURE` exactly (`e1dce9a9…`, `430102d9…`, `f66aaabe…`, `7d61f647…`, `de74c62c…`) | BYTE-IDENTICAL |
| Phase 1 files | `OnnxBubbleSegmenter.kt` diff = 248 changed lines = Phase 1 review's +219/−29 exactly; **0** grep hits for `trace|diagnos` in its diff. `App.kt` diff content is exclusively the Phase-1 debug broadcast receiver (no trace wiring) | PHASE-1-ONLY |
| Modified tracked inventory | 22 modified files = 14 baseline + App.kt + OnnxBubbleSegmenter.kt + the 6 authorized Phase 3 files (TranslationScheduler, TranslationPipeline, SinglePageOnnxPhase, RoiPageRecognitionEngine, SinglePageHttpRenderPhase, RollingAutoCoordinator) | EXACT |
| New source files | Only `scheduling/TranslationSchedulerTraceTest.kt` + `scheduling/RollingAutoCoordinatorTraceTest.kt` (Phase 3) on top of the Phase 1/2 inventory | EXACT |
| AOT provenance boundary | `AOTInpainting.kt` untouched (inside the byte-identical baseline set); inpaint `provenProvider` captured at the caller from `inpainting?.lastAcceptedRoute` (RoiPageRecognitionEngine) per contract G | VERIFIED |

## 2. Terminal-ownership audit (amendment 10.2) — VERIFIED, with one narrow race (F1 below)

Walked every exit path in live code:

**Manual (TranslationScheduler.translatePage, :658–780).** Schedule + run created only AFTER the dedup early return (line 669–672: an already-active duplicate intent creates no trace objects — no orphan possible). Terminal ownership lives entirely in `job.invokeOnCompletion` (:768–778), which fires exactly once for every Job termination including cancel-before-dispatch. Path walk: normal return → finally maps `outcome` into `traceOutcome` (runs before the handler); `CancellationException` → `CANCELLED` set in catch, rethrow preserved; `Throwable` → `FAILURE`; handler fallback `job.isCancelled → CANCELLED` else `TEARDOWN_EXCEPTION`. `leaseWaitSpan.end()` is called both at coroutine-body start and in the handler (CAS-idempotent, covers never-started bodies). Exactly one `run_end` and one `schedule_end` on every path; no lane tokens are held in the scheduler. `mapManualTraceOutcome` covers every `SinglePageOutcome` variant exhaustively (Attached/AttachedUnresolved→attached, Rejected→skip, Stalled→failure).

**Pipeline boundary (Wire B).** `runGrantedSinglePageBoundary`: NATIVE lane token closed on all four exits (residual-reject return :538, normal `.also` :590, `NativePageAlreadyInFlight` :592, outer Throwable :596) — verified no `return` exists between entry and those closes. PROVIDER token entered after persist, closed in the Throwable catch (:676) and normal path (:684); the timeout `?: run{}` branch falls through to the close (no early return inside the try — verified). `translatePreparedPage`: all six early returns (terminal-skip, null store, lease denied, stale reference, null pageState, null cleaned image) precede token entry (:1066–1152 vs entry :1156); all four try exits close. `prepareSinglePage`: same pattern; its `NativePageAlreadyInFlight` has no dedicated catch and is settled by the Throwable catch. Lane balance is `finally`-guaranteed on the coordinator side; on the pipeline side every path is covered by construction (verified by reading every return/throw in the audited ranges).

**Rolling Auto (Wire F).** Consumer loop (`consumeTranslations` :526–687): stale pickup before the try → `prepared_queue` span settled FIRST, then `stale_handoff` terminal + retire (`continue`); stale re-check inside try → same; drained run (`NonCancellable`) → real mapped outcome via `mapTranslatedTraceOutcome`; `TimeoutCancellationException` caught BEFORE the `CancellationException` catch (subclass order correct) → `timeout` + unchanged rethrow; consumer cancellation outside drain → `cancelled`; `Throwable` → `failure` (swallowed exactly as pre-existing behavior). `finally` clears `drainingRun` and closes the PROVIDER lane token on every exit. Swept-but-buffered runs: consumer pickup after sweep hits the idempotent close → still exactly one terminal (the sweep's). Producer (`reconcilePass`): `prepared == null` → `failure`; terminal prepared page → `skip`; `markTranslateAdmitted` failure → `cancelled`; parked send cancelled → `cancelled_during_send` (guarded by `sendingPrepared`, set immediately before `send`; no suspension exists between send-return and try-exit, so a post-send cancellation cannot be misattributed); `Throwable` → `failure`. `nativeLaneToken` closed in `finally` on all exits. Sweep (`sweepTracesLocked`, under lifecycleLock, non-suspending): closes every admitted-but-unclosed run except `drainingRun`, closes the schedule (`cancelled`, upgraded to `success` only when the one-counter `hasNoFailedRuns()` is true), nulls `scheduleTrace`. Late-registration race found — see F1.

**Diagnostics primitives.** Every `run.end`/`schedule.end`/span `end` is a single CAS (`AtomicBoolean.compareAndSet`, TranslationTrace.kt :576, :735, :833); lane token close is fail-open and clamp-at-zero. Double-terminal cannot emit twice.

## 3. No-new-suspension-points audit — VERIFIED

All added trace calls (`currentRun`, `beginStage`, span `end`, `enterLane`, `reportState`, `recordRetry`, `updatePlan`, `noteRunTerminal`, `pageLeaseOwner`, `providerFromLabel`) are non-suspending plain functions — grep-verified (`pageLeaseOwner` is a synchronized plain fun, ChapterTranslationStore.kt:533 / PageStageLeaseTable.kt:187). The only added coroutine boundary is the disclosed `withContext(TranslationTraceElement)` around the coordinator's prepare (:916) and the `NonCancellable + elementFor` consumer wrap — the §4.4-sanctioned propagation mechanism, documented as deviation 4. The `computeGate` with/else structure inside the withContext block is logic-identical to the original. Timers are try/finally or explicit-catch based; all existing typed outcomes and rethrows preserved (`TimeoutCancellationException` rethrow unchanged; CancellationException rethrows after span settlement in all six sites checked).

## 4. No-scheduling-change audit — VERIFIED

- Admission (`markNativeAdmitted`/`markTranslateAdmitted`), eviction, governor (`computeGate.withPermit`), and prepared-channel semantics untouched (PreparedWork gained two defaulted nullable fields only).
- All diagnostics are read-only observers: `updatePlan` writes an AtomicReference; nothing in the diff branches scheduling on trace state.
- Proven by test: `diagnostics off does not change scheduling behavior` asserts identical (prepare, translate, maxConcurrent) invocation triples with the gate off vs on, and the pre-existing 31-test `RollingAutoCoordinatorTest` + T917/T921 suites pass unchanged (1432 total, 0 failures).
- `beginStage(lane = …)` is metadata-only (verified: no `enterLane` call inside `beginStage`), so stage lanes cannot double-count against the coordinator's explicit lane tokens.

## 5. Privacy audit (10.3/10.4) — VERIFIED

Grep of every added line across the six production diffs: the only raw identifiers are `chapterRaw = chapter.name` / `pageRaw = pageKey` / `chapterRaw = identity.chapterId?.toString()` — all keyed-HMAC **inputs**, never emissions (boundary verified in Phase 2). Emission values are enum tokens, counts, durations, or `pageIndex` (explicitly accepted by §10.3).

Legacy-log migration inventory cross-checked against the live diff — implementer's table is accurate:

| Site | Verified change |
|---|---|
| Scheduler manual failure (:712–723) | raw `pageKey/chapter/manga/source` removed → `errorType=${classifyError(e).type}` (bounded); throwable kept on default tag |
| Recognition `[translation_perf]` (2 sites) | ambiguous global `route=${HardwareDiscoveryEngine.activeRoute.name}` removed; per-engine `providers(...)` and execution-proven `inpaintRoute=` retained; unused import removed (HardwareDiscoveryEngine.kt itself untouched) |
| Coordinator D9 ledger warn | raw `pageKey` → `pageIndex` |
| Onnx `[translation_page]` | `traceTotalMs=` from the correlated run when traced; legacy `elapsedMs=` outside traces; pre-existing raw `fileName` on default tag unchanged (outside the boundary, pre-existing) |

Retained error logging under trace emission is bounded: `resolveError`/`classifyError` whitelist plus digit-group OrtException code extraction only (Phase 2 verified; unchanged).

## 6. Gating audit (10.5) — VERIFIED

`emitStageStart` checks the gate before any formatting (:447); `emitStageEnd` computes two integer comparisons before the gate check and survives the gate when `lag || outcome != SUCCESS` (:481); `run_end`/`schedule_end` have no gate check (terminal summaries always emit). Coalescing of identical consecutive `(state, reason)` retained from Phase 2 and exercised by `updateWindow`'s repeated `window_update` reports on the same schedule. Parity test pins the exact survivor families with the gate off (0 schedule_start/run_start/stage_start/schedule_state/plain-success stage_end; 2 run_end + 1 schedule_end still emitted).

## 7. Schema conformance — VERIFIED (spot-check)

All emitted stages are from the canonical §4.2 set: `lease_wait` (Wire A), `native_queue` (Wire B, both boundaries), `source_decode` (Wire C), `detect`/`segment`/`ocr`/`inpaint` (Wire D), `cleaned_persist` (Wire B, storage lane), `prepared_queue` (Wire F), `provider_governor_wait`/`translate`/`layout`/`render`/`store_commit`/`store_flush` (Wire E). Lanes used: scheduler (queue-stage defaults), native, provider, render, storage — `defaultLane` mapping matches the §4.2 lane table (:323–345). `run_end` carries `plan` (resolved via `updatePlan`, last-write-wins), `queuedMs`, `totalMs`, `stageSumMs`, `bottleneck`, `bottleneckMs`, `retries`, `outcome`, `errorType/errorCode`. Provenance suffixes (`registeredProvider=`, `provenProvider=`) are trailing, enum-tokened, and emitted only when non-null — the Phase 2 exact-line tests pass unchanged (10/10 + 13/13 diagnostics green). Queue-class wiring follows the Phase 2 F4 recommendation: all four queue waits (lease_wait, native_queue, prepared_queue, provider_governor_wait) are wired as queue stages and feed `schedule_end.maxQueueMs`.

## 8. Legacy Auto coverage (10.7) — VERIFIED (proof option)

Own independent grep (not the implementer's): `.requestAutoWindow(` has zero call sites outside the `TranslationManager.kt:1524` delegation itself; no hits anywhere under `eu/kanade/tachiyomi` (reader/UI); `translateSinglePageFromStream` is invoked only at `TranslationScheduler.kt:447`, inside the same legacy window flow. The path is therefore unreachable from the reader, which satisfies §10.7's "prove unreachable and deprecate separately" arm: `@Deprecated` marker + §10.7 KDoc at `TranslationScheduler.kt:289–303`, internals untouched, no trace wiring, no deletion. No `mode=auto origin=auto` propagation or terminality test is required for a proven-dormant path.

## 9. Tests — VERIFIED (real, mutation-sensitive), re-run independently

Reviewer re-ran the focused suite in a clean background build: **BUILD SUCCESSFUL, 1432 tests, 0 failures, 0 errors** (`:app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "…ReaderTranslationFeedbackTest"`). Relevant XMLs: TranslationSchedulerTraceTest 3/3, RollingAutoCoordinatorTraceTest 5/5, TranslationPipelineDiagnosticsTest 10/10, TranslationTraceTest 13/13, ReaderTranslationFeedbackTest 14/14, RollingAutoCoordinatorTest 31/31 (pre-existing, unchanged).

- `TranslationSchedulerTraceTest` (real scheduler + fake executor): one schedule+one run correlation with measured `lease_wait`; mid-flight cancel → exactly one `cancelled` run_end + schedule_end; **cancel-before-dispatch** (immediate `close()`) → exactly one terminal from `invokeOnCompletion`. Directly closes the amendment 10.2 hole flagged in the assignment.
- `RollingAutoCoordinatorTraceTest` (real coordinator + gated executor): overlap case with `maxConcurrent=2`, one schedule / 2 runs / distinct rids / correlated `prepared_queue` / exactly one success terminal each; cancel case asserting `run_end count == run_start count` with exactly one drained `success`, the rest `cancelled`/`cancelled_during_send`, one `schedule_end cancelled`; manual lease theft → `evicted` (real store lease manipulation); drain-grace expiry → `timeout` with unchanged rethrow; gate-off parity (identical invocation counts, detailed families suppressed, terminals emitted).
- Diagnostics additions close Phase 2 F1 with strong pins: hostile reason, charset-safe-but-unregistered reason, and empty all → `reason=invalid` on both `schedule_state` and `route_change`; every vocabulary token passes through; hostile `errorType` override → `errorType=invalid`; `providerFromLabel` bounded incl. path-traversal and `fixed_qnn_htp`-style AOT labels → NONE/`qnn_htp`.

Coverage notes (non-blocking): `coordinator_replaced`, `stale_handoff`, `persistence_rejected` (non-theft), and the attached-family manual outcomes are wired but not terminality-tested (the implementer's matrix honestly marks them "wired"); `native_queue` is not exercised by JVM coordinator tests (fake executor replaces the pipeline — disclosed; observable in Phase 4 device validation).

## 10. Failure-mode audit results

- **Double-logging:** none found; all terminals CAS-guarded; the repeated-viewport `window_update` reports coalesce.
- **Run traces leaking across pages:** each run is bound via `TranslationTraceElement` install/restore; consumer installs per-`PreparedWork` trace inside the drain block. No cross-page contamination path found.
- **Accumulator balance on replacement:** sweep closes the schedule and nulls `scheduleTrace`; in-flight lane tokens captured from the old schedule close as no-ops on a closed accumulator; the cancelled consumer loop cannot capture a token from a *new* schedule (the loop exits at `ensureActive()` after the drained item finishes).
- **Trace state beyond termination:** bounded by construction (fixed EnumMap + counters; sweep empties `activeRunTraces`; `drainingRun` nulled in `finally`). One exception: the F1 raced run below.
- **Perf hazards:** ~13 small span allocations per page regardless of gate (no string building when the gate is off — formatting sits behind the gate check); `providerFromLabel` does a few lowercase allocations per recognition; `recordRetry` is one atomic increment. Bounded; the ≤3% overhead acceptance stays deferred to Phase 4 device validation (disclosed).

---

## Findings

### F1 — MEDIUM likelihood-LOW (terminality race; recommended follow-up, not blocking)
`RollingAutoCoordinator.reconcilePass` registers the run via `activeRunTraces[idx] = runTrace` (:867) **outside** `lifecycleLock`, while `cancelLocked` → `sweepTracesLocked` iterates that map under the lock. Interleaving: sweep iterates before the put → the run is missed; the producer then reaches `if (!isGenerationActive(...)) return false` (:879, and again post-prepare) and returns **without a terminal or a retire**. The run stays in `activeRunTraces` and receives its terminal only at the *next* `cancelLocked` sweep — or never, if no further cancel/shutdown occurs in the process lifetime. Impact is diagnostics-only (a `run_start` whose `run_end` is late or absent; one small retained object), the schedule summary is unaffected, and every deterministic path is correct — hence not blocking. Evidence: live code ordering above; the implementer's matrix row "generation death before/inside prepare → sweep" holds except for this window. Options for Phase 4: re-check `isGenerationActive` under `lifecycleLock` immediately after registration and close+retire locally, or register before `markNativeAdmitted` so the sweep can never precede registration.

### F2 — LOW (span dangles on throwable)
`persistSpan` (TranslationPipeline.kt :638/:966), `commitSpan` and `flushSpan` (SinglePageHttpRenderPhase.kt :710/:745) are ended only after the wrapped call **returns**; if the wrapped call throws (cancellation, or a store exception on paths that historically propagate), the span never settles and a gate-on log shows `stage_start` without `stage_end`. Terminals are unaffected (fail-open, run end still fires), and `persistOnnxCleanedImage`'s null-return contract makes the window narrow. A `try/finally` (or ending in a `runCatching` result branch) would close it; not required.

### F3 — LOW (terminal ordering/cosmetics)
(a) A drained run's real `run_end` can land after its schedule's `schedule_end` (sweep exempts `drainingRun`): terminality holds, ordering is cosmetically inverted. (b) `schedule_end` upgrades `cancelled`→`success` from `hasNoFailedRuns()`; a drained run that subsequently reports `failure` cannot retract the already-emitted `success` summary. Rare, diagnostics-only.

### F4 — LOW (accepted, disclosed)
Trace objects (schedule/run/spans) are allocated even when the gate is off; only emission is gated. Per-page cost is a dozen small short-lived objects. The §10.5 ≤3% overhead and log-lines-per-page acceptance criteria remain unmeasurable until Phase 4 device validation — carried forward, already disclosed in the implementation report.

### F5 — INFO
`updateWindow` emits `schedule_start`/`schedule_state` while holding `lifecycleLock`; the emission is fail-open logcat I/O under a lock, but only in traced/debug configurations (gate-gated). Acceptable; avoid adding heavier sinks there later.

### F6 — INFO
A manual duplicate intent that hits the "already active" dedup early return emits **no** schedule/run at all (creation is deliberately after the return — the correct choice vs an orphan). Note for Phase 4 log analysis: dedup-skipped manual intents are invisible in traces (the executor-level `Rejected`→`skip` mapping still covers rejections that reach the executor).

### F7 — INFO (report accuracy)
The implementer's terminal-coverage matrix, legacy-log inventory, deviation list, and risk list were each cross-checked against live code and found accurate, including the disclosed RENDER-includes-LAYOUT sum and the unsettled swept `prepared_queue` spans. One terminology mapping required by Phase 2 F8 is satisfied implicitly: the code uses `stale_handoff` throughout (no `stale_retry` token exists or is needed).

## Required changes

**None.** Recommended (non-blocking): F1 follow-up at the start of Phase 4; consider F2's `try/finally` hardening opportunistically when those lines are next touched.

## Evidence index

- Scope: reviewer-run `git diff --binary` over the 14 baseline paths (SHA-256 `c0be540a…` ≡ baseline patch); `sha256sum` of the 5 protected untracked files; per-file diff stats and content greps for App.kt / OnnxBubbleSegmenter.kt.
- Terminality: TranslationScheduler.kt:658–795; TranslationPipeline.kt:497–706, 864–975, 1059–1196; SinglePageOnnxPhase.kt (decode span, plan resolution, traceTotalMs); RoiPageRecognitionEngine.kt:275–700 (span tracking + outer catch; no bypass returns — grep-verified); SinglePageHttpRenderPhase.kt:211–760 (governor flag, translate/layout/render/commit/flush spans); RollingAutoCoordinator.kt:143–168, 212–243, 336–400, 470–478, 526–712, 811–975, 1104–1108.
- Diagnostics: TranslationTrace.kt:559–596, 605–790, 798–926; TranslationPipelineDiagnostics.kt:180–211, 439–516, 720–812.
- Tests: result XMLs under `app/build/test-results/testStandardDebugUnitTest/` (1432/0/0, reviewer re-run 2026-09-04); new suites `app/src/test/java/eu/kanade/translation/scheduling/TranslationSchedulerTraceTest.kt`, `…/RollingAutoCoordinatorTraceTest.kt`; F1-closing tests in `…/diagnostics/TranslationPipelineDiagnosticsTest.kt:417–530`.
- 10.7 proof: repo-wide grep for `requestAutoWindow` / `translateSinglePageFromStream` call sites (only definitions + the TranslationManager delegation + the in-flow executor call at TranslationScheduler.kt:447).
