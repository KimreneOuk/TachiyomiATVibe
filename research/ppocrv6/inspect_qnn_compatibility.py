#!/usr/bin/env python3
"""Offline ONNX/QNN-Hardware-Accelerator compatibility inventory.

This tool deliberately does not claim device execution.  It inspects ONNX
graphs, records dynamic dimensions and quantization forms, and compares ops
against the operator names documented by ONNX Runtime's QNN EP page.  The
output is intended to be an auditable pre-flight report and a starting point
for an Android HTP run with ``session.disable_cpu_ep_fallback=1``.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import onnx
from onnx import TensorProto

try:
    import onnxruntime
except ImportError:  # inventory remains useful without ORT installed
    onnxruntime = None


# ONNX Runtime QNN EP's public operator inventory (ai.onnx names) as of the
# current documentation.  This is deliberately conservative: an op being in
# the inventory does not prove that this model's attributes, rank, dtype, or
# QNN SDK version are accepted by HTP.
QNN_DOC_OPS = {
    "Abs", "Add", "ArgMax", "ArgMin", "AveragePool", "BatchNormalization",
    "Cast", "Clip", "Concat", "Constant", "ConstantOfShape", "Conv",
    "ConvTranspose", "DequantizeLinear", "Div", "Dropout", "Equal",
    "Expand", "Flatten", "Gather", "GatherElements", "GatherND", "Gelu",
    "GlobalAveragePool", "GlobalMaxPool", "Greater", "GreaterOrEqual",
    "Identity", "If", "LayerNormalization", "LeakyRelu", "Log", "LogSoftmax",
    "LpNormalization", "MatMul", "Max", "MaxPool", "Min", "Mul", "Neg",
    "NonZero", "Not", "Or", "Pad", "Pow", "Prelu", "QuantizeLinear",
    "ReduceMax", "ReduceMean", "ReduceMin", "ReduceProd", "ReduceSum",
    "Relu", "Reshape", "Resize", "Round", "Shape", "Sigmoid", "Sign",
    "Slice", "Softmax", "SpaceToDepth", "Split", "Sqrt", "Squeeze", "Sub",
    "Tanh", "Tile", "TopK", "Transpose", "Unsqueeze", "Where",
}

# These constructs are either explicitly called out as not supported by QNN
# EP or are commonly shape/control-flow blockers for HTP partitioning.
KNOWN_QNN_BLOCKERS = {"Loop", "If", "Scan", "Optional", "SequenceConstruct"}


def _dim(d: onnx.TensorShapeProto.Dimension) -> int | str:
    if d.HasField("dim_value"):
        return int(d.dim_value)
    if d.HasField("dim_param"):
        return d.dim_param
    return "?"


def _shape(value_info: onnx.ValueInfoProto) -> list[int | str] | None:
    tensor = value_info.type.tensor_type
    if not tensor.HasField("shape"):
        return None
    return [_dim(d) for d in tensor.shape.dim]


def _dtype(value_info: onnx.ValueInfoProto) -> str | None:
    tensor = value_info.type.tensor_type
    if not tensor.HasField("elem_type"):
        return None
    try:
        return TensorProto.DataType.Name(tensor.elem_type)
    except ValueError:
        return str(tensor.elem_type)


def _is_dynamic(shape: Iterable[int | str] | None) -> bool:
    return bool(shape) and any(not isinstance(d, int) or d <= 0 for d in shape)


def _attribute_value(attr: onnx.AttributeProto) -> Any:
    # Keep output compact, while retaining useful QNN-relevant attributes.
    if attr.type == onnx.AttributeProto.INT:
        return int(attr.i)
    if attr.type == onnx.AttributeProto.FLOAT:
        return float(attr.f)
    if attr.type == onnx.AttributeProto.INTS:
        return list(attr.ints)
    if attr.type == onnx.AttributeProto.FLOATS:
        return list(attr.floats)
    if attr.type == onnx.AttributeProto.STRING:
        return attr.s.decode("utf-8", errors="replace")
    return {"type": onnx.AttributeProto.AttributeType.Name(attr.type)}


def _input_shapes(model: onnx.ModelProto) -> list[dict[str, Any]]:
    return [
        {"name": x.name, "dtype": _dtype(x), "shape": _shape(x), "dynamic": _is_dynamic(_shape(x))}
        for x in model.graph.input
    ]


def _output_shapes(model: onnx.ModelProto) -> list[dict[str, Any]]:
    return [
        {"name": x.name, "dtype": _dtype(x), "shape": _shape(x), "dynamic": _is_dynamic(_shape(x))}
        for x in model.graph.output
    ]


def _collect_value_info(model: onnx.ModelProto) -> dict[str, dict[str, Any]]:
    values: dict[str, dict[str, Any]] = {}
    for info in list(model.graph.input) + list(model.graph.output) + list(model.graph.value_info):
        values[info.name] = {"dtype": _dtype(info), "shape": _shape(info)}
    return values


def _graph_ops(model: onnx.ModelProto) -> dict[str, Any]:
    domain_counts = Counter((node.domain or "ai.onnx", node.op_type) for node in model.graph.node)
    op_counts = Counter(node.op_type for node in model.graph.node)
    unsupported = sorted({op for op in op_counts if op not in QNN_DOC_OPS})
    blockers = sorted({op for op in op_counts if op in KNOWN_QNN_BLOCKERS})
    node_details = []
    for node in model.graph.node:
        attrs = {a.name: _attribute_value(a) for a in node.attribute}
        node_details.append({
            "name": node.name,
            "op_type": node.op_type,
            "domain": node.domain or "ai.onnx",
            "inputs": list(node.input),
            "outputs": list(node.output),
            "attributes": attrs,
        })
    return {
        "node_count": len(model.graph.node),
        "op_counts": dict(sorted(op_counts.items())),
        "domain_op_counts": {
            f"{domain}:{op}": count for (domain, op), count in sorted(domain_counts.items())
        },
        "qnn_doc_unknown_ops": unsupported,
        "known_qnn_blockers": blockers,
        "nodes": node_details,
    }


def _initializer_stats(model: onnx.ModelProto) -> dict[str, Any]:
    dtypes = Counter()
    total_bytes = 0
    for tensor in model.graph.initializer:
        try:
            dtype = TensorProto.DataType.Name(tensor.data_type)
        except ValueError:
            dtype = str(tensor.data_type)
        dtypes[dtype] += 1
        total_bytes += len(tensor.raw_data)
        if tensor.data_location == TensorProto.EXTERNAL:
            # raw_data is normally empty for external tensors.
            total_bytes += sum(len(e.value) for e in tensor.external_data)
    return {
        "count": len(model.graph.initializer),
        "dtype_counts": dict(sorted(dtypes.items())),
        "approx_embedded_bytes": total_bytes,
        "external_data_count": sum(
            1 for tensor in model.graph.initializer if tensor.data_location == TensorProto.EXTERNAL
        ),
    }


def _quantization_profile(model: onnx.ModelProto) -> dict[str, Any]:
    op_counts = Counter(node.op_type for node in model.graph.node)
    qdq = op_counts.get("QuantizeLinear", 0) + op_counts.get("DequantizeLinear", 0)
    weight_dtypes = Counter()
    for tensor in model.graph.initializer:
        if tensor.name.lower().endswith(("_scale", "_zero_point")):
            try:
                weight_dtypes[TensorProto.DataType.Name(tensor.data_type)] += 1
            except ValueError:
                weight_dtypes[str(tensor.data_type)] += 1
    return {
        "has_qdq_nodes": qdq > 0,
        "quantize_linear_nodes": op_counts.get("QuantizeLinear", 0),
        "dequantize_linear_nodes": op_counts.get("DequantizeLinear", 0),
        "quantization_annotation_count": len(model.graph.quantization_annotation),
        "scale_zero_point_initializer_dtypes": dict(sorted(weight_dtypes.items())),
        "htp_float_model_note": (
            "QNN HTP requires a quantized graph for the normal ORT QNN workflow; "
            "FP16 may be a QNN/SDK/device-specific exception and must be tested."
        ),
    }


def inspect_model(path: Path) -> dict[str, Any]:
    model = onnx.load(str(path), load_external_data=False)
    try:
        onnx.checker.check_model(model)
        checker = {"status": "CONFIRMED", "error": None}
    except Exception as exc:
        checker = {"status": "FAILED", "error": f"{type(exc).__name__}: {exc}"}
    shapes = _input_shapes(model) + _output_shapes(model)
    dynamic_inputs = [x for x in _input_shapes(model) if x["dynamic"]]
    metadata = {p.key: p.value for p in model.metadata_props}
    return {
        "path": str(path),
        "size_bytes": path.stat().st_size,
        "sha256": __import__("hashlib").sha256(path.read_bytes()).hexdigest(),
        "onnx": {
            "checker": checker,
            "ir_version": model.ir_version,
            "producer_name": model.producer_name,
            "producer_version": model.producer_version,
            "domain": model.domain,
            "model_version": model.model_version,
            "opsets": {x.domain or "ai.onnx": x.version for x in model.opset_import},
            "metadata": metadata,
        },
        "inputs": _input_shapes(model),
        "outputs": _output_shapes(model),
        "dynamic_input_count": len(dynamic_inputs),
        "all_graph_io_shapes": shapes,
        "graph": _graph_ops(model),
        "initializers": _initializer_stats(model),
        "quantization": _quantization_profile(model),
        "offline_assessment": {
            "dynamic_shapes": "DEVICE TEST / FIXED SHAPE REQUIRED" if dynamic_inputs else "LIKELY",
            "operator_inventory": "DEVICE TEST" if not _graph_ops(model)["qnn_doc_unknown_ops"] else "PARTIAL",
            "whole_graph_htp": (
                "UNSUPPORTED without model rewrite" if _graph_ops(model)["known_qnn_blockers"]
                else "DEVICE TEST"
            ),
            "confidence": "UNTESTED",
        },
    }


def _tool_info() -> dict[str, Any]:
    info: dict[str, Any] = {
        "python": sys.version,
        "platform": platform.platform(),
        "onnx_package": getattr(onnx, "__version__", "unknown"),
        "onnxruntime_package": getattr(onnxruntime, "__version__", None),
        "onnxruntime_providers": (
            onnxruntime.get_available_providers() if onnxruntime is not None else []
        ),
        "adb_on_path": shutil.which("adb"),
        "qnn_tools_on_path": {name: shutil.which(name) for name in (
            "qnn-onnx-converter", "qnn-context-binary-generator", "qnn-net-run", "qnn-profile-viewer"
        )},
    }
    if info["adb_on_path"]:
        try:
            info["adb_version"] = subprocess.run(
                ["adb", "version"], capture_output=True, text=True, check=False, timeout=5
            ).stdout.strip()
            info["adb_devices"] = subprocess.run(
                ["adb", "devices", "-l"], capture_output=True, text=True, check=False, timeout=5
            ).stdout.strip()
        except (OSError, subprocess.SubprocessError) as exc:
            info["adb_error"] = repr(exc)
    return info


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="*", type=Path, help="ONNX models to inspect")
    parser.add_argument("--root", type=Path, default=Path("app/src/main/assets/models"), help="Search root when no models are given")
    parser.add_argument("--output", type=Path, default=Path("research/qnn_compatibility_raw.json"))
    args = parser.parse_args()

    models = args.models or sorted(args.root.rglob("*.onnx"))
    records = []
    for path in models:
        if not path.is_file():
            raise SystemExit(f"model does not exist: {path}")
        try:
            records.append(inspect_model(path))
        except Exception as exc:  # keep a machine-readable failure record
            records.append({"path": str(path), "inspection_error": f"{type(exc).__name__}: {exc}"})
    report = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "scope": "offline ONNX inventory; no QNN SDK or Android HTP execution claimed",
        "qnn_documented_operator_inventory": sorted(QNN_DOC_OPS),
        "known_qnn_blockers": sorted(KNOWN_QNN_BLOCKERS),
        "tooling": _tool_info(),
        "models": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {args.output} ({len(records)} model(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
