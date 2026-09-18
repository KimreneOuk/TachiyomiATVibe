# Ticket 01 Review — Paddle OCR Android B1 benchmark foundation

- Reviewer: Reviewer agent (independent audit)
- Commit under review: `6b022c8` "Add Paddle OCR v6 Android B1 benchmark foundation"
- Worktree: `research/paddle-ocr-android-batching` (isolated, clean at review start)
- Verdict: **PASS** (with 3 non-blocking observations for later tickets)
- Method: handoff claims were NOT trusted; every criterion was re-verified from the
  commit diff, the code, build outputs, repo assets, and the connected reference
  device (PKG110 / SM8650, adb read-only).

## Acceptance criteria audit

### 1. Benchmark builds/runs without production behavior change — VERIFIED

- `git show 6b022c8 --name-only`: 16 files. Only non-benchmark file is
  `app/build.gradle.kts`, and its entire diff is one line:
  `applicationIdSuffix = ".benchmark"` → `".debug"` inside `create("benchmark")`
  (app/build.gradle.kts:95). Zero production source files touched.
- Build outputs (built 20:16, commit 20:30 — consistent):
  - `app/build/tmp/kotlin-classes/devDebug` and `devRelease`: zero files matching
    `*enchmark*`. Benchmark classes exist only under `devBenchmark`.
  - Merged packaged manifests (arm64-v8a):
    - devRelease → `package="app.kanade.tachiyomi.at"`, no `PaddleBenchmarkActivity`
    - devDebug → `package="app.kanade.tachiyomi.at.debug"`, no `PaddleBenchmarkActivity`
    - devBenchmark → `package="app.kanade.tachiyomi.at.debug"`, activity present
- The benchmark source set references only pre-existing production APIs
  (unchanged by this commit): `PaddleOcrV6SmallEngine.recognizeWithConf/alignWidth/
  RECOGNITION_HEIGHT/MAX_RECOGNITION_WIDTH`, `OnnxModelStore.ensurePaddleOcrV6Small/
  ensurePaddleOcrV6Det`, `HardwareDiscoveryEngine.activeRoute`,
  `OnnxRuntimeProvider.environment`, `getChapterPages`, `BuildConfig.COMMIT_SHA`.

### 2. Cold vs two warm runs distinguishable — VERIFIED

- `PaddleBenchmarkRunner.kt:70-85`: per sample, three measured runs; the process's
  first-ever inference is stage `cold_inference` (`coldSession=true`), later
  samples' first runs are stage `first_inference` (`coldSession=false`), then
  `warm_inference_1` and `warm_inference_2`. `SampleTiming` stores all three plus
  the `coldSession` flag (PaddleBenchmarkResult.kt:48-61).
- Cold-session validity comes from process isolation: README headless protocol uses
  `am start -S` (force-stop) per run, and the activity is the only launcher of the
  runner, so each run gets a fresh ONNX session (engine created per `run()`,
  closed in `finally`, Runner:37,104-107).
- Committed CSV row 2: `cold_session=true` for the 640 fixture; row 3:
  `cold_session=false` for the 1600 fixture. Bucket table matches CSV exactly
  (640: 212.589 / 173.799 / 174.383; 1600: 423.136 / 361.787 / 369.769).

### 3. PSS sampling 50–100 ms — VERIFIED

- `PssSampler.kt:12` default `intervalMs = 75L`; `scheduleAtFixedRate(..., 0, 75ms)`
  (PssSampler.kt:27-32) on a dedicated daemon thread; final extra sample at `stop()`
  (PssSampler.kt:41).
- Committed baseline JSON: `intervalMs=75`, `sampleCount=46`, peak `214914` KiB.
  Elapsed timestamps 49→3509 ms; per-sample deltas nominal 75 ms with scheduler
  jitter under inference load (honest per-sample wall-clock, no interpolation).

### 4. Provider provenance + model hashes from runtime state — VERIFIED (honest)

- Provider block is assembled from runtime, not constants
  (PaddleBenchmarkRunner.kt:111-121): actual = `engine.executionProviderLabel`,
  requested = `HardwareDiscoveryEngine.activeRoute.name` (read after session
  creation), ORT version = `OnnxRuntimeProvider.environment.getVersion()`,
  available = `OrtEnvironment.getAvailableProviders()`, and SHA-256 digests of
  onnx/qnn/xnn/nnapi `.so` files enumerated from `nativeLibraryDir`
  (BenchmarkDeviceMetadata.kt:115-129).
- Model hashes computed at run time from the extracted model files
  (Runner:57-61, `BenchmarkFileHasher.sha256`). Cross-checked all three recorded
  hashes against repo assets — **exact matches**:
  - `paddle_v6_recognition_onnx` `5435fd74…a24634` = `app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx`
  - `paddle_v6_dictionary` `b5f2bfe2…401c5d` = `…/PP-OCRv6_small_rec.txt`
  - `paddle_v6_det_onnx` `d73e0058…c9410e` = `…/det/inference.onnx`
- Honesty spot-check: baseline records `actualRegisteredProvider=cpu` while
  `requestedRoute=QUALCOMM_QNN_HTP` — the harness reports the CPU fallback rather
  than laundering provenance through the engine name. `batched=false` /
  `measuredBatchSize=1` recorded from the B1 runner shape, not inferred.

### 5. Rerunnable after later tickets — VERIFIED

