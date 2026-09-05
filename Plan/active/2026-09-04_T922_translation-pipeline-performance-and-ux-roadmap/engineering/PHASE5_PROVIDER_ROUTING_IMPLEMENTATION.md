# Phase 5 (Provider/routing correctness) — Implementation Report

**Role:** Implementer (T922)
**Date:** 2026-09-04
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`; nothing committed — all work stays uncommitted per instruction)
**Governing contract:** plan `engineering/translation-pipeline-fix-and-observability-plan.md` §3.3, §3.4, §6.1; mandatory amendments §10 (esp. 10.8); Phase 4 review carried-forward hardening (N1/N2/N3).
**Inputs:** role file `docs/roles/implementer.md`, task README, plan, Phase 1–4 reports, `review/PHASE4_BATCH_WIRING_REVIEW.md`, `repository/BASELINE_CAPTURE_2026-09-04.md`, live source/tests.

## 0. Result summary

- Carried-forward Phase 4 review hardening (N1 runOcr failure-path stage drop, N2 cancel-path dangling spans, N3 commitSpan hardening) implemented in `SequentialBatchCoordinator.kt` and `BatchRenderJoin.kt` — diagnostics-only.
- `OnnxRuntimeProvider`: honest typed provider labels. `createSessionOptions()` now has a typed-result variant (`createSessionOptionsWithRegistration` → `RegisteredExecutionProvider`); the sink label derives ONLY from what actually registered, never from `HardwareDiscoveryEngine.activeRoute`; XNNPACK registration failure resolves to CPU and reaches `providerSink`; strict accelerator registration failures propagate to `createSessionWithFallback`, which owns the CPU retry; `providerSink` fires only after `createSession` succeeds; `markSupported` removed from session creation.
- `ModelRoutingEngine`: `recordSuccessfulInference(model, route)` added (SUPPORTED = created AND executed); coherent attempt gate `isAcceleratorAttemptAllowed` (UNKNOWN/SUPPORTED/TEMPORARY_FAILURE-with-retry-credit) replaces `isSupported` as the session-creation gate; QNN graph execute error 1100 classified as a hard execution failure even when the text contains "ENGINE_ERROR".
- `OnnxBubbleSegmenter`: successful accelerated run records the execution proof (`recordSuccessfulInference`) exactly once; CPU-primary runs never mark accelerator support.
- Tests: new `OnnxRuntimeProviderProvenanceTest` (7 cases), +6 additive `ModelRoutingEngineTest` cases, +2 additive bubble provenance cases; 1 intentional semantic update in `BubbleSegmenterRecoveryPolicyTest` (1100 classification — the test's own comment deferred this to Phase 5).
- Focused suite re-run: see §5.

## 1. Mandatory pre-edit captures (Director-owned files)

All in `C:/Users/User/Documents/T922_baseline_backup_2026-09-04/`:

| File | Capture |
|---|---|
| `OnnxRuntimeProvider.kt` (dirty tracked) | `preedit_phase5_OnnxRuntimeProvider.diff` (git diff, 9,028 bytes) |
| `SequentialBatchCoordinator.kt` (dirty tracked) | `preedit_phase5_SequentialBatchCoordinator.diff` (43,251 bytes) |
| `BatchRenderJoin.kt` (dirty tracked) | `preedit_phase5_BatchRenderJoin.diff` (4,005 bytes) |
| `OnnxBubbleSegmenter.kt` (dirty tracked) | `preedit_phase5_OnnxBubbleSegmenter.diff` (14,833 bytes) |
| `ModelRoutingEngine.kt` (untracked Director-owned) | `preedit_phase5_ModelRoutingEngine.kt` (file copy) |
| `ModelRoutingEngineTest.kt` (untracked Director-owned) | `preedit_phase5_ModelRoutingEngineTest.kt` (file copy) |

Post-edit integrity of the protected set:

- `QnnContextCacheManager.kt` re-hashed `f66aaabe07a374e333ecfdf465aee5a339135f6306171cb3b7686d4cbcb641cf` — **byte-identical to BASELINE_CAPTURE**.
- `AOTInpainting.kt`, `PaddleOcrV6DetEngine.kt`, `HardwareDiscoveryEngine.kt`, `QnnDiagnostics.kt` — never opened for edit; their working-tree diffs are the pre-existing baseline hunks only.

## 2. Files changed — hunk-by-hunk

### 2.1 `app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt` (Phase 4 file; hardening only)

1. **N1 — `runOcr` failure paths (~:112-133):** each of the three catch blocks (`BatchPersistenceRejectedException`, `CancellationException`, generic `Exception`) now settles `ocrSpan` (`end(outcome, error)`) BEFORE `runTrace?.end(...)` — `finishStage` drops post-terminal stage ends, so the old order (run terminal first, span in `finally`) dropped `stage_end(ocr)` on every failure path. The `finally` retains `ocrSpan?.end(ocrOutcome)` (CAS no-op after the catch settled it) plus the unchanged `nativeLaneToken?.close()` / `listener.ocrFinished(pageKey)`. Run terminals, outcomes, and rethrows unchanged.
2. **N2 — render-join span (~:186-199):** `joinSpan` now settles in a `finally` around the `nativeGate`/`translationGate` awaits; a cancellation while suspended on either gate can no longer leave `stage_start(render_join)` dangling. `page.renderJoinReadyAtNanos` assignment stays inside the protected region (same position on the normal path).
3. **N2 — translate WAIT spans:** both translation branches (remote `async` ~:419-427 and inline ~:536-541) get a `sweepOpenTranslateWaits(chunk)` call in their existing `finally` (before the lane-token close). On the normal path every WAIT span is already settled/nulled by `settleTranslateWaits`, so the sweep is a no-op; on cancellation mid-envelope it settles the still-open spans as `CANCELLED` instead of leaving them dangling.
4. **New private helper `sweepOpenTranslateWaits(chunk)` (~:858-871):** per page, ends `page.translateSpan` with `CANCELLED` and nulls it; never throws, never suspends.

### 2.2 `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchRenderJoin.kt` (Phase 4 file; hardening only)

- **N3 — commitSpan (~:247-271):** `store.mergeRender(patch)` now runs inside try/catch: accepted→`SUCCESS`, rejected→`FAILURE` (identical to the old post-return mapping); a throw ends the span `CANCELLED` (`CancellationException`) or `FAILURE` + rethrow. Outcome mapping for the accepted/rejected paths is byte-equivalent to the original; only the previously-dangling throw path gained a settle.

### 2.3 `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt` (Director-owned dirty; surgical, all pre-existing hunks preserved)

1. **New types (after `environment`):**
   - `enum class RegisteredExecutionProvider { QNN_HTP, QNN_GPU, NNAPI, XNNPACK, CPU }` (plan §3.4 naming).
   - member extension `RegisteredExecutionProvider.wireLabel` → `"qnn_htp"/"qnn_gpu"/"nnapi"/"xnnpack"/"cpu"`.
   - `class SessionOptionsWithRegistration(val options, val registered)` — options plus the typed registration fact.
   - `class AcceleratorRegistrationException(val route, cause)` — thrown by the builder on strict EP registration failure (options closed first).
   - `internal class ProviderOptionsBuild<O>(options, registered)` — ORT-free form of the build result for the generic core.
2. **New pure orchestration core `internal fun <O, S> openSessionWithHonestLabel(...)` + private `closeQuietly`:** the exact main-path policy of `createSessionWithFallback`, generic over option/session types (see §3, clause A, and Deviation D1 for why it is generic). Contract encoded: strict registration failure → CPU retry without touching model routing state; sink label = typed registration result of the options actually opened, exactly once, only after `open` returns; `open` failure → close, model failure recorded (accelerator attempts only), single CPU retry (even for CPU-primary requests — pre-existing behavior preserved); requested options always closed (catch + finally, both guarded — mirrors the original double-guarded close).
3. **`createSessionWithFallback` rewritten around the core, behavior-preserving except the contract items:**
   - attempt gate: `ModelRoutingEngine.isAcceleratorAttemptAllowed(modelName, route)` replaces `isSupported` (TEMPORARY_FAILURE coherence, plan §3.3);
   - the "known UNSUPPORTED" bypass branch is unchanged in shape (same log text, CPU options, `providerSink("cpu")` after `createSession`);
   - **`ModelRoutingEngine.markSupported(...)` removed** from the post-`createSession` success path (§3.3: creation proves SESSION_CREATED, not execution);
   - **the label `when { route == QUALCOMM_QNN_HTP -> "qnn_htp" ... }` block deleted** — the sink now receives `registered.wireLabel` from the typed build result (§3.4: never from `activeRoute`);
   - `routeName` attempt descriptor retained verbatim for the WARN log ("Session creation with $routeName failed …");
   - `recordFailure` on accelerated creation failure retained verbatim.
4. **`createSessionOptions` body refactored into `createSessionOptionsWithRegistration`** — option-building code byte-preserved (same logs incl. "Failed to add XNNPACK EP!", same `tripCircuitBreaker` calls, same QNN/NNAPI/XNNPACK/strict/cache option code, same `configure` position) except:
   - strict route `onFailure` additionally records `strictRegistrationFailure = route to e` after the existing log + circuit-breaker trip; after the build, that failure closes the options (suppression-safe) and throws `AcceleratorRegistrationException` (§3.4 propagation);
   - XNNPACK `onFailure` additionally sets `xnnpackRegistrationFailed = true`; the reported registration becomes `CPU` (§3.4: explicit CPU resolution);
   - typed result mapping `route → RegisteredExecutionProvider` (XNNPACK build → `XNNPACK` unless the registration failed → `CPU`; null route → `CPU`).
5. **`createSessionOptions` (public, used by `MangaOcrEngine` CPU-only) kept** with its exact original signature, now delegating to `createSessionOptionsWithRegistration(...).options`. Its CPU-only callers are behaviorally untouched.
6. Untouched: `buildGenericHtpOptions`, `buildQnnProviderOptions`, `createQnnHtpSessionOptions`, `createRequiredXnnpackSessionOptions`, `createStrictNnapiSessionOptions`, `configureOwnedAotOptions`, `createBaseAotOptions` (AOT + diagnostics consumers unchanged).

### 2.4 `app/src/main/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngine.kt` (untracked Director-owned; additive/minimal)

1. **`recordSuccessfulInference(modelName, route)`** — mirrors `markSupported` (sets SUPPORTED, clears the SSR retry counter, INFO log "(inference executed)"). SUPPORTED now means created AND executed (§3.3, §10.8).
2. **`isAcceleratorAttemptAllowed(modelName, route)`** — the review-endorsed coherent attempt gate: `UNSUPPORTED` → false; `TEMPORARY_FAILURE` → true while the SSR retry counter permits (counter ≤ 1 — `recordFailure` grants the documented recreation by incrementing to 1; a second consecutive failure demotes to UNSUPPORTED); `UNKNOWN`/`SUPPORTED` → true. `isSupported` itself is intentionally UNCHANGED (strict proven-or-unattempted) because `AOTInpainting.kt:132` depends on it and AOT behavior must stay provably unchanged — the incoherence is resolved by moving the session-creation gate, not by widening `isSupported`.
3. **1100 classification in `recordFailure`** — before the SSR heuristic: if `error is ai.onnxruntime.OrtException` and the message matches `(?i)error\s*code\s*[:=]?\s*1100\b`, the model is `markUnsupported`'d ("QNN graph execute error 1100") and `recordFailure` returns false — no SSR/TEMPORARY_FAILURE retry. Rationale: a real device 1100 message ("…QNN graph execute error. Error code: 1100", ORT_FAIL) has no ENGINE_ERROR token; conversely an OrtException built from `OrtErrorCode.ORT_ENGINE_ERROR` leaks "ENGINE_ERROR" into the text and used to misclassify hard 1100 execute failures as a recoverable SSR (exactly the Phase 1 test's TEMPORARY_FAILURE result).
4. KDoc updates only otherwise (class doc already claimed SUPPORTED = create+execute; now true).

### 2.5 `app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt` (Phase 1 file; one additive hook)

- `runInferenceWithRecovery`: success path gains `.also { markAcceleratedRouteProven() }` — new private helper maps `executionProviderLabel` → route via the existing `hardwareRouteForLabel` and calls `ModelRoutingEngine.recordSuccessfulInference(resolveModelId(modelPath), route)`. CPU/`xnnpack` labels map to no route → never marked. Recovery, close, pooling, and all existing behavior untouched.

### 2.6 Tests

- **NEW `app/src/test/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProviderProvenanceTest.kt`** — 7 cases driving `openSessionWithHonestLabel` with inert String/Int tokens (no ORT types instantiated — see Deviation D1).
- **`ModelRoutingEngineTest.kt`** (additive; no existing case touched): `recordSuccessfulInference` transitions + retry-counter clearing; TEMPORARY_FAILURE recreation coherence through the attempt gate (allowed after first SSR failure, rejected after demotion); gate matrix UNKNOWN/SUPPORTED/UNSUPPORTED; `isSupported` strictness preserved while the gate allows TEMPORARY_FAILURE, and execution proof flips it back; 1100 classified as execution failure WITHOUT "ENGINE_ERROR" text (real device message); 1100 classified even WITH "ENGINE_ERROR" text (ORT_ENGINE_ERROR leak); non-Ort throwable with "1100" in the message does not hijack classification while a FastRPC OrtException still classifies temporary.
- **`BubbleSegmenterRecoveryPolicyTest.kt`**: one intentional semantic update + two additive cases (see §3, clause B/C tests).
- **`QnnProviderOptionsTest.kt`: untouched** — the new provenance cases went to the NEW file (cleaner, per the option given in the assignment).

## 3. Contract clauses → satisfaction + proof

### A) Honest provider labels (`OnnxRuntimeProvider.kt`)

| Clause | How satisfied | Proof |
|---|---|---|
| Typed registration result from `createSessionOptions()` (or equivalent) | `createSessionOptionsWithRegistration` returns `SessionOptionsWithRegistration` carrying `RegisteredExecutionProvider`; `createSessionOptions` delegates and keeps the old public surface for its CPU-only callers | Compile; the typed result is exercised by every `OnnxRuntimeProviderProvenanceTest` case |
| Label never derived from `activeRoute` alone; fix `createSessionWithFallback` ≈:98-116 decision | The sink label is `registered.wireLabel` from the build actually opened; the old `route == QUALCOMM_QNN_HTP -> "qnn_htp"` mapping is deleted. `route` remains only for the attempt gate, the attempt descriptor in the WARN log, and `recordFailure` keying (all legitimate) | `non accelerated request on htp capable device is labelled from registration never from active route` (route=HTP + registered=XNNPACK → sunk `xnnpack`; old code sank `qnn_htp` here) |
| XNNPACK registration failure resolves to CPU and reaches `providerSink` | Builder sets `xnnpackRegistrationFailed`, reports `RegisteredExecutionProvider.CPU`; label flows to the sink | `xnnpack registration failure resolves to cpu and reaches the provider sink` |
| Strict accelerator registration failures propagate to `createSessionWithFallback`, which owns the CPU retry; default-CPU never labelled accelerator | Builder logs (text preserved), trips the circuit breaker (preserved), closes options, throws `AcceleratorRegistrationException`; the core catches it, performs the CPU retry, sinks `cpu`; no `recordFailure` for the model (device-level — matches the old behavior where registration failure never touched ModelRoutingEngine) | `strict qnn registration failure rebuilds on cpu and labels cpu never qnn_htp` (asserts opened=cpu-options, sunk=[cpu], no model failure) |
| `providerSink` receives the registered provider only after `createSession` succeeds | Every sink call is inside `.also {}` on the `open` result in the core; a failed open never sinks | `provider sink receives the accelerator label only after session creation succeeds` (accelerator open throws → sunk labels == [cpu] only) + `cpu attempt failure … propagates without sinking a label` |
| Preserve creation-time CPU fallback + all QNN option-building except the label change | The builder's option code, logs, strict mode, context-cache entries, and circuit-breaker calls are byte-preserved; CPU retry-on-creation-failure retained verbatim (including retrying a failed CPU attempt — pinned by `cpu retry after failed cpu attempt still labels cpu on success`) | diff review; existing option tests + QnnProbeModelTest unchanged and passing |

### B) Routing-state semantics (`ModelRoutingEngine.kt` + bubble wiring)

| Clause | How satisfied | Proof |
|---|---|---|
| Remove `markSupported()` from session creation | Deleted from `createSessionWithFallback`; AOT's own `markSupported` proven-route telemetry untouched (`AOTInpainting.kt` never edited) | `successful accelerator creation sinks the registered label exactly once` runs with the real ModelRoutingEngine contract in mind; direct state proof in `BubbleSegmenterRecoveryPolicyTest.cpu primary successful run never marks accelerator support` and ModelRoutingEngineTest `isSupported keeps strict…` (only `recordSuccessfulInference` turns state SUPPORTED) |
| `recordSuccessfulInference(model, route)`; SUPPORTED = created AND executed; wire at minimum in the bubble path | Added; wired in `OnnxBubbleSegmenter.runInferenceWithRecovery` after a successful accelerated run; `recordFailure` wiring from Phase 1 kept | `successful accelerated run records execution proof exactly once` (mockkObject spy: exactly 1 `recordSuccessfulInference(modelId, HTP)`, 0 `recordFailure`, state SUPPORTED); `cpu primary successful run never marks accelerator support` (0 engine calls, state UNKNOWN) |
| Temporary-retry coherence; prefer review-endorsed option | `isAcceleratorAttemptAllowed` (UNKNOWN/SUPPORTED/TEMPORARY_FAILURE-with-credit) is the new session-creation gate; `recordFailure`'s one-recreation promise is now actually reachable; `isSupported` semantics untouched for AOT | `temporary failure remains eligible for exactly one recreation attempt via the attempt gate`; `attempt gate allows unknown and supported and rejects unsupported`; `isSupported keeps strict proven-or-unattempted semantics excluding temporary failure` |
| QNN error 1100 classified as accelerated-route execution failure regardless of "ENGINE_ERROR" text | Checked BEFORE the SSR heuristic, scoped to `OrtException` + `error code[: ]1100` regex → `markUnsupported`, `return false` | `qnn graph execute 1100 classifies as execution failure even without engine error text` (real device message), `…even when text contains engine error` (ORT_ENGINE_ERROR leak), negative case `error code 1100 pattern on a non-ort exception does not hijack ssr classification` |
| AOT provenance untouched; `AOTInpainting.kt` not edited | File not opened; its `isSupported`/`markSupported`/`recordFailure` calls behave exactly as before (`isSupported` semantics unchanged was a deliberate design constraint) | Protected-file check (§1); AOT tests re-run in the suite |

### C) Provenance (§10.8)

- **requested**: the request flags/route the provider was asked for — represented by `buildRequested` inputs (`useAccelerator`/`useXnnpack`/route) and the retained attempt descriptor; obtainable at the provider layer.
- **registered**: `RegisteredExecutionProvider` + the sunk `wireLabel` — now provably the options' registration fact, sunk post-`createSession`.
- **proven**: `ModelRoutingEngine.SUPPORTED` via `recordSuccessfulInference` — set only after a real executed run (bubble wired; AOT keeps `lastAcceptedRoute`).
- **Trace fields fed from these values are updated by construction**: `RoiPageRecognitionEngine` feeds `registeredProvider = providerFromLabel(engine.executionProviderLabel)`; that label is now the registration fact (and post-success, execution-backed — the segment span's `end` fires after `segment()` completes, and on the recovery path the label is already `cpu` by then). No trace-schema change was needed or made; `providerFromLabel` already maps `"xnnpack"` → `XNNPACK`.

### Carried-forward Phase 4 hardening

- **N1** runOcr: stage span settled before run terminal on all three failure paths — `stage_end(ocr)` no longer dropped; run `stageSumMs`/`bottleneck` now include OCR on failures. Pinned structurally by the existing `BatchPhase4TraceWiringTest` suites (all passing; ordering backstop CAS prevents double emission).
- **N2** cancel-path: `joinSpan` finally-settled; translate WAIT spans swept in both translation-branch `finally`s.
- **N3** `commitSpan` try/catch settle with CANCELLED/FAILURE on throw, accepted/rejected mapping unchanged.

## 4. Behavior preserved (reviewer double-check list)

1. **All option-building code** in `createSessionOptionsWithRegistration` — logs ("ONNX session options using…", "Successfully added … EP (strict)", "Failed to add … falling back to CPU!", "Failed to add XNNPACK EP!"), strict config entries, context-cache entries, `tripCircuitBreaker` reasons, thread/arena/mem-pattern/spinning settings, `configure` position — byte-preserved from the pre-edit capture.
2. **CPU retry-on-creation-failure** semantics, including retrying an already-CPU attempt (pre-existing quirk, now pinned by a test rather than changed).
3. **`recordFailure` NOT called on registration failure** — preserved old routing-state attribution (device-level failures never marked the model).
4. **`isSupported` semantics unchanged** — deliberate; `AOTInpainting` and its tests are untouched. Reviewer should confirm this reads as intended: the coherence fix lives in the new gate.
5. **Bypass branch** ("known UNSUPPORTED… bypassing accelerator to CPU") — same log text and flow; only the sink is typed (`"cpu"` constant before, `CPU.wireLabel`/literal now — same value).
6. **Bubble recovery policy** — every pre-existing Phase 1 test case still passes with only the 1100-classification expectation updated (the case the test itself deferred to Phase 5).
7. **Batch scheduling** — N1/N2/N3 are diagnostics-only; no chunk/admission/branch flow changed (removed/added lines in the coordinator diff are span handling + comments only).
8. **`MangaOcrEngine`'s `createSessionOptions` CPU-only usage** — signature and behavior unchanged.

## 5. Verification

- `:app:compileStandardDebugKotlin` + `:app:compileStandardDebugUnitTestKotlin` — BUILD SUCCESSFUL.
- Focused suite (`:app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "eu.kanade.tachiyomi.ui.reader.viewer.ReaderTranslationFeedbackTest"`), final run with `--rerun-tasks` (no cached results): **BUILD SUCCESSFUL — 196 suites, 1455 tests, 0 failures, 0 errors, 0 skipped** (Phase 4 review baseline was 195/1439/0/0; +1 suite, +16 tests).
  - New/updated suites: `OnnxRuntimeProviderProvenanceTest` 7/7 (new), `ModelRoutingEngineTest` 14/14 (7 new), `BubbleSegmenterRecoveryPolicyTest` 8/8 (2 new + 1 updated expectation), `QnnProviderOptionsTest` 3/3 and `QnnContextCacheManagerTest` 5/5 and `QnnProbeModelTest` 3/3 and `HardwareDiscoveryEngineTest` 9/9 (all unchanged, all passing), `ReaderTranslationFeedbackTest` passing.
- Honest process note: the first test run failed on 2 of the new ModelRoutingEngineTest cases — the initial attempt-gate bound was `counter < 1`, but `recordFailure` increments the counter to 1 when it GRANTS the recreation, so the bound is `<= 1`. Fixed in `isAcceleratorAttemptAllowed` (KDoc documents the arithmetic); no production caller had shipped with the wrong bound and no other case was affected. The final numbers above are from the clean `--rerun-tasks` run after that fix.
- Whole-app suite, APK assembly, and device work were NOT run/authorized (per instruction).

## 6. Deviations

- **D1 (test seam design):** the provenance tests drive `openSessionWithHonestLabel` — the pure generic core that `createSessionWithFallback` executes verbatim — instead of calling `createSessionWithFallback` directly with fakes. Reason: `ai.onnxruntime.OrtSession`'s static initializer loads the native library (verified in the 1.28.0 jar bytecode) and throws off-device, so ANY runtime reference that class-initializes `OrtSession`/`SessionOptions` (construction, mockk instantiation, option `close()`) fails on the JVM. The core carries the full label/sink/retry/registration-failure policy; what is NOT JVM-tested is the thin native lambda layer (`environment.createSession`, `SessionOptions::close`, `createSessionOptionsWithRegistration`'s real registration calls — the latter is untestable even in principle without a device). Device validation (plan §7) covers that layer.
- **D2 (`SessionOptionsWithRegistration` + `createSessionOptionsWithRegistration` naming):** the plan said "createSessionOptions() (or equivalent) reports a typed result"; "or equivalent" taken — the original public `createSessionOptions` signature is preserved as a delegating wrapper so `MangaOcrEngine` and all existing callers/tests stay untouched.
- **D3 (joinSpan cancel outcome):** on cancellation while suspended on a gate, `joinSpan` settles with the default SUCCESS outcome rather than CANCELLED (settling-in-finally per the carry-forward; labelling the cancel would require an extra catch/rethrow around suspension). Same cosmetic class as Phase 4 review N5; terminality is unaffected.
- **D4 (QnnProviderOptionsTest untouched):** the assignment allowed additive cases there OR a new file; the new file was cleaner (per the assignment's own preference), so `QnnProviderOptionsTest.kt` remains byte-identical.

## 7. Risks

1. **Strict registration failure now throws where it used to be swallowed inside the builder.** Any caller of `createSessionOptionsWithRegistration` with a strict route must handle `AcceleratorRegistrationException`. Production callers: only `createSessionWithFallback` (owns the retry) and the delegating `createSessionOptions` (strict-route users would now see the throw — grep shows no other strict-route callers; `MangaOcrEngine` is CPU-only). AOT uses its own dedicated option builders (unchanged).
2. **`xnnpack` is now a possible sink label** (XNNPACK build on a non-HTP-latched device). Consumers were checked: bubble `ACCELERATED_PROVIDER_LABELS` correctly excludes it (CPU-terminal recovery), trace `providerFromLabel("xnnpack")` → `XNNPACK` token exists, AOT labels are independent. Residual risk is any future string-match on the old label set.
3. **TEMPORARY_FAILURE now retries accelerator creation** via the new gate (previously the promise was dead code). On devices with flapping SSR this means one extra accelerated session-creation attempt per model/route — bounded by the existing retry counter, and bubble is CPU-primary in production so the exposure is AOT/detector/OCR shapes only.
4. **1100 → UNSUPPORTED demotion is permanent per process** (no SSR-style retry). Per plan §3.3 this is the intended "bubble demoted for that route, page retries on CPU" semantics; if a future QNN build makes 1100 transient, the classification is a one-regex change.
5. **Device-level validation of the native layer** (strict registration failure on real hardware, honest labels under probe-gated routes, ≤3% overhead) remains open — carried in Phase 4 review N7 and plan §7.

## 8. Evidence index

- Pre-edit captures: `C:/Users/User/Documents/T922_baseline_backup_2026-09-04/preedit_phase5_*` (6 files, §1).
- Protected integrity: `QnnContextCacheManager.kt` sha256 `f66aaabe…` ≡ BASELINE_CAPTURE; `AOTInpainting.kt` / `PaddleOcrV6DetEngine.kt` / `HardwareDiscoveryEngine.kt` / `QnnDiagnostics.kt` not edited.
- Phase 4 review items: `review/PHASE4_BATCH_WIRING_REVIEW.md` N1 (:105-133 coordinator), N2 (coordinator translation branches + render job), N3 (`BatchRenderJoin.kt` ~:247).
- Native-init evidence for D1: `onnxruntime-android-qnn-1.28.0` classes.jar — `OrtSession.static {}` → `OnnxRuntime.init()` (loads native; static block rethrows on failure); `OrtSession.SessionOptions.<init>` invokes native `createOptions`; `close()` throws IllegalStateException on double-close (hence the guarded-close preservation).
- Test run log: full gradle output retained at `/tmp/phase5_test_run2.log` (final `--rerun-tasks` run); result XMLs under `app/build/test-results/testStandardDebugUnitTest/` (196/1455/0/0).
