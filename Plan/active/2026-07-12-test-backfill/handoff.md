# Handoff — test backfill + 3 follow-up checkpoints

**Status:** ALL DONE. 4 commits on `fix/translation-race-p0-quality`.
**Suite:** 593 tests green (`./gradlew :app:testStandardDebugUnitTest`).

## Commits (this session, in order)

1. `test(translation): backfill race-condition regression tests and extract safety primitives`
   - TranslationSafetyPrimitives (3 helpers, P0-1/P0-3/P0-4 invariants)
   - TranslationSafetyPrimitivesTest (10 cases)
   - RenderColorEstimatorDedupTest (7 cases, P1a golden)
   - TranslationSchedulerCancellationTest +2 cases (P0-2 no-op branches)
   - 2 stale comments fixed
   - dead MainDispatcherRule + inFlightPageKeysSnapshot removed
2. `fix(translation): content-hash model deployment stamps; extract ModelDeployment helper`
   - ModelDeployment pure helper (ex-copyIfNeeded stamp logic)
   - stamp = `<version>:<assetPath>:<sha256>`; fast path compares prefix only
   - ModelDeploymentTest (13 cases)
3. `feat(translation): real fixed-512 AOT model via onnxslim (1940 -> 400 nodes)`
   - tools/aot_conversion/convert_aot_512.py
   - tools/aot_conversion/REPORT.md
   - aot-512.onnx replaced (real conversion, not shape-relabel)
   - Tier 2 numerics PASS (worst 8.31e-5)
4. (pending commit) progress.md + this handoff update

## What's NOT done (unchanged from prior handoff, still gates Wave 5.2+)

- 20-page corpus + Tier 3 guard-rejection harness (Wave 5.1L/M)
- AotPadPath integration into AOTInpainting.inpaint() (Wave 5.2)
- NNAPI capability gate + dual session + fallback cascade (Wave 5.3)
- Wave 6 on-device matrix

## What's proven now that wasn't before

- The three Wave 1 P0 concurrency fixes are guarded by automated tests
  (regression would fail TranslationSafetyPrimitivesTest).
- The Wave 4 deployment stamp catches asset byte changes + cached-file
  corruption (regression would fail ModelDeploymentTest).
- The fixed-512 model is a real onnxslim conversion (1940 → 400 nodes), not a
  shape-relabel. The "max-abs-diff == 0" claim in the prior progress.md was
  vacuously true; the real comparison shows 8.31e-5 worst on real-range
  inputs, well under the 1e-3 Tier 2 gate.

## Next safe action

Build the 20-page corpus + Tier 3 harness (Wave 5.1L/M). That is the next
hard gate before any Wave 5.2 integration work. Everything else through Wave
4 is now both implemented and test-guarded.