- README commands are coherent end-to-end: `assembleDevBenchmark`, apksigner
  cert compare, `install -r` with the `--force-non-staged` fallback for
  `Failure [-99]`, intent extras exactly match the activity's EXTRA names
  (outputDir/pageLimit/includeDownloadedCorpus/includeExternalCorpus),
  `complete.marker` poll + pull. Fixture-only mode is reachable with
  `--ez includeDownloadedCorpus false` (external defaults false, Activity:50-52).
- Device evidence (live, read-only): package `app.kanade.tachiyomi.at.debug`
  installed, `versionName=0.17.1-benchmark`, `dataDir=/data/user/0/…`,
  `firstInstallTime=2026-06-18` with `lastUpdateTime=2026-09-18 20:17:29` —
  install -r updated in place and preserved the June data directory (no wipe,
  no onboarding). Three run output dirs exist on device
  (`reference-sm8650-{fixtures,downloaded-limited,downloaded-fixed}`).

### 6. Separate focused files / no god class / minimal comments — VERIFIED

- 9 Kotlin files, largest 207 lines (serializer; verbose by nature of JSON
  mapping). Activity 67, timer 37, sampler 103, runners/loaders ≤ 204. Each has
  one responsibility. Only one doc comment in the whole set
  (BenchmarkDownloadedCorpusLoader.kt:10) plus zero inline commentary.
- Minor note: `BenchmarkDeviceMetadata.kt` bundles three small objects
  (metadata, file hasher, provider-library collector) — cohesive provenance
  utilities, acceptable.

### 7. No model/chapter binaries committed — VERIFIED

- All 16 committed files are text (`kts`, `xml`, `json`, `kt`, `md`, `csv`).
  Fixtures are two JSON specs rendered to bitmaps at run time
  (BenchmarkCorpusLoader.kt:164-175) — no images shipped. Downloaded corpus is
  streamed from the installed app's storage, never copied or packaged
  (BenchmarkDownloadedCorpusLoader.kt:11). Exploratory downloaded-corpus results
  were correctly left on device, not committed.

### 8. applicationId / versionName safety — VERIFIED

- The suffix change applies only inside `create("benchmark")`
  (app/build.gradle.kts:87-96). `debug` keeps its pre-existing `.debug`;
  `preview` copies debug's suffix (pre-existing behavior, lines 83-85);
  `release` has no suffix. Built devRelease manifest confirms production id
  `app.kanade.tachiyomi.at` unchanged.
- `versionNameSuffix = "-benchmark"` is benchmark-only (line 94); device shows
  `0.17.1-benchmark` on the benchmark install only.
- Signature safety: built benchmark APK cert SHA-256
  `d314b00ca115ff3b7415336426e274689c3a951429e4709273a4a3ba93ac4762` (verified
  locally with apksigner 37.0.0) matches the README claim and the installed
  debug app's signing config; today's in-place update proves it.

### Baseline internal consistency — VERIFIED

- JSON ↔ report ↔ CSV agree: 2 fixtures, buckets 640/1600 with 1 sample each,
  identical timing values, 46 PSS samples @ intervalMs=75 over a 3797 ms run,
  peak 214914 KiB, provider cpu / requested QUALCOMM_QNN_HTP / ORT 1.28.0,
  `batched=false`, `measuredBatchSize=1`, commit `b2d5c8e` (parent of this
  commit — correct: the APK was built from the pre-commit tree).
- Downloaded-corpus run (uncommitted, device) is genuine: `2/4734` pages,
  `chapters=77`, honest cpu provenance, 44 PSS samples.

## Non-blocking observations (for Ticket 02+ conventions, not gates)

1. **Cached-process freeze risk on long runs.** The uncommitted downloaded run
   shows a 145,525 ms outlier inside a *warm* run (bucket 640 range max,
   device report `reference-sm8650-downloaded-fixed`) on a 155 s run — consistent
   with the OS freezing the process after the headless activity `finish()`es
   (no foreground component remains). The committed fixtures baseline (3.8 s) is
   unaffected. Before any long-run measurement ticket: hold a wake lock / keep a
   foreground component alive during runs, and treat multi-minute wall-clock
   deltas with suspicion. Recommend recording this convention in the task README.
2. **Bucket cold p50 mixes populations.** `coldInferenceP50Ms` pools the true
   cold session run with later samples' first runs (`coldSession=false`). Raw
   rows distinguish them; analysis tickets should filter on `cold_session=true`
   for the cold figure.
3. **Dead field.** `BenchmarkDeviceMetadata.Snapshot.requestedRoute`
   (BenchmarkDeviceMetadata.kt:34) is collected but never serialized
   (`DeviceMetadata` has no such field; `finish()` drops it). Harmless; the
   authoritative requested-route value (read post-initialize) is the one in
   `ProviderMetadata`. May be removed or wired through in a later touch-up.

## Gate statements (Director-mandated, evidence-backed)

- Production isolation: PASS (diff, class outputs, manifests).
- Honest provenance: PASS (runtime-derived provider block; cpu fallback recorded
  against QUALCOMM_QNN_HTP request; hashes match repo assets).
- Rerunnability: PASS (README protocol verified live on device today).
- No binaries / no user data committed: PASS.
- ApplicationId safety: PASS (release id untouched; benchmark-only suffix).

Verdict: **PASS — no retry needed.**
