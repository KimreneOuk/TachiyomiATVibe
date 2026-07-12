# AOT on NPU (NNAPI, Fixed-512) — Design & Implementation Route

**Date:** 2026-07-12
**Scope:** Design only. No code changes. Full route for moving AOT inpainting from XNNPACK (CPU) to NNAPI (NPU) via consistent 512×512 feeding, with a multi-layer fallback, automated verification, and a parallel-subagent execution route.
**Companion reports:** `INPAINTING_EFFICIENCY_INVESTIGATION_2026-07-12.md`, `PIPELINE_EFFICIENCY_INVESTIGATION_2026-07-12.md`, `TRANSLATION_RACE_CONDITION_INVESTIGATION_2026-07-11.md`
**Status:** Awaiting approval. Not initiated.

---

## Executive Summary

**Goal:** Move AOT inpainting onto NNAPI (broad device coverage, no quantization required).

**Mandate from the user:** If AOT goes to NPU, input must be **consistent 512×512**. No variable shapes.

**Two independent blockers must fall for NNAPI to win** (verified in the inpainting investigation):
1. **Dynamic input shapes** — NNAPI refuses them. Fixed-512 padding removes this blocker.
2. **Operator coverage** — model has 4 `ConvTranspose` ops NNAPI doesn't support + 26 `Shape` + 10 `ReduceProd` → 23 NPU↔CPU partitions → ORT's own checker reports "NNAPI worse than CPU." Fixed shapes alone do NOT remove this. Op surgery (Phase 4) may be required.

**Critical implementation fact that shapes the fallback:** `OnnxRuntimeProvider.kt:46-51` wraps `addNnapi()` in `runCatching`. **If NNAPI rejects the model, it silently falls back to CPU — no exception, no callback.** Session creation succeeds; the session just runs slower. The fallback design cannot rely on a creation-time error signal.

**Approach:** Consistent 512×512 feeding with **background-color padding** (not zero-padding), a **four-layer fallback cascade** holding dual NNAPI+XNNPACK sessions, **four-tier automated verification**, and a **six-phase implementation route** with explicit gates and parallel subagents.

**Honest scope flag:** 6 phases, ~4 subagents at peak parallelism, 3 hard quality gates. Realistically 2-3 weeks if gates pass cleanly, longer if op surgery (Phase 4) is needed. All speed gains at the NNAPI phase are *estimated* (no on-device benchmarking exists yet) — Phase 1B starts building the measurement infrastructure to make Phase 3 data-driven.

---

## 1. Constraint Decisions (Locked)

| Decision | Choice | Rationale |
|---|---|---|
| NPU target | **NNAPI (broad)** | Covers Qualcomm, MediaTek, Exynos, Mali. No quantization required (can run FP). User-chosen over QNN/Snapdragon-only. |
| Input shape | **Fixed 512×512** | User mandate. NNAPI requires fully-static dims. Removes blocker #1. |
| Pin resolution | **512 (lean)** | Matches existing `REPORT_AOT_CONTEXT=512`. Smallest compute (512²=262K px vs 768²=589K px → ~2.2× less work). Smallest buffers. |
| Padding method | **Background-color** | See §2. |
| Fallback model | **Preserve dynamic-768 XNNPACK** | The current path remains the last-neural-resort before heuristics. Not deleted. |
| Sub-project scope | **Full NPU project** | User-chosen over no-regret-first or defer. All 6 phases in scope. |

---

## 2. Edge-Case Questions — Resolved

### Q1: Crops larger than 512, or in the 512-768 range

**Locked by user mandate:** all crops scale to 512. Crops >512 downscale; crops <512 pad. The quality risk on mid-sized/dense crops is real and is exactly what the corpus + automated tests exist to catch.

**Escape valve if corpus shows regression:** runtime EP selection — 512 on NPU for the fast path, keep 768-dynamic on XNNPACK for quality-critical paths. This is a runtime decision, not a compile-time one. The design builds EP selection as a runtime cascade (§3) precisely so this escape valve exists without re-architecting.

### Q2: Pad-boundary artifacts

**Choice: background-color padding, not zero-padding.**

