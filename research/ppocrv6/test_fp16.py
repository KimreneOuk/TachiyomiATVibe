#!/usr/bin/env python3
"""Reproducible PP-OCRv6 FP16 conversion and runtime benchmark.

This is deliberately a research harness, not production code.  It compares
the checked-in FP32 PP-OCRv6 small detector/recognizer with ONNX Runtime's
float16 converter variants and, when installed, OpenVINO.

Examples:
  python research/test_fp16.py --out-dir research/fp16-results
  python research/test_fp16.py --image-dir path/to/pages --out-dir research/fp16-results

The no-image run is still useful for conversion, model-load, tensor-output,
and warm-runtime checks.  Real page/crop OCR parity is explicitly reported as
UNTESTED when no image directory is provided.
"""

from __future__ import annotations

import argparse
import csv
import gc
import hashlib
import json
import math
import os
import platform
import shutil
import statistics
import sys
import threading
import time
from pathlib import Path
from typing import Any, Iterable

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DET = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"
DEFAULT_REC = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx"
DEFAULT_VOCAB = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt"


def optional_imports() -> dict[str, Any]:
    out: dict[str, Any] = {}
    try:
        import onnx  # type: ignore
        out["onnx"] = onnx
    except Exception as exc:  # pragma: no cover - environment dependent
        out["onnx_error"] = repr(exc)
    try:
        import onnxruntime as ort  # type: ignore
        out["ort"] = ort
    except Exception as exc:  # pragma: no cover - environment dependent
        out["ort_error"] = repr(exc)
    try:
        from onnxruntime.transformers.float16 import convert_float_to_float16  # type: ignore
        out["convert_fp16"] = convert_float_to_float16
    except Exception as exc:  # pragma: no cover - environment dependent
        out["convert_fp16_error"] = repr(exc)
    try:
        import openvino as ov  # type: ignore
        out["openvino"] = ov
    except Exception as exc:  # pragma: no cover - environment dependent
        out["openvino_error"] = repr(exc)
    try:
        import cv2  # type: ignore
        out["cv2"] = cv2
    except Exception as exc:  # pragma: no cover - environment dependent
        out["cv2_error"] = repr(exc)
    try:
        import psutil  # type: ignore
        out["psutil"] = psutil
    except Exception as exc:  # pragma: no cover - environment dependent
        out["psutil_error"] = repr(exc)
    return out


LIBS = optional_imports()


def jsonable(value: Any) -> Any:
    if isinstance(value, (np.integer, np.floating)):
        return value.item()
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    return value


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def rss_bytes() -> int | None:
    psutil = LIBS.get("psutil")
    if psutil is None:
        return None
    try:
        return int(psutil.Process(os.getpid()).memory_info().rss)
    except Exception:
        return None


class RssSampler:
    def __init__(self, interval: float = 0.005) -> None:
        self.interval = interval
        self.max_rss = rss_bytes()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def __enter__(self) -> "RssSampler":
        def sample() -> None:
            while not self._stop.is_set():
                current = rss_bytes()
                if current is not None and (self.max_rss is None or current > self.max_rss):
                    self.max_rss = current
                self._stop.wait(self.interval)

        self._thread = threading.Thread(target=sample, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_: Any) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=1)


def make_synthetic_inputs(kind: str, count: int = 4) -> list[np.ndarray]:
    rng = np.random.default_rng(20260918)
    if kind == "det":
        # A deterministic page-like image: gradients, blocks and thin lines.
        base = np.zeros((960, 960, 3), np.float32)
        yy, xx = np.mgrid[:960, :960]
        base[..., 0] = (xx % 255) / 255.0
        base[..., 1] = (yy % 255) / 255.0
        base[..., 2] = ((xx + yy) % 255) / 255.0
        base[100:180, 100:800] = 0.1
        base[400:470, 120:850] = 0.9
        samples = [base]
        samples.extend(rng.random((960, 960, 3), dtype=np.float32) for _ in range(count - 1))
        return [np.transpose(x, (2, 0, 1))[None, ...] if x.ndim == 3 else x[None, ...] for x in samples]
    samples = []
    for i in range(count):
        x = rng.random((3, 48, 320), dtype=np.float32)
        x[:, 10 + i : 18 + i, 20:280] = 0.05
        samples.append(x[None, ...])
    return samples


