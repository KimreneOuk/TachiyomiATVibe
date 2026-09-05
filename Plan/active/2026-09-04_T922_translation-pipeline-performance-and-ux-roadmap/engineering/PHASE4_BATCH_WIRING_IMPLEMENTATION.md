# Phase 4 — Batch Trace Wiring Implementation (T922)

Task: `2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap` (plan §4.4 Batch, §5 file map, §9 envelope double-count risk, §10 amendments 10.2/10.4/10.5/10.6)

Status: complete. Main sources compile (`:app:compileStandardDebugKotlin` BUILD SUCCESSFUL); new Phase 4 test classes pass; full focused suite run recorded at the end of this report. All work is uncommitted in worktree `optimize_translation_pipeline_ux` as directed.

---

## 1. Scope delivered

One schedule trace per batch invocation, one distinct correlated run per page, stage timers replacing the isolated legacy `TachiyomiAT.Batch` lines, schedule-scope envelope attribution (§9), exactly-one-terminal run ownership on every exit path (§10.2), and the F1/F2 carry-forward fixes from the Phase 3 review.

## 2. Carried-forward fixes (done first)

### F1 (MEDIUM) — `RollingAutoCoordinator.reconcilePass` registration race

`app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` (~:865-886)

Before: the page run was registered in `activeRunTraces` OUTSIDE `lifecycleLock`, so `cancelLocked → sweepTracesLocked` (which iterates the map under the lock) could interleave between `startRun` and registration — the sweep never saw the run and the run leaked unclosed forever.

After: registration and the generation liveness re-check are ATOMIC under `lifecycleLock`:

```kotlin
var generationDeadAtRegistration = false
synchronized(lifecycleLock) {
    if (isGenerationActiveLocked(spec.generation)) {
        if (runTrace != null) activeRunTraces[idx] = runTrace
    } else {
        generationDeadAtRegistration = true
    }
}
if (generationDeadAtRegistration) {
    runTrace?.end(TranslationTraceOutcome.CANCELLED)   // local terminal
    if (runTrace != null) activeRunTraces.remove(idx, runTrace)
    removeNativeAdmitted(idx, spec.generation)
    return false
}
```

Either the generation is alive (run registered; the sweep owns its terminal) or already dead (the sweep already ran and never saw this run, so it closes + retires it locally). `removeNativeAdmitted` is the lifecycleLock-guarded no-op on a dead generation. No scheduling behavior change.

Deterministic regression test: `RollingAutoCoordinatorRegistrationRaceTest` — the trace sink fires synchronously inside `startRun`; the hook performs an identity change (cancel sweep under the lifecycle lock) exactly in the between-startRun-and-registration window, forcing the race deterministically (Unconfined dispatcher, no sleeps). Asserts the run gets exactly one `run_end outcome=cancelled` (pre-fix: zero terminals — the leak) and both schedules terminate exactly once.

### F2 (LOW) — persistence spans now settle on every path

- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` — both `persistSpan` sites (cleaned-image persist before AI commit ~:638 and ~:966) converted to try/`catch end(FAILURE, error)` / settle-success pattern so `stage_end` always settles when `persistOnnxCleanedImage` throws.
- `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt` — `commitSpan` around `store.patchPage` (~:711) settled via try/catch (Rejected → FAILURE, throw → FAILURE+error); `flushSpan` around `store.flush()` (~:749) now try/finally (`end()` always, default SUCCESS, `end` is CAS-idempotent).

## 3. Diagnostics extensions (schedule-scope facility)

Plan §4.4 required a schedule-scope stage facility that did not exist in Phase 2.

`app/src/main/java/eu/kanade/translation/diagnostics/TranslationTrace.kt`:

- `TranslationTraceStage.SOURCE_FINGERPRINT` (batch fingerprint preflight).
- `TranslationScheduleState.SKIP` (OCR skip / artifact reuse contribute no work).
- 15 bounded `TranslationTraceReason` batch tokens: `reference_ready, no_reference, cache_hit, candidate_active, stage_failure, transient_failure, terminal_failure, artifact_reuse, envelope_admitted, envelope_request, envelope_retry, envelope_parsed, envelope_succeeded, envelope_failed, envelope_cancelled`.
- `TranslationScheduleTrace.beginStage(...)` returning the NEW `TranslationScheduleStageSpan` — schedule-scoped stage interval (`rid=none`): emits `stage_start`/`stage_end` with the schedule identity, `end(outcome, error, items, errorType, errorCode, queueMs, envelope)` CAS-exactly-once, `AutoCloseable`, fail-open.
- `TranslationScheduleTrace.reportUngroupedState(...)` (bypasses the state coalescer — used by the compatibility facade so facade emissions never mutate schedule state machine counters).
- `TranslationScheduleTrace.totalMsAt(nowNanos)` for terminal summaries.

`app/src/main/java/eu/kanade/translation/diagnostics/TranslationPipelineDiagnostics.kt`:

- `SOURCE_FINGERPRINT_MS = 750L` budget + `defaultLane` → NATIVE.
- `emitStageEnd`/`stageEndRecord` gained a trailing optional `envelope` field (` envelope=<token>` via `safeToken`, so only bounded tokens can appear).
- `emitScheduleState`/`scheduleStateRecord` gained `envelope` + `attempt` (`attempt=` suffix only when > 0).
- Public standalone helpers for the facade (they mutate NO run stage map and NO overlap accumulator — they can never double-count against trace-native spans):
  - `recordBatchStageEnd(identity, stage, lane, durationMs, items, outcome, errorType, errorCode, envelope)`
  - `recordBatchScheduleState(identity, state, reason, envelope, attempt)`

## 4. File-by-file wiring

### 4.1 `pipeline/batch/BatchTranslationDiagnostics.kt` — compatibility facade (rewritten)

Public surface preserved for out-of-scope callers (AiTranslationRetryController, TranslationRetry, ChapterArtifactStore, SinglePageHttpRenderPhase). Every method now delegates to `translation_trace_v1` under `TachiyomiAT.Translation`:

- Identity resolution: `TranslationTrace.currentRun()?.identity ?: activeSchedule?.takeIf { !it.isClosed }?.identity` — else fail open (no emission, no fabricated sid). `noteActiveSchedule`/`@Volatile internal var activeSchedule` is registered by `BatchChapterTranslator` for the duration of a batch.
- `stageDecision` → `recordBatchScheduleState` (EXECUTE→ADMITTED, REUSE/SKIP→SKIP, RETRY/FAIL→DEFERRED; reason = legacy reason token mapped through `toTraceToken()`).
- `timing` → `recordBatchStageEnd` (success→SUCCESS else FAILURE; items preserved).
- `reuse` → `recordBatchScheduleState(SKIP, reason)`.
- `failure` → `recordBatchStageEnd(FAILURE, errorType=boundedErrorType(errorClass))` — bounded mapping `ortexception→ort, cancellationexception→cancel, outofmemoryerror→oom, sockettimeoutexception→http, ioexception→io, illegalstateexception/illegalargumentexception/batchpersistencerejectedexception→contract, else "unknown"` (raw class names never survive).
- `envelopeLifecycle` → `recordBatchScheduleState` with phase→state/reason mapping (ADMITTED→QUEUED/envelope_admitted, PROVIDER_REQUEST/PARSED/SUCCEEDED→ADMITTED/envelope_request|parsed|succeeded, RETRY/FAILED→DEFERRED/envelope_retry|failed), plus `envelope=traceEnvelopeToken(pageKeys)` and `attempt`.
- `traceEnvelopeToken(pageKeys)` = `envelopeId(...).removePrefix("h#")` — the `#` separator is outside the schema's safe token charset, so the facade strips it instead of altering `safeTokenPattern` (which would have broken Phase 2 exact-line tests).
- Legacy message builders REMOVED (see migration inventory §6); `memorySnapshot`/`memoryMessage` intentionally unchanged.

### 4.2 `pipeline/batch/BatchChapterTranslator.kt` — schedule lifecycle + schedule-scope stages

- `translateBatch` is now a thin wrapper: creates the schedule via `startSchedule(mode=BATCH, origin=BATCH, chapterRaw=chapter.name, pages=size.takeIf{>0})` BEFORE engine setup, registers it as the facade's `activeSchedule`, then delegates to the verbatim body (renamed `translateBatchTraced`) with `scheduleTrace` and a `setScheduleOutcome` callback.
- `var scheduleOutcome = TEARDOWN_EXCEPTION` default; `CancellationException → CANCELLED`; any unplanned `Throwable → TEARDOWN_EXCEPTION` (rethrown); every planned exit sets a typed outcome; `finally { scheduleTrace.end(scheduleOutcome) }` — idempotent, survives teardown throws; `activeSchedule` cleared conditionally (only if still ours).
- Empty batch → `SKIP` terminal + tracker abort (zero-page failure semantics preserved, R4).
- Schedule-scope stages: `engine_setup` (span settles on throw, `onTimeout` flags TIMEOUT; null result → TIMEOUT/FAILURE outcome + tracker abort + lease release); `source_fingerprint` (wraps the I/O-only preflight, items = page count, FAILURE on throw); `store_flush` in the `NonCancellable` teardown (try/finally).
- OOM abort → FAILURE; pass-1 stop → `BatchPass1Status.toScheduleOutcome()` (PAUSED→pause, PERSISTENCE_REJECTED→persistence_rejected, FAILED→failure, COMPLETED→success); success → SUCCESS.
- `scheduleTrace` plumbed into `BatchLaneWorkers` and `SequentialBatchCoordinator`.

