# Phase 4 (Batch trace wiring) — Independent Review

**Role:** Reviewer / Failure-mode auditor (T922)
**Date:** 2026-09-04
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`, HEAD `7a95f9c9` — unchanged from baseline capture)
**Reviewed artifact:** the uncommitted Phase 4 diff — 5 batch production files + `RollingAutoCoordinator.kt` (F1) + `TranslationPipeline.kt` / `SinglePageHttpRenderPhase.kt` (F2) + the extended untracked `diagnostics` package + 1 rewritten and 3 new JVM test suites.
**Governing contract:** plan `engineering/translation-pipeline-fix-and-observability-plan.md` §4.4 Batch, §5, §9, §10 amendments (10.2/10.4/10.5/10.6); Phase 3 review follow-ups F1/F2 (required in Phase 4).
**Inputs:** role file, task README, plan, `PHASE4_BATCH_WIRING_IMPLEMENTATION.md`, `PHASE3_MANUAL_AUTO_WIRING_REVIEW.md`, `PHASE2_TRACE_FOUNDATION_REVIEW.md`, `BASELINE_CAPTURE_2026-09-04.md` + baseline patch, live source/tests.

## Verdict: APPROVED WITH NOTES

Every audited contract clause is verified against primary evidence. Scope discipline is byte-for-byte proven; F1/F2 are implemented exactly as the Phase 3 review specified and nothing else changed in the Phase 1-3 files. The envelope double-count risk (§9) is closed at the mechanism level and pinned by a regression test. Schedule and run terminality (§10.2) holds on every walked exit path including teardown-exception. Tests are real and mutation-sensitive; the focused suite re-run by this reviewer is 195 suites / 1439 tests / 0 failures / 0 errors. Findings below are LOW/informational (diagnostics cosmetics and two narrow test-coverage gaps); none blocks Phase 4. The §10.5 ≤3 % overhead + log-lines-per-page acceptance remains the outstanding carry-forward and now requires the plan §7 physical-device validation.

---

## 1. Scope integrity — VERIFIED, zero deviation

Independently re-run by reviewer (not taken from the implementer's report):

| Check | Evidence | Result |
|---|---|---|
| HEAD / branch / staged state | `git rev-parse HEAD` = `7a95f9c9…`; branch correct; `git diff --cached --binary` = 0 bytes | Matches baseline |
| 14 Director-owned dirty tracked files | Fresh `git diff --binary` over exactly the 14 baseline paths → SHA-256 `c0be540a29f9…ab467`, **identical** to `baseline_tracked.patch` | ZERO new hunks |
| 5 protected untracked files | Re-hashed: `e1dce9a9…`, `430102d9…`, `f66aaabe…`, `7d61f647…`, `de74c62c…` — all match `BASELINE_CAPTURE` exactly | BYTE-IDENTICAL |
| Modified tracked inventory | 28 = 14 baseline + App.kt + OnnxBubbleSegmenter.kt (Phase 1) + the 6 Phase 3 files + 5 batch files (`BatchChapterTranslator`, `SequentialBatchCoordinator`, `BatchTranslationDiagnostics`, `BatchLaneWorkers`, `BatchRenderJoin`) + `BatchTranslationDiagnosticsTest.kt` | EXACT |
| Phase 1-3 files beyond F1/F2 | `TranslationScheduler`, `SinglePageOnnxPhase`, `RoiPageRecognitionEngine`: added lines contain **zero** Phase-4 markers (grep for `scheduleTrace/beginScheduleStage/TranslationScheduleStageSpan/reportUngroupedState/SOURCE_FINGERPRINT/envelope/BatchTranslation/activeSchedule`; the single `totalMsAt` hit in SinglePageOnnxPhase is the Phase 3 run-level API, not the new schedule-level one). `RollingAutoCoordinator` added lines: **0** batch-marker hits — F1 hunk only. `TranslationPipeline` / `SinglePageHttpRenderPhase`: F2 hunks only (below) | F1/F2 ONLY |
| New untracked source files | `BatchPhase4TraceWiringTest.kt`, `RollingAutoCoordinatorRegistrationRaceTest.kt`, `ManualAttachOnBatchTraceTest.kt` (+ the 2 Phase 4 test rewrites/edits inside already-untracked/modified paths) | EXACT |

### F1 hunk — exactly the review's suggested fix

`RollingAutoCoordinator.reconcilePass` (~:865-890): registration `activeRunTraces[idx] = runTrace` and the generation liveness re-check are now one `synchronized(lifecycleLock)` block; on a dead generation the run is closed locally (`end(CANCELLED)`), retired with the **identity-based two-arg** `remove(idx, runTrace)`, `removeNativeAdmitted` is invoked, and the producer returns false.

- **Race closed:** `cancelLocked → sweepTracesLocked` iterates the registry under the same `lifecycleLock` (live :336-374). All orderings are covered: sweep-before-register → sweep misses the run, the F1 block then observes the dead generation and closes + retires locally; register-before-sweep → the sweep owns the terminal; no third ordering exists because check-and-register is atomic against the sweep's iterate-and-close. VERIFIED.
- **No return without terminal/retire:** the only new early exit is the dead-generation path, which performs terminal + retire + admission cleanup before `return false`. `removeNativeAdmitted` (:1097-1103) is lifecycleLock-guarded and a no-op (`isGenerationActiveLocked` guard) on a dead generation — no scheduling-state change on live generations. VERIFIED.
- **No suspension under the lock:** the block contains only `isGenerationActiveLocked` (two field reads, :1069-1070) and a ConcurrentHashMap put. `runTrace?.end` is CAS + fail-open emission, non-suspending. VERIFIED.
- **No deadlock:** no nested lock acquisition; `removeNativeAdmitted` takes the lock after the block exits; the sweep side does only in-memory + CAS work. VERIFIED.
- Deterministic regression test: `RollingAutoCoordinatorRegistrationRaceTest` — the trace sink re-enters synchronously inside `startRun`, performs an identity change (cancel sweep under the lifecycle lock) in the exact between-startRun-and-registration window (Unconfined, no sleeps), and asserts exactly one `run_end outcome=cancelled` for the raced rid (pre-fix: zero — the leak) plus both schedule terminals (`coordinator_replaced`, `success`). Mutation-sensitive by construction. VERIFIED.

### F2 hunks — exactly the review's suggested fix

- `TranslationPipeline.kt` both `persistSpan` sites (~:634-669 and ~:972-993): converted to try/`catch end(FAILURE, error) → rethrow` / settle-success; null-return still maps to the pre-existing `Failed` outcome / abort branch. Call semantics byte-equivalent to the original `?:` / null-check flow — no behavior change. VERIFIED.
- `SinglePageHttpRenderPhase.kt`: `commitSpan` around `store.patchPage` (~:707-737) settled via try/catch (Rejected → FAILURE, throw → FAILURE + rethrow; result handling below unchanged); `flushSpan` in the outer `finally` (~:748-765) is try/finally around `store.flush()`, exception propagation unchanged. No double-settle: every span `end` is a single CAS (`TranslationScheduleStageSpan.end`/`TranslationStageSpan.finishStage`, TranslationTrace.kt :735, plus Phase 2 CAS at run/schedule closes). VERIFIED.
- Cosmetic only: a throwing `store.flush()` still records the flush span's default SUCCESS outcome (disclosed in the report), and the persist spans label a cancellation throw FAILURE rather than CANCELLED. Terminality — the §10.2 requirement — is intact in both cases.

## 2. Envelope attribution (plan §9, amendment 10.6 risk) — VERIFIED

Arithmetic walk for N pages sharing one envelope of duration D:

- **Envelope duration counted ONCE:** `BatchLaneWorkers.translateChunkAi` (:334-370) opens exactly one `scheduleTrace.beginStage(TRANSLATE, lane=PROVIDER, provider=REMOTE, items=blockCount)` — a `TranslationScheduleStageSpan` with `rid=none` that "records into no per-run map" (TranslationTrace.kt :601-635) — per provider request (invoked per planner emission, `processAiEmission` :1606-1637). The wrapper/delegate split guarantees the body's early `return`s cannot bypass settlement: typed outcome on normal return (Completed→success, Paused→pause, Failed/Unexpected→failure, PersistenceRejected→persistence_rejected), CANCELLED/FAILURE + error on throw, `envelope=<opaque token>` on both.
- **Nothing multiplies D into page times:** per-page provider WAITING is a separate run-scoped span opened by the coordinator at `translationRequested` (SequentialBatchCoordinator.kt :332-337 / :369-374 / :441-446 / :480-485) carrying its own duration (the page's honest wait) and `queueMs` (envelope work start − page translate-ready); stage spans are metadata-only (`beginStage` performs no `enterLane`, Phase 3 verified), so they cannot feed the schedule busy/overlap totals; the PROVIDER lane token that feeds the accumulator spans the translation branch once per chunk and is `finally`-closed (:320/:420, :434/:531). Provider busy time is therefore integrated once ≈ D.
- **Facade cannot double-count:** `recordBatchStageEnd`/`recordBatchScheduleState` (TranslationPipelineDiagnostics.kt :598-654) are standalone emitters that mutate no run stage map and no accumulator (explicit contract comment :584-589, verified by read).
- **Regression test pins it:** `envelope duration counted once at schedule scope independent of page waits` — exactly one `rid=none` translate stage_end carrying the envelope token with durationMs ≥ 50 (real 80 ms sleep); a second `end()` emits nothing (CAS); exactly one rid=none+envelope event; two per-page events with durationMs ≤ 50, `queueMs=7`, same sid; facade failure (SocketTimeoutException → `errorType=http`) and timing fall back to the active schedule identity without envelope tokens; exactly 2 run terminals + 1 schedule_end. The parity test additionally asserts **zero** rid=none translate events on the coordinator path.

## 3. Terminal semantics (amendment 10.2) — VERIFIED, every walked exit path

**Schedule end exactly once (`BatchChapterTranslator.translateBatch` :194-254).** Default `TEARDOWN_EXCEPTION`; `CancellationException → CANCELLED` (rethrow); any unplanned `Throwable → TEARDOWN_EXCEPTION` (rethrow); every planned exit sets a typed outcome: empty batch → SKIP (+ tracker abort, R4 semantics preserved), engine-setup null → TIMEOUT/FAILURE (+ lease release + tracker abort), OOM abort → FAILURE, pass-1 stop → `BatchPass1Status.toScheduleOutcome()` (PAUSED/PERSISTENCE_REJECTED/FAILED/COMPLETED, exhaustive when), success → SUCCESS; `finally { scheduleTrace.end(scheduleOutcome) }` is idempotent and survives a throwing teardown region (proven by the teardown-exception test: exception propagates, exactly one `schedule_end outcome=teardown_exception`, `engine_setup` settled). `activeSchedule` cleared only when still ours (`===` guard). Schedule-scope spans (`engine_setup`, `source_fingerprint`, NonCancellable `store_flush`) all settle on throw/timeout.

**Page run_end exactly once (`SequentialBatchCoordinator`).** Runs are registered in the `activeRuns` sweep registry **before** any work (:101). Walked exits: OCR persistence-rejected → `persistence_rejected` + rethrow; OCR cancellation → `cancelled` + rethrow; OCR other → `failure` + `UnexpectedBatchStageException`; null work item → `updatePlan(SKIP)` + `skip` terminal + trace nulled (prevents post-terminal render spans); normal chunks → `settleChunkRuns` after `renderJob.await()` with `mappedRunTerminal` (SUCCESS for pass-completed keys; PAUSE; anchor `persistence_rejected`/FAILURE else CANCELLED; Completed→CANCELLED defensive-dead); unwind sweeps: `BatchPersistenceRejectedException` → anchor `persistence_rejected` + co-chunk `cancelled`, `UnexpectedBatchStageException` → anchor `failure`, `finally` → all `cancelled` (runs even during cancellation unwinding; non-suspending). Sweeps and per-path closes all remove with identity from the CAS-guarded registry — exactly one terminal even where paths overlap. D5 (terminal strictly after all stage ends on the normal path) verified: `settleTranslateWaits` closes WAIT spans at translation-branch completion, run terminals fire only after the render join.

**Lane handles balanced.** Coordinator NATIVE (:105/:130, :544/:570), PROVIDER (:320/:420, :434/:531), RENDER (:204/:229) tokens are all `finally`-closed — including on cancellation mid-envelope, so the overlap accumulator can never be left unbalanced (only non-accumulator stage spans can dangle — see N2).

**No suspension points added.** Zero `suspend` declarations in the diagnostics package (grep) and in the facade; all new trace calls are plain functions. The only new coroutine boundaries are the `withContext(TranslationTrace.elementFor(run))` wraps around the stage calls — the §4.4-sanctioned propagation mechanism (same documented deviation as Phase 3). Timers are try/finally or explicit-catch based; all existing typed outcomes and rethrows preserved (`CancellationException` rethrow preserved in all six coordinator catch sites checked).

## 4. No-scheduling-change — VERIFIED

- Removed lines across `SequentialBatchCoordinator` and `BatchChapterTranslator` diffs are exclusively legacy `BatchTranslationDiagnostics.failure/timing/stageDecision` calls, their `startedAt`/`elapsedMs` scaffolding, and re-indentation from span wrapping. Chunk planner (admission/boundary/probe/RESCAN_MAX_ATTEMPTS), native/translate overlap design, render-join semantics, OOM/stop/reconcile flow are untouched.
- `RollingAutoCoordinator` beyond F1: zero added lines reference batch or new diagnostics APIs.
- Proven by tests: `BatchPhase4TraceWiringTest` parity case drives the real coordinator concurrently and pins the exact per-page event families; the pre-existing `SequentialBatchCoordinatorTest` (17/17) and 15 coexistence-suite XMLs pass unchanged; ReaderTranslationFeedbackTest 14/14.

## 5. Privacy (amendments 10.3/10.4) — VERIFIED

- Legacy migration inventory cross-checked against the live diff — accurate. The `TachiyomiAT.Batch` tag now exists only as the facade constant and is used solely by `memorySnapshot` (deviation D1, disclosed; JVM heap telemetry, no raw content). All legacy `*Message` builders are deleted except `memoryMessage`.
- Every facade method resolves identity first (`currentRun()?.identity ?: activeSchedule.takeIf { !it.isClosed }`) and fails open — no fabricated sid. Emissions carry enum tokens, counts, durations, the opaque envelope token (`ShortHash` hex, `h#` prefix stripped for the schema charset), and `attempt` only when > 0. `boundedErrorType` collapses unrecognized class names to the fixed `unknown` token — raw class names never survive (pinned by test asserting the hostile errorClass yields `errorType=unknown` and no `Exception`/`IllegalState` substring anywhere).
- No raw chapter/manga/page names, source text, translations, or throwable messages under `TachiyomiAT.Translation`: `chapterRaw`/`pageRaw` are keyed-HMAC inputs only (Phase 2 boundary). The pre-existing raw-name shell logs (`batch START chapter=…`, `stranded page … pageKey=…`, etc.) were verified present at HEAD `7a95f9c9` — pre-existing behavior on the default tag, outside the boundary, untouched by Phase 4.
- Hostile-string regression: `delegated diagnostic events carry only bounded tokens and no raw content` asserts a hostile page/fingerprint/errorClass string (XSS + prompt-injection pattern) appears in **no** captured line.

