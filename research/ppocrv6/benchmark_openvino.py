"""OpenVINO probe for the shipped PP-OCRv6-small detector/recognizer.

This is intentionally a read-only host research harness.  It uses the exact
model assets shipped by the app, the Kotlin-equivalent preprocessing, and the
real manga crops in app/src/test/resources/corpus.  Device probes run in child
processes so a vendor plugin failure is retained as an explicit result rather
than aborting the complete matrix.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import pathlib
import statistics
import subprocess
import sys
import time
from typing import Any

import cv2
import numpy as np
from PIL import Image


ROOT = pathlib.Path(__file__).resolve().parents[1]
DET_MODEL = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"
REC_MODEL = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx"
DICT_FILE = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt"
CORPUS_ROOT = ROOT / "app/src/test/resources/corpus/aot"
RESULT_DIR = ROOT / "research/results"
FINDING_FILE = ROOT / "research/findings/openvino.md"


def safe(v: Any) -> Any:
    if isinstance(v, (np.integer, np.floating)):
        return v.item()
    if isinstance(v, np.ndarray):
        return {"shape": list(v.shape), "dtype": str(v.dtype)}
    if isinstance(v, pathlib.Path):
        return str(v)
    raise TypeError(type(v).__name__)


def summary(values: list[float]) -> dict[str, float]:
    if not values:
        return {}
    a = sorted(float(x) for x in values)
    return {
        "count": len(a),
        "min_ms": a[0],
        "mean_ms": statistics.fmean(a),
        "median_ms": statistics.median(a),
        "p95_ms": a[min(len(a) - 1, math.ceil(len(a) * 0.95) - 1)],
        "max_ms": a[-1],
    }


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def corpus_paths(limit: int) -> list[pathlib.Path]:
    paths = sorted(CORPUS_ROOT.glob("*/dynamic_out.png"))
    if not paths:
        raise FileNotFoundError(f"No real corpus images under {CORPUS_ROOT}")
    return paths[:limit]


def image_bgr(path: pathlib.Path) -> np.ndarray:
    bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if bgr is None:
        raise RuntimeError(f"Cannot decode {path}")
    return bgr


def det_input(bgr: np.ndarray) -> tuple[np.ndarray, dict[str, float]]:
    h, w = bgr.shape[:2]
    scale = 736.0 / max(w, h)
    rw, rh = max(1, min(736, round(w * scale))), max(1, min(736, round(h * scale)))
    resized = cv2.resize(bgr, (rw, rh), interpolation=cv2.INTER_LINEAR)
    canvas = np.zeros((736, 736, 3), dtype=np.uint8)
    canvas[:rh, :rw] = resized
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    rgb = (rgb - np.array([0.485, 0.456, 0.406], np.float32)) / np.array([0.229, 0.224, 0.225], np.float32)
    return np.transpose(rgb, (2, 0, 1))[None], {"rw": rw, "rh": rh, "sx": rw / w, "sy": rh / h}


def rec_input(bgr: np.ndarray) -> tuple[np.ndarray, int]:
    h, w = bgr.shape[:2]
    scaled = max(1, math.ceil(w * (48.0 / max(1, h))))
    scaled = min(scaled, 1600)
    width = 640 if scaled <= 640 else 1600
    resized = cv2.resize(bgr, (scaled, 48), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((48, width, 3), 128, dtype=np.uint8)
    canvas[:, :scaled] = resized
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32)
    rgb = (rgb / 255.0 - 0.5) / 0.5
    return np.transpose(rgb, (2, 0, 1))[None], width


def boxes_from_map(prob: np.ndarray, meta: dict[str, float]) -> list[list[int]]:
    active = prob[: int(meta["rh"]), : int(meta["rw"])]
    mask = (active > 0.2).astype(np.uint8)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    boxes: list[list[int]] = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < 3:
            continue
        score = float(active[labels == i].mean())
        if score < 0.45:
            continue
        boxes.append([
            max(0, round(x / meta["sx"])),
            max(0, round(y / meta["sy"])),
            min(round((x + w) / meta["sx"]), round(meta["rw"] / meta["sx"])),
            min(round((y + h) / meta["sy"]), round(meta["rh"] / meta["sy"])),
        ])
    return boxes


def decode(logits: np.ndarray, dictionary: list[str]) -> str:
    ids = logits[0].argmax(axis=1).tolist()
    out: list[str] = []
    prev = -1
    space = len(dictionary) + 1
    for idx in ids:
        if idx != prev and idx != 0:
            if 1 <= idx <= len(dictionary):
                out.append(dictionary[idx - 1])
            elif idx == space:
                out.append(" ")
        prev = idx
    return "".join(out).strip()


def properties(core: Any, device: str) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key in ("FULL_DEVICE_NAME", "DEVICE_ID", "SUPPORTED_PROPERTIES"):
        try:
            value = core.get_property(device, key)
            out[key] = list(value) if isinstance(value, (tuple, list)) else value
        except Exception as exc:
            out[key] = {"error": f"{type(exc).__name__}: {exc}"}
    return out


def child_probe(stage: str, device: str, paths: list[pathlib.Path], warmup: int, iterations: int) -> dict[str, Any]:
    import openvino as ov
    import onnxruntime as ort
    import psutil

    model_path = DET_MODEL if stage == "det" else REC_MODEL
    # Compute the pristine ORT FP32 reference before compiling OpenVINO.  Both
    # runtimes otherwise create large native thread pools and this host can
    # terminate the process under the combined detector footprint.
    ort_options = ort.SessionOptions()
    ort_options.intra_op_num_threads = 1
    ort_options.inter_op_num_threads = 1
    ort_session = ort.InferenceSession(str(model_path), sess_options=ort_options, providers=["CPUExecutionProvider"])
    ort_name = ort_session.get_inputs()[0].name
    dictionary = [line.rstrip("\n\r") for line in DICT_FILE.open(encoding="utf-8")]
    inputs: list[np.ndarray] = []
    for p in paths:
        bgr = image_bgr(p)
        inputs.append(det_input(bgr)[0] if stage == "det" else rec_input(bgr)[0])
    refs = [ort_session.run(None, {ort_name: x})[0] for x in inputs]
    del ort_session

    core = ov.Core()
    process = psutil.Process(os.getpid())
    model = core.read_model(str(model_path))
    result: dict[str, Any] = {
        "evidence": "OBSERVED",
        "stage": stage,
        "device": device,
        "available_devices": list(core.available_devices),
        "device_properties": properties(core, device),
        "model": str(model_path),
        "model_bytes": model_path.stat().st_size,
        "model_sha256": sha256(model_path),
        "op_count": len(model.get_ordered_ops()),
        "rss_before_compile_mb": process.memory_info().rss / 1048576.0,
    }
    try:
        query = core.query_model(model, device)
        result["query_model"] = {
            "supported_op_count": len(query),
            "unsupported_op_count": max(0, result["op_count"] - len(query)),
            "unsupported_ops": [op.get_type_name() + ":" + op.get_friendly_name() for op in model.get_ordered_ops() if op.get_friendly_name() not in query],
        }
    except Exception as exc:
        result["query_model_error"] = f"{type(exc).__name__}: {exc}"
    compile_started = time.perf_counter()
    compiled = core.compile_model(model, device)
    result["compile_ms"] = (time.perf_counter() - compile_started) * 1000.0
    result["rss_after_compile_mb"] = process.memory_info().rss / 1048576.0
    try:
        result["compiled_execution_devices"] = list(compiled.get_property("EXECUTION_DEVICES"))
    except Exception as exc:
        result["compiled_execution_devices_error"] = f"{type(exc).__name__}: {exc}"
    request = compiled.create_infer_request()
    first_ms = []
    for x in inputs:
        t = time.perf_counter(); request.infer({"x": x}); first_ms.append((time.perf_counter() - t) * 1000.0)
    for _ in range(warmup):
        for x in inputs:
            request.infer({"x": x})
    warm_ms: list[float] = []
    parity: list[dict[str, Any]] = []
    for idx, (p, x, ref) in enumerate(zip(paths, inputs, refs)):
        vals: list[float] = []
        out = None
        for _ in range(iterations):
            t = time.perf_counter(); out = next(iter(request.infer({"x": x}).values())); vals.append((time.perf_counter() - t) * 1000.0)
        assert out is not None
        diff = np.asarray(out, dtype=np.float32) - np.asarray(ref, dtype=np.float32)
        row: dict[str, Any] = {
            "sample": str(p.relative_to(ROOT)),
            "warm_latency": summary(vals),
            "max_abs_diff_vs_ort": float(np.max(np.abs(diff))),
            "mean_abs_diff_vs_ort": float(np.mean(np.abs(diff))),
            "argmax_equal_fraction": float(np.mean(np.argmax(out, axis=-1) == np.argmax(ref, axis=-1))) if stage == "rec" else None,
        }
        if stage == "det":
            meta = det_input(image_bgr(p))[1]
            row["ort_boxes"] = boxes_from_map(ref[0, 0], meta)
            row["device_boxes"] = boxes_from_map(np.asarray(out)[0, 0], meta)
            row["boxes_equal"] = row["ort_boxes"] == row["device_boxes"]
        else:
            row["ort_text"] = decode(ref, dictionary)
            row["device_text"] = decode(np.asarray(out), dictionary)
            row["text_equal"] = row["ort_text"] == row["device_text"]
        parity.append(row)
        warm_ms.extend(vals)
    result["cold_first_infer"] = summary(first_ms)
    result["warm_infer"] = summary(warm_ms)
    result["rss_after_infer_mb"] = process.memory_info().rss / 1048576.0
    result["parity"] = parity
    result["rss_delta_compile_mb"] = result["rss_after_compile_mb"] - result["rss_before_compile_mb"]
    return result


def child_hybrid(det_device: str, rec_device: str, paths: list[pathlib.Path], iterations: int) -> dict[str, Any]:
    """Connected DET -> crop -> REC path using real corpus images."""
    import openvino as ov
    import psutil
    core = ov.Core(); process = psutil.Process(os.getpid())
    det = core.compile_model(core.read_model(str(DET_MODEL)), det_device)
    rec = core.compile_model(core.read_model(str(REC_MODEL)), rec_device)
    det_req, rec_req = det.create_infer_request(), rec.create_infer_request()
    dictionary = [line.rstrip("\n\r") for line in DICT_FILE.open(encoding="utf-8")]
    for p in paths:
        bgr = image_bgr(p); x, meta = det_input(bgr); prob = next(iter(det_req.infer({"x": x}).values()))
        boxes = boxes_from_map(np.asarray(prob)[0, 0], meta)
        if not boxes:
            boxes = [[0, 0, bgr.shape[1], bgr.shape[0]]]
        for box in boxes[:8]:
            x0, y0, x1, y1 = box; crop = bgr[max(0,y0):max(y0+1,y1), max(0,x0):max(x0+1,x1)]
            rx, _ = rec_input(crop); rec_req.infer({"x": rx})
    totals: list[float] = []; all_boxes = 0; texts: list[str] = []
    for _ in range(iterations):
        started = time.perf_counter(); run_boxes = 0
        for p in paths:
            bgr = image_bgr(p); x, meta = det_input(bgr); prob = next(iter(det_req.infer({"x": x}).values()))
            boxes = boxes_from_map(np.asarray(prob)[0, 0], meta) or [[0, 0, bgr.shape[1], bgr.shape[0]]]
            run_boxes += len(boxes)
            for x0, y0, x1, y1 in boxes[:8]:
                crop = bgr[max(0,y0):max(y0+1,y1), max(0,x0):max(x0+1,x1)]
                rx, _ = rec_input(crop); out = next(iter(rec_req.infer({"x": rx}).values())); texts.append(decode(np.asarray(out), dictionary))
        totals.append((time.perf_counter() - started) * 1000.0); all_boxes += run_boxes
    return {"evidence":"OBSERVED", "det_device":det_device, "rec_device":rec_device, "samples":len(paths), "iterations":iterations, "boxes_per_iteration":all_boxes/iterations, "texts_seen":len(texts), "pipeline":summary(totals), "rss_after_mb":process.memory_info().rss/1048576.0}


def invoke(extra: list[str], timeout_s: int = 180) -> dict[str, Any]:
    command = [sys.executable, "-u", str(pathlib.Path(__file__).resolve()), "--child", *extra]
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=timeout_s)
    except subprocess.TimeoutExpired as exc:
        return {"evidence":"BLOCKED", "status":"timeout", "command":command, "timeout_s":timeout_s, "stdout":str(exc.stdout or "")[-8000:], "stderr":str(exc.stderr or "")[-8000:]}
    parsed: dict[str, Any] | None = None
    lines = done.stdout.strip().splitlines()
    if lines:
        try: parsed = json.loads(lines[-1])
        except json.JSONDecodeError: pass
    if parsed is None: parsed = {"evidence":"BLOCKED", "status":"child_no_json"}
    parsed.update({"returncode":done.returncode, "child_stdout":done.stdout[-8000:], "child_stderr":done.stderr[-8000:]})
    if done.returncode != 0 and "error" not in parsed:
        parsed["evidence"] = "BLOCKED"; parsed["status"] = "child_exit"
    return parsed


def inventory() -> dict[str, Any]:
    out: dict[str, Any] = {"evidence":"OBSERVED", "python":sys.version}
    try:
        import openvino as ov
        core = ov.Core(); out.update({"openvino_version":ov.__version__, "available_devices":list(core.available_devices), "devices":{d:properties(core,d) for d in core.available_devices}})
    except Exception as exc: out.update({"evidence":"BLOCKED", "error":f"{type(exc).__name__}: {exc}"})
    try:
        import onnxruntime as ort
        out.update({"onnxruntime_version":ort.__version__, "onnxruntime_providers":ort.get_available_providers()})
    except Exception as exc: out["onnxruntime_error"] = f"{type(exc).__name__}: {exc}"
    return out


def run(args: argparse.Namespace) -> None:
    paths = corpus_paths(args.samples)
    inv = inventory()
    devices = list(dict.fromkeys(inv.get("available_devices", []) + ["NPU", "AUTO"]))
    if "GPU" in inv.get("available_devices", []): devices.append("HETERO:GPU,CPU")
    if args.devices:
        devices = [d.strip() for d in args.devices.split(",") if d.strip()]
    matrix: list[dict[str, Any]] = []
    for stage in ("det", "rec"):
        for device in devices:
            matrix.append(invoke(["probe", stage, device, str(args.samples), str(args.warmup), str(args.iterations)], args.timeout))
    hybrids: list[dict[str, Any]] = []
    if "CPU" in inv.get("available_devices", []):
        for dd, rd in [("CPU","CPU"), ("GPU","CPU"), ("CPU","GPU"), ("AUTO","AUTO")]:
            if dd == "GPU" and "GPU" not in inv.get("available_devices", []): continue
            hybrids.append(invoke(["hybrid", dd, rd, str(args.samples), str(args.iterations)], args.timeout))
    raw = {"inventory":inv, "corpus":[str(p.relative_to(ROOT)) for p in paths], "models":{"det_sha256":sha256(DET_MODEL),"rec_sha256":sha256(REC_MODEL)}, "matrix":matrix, "hybrids":hybrids, "timestamp_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    (RESULT_DIR / "openvino_ppocrv6.json").write_text(json.dumps(raw, indent=2, default=safe), encoding="utf-8")
    rows: list[dict[str, Any]] = []
    for r in matrix:
        rows.append({"kind":"probe", "stage":r.get("stage"), "device":r.get("device"), "returncode":r.get("returncode"), "compile_ms":r.get("compile_ms"), "warm_mean_ms":r.get("warm_infer",{}).get("mean_ms"), "warm_median_ms":r.get("warm_infer",{}).get("median_ms"), "rss_delta_compile_mb":r.get("rss_delta_compile_mb"), "unsupported_op_count":r.get("query_model",{}).get("unsupported_op_count"), "status":"ok" if r.get("returncode")==0 else r.get("status","blocked")})
    for r in hybrids:
        rows.append({"kind":"hybrid", "stage":"det->rec", "device":f"{r.get('det_device')}+{r.get('rec_device')}", "returncode":r.get("returncode"), "compile_ms":"", "warm_mean_ms":r.get("pipeline",{}).get("mean_ms"), "warm_median_ms":r.get("pipeline",{}).get("median_ms"), "rss_delta_compile_mb":"", "unsupported_op_count":"", "status":"ok" if r.get("returncode")==0 else r.get("status","blocked")})
    with (RESULT_DIR / "openvino_ppocrv6.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]) if rows else ["kind"]); writer.writeheader(); writer.writerows(rows)
    write_findings(raw)


def write_findings(raw: dict[str, Any]) -> None:
    inv = raw["inventory"]; rows = raw["matrix"]
    lines = ["---", "kind: review", 'title: "PP-OCRv6 OpenVINO laptop acceleration"', "comments: none", "---", "", "# OpenVINO / PP-OCRv6 findings", "", f"Date: {raw['timestamp_utc'][:10]}", "", "## Executive recommendation", "", "This is host evidence only. Do not infer Android, Snapdragon, Qualcomm GPU, or Qualcomm HTP support from the Intel device results. Prefer a route only when compile success, actual execution-device metadata, real-corpus latency, and ORT FP32 parity all pass.", "", "## Inventory", "", f"- OpenVINO: `{inv.get('openvino_version', 'unavailable')}`; available devices: `{inv.get('available_devices', [])}`.", f"- ONNX Runtime: `{inv.get('onnxruntime_version', 'unavailable')}`; providers: `{inv.get('onnxruntime_providers', [])}`.", f"- Corpus: `{len(raw['corpus'])}` real manga crops from `app/src/test/resources/corpus/aot/*/dynamic_out.png`.", "", "## Matrix", "", "Raw JSON: [`openvino_ppocrv6.json`](../results/openvino_ppocrv6.json). CSV: [`openvino_ppocrv6.csv`](../results/openvino_ppocrv6.csv).", "", "| stage | requested device | compile | execution devices | warm mean ms | unsupported ops | parity |", "|---|---|---:|---|---:|---:|---|"]
    for r in rows:
        if r.get("returncode") != 0:
            err = (r.get("error") or r.get("child_stderr") or "blocked").replace("\n", " ")[:180]
            lines.append(f"| {r.get('stage')} | `{r.get('device')}` | **BLOCKED** | — | — | — | {err} |")
            continue
        parity = r.get("parity", []); equal = sum(bool(x.get("text_equal", x.get("boxes_equal", False))) for x in parity); total = len(parity)
        lines.append(f"| {r.get('stage')} | `{r.get('device')}` | {r.get('compile_ms',0):.1f} | `{r.get('compiled_execution_devices', [])}` | {r.get('warm_infer',{}).get('mean_ms',0):.2f} | {r.get('query_model',{}).get('unsupported_op_count','?')} | {equal}/{total} |")
    lines += ["", "## Interpretation", "", "- `AUTO` is reported with its compiled execution-device metadata; a successful AUTO row is not an accelerator claim if it resolves to CPU.", "- NPU errors are retained verbatim in raw JSON. An Intel NPU plugin/driver error says nothing about Snapdragon or Qualcomm HTP.", "- Parity compares direct OpenVINO output with pristine ONNX Runtime CPU FP32 on the same preprocessed real-corpus input; text/box equality is supplementary to numeric max/mean absolute differences.", "- Hybrid rows are connected detector-to-recognizer runs: detector output boxes feed recognizer crops. They are not disconnected random-tensor timings.", "", "## Limits", "", "No Android packaging, ARM CPU, sustained thermal, Android RSS, or Qualcomm device was tested. Production sources and model assets were not modified.", ""]
    FINDING_FILE.parent.mkdir(parents=True, exist_ok=True); FINDING_FILE.write_text("\n".join(lines), encoding="utf-8")


def child_main(argv: list[str]) -> int:
    try:
        if argv[0] == "probe":
            stage, device, samples, warmup, iterations = argv[1], argv[2], int(argv[3]), int(argv[4]), int(argv[5])
            print(json.dumps(child_probe(stage, device, corpus_paths(samples), warmup, iterations), default=safe), flush=True)
        elif argv[0] == "hybrid":
            dd, rd, samples, iterations = argv[1], argv[2], int(argv[3]), int(argv[4])
            print(json.dumps(child_hybrid(dd, rd, corpus_paths(samples), iterations), default=safe), flush=True)
        else: raise ValueError(argv[0])
        return 0
    except Exception as exc:
        print(json.dumps({"evidence":"OBSERVED", "error_type":type(exc).__name__, "error":str(exc)}), flush=True)
        return 1


def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("--child", nargs=argparse.REMAINDER); parser.add_argument("--samples", type=int, default=8); parser.add_argument("--warmup", type=int, default=1); parser.add_argument("--iterations", type=int, default=3); parser.add_argument("--timeout", type=int, default=240); parser.add_argument("--devices", default="", help="comma-separated requested devices for a bounded matrix")
    args = parser.parse_args()
    if args.child: raise SystemExit(child_main(args.child))
    run(args)


if __name__ == "__main__": main()
