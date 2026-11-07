#!/usr/bin/env python3
"""Widen the manga-colorization-v2 fp16 ONNX generator to fp32 for the CPU EP.

The published export (Faridzar/manga-colorization-v2-onnx) stores every weight
and constant as float16, which ORT's ARM64 CPU provider executes through its
half-precision fused-conv path; fp32 keeps the graph on the mature fp32 kernels.
Rewrites every FLOAT16 element type — initializers, Constant-node tensors,
graph I/O, value_info, and Cast targets — to FLOAT. Output is bit-for-bit
deterministic for a given source file.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, numpy_helper


def widen_tensor(tensor: onnx.TensorProto) -> bool:
    if tensor.data_type != TensorProto.FLOAT16:
        return False
    widened = numpy_helper.to_array(tensor).astype(np.float32)
    tensor.CopyFrom(numpy_helper.from_array(widened, tensor.name))
    return True


def widen_graph(graph: onnx.GraphProto) -> int:
    widened = sum(widen_tensor(tensor) for tensor in graph.initializer)
    for value_info in (*graph.input, *graph.output, *graph.value_info):
        tensor_type = value_info.type.tensor_type
        if tensor_type.elem_type == TensorProto.FLOAT16:
            tensor_type.elem_type = TensorProto.FLOAT
    for node in graph.node:
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.TENSOR:
                widened += widen_tensor(attribute.t)
            elif attribute.type == onnx.AttributeProto.TENSORS:
                widened += sum(widen_tensor(tensor) for tensor in attribute.tensors)
            elif attribute.type == onnx.AttributeProto.GRAPH:
                widened += widen_graph(attribute.g)
        if node.op_type == "Cast":
            for attribute in node.attribute:
                if attribute.name == "to" and attribute.i == TensorProto.FLOAT16:
                    attribute.i = TensorProto.FLOAT
    return widened


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="path to the fp16 generator .onnx")
    parser.add_argument("--output", type=Path, required=True, help="path of the fp32 .onnx to write")
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    model = onnx.load(str(arguments.source))
    widened = widen_graph(model.graph)
    onnx.checker.check_model(model)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(arguments.output))
    print(f"widened {widened} fp16 tensors -> {arguments.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
