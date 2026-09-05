# T922 Technical Plan — Bubble Segmentation Recovery and Unified Translation Tracing

**Status:** PLAN ONLY; no production source was changed by this investigation.  
**Scope:** manual single-page, rolling Auto, and ordered batch translation.  
**Target constraints:** Android 8+, bounded memory, target devices with at least 6 GB RAM, reader stability, and no regression for ordinary manga.

## 1. Recommendation

Ship the bubble segmenter on the default CPU execution provider, retain QNN HTP only for the already-qualified AOT-GAN path, remove automatic QNN stress diagnostics from app startup, and introduce one privacy-safe `translation_trace_v1` event schema shared by manual, Auto, and batch scheduling. Keep a one-shot accelerator-to-CPU recovery path inside the bubble segmenter as defense in depth, but do not depend on it for normal production routing.

Do **not** restore QNN finalization mode 3 globally as part of this fix. The retained device evidence described in the prior investigation showed both the successful and failing bubble sessions using `options={backend_type=htp}`. Mode 3 may be a useful model-specific experiment, but it is not an evidenced explanation for the manual/Auto difference and does not repair runtime recovery.

## 2. Current behavior and evidence

### 2.1 Failure path

- **VERIFIED:** `OnnxBubbleSegmenter.initialize()` currently requests acceleration (`useAccelerator = true`) and records the provider string returned by `OnnxRuntimeProvider` (`OnnxBubbleSegmenter.kt:31-38`).
- **VERIFIED:** the first real inference occurs later in `segment()` at `current.run(...)`, outside session-creation fallback (`OnnxBubbleSegmenter.kt:49-99`). There is no `OrtException` recovery there.
- **VERIFIED:** `RoiPageRecognitionEngine.analyze()` invokes bubble segmentation inside the serialized native guard (`RoiPageRecognitionEngine.kt:274-297`). Thus an execution failure aborts recognition for the page; later pages reuse the same session.
- **VERIFIED:** `SinglePageOnnxPhase.processSinglePage()` converts recognition exceptions into a failed `PageTranslation` and skips inpaint (`SinglePageOnnxPhase.kt:999-1059`). The common native phase is used by manual and Auto. Batch calls the same recognition engine through `analyzePage()` (`SinglePageOnnxPhase.kt:801-916` and `BatchChapterTranslator.kt:510-515`). Therefore all three modes share exposure to a failing bubble session.
- **VERIFIED:** session creation currently calls `ModelRoutingEngine.markSupported()` before any inference executes (`OnnxRuntimeProvider.kt:98-106`), contradicting `ModelRoutingEngine`'s documented claim that `SUPPORTED` means the model creates **and executes** successfully.
- **VERIFIED:** `ModelRoutingEngine.isSupported()` excludes `TEMPORARY_FAILURE`, although `recordFailure()` says that state permits one recreation attempt (`ModelRoutingEngine.kt:57-65, 83-111`). The current temporary-retry contract is internally inconsistent.

### 2.2 Provider-label accuracy

- **VERIFIED:** in `createSessionWithFallback`, the label decision consults the device's active route after the `useXnnpack` branch instead of proving which EP registered (`OnnxRuntimeProvider.kt:98-116`). A non-accelerated XNNPACK request on an HTP-capable device can therefore be labelled `qnn_htp`.
- **VERIFIED:** XNNPACK registration failure is caught inside `createSessionOptions()` and only logged; the default CPU EP remains in use. No registration result reaches `providerSink` (`OnnxRuntimeProvider.kt:289-294 and CPU/XNNPACK branch below it`).
- **VERIFIED:** current recognition telemetry prints both global `HardwareDiscoveryEngine.activeRoute` and actual per-engine labels, which can disagree (`RoiPageRecognitionEngine.kt:554-562`). The global route is device preference, not proof of execution for that model.

### 2.3 Scheduling and timing visibility

