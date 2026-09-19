#!/usr/bin/env python3
"""Calibration-based QDQ INT8 experiment (kept separate from QOperator INT8)."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np
from onnxruntime.quantization import CalibrationMethod, QuantFormat, QuantType, quantize_static

from compare_outputs import sequence_metrics, summarize_metrics, tensor_metrics
from quantization_common import CACHE_DIR, MODEL_DIR, RESULT_DIR, DictCalibrationReader, chapter_fixtures, environment, load_session, model_info, preprocess, run_encoder, run_ocr, synthetic_crops
from test_int8 import decoder_step_samples

MODEL_NAMES = ("encoder.onnx", "decoder_init.onnx", "decoder_step.onnx")


def convert(source: Path, destination: Path, reader: DictCalibrationReader) -> dict[str, Any]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        # QDQ is deliberately per-tensor: ORT 1.24.1's per-channel encoder
        # path fails with a reproducible (16384,) vs (64,) broadcast error.
        quantize_static(str(source), str(destination), reader, quant_format=QuantFormat.QDQ, activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8, per_channel=False, calibrate_method=CalibrationMethod.MinMax, extra_options={"ActivationSymmetric": False})
    return model_info(destination)


def float_inputs(session, values):
    out = {}
    for info in session.get_inputs():
        value = values[info.name]
        if "float16" in info.type: value = np.asarray(value, dtype=np.float16)
        elif "float" in info.type: value = np.asarray(value, dtype=np.float32)
        out[info.name] = value
    return out


def main() -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--real-dir", type=Path); parser.add_argument("--max-real", type=int, default=8); parser.add_argument("--decode-length", type=int, default=32); parser.add_argument("--seed", type=int, default=20260918)
    args = parser.parse_args(); RESULT_DIR.mkdir(parents=True, exist_ok=True); CACHE_DIR.mkdir(parents=True, exist_ok=True)
    synthetic = [{"name": f"synthetic:{i}", "source": "generated synthetic_crops(seed)", "expected_text": "", "image": c} for i, c in enumerate(synthetic_crops(args.seed))]
    real = chapter_fixtures(args.real_dir, args.max_real, args.seed) if args.real_dir else []
    fixtures = synthetic + real; inputs = [{**f, "input": preprocess(f["image"])} for f in fixtures]
    sources = {n: MODEL_DIR / n for n in MODEL_NAMES}; baseline = tuple(load_session(sources[n]) for n in MODEL_NAMES)
    enc_name = baseline[0].get_inputs()[0].name
    init_samples, step_samples = decoder_step_samples(baseline, [f["input"] for f in inputs])
    readers = {"encoder.onnx": DictCalibrationReader([{enc_name: f["input"]} for f in inputs]), "decoder_init.onnx": DictCalibrationReader(init_samples), "decoder_step.onnx": DictCalibrationReader(step_samples)}
    result: dict[str, Any] = {"metadata": {"experiment": "qdq_int8", "evidence": "OBSERVED local ONNX Runtime CPU experiment; real fixtures are existing .studio/ocr.json crops; synthetic fixtures are clearly labeled and are not OCR ground truth", **environment(), "seed": args.seed, "max_real": args.max_real, "decode_length": args.decode_length, "fixture_count": len(fixtures)}, "models": {"fp32": {n: model_info(p) for n, p in sources.items()}}, "conversion_errors": {}, "calibration": {"synthetic_count": len(synthetic), "real_count": len(real), "encoder_count": len(inputs), "decoder_init_count": len(init_samples), "decoder_step_count": len(step_samples)}, "encoder": [], "pipeline": []}
    paths: dict[str, Path] = {}
    for name, source in sources.items():
        try:
            path = CACHE_DIR / f"qdq_int8_{name}"; result["models"].setdefault("qdq_int8", {})[name] = {"source": model_info(source), "converted": convert(source, path, readers[name])}; paths[name] = path
        except Exception as exc: result["conversion_errors"][name] = f"{type(exc).__name__}: {exc}"
    if "encoder.onnx" in paths:
        try:
            session = load_session(paths["encoder.onnx"])
            for f in inputs: result["encoder"].append({"fixture": f["name"], "source": f["source"], "metrics": tensor_metrics(run_encoder(baseline[0], f["input"]), run_encoder(session, f["input"]))})
        except Exception as exc: result["conversion_errors"]["run:encoder"] = f"{type(exc).__name__}: {exc}"
    if len(paths) == 3:
        try:
            candidate = tuple(load_session(paths[n]) for n in MODEL_NAMES)
            for f in inputs:
                ref = run_ocr(*baseline, f["input"], args.decode_length); got = run_ocr(*candidate, f["input"], args.decode_length)
                result["pipeline"].append({"fixture": f["name"], "source": f["source"], "expected_text": f.get("expected_text", ""), "baseline_text": ref["text"], "candidate_text": got["text"], "sequence": sequence_metrics(ref["token_ids"], got["token_ids"]), "first_step_logits": tensor_metrics(ref["logits"][0], got["logits"][0]) if ref["logits"] and got["logits"] else None})
        except Exception as exc: result["conversion_errors"]["run:pipeline"] = f"{type(exc).__name__}: {exc}"
    result["summary"] = {"encoder": summarize_metrics(r["metrics"] for r in result["encoder"]), "sequence": summarize_metrics(r["sequence"] for r in result["pipeline"]), "first_step_logits": summarize_metrics(r["first_step_logits"] for r in result["pipeline"] if r.get("first_step_logits"))}
    (RESULT_DIR / "qdq_results.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8"); print(json.dumps(result["summary"], indent=2)); return 0


if __name__ == "__main__": raise SystemExit(main())
