# Inpainting Efficiency — Implementation Design

**Date:** 2026-07-12
**Scope:** Design only. No code changes. Implementation route for the fixes proposed in `INPAINTING_EFFICIENCY_INVESTIGATION_2026-07-12.md`.
**Source plan:** `INPAINTING_EFFICIENCY_INVESTIGATION_2026-07-12.md` (audited 2026-07-12; both BLOCKERs verified, 2 issues found)
**Companion designs:** `AOT_NPU_FIXED512_DESIGN_2026-07-12.md` (absorbs P0 + P2 below), `TRANSLATION_RACE_CONDITION_DESIGN_2026-07-12.md`, `PIPELINE_EFFICIENCY_DESIGN_2026-07-12.md`
**Status:** Awaiting approval. Not initiated.

---

## Executive Summary

The source plan investigated 18 approaches, eliminated 9, and recommended 4 priorities (P0-P3). Audit verified **both BLOCKERs are real and exactly as described**, but found **2 issues** the source plan got wrong internally.

**Critical reconciliation up front:** This plan's recommendations are **partially absorbed by the AOT NPU design**. Specifically:
- Inpainting **P0** (copyIfNeeded deployment bug) → **now AOT NPU Phase 0-1** (same fix, same systemic scope).
- Inpainting **P2** (onnxslim constant folding) → **now AOT NPU Phase 1-A** (bundled with static-512 conversion).

**What remains standalone in THIS plan** (not covered by AOT NPU):
- **P1** — disable intra-op spinning for AOT session (~1-5%, safe, reversible).
- **P3** — ORT 1.21→1.23 upgrade (~3-8%, low-medium risk).

This document designs those two remaining items and explicitly points to AOT NPU for the absorbed ones, to avoid double-execution.

---

## 2. Issues Found in Source Plan (must be corrected before relying on it)

### Issue 1 — Image-feeding buffers: source plan contradicts itself, and both are wrong

**Source plan §1.3 step 4** says the feeding loop uses pooled scratch arrays (`sharedImgPixels`/`sharedMaskPixels`, lines 63-75).

**Source plan Appendix A** doubles down: *"IntArray intermediates are pooled, not fresh per call."*

**Live code says otherwise:** the actual feeding loop allocates **fresh `IntArray` per call**:
- `AOTInpainting.kt:514`: `val imgPixels = IntArray(inferenceWidth * inferenceHeight)`
- `AOTInpainting.kt:516`: `val maskPixels = IntArray(inferenceWidth * inferenceHeight)`

The pooled arrays (`sharedImgPixels`/`sharedMaskPixels`/`sharedResultPixels`, lines 63-75) exist but are used **only in postprocess** (`isSuspiciousUniformOutput` at 688-689, `featherBlend` at 716-718).

**Consequence:** There IS a small un-pooled allocation the source plan wrongly dismissed (~6MB heap churn per page on dense pages). It's in the noise next to 300-600ms inference, but anyone acting on the "already pooled" claim would be wrong. This is a candidate fix — see §3 (new item P1.5).

### Issue 2 — Sibling model resolution: "640×640 for all 4" is wrong

Source plan §2.1 says the 4 sibling models work on NNAPI because they have "fixed 640×640 inputs."

**Live code:** Paddle det is **736×736** (`PaddleOcrV6DetEngine.kt:294`, `TARGET = 736`), not 640. The static-vs-dynamic conclusion still holds; the specific dimension is wrong.

**Consequence:** Minor. Doesn't change any recommendation. Noted for accuracy.

### Issue 3 (not a plan error, a verification gap) — ONNX op-count table is unverified

The op-count table (Conv=72, ConvTranspose=4, 0 MatMul, 78 ReduceMean, etc.) comes from the source plan's reported ONNX inspector run. **Not independently verifiable without an ONNX parser.** The "AOT is a CNN, not a transformer" conclusion is plausible but unconfirmed by this audit. AOT NPU Phase 1-A / Phase 3 must re-verify with a live `onnxruntime` capability check.

---

## 3. Fixes In Scope (reconciled with AOT NPU)

### Absorbed by AOT NPU design — DO NOT execute here

