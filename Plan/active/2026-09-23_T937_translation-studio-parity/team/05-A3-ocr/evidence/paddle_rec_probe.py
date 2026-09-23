"""Run real PaddleRec ONNX recognition on fixed JP/CN/EN crops.

The A3 worktree stores these model files as LFS pointers. Hydrate them locally
before running, then restore the pointers before committing this evidence.

    python paddle_rec_probe.py --model PATH_TO_REC_ONNX \
        --dictionary PATH_TO_REC_DICTIONARY
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
PADDLE_ROOT = REPO_ROOT / "app" / "src" / "main" / "assets" / "models" / "ocr" / "paddle-v6-small"
FIXTURES = {
    "ja": ("日本語テスト", "YuGothB.ttc"),
    "zh": ("中文测试", "msyh.ttc"),
    "en": ("FREE TEXT", "arialbd.ttf"),
}


def _make_fixture(text: str, font_name: str) -> tuple[Image.Image, str]:
    font_path = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Fonts" / font_name
    if not font_path.is_file():
        raise FileNotFoundError(f"fixture font is not installed: {font_path}")
    image = Image.new("RGB", (640, 160), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(font_path), 64)
    bounds = draw.textbbox((0, 0), text, font=font, stroke_width=1)
    width = bounds[2] - bounds[0]
    height = bounds[3] - bounds[1]
    x = (image.width - width) // 2 - bounds[0]
    y = (image.height - height) // 2 - bounds[1]
    draw.text((x, y), text, fill=(5, 5, 5), font=font,
              stroke_width=1, stroke_fill=(5, 5, 5))
    return image, str(font_path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=PADDLE_ROOT / "inference.onnx")
    parser.add_argument("--dictionary", type=Path,
                        default=PADDLE_ROOT / "PP-OCRv6_small_rec.txt")
    parser.add_argument("--output-dir", type=Path, default=HERE)
    args = parser.parse_args()
    model = args.model.resolve(strict=True)
    dictionary = args.dictionary.resolve(strict=True)
    if model.stat().st_size < 100_000:
        raise SystemExit(f"recognition payload is not hydrated: {model} ({model.stat().st_size} bytes)")

    sys.path.insert(0, str(STUDIO_ROOT))
    import paddle_ocr

    crops, entries = [], []
    for language, (text, font_name) in FIXTURES.items():
        crop, font_path = _make_fixture(text, font_name)
        crops.append(crop)
        entries.append({"language": language, "text": text,
                        "font_path": font_path})
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for crop, entry in zip(crops, entries):
        fixture_path = args.output_dir / f"paddle_rec_{entry['language']}.png"
        crop.save(fixture_path)
        entry["fixture"] = fixture_path.name
        entry["fixture_sha256"] = hashlib.sha256(fixture_path.read_bytes()).hexdigest()

    try:
        rec = paddle_ocr.PaddleRec(model=model, dictionary=dictionary)
        results = rec.recognize_batch(crops, max_batch=4)
    finally:
        for crop in crops:
            crop.close()

    output_rows = []
    for entry, result in zip(entries, results):
        row = dict(entry)
        if isinstance(result, Exception):
            row["error"] = str(result)
        else:
            row["recognized_text"], row["confidence"] = result
        output_rows.append(row)
    report = {
        "model_path": str(model),
        "model_bytes": model.stat().st_size,
        "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
        "dictionary_path": str(dictionary),
        "dictionary_bytes": dictionary.stat().st_size,
        "provider_names": rec.sess.get_providers(),
        "requested_max_batch": 4,
        "results": output_rows,
        "batch_trace": rec.last_batch_trace,
        "useful_output_count": sum(bool(row.get("recognized_text")) for row in output_rows),
    }
    report_path = args.output_dir / "paddle_rec_probe.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                           encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
