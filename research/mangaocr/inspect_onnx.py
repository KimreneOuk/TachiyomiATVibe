#!/usr/bin/env python3
"""Inspect the ogkalu/manga-ocr-mobile ONNX bundle.

This is deliberately a read-only inventory tool.  It does not execute the
model and it does not copy model bytes into the result directory.  The JSON
output is intended to be stable enough for later comparisons between model
exports.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

import onnx
from onnx import TensorProto, numpy_helper


ONNX_FILES = ("encoder.onnx", "decoder_init.onnx", "decoder_step.onnx")
OPTIONAL_FILES = ("config.json", "generation_config.json", "tokenizer_config.json", "special_tokens_map.json")


def dim_to_json(dim: Any) -> Any:
    if dim.HasField("dim_value"):
        return int(dim.dim_value)
    if dim.HasField("dim_param"):
        return str(dim.dim_param)
    return "?"


def shape_to_json(value_info: Any) -> list[Any] | None:
    tensor = value_info.type.tensor_type
    if not tensor.HasField("shape"):
        return None
    return [dim_to_json(dim) for dim in tensor.shape.dim]


def type_to_json(value_info: Any) -> dict[str, Any]:
    tensor = value_info.type.tensor_type
    elem = int(tensor.elem_type)
    try:
        elem_name = TensorProto.DataType.Name(elem)
    except ValueError:
        elem_name = f"UNKNOWN_{elem}"
    return {"onnx_type": elem_name, "onnx_type_id": elem}


def value_info_json(value_info: Any) -> dict[str, Any]:
    return {
        "name": value_info.name,
        "shape": shape_to_json(value_info),
        **type_to_json(value_info),
    }


def bytes_for_initializer(tensor: TensorProto) -> int:
    # raw_data is exact for ordinary embedded tensors.  For typed fields,
    # numpy_helper handles packed/string types and gives the best estimate.
    if tensor.raw_data:
        return len(tensor.raw_data)
    try:
        return int(numpy_helper.to_array(tensor).nbytes)
    except Exception:
        return 0


def external_location(tensor: TensorProto) -> str | None:
    for item in tensor.external_data:
        if item.key == "location":
            return item.value
    return None


def initializer_json(tensor: TensorProto) -> dict[str, Any]:
    try:
        dtype = TensorProto.DataType.Name(int(tensor.data_type))
    except ValueError:
        dtype = f"UNKNOWN_{tensor.data_type}"
    dims = [int(x) for x in tensor.dims]
    count = math.prod(dims) if dims else 1
    return {
        "name": tensor.name,
        "dtype": dtype,
        "shape": dims,
        "element_count": int(count),
        "embedded_bytes": bytes_for_initializer(tensor),
        "external_location": external_location(tensor),
        "external_data": bool(tensor.external_data),
    }


def attr_json(attr: Any) -> Any:
    kind = attr.type
    if kind == onnx.AttributeProto.INT:
        return int(attr.i)
    if kind == onnx.AttributeProto.FLOAT:
        return float(attr.f)
    if kind == onnx.AttributeProto.STRING:
        return attr.s.decode("utf-8", errors="replace")
    if kind == onnx.AttributeProto.INTS:
        return [int(x) for x in attr.ints]
    if kind == onnx.AttributeProto.FLOATS:
        return [float(x) for x in attr.floats]
    if kind == onnx.AttributeProto.STRINGS:
        return [x.decode("utf-8", errors="replace") for x in attr.strings]
    if kind == onnx.AttributeProto.TENSOR:
        return {"tensor_name": attr.t.name, "shape": list(attr.t.dims), "dtype": int(attr.t.data_type)}
    return {"type": int(kind)}


def node_json(node: Any) -> dict[str, Any]:
    return {
        "name": node.name,
        "op_type": node.op_type,
        "domain": node.domain or "ai.onnx",
        "inputs": list(node.input),
        "outputs": list(node.output),
        "attributes": {attr.name: attr_json(attr) for attr in node.attribute},
    }


def all_value_infos(graph: Any) -> dict[str, Any]:
    result = {}
    for item in list(graph.input) + list(graph.output) + list(graph.value_info):
        result.setdefault(item.name, item)
    return result


def dynamic_axes(items: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    for item in items:
        axes = [idx for idx, dim in enumerate(item.get("shape") or []) if isinstance(dim, str) or dim == "?"]
        if axes:
            result.append({"name": item["name"], "axes": axes, "shape": item.get("shape")})
    return result


def normalize_name(name: str) -> str:
    return name.lower().replace(".", "_").replace("/", "_")


def kv_evidence(graph: Any) -> dict[str, Any]:
    names = [x.name for x in graph.input] + [x.name for x in graph.output]
    cache_terms = (
        "past_key", "present_key", "past_value", "present_value", "key_cache", "value_cache",
        "kv_cache", "cache", "self_k", "self_v", "cross_k", "cross_v",
    )
    cache_names = [name for name in names if any(term in normalize_name(name) for term in cache_terms)]
    key_value_names = [name for name in names if re.search(r"(^|[_./])(key|value|self_k|self_v|cross_k|cross_v)([_./]|$)", name, flags=re.I)]
    op_counts = Counter(node.op_type for node in graph.node)
    attention_ops = {op: op_counts[op] for op in ("Attention", "MultiHeadAttention", "GroupQueryAttention") if op_counts[op]}
    return {
        "boundary_names": names,
        "cache_like_boundary_names": cache_names,
        "key_value_boundary_names": key_value_names,
        "attention_operator_counts": attention_ops,
        "cache_boundary_detected": bool(cache_names),
        "attention_operator_detected": bool(attention_ops),
    }


def graph_json(path: Path) -> dict[str, Any]:
    model = onnx.load(str(path), load_external_data=False)
    graph = model.graph
    inputs = [value_info_json(x) for x in graph.input if x.name not in {t.name for t in graph.initializer}]
    outputs = [value_info_json(x) for x in graph.output]
    initializers = [initializer_json(x) for x in graph.initializer]
    nodes = [node_json(x) for x in graph.node]
    op_counts = Counter(x["op_type"] for x in nodes)
    domain_counts = Counter(x["domain"] for x in nodes)
    params = sum(x["element_count"] for x in initializers)
    param_bytes = sum(x["embedded_bytes"] for x in initializers)
    infos = all_value_infos(graph)
    all_shapes = [value_info_json(x) for x in infos.values()]
    dynamic = dynamic_axes(inputs + outputs)
    return {
        "path": str(path),
        "file_bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "ir_version": int(model.ir_version),
        "producer_name": model.producer_name,
        "producer_version": model.producer_version,
        "domain": model.domain,
        "model_version": int(model.model_version),
        "opsets": [{"domain": x.domain or "ai.onnx", "version": int(x.version)} for x in model.opset_import],
        "graph_name": graph.name,
        "node_count": len(nodes),
        "initializer_count": len(initializers),
        "parameter_element_count": int(params),
        "parameter_embedded_bytes": int(param_bytes),
        "inputs": inputs,
        "outputs": outputs,
        "dynamic_boundary_axes": dynamic,
        "all_value_info_count": len(all_shapes),
        "initializers": initializers,
        "op_counts": dict(sorted(op_counts.items(), key=lambda kv: (-kv[1], kv[0]))),
        "domain_counts": dict(sorted(domain_counts.items())),
        "nodes": nodes,
        "kv_attention_evidence": kv_evidence(graph),
        "metadata_props": {item.key: item.value for item in model.metadata_props},
    }


def vocab_json(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    raw = path.read_bytes()
    lines = raw.decode("utf-8-sig").splitlines()
    tokens = [line.rstrip("\r\n") for line in lines]
    special = {token: idx for idx, token in enumerate(tokens) if token in {"[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"}}
    return {
        "path": str(path),
        "file_bytes": path.stat().st_size,
        "sha256": hashlib.sha256(raw).hexdigest(),
        "line_count": len(tokens),
        "empty_token_count": sum(token == "" for token in tokens),
        "special_token_indices": special,
        "first_tokens": tokens[:16],
        "last_tokens": tokens[-16:],
        "max_token_codepoints": max((len(token) for token in tokens), default=0),
        "max_token_utf8_bytes": max((len(token.encode("utf-8")) for token in tokens), default=0),
        "unique_token_count": len(set(tokens)),
    }


def file_inventory(model_dir: Path) -> list[dict[str, Any]]:
    known = list(ONNX_FILES) + ["vocab.txt", "README.md"] + list(OPTIONAL_FILES)
    files = []
    for name in known:
        path = model_dir / name
        record = {"name": name, "present": path.is_file()}
        if path.is_file():
            record.update({"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        files.append(record)
    return files


def compact_summary(result: dict[str, Any]) -> dict[str, Any]:
    """Make a review-friendly, machine-readable subset of the full report."""
    graphs = {}
    for name, graph in result["graphs"].items():
        dtype_counts = Counter(item["dtype"] for item in graph["initializers"])
        dtype_bytes = Counter()
        for item in graph["initializers"]:
            dtype_bytes[item["dtype"]] += item["embedded_bytes"]
        layer_numbers = []
        for item in graph["initializers"]:
            match = re.search(r"layers\.(\d+)", item["name"])
            if match:
                layer_numbers.append(int(match.group(1)))
        graphs[name] = {
            "file_bytes": graph["file_bytes"],
            "sha256": graph["sha256"],
            "opsets": graph["opsets"],
            "node_count": graph["node_count"],
            "initializer_count": graph["initializer_count"],
            "parameter_element_count": graph["parameter_element_count"],
            "parameter_embedded_bytes": graph["parameter_embedded_bytes"],
            "initializer_dtype_counts": dict(sorted(dtype_counts.items())),
            "initializer_dtype_bytes": dict(sorted(dtype_bytes.items())),
            "decoder_layer_count_inferred": (max(layer_numbers) + 1) if layer_numbers else None,
            "inputs": graph["inputs"],
            "outputs": graph["outputs"],
            "dynamic_boundary_axes": graph["dynamic_boundary_axes"],
            "op_counts": graph["op_counts"],
            "kv_attention_evidence": graph["kv_attention_evidence"],
        }
    return {
        "tool": result["tool"],
        "tool_version": result["tool_version"],
        "model_dir": result["model_dir"],
        "total_onnx_file_bytes": sum(graph["file_bytes"] for graph in result["graphs"].values()),
        "graphs": graphs,
        "vocab": result["vocab"],
        "config_files_present": result["config_files_present"],
        "errors": result["errors"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=Path(__file__).parent / "models" / "manga-ocr-mobile")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "results" / "onnx_inspection.json")
    args = parser.parse_args()

    model_dir = args.model_dir.resolve()
    output = args.output.resolve()
    graphs: dict[str, Any] = {}
    errors: list[dict[str, str]] = []
    for name in ONNX_FILES:
        path = model_dir / name
        if not path.is_file():
            errors.append({"file": name, "error": "missing"})
            continue
        try:
            graphs[name] = graph_json(path)
        except Exception as exc:  # keep a partial inventory reproducible
            errors.append({"file": name, "error": f"{type(exc).__name__}: {exc}"})

    result = {
        "tool": "research/inspect_onnx.py",
        "tool_version": 1,
        "model_dir": str(model_dir),
        "inventory": file_inventory(model_dir),
        "vocab": vocab_json(model_dir / "vocab.txt"),
        "graphs": graphs,
        "errors": errors,
        "config_files_present": [name for name in OPTIONAL_FILES if (model_dir / name).is_file()],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    summary_path = output.with_name("graph_summary.json")
    summary_path.write_text(json.dumps(compact_summary(result), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "summary": str(summary_path), "graphs": list(graphs), "errors": errors}, indent=2))
    return 0 if not errors else 2


if __name__ == "__main__":
    sys.exit(main())