- **VERIFIED:** single-page recognition reports one total plus detector/segmenter/OCR component durations, and inpaint reports another total (`RoiPageRecognitionEngine.kt:274-297, 329-511, 554-612`). Those events have no schedule, run, page, origin, or queue identity.
- **VERIFIED:** `[translation_page]` covers only the fused recognition/inpaint portion; it is not end-to-end page latency (`SinglePageOnnxPhase.kt:983-1105`).
- **VERIFIED:** provider translation and render have start/finish messages but no consistent durations or common correlation identifiers (`SinglePageHttpRenderPhase.kt:340-486, 491-666`).
- **VERIFIED:** batch has a privacy-safe diagnostic facade and individual stage timings (`BatchTranslationDiagnostics.kt`, `SequentialBatchCoordinator.kt:57-169, 376-450`), but lacks schedule/run identity, queue waits, render-join waits, overlap totals, end-to-end page totals, and a bottleneck summary.
- **VERIFIED:** rolling Auto has explicit native and translate lanes with a bounded prepared channel (`RollingAutoCoordinator.kt:321-350`), but currently logs only failures and UI slot state. Prepared-channel wait and cross-page native/provider overlap are not measured.
- **VERIFIED:** batch intentionally overlaps the translation branch with serialized native inpaint for remote translators, then joins both before render (`SequentialBatchCoordinator.kt:23-27, 124-171, 423-460`). Existing logs cannot quantify the overlap saved.

### 2.4 Diagnostic interference

- **VERIFIED:** every debug app startup launches `QnnDiagnostics.runOnce()` after one second on `Dispatchers.IO` (`App.kt:170-175`). It may overlap normal reader translation and creates a non-production workload during performance measurement.
- **STRONG INFERENCE:** concurrent diagnostic HTP/GPU sessions can amplify resource, thermal, or driver instability. It is not proven to be the sole cause of QNN error 1100, but it invalidates clean production-like timing and must be removed from automatic startup.

## 3. Fix design

### 3.1 CPU-primary bubble segmentation

Change `OnnxBubbleSegmenter.initialize()` to create the production session with:

```kotlin
useAccelerator = false
useXnnpack = false
```

Use the default CPU provider deliberately. The packaged QNN ORT artifact has already logged that XNNPACK is unavailable, so requesting XNNPACK today would only add a failed registration and obscure the real route. Expected label: `cpu`.

Store the normalized model path on the segmenter so a future accelerated/experimental session can be rebuilt on CPU after a runtime failure.

### 3.2 One-shot runtime recovery

Keep recovery local to the session owner, not in `SinglePageOnnxPhase`:

1. Execute `OrtSession.run()` normally.
2. Catch **only** `OrtException` from the run call. Do not reinterpret decoder contract errors, invalid output shapes, cancellation, OOM, or arbitrary application exceptions as provider failures.
3. If `executionProviderLabel` is not accelerated (`qnn_htp`, `qnn_gpu`, or `nnapi`), rethrow immediately. CPU is already the terminal route.
4. Record the accelerated runtime failure against the model and actual route in `ModelRoutingEngine`.
5. Close and discard the failed session under a segmenter-local lock. The existing recognition `nativeGuard` serializes current production calls, but the segmenter should still own its session-swap invariant for future callers.
6. Create an explicit default-CPU session, validate its input/output contract, set the label to `cpu`, and retry the same inference once.
7. If CPU creation or retry fails, propagate that failure with the first accelerator failure suppressed; never attempt a third run.

No bitmap, `OnnxTensor`, result, or direct-buffer ownership changes: the existing `finally` remains the single cleanup point. A failed partial result must be closed before retry if ORT returns one.

Because production initialization is CPU-primary, this branch is normally dormant. It protects later experiments/configuration changes and any already-created accelerated segmenter without making QNN the normal path.

### 3.3 Routing-state correction

Make the minimum consistency correction while touching the path:

- Remove `markSupported()` from session creation. Session creation proves `SESSION_CREATED`, not successful execution.
- Add `ModelRoutingEngine.recordSuccessfulInference(model, route)` and call it after the first successful accelerated `run()` in participating model owners. At minimum wire the bubble path; AOT-GAN should retain its own proven route telemetry and can be migrated separately if its session lifecycle is different.
- Treat `TEMPORARY_FAILURE` as eligible for the documented single accelerated recreation, or simplify the state machine by removing that promise. Preferred: make `isAcceleratorAttemptAllowed()` return true for `UNKNOWN`, `SUPPORTED`, and `TEMPORARY_FAILURE` only while the retry counter permits it.
- Classify QNN graph execute code 1100 as an accelerated model runtime failure even when the text does not contain `ENGINE_ERROR`; bubble will be demoted for that route and the current page will retry on CPU.

