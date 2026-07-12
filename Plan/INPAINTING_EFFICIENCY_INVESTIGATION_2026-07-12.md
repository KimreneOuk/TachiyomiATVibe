# Inpainting Efficiency Investigation — Fast vs Quality Mode

**Date:** 2026-07-12
**Scope:** Investigation only. No code changes. Assess efficiency of both inpainting modes, image-feeding correctness, NPU utilization, and speed headroom.
**Method:** 3-round critique loop (defender verifies + subagents attack/refine). All claims verified against live code + model inspection. Test artifacts cleaned.

---

## Executive Summary

**Current inpainting is already well-engineered.** Image feeding is correct (NCHW, `[-1,1]` normalization, 512×512 crop, pooled buffers). FAST mode is a pure-CPU heuristic pipeline with no neural model — it is fast and appropriate. QUALITY mode uses the AOT-GAN neural model on XNNPACK — the dominant cost center (~300–600ms per free-text group).

**NPU/NNAPI is correctly NOT used for the AOT model.** This is not a missed optimization — it is a hard technical block. Two independent blockers verified: (1) dynamic input shapes, and (2) **operator coverage** — the model contains 4 `ConvTranspose` (decoder upsample) ops that NNAPI/CoreML do not support at all. ORT's own NNAPI usability checker, run on the actual `aot.onnx`, reports **6.7% node coverage as-is** ("NNAPI not recommended — worse than CPU") and **still "NO" with fixed shapes** (94.7% coverage but 23 partitions due to the 4 ConvTranspose + 26 Shape + 10 ReduceProd). Each partition forces an NPU↔CPU crossing. Shape was never the binding constraint; operator composition is.

**Speed headroom exists but is modest and gated by two deployment blockers.** The highest-value safe win (onnxslim graph optimization) is numerically verified safe but **cannot reach existing users** due to a `copyIfNeeded` deployment bug. The thread-config lever exists but is smaller than initially estimated (~1–5%, not 15–25%).

**Bottom line:** Translation is already fast. Pushing harder is possible but the risk/reward is marginal. The single most valuable action is fixing the model-deployment bug — it unblocks ALL future model improvements, not just inpainting.

---

## 1. Verified Baseline

### 1.1 Model Architecture (verified by direct ONNX inspection)

`app/src/main/assets/models/inpainting/aot.onnx` — 23,068,213 bytes, opset 18, IR version 8, pytorch 2.8.0 producer.

**It is a pure CNN (AOT-GAN), NOT a transformer.** This corrects the initial assumption in the task prompt ("AOT is a neural network model... tokenized transformer").

| Op | Count | Op | Count |
|---|---|---|---|
| Constant | 698 | Relu | 46 |
| Mul | 178 | Sub | 82 |
| Reshape | 148 | ReduceMean | 78 |
| Cast | 92 | Concat | 77 |
| **Conv** | **72** | ConstantOfShape | 66 |
| Slice | 66 | Transpose | 66 |
| Pad | 66 | Div | 52 |
| Sqrt | 26 | Shape | 26 |
| Identity | 22 | Add | 20 |
| Sigmoid | 18 | Max | 16 |
| Gather | 10 | ReduceProd | 10 |
| **ConvTranspose** | **4** | Clip | 1 |

- MatMul=0, Attention=0, Softmax=0, Gemm=0, LayerNormalization=0 — **no transformer components**.
- 78 ReduceMean + 82 Sub + 26 Sqrt + 52 Div = **manual GroupNorm/InstanceNorm subgraphs** (mean→sub→square→mean→sqrt→div), not fused ONNX norm ops.
- 698 Constant nodes are opset-18 dynamic-axes scaffolding (axes for ReduceMean fed as runtime inputs) — **foldable offline**.
- **Inputs fully dynamic**: `image ['batch', 3, 'h', 'w']`, `mask ['batch', 1, 'h', 'w']` (dtype float32). Output: `inpainted ['batch', 3, 'h', 'w']`.
- 178 float32 initializers, total 21.66 MB of weights. Conv weight dtype = float32.

### 1.2 Session Configuration

`OnnxRuntimeProvider.kt:27–59` (shared factory):