## 6. Gating (amendment 10.5) — VERIFIED

`recordBatchStageEnd` delegates to `emitStageEnd`, which applies the identical rule as native stage ends (`if (!detailedTracingEnabled && !lag && outcome == SUCCESS) return`, TranslationPipelineDiagnostics.kt :481 region) — facade failures always survive the gate at WARN, facade successes are detailed-only. `recordBatchScheduleState` → `emitScheduleState` → detailed-gated (:564). Terminal `run_end`/`schedule_end` have no gate check. There is **no ungated duplicate emission path** — the facade has no independent sink. Non-suspending, fail-open (`try/catch(Throwable)` everywhere).

## 7. Overlap math usage (amendment 10.6) — VERIFIED

Batch feeds NATIVE (one token per OCR admission + one per page inpaint), PROVIDER (one token per chunk translation branch / inline translation), and RENDER (one per page render) into the Phase 2 accumulator. `schedule_end` exposes the full field set — `wallMs, nativeBusyMs, providerBusyMs, renderBusyMs, overlapMs, unionActiveMs, concurrencySavingsMs, workMs, criticalPathMs, maxQueueMs, slowestPage, bottleneck, outcome` (scheduleEndRecord :679-708). Pinned by the parity test with real concurrency: `overlapMs ≥ 1`, `concurrencySavingsMs ≥ 1`, `wallMs ≥ overlapMs`, outcome `success`. Queue waits (`native_queue` spans) are wired as queue stages and feed `maxQueueMs` per the Phase 2 F4 decision.

