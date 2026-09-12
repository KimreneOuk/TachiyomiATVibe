"""Microbatched decode driver (S3) — chunks the frozen corpus order into
ceil(N/B) microbatches at TRUE size (patched graphs are fully dynamic; no
padding rows, no masks) and reassembles per-crop results in input order.
"""
from __future__ import annotations

import time

import numpy as np
from PIL import Image

from . import decode_common as dc
from . import state as st
from .corpus import Crop
from .preprocessing import preprocess
from .records import CropRecord


class MicrobatchRun:
    """Holds per-microbatch context the trace helpers need."""

    def __init__(self, chunk: list[Crop], vocab: list[str],
                 trace_sink: list | None, trace_top: int):
        self.chunk = chunk
        self.vocab = vocab
        self.trace_sink = trace_sink
        self.trace_top = trace_top
        self.state = st.MicrobatchState([c.crop_id for c in chunk])

    def _trace_step0(self, logits0: np.ndarray) -> None:
        for b, row in enumerate(self.state.rows):
            t = int(np.argmax(logits0[b]))
            self.trace_sink.append({
                "sample": row.crop_id, "mode": "batch",
                "batch_size": len(self.chunk), "row": b,
                "step": 0, "position_id": 1, "kv_slot": 0,
                "input_token": dc.BOS,
                "input_text": dc.token_text(self.vocab, dc.BOS),
                "argmax_token": t, "argmax_text": dc.token_text(self.vocab, t),
                "is_eos": t == dc.EOS,
                "top": dc.top_k(logits0[b], self.vocab, self.trace_top),
                "logits_sum": round(float(np.sum(logits0[b])), 4),
            })

    def _trace_step(self, step_no: int, logits: np.ndarray) -> None:
        p = self.state.position
        for b, row in enumerate(self.state.rows):
            t = int(np.argmax(logits[b]))
            self.trace_sink.append({
                "sample": row.crop_id, "mode": "batch",
                "batch_size": len(self.chunk), "row": b,
                "step": step_no, "position_id": p, "kv_slot": p - 1,
                "input_token": row.next_input,
                "input_text": dc.token_text(self.vocab, row.next_input),
                "argmax_token": t, "argmax_text": dc.token_text(self.vocab, t),
                "is_eos": t == dc.EOS,
                "top": dc.top_k(logits[b], self.vocab, self.trace_top),
                "logits_sum": round(float(np.sum(logits[b])), 4),
            })


def run_microbatch(bank, chunk: list[Crop], pixels: list[np.ndarray],
                   vocab: list[str], trace_sink: list | None = None,
                   trace_top: int = 5, capture: dict | None = None):
    """One encoder+init+step sequence over a single true-size microbatch.

    Returns (rows, chunk_ms, step_count) where rows is a list of
    (Crop, RowState) in input order and chunk_ms holds shared stage wall
    times. `capture` (optional) maps crop_id -> {"encoder": [], "init": [],
    "steps": []} and receives row-extracted copies for numeric checks.
    """
    b = len(chunk)
    run = MicrobatchRun(chunk, vocab, trace_sink, trace_top)

    t0 = time.perf_counter()
    x = np.stack(pixels)                                    # [B,3,224,224]
    hidden = bank.encoder.run({"serving_default_args_0:0": x})[0]
    t_enc = time.perf_counter()

    init_out = bank.decoder_init.run({
        "encoder_hidden_states": hidden,
        "input_ids": np.full((b, 1), dc.BOS, dtype=np.int64),
    })
    logits0, self_k, self_v, cross_k, cross_v = init_out
    t_init = time.perf_counter()

    run.state.apply_init(logits0, self_k, self_v)

    if trace_sink is not None:
        run._trace_step0(logits0)
    if capture is not None:
        for i, crop in enumerate(chunk):
            cap = capture.get(crop.crop_id)
            if cap is not None:
                cap["encoder"].append(hidden[i].copy())
                cap["init"].append(logits0[i].copy())

    step_count = 0
    while run.state.can_step:
        run.state.prepare_feed()
        logits, k_slice, v_slice = bank.decoder_step.run({
            "encoder_hidden_states": hidden,
            "input_ids": run.state.input_ids,
            "position_ids": run.state.position_ids,
            "self_k_cache": run.state.sk, "self_v_cache": run.state.sv,
            "cross_k_cache": cross_k, "cross_v_cache": cross_v,
        })
        step_count += 1
        if trace_sink is not None:
            run._trace_step(step_count, logits)
        if capture is not None:
            for i, crop in enumerate(chunk):
                cap = capture.get(crop.crop_id)
                if cap is not None:
                    cap["steps"].append(logits[i].copy())
        run.state.apply_step(logits, k_slice, v_slice)

    for row in run.state.rows:
        if not row.finished:
            row.finish_position_limit()       # shared ceiling hit with rows active

    t_end = time.perf_counter()
    chunk_ms = {
        "encoder": round((t_enc - t0) * 1000.0, 3),
        "decoder_init": round((t_init - t_enc) * 1000.0, 3),
        "decoder_step_total": round((t_end - t_init) * 1000.0, 3),
        "total": round((t_end - t0) * 1000.0, 3),
    }
    return list(zip(chunk, run.state.rows)), chunk_ms, step_count


def run_batched_pass(bank, crops: list[Crop], vocab: list[str], batch_size: int,
                     trace_sink: list | None = None, trace_top: int = 5) -> list[CropRecord]:
    """Full corpus pass: preprocess, skip empties, chunk, decode, reassemble."""
    records: dict[str, CropRecord] = {}
    runnable: list[tuple[Crop, np.ndarray]] = []

    for crop in crops:
        t_pre = time.perf_counter()
        with Image.open(crop.path) as im:
            px = preprocess(im)
        pre_ms = round((time.perf_counter() - t_pre) * 1000.0, 3)
        rec = CropRecord(crop_id=crop.crop_id, category=crop.category,
                         path=str(crop.path), decoder="batched",
                         batch_size=batch_size, expected=crop.expected)
        rec.timings_ms["preprocess"] = pre_ms
        records[crop.crop_id] = rec
        if px is None:
            rec.skipped_empty = True
            continue
        runnable.append((crop, px))

    for start in range(0, len(runnable), batch_size):
        window = runnable[start:start + batch_size]
        rows, chunk_ms, step_count = run_microbatch(
            bank, [c for c, _ in window], [p for _, p in window],
            vocab, trace_sink=trace_sink, trace_top=trace_top)
        for (crop, row) in rows:
            rec = records[crop.crop_id]
            rec.token_ids = list(row.tokens)
            rec.token_count = len(row.tokens)
            rec.eos_emitted = row.outcome == st.OUTCOME_EOS
            rec.eos_position = row.eos_position
            rec.position_limit_reached = row.outcome == st.OUTCOME_POSITION_LIMIT
            if row.confs:
                rec.confidence_mean = round(sum(row.confs) / len(row.confs), 6)
                rec.confidence_min = round(min(row.confs), 6)
            rec.text_raw = dc.raw_text(vocab, row.tokens)
            rec.text = dc.android_postprocess(rec.text_raw)
            # Batched stage timings are CHUNK aggregates shared by all rows in
            # the microbatch — not attributable per crop (stamped in run.json).
            rec.timings_ms.update(chunk_ms)
            rec.decoder_step_calls = step_count   # shared: strategy-A truth

    return [records[c.crop_id] for c in crops]
