"""Path resolution for the Manga Render Lab.

All models/fonts live in the Android app's assets; the lab resolves them by
relative path from the repo root so nothing is duplicated. The segmentation
bubble model is resolved from the in-repo experimental copy
(``experimental/models/manga109-segmentation-bubble``) first, with the HF cache
as a fallback. The default variant is ``best_int8.onnx`` — the Android-constrained
int8 export that will ship on a 6GB device.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
APP_ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets"
APP_RES = REPO_ROOT / "app" / "src" / "main" / "res"

MODELS = APP_ASSETS / "models"
FONTS = APP_RES / "font"

DETECTOR_MODEL = MODELS / "detection" / "detector-v4-s_int8.onnx"
PANEL_MODEL = MODELS / "detection" / "manga_panel_detector_int8.onnx"
AOT_MODEL = MODELS / "inpainting" / "aot.onnx"

MANGA_OCR_ENCODER = MODELS / "ocr" / "encoder.onnx"
MANGA_OCR_DECODER_INIT = MODELS / "ocr" / "decoder_init.onnx"
MANGA_OCR_DECODER_STEP = MODELS / "ocr" / "decoder_step.onnx"
MANGA_OCR_VOCAB = MODELS / "ocr" / "vocab.txt"

PADDLE_REC_MODEL = MODELS / "ocr" / "paddle-v6-small" / "inference.onnx"
PADDLE_REC_DICT = MODELS / "ocr" / "paddle-v6-small" / "PP-OCRv6_small_rec.txt"
PADDLE_DET_MODEL = MODELS / "ocr" / "paddle-v6-small" / "det" / "inference.onnx"

ANIMEACE_FONT = FONTS / "animeace.ttf"
COMIC_BOOK_FONT = FONTS / "comic_book.otf"
MANGA_MASTER_FONT = FONTS / "manga_master_bb.ttf"

SEGMENTATION_MODEL_CANDIDATES = [
    # Local experimental copy
    REPO_ROOT / "experimental" / "models" / "manga109-segmentation-bubble",
    # HF cache locations — snapshot hash may change on re-download.
    Path.home() / ".cache" / "huggingface" / "hub" / "models--huyvux3005--manga109-segmentation-bubble",
]

SAMPLES_DIR = REPO_ROOT / "tools" / (
    "Okiraku Ryoushu no Tanoshii Ryouchi Bouei ~Seisan-kei Majutsu de "
    "Na mo na Kimura wo Saikyou no Jousai Toshi ni~ Chapter 33 &#8211; Rawkuma"
)

COMPANION_SERVER = REPO_ROOT / "companion_server"


# ── Model registries ────────────────────────────────────────────────────


# Recognized working variants; exclude known-broken (best_int8_broken).
_SEG_FILENAMES = (
    "best_int8.onnx",     # manga-calibrated int8 (default, ~3.4MB, fixed 640×640)
    "best.onnx",          # fp32/dynamic export (~11.8MB, trained at 1600)
    "best_fp32.onnx",     # clean fp32 baseline (~11.6MB, fixed 640×640)
    "best.pt",            # original PyTorch weights (~12MB)
)

# Default segmentation model. best_int8.onnx is chosen for the lab because:
#   - the experiment is constrained to what will ship on Android (6GB target),
#     where the 3.4 MB int8 asset is the only viable option;
#   - its fixed 640×640 input matches the contract the Android ONNX Runtime
#     will feed, so lab results are representative of the Android result;
#   - the larger dynamic best.onnx (11.8 MB, 1600-trained) remains available as
#     an explicit override when higher small-bubble recall is needed.
_SEG_DEFAULT_NAME = "best_int8.onnx"


def _scan_seg_models() -> list[dict[str, Any]]:
    """Scan candidate dirs for segmentation models.

    Handles two on-disk layouts:
      - HF cache `snapshots/<hash>/*.onnx` layout, and
      - a flat dir with the model files at the root (the layout used by
        ``experimental/models/manga109-segmentation-bubble``).

    Returns list of {name, path, size_bytes}. Priority order is set by the
    caller's default (`best_int8.onnx`) but every discovered variant is returned
    so an explicit name override can select any of them.
    """
    models: list[dict[str, Any]] = []
    seen: set[str] = set()
    for base in SEGMENTATION_MODEL_CANDIDATES:
        if not base.exists():
            continue

        # Layout 1: HF-cache style — model files under snapshots/<hash>/
        snapshots_dir = base / "snapshots"
        if snapshots_dir.exists():
            for snap in snapshots_dir.iterdir():
                if not snap.is_dir():
                    continue
                for fname in _SEG_FILENAMES:
                    if fname in seen:
                        continue
                    fp = snap / fname
                    if fp.exists():
                        seen.add(fname)
                        models.append({
                            "name": fname,
                            "path": fp,
                            "size_bytes": fp.stat().st_size,
                        })

        # Layout 2: flat — model files directly under base/ (experimental copy)
        for fname in _SEG_FILENAMES:
            if fname in seen:
                continue
            fp = base / fname
            if fp.exists():
                seen.add(fname)
                models.append({
                    "name": fname,
                    "path": fp,
                    "size_bytes": fp.stat().st_size,
                })

    # Default first, then by size ascending (smaller = preferred fallback).
    order = {
        _SEG_DEFAULT_NAME: 0,
        "best.onnx": 1,
        "best_fp32.onnx": 2,
        "best.pt": 3,
    }
    models.sort(key=lambda m: order.get(m["name"], 99))
    return models


def _scan_det_models() -> list[dict[str, Any]]:
    """Scan the detection models directory for known models.

    Returns list of {name, path, size_bytes}.
    """
    models: list[dict[str, Any]] = []
    registrations = [
        ("detector-v4", DETECTOR_MODEL),
        ("panel", PANEL_MODEL),
    ]
    for name, fp in registrations:
        info: dict[str, Any] = {"name": name, "path": fp, "size_bytes": 0}
        if fp.exists():
            info["size_bytes"] = fp.stat().st_size
        models.append(info)
    return models


SEG_MODELS: list[dict[str, Any]] = _scan_seg_models()
DET_MODELS: list[dict[str, Any]] = _scan_det_models()


def find_seg_model(name: str | None) -> Path | None:
    """Resolve a segmentation model path by name.

    - name=None or "" → return the default (best_int8.onnx if present, else
      the first available model in SEG_MODELS, else None)
    - known name in SEG_MODELS → return that model's path
    - unknown name → None
    """
    if not name:
        # Default: best_int8.onnx (Android-constrained int8 export).
        for m in SEG_MODELS:
            if m["name"] == _SEG_DEFAULT_NAME:
                return m["path"]
        # Fallback to first available (best.onnx etc.)
        return SEG_MODELS[0]["path"] if SEG_MODELS else None
    for m in SEG_MODELS:
        if m["name"] == name:
            return m["path"]
    return None


def default_seg_model_name() -> str:
    """Return the default segmentation model name."""
    return _SEG_DEFAULT_NAME


def find_segmentation_model() -> Path | None:
    """Legacy function — equivalent to find_seg_model(None)."""
    return find_seg_model(None)


def font_path(name: str = "animeace") -> Path:
    table = {
        "animeace": ANIMEACE_FONT,
        "comic": COMIC_BOOK_FONT,
        "manga": MANGA_MASTER_FONT,
    }
    return table.get(name, ANIMEACE_FONT)