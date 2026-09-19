"""OpenVINO device/partitioning probe for the mobile MangaOCR ONNX split.

This is a read-only research harness.  It never modifies application sources or
the downloaded model checkout.  Device/model probes run in child processes so
that a vendor plugin crash or deadlock becomes an explicit result row.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import pathlib
import statistics
import subprocess
import sys
import time
from typing import Any

import numpy as np


ROOT = pathlib.Path(__file__).resolve().parent
MODEL_DIR = ROOT / "models" / "manga-ocr-mobile"
RESULT_DIR = ROOT / "results"
MODELS = ("encoder", "decoder_init", "decoder_step")


def _json_safe(value: Any) -> Any:
    if isinstance(value, (np.integer, np.floating)):
        return value.item()
    if isinstance(value, np.ndarray):
        return {"shape": list(value.shape), "dtype": str(value.dtype)}
    if isinstance(value, pathlib.Path):
        return str(value)
    raise TypeError(type(value).__name__)


def _summary(values: list[float]) -> dict[str, float]:
    values = sorted(float(v) for v in values)
    if not values:
        return {}
    return {
        "count": len(values),
        "min_ms": values[0],
        "mean_ms": statistics.fmean(values),
        "median_ms": statistics.median(values),
        "p95_ms": values[min(len(values) - 1, math.ceil(len(values) * 0.95) - 1)],
        "max_ms": values[-1],
    }


def _model_path(model: str) -> pathlib.Path:
    return MODEL_DIR / f"{model}.onnx"


def _inputs(model: str, seed: int = 1234) -> dict[str, np.ndarray]:
    rng = np.random.default_rng(seed)
    hidden = rng.standard_normal((1, 196, 256), dtype=np.float32)
    if model == "encoder":
        return {"serving_default_args_0:0": rng.standard_normal((1, 3, 224, 224), dtype=np.float32)}
    if model == "decoder_init":
        return {
            "encoder_hidden_states": hidden,
            "input_ids": np.zeros((1, 1), dtype=np.int64),
        }
    if model == "decoder_step":
        return {
            "encoder_hidden_states": hidden,
            "input_ids": np.ones((1, 1), dtype=np.int64),
            "position_ids": np.ones((1, 1), dtype=np.int64),
            "self_k_cache": np.zeros((4, 1, 4, 256, 64), dtype=np.float32),
            "self_v_cache": np.zeros((4, 1, 4, 256, 64), dtype=np.float32),
            "cross_k_cache": np.zeros((4, 1, 4, 196, 64), dtype=np.float32),
            "cross_v_cache": np.zeros((4, 1, 4, 196, 64), dtype=np.float32),
        }
    raise ValueError(model)


def _device_properties(core: Any, device: str) -> dict[str, Any]:
    properties: dict[str, Any] = {}
    for name in ("FULL_DEVICE_NAME", "DEVICE_ID", "SUPPORTED_PROPERTIES"):
        try:
            value = core.get_property(device, name)
            if isinstance(value, (tuple, list)):
                value = list(value)
            properties[name] = value
        except Exception as exc:  # plugin-specific property gaps are evidence
            properties[name] = {"error": f"{type(exc).__name__}: {exc}"}
    return properties


def child_probe(model: str, device: str, warmup: int, iterations: int) -> dict[str, Any]:
    import openvino as ov

    started = time.perf_counter()
    core = ov.Core()
    result: dict[str, Any] = {
        "model": model,
        "device": device,
        "available_devices": list(core.available_devices),
        "device_properties": _device_properties(core, device),
        "evidence": "OBSERVED",
    }
    model_obj = core.read_model(str(_model_path(model)))
    result["op_count"] = len(model_obj.get_ordered_ops())

    try:
        query = core.query_model(model_obj, device)
        result["query_model"] = {
            "supported_op_count": len(query),
            "unsupported_op_count": max(0, result["op_count"] - len(query)),
            "unsupported_ops": [
                op.get_friendly_name() for op in model_obj.get_ordered_ops() if op.get_friendly_name() not in query
            ],
        }
    except Exception as exc:
        result["query_model_error"] = f"{type(exc).__name__}: {exc}"

    compile_started = time.perf_counter()
    compiled = core.compile_model(model_obj, device)
    result["compile_ms"] = (time.perf_counter() - compile_started) * 1000.0
    try:
        result["compiled_execution_devices"] = list(compiled.get_property("EXECUTION_DEVICES"))
    except Exception as exc:
        result["compiled_execution_devices_error"] = f"{type(exc).__name__}: {exc}"

    inputs = _inputs(model)
    request = compiled.create_infer_request()
    for _ in range(warmup):
        request.infer(inputs)

    latencies: list[float] = []
    for _ in range(iterations):
        started_infer = time.perf_counter()
        outputs = request.infer(inputs)
        latencies.append((time.perf_counter() - started_infer) * 1000.0)
    result["infer"] = _summary(latencies)
    result["output_shapes"] = {str(k): list(v.shape) for k, v in outputs.items()}
    result["output_finite"] = all(bool(np.isfinite(v).all()) for v in outputs.values())
    result["total_ms"] = (time.perf_counter() - started) * 1000.0
    return result


def child_dispatch(device: str, steps: int, warmup: int, iterations: int) -> dict[str, Any]:
    """Measure repeated decoder-step calls, including host dispatch per call."""
    import openvino as ov

    core = ov.Core()
    model_obj = core.read_model(str(_model_path("decoder_step")))
    compiled = core.compile_model(model_obj, device)
    inputs = _inputs("decoder_step")
    request = compiled.create_infer_request()
    for _ in range(warmup):
        request.infer(inputs)
    totals: list[float] = []
    for _ in range(iterations):
        started = time.perf_counter()
        for _ in range(steps):
            request.infer(inputs)
        totals.append((time.perf_counter() - started) * 1000.0)
    summary = _summary(totals)
    summary["steps"] = steps
    summary["per_step_mean_ms"] = summary["mean_ms"] / steps
    return {
        "device": device,
        "model": "decoder_step",
        "kind": "dispatch_loop",
        "steps": steps,
        "iterations": iterations,
        "loop": summary,
        "evidence": "OBSERVED",
    }


def child_hybrid(encoder_device: str, decoder_device: str, steps: int, warmup: int, iterations: int) -> dict[str, Any]:
    """Run encoder once, decoder init once, then decoder steps on chosen devices."""
    import openvino as ov

    core = ov.Core()
    encoder = core.compile_model(core.read_model(str(_model_path("encoder"))), encoder_device)
    decoder_init = core.compile_model(core.read_model(str(_model_path("decoder_init"))), decoder_device)
    decoder_step = core.compile_model(core.read_model(str(_model_path("decoder_step"))), decoder_device)
    encoder_request = encoder.create_infer_request()
    init_request = decoder_init.create_infer_request()
    step_request = decoder_step.create_infer_request()
    encoder_inputs = _inputs("encoder")
    init_inputs = _inputs("decoder_init")
    step_inputs = _inputs("decoder_step")
    # Keep the stage contract explicit while avoiding dependence on output-name order.
    for _ in range(warmup):
        encoder_request.infer(encoder_inputs)
        init_request.infer(init_inputs)
        for _ in range(steps):
            step_request.infer(step_inputs)
    totals: list[float] = []
    for _ in range(iterations):
        started = time.perf_counter()
        encoder_request.infer(encoder_inputs)
        init_request.infer(init_inputs)
        for _ in range(steps):
            step_request.infer(step_inputs)
        totals.append((time.perf_counter() - started) * 1000.0)
    summary = _summary(totals)
    summary["steps"] = steps
    summary["per_step_pipeline_ms"] = summary["mean_ms"] / steps
    return {
        "encoder_device": encoder_device,
        "decoder_device": decoder_device,
        "steps": steps,
        "iterations": iterations,
        "pipeline": summary,
        "evidence": "OBSERVED",
    }


def _run_child(args: argparse.Namespace) -> int:
    try:
        if args.child_kind == "probe":
            result = child_probe(args.model, args.device, args.warmup, args.iterations)
        elif args.child_kind == "dispatch":
            result = child_dispatch(args.device, args.steps, args.warmup, args.iterations)
        elif args.child_kind == "hybrid":
            result = child_hybrid(args.encoder_device, args.decoder_device, args.steps, args.warmup, args.iterations)
        else:
            raise ValueError(args.child_kind)
        print(json.dumps(result, default=_json_safe), flush=True)
        return 0
    except Exception as exc:
        print(json.dumps({"evidence": "OBSERVED", "error_type": type(exc).__name__, "error": str(exc)}), flush=True)
        return 1


def _invoke_child(extra: list[str], timeout_s: int) -> dict[str, Any]:
    command = [sys.executable, "-u", str(pathlib.Path(__file__).resolve()), "--child", *extra]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout_s)
    except subprocess.TimeoutExpired as exc:
        return {
            "evidence": "BLOCKED",
            "status": "timeout",
            "command": command,
            "timeout_s": timeout_s,
            "stdout": (exc.stdout or "")[-4000:],
            "stderr": (exc.stderr or "")[-4000:],
        }
    stdout = completed.stdout.strip()
    parsed: dict[str, Any] | None = None
    if stdout:
        try:
            parsed = json.loads(stdout.splitlines()[-1])
        except json.JSONDecodeError:
            pass
    if parsed is None:
        parsed = {"evidence": "BLOCKED", "status": "child_no_json"}
    parsed.update(
        {
            "returncode": completed.returncode,
            "child_stdout": stdout[-4000:],
            "child_stderr": completed.stderr[-4000:],
        }
    )
    if completed.returncode != 0 and "error" not in parsed:
        parsed["evidence"] = "BLOCKED"
        parsed["status"] = "child_exit"
    return parsed


def _inventory() -> dict[str, Any]:
    result: dict[str, Any] = {"evidence": "OBSERVED"}
    try:
        import openvino as ov

        core = ov.Core()
        result["openvino_version"] = ov.__version__
        result["available_devices"] = list(core.available_devices)
        result["devices"] = {device: _device_properties(core, device) for device in core.available_devices}
    except Exception as exc:
        result.update({"evidence": "BLOCKED", "error_type": type(exc).__name__, "error": str(exc)})
    try:
        import onnxruntime as ort

        result["onnxruntime_version"] = ort.__version__
        result["onnxruntime_providers"] = ort.get_available_providers()
        result["onnxruntime_openvino_ep"] = "OpenVINOExecutionProvider" in ort.get_available_providers()
    except Exception as exc:
        result["onnxruntime_error"] = f"{type(exc).__name__}: {exc}"
    return result


def _requested_devices(inventory: dict[str, Any]) -> list[str]:
    devices = list(inventory.get("available_devices", []))
    requested = list(dict.fromkeys(devices + ["NPU", "AUTO"]))
    if "GPU" in devices or len(devices) > 1:
        requested.append("HETERO:GPU,CPU")
    return list(dict.fromkeys(requested))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--child", action="store_true")
    parser.add_argument("--child-kind", choices=("probe", "dispatch", "hybrid"))
    parser.add_argument("--model", choices=MODELS)
    parser.add_argument("--device")
    parser.add_argument("--encoder-device")
    parser.add_argument("--decoder-device")
    parser.add_argument("--steps", type=int, default=8)
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=10)
    parser.add_argument("--timeout-s", type=int, default=180)
    args = parser.parse_args()

    if args.child:
        return _run_child(args)

    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    inventory = _inventory()
    timeout_s = args.timeout_s
    probe_results: list[dict[str, Any]] = []
    for model in MODELS:
        for device in _requested_devices(inventory):
            result = _invoke_child(
                [
                    "--child-kind",
                    "probe",
                    "--model",
                    model,
                    "--device",
                    device,
                    "--warmup",
                    str(args.warmup),
                    "--iterations",
                    str(args.iterations),
                ],
                timeout_s,
            )
            result.update({"model": model, "device": device})
            probe_results.append(result)

    successful_devices = {
        (row.get("model"), row.get("device"))
        for row in probe_results
        if row.get("returncode") == 0 and row.get("infer", {}).get("count")
    }
    dispatch_results: list[dict[str, Any]] = []
    for device in _requested_devices(inventory):
        if ("decoder_step", device) in successful_devices:
            for steps in (1, 4, 16):
                row = _invoke_child(
                    [
                        "--child-kind",
                        "dispatch",
                        "--device",
                        device,
                        "--steps",
                        str(steps),
                        "--warmup",
                        str(args.warmup),
                        "--iterations",
                        str(max(3, args.iterations // 2)),
                    ],
                    timeout_s,
                )
                row.update({"device": device, "steps": steps})
                dispatch_results.append(row)

    hybrid_results: list[dict[str, Any]] = []
    enc_devices = [d for m, d in successful_devices if m == "encoder"]
    dec_devices = [d for m, d in successful_devices if m == "decoder_step" and ("decoder_init", d) in successful_devices]
    for encoder_device in dict.fromkeys(enc_devices):
        for decoder_device in dict.fromkeys(dec_devices):
            row = _invoke_child(
                [
                    "--child-kind",
                    "hybrid",
                    "--encoder-device",
                    encoder_device,
                    "--decoder-device",
                    decoder_device,
                    "--steps",
                    str(args.steps),
                    "--warmup",
                    str(args.warmup),
                    "--iterations",
                    str(args.iterations),
                ],
                timeout_s,
            )
            row.update({"encoder_device": encoder_device, "decoder_device": decoder_device})
            hybrid_results.append(row)

    payload = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "script": str(pathlib.Path(__file__).resolve()),
        "models": {model: str(_model_path(model)) for model in MODELS},
        "inventory": inventory,
        "probes": probe_results,
        "dispatch": dispatch_results,
        "hybrids": hybrid_results,
        "parameters": {
            "warmup": args.warmup,
            "iterations": args.iterations,
            "hybrid_steps": args.steps,
            "timeout_s": timeout_s,
        },
    }
    json_path = RESULT_DIR / "openvino_probe.json"
    json_path.write_text(json.dumps(payload, indent=2, default=_json_safe), encoding="utf-8")

    csv_path = RESULT_DIR / "openvino_benchmark.csv"
    rows: list[dict[str, Any]] = []
    for row in probe_results:
        flat = {"kind": "stage", "model": row.get("model"), "device": row.get("device"), "evidence": row.get("evidence"), "returncode": row.get("returncode")}
        flat.update({f"infer_{k}": v for k, v in row.get("infer", {}).items()})
        flat["compile_ms"] = row.get("compile_ms")
        flat["unsupported_op_count"] = row.get("query_model", {}).get("unsupported_op_count")
        rows.append(flat)
    for row in dispatch_results:
        flat = {"kind": "dispatch_loop", "model": "decoder_step", "device": row.get("device"), "steps": row.get("steps"), "evidence": row.get("evidence"), "returncode": row.get("returncode")}
        flat.update({f"loop_{k}": v for k, v in row.get("loop", {}).items()})
        rows.append(flat)
    for row in hybrid_results:
        flat = {"kind": "hybrid", "encoder_device": row.get("encoder_device"), "decoder_device": row.get("decoder_device"), "evidence": row.get("evidence"), "returncode": row.get("returncode")}
        flat.update({f"pipeline_{k}": v for k, v in row.get("pipeline", {}).items()})
        rows.append(flat)
    fieldnames = sorted({key for row in rows for key in row})
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({"inventory": inventory, "json": str(json_path), "csv": str(csv_path), "probe_count": len(probe_results), "dispatch_count": len(dispatch_results), "hybrid_count": len(hybrid_results)}, indent=2, default=_json_safe))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
