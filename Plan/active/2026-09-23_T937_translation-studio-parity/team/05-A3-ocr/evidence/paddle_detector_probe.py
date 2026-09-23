"""Reproduce real PaddleDet + A2 free-text refinement on a synthetic crop.

Run after hydrating the Paddle detector ONNX payload::

    python paddle_detector_probe.py --model PATH_TO_DET_ONNX \
        --a2-tools PATH_TO_A2_WORKTREE/tools/translation_studio

The A2 worktree path is explicit because A3 and A2 are committed on separate
branches. The script writes the exact input crop, inpaint output, mask, and a
JSON record of detector boxes and A2 provenance beside itself (or --output-dir).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[5]
STUDIO_ROOT = REPO_ROOT / "tools" / "translation_studio"


def _font_path(requested: str | None) -> Path | None:
    candidates = [Path(requested)] if requested else []
    windows = os.environ.get("WINDIR")
    if windows:
        candidates.extend([Path(windows) / "Fonts" / "arialbd.ttf",
                           Path(windows) / "Fonts" / "arial.ttf"])
    candidates.extend([
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
        Path("/Library/Fonts/Arial Bold.ttf"),
    ])
    return next((candidate for candidate in candidates if candidate.is_file()), None)


def _make_fixture(font: str | None) -> tuple[Image.Image, list[int], str | None]:
    page = Image.new("RGB", (800, 360), "white")
    draw = ImageDraw.Draw(page)
    selected = _font_path(font)
    typeface = ImageFont.truetype(str(selected), 68) if selected else ImageFont.load_default()
    text = "FREE TEXT"
    bounds = draw.textbbox((0, 0), text, font=typeface, stroke_width=1)
    x = (page.width - (bounds[2] - bounds[0])) // 2
    y = 118 - bounds[1]
    draw.text((x, y), text, fill=(8, 8, 8), font=typeface,
              stroke_width=1, stroke_fill=(8, 8, 8))
    # The free-text region includes the text and margin; A2 adds its own 12px
    # detector context before calling PaddleDet.detect_lines.
    box = [max(0, x - 24), max(0, y + bounds[1] - 22),
           min(page.width, x + bounds[2] + 24),
           min(page.height, y + bounds[3] + 22)]
    return page, box, str(selected) if selected else None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=STUDIO_ROOT.parent.parent /
                        "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx")
    parser.add_argument("--a2-tools", type=Path, required=True,
                        help="A2 worktree tools/translation_studio directory")
    parser.add_argument("--output-dir", type=Path, default=HERE)
    parser.add_argument("--font", type=str, default=None)
    args = parser.parse_args()
    model = args.model.resolve(strict=True)
    a2_tools = args.a2_tools.resolve(strict=True)
    if not (a2_tools / "inpaint_android.py").is_file():
        raise SystemExit(f"not an A2 translation_studio directory: {a2_tools}")
    if model.stat().st_size < 100_000:
        raise SystemExit(f"detector payload is not hydrated: {model} ({model.stat().st_size} bytes)")

    sys.path.insert(0, str(STUDIO_ROOT))
    import paddle_ocr

    detector = paddle_ocr.PaddleDet(model)
    page, box, font = _make_fixture(args.font)
    region_crop = page.crop(tuple(box))
    direct_lines = detector.detect_lines(region_crop, thresh=0.18, box_thresh=0.34)
    region_crop.close()
    if not direct_lines:
        raise AssertionError("real PaddleDet.detect_lines found no lines in the synthetic free-text crop")

    # Load A2's inpaint entry point from its own worktree, while explicitly
    # retaining this A3 PaddleDet implementation for the real model invocation.
    sys.path.insert(0, str(a2_tools))
    import inpaint_android

    refine_calls = []
    real_detect_lines = detector.detect_lines

    def record_real_call(crop, thresh=0.2, box_thresh=0.45):
        lines = real_detect_lines(crop, thresh=thresh, box_thresh=box_thresh)
        refine_calls.append({
            "crop_size": list(crop.size),
            "thresh": thresh,
            "box_thresh": box_thresh,
            "lines": lines,
        })
        return lines

    detector.detect_lines = record_real_call
    cleaned, mask, records, stats = inpaint_android.inpaint_page_android(
        page, [{"id": "synthetic-free-text", "box": box,
                "label": 2, "text": "FREE TEXT", "score": 0.99}],
        raw_detections=[], seg_masks=[], bubble_leg="android-fill",
        free_leg="opencv", paddle_det=detector, aot=None,
    )
    if not refine_calls or not refine_calls[0]["lines"]:
        raise AssertionError("A2 did not call the real Paddle detector for free-text refinement")
    if len(records) != 1 or not any(
            item.get("source") == "paddle-refined"
            for item in records[0].get("source_boxes", [])):
        raise AssertionError(f"A2 did not record Paddle-refined source boxes: {records}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    fixture_path = args.output_dir / "paddle_free_text_fixture.png"
    clean_path = args.output_dir / "paddle_free_text_inpainted.png"
    mask_path = args.output_dir / "paddle_free_text_mask.png"
    evidence_path = args.output_dir / "paddle_free_text_probe.json"
    page.save(fixture_path)
    cleaned.save(clean_path)
    Image.fromarray(mask.astype("uint8") * 255).save(mask_path)

    report = {
        "fixture": {"text": "FREE TEXT", "page_size": list(page.size),
                    "label": 2, "region_box": box, "font_path": font},
        "detector": {"model_path": str(model), "model_size_bytes": model.stat().st_size,
                     "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
                     "engine": "A3 paddle_ocr.PaddleDet",
                     "direct_detect_lines": direct_lines},
        "a2_refinement": {"module_path": str(Path(inpaint_android.__file__).resolve()),
                          "entrypoint": "inpaint_page_android",
                          "paddle_detect_lines_calls": refine_calls,
                          "record": records[0], "stats": stats,
                          "mask_pixels": int(mask.sum())},
        "artifacts": {"fixture": fixture_path.name,
                      "inpainted": clean_path.name,
                      "mask": mask_path.name},
    }
    evidence_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                             encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
