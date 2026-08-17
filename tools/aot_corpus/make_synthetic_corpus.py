"""
Generate a small SYNTHETIC corpus to prove the Tier 3 gate harness works.

These are NOT real manga pages — they are programmatically generated test
images covering the inpaint-model input space (text-like strokes, uniform
regions, screentone-like noise, color backgrounds). They exist so the harness
can be validated end-to-end without waiting for human-curated real pages.

The REAL 20-page corpus described in the AOT design (dense text, screentone,
color, large bubbles, small pages, asymmetric, vertical JP) is a human
curation step — see tools/aot_corpus/README.md for what's needed.

Usage:
    python tools/aot_corpus/make_synthetic_corpus.py
Output:
    tools/aot_corpus/synthetic_corpus/<page_name>/{page.jpg,mask.png,manifest.json}
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

OUT_ROOT = Path(__file__).resolve().parent / "synthetic_corpus"


def _save_page(page_dir: Path, name: str, page_rgb: np.ndarray, mask_l: np.ndarray, category: str, box_count: int):
    page_dir.mkdir(parents=True, exist_ok=True)
    Image.fromarray(page_rgb.astype(np.uint8), "RGB").save(page_dir / "page.jpg", quality=92)
    Image.fromarray(mask_l.astype(np.uint8), "L").save(page_dir / "mask.png")
    (page_dir / "manifest.json").write_text(json.dumps({
        "page": name,
        "category": category,
        "expected_box_count": box_count,
        "synthetic": True,
        "note": "Programmatically generated; not a real manga page. "
                "Used to validate the harness, not to substitute for the real corpus.",
    }, indent=2))


def gen_uniform_white_with_text_box():
    """White page with a single text-box mask. Common case: model should reconstruct white."""
    page = np.full((512, 512, 3), 255, dtype=np.uint8)
    mask = np.zeros((512, 512), dtype=np.uint8)
    # Mask a 120x40 rectangle where "text" was.
    mask[236:276, 196:316] = 255
    _save_page(OUT_ROOT / "uniform_white_text_box", "uniform_white_text_box",
               page, mask, "dense-text", 1)


def gen_screentone_page():
    """Grayscale screentone-like page (random dots) + text mask."""
    rng = np.random.default_rng(42)
    # Screentone: thresholded noise.
    base = (rng.random((512, 512)) < 0.3).astype(np.uint8) * 180
    page = np.stack([base, base, base], axis=-1)
    # Add a few darker text-block-like strokes outside the mask for context.
    page[100:140, 100:300] = np.maximum(page[100:140, 100:300], 40)
    mask = np.zeros((512, 512), dtype=np.uint8)
    mask[100:140, 100:300] = 255  # erase the dark text
    _save_page(OUT_ROOT / "screentone_text", "screentone_text",
               page, mask, "screentone", 1)


def gen_color_background_page():
    """Color manga-like page: warm background + text mask."""
    # Background: warm gradient (reddish).
    x = np.linspace(0, 1, 512, dtype=np.float32)
    bg_r = (180 + 40 * x)[None, :] * np.ones((512, 1), np.float32)
    bg_g = (120 + 20 * x)[None, :] * np.ones((512, 1), np.float32)
    bg_b = (90 + 10 * x)[None, :] * np.ones((512, 1), np.float32)
    page = np.stack([bg_r, bg_g, bg_b], axis=-1).astype(np.uint8)
    # Add white speech-bubble-like region + text mask inside it.
    page[200:320, 150:370] = 245  # near-white bubble
    mask = np.zeros((512, 512), dtype=np.uint8)
    mask[240:280, 180:340] = 255  # text inside bubble
    _save_page(OUT_ROOT / "color_bubble_text", "color_bubble_text",
               page, mask, "color", 1)


def gen_large_mask_page():
    """Large inpaint region (big bubble) — stress the model's receptive field."""
    page = np.full((512, 512, 3), 250, dtype=np.uint8)
    mask = np.zeros((512, 512), dtype=np.uint8)
    mask[150:370, 150:370] = 255  # 220x220 mask — large
    _save_page(OUT_ROOT / "large_mask", "large_mask",
               page, mask, "large-bubble", 1)


def gen_dark_panel_page():
    """Dark panel (night scene) + light text mask — near-black background."""
    page = np.full((512, 512, 3), 18, dtype=np.uint8)
    # Some texture so it isn't perfectly uniform.
    rng = np.random.default_rng(7)
    page = np.clip(page.astype(np.int32) + rng.integers(-4, 5, page.shape), 0, 255).astype(np.uint8)
    mask = np.zeros((512, 512), dtype=np.uint8)
    mask[236:276, 196:316] = 255
    _save_page(OUT_ROOT / "dark_panel_text", "dark_panel_text",
               page, mask, "dark-panel", 1)


def main() -> int:
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    gen_uniform_white_with_text_box()
    gen_screentone_page()
    gen_color_background_page()
    gen_large_mask_page()
    gen_dark_panel_page()
    pages = sorted(p.name for p in OUT_ROOT.iterdir() if p.is_dir())
    print(f"Generated {len(pages)} synthetic pages under {OUT_ROOT}:")
    for name in pages:
        print(f"  {name}")
    print()
    print("NOTE: these are SYNTHETIC. The real 20-page corpus (see README.md) is")
    print("a human curation step and must be added before Wave 5.2 integration.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
