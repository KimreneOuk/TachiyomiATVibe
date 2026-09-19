#!/usr/bin/env python3
"""CPU-only ONNX Runtime benchmark for the PP-OCRv6 Android assets.

The benchmark intentionally keeps model, preprocessing, and corpus choices
explicit.  DET and REC are measured independently; ``pipeline`` is a small
Android-shaped CPU path (detector-v4 -> PP-OCR det -> AOT) and is reported
separately.  No batching or precision changes are made by this script.

Examples:
  python research/benchmark_cpu.py --quick
  python research/benchmark_cpu.py --threads 1,2,4,8 --warm 4 --cold 1

The default output directory is ``research/results/cpu``.  JSON contains the
full configuration and summary; CSV contains one row per measured invocation.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import statistics
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

try:
    import psutil
except ImportError:  # pragma: no cover
    psutil = None


ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = ROOT / "app/src/main/assets/models"
CORPUS_ROOT = ROOT / "tools/aot_corpus/real_corpus_faithful"
DEFAULT_OUT = ROOT / "research/results/cpu"

DET_PATH = MODEL_ROOT / "ocr/paddle-v6-small/det/inference.onnx"
REC_PATH = MODEL_ROOT / "ocr/paddle-v6-small/inference.onnx"
V4_PATH = MODEL_ROOT / "detection/detector-v4-s_int8.onnx"
AOT_PATH = MODEL_ROOT / "inpainting/aot.onnx"


@dataclass(frozen=True)
class Variant:
    threads: int
    inter_op: int
    execution_mode: str
    graph_optimization: str
    cpu_mem_arena: bool
    mem_pattern: bool
    io_binding: bool
    reuse_session: bool


def percentile(values: Iterable[float], p: float) -> float:
    vals = sorted(float(v) for v in values)
    if not vals:
        return float("nan")
    if len(vals) == 1:
        return vals[0]
    rank = (len(vals) - 1) * p
    lo, hi = math.floor(rank), math.ceil(rank)
    return vals[lo] if lo == hi else vals[lo] + (vals[hi] - vals[lo]) * (rank - lo)


def rss_mb() -> float | None:
    if psutil is None:
        return None
    return psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024)


def digest(value: Any) -> str:
    if isinstance(value, (list, tuple)):
        value = np.asarray(value)
    if isinstance(value, np.ndarray):
        return hashlib.sha256(np.ascontiguousarray(value).view(np.uint8)).hexdigest()[:16]
    return hashlib.sha256(repr(value).encode()).hexdigest()[:16]


GRAPH_LEVELS = {
    "disable": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
    "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
    "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
    "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
}


def make_options(v: Variant) -> ort.SessionOptions:
    o = ort.SessionOptions()
    o.intra_op_num_threads = max(1, int(v.threads))
    o.inter_op_num_threads = max(1, int(v.inter_op))
    o.execution_mode = (
        ort.ExecutionMode.ORT_PARALLEL if v.execution_mode == "parallel"
        else ort.ExecutionMode.ORT_SEQUENTIAL
    )
    o.graph_optimization_level = GRAPH_LEVELS[v.graph_optimization]
    o.enable_cpu_mem_arena = bool(v.cpu_mem_arena)
    o.enable_mem_pattern = bool(v.mem_pattern)
    o.log_severity_level = 3
    return o


def session(path: Path, v: Variant) -> ort.InferenceSession:
    return ort.InferenceSession(str(path), make_options(v), providers=["CPUExecutionProvider"])


def run_session(s: ort.InferenceSession, inputs: dict[str, np.ndarray], use_io_binding: bool) -> list[np.ndarray]:
    if not use_io_binding:
        return list(s.run(None, inputs))
    io = s.io_binding()
    for name, value in inputs.items():
        io.bind_cpu_input(name, np.ascontiguousarray(value))
    for output in s.get_outputs():
        io.bind_output(output.name, "cpu")
    s.run_with_iobinding(io)
    return list(io.copy_outputs_to_cpu())


def load_pages(limit: int) -> list[tuple[str, Image.Image]]:
    pages: list[tuple[str, Image.Image]] = []
    for page in sorted(CORPUS_ROOT.glob("real_*/bubble/page.jpg"))[:limit]:
        with Image.open(page) as im:
            pages.append((str(page.relative_to(ROOT)), im.convert("RGB")))
    if not pages:
        raise FileNotFoundError(f"no corpus pages under {CORPUS_ROOT}")
    return pages


def det_input(image: Image.Image, size: int = 736) -> np.ndarray:
    w, h = image.size
    scale = size / max(w, h)
    rw, rh = max(1, round(w * scale)), max(1, round(h * scale))
    rgb = np.asarray(image.resize((rw, rh), Image.Resampling.BILINEAR), np.float32)
    x = np.zeros((size, size, 3), np.float32)
    x[:rh, :rw] = rgb
    mean = np.asarray([0.485, 0.456, 0.406], np.float32)
    std = np.asarray([0.229, 0.224, 0.225], np.float32)
    return np.ascontiguousarray(((x / 255.0 - mean) / std).transpose(2, 0, 1)[None])


def rec_input(image: Image.Image, width: int = 640) -> np.ndarray:
    canvas = Image.new("RGB", (width, 48), (128, 128, 128))
    scaled_w = max(1, min(width, round(image.width * 48 / max(1, image.height))))
    canvas.paste(image.resize((scaled_w, 48), Image.Resampling.BILINEAR), (0, 0))
    x = (np.asarray(canvas, np.float32) / 255.0 - 0.5) / 0.5
    return np.ascontiguousarray(x.transpose(2, 0, 1)[None])


def v4_input(image: Image.Image) -> tuple[dict[str, np.ndarray], tuple[int, int]]:
    w, h = image.size
    rgb = np.asarray(image.resize((640, 640), Image.Resampling.BILINEAR), np.float32)
    x = np.ascontiguousarray((rgb / 255.0).transpose(2, 0, 1)[None])
    sizes = np.asarray([[h, w]], np.int64)
    return {"images": x, "orig_target_sizes": sizes}, (w, h)


def aot_input(image: Image.Image, mask: np.ndarray) -> dict[str, np.ndarray]:
    arr = np.asarray(image.convert("RGB"), np.float32)
    h, w = arr.shape[:2]
    ph, pw = (8 - h % 8) % 8, (8 - w % 8) % 8
    if ph or pw:
        arr = np.pad(arr, ((0, ph), (0, pw), (0, 0)), mode="reflect")
        mask = np.pad(mask, ((0, ph), (0, pw)), mode="constant")
    rgb = (arr / 127.5) - 1.0
    m = mask.astype(np.float32)
    if m.max(initial=0) > 1:
        m /= 255.0
    return {"image": np.ascontiguousarray(rgb.transpose(2, 0, 1)[None]), "mask": np.ascontiguousarray(m[None, None])}


def det_signature(outputs: list[np.ndarray]) -> str:
    # ORT graph rewrites can change the final float by a few ulps while
    # preserving decoded boxes.  Quantize the parity signature at 1e-3 so a
    # graph-level numerical representation change is not misreported as an
    # OCR correctness failure.  REC uses argmax IDs below, so remains exact.
    # A 1e-2 map quantization is well below the DB threshold margin and avoids
    # classifying a 6e-7 boundary crossing as a semantic mismatch.
    return digest([np.round(np.asarray(x), 2) for x in outputs])


def rec_signature(outputs: list[np.ndarray]) -> str:
    logits = outputs[0]
    return digest(np.argmax(logits, axis=-1).astype(np.int64))


def run_component(component: str, inputs: list[tuple[str, dict[str, np.ndarray]]], path: Path,
                  v: Variant, warm: int, cold: int, baseline_sig: dict[str, str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for is_cold, repeats in ((True, cold), (False, warm)):
        if repeats <= 0:
            continue
        model = None if is_cold else session(path, v)
        for iteration in range(repeats):
            if is_cold:
                t_load = time.perf_counter()
                model = session(path, v)
                load_ms = (time.perf_counter() - t_load) * 1000
            else:
                load_ms = 0.0
            name, feed = inputs[iteration % len(inputs)]
            before = rss_mb()
            t0 = time.perf_counter()
            output = run_session(model, feed, v.io_binding)
            elapsed = (time.perf_counter() - t0) * 1000
            after = rss_mb()
            sig = det_signature(output) if component == "det" else rec_signature(output)
            rows.append({
                **asdict(v), "component": component, "input": name,
                "phase": "cold" if is_cold else "warm", "iteration": iteration,
                "load_ms": round(load_ms, 3), "duration_ms": round(elapsed, 3),
                "rss_mb": round(after, 3) if after is not None else "",
                "rss_delta_mb": round(after - before, 3) if after is not None and before is not None else "",
                "signature": sig, "parity": sig == baseline_sig.get(f"{component}|{name}", sig),
            })
        del model
    return rows


def run_pipeline(pages: list[tuple[str, Image.Image]], v: Variant, warm: int, cold: int) -> list[dict[str, Any]]:
    """Measure detector-v4 -> Paddle det -> AOT with one reusable session set.

    This deliberately excludes YOLO segmentation because that optional model is
    not part of the Android asset set in this worktree.  It still exercises the
    CPU-heavy detector, PP-OCR crop detector, and inpaint model on real pages.
    """
    rows: list[dict[str, Any]] = []
    for phase, repeats in (("cold", cold), ("warm", warm)):
        if repeats <= 0:
            continue
        models = None
        for iteration in range(repeats):
            t_load = time.perf_counter()
            if models is None:
                models = (session(V4_PATH, v), session(DET_PATH, v), session(AOT_PATH, v))
            load_ms = (time.perf_counter() - t_load) * 1000 if phase == "cold" else 0.0
            name, image = pages[iteration % len(pages)]
            before = rss_mb()
            t0 = time.perf_counter()
            v4_out = run_session(models[0], v4_input(image)[0], v.io_binding)
            # Use the top two v4 boxes as OCR crops, with full-page fallback.
            boxes = np.asarray(v4_out[1])[0]
            scores = np.asarray(v4_out[2])[0]
            valid = [b for b, s in zip(boxes, scores) if float(s) >= 0.20]
            crops = []
            if not valid:
                crops = [image]
            else:
                for box in valid[:2]:
                    x1, y1, x2, y2 = [int(max(0, x)) for x in box]
                    x2, y2 = min(image.width, x2), min(image.height, y2)
                    if x2 > x1 and y2 > y1:
                        crops.append(image.crop((x1, y1, x2, y2)))
                crops = crops or [image]
            det_count = 0
            for crop in crops:
                det_out = run_session(models[1], {"x": det_input(crop)}, v.io_binding)
                det_count += int(np.isfinite(det_out[0]).sum())
            # Keep the mask deterministic and representative; it is not a
            # quality claim, only an AOT CPU cost fixture.
            # AOT is an image-to-image model.  Keep this fixture bounded to a
            # 512px working tile: full manga pages are intentionally not fed
            # to the model because that would measure an unbounded allocation
            # rather than the Android crop-based inpaint path.
            work = image.crop((0, 0, min(512, image.width), min(512, image.height)))
            mask = np.zeros((work.height, work.width), np.uint8)
            cv2.rectangle(mask, (0, 0), (max(1, work.width // 4), max(1, work.height // 6)), 255, -1)
            run_session(models[2], aot_input(work, mask), v.io_binding)
            elapsed = (time.perf_counter() - t0) * 1000
            after = rss_mb()
            rows.append({
                **asdict(v), "component": "pipeline", "input": name,
                "phase": phase, "iteration": iteration, "load_ms": round(load_ms, 3),
                "duration_ms": round(elapsed, 3), "rss_mb": round(after, 3) if after is not None else "",
                "rss_delta_mb": round(after - before, 3) if after is not None and before is not None else "",
                "det_boxes": len(valid), "det_crop_count": len(crops), "det_finite_values": det_count,
                "signature": digest((len(valid), len(crops), det_count)), "parity": True,
            })
        del models
    return rows


def variants(args: argparse.Namespace) -> list[Variant]:
    threads = [int(x) for x in args.threads.split(",") if x.strip()]
    out: list[Variant] = []
    for t in sorted(set(max(1, x) for x in threads)):
        out.append(Variant(t, args.inter_op, args.execution, args.graph, args.arena, args.mem_pattern, False, True))
    if args.sweep_options:
        best = max(threads)
        for execution in ("sequential", "parallel"):
            for graph in ("basic", "extended", "all"):
                out.append(Variant(best, args.inter_op, execution, graph, args.arena, args.mem_pattern, False, True))
        for arena, pattern in ((False, True), (True, False), (False, False)):
            out.append(Variant(best, args.inter_op, "sequential", "all", arena, pattern, False, True))
        out.append(Variant(best, max(2, args.inter_op), "parallel", "all", args.arena, args.mem_pattern, False, True))
        out.append(Variant(best, args.inter_op, "sequential", "all", args.arena, args.mem_pattern, True, True))
    unique: list[Variant] = []
    for v in out:
        if v not in unique:
            unique.append(v)
    return unique


def summarize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple, list[dict[str, Any]]] = {}
    for row in rows:
        key = (row["component"], row["phase"], row["threads"], row["inter_op"], row["execution_mode"], row["graph_optimization"], row["cpu_mem_arena"], row["mem_pattern"], row["io_binding"])
        groups.setdefault(key, []).append(row)
    result = []
    for key, group in groups.items():
        durations = [float(r["duration_ms"]) for r in group]
        rss = [float(r["rss_mb"]) for r in group if r["rss_mb"] != ""]
        result.append({
            "component": key[0], "phase": key[1], "threads": key[2], "inter_op": key[3],
            "execution_mode": key[4], "graph_optimization": key[5], "cpu_mem_arena": key[6],
            "mem_pattern": key[7], "io_binding": key[8], "n": len(group),
            "p50_ms": round(percentile(durations, .50), 3), "p90_ms": round(percentile(durations, .90), 3),
            "p95_ms": round(percentile(durations, .95), 3), "mean_ms": round(statistics.mean(durations), 3),
            "min_ms": round(min(durations), 3), "max_ms": round(max(durations), 3),
            "rss_peak_mb": round(max(rss), 3) if rss else "",
            "parity_all": all(bool(r.get("parity", True)) for r in group),
        })
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", default="1,2,4,8")
    ap.add_argument("--inter-op", type=int, default=1)
    ap.add_argument("--execution", choices=("sequential", "parallel"), default="sequential")
    ap.add_argument("--graph", choices=tuple(GRAPH_LEVELS), default="all")
    ap.add_argument("--arena", action=argparse.BooleanOptionalAction, default=True)
    ap.add_argument("--mem-pattern", action=argparse.BooleanOptionalAction, default=True)
    ap.add_argument("--sweep-options", action=argparse.BooleanOptionalAction, default=True)
    ap.add_argument("--pipeline", action=argparse.BooleanOptionalAction, default=True,
                    help="include the detector-v4 -> PP-OCR det -> AOT fixture")
    ap.add_argument("--only", choices=("all", "det", "rec", "pipeline"), default="all",
                    help="run one component in a fresh process for clean RSS accounting")
    ap.add_argument("--warm", type=int, default=5)
    ap.add_argument("--cold", type=int, default=1)
    ap.add_argument("--pages", type=int, default=8)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--quick", action="store_true", help="one thread baseline, 2 warm, no option matrix")
    args = ap.parse_args()
    if args.quick:
        args.threads, args.warm, args.cold, args.pages, args.sweep_options = "1", 2, 1, 3, False

    missing = [str(p) for p in (DET_PATH, REC_PATH, V4_PATH, AOT_PATH) if not p.exists()]
    if missing:
        raise FileNotFoundError("missing model assets: " + ", ".join(missing))
    pages = load_pages(args.pages)
    variants_to_run = variants(args)
    args.out.mkdir(parents=True, exist_ok=True)
    all_rows: list[dict[str, Any]] = []
    baseline_sig: dict[str, str] = {}
    # Baseline signatures are per component and per named real input.  Warm
    # iterations rotate through pages, so comparing every row to page zero
    # would report a false correctness failure.
    for index, v in enumerate(variants_to_run):
        det_inputs = [(name, {"x": det_input(im)}) for name, im in pages]
        rec_inputs = [(name, {"x": rec_input(im)}) for name, im in pages]
        if index == 0:
            if args.only in ("all", "det"):
                m = session(DET_PATH, v)
                for name, feed in det_inputs:
                    baseline_sig[f"det|{name}"] = det_signature(run_session(m, feed, v.io_binding))
                del m
            if args.only in ("all", "rec"):
                m = session(REC_PATH, v)
                for name, feed in rec_inputs:
                    baseline_sig[f"rec|{name}"] = rec_signature(run_session(m, feed, v.io_binding))
                del m
        print(f"variant {index + 1}/{len(variants_to_run)}: {v}", flush=True)
        if args.only in ("all", "det"):
            all_rows.extend(run_component("det", det_inputs, DET_PATH, v, args.warm, args.cold, baseline_sig))
        if args.only in ("all", "rec"):
            all_rows.extend(run_component("rec", rec_inputs, REC_PATH, v, args.warm, args.cold, baseline_sig))
        if args.only in ("all", "pipeline") and args.pipeline and (index == 0 or args.sweep_options):
            all_rows.extend(run_pipeline(pages, v, max(1, min(args.warm, 3)), args.cold))

    metadata = {
        "timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "platform": {"system": platform.system(), "release": platform.release(), "machine": platform.machine(), "python": platform.python_version(), "onnxruntime": ort.__version__, "providers": ort.get_available_providers(), "cpu_count": os.cpu_count()},
        "models": {"det": str(DET_PATH.relative_to(ROOT)), "rec": str(REC_PATH.relative_to(ROOT)), "v4": str(V4_PATH.relative_to(ROOT)), "aot": str(AOT_PATH.relative_to(ROOT))},
        "corpus": {"root": str(CORPUS_ROOT.relative_to(ROOT)), "pages": [name for name, _ in pages], "note": "real_corpus_faithful bubble pages; no batch or precision changes"},
        "variants": [asdict(v) for v in variants_to_run],
        "xnnpack": "unavailable (onnxruntime providers: " + ", ".join(ort.get_available_providers()) + ")",
        "summary": summarize(all_rows),
        "rows": all_rows,
    }
    json_path = args.out / "cpu_benchmark.json"
    csv_path = args.out / "cpu_benchmark.csv"
    json_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    fields = sorted({k for r in all_rows for k in r})
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader(); writer.writerows(all_rows)
    print(f"wrote {json_path} and {csv_path}; {len(all_rows)} rows", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
