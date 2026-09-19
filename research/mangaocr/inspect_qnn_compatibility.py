#!/usr/bin/env python3
"""Inspect MangaOCR ONNX graphs for Qualcomm QNN/HTP feasibility.

This is an offline pre-flight tool. It never claims that an operator is
supported by a particular QNN/QAIRT build: only a real Android session with
``session.disable_cpu_ep_fallback=1`` can prove full HTP partitioning.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path
from typing import Any, Iterable

try:
    import onnx
except ImportError as exc:  # pragma: no cover
    raise SystemExit("onnx is required: python -m pip install onnx") from exc


MODEL_NAMES = ("encoder", "decoder_init", "decoder_step")
CACHE_NAMES = ("self_k_cache", "self_v_cache", "cross_k_cache", "cross_v_cache")
FLOAT_TYPES = {onnx.TensorProto.FLOAT, onnx.TensorProto.FLOAT16,
               onnx.TensorProto.BFLOAT16, onnx.TensorProto.DOUBLE}


def _shape(value_info: onnx.ValueInfoProto) -> list[Any]:
    result: list[Any] = []
    for dim in value_info.type.tensor_type.shape.dim:
        if dim.HasField("dim_value"):
            result.append(int(dim.dim_value))
        elif dim.HasField("dim_param"):
            result.append(dim.dim_param)
        else:
            result.append("?")
    return result


def _type_name(elem_type: int) -> str:
    return onnx.TensorProto.DataType.Name(elem_type)


def _dims_are_static(values: Iterable[Any]) -> bool:
    return all(isinstance(value, int) and value > 0 for value in values)


def _classification(name: str, ops: collections.Counter[str], inputs: list[dict[str, Any]],
                    outputs: list[dict[str, Any]], node_count: int) -> dict[str, Any]:
    has_int64_io = any(item["dtype"] == "INT64" for item in inputs + outputs)
    has_dynamic = any(any(not isinstance(dim, int) for dim in item["shape"])
                      for item in inputs + outputs)
    has_qdq = bool(ops.get("QuantizeLinear") or ops.get("DequantizeLinear"))
    high_risk = [op for op in ("Erf", "LayerNormalization", "Softmax", "Gather",
                               "Where", "Equal", "LessOrEqual", "Clip", "Cast")
                 if ops.get(op)]
    if name == "encoder":
        label = "PARTIAL"
        rationale = (
            "Static B=1 vision graph with conventional Conv/Reshape/Transpose/elementwise "
            "operations is a plausible HTP partition candidate, but it is FP32, has 21 Erf "
            "nodes and reduction/normalization work, and has no QDQ. Expect a partition or "
            "an FP16/QDQ export before claiming full HTP."
        )
    elif name == "decoder_init":
        label = "PARTIAL"
        rationale = (
            "Static B=1 graph and fixed cache outputs are favorable, but token Gather, "
            "LayerNormalization and Softmax repeat throughout the transformer and the graph "
            "is FP32 with INT64 token input. HTP coverage and tolerance are runtime dependent."
        )
    else:
        label = "UNLIKELY"
        rationale = (
            "Static tensor extents do not make this a static computation: every token step "
            "selects cache rows using runtime position/index values and applies Where/Equal/"
            "LessOrEqual/Cast/Clip logic. These cache paths are likely to partition to CPU or "
            "fail strict HTP creation. Keep decoder_step on CPU unless a device test proves otherwise."
        )
    return {
        "offline_classification": label,
        "device_verdict": "REQUIRES_DEVICE_TEST",
        "rationale": rationale,
        "flags": {"has_dynamic_io_shape": has_dynamic, "has_int64_io": has_int64_io,
                   "has_qdq": has_qdq, "high_risk_ops_present": high_risk,
                   "node_count": node_count},
    }


def inspect_model(path: Path, run_runtime: bool = False) -> dict[str, Any]:
    data = path.read_bytes()
    # The bundled models are self-contained. ``load_model_from_string`` has
    # no ``load_external_data`` keyword on older ONNX releases.
    model = onnx.load_model_from_string(data)
    onnx.checker.check_model(model)
    graph = model.graph
    inputs = [{"name": value.name, "dtype": _type_name(value.type.tensor_type.elem_type),
               "shape": _shape(value)} for value in graph.input]
    outputs = [{"name": value.name, "dtype": _type_name(value.type.tensor_type.elem_type),
                "shape": _shape(value)} for value in graph.output]
    ops = collections.Counter(node.op_type for node in graph.node)
    qdq_nodes = [node.name or f"node_{i}" for i, node in enumerate(graph.node)
                 if node.op_type in {"QuantizeLinear", "DequantizeLinear"}]
    cache_gathers = []
    gather_index_inputs = []
    for node in graph.node:
        if node.op_type != "Gather" or not node.input:
            continue
        if node.input[0] in CACHE_NAMES:
            index = node.input[1] if len(node.input) > 1 else None
            cache_gathers.append({"node": node.name, "source": node.input[0], "index": index})
            if index:
                gather_index_inputs.append(index)
    result: dict[str, Any] = {
        "model": path.stem,
        "path": path.as_posix(),
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "onnx": {"ir_version": model.ir_version,
                 "opsets": [{"domain": item.domain, "version": item.version}
                             for item in model.opset_import],
                 "checker": "PASS"},
        "inputs": inputs,
        "outputs": outputs,
        "node_count": len(graph.node),
        "op_counts": dict(sorted(ops.items())),
        "initializer_count": len(graph.initializer),
        "initializer_types": dict(sorted(collections.Counter(
            _type_name(item.data_type) for item in graph.initializer).items())),
        "static_io_shapes": all(_dims_are_static(item["shape"]) for item in inputs + outputs),
        "float_initializer_count": sum(item.data_type in FLOAT_TYPES for item in graph.initializer),
        "qdq_nodes": qdq_nodes,
        "cache_inputs": [item for item in inputs if item["name"] in CACHE_NAMES],
        "cache_outputs": [item for item in outputs if item["name"] in CACHE_NAMES or "_slice" in item["name"]],
        "cache_gather_paths": cache_gathers,
        "cache_gather_index_inputs": sorted(set(gather_index_inputs)),
    }
    result["classification"] = _classification(path.stem, ops, inputs, outputs, len(graph.node))
    result["host_evidence"] = {"onnx_checker": "PASS", "qnn_sdk_tools": False,
                                "adb": shutil.which("adb") is not None,
                                "onnxruntime_cpu": False, "runtime": None}
    if run_runtime:
        try:
            import numpy as np
            import onnxruntime as ort
            started = time.perf_counter()
            session = ort.InferenceSession(data, providers=["CPUExecutionProvider"])
            feeds: dict[str, Any] = {}
            for value in graph.input:
                shape = _shape(value)
                if not _dims_are_static(shape):
                    raise ValueError(f"dynamic input shape cannot be synthesized: {value.name} {shape}")
                dtype = value.type.tensor_type.elem_type
                np_dtype = {onnx.TensorProto.FLOAT: np.float32,
                            onnx.TensorProto.FLOAT16: np.float16,
                            onnx.TensorProto.INT64: np.int64,
                            onnx.TensorProto.INT32: np.int32}.get(dtype)
                if np_dtype is None:
                    raise ValueError(f"no deterministic smoke dtype for {_type_name(dtype)}")
                feeds[value.name] = np.zeros(shape, dtype=np_dtype)
            values = session.run(None, feeds)
            result["host_evidence"]["onnxruntime_cpu"] = True
            result["host_evidence"]["runtime"] = {
                "providers": session.get_providers(),
                "session_ms": round((time.perf_counter() - started) * 1000, 2),
                "output_shapes": [list(value.shape) for value in values], "status": "PASS",
            }
        except Exception as exc:
            result["host_evidence"]["runtime"] = {"status": "FAIL", "error": repr(exc)}
    return result


def device_validation_commands(model_dir: Path) -> str:
    root = "/data/local/tmp/mangaocr-qnn"
    return f'''# Qualcomm QNN/HTP strict validation (Android arm64-v8a)
# Prerequisites: adb, an ONNX Runtime Android QNN build, and a QAIRT/QNN SDK
# matching the packaged libQnnHtp.so. The laptop cannot run these commands.
set -eu
adb shell mkdir -p {root}/models {root}/out
adb push {model_dir.as_posix()}/encoder.onnx {root}/models/encoder.onnx
adb push {model_dir.as_posix()}/decoder_init.onnx {root}/models/decoder_init.onnx
adb push {model_dir.as_posix()}/decoder_step.onnx {root}/models/decoder_step.onnx

# Export/compile a fixed-shape context with the matching SDK. QNN conversion
# is SDK-specific; use the SDK's qnn-onnx-converter and record its version.
export QNN_SDK_ROOT=/opt/qairt
$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-onnx-converter \\
  --input_network {root}/models/encoder.onnx --output_path {root}/out/encoder.cpp

# In ORT, addQnn({{backend_type:"htp", soc_model:"<device-specific>"}}) and
# addConfigEntry("session.disable_cpu_ep_fallback", "1"). A created session
# plus one real run is required; EP registration alone is not evidence.
adb shell 'getprop ro.board.platform; getprop ro.product.cpu.abi; logcat -c'
adb logcat -v threadtime -s onnxruntime QnnDiagnostics HardwareDiscoveryEngine > qnn-device.log &
echo "Open a MangaOCR page and let all three model sessions create and run."
grep -E 'probeCombo|providers\\(|QNN SetupBackend|strict|context' qnn-device.log || true
# PASS only if each model has a strict session and real inference without CPU fallback.
'''


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="*", type=Path)
    parser.add_argument("--model-dir", type=Path, default=Path("research/models/manga-ocr-mobile"))
    parser.add_argument("--out", type=Path, default=Path("research/results/qnn_compatibility.json"))
    parser.add_argument("--runtime", action="store_true", help="run zero-input ORT CPU smoke tests")
    parser.add_argument("--emit-commands", action="store_true", help="print Android/QNN gate commands")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = args.models or [args.model_dir / f"{name}.onnx" for name in MODEL_NAMES]
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        print("missing model(s): " + ", ".join(missing), file=sys.stderr)
        return 2
    payload = {
        "schema": "tachiyomiat.qnn-compatibility.v1",
        "generated_by": "research/inspect_qnn_compatibility.py",
        "host": {"platform": sys.platform, "python": sys.version.split()[0], "cwd": os.getcwd(),
                 "qnn_tools": False, "adb": shutil.which("adb") is not None},
        "models": [inspect_model(path, run_runtime=args.runtime) for path in paths],
        "limitations": [
            "No QNN/QAIRT SDK, qnn-onnx-converter, qnn-net-run, or adb was found on this host.",
            "ONNX checker and optional ORT CPU smoke results are laptop evidence only.",
            "Only a strict Android QNN HTP session with CPU fallback disabled and real inference proves full HTP execution.",
        ],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2, sort_keys=True))
    if args.emit_commands:
        print("\n" + device_validation_commands(args.model_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
