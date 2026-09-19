#!/usr/bin/env python3
"""Reproducible CPU baseline for the manga-ocr-mobile ONNX split.

The benchmark always runs deterministic synthetic crops.  With ``--chapter-dir``
it also runs a small, read-only sample of a real chapter's pages and detector
crops.  Real images are referenced in-place and never copied to the repository.
Neither fixture establishes OCR quality; this is a runtime baseline.
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
import sys
import time
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

try:
    import psutil
except ImportError:  # pragma: no cover - optional memory measurement
    psutil = None


ROOT = Path(__file__).resolve().parent
MODEL_DIR = ROOT / "models" / "manga-ocr-mobile"
RESULT_DIR = ROOT / "results"

ENCODER_INPUT = "serving_default_args_0:0"
ENCODER_OUTPUT = "StatefulPartitionedCall:0"
VOCAB_SIZE = 9415
START_TOKEN = 2  # [CLS]
END_TOKEN = 3  # [SEP]


def now_ns() -> int:
    return time.perf_counter_ns()


def rss_mb() -> float | None:
    if psutil is None:
        return None
    return psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024)


def percentile(values: Iterable[float], p: float) -> float:
    values = sorted(values)
    if not values:
        return float("nan")
    if len(values) == 1:
        return values[0]
    rank = (len(values) - 1) * p
    lo, hi = math.floor(rank), math.ceil(rank)
    if lo == hi:
        return values[lo]
    return values[lo] + (values[hi] - values[lo]) * (rank - lo)


def synthetic_crops(seed: int = 20260918) -> list[Image.Image]:
    """Create varied, deterministic RGB crops with text-like geometry."""
    rng = np.random.default_rng(seed)
    sizes = [(64, 64), (96, 256), (128, 384), (180, 240), (256, 128), (320, 512)]
    crops: list[Image.Image] = []
    for index, (width, height) in enumerate(sizes):
        base = np.zeros((height, width, 3), dtype=np.uint8)
        # Smooth paper-like gradient plus a low-amplitude deterministic texture.
        yy, xx = np.mgrid[:height, :width]
        gradient = (235 + 15 * xx / max(1, width - 1) - 10 * yy / max(1, height - 1)).astype(np.uint8)
        base[:] = gradient[..., None]
        base = np.clip(base.astype(np.int16) + rng.integers(-4, 5, base.shape), 0, 255).astype(np.uint8)
        image = Image.fromarray(base)
        draw = ImageDraw.Draw(image)
        for line in range(4 + index % 4):
            y = int((line + 1) * height / (5 + index % 4))
            left = int((line * width) / (14 + index))
            right = width - int(((line + 2) * width) / (18 + index))
            draw.line((left, y, max(left + 1, right), y), fill=(30 + line * 7,) * 3, width=max(1, width // 80))
        for box in range(1 + index % 3):
            left = int((box + 1) * width / (5 + index))
            top = int((box + 2) * height / (7 + index))
            draw.rectangle((left, top, min(width - 1, left + width // 5), min(height - 1, top + height // 9)), outline=(45, 45, 45), width=max(1, width // 100))
        crops.append(image)
    return crops


def real_fixtures(chapter_dir: Path, max_pages: int = 8) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Select evenly spaced pages plus one high-confidence detector crop/page."""
    page_paths = sorted(p for p in chapter_dir.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"} and p.is_file())
    if not page_paths:
        raise FileNotFoundError(f"no page images found under {chapter_dir}")
    selected_indices = np.linspace(0, len(page_paths) - 1, min(max_pages, len(page_paths)), dtype=int).tolist()
    detection_path = chapter_dir / ".studio" / "detections.json"
    detections: dict[str, Any] = {}
    if detection_path.exists():
        detections = json.loads(detection_path.read_text(encoding="utf-8"))
    fixtures: list[dict[str, Any]] = []
    for page_index in selected_indices:
        page_path = page_paths[page_index]
        with Image.open(page_path) as source:
            page = source.convert("RGB")
        fixtures.append({"name": f"page:{page_path.name}", "image": page, "source": str(page_path)})
        page_detection = detections.get(page_path.name, {})
        boxes = page_detection.get("boxes", []) if isinstance(page_detection, dict) else []
        candidates = [b for b in boxes if len(b) >= 6 and float(b[1]) >= 0.20]
        if candidates:
            box = max(candidates, key=lambda b: float(b[1]))
            margin = 4
            left = max(0, int(box[2]) - margin)
            top = max(0, int(box[3]) - margin)
            right = min(page.width, int(box[4]) + margin)
            bottom = min(page.height, int(box[5]) + margin)
            crop = page.crop((left, top, max(left + 1, right), max(top + 1, bottom)))
            fixtures.append({"name": f"crop:{page_path.name}:{left},{top},{right},{bottom}", "image": crop, "source": str(detection_path)})
    return fixtures, {
        "chapter_dir": str(chapter_dir),
        "page_count": len(page_paths),
        "page_files": [p.name for p in page_paths],
        "selected_page_files": [page_paths[i].name for i in selected_indices],
        "detector_file": str(detection_path) if detection_path.exists() else None,
        "fixture_count": len(fixtures),
        "crop_assumption": "highest-confidence detector box with score >= 0.20, 4px padding, clamped to page bounds; pages are full-page controls",
    }


def preprocess(crop: Image.Image) -> np.ndarray:
    """Match the common ViT input convention: RGB, 224 square, float [-1, 1]."""
    resized = crop.resize((224, 224), Image.Resampling.BICUBIC)
    array = np.asarray(resized, dtype=np.float32) / 127.5 - 1.0
    return np.transpose(array, (2, 0, 1))[None, ...].astype(np.float32, copy=False)


def session_options(threads: int) -> ort.SessionOptions:
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return options


def load_sessions(threads: int) -> tuple[ort.InferenceSession, ort.InferenceSession, ort.InferenceSession]:
    options = session_options(threads)
    return (
        ort.InferenceSession(str(MODEL_DIR / "encoder.onnx"), options, providers=["CPUExecutionProvider"]),
        ort.InferenceSession(str(MODEL_DIR / "decoder_init.onnx"), options, providers=["CPUExecutionProvider"]),
        ort.InferenceSession(str(MODEL_DIR / "decoder_step.onnx"), options, providers=["CPUExecutionProvider"]),
    )


def run_encoder(session: ort.InferenceSession, inputs: list[np.ndarray], index: int) -> np.ndarray:
    output = session.run([ENCODER_OUTPUT], {ENCODER_INPUT: inputs[index % len(inputs)]})[0]
    if output.shape != (1, 196, 256) or not np.isfinite(output).all():
        raise RuntimeError(f"encoder validation failed: shape={output.shape}, finite={np.isfinite(output).all()}")
    return output


def generate(
    encoder: ort.InferenceSession,
    decoder_init: ort.InferenceSession,
    decoder_step: ort.InferenceSession,
    inputs: list[np.ndarray],
    length: int,
    index: int,
) -> tuple[dict[str, float], list[int]]:
    timings = {"preprocess": 0.0, "encoder": 0.0, "decoder_init": 0.0, "decoder_step": 0.0, "token_selection": 0.0}
    t0 = now_ns()
    pixel_input = inputs[index % len(inputs)]
    timings["preprocess"] = (now_ns() - t0) / 1e6
    t0 = now_ns()
    hidden = run_encoder(encoder, [pixel_input], 0)
    timings["encoder"] = (now_ns() - t0) / 1e6
    t0 = now_ns()
    initial = decoder_init.run(None, {"encoder_hidden_states": hidden, "input_ids": np.array([[START_TOKEN]], dtype=np.int64)})
    timings["decoder_init"] = (now_ns() - t0) / 1e6
    logits, self_k_initial, self_v_initial, cross_k, cross_v = initial
    # decoder_init returns one self-cache slice; decoder_step expects the
    # preallocated 256-position cache used by the mobile decoder contract.
    self_k = np.zeros((4, 1, 4, 256, 64), dtype=np.float32)
    self_v = np.zeros((4, 1, 4, 256, 64), dtype=np.float32)
    self_k[:, :, :, :1, :] = self_k_initial
    self_v[:, :, :, :1, :] = self_v_initial
    tokens: list[int] = []
    for position in range(length):
        t0 = now_ns()
        token = int(np.argmax(logits[0]))
        timings["token_selection"] += (now_ns() - t0) / 1e6
        if token < 0 or token >= VOCAB_SIZE:
            raise RuntimeError(f"token id out of range: {token}")
        tokens.append(token)
        # This is a fixed-step cost benchmark: continue after [SEP] so every
        # requested decode length (5/10/20/40/80/128) measures that many steps.
        if position + 1 == length:
            break
        t0 = now_ns()
        outputs = decoder_step.run(None, {
            "encoder_hidden_states": hidden,
            "input_ids": np.array([[token]], dtype=np.int64),
            "position_ids": np.array([[position + 1]], dtype=np.int64),
            "self_k_cache": self_k,
            "self_v_cache": self_v,
            "cross_k_cache": cross_k,
            "cross_v_cache": cross_v,
        })
        timings["decoder_step"] += (now_ns() - t0) / 1e6
        logits, self_k_slice, self_v_slice = outputs
        self_k[:, :, :, position:position + 1, :] = self_k_slice
        self_v[:, :, :, position:position + 1, :] = self_v_slice
    if not tokens:
        raise RuntimeError("generation produced no tokens")
    return timings, tokens


def add_row(rows: list[dict[str, Any]], *, component: str, input_set: str, input_name: str, threads: int, length: int | None, cold: bool, iteration: int, duration_ms: float, tokens: int = 0, memory: float | None = None, valid: bool = True, token_ids: list[int] | None = None) -> None:
    rows.append({
        "component": component,
        "input_set": input_set,
        "input_name": input_name,
        "threads": threads,
        "decode_length": length if length is not None else "",
        "cold": int(cold),
        "iteration": iteration,
        "duration_ms": duration_ms,
        "tokens": tokens,
        "tokens_per_sec": (tokens / (duration_ms / 1000.0)) if tokens and duration_ms > 0 else "",
        "rss_mb": memory if memory is not None else "",
        "valid": int(valid),
        "token_ids": " ".join(map(str, token_ids or [])),
    })


def benchmark(args: argparse.Namespace) -> dict[str, Any]:
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    synthetic = [{"name": f"synthetic:{i}", "image": image, "source": "deterministic-generated"} for i, image in enumerate(synthetic_crops(args.seed))]
    fixture_sets: dict[str, list[dict[str, Any]]] = {"synthetic": synthetic}
    real_metadata: dict[str, Any] | None = None
    if args.chapter_dir:
        fixture_sets["real"] , real_metadata = real_fixtures(Path(args.chapter_dir), args.real_max_pages)
    prepared: dict[str, list[np.ndarray]] = {}
    prep_rows: list[tuple[str, str, bool, int, float]] = []
    for input_set, fixtures in fixture_sets.items():
        prepared[input_set] = []
        for fixture in fixtures:
            t0 = now_ns()
            prepared[input_set].append(preprocess(fixture["image"]))
            prep_rows.append((input_set, fixture["name"], True, 0, (now_ns() - t0) / 1e6))
            for iteration in range(max(args.warm_repeats, 1)):
                t0 = now_ns()
                preprocess(fixture["image"])
                prep_rows.append((input_set, fixture["name"], False, iteration, (now_ns() - t0) / 1e6))
    rows: list[dict[str, Any]] = []
    lengths = args.lengths
    logical = os.cpu_count() or 1
    threads = sorted(set(min(max(1, t), logical) for t in args.threads))
    validation: dict[str, Any] = {"fixture_counts": {k: len(v) for k, v in fixture_sets.items()}, "input_shapes": {k: [list(x.shape) for x in values] for k, values in prepared.items()}, "all_finite": bool(all(np.isfinite(x).all() for values in prepared.values() for x in values))}

    for thread_count in threads:
        for cold in (True, False):
            encoder = decoder_init = decoder_step = None
            if not cold:
                encoder, decoder_init, decoder_step = load_sessions(thread_count)
            for iteration in range(args.cold_repeats if cold else args.warm_repeats):
                if cold:
                    t0 = now_ns()
                    encoder, decoder_init, decoder_step = load_sessions(thread_count)
                    add_row(rows, component="session_create", input_set="all", input_name="-", threads=thread_count, length=None, cold=True, iteration=iteration, duration_ms=(now_ns() - t0) / 1e6, memory=rss_mb())
                assert encoder is not None and decoder_init is not None and decoder_step is not None
                for input_set, fixtures in fixture_sets.items():
                    inputs = prepared[input_set]
                    for index, fixture in enumerate(fixtures):
                        t0 = now_ns()
                        hidden = run_encoder(encoder, inputs, index)
                        add_row(rows, component="encoder", input_set=input_set, input_name=fixture["name"], threads=thread_count, length=None, cold=cold, iteration=iteration, duration_ms=(now_ns() - t0) / 1e6, memory=rss_mb())
                        t0 = now_ns()
                        out = decoder_init.run(None, {"encoder_hidden_states": hidden, "input_ids": np.array([[START_TOKEN]], dtype=np.int64)})
                        ok = len(out) == 5 and out[0].shape == (1, VOCAB_SIZE) and all(np.isfinite(x).all() for x in out)
                        add_row(rows, component="decoder_init", input_set=input_set, input_name=fixture["name"], threads=thread_count, length=None, cold=cold, iteration=iteration, duration_ms=(now_ns() - t0) / 1e6, memory=rss_mb(), valid=ok)
                    # Generation uses one stable fixture per set; component-level
                    # rows above still cover every sampled page/crop.
                    for length in lengths:
                        t0 = now_ns()
                        timings, token_ids = generate(encoder, decoder_init, decoder_step, inputs, length, 0)
                        total_ms = (now_ns() - t0) / 1e6
                        representative = fixtures[0]["name"]
                        for component, duration in timings.items():
                            add_row(rows, component=component, input_set=input_set, input_name=representative, threads=thread_count, length=length, cold=cold, iteration=iteration, duration_ms=duration, tokens=len(token_ids) if component in {"decoder_step", "token_selection"} else 0, memory=rss_mb(), token_ids=token_ids if component == "token_selection" else None)
                        add_row(rows, component="full_generation", input_set=input_set, input_name=representative, threads=thread_count, length=length, cold=cold, iteration=iteration, duration_ms=total_ms, tokens=len(token_ids), memory=rss_mb(), token_ids=token_ids)
            # Session references are intentionally dropped between cold/warm phases.
            del encoder, decoder_init, decoder_step

    for index, (input_set, input_name, cold, iteration, value) in enumerate(prep_rows):
        add_row(rows, component="preprocess", input_set=input_set, input_name=input_name, threads=0, length=None, cold=cold, iteration=iteration, duration_ms=value, memory=rss_mb())

    metadata = {
        "benchmark": "manga-ocr-mobile ONNX Runtime CPU baseline",
        "evidence": "OBSERVED runtime measurements on deterministic synthetic crops and, when supplied, a read-only real chapter sample; neither fixture establishes OCR accuracy",
        "timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "python": sys.version,
        "platform": platform.platform(),
        "processor": platform.processor(),
        "onnxruntime": ort.__version__,
        "providers": ort.get_available_providers(),
        "model_files": {name: {"bytes": (MODEL_DIR / name).stat().st_size, "sha256": hashlib.sha256((MODEL_DIR / name).read_bytes()).hexdigest()} for name in ["encoder.onnx", "decoder_init.onnx", "decoder_step.onnx"]},
        "vocab_sha256": hashlib.sha256((MODEL_DIR / "vocab.txt").read_bytes()).hexdigest(),
        "seed": args.seed,
        "fixture_sets": {k: [{"name": f["name"], "size": [f["image"].width, f["image"].height], "source": f["source"]} for f in values] for k, values in fixture_sets.items()},
        "real_chapter": real_metadata,
        "threads": threads,
        "lengths": lengths,
        "fixed_step_decode": True,
        "warm_repeats": args.warm_repeats,
        "cold_repeats": args.cold_repeats,
        "validation": validation,
        "row_count": len(rows),
    }
    csv_path = RESULT_DIR / "baseline_benchmarks.csv"
    json_path = RESULT_DIR / "baseline_benchmarks.json"
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    json_path.write_text(json.dumps({"metadata": metadata, "rows": rows}, indent=2), encoding="utf-8")
    write_findings(metadata, rows)
    return metadata


def write_findings(metadata: dict[str, Any], rows: list[dict[str, Any]]) -> None:
    path = ROOT / "findings" / "baseline_benchmarks.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    grouped: dict[tuple[str, str, int, int, int], list[float]] = {}
    for row in rows:
        if row["component"] in {"full_generation", "encoder", "decoder_init", "decoder_step", "token_selection", "preprocess"}:
            key = (row["input_set"], row["component"], int(row["threads"]), int(row["decode_length"] or 0), int(row["cold"]))
            grouped.setdefault(key, []).append(float(row["duration_ms"]))
    real_note = " Real chapter fixtures are included." if metadata.get("real_chapter") else " No real chapter path was supplied."
    lines = ["# ONNX Runtime CPU baseline benchmarks", "", "## Evidence and reproducibility", "", f"- **OBSERVED:** Runtime measurements were produced by `research/benchmark_onnx.py` on {metadata['timestamp_utc']} with ONNX Runtime {metadata['onnxruntime']}, Python {platform.python_version()}, and CPU `{metadata['processor'] or 'unspecified'}`.", "- **OBSERVED:** Synthetic inputs are six deterministic RGB crops (seed `" + str(metadata["seed"]) + "`), resized to `[1, 3, 224, 224]`; they are an isolation fixture and do not establish OCR quality." + real_note, "- **OBSERVED:** Real inputs, when present, are evenly spaced full pages plus one detector crop per selected page. Crop assumptions and page enumeration are recorded in the JSON metadata; source files remain outside the repository.", "- **OBSERVED:** The model output shapes and token IDs were validated during execution; raw rows are in `research/results/baseline_benchmarks.csv` and metadata is in `research/results/baseline_benchmarks.json`.", "- **INFERRED:** Compare component medians across thread counts to select a candidate CPU configuration; Android-device performance remains **UNVERIFIED**.", "", "## Summary (median / P90 / P95 milliseconds)", "", "| input set | component | threads | decode length | cold | n | median | P90 | P95 |", "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    for key in sorted(grouped):
        values = grouped[key]
        input_set, component, thread_count, length, cold = key
        lines.append(f"| {input_set} | {component} | {thread_count} | {length or '-'} | {cold} | {len(values)} | {statistics.median(values):.3f} | {percentile(values, .90):.3f} | {percentile(values, .95):.3f} |")
    lines += ["", "## Interpretation", "", "- `cold=1` includes fresh ONNX Runtime session creation as a separate `session_create` row; model invocation rows show cold first-use behavior after that creation.", "- Decode lengths are fixed-step measurements: the loop continues after `[SEP]` so 5/10/20/40/80/128 each performs exactly that many token selections and cache steps. This isolates cost; production generation may stop early.", "- `full_generation` includes the measured pipeline, while `decoder_step` and `token_selection` rows expose per-generation totals. Tokens/sec is retained in the CSV for generation rows.", "- Memory is process RSS sampled around each operation and should be treated as an approximate upper-bound signal, not a model allocation trace.", "- **UNVERIFIED:** Correlation with real manga crops, Android NNAPI/GPU/NPU providers, and end-to-end app latency requires device experiments.", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--warm-repeats", type=int, default=2)
    parser.add_argument("--cold-repeats", type=int, default=1)
    parser.add_argument("--threads", type=int, nargs="+", default=[1, 2, 4])
    parser.add_argument("--lengths", type=int, nargs="+", default=[5, 10, 20, 40, 80, 128])
    parser.add_argument("--seed", type=int, default=20260918)
    parser.add_argument("--chapter-dir", type=Path, default=None, help="Optional real chapter directory containing page images and optional .studio/detections.json")
    parser.add_argument("--real-max-pages", type=int, default=8, help="Evenly spaced real pages to sample (each contributes a page and detector crop when available)")
    return parser.parse_args()


if __name__ == "__main__":
    metadata = benchmark(parse_args())
    print(json.dumps({"results": str(RESULT_DIR), "rows": metadata["row_count"], "onnxruntime": metadata["onnxruntime"]}, indent=2))
