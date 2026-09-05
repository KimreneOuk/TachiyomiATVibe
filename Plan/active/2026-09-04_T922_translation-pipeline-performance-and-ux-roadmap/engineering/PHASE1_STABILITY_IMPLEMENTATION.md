# T922 Phase 1 (Stability Only) — Implementation Report

**Role:** Implementer
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`, HEAD `7a95f9c`)
**Governing plan:** `translation-pipeline-fix-and-observability-plan.md` §2.1, §2.4, §3.1, §3.2, §3.5, §6.1; mandatory amendments §10 (§10.1 independence from OnnxRuntimeProvider/ModelRoutingEngine refactor).
**Contract:** stability only — no telemetry, no tracing, no provider refactor, no commits (Director's rule: all work stays uncommitted).

## Status: COMPLETE

All Phase 1 contract items (A–F) implemented and verified. Focused tests green. All 14 Director-owned baseline diffs verified byte-identical to the captured baseline patch after implementation.

---

## 1. Exact files changed (hunk-level summary)

### 1.1 `app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt` (modified; was clean)

File was clean before editing (verified via `git status` immediately before the edit group, and again before the App.kt edit group).

| Hunk | Lines (new file) | Change |
|---|---|---|
| Imports | 3–14 | Added `ai.onnxruntime.OrtException`, `eu.kanade.translation.runtime.onnx.HardwareDiscoveryEngine`, `eu.kanade.translation.runtime.onnx.ModelRoutingEngine`. |
| Constructor seam (§6.1) | 18–23 | `class OnnxBubbleSegmenter(private val sessionFactory: SessionFactory = ProductionSessionFactory)`. Default parameter keeps the existing no-arg call site (`RoiPageRecognitionEngine.kt:173`) source-compatible; production behavior unchanged. |
| Session seam types (§6.1) | 28–73 | New `interface SegmenterSessionHandle` (input/output names + `run(FloatBuffer): Pair<FloatArray, FloatArray>` + `AutoCloseable`) and `fun interface SessionFactory`. Public only because they appear in the public constructor's seam type; documented as not for external use. |
| Production adapter | 76–112 | `private class OrtSessionHandle(OrtSession)` — moves the tensor creation, `session.run(mapOf("images" to tensor))`, output-contract shape checks, and float-buffer extraction verbatim from the old `segment()` body. The per-attempt `result` closes in an inner `finally`, the per-attempt tensor in an outer `finally` (each exactly once, before any value escapes or error propagates). |
| State fields (§3.1) | 114–124 | `private var modelPath: String?` (normalized path retained for CPU rebuild); `sessionSwapLock` guarding the session swap; `session` retyped to `SegmenterSessionHandle?`. |
| `initialize()` (§3.1) | 130–158 | Now requests `useAccelerator = false`, `useXnnpack = false` (explicit default CPU), stores `modelPath`, routes creation through the factory, validates the input/output contract via a shared `validateContract()` helper, and logs `provider=$executionProviderLabel` in addition to the original inputs/outputs line. |
| `segment()` | 194–237 | Unchanged bitmap letterbox, buffer fill, and finally cleanup for the pooled input buffer + temporary bitmap. The run block now delegates to `runInferenceWithRecovery(current, buffer)` and decodes the returned arrays via the unchanged `BubbleSegmentationDecoder.decodeRle(...)` call. |
| One-shot recovery (§3.2) | 239–302 | `internal fun runInferenceWithRecovery(current, input)`: catches **only** `OrtException` from the run; if `executionProviderLabel !in {"qnn_htp","qnn_gpu","nnapi"}` it rethrows immediately (CPU is terminal); otherwise calls `rebuildOnCpuAfterAcceleratedFailure(current, error)` and retries the same inference exactly once, propagating any CPU-retry failure with the accelerator failure suppressed. `private fun rebuildOnCpuAfterAcceleratedFailure(...)`: (a) records the failure read-only via `ModelRoutingEngine.recordFailure(resolveModelId(modelPath), route, error)` using a label→route mapping (`hardwareRouteForLabel`, lines 259–273, 304–308); (b) under `sessionSwapLock`, closes and discards the failed session only if it is still the active one (close errors suppressed onto the accelerator error), creates an explicit default-CPU session through the factory (`useAccelerator=false, useXnnpack=false`), contract-validates it, installs it, sets the label via the same `providerSink`, logs the swap; (c) any swap/creation failure propagates with the accelerator failure suppressed. Never a third run on any path. |
| `close()` (§6.1 case 6) | 310–318 | Now serialized against the swap under `sessionSwapLock`; idempotent (`session` nulled after close; second call closes nothing). `inputBufferPool.clear()` unchanged. |
| Companion | 333–338 | Added `ACCELERATED_PROVIDER_LABELS = setOf("qnn_htp", "qnn_gpu", "nnapi")`. |

Forbidden files untouched: `OnnxRuntimeProvider.kt`, `ModelRoutingEngine.kt` (only public APIs *called*), `AOTInpainting.kt`, `HardwareDiscoveryEngine.kt` (only enum constants *read*), `QnnDiagnostics.kt`, `PaddleOcrV6DetEngine.kt`, `CleanedPublication.kt`, `ReaderTranslationFeedback.kt`, `gradle/libs.versions.toml`. No QNN finalization mode restored. No tracing/telemetry.

### 1.2 `app/src/main/java/eu/kanade/tachiyomi/App.kt` (modified; was clean)

| Hunk | Lines (new file) | Change |
|---|---|---|
| Startup diagnostics removal (§3.5) | 170–191 | Removed the `BuildConfig.DEBUG` → `scope.launch(Dispatchers.IO) { delay(1000); QnnDiagnostics.runOnce() }` startup launch. A normal debug startup now performs **zero** QnnDiagnostics workload. Replaced in place by the narrow developer-only explicit trigger: a dynamically registered broadcast receiver, gated on `BuildConfig.DEBUG`, action `tachi.action.DEBUG_RUN_QNN_DIAGNOSTICS`, registered with `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)` (exported so the adb shell uid can deliver; release builds never register it because of the DEBUG gate). It never runs during normal startup — only on an explicit broadcast: `adb shell am broadcast -a tachi.action.DEBUG_RUN_QNN_DIAGNOSTICS`. `QnnDiagnostics.kt` itself untouched (Director-owned). |
| Constant | 306 | Added `private const val ACTION_DEBUG_RUN_QNN_DIAGNOSTICS = "tachi.action.DEBUG_RUN_QNN_DIAGNOSTICS"` beside the existing `ACTION_DISABLE_INCOGNITO_MODE`. |

No imports added or removed (all needed types were already imported); the removed `kotlinx.coroutines.delay` call was fully qualified. `Dispatchers`/`launch` remain used (receiver + `newImageLoader`).

### 1.3 `app/src/test/java/eu/kanade/translation/segmentation/BubbleSegmenterRecoveryPolicyTest.kt` (new file)

Name verified free immediately before creation (`ls` + `git status --untracked-files=all` on the directory). JUnit 5 + kotest, matching the project's existing ONNX-test style (`ModelRoutingEngineTest`). Pure JVM: uses the constructor `SessionFactory` seam; no native ORT execution, no physical model, no Android graphics. `OrtException` is constructible on the JVM (`OrtException(OrtErrorCode.ORT_ENGINE_ERROR, ...)`).

Six test cases (plan §6.1, contract E):

1. `production initialize requests default cpu and never qnn or xnnpack` — factory records `useAccelerator=false`, `useXnnpack=false`, label `cpu`.
2. `accelerated ort exception code 1100 swaps to cpu retries once and returns cpu result` — failed session closed (exactly once), CPU session created with CPU-only flags, exactly one retry, label `cpu`, CPU result returned, `ModelRoutingEngine` records the failure (see §4 note on `TEMPORARY_FAILURE`).
3. `cpu primary ort exception propagates without session recreation` — same exception instance propagates; 1 creation, 1 run, 0 closes.
4. `accelerator failure followed by cpu failure performs exactly two runs and propagates cpu failure` — CPU failure propagated, accelerator failure present in `suppressed`, exactly two runs total.
5. `decoder output shape errors do not trigger provider fallback` — `IllegalArgumentException` on an accelerated session propagates; no recreation, no close, label unchanged.
6. `close is idempotent after a swap and releases each session exactly once` — swapped-out failed session closed exactly once (during swap), CPU session closed exactly once by first `close()`, second `close()` closes nothing; `reclaimPooledMemory()`/`forceReleaseNativeBuffers()` safe after teardown.

---

## 2. Test verification (full output summary)

Command (Git Bash, worktree root):

```
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.segmentation.*"
```

Result: **BUILD SUCCESSFUL**. Segmentation package suites (from `app/build/test-results/testStandardDebugUnitTest/`):

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| `BubbleSegmenterRecoveryPolicyTest` (new) | 6 | 0 | 0 |
| `BubbleSegmentationDecoderTest` (existing) | 8 | 0 | 0 |
| `MaskGeometryDeterministicAssignmentTest` (existing) | 6 | 0 | 0 |
| `MaskGeometryOrderedRleTest` (existing) | 9 | 0 | 0 |
| `MaskGeometryTest` (existing) | 7 | 0 | 0 |
| `MaskGeometryStressTest` (existing) | 2 | 0 | 0 |

Total 38/38 green; all pre-existing segmentation tests keep passing. During iteration, two failures were observed and fixed (see §4: retry-failure suppression; `OrtException` message prefix).

Focused suites covering App.kt changes: none exist (no `AppTest` / Application-class unit test in the repo — verified by filename search), so none were run, per contract. Full unit suite and APK assembly intentionally NOT run (contract forbids).

---

## 3. Route verification (contract F) — all modes reach the same `OnnxBubbleSegmenter` instance

**Single-instance chain (shared by every mode):**

- `TranslationManager.kt:100` — one `TranslationPipeline` per manager (`private val pipeline = TranslationPipeline(context, provider)`); `TranslationManager.kt:101` — `ChapterTranslator(context, provider, pipeline = pipeline)`.
- `TranslationPipeline.kt:237` — `internal val engines = EngineLane(...)` — one `EngineLane` per pipeline.
- `EngineLane.kt:170` — `internal var recognitionEngine` (cached; rebuilt only on config signature change) and `EngineLane.kt:287–296` — `createRecognitionEngine()` builds the single `RoiPageRecognitionEngine`.
- `RoiPageRecognitionEngine.kt:61` — `private var bubbleSegmenter: OnnxBubbleSegmenter?`; `RoiPageRecognitionEngine.kt:173` — `bubbleSegmenter = OnnxBubbleSegmenter().also { it.initialize(bubbleModelFile) }` (created once inside `initialize()` under `initMutex`).
- `RoiPageRecognitionEngine.kt:293` — `WebtoonSlidingDetector.segmentSliding(bitmap) { segmenter.segment(it) }` inside the serialized `nativeGuard` (284); `WebtoonSlidingDetector.kt:190–196` — `segmentSliding` invokes the segmenter lambda directly (per window for tall images). This is the only `segment()` call site.

**1) Manual (reader tap):** `TranslationPipeline.translateSinglePage(...)` (`TranslationPipeline.kt:388`) → `runSinglePageBoundary` (442) → `runGrantedSinglePageBoundary` → `translateSinglePageOnnx` delegate (`TranslationPipeline.kt:1230` → `singlePageOnnxPhase.translateSinglePageOnnx`) → `SinglePageOnnxPhase.processSinglePage` (`SinglePageOnnxPhase.kt:497` → 983) → `recognitionEngine.analyze(bitmap)` (`SinglePageOnnxPhase.kt:1001`).

**2) Rolling Auto:** `TranslationScheduler` constructed with `executor = pipeline` (`TranslationManager.kt:196`); it creates `RollingAutoCoordinator` (`TranslationScheduler.kt:206`); the coordinator's native lane calls `executor.prepareSinglePage(...)` (`RollingAutoCoordinator.kt:671` and 686) → `TranslationPipeline.prepareSinglePage` (`TranslationPipeline.kt:828`) → same `singlePageOnnxPhase` native phase → `processSinglePage` → `recognitionEngine.analyze` (`SinglePageOnnxPhase.kt:1001`).

**3) Legacy `TranslationScheduler.requestAutoWindow()` / `translateSinglePageFromStream()`:** `TranslationManager.requestAutoWindow` (`TranslationManager.kt:1508`) → `scheduler.requestAutoWindow` (`TranslationManager.kt:1524` → `TranslationScheduler.kt:282`) → `executor.translateSinglePageFromStream` (`TranslationScheduler.kt:425`) → `TranslationPipeline.translateSinglePageFromStream` (`TranslationPipeline.kt:427`) → `runSinglePageBoundary` → same onnx phase → `recognitionEngine.analyze` (`SinglePageOnnxPhase.kt:1001`). **Reachability:** a repo-wide sweep finds no caller of `TranslationManager.requestAutoWindow`/`TranslationScheduler.requestAutoWindow` outside these definitions (`TranslationExecutor.kt:36` only mentions it in a comment) — the path is defined but dormant (superseded by `RollingAutoCoordinator`). If ever invoked, it still reaches the same CPU-routed segmenter; formal deprecation is out of Phase 1 scope (plan amendment §10.7 keeps that decision with a later slice).

**4) Batch:** `TranslationPipeline.kt:1133/1158` — `BatchChapterTranslator(... analyzePage = this::analyzePage ...)`; `TranslationPipeline.analyzePage` (`TranslationPipeline.kt:1309–1316`) → `singlePageOnnxPhase.analyzePage` → `recognitionEngine.analyze(bitmap)` (`SinglePageOnnxPhase.kt:819`). `BatchLaneWorkers.kt:912` invokes `analyzePageFn` (constructor param at `BatchChapterTranslator.kt:83`, forwarded at `BatchChapterTranslator.kt:510`; delegate at `BatchLaneWorkers.kt:250–256`). `SinglePageOnnxPhase` resolves the engine through the same shared lane: `engines: EngineLane` (`SinglePageOnnxPhase.kt:59`) and `recognitionEngine get() = engines.recognitionEngine` (`SinglePageOnnxPhase.kt:72`).

**Conclusion:** manual, rolling Auto, the dormant legacy Auto path, and batch all converge on the one `RoiPageRecognitionEngine` → one `OnnxBubbleSegmenter` instance. `initialize()` now creates that single instance on default CPU, so the CPU route covers every mode, and the §3.2 recovery (normally dormant) protects any future/experimental accelerated session of that same instance.

---

## 4. Intentional deviations and deferred items

1. **Tensor/result cleanup scope moved inside the session handle (required by §3.2).** The old `segment()` finally closed the tensor and result; the plan requires "a failed partial result must be closed before retry", which forces per-attempt closure to live inside the run attempt. Each attempt's result closes in an inner `finally` and its tensor in an outer `finally` (exactly once each, before any retry). The `segment()` finally remains the single cleanup point for the pooled input buffer and temporary bitmap; no ownership transfers and nothing is double-closed (covered by test 6).
2. **ModelRoutingEngine integration — partial by design.** `ModelRoutingEngine.recordFailure(...)` is *called* (read-only use of existing public APIs; the untracked file is not modified), and `HardwareDiscoveryEngine.HardwareRoute` constants are only read. However, because `OrtException.getMessage()` is prefixed with `"ORT_ENGINE_ERROR : ..."`, the routing engine's existing SSR heuristic classifies the failure as `TEMPORARY_FAILURE` (first failure) rather than `UNSUPPORTED`. Reclassifying QNN execute code 1100 (plan §3.3) would require modifying `ModelRoutingEngine.kt` and is therefore **deferred to Phase 5** per amendment §10.1 and the phase contract ("if proper failure recording would require modifying it, skip that sub-step"). The test pins the current, honest behavior with an explanatory comment.
3. **QnnDiagnostics explicit trigger mechanism.** The chosen "narrowest honest mechanism" is a `BuildConfig.DEBUG`-gated, dynamically registered broadcast receiver (`adb shell am broadcast -a tachi.action.DEBUG_RUN_QNN_DIAGNOSTICS`). `RECEIVER_EXPORTED` is required so the adb shell uid can deliver the broadcast; the surface exists only in debug builds and only fires on explicit broadcast — a normal debug startup performs zero QnnDiagnostics work. A settings-gated UI entry would have touched additional presentation files and strings; the intent-handler entry matches the contract's example.
4. **Seam types are public, not internal.** Kotlin forbids a public constructor exposing an `internal` parameter type; the seam interfaces therefore sit as public nested types with "not intended for external use" documentation. The policy entry point `runInferenceWithRecovery` remains `internal`.
5. **`OrtSession.outputNames` is `Set<String>` in ORT 1.27.0** (verified in the resolved artifact), so the seam types use `Set<String>`; the contract check (`size == 2`) is unchanged from the original code.
6. **Not done in this phase (by contract):** telemetry/tracing (Phase 2–4), provider-label provenance and routing-state corrections in `OnnxRuntimeProvider.kt`/`ModelRoutingEngine.kt` (Phase 5), removal of `markSupported()`-at-creation semantics (Phase 5), legacy-Auto deprecation (later slice), physical-device validation (Director/coordinator task).

## 5. Baseline preservation verification

- `git status --short` re-run immediately before each edit group; neither target file was modified by anyone else at those moments.
- After implementation, `git diff` was split per file and every one of the **14 Director-owned baseline file diffs was verified byte-identical (SHA-256) to `C:/Users/User/Documents/T922_baseline_backup_2026-09-04/baseline_tracked.patch`** — 14/14 identical, 0 differing.
- Net new working-tree changes attributable to this phase: `App.kt` (2 hunks, +21/−4), `OnnxBubbleSegmenter.kt` (clean → modified, +219/−29 approximately), and the new untracked test file. Nothing committed, pushed, or branched (Director's rule).

## 6. Remaining risks

1. **Device behavior unverified (by design).** Phase 1 forbids device work; the three-mode on-device validation (`stage=segment provider=cpu`, no QNN 1100 from bubble) still must be performed per plan §7.4–7.5.
2. **`RECEIVER_EXPORTED` debug surface.** In debug builds, any app on the device could broadcast the diagnostics action and trigger QNN stress diagnostics. Accepted for a debug-only tool; the release build never registers the receiver.
3. **Recovery path is exercised only on JVM fakes.** The production `OrtSessionHandle` adapter compiles against the real ORT API and its shape/contract checks are moved verbatim, but no real-device accelerated→CPU swap has run (production init is CPU-primary, so the branch is dormant by construction).
4. **`TEMPORARY_FAILURE` classification (see §4.2).** Until Phase 5 reclassifies code 1100, `ModelRoutingEngine` treats the first bubble QNN-1100 as a temporary failure and would permit one accelerated recreation attempt at *session creation* time for an experimental accelerated route. This does not affect the CPU-primary production path.
5. **`SinglePageOnnxPhase` retry interplay unchanged.** Upstream recognition-failure retry logic (`SinglePageOnnxPhase`, `RoiPageRecognitionEngine`) was not modified; a CPU-terminal bubble failure still fails the page exactly as before Phase 1 (intended — no hidden retry loops).
