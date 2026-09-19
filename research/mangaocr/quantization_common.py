#!/usr/bin/env python3
"""Shared fixtures and exact app-compatible execution for quantization tests."""
from __future__ import annotations

import hashlib
import json
import platform
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
MODEL_DIR = ROOT / "models" / "manga-ocr-mobile"
RESULT_DIR = ROOT / "results"
CACHE_DIR = ROOT / "cache" / "quantization"
START_TOKEN = 2
END_TOKEN = 3
DECODER_POSITION_COUNT = 128
DECODER_CACHE_LENGTH = 256


def session_options(threads: int = 1) -> ort.SessionOptions:
    options = ort.SessionOptions()
    options.intra_op_num_threads = max(1, int(threads))
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return options


def load_session(path: Path, threads: int = 1) -> ort.InferenceSession:
    return ort.InferenceSession(str(path), session_options(threads), providers=["CPUExecutionProvider"])


def synthetic_crops(seed: int = 20260918) -> list[Image.Image]:
    """Deterministic text-like images; these are explicitly not OCR ground truth."""
    rng = np.random.default_rng(seed)
    sizes = [(64, 64), (96, 256), (128, 384), (180, 240), (256, 128), (320, 512)]
    crops: list[Image.Image] = []
    for index, (width, height) in enumerate(sizes):
        yy, xx = np.mgrid[:height, :width]
        gradient = (235 + 15 * xx / max(1, width - 1) - 10 * yy / max(1, height - 1)).astype(np.uint8)
        base = np.clip(gradient[..., None].astype(np.int16) + rng.integers(-4, 5, (height, width, 3)), 0, 255).astype(np.uint8)
        image = Image.fromarray(base, mode="RGB")
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


def preprocess(crop: Image.Image) -> np.ndarray:
    """Exact app preprocessing: grayscale, aspect-preserving 224 square, white pad, [-1,1] CHW."""
    image = crop.convert("L")
    width, height = image.size
    scale = 224.0 / max(width, height)
    new_size = (max(1, int(width * scale)), max(1, int(height * scale)))
    resized = image.resize(new_size, Image.Resampling.BILINEAR)
    padded = Image.new("L", (224, 224), 255)
    padded.paste(resized, ((224 - new_size[0]) // 2, (224 - new_size[1]) // 2))
    pixels = np.asarray(padded, dtype=np.float32) / 127.5 - 1.0
    return np.repeat(pixels[None, ...], 3, axis=0)[None, ...].astype(np.float32, copy=False)


def chapter_fixtures(chapter_dir: Path, max_regions: int = 32, seed: int = 20260918) -> list[dict[str, Any]]:
    """Read existing `.studio/ocr.json` boxes in-place; no chapter data is copied."""
    ocr_path = chapter_dir / ".studio" / "ocr.json"
    metadata = json.loads(ocr_path.read_text(encoding="utf-8"))
    candidates: list[dict[str, Any]] = []
    for page_name in sorted(metadata):
        page_path = chapter_dir / page_name
        if not page_path.exists():
            continue
        with Image.open(page_path) as source:
            page = source.convert("RGB")
        for region in metadata[page_name].get("regions", []):
            box = region.get("ocr_box") or region.get("box")
            if not box or len(box) != 4:
                continue
            left, top, right, bottom = [int(round(float(value))) for value in box]
            left = max(0, min(page.width - 1, left)); top = max(0, min(page.height - 1, top))
            right = max(left + 1, min(page.width, right)); bottom = max(top + 1, min(page.height, bottom))
            candidates.append({"name": f"{page_name}:{left},{top},{right},{bottom}", "source": str(page_path), "metadata": str(ocr_path), "box": [left, top, right, bottom], "expected_text": str(region.get("text", "")), "image": page.crop((left, top, right, bottom))})
    rng = np.random.default_rng(seed)
    if len(candidates) > max_regions:
        candidates = [candidates[int(i)] for i in np.sort(rng.choice(len(candidates), size=max_regions, replace=False))]
    return candidates


def input_dtype(session: ort.InferenceSession, name: str) -> np.dtype:
    info = next(i for i in session.get_inputs() if i.name == name)
    return np.float16 if "float16" in info.type else np.float32


def run_encoder(session: ort.InferenceSession, pixel_input: np.ndarray) -> np.ndarray:
    name = session.get_inputs()[0].name
    return np.asarray(session.run(None, {name: pixel_input.astype(input_dtype(session, name), copy=False)})[0])


def _cast_inputs(session: ort.InferenceSession, values: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    out: dict[str, np.ndarray] = {}
    for info in session.get_inputs():
        value = values[info.name]
        if "float16" in info.type: value = np.asarray(value, dtype=np.float16)
        elif "float" in info.type: value = np.asarray(value, dtype=np.float32)
        out[info.name] = value
    return out


def run_ocr(encoder: ort.InferenceSession, decoder_init: ort.InferenceSession, decoder_step: ort.InferenceSession, pixel_input: np.ndarray, max_length: int = 127) -> dict[str, Any]:
    """Run the Kotlin-equivalent greedy decoder; position 1 starts with [CLS]."""
    hidden = run_encoder(encoder, pixel_input)
    init = [np.asarray(x) for x in decoder_init.run(None, _cast_inputs(decoder_init, {"encoder_hidden_states": hidden, "input_ids": np.array([[START_TOKEN]], dtype=np.int64)}))]
    _, self_k_init, self_v_init, cross_k, cross_v = init
    self_k = np.zeros((4, 1, 4, DECODER_CACHE_LENGTH, 64), dtype=self_k_init.dtype); self_v = np.zeros_like(self_k)
    self_k[:, :, :, :1, :] = self_k_init; self_v[:, :, :, :1, :] = self_v_init
    token_ids: list[int] = []; logits: list[np.ndarray] = []; current = START_TOKEN; position = 1
    for _ in range(min(max_length, DECODER_POSITION_COUNT - 1)):
        values = {"encoder_hidden_states": hidden, "input_ids": np.array([[current]], dtype=np.int64), "position_ids": np.array([[position]], dtype=np.int64), "self_k_cache": self_k, "self_v_cache": self_v, "cross_k_cache": cross_k, "cross_v_cache": cross_v}
        out = [np.asarray(x) for x in decoder_step.run(None, _cast_inputs(decoder_step, values))]
        step_logits, new_k, new_v = out; logits.append(step_logits); token = int(np.argmax(step_logits[0]))
        if token == END_TOKEN: break
        token_ids.append(token); self_k[:, :, :, position:position + 1, :] = new_k; self_v[:, :, :, position:position + 1, :] = new_v; current = token; position += 1
    vocab = (MODEL_DIR / "vocab.txt").read_text(encoding="utf-8").splitlines()
    return {"hidden": hidden, "init_outputs": init, "token_ids": token_ids, "text": "".join(vocab[t] for t in token_ids if 0 <= t < len(vocab)), "logits": logits}


def model_info(path: Path) -> dict[str, Any]:
    data = path.read_bytes(); return {"path": str(path), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def environment() -> dict[str, Any]:
    return {"timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "python": sys.version, "platform": platform.platform(), "processor": platform.processor(), "onnxruntime": ort.__version__, "providers": ort.get_available_providers()}


class DictCalibrationReader:
    def __init__(self, samples: list[dict[str, np.ndarray]]): self.samples, self.index = samples, 0
    def get_next(self) -> dict[str, np.ndarray] | None:
        if self.index >= len(self.samples): return None
        value = self.samples[self.index]; self.index += 1; return value
    def rewind(self) -> None: self.index = 0