```kotlin
val cpuCores = Runtime.getRuntime().availableProcessors()
val threads = (cpuCores / 2).coerceIn(2, 4)
setInterOpNumThreads(threads)
setIntraOpNumThreads(threads)
setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
runCatching { setCPUArenaAllocator(false) }
runCatching { setMemoryPatternOptimization(false) }
// EP branch (NNAPI / XNNPACK / CPU)
```

| Model | EP | File:Line |
|---|---|---|
| Text detector (RT-DETR int8) | **NNAPI** | `OnnxPageTextDetector.kt:38` |
| Panel detector (YOLO int8) | **NNAPI** | `OnnxPanelDetector.kt:57` |
| Paddle det (DB) | **NNAPI** | `PaddleOcrV6DetEngine.kt:70` |
| Bubble segmenter (YOLO-seg int8) | **NNAPI** | `OnnxBubbleSegmenter.kt:22` |
| **AOT inpaint (float32)** | **XNNPACK** | `AOTInpainting.kt:111` |
| Manga-OCR encoder | CPU | `MangaOcrEngine.kt:57` |
| Manga-OCR decoders | CPU | `MangaOcrEngine.kt:68` |
| Paddle rec (CTC) | CPU | `PaddleOcrV6SmallEngine.kt:54` |

- `onnxruntime-android-qnn:1.21.0` (QNN natives bundled) — but `addQnn()` is **never called** anywhere.
- `DeviceCapability.isQualcommSnapdragon` exists but has **zero call sites** (dead code).
- XNNPACK registered with **empty HashMap** (`OnnxRuntimeProvider.kt:54`).

### 1.3 Image Feeding Pipeline (verified correct)

`AOTInpainting.inpaint()` (`AOTInpainting.kt:417–656`):

1. **Crop**: 512×512 square per free-text group (`centeredReportCrop`, `AotBoxGeometry.kt:26`). Sub-512px pages → smaller crop.
2. **Resize/pad**: if crop >768, downscale to 768; pad to multiple of 8 (`AOTInpainting.kt:461–475`).
3. **Canvas draw** into pooled ARGB_8888 bitmaps (lines 488–512).
4. **getPixels** into pooled scratch IntArrays (`sharedImgPixels`/`sharedMaskPixels`, lines 63–75 — **pooled, not fresh per call**).
5. **Normalize**: `/127.5f - 1.0f` → `[-1,1]`, NCHW layout (lines 529–546). RGB channels pre-multiplied by `(1-mask)` before model.
6. **Tensor create**: `OnnxTensor.createTensor(env, pooledFloatBuffer, shape)` (lines 549–550).
7. **Inference**: `sess.run(feed)` (line 556).
8. **Postprocess**: de-normalize → resultBitmap → resize back → feather blend (12px ramp) → composite onto source.

**Image feeding is correct.** Normalization matches model expectation. Channel order is standard RGB. Mask is binary `{0,1}`. Buffers are pooled direct FloatBuffer + pooled IntArray scratch. No correctness issue found.

**Neural model is called once per free-text GROUP** (not per box, not per page). Bubble regions are CPU-filled (`AotReportBubbleFill`). Most pages have 1–2 free-text groups → 1–2 neural calls per page.

### 1.4 FAST vs QUALITY Mode

| Aspect | FAST | QUALITY |
|---|---|---|
| Neural model | ❌ never loaded | ✅ AOT-GAN (`sess != null`) |
| EP | N/A (no ONNX) | XNNPACK |
| Free-text fill | `PushPullGradient` (CPU) | `inpaintReportFreeTextAot512` (neural) |
| Bubble fill | `AotReportBubbleFill` (CPU BFS+median) | Same (CPU) — **bubbles are always CPU** |
| Fallback | — | Neural → push-pull → `SmartBubbleTextCleaner` (Telea) |
| Default pref | ✅ `"FAST"` is default | User opt-in |

**FAST mode is efficient and appropriate.** No neural model, no ONNX overhead, pure-CPU heuristics. No optimization opportunity in FAST mode — it's already the fast path.

---

## 2. NPU / NNAPI Assessment

### 2.1 Why NNAPI is correctly NOT used for AOT