### 4.3 `pipeline/batch/SequentialBatchCoordinator.kt` — page runs + stage timers + lane transitions

- Constructor gained trailing defaulted `scheduleTrace: TranslationScheduleTrace?` (all existing positional call sites unaffected).
- `runOcr`: one `TranslationPipelineDiagnostics.startRun(schedule, pageRaw=pageKey, pageIndex, plan=FRESH)` per admission; registered in the `activeRuns` ConcurrentHashMap sweep registry BEFORE any work; OCR span + NATIVE lane token + `withContext(TranslationTrace.elementFor(run))` wrap around `nativeWorker.runOcrStage`; typed terminals: `BatchPersistenceRejected → run end(persistence_rejected) + rethrow`, `Cancellation → cancelled + rethrow`, other → `failure + UnexpectedBatchStageException`, settle in `finally` (span end + token close + listener). Null work item → `updatePlan(SKIP)` + `run end(SKIP)`; the returned `ChunkPage.trace` is nulled for skipped pages so the render join cannot open post-terminal spans.
- `ChunkPage` now carries `trace`, `ocrReadyAtNanos`, `translateReadyAtNanos` (set at lane admission completion), `renderJoinReadyAtNanos`, and the open `translateSpan`.
- Render job: `render_join` span around both branch gates (join-ready timestamp), `render` span + RENDER lane token + trace-context wrap around `awaitAndRender`/`awaitAndSettle`, outcome-var settle pattern (PersistenceRejected → FAILURE+rethrow, Cancellation → CANCELLED+rethrow, other → FAILURE + `UnexpectedBatchStageException(RENDER)`).
- Translation lane (remote): PROVIDER lane token for the whole chunk translation feeding the overlap accumulator; per-page TRANSLATE spans (`provider=REMOTE|LOCAL by compute class`, `items=blockFingerprints.size`) opened at `translationRequested` — the per-page provider WAIT, with `queueMs = workStart − translateReadyAtNanos` (planner/assembly wait). Trace-context wraps around `translateOutcome`/`runInpaintStage` so deep provider/ONNX code correlates stages to the page run.
- Inpaint: INPAINT span + NATIVE lane token per page with the same settle pattern.
- Terminal ownership (§10.2): `settleTranslateWaits(chunk, outcome, workStartNanos)` closes open translate WAIT spans exactly once when the translation branch completes; `settleChunkRuns(chunk, outcome)` — called ONLY after `renderJob.await()` — closes each run against `mappedRunTerminal` (SUCCESS for pass-completed keys; PAUSE; PERSISTENCE_REJECTED for the anchor else CANCELLED; FAILURE for the anchor/terminal keys else CANCELLED; Completed→CANCELLED defensive) and deregisters; `sweepUnsettledRuns(anchor, anchorTerminal, error)` closes every still-open run on unwind (`BatchPersistenceRejectedException` → anchor persistence_rejected, `UnexpectedBatchStageException` → anchor failure, `finally` → all-cancelled). CAS `end()` guarantees exactly one `run_end` even where paths overlap.
- Rationale for the split (found by the new parity test): settling runs mid-chunk (before the render join) raced the render loop and could drop the run's `render_join`/`render` stage facts (post-terminal stage ends are dropped by §10.2). Translate spans still close at translation completion; run terminals now strictly follow all stage ends. Terminals remain exactly-once via the sweep.
- Legacy `BatchTranslationDiagnostics.timing(...)` / `stageDecision(EXECUTE/SKIP)` call sites removed (replaced by correlated spans); `memorySnapshot` calls retained; the admission-catch `failure(...)` line retained (parity, schedule-scoped); unused `elapsedMs` helper removed.

### 4.4 `pipeline/batch/BatchLaneWorkers.kt` — envelope ONCE at schedule scope + persistence substages