Reasoning:
- AOT-GAN's receptive field "sees" the pad region. Zero (black) is the worst choice — a hard luminance edge the model may inpaint against.
- The page's dominant background color is cheap to compute. `RenderColorEstimator.sampleBackgroundLuma` already samples it; `AotOutputGuard` thresholds imply the pipeline knows luma.
- Same pattern as existing engines: MangaOcr uses white center-pad at 224² (MangaOcrEngine.kt:356-359); Paddle det uses black top-left pad at 736² (PaddleOcrV6DetEngine.kt:219-224). Consistent with existing conventions.
- For manga (white-paper background), near-equivalent to white-padding.

**Caveat:** non-uniform backgrounds (screentone, color manga) make a single dominant color an approximation. The corpus MUST include screentone/color pages to validate.

**Escalation if corpus shows artifacts:** mirror-padding (reflect edge pixels) — more expensive but no invented color. Documented as the fallback if background-color padding fails the Tier 3 gate.

---

## 3. Fallback Design — Four-Layer Cascade

The fallback is a decision cascade, not a single switch. Each layer has a specific trigger and target. Designed around the verified fact that `addNnapi()` fails silently.

```
Layer 0: Device capability gate (at session creation, once)
  Trigger:  Device fails SoC / Android-version / NNAPI-feature check
  Fallback: XNNPACK session (current behavior; 512-static model works on XNNPACK too)
  Why:      Don't attempt NNAPI on devices known to misbehave

Layer 1: NNAPI capability probe (at session creation, once)
  Trigger:  ORT NNAPI GetCapability reports coverage < threshold
            OR partition count > threshold (the 23-partition problem)
  Fallback: XNNPACK session
  Why:      Prevents the silent "NNAPI worse than CPU" slowdown.
            Probe via throwaway session, inspect capability, decide BEFORE
            creating the real session.

Layer 2: Runtime guard (per-inference, cheap)
  Trigger:  AotOutputGuard rejects output (uniform fill — already exists at
            AOTInpainting.kt:663-698)
            OR new: per-page quality score below threshold (Q1)
  Fallback: Re-run on XNNPACK session (held in parallel) → if still bad,
            push-pull → Telea
  Why:      Catches "NNAPI produced garbage but didn't error"

Layer 3: Crash/exception guard (per-inference, existing)
  Trigger:  OrtException (shape mismatch on buggy driver, etc.)
            Caught at AOTInpainting.kt:243
  Fallback: FAST path (push-pull) — existing behavior
  Why:      Last-resort safety net, already in place

Layer 4: Aggregate health monitor (rolling window)
  Trigger:  Guard-rejection rate over last N pages exceeds threshold (e.g. >20%)
  Fallback: Disable NNAPI for session lifetime, switch to XNNPACK, log telemetry
  Why:      A bad driver doesn't crash once — it produces systematic garbage.
            One bad page is noise; 20% is a signal.
```

### Dual-session holder

Layer 2 requires holding BOTH an NNAPI session and an XNNPACK session simultaneously.

**Memory cost:** one extra set of model weights (~22MB float32). Acceptable on 6GB-min devices.

**Memory-pressure teardown:** NNAPI session is the first to tear down. Reuses the existing `onMemoryPressure` path (ChapterTranslator.kt:225-241) — but only AFTER the race-condition Bug 5 fix (P0-1 from the race-condition plan) lands, so `forceReleaseNativeBuffers` no longer SIGSEGVs.

### Fallback chain summary

```
NNAPI(512-static) → XNNPACK(512-static) → XNNPACK(dynamic-768, current) → push-pull → Telea
     ↑ Layer 0/1 gate        ↑ Layer 2/3 trigger         ↑ existing FAST fallback
```

**The current dynamic-768 XNNPACK path must be PRESERVED as a fallback layer, not deleted.** The 512-static model runs on both NNAPI (fast path) and XNNPACK (fallback); the dynamic model remains the last-neural-resort before heuristics.

---

## 4. Verification Test Design — Four Tiers

Four tiers from fastest to most thorough. Each is a gate — failure stops the route.

### Tier 1 — Pure unit tests (CI, no device, no model)

Extend the existing `app/src/test/java/eu/kanade/translation/inpainting/` pattern.

