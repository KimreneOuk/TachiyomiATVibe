"""
Emit dynamic-vs-static AOT inpaint outputs for the Tier 3 corpus gate.

This script does ONLY model inference (Python: onnxruntime is Python-only, like
tools/aot_conversion/). It writes model outputs as raw binary (.bin) for the JVM
gate test to read, plus PNGs for human visual QA. The actual guard verdict (the
Kotlin AotOutputGuard) runs in the JVM test AotCorpusGateTest.kt, which reads
the .bin files back. No guard math lives here, so there is no parity mirror to
drift out of sync.

Why .bin not PNG for the test: Android unit tests stub out java.awt, so
javax.imageio is unavailable on the test classpath. The .bin format is trivial
to read with java.io.DataInputStream and carries the exact ARGB IntArray prod
feeds to AotOutputGuard, with no decoder in the loop.

Inputs:
  app/src/main/assets/models/inpainting/aot.onnx      (dynamic)
  app/src/main/assets/models/inpainting/aot-512.onnx  (static, slimmed)
  <corpus>/<page>/{page.jpg, mask.png, manifest.json}

Outputs (one folder per page):
  <out>/<page>/dynamic_out.bin   raw ARGB int32 array (see FORMAT)
  <out>/<page>/static_out.bin    raw ARGB int32 array
  <out>/<page>/mask.bin          raw ARGB int32 array (ALPHA8-style packing)
  <out>/<page>/dynamic_out.png   512x512 RGB — human visual QA only
  <out>/<page>/static_out.png    512x512 RGB — human visual QA only
  <out>/<page>/mask.png          512x512 L — human visual QA only
  <out>/<page>/manifest.json     copied through

FORMAT (.bin): big-endian, no padding
  int32 width
  int32 height
  int32[width*height]  ARGB pixels, Kotlin-Int semantics (signed 32-bit)

ARGB packing mirrors Android Bitmap.getPixels (0xAARRGGBB):
  - output pixels: alpha=0xFF, (r,g,b) from the model output clipped [0,255]
  - mask pixels:   ALPHA8-style. erase -> 0xFF000000 (alpha 255), keep -> 0x00000000.
    AotOutputGuard.maskValue = max(byte0, alpha) -> erase yields 255 (>127=masked).

Usage:
  python tools/aot_corpus/emit_corpus_outputs.py \\
      --corpus tools/aot_corpus/synthetic_corpus \\
      --out app/src/test/resources/corpus/aot
"""
from __future__ import annotations

import argparse
import json
import shutil
import struct
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
DYNAMIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot.onnx"
STATIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot-512.onnx"
MODEL_INPUT_SIZE = 512


def load_model(path: Path) -> ort.InferenceSession:
    if not path.exists():
        print(f"ERROR: model not found: {path}", file=sys.stderr)
        sys.exit(1)
    so = ort.SessionOptions()
    so.log_severity_level = 3  # silence ORT chatter
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def page_to_512_inputs(page_path: Path, mask_path: Path):
    """Resize page+mask to 512x512 bilinear; binarize mask. Returns NCHW float32."""
    page = Image.open(page_path).convert("RGB").resize(
        (MODEL_INPUT_SIZE, MODEL_INPUT_SIZE), Image.BILINEAR
    )
    mask = Image.open(mask_path).convert("L").resize(
        (MODEL_INPUT_SIZE, MODEL_INPUT_SIZE), Image.BILINEAR
    )
    page_arr = np.asarray(page, dtype=np.float32) / 255.0      # HWC [0,1]
    mask_arr = np.asarray(mask, dtype=np.float32) / 255.0      # HW  [0,1]
    mask_arr = (mask_arr > 0.5).astype(np.float32)             # binarize post-resize
    img_chw = np.transpose(page_arr, (2, 0, 1))[None, ...]      # 1,3,H,W
    mask_nchw = mask_arr[None, None, ...]                       # 1,1,H,W
    return np.ascontiguousarray(img_chw), np.ascontiguousarray(mask_nchw), mask_arr


