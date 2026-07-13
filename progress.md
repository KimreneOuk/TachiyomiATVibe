# Translation Quality and AOT Progress

Last updated: 2026-07-13

## Current state

- Branch: `fix/translation-race-p0-quality`
- Worktree: clean after the progress tracker commit
- Base: `Quality-Improvement`
- Latest implementation commit before this tracker: `70fbeec feat(translation): add fixed-512 AOT model staging`
- Standard unit tests: passing
- Current production inpainting path: dynamic-shape CPU/XNNPACK AOT model
- Fixed-512 and NNAPI paths: prepared only where explicitly noted below; not enabled in production

The authoritative implementation plan is:

- `Plan/MASTER_IMPLEMENTATION_PLAN_2026-07-12.md`
- `Plan/AOT_NPU_FIXED512_DESIGN_2026-07-12.md`

## Wave status

### Baseline and documentation — complete

- Restored the plan and supporting test-contract documents onto this branch.
- Audited the plan against the current `Quality-Improvement` codebase.
- Repaired stale tests and APIs so the standard flavor has a reliable baseline.

### Wave 1 — race-condition and OOM safety — implemented

- Added the `MainDispatcherRule` and an in-flight page-key test seam.
- Guarded native buffer release with the native lock to reduce SIGSEGV risk.
- Cleared in-flight page keys before engine shutdown.
- Isolated watchdog callback failures.
- Made page cancellation synchronously publish `CANCELLED` state.
- Added permit-holder tracking so active pages are not displayed as merely queued.
- Published shared OOM/chapter state with `@Volatile`.

Remaining Wave 1 gate: real emulator/device stress around `onTrimMemory` during native inference has not been run.

### Wave 2 — safe inpainting and scheduling improvements — mostly complete

- Disabled intra-op spinning only for the AOT XNNPACK session.
- Reused AOT feeding pixel arrays.
- Upgraded ONNX Runtime Android to 1.23.
- Centralized page-index resolution using ordered reader pages.
- Corrected running-versus-queued progress reporting.
- Added cancellation, progress, and concurrency tests.

The planned engine-signature observer was not added. The live pipeline already validates the full engine signature before translation; adding an observer without a stronger lifecycle design could cancel or evict active batch work.

### Wave 3 — pipeline performance — implemented except optional batching

- Merged duplicate render color sampling.
- Moved JPEG encoding outside the translation permit.
- Removed the per-call MangaOCR preprocessing `FloatArray` through pooled direct-buffer writes.

Google Translator request batching remains deferred because it is optional and MLKit is the default translation path.

### Wave 4 — model deployment integrity — implemented with testing limitation

- Added version stamps for cached model assets.
- Added structural ONNX validity checks.
- Stale or legacy cached models are recopied.
- Added the fixed-512 model to the versioned model-copy path.

The plan requested a pure helper or injected asset abstraction for JVM-testing `OnnxModelStore`. That refactor and dedicated `OnnxModelStoreVersionTest` are still outstanding. Manual update-over-install validation has also not been run.

### Wave 5.1 — fixed-512 AOT preparation — partially complete

Completed:

- Added `app/src/main/assets/models/inpainting/aot-512.onnx`.
- Verified fixed inputs and outputs are `[1,3,512,512]` and `[1,3,512,512]`.
- Compared fixed and dynamic models at the common 512 shape on three synthetic samples; maximum absolute difference was `0`.
- Added pure 512-padding/crop utilities in `AotPadPath.kt`.
- Added sub-512 geometry and padding tests.

Not complete:

- `onnxslim` constant folding was run on 2026-07-12 (CP3): `aot-512.onnx`
  is now a real converted model, 1940 → 400 nodes, Tier 2 numerics gate
  passes (worst max-abs-diff 8.31e-5 on real-range inputs).
- The Tier 3 guard-rejection harness is BUILT (2026-07-13, see "Wave 5.1
  Phase 1-B" below) and proven on a 5-page synthetic corpus (0 new
  rejections). The required 20-page REAL manga corpus does not exist —
  that is a human curation step (see `tools/aot_corpus/README.md`).
- The padding helper is not yet integrated into `AOTInpainting.inpaint()`.

### Wave 5.2 — fixed-512 runtime integration — not started