| Test file | What it asserts | New/extends |
|---|---|---|
| `AotBoxGeometryTest.kt` | `centeredReportCrop` on sub-512 pages returns in-bounds crop (no throw); 512×512 crop on 400×600 page valid | Extends — 3 cases |
| `AotOutputGuardTest.kt` | Guard verdicts stable when input has pad-region pixels (bg-color padded); boundary cases | Extends — pad cases |
| `AotPadPathTest.kt` (NEW) | Pure pixel math: 400×400 source + bg color → 512×512 with correct interior; crop-back recovers 400×400 exactly | New |
| `OnnxModelStoreVersionTest.kt` (NEW) | copyIfNeeded re-copies on version-stamp mismatch; keeps cache on match; handles missing stamp (legacy cache) | New |
| `NnapiCapabilityGateTest.kt` (NEW) | Given mock capability stats (coverage %, partition count), gate decides NNAPI vs XNNPACK correctly at boundaries | New |

**Gate criterion:** All pass. Run on every commit.

### Tier 2 — Model numerics test (offline Python, pre-merge of model asset)

Runs in the model-conversion script, not Android CI. Compares old dynamic model vs new static-512 model on a fixed input set.

```python
for sample in corpus:
    dyn_out = run_dynamic_model(sample)          # current aot.onnx, native size
    static_out = run_static_512_model(sample)    # new aot-512.onnx, padded
    diff = max_abs_diff(crop_back(dyn_out), crop_back(static_out))
    assert diff < 1e-3, f"Numerics diverged on {sample}: {diff}"
```

**Gate criterion:** max-abs-diff < 1e-3 across all corpus samples. (Verified 3.2e-5 for onnxslim alone; static-shape conversion adds reshape overhead, so using a looser but still-strict bound.)

### Tier 3 — Guard-rejection corpus harness (offline, JVM)

The core quality gate. Runs both models on the 20-page corpus, compares guard verdicts.

```
For each page in corpus:
    old_verdict, old_stats = run_dynamic + AotOutputGuard.inspect
    new_verdict, new_stats = run_static_512 + AotOutputGuard.inspect
    Record (page, old_verdict, new_verdict, old_stats, new_stats)

ASSERT: verdict count unchanged (no new rejections)
LOG:   any page where stats shifted (mean delta > 5, variance delta > 2)
SAVE:  output bitmaps for visual QA (old vs new side-by-side)
```

**Gate criterion:** Zero new rejections. Pages with shifted stats get visual-QA-flagged. This is the gate the audit said was missing — building it is a deliverable, not optional.

### Tier 4 — On-device smoke test (manual/instrumented, pre-release)

Not CI-automatable. Run on minimum: one Qualcomm flagship, one MediaTek, one old/emulated ARMv7.

```
1. Install build with new model
2. Verify copyIfNeeded re-copied (logcat: "Copying model from assets" on UPDATE,
   not just fresh install)
3. Translate 5 chapters including corpus pages
4. Check logcat: EP selected (NNAPI vs XNNPACK), guard rejections, fallback invocations
5. Confirm: no SIGSEGV, no OOM, no 90s stalls
6. Visual check: translations render correctly
```

**Gate criterion:** No crashes; EP selection shows NNAPI on capable devices, XNNPACK fallback on others; visual quality acceptable. Gate before release, not before merge.

### Test corpus composition (20 pages)

| Category | Count | Why |
|---|---|---|
| Dense text (shounen/seinen) | 4 | Stress text-removal |
| Screentone-heavy | 3 | Edge case: grayscale + boundary artifacts |
| Color manga | 2 | Non-uniform background padding stress |
| Large bubbles | 3 | Big inpaint regions |
| Small pages (<512 either axis) | 3 | The crash case being fixed |
| Tall/wide asymmetric (>512 one axis) | 3 | Edge cases #2/#3 |
| Vertical JP text | 2 | MangaOcr path interaction |

Stored as compressed JPEGs in `app/src/test/assets/corpus/aot/` with a manifest JSON (page, expected-category, expected-box-count).

---

## 5. Implementation Route — Six Phases

Designed for parallel subagents where there's no shared state. Not initiated — awaits approval.

### Phase 0 — Prerequisite (sequential, blocking)