- Constructor gained trailing defaulted `scheduleTrace: TranslationScheduleTrace?`.
- `translateChunkAi` split into wrapper + `translateChunkAiTraced` (verbatim body): the wrapper opens ONE `scheduleTrace.beginStage(TRANSLATE, lane=PROVIDER, provider=REMOTE, items=chunk.blockCount)` — the ENVELOPE duration at SCHEDULE scope (`rid=none`), settled once on normal return with the mapped chunk outcome (`Completed→success, Paused→pause, Failed/Unexpected→failure, PersistenceRejected→persistence_rejected`) and `envelope=traceEnvelopeToken(chunk.pages.keys)`; on throw → `CANCELLED`/`FAILURE` + error + rethrow. Wrapper/delegate split guarantees early `return`s inside the body cannot bypass settlement. The envelope duration is never multiplied into pages: pages carry only the separate WAIT spans opened by the coordinator.
- `runOcrStage` / `runInpaintStage`: `native_queue` spans (`TranslationTrace.beginStage(NATIVE_QUEUE)` — resolves the currentRun installed by the coordinator's element wrap; schedule is what feeds the lane accumulator in the coordinator, and the queue stage feeds `maxQueueMs` via `noteQueueWait`). Settle: first statement inside the lane (admission), `TIMEOUT` when `withNativeLane` returns null (onTimeout path), `CANCELLED`/`FAILURE` + error on throw — CAS-idempotent.
- Cleaned-image publication: `cleaned_persist` span (STORAGE lane) around `persistCleanedBitmap`, `FAILURE` when publication returns null or throws (typed outcomes per contract; the `BatchPersistenceRejectedException` semantics are unchanged).

### 4.5 `pipeline/batch/BatchRenderJoin.kt` — layout/render/commit outcomes

In `tryRender` (fail-open via `TranslationTrace.beginStage`, currentRun installed by the coordinator's render wrap):

- `layout` span (RENDER lane) around `RenderColorEstimator.recomputeFor`; `FAILURE`+error on throw (layout-failure handling below unchanged), otherwise SUCCESS.
- `render` span (RENDER lane) around the `RenderStagePatch` construction; always settles.
- `store_commit` span (STORAGE lane) around `store.mergeRender`: `Accepted → SUCCESS`, `Rejected → FAILURE` (existing `BatchPersistenceRejectedException` flow untouched).

Join WAIT is measured by the coordinator's `render_join` span (the gates live there), per the plan's split.

## 5. Envelope attribution design (§9)

- The shared provider envelope is a SCHEDULE fact: exactly ONE `stage=translate stage_end` with `rid=none`, `sid=<batch schedule>`, `envelope=<opaque token>`, duration = the real multi-page request.
- Each page's provider WAITING is a RUN fact: `stage=translate` with the page `rid`, `queueMs` = envelope-work-start − page translation-ready (planner/assembly wait), duration = the page's own wait, NOT the envelope duration.
- The facade's delegated emissions are standalone records (no stage-map/accumulator mutation), so a legacy `timing()` call can never add a second envelope count.
- Regression test asserts: exactly one envelope-token event; two per-page events with independent (short) durations and their own `queueMs`; a double `end()` emits once; facade failure/timing with no currentRun fall back to the active schedule and carry bounded `errorType`.

## 6. Legacy batch log migration inventory

| Legacy (TachiyomiAT.Batch) | Facade method | New event(s) (TachiyomiAT.Translation) | Parity proof | Legacy removed? |
|---|---|---|---|---|
| `stageDecisionMessage` line (stage/page/decision/reason/fingerprint/items) | `stageDecision` | `schedule_state` (state=admitted/skip/deferred, reason=legacy reason token, sid/rid correlation) | `BatchTranslationDiagnosticsTest.delegated diagnostic events...` — decision+reason facts present, raw page/fingerprint absent | Yes (builder deleted; only memoryMessage retained) |
| `timingMessage` line (stage/page/duration/items/success) | `timing` | `stage_end` (stage per BatchDiagnosticStage, lane, durationMs, items, outcome) | same test (timing success + failure variants); coordinator parity test shows per-page translate stage_ends | Yes |
| `reuseMessage` line | `reuse` | `schedule_state` (skip + reason) | same test | Yes |
| `failureMessage` line (stage/page/errorClass/retry/reason) | `failure` | `stage_end` FAILURE with bounded `errorType` (unknown-class names collapse to `unknown`) | same test asserts `errorType=unknown` + `errorClass` name never present | Yes |
| `envelopeLifecycleMessage` line (phase/envelope/pages/attempt/items/reason) | `envelopeLifecycle` | `schedule_state` (typed state/reason token, `envelope=` schema-safe token, `attempt=`) | `envelope lifecycle event has bounded correlation fields` test | Yes |
| `memorySnapshot` line (JVM heap telemetry) | unchanged | unchanged (legacy tag + format) | `legacy memory snapshot format is unchanged` test | Kept (deviation D1) |
| Coordinator `stageDecision(EXECUTE/SKIP)` + `timing(...)` call sites | (deleted calls) | correlated `stage=ocr|translate|inpaint` stage_end on the page run | SequentialBatchCoordinator parity test (per-page stage counts) | Yes |
| Coordinator admission-catch `failure(...)` | retained | schedule-scoped `stage_end` FAILURE | preserved call site | n/a |

No legacy line with raw names/text survives under the new tag: all page/chapter identity passes through HMAC keyed tokens; only `pageIndex` is positional (§4.2); all error classes pass through the bounded map.

## 7. Tests

New/updated (all JVM, JUnit5 + kotest + mockk):

1. `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchPhase4TraceWiringTest.kt` (new, 4 tests)
   - `batch parity one schedule distinct runs per-page stages and lane overlap` (§6.3 case 3): real concurrency (`Dispatchers.Default` + real sleeps) through `SequentialBatchCoordinator` with a batch schedule — one `schedule_start`/`schedule_end` (success, overlapMs>0, concurrencySavingsMs>0, wallMs≥overlap), 2 runs with distinct rids sharing the sid and exactly one success terminal each, 2 per-page translate WAIT stage_ends (rid≠none, queueMs≥0), 2 inpaint + 2 render_join + 2 render stage_ends, and no schedule-scoped translate event on the coordinator path.
   - `envelope duration counted once at schedule scope independent of page waits` (§9): one rid=none translate event carrying the envelope token with the real (≥50 ms) duration; per-page events short (≤50 ms) with own `queueMs=7`; double `end()` emits once; facade failure (SocketTimeoutException→`errorType=http`) and timing fall back to the active schedule identity; exactly 2 run terminals; exactly 1 schedule_end.
   - `teardown exception still emits exactly one schedule_end as teardown_exception`: real `BatchChapterTranslator` (NativeLaneRunner admission null → engine-setup failure; throwing `onBatchClosed` callback) — exception propagates, exactly one `schedule_end outcome=teardown_exception`, `engine_setup` stage settled.
   - `clean teardown emits one schedule_end with failure outcome` (control): no callback → one `schedule_end outcome=failure`.
2. `app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt` (new, 1 test) — deterministic F1 regression (see §2/F1).
3. `app/src/test/java/eu/kanade/translation/scheduling/ManualAttachOnBatchTraceTest.kt` (new, 1 test) — §6.3 case 4: manual intent over a batch-owned page (`SinglePageOutcome.Attached(BATCH)`) → one manual schedule + one run, `run_end outcome=attached`, ZERO native/provider stage facts attributed to the run (the paying work stays owned by the batch schedule), one `schedule_end outcome=attached`.
4. `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchTranslationDiagnosticsTest.kt` (rewritten for the facade, 4 tests) — assertion changes vs. the legacy file, all documented:
   - `diagnostic messages hash page and fingerprint inputs` → now drives the FACADE methods (stageDecision/timing/reuse/failure/envelopeLifecycle) and asserts the emitted schema lines contain no raw content, identity falls back to the active schedule sid, and unknown error classes collapse to the fixed `unknown` token. (The legacy internal `*Message` builders no longer exist — the builders were the privacy surface; the facade + schema now provide it.)
   - `facade fails open with no active schedule and emits nothing` (NEW — replaces nothing; covers the fail-open contract).
   - `legacy memory snapshot format is unchanged` — carried over verbatim (assertion unchanged).
   - `envelope lifecycle event has bounded correlation fields` — replaces the legacy exact-string `envelope_lifecycle` format test with the schema event (`state=deferred reason=envelope_retry attempt=3 envelope=<token> sid=<schedule>`).

Existing suites kept green (no assertion changes needed): `SequentialBatchCoordinatorTest` (constructor gained only trailing defaulted params; null-schedule paths fail open identically), RollingAutoCoordinator/TranslationScheduler/diagnostics Phase 1-3 tests, ReaderTranslationFeedbackTest.

### Output summary

- `:app:compileStandardDebugKotlin` — BUILD SUCCESSFUL.
- New Phase 4 classes: `BatchPhase4TraceWiringTest` (4), `BatchTranslationDiagnosticsTest` (4), `RollingAutoCoordinatorRegistrationRaceTest` (1), `ManualAttachOnBatchTraceTest` (1) — 10/10 pass.
- Focused suite `--tests "eu.kanade.translation.*" --tests "...ReaderTranslationFeedbackTest"` — result recorded below (updated at run completion).

**Focused suite result: BUILD SUCCESSFUL — 195 suites, 1439 tests, 0 failures, 0 errors, 0 skipped (`eu.kanade.translation.*` families plus `ReaderTranslationFeedbackTest`); all Phase 1-3 suites green.**

## 8. Deviations from the letter of the plan (all deliberate, none behavior-affecting)

1. **D1 — `memorySnapshot` stays on `TachiyomiAT.Batch`**: JVM heap telemetry is not a `translation_trace_v1` event family and contains no raw content; migrating it would break its consumers for zero schema gain.
2. **D2 — `stageDecision(EXECUTE)` fingerprint detail dropped**: the legacy line carried the (already-hashed) fingerprint; the correlated `stage=ocr` stage_end carries the execution fact and the schema keeps stage events content-free. Decision+reason facts move to `schedule_state`.
3. **D3 — envelope attempt granularity**: attempt counts live in the facade's `schedule_state` events (`attempt=`) and the envelope token on the schedule-scoped span; per-attempt DURATION accumulation was not added (single envelope attempt in flight per chunk; retry rounds are visible as new schedule_state events).
4. **D4 — native_queue settle model**: the span settles at lane admission (first statement inside the lane) and is re-settled CAS-idempotently for TIMEOUT/null/throw, instead of a separate queued-vs-running state machine.
5. **D5 — terminal timing split (`settleTranslateWaits`)**: run terminals are issued after the render join rather than at translation-branch completion, so `render_join`/`render` stage facts precede `run_end` (post-terminal stage ends are dropped by §10.2). Found by the new parity test; exactly-one-terminal ownership unchanged (sweeps + CAS).
6. **D6 — skipped-page trace nulling**: a null-ref OCR skip ends its run immediately (skip terminal) and nulls the `ChunkPage` trace so the render join cannot open post-terminal spans.
7. **D7 — legacy admission `failure()` call retained**: the one legacy facade call site that models a real failure line (translator admission throw) stays, now emitting a bounded schedule-scoped stage_end instead of a raw line.

## 9. Risks

1. **F1 test relies on synchronous sink re-entrancy** (sink fires inside `startRun`; Unconfined dispatcher makes the interleaving deterministic). If emission ever becomes async/queued, the test would stop hitting the race window (it would fail loudly, not pass silently).
2. **Parity test uses real sleeps** (100/150 ms lanes). Margins are wide; overlap>0 only requires ≥1 ms of genuine lane concurrency, so slow CI should not flake it. `@Timeout(120)` bounds a pathological run.
3. **`ChapterTranslationStore(translationFile=null)` fixture** (established Phase 3 pattern) exercises the in-memory store paths in the teardown test; a future store change that requires a real file would need the fixture updated.
4. **Facade identity resolution** uses the caller's installed run context or the single `activeSchedule` reference; a hypothetical second concurrent batch would leave facade events attributed to the newest schedule (single-batch-at-a-time is today's pipeline reality; per-run correlation is unaffected).
5. **`boundedErrorType` collapse to `unknown`** intentionally trades diagnostic specificity for the privacy boundary; unrecognized exception classes are indistinguishable in traces.
6. **Post-terminal stage-end drop rule (§10.2)** remains the enforcement backstop: any future path that emits stages after a run terminal will silently lose the stage fact rather than corrupt the terminal summary (this is what made D5 observable).

## 10. Director-owned files — untouched

Verified via `git status --short`: all Director-owned dirty/untracked files (OnnxRuntimeProvider.kt, ModelRoutingEngine.kt, QnnContextCacheManager.kt, AOTInpainting.kt, PaddleOcrV6DetEngine.kt, HardwareDiscoveryEngine.kt, QnnDiagnostics.kt, CleanedPublication.kt, ReaderTranslationFeedback.kt, AotBoxGeometry.kt, gradle/libs.versions.toml, and their tests) carry only their pre-existing Phase 1-3 baseline modifications; no Phase 4 edit touched them. Phase 1-3 production/test files were modified only for F1/F2 and the diagnostics extensions listed above. Nothing is committed.