def image_files(directory: Path | None) -> list[Path]:
    if directory is None or not directory.exists():
        return []
    return sorted(p for p in directory.rglob("*") if p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".bmp"} and p.name.lower() not in {"mask.png", "mask.jpg"})


def prepare_image(path: Path, kind: str, box: list[int] | tuple[int, int, int, int] | None = None, det_size: int = 960) -> np.ndarray:
    cv2 = LIBS.get("cv2")
    if cv2 is None:
        raise RuntimeError("opencv-python is required for image benchmarks")
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"cannot decode image: {path}")
    if box is not None:
        x1, y1, x2, y2 = [int(v) for v in box]
        image = image[max(0, y1):max(0, y2), max(0, x1):max(0, x2)]
        if image.size == 0:
            raise ValueError(f"empty crop {box} in {path}")
    if kind == "det":
        image = cv2.resize(image, (det_size, det_size), interpolation=cv2.INTER_LINEAR)
        image = image[:, :, ::-1].astype(np.float32) / 255.0
        return np.transpose(image, (2, 0, 1))[None, ...]
    h, w = image.shape[:2]
    target_w = min(320, max(8, int(round(48 * w / max(h, 1)))))
    image = cv2.resize(image, (target_w, 48), interpolation=cv2.INTER_LINEAR)
    image = image[:, :, ::-1].astype(np.float32) / 255.0
    image = (image - 0.5) / 0.5
    padded = np.zeros((48, 320, 3), np.float32)
    padded[:, :target_w] = image
    return np.transpose(padded, (2, 0, 1))[None, ...]


def input_dtype(session: Any, backend: str) -> np.dtype:
    if backend == "ort":
        typ = session.get_inputs()[0].type
        return np.float16 if "float16" in typ else np.float32
    typ = str(session.inputs[0].get_element_type())
    return np.float16 if "f16" in typ.lower() else np.float32


class Runner:
    def __init__(self, path: Path, backend: str) -> None:
        self.path = path
        self.backend = backend
        self.session: Any = None
        self.input_name = "x"
        if backend == "ort":
            ort = LIBS.get("ort")
            if ort is None:
                raise RuntimeError("onnxruntime is unavailable")
            opts = ort.SessionOptions()
            # ORT_ENABLE_ALL currently rejects a precision-free cast helper
            # emitted for PP-OCRv6 REC; BASIC avoids that optimizer-only
            # failure while keeping all variants on the same runtime path.
            opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
            self.session = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
            self.input_name = self.session.get_inputs()[0].name
        elif backend == "openvino":
            ov = LIBS.get("openvino")
            if ov is None:
                raise RuntimeError("openvino is unavailable")
            core = ov.Core()
            model = core.read_model(str(path))
            self.session = core.compile_model(model, "CPU")
            self.input_name = self.session.inputs[0].any_name
        else:
            raise ValueError(backend)

    def run(self, array: np.ndarray) -> np.ndarray:
        array = array.astype(input_dtype(self.session, self.backend), copy=False)
        if self.backend == "ort":
            return np.asarray(self.session.run(None, {self.input_name: array})[0])
        result = self.session({self.input_name: array})
        return np.asarray(next(iter(result.values())))


def output_stats(a: np.ndarray, b: np.ndarray) -> dict[str, Any]:
    af = a.astype(np.float32)
    bf = b.astype(np.float32)
    diff = np.abs(af - bf)
    denom = np.maximum(np.abs(af), 1e-8)
    flat_a, flat_b = af.ravel(), bf.ravel()
    cosine = float(np.dot(flat_a, flat_b) / (np.linalg.norm(flat_a) * np.linalg.norm(flat_b) + 1e-12))
    return {
        "shape_equal": list(a.shape) == list(b.shape),
        "dtype_baseline": str(a.dtype),
        "dtype_variant": str(b.dtype),
        "max_abs": float(diff.max(initial=0.0)),
        "mean_abs": float(diff.mean()),
        "max_rel": float((diff / denom).max(initial=0.0)),
        "rmse": float(np.sqrt(np.mean(np.square(af - bf)))),
        "cosine": cosine,
    }


