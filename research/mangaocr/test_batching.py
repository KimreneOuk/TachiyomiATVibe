#!/usr/bin/env python3
"""Probe MangaOCR ONNX batch support and decoder scheduling semantics.

This is research-only: it never edits Android sources or model assets. The
pristine graphs are probed as shipped (batch=1), while the already-reviewed
dynamic derivation in ``tools/mangaocr_lab/lab/graphs.py`` is probed as the
candidate batch implementation. Results are JSON for later Android/NPU work.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
LAB = ROOT / "tools" / "mangaocr_lab"
sys.path.insert(0, str(LAB))
sys.path.insert(0, str(HERE))

from compare_outputs import array_summary, diff_summary  # noqa: E402
from lab import assets, batched, corpus, graphs, preprocessing  # noqa: E402
from lab.sessions import SessionBank  # noqa: E402

BATCHES = (1, 2, 4, 8)
STAGES = ("encoder", "decoder_init", "decoder_step")
VOCAB = 9415


def model_paths() -> dict[str, Path]:
    """Prefer the local research checkout, falling back to app assets."""
    checkout = HERE / "models" / "manga-ocr-mobile"
    names = ("encoder.onnx", "decoder_init.onnx", "decoder_step.onnx", "vocab.txt")
    if all((checkout / name).is_file() for name in names):
        return {name: checkout / name for name in names}
    return assets.model_paths()


def _shape(value_info) -> list[str]:
    dims = []
    for dim in value_info.type.tensor_type.shape.dim:
        if dim.HasField("dim_param") and dim.dim_param:
            dims.append(dim.dim_param)
        elif dim.HasField("dim_value"):
            dims.append(str(dim.dim_value))
        else:
            dims.append("?")
    return dims


def graph_metadata(path: Path) -> dict[str, Any]:
    model = onnx.load(str(path))
    return {
        "path": str(path),
        "sha256": assets.sha256(path),
        "nodes": len(model.graph.node),
        "value_info": len(model.graph.value_info),
        "inputs": {item.name: _shape(item) for item in model.graph.input},
        "outputs": {item.name: _shape(item) for item in model.graph.output},
    }


def _options() -> ort.SessionOptions:
    options = ort.SessionOptions()
    options.log_severity_level = 3
    return options


def _load_sessions(paths: dict[str, Path]) -> dict[str, ort.InferenceSession]:
    return {
        name.removesuffix(".onnx"): ort.InferenceSession(
            str(path), _options(), providers=["CPUExecutionProvider"]
        )
        for name, path in paths.items() if name.endswith(".onnx")
    }


def _error(exc: BaseException) -> str:
    return f"{type(exc).__name__}: {str(exc).replace(os.linesep, ' ')[:600]}"


def _run(session, feeds: dict[str, np.ndarray]) -> tuple[list[np.ndarray] | None, str | None]:
    try:
        return [np.asarray(v) for v in session.run(None, feeds)], None
    except Exception as exc:  # noqa: BLE001 - probe must record failed support
        return None, _error(exc)


def _canonical_inputs(derived_sessions: dict[str, ort.InferenceSession], x: np.ndarray) -> dict[str, Any]:
    """Build valid B-sized decoder feeds from the candidate graph."""
    hidden_out, error = _run(
        derived_sessions["encoder"], {"serving_default_args_0:0": x}
    )
    if error:
        raise RuntimeError(f"candidate encoder failed while building feeds: {error}")
    hidden = hidden_out[0]
    init_out, error = _run(
        derived_sessions["decoder_init"],
        {"encoder_hidden_states": hidden,
         "input_ids": np.full((x.shape[0], 1), 2, dtype=np.int64)},
    )
    if error:
        raise RuntimeError(f"candidate decoder_init failed while building feeds: {error}")
    _, self_k, self_v, cross_k, cross_v = init_out
    self_k_cache = np.zeros((4, x.shape[0], 4, 256, 64), np.float32)
    self_v_cache = np.zeros_like(self_k_cache)
    self_k_cache[:, :, :, 0, :] = self_k[:, :, :, 0, :]
    self_v_cache[:, :, :, 0, :] = self_v[:, :, :, 0, :]
    return {
        "hidden": hidden,
        "encoder": {"serving_default_args_0:0": x},
        "decoder_init": {"encoder_hidden_states": hidden,
                          "input_ids": np.full((x.shape[0], 1), 2, dtype=np.int64)},
        "decoder_step": {"encoder_hidden_states": hidden,
                          "input_ids": np.arange(x.shape[0], dtype=np.int64).reshape(-1, 1) % VOCAB,
                          "position_ids": np.full((x.shape[0], 1), 2, dtype=np.int64),
                          "self_k_cache": self_k_cache,
                          "self_v_cache": self_v_cache,
                          "cross_k_cache": cross_k,
                          "cross_v_cache": cross_v},
    }


def probe_source(sessions: dict[str, ort.InferenceSession], canonical: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for stage in STAGES:
        output, error = _run(sessions[stage], canonical[stage])
        if error:
            result[stage] = {"supported": False, "error": error}
        else:
            result[stage] = {
                "supported": True,
                "outputs": [array_summary(v) for v in output],
            }
    return result


def _stage_outputs(sessions, x: np.ndarray) -> dict[str, list[np.ndarray]]:
    """Run the three stages for one batch; used by B=1 equivalence checks."""
    hidden = sessions["encoder"].run(None, {"serving_default_args_0:0": x})[0]
    init = sessions["decoder_init"].run(None, {
        "encoder_hidden_states": hidden,
        "input_ids": np.full((x.shape[0], 1), 2, np.int64),
    })
    _, self_k, self_v, cross_k, cross_v = init
    sk = np.zeros((4, x.shape[0], 4, 256, 64), np.float32)
    sv = np.zeros_like(sk)
    sk[:, :, :, 0, :] = self_k[:, :, :, 0, :]
    sv[:, :, :, 0, :] = self_v[:, :, :, 0, :]
    step = sessions["decoder_step"].run(None, {
        "encoder_hidden_states": hidden,
        "input_ids": np.zeros((x.shape[0], 1), dtype=np.int64),
        "position_ids": np.full((x.shape[0], 1), 2, np.int64),
        "self_k_cache": sk, "self_v_cache": sv,
        "cross_k_cache": cross_k, "cross_v_cache": cross_v,
    })
    return {"encoder": [hidden], "decoder_init": [np.asarray(v) for v in init],
            "decoder_step": [np.asarray(v) for v in step]}


def numerical_equivalence(sessions: dict[str, ort.InferenceSession], x8: np.ndarray) -> dict[str, Any]:
    solo = [_stage_outputs(sessions, x8[i:i + 1]) for i in range(x8.shape[0])]
    out: dict[str, Any] = {}
    for batch in (2, 4, 8):
        bat = _stage_outputs(sessions, x8[:batch])
        stages: dict[str, Any] = {}
        for stage in STAGES:
            row_diffs = []
            for row in range(batch):
                for output_index, candidate in enumerate(bat[stage]):
                    reference_value = solo[row][stage][output_index]
                    if stage == "encoder" or output_index == 0:
                        reference = reference_value[0]
                        candidate_value = candidate[row]
                    else:
                        # Decoder KV tensors are [layers, batch, heads, seq, dim].
                        reference = reference_value[:, 0, ...]
                        candidate_value = candidate[:, row, ...]
                    row_diffs.append(diff_summary(reference, candidate_value))
            stages[stage] = {
                "row_output_diffs": row_diffs,
                "max_abs": max((d["max_abs"] for d in row_diffs if d["max_abs"] is not None), default=0.0),
                "mean_abs_max": max((d["mean_abs"] for d in row_diffs if d["mean_abs"] is not None), default=0.0),
            }
        out[str(batch)] = stages
    return out


def decoder_schedule_check(derived_paths: dict[str, Path], vocab: list[str]) -> dict[str, Any]:
    """Compare B=8 lockstep decoding against eight independent B=1 runs."""
    crops = [c for c in corpus.discover(LAB / "test_crops") if c.category != "empty"][:8]
    pixels = []
    usable = []
    for crop in crops:
        from PIL import Image
        with Image.open(crop.path) as image:
            px = preprocessing.preprocess(image)
        if px is not None:
            usable.append(crop)
            pixels.append(px)
    bank = SessionBank(derived_paths, "default")
    batched_rows, timings, step_count = batched.run_microbatch(bank, usable, pixels, vocab)
    singles = []
    for crop, px in zip(usable, pixels):
        rows, _, steps = batched.run_microbatch(bank, [crop], [px], vocab)
        singles.append((rows[0][1], steps))
    comparisons = []
    for (crop, row), (solo, solo_steps) in zip(batched_rows, singles):
        comparisons.append({
            "crop_id": crop.crop_id,
            "tokens_equal": bool(row.tokens == solo.tokens),
            "batched_tokens": list(row.tokens),
            "solo_tokens": list(solo.tokens),
            "batched_outcome": row.outcome,
            "solo_outcome": solo.outcome,
            "batched_eos_position": row.eos_position,
            "solo_eos_position": solo.eos_position,
            "batched_step_calls": step_count,
            "solo_step_calls": solo_steps,
        })
    eos_positions = [c["batched_eos_position"] for c in comparisons if c["batched_eos_position"] is not None]
    distinct_eos = sorted(set(eos_positions))
    return {
        "batch_size": len(usable),
        "chunk_timings_ms": timings,
        "shared_step_calls": step_count,
        "row_results": comparisons,
        "all_tokens_match_independent_B1": all(c["tokens_equal"] for c in comparisons),
        "distinct_eos_positions": distinct_eos,
        "independent_eos_observed": len(distinct_eos) > 1,
        "inactive_rows_fed_as_eos_until_shared_stop": len(distinct_eos) > 1,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path,
                        default=HERE / "results" / "batching-probe.json")
    args = parser.parse_args()

    pristine = model_paths()
    derived_info = graphs.ensure_derived(pristine, LAB / "work")
    derived = derived_info["paths"]
    rng = np.random.default_rng(1841)
    x8 = rng.random((8, 3, 224, 224), dtype=np.float32)
    pristine_sessions = _load_sessions(pristine)
    derived_sessions = _load_sessions(derived)

    metadata = {"pristine": {}, "derived": {}}
    for name, paths in (("pristine", pristine), ("derived", derived)):
        for stage in STAGES:
            metadata[name][stage] = graph_metadata(paths[f"{stage}.onnx"])

    probes: dict[str, Any] = {"pristine": {}, "derived": {}}
    for batch in BATCHES:
        x = x8[:batch]
        canonical = _canonical_inputs(derived_sessions, x)
        probes["pristine"][str(batch)] = probe_source(pristine_sessions, canonical)
        probes["derived"][str(batch)] = probe_source(derived_sessions, canonical)

    started = time.perf_counter()
    equivalence = numerical_equivalence(derived_sessions, x8)
    vocab = pristine["vocab.txt"].read_text(encoding="utf-8").splitlines()
    schedule = decoder_schedule_check(derived, vocab)

    cache_scaling = {}
    for batch in BATCHES:
        cache_scaling[str(batch)] = {
            "self_k_or_v_cache": array_summary(np.zeros((4, batch, 4, 256, 64), np.float32)),
            "cross_k_or_v_cache": array_summary(np.zeros((4, batch, 4, 196, 64), np.float32)),
            "self_k_and_v_bytes": int(2 * 4 * batch * 4 * 256 * 64 * 4),
            "cross_k_and_v_bytes": int(2 * 4 * batch * 4 * 196 * 64 * 4),
        }

    payload = {
        "schema_version": 1,
        "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "model_assets": {"pristine": {k: str(v) for k, v in pristine.items()},
                         "derived": {k: str(v) for k, v in derived.items()}},
        "derived_B1_gate": derived_info["gate"],
        "batch_sizes": list(BATCHES),
        "metadata": metadata,
        "batch_probes": probes,
        "derived_B1_vs_independent_B1": equivalence,
        "decoder_schedule": schedule,
        "kv_cache_scaling": cache_scaling,
        "notes": [
            "Pristine assets are the exact app bytes; derived graphs are generated by the reviewed lab patch.",
            "Desktop CPU execution establishes shape/correctness only; it is not an Android timing claim.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "pristine_B2": probes["pristine"]["2"],
        "derived_B8": probes["derived"]["8"],
        "schedule": schedule,
    }, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
