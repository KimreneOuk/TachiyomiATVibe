"""Step 4: validation gate — PyTorch sample() vs graph-driven Euler loop.

Runs the pinned model.sample() on >=3 B&W manga pages (512x768), then the same
pages through the exported .tflite graphs driven by a pure-python loop that
replicates sample() (FlowMatchEulerDiscreteScheduler, 20 steps, CFG 2.0, seeded
noise, python-side look-vector lookup, pinned merge_lines). Reports per-page
max abs diff + PSNR, side-by-side sheets, per-graph latencies.

Writes: exported/validation/{pages,ref_png,graph_png,sheets}/*.png,
exported/validation/parity_report.md, exported/validation/latency.json.
"""

import gc
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

import numpy as np
import torch
from PIL import Image, ImageDraw


VAL_DIR = os.path.join(common.EXPORTED, "validation")


# ---------------------------------------------------------------- pages

def make_synthetic_page(seed):
    """Structured B&W manga-like page (portrait 512x768): panels, screentone,
    bubble, strokes."""
    rng = np.random.default_rng(seed)
    img = Image.new("L", (common.W_PX, common.H_PX), 255)
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, common.W_PX - 1, common.H_PX - 1], outline=0, width=6)
    # Panel borders.
    draw.rectangle([24, 24, common.W_PX - 24, 300], outline=0, width=4)
    draw.rectangle([24, 320, 240, common.H_PX - 24], outline=0, width=4)
    draw.rectangle([260, 320, common.W_PX - 24, 560], outline=0, width=4)
    draw.rectangle([260, 580, common.W_PX - 24, common.H_PX - 24], outline=0, width=3)
    # Speed lines in panel 1.
    for i in range(40):
        y = 40 + i * 6
        w = int(rng.integers(1, 4))
        draw.line([(40, y), (int(180 + 240 * rng.random()), y)], fill=0, width=w)
    # Radial action burst in panel 3.
    cx, cy = 380, 440
    for a in range(0, 360, 9):
        r_in, r_out = 30 + 18 * (a % 3), 100
        x0 = cx + r_in * math.cos(math.radians(a)); y0 = cy + r_in * math.sin(math.radians(a))
        x1 = cx + r_out * math.cos(math.radians(a)); y1 = cy + r_out * math.sin(math.radians(a))
        draw.line([(x0, y0), (x1, y1)], fill=40, width=2)
    # Speech bubble + text-like marks in panel 2.
    draw.ellipse([40, 330, 230, 420], outline=0, width=3, fill=255)
    for line in range(3):
        x = 60
        y = 355 + line * 20
        while x < 210:
            seg = int(rng.integers(8, 30))
            draw.line([(x, y), (min(x + seg, 210), y + int(rng.integers(-3, 4)))], fill=0, width=2)
            x += seg + int(rng.integers(4, 12))
    # Screentone dots in panel 4.
    for y in range(600, common.H_PX - 30, 7):
        for x in range(275, common.W_PX - 30, 7):
            r = 1 + (x + y) % 3
            draw.ellipse([x, y, x + r, y + r], fill=80)
    # Hatching band.
    for i in range(-300, 300, 10):
        draw.line([(i, 730), (i + 200, 590)], fill=120, width=2)
    arr = np.array(img, dtype=np.uint8)
    # Slight paper noise so pages are not perfectly flat.
    noise = rng.normal(0, 3.0, arr.shape)
    arr = np.clip(arr.astype(np.float64) + noise, 0, 255).astype(np.uint8)
    return arr


def make_results_page(jpg_path, seed=0):
    """Grayscale conversion of the repo's colored sample output (structured
    image -> valid B&W input for parity math)."""
    arr = np.array(Image.open(jpg_path).convert("L"), dtype=np.uint8)
    pil = Image.fromarray(arr)
    w, h = pil.size
    target = common.W_PX / common.H_PX
    if w / h > target:  # crop to 2:3
        nw = int(h * target)
        x0 = (w - nw) // 2
        pil = pil.crop((x0, 0, x0 + nw, h))
    else:
        nh = int(w / target)
        y0 = (h - nh) // 2
        pil = pil.crop((0, y0, w, y0 + nh))
    return np.array(pil.resize((common.W_PX, common.H_PX), Image.BILINEAR), dtype=np.uint8)


