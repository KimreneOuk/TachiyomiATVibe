"""
Finalize the real corpus for the Tier 3 gate:
  1. Assign each selected page to its category (manifest.json "category").
  2. Downscale the small_pages_lt512 entries below 512 on the long side so they
     exercise the upscaling crash case the fixed-512 model is meant to fix.
  3. Remove pages not in the selected 18 so the corpus = exactly the gate input.

Run after generate_masks.py has produced real_corpus/real_*/*:
  python tools/aot_corpus/curate_corpus.py

Selection is hand-tuned from per-crop pixel stats (mean/std/edge/dark%,
connected-component geometry). See tools/aot_corpus/CURATION_REPORT.md for the
table and the rationale per page.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS = REPO_ROOT / "tools/aot_corpus/real_corpus"

# Hand-tuned from crop pixel stats. 18 pages across 6 categories.
# Color category deferred (source chapter is B&W).
ASSIGNMENT: dict[str, list[str]] = {
    "dense_text": ["real_002", "real_019", "real_025", "real_017"],
    "screentone_heavy": ["real_003", "real_004", "real_030"],
    "large_bubble": ["real_008", "real_011", "real_028"],
    "vertical_jp_text": ["real_012", "real_020"],
    "tall_wide_asymmetric": ["real_006", "real_010", "real_013"],
    "small_pages_lt512": ["real_005", "real_014", "real_022"],
}

# Target long side for the downscaled small-page entries. Below 512 so the emit
# step must upscale to the model's 512x512 tensor — the crash case being fixed.
SMALL_TARGET_LONG_SIDE = 480


def main() -> int:
    if not CORPUS.is_dir():
        print(f"ERROR: corpus dir not found: {CORPUS}", file=sys.stderr)
        return 1

    selected: set[str] = set()
    page_to_category: dict[str, str] = {}
    for cat, pages in ASSIGNMENT.items():
        for p in pages:
            page_to_category[p] = cat
            selected.add(p)
    print(f"Selected {len(selected)} pages across {len(ASSIGNMENT)} categories.")

    # 1. Remove unselected pages.
    for d in sorted(CORPUS.iterdir()):
        if d.is_dir() and d.name.startswith("real_") and d.name not in selected:
            print(f"  rm {d.name} (not selected)")
            shutil.rmtree(d)

    # 2. Fill category in manifest + downscale small-page entries.
    for page, cat in page_to_category.items():
        d = CORPUS / page
        if not d.is_dir():
            print(f"  WARN: {page} missing, skip", file=sys.stderr)
            continue
        manifest_path = d / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["category"] = cat
        manifest["selected"] = True

        if cat == "small_pages_lt512":
            # Downscale page + mask below 512 on the long side. Store the
            # downscaled image as page.jpg / mask.png so emit upscales it to
            # the 512x512 model tensor - the genuine sub-512 crash case.
            page_img = Image.open(d / "page.jpg").convert("RGB")
            mask_img = Image.open(d / "mask.png").convert("L")
            w, h = page_img.size
            scale = SMALL_TARGET_LONG_SIDE / float(max(w, h))
            nw = max(8, round(w * scale))
            nh = max(8, round(h * scale))
            page_img.resize((nw, nh), Image.BILINEAR).save(d / "page.jpg", quality=92)
            mask_img.resize((nw, nh), Image.BILINEAR).save(d / "mask.png")
            manifest["downscaled_from"] = [w, h]
            manifest["downscaled_to"] = [nw, nh]
            manifest["small_target_long_side"] = SMALL_TARGET_LONG_SIDE
            print(f"  {page}: {cat} downscaled {w}x{h} -> {nw}x{nh}")
        else:
            print(f"  {page}: {cat}")

        manifest_path.write_text(json.dumps(manifest, indent=2))

    # 3. Summary.
    remaining = sorted(d.name for d in CORPUS.iterdir() if d.is_dir() and d.name.startswith("real_"))
    print(f"\nFinal corpus: {len(remaining)} pages")
    for cat, pages in ASSIGNMENT.items():
        have = [p for p in pages if p in remaining]
        print(f"  {cat}: {len(have)}/{len(pages)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
