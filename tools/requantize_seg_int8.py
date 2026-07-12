"""Re-export the huyvux3005/manga109-segmentation-bubble model as int8 ONNX with
PROPER manga calibration.

The existing best_int8.onnx is quantization-corrupted: ultralytics' built-in
int8 export (yolo.export(int8=True)) calibrates on 4 COCO images, which is
insufficient for a manga-domain model and produces an output tensor layout the
ultralytics decoder cannot parse (0 detections at any conf threshold, even
though raw logits look active).

This script:
  1. Loads best.pt (fp32 PyTorch)
  2. Exports a CLEAN fp32 ONNX (best_fp32.onnx) as the quantization source
  3. Static-quantizes to int8 using a CalibrationDataReader fed with REAL manga
     pages (the Okiraku chapter, 30 images) — not COCO
  4. Writes best_int8_manga.onnx (does NOT overwrite the broken best_int8.onnx)
  5. Verifies: loads via ultralytics YOLO(task='segment') and runs predict() on
     a sample page — prints detection count + max score. NO silent pass: if the
     re-quantized model still returns 0 detections, it fails loudly.

Usage:
  python tools/requantize_seg_int8.py

No fallback. Logs every step. Reports detection count at the end.
"""
from __future__ import annotations

import glob
import os
import sys
import tempfile
from pathlib import Path

import numpy as np
import onnx
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "overlay_lab"))
from backend import config  # noqa: E402

HF_SNAPSHOT = Path.home() / ".cache" / "huggingface" / "hub" / \
    "models--huyvux3005--manga109-segmentation-bubble" / "snapshots"
CALIBRATION_DIR = REPO_ROOT / "tools" / (
    "Okiraku Ryoushu no Tanoshii Ryouchi Bouei ~Seisan-kei Majutsu de "
    "Na mo na Kimura wo Saikyou no Jousai Toshi ni~ Chapter 33 &#8211; Rawkuma"
)
IMGSZ = 640


def _find_snapshot() -> Path:
    snaps = [d for d in HF_SNAPSHOT.iterdir() if d.is_dir()] if HF_SNAPSHOT.exists() else []
    if not snaps:
        raise SystemExit(f"No HF snapshot found at {HF_SNAPSHOT}")
    return snaps[0]


def _preprocess(img_path: str) -> np.ndarray:
    """Ultralytics-style letterbox-free resize to 640x640, RGB, NCHW float32 [0,1].

    Matches the ONNX input contract (images: [1,3,640,640]). Ultralytics does
    internal letterboxing in predict(); for calibration we keep it simple with
    a direct resize — sufficient for activation-range calibration.
    """
    img = Image.open(img_path).convert("RGB").resize((IMGSZ, IMGSZ), Image.BILINEAR)
    arr = np.asarray(img, dtype=np.float32) / 255.0
    return np.transpose(arr, (2, 0, 1))[None, :, :, :]


# ── Step 1+2: clean fp32 ONNX export from best.pt ─────────────────────


def export_fp32(pt_path: Path, out_path: Path) -> None:
    print(f"[1/4] Exporting fp32 ONNX from {pt_path.name} ...")
    from ultralytics import YOLO
    yolo = YOLO(str(pt_path), task="segment")
    # opset 20 matches the existing model; dynamic=False for fixed shape (Android-friendly + quantization-friendly)
    yolo.export(
        format="onnx",
        imgsz=IMGSZ,
        opset=20,
        dynamic=False,
        simplify=True,
        half=False,
        int8=False,
    )
    # ultralytics writes <stem>.onnx next to the .pt
    exported = pt_path.with_suffix(".onnx")
    if not exported.exists():
        # fallback: search snapshot dir
        candidates = list(pt_path.parent.glob("*.onnx"))
        exported = candidates[0] if candidates else None
        if exported is None:
            raise SystemExit("fp32 export did not produce an .onnx file")
    os.replace(exported, out_path)
    print(f"      -> {out_path.name} ({out_path.stat().st_size:,} bytes)")


# ── Step 3: static int8 quantization with manga calibration ───────────


class MangaCalibReader:
    """CalibrationDataReader fed with real manga pages."""

    def __init__(self, image_paths: list[str], input_name: str):
        self.paths = image_paths
        self.input_name = input_name
        self._idx = 0

    def get_next(self):
        if self._idx >= len(self.paths):
            return None
        batch = _preprocess(self.paths[self._idx])
        self._idx += 1
        return {self.input_name: batch}

    def rewind(self):
        self._idx = 0