**BLOCKER: NNAPI does not support dynamic input shapes.** Verified against ORT maintainers (issue #11073) and the `make-dynamic-shape-fixed` helper tool documentation:

> "ORT supports dynamic input shapes in general, and the CPU EP can be used with them. **NNAPI and CoreML do not support dynamic shapes.**"

The AOT model has `image ['batch', 3, 'h', 'w']` + `mask ['batch', 1, 'h', 'w']` with runtime-computed H,W. If we called `addNnapi()`:
- **Best case**: NNAPI's `GetCapability()` sees dynamic dims, refuses all nodes → entire graph falls back to CPU EP. Net result: **slower than XNNPACK** (XNNPACK is hand-tuned for ARM; CPU EP is generic MLAS). Log would misleadingly say "Successfully added NNAPI EP."
- **Worst case**: NNAPI claims the graph, a driver materializes a shape at first-run, then **fails or returns garbage** on a differently-sized subsequent input (driver-dependent, known on older Mali/Adreno drivers).

**The 4 sibling models work on NNAPI because they have static shapes** (fixed 640×640 inputs). AOT is the odd one out due to its variable crop sizes.

### 2.2 Why QNN/NPU is correctly NOT used

**Two independent blockers:**

1. **Dynamic shapes** — same as NNAPI. QNN EP docs: *"QNN EP does not support models with dynamic shapes."*
2. **HTP requires quantized models** — QNN HTP (the NPU) docs: *"only supports quantized models... must first be quantized to 8-bit or 16-bit."* `aot.onnx` is float32.

**`addQnn()` IS callable from the Java/Kotlin API** (verified: `OrtSession.SessionOptions.addQNN(Map<String,String>)` exists in ORT 1.21). The QNN natives ARE bundled (`onnxruntime-android-qnn` artifact). But the model is not QNN-ready on either count.

### 2.3 What WOULD be needed to use an accelerator

To unblock NNAPI/QNN for AOT:
1. **Convert `aot.onnx` to fixed input shape** (e.g., `[1,3,512,512]`). Verified feasible: dim override + `onnxslim` → 390 nodes, `onnx.checker` VALID, runtime max-abs-diff 1.1e-4 (float32 noise).
2. **Rework ALL call sites** to always feed 512×512 (pad sub-512px crops — see BLOCKER C1 below).
3. For QNN specifically: **offline INT8/INT16 quantization** with calibration data + quality revalidation.
4. **Device capability detection** beyond the current `isQualcommSnapdragon` boolean (QNN needs specific SoC model code).

This is a multi-step project, not a toggle. The dynamic→static conversion is the prerequisite, and it has its own correctness blocker (§4.2).

---

## 3. Approaches Evaluated (18 total, 3-round critique)

### 3.1 Eliminated (INFEASIBLE or negative ROI)

| ID | Approach | Verdict | Reason |
|---|---|---|---|
| A1 | NNAPI for AOT | **INFEASIBLE** | Dynamic shapes block; silent fallback to slower CPU |
| A7 | QNN/NPU for AOT | **INFEASIBLE** | Dynamic shapes + float32 (needs quantization); multi-quarter project |
| A2-arena | Re-enable CPU arena | **INFEASIBLE** | Re-trips June-2026 OOM; XNNPACK ops don't use ORT arena |
| A2-mempattern | Re-enable mem-pattern | **INFEASIBLE** | Dynamic shapes invalidate pattern every call |
| A5 | Batch free-text groups | **INFEASIBLE** | N=1 is common case (zero gain); model batch axis may be fixed; OOM risk |
| A4 | Eliminate IntArray intermediary | **TRIVIAL** | "Extra pass" doesn't exist; loop already fused. Only ~1-2ms from pooling unused scratch arrays |
| A12 | Fused norm op replacement | **LOW-VALUE** | Subsumed by onnxslim (A11) |
| A15 | Inpaint cache | **LOW-VALUE** | Disk persistence already handles re-translation |
| A17 | Vulkan/WebGPU EP | **INFEASIBLE** | No production Android Vulkan EP for ORT |

### 3.2 CONDITIONAL (needs research/on-device testing)

| ID | Approach | Verdict | Condition |
|---|---|---|---|
| A3 | INT8 quantize AOT | **CONDITIONAL→INFEASIBLE-leaning** | Manual GroupNorm subgraphs are quantization-hostile; forces XNNPACK→CPU-EP move; quality risk for GAN pixel output. Dynamic quant no-op (0 MatMul). Only static+calibration+selective-exclusion could work. |
| A6 | Lower crop res (512→384/256) | **CONDITIONAL** | 384² defensible (~1.8× compute win); 256² too aggressive (guard cascade). Must validate guard-rejection rate + visual QA. |
| A8-full | Full thread-config (3-part) | **CONDITIONAL** | Gain overestimated (~1-5% not 15-25%); `setIntraOpNumThreads(1)` serializes ReduceMean (regression); thermal risk on sustained batches. |
| A9 | FP16 conversion | **CONDITIONAL** | XNNPACK source accepts FP16 Conv, BUT: (1) Android prebuilt may not include FP16 microkernels, (2) only ARMv8.2+ devices benefit, (3) conversion requires ORT transformer converter (not `onnxconverter_common`), (4) adversarial-input max-diff 0.19. Needs on-device verification. |
| A13 | ORT 1.21→1.22/1.23 upgrade | **CONDITIONAL-SAFE** | QNN artifact exists for both; no API breaks; no ArmNN dependency. Brings 16KB-page support (Android 15+). Low risk, small gain (~3-8%). |
| A14 | Async prep/inference pipelining | **CONDITIONAL** | Overlap next-page prep with current inference. Batch-path only (~5-10%). Memory tradeoff (2 pages' buffers alive). |
| A16 | Model distillation (lighter generator) | **STRATEGIC** | 2-3× ceiling. Requires training pipeline + QA. Separate project. |
| A18 | QAT for INT8 | **STRATEGIC** | Correct path to quantization (solves A3's quality problem). Needs training pipeline. |

### 3.3 Winners (survived all 3 critique rounds)

| ID | Approach | Gain | Risk | Status |
|---|---|---|---|---|
| **A11** | **onnxslim graph optimization** | 76.8% node reduction (1940→450) | Numerically safe (max-diff 3.2e-5) | **BLOCKED by deployment bug** |
| **A10** | **Static-512 shape conversion** | 390 nodes; unblocks accelerators | Sub-512px crash + memory budget | **BLOCKED by correctness bug (C1)** |
| **A8-partial** | **Disable intra-op spinning** | ~1-5% (spinning waste elimination) | Low (config-only, reversible) | **SAFE but small** |

---

## 4. Critical Findings (BLOCKERS)

### 4.1 BLOCKER — Model deployment bug (`OnnxModelStore.copyIfNeeded`)

**File:** `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxModelStore.kt:187–227`

```kotlin
private fun copyIfNeeded(dir: File, name: String, assetPath: String): File {
    val dest = File(dir, name)
    if (dest.exists() && dest.length() > 0) {
        if (name.endsWith(".onnx")) {
            if (looksLikeValidOnnx(dest)) return dest  // ← returns OLD cached model
            // ...only re-copies if structurally corrupt
        } else {
            return dest
        }
    }
    // ...copy from assets
}
```

`looksLikeValidOnnx` (lines 253–270) checks only: (1) size ≥ 64KiB, (2) byte 0 == `0x08`, (3) byte 1 high-bit clear. **No hash, no versionCode, no size comparison.**

**Consequence:** An optimized AOT model (onnxslim'd, static-512, FP16, etc.) is still a valid ONNX file. It passes `looksLikeValidOnnx`. The old cached 1940-node model also passes. **`copyIfNeeded` returns the old cached model forever.** Only fresh installs get the new model.

This is **systemic** — it affects ALL models (detector, OCR, inpaint, panel, segmenter, paddle), not just AOT. Any future model improvement is invisible to existing users.

**This is the single highest-priority fix.** It unblocks not just inpainting optimization but ALL future model updates across the entire translation pipeline.

**Minimal fix:** Embed a version stamp (e.g., a sibling `.version` file with `BuildConfig.VERSION_CODE` + model content hash). Force re-copy on mismatch. Alternatively, compare asset size to cached size (cheaper, less robust).

### 4.2 BLOCKER — Static-512 correctness bug (`AotBoxGeometry.centeredReportCrop`)

**File:** `app/src/main/java/eu/kanade/translation/inpainting/AotBoxGeometry.kt:32,35`

```kotlin
val side = min(contextSize, min(width, height))  // ← sub-512 on small pages
val x1 = (cx - side / 2).coerceIn(0, width - side)  // ← throws if width < side
```

When `min(image.width, image.height) < 512`, `side` is clamped down (e.g., 400 on a 400×600 page). A static-512 model would **hard-reject** the resulting 400×400 crop:

```
OrtException: INVALID_ARGUMENT: Got invalid dimensions for input image.
index 2: Got: 400 Expected: 512
```

This is caught by the generic `catch (e: Exception)` at `AOTInpainting.kt:243` → **silent fallback to `inpaintReportFreeTextFast`**. The neural path is dead for sub-512px pages with no signal.

**The naive fix ("force side=512") crashes:** `coerceIn(0, width - side)` = `coerceIn(0, 400 - 512)` = `coerceIn(0, -112)` → Kotlin throws `IllegalArgumentException` (min > max).

**Correct fix requires either:**
- (a) Zero-pad a 512×512 bitmap copy, run inference, crop back — nontrivial code change in `inpaint()`, or
- (b) Early-return in `inpaintReportFreeTextAot512` that skips AOT when `min(width,height) < 512` (accept FAST fallback for small pages).

**Recommendation:** Drop static-512 (A10) for now. The dynamic model + onnxslim (A11) captures ~95% of the node-count benefit with zero correctness risk. Revisit static-512 only when pursuing an accelerator (NNAPI/QNN) that demands it.

### 4.3 RISK — Quality regression detection gap

There is **no automated quality check** beyond `isSuspiciousUniformOutput` (`AOTInpainting.kt:663`, `AotOutputGuard.kt`). The guard catches uniform near-black/mid-gray/near-white fills. It does NOT catch plausible-but-wrong non-uniform output.

The guard thresholds (`NEAR_BLACK_MAX=24`, `MAX_LUMA_VARIANCE=9`, `MAX_CHANNEL_DELTA=8`) are far above the onnxslim numerical diff (3.2e-5 ≈ 0.007 in 0–255 luma) — **the guard verdict will not change on borderline outputs.** But if an optimization causes the model to produce semantically wrong (but textured) inpaints, nothing catches it.

`AotOutputGuardTest.kt` has 11 tests covering synthetic uniform fills — it tests the classifier logic, not real-model output interaction.

**Recommendation:** Before shipping any model optimization, run a corpus of real pages (dense text, screentone, color, large bubbles) and compare guard-rejection counts before/after. Visual QA on the non-rejected outputs.

---

## 5. The XNNPACK Thread Configuration (detailed — the most surprising finding)

### 5.1 The initial hypothesis (WRONG)

Round 1 critique claimed XNNPACK's default `intra_op_num_threads` = 1 (with empty HashMap), meaning the Conv-heavy compute runs single-threaded. Expected gain from fixing: 15–25%.

### 5.2 The corrected reality (verified against ORT v1.21 source)

Read `xnnpack_execution_provider.cc` directly:

```cpp
int xnn_thread_pool_size = info.xnn_thread_pool_size;  // 0 when HashMap empty
int ort_thread_pool_size = info.session_options ? ...thread_pool_size : 1;
if (xnn_thread_pool_size == 0) {
    xnn_thread_pool_size = ort_thread_pool_size;  // FALLS BACK TO ORT POOL
}
if (xnn_thread_pool_size > 1 && allow_intra_op_spinning && ort_thread_pool_size > 1) {
    LOGS_DEFAULT(WARNING) << "contention between the two thread pools";
}
```

With the project's config (`setIntraOpNumThreads(2-4)` + empty XNNPACK HashMap):
- XNNPACK pool inherits ORT pool size → **2–4 threads, NOT 1**.
- ORT pool = 2–4 threads. XNNPACK pool = 2–4 threads.
- **Both pools are active** → the source code's own WARNING fires.

### 5.3 The actual cost (smaller than expected)

ORT's default execution mode is SEQUENTIAL — one node finishes before the next starts. So:
- When XNNPACK Conv runs, ORT pool workers are **idle**.
- When CPU-EP fallback ops run, XNNPACK pool is **idle**.
- The two pools **never run compute simultaneously**.

The "contention" is **spinning waste**, not compute overlap:
- With `allow_spinning=true` (default), ORT workers spin **1,048,576 iterations** before blocking.
- On ARM, `SpinPause()` is a **no-op** (only x86 has `_mm_pause`) — so those 1M iterations are pure busy-wait.
- Estimated cost: **~5–30ms of spinning waste** on a ~300–600ms inference = **~1–5%**.

### 5.4 The safe fix (A8-partial)

```kotlin
// AOTInpainting.kt:111 — ONLY change needed
val opts = OnnxRuntimeProvider.createSessionOptions(
    useAccelerator = false,
    useXnnpack = true,
) { opts ->
    opts.addConfigEntry("session.intra_op.allow_spinning", "0")
}
```

**Critical ordering:** `addConfigEntry` MUST be called BEFORE `addXnnpack()`. XNNPACK reads the spinning flag at construction time. The `configure` lambda runs AFTER EP registration (line 60) — so this must be moved into the factory itself, or the lambda must be called before the EP branch.

**Scoped to AOT session only** — the 4 NNAPI models and OCR sessions don't pass this lambda, so they're unaffected. No risk of global disable.

**Expected gain: ~1–5%.** Safe, reversible (remove one line). The full 3-part fix (raise XNNPACK threads + lower ORT pool to 1) is NOT recommended — it risks ReduceMean serialization regression + thermal throttle on sustained batches.

---

## 6. Safety Assessment ("How safe is it to push for more speed?")

| Approach | Risk | Deploy today? | Honest assessment |
|---|---|---|---|
| **Fix `copyIfNeeded`** (deployment bug) | LOW | ✅ YES | Prerequisite for ALL model changes. Independently fixes a latent bug. **Do this first.** |
| **A8-partial** (spinning-off) | LOW | ✅ YES (measure) | Config-only, reversible. Gain uncertain (~1–5%, possibly neutral on some big.LITTLE devices). Safe to try behind a log line. |
| **A11** (onnxslim) | LOW (numerics) + BLOCKER (deploy) | ❌ AFTER copyIfNeeded fix | Numerically verified safe. Benefit is graph cleanliness, not speed (file size only shrinks 0.9%). Needs guard-rejection corpus run before/after. |
| **A13** (ORT 1.22/1.23 upgrade) | LOW-MEDIUM | ⚠️ Test first | No API breaks, QNN artifact exists. Brings 16KB-page support. Needs build verification. |
| **A10** (static-512) | HIGH | ❌ NO | Correctness bug on sub-512px pages. Memory budget gate unsafe for fixed shape. Only worth it as prerequisite for accelerator (NNAPI/QNN). |
| **A6** (lower resolution 384) | MEDIUM-HIGH | ❌ Test first | Real compute win (~1.8×) but quality risk. Must validate guard-rejection rate + visual QA. |
| **A9** (FP16) | HIGH | ❌ Test first | Needs on-device FP16-microkernel verification. Possible regression on older devices (ARMv8.0/8.1). |
| **A3** (INT8 PTQ) | HIGH | ❌ NO | Quality + EP-change risk. Manual norm subgraphs are quantization-hostile. |

**Direct answer:** For a manga reader where translation is "already fast," most of these are **not worth the regression risk**. The translation pipeline has no automated quality regression detection beyond the uniform-fill guard — a "fast but subtly wrong" model ships with no signal until users complain.

The two things worth doing:
1. **Fix the `copyIfNeeded` deployment bug** — zero-risk, unblocks everything.
2. **Try spinning-off (A8-partial)** — low-risk, reversible, small gain.

Everything else should wait for on-device benchmarking infrastructure + a quality regression corpus.

---

## 7. Recommendations (Priority Order)

### P0 — Fix deployment bug (prerequisite for all model changes)
**`OnnxModelStore.copyIfNeeded`**: Add version/hash-based re-copy. Without this, no model optimization reaches existing users. Affects ALL models, not just AOT.

### P1 — Disable intra-op spinning for AOT session (safe, small gain)
**`AOTInpainting.kt:111`**: `addConfigEntry("session.intra_op.allow_spinning", "0")` before `addXnnpack()`. Scoped to AOT only. Expected ~1–5%. Reversible.

### P2 — onnxslim model optimization (after P0)
Offline: `onnxslim.slim(aot.onnx)` → 450 nodes (76.8% reduction). Drop-in model swap. Numerically safe (max-diff 3.2e-5). Validate with guard-rejection corpus. Benefit is graph cleanliness, not APK size (only 0.9% smaller).

### P3 — ORT version upgrade (low priority, small gain)
`gradle/libs.versions.toml:17`: `1.21.0` → `1.23.2`. Brings 16KB-page support. No API breaks. Build verification needed.

### Deferred (needs research/training)
- **Static-512 (A10)**: Only as prerequisite for NNAPI/QNN accelerator work. Fix C1 correctness bug first.
- **FP16 (A9)**: Needs on-device FP16-microkernel verification.
- **INT8 via QAT (A18)**: Needs training pipeline. Solves A3's quality problem.
- **Model distillation (A16)**: Separate strategic project.

---

## 8. Files To Touch (when implementing)

**P0 (deployment bug):**
- `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxModelStore.kt` (`copyIfNeeded` ~line 187, `looksLikeValidOnnx` ~line 253)

**P1 (spinning-off):**
- `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt` (reorder: `configure` lambda must run before EP branch, OR add `allowSpinning` param)
- `app/src/main/java/eu/kanade/translation/inpainting/AOTInpainting.kt` (line 111: pass spinning-disable config)

**P2 (onnxslim):**
- `app/src/main/assets/models/inpainting/aot.onnx` (offline transform, swap file)

**P3 (ORT upgrade):**
- `gradle/libs.versions.toml` (line 17: version ref)

---

## 9. Method Note

3-round critique loop. Each round: defender (me) verifies claims against live code + model inspection; attack agents (6 total across 3 rounds) find gaps, refute, and refine. Key corrections made:

- **Round 1→2**: XNNPACK default threads corrected (inherits ORT pool, not 1). Model architecture corrected (CNN, not transformer — verified by direct ONNX inspection). The "15–25% gain" from thread-config was downgraded to ~1–5%.
- **Round 2→3**: Two deployment blockers discovered: `copyIfNeeded` (optimized model never reaches users) and `centeredReportCrop` (static-512 crashes on small pages). onnxslim gain reframed (graph cleanliness, not speed — file size only shrinks 0.9%).
- **All rounds**: Test artifacts generated by subagents (9 FP16/static model variants) were cleaned from the repo root. No artifacts committed.

**No code changes made. Report-only as requested.**

---

## Appendix A — Refuted Claims

| Claim | Source | Verdict | Evidence |
|---|---|---|---|
| AOT is a "tokenized transformer" | Task prompt | **REFUTED** | Direct ONNX inspection: 0 MatMul, 0 Attention. Pure CNN (AOT-GAN). |
| NNAPI default threads = 0 (auto) | Web search | **REFUTED** | ORT v1.21 source: XNNPACK inherits ORT pool size when HashMap empty. |
| XNNPACK default = 1 thread | Round 1 critique | **REFUTED** | Same source: falls back to `ort_thread_pool_size`, not 1. |
| Thread-config gives 15–25% gain | Round 1 | **REFUTED** | Sequential executor = pools never compute simultaneously. Gain is spinning-waste only (~1–5%). |
| Static-512 = "no app code change" | Round 2 | **REFUTED** | `centeredReportCrop` produces sub-512 crops on small pages → shape mismatch. |
| onnxslim = 10–25% speed gain | Round 2 | **DOWNGRADED** | File size shrinks 0.9%. Benefit is graph cleanliness. Speed gain unverified without on-device benchmark. |
| IntArray intermediates are fresh per call | Round 1 agent | **REFUTED** | `sharedImgPixels`/`sharedMaskPixels` (lines 63–75) are pooled scratch arrays. Defender caught this. |