```
P0-1: Fix copyIfNeeded (version-stamp re-copy)
  Files: OnnxModelStore.kt (copyIfNeeded ~line 187, looksLikeValidOnnx ~line 253)
  Tests: OnnxModelStoreVersionTest.kt (Tier 1)
  Blocker for: EVERYTHING below (model won't reach users otherwise)
  Method: Add a sibling .version file containing BuildConfig.VERSION_CODE + model
          content hash. copyIfNeeded re-copies on mismatch. Handles missing stamp
          file (legacy cache) by treating absence as "always re-copy once."
  Scope: Systemic — benefits all 12 model call sites, not just AOT.
  Single agent, ~half-day
```

**Verification fact this rests on:** `OnnxModelStore.kt` currently has ZERO version/hash mechanism (verified — searched whole file for version|hash|metadata|sha256|md5|etag, zero hits). All 12 model call sites use the same `copyIfNeeded`. The fix is systemic.

### Phase 1 — Three Parallel Workstreams (no shared state)

Once P0-1 lands, these three are independent and run concurrently:

```
┌─────────────────────────────────────────────────────────────────────┐
│ P1-A: Model conversion (offline Python, subagent)                  │
│   - Convert aot.onnx → aot-512.onnx (static [1,3,512,512])         │
│   - Run onnxslim constant folding (1940→450 nodes, verified safe)  │
│   - Run Tier 2 numerics test (gate: max-diff < 1e-3)               │
│   - Output: aot-512.onnx committed to assets/models/inpainting/    │
│   Dependency: P0-1 done (so asset swap is meaningful)              │
│   Single agent                                                     │
└─────────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────────┐
│ P1-B: Quality corpus + harness (subagent)                          │
│   - Curate 20-page corpus into app/src/test/assets/corpus/aot/     │
│   - Build Tier 3 guard-rejection harness (JVM, uses AotOutputGuard)│
│   - Establish baseline: run dynamic model on corpus, record stats  │
│   - Output: corpus + baseline_report.json                          │
│   Dependency: none (corpus is independent of code changes)         │
│   Single agent                                                     │
└─────────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────────┐
│ P1-C: Geometry fix + pad path (subagent, Kotlin)                   │
│   - Fix centeredReportCrop sub-512 crash (AotBoxGeometry.kt:32,35) │
│   - Add bg-color pad-to-512 + crop-back in AOTInpainting.inpaint() │
│   - Update buffer pool sizing (512²; keep 768² headroom for the    │
│     dynamic fallback path — both pools must coexist)               │
│   - Tests: extend AotBoxGeometryTest, new AotPadPathTest (Tier 1)  │
│   Dependency: P0-1 done; independent of P1-A (model swap is later) │
│   Single agent                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Why these three are safe in parallel:**
- P1-A produces a model file (no Kotlin).
- P1-B produces test assets + a harness (no production Kotlin).
- P1-C modifies production Kotlin but tests against the EXISTING dynamic model — the static model isn't wired in until Phase 2.
- No shared files, no ordering conflict.

### Phase 2 — Integration (sequential, after all P1 done)

```
P2-1: Wire static-512 model into AOTInpainting
  - OnnxModelStore: add aot-512.onnx as a variant (version-stamped via P0-1)
  - AOTInpainting: load 512-static model, feed padded input, crop back
  - Run Tier 3 corpus harness against integrated path (gate)
  Single agent (integration needs coherence), ~1 day

P2-2: Tier 3 gate decision
  - If pass: proceed to P3
  - If fail (new rejections): diagnose, loop back to P1-A (model) or P1-C (pad)
  DECISION POINT — do NOT proceed on failure
```

### Phase 3 — NNAPI EP + Fallback Cascade (sequential, after P2 gate)

Riskiest phase. Single agent, careful sequencing.

```
P3-1: NNAPI capability probe + Layer 0/1 gate
  - Add NnapiCapabilityGate (probe session, inspect coverage/partitions)
  - Device capability detection (beyond dead isQualcommSnapdragon —
    verified: zero functional call sites, only a log string)
  - Tests: NnapiCapabilityGateTest (Tier 1)

P3-2: Dual-session holder + Layer 2/3 runtime fallback
  - AOTInpainting holds both NNAPI and XNNPACK sessions
  - Per-inference: try NNAPI → guard check → XNNPACK fallback on reject
  - Memory-pressure teardown: NNAPI session first (AFTER race-condition
    Bug 5 fix lands so forceReleaseNativeBuffers no longer SIGSEGVs)