### 3.4 Honest provider labels

Refactor provider selection so the label derives from successful EP registration/session creation, never from `HardwareDiscoveryEngine.activeRoute` alone:

- `createSessionOptions()` reports a typed `RegisteredExecutionProvider` (`QNN_HTP`, `QNN_GPU`, `NNAPI`, `XNNPACK`, `CPU`).
- Strict accelerator registration errors propagate to `createSessionWithFallback`, which owns the CPU retry. Do not swallow the registration exception and then allow a default-CPU session to masquerade as QNN.
- XNNPACK registration failure explicitly resolves to `CPU`.
- `providerSink` receives the registered provider only after `environment.createSession()` succeeds.
- Recognition logs remove the ambiguous global `route=` field; each component reports its actual provider.

This is required for the new telemetry to be trustworthy.

### 3.5 Disable startup diagnostics

Remove the `BuildConfig.DEBUG` startup launch of `QnnDiagnostics.runOnce()` from `App.onCreate()`. Preserve `QnnDiagnostics` itself for an explicit developer/diagnostic entry point only. A normal debug reader session must have the same translation concurrency shape as release unless the developer intentionally starts the diagnostic suite.

## 4. Unified structured tracing

### 4.1 New diagnostic boundary

Add `eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics` and make existing `BatchTranslationDiagnostics` delegate to it. Use one log tag: `TachiyomiAT.Translation`.

Add three lightweight types:

- `TranslationScheduleTrace`: one manual intent, one rolling-Auto generation/session, or one batch invocation.
- `TranslationRunTrace`: one concrete page attempt. Auto carries the same run through native prepare and prepared translate/render. A stale re-prepare creates a new run under the same schedule. Batch page work shares the batch schedule but has a distinct page run.
- `TranslationTraceElement`: a coroutine `ThreadContextElement` exposing the current immutable trace identity to synchronous detector/segmenter/OCR/inpaint code without adding page arguments to every model API.

IDs are process-local opaque counters with a random process prefix; page, chapter, and engine-specific identifiers are hashed through `ShortHash`. Never log chapter names, manga titles, source/OCR text, translations, prompts, API keys, URLs, glossary contents, or raw exception messages.

Memory remains bounded:

- one fixed `EnumMap<TranslationTraceStage, Long>` per active page run;
- one constant-size online schedule accumulator;
- no retained event list and no per-block timing list;
- Auto keeps traces only in already-bounded admitted/prepared work;
- batch attaches trace state to its already-bounded current chunk and releases it at terminal render/failure.

### 4.2 Schema

Every line starts with `schema=translation_trace_v1`. Use fixed key order and `key=value` tokens so `adb logcat`, simple PowerShell filters, and later parsers can consume it.

Required identity fields on every event:

```text
schema=translation_trace_v1 event=<event> sid=<schedule-id> rid=<run-id|none>
mode=<manual|auto|batch> origin=<manual|auto|batch>
chapter=<hash> page=<hash|none> pageIndex=<zero-based|none>
```

Event families:

1. `schedule_start` / `schedule_end`
   - `pages`, `wallMs`, `nativeBusyMs`, `providerBusyMs`, `renderBusyMs`, `overlapMs`, `maxQueueMs`, `slowestPage`, `bottleneck`, `outcome`.
2. `run_start` / `run_end`
   - `plan=<fresh|resume|render_only|skip>`, `queuedMs`, `totalMs`, `stageSumMs`, `bottleneck`, `bottleneckMs`, `retries`, `outcome`, `errorType`, `errorCode`.
3. `stage_start` / `stage_end`
   - `lane=<scheduler|native|provider|render|storage>`, `stage`, `queueMs`, `durationMs`, `totalMs`, `provider`, `model`, `items`, `outcome`, `lag`, `budgetMs`, `errorType`, `errorCode`.
4. `route_change`
   - `stage=segment`, `model=bubble_segmenter`, `from=qnn_htp`, `to=cpu`, `reason=runtime_failure`, `errorType=ort`, `errorCode=1100`, `retry=1`.
5. `schedule_state`
   - bounded scheduling decisions: `state=<queued|admitted|deferred|attached|evicted|cancelled>`, `reason`, `queueDepth`, `nativeActive`, `providerActive`.

