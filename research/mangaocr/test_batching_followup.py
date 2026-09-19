#!/usr/bin/env python3
"""Wave-2 batching follow-up: graph contract, full gate, and row parity."""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import onnx

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
LAB = ROOT / "tools" / "mangaocr_lab"
sys.path.insert(0, str(LAB))
sys.path.insert(0, str(HERE))

import test_batching as tb  # noqa: E402
from lab import batched, corpus, graphs, preprocessing  # noqa: E402
from lab.sessions import SessionBank  # noqa: E402


def _err(exc: BaseException) -> str:
    return f"{type(exc).__name__}: {str(exc).replace(os.linesep, ' ')[:600]}"


def _negative_shape_tests(sessions, x: np.ndarray) -> dict:
    hidden = sessions["encoder"].run(None, {"serving_default_args_0:0": x})[0]
    init = sessions["decoder_init"].run(None, {
        "encoder_hidden_states": hidden,
        "input_ids": np.full((x.shape[0], 1), 2, np.int64),
    })
    _, sk0, sv0, ck, cv = init
    sk = np.zeros((4, x.shape[0], 4, 256, 64), np.float32)
    sv = np.zeros_like(sk)
    sk[:, :, :, 0, :] = sk0[:, :, :, 0, :]
    sv[:, :, :, 0, :] = sv0[:, :, :, 0, :]
    base = {
        "encoder_hidden_states": hidden,
        "input_ids": np.zeros((x.shape[0], 1), np.int64),
        "position_ids": np.full((x.shape[0], 1), 2, np.int64),
        "self_k_cache": sk, "self_v_cache": sv,
        "cross_k_cache": ck, "cross_v_cache": cv,
    }
    out = {}
    bad = np.zeros((x.shape[0], x.shape[0]), np.int64)
    for stage, key in (("decoder_init", "input_ids"),
                       ("decoder_step", "input_ids"),
                       ("decoder_step", "position_ids")):
        feed = (dict(base) if stage == "decoder_step" else {
            "encoder_hidden_states": hidden,
            "input_ids": base["input_ids"],
        })
        feed[key] = bad
        try:
            sessions[stage].run(None, feed)
        except Exception as exc:
            out[f"{stage}.{key}"] = {"rejected": True, "error": _err(exc)}
        else:
            out[f"{stage}.{key}"] = {"rejected": False, "error": None}
    return {"batch": int(x.shape[0]), "cases": out,
            "all_rejected": all(v["rejected"] for v in out.values())}


def _row_parity(derived: dict, vocab: list[str]) -> dict:
    crops = [c for c in corpus.discover(LAB / "test_crops") if c.category != "empty"][:8]
    usable = []
    pixels = []
    from PIL import Image
    for crop in crops:
        with Image.open(crop.path) as image:
            px = preprocessing.preprocess(image)
        if px is not None:
            usable.append(crop)
            pixels.append(px)
    result = {}
    for batch_size in (2, 4, 8):
        bank = SessionBank(derived, "default")
        rows = []
        for start in range(0, len(usable), batch_size):
            window = usable[start:start + batch_size]
            got, _, _ = batched.run_microbatch(
                bank, window, pixels[start:start + batch_size], vocab)
            rows.extend(got)
        comparisons = []
        for (crop, row), px in zip(rows, pixels):
            solo_rows, _, _ = batched.run_microbatch(bank, [crop], [px], vocab)
            solo = solo_rows[0][1]
            comparisons.append({
                "crop_id": crop.crop_id,
                "tokens_equal": bool(row.tokens == solo.tokens),
                "batched_tokens": list(row.tokens), "solo_tokens": list(solo.tokens),
                "batched_outcome": row.outcome, "solo_outcome": solo.outcome,
                "batched_eos_position": row.eos_position,
                "solo_eos_position": solo.eos_position,
            })
        result[str(batch_size)] = {
            "batch_size": batch_size, "rows_tested": len(comparisons),
            "row_results": comparisons,
            "all_tokens_match_independent_B1": all(c["tokens_equal"] for c in comparisons),
            "all_outcomes_match": all(c["batched_outcome"] == c["solo_outcome"]
                                      for c in comparisons),
        }
    return result