## 8. Tests — VERIFIED (real, mutation-sensitive), re-run independently

Reviewer re-ran the full focused suite in a clean background build: **BUILD SUCCESSFUL — 195 suites, 1439 tests, 0 failures, 0 errors, 0 skipped** (`:app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "…ReaderTranslationFeedbackTest"`). Key XMLs: BatchPhase4TraceWiringTest 4/4, BatchTranslationDiagnosticsTest 4/4, RollingAutoCoordinatorRegistrationRaceTest 1/1, ManualAttachOnBatchTraceTest 1/1, SequentialBatchCoordinatorTest 17/17, TranslationTraceTest 13/13, TranslationPipelineDiagnosticsTest 10/10, ReaderTranslationFeedbackTest 14/14.

- **Parity test** (§6.3 case 3): real concurrency through the real coordinator (real sleeps, `Dispatchers.Default`) — exact counts for schedule/run/stage families, distinct rids under one sid, exactly-once terminals, measured overlap.
- **Envelope regression** (§9): see §2 above — exact-count, mutation-sensitive.
- **Teardown-exception + control** (§10.2): real `BatchChapterTranslator` with an admission-null native lane; throwing `onBatchClosed` → exception propagates AND exactly one `schedule_end outcome=teardown_exception` with `engine_setup` settled; control → `failure`.
- **Attach test** (§6.3 case 4): real scheduler manual intent over a batch-owned page → `run_end outcome=attached`, `schedule_end outcome=attached`, ZERO native/translate/ocr/inpaint stage facts on the run's rid.
- **Facade tests**: hostile-string privacy, fail-open with no schedule (0 lines), memory-snapshot format unchanged, envelope lifecycle exact fields (state/reason/attempt/envelope/sid).

