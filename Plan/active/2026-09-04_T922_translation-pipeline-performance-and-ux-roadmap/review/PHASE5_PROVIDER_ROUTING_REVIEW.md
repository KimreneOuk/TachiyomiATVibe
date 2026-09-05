# Phase 5 (Provider/routing correctness) — Independent Review

**Role:** Reviewer / Failure-mode auditor (T922)
**Date:** 2026-09-04
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`, HEAD `7a95f9c9` — unchanged from baseline capture; nothing committed)
**Reviewed artifact:** the uncommitted Phase 5 diff over Director-owned files — `OnnxRuntimeProvider.kt` (tracked-dirty), `ModelRoutingEngine.kt` + `ModelRoutingEngineTest.kt` (untracked-protected), `OnnxBubbleSegmenter.kt` (Phase 1 file) — plus the Phase 4 carried-forward hardening in `SequentialBatchCoordinator.kt` / `BatchRenderJoin.kt`, and the new `OnnxRuntimeProviderProvenanceTest.kt`.
**Governing contract:** plan `engineering/translation-pipeline-fix-and-observability-plan.md` §3.3, §3.4, §6.1; mandatory amendments §10 (esp. 10.8); Phase 4 review carry-forward N1/N2/N3; plan-review §8 (provider provenance — the origin of this phase).
**Inputs:** role file, task README, plan + amendments, `PHASE5_PROVIDER_ROUTING_IMPLEMENTATION.md`, `PHASE4_BATCH_WIRING_REVIEW.md`, `translation-pipeline-fix-and-observability-plan-review.md`, `BASELINE_CAPTURE_2026-09-04.md` + `baseline_tracked.patch` + `preedit_phase5_*` captures, live source/tests.

## Verdict: APPROVED WITH NOTES

Every audited contract clause is verified against primary evidence, most of it independently re-derived by this reviewer (baseline reconstruction, preedit byte diffs, live-policy walk, call-site enumeration, independent test rerun). Director-owned state survived Phase 5 exactly within the authorized contract: every baseline hunk of `OnnxRuntimeProvider.kt` is present with the delta limited to the mandated items (markSupported removal, activeRoute-derived label deletion, coherent attempt gate, typed registration propagation); `ModelRoutingEngine.kt`/`ModelRoutingEngineTest.kt` are purely additive versus the preedit captures; all six protected files are byte-identical. The focused suite was re-run by this reviewer: **BUILD SUCCESSFUL — 196 suites, 1455 tests, 0 failures, 0 errors, 0 skipped** (Phase 4 baseline 195/1439; +1 suite, +16 tests, all accounted). Findings below are LOW/informational; none blocks Phase 5. The two standing carry-forwards (device validation of the native layer; §10.5 overhead acceptance) remain open and now also cover the strict-registration-failure path that is only JVM-tested at the pure-core level.

---

## 1. Preservation — VERIFIED, zero unauthorized deviation (independently re-derived)

Reviewer did not trust the implementer's diff claims; each was recomputed from the baseline artifacts.

| Check | Method | Result |
|---|---|---|
| `OnnxRuntimeProvider.kt` vs baseline | Reconstructed pre-Phase5 state = `git show HEAD:` + applied `OnnxRuntimeProvider` portion of `baseline_tracked.patch` (172-line hunk applied cleanly); diffed against working tree (`--strip-trailing-cr`) | Delta = 230 added / 57 removed lines, **every removed line accounted** (see §1.1); all other baseline content (option builders, AOT helpers, docs) preserved as context |
| `ModelRoutingEngine.kt` vs preedit | `diff` vs `preedit_phase5_ModelRoutingEngine.kt` | Purely additive: `isAcceleratorAttemptAllowed`, `recordSuccessfulInference`, 1100 pre-check in `recordFailure`, regex constant, KDoc. **No existing line modified or removed** |
| `ModelRoutingEngineTest.kt` vs preedit | `diff` vs `preedit_phase5_ModelRoutingEngineTest.kt` | Purely additive: 7 → 14 `@Test` (+7 new cases appended after the preedit cases; zero deletions/weakening) |
| `AOTInpainting.kt`, `PaddleOcrV6DetEngine.kt`, `HardwareDiscoveryEngine.kt`, `QnnDiagnostics.kt` | Current `git diff` per file vs the same file's portion of `baseline_tracked.patch` | All four **byte-identical diffs** — never touched in Phase 5 |
| `QnnContextCacheManager.kt`, `QnnContextCacheManagerTest.kt` | SHA-256 vs `BASELINE_CAPTURE` | `f66aaabe…` and `de74c62c…` — **match exactly** |
| Director-owned tracked test files | Current diffs vs baseline portions: `ReaderTranslationFeedbackTest`, `AotBoxGeometryTest`, `QnnProbeModelTest`, `QnnProviderOptionsTest` | All **byte-identical diffs** — additive-only constraint respected |
| `BatchTranslationDiagnosticsTest.kt` (Phase 4-rewritten) | Removed lines inspected | Deletions are exclusively the Phase 4 legacy-`*Message` test removals (verified against the Phase 4 review's rewrite description) — no Phase 5 edits |
| Bubble (`OnnxBubbleSegmenter.kt`) vs preedit | Current `git diff` vs `preedit_phase5_OnnxBubbleSegmenter.diff` | Delta = exactly the `.also { markAcceleratedRouteProven() }` + private helper + KDoc. Recovery/swap/close logic untouched |

### 1.1 OnnxRuntimeProvider removed-line accounting (all 57 lines)

Each removed line is a mandated contract item or a move into the new core/registration function:

1. `isSupported` → `isAcceleratorAttemptAllowed` gate (§3.3) — 1 line.
2. `markSupported(modelName, route)` deleted from post-`createSession` success path (§3.3) — verified absent from the whole file (grep).
3. The `route == QUALCOMM_QNN_HTP -> "qnn_htp" …` sink `when` block deleted (§3.4) — the exact §2.2 defect; replaced by `registered.wireLabel`.
4. One KDoc line (label list) updated to include `qnn_gpu`/`xnnpack`.
5. Remaining ~48 lines are the main-path `try/catch/finally` body, CPU retry, and `createSessionOptions` body **moved verbatim** into `openSessionWithHonestLabel` / `createSessionOptionsWithRegistration` (re-indentation only; the double-guarded close, `recordFailure`-on-accelerated-only, WARN text, and CPU-retry-even-for-CPU quirk all re-verified present in the core).

## 2. Label truthfulness (§3.4) — VERIFIED by live-code walk

- **Label source:** the only `sink(...)` calls in `OnnxRuntimeProvider` receive `registered.wireLabel` from the `ProviderOptionsBuild` actually opened. `registered` is computed in `createSessionOptionsWithRegistration` from the **builder-local** `route` (`useAccelerator → resolveRoute()`, `useXnnpack → CPU_XNNPACK`, `else → null`) — i.e., from which EP registration was attempted, never from `HardwareDiscoveryEngine.activeRoute` read for gating. A non-accelerated request resolves to `CPU`/`XNNPACK` only; `QNN_HTP/QNN_GPU/NNAPI` are unreachable for it. Pinned by `non accelerated request on htp capable device is labelled from registration never from active route` (old code sinks `qnn_htp` in exactly this scenario).
- **XNNPACK failure → CPU reaching the sink:** builder sets `xnnpackRegistrationFailed` → `registered = CPU` → label `cpu` sinks after open. Previously only logged. Pinned by test.
- **Strict accelerator failure → honest CPU retry:** QNN HTP/GPU/NNAPI `onFailure` now records `strictRegistrationFailure` (log text and `tripCircuitBreaker` reasons byte-preserved), closes options (suppression-safe), throws `AcceleratorRegistrationException`; the core catches it, builds CPU options, opens, sinks `cpu`. A default-CPU session can no longer masquerade as `qnn_htp/qnn_gpu/nnapi`. `recordFailure` is NOT called for registration failures (device-level), preserving old routing attribution. Pinned by `strict qnn registration failure rebuilds on cpu and labels cpu never qnn_htp` (asserts opened=cpu-options, sunk=[cpu], no model failure).
- **Sink only after createSession succeeds:** every sink call is inside `.also {}` on a successful `open`; the bypass branch keeps its original `.also { providerSink("cpu") }` after `createSession`. A failed open never sinks (pinned by two tests, including the CPU-failure propagation case with `sunkLabels` empty). Exactly-once sink on every success path walked (bypass / main / strict-failure retry / creation-failure retry).
- **All call sites enumerated and regression-walked:** `createSessionWithFallback`: `OnnxPageTextDetector:45`, `OnnxPanelDetector:62`, `PaddleOcrV6DetEngine:72`, `PaddleOcrV6SmallEngine:53`, `OnnxBubbleSegmenter:60` (all `useAccelerator=true` except bubble's CPU-primary `false/false`, all consume `providerSink`) — each compiles (suite built) and behaves per §3/§4 below; accelerator success labels are unchanged in value, failure labels improve from masquerade to `cpu`. `createSessionOptions`: `MangaOcrEngine:61/:72` only (CPU-only; delegate path `route=null`, no strict failure possible → behaviorally untouched). `contextCacheFile` is passed by **no** `createSessionWithFallback` caller (AOT uses its own strict builders), so the new throw path cannot affect context-cache flows.

## 3. Routing semantics (§3.3) — VERIFIED

- **`markSupported` removed from session creation** (§1.1 item 2). Session creation now proves `SESSION_CREATED` only.
- **`recordSuccessfulInference(model, route)`** added; mirrors `markSupported` (SUPPORTED + counter clear) with "(inference executed)" log. Wired on the bubble path: `runInferenceWithRecovery` success → `markAcceleratedRouteProven()` → label→route mapping (`qnn_htp/qnn_gpu/nnapi` only; `cpu`/`xnnpack` → null → no-op) → `recordSuccessfulInference(resolveModelId(path), route)`. The CPU retry run is deliberately not wrapped — no marking. Pinned by `successful accelerated run records execution proof exactly once` (exactly-1 verify + SUPPORTED state) and `cpu primary successful run never marks accelerator support` (exactly-0 verifies + UNKNOWN state).
- **TEMPORARY_FAILURE gate coherence:** walked the full state machine. `recordFailure` returns true (promises one recreation) **only** in the SSR branch that sets counter=1 then TEMPORARY_FAILURE (in that safe write order — counter first, so the gate can never observe TEMPORARY_FAILURE with counter 0). `isAcceleratorAttemptAllowed` on TEMPORARY_FAILURE = `counter <= 1`, which is exactly `true` in that state. Second consecutive SSR failure → counter not `< 1` → `markUnsupported` → gate false. There is **no state where the promise is forbidden**: gate=false ⟺ UNSUPPORTED (which also makes the bypass branch's "known UNSUPPORTED" log text remain accurate). Pinned by `temporary failure remains eligible for exactly one recreation attempt via the attempt gate`.
- **`isSupported` deliberately unchanged** (strict proven-or-unattempted) — required by `AOTInpainting.kt:132` whose file is byte-identical to baseline; the incoherence is resolved by moving the creation gate, not by widening `isSupported`. Pinned by `isSupported keeps strict proven-or-unattempted semantics excluding temporary failure`.
- **1100 classification:** `recordFailure` checks `OrtException && /(?i)error\s*code\s*[:=]?\s*1100\b/` **before** the SSR heuristic → `markUnsupported`, return false — no TEMPORARY_FAILURE retry even when `ORT_ENGINE_ERROR` leaks "ENGINE_ERROR" into the text. Pinned by three tests (real device message; ENGINE_ERROR leak; non-Ort negative control that still classifies a FastRPC OrtException as temporary).
- **Retry counters bounded:** `ssrRetryCount` values max 1 (increment guarded by `< 1`), entries removed on success/`markSupported`/`recordSuccessfulInference`, map bounded by model×route keys. Consequence of the new gate: accelerator-requesting models get **at most one extra creation attempt per SSR episode** (first SSR → TEMPORARY_FAILURE/counter=1 → next creation allowed → second failure → UNSUPPORTED). Bounded.

## 4. Provenance (§10.8) — VERIFIED at the mechanism level

- **requested** = the request flags + builder route (attempt descriptor retained verbatim in the WARN log; `buildRequested` inputs). **registered** = `RegisteredExecutionProvider` + the sunk `wireLabel`, emitted only after a successful open. **proven** = `ModelRoutingEngine.SUPPORTED` via `recordSuccessfulInference` after a real executed run (bubble wired; AOT retains its own baseline `markSupported`-after-proven-run telemetry at `AOTInpainting.kt:157/:193`).
- **CPU fallback clears accelerator claims:** on any CPU fallback the sink label is `cpu`; the accelerator routing state is only ever demoted (`recordFailure`) or never set (registration failure); the bubble swap overwrites `executionProviderLabel` to `cpu` before any further use.
- **Phase 3/4 trace fields fed from these values:** `RoiPageRecognitionEngine` (Phase 3 file, not edited) feeds `registeredProvider = providerFromLabel(<engine>.executionProviderLabel)` — that label is now the registration fact by construction, and post-success it is execution-backed for the segmenter. `provenProvider = providerFromLabel(inpainting?.lastAcceptedRoute)` (AOT's own proven telemetry, baseline behavior). No trace-schema change needed or made — consistent with the report.

## 5. Phase 4 carried-forward fixes — VERIFIED present, minimal, no double-settle

Recomputed delta of both files' git diffs vs the `preedit_phase5_*` captures:

- **N1 (`runOcr` failure paths):** all three catch blocks (`BatchPersistenceRejectedException` / `CancellationException` / generic) now `ocrSpan?.end(ocrOutcome, error)` **before** `runTrace.end(...)`; `finally` retains `ocrSpan?.end(ocrOutcome)` + unchanged lane-token close and `listener.ocrFinished`. `TranslationStageSpan.end` is CAS-guarded (`closed.compareAndSet(false, true) → return false`, TranslationTrace.kt:898) — the catch+finally double-end is a verified no-op, exactly one emission. `stage_end(ocr)` now survives failure paths.
- **N2 (cancel-path spans):** `joinSpan` settles in a `finally` around both gate awaits; `page.renderJoinReadyAtNanos` assignment stays inside the protected region; both translation branches (remote and inline) call `sweepOpenTranslateWaits(chunk)` in their existing `finally` before the lane-token close; the helper (`:862`) is a non-suspending, per-page `end(CANCELLED)` + null loop.
- **N3 (`BatchRenderJoin` commitSpan):** `store.mergeRender` wrapped in try/catch; accepted→SUCCESS / rejected→FAILURE mapping byte-equivalent to the original post-return mapping; throw → `CANCELLED` (CancellationException) or `FAILURE` + rethrow. Previously-dangling throw path now settles.
- Batch scheduling/chunk flow untouched (delta is span handling and comments only); `BatchPhase4TraceWiringTest` 4/4 and `SequentialBatchCoordinatorTest` 17/17 pass in the reviewer rerun.

## 6. Tests — VERIFIED real and mutation-sensitive; additive-only respected

Independent rerun (`--tests "eu.kanade.translation.*" --tests "…ReaderTranslationFeedbackTest"`): **196 suites / 1455 tests / 0 failures / 0 errors / 0 skipped.** Key suites: OnnxRuntimeProviderProvenanceTest 7/7 (new), ModelRoutingEngineTest 14/14 (7 new), BubbleSegmenterRecoveryPolicyTest 8/8 (2 new + 1 updated), QnnProviderOptionsTest 3/3, QnnContextCacheManagerTest 5/5, QnnProbeModelTest 3/3, HardwareDiscoveryEngineTest 9/9 (all unchanged, all passing), SequentialBatchCoordinatorTest 17/17, BatchPhase4TraceWiringTest 4/4, ReaderTranslationFeedbackTest 14/14.

- **Coverage mapped to the assignment's required assertions:** strict-failure→CPU label (case 1), XNNPACK-fail→`cpu` reaches sink (case 2), activeRoute never decides label (case 3), sink-after-create (cases 4/6), successful accelerator creation sinks once (case 5), CPU-retry quirk pinned (case 7); inference-qualified SUPPORTED, TEMPORARY_FAILURE coherence, gate matrix, 1100 classification ×3 (MRE tests); bubble records-once / CPU-never-marks / 1100 recovery demotion. Each asserts exact values (sunk-label lists, option open/close order, verify counts, routing states) — not smoke assertions. The close-order assertion `["htp-options","cpu-options","htp-options"]` even pins the preserved double-guarded close.
- **Additive-only:** `ModelRoutingEngineTest` +7 with zero deletions (diff-verified). The one updated expectation is in Phase 1's own `BubbleSegmenterRecoveryPolicyTest` (implementer-owned, not Director-owned): the 1100 case now asserts UNSUPPORTED demotion, with the change documented in an in-test comment and matching the plan §3.3 mandate the Phase 1 test explicitly deferred.
- **Known seam boundary (disclosed as D1):** the provenance tests drive `openSessionWithHonestLabel` with inert tokens because `ai.onnxruntime` class-initialization loads the native library off-device. The core carries the full label/sink/retry/registration policy and `createSessionWithFallback` delegates to it verbatim, but the thin native lambda layer (`environment.createSession`, real registration calls, exception typing across the ORT boundary) is read-verified and device-validation territory only.

## 7. Failure-mode audit

- **New deadlock/lock risk in provider creation: none.** `OnnxRuntimeProvider` and `ModelRoutingEngine` remain lock-free (lazy env + `ConcurrentHashMap`); `openSessionWithHonestLabel` introduces no lock; `AcceleratorRegistrationException` is thrown before any lock could be held. The only lock in the touched flow is the pre-existing Phase 1 bubble `sessionSwapLock` (held across native CPU-session creation during recovery — blocking, not deadlock; unchanged by Phase 5).
- **AOT verified `qnn_htp` path end-to-end: preserved.** AOT never calls `createSessionWithFallback`; it builds `createQnnHtpSessionOptions` (byte-preserved) and calls `environment.createSession` directly (`AOTInpainting.kt:150-155/:183-188/:253/:303`). Its `isSupported` gate (:132) keeps strict semantics; its `markSupported` proven-run telemetry (:157/:193) is baseline state in a byte-identical file. AOT still gets strict QNN HTP sessions when requested. One intended interaction (below).
- **Intended semantic change (AOT-adjacent, plan-mandated):** an AOT `OrtException` 1100 previously misclassified as SSR/TEMPORARY_FAILURE (retry-eligible) now demotes the model to UNSUPPORTED on HTP for the process. This is exactly plan §3.3's "1100 is an execution failure regardless of message text", is pinned by the MRE tests, and replaces a broken recovery (the old TEMPORARY_FAILURE path never actually permitted a creation retry). Not a regression.
- **Context-cache interplay: unchanged.** `QnnContextCacheManager` is byte-identical; no `createSessionWithFallback` caller passes `contextCacheFile`; on strict registration failure no session is ever created (so no partial context binary is produced), and the CPU retry build omits the cache entries exactly as the old code did.
- **Strict-failure CPU retry recursion: impossible.** `buildCpu` uses `useAccelerator=false/useXnnpack=false` → builder route `null` → the strict `when` branches are unreachable → `AcceleratorRegistrationException` cannot recur; the core's catch does not re-enter `buildRequested`; the CPU-retry open failure propagates (no second retry).
- **Thread-safety of typed registration results:** `SessionOptionsWithRegistration`/`ProviderOptionsBuild` are immutable holders; `strictRegistrationFailure`/`xnnpackRegistrationFailed` are builder-local (thread-confined); `RegisteredExecutionProvider` is a stateless enum. Safe.
- **Gate TOCTOU (informational):** `isAcceleratorAttemptAllowed` reads status then counter non-atomically; two concurrent creations could both take the single TEMPORARY_FAILURE recreation. Benign (result is one extra bounded creation attempt, and per-model session creation is serialized by the engine owners in practice); same non-atomicity shape as the pre-Phase-5 `isSupported` reads. No change requested.
- **Consumers of the new `xnnpack` sink label:** bubble `ACCELERATED_PROVIDER_LABELS` correctly excludes it (CPU-terminal recovery); trace `providerFromLabel("xnnpack")` exists; detector/OCR engines store the string for logging only. No consumer breaks.

## Findings (all non-blocking)

### N1 — INFO (report miscount)
`PHASE5_PROVIDER_ROUTING_IMPLEMENTATION.md` §0/§2.6 says "+6 additive ModelRoutingEngineTest cases"; the diff against `preedit_phase5_ModelRoutingEngineTest.kt` shows **+7** (7 → 14, matching the XML). Total new-test arithmetic remains consistent (7+7+2 = +16, matching 1439→1455). Documentation accuracy only.

### N2 — LOW (native delegation layer verified by reading, not by JVM tests)
The link between `createSessionWithFallback` and the pure core (lambda wiring, `sink = providerSink`, `recordModelFailure` capturing `modelName`/`route`, exception typing of real ORT registration failures into `AcceleratorRegistrationException`) is read-verified; JVM tests stop at the core boundary (D1, unavoidable off-device). Add to the existing Phase 4 review N7 / plan §7 device-validation checklist: force a strict registration failure on device and confirm the sink receives `cpu` and the circuit-breaker trip is observed.

### N3 — INFO (cosmetic, carried class)
D3 as disclosed: `joinSpan` settles SUCCESS when cancellation interrupts a gate await (terminality intact); same cosmetic class as Phase 4 N5. `recordSuccessfulInference` fires after *every* successful accelerated bubble run, not only the first — idempotent state transition, log-noise only, and unreachable in CPU-primary production.

### N4 — CARRY-FORWARD
§10.5 overhead acceptance and plan §7 physical-device validation remain the outstanding acceptance items (unchanged from Phase 4 review N7); Phase 5 adds the strict-registration-failure device scenario above. The implementer's disclosed process note (first test run failed 2 cases on the `<= 1` gate bound, fixed before this review, final rerun clean) matches the reviewed code and tests.

## Required changes

**None.** The verdict is APPROVED WITH NOTES: findings N1–N4 are informational/carry-forward; no code change is requested for Phase 5 scope.

## Evidence index

- Baseline reconstruction: `git show HEAD:` + `OnnxRuntimeProvider` portion of `baseline_tracked.patch` applied to a clean copy; full delta (392 lines) walked; 57 removed lines individually accounted (§1.1).
- Preedit diffs: `preedit_phase5_ModelRoutingEngine.kt` (+23/-0), `preedit_phase5_ModelRoutingEngineTest.kt` (+139/-0), `preedit_phase5_OnnxBubbleSegmenter.diff` vs current (delta = provenance hook only), `preedit_phase5_SequentialBatchCoordinator.diff` / `preedit_phase5_BatchRenderJoin.diff` vs current (delta = N1/N2/N3 only).
- Byte-identity: 4 protected tracked diffs ≡ baseline portions; `QnnContextCacheManager.kt` sha256 `f66aaabe…`, `QnnContextCacheManagerTest.kt` `de74c62c…` ≡ BASELINE_CAPTURE.
- Live reads: `OnnxRuntimeProvider.kt` (full), `ModelRoutingEngine.kt` (full), `OnnxBubbleSegmenter.kt` (full), `SequentialBatchCoordinator.kt` runOcr :87-160 + translation-branch finallys :440/:555 + helper :862, `BatchRenderJoin` commitSpan, `AOTInpainting.kt` :131-204/:150-303, `RoiPageRecognitionEngine.kt` :300-695, call sites `OnnxPageTextDetector:45`, `OnnxPanelDetector:62`, `PaddleOcrV6DetEngine:72`, `PaddleOcrV6SmallEngine:53`, `MangaOcrEngine:61/:72`.
- Tests: `app/build/test-results/testStandardDebugUnitTest/` (reviewer rerun 2026-09-04, 196/1455/0/0); `OnnxRuntimeProviderProvenanceTest.kt` (full read), `ModelRoutingEngineTest.kt` (delta read), `BubbleSegmenterRecoveryPolicyTest.kt` :102-224.
