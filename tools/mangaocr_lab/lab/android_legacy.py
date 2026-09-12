"""Emulation of the CURRENT Android host protocol — defects included.

This mirrors MangaOcrEngine.decodeHiddenState (kt:160-314) faithfully:
  * init logits are DISCARDED; token 1 comes from a redundant step at position 1
  * the returned KV slice is written at cache slot `pos` (the proven off-by-one;
    the graph convention is slot pos-1)
  * outer loop bound MAX_GENERATION_LENGTH = 300, inner break at pos >= 128

Purpose: reproduce device-observed behavior and quantify the misalignment.
NEVER use as a correctness baseline — reports label it `legacy-defective`.
"""
from __future__ import annotations

import time

import numpy as np

from . import decode_common as dc
from .records import CropRecord
from .reference import _fill_common

MAX_GENERATION_LENGTH = 300


def decode_android_legacy(bank, pixels: np.ndarray | None, vocab: list[str],
                          crop_id: str = "", category: str = "", path: str = "",
                          trace_sink: list | None = None, trace_top: int = 5):
    rec = CropRecord(crop_id=crop_id, category=category, path=path,
                     decoder="android-legacy")
    t_start = time.perf_counter()

    if pixels is None:
        rec.skipped_empty = True
        rec.timings_ms["total"] = round((time.perf_counter() - t_start) * 1000.0, 3)
        return rec

    t0 = time.perf_counter()
    x = pixels[None]
    hidden = bank.encoder.run({"serving_default_args_0:0": x})[0]
    t_enc = time.perf_counter()

    init_out = bank.decoder_init.run({
        "encoder_hidden_states": hidden,
        "input_ids": np.array([[dc.BOS]], dtype=np.int64),
    })
    _logits0, self_k, self_v, cross_k, cross_v = init_out   # logits0 discarded, as in production
    t_init = time.perf_counter()

    sk = np.zeros((dc.NUM_LAYERS, 1, dc.NUM_HEADS, dc.CACHE_WINDOW, dc.HEAD_DIM), np.float32)
    sv = np.zeros_like(sk)
    sk[:, :, :, 0, :] = self_k[:, :, :, 0, :]
    sv[:, :, :, 0, :] = self_v[:, :, :, 0, :]

    tokens: list[int] = []
    confs: list[float] = []
    eos_position: int | None = None
    position_limit = False
    step_calls = 0

    pos = 1
    cur = dc.BOS
    for _step_idx in range(MAX_GENERATION_LENGTH):
        if pos >= dc.POSITION_LIMIT:
            break
        step_out = bank.decoder_step.run({
            "encoder_hidden_states": hidden,
            "input_ids": np.array([[cur]], dtype=np.int64),
            "position_ids": np.array([[pos]], dtype=np.int64),
            "self_k_cache": sk, "self_v_cache": sv,
            "cross_k_cache": cross_k, "cross_v_cache": cross_v,
        })
        step_calls += 1
        logits, k_slice, v_slice = step_out
        # PRODUCTION DEFECT: slice written at slot `pos` (graph expects pos-1)
        sk[:, :, :, pos, :] = k_slice[:, :, :, 0, :]
        sv[:, :, :, pos, :] = v_slice[:, :, :, 0, :]

        t = int(np.argmax(logits[0]))
        conf = _softmax_conf_local(logits[0])
        if trace_sink is not None:
            trace_sink.append({
                "sample": crop_id, "mode": "android-legacy", "batch_size": 1,
                "step": step_calls, "position_id": pos, "kv_slot": pos,  # defect visible here
                "input_token": cur, "input_text": dc.token_text(vocab, cur),
                "argmax_token": t, "argmax_text": dc.token_text(vocab, t),
                "is_eos": t == dc.EOS,
                "top": dc.top_k(logits[0], vocab, trace_top),
                "logits_sum": round(float(np.sum(logits[0])), 4),
            })
        if t == dc.EOS:
            eos_position = pos
            break
        tokens.append(t)
        confs.append(conf)
        cur = t
        pos += 1

    t_end = time.perf_counter()
    _fill_common(rec, vocab, tokens, eos_position, position_limit, confs)
    rec.timings_ms = {
        "preprocess": 0.0,
        "encoder": round((t_enc - t0) * 1000.0, 3),
        "decoder_init": round((t_init - t_enc) * 1000.0, 3),
        "decoder_step_total": round((t_end - t_init) * 1000.0, 3),
        "total": round((t_end - t_start) * 1000.0, 3),
    }
    rec.decoder_step_calls = step_calls
    return rec


def _softmax_conf(row) -> float:
    return dc.softmax_max(row)


_softmax_conf_local = _softmax_conf
