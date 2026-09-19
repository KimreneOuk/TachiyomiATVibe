#!/usr/bin/env python3
"""Research-only PP-OCRv6 quantization and comparison harness helpers."""
from __future__ import annotations

import hashlib
import json
import os
import platform
import sys
import time
from pathlib import Path
from typing import Any, Iterable

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RESEARCH = ROOT / "research"
RESULTS = RESEARCH / "results"
CACHE = RESEARCH / "cache" / "quantization"
MODEL_DIR = ROOT / "app" / "src" / "main" / "assets" / "models" / "ocr" / "paddle-v6-small"
REC_MODEL = MODEL_DIR / "inference.onnx"
DET_MODEL = MODEL_DIR / "det" / "inference.onnx"
DICT = MODEL_DIR / "PP-OCRv6_small_rec.txt"
MANIFEST = RESEARCH / "fixed_manifest.json"
REC_H = 48
REC_SMALL_W = 640
REC_MAX_W = 1600
DET_TARGET = 736
MEAN = np.asarray([0.485, 0.456, 0.406], np.float32)
STD = np.asarray([0.229, 0.224, 0.225], np.float32)


def model_info(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    return {"path": str(path), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def env_info() -> dict[str, Any]:
    import onnxruntime as ort
    return {"timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "python": sys.version, "platform": platform.platform(),
            "processor": platform.processor(), "onnxruntime": ort.__version__,
            "providers": ort.get_available_providers(), "host_note": "desktop CPU; not Android timing"}


def load_session(path: Path):
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.log_severity_level = 3
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    start = time.perf_counter_ns()
    session = ort.InferenceSession(str(path), opts, providers=["CPUExecutionProvider"])
    return session, (time.perf_counter_ns() - start) / 1e6


def resolved_manifest() -> list[dict[str, Any]]:
    spec = json.loads(MANIFEST.read_text(encoding="utf-8"))
    out: list[dict[str, Any]] = []
    exts = {str(x).lower() for x in spec["extensions"]}
    for root_rel in spec["roots"]:
        root = ROOT / root_rel
        for p in sorted(x for x in root.rglob("*") if x.is_file() and x.suffix.lower() in exts):
            digest = hashlib.sha256(p.read_bytes()).hexdigest()
            out.append({"id": p.relative_to(ROOT).as_posix(), "path": str(p),
                        "root": root_rel, "sha256": digest,
                        "kind": "crop" if "faithful" in root_rel else "page"})
    return out


def fixtures(limit: int, seed: int) -> list[dict[str, Any]]:
    """Return deterministic real fixtures; no synthetic tensors are used."""
    entries = resolved_manifest()
    # Interleave roots while preserving each root's sorted ordering, then cap.
    # Prefer actual JPG page/crop pixels over PNG masks when a bounded run is
    # requested; masks remain in the resolved manifest and are still available
    # for an explicit larger run.
    entries = sorted(entries, key=lambda e: (0 if Path(e["path"]).suffix.lower() in {".jpg", ".jpeg", ".webp"} else 1, e["id"]))
    if limit > 0:
        entries = entries[:limit]
    result = []
    for e in entries:
        with Image.open(e["path"]) as image:
            result.append({**e, "image": image.convert("RGB")})
    return result


def rec_input(image: Image.Image) -> np.ndarray:
    w = max(1, image.width); h = max(1, image.height)
    scaled = max(1, min(REC_MAX_W, int(np.ceil(w * REC_H / float(h)))))
    iw = REC_SMALL_W if scaled <= REC_SMALL_W else REC_MAX_W
    canvas = Image.new("RGB", (iw, REC_H), (128, 128, 128))
    canvas.paste(image.resize((scaled, REC_H), Image.Resampling.BILINEAR), (0, 0))
    arr = np.asarray(canvas, np.float32)
    return np.ascontiguousarray(((arr / 255.0 - 0.5) / 0.5).transpose(2, 0, 1)[None])


def det_input(image: Image.Image) -> tuple[np.ndarray, int, int]:
    w = max(1, image.width); h = max(1, image.height)
    scale = DET_TARGET / float(max(w, h))
    rw = max(1, min(DET_TARGET, int(round(w * scale))))
    rh = max(1, min(DET_TARGET, int(round(h * scale))))
    resized = np.asarray(image.resize((rw, rh), Image.Resampling.BILINEAR), np.float32)
    padded = np.zeros((DET_TARGET, DET_TARGET, 3), np.float32)
    padded[:rh, :rw] = resized
    x = ((padded / 255.0 - MEAN) / STD).transpose(2, 0, 1)[None]
    return np.ascontiguousarray(x, dtype=np.float32), rw, rh


def ctc_decode(output: np.ndarray, dictionary: list[str]) -> tuple[str, list[int], float]:
    logits = np.asarray(output)[0] if output.ndim == 3 else np.asarray(output)
    ids = np.argmax(logits, axis=1)
    probs = np.max(logits, axis=1)
    tokens: list[int] = []; conf: list[float] = []; previous = 0
    space = len(dictionary) + 1
    for token, probability in zip(ids, probs):
        i = int(token)
        if i != previous and i != 0:
            if 1 <= i <= len(dictionary):
                tokens.append(i); conf.append(float(probability))
            elif i == space:
                tokens.append(i); conf.append(float(probability))
        previous = i
    text = "".join(" " if i == space else dictionary[i - 1] for i in tokens)
    return text, tokens, (sum(conf) / len(conf) if conf else 0.0)


def boxes(prob: np.ndarray, rw: int, rh: int, threshold: float = 0.2, box_threshold: float = 0.45) -> list[list[float]]:
    """Use the committed Android-matching DB postprocess and back-project."""
    import sys as _sys
    studio = str(ROOT / "tools" / "translation_studio")
    if studio not in _sys.path:
        _sys.path.insert(0, studio)
    import paddle_ocr
    active = np.asarray(prob)[0, 0, :rh, :rw]
    result = paddle_ocr.db_post_process(active, threshold, box_threshold)
    sx = (DET_TARGET / float(max(1, rw))) if False else 1.0
    # db_post_process returns active-map coordinates; map coordinates scale by
    # original crop / resized dimensions. The caller performs that final scale.
    return [list(map(float, b)) for b in result]


def box_metrics(reference: list[list[float]], candidate: list[list[float]], rw: int, rh: int, width: int, height: int) -> dict[str, Any]:
    def project(b: list[float]) -> list[float]:
        sx, sy = width / float(max(1, rw)), height / float(max(1, rh))
        return [b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy, b[4]]
    a = [project(x) for x in reference]; c = [project(x) for x in candidate]
    def iou(x: list[float], y: list[float]) -> float:
        ix1, iy1 = max(x[0], y[0]), max(x[1], y[1]); ix2, iy2 = min(x[2], y[2]), min(x[3], y[3])
        inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
        ax = max(0.0, x[2] - x[0]) * max(0.0, x[3] - x[1]); ay = max(0.0, y[2] - y[0]) * max(0.0, y[3] - y[1])
        return inter / max(1e-9, ax + ay - inter)
    pairs = []
    for x in a:
        best = max((iou(x, y) for y in c), default=0.0)
        pairs.append(best)
    return {"reference_count": len(a), "candidate_count": len(c),
            "mean_best_iou": float(np.mean(pairs)) if pairs else 1.0,
            "min_best_iou": float(min(pairs)) if pairs else 1.0,
            "count_delta": len(c) - len(a), "boxes_reference": a, "boxes_candidate": c}


def tensor_metrics(a: np.ndarray, b: np.ndarray) -> dict[str, float]:
    x, y = np.asarray(a, np.float32), np.asarray(b, np.float32)
    d = np.abs(x - y)
    denom = max(1e-12, float(np.linalg.norm(x) * np.linalg.norm(y)))
    return {"max_abs": float(d.max()) if d.size else 0.0, "mean_abs": float(d.mean()) if d.size else 0.0,
            "rmse": float(np.sqrt(np.mean((x - y) ** 2))) if d.size else 0.0,
            "cosine": float(np.sum(x * y) / denom) if d.size else 1.0,
            "shape_equal": bool(x.shape == y.shape)}


def sequence_metrics(a: list[int], b: list[int]) -> dict[str, Any]:
    n = max(len(a), len(b)); mismatch = next((i for i in range(min(len(a), len(b))) if a[i] != b[i]), None)
    if mismatch is None and len(a) != len(b): mismatch = min(len(a), len(b))
    return {"exact": a == b, "reference_length": len(a), "candidate_length": len(b), "first_mismatch": mismatch,
            "common_prefix": (mismatch if mismatch is not None else min(len(a), len(b)))}


class Reader:
    def __init__(self, values: list[dict[str, np.ndarray]]): self.values, self.index = values, 0
    def get_next(self):
        if self.index >= len(self.values): return None
        value = self.values[self.index]; self.index += 1; return value
    def rewind(self): self.index = 0


def rss_bytes() -> int | None:
    try:
        import psutil
        return int(psutil.Process().memory_info().rss)
    except Exception:
        return None


def timed(session, input_name: str, value: np.ndarray, repeats: int) -> dict[str, Any]:
    warm = max(1, min(2, repeats))
    for _ in range(warm): session.run(None, {input_name: value})
    values = []
    before = rss_bytes()
    for _ in range(max(1, repeats)):
        t0 = time.perf_counter_ns(); session.run(None, {input_name: value}); values.append((time.perf_counter_ns() - t0) / 1e6)
    after = rss_bytes()
    return {"samples_ms": values, "median_ms": float(np.median(values)), "p95_ms": float(np.percentile(values, 95)),
            "throughput_per_s": float(1000.0 / np.median(values)) if np.median(values) else 0.0,
            "rss_before": before, "rss_after": after, "rss_delta": (after - before if before is not None and after is not None else None)}