Canonical stages:

```text
lease_wait, native_queue, engine_setup, source_decode,
detect, segment, ocr, inpaint, cleaned_persist,
prepared_queue, provider_governor_wait, translate,
render_join, layout, render, store_commit, store_flush
```

Provider values are execution facts, not device capability: `cpu`, `xnnpack`, `qnn_htp`, `qnn_gpu`, `nnapi`, `android_canvas`, `remote`, `local`, or `none`. `model` is a bounded stable identifier such as `bubble_segmenter`, `page_detector`, `manga_ocr`, `paddle_ocr`, `aot_gan`, or `none`.

### 4.3 Lag and bottleneck rules

Use monotonic `System.nanoTime()` for all durations and wall-clock time only for provider retry eligibility already required by behavior.

Initial conservative stage budgets (constants, unit-tested and easy to tune from device evidence):

| Stage | Lag budget |
|---|---:|
| native/lease/prepared queue wait | 1,000 ms |
| source decode | 750 ms |
| detect | 750 ms |
| segment | 750 ms |
| OCR | 2,000 ms |
| AOT-GAN on QNN HTP | 1,000 ms |
| inpaint on CPU | 6,000 ms |
| remote translate | 8,000 ms |
| local translate | 5,000 ms |
| layout/render | 1,000 ms |
| storage commit/flush | 1,000 ms |

`lag=true` means `durationMs > budgetMs` or `queueMs > queue budget`; it is diagnostic only and must not alter scheduling. `run_end.bottleneck` is the largest measured stage for that page. `schedule_end.bottleneck` is the lane with the greatest busy time; `slowestPage` is the opaque page hash with greatest end-to-end duration.

For overlap, use an online accumulator rather than retaining intervals. On every lane-active-count transition, settle elapsed time since the previous transition into native/provider/render busy totals and into `overlapMs` whenever at least two lanes were active. This measures cross-page overlap for Auto and batch with O(1) schedule state. Also emit `criticalPathMs=wallMs` and `workMs=nativeBusyMs+providerBusyMs+renderBusyMs`; `overlapMs` makes the saved concurrency visible.

### 4.4 Instrumentation points by mode

#### Manual

- `TranslationScheduler.translatePage()` creates `schedule` + `run` before `scope.launch`; measure request-to-coroutine-start as scheduler queue.
- `TranslationPipeline.runGrantedSinglePageBoundary()` measures lease/admission, native-lane wait, native phase, deferred publication, HTTP/render phase, terminal outcome, and total.
- `SinglePageOnnxPhase` measures source decode and cleaned persistence boundaries.
- `RoiPageRecognitionEngine` emits detect, segment, OCR, and inpaint component events using the current trace element and actual providers.
- `SinglePageHttpRenderPhase` measures provider/governor translation, retry rounds, layout/color estimation, render-state work, store commit, and flush.

#### Rolling Auto

- `RollingAutoCoordinator.updateWindow()` creates/replaces the schedule trace only when chapter/session identity or active generation changes; repeated viewport updates remain state events under the same schedule.
- `reconcilePass()` creates a page run on admission and records source/memory/lease deferrals.
- Add `preparedQueuedAtNanos` and `trace` to `PreparedWork`. `consumeTranslations()` reports `prepared_queue` wait before translate begins.
- Wrap each `prepareSinglePage()` and matching `translatePreparedPage()` call in that run's `TranslationTraceElement`, so deep synchronous model events retain the same `sid/rid/page` across lane and dispatcher changes.
- Schedule accumulator receives native/provider lane enter/exit transitions. This exposes whether page N+1 native preparation actually overlapped page N translation and how much.
- Auto cancellation/eviction emits a terminal run outcome (`cancelled`, `evicted`, `stale_retry`, `failed`, or `completed`) rather than leaving a start without an end.

#### Batch