def _studio_protocol_status() -> dict:
    files = sorted(p for p in ROOT.rglob("ocr.json") if ".studio" in p.parts)
    return {"status": "TESTED" if files else "UNTESTED",
            "files": [str(p) for p in files],
            "reason": None if files else (
                "No .studio/ocr.json fixture exists in this checkout; current-Kotlin "
                "versus corrected-protocol crop comparison was not run.")}


def main() -> int:
    started = time.perf_counter()
    pristine = tb.model_paths()
    derived_info = graphs.ensure_derived(pristine, LAB / "work")
    derived = derived_info["paths"]
    sessions = tb._load_sessions(derived)
    x = np.random.default_rng(1841).random((2, 3, 224, 224), dtype=np.float32)
    metadata = {name: {
        stage: tb.graph_metadata(paths[f"{stage}.onnx"])
        for stage in tb.STAGES
    } for name, paths in (("pristine", pristine), ("derived", derived))}
    shape_contract = {
        "decoder_init_input_ids": metadata["derived"]["decoder_init"]["inputs"]["input_ids"],
        "decoder_step_input_ids": metadata["derived"]["decoder_step"]["inputs"]["input_ids"],
        "decoder_step_position_ids": metadata["derived"]["decoder_step"]["inputs"]["position_ids"],
    }
    shape_contract["asserted"] = all(shape_contract[k] == ["N", "1"]
                                     for k in ("decoder_init_input_ids",
                                               "decoder_step_input_ids",
                                               "decoder_step_position_ids"))
    checker = {}
    for name, path in derived.items():
        try:
            onnx.checker.check_model(onnx.load(str(path)))
            checker[name] = {"valid": True, "error": None}
        except Exception as exc:
            checker[name] = {"valid": False, "error": _err(exc)}
    negative = _negative_shape_tests(sessions, x)
    vocab = pristine["vocab.txt"].read_text(encoding="utf-8").splitlines()
    rows = _row_parity(derived, vocab)
    payload = {
        "schema_version": 2,
        "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "status": "PASS" if (
            derived_info["gate"].get("pass") and shape_contract["asserted"]
            and all(v["valid"] for v in checker.values())
            and negative["all_rejected"]
            and all(v["all_tokens_match_independent_B1"] and v["all_outcomes_match"]
                    for v in rows.values())
        ) else "FAILED",
        "model_assets": {"pristine": {k: str(v) for k, v in pristine.items()},
                         "derived": {k: str(v) for k, v in derived.items()}},
        "derived_B1_full_gate": derived_info["gate"],
        "metadata": metadata,
        "shape_contract": shape_contract,
        "onnx_checker": checker,
        "negative_token_position_shape_tests": negative,
        "row_level_parity": rows,
        "kotlin_vs_corrected_protocol_on_studio_crops": _studio_protocol_status(),
        "notes": [
            "The full B=1 gate uses separate pristine and derived encoder outputs and decodes through safe position 127.",
            "Token and position feeds are required to remain [B,1]; cache batch axes are the only widened decoder axes.",
            "Desktop CPU parity is an implementation gate, not Android performance or OCR accuracy evidence.",
        ],
    }
    out = HERE / "results" / "batching_followup.json"
    out.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"output": str(out), "status": payload["status"],
                      "gate": derived_info["gate"], "shape_contract": shape_contract,
                      "negative": negative,
                      "row_summary": {k: (v["rows_tested"],
                          v["all_tokens_match_independent_B1"], v["all_outcomes_match"])
                          for k, v in rows.items()},
                      "studio": payload["kotlin_vs_corrected_protocol_on_studio_crops"]},
                     indent=2, ensure_ascii=False))
    return 0 if payload["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
