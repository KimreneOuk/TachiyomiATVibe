#!/usr/bin/env python3
"""Connected OpenVINO pipeline probe for the pristine MangaOCR split.

Unlike ``test_openvino.py`` this harness carries real stage tensors through the
pipeline: encoder -> decoder_init -> greedy decoder_step, including self KV
cache updates between steps.  Each provider run is isolated in a child process
so a plugin compile/infer failure becomes a recorded result row.

This is research-only.  It never changes app sources or model assets.
"""

from __future__ import annotations

import argparse
import json
import math
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
BOS = 2
EOS = 3
MAX_FED_POSITION = 127
NUM_LAYERS = 4
NUM_HEADS = 4
CACHE_WINDOW = 256
HEAD_DIM = 64
LOGIT_MAX_ABS_TOL = 5e-3
LOGIT_MEAN_ABS_TOL = 1e-4


def _safe(value: Any) -> Any:
    if isinstance(value, (np.integer, np.floating)):
        return value.item()
    if isinstance(value, np.ndarray):
        return {"shape": list(value.shape), "dtype": str(value.dtype)}
    if isinstance(value, pathlib.Path):
        return str(value)
    raise TypeError(type(value).__name__)


def _summary(values: list[float]) -> dict[str, float]:
    ordered = sorted(float(v) for v in values)
    if not ordered:
        return {}
    return {
        "count": len(ordered),
        "min_ms": ordered[0],
        "mean_ms": statistics.fmean(ordered),
        "median_ms": statistics.median(ordered),
        "p95_ms": ordered[min(len(ordered) - 1, math.ceil(len(ordered) * 0.95) - 1)],
        "max_ms": ordered[-1],
    }


def _model_path(model: str) -> pathlib.Path:
    return MODEL_DIR / f"{model}.onnx"


def _reference_sessions() -> dict[str, Any]:
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.log_severity_level = 3
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return {
        model: ort.InferenceSession(str(_model_path(model)), options, providers=["CPUExecutionProvider"])
        for model in MODELS
    }


def _synthetic_inputs(seed: int, count: int) -> list[tuple[str, np.ndarray]]:
    """Deterministic app-shaped tensors; these are not OCR ground truth."""
    rng = np.random.default_rng(seed)
    return [(f"synthetic:{index}", rng.standard_normal((1, 3, 224, 224), dtype=np.float32)) for index in range(count)]


def _ort_run(session: Any, values: dict[str, np.ndarray]) -> list[np.ndarray]:
    return [np.asarray(value) for value in session.run(None, values)]


def _reference_decode(sessions: dict[str, Any], pixels: np.ndarray, max_steps: int) -> dict[str, Any]:
    """Pristine ORT CPU reference using the graph's corrected autoregressive protocol."""
    hidden = _ort_run(sessions["encoder"], {"serving_default_args_0:0": pixels})[0]
    init = _ort_run(sessions["decoder_init"], {
        "encoder_hidden_states": hidden,
        "input_ids": np.array([[BOS]], dtype=np.int64),
    })
    logits0, self_k0, self_v0, cross_k, cross_v = init
    self_k = np.zeros((NUM_LAYERS, 1, NUM_HEADS, CACHE_WINDOW, HEAD_DIM), dtype=np.float32)
    self_v = np.zeros_like(self_k)
    self_k[:, :, :, 0, :] = self_k0[:, :, :, 0, :]
    self_v[:, :, :, 0, :] = self_v0[:, :, :, 0, :]
    logits = [np.asarray(logits0[0]).copy()]
    tokens: list[int] = []
    current = int(np.argmax(logits0[0]))
    positions: list[int] = [1]
    if current != EOS:
        tokens.append(current)
        for position in range(2, min(MAX_FED_POSITION, 1 + max_steps) + 1):
            step = _ort_run(sessions["decoder_step"], {
                "encoder_hidden_states": hidden,
                "input_ids": np.array([[current]], dtype=np.int64),
                "position_ids": np.array([[position]], dtype=np.int64),
                "self_k_cache": self_k,
                "self_v_cache": self_v,
                "cross_k_cache": cross_k,
                "cross_v_cache": cross_v,
            })
            step_logits, step_k, step_v = step
            self_k[:, :, :, position - 1, :] = step_k[:, :, :, 0, :]
            self_v[:, :, :, position - 1, :] = step_v[:, :, :, 0, :]
            logits.append(np.asarray(step_logits[0]).copy())
            positions.append(position)
            current = int(np.argmax(step_logits[0]))
            if current == EOS:
                break
            tokens.append(current)
    return {
        "tokens": tokens,
        "positions": positions,
        "logits": logits,
        "text": "<ids:" + ",".join(str(token) for token in tokens) + ">",
    }