def run_model(sess: ort.InferenceSession, img: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Run inference, return output as HWC uint8 [0,255] (clipped from model range)."""
    out = sess.run(None, {"image": img, "mask": mask})[0]  # 1,3,H,W float
    out = np.clip(out[0], 0.0, 1.0)                         # 3,H,W
    out = np.transpose(out, (1, 2, 0))                      # H,W,3
    return (out * 255.0).astype(np.uint8)


def write_argb_bin(path: Path, hwc: np.ndarray) -> None:
    """Write a HWC uint8 RGB image as big-endian ARGB int32 .bin (see FORMAT in docstring)."""
    h, w, _ = hwc.shape
    r = hwc[..., 0].astype(np.int64)
    g = hwc[..., 1].astype(np.int64)
    b = hwc[..., 2].astype(np.int64)
    # 0xFF<<24 overflows int32; reinterpret as signed to match Kotlin Int.
    unsigned = (np.uint32(0xFF000000).astype(np.uint32) | (r << 16) | (g << 8) | b).astype(np.uint32)
    signed = unsigned.astype(np.int32)
    with open(path, "wb") as f:
        f.write(struct.pack(">i", int(w)))
        f.write(struct.pack(">i", int(h)))
        # Big-endian int32 per pixel.
        f.write(signed.astype(">i4").tobytes())


def write_mask_bin(path: Path, mask_hw: np.ndarray) -> None:
    """Write a HW {0,1} mask as ALPHA8-style ARGB int32 .bin.
    erase (1) -> 0xFF000000 (alpha 255, RGB 0). AotOutputGuard.maskValue=max(byte0, alpha)=255.
    keep (0) -> 0x00000000. maskValue=0 (<127=not masked).
    """
    h, w = mask_hw.shape
    with open(path, "wb") as f:
        f.write(struct.pack(">i", int(w)))
        f.write(struct.pack(">i", int(h)))
        # Build ARGB: alpha=255 where mask==1, else 0. RGB=0.
        alpha = (mask_hw.astype(np.uint32) * np.uint32(0xFF000000)).astype(np.int32)
        f.write(alpha.astype(">i4").tobytes())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, help="corpus dir (one subfolder per page)")
    ap.add_argument("--out", required=True, help="output dir (PNGs written per page)")
    args = ap.parse_args()

    corpus_dir = Path(args.corpus)
    out_dir = Path(args.out)
    if not corpus_dir.is_dir():
        print(f"ERROR: corpus dir not found: {corpus_dir}", file=sys.stderr)
        return 1

    pages = sorted(p for p in corpus_dir.iterdir() if p.is_dir())
    if not pages:
        print(f"ERROR: no page subfolders in {corpus_dir}", file=sys.stderr)
        return 1

    print(f"Loading models ...")
    dyn_sess = load_model(DYNAMIC_MODEL)
    stat_sess = load_model(STATIC_MODEL)

    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for page_dir in pages:
        page_img = page_dir / "page.jpg"
        page_mask = page_dir / "mask.png"
        manifest = page_dir / "manifest.json"
        if not (page_img.exists() and page_mask.exists()):
            print(f"SKIP {page_dir.name}: missing page.jpg or mask.png")
            continue

        img, mask_nchw, mask_hw = page_to_512_inputs(page_img, page_mask)
        dyn_out = run_model(dyn_sess, img, mask_nchw)
        stat_out = run_model(stat_sess, img, mask_nchw)

        page_out = out_dir / page_dir.name
        page_out.mkdir(parents=True, exist_ok=True)
        # Raw ARGB .bin — what the JVM gate test reads.
        write_argb_bin(page_out / "dynamic_out.bin", dyn_out)
        write_argb_bin(page_out / "static_out.bin", stat_out)
        write_mask_bin(page_out / "mask.bin", mask_hw)
        # PNG — human visual QA only.
        Image.fromarray(dyn_out).save(page_out / "dynamic_out.png")
        Image.fromarray(stat_out).save(page_out / "static_out.png")
        Image.fromarray((mask_hw * 255).astype(np.uint8), mode="L").save(page_out / "mask.png")
        if manifest.exists():
            shutil.copy(manifest, page_out / "manifest.json")
        written += 1
        print(f"  {page_dir.name}: wrote dynamic/static/mask .bin + .png")

    print(f"\nDone. {written} page(s) emitted to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