- `BatchChapterTranslator.translateBatch()` creates the schedule trace before engine setup and closes it in the outer `finally` on every exit (empty, setup timeout, OOM, pause, failure, cancellation, success).
- `SequentialBatchCoordinator.runOcr()` creates/reuses the page run; stage timers replace the current isolated `BatchTranslationDiagnostics.timing()` calls.
- `ChunkPage` carries the page trace and timestamps for OCR-ready, translation/inpaint-ready, and render-join-ready.
- Measure source fingerprint preflight, engine setup, OCR, inpaint, translation envelope, per-page translation settlement, render join wait, render/layout, cleaned image publication, store commit, and flush.
- Existing envelope IDs remain, but gain `sid/rid` and provider attempt timing. Envelope lifecycle must not be counted as each page's provider duration multiple times: emit one envelope stage event and attribute page waiting separately.
- `BatchTranslationDiagnostics` keeps compatibility wrappers during migration, delegating to the unified schema. Remove duplicate legacy timing messages only after equivalent trace events exist.

## 5. Exact implementation map

### New files

- `app/src/main/java/eu/kanade/translation/diagnostics/TranslationPipelineDiagnostics.kt`
  - schema formatter, privacy sanitizer, log sink, stage budgets, start/end APIs.
- `app/src/main/java/eu/kanade/translation/diagnostics/TranslationTrace.kt`
  - schedule/run identities, immutable trace element, fixed-size timers, online overlap accumulator, injectable monotonic clock.
- `app/src/test/java/eu/kanade/translation/diagnostics/TranslationPipelineDiagnosticsTest.kt`
- `app/src/test/java/eu/kanade/translation/diagnostics/TranslationTraceTest.kt`
- `app/src/test/java/eu/kanade/translation/segmentation/BubbleSegmenterRecoveryPolicyTest.kt`

### Modified production files

- `app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt`
  - CPU-primary creation, model path ownership, one-shot accelerated runtime fallback, actual provider route event.
- `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt`
  - typed registration result and truthful provider sink; strict registration failure propagation.
- `app/src/main/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngine.kt`
  - inference-success semantics, code-1100 classification, coherent temporary retry gate.
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`
  - remove automatic `QnnDiagnostics.runOnce()` startup.
- `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt`
  - manual schedule/run lifecycle and legacy Auto-window decision correlation.
- `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt`
  - Auto schedule identity, per-attempt trace propagation, prepared queue and overlap measurements, terminal outcomes.
- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
  - native admission/lane, phase, outcome, and total boundary events.
- `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt`
  - decode/persist/resume-plan timing; replace ambiguous `[translation_page]` total.
- `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt`
  - correlated detect/segment/OCR/inpaint stages using actual provider labels; retire global route claim.
- `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt`
  - translation/retry, render/layout, commit/flush timings and typed outcomes.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt`
  - batch schedule lifecycle, setup/fingerprint/total timings, guaranteed terminal summary.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt`
  - page run lifecycle, queue/handoff/render-join timing, lane overlap transitions.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchTranslationDiagnostics.kt`
  - compatibility facade delegating to `translation_trace_v1`.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt`
  - envelope/provider and persistence substage events.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchRenderJoin.kt`
  - join wait, layout/render, commit outcomes.
- `app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt`
  - attach existing AOT route/inference messages to current trace identity; keep route/fallback detail.

### Modified tests

- `app/src/test/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngineTest.kt`
- `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnProviderOptionsTest.kt`
- `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchTranslationDiagnosticsTest.kt`
- `app/src/test/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinatorTest.kt`
- existing rolling Auto coordinator tests under `app/src/test/java/eu/kanade/translation/scheduling/`
- existing manual/Auto/batch coexistence tests under `app/src/test/java/eu/kanade/translation/coexistence/`

## 6. Test seams and required cases

### 6.1 Bubble and provider routing

Extract or inject a small session-creation/run seam so JVM tests do not require a physical ORT model:

1. Production initialization requests default CPU, never QNN/XNNPACK.
2. Actual provider label is `cpu` when XNNPACK registration fails.
3. Strict QNN registration failure creates a CPU session and reports `cpu`, never `qnn_htp`.
4. Successful accelerated inference records support only after the run succeeds.
5. Accelerated `OrtException` code 1100 records failure, closes the failed session, creates CPU, retries once, updates label, and returns the CPU result.
6. CPU-primary `OrtException` is propagated without recreation.
7. Accelerator failure followed by CPU failure performs exactly two total runs and propagates CPU failure with accelerator failure suppressed.
8. Decoder/output-shape errors do not trigger provider fallback.
9. `close()` remains idempotent after a swap/failure; tensors, results, pooled buffer, and temporary bitmap are each released exactly once.

