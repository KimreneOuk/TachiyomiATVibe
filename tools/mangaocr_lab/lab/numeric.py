"""Stage-level numerical checking (req 9): batched row vs independent B=1.

For each selected crop we run the SAME microbatch code path twice —
solo (a 1-row microbatch) and in-situ (the row's real place inside a B-sized
microbatch carved from the frozen corpus order, exactly as the full pass
chunks it) — capturing encoder output, decoder_init logits, and every
decoder_step logits row, then diff them. Tolerance is reported, never
enforced; the numbers are the deliverable.
"""
from __future__ import annotations

import numpy as np
from PIL import Image

from .batched import run_microbatch
from .corpus import Crop
from .preprocessing import preprocess
from .records import NumericCheck


def pick_crops(crops: list[Crop], count: int = 4,
               explicit_ids: list[str] | None = None) -> list[Crop]:
    if explicit_ids:
        wanted = set(explicit_ids)
        picked = [c for c in crops if c.crop_id in wanted]
        missing = wanted - {c.crop_id for c in picked}
        if missing:
            raise SystemExit(f"--numeric-check-crops not in corpus: {sorted(missing)}")
        return picked
    if len(crops) <= count:
        return list(crops)
    idx = [round(i * (len(crops) - 1) / (count - 1)) for i in range(count)]
    return [crops[i] for i in dict.fromkeys(idx)]


def _pixels(crop: Crop) -> np.ndarray | None:
    with Image.open(crop.path) as im:
        return preprocess(im)


def check_crop(bank, runnable: list[tuple[Crop, np.ndarray]], vocab: list[str],
               target: Crop, batch_size: int) -> NumericCheck:
    """`runnable` is the non-empty corpus in frozen order, pixels preloaded —
    the same list the full batched pass chunks, so the in-situ microbatch is
    the crop's real batch neighborhood."""
    idx = next(i for i, (c, _) in enumerate(runnable) if c.crop_id == target.crop_id)

    solo_cap: dict = {target.crop_id: {"encoder": [], "init": [], "steps": []}}
    run_microbatch(bank, [target], [runnable[idx][1]], vocab, capture=solo_cap)

    start = (idx // batch_size) * batch_size
    chunk = runnable[start:start + batch_size]
    batch_cap: dict = {target.crop_id: {"encoder": [], "init": [], "steps": []}}
    run_microbatch(bank, [c for c, _ in chunk], [p for _, p in chunk],
                   vocab, capture=batch_cap)

    solo = solo_cap[target.crop_id]
    bat = batch_cap[target.crop_id]

    def diff(a, b) -> dict:
        d = np.abs(np.asarray(a, np.float32) - np.asarray(b, np.float32))
        return {"max_abs": float(d.max()), "mean_abs": float(d.mean())}

    enc = diff(solo["encoder"][0], bat["encoder"][-1])
    init = diff(solo["init"][0], bat["init"][-1])
    steps_solo, steps_bat = solo["steps"], bat["steps"]
    compared = min(len(steps_solo), len(steps_bat))
    step = (diff(np.stack(steps_solo[:compared]), np.stack(steps_bat[:compared]))
            if compared else {"max_abs": None, "mean_abs": None})
    note = ""
    if len(steps_solo) != len(steps_bat):
        note = (f"step-count differs: solo={len(steps_solo)} "
                f"in-batch={len(steps_bat)}; compared min={compared}")
    return NumericCheck(crop_id=target.crop_id, batch_size=batch_size,
                        encoder=enc, init_logits=init, step_logits=step,
                        note=note)


def run_checks(bank, crops: list[Crop], vocab: list[str], batch_size: int,
               count: int = 4, explicit_ids: list[str] | None = None) -> list[NumericCheck]:
    runnable = []
    for c in crops:
        px = _pixels(c)
        if px is not None:
            runnable.append((c, px))
    picked = pick_crops([c for c, _ in runnable], count, explicit_ids)
    print(f"  numeric checks: {[c.crop_id for c in picked]} at B={batch_size}")
    return [check_crop(bank, runnable, vocab, c, batch_size) for c in picked]
