"""paddle_rec_parity.py — PP-OCRv6_small_rec input normalization parity check.

Loads the bundled ONNX model and runs a crop through multiple preprocessing
variants, printing decoded text + mean top-1 prob for each.

Usage:
    python tools/inpaint-debug-viewer/paddle_rec_parity.py

    To test on a real crop from disk, uncomment the "Real crop test" section
    near the bottom and set CROP_PATH to your image file.

Requires: numpy, onnxruntime, Pillow
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ── paths ──────────────────────────────────────────────────────────────
ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "app" / "src" / "main" / "assets" / "models" / "ocr" / "paddle-v6-small" / "inference.onnx"
DICT = ROOT / "app" / "src" / "main" / "assets" / "models" / "ocr" / "paddle-v6-small" / "PP-OCRv6_small_rec.txt"

RECOGNITION_HEIGHT = 48
MAX_RECOGNITION_WIDTH = 960
MIN_TARGET_WIDTH = 320
WIDTH_ALIGNMENT = 16


def load_dict(path: str | Path) -> list[str]:
    with open(path, "r", encoding="utf-8") as f:
        return [line.strip("\n") for line in f]


def align_width(width: int) -> int:
    floored = max(width, MIN_TARGET_WIDTH)
    aligned = ((floored + WIDTH_ALIGNMENT - 1) // WIDTH_ALIGNMENT) * WIDTH_ALIGNMENT
    return min(aligned, MAX_RECOGNITION_WIDTH)


# ── CTC decoder (mirrors server.py) ────────────────────────────────────
BLANK_IDX = 0

def ctc_decode_with_conf(preds_idx, preds_prob, dict_chars: list[str]) -> tuple[str, float]:
    SPACE_IDX = len(dict_chars) + 1
    decoder_table = ["__BLANK__"] + dict_chars + [" "]
    text = ""
    confs: list[float] = []
    prev = -1
    for i, idx in enumerate(preds_idx):
        if idx != BLANK_IDX and idx != prev:
            if idx < len(decoder_table):
                text += decoder_table[idx]
                if idx != SPACE_IDX:
                    confs.append(float(preds_prob[i]))
        prev = idx
    confidence = float(np.mean(confs)) if confs else 0.0
    return text, confidence


# ── preprocessing pipeline (shared resize/pad logic) ───────────────────
def _resize_and_pad(crop_rgb: np.ndarray) -> tuple[np.ndarray, int]:
    h, w = crop_rgb.shape[:2]
    scaled_w = max(1, min(960, math.ceil(w * RECOGNITION_HEIGHT / h)))
    input_w = align_width(scaled_w)
    img = Image.fromarray(crop_rgb).resize((scaled_w, RECOGNITION_HEIGHT), Image.Resampling.BILINEAR)
    padded = Image.new("RGB", (input_w, RECOGNITION_HEIGHT), (128, 128, 128))
    padded.paste(img, (0, 0))
    return np.array(padded, dtype=np.float32), input_w


# ── preprocess variants ────────────────────────────────────────────────
def preprocess_app(crop_rgb: np.ndarray) -> np.ndarray:
    """Current app / Python-prototype pipeline: [-1, 1] RGB NCHW."""
    arr, _ = _resize_and_pad(crop_rgb)
    arr = arr / 255.0
    arr = (arr - 0.5) / 0.5
    return np.transpose(arr, (2, 0, 1))[None]


def preprocess_raw_255(crop_rgb: np.ndarray) -> np.ndarray:
    """Raw [0, 255] RGB, no normalize."""
    arr, _ = _resize_and_pad(crop_rgb)
    return np.transpose(arr, (2, 0, 1))[None]


def preprocess_0_1(crop_rgb: np.ndarray) -> np.ndarray:
    """[0, 1] float RGB, /255 only."""
    arr, _ = _resize_and_pad(crop_rgb)
    arr = arr / 255.0
    return np.transpose(arr, (2, 0, 1))[None]


def preprocess_app_brg(crop_rgb: np.ndarray) -> np.ndarray:
    """[-1, 1] BRG (B<->R swapped channels) to test yml BGR claim."""
    bgr = crop_rgb[:, :, ::-1]
    arr, _ = _resize_and_pad(bgr)
    arr = arr / 255.0
    arr = (arr - 0.5) / 0.5
    return np.transpose(arr, (2, 0, 1))[None]


# ── generate a synthetic test crop ─────────────────────────────────────
def make_synthetic_crop(text: str = "Hello World 123", font_size: int = 36) -> np.ndarray:
    try:
        font = ImageFont.truetype("arial.ttf", font_size)
    except Exception:
        font = ImageFont.load_default()
    dummy = Image.new("RGB", (1, 1))
    draw = ImageDraw.Draw(dummy)
    bbox = draw.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    pad = 8
    img = Image.new("RGB", (tw + pad * 2, th + pad * 2), (255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.text((pad, pad), text, fill=(0, 0, 0), font=font)
    return np.array(img)


# ── run a single variant ──────────────────────────────────────────────
def evaluate(session, input_name: str, tensor: np.ndarray, dict_chars: list[str]) -> dict:
    preds = session.run(None, {input_name: tensor})[0]
    preds_idx = preds.argmax(axis=2)[0]
    preds_prob = preds.max(axis=2)[0]
    text, conf = ctc_decode_with_conf(preds_idx, preds_prob, dict_chars)
    return {
        "text": text,
        "conf": conf,
        "output_min": float(preds.min()),
        "output_max": float(preds.max()),
        "mean_sum_per_row": float(preds[0].sum(axis=-1).mean()),
        "nonblank_max_prob": float(preds_prob[preds_prob > 0].max()) if np.any(preds_prob > 0) else 0.0,
    }


# ── main ───────────────────────────────────────────────────────────────
def main():
    import onnxruntime as ort

    dict_chars = load_dict(DICT)
    session = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    # ── Synthetic test ──────────────────────────────────────────────
    crop_rgb = make_synthetic_crop("Hello World 123")
    print(f"Synthetic crop: {crop_rgb.shape[1]}x{crop_rgb.shape[0]}")
    print()

    variants = [
        ("[-1,1] RGB  (CURRENT app/prototype)", preprocess_app(crop_rgb)),
        ("raw[0,255] RGB (NO normalize)",       preprocess_raw_255(crop_rgb)),
        ("[0,1] RGB     (/255 only)",            preprocess_0_1(crop_rgb)),
        ("[-1,1] BGR    (yml channel order)",    preprocess_app_brg(crop_rgb)),
    ]

    for label, tensor in variants:
        r = evaluate(session, input_name, tensor, dict_chars)
        safe_text = r["text"].encode("ascii", errors="replace").decode("ascii")
        print(f"  {label}")
        print(f"    decoded = '{safe_text}'")
        print(f"    conf    = {r['conf']:.4f}")
        print(f"    output  = [{r['output_min']:.4f}, {r['output_max']:.4f}]  "
              f"mean-sum/row = {r['mean_sum_per_row']:.4f}")
        print()

    # ── Real crop test (uncomment and set CROP_PATH) ────────────────
    # CROP_PATH = "path/to/crop.png"
    # if Path(CROP_PATH).exists():
    #     crop_rgb_real = np.array(Image.open(CROP_PATH).convert("RGB"))
    #     print(f"Real crop: {crop_rgb_real.shape[1]}x{crop_rgb_real.shape[0]}")
    #     for label, fn in [
    #         ("[-1,1] RGB (app pipeline)", preprocess_app),
    #         ("[-1,1] BGR (yml BGR)", preprocess_app_brg),
    #     ]:
    #         r = evaluate(session, input_name, fn(crop_rgb_real), dict_chars)
    #         print(f"  {label}: '{r['text']}'  conf={r['conf']:.4f}")


if __name__ == "__main__":
    main()
