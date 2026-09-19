#!/usr/bin/env python3
"""Inspect ONNX graphs for reproducible model/EP research.

The inspector intentionally reports the serialized graph as shipped.  It also
runs ONNX shape inference and records the inferred value_info used for the
node-level shape/dtype summaries.  It has no runtime/provider dependency.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

import onnx
from onnx import TensorProto, shape_inference


def _dim(dim: Any) -> int | str | None:
    if dim.HasField("dim_value"):
        return int(dim.dim_value)
    if dim.HasField("dim_param"):
        return str(dim.dim_param)
    return None


def _shape(type_proto: Any) -> list[int | str | None] | None:
    if not type_proto or type_proto.WhichOneof("value") != "tensor_type":
        return None
    tensor = type_proto.tensor_type
    if not tensor.HasField("shape"):
        return None
    return [_dim(d) for d in tensor.shape.dim]


def _dtype(type_proto: Any) -> str | None:
    if not type_proto or type_proto.WhichOneof("value") != "tensor_type":
        return None
    return TensorProto.DataType.Name(type_proto.tensor_type.elem_type)


def _type_summary(type_proto: Any) -> dict[str, Any]:
    return {"dtype": _dtype(type_proto), "shape": _shape(type_proto)}


def _git_revision(path: Path, repo_root: Path) -> dict[str, Any]:
    rel = path.resolve().relative_to(repo_root.resolve()).as_posix()
    try:
        commit = subprocess.check_output(
            ["git", "-C", str(repo_root), "log", "-1", "--format=%H", "--", rel],
            text=True,
        ).strip()
        subject = subprocess.check_output(
            ["git", "-C", str(repo_root), "log", "-1", "--format=%s", "--", rel],
            text=True,
        ).strip()
        return {"commit": commit or None, "subject": subject or None}
    except (OSError, subprocess.CalledProcessError):
        return {"commit": None, "subject": None}


def _attributes(node: Any) -> dict[str, Any]:
    """Keep scalar/list attributes useful for auditing without large tensors."""
    out: dict[str, Any] = {}
    for attr in node.attribute:
        kind = attr.type
        if kind == onnx.AttributeProto.FLOAT:
            out[attr.name] = attr.f
        elif kind == onnx.AttributeProto.INT:
            out[attr.name] = attr.i
        elif kind == onnx.AttributeProto.STRING:
            out[attr.name] = attr.s.decode("utf-8", "replace")
        elif kind == onnx.AttributeProto.FLOATS:
            out[attr.name] = list(attr.floats)
        elif kind == onnx.AttributeProto.INTS:
            out[attr.name] = list(attr.ints)
        elif kind == onnx.AttributeProto.STRINGS:
            out[attr.name] = [s.decode("utf-8", "replace") for s in attr.strings]
        elif kind == onnx.AttributeProto.TENSOR:
            out[attr.name] = {"tensor_name": attr.t.name, "dims": list(attr.t.dims)}
        else:
            out[attr.name] = f"attribute_type_{kind}"
    return out


def inspect(path: Path, repo_root: Path) -> dict[str, Any]:
    path = path.resolve()
    raw = path.read_bytes()
    model = onnx.load_model(str(path), load_external_data=False)
    inferred = shape_inference.infer_shapes(model)
    graph = inferred.graph

    types: dict[str, Any] = {}
    for value in list(graph.input) + list(graph.output) + list(graph.value_info):
        types[value.name] = value.type

    initializer_summaries = {
        init.name: {
            "dtype": TensorProto.DataType.Name(init.data_type),
            "shape": list(init.dims),
        }
        for init in graph.initializer
    }
    initializer_dtypes = collections.Counter(TensorProto.DataType.Name(i.data_type) for i in graph.initializer)
    initializer_elements = sum(int(__import__("math").prod(i.dims or [1])) for i in graph.initializer)
    initializer_bytes = sum(len(i.raw_data) for i in graph.initializer)
    if not initializer_bytes:
        # Some models use typed repeated fields rather than raw_data.
        initializer_bytes = sum(len(i.SerializeToString()) for i in graph.initializer)

    node_inventory: list[dict[str, Any]] = []
    op_counts: collections.Counter[str] = collections.Counter()
    domain_op_counts: collections.Counter[str] = collections.Counter()

    def input_summary(name: str) -> dict[str, Any]:
        if name in types:
            return _type_summary(types[name])
        return initializer_summaries.get(name, {"dtype": None, "shape": None})

    for index, node in enumerate(graph.node):
        op_counts[node.op_type] += 1
        domain = node.domain or "ai.onnx"
        domain_op_counts[f"{domain}:{node.op_type}"] += 1
        node_inventory.append(
            {
                "index": index,
                "name": node.name,
                "domain": domain,
                "op_type": node.op_type,
                "inputs": [
                    {"name": name, **input_summary(name)} if name else {"name": ""}
                    for name in node.input
                ],
                "outputs": [
                    {"name": name, **_type_summary(types.get(name))} if name else {"name": ""}
                    for name in node.output
                ],
                "attributes": _attributes(node),
            }
        )

    def io_entry(value: Any) -> dict[str, Any]:
        return {"name": value.name, **_type_summary(value.type)}

    def initializer_entry(init: Any) -> dict[str, Any]:
        return {
            "name": init.name,
            "dtype": TensorProto.DataType.Name(init.data_type),
            "shape": list(init.dims),
            "elements": int(__import__("math").prod(init.dims or [1])),
        }

    return {
        "schema_version": 1,
        "source": {
            "path": path.relative_to(repo_root.resolve()).as_posix(),
            "size_bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "git_revision": _git_revision(path, repo_root),
        },
        "onnx": {
            "ir_version": model.ir_version,
            "opset_imports": [{"domain": x.domain or "ai.onnx", "version": x.version} for x in model.opset_import],
            "producer_name": model.producer_name,
            "producer_version": model.producer_version,
            "domain": model.domain,
            "model_version": model.model_version,
            "metadata": {p.key: p.value for p in model.metadata_props},
        },
        "graph": {
            "name": graph.name,
            "node_count": len(graph.node),
            "input_count": len(graph.input),
            "output_count": len(graph.output),
            "value_info_count": len(graph.value_info),
            "initializer_count": len(graph.initializer),
            "sparse_initializer_count": len(graph.sparse_initializer),
            "parameter_element_count": initializer_elements,
            "initializer_serialized_bytes": initializer_bytes,
            "initializer_dtype_counts": dict(sorted(initializer_dtypes.items())),
            "inputs": [io_entry(value) for value in graph.input],
            "outputs": [io_entry(value) for value in graph.output],
        },
        "operators": {
            "counts": dict(sorted(op_counts.items())),
            "domain_counts": dict(sorted(domain_op_counts.items())),
            "nodes": node_inventory,
        },
        "initializers": [initializer_entry(init) for init in graph.initializer],
        "dynamic_dimensions": sorted(
            {
                dim
                for value in list(graph.input) + list(graph.output)
                for dim in (_shape(value.type) or [])
                if isinstance(dim, str)
            }
        ),
        "inspector": {
            "onnx_python_version": onnx.__version__,
            "shape_inference": "onnx.shape_inference.infer_shapes",
            "load_external_data": False,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="+", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    result = {"models": [inspect(path, repo_root) for path in args.models]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
