"""
Visual + timing QA for the three inpainting paths on the real corpus.

Produces, per corpus page, a side-by-side PNG:
  [input+mask | fast push-pull | quality dynamic | quality static-512]
and prints a timing + guard-verdict table.

The three paths mirror prod faithfully:

  FAST     = inpaintReportFreeTextFast (AOTInpainting.kt:356)
             PushPullGradient.localRingMedian + pushPullFill + featherAlpha blend
             (classical, no neural net)

  QUALITY  = inpaintReportFreeTextAot512 (AOTInpainting.kt:378) -> inpaint() (line 421)
  dynamic    dynamic-shape aot.onnx, the current prod model.
  static     static-512 aot-512.onnx, the Wave 5.2 candidate.

All three run on the SAME 512x512 centered crop the corpus already contains (the
region the AOT model sees in prod). This isolates the algorithm difference
from the crop-geometry difference so the comparison is apples-to-apples.

Outputs:
  tools/aot_corpus/qa_output/<page>.png      side-by-side
  tools/aot_corpus/qa_output/qa_report.md    timing + guard verdicts
  tools/aot_corpus/qa_output/qa_report.csv   same, machine-readable

Usage:
  python tools/aot_corpus/qa_compare_modes.py
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import cv2
from PIL import Image, ImageDraw, ImageFont

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS = REPO_ROOT / "tools/aot_corpus/real_corpus_ft"
OUT = REPO_ROOT / "tools/aot_corpus/qa_output"
DYNAMIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot.onnx"
STATIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot-512.onnx"
MODEL_INPUT_SIZE = 512

# --- prod constants (AOTInpainting.kt / PushPullGradient.kt / BubbleMaskBuilder.kt) ---
REPORT_FREE_TEXT_FEATHER = 3      # AOTInpainting.kt:39
PUSH_PULL_RING = 8                # PushPullGradient.DEFAULT_RING
PUSH_PULL_DOWN_DIV = 20           # PushPullGradient.DOWN_SAMPLE_DIV
PUSH_PULL_DIFFUSION_PASSES = 15   # PushPullGradient.DIFFUSION_PASSES


# ============ Push-pull (faithful port of PushPullGradient.kt) ============

def local_ring_median(pixels, width, height, mask, ring=PUSH_PULL_RING):
    """PushPullGradient.localRingMedian. pixels = HxWx3 uint8, mask = HW {0,1}."""
    ys, xs = np.where(mask > 0)
    if len(ys) == 0:
        return tuple(int(np.median(pixels[..., c])) for c in range(3))
    y1, y2 = int(ys.min()), int(ys.max())
    x1, x2 = int(xs.min()), int(xs.max())
    ry1, ry2 = max(0, y1 - ring), min(height, y2 + ring + 1)
    rx1, rx2 = max(0, x1 - ring), min(width, x2 + ring + 1)
    # Annulus = ring rect minus the mask bbox interior (PushPullGradient.kt:84-99)
    ah, aw = ry2 - ry1, rx2 - rx1
    inY1, inX1 = y1 - ry1, x1 - rx1
    inY2, inX2 = inY1 + (y2 - y1 + 1), inX1 + (x2 - x1 + 1)
    keep = np.ones((ah, aw), dtype=bool)
    keep[max(0, inY1):min(ah, inY2), max(0, inX1):min(aw, inX2)] = False
    samples = pixels[ry1:ry2, rx1:rx2][keep]
    if len(samples) == 0:
        return tuple(int(np.median(pixels[..., c])) for c in range(3))
    return tuple(int(np.median(samples[:, c])) for c in range(3))


def push_pull_fill(pixels, width, height, mask, bg_rgb):
    """PushPullGradient.pushPullFill. pixels = HxWx3 uint8 (mutated copy), mask HW {0,1}."""
    work = pixels.copy()
    # a. erase ink with bg
    work[mask > 0] = bg_rgb
    # b. box-average downscale
    small_w = max(2, width // PUSH_PULL_DOWN_DIV)
    small_h = max(2, height // PUSH_PULL_DOWN_DIV)
    small = _downsample_box_avg(work, width, height, small_w, small_h)
    # c. bilinear upscale
    gradient = _upsample_bilinear(small, small_w, small_h, width, height)
    # d. paint gradient into hole
    work[mask > 0] = gradient[mask > 0]
    # e. boundary diffusion (edge-clamped 4-neighbour avg), DIFFUSION_PASSES iterations
    for _ in range(PUSH_PULL_DIFFUSION_PASSES):
        scratch = work.copy()
        masked_idx = mask > 0
        if not masked_idx.any():
            break
        # 4-neighbour avg with edge clamp
        up = np.roll(work, -1, axis=0); up[-1] = work[-1]
        down = np.roll(work, 1, axis=0); down[0] = work[0]
        left = np.roll(work, -1, axis=1); left[:, -1] = work[:, -1]
        right = np.roll(work, 1, axis=1); right[:, 0] = work[:, 0]
        avg4 = (up.astype(np.int32) + down.astype(np.int32) + left.astype(np.int32) + right.astype(np.int32) + 2) >> 2
        avg4 = np.clip(avg4, 0, 255).astype(np.uint8)
        work[masked_idx] = avg4[masked_idx]
    return work


def _downsample_box_avg(src, width, height, sw, sh):
    """PushPullGradient.downsampleBoxAvg — area-average downscale."""
    # Use PIL resize with BOX (area average) for fidelity to the box-sum math.
    img = Image.fromarray(src, mode="RGB").resize((sw, sh), Image.BOX)
    return np.asarray(img, dtype=np.uint8)


def _upsample_bilinear(small, sw, sh, width, height):
    """PushPullGradient.upsampleBilinear."""
    img = Image.fromarray(small, mode="RGB").resize((width, height), Image.BILINEAR)
    return np.asarray(img, dtype=np.uint8)


# ============ feather + blend (BubbleMaskBuilder.featherAlphaField + AotPixelOps.blendPixel) ============

def feather_blend(original, work, mask, width, height, ramp=REPORT_FREE_TEXT_FEATHER):
    """Apply featherAlphaField + blendPixel: composite work onto original with
    a distance-field alpha ramp at the mask edge."""
    alpha = _feather_alpha_field(mask, width, height, ramp)
    a = alpha[..., None]  # HxWx1
    out = original.astype(np.float32) * (1.0 - a) + work.astype(np.float32) * a
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def _feather_alpha_field(mask, width, height, ramp):
    """BubbleMaskBuilder.featherAlphaField via distanceToMask (chamfer)."""
    if not mask.any():
        return np.zeros((height, width), dtype=np.float32)
    # distance transform: distance from each pixel to nearest mask pixel
    # cv2.distanceTransform gives distance from zero pixels to nearest nonzero;
    # invert mask so it gives distance from non-mask to nearest mask.
    inv = (mask == 0).astype(np.uint8)
    dist = cv2.distanceTransform(inv, cv2.DIST_L2, 3)
    ramp_f = float(max(2, ramp))
    alpha = np.where(mask > 0, 1.0, np.clip(1.0 - dist / ramp_f, 0.0, 1.0))
    return alpha.astype(np.float32)


# ============ AOT model (mirrors emit_corpus_outputs.py faithful path) ============

def aot_inpaint(sess, page_arr, mask_arr):
    """Run AOT model on a 512x512 crop. Mirrors AOTInpainting.inpaint tensor prep.
    page_arr = HxWx3 uint8, mask_arr = HW {0,1}. Returns HxWx3 uint8."""
    img_norm = page_arr.astype(np.float32) / 127.5 - 1.0
    img_norm = img_norm * (1.0 - mask_arr[..., None])  # black out masked
    img_chw = np.ascontiguousarray(img_norm.transpose(2, 0, 1)[None, ...], dtype=np.float32)
    mask_nchw = np.ascontiguousarray(mask_arr[None, None, ...], dtype=np.float32)
    out = sess.run(None, {"image": img_chw, "mask": mask_nchw})[0][0]  # 3,H,W
    out = np.transpose(out, (1, 2, 0))
    out = np.clip((out + 1.0) * 127.5, 0, 255)
    # grayscale luma collapse if input grayscale
    r, g, b = page_arr[..., 0], page_arr[..., 1], page_arr[..., 2]
    chroma = (np.maximum(np.maximum(r, g), b) - np.minimum(np.minimum(r, g), b))
    if chroma.mean() < 15.0:
        luma = np.round(0.299 * out[..., 0] + 0.587 * out[..., 1] + 0.114 * out[..., 2]).astype(np.uint8)
        return np.stack([luma, luma, luma], axis=-1)
    return out.astype(np.uint8)


# ============ AotOutputGuard (for the verdict column) ============

def guard_verdict(out_rgb, mask, width, height):
    """AotOutputGuard.isSuspiciousUniformFill on the masked region."""
    masked = mask > 0
    if masked.sum() < 16:
        return "skip<16", (0, 0, 0)
    px = out_rgb[masked]
    r, g, b = px[:, 0], px[:, 1], px[:, 2]
    luma = 0.299 * r + 0.587 * g + 0.114 * b
    mean = luma.mean()
    var = (luma * luma).mean() - mean * mean
    cd = (np.abs(r - g) + np.abs(g - b)).mean()
    uniform = var < 9.0 and cd < 8.0
    if not uniform:
        return "accept", (mean, var, cd)
    if mean <= 24.0:
        return "REJECT(near-black)", (mean, var, cd)
    if 96.0 <= mean <= 160.0:
        return "REJECT(mid-gray)", (mean, var, cd)
    if mean >= 238.0:
        return "REJECT(near-white)", (mean, var, cd)
    return "accept", (mean, var, cd)


# ============ Visualization ============

def make_side_by_side(page_rgb, mask, fast, dyn, stat, page_name, category, timings, verdicts):
    """4-panel side-by-side: input+mask-overlay | fast | dynamic | static."""
    H, W = page_rgb.shape[:2]
    pad = 4
    label_h = 28
    total_w = W * 4 + pad * 5
    total_h = H + label_h + pad * 2
    canvas = Image.new("RGB", (total_w, total_h), (32, 32, 32))
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype("arial.ttf", 16)
    except OSError:
        font = ImageFont.load_default()

    # input + mask overlay (red tint on masked region)
    inp = page_rgb.copy()
    overlay = inp.copy()
    overlay[mask > 0] = [255, 60, 60]
    inp_blend = (inp.astype(np.float32) * 0.55 + overlay.astype(np.float32) * 0.45).astype(np.uint8)

    panels = [
        (inp_blend, f"input + mask", ""),
        (fast, f"FAST push-pull", f"{timings['fast']*1000:.0f}ms  {verdicts['fast'][0]}"),
        (dyn, f"QUALITY dynamic", f"{timings['dyn']*1000:.0f}ms  {verdicts['dyn'][0]}"),
        (stat, f"QUALITY static-512", f"{timings['stat']*1000:.0f}ms  {verdicts['stat'][0]}"),
    ]
    for i, (img, title, sub) in enumerate(panels):
        x = pad + i * (W + pad)
        canvas.paste(Image.fromarray(img, "RGB"), (x, label_h))
        draw.text((x + 4, 2), title, fill=(255, 255, 255), font=font)
        if sub:
            draw.text((x + 4, label_h - 16), sub, fill=(180, 220, 255), font=font)
    draw.text((4, total_h - 16), f"{page_name}  [{category}]", fill=(255, 230, 120), font=font)
    return canvas


# ============ Main ============

def main() -> int:
    if not CORPUS.is_dir():
        print(f"ERROR: corpus not found: {CORPUS}", file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)

    print("Loading AOT models ...")
    so = ort.SessionOptions(); so.log_severity_level = 3
    dyn_sess = ort.InferenceSession(str(DYNAMIC_MODEL), so, providers=["CPUExecutionProvider"])
    stat_sess = ort.InferenceSession(str(STATIC_MODEL), so, providers=["CPUExecutionProvider"])

    pages = sorted(d for d in CORPUS.iterdir() if d.is_dir() and d.name.startswith("real_"))
    if not pages:
        print(f"ERROR: no real_* pages in {CORPUS}", file=sys.stderr)
        return 1

    rows = []
    # warmup (first run is slower: model load / thread spin-up)
    p0 = pages[0]
    pa = np.asarray(Image.open(p0 / "page.jpg").convert("RGB").resize((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)))
    ma = (np.asarray(Image.open(p0 / "mask.png").convert("L").resize((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE))) > 127).astype(np.uint8)
    aot_inpaint(dyn_sess, pa, ma); aot_inpaint(stat_sess, pa, ma)

    print(f"\n{'page':<10}{'category':<22}{'fast(ms)':>9}{'dyn(ms)':>9}{'stat(ms)':>9}  fast_verdict        dyn_verdict         stat_verdict")
    for d in pages:
        name = d.name
        manifest = json.loads((d / "manifest.json").read_text())
        cat = manifest.get("category", "?")
        page_rgb = np.asarray(Image.open(d / "page.jpg").convert("RGB").resize((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)))
        mask_hw = (np.asarray(Image.open(d / "mask.png").convert("L").resize((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE))) > 127).astype(np.uint8)
        H, W = mask_hw.shape

        # FAST: push-pull + feather blend (on the 512 crop, same canvas)
        t = time.perf_counter()
        bg = local_ring_median(page_rgb, W, H, mask_hw)
        filled = push_pull_fill(page_rgb, W, H, mask_hw, bg)
        fast_out = feather_blend(page_rgb, filled, mask_hw, W, H)
        t_fast = time.perf_counter() - t

        # QUALITY dynamic
        t = time.perf_counter()
        dyn_out = aot_inpaint(dyn_sess, page_rgb, mask_hw)
        t_dyn = time.perf_counter() - t

        # QUALITY static-512
        t = time.perf_counter()
        stat_out = aot_inpaint(stat_sess, page_rgb, mask_hw)
        t_stat = time.perf_counter() - t

        v_fast = guard_verdict(fast_out, mask_hw, W, H)
        v_dyn = guard_verdict(dyn_out, mask_hw, W, H)
        v_stat = guard_verdict(stat_out, mask_hw, W, H)

        timings = {"fast": t_fast, "dyn": t_dyn, "stat": t_stat}
        verdicts = {"fast": v_fast, "dyn": v_dyn, "stat": v_stat}
        side = make_side_by_side(page_rgb, mask_hw, fast_out, dyn_out, stat_out, name, cat, timings, verdicts)
        side.save(OUT / f"{name}.png")

        print(f"{name:<10}{cat:<22}{t_fast*1000:>9.0f}{t_dyn*1000:>9.0f}{t_stat*1000:>9.0f}  {v_fast[0]:<20}{v_dyn[0]:<20}{v_stat[0]}")
        rows.append({
            "page": name, "category": cat,
            "fast_ms": round(t_fast * 1000), "dyn_ms": round(t_dyn * 1000), "stat_ms": round(t_stat * 1000),
            "fast_verdict": v_fast[0], "dyn_verdict": v_dyn[0], "stat_verdict": v_stat[0],
            "fast_stats": [round(x, 1) for x in v_fast[1]],
            "dyn_stats": [round(x, 1) for x in v_dyn[1]],
            "stat_stats": [round(x, 1) for x in v_stat[1]],
        })

    # Summary report
    avg_fast = np.mean([r["fast_ms"] for r in rows])
    avg_dyn = np.mean([r["dyn_ms"] for r in rows])
    avg_stat = np.mean([r["stat_ms"] for r in rows])
    md = ["# Inpainting QA — fast vs quality (dynamic vs static-512)\n"]
    md.append(f"{len(rows)} faithful free-text pages (AOT-512 path inputs), 512x512 centered crops, CPU onnxruntime.\n")
    md.append(f"**Avg latency:** fast push-pull = {avg_fast:.0f}ms | quality dynamic = {avg_dyn:.0f}ms | quality static-512 = {avg_stat:.0f}ms\n")
    md.append(f"Static-512 vs dynamic speedup: {(avg_dyn/avg_stat):.2f}x (if <1, static is slower).\n\n")
    md.append("| page | category | fast ms | dyn ms | stat ms | fast verdict | dyn verdict | stat verdict |")
    md.append("|---|---|---|---|---|---|---|---|")
    for r in rows:
        md.append(f"| {r['page']} | {r['category']} | {r['fast_ms']} | {r['dyn_ms']} | {r['stat_ms']} | {r['fast_verdict']} | {r['dyn_verdict']} | {r['stat_verdict']} |")
    (OUT / "qa_report.md").write_text("\n".join(md) + "\n")
    import csv
    with open(OUT / "qa_report.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    print(f"\nSide-by-side PNGs + report in {OUT}")
    print(f"avg: fast={avg_fast:.0f}ms dyn={avg_dyn:.0f}ms stat={avg_stat:.0f}ms (static/dyn={avg_stat/avg_dyn:.2f}x)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