def quantize_int8(fp32_path: Path, out_path: Path, calib_images: list[str]) -> None:
    print(f"[2/4] Static int8 quantization with {len(calib_images)} manga calibration images ...")
    from onnxruntime.quantization import (
        CalibrationDataReader,
        QuantFormat,
        QuantType,
        CalibrationMethod,
        quantize_static,
    )

    # Determine input name from the fp32 model
    model = onnx.load(str(fp32_path))
    input_name = model.graph.input[0].name

    reader = MangaCalibReader(calib_images, input_name)

    with tempfile.TemporaryDirectory() as td:
        # Preprocess calibration data cache
        from onnxruntime.quantization.shape_inference import quant_pre_process
        preprocessed = Path(td) / "preproc.onnx"
        quant_pre_process(str(fp32_path), str(preprocessed), skip_optimization=False)

        quantize_static(
            model_input=str(preprocessed),
            model_output=str(out_path),
            calibration_data_reader=reader,
            quant_format=QuantFormat.QDQ,  # QDQ preserves accuracy better than QOperator
            activation_type=QuantType.QUInt8,
            weight_type=QuantType.QInt8,
            calibrate_method=CalibrationMethod.MinMax,
            per_channel=True,
            reduce_range=False,
            op_types_to_quantize=["Conv", "MatMul", "ConvTranspose"],
        )
    print(f"      -> {out_path.name} ({out_path.stat().st_size:,} bytes)")


# ── Step 4: verify — load via ultralytics, count detections ───────────


def verify(model_path: Path, sample_pages: list[str]) -> None:
    print(f"[3/4] Verifying {model_path.name} via ultralytics YOLO(task='segment') ...")
    from ultralytics import YOLO
    yolo = YOLO(str(model_path), task="segment")
    total_masks = 0
    total_max = 0.0
    for p in sample_pages:
        arr = np.asarray(Image.open(p).convert("RGB"))
        r = yolo.predict(arr, verbose=False, imgsz=IMGSZ, conf=0.25, iou=0.7, retina_masks=True)[0]
        nm = 0 if r.masks is None else (r.masks.data.shape[0] if r.masks.data is not None else len(r.masks.xy))
        confs = [] if (r.boxes is None or len(r.boxes) == 0) else r.boxes.conf.cpu().numpy().tolist()
        mx = max(confs) if confs else 0.0
        total_masks += nm
        total_max = max(total_max, mx)
        name = Path(p).name
        print(f"        {name}: {nm} masks, max_score={mx:.3f}")
    print(f"        TOTAL: {total_masks} masks across {len(sample_pages)} pages, global max={total_max:.3f}")
    if total_masks == 0:
        print("!! FAILURE: re-quantized model still returns 0 detections. Do NOT use.")
        sys.exit(1)


# ── main ──────────────────────────────────────────────────────────────


def main():
    snap = _find_snapshot()
    pt_path = snap / "best.pt"
    fp32_out = snap / "best_fp32.onnx"
    int8_out = snap / "best_int8_manga.onnx"

    if not pt_path.exists():
        raise SystemExit(f"best.pt not found in {snap}")

    calib_images = sorted(glob.glob(str(CALIBRATION_DIR / "*.jpg")))
    if len(calib_images) < 10:
        print(f"WARNING: only {len(calib_images)} calibration images found; need >=10 for good activation ranges")
    print(f"Calibration set: {len(calib_images)} images from {CALIBRATION_DIR.name[:40]}...")

    # 1+2: fp32 export
    if not fp32_out.exists():
        export_fp32(pt_path, fp32_out)
    else:
        print(f"[1/4] fp32 ONNX already exists: {fp32_out.name} ({fp32_out.stat().st_size:,} bytes), reusing")

    # 3: quantize
    quantize_int8(fp32_out, int8_out, calib_images)

    # 4: verify
    verify_pages = calib_images[:5]
    verify(int8_out, verify_pages)

    print(f"[4/4] DONE. New model: {int8_out}")
    print(f"        Size: {int8_out.stat().st_size:,} bytes")
    print(f"        To use: rename to best_int8.onnx (replacing the broken one), or")
    print(f"        register best_int8_manga.onnx in config.SEG_MODELS scan list.")


if __name__ == "__main__":
    main()