def build_pages():
    pages = []
    pages.append(("synth_panels", make_synthetic_page(11)))
    pages.append(("synth_screentone", make_synthetic_page(12)))
    results_dir = os.path.join(common.COLORING_REPO, "results")
    jpgs = sorted(os.listdir(results_dir))
    for i, name in enumerate(jpgs[:2]):
        pages.append(("from_results_%d" % (i + 1), make_results_page(os.path.join(results_dir, name))))
    return pages


def to_bw_tensor(arr_u8):
    """(H,W) uint8 -> (1,3,H,W) float32 in [-1,1] + the [0,1] version."""
    x = torch.from_numpy(np.ascontiguousarray(arr_u8)).float() / 255.0
    x = (x * 2.0 - 1.0).unsqueeze(0).unsqueeze(0).repeat(1, 3, 1, 1)
    return x, (x * 0.5 + 0.5)


def save_png(arr01_u8, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(arr01_u8).save(path)


def sheet(bw01, ref01, graph01, path):
    h, w = bw01.shape
    canvas = Image.new("RGB", (w * 3 + 40, h + 20), (220, 220, 220))
    for i, a in enumerate((bw01, ref01, graph01)):
        im = Image.fromarray(a).convert("RGB")
        canvas.paste(im, (10 + i * (w + 10), 10))
    canvas.save(path)


def psnr(a, b):
    mse = float(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    if mse <= 0:
        return 99.0
    return 10 * math.log10(1.0 / mse)


# ------------------------------------------------- sampling / parity

def torch_reference(colorizer, vae, pinned_model, bw, seed):
    ver = torch.full((1,), common.LOOK_NORMAL, dtype=torch.long)
    out = pinned_model.sample(colorizer, vae, bw.clone(), ver, steps=20, cfg=2.0, shift=3.0, sigma=3.0, ink=1.0, seed=seed)
    return out.clamp(0, 1)


def graph_loop(colorizer, pinned_model, bw, seed, paths, lat):
    """Replicates pinned model.sample() with the tflite graphs + python Euler."""
    bwcat = torch.cat([bw, bw], 0)
    t0 = time.perf_counter()
    z_bw_all = common.interpreter_run(paths["enc"], {"x": bwcat.numpy()})[0]
    lat["vae_enc"] = time.perf_counter() - t0
    z_bw = torch.from_numpy(z_bw_all[:1].copy())

    g = torch.Generator().manual_seed(seed)
    z = torch.randn(1, 32, bw.shape[2] // 32, bw.shape[3] // 32, generator=g)
    from diffusers import FlowMatchEulerDiscreteScheduler
    sched = FlowMatchEulerDiscreteScheduler(shift=3.0)
    sched.set_timesteps(20, device="cpu")
    ver_c = torch.full((1,), common.LOOK_NORMAL, dtype=torch.long)
    ver_u = torch.full((1,), common.LOOK_NULL, dtype=torch.long)
    tokens_c = common.look_tokens(colorizer, ver_c)
    tokens_u = common.look_tokens(colorizer, ver_u)
    tokens2 = torch.cat([tokens_c, tokens_u], 0)
    step_times = []
    for t in sched.timesteps:
        tin = t.expand(2).float().reshape(2)
        t0 = time.perf_counter()
        vel = common.interpreter_run(paths["dit"], {
            "z_t": torch.cat([z, z], 0).numpy(),
            "z_bw": torch.cat([z_bw, z_bw], 0).numpy(),
            "bw": bwcat.numpy(),
            "t": tin.numpy(),
            "tokens": tokens2.numpy(),
        })[0]
        step_times.append(time.perf_counter() - t0)
        vc, vu = torch.from_numpy(vel[:1].copy()), torch.from_numpy(vel[1:].copy())
        z = sched.step(vu + 2.0 * (vc - vu), t, z).prev_sample
    lat["dit_step_avg"] = float(np.mean(step_times))
    lat["dit_step_max"] = float(np.max(step_times))

    t0 = time.perf_counter()
    dec_all = common.interpreter_run(paths["dec"], {"z": torch.cat([z, z], 0).numpy()})[0]
    lat["vae_dec"] = time.perf_counter() - t0
    rgb = torch.from_numpy(dec_all[:1].copy()).float()
    out = pinned_model.merge_lines(rgb, (bw * 0.5 + 0.5).float(), 3.0, ink=1.0).clamp(0, 1)
    return out


def main():
    os.makedirs(VAL_DIR, exist_ok=True)
    for sub in ("pages", "ref_png", "graph_png", "sheets"):
        os.makedirs(os.path.join(VAL_DIR, sub), exist_ok=True)

    pinned_model = common.import_pinned_code()
    colorizer = common.load_colorizer_fp32()
    vae = common.build_vae_fp32()

    paths = {
        "dit": os.path.join(common.EXPORTED, "dit_fp16.tflite"),
        "enc": os.path.join(common.EXPORTED, "vae_enc_fp16.tflite"),
        "dec": os.path.join(common.EXPORTED, "vae_dec_fp16.tflite"),
    }
    for k, p in paths.items():
        if not os.path.exists(p):
            raise FileNotFoundError(p)

    pages = build_pages()
    rows = []
    lat_total = {}
    for idx, (name, arr) in enumerate(pages):
        seed = 100 + idx
        bw, bw01 = to_bw_tensor(arr)
        u8 = lambda x: (np.clip(x, 0, 1) * 255).round().astype(np.uint8)[0, 0]
        save_png(arr, os.path.join(VAL_DIR, "pages", "%s_bw.png" % name))
        save_png(u8(bw01.numpy()), os.path.join(VAL_DIR, "pages", "%s_bw01.png" % name))

        t0 = time.perf_counter()
        ref = torch_reference(colorizer, vae, pinned_model, bw, seed)
        torch_seconds = time.perf_counter() - t0
        graph01 = (bw * 0.5 + 0.5)
        gout = graph_loop(colorizer, pinned_model, bw, seed, paths, lat_total)
        save_png(u8(ref.numpy()), os.path.join(VAL_DIR, "ref_png", "%s.png" % name))
        save_png(u8(gout.numpy()), os.path.join(VAL_DIR, "graph_png", "%s.png" % name))
        sheet(u8(graph01.numpy()), u8(ref.numpy()), u8(gout.numpy()),
              os.path.join(VAL_DIR, "sheets", "%s_side_by_side.png" % name))
        diff = float(np.abs(ref.numpy() - gout.numpy()).max())
        rows.append({
            "page": name, "seed": seed,
            "max_abs_pixel_diff": diff,
            "psnr_db": psnr(u8(ref.numpy()), u8(gout.numpy())),
            "torch_reference_seconds": round(torch_seconds, 1),
        })
        print("PAGE %s max_abs=%.4f psnr=%.2f dB (torch %.0fs)" % (
            name, diff, rows[-1]["psnr_db"], torch_seconds))
        gc.collect()

    lat = {"per_call_seconds": lat_total, "device": "tflite CPU interpreter, 4 threads"}
    common.write_json(os.path.join(VAL_DIR, "latency.json"), lat)

    report = ["# Graph-driven vs PyTorch parity report (UTF-8)", "",
              "Reference: pinned model.sample() (fp32 Colorizer + fp32 DC-AE, steps=20, cfg=2.0, shift=3.0, sigma=3.0).",
              "Graph side: dit_fp16/vae_enc_fp16/vae_dec_fp16 via LiteRT CPU interpreter, python Euler loop,",
              "python-side look-vector lookup (normal look + NULL zero vector), pinned merge_lines(sigma=3.0, ink=1.0).", "",
              "| page | seed | max abs pixel diff | PSNR (dB) | torch ref (s) |",
              "|---|---|---|---|---|"]
    for r in rows:
        report.append("| %s | %d | %.4f | %.2f | %.0f |" % (r["page"], r["seed"], r["max_abs_pixel_diff"], r["psnr_db"], r["torch_reference_seconds"]))
    report += ["", "Latency (informational, this machine):",
               "- DiT step avg %.2fs, max %.2fs (graph, CPU interpreter)" % (lat_total.get("dit_step_avg", -1), lat_total.get("dit_step_max", -1)),
               "- VAE enc %.2fs, VAE dec %.2fs" % (lat_total.get("vae_enc", -1), lat_total.get("vae_dec", -1))]
    common.write_text(os.path.join(VAL_DIR, "parity_report.md"), "\n".join(report) + "\n")
    common.write_json(os.path.join(VAL_DIR, "parity_rows.json"), rows)
    print("STEP4_DONE pages=%d worst_psnr=%.2f worst_max_abs=%.4f" % (
        len(rows), min(r["psnr_db"] for r in rows), max(r["max_abs_pixel_diff"] for r in rows)))


if __name__ == "__main__":
    main()