def ctc_decode(output: np.ndarray, vocab: list[str]) -> list[str]:
    # PP-OCRv6 REC output is [N, time, classes].  Index 0 is CTC blank.
    if output.ndim != 3:
        return []
    indices = np.argmax(output, axis=2)
    texts: list[str] = []
    for row in indices:
        chars: list[str] = []
        previous = -1
        for index in row.tolist():
            if index != 0 and index != previous and index < len(vocab):
                chars.append(vocab[index])
            previous = index
        texts.append("".join(chars))
    return texts


def det_boxes(output: np.ndarray, threshold: float = 0.3) -> list[tuple[int, int, int, int]]:
    cv2 = LIBS.get("cv2")
    if cv2 is None or output.ndim != 4:
        return []
    mask = (output[0, 0] >= threshold).astype(np.uint8)
    count, _, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    boxes: list[tuple[int, int, int, int]] = []
    for x, y, w, h, area in stats[1:]:
        if area >= 4:
            boxes.append((int(x), int(y), int(x + w), int(y + h)))
    return boxes


def iou(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    x1, y1, x2, y2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    ua = (a[2] - a[0]) * (a[3] - a[1])
    ub = (b[2] - b[0]) * (b[3] - b[1])
    return float(inter / max(ua + ub - inter, 1))


def box_parity(a: np.ndarray, b: np.ndarray) -> dict[str, Any]:
    boxes_a, boxes_b = det_boxes(a), det_boxes(b)
    matches = [max((iou(x, y) for y in boxes_b), default=0.0) for x in boxes_a]
    return {
        "baseline_box_count": len(boxes_a),
        "variant_box_count": len(boxes_b),
        "matched_iou_mean": float(statistics.mean(matches)) if matches else 1.0 if not boxes_b else 0.0,
        "matched_iou_min": float(min(matches)) if matches else 1.0 if not boxes_b else 0.0,
        "box_count_equal": len(boxes_a) == len(boxes_b),
    }


def convert_model(src: Path, dst: Path, keep_io_types: bool) -> dict[str, Any]:
    convert = LIBS.get("convert_fp16")
    result: dict[str, Any] = {
        "source": str(src), "target": str(dst), "keep_io_types": keep_io_types,
        "source_bytes": src.stat().st_size, "status": "FAILED",
    }
    if convert is None:
        result["error"] = LIBS.get("convert_fp16_error", "onnxruntime float16 converter unavailable")
        return result
    try:
        import onnx  # type: ignore
        # Passing a path makes ORT's converter create a temporary inferred
        # model next to the source.  Checked-in Android assets may be
        # read-only, so load in memory and write only to our output folder.
        model = convert(onnx.load(str(src)), keep_io_types=keep_io_types)
        # Recent ORT releases occasionally append the input Cast after the
        # first consumer when keep_io_types=True.  Normalize node order before
        # checking/saving; this is a graph-order repair, not a numeric change.
        graph = model.graph
        available = {x.name for x in graph.input} | {x.name for x in graph.initializer} | {x.name for x in graph.sparse_initializer}
        pending = list(graph.node)
        ordered = []
        while pending:
            progress = False
            for node in list(pending):
                if all((not name) or (name in available) for name in node.input):
                    ordered.append(node)
                    pending.remove(node)
                    available.update(x for x in node.output if x)
                    progress = True
            if not progress:
                raise ValueError("unable to topologically sort converted ONNX graph")
        del graph.node[:]
        graph.node.extend(ordered)
        onnx.checker.check_model(model)
        dst.parent.mkdir(parents=True, exist_ok=True)
        onnx.save(model, str(dst))
        result.update({"status": "CONFIRMED", "target_bytes": dst.stat().st_size, "sha256": sha256(dst)})
    except Exception as exc:
        result["error"] = repr(exc)
    return result


def load_vocab(path: Path) -> list[str]:
    if not path.exists():
        return [""]
    return [line.rstrip("\r\n") for line in path.read_text(encoding="utf-8").splitlines()]


def benchmark(runner: Runner, samples: list[np.ndarray], warmup: int, runs: int, kind: str, vocab: list[str]) -> dict[str, Any]:
    for sample in samples[:1]:
        runner.run(sample)
    for _ in range(warmup):
        runner.run(samples[0])
    latencies: list[float] = []
    rss_before = rss_bytes()
    with RssSampler() as sampler:
        outputs = []
        for i in range(runs):
            start = time.perf_counter()
            outputs.append(runner.run(samples[i % len(samples)]))
            latencies.append((time.perf_counter() - start) * 1000.0)
    rss_after = rss_bytes()
    result = {
        "status": "CONFIRMED",
        "runs": runs,
        "warmup": warmup,
        "latency_ms_mean": float(statistics.mean(latencies)),
        "latency_ms_p50": float(statistics.median(latencies)),
        "latency_ms_p95": float(np.percentile(latencies, 95)),
        "throughput_per_s": float(1000.0 / statistics.mean(latencies)),
        "rss_before_bytes": rss_before,
        "rss_after_bytes": rss_after,
        "rss_peak_bytes": sampler.max_rss,
        "output_shape": list(outputs[-1].shape),
        "output_dtype": str(outputs[-1].dtype),
        "decoded_sequences": ctc_decode(outputs[-1], vocab) if kind == "rec" else None,
        "det_box_count": len(det_boxes(outputs[-1])) if kind == "det" else None,
    }
    return result


def run_case(kind: str, model_paths: dict[str, Path], backend: str, samples: list[np.ndarray], warmup: int, runs: int, vocab: list[str], label: str) -> dict[str, Any]:
    record: dict[str, Any] = {"kind": kind, "backend": backend, "variant": label, "model": str(model_paths[kind])}
    try:
        gc.collect()
        runner = Runner(model_paths[kind], backend)
        record.update(benchmark(runner, samples, warmup, runs, kind, vocab))
        return record
    except Exception as exc:
        record.update({"status": "FAILED", "error": repr(exc)})
        return record


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--det", type=Path, default=DEFAULT_DET)
    p.add_argument("--rec", type=Path, default=DEFAULT_REC)
    p.add_argument("--vocab", type=Path, default=DEFAULT_VOCAB)
    p.add_argument("--image-dir", type=Path, default=None, help="optional real manga pages/crops")
    p.add_argument("--manifest", type=Path, default=None, help="fixed dataset manifest with page paths and annotated crop boxes")
    p.add_argument("--max-real", type=int, default=16, help="maximum pages and regions sampled from --manifest (legacy alias)")
    p.add_argument("--max-pages", type=int, default=None, help="maximum real pages; defaults to --max-real")
    p.add_argument("--max-crops", type=int, default=None, help="maximum annotated real crops; defaults to --max-real")
    p.add_argument("--det-size", type=int, default=960, help="square side used for real-page detector probes (960 matches production; lower values are exploratory)")
    p.add_argument("--out-dir", type=Path, default=ROOT / "research/fp16-results")
    p.add_argument("--backend", choices=["ort", "openvino", "all"], default="all")
    p.add_argument("--warmup", type=int, default=3)
    p.add_argument("--runs", type=int, default=10)
    p.add_argument("--skip-convert", action="store_true")
    return p.parse_args()


def manifest_samples(path: Path | None, max_pages: int, max_crops: int) -> tuple[list[Path], list[tuple[Path, list[int], str]]]:
    """Return real page paths and annotated region crops without copying assets."""
    if path is None or not path.exists():
        return [], []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return [], []
    pages: list[Path] = []
    crops: list[tuple[Path, list[int], str]] = []
    for chapter in data.get("chapters", []):
        for page in chapter.get("pages", []):
            page_path = Path(page.get("source_path", ""))
            if page_path.exists() and len(pages) < max_pages:
                pages.append(page_path)
            for region in page.get("regions", []):
                if len(crops) >= max_crops:
                    break
                region_box = region.get("ocr_box") or region.get("box")
                if page_path.exists() and region_box:
                    crops.append((page_path, region_box, str(region.get("id", len(crops)))))
            if len(pages) >= max_pages and len(crops) >= max_crops:
                return pages, crops
    return pages, crops


def main() -> int:
    args = parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    backends = ["ort", "openvino"] if args.backend == "all" else [args.backend]
    models = {"det": args.det, "rec": args.rec}
    vocab = load_vocab(args.vocab)
    max_pages = args.max_pages if args.max_pages is not None else args.max_real
    max_crops = args.max_crops if args.max_crops is not None else args.max_real
    manifest_pages, manifest_crops = manifest_samples(args.manifest, max_pages, max_crops)
    real_pages = (image_files(args.image_dir)[:max_pages] if args.image_dir else manifest_pages)
    real_crops = manifest_crops
    real_available = bool(real_pages or real_crops)
    manifest: dict[str, Any] = {
        "status": "CONFIRMED", "timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "python": sys.version, "platform": platform.platform(), "libraries": {k: str(v) for k, v in LIBS.items() if not k.endswith("_error")},
        "library_errors": {k: v for k, v in LIBS.items() if k.endswith("_error")},
        "inputs": {k: {"path": str(v), "bytes": v.stat().st_size, "sha256": sha256(v)} for k, v in models.items() if v.exists()},
        "image_files": [str(p) for p in real_pages],
        "manifest": str(args.manifest) if args.manifest else None,
        "real_page_count": len(real_pages), "real_crop_count": len(real_crops),
        "real_probe_det_size": args.det_size,
        "real_image_status": "UNTESTED" if not real_available else "CONFIRMED",
        "conversion": [], "load_and_runtime": [], "parity": [], "mixed_combinations": [],
    }
    converted: dict[str, dict[str, Path]] = {"fp16_io": {}, "fp16_keep_io": {}}
    if not args.skip_convert:
        for kind, src in models.items():
            for label, keep_io in [("fp16_io", False), ("fp16_keep_io", True)]:
                dst = args.out_dir / "models" / f"{kind}_{label}.onnx"
                conv = convert_model(src, dst, keep_io)
                conv["kind"], conv["variant"] = kind, label
                manifest["conversion"].append(conv)
                if conv["status"] == "CONFIRMED":
                    converted[label][kind] = dst
    else:
        for label in converted:
            for kind in models:
                dst = args.out_dir / "models" / f"{kind}_{label}.onnx"
                if dst.exists():
                    converted[label][kind] = dst
                    manifest["conversion"].append({
                        "source": str(models[kind]), "target": str(dst), "kind": kind,
                        "variant": label, "keep_io_types": label == "fp16_keep_io",
                        "source_bytes": models[kind].stat().st_size,
                        "target_bytes": dst.stat().st_size, "sha256": sha256(dst),
                        "status": "CONFIRMED", "mode": "reused-existing-output",
                    })

    samples = {"det": make_synthetic_inputs("det"), "rec": make_synthetic_inputs("rec")}
    for kind in ("det", "rec"):
        runtime_sets: dict[str, Path] = {"fp32": models[kind]}
        for label, paths in converted.items():
            if kind in paths:
                runtime_sets[label] = paths[kind]
        for label, model_path in runtime_sets.items():
            if model_path is None:
                continue
            for backend in backends:
                manifest["load_and_runtime"].append(run_case(kind, {kind: model_path}, backend, samples[kind], args.warmup, args.runs, vocab, label))

        # Compare each converted model with FP32 on fixed tensors.
        for label, paths in converted.items():
            variant_path = paths.get(kind)
            if variant_path is None:
                continue
            for backend in backends:
                try:
                    base_runner, var_runner = Runner(models[kind], backend), Runner(variant_path, backend)
                    for index, sample in enumerate(samples[kind]):
                        base_out = base_runner.run(sample)
                        var_out = var_runner.run(sample)
                        parity = output_stats(base_out, var_out)
                        parity.update({"kind": kind, "backend": backend, "variant": label, "sample": index})
                        if kind == "rec":
                            bt, vt = ctc_decode(base_out, vocab), ctc_decode(var_out, vocab)
                            parity.update({"baseline_text": bt, "variant_text": vt, "exact_sequence_equal": bt == vt})
                        else:
                            parity.update(box_parity(base_out, var_out))
                        manifest["parity"].append(parity)
                except Exception as exc:
                    manifest["parity"].append({"kind": kind, "backend": backend, "variant": label, "status": "FAILED", "error": repr(exc)})

    # Mixed combinations are represented explicitly even though the models are
    # separate ONNX graphs: this prevents approving both-FP16 from size alone.
    for backend in backends:
        for combo, det_label, rec_label in [("fp32/fp32", "fp32", "fp32"), ("fp16-det/fp32-rec", "fp16_keep_io", "fp32"), ("fp32-det/fp16-rec", "fp32", "fp16_keep_io"), ("fp16-det/fp16-rec", "fp16_keep_io", "fp16_keep_io")]:
            if det_label != "fp32" and "det" not in converted.get(det_label, {}):
                manifest["mixed_combinations"].append({"backend": backend, "combination": combo, "status": "FAILED", "error": "det conversion unavailable"})
                continue
            if rec_label != "fp32" and "rec" not in converted.get(rec_label, {}):
                manifest["mixed_combinations"].append({"backend": backend, "combination": combo, "status": "FAILED", "error": "rec conversion unavailable"})
                continue
            det_path = models["det"] if det_label == "fp32" else converted[det_label]["det"]
            rec_path = models["rec"] if rec_label == "fp32" else converted[rec_label]["rec"]
            det = run_case("det", {"det": det_path}, backend, samples["det"], args.warmup, args.runs, vocab, combo)
            rec = run_case("rec", {"rec": rec_path}, backend, samples["rec"], args.warmup, args.runs, vocab, combo)
            manifest["mixed_combinations"].append({"backend": backend, "combination": combo, "status": "CONFIRMED" if det.get("status") == rec.get("status") == "CONFIRMED" else "FAILED", "det": det, "rec": rec})

    # Real page and annotated-crop parity.  A manifest gives REC actual
    # regions rather than feeding full pages through the recognizer.
    if real_available:
        real_items = {
            "det": [(image, None, "page") for image in real_pages],
            "rec": ([(image, box, f"crop:{region_id}") for image, box, region_id in real_crops]
                    if real_crops else [(image, None, "page") for image in real_pages]),
        }
        real_runners: dict[tuple[str, str, str], Runner] = {}
        for kind in ("det", "rec"):
            for backend in backends:
                try:
                    real_runners[(kind, backend, "fp32")] = Runner(models[kind], backend)
                    for label, paths in converted.items():
                        if kind in paths:
                            real_runners[(kind, backend, label)] = Runner(paths[kind], backend)
                except Exception:
                    # The per-item loop records a FAILED row with the useful
                    # exception if a backend cannot load a particular variant.
                    pass
        for kind in ("det", "rec"):
            for image, box, corpus_item in real_items[kind]:
                sample = prepare_image(image, kind, box=box, det_size=args.det_size)
                for backend in backends:
                    try:
                        base = real_runners.get((kind, backend, "fp32")) or Runner(models[kind], backend)
                        base_out = base.run(sample)
                        for label, paths in converted.items():
                            if kind not in paths:
                                continue
                            variant = real_runners.get((kind, backend, label)) or Runner(paths[kind], backend)
                            var_out = variant.run(sample)
                            record = output_stats(base_out, var_out)
                            record.update({"kind": kind, "backend": backend, "variant": label, "image": str(image), "corpus_item": corpus_item, "status": "CONFIRMED"})
                            if kind == "rec":
                                bt, vt = ctc_decode(base_out, vocab), ctc_decode(var_out, vocab)
                                record.update({"baseline_text": bt, "variant_text": vt, "exact_sequence_equal": bt == vt})
                            else:
                                record.update(box_parity(base_out, var_out))
                            manifest.setdefault("real_image_parity", []).append(record)
                    except Exception as exc:
                        manifest.setdefault("real_image_parity", []).append({"kind": kind, "backend": backend, "variant": "all", "image": str(image), "corpus_item": corpus_item, "status": "FAILED", "error": repr(exc)})
    else:
        manifest["real_image_parity"] = [{"status": "UNTESTED", "reason": "--image-dir/--manifest was not supplied or contained no supported images"}]

    (args.out_dir / "results.json").write_text(json.dumps(jsonable(manifest), indent=2, ensure_ascii=False), encoding="utf-8")
    rows: list[dict[str, Any]] = []
    for section in ("conversion", "load_and_runtime", "parity"):
        for row in manifest[section]:
            flat = {"section": section}
            flat.update({k: v for k, v in row.items() if not isinstance(v, (dict, list))})
            rows.append(flat)
    if rows:
        fields = sorted({k for row in rows for k in row})
        with (args.out_dir / "results.csv").open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
    print(json.dumps({"results": str(args.out_dir / 'results.json'), "csv": str(args.out_dir / 'results.csv'), "conversion": manifest["conversion"], "real_image_status": manifest["real_image_status"]}, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
