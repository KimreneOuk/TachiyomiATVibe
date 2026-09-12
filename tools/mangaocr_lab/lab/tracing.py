"""Per-step JSONL trace dumps (req 8).

`trace --crop P --batch-size B` writes:
  <out>/<stem>.reference.jsonl   — corrected B=1 reference steps
  <out>/<stem>.b<B>.jsonl        — the crop decoded inside a B-row microbatch

Filler rows come from the crop's own directory (sorted siblings, deterministic);
the target sits at row B//2 so row-indexing bugs surface. If fewer siblings
exist the microbatch runs at its true size — records stamp the real batch_size.
`logits_sum` is a per-step float checksum: the first diverging step between two
traces of the same crop is the first line whose logits_sum differs.
"""
from __future__ import annotations

import json
from pathlib import Path

from . import reference
from .batched import run_microbatch
from .corpus import Crop
from .preprocessing import preprocess


def _crop(path: Path) -> Crop:
    return Crop(crop_id=path.stem, path=path,
                category=path.parent.name or "trace", expected=None)


def _load(path: Path):
    from PIL import Image
    with Image.open(path) as im:
        return preprocess(im)


def trace_crop(bank, crop_path: Path, vocab: list[str], batch_size: int,
               out_dir: Path, trace_top: int = 5) -> dict:
    crop_path = Path(crop_path)
    if not crop_path.exists():
        raise SystemExit(f"--crop not found: {crop_path}")
    target = _crop(crop_path)
    px = _load(crop_path)
    if px is None:
        raise SystemExit(f"crop is empty (zero-size): {crop_path}")

    out_dir.mkdir(parents=True, exist_ok=True)
    summary = {"crop": target.crop_id}

    # reference trace
    sink_ref: list = []
    reference.decode_reference(bank, px, vocab, crop_id=target.crop_id,
                               trace_sink=sink_ref, trace_top=trace_top)
    p_ref = out_dir / f"{target.crop_id}.reference.jsonl"
    _write_jsonl(p_ref, sink_ref)
    summary["reference_steps"] = len(sink_ref)
    summary["reference_path"] = str(p_ref)

    # batched trace: fillers from the same directory, target mid-batch
    siblings = [p for p in sorted(crop_path.parent.iterdir())
                if p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
                and p != crop_path][:batch_size - 1]
    chunk_crops = [target] + [_crop(s) for s in siblings]
    row = batch_size // 2
    chunk_crops[0], chunk_crops[row] = chunk_crops[row], chunk_crops[0]
    pixels = [_load(c.path) for c in chunk_crops]
    keep = [i for i, p in enumerate(pixels) if p is not None]
    chunk_crops = [chunk_crops[i] for i in keep]
    pixels = [pixels[i] for i in keep]

    sink_b: list = []
    _, _, steps = run_microbatch(bank, chunk_crops, pixels, vocab,
                                 trace_sink=sink_b, trace_top=trace_top)
    p_bat = out_dir / f"{target.crop_id}.b{len(chunk_crops)}.jsonl"
    _write_jsonl(p_bat, sink_b)
    summary["batched_steps"] = len(sink_b)
    summary["batch_size_actual"] = len(chunk_crops)
    summary["target_row"] = chunk_crops.index(target) if target in chunk_crops else None
    summary["batched_path"] = str(p_bat)
    summary["decoder_step_dispatches"] = steps

    # first divergence between the two traces of the same crop (target row only)
    target_row = summary["target_row"]
    summary["first_logits_sum_divergence"] = _first_divergence(
        sink_ref, sink_b, target_row)
    return summary


def _first_divergence(ref: list, bat: list, target_row: int | None):
    """First step where the TARGET row's logits_sum differs from the reference
    trace. Other rows are different crops — their divergence is expected."""
    if target_row is None:
        return None
    steps_ref = list(ref)
    if not steps_ref:
        return None
    first = None
    for r in bat:
        if r.get("row") != target_row:
            continue
        step = r["step"]
        if step >= len(steps_ref):
            break
        if abs(r["logits_sum"] - steps_ref[step]["logits_sum"]) > 1e-2:
            first = {"row": target_row, "step": step,
                     "reference": steps_ref[step]["logits_sum"],
                     "batched": r["logits_sum"]}
            break
    return first


def _write_jsonl(path: Path, records: list) -> None:
    with open(path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