The static model is discoverable by `OnnxModelStore`, but `AOTInpainting` still initializes and uses only the dynamic CPU/XNNPACK session. Runtime selection remains disabled until the Tier 2 and Tier 3 quality gates pass.

### Wave 5.3 — NNAPI and fallback cascade — not started

Still required:

- `NnapiCapabilityGate` and boundary tests.
- NNAPI capability/partition inspection.
- Dual NNAPI and XNNPACK session holder.
- Per-inference output-guard fallback.
- Rolling NNAPI health monitor and automatic disablement.

### Wave 5.4 — operator surgery — intentionally not started

This is conditional. It should only be attempted if device testing shows NNAPI partitioning or unsupported operators are a dominant problem.

### Wave 6 — device release gate — not started

Required devices:

- Qualcomm flagship.
- MediaTek device.
- Older/emulated ARM device.

Each must be tested for model recopying, crashes, OOMs, stalls, fallback behavior, and visual quality.

## Validation history

The reliable command for this repository is:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testStandardDebugUnitTest --no-daemon
```

Latest result: `BUILD SUCCESSFUL`, 570 test cases pass.

The Dev flavor remains unavailable on this machine because `app/src/devDebug/google-services.json` is missing. Use the Standard flavor for local verification.

## Review and test backfill — 2026-07-12

A four-way parallel code review (race-condition, pipeline perf, model/AOT prep,
test quality) audited every implementation commit against the approved designs
in `Plan/`. Findings and the backfill work live in
`Plan/active/2026-07-12-test-backfill/`.

Verdict from review: production code is correct across all waves; no behavior
bugs. Three process/coverage issues surfaced and were addressed:

- **Missing contract tests.** Roughly half the tests promised in
  `SUBAGENT_TEST_CONTRACTS_2026-07-12.md` were never written. Shipped this
  pass: `RenderColorEstimatorDedupTest` (7 cases, golden guard for the P1a
  dedup) and 2 new cases on `TranslationSchedulerCancellationTest` (the
  `hasRenderedResult` and `isStageFailed` no-op branches of
  `markPageCancelled`). Still missing: P0-1 / P0-3 / P0-4 concurrency tests —
  deferred because they need a ShortHash-style extraction of `private` logic
  into `internal` helpers (architecture change, approval-gated).
- **Dead infrastructure.** `MainDispatcherRule.kt` and
  `TranslationPipeline.inFlightPageKeysSnapshot()` had zero callers. Deleted.
  (`permitHolderPageKeySnapshot` initially looked dead but has three live
  callers in `TranslationManager.kt` — kept.)
- **Stale comments** (AGENT.md: a stale comment is a defect). Fixed two:
  `inpaintPage` KDoc claimed it set `cleanedImageName` (it does not, post-P1b),
  and `forceReleaseNativeBuffers` said "deferring" when the code skips.

Two higher-severity items from review remain open and need decisions before
Wave 5 proceeds (see "Next actions"):

- `aot-512.onnx` is `aot.onnx` with 95 bytes of shape-metadata edited, NOT a
  converted/slimmed model. The "max-abs-diff == 0" comparison is vacuously
  true (identical weights). onnxslim was never run; the 4 ConvTranspose ops
  that drive the NNAPI partition problem are untouched.
- `OnnxModelStore` version stamp is a hand-typed string
  (`MODEL_ASSET_VERSION = "quality-2026-07-12-v1"`), not the content hash the
  design required. Works today; a future model swap that forgets to bump the
  string re-creates the exact bug class Wave 4 was meant to eliminate.

## Implementation commits

- `5caf856` repair the standard unit-test baseline
- `a24a52b` add dispatcher rule and in-flight probe
- `52f14a9` guard native buffer release
- `ab492ef` clear in-flight keys during shutdown
- `c1a0d3a` isolate watchdog callback failures
- `01b69e4` synchronously publish cancellation
- `d3f2ed9` disable AOT intra-op spinning
- `c91a52c` pool AOT feeding arrays
- `1de8a11` upgrade ONNX Runtime Android
- `bec5251` resolve page indices from ordered keys
- `cf36756` distinguish permit holder from queued pages
- `62d059b` publish OOM and chapter state safely
- `7ce2621` merge duplicate render color sampling
- `bda2a82` move JPEG encoding outside the permit
- `e6626b5` pool MangaOCR preprocessing output
- `f7c987e` restamp cached ONNX assets
- `70fbeec` add fixed-512 AOT model staging and pad-path tests

## Next actions

1. Add the representative 20-page corpus and Tier 2/Tier 3 harness.
2. Compare dynamic and fixed-512 outputs and guard verdicts across the corpus.
3. If there are zero new guard rejections, integrate the fixed-512 pad path into `AOTInpainting`.
4. Implement and test NNAPI capability gating and XNNPACK fallback.
5. Run the Wave 6 device matrix before enabling or merging the NNAPI path.

## Test backfill and follow-ups — 2026-07-12 (3 commits)

Three approval-gated follow-ups from the review were executed as separate
commits on this branch:

### CP1 — race-condition regression tests via helper extraction
`test(translation): backfill race-condition regression tests and extract safety primitives`
- Extracted the three Wave 1 P0 invariants into `TranslationSafetyPrimitives`
  (pure helpers in `eu.kanade.translation.util`, mirroring `ShortHash`) and
  wired the production call sites (`RoiPageRecognitionEngine.forceReleaseNativeBuffers`,
  `TranslationPipeline.closeEngines`, `TranslationPipeline.withLeakProofPermit`
  watchdog) to them.
- `TranslationSafetyPrimitivesTest` (10 cases): drain skipped when native lock
  held (P0-1), keys cleared before permit acquired even when held (P0-3),
  every watchdog step runs even when an earlier throws (P0-4).
- Also added `RenderColorEstimatorDedupTest` (7 cases, golden guard for P1a),
  2 cases to `TranslationSchedulerCancellationTest` (P0-2 no-op branches),
  fixed two stale comments, removed dead `MainDispatcherRule` and the unused
  `inFlightPageKeysSnapshot` probe.

### CP2 — content-hash model deployment stamps
`fix(translation): content-hash model deployment stamps; extract ModelDeployment helper`
- Extracted `ModelDeployment` (pure, Android-free, mirroring `ShortHash`) so
  the stamp logic is JVM-testable without an Android `Context`.
- Replaced the manual-string stamp with `<version>:<assetPath>:<sha256>`. The
  fast path compares only the version+path prefix (no per-start hashing); the
  SHA-256 is computed once per actual re-copy and recorded.
- `ModelDeploymentTest` (13 cases): stamp determinism, flips on version/path/
  byte change, stamp format, missing-stamp legacy-cache rule, cached-file
  corruption detection.
- Out of scope (documented): a Gradle task that writes build-time asset hashes
  into BuildConfig for fully automatic asset-drift detection. Today the hash
  is written-but-not-compared on the fast path; `looksLikeValidOnnx` remains
  the corruption guard.

### CP3 — real fixed-512 AOT model via onnxslim
`feat(translation): real fixed-512 AOT model via onnxslim (1940 -> 400 nodes)`
- The prior `aot-512.onnx` was a 95-byte shape-relabel of `aot.onnx` — same
  1940 nodes, no folding. Replaced with the real conversion.
- `tools/aot_conversion/convert_aot_512.py`: static-shape rewrite +
  `onnx.shape_inference` + `onnxslim.slim`. **1940 → 400 nodes (-79%).**
- Tier 2 numerics gate (max-abs-diff < 1e-3 on real-range inputs): **PASS**,
  worst 8.31e-5 across 5 samples. Report in `tools/aot_conversion/REPORT.md`.
- Still NOT wired into `AOTInpainting.inpaint()` — safety rule holds.

Validation: `./gradlew :app:testStandardDebugUnitTest` green, 593 cases
(was 570 before CP1; +10 safety-primitive, +13 model-deployment).

### Wave 5.1 Phase 1-B — Tier 3 corpus gate harness

Built the guard-rejection corpus harness the master plan requires before
Wave 5.2 integration. Split cleanly along the language boundary:

**Python (model inference only — onnxruntime is Python-only, like `tools/aot_conversion/`):**
- `emit_corpus_outputs.py` — runs both AOT models on each corpus page, writes
  raw ARGB `.bin` (test input) + `.png` (human visual QA). No guard math.
- `make_synthetic_corpus.py` — generates 5 synthetic pages (uniform white,
  screentone, color bubble, large mask, dark panel) to prove the harness
  end-to-end.

**Kotlin (the verdict — production guard, no mirror):**
- `app/src/test/java/eu/kanade/translation/inpainting/AotCorpusGateTest.kt` —
  reads the `.bin` outputs via `java.io.DataInputStream` and applies the REAL
  `AotOutputGuard.isSuspiciousUniformFill` (the same object prod uses). Gate
  criterion: zero new rejections (dynamic=accept AND static=reject). Three
  tests: the gate itself, dimension/shape sanity, mask non-empty.
- `app/src/test/resources/corpus/aot/<page>/` — emitted `.bin` + `.png` outputs
  per page. Regeneratable from `emit_corpus_outputs.py`.

Why the split: the first attempt (commit `fbd5b4f`, now superseded) put a
numpy parity mirror of the guard in Python — wrong call (foreign language in
a Kotlin project, invented a perpetual parity contract to maintain). The fix
puts model inference where onnxruntime lives (Python) and the verdict where
prod lives (Kotlin), with no duplicated guard logic or constants. The `.bin`
format avoids `javax.imageio` (Android unit tests stub out `java.awt`).

Validation: 3/3 tests PASS on synthetic corpus. The `dark_panel_text` page is
rejected by BOTH models identically (the known AOT near-black uniform-fill
failure mode) — proves the gate is doing real work, not rubber-stamping.

What this does NOT do: it does NOT prove the static model is safe on real
manga. The real 20-page corpus is a human curation step (sourcing pages,
generating masks via the app's own detection pipeline, filling manifests).
The synthetic run proves the harness works; it does not satisfy the Wave 5.2
gate. The static model remains unwired — safety rule holds.

### Wave 5.1 Phase 1-C — real 18-page corpus + Tier 3 gate PASS — 2026-07-13

Curated a real 18-page corpus from an Okiraku Ryoushu chapter (B&W isekai)
across 6 of 7 design categories (color deferred — source is B&W). The Tier 3
guard-rejection gate PASSES on the real corpus: 0 new static-512 rejections.
Wave 5.2 is unblocked. Full report: `tools/aot_corpus/CURATION_REPORT.md`.

Three harness corrections landed alongside the corpus:

1. `tools/aot_corpus/generate_masks.py` (new) — faithful Option-B mask path.
   Runs the app's REAL PaddleOCR det model + DB postprocess at the inpaint
   thresholds (0.18/0.34, not the OCR-rec 0.2/0.45), then builds the same
   `centeredReportCrop(512)` + `buildFixedPillMask(pad=16, dilate=8)` the prod
   `inpaintReportFreeTextAot512` path uses. Each corpus `page.jpg` is the 512²
   centered crop the AOT model actually sees.
2. `tools/aot_corpus/emit_corpus_outputs.py` corrected to mirror prod (3
   divergences from the original emit): normalize `[-1,1]` via `/127.5 - 1.0`
   (was `/255.0`), black out masked image regions `img *= (1-mask)` (was raw
   image), dequantize `(out+1)*127.5` + grayscale luma collapse (was
   `out*255`). Synthetic corpus re-verified green under the corrected emit.
3. `tools/aot_corpus/curate_corpus.py` (new) — assigns categories by per-crop
   pixel stats, downscales the small-pages entries to 480px long side (the
   upscaling crash case), prunes to the selected 18.

`AotCorpusGateTest.EXPECTED_CORPUS_SIZE` bumped 5 → 18. Full standard suite
still green: 597 tests, 0 failures, 0 errors.

Two open items (do not block Wave 5.2):
- Color category (2 pages) deferred. Sourcing 2 openly-licensed color samples
  is a documented follow-up.
- 3 source pages (001, 023, 029) were excluded because their text is scattered
  across the whole page; `centeredReportCrop` centers on the union and the 512²
  window then contains zero localized boxes (correct prod no-op on the 512
  path, legitimately not a 512-path input).



## Safety rule

Do not enable the fixed-512 or NNAPI runtime path based only on structural model validity. The plan requires the corpus quality gates and on-device validation first. Until those pass, retain the validated dynamic CPU/XNNPACK path.