def _port_name(port: Any) -> str:
    try:
        return str(port.any_name)
    except Exception:
        names = list(port.get_names())
        return str(names[0]) if names else str(port)


def _infer(compiled: Any, request: Any, values: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    raw = request.infer(values)
    outputs: dict[str, np.ndarray] = {}
    for port in compiled.outputs:
        name = _port_name(port)
        try:
            outputs[name] = np.asarray(raw[port])
        except Exception:
            outputs[name] = np.asarray(raw[name])
    return outputs


def _input_name(compiled: Any, expected: str | None = None, index: int = 0) -> str:
    ports = list(compiled.inputs)
    if expected is not None:
        for port in ports:
            name = _port_name(port)
            if name == expected:
                return name
    return _port_name(ports[index])


def _output(outputs: dict[str, np.ndarray], expected: str, index: int) -> np.ndarray:
    if expected in outputs:
        return outputs[expected]
    return list(outputs.values())[index]


def _compile_info(compiled: Any) -> list[str] | dict[str, str]:
    try:
        return [str(value) for value in compiled.get_property("EXECUTION_DEVICES")]
    except Exception as exc:
        return {"error": f"{type(exc).__name__}: {exc}"}


def _openvino_decode(compiled: dict[str, Any], pixels: np.ndarray, max_steps: int) -> dict[str, Any]:
    encoder, decoder_init, decoder_step = (compiled[name] for name in MODELS)
    encoder_request = encoder.create_infer_request()
    init_request = decoder_init.create_infer_request()
    step_request = decoder_step.create_infer_request()

    encoder_input = _input_name(encoder)
    hidden = _infer(encoder, encoder_request, {encoder_input: pixels})
    hidden_value = next(iter(hidden.values()))

    init_inputs = {
        _input_name(decoder_init, "encoder_hidden_states"): hidden_value,
        _input_name(decoder_init, "input_ids"): np.array([[BOS]], dtype=np.int64),
    }
    init = _infer(decoder_init, init_request, init_inputs)
    logits0 = _output(init, "logits", 0)
    self_k0 = _output(init, "self_k", 1)
    self_v0 = _output(init, "self_v", 2)
    cross_k = _output(init, "cross_k", 3)
    cross_v = _output(init, "cross_v", 4)
    self_k = np.zeros((NUM_LAYERS, 1, NUM_HEADS, CACHE_WINDOW, HEAD_DIM), dtype=np.float32)
    self_v = np.zeros_like(self_k)
    self_k[:, :, :, 0, :] = self_k0[:, :, :, 0, :]
    self_v[:, :, :, 0, :] = self_v0[:, :, :, 0, :]

    logits = [np.asarray(logits0[0]).copy()]
    tokens: list[int] = []
    positions: list[int] = [1]
    current = int(np.argmax(logits0[0]))
    if current != EOS:
        tokens.append(current)
        for position in range(2, min(MAX_FED_POSITION, 1 + max_steps) + 1):
            step_inputs = {
                _input_name(decoder_step, "encoder_hidden_states"): hidden_value,
                _input_name(decoder_step, "input_ids"): np.array([[current]], dtype=np.int64),
                _input_name(decoder_step, "position_ids"): np.array([[position]], dtype=np.int64),
                _input_name(decoder_step, "self_k_cache"): self_k,
                _input_name(decoder_step, "self_v_cache"): self_v,
                _input_name(decoder_step, "cross_k_cache"): cross_k,
                _input_name(decoder_step, "cross_v_cache"): cross_v,
            }
            step = _infer(decoder_step, step_request, step_inputs)
            step_logits = _output(step, "logits", 0)
            step_k = _output(step, "self_k_slice", 1)
            step_v = _output(step, "self_v_slice", 2)
            self_k[:, :, :, position - 1, :] = step_k[:, :, :, 0, :]
            self_v[:, :, :, position - 1, :] = step_v[:, :, :, 0, :]
            logits.append(np.asarray(step_logits[0]).copy())
            positions.append(position)
            current = int(np.argmax(step_logits[0]))
            if current == EOS:
                break
            tokens.append(current)
    return {
        "tokens": tokens,
        "positions": positions,
        "logits": logits,
        "text": "<ids:" + ",".join(str(token) for token in tokens) + ">",
    }


def _tensor_metrics(reference: np.ndarray, candidate: np.ndarray) -> dict[str, Any]:
    reference = np.asarray(reference, dtype=np.float32)
    candidate = np.asarray(candidate, dtype=np.float32)
    if reference.shape != candidate.shape:
        return {"shape_equal": False, "reference_shape": list(reference.shape), "candidate_shape": list(candidate.shape)}
    diff = np.abs(reference - candidate)
    return {
        "shape_equal": True,
        "max_abs": float(diff.max()) if diff.size else 0.0,
        "mean_abs": float(diff.mean()) if diff.size else 0.0,
        "finite": bool(np.isfinite(candidate).all()),
    }


def _compare(reference: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    per_position = []
    for index, (ref_logits, got_logits) in enumerate(zip(reference["logits"], candidate["logits"])):
        metrics = _tensor_metrics(ref_logits, got_logits)
        metrics["position"] = reference["positions"][index]
        per_position.append(metrics)
    max_abs = max((row.get("max_abs", float("inf")) for row in per_position), default=float("inf"))
    mean_abs = max((row.get("mean_abs", float("inf")) for row in per_position), default=float("inf"))
    return {
        "tokens_equal": reference["tokens"] == candidate["tokens"],
        "reference_tokens": reference["tokens"],
        "candidate_tokens": candidate["tokens"],
        "reference_text": reference["text"],
        "candidate_text": candidate["text"],
        "length_equal": len(reference["logits"]) == len(candidate["logits"]),
        "logit_positions_compared": len(per_position),
        "logit_max_abs": max_abs,
        "logit_mean_abs_max": mean_abs,
        "logit_within_tolerance": max_abs <= LOGIT_MAX_ABS_TOL and mean_abs <= LOGIT_MEAN_ABS_TOL,
        "per_position": per_position,
    }


def _fallback_label(requested: str, execution_devices: dict[str, Any]) -> dict[str, Any]:
    values = [value for value in execution_devices.values() if isinstance(value, list)]
    flat = sorted({item for value in values for item in value})
    requested_upper = requested.upper()
    requested_seen = any(requested_upper in item.upper() for item in flat)
    if requested_upper == "CPU":
        fallback = False
    else:
        fallback = not requested_seen
    return {
        "requested_device": requested,
        "compiled_execution_devices": execution_devices,
        "fallback_detected": fallback,
        "label": "requested-device" if requested_seen else ("cpu-fallback" if flat else "unknown-execution-device"),
    }


def child_run(device: str, steps: int, warmup: int, iterations: int, fixture_count: int, seed: int) -> dict[str, Any]:
    import openvino as ov
    import onnxruntime as ort

    inventory_core = ov.Core()
    core = inventory_core
    compiled: dict[str, Any] = {}
    execution_devices: dict[str, Any] = {}
    compile_ms: dict[str, float] = {}
    for model in MODELS:
        started = time.perf_counter()
        compiled[model] = core.compile_model(core.read_model(str(_model_path(model))), device)
        compile_ms[model] = (time.perf_counter() - started) * 1000.0
        execution_devices[model] = _compile_info(compiled[model])

    fixtures = _synthetic_inputs(seed, fixture_count)
    ort_sessions = _reference_sessions()
    references = {name: _reference_decode(ort_sessions, pixels, steps) for name, pixels in fixtures}
    comparisons = []
    for name, pixels in fixtures:
        candidate = _openvino_decode(compiled, pixels, steps)
        comparisons.append({"fixture": name, "comparison": _compare(references[name], candidate)})

    def timed_fixture(pixels: np.ndarray) -> None:
        _openvino_decode(compiled, pixels, steps)

    for _, pixels in fixtures:
        for _ in range(max(0, warmup)):
            timed_fixture(pixels)
    timings: list[dict[str, Any]] = []
    for name, pixels in fixtures:
        values: list[float] = []
        for _ in range(max(1, iterations)):
            started = time.perf_counter()
            timed_fixture(pixels)
            values.append((time.perf_counter() - started) * 1000.0)
        timings.append({"fixture": name, "pipeline": _summary(values)})

    return {
        "status": "completed",
        "requested_device": device,
        "available_devices": list(core.available_devices),
        "openvino_version": ov.__version__,
        "reference_runtime": {"onnxruntime_version": ort.__version__, "provider": "CPUExecutionProvider"},
        "compile_ms": compile_ms,
        "execution": _fallback_label(device, execution_devices),
        "comparisons": comparisons,
        "timings": timings,
        "parameters": {"steps": steps, "warmup": warmup, "iterations": iterations, "fixture_count": fixture_count, "seed": seed},
        "evidence": "OBSERVED",
    }


def _run_child(args: argparse.Namespace) -> int:
    try:
        result = child_run(args.device, args.steps, args.warmup, args.iterations, args.fixture_count, args.seed)
        print(json.dumps(result, default=_safe), flush=True)
        return 0
    except Exception as exc:
        print(json.dumps({
            "status": "blocked",
            "evidence": "BLOCKED",
            "error_type": type(exc).__name__,
            "error": str(exc),
        }), flush=True)
        return 1


def _invoke_child(device: str, args: argparse.Namespace) -> dict[str, Any]:
    command = [
        sys.executable, "-u", str(pathlib.Path(__file__).resolve()), "--child",
        "--device", device, "--steps", str(args.steps), "--warmup", str(args.warmup),
        "--iterations", str(args.iterations), "--fixture-count", str(args.fixture_count),
        "--seed", str(args.seed),
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=args.timeout_s)
    except subprocess.TimeoutExpired as exc:
        return {"status": "timeout", "evidence": "BLOCKED", "requested_device": device, "timeout_s": args.timeout_s, "stdout": (exc.stdout or "")[-4000:], "stderr": (exc.stderr or "")[-4000:]}
    stdout = completed.stdout.strip()
    parsed: dict[str, Any] | None = None
    if stdout:
        try:
            parsed = json.loads(stdout.splitlines()[-1])
        except json.JSONDecodeError:
            parsed = None
    if parsed is None:
        parsed = {"status": "child_no_json", "evidence": "BLOCKED"}
    parsed.update({"returncode": completed.returncode, "child_stdout": stdout[-4000:], "child_stderr": completed.stderr[-4000:]})
    if completed.returncode != 0:
        parsed["status"] = "blocked"
        parsed["evidence"] = "BLOCKED"
    return parsed


def _inventory() -> dict[str, Any]:
    result: dict[str, Any] = {"evidence": "OBSERVED"}
    try:
        import openvino as ov

        core = ov.Core()
        result["openvino_version"] = ov.__version__
        result["available_devices"] = list(core.available_devices)
        result["device_properties"] = {}
        for device in result["available_devices"]:
            properties = {}
            for key in ("FULL_DEVICE_NAME", "DEVICE_ID"):
                try:
                    properties[key] = core.get_property(device, key)
                except Exception as exc:
                    properties[key] = {"error": f"{type(exc).__name__}: {exc}"}
            result["device_properties"][device] = properties
    except Exception as exc:
        result.update({"evidence": "BLOCKED", "error_type": type(exc).__name__, "error": str(exc)})
    return result


def _unavailable_row(device: str, inventory: dict[str, Any]) -> dict[str, Any]:
    return {
        "status": "unavailable",
        "evidence": "UNAVAILABLE",
        "requested_device": device,
        "available_devices": inventory.get("available_devices", []),
        "reason": f"{device} is not listed by OpenVINO Core.available_devices",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--child", action="store_true")
    parser.add_argument("--device")
    parser.add_argument("--steps", type=int, default=8)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--fixture-count", type=int, default=2)
    parser.add_argument("--seed", type=int, default=20260918)
    parser.add_argument("--timeout-s", type=int, default=240)
    parser.add_argument("--output", type=pathlib.Path, default=RESULT_DIR / "openvino_chained.json")
    args = parser.parse_args()
    if args.child:
        return _run_child(args)

    inventory = _inventory()
    available = set(inventory.get("available_devices", []))
    requested = ["CPU", "GPU", "AUTO", "NPU"]
    rows: list[dict[str, Any]] = []
    for device in requested:
        if device == "GPU" and device not in available:
            rows.append(_unavailable_row(device, inventory))
            continue
        # NPU is intentionally attempted even when absent: the exact plugin
        # blocker is useful evidence and is kept separate from availability.
        rows.append(_invoke_child(device, args))
        rows[-1]["requested_device"] = device

    payload = {
        "schema_version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "script": str(pathlib.Path(__file__).resolve()),
        "models": {model: str(_model_path(model)) for model in MODELS},
        "inventory": inventory,
        "provider_runs": rows,
        "parameters": {"steps": args.steps, "warmup": args.warmup, "iterations": args.iterations, "fixture_count": args.fixture_count, "seed": args.seed, "timeout_s": args.timeout_s},
        "protocol": {
            "encoder_output_feeds_decoder_init": True,
            "decoder_init_kv_feeds_decoder_step": True,
            "self_cache_updated_each_step": True,
            "cache_write_slot": "position_id - 1",
            "reference": "pristine ONNX Runtime CPU, corrected graph protocol",
        },
        "notes": [
            "Synthetic tensors are deterministic throughput/implementation fixtures, not OCR ground truth.",
            "A requested provider is accelerated only when compiled execution devices include that provider and logits/tokens match the pristine ORT CPU reference.",
            "AUTO and NPU outcomes are explicitly labeled; CPU fallback is not an acceleration claim.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, ensure_ascii=False, default=_safe), encoding="utf-8")
    compact = []
    for row in rows:
        compact.append({
            "device": row.get("requested_device"),
            "status": row.get("status"),
            "execution": row.get("execution", {}).get("label") if isinstance(row.get("execution"), dict) else None,
            "fallback": row.get("execution", {}).get("fallback_detected") if isinstance(row.get("execution"), dict) else None,
            "error": row.get("error"),
        })
    print(json.dumps({"output": str(args.output), "runs": compact}, indent=2, default=_safe))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
