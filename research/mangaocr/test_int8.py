#!/usr/bin/env python3
"""Evaluate dynamic-weight and static QOperator INT8 conversion.

Static calibration uses both deterministic synthetic crops and optional real
chapter OCR-box crops.  Real crops are referenced by path and the source
``.studio/ocr.json`` box metadata; they are not copied into the repository.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import numpy as np
from onnxruntime.quantization import CalibrationMethod, QuantFormat, QuantType, quantize_dynamic, quantize_static

from compare_outputs import sequence_metrics, summarize_metrics, tensor_metrics
from quantization_common import CACHE_DIR, MODEL_DIR, RESULT_DIR, DictCalibrationReader, chapter_fixtures, environment, load_session, model_info, preprocess, run_encoder, run_ocr, synthetic_crops


MODEL_NAMES = ("encoder.onnx", "decoder_init.onnx", "decoder_step.onnx")


def dynamic_model(source: Path, destination: Path) -> dict[str, Any]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        quantize_dynamic(str(source), str(destination), weight_type=QuantType.QInt8, per_channel=True, reduce_range=False, extra_options={"MatMulConstBOnly": False})
    return model_info(destination)


def float_inputs(session, values: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    out = {}
    for info in session.get_inputs():
        if info.name not in values:
            raise KeyError(f"missing calibration input {info.name}")
        value = values[info.name]
        if "float16" in info.type: value = np.asarray(value, dtype=np.float16)
        elif "float" in info.type: value = np.asarray(value, dtype=np.float32)
        out[info.name] = value
    return out


def decoder_step_samples(sessions, inputs: list[np.ndarray], max_steps: int = 4) -> tuple[list[dict[str, np.ndarray]], list[dict[str, np.ndarray]]]:
    """Materialize valid init/step calibration tensors from baseline execution."""
    enc, init, step = sessions
    init_samples: list[dict[str, np.ndarray]] = []; step_samples: list[dict[str, np.ndarray]] = []
    for pixel in inputs:
        hidden = run_encoder(enc, pixel)
        init_values = {"encoder_hidden_states": hidden, "input_ids": np.array([[2]], dtype=np.int64)}
        init_out = [np.asarray(x) for x in init.run(None, float_inputs(init, init_values))]
        _, self_k_init, self_v_init, cross_k, cross_v = init_out
        init_samples.append(float_inputs(init, init_values))
        self_k = np.zeros((4, 1, 4, 256, 64), dtype=self_k_init.dtype); self_v = np.zeros_like(self_k)
        self_k[:, :, :, :1, :] = self_k_init; self_v[:, :, :, :1, :] = self_v_init
        current = 2
        for position in range(1, max_steps + 1):
            values = {"encoder_hidden_states": hidden, "input_ids": np.array([[current]], dtype=np.int64), "position_ids": np.array([[position]], dtype=np.int64), "self_k_cache": self_k, "self_v_cache": self_v, "cross_k_cache": cross_k, "cross_v_cache": cross_v}
            step_samples.append(float_inputs(step, values))
            out = [np.asarray(x) for x in step.run(None, float_inputs(step, values))]
            token = int(np.argmax(out[0][0])); self_k[:, :, :, position:position + 1, :] = out[1]; self_v[:, :, :, position:position + 1, :] = out[2]; current = token
    return init_samples, step_samples


def static_model(source: Path, destination: Path, reader: DictCalibrationReader) -> dict[str, Any]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        # Per-channel static calibration currently fails on this encoder with
        # ORT 1.24.1 (broadcast shapes 16384 vs 64). Keep the retry portable
        # and record the limitation rather than silently dropping the variant.
        quantize_static(str(source), str(destination), reader, quant_format=QuantFormat.QOperator, activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8, per_channel=False, calibrate_method=CalibrationMethod.MinMax, extra_options={"ActivationSymmetric": False})
    return model_info(destination)


def timed(fn, repeats: int) -> list[float]:
    values = []
    for _ in range(max(1, repeats)):
        start = time.perf_counter_ns(); fn(); values.append((time.perf_counter_ns() - start) / 1e6)
    return values


def main() -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--real-dir", type=Path); parser.add_argument("--max-real", type=int, default=8); parser.add_argument("--decode-length", type=int, default=32); parser.add_argument("--repeats", type=int, default=2); parser.add_argument("--seed", type=int, default=20260918)
    args = parser.parse_args(); RESULT_DIR.mkdir(parents=True, exist_ok=True); CACHE_DIR.mkdir(parents=True, exist_ok=True)
    synthetic = [{"name": f"synthetic:{i}", "source": "generated synthetic_crops(seed)", "expected_text": "", "image": c} for i, c in enumerate(synthetic_crops(args.seed))]
    real = chapter_fixtures(args.real_dir, args.max_real, args.seed) if args.real_dir else []
    fixtures = synthetic + real; inputs = [{**f, "input": preprocess(f["image"])} for f in fixtures]
    sources = {name: MODEL_DIR / name for name in MODEL_NAMES}
    baseline = tuple(load_session(sources[name]) for name in MODEL_NAMES)
    result: dict[str, Any] = {"metadata": {"experiment": "int8", "evidence": "OBSERVED local ONNX Runtime CPU experiment; synthetic calibration is explicitly not OCR ground truth; real calibration uses .studio/ocr.json boxes", **environment(), "seed": args.seed, "max_real": args.max_real, "decode_length": args.decode_length, "repeats": args.repeats, "fixture_count": len(fixtures)}, "models": {"fp32": {n: model_info(p) for n, p in sources.items()}}, "conversion_errors": {}, "encoder": [], "pipeline": [], "timing": [], "calibration": {}}
    # Build readers from valid FP32 tensors.  Decoder readers are kept separate
    # because each graph has a distinct input set.
    enc_name = baseline[0].get_inputs()[0].name
    enc_reader = DictCalibrationReader([{enc_name: f["input"]} for f in inputs])
    init_samples, step_samples = decoder_step_samples(baseline, [f["input"] for f in inputs])
    result["calibration"] = {"synthetic_count": len(synthetic), "real_count": len(real), "encoder_count": len(enc_reader.samples), "decoder_init_count": len(init_samples), "decoder_step_count": len(step_samples), "decoder_step_max_steps_per_fixture": 4}
    readers = {"encoder.onnx": enc_reader, "decoder_init.onnx": DictCalibrationReader(init_samples), "decoder_step.onnx": DictCalibrationReader(step_samples)}
    dynamic_paths: dict[str, Path] = {}; static_paths: dict[str, Path] = {}
    for name, source in sources.items():
        try:
            dynamic_paths[name] = CACHE_DIR / f"dynamic_int8_{name}"; result["models"].setdefault("dynamic_int8", {})[name] = {"source": model_info(source), "converted": dynamic_model(source, dynamic_paths[name])}
        except Exception as exc: result["conversion_errors"][f"dynamic:{name}"] = f"{type(exc).__name__}: {exc}"
        try:
            static_paths[name] = CACHE_DIR / f"static_int8_{name}"; result["models"].setdefault("static_int8", {})[name] = {"source": model_info(source), "converted": static_model(source, static_paths[name], readers[name])}
        except Exception as exc: result["conversion_errors"][f"static:{name}"] = f"{type(exc).__name__}: {exc}"
    variants = {"dynamic_int8": dynamic_paths, "static_int8": static_paths}
    # Encoder output similarity is measured wherever conversion succeeded.
    for variant, paths in variants.items():
        if "encoder.onnx" not in paths: continue
        try:
            session = load_session(paths["encoder.onnx"])
            for f in inputs:
                ref = run_encoder(baseline[0], f["input"]); got = run_encoder(session, f["input"])
                result["encoder"].append({"variant": variant, "fixture": f["name"], "source": f["source"], "metrics": tensor_metrics(ref, got)})
        except Exception as exc: result["conversion_errors"][f"run:{variant}:encoder"] = f"{type(exc).__name__}: {exc}"
    base_pipeline = None
    try:
        base_pipeline = tuple(load_session(sources[n]) for n in MODEL_NAMES)
    except Exception as exc: result["conversion_errors"]["load:fp32"] = f"{type(exc).__name__}: {exc}"
    for variant, paths in variants.items():
        if len(paths) != 3 or base_pipeline is None: continue
        try:
            candidate = tuple(load_session(paths[n]) for n in MODEL_NAMES)
            for f in inputs:
                ref = run_ocr(*base_pipeline, f["input"], args.decode_length); got = run_ocr(*candidate, f["input"], args.decode_length)
                result["pipeline"].append({"variant": variant, "fixture": f["name"], "source": f["source"], "expected_text": f.get("expected_text", ""), "baseline_text": ref["text"], "candidate_text": got["text"], "sequence": sequence_metrics(ref["token_ids"], got["token_ids"]), "first_step_logits": tensor_metrics(ref["logits"][0], got["logits"][0]) if ref["logits"] and got["logits"] else None})
            result["timing"].append({"variant": variant, "fixture": f["name"], "full_pipeline_ms": timed(lambda: run_ocr(*candidate, f["input"], args.decode_length), args.repeats)})
        except Exception as exc: result["conversion_errors"][f"run:{variant}:pipeline"] = f"{type(exc).__name__}: {exc}"
    result["summary"] = {"encoder": summarize_metrics(r["metrics"] for r in result["encoder"]), "sequence": summarize_metrics(r["sequence"] for r in result["pipeline"]), "first_step_logits": summarize_metrics(r["first_step_logits"] for r in result["pipeline"] if r.get("first_step_logits"))}
    (RESULT_DIR / "int8_results.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8"); print(json.dumps(result["summary"], indent=2)); return 0


if __name__ == "__main__": raise SystemExit(main())
