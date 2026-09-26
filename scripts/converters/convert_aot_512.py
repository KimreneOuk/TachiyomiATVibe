#!/usr/bin/env python3
"""Create a fixed 512x512 AOT graph and fold its static-shape operations."""

from __future__ import annotations

import argparse
from pathlib import Path

import onnx
import onnxslim


STATIC_DIMS = {
    "image": [1, 3, 512, 512],
    "mask": [1, 1, 512, 512],
    "inpainted": [1, 3, 512, 512],
}


def set_static_shapes(model: onnx.ModelProto) -> onnx.ModelProto:
    found: set[str] = set()
    for value_info in (*model.graph.input, *model.graph.output):
        dims = STATIC_DIMS.get(value_info.name)
        if dims is None:
            continue
        found.add(value_info.name)
        shape = value_info.type.tensor_type.shape
        del shape.dim[:]
        for dimension in dims:
            shape.dim.add().dim_value = dimension
    missing = set(STATIC_DIMS) - found
    if missing:
        raise ValueError(
            "upstream AOT graph is missing expected tensors: "
            + ", ".join(sorted(missing))
        )
    return model


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()

    model = set_static_shapes(onnx.load(str(arguments.source)))
    model = onnx.shape_inference.infer_shapes(model)
    slimmed = onnxslim.slim(
        model,
        input_shapes=["image:1,3,512,512", "mask:1,1,512,512"],
    )
    onnx.checker.check_model(slimmed)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_bytes(slimmed.SerializeToString(deterministic=True))
    print(f"Wrote {arguments.output} ({arguments.output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