### 6.2 Trace formatter and privacy

1. Fixed key order and schema version for every event family.
2. Malicious page/chapter/error strings never appear raw.
3. Same `sid/rid/page` persists across dispatcher hops via `TranslationTraceElement`.
4. Durations clamp negative fake-clock deltas to zero.
5. Stage budget produces deterministic `lag` and `budgetMs`.
6. Run summary selects the maximum stage as bottleneck.
7. Online lane accumulator computes union busy time and intersection overlap correctly for nested/overlapping transitions without retaining intervals.
8. Terminal end events occur on success, typed pause, failure, cancellation, timeout, stale retry, attach, skip, and persistence rejection.

### 6.3 Mode parity

1. Manual test: one schedule, one run, ordered native/translate/render stages, terminal page total.
2. Rolling Auto test: page A provider interval overlaps page B native interval; shared schedule, distinct run IDs; measured `prepared_queue`; cancellation closes admitted runs.
3. Batch test: shared schedule, distinct page runs, one envelope event per provider request, per-page wait attribution, render join duration, terminal schedule summary.
4. Manual-on-batch attach test: manual trace reports `attached` and zero native/provider work; batch retains ownership.
5. Manual preemption of Auto: old Auto run closes `evicted`; manual run has a distinct schedule/run and becomes the only writer.
6. Normal manga test: no change to `PageWorkPlanner`, reading order, stage reuse, rendered output, or page lifecycle; diagnostics disabled/enabled produces identical outputs and invocation counts.

## 7. Verification sequence

1. Run focused JVM tests for segmentation recovery, provider labels, trace math/schema/privacy, rolling Auto, sequential batch, and coexistence.
2. Run the full `:app:testStandardDebugUnitTest` suite.
3. Assemble the arm64 standard debug APK.
4. Verify a clean app start produces no `QnnDiagnostics` events unless explicitly triggered.
5. On the OnePlus Ace 5, translate uncached pages in three controlled runs: manual, rolling Auto with a fixed lookahead, and batch on the same chapter after clearing only generated translation artifacts between runs.
6. Capture only `TachiyomiAT.Translation`, ORT errors, and AOT route logs. Confirm:
   - bubble `stage=segment provider=cpu model=bubble_segmenter` on every mode;
   - AOT `stage=inpaint provider=qnn_htp model=aot_gan` when HTP succeeds;
   - no QNN error 1100 from bubble segmentation;
   - each `run_start` has exactly one terminal `run_end`;
   - each schedule has a terminal summary;
   - Auto/batch report nonzero overlap when remote translation and next-page native work overlap;
   - logged total approximately equals the monotonic wall interval and all component times are nonnegative;
   - page failures include typed stage/provider/error code without source text or raw page name.
7. Repeat a long batch while monitoring heap/native memory and thermal state. Trace state must remain constant-size per active work item; no chapter-length event retention is allowed.

## 8. Acceptance criteria

- Bubble segmentation uses default CPU for manual, Auto, and batch.
- A QNN/NNAPI bubble runtime failure, if an experimental accelerated session exists, retries exactly once on CPU and does not poison later pages.
- A CPU bubble failure is not hidden by a retry loop.
- AOT-GAN's proven QNN HTP route and CPU fallback remain unchanged functionally.
- Provider labels reflect actual registered/executing providers; no CPU inference is labelled QNN or XNNPACK.
- Normal debug startup does not run QNN diagnostics.
- One log filter (`TachiyomiAT.Translation`) reconstructs every schedule and page run across all three modes.
- Each page terminal line exposes end-to-end time, queue time, slowest stage, providers, retry count, and typed outcome/error.
- Auto and batch schedule summaries expose native/provider/render busy time and measured overlap.
- Trace payloads contain no raw content or sensitive identifiers.
- Diagnostics add no unbounded collections, do not hold bitmaps/models/sessions, do not change scheduling decisions, and do not regress existing lifecycle/coexistence tests.

## 9. Risks and sequencing

Implement in four reviewable slices:

1. **Stability:** CPU-primary bubble, one-shot recovery seam, routing/provider-label tests, remove startup diagnostics.
2. **Trace core:** schema, clock, privacy, coroutine propagation, fixed-memory overlap math.
3. **Manual + Auto wiring:** page totals, stage components, prepared queue, Auto overlap and terminality.
4. **Batch wiring:** adapt existing diagnostics, envelope attribution, render join, schedule summary, parity tests and device validation.

