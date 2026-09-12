"""Deterministic derivation of batch-capable graphs from the pristine assets.

The exact patch proven in T927 evidence (bit-exact batch-30, no re-export):

encoder (tf2onnx, no value_info):
  - graph input batch dim -> symbolic 'N'
  - every Reshape shape constant with leading 1 -> -1
  - both ReduceMean axes [0,2] -> [2]   (batch-axis pooling = batch-1 export artifact)

decoder_init / decoder_step (pytorch export):
  - delete all static value_info (they pin batch=1 and corrupt ORT's Gemm fusion)
  - graph I/O dims 0 and 1 -> symbolic where fixed 1
  - Reshape shape constants: leading 1 -> -1; KV stacks [4,1,4,S,64] -> [4,-1,4,S,64]

Mandatory gate: patched B=1 must match original B=1 (token equality +
logit maxdiff <= 5e-5 encoder / 1e-4 decoders) on deterministic inputs.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper

from . import decode_common as dc


def _symbolize(vi, dims_to_patch):
    tt = vi.type.tensor_type
    if not tt.HasField("shape"):
        return
    dims = tt.shape.dim
    for bd in dims_to_patch:
        if bd < len(dims) and dims[bd].HasField("dim_value") and dims[bd].dim_value == 1:
            dims[bd].dim_param = "N"


def _patch_decoder(path: Path, out_path: Path) -> list:
    m = onnx.load(str(path))
    g = m.graph
    del g.value_info[:]
    for vi in list(g.input) + list(g.output):
        _symbolize(vi, (0, 1))
    inits = {i.name: i for i in g.initializer}
    patched = []
    for n in g.node:
        if n.op_type == "Reshape" and len(n.input) > 1 and n.input[1] in inits:
            arr = numpy_helper.to_array(inits[n.input[1]])
            if arr.dtype != np.int64 or arr.size == 0:
                continue
            flat = arr.flatten().copy()
            changed = False
            if flat[0] == 1:
                flat[0] = -1
                changed = True
            elif arr.size == 5 and arr[0] == 4 and arr[2] == 4 and arr[1] == 1:
                flat[1] = -1
                changed = True
            if changed:
                inits[n.input[1]].CopyFrom(
                    numpy_helper.from_array(flat.reshape(arr.shape), n.input[1]))
                patched.append(n.input[1])
    onnx.save(m, str(out_path))
    return patched


def _patch_encoder(path: Path, out_path: Path) -> list:
    m = onnx.load(str(path))
    g = m.graph
    for vi in list(g.input) + list(g.output):
        _symbolize(vi, (0,))
    inits = {i.name: i for i in g.initializer}
    patched = []
    axes_fixed = 0
    for n in g.node:
        if n.op_type == "Reshape" and len(n.input) > 1 and n.input[1] in inits:
            arr = numpy_helper.to_array(inits[n.input[1]])
            if arr.dtype == np.int64 and arr.size > 0 and arr.flatten()[0] == 1:
                new = arr.flatten().copy()
                new[0] = -1
                inits[n.input[1]].CopyFrom(
                    numpy_helper.from_array(new.reshape(arr.shape), n.input[1]))
                patched.append(n.input[1])
        if n.op_type == "ReduceMean":
            for a in n.attribute:
                if a.name == "axes" and list(a.ints) == [0, 2]:
                    del a.ints[:]
                    a.ints.extend([2])
                    axes_fixed += 1
    if axes_fixed == 0:
        raise RuntimeError("encoder: expected 2 ReduceMean(axes=[0,2]) nodes, found none "
                           "— upstream model changed; re-derive the patch recipe")
    onnx.save(m, str(out_path))
    return patched


def _gate(pristine: dict, derived: dict) -> dict:
    """B=1 equivalence gate on deterministic synthetic inputs."""
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.log_severity_level = 3
    prov = ["CPUExecutionProvider"]
    rng = np.random.default_rng(0)

    # encoder
    se_o = ort.InferenceSession(str(pristine["encoder.onnx"]), so, providers=prov)
    se_p = ort.InferenceSession(str(derived["encoder.onnx"]), so, providers=prov)
    x = rng.random((1, 3, 224, 224), dtype=np.float32)
    enc_diff = float(np.abs(
        np.asarray(se_o.run(None, {"serving_default_args_0:0": x})[0])
        - np.asarray(se_p.run(None, {"serving_default_args_0:0": x})[0])).max())

    # decoder mini-decode (6 steps, uniform lockstep positions)
    def mini_decode(bank_paths):
        s_i = ort.InferenceSession(str(bank_paths["decoder_init.onnx"]), so, providers=prov)
        s_s = ort.InferenceSession(str(bank_paths["decoder_step.onnx"]), so, providers=prov)
        hidden = np.asarray(se_p.run(None, {"serving_default_args_0:0": x})[0])
        o0 = s_i.run(None, {"encoder_hidden_states": hidden,
                            "input_ids": np.array([[dc.BOS]], np.int64)})
        logits0, sk0, sv0, ck, cv = [np.asarray(v) for v in o0]
        sk = np.zeros((dc.NUM_LAYERS, 1, dc.NUM_HEADS, dc.CACHE_WINDOW, dc.HEAD_DIM), np.float32)
        sv = np.zeros_like(sk)
        sk[:, :, :, 0, :] = sk0[:, :, :, 0, :]
        sv[:, :, :, 0, :] = sv0[:, :, :, 0, :]
        toks = [int(np.argmax(logits0[0]))]
        logits_seq = [logits0[0].copy()]
        cur, pos = toks[0], 2
        for _ in range(6):
            if cur == dc.EOS or pos > dc.MAX_FED_POSITION:
                break
            o1 = s_s.run(None, {"encoder_hidden_states": hidden,
                                "input_ids": np.array([[cur]], np.int64),
                                "position_ids": np.array([[pos]], np.int64),
                                "self_k_cache": sk, "self_v_cache": sv,
                                "cross_k_cache": ck, "cross_v_cache": cv})
            lg, ks, vs = [np.asarray(v) for v in o1]
            sk[:, :, :, pos - 1, :] = ks[:, :, :, 0, :]
            sv[:, :, :, pos - 1, :] = vs[:, :, :, 0, :]
            cur = int(np.argmax(lg[0]))
            toks.append(cur)
            logits_seq.append(lg[0].copy())
            pos += 1
        return toks, logits_seq

    toks_o, seq_o = mini_decode(pristine)
    toks_p, seq_p = mini_decode(derived)
    steps = min(len(seq_o), len(seq_p))
    dec_diff = max(float(np.abs(np.asarray(a) - np.asarray(b)).max())
                   for a, b in zip(seq_o[:steps], seq_p[:steps])) if steps else 0.0
    return {
        "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "encoder_max_abs_diff": enc_diff,
        "decoder_max_abs_diff": dec_diff,
        "decoder_tokens_original": toks_o,
        "decoder_tokens_patched": toks_p,
        "tokens_equal": toks_o == toks_p,
        "pass": toks_o == toks_p and enc_diff <= 5e-5 and dec_diff <= 1e-4,
        "tolerances": {"encoder": 5e-5, "decoder": 1e-4},
    }


def ensure_derived(pristine_paths: dict, work_dir: Path, skip_gate: bool = False) -> dict:
    """Returns {paths, gate, patched_sha256}. Cached by pristine-content key."""
    from .assets import sha256

    key = "-".join(sha256(pristine_paths[n])[:8] for n in
                   ("encoder.onnx", "decoder_init.onnx", "decoder_step.onnx"))
    out_dir = work_dir / "derived" / key
    out_dir.mkdir(parents=True, exist_ok=True)
    derived = {
        "encoder.onnx": out_dir / "encoder.b0c0.onnx",
        "decoder_init.onnx": out_dir / "decoder_init.b0c0.onnx",
        "decoder_step.onnx": out_dir / "decoder_step.b0c0.onnx",
    }
    gate_path = out_dir / "gate.json"

    if gate_path.exists() and all(p.exists() for p in derived.values()):
        gate = json.loads(gate_path.read_text(encoding="utf-8"))
    else:
        t0 = time.perf_counter()
        pe = _patch_encoder(pristine_paths["encoder.onnx"], derived["encoder.onnx"])
        pi = _patch_decoder(pristine_paths["decoder_init.onnx"], derived["decoder_init.onnx"])
        ps = _patch_decoder(pristine_paths["decoder_step.onnx"], derived["decoder_step.onnx"])
        print(f"  derived patched graphs in {time.perf_counter() - t0:.1f}s "
              f"(enc {len(pe)} consts + axes, init {len(pi)}, step {len(ps)})")
        gate = _gate(pristine_paths, derived)
        gate_path.write_text(json.dumps(gate, indent=2), encoding="utf-8")

    if not gate.get("pass") and not skip_gate:
        raise SystemExit(f"patched-graph B=1 gate FAILED: {gate} "
                         "(pass --skip-gate to override; run stamped)")
    return {
        "paths": derived,
        "gate": gate,
        "patched_sha256": {n: sha256(p) for n, p in derived.items()},
    }
