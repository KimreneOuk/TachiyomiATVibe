"""
Tier 3 corpus gate harness for the fixed-512 AOT model.

What it does:
  For each page in a corpus directory, runs BOTH the dynamic and the
  static-512 AOT models, applies AotOutputGuard (numpy parity impl), and
  reports:
    - verdict change (the gate criterion: zero NEW rejections allowed)
    - stat shifts (mean / variance delta) for visual-QA flagging
    - side-by-side output PNGs for human visual QA

Inputs:
  - app/src/main/assets/models/inpainting/aot.onnx      (dynamic)
  - app/src/main/assets/models/inpainting/aot-512.onnx  (static, slimmed)
  - corpus directory: one folder per page, each containing:
        page.jpg            (the source page image; any size)
        mask.png            (the inpaint mask; white = erase, black = keep)
        manifest.json       (page metadata; see manifest.schema.json)

Outputs (in --out, default tools/aot_corpus/gate_output/):
  - baseline_report.json   (per-page: old/new verdict, stats, deltas, paths)
  - side_by_side/<page>.png (visual QA, only if --save-images)
  - stdout summary         (verdict changes, gate PASS/FAIL)

Gate criterion (per MASTER_IMPLEMENTATION_PLAN_2026-07-12.md Wave 5.2):
  Zero NEW rejections. The static model may produce a verdict the dynamic one
  did not only if the dynamic verdict was already suspicious (rare). Any
  page where dynamic=accept and static=reject is a regression and FAILS the
  gate.

Numerics:
  - Image is resized to 512x512 (the static model's fixed input) via PIL
    bilinear; the dynamic model is fed the SAME 512 crop so the comparison
    isolates the static-shape effect, not resize differences.
  - Output (float32 [-1,1]-ish) is denormalized to ARGB IntArray form for the
    guard, matching how AOTInpainting postprocesses (rescale to 0-255, pack
    ARGB).

Usage:
  python tools/aot_corpus/run_corpus_gate.py \\
      --corpus tools/aot_corpus/synthetic_corpus \\
      --out tools/aot_corpus/gate_output

  # Add --save-images to dump side-by-side PNGs for visual QA.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

from aot_output_guard import GuardStats, inspect, classify

REPO_ROOT = Path(__file__).resolve().parents[2]
DYNAMIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot.onnx"
STATIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot-512.onnx"
MODEL_INPUT_SIZE = 512


def load_model(path: Path) -> ort.InferenceSession:
    if not path.exists():
        print(f"ERROR: model not found: {path}", file=sys.stderr)
        sys.exit(1)
    so = ort.SessionOptions()
    so.log_severity_level = 3  # silence ORT warnings
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def page_to_512_inputs(page_path: Path, mask_path: Path):
    """
    Load page + mask, resize both to 512x512 bilinear, return (image_float, mask_float)
    in the NCHW [1,3,512,512] / [1,1,512,512] layout the AOT model expects.

    AOT expects:
      image: float32, NCHW, values in [0,1] (Bitmap/255).
      mask:  float32, NCHW, values in {0,1}.
    """
    page = Image.open(page_path).convert("RGB").resize(
        (MODEL_INPUT_SIZE, MODEL_INPUT_SIZE), Image.BILINEAR
    )
    mask = Image.open(mask_path).convert("L").resize(
        (MODEL_INPUT_SIZE, MODEL_INPUT_SIZE), Image.BILINEAR
    )
    page_arr = np.asarray(page, dtype=np.float32) / 255.0  # HWC, [0,1]
    mask_arr = np.asarray(mask, dtype=np.float32) / 255.0  # HW, [0,1]
    mask_arr = (mask_arr > 0.5).astype(np.float32)  # binarize after resize

    # HWC -> CHW -> NCHW.
    img_chw = np.transpose(page_arr, (2, 0, 1))[None, ...]  # 1,3,H,W
    mask_nchw = mask_arr[None, None, ...]  # 1,1,H,W
    return np.ascontiguousarray(img_chw), np.ascontiguousarray(mask_nchw)


def output_to_argb(out: np.ndarray) -> np.ndarray:
    """
    Convert the model's [1,3,H,W] float output to a Kotlin-IntArray-equivalent
    ARGB-packed array, matching how AOTInpainting postprocesses the AOT output
    back into an Android Bitmap.

    AOT output is approximately in [-1, 1]; clip to [0,1], scale to [0,255],
    pack ARGB. Alpha is 0xFF (opaque).
    """
    out = np.clip(out[0], 0.0, 1.0)  # 3,H,W
    out = np.transpose(out, (1, 2, 0))  # H,W,3
    out255 = (out * 255.0).astype(np.int64)
    r, g, b = out255[..., 0], out255[..., 1], out255[..., 2]
    argb = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
    # numpy int32 view — Kotlin Int is signed 32-bit.
    return argb.astype(np.int32).flatten()


def mask_to_argb(mask_nchw: np.ndarray) -> np.ndarray:
    """Pack a [1,1,H,W] mask into ARGB IntArray form. Mask value 1 -> opaque white."""
    m = mask_nchw[0, 0]  # H,W
    # Mask value per AotOutputGuard.maskValue = max(byte0, alpha). For value=1
    # (255 after *255), byte0=255 -> maskValue=255 > 127 threshold. Pack as
    # opaque white; byte0 carries the mask signal.
    val = (m * 255).astype(np.int64) & 0xFF
    argb = (0xFF << 24) | (val << 16) | (val << 8) | val
    return argb.astype(np.int32).flatten()


def run_one(sess: ort.InferenceSession, img: np.ndarray, mask: np.ndarray):
    out = sess.run(None, {"image": img, "mask": mask})[0]
    out_argb = output_to_argb(out)
    mask_argb = mask_to_argb(mask)
    stats = inspect(out_argb, mask_argb)
    verdict = classify(stats)
    return verdict, stats, out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, help="corpus directory (one subfolder per page)")
    ap.add_argument("--out", default="tools/aot_corpus/gate_output", help="output directory")
    ap.add_argument("--save-images", action="store_true", help="save side-by-side PNGs for visual QA")
    args = ap.parse_args()

    corpus_dir = Path(args.corpus)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    sbs_dir = out_dir / "side_by_side"
    if args.save_images:
        sbs_dir.mkdir(parents=True, exist_ok=True)

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

    report = {"pages": [], "summary": {}}
    new_rejections = 0
    stat_shifts = 0

    for page_dir in pages:
        page_img = page_dir / "page.jpg"
        page_mask = page_dir / "mask.png"
        manifest_path = page_dir / "manifest.json"
        if not (page_img.exists() and page_mask.exists()):
            print(f"SKIP {page_dir.name}: missing page.jpg or mask.png")
            continue

        manifest = {}
        if manifest_path.exists():
            manifest = json.loads(manifest_path.read_text())

        img, mask = page_to_512_inputs(page_img, page_mask)
        dyn_verdict, dyn_stats, dyn_out = run_one(dyn_sess, img, mask)
        stat_verdict, stat_stats, stat_out = run_one(stat_sess, img, mask)

        # Gate logic.
        is_new_rejection = (not dyn_verdict) and stat_verdict
        if is_new_rejection:
            new_rejections += 1
        mean_delta = abs(stat_stats.mean - dyn_stats.mean)
        var_delta = abs(stat_stats.variance - dyn_stats.variance)
        shifted = mean_delta > 5.0 or var_delta > 2.0
        if shifted:
            stat_shifts += 1

        entry = {
            "page": page_dir.name,
            "category": manifest.get("category", "unknown"),
            "expected_box_count": manifest.get("expected_box_count"),
            "dynamic": {"verdict": dyn_verdict, **_stats_dict(dyn_stats)},
            "static": {"verdict": stat_verdict, **_stats_dict(stat_stats)},
            "delta": {"mean": stat_stats.mean - dyn_stats.mean,
                      "variance": stat_stats.variance - dyn_stats.variance},
            "new_rejection": is_new_rejection,
            "stats_shifted": shifted,
        }
        report["pages"].append(entry)
        flag = " NEW-REJECTION" if is_new_rejection else (" SHIFT" if shifted else "")
        print(f"  {page_dir.name:30s} dyn={int(dyn_verdict)} stat={int(stat_verdict)}"
              f"  mean_d={entry['delta']['mean']:+.2f} var_d={entry['delta']['variance']:+.2f}{flag}")

        if args.save_images:
            _save_side_by_side(sbs_dir / f"{page_dir.name}.png", page_img, dyn_out, stat_out, dyn_verdict, stat_verdict)

    report["summary"] = {
        "pages_run": len(report["pages"]),
        "new_rejections": new_rejections,
        "stats_shifts": stat_shifts,
        "gate_pass": new_rejections == 0,
    }
    (out_dir / "baseline_report.json").write_text(json.dumps(report, indent=2))

    print()
    print("=" * 60)
    print(f"Pages run:        {report['summary']['pages_run']}")
    print(f"New rejections:   {new_rejections}")
    print(f"Stats shifts:     {stat_shifts}")
    print(f"Gate (zero new):  {'PASS' if report['summary']['gate_pass'] else 'FAIL'}")
    print("=" * 60)
    return 0 if report["summary"]["gate_pass"] else 2


def _stats_dict(s: GuardStats) -> dict:
    d = asdict(s)
    # asdict uses field names with the dataclass's snake_case; rename to match Kotlin.
    return {"mean": d["mean"], "variance": d["variance"],
            "channel_delta": d["channel_delta"], "masked_count": d["masked_count"]}


def _save_side_by_side(path: Path, page_img: Path, dyn_out: np.ndarray, stat_out: np.ndarray,
                       dyn_verdict: bool, stat_verdict: bool) -> None:
    """Save a 3-panel side-by-side: original page, dynamic output, static output."""
    try:
        orig = Image.open(page_img).convert("RGB").resize((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE), Image.BILINEAR)
        dyn_img = _out_to_pil(dyn_out)
        stat_img = _out_to_pil(stat_out)
        w, h = MODEL_INPUT_SIZE, MODEL_INPUT_SIZE
        canvas = Image.new("RGB", (w * 3 + 20, h + 30), (32, 32, 32))
        canvas.paste(orig, (0, 30))
        canvas.paste(dyn_img, (w + 10, 30))
        canvas.paste(stat_img, (w * 2 + 20, 30))
        canvas.save(path)
    except Exception as e:
        print(f"    (could not save side-by-side for {path.name}: {e})")


def _out_to_pil(out: np.ndarray) -> Image.Image:
    out = np.clip(out[0], 0.0, 1.0)
    out = np.transpose(out, (1, 2, 0))
    out255 = (out * 255.0).astype(np.uint8)
    return Image.fromarray(out255, "RGB")


if __name__ == "__main__":
    sys.exit(main())