| Source ID | Fix | Now lives in |
|---|---|---|
| Inpainting P0 | copyIfNeeded version-stamp | **AOT NPU Phase 0-1** |
| Inpainting P2 | onnxslim constant folding | **AOT NPU Phase 1-A** (bundled with static-512) |
| Source plan A10 (static-512) | Static shape conversion | **AOT NPU Phase 1-A** (the user mandate) |
| Source plan A11 (onnxslim as standalone) | Graph optimization | **AOT NPU Phase 1-A** |

### Standalone in this plan

| ID | Approach | Gain | Risk | File:Line |
|---|---|---|---|---|
| **P1** (source A8-partial) | Disable intra-op spinning for AOT session: `addConfigEntry("session.intra_op.allow_spinning", "0")` BEFORE `addXnnpack()` | ~1-5% | LOW (config-only, reversible) | `OnnxRuntimeProvider.kt` (lambda ordering) + `AOTInpainting.kt:111` |
| **P3** (source A13) | ORT 1.21→1.23 upgrade | ~3-8% | LOW-MEDIUM (no API breaks; brings 16KB-page support Android 15+) | `gradle/libs.versions.toml:17` |
| **P1.5** (NEW — from Issue 1) | Pool the feeding-loop `IntArray`s (`imgPixels`/`maskPixels` at 514/516), matching the postprocess pooled arrays | ~6MB heap churn/page eliminated | LOW (mirror existing pooling) | `AOTInpainting.kt:514, 516, 63-75` |

---

## 4. Verification Design

### P1 — Spinning-off

**Critical ordering constraint (from source plan, verified):** `addConfigEntry` MUST run BEFORE `addXnnpack()`. The `configure` lambda currently runs AFTER EP registration (`OnnxRuntimeProvider.kt:60`), so the lambda is too late. Fix requires either:
- Moving the `configure` lambda call before the EP branch (line 45), OR
- Adding an `allowSpinning` param to `createSessionOptions`.

**Test approach:**
- **Structural:** assert the spinning-disable config is present in the AOT session options (not the 4 NNAPI models or OCR sessions — scoped to AOT only).
- **Manual:** on-device timing of AOT inference before/after. The gain (~1-5%) is within measurement noise on a single run — need a batch of ≥20 inferences averaged.
- **Negative test:** assert the 4 NNAPI model sessions and OCR sessions do NOT get the spinning-disable (no global disable).

**Gate:** structural test passes; manual timing shows no regression (neutral is acceptable — the source plan notes possibly-neutral on some big.LITTLE devices).

### P3 — ORT upgrade

**Test approach:**
- **Build verification:** project compiles against ORT 1.23. QNN artifact exists for both (verified: artifact is `onnxruntime-android-qnn`).
- **Regression:** all existing translation unit tests pass.
- **Smoke:** load each model (detector, OCR, inpaint, panel, segmenter, paddle) on-device, confirm no load-time regression.
- **16KB-page support:** verify on an Android 15+ device if available (the headline feature of 1.22+).

**Gate:** builds; existing tests pass; on-device smoke clean.

### P1.5 — Pool feeding IntArrays

**Test approach:**
- The feeding loop writes to `IntArray` then copies to the direct `FloatBuffer`. Pooling means reusing a scratch `IntArray` across calls (like postprocess already does).
- Assert: pooled array is cleared/overwritten fully each call (no stale-pixel bleed).
- Assert: output tensor identical to current fresh-array version.
- Pattern exists: `sharedImgPixels`/`sharedMaskPixels` (lines 63-75) already do this for postprocess — extend to feeding.

**Gate:** output identical; no stale-pixel bleed.

---

## 5. Implementation Route

### Phase 1 — P1 (spinning-off) + P1.5 (pool feeding arrays)

These touch `AOTInpainting.kt` and `OnnxRuntimeProvider.kt`. P1 also needs the factory change. Run as a single agent (related, same files).

```
P1 + P1.5 (single agent):
  - OnnxRuntimeProvider.kt: reorder configure lambda OR add allowSpinning param
  - AOTInpainting.kt:111: pass spinning-disable config
  - AOTInpainting.kt:514, 516: pool feeding IntArrays
  Tests: structural (spinning scoped to AOT) + output-identical (pooling)
  ~1 day
```

### Phase 2 — P3 (ORT upgrade)

Separate agent, different file (`gradle/libs.versions.toml`).

