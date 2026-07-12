"""
Convert the dynamic-shape AOT inpainting model (aot.onnx) to a fixed-512 static
variant (aot-512.onnx) and run onnxslim constant folding.

Why: NNAPI rejects or fragments on dynamic-shape graphs and on the redundant
Shape/ReduceProd ops that dynamic shapes generate. A fixed 512x512 input lets
onnxslim fold those into constants, shrinking the graph (1940 -> target ~450
nodes) so NNAPI partitions fewer times and the AOT NPU path becomes viable.

Steps:
  1. Load aot.onnx.
  2. Overwrite input/output shapes: image [1,3,512,512], mask [1,1,512,512],
     inpainted [1,3,512,512].
  3. Run onnx.shape_inference + onnxslim.optimize (constant folding + unused-op
     pruning). onnxslim in static-shape mode folds the dynamic Shape/ReduceProd
     chain.
  4. Save as aot-512.onnx.
  5. Print node counts before/after + a numerics sanity check on one random
     tensor (must be bit-identical because no weights changed).

Usage:
    python tools/aot_conversion/convert_aot_512.py

Inputs:
    app/src/main/assets/models/inpainting/aot.onnx

Output:
    app/src/main/assets/models/inpainting/aot-512.onnx (overwritten)
"""
from __future__ import annotations

import sys
from pathlib import Path

import onnx
import onnxslim
import onnxruntime as ort
import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC = REPO_ROOT / "app/src/main/assets/models/inpainting/aot.onnx"
DST = REPO_ROOT / "app/src/main/assets/models/inpainting/aot-512.onnx"
STATIC_DIMS = {
    "image": [1, 3, 512, 512],
    "mask": [1, 1, 512, 512],
    "inpainted": [1, 3, 512, 512],
}


def set_static_shapes(model: onnx.ModelProto) -> onnx.ModelProto:
    """Overwrite input/output tensor shapes to the fixed 512x512 static dims."""
    for graph_list in (model.graph.input, model.graph.output):
        for value_info in graph_list:
            dims = STATIC_DIMS.get(value_info.name)
            if dims is None:
                continue
            shape = value_info.type.tensor_type.shape
            # Clear existing dims then append literal (dim_value) entries.
            while len(shape.dim) > 0:
                shape.dim.pop()
            for d in dims:
                shape.dim.add().dim_value = d
    return model


def main() -> int:
    if not SRC.exists():
        print(f"ERROR: source model not found: {SRC}", file=sys.stderr)
        return 1

    print(f"Loading {SRC.name} ...")
    model = onnx.load(str(SRC))
    nodes_before = len(model.graph.node)
    print(f"  nodes: {nodes_before}")

    # Step 1: fix shapes.
    print("Setting static 512x512 shapes ...")
    model = set_static_shapes(model)

    # Step 2: shape inference (onnxslim expects a well-formed graph).
    print("Running onnx shape_inference ...")
    model = onnx.shape_inference.infer_shapes(model)

    # Step 3: onnxslim constant folding + pruning. Input shapes are passed so
    # the folder knows the static dims; output shape is already baked into the
    # model by set_static_shapes above. onnxslim's input_shapes is an iterable
    # of "name:d0,d1,d2,d3" strings (see onnxslim.core.input_shape_modification).
    print("Running onnxslim.slim ...")
    slimmed = onnxslim.slim(
        model,
        input_shapes=["image:1,3,512,512", "mask:1,1,512,512"],
    )
    nodes_after = len(slimmed.graph.node)
    print(f"  nodes after slim: {nodes_after} (was {nodes_before}, delta={nodes_after - nodes_before})")

    # Step 4: save.
    print(f"Saving {DST.name} ...")
    onnx.save(slimmed, str(DST))
    print(f"  size: {DST.stat().st_size:,} bytes")

    # Step 5: numerics sanity. Run both models on the same random input and
    # confirm outputs are bit-identical (onnxslim must not change math).
    print("Numerics sanity (random input, max-abs-diff must be 0) ...")
    rng = np.random.default_rng(42)
    img = rng.standard_normal((1, 3, 512, 512)).astype(np.float32)
    mask = rng.integers(0, 2, (1, 1, 512, 512)).astype(np.float32)

    so_dyn = ort.SessionOptions()
    so_dyn.log_severity_level = 3
    # The dynamic model accepts the 512 shape directly since 512 is within its
    # dynamic range; load from source so we compare pre-slim vs post-slim.
    dyn_sess = ort.InferenceSession(str(SRC), so_dyn, providers=["CPUExecutionProvider"])
    slim_sess = ort.InferenceSession(str(DST), so_dyn, providers=["CPUExecutionProvider"])

    out_dyn = dyn_sess.run(None, {"image": img, "mask": mask})[0]
    out_slim = slim_sess.run(None, {"image": img, "mask": mask})[0]
    max_abs_diff = float(np.max(np.abs(out_dyn - out_slim)))
    print(f"  max-abs-diff (dyn vs slim-512): {max_abs_diff}")
    if max_abs_diff != 0.0:
        print("  WARN: non-zero diff — onnxslim changed math. Investigate before shipping.")
        return 2

    # Bonus: check the dynamic model's output at 512 matches the slimmed one,
    # confirming the static shape itself did not change math either.
    print("  max-abs-diff is 0 — conversion is numerically safe.")
    print("DONE.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