## 9. Failure-mode audit results

- **ChunkPage trace state beyond chunk lifetime:** bounded. `ChunkPage` (trace + timestamps + open translateSpan) lives only in per-iteration chunk lists and the single `retainedProbe` slot; `trace` nulled at `settleChunkRuns`/skip; the probe's run is in `activeRuns` and is swept on any stop; its native handoff is released in the `finally` (:790-791). No chapter-length retention.
- **Double lane-enter on retry paths:** none found. The inpaint downscale retry runs inside the already-admitted lane (no second `withNativeLane`, no second token); the deferred-page rescan creates a fresh run + token per `runOcr` call, each `finally`-balanced.
- **Schedule accumulator unbalanced on cancellation mid-envelope:** impossible — all lane tokens are `finally`-closed (§3). Only non-accumulator stage spans can dangle (N2/N3), which loses a stage fact but cannot corrupt busy/overlap/savings totals.
- **Facade dead code:** `timing` now has zero production callers (the coordinator's call sites were replaced by correlated spans; the method is kept and tested for the public compatibility surface). `stageDecision`'s `fingerprint` parameter is accepted but ignored (D2, disclosed). `stageDecision`/`reuse` retain live out-of-scope callers (`SinglePageHttpRenderPhase:385`, `ChapterArtifactStore:691`). Informational.
- **Post-terminal stage ends** are dropped by the §10.2 rule (`finishStage` :860 `if (closed.get()) return`) — the enforcement backstop works, but produces the two ordering notes below.

---

## Findings (all non-blocking)

### N1 — LOW (runOcr failure-path ordering drops the OCR stage fact)
In `runOcr`, the failure catches end the **run** (`runTrace.end(...)`) before the `finally` settles `ocrSpan` — and `finishStage` drops post-terminal stage ends. On OCR failure/cancel/persistence-reject, the log shows `stage_start(ocr)` with no `stage_end(ocr)`, and the already-emitted `run_end` summary excludes the OCR duration from `stageSumMs`/`bottleneck` (the wall `totalMs` and typed outcome remain honest). Success paths are unaffected (terminal comes later via `settleChunkRuns`). Options: end the span before the run in each catch, or move the run terminal into the `finally`. Diagnostics-only; exactly-once terminality holds.

### N2 — LOW (cancellation mid-chunk leaves some stage spans dangling)
If the batch scope is cancelled while the render job waits on a branch gate, `joinSpan` (opened before the gate awaits, no try/finally) and any still-open chunk-admission per-page translate WAIT spans (`settleTranslateWaits` is reached only on the non-cancel path) never settle — `stage_start` without `stage_end` on a gate-on log. Run/schedule terminals still land exactly once via the sweeps, and no accumulator state is affected. Consistent with the §9 "timers in try/finally" guidance; candidates for the same hardening pattern used elsewhere.

### N3 — LOW (BatchRenderJoin commitSpan not throw-protected)
`commitSpan` around `store.mergeRender` (BatchRenderJoin.kt :248-259) is ended only after the call returns; a throw (e.g. cancellation while suspended, or a store exception) leaves it dangling. The F2-style try/finally hardening applied to `SinglePageHttpRenderPhase` was not applied here. Also, if `RenderStagePatch` construction throws after `layoutSpan.end(SUCCESS)`, the outer catch's `end(FAILURE)` is CAS-suppressed and the span stays recorded SUCCESS — pathological, cosmetic.

### N4 — LOW (production envelope path not exercised end-to-end by JVM tests)
No test drives `BatchLaneWorkers.translateChunkAi` (grep: zero references in test sources), so the envelope span's production-path outcome mapping and on-throw settlement are read-verified but not test-pinned; the §9 pin is at the `schedule.beginStage` mechanism level (which the CAS/primitive tests do cover), plus the parity test's assertion that the coordinator path emits no rid=none translate event. Likewise `native_queue`/`cleaned_persist` worker spans remain JVM-untested (carried from Phase 3, observable in §7 device validation). Acceptable; listed so the gap is explicit.

### N5 — INFO (cosmetic outcome labels)
`flushSpan` records default SUCCESS even when `store.flush()` throws (disclosed); the F2 persist spans label a cancellation throw FAILURE rather than CANCELLED; `engine_setup` settles SUCCESS when the lane returns null (the failure is carried by the schedule outcome TIMEOUT/FAILURE). None affects terminality or totals.

### N6 — INFO (report accuracy)
The implementer's file-by-file wiring, envelope design, deviation list (D1-D7), legacy-log inventory, and risk list were each cross-checked against live code and found accurate. One nuance not itemized in the report: the pass-1 catch blocks' legacy `failure(...)` lines were replaced by the `sweepUnsettledRuns` terminals (the failure fact moves to run_end + schedule summary) — an even stronger replacement than "retained", consistent with the report's sweep description. Edge: a pass-1 exception with an empty sweep registry emits no per-page failure event (schedule outcome still carries it).

### N7 — CARRY-FORWARD (§10.5/§7 acceptance still open)
The ≤3 % diagnostics-on/off overhead, dropped-frame, bounded-allocation, and log-lines-per-page acceptance criteria remain unmeasured; plan §7's physical-device validation (manual/Auto/batch controlled runs, terminal-parity and overlap checks on device) is the remaining Phase 4 deliverable. Disclosed in every phase report; must be scheduled — it is the only acceptance item not satisfiable by JVM evidence.

## Required changes

**None.** Recommended (non-blocking): N1/N2/N3 span-hardening at the next touch of those regions; N4 envelope-path JVM test when the lane workers next get a test seam; N7 device validation before the roadmap's performance claims are signed off.

## Evidence index

- Scope: reviewer-run `git diff --binary` over the 14 baseline paths (SHA-256 `c0be540a…` ≡ `baseline_tracked.patch`); `sha256sum` of the 5 protected untracked files; per-file added/removed-line attribution greps for all 12 modified source files; F1/F2 hunks read in full.
- F1: RollingAutoCoordinator.kt :151-179, :336-374, :865-890, :1066-1131; race-window walk in §1.
- F2: TranslationPipeline.kt :634-697, :972-993; SinglePageHttpRenderPhase.kt :707-765 (live diff hunks).
- Envelope/terminality: BatchChapterTranslator.kt :194-254, :295-336, :390-409, :665-682, :769-806; SequentialBatchCoordinator.kt :86-160, :185-235, :316-422, :431-533, :535-595, :798-910; BatchLaneWorkers.kt :334-370, :903-1010, :1085-1232; BatchRenderJoin.kt :196-267.
- Diagnostics: TranslationTrace.kt :574-598, :601-759, :840-917; TranslationPipelineDiagnostics.kt :447-528 (gate), :560-654 (state + recordBatch*), :679-708 (schedule_end fields), :818-822 (envelope sanitization).
- Tests: result XMLs under `app/build/test-results/testStandardDebugUnitTest/` (195/1439/0/0, reviewer re-run 2026-09-04); new suites `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchPhase4TraceWiringTest.kt`, `…/BatchTranslationDiagnosticsTest.kt`, `…/scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt`, `…/ManualAttachOnBatchTraceTest.kt`.
- Privacy: `TachiyomiAT.Batch` tag grep (facade only, memorySnapshot sole user); HEAD pre-existence check of raw-name shell logs via `git show HEAD:…BatchChapterTranslator.kt`.