The main risk is accidentally changing behavior while adding instrumentation around cancellation and `NonCancellable` drain regions. Timers must use `try/finally`, emit only after preserving the existing typed outcome, and never add suspension points. The second risk is double-counting batch envelope time for every page; keep envelope work at schedule scope and record per-page waiting separately.

## 10. Reviewed implementation contract (required amendments)

The independent review in `review/translation-pipeline-fix-and-observability-plan-review.md` is accepted. The requirements below supersede any conflicting or less-specific wording above.

1. **Keep stability independent from telemetry and global routing work.** The first shippable slice is limited to CPU-primary bubble segmentation, removal of automatic startup diagnostics, focused tests, and manual/Auto/batch device verification. Changes to the already-dirty `OnnxRuntimeProvider.kt` and untracked `ModelRoutingEngine.kt` are a separate, independently reviewed slice and are not prerequisites for removing bubble QNN error 1100.
2. **Make trace termination idempotent and externally owned.** Every started run and schedule receives an idempotent terminal handle plus balanced lane tokens. Owners must close traces for cancel-before-dispatch, channel-send cancellation, stale consumer handoff, eviction, timeout, coordinator replacement, teardown exceptions, and repeated terminal attempts. Instrumentation must introduce no new suspension points.
3. **Do not use deterministic `ShortHash` as a privacy boundary.** Use a process-random keyed digest or a process-local opaque-ID map bounded to active schedules and cleared at schedule termination. Raw page/chapter/manga names, source text, translated text, prompts, URLs, API material, glossary content, and arbitrary exception messages are forbidden. Retain `pageIndex` only as an explicitly accepted non-content diagnostic field.
4. **Inventory and migrate legacy translation logs.** Replace duplicate/raw manual, Auto, and batch timing/status lines only after schema parity exists. Keep native ORT logs outside the privacy-safe tag. Error reporting under `TachiyomiAT.Translation` is limited to bounded `errorType` and recognized provider error codes.
5. **Gate detailed tracing and enforce overhead limits.** Detailed stage/state events are debug-only or explicitly user-enabled. Release/default telemetry retains terminal summaries plus lag/failure events at a deliberate level. Repeated identical schedule states are coalesced. Formatting/logging is non-suspending and fail-open. Acceptance requires diagnostics-on versus diagnostics-off overhead of no more than 3% page/schedule wall time, no material dropped-frame regression, bounded allocations, and a recorded log-lines-per-page count.
6. **Use correct overlap accounting.** Track lane busy totals, union-active time, overlap-union time, and `concurrencySavingsMs = sum(max(activeLaneCount - 1, 0) * delta)`. Repeated stage/retry intervals are summed rather than overwritten. Queue waits are reported separately and may become the page bottleneck when they exceed all execution stages.
7. **Cover every reachable Auto entry point.** Inventory reader call sites for both `RollingAutoCoordinator` and legacy `TranslationScheduler.requestAutoWindow()`/`translateSinglePageFromStream()`. Either prove the legacy path unreachable and deprecate it separately, or propagate `mode=auto origin=auto` through it and include it in parity/terminality tests.
8. **Separate provider provenance levels.** Record `requestedProvider`, `registeredProvider`, and `provenProvider` where applicable. Registration/session creation is not execution proof. An accelerator becomes proven only after representative inference plus output-contract validation; CPU fallback must clear accelerator claims.

### Final reviewed sequence

1. **Stability-only:** CPU bubble route, disable automatic QNN diagnostics, focused tests, three-mode physical-device validation, AOT HTP regression check.
2. **Trace foundation:** gated schema, keyed opaque identities, idempotent terminal/lane handles, repeated-stage aggregation, correct overlap math, privacy and overhead tests.
3. **Manual and all Auto paths:** schedule/run propagation, queue/lease/native/provider/render totals, cancellation and stale-handoff coverage.
4. **Batch:** page-run propagation, envelope attribution, render-join waits, schedule summaries, legacy-log migration.
5. **Provider/routing correction:** independently review truthful provider provenance and inference-qualified model routing across detector, OCR, AOT, and segmenter call sites.

No production source, build, install, or deployment work is authorized by this plan.