P3-3: Layer 4 aggregate health monitor
  - Rolling window of guard verdicts
  - Auto-disable NNAPI on systematic failure
```

### Phase 4 — Op Surgery (CONDITIONAL, only if P3 reveals NNAPI still worse than CPU)

This is Sub-Project C from the decomposition — conditionally needed. If P3's on-device testing shows NNAPI partition-stall dominates, THEN do the ConvTranspose surgery. Otherwise skip.

```
P4 (conditional): Replace 4 ConvTranspose + fold Shape/ReduceProd
  - Offline model surgery (Python)
  - Re-run Tier 2 numerics gate (STRICTER — op replacement changes math,
    tolerance may need to tighten or loosen depending on replacement)
  - Re-run Tier 3 corpus gate
  Single agent
```

### Phase 5 — Release Gate (Tier 4)

```
P5: On-device smoke test on 3 device classes
  - Qualcomm flagship, MediaTek, old/emulated ARMv7
  - Confirm EP selection, fallback, no crashes
  - Then release
  Manual / instrumented
```

---

## 6. Parallelism Summary

| Phase | Concurrency | Rationale |
|---|---|---|
| P0 | 1 agent | Sequential prereq |
| P1 | **3 agents parallel** | No shared state (model file / test assets / Kotlin code) |
| P2 | 1 agent | Integration needs coherence + gate decision |
| P3 | 1 agent | Riskiest, needs careful sequencing |
| P4 | 1 agent (conditional) | Only if P3 on-device testing fails |
| P5 | Manual / instrumented | Needs devices |

---

## 7. Gates That Stop the Route

- **Tier 1 fails →** fix before merge. Blocks all downstream.
- **Tier 2 (numerics) fails →** P1-A model conversion is wrong. Loop.
- **Tier 3 (corpus) fails →** quality regression. DO NOT SHIP. The copyIfNeeded fix (P0-1) + crash fix (P1-C) can ship independently as a no-regret partial.
- **Tier 4 (on-device) fails →** EP selection or fallback broken. Do not release.

---

## 8. Files To Touch (when implementing)

**Phase 0 (deployment fix — systemic):**
- `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxModelStore.kt` (`copyIfNeeded` ~187, `looksLikeValidOnnx` ~253; add version-stamp mechanism)

**Phase 1A (model conversion — offline):**
- `app/src/main/assets/models/inpainting/aot-512.onnx` (new file, converted from aot.onnx)

**Phase 1B (corpus + harness — test-only):**
- `app/src/test/assets/corpus/aot/` (new dir, 20 JPEGs + manifest.json)
- `app/src/test/java/eu/kanade/translation/inpainting/AotCorpusHarnessTest.kt` (new)

**Phase 1C (geometry + pad path):**
- `app/src/main/java/eu/kanade/translation/inpainting/AotBoxGeometry.kt` (`centeredReportCrop` 26-38 — fix sub-512 crash)
- `app/src/main/java/eu/kanade/translation/inpainting/AOTInpainting.kt` (`inpaint` 417-656 — add bg-color pad-to-512 + crop-back; pool sizing ~54-61)
- `app/src/test/java/eu/kanade/translation/inpainting/AotBoxGeometryTest.kt` (extend)
- `app/src/test/java/eu/kanade/translation/inpainting/AotPadPathTest.kt` (new)

**Phase 2 (integration):**
- `OnnxModelStore.kt` (add aot-512.onnx variant resolution)
- `AOTInpainting.kt` (wire static model load + padded feed)

**Phase 3 (NNAPI + fallback):**
- `OnnxRuntimeProvider.kt` (extend to expose capability probe; currently `addNnapi` at 46-51 is fire-and-forget)
- New: `NnapiCapabilityGate.kt`
- New: `DeviceCapability` extension (SoC-level detection — current `isQualcommSnapdragon` is dead code)
- `AOTInpainting.kt` (dual-session holder, runtime fallback, Layer 4 health monitor)
- `app/src/test/java/.../NnapiCapabilityGateTest.kt` (new)

**Phase 4 (conditional op surgery — offline):**
- `aot-512.onnx` (re-surgeried)

---

## 9. Dependencies On Other Plans

This design depends on fixes from the companion plans landing first or in parallel:

| Dependency | Source plan | Why |
|---|---|---|
| **Bug 5 fix (`forceReleaseNativeBuffers` nativeGuard)** | Race-condition plan P0-1 | Phase 3 dual-session teardown reuses `onMemoryPressure` path. Without Bug 5 fixed, tearing down the NNAPI session mid-inference SIGSEGVs. **HARD prerequisite for P3-2.** |
| copyIfNeeded fix (this plan's P0-1) | — | Unblocks all model changes. Hard prereq for P1-A onward. |
| Optional: pipeline P1b (JPEG off permit) | Pipeline plan | Frees ~30-80ms/page — complements but doesn't block. |

The race-condition P0s and this plan's P0 can proceed in parallel — different files, no shared state.

---

## 10. Risks & Assumptions

- **Numerics gate tolerance (1e-3):** chosen loose-strict for shape conversion. If real diff is much larger, the conversion approach itself is suspect. Verify empirically in P1-A.
- **Background-color padding assumption:** assumes dominant-color padding is close enough to training distribution. Screentone/color manga are the risk. Tier 3 corpus MUST cover these. Escalation: mirror-padding.
- **NNAPI driver quality:** older Mali/Adreno drivers are known to claim graphs then produce garbage. Layer 4 aggregate monitor exists for this. Cannot be fully tested without real devices (Tier 4).
- **Op surgery (Phase 4) tolerance:** replacing ConvTranspose changes math. Tolerance may need adjustment. Cannot be specified until the replacement op is chosen.
- **Speed gains are estimates:** no on-device benchmarking exists. P1-B begins building measurement infra. Phase 3 decisions should be data-driven, not assumption-driven.
- **Dual-session memory (~22MB extra weights):** acceptable on 6GB-min devices. On memory-pressure path, NNAPI session tears down first. Requires Bug 5 fix to be safe.
- **Model inspection claims unverified by this audit:** the inpainting plan's op-count table (Conv=72, ConvTranspose=4, 0 MatMul, etc.) and "23 partitions / 94.7% coverage with fixed shapes" come from a reported ONNX inspector run, not independently verified here without an ONNX parser. Phase 1A/P3 must re-verify with a live `onnxruntime` capability check on the actual model.

---

## 11. Out of Scope (Deferred to Later Sub-Projects)

- **QNN/HTP path** (Snapdragon NPU core) — requires INT8/INT16 quantization + SoC gating. User chose NNAPI-broad instead. Revisit only if NNAPI proves insufficient and a quantization training pipeline exists.
- **Model distillation / retrain** (item B6 from opportunity list) — 2-3× speed ceiling but needs training pipeline + QA. Strategic separate project.
- **FP16 conversion** (item S7) — adversarial max-diff 0.19 is large enough to flip guard verdicts; ARMv8.0/8.1 device regression risk. Defer until on-device FP16-microkernel verification exists.
- **Lower crop res 512→384** (item S8) — mutually exclusive with the 512 mandate; would need a separate quality study.
- **Centralizing the 4 existing pad implementations** (white-center / black-top-left / gray-letterbox / gray-right) into a shared helper — pure refactor, no behavior change, can ship independently but not required for this route.

---

## 12. Method Note

This design was developed through a structured brainstorming process after auditing three companion investigation reports. Every load-bearing code claim was verified against live code:

- `OnnxModelStore.copyIfNeeded` / `looksLikeValidOnnx` — verified, zero version mechanism.
- `OnnxRuntimeProvider.addNnapi` silent fallback — verified at lines 46-51.
- `AotBoxGeometry.centeredReportCrop` crash — verified at lines 32, 35.
- `AOTInpainting.inpaint` resize-back to cropWidth×cropHeight — verified at lines 592-608.
- `AotOutputGuard` decoupled + unit-testable with raw IntArrays — verified.
- Buffer pools sized 768² (~6.75MB img, ~2.25MB mask) — verified at lines 54-61.
- `isQualcommSnapdragon` dead code — verified (zero functional call sites).
- Existing pad patterns (MangaOcr white-center, Paddle black-top-left, BubbleSegmenter gray-letterbox, Paddle-rec gray-right) — verified.

**No code changes made. Design only. Awaiting approval.**
