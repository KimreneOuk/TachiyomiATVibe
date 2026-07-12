# AOT Fixed-512 Conversion Report

**Date:** 2026-07-12
**Tool:** onnxslim 0.1.94 + onnx 1.20.1 + onnxruntime (CPU)
**Inputs:** `app/src/main/assets/models/inpainting/aot.onnx` (dynamic, 1940 nodes)
**Output:** `app/src/main/assets/models/inpainting/aot-512.onnx` (static 512², 400 nodes)

## Result

| Metric | Before (aot.onnx) | After (aot-512.onnx, slimmed) |
|---|---|---|
| Graph nodes | 1940 | **400** (-1540, -79%) |
| Input shape | `[batch,3,h,w]` dynamic | `[1,3,512,512]` static |
| Mask shape | `[batch,1,h,w]` dynamic | `[1,1,512,512]` static |
| Output shape | `[batch,3,h,w]` dynamic | `[1,3,512,512]` static |
| File size | 23,068,213 B | 22,854,564 B (-213,649 B) |

## Why this matters

The 1540 folded nodes are the dynamic-shape machinery (Shape, ReduceProd,
Gather, Unsqueeze chains) that NNAPI cannot execute and so forces into CPU
partitions. With static 512² inputs those ops fold to constants at conversion
time, leaving a graph NNAPI can attempt end-to-end. This is the precondition
for Wave 5.3 (NNAPI EP) to be anything other than a partition-stall.

This also corrects the prior `aot-512.onnx` which was a 95-byte shape-relabel
of `aot.onnx` — same 1940 nodes, no folding. That file gave the false
impression that Wave 5.1 Phase 1-A was complete; this conversion is the real
Phase 1-A deliverable.

## Numerics (Tier 2 gate)

Gate criterion (per `MASTER_IMPLEMENTATION_PLAN_2026-07-12.md` Wave 5.1):
**max-abs-diff < 1e-3** between dynamic and static-slimmed outputs on real-range
inputs (image ∈ [0,1], mask ∈ {0,1}).

| Sample | Seed | max-abs-diff |
|---|---|---|
| 0 | 7 | 5.90e-5 |
| 1 | 7 | 5.03e-5 |
| 2 | 7 | 4.93e-5 |
| 3 | 7 | 8.31e-5 |
| 4 | 7 | 6.43e-5 |
| **Worst** | | **8.31e-5** |

Output range is [-1, 1], so worst diff = 0.004% of range — visually irrelevant.
**Gate PASSES.** (An earlier N(0,1) probe showed 3.25e-4, still under gate;
the larger value is float-reordering amplification by the large input
magnitudes, not a model bug.)

The non-zero diff is expected: onnxslim's constant folding reorders floating-
point operations (e.g. folding a Shape→ReduceProd chain that previously ran at
inference time into a baked constant), and float32 reordering is not
associative. The math is equivalent to within float32 precision; this is not a
quality regression.

## Reproducibility

```
pip install onnxslim onnxruntime numpy
python tools/aot_conversion/convert_aot_512.py
```

The script:
1. Loads `aot.onnx`.
2. Overwrites input/output shapes to static 512².
3. Runs `onnx.shape_inference.infer_shapes`.
4. Runs `onnxslim.slim` with `input_shapes=["image:1,3,512,512","mask:1,1,512,512"]`.
5. Saves `aot-512.onnx`.
6. Sanity-checks max-abs-diff on one random input.

## What is NOT proven here

- **Tier 3 corpus gate (20 real pages).** The five synthetic samples above are
  a numerics sanity check, NOT the 20-page corpus the master plan requires
  before wiring the static model into production. That corpus + harness does
  not yet exist; it remains the next gate before Wave 5.2 integration.
- **On-device NNAPI partition behavior.** 400 nodes is necessary but not
  sufficient for clean NNAPI execution — the 4 ConvTranspose ops remain and
  may still force partitions on some drivers. Wave 5.3 + Wave 6 (on-device)
  settle this.
- **Visual quality on real manga.** Synthetic random inputs exercise the math
  path; they do not exercise the dense-text / screentone / color edge cases
  that real pages present. The Tier 3 corpus is the defense.

## Safety

`aot-512.onnx` is staged but NOT wired into `AOTInpainting.inpaint()`. The
production path still loads the dynamic CPU/XNNPACK model. Per the master
plan's safety rule, the static model stays dormant until the Tier 3 corpus
gate and Wave 6 on-device validation pass.
