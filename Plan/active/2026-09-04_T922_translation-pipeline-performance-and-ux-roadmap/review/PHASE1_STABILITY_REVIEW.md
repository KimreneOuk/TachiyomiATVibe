# Phase 1 (Stability Only) — Independent Review

**Role:** Reviewer / Failure-mode Auditor
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`)
**Reviewed:** current uncommitted diff for Phase 1 only, against:
- plan `engineering/translation-pipeline-fix-and-observability-plan.md` §3.1, §3.2, §3.5, §6.1, amendments §10 (esp. §10.1)
- baseline `repository/BASELINE_CAPTURE_2026-09-04.md` and `C:/Users/User/Documents/T922_baseline_backup_2026-09-04/baseline_tracked.patch`
- implementer report `engineering/PHASE1_STABILITY_IMPLEMENTATION.md`

## Verdict: APPROVED WITH NOTES

No required changes. All Phase 1 contract items verified against primary evidence. The notes below are LOW-severity or informational; none blocks Phase 1.

---

## 1. Scope integrity — VERIFIED

- `git status --short` shows exactly 16 modified tracked files = the 14 baseline-dirty files + `App.kt` + `OnnxBubbleSegmenter.kt`. Nothing else.
- Per-file section extraction from `baseline_tracked.patch` vs fresh `git diff -- <file>` for each of the 14 Director-owned files: **14/14 SHA-256 identical, 0 differing** (independently re-run by reviewer, not taken from the implementer's report). This includes `OnnxRuntimeProvider.kt`, `QnnDiagnostics.kt`, `AOTInpainting.kt`, `HardwareDiscoveryEngine.kt`, `PaddleOcrV6DetEngine.kt`, `CleanedPublication.kt`, `ReaderTranslationFeedback.kt`, `gradle/libs.versions.toml`, and the baseline test files.
- Five protected untracked files re-hashed in the worktree: all five match `BASELINE_CAPTURE` SHA-256 values exactly, including `ModelRoutingEngine.kt` (`430102d9…`) and `QnnContextCacheManager.kt` (`f66aaabe…`).
- Untracked source inventory: only the 4 protected untracked files + the one new test file `app/src/test/java/eu/kanade/translation/segmentation/BubbleSegmenterRecoveryPolicyTest.kt`. No unexpected source files, no scope creep.

## 2. CPU-primary routing (§3.1) — VERIFIED

- `OnnxBubbleSegmenter.kt:139-144` (`initialize`) and `:283-288` (CPU rebuild) both pass `useAccelerator = false, useXnnpack = false`. No QNN/XNNPACK request anywhere in production init.
- Label honesty traced through the (unchanged, Director-owned) `OnnxRuntimeProvider.createSessionWithFallback`: for `!canUseAccelerator && !useXnnpack` the sink receives `"cpu"` unconditionally (`OnnxRuntimeProvider.kt:103-111`), and even the creation-failure CPU fallback labels `"cpu"` (line ~140). The §2.2 mislabel defect only manifests for `useXnnpack=true` requests, which Phase 1 never makes. Production label is an honest `cpu`, so the §3.2 recovery is dormant by construction and a CPU `OrtException` rethrows immediately (§3.2 step 3 satisfied in production, not just in tests).

## 3. One-shot recovery (§3.2) — VERIFIED

Code trace of `OnnxBubbleSegmenter.kt`:

- Only `OrtException` caught from the run (`:246`); decoder/contract errors (`error`/`require` → `IllegalStateException`/`IllegalArgumentException`), cancellation, and OOM propagate unchanged.
- Non-accelerated label rethrows immediately (`:247`, `ACCELERATED_PROVIDER_LABELS = {qnn_htp, qnn_gpu, nnapi}` `:337`).
- Session swap serialized under segmenter-local `sessionSwapLock` (`:273`); failed session closed only if still active; close errors suppressed onto the accelerator error (`:276-279`).
- CPU session contract-validated before installation (`validateContract`, `:289`); label set via the same `providerSink` (`:287`).
- Exactly one retry, never a third run: recovery path is at most `current.run` + `cpu.run`; every failure path propagates (retry failure `:254`, swap/creation failure `:296-301`). Confirmed by test 4 (`exactly two runs`).
- CPU-failure propagates with the accelerator failure suppressed on both the retry-failure path (`throw cpuError.apply { addSuppressed(error) }` `:254`) and the swap-failure path (`:299-300`).
- Ownership: per-attempt `result` closes in the inner `finally` and per-attempt `tensor` in the outer `finally` inside `OrtSessionHandle.run` (`:98-103`) — a failed partial result cannot survive into the retry; the retry's tensor is created only after the first attempt's tensor closed, so the "buffer outlives tensor" contract of the pooled direct buffer still holds. `segment()`'s `finally` (`:221-224`) remains the single cleanup for the pooled buffer + temporary bitmap; no double-release found.
- `close()` idempotent, serialized against the swap (`:311-320`); `session` nulled after close.
- Thread-safety: `segment()` is invoked only from `RoiPageRecognitionEngine.kt:293` inside `nativeGuard.withLock` (serialized); initialization happens once under the engine's `initMutex` before any segment call. The segmenter-local lock additionally covers close/recovery interleavings. Consistent with plan §3.2 step 5.
- `ModelRoutingEngine` usage is call-only: `recordFailure` keys are per-model-per-route (`"${id}_${route.name}"`, `ModelRoutingEngine.kt:58-61, 92-117`), so a bubble failure record cannot influence AOT-GAN or any other model. `resolveModelId` is idempotent under the double resolution performed. The resulting `TEMPORARY_FAILURE` state is inert for production because `canUseAccelerator = useAccelerator && …` short-circuits false (`OnnxRuntimeProvider.kt:67`) — the bubble route never consults routing state.

## 4. App.kt / startup diagnostics (§3.5) — VERIFIED

- The `BuildConfig.DEBUG` → `delay(1000)` → `QnnDiagnostics.runOnce()` startup launch is removed (App.kt diff hunk 1).
- The only remaining `runOnce()` call site in production code outside `QnnDiagnostics.kt` is inside the dynamically registered broadcast receiver (`App.kt:183`), registered only when `BuildConfig.DEBUG` is true, for action `tachi.action.DEBUG_RUN_QNN_DIAGNOSTICS`. It cannot fire on normal startup — only on an explicit `adb shell am broadcast`. All referenced types (`ContextCompat`, `BroadcastReceiver`, `IntentFilter`, `Intent`, `Dispatchers`) were already imported; compilation succeeds.
- `QnnDiagnostics.kt` untouched (covered by the 14/14 baseline identity check). No finalization mode 3 anywhere in the Phase 1 diff (grep of both file diffs: clean).
- All required imports present; `delay` was fully qualified, so no import residue.

## 5. Tests (§6.1 / contract E) — VERIFIED

`BubbleSegmenterRecoveryPolicyTest.kt` audited: six tests with real behavioral assertions, each driving the REAL recovery policy (`runInferenceWithRecovery` → `rebuildOnCpuAfterAcceleratedFailure`) with only session creation/run faked through the constructor `SessionFactory` seam:

1. CPU-primary init flags (`useAccelerator=false`, `useXnnpack=false`, label `cpu`).
2. Accelerated 1100 → close failed session (exactly once), CPU session with CPU-only flags, exactly one retry, label `cpu`, CPU result returned, routing state asserted (`TEMPORARY_FAILURE` pinned with explanatory comment).
3. CPU `OrtException` propagates same instance; 1 creation, 1 run, 0 closes.
4. Accelerator+CPU failure → exactly two total runs, CPU failure propagated, accelerator failure in `suppressed`.
5. `IllegalArgumentException` (shape/decoder) on accelerated session propagates; no recreation, no close, label unchanged.
6. Post-swap `close()` idempotence: swapped-out session closed exactly once, CPU session closed exactly once by first `close()`, second `close()` closes nothing; pooled-memory relief entries safe after teardown.

Seam does not weaken production: default parameter keeps the no-arg production constructor (`RoiPageRecognitionEngine.kt:173` unchanged); `ProductionSessionFactory` wraps the unchanged `createSessionWithFallback` with tensor/result handling moved verbatim from the old `segment()` body (compared against `git show HEAD:…OnnxBubbleSegmenter.kt`).

Reviewer re-ran the focused suite (`JAVA_HOME` = Android Studio JBR):

```
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.segmentation.*"
```

BUILD SUCCESSFUL. Result XMLs: `BubbleSegmenterRecoveryPolicyTest` 6/6, plus existing `BubbleSegmentationDecoderTest` 8, `MaskGeometryDeterministicAssignmentTest` 6, `MaskGeometryOrderedRleTest` 9, `MaskGeometryStressTest` 2, `MaskGeometryTest` 7 — **38/38, 0 failures/errors**.

## 6. Route coverage (contract F) — VERIFIED

Exactly one `OnnxBubbleSegmenter` construction (`RoiPageRecognitionEngine.kt:173`) and exactly one `segment()` call site (`RoiPageRecognitionEngine.kt:293`, inside `nativeGuard.withLock`); `WebtoonSlidingDetector.segmentSliding` (`app/src/main/java/eu/kanade/translation/webtoon/WebtoonSlidingDetector.kt:190-196`) invokes the segment lambda directly per window. Therefore:

- **Manual:** `SinglePageOnnxPhase.processSinglePage` → `recognitionEngine.analyze` (`SinglePageOnnxPhase.kt:1001`).
- **Rolling Auto:** `RollingAutoCoordinator.kt:671/686` → `TranslationPipeline.prepareSinglePage` (`TranslationPipeline.kt:828`) → same phase → `analyze` (`SinglePageOnnxPhase.kt:1001`).
- **Legacy Auto:** `TranslationManager.requestAutoWindow` (`TranslationManager.kt:1508/1524`) → `TranslationScheduler.requestAutoWindow` (`:282`) → `executor.translateSinglePageFromStream` (`:425`). Repo-wide grep finds **no caller** of these outside their definitions (only a comment in `TranslationExecutor.kt:36`) — defined but dormant, as the implementer claimed; if ever invoked it still converges on the same engine.
- **Batch:** `BatchChapterTranslator` forwards `analyzePageFn = analyzePage` (`:510-515`) → `TranslationPipeline.analyzePage` (`TranslationPipeline.kt:1309-1316`) → `singlePageOnnxPhase.analyzePage` → `recognitionEngine.analyze` (`SinglePageOnnxPhase.kt:819`), with the engine resolved from the shared `EngineLane` (`SinglePageOnnxPhase.kt:59,72`).

All four modes share the single CPU-initialized segmenter instance. Claim confirmed.

## 7. Failure-mode audit / notes

No CRITICAL or HIGH findings. All findings below are LOW or informational; none requires a change before Phase 1 sign-off.

- **N1 (LOW, dormant path):** In `rebuildOnCpuAfterAcceleratedFailure`, if `validateContract(cpu)` throws, the newly created CPU session is neither installed nor closed — a one-shot native session leak on an already-failing experimental path; `session` stays null so later pages fail fast with "not initialized". Unreachable in production (CPU-primary). Option for Phase 5: close `cpu` in the swap catch before rethrowing.
- **N2 (LOW, dormant):** If `session !== failed` at swap time (only possible via a concurrent close/re-initialize race, excluded by the serialized native guard in production), the current session object would be overwritten without being closed. Acceptable under the documented threading model.
- **N3 (expected behavior, unchanged):** `initialize()` still installs `session` before `validateContract`, so a contract failure leaves an invalid session installed — identical to pre-Phase-1 semantics; not a regression.
- **N4 (accepted, disclosed):** The debug-only receiver is `RECEIVER_EXPORTED`, so in debug builds any device app could broadcast the action and trigger QNN stress diagnostics. Release never registers it. Also, the receiver is never unregistered — Application-lifetime registration on the Application context is standard practice.
- **N5 (deferred by design, §10.1):** QNN code 1100 is recorded as `TEMPORARY_FAILURE` (OrtException message prefix `ORT_ENGINE_ERROR` trips the SSR heuristic). Honestly pinned in test 2 with a comment; inert on the production CPU route (see §3 above). When Phase 5 reclassifies code 1100, test 2's routing assertion must be updated.
- **N6 (informational):** Seam types `SegmenterSessionHandle`/`SessionFactory` are public because Kotlin forbids `internal` types in public constructor signatures; documented "not intended for external use". `runInferenceWithRecovery` is `internal`. Acceptable.
- **N7 (informational):** `executionProviderLabel` is a plain `var` — safe under the serialized production threading model; would need revisit only if the segmenter were ever called concurrently, which plan §3.2 step 5 already anticipates.
- **No behavior change for normal manga** beyond the intended CPU route: bitmap letterbox, buffer fill, decode, and upstream retry/failure semantics are unchanged (verbatim move verified). No new suspension points, no lifecycle regressions, no tracing/scope creep.

## 8. Outstanding (outside Phase 1 contract)

- Physical-device three-mode validation per plan §7.4-7.5 (`provider=cpu` on every mode, no QNN 1100 from bubble) remains to be performed by the Director/coordinator — the implementer correctly did not attempt device work.
- Phases 2-5 (trace core, manual/Auto wiring, batch wiring, provider/routing provenance) are untouched, as required by §10.1.
