"""Corrected B=1 reference decoder — the lab's ground truth.

Implements the ONNX graph's own semantics (NOT the Android host protocol):
  * init logits provide token 1 (the Android loop discards them and re-predicts)
  * each step's KV slice is written at cache slot  position_ids - 1
  * position_ids 2..127 are fed; 128 is never fed (pos table has 128 rows)
  * EOS (3) terminates; running out of positions silently truncates
"""
from __future__ import annotations

import time

import numpy as np

from . import decode_common as dc


def _softmax_conf(row) -> float:
    return dc.softmax_max(row)


def decode_reference(bank, pixels: np.ndarray | None, vocab: list[str],
                     crop_id: str = "", category: str = "", path: str = "",
                     expected: str | None = None,
                     trace_sink: list | None = None, trace_top: int = 5,
                     capture_logits: list | None = None):
    """Returns a CropRecord. `bank` is a SessionBank (B=1 usage)."""
    from .records import CropRecord
    rec = CropRecord(crop_id=crop_id, category=category, path=path, decoder="reference",
                     expected=expected)
    t_start = time.perf_counter()

    if pixels is None:
        rec.skipped_empty = True
        rec.timings_ms["total"] = round((time.perf_counter() - t_start) * 1000.0, 3)
        return rec

    t0 = time.perf_counter()
    x = pixels[None]                                  # [1,3,224,224]
    hidden = bank.encoder.run({"serving_default_args_0:0": x})[0]
    t_enc = time.perf_counter()

    init_out = bank.decoder_init.run({
        "encoder_hidden_states": hidden,
        "input_ids": np.array([[dc.BOS]], dtype=np.int64),
    })
    logits0, self_k, self_v, cross_k, cross_v = init_out
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

    if trace_sink is not None:
        trace_sink.append({
            "sample": crop_id, "mode": "reference", "batch_size": 1, "step": 0,
            "position_id": 1, "kv_slot": 0, "input_token": dc.BOS,
            "input_text": dc.token_text(vocab, dc.BOS),
            "argmax_token": int(np.argmax(logits0[0])),
            "argmax_text": dc.token_text(vocab, int(np.argmax(logits0[0]))),
            "is_eos": int(np.argmax(logits0[0])) == dc.EOS,
            "top": dc.top_k(logits0[0], vocab, trace_top),
            "logits_sum": round(float(np.sum(logits0[0])), 4),
        })

    t = int(np.argmax(logits0[0]))
    if t == dc.EOS:
        eos_position = 1
    else:
        tokens.append(t)
        confs.append(_softmax_conf(logits0[0]))
        cur = t
        pos = 2
        while pos <= dc.MAX_FED_POSITION:
            step_out = bank.decoder_step.run({
                "encoder_hidden_states": hidden,
                "input_ids": np.array([[cur]], dtype=np.int64),
                "position_ids": np.array([[pos]], dtype=np.int64),
                "self_k_cache": sk, "self_v_cache": sv,
                "cross_k_cache": cross_k, "cross_v_cache": cross_v,
            })
            step_calls += 1
            logits, k_slice, v_slice = step_out
            sk[:, :, :, pos - 1, :] = k_slice[:, :, :, 0, :]   # GRAPH CONVENTION: slot = pos-1
            sv[:, :, :, pos - 1, :] = v_slice[:, :, :, 0, :]

            t = int(np.argmax(logits[0]))
            conf = _softmax_conf(logits[0])
            if capture_logits is not None:
                capture_logits.append(logits[0].copy())
            if trace_sink is not None:
                trace_sink.append({
                    "sample": crop_id, "mode": "reference", "batch_size": 1,
                    "step": step_calls, "position_id": pos, "kv_slot": pos - 1,
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
        else:
            position_limit = True

    t_end = time.perf_counter()
    _fill_common(rec, vocab, tokens, eos_position, position_limit, confs)
    rec.timings_ms = {
        "preprocess": 0.0,   # caller adds (batched) or pass pixels timing in
        "encoder": round((t_enc - t0) * 1000.0, 3),
        "decoder_init": round((t_init - t_enc) * 1000.0, 3),
        "decoder_step_total": round((t_end - t_init) * 1000.0, 3),
        "total": round((t_end - t_start) * 1000.0, 3),
    }
    rec.decoder_step_calls = step_calls
    return rec


def _fill_common(rec: CropRecord, vocab, tokens, eos_position, position_limit, confs):
    rec.token_ids = tokens
    rec.token_count = len(tokens)
    rec.eos_emitted = eos_position is not None
    rec.eos_position = eos_position
    rec.position_limit_reached = position_limit
    rec.text_raw = dc.raw_text(vocab, tokens)
    rec.text = dc.android_postprocess(rec.text_raw)
    if confs:
        rec.confidence_mean = round(sum(confs) / len(confs), 6)
        rec.confidence_min = round(min(confs), 6)