```
P3 (single agent):
  - libs.versions.toml:17: 1.21.0 → 1.23.x
  - Build verification
  - Existing test suite
  - On-device smoke (manual)
  ~half-day + device time
```

**P1+P1.5 and P3 are parallel-safe** (different files).

---

## 6. Cross-Plan Reconciliation

| Item | Overlap | Resolution |
|---|---|---|
| **Inpainting P0/P2 absorbed by AOT NPU** | copyIfNeeded + onnxslim moved to AOT NPU Phase 0-1 / 1-A. | **Do NOT execute here.** AOT NPU owns these. This plan points to AOT NPU to avoid double-execution. |
| **P1 (spinning-off) vs AOT NPU Phase 3 (NNAPI EP)** | If AOT moves to NNAPI, the XNNPACK spinning config becomes irrelevant for the NNAPI path but still relevant for the XNNPACK fallback session. | P1 ships independently now (helps today's XNNPACK path). When AOT NPU lands, the spinning config applies to the fallback XNNPACK session. No conflict. |
| **P1.5 (pool feeding arrays) vs AOT NPU Phase 1-C (pad path)** | AOT NPU Phase 1-C rewrites the feeding loop for 512-padding. P1.5 pools the arrays in the current feeding loop. | **Order matters:** ship P1.5 BEFORE AOT NPU Phase 1-C, so Phase 1-C inherits the pooled arrays. Or let Phase 1-C absorb P1.5. Either way, don't execute both independently — coordinate. |
| P3 (ORT upgrade) vs AOT NPU | AOT NPU assumes ORT 1.21 capabilities. P3 upgrades to 1.23. | P3 can ship before or after AOT NPU. If before, AOT NPU's NNAPI capability probe (Phase 3-1) should re-verify against 1.23. |

---

## 7. Files To Touch

**P1 + P1.5:**
- `OnnxRuntimeProvider.kt` (reorder configure lambda before EP branch at 45, OR add `allowSpinning` param)
- `AOTInpainting.kt:111` (pass spinning-disable), `:514, 516, 63-75` (pool feeding arrays)
- New structural test for spinning scope

**P3:**
- `gradle/libs.versions.toml:17` (version ref)

---

## 8. Risks & Assumptions

- **P1 gain is an estimate (~1-5%), possibly neutral.** Source plan honestly notes big.LITTLE devices may show no gain. The value is "safe to try behind a log line" — not a guaranteed win.
- **P1.5 stale-pixel risk:** pooling feeding arrays means the array must be fully overwritten each call. The inference width/height varies per call (dynamic model), so the pooled array must be sized for max (768²) and the active region fully written. Verify no partial-write path.
- **P3 16KB-page support unverified on this project's minSdk.** The feature targets Android 15+; if the project's minSdk is lower, the benefit is forward-looking.
- **Source plan's op-count table unverified** (Issue 3). Doesn't affect P1/P1.5/P3, but affects AOT NPU Phase 4 (op surgery) scoping.
- **Source plan's "file size shrinks 0.9%" for onnxslim** is in AOT NPU's scope now; benefit is graph cleanliness, not APK size or verified speed.

---

## 9. Out of Scope

- **copyIfNeeded fix** — moved to AOT NPU Phase 0-1.
- **onnxslim** — moved to AOT NPU Phase 1-A.
- **Static-512 / NNAPI / QNN** — fully in AOT NPU design.
- **FP16 (A9), INT8 PTQ (A3), model distillation (A16), QAT (A18)** — source plan defers all; needs training pipeline or on-device verification that doesn't exist.
- **Lower crop res 512→384 (A6)** — mutually exclusive with AOT NPU's 512 mandate.

---

## 10. Method Note

Source plan audited 2026-07-12: both BLOCKERs (copyIfNeeded 187-227, static-512 crash AotBoxGeometry.kt:32,35) verified exactly. Two issues found: (1) feeding-loop buffers are fresh per call, not pooled (source plan §1.3 and Appendix A both wrong); (2) "640×640 for all 4 siblings" wrong — Paddle det is 736×736. Op-count table unverifiable without ONNX parser. Reconciliation with AOT NPU design done: P0 + P2 absorbed; P1, P1.5, P3 remain standalone.

**No code changes made. Design only. Awaiting approval.**
