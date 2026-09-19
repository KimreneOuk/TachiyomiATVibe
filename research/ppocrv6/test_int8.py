#!/usr/bin/env python3
"""PP-OCRv6 dynamic/QOperator INT8 research experiment.

No Android assets are changed. Quantized graphs and raw measurements are
written below ignored research/cache and research/results directories.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import numpy as np

from ppocr_quant_common import (CACHE, DET_MODEL, DICT, MODEL_DIR, REC_MODEL,
    RESULTS, Reader, box_metrics, boxes, ctc_decode, det_input, env_info,
    fixtures, load_session, model_info, rec_input, resolved_manifest,
    sequence_metrics, tensor_metrics, timed)

MODEL_NAMES = {"rec": REC_MODEL, "det": DET_MODEL}


def convert_dynamic(source: Path, dest: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(str(source), str(dest), weight_type=QuantType.QInt8,
                     per_channel=True, reduce_range=False,
                     extra_options={"MatMulConstBOnly": False})


def convert_static(source: Path, dest: Path, reader: Reader, fmt: str, per_channel: bool) -> None:
    from onnxruntime.quantization import CalibrationMethod, QuantFormat, QuantType, quantize_static
    # ORT consumes a reader to exhaustion; every graph/format attempt must
    # start from the same real calibration tensors.
    reader.rewind()
    quantize_static(str(source), str(dest), reader,
                    quant_format=QuantFormat.QOperator if fmt == "qoperator" else QuantFormat.QDQ,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
                    per_channel=per_channel, calibrate_method=CalibrationMethod.MinMax,
                    extra_options={"ActivationSymmetric": False})


def run(session, value: np.ndarray) -> np.ndarray:
    return np.asarray(session.run(None, {session.get_inputs()[0].name: value})[0])


def rec_record(fp, cand, image, fixture_id: str) -> dict[str, Any]:
    x = rec_input(image)
    a, b = run(fp, x), run(cand, x)
    dictionary = DICT.read_text(encoding="utf-8").splitlines()
    at, ai, ac = ctc_decode(a, dictionary); bt, bi, bc = ctc_decode(b, dictionary)
    return {"fixture": fixture_id, "output": tensor_metrics(a, b),
            "sequence": sequence_metrics(ai, bi), "text_exact": at == bt,
            "baseline_text": at, "candidate_text": bt,
            "baseline_conf": ac, "candidate_conf": bc}


def det_record(fp, cand, image, fixture_id: str) -> dict[str, Any]:
    x, rw, rh = det_input(image)
    a, b = run(fp, x), run(cand, x)
    # Compare the full probability map and the actual Android DB boxes.
    ref_boxes, got_boxes = boxes(a, rw, rh), boxes(b, rw, rh)
    metrics = box_metrics(ref_boxes, got_boxes, rw, rh, image.width, image.height)
    return {"fixture": fixture_id, "output": tensor_metrics(a, b), "boxes": metrics}


def project(raw: list[list[float]], rw: int, rh: int, width: int, height: int) -> list[list[float]]:
    sx, sy = width / float(max(1, rw)), height / float(max(1, rh))
    return [[b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy, b[4]] for b in raw]


def mixed_record(det, rec, baseline_det, baseline_rec, image, fixture_id: str) -> dict[str, Any]:
    x, rw, rh = det_input(image)
    raw = boxes(run(det, x), rw, rh); base_raw = boxes(run(baseline_det, x), rw, rh)
    got = project(raw, rw, rh, image.width, image.height)
    base = project(base_raw, rw, rh, image.width, image.height)
    dictionary = DICT.read_text(encoding="utf-8").splitlines()
    def texts(session, regions):
        out = []
        for b in regions[:8]:
            x1, y1, x2, y2 = [max(0, int(round(v))) for v in b[:4]]
            if x2 <= x1 or y2 <= y1: continue
            text, ids, conf = ctc_decode(run(session, rec_input(image.crop((x1, y1, min(image.width, x2), min(image.height, y2))))), dictionary)
            out.append({"text": text, "ids": ids, "conf": conf, "bbox": [x1, y1, x2, y2]})
        return out
    got_lines, base_lines = texts(rec, got), texts(baseline_rec, base)
    return {"fixture": fixture_id, "det_box_metrics": box_metrics(base_raw, raw, rw, rh, image.width, image.height),
            "baseline_lines": base_lines, "candidate_lines": got_lines,
            "text_exact": [x["text"] for x in got_lines] == [x["text"] for x in base_lines]}


def main(argv: list[str] | None = None, forced_mode: str | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-fixtures", type=int, default=24)
    parser.add_argument("--calibration-fixtures", type=int, default=16)
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--include-per-channel", action="store_true", help="also attempt static per-channel variants (slow on large graphs)")
    parser.add_argument("--no-mixed", action="store_true", help="skip mixed DET/REC cross-product while keeping independent evidence")
    parser.add_argument("--mode", choices=["int8", "qdq", "all"], default=forced_mode or "int8")
    args = parser.parse_args(argv)
    RESULTS.mkdir(parents=True, exist_ok=True); CACHE.mkdir(parents=True, exist_ok=True)
    selected = fixtures(args.max_fixtures, 20260918)
    calibration = selected[:max(1, min(args.calibration_fixtures, len(selected)))]
    manifest = resolved_manifest()
    result: dict[str, Any] = {"metadata": {"experiment": "ppocrv6_int8_qoperator", **env_info(),
        "corpus_manifest": str((Path("research") / "fixed_manifest.json").as_posix()),
        "manifest_count": len(manifest), "fixture_count": len(selected),
        "calibration_fixture_count": len(calibration), "calibration_is_real_only": True,
        "models": {k: model_info(v) for k, v in MODEL_NAMES.items()}},
        "manifest": manifest, "conversion_errors": {}, "load_errors": {},
        "models": {"fp32": {k: model_info(v) for k, v in MODEL_NAMES.items()}},
        "measurements": {"rec": [], "det": [], "mixed": []}}
    fp: dict[str, Any] = {}; fp_load: dict[str, float] = {}
    for kind, path in MODEL_NAMES.items():
        fp[kind], fp_load[kind] = load_session(path)
    rec_values = [{"x": rec_input(f["image"])} for f in calibration]
    det_values = [{"x": det_input(f["image"])[0]} for f in calibration]
    readers = {"rec": Reader(rec_values), "det": Reader(det_values)}
    variants: dict[str, dict[str, Path]] = {}
    wanted = args.mode
    if wanted in ("int8", "all"):
        variants["dynamic_pc"] = {}
        variants["qoperator_pt"] = {}
        if args.include_per_channel:
            variants["qoperator_pc"] = {}
    if wanted in ("qdq", "all"):
        variants["qdq_pt"] = {}
        if args.include_per_channel:
            variants["qdq_pc"] = {}
    for variant in variants:
        result["models"][variant] = {}
        for kind, source in MODEL_NAMES.items():
            dest = CACHE / f"{variant}_{kind}.onnx"
            try:
                t0 = time.perf_counter_ns()
                if not dest.exists():
                    if variant == "dynamic_pc": convert_dynamic(source, dest)
                    else: convert_static(source, dest, readers[kind], "qoperator" if variant.startswith("qoperator") else "qdq", variant.endswith("_pc"))
                result["models"][variant][kind] = {"source": model_info(source), "converted": model_info(dest), "conversion_ms": (time.perf_counter_ns() - t0) / 1e6}
                variants[variant][kind] = dest
                # Explicit load compatibility is part of the result, even if
                # conversion succeeded.
                load_session(dest)[0]
            except Exception as exc:
                result["conversion_errors"][f"{variant}:{kind}"] = f"{type(exc).__name__}: {exc}"
                if dest.exists(): result["models"][variant][kind] = {"source": model_info(source), "converted": model_info(dest), "loadable": False}
    for variant, paths in variants.items():
        for kind in ("rec", "det"):
            path = paths.get(kind)
            if not path: continue
            try:
                sess, load_ms = load_session(path)
                baseline_sess = fp[kind]
                if kind == "rec":
                    result["measurements"][kind].extend({"variant": variant, **rec_record(baseline_sess, sess, f["image"], f["id"])} for f in selected)
                    result["measurements"][kind].append({"variant": variant, "timing": timed(sess, sess.get_inputs()[0].name, rec_input(selected[0]["image"]), args.repeats), "load_ms": load_ms, "fp32_load_ms": fp_load[kind]})
                else:
                    result["measurements"][kind].extend({"variant": variant, **det_record(baseline_sess, sess, f["image"], f["id"])} for f in selected)
                    result["measurements"][kind].append({"variant": variant, "timing": timed(sess, sess.get_inputs()[0].name, det_input(selected[0]["image"])[0], args.repeats), "load_ms": load_ms, "fp32_load_ms": fp_load[kind]})
            except Exception as exc:
                result["load_errors"][f"run:{variant}:{kind}"] = f"{type(exc).__name__}: {exc}"
    # Useful mixed combinations use the same real page/crop inputs. Limit the
    # cross product to the first few fixtures to keep the research run bounded.
    det_variants = {"fp32": fp["det"]}; rec_variants = {"fp32": fp["rec"]}
    for variant, paths in variants.items():
        if "det" in paths:
            try: det_variants[variant] = load_session(paths["det"])[0]
            except Exception: pass
        if "rec" in paths:
            try: rec_variants[variant] = load_session(paths["rec"])[0]
            except Exception: pass
    if not args.no_mixed:
        for dname, dsess in det_variants.items():
            for rname, rsess in rec_variants.items():
                if dname == rname == "fp32": continue
                for f in selected[:min(6, len(selected))]:
                    try: result["measurements"]["mixed"].append({"det_variant": dname, "rec_variant": rname, **mixed_record(dsess, rsess, fp["det"], fp["rec"], f["image"], f["id"])})
                    except Exception as exc: result["load_errors"][f"mixed:{dname}:{rname}:{f['id']}"] = f"{type(exc).__name__}: {exc}"
    out = RESULTS / ("int8_results.json" if args.mode == "int8" else "qdq_results.json" if args.mode == "qdq" else "int8_qdq_results.json")
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    # Flat CSV is intentionally emitted alongside JSON for quick plotting.
    rows = ["kind,variant,fixture,max_abs,mean_abs,rmse,cosine,exact,text_exact,reference_count,candidate_count,mean_best_iou"]
    for kind in ("rec", "det"):
        for r in result["measurements"][kind]:
            if "output" not in r: continue
            m = r["output"]; seq = r.get("sequence", {})
            b = r.get("boxes", {})
            rows.append(",".join(map(str, [kind, r["variant"], r["fixture"], m["max_abs"], m["mean_abs"], m["rmse"], m["cosine"], seq.get("exact", ""), r.get("text_exact", ""), b.get("reference_count", ""), b.get("candidate_count", ""), b.get("mean_best_iou", "")])) )
    (RESULTS / ("int8_results.csv" if args.mode == "int8" else "qdq_results.csv" if args.mode == "qdq" else "int8_qdq_results.csv")).write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(out), "conversion_errors": len(result["conversion_errors"]), "load_errors": len(result["load_errors"]), "variants": list(result["models"].keys())}, indent=2))
    return 0


if __name__ == "__main__": raise SystemExit(main())
