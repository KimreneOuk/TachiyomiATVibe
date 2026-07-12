# Translation Quality and AOT Progress

Last updated: 2026-07-12

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

- `onnxslim` constant folding was not run because it is unavailable in the local environment.
- The required 20-page AOT corpus and baseline report do not exist.
- The Tier 2 corpus numerics gate has not passed.
- The Tier 3 guard-rejection harness has not been built.
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

Latest result: `BUILD SUCCESSFUL`.

The Dev flavor remains unavailable on this machine because `app/src/devDebug/google-services.json` is missing. Use the Standard flavor for local verification.

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

1. Obtain or install `onnxslim`/onnxslim-equivalent and run the model conversion gate.
2. Add the representative 20-page corpus and Tier 2/Tier 3 harness.
3. Compare dynamic and fixed-512 outputs and guard verdicts.
4. If there are zero new guard rejections, integrate the fixed-512 pad path into `AOTInpainting`.
5. Implement and test NNAPI capability gating and XNNPACK fallback.
6. Run the Wave 6 device matrix before enabling or merging the NNAPI path.

## Safety rule

Do not enable the fixed-512 or NNAPI runtime path based only on structural model validity. The plan requires the corpus quality gates and on-device validation first. Until those pass, retain the validated dynamic CPU/XNNPACK path.
