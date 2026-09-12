"""Comparison (req 7), artifact writers (req 11), and terminal report (req 12)."""
from __future__ import annotations

import csv
import json

from .decode_common import token_text


# ---------------------------------------------------------------- comparison

def compare_to_reference(ref_by_id: dict, batched: list, batch_size: int,
                         vocab: list[str]):
    """Per-ROI token/text comparison vs the reference. Returns (mismatches, stats).

    Every non-match is materialized in full — sequences included, never just
    percentages (req 7)."""
    from .records import MismatchRecord

    mismatches: list[MismatchRecord] = []
    compared = token_matches = text_matches = 0
    for rec in batched:
        ref = ref_by_id.get(rec.crop_id)
        if ref is None or ref.skipped_empty or rec.skipped_empty:
            continue
        compared += 1
        t_ok = ref.token_ids == rec.token_ids
        s_ok = ref.text == rec.text
        token_matches += t_ok
        text_matches += s_ok
        if t_ok:
            continue
        rt, bt = ref.token_ids, rec.token_ids
        idx = next((i for i in range(min(len(rt), len(bt))) if rt[i] != bt[i]),
                   min(len(rt), len(bt)))          # one is a prefix of the other
        mismatches.append(MismatchRecord(
            crop_id=rec.crop_id, category=rec.category, batch_size=batch_size,
            text_match=s_ok, token_match=False,
            first_mismatch={
                "index": idx,
                "reference": rt[idx] if idx < len(rt) else None,
                "batched": bt[idx] if idx < len(bt) else None,
                "reference_text": token_text(vocab, rt[idx]) if idx < len(rt) else "<END>",
                "batched_text": token_text(vocab, bt[idx]) if idx < len(bt) else "<END>",
            },
            reference_tokens=rt, batched_tokens=bt,
            reference_text=ref.text, batched_text=rec.text,
        ))
    stats = {"roi_count": compared, "token_matches": token_matches,
             "text_matches": text_matches,
             "mismatch_ids": [m.crop_id for m in mismatches]}
    return mismatches, stats


# ---------------------------------------------------------------- artifacts

def write_run_json(path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False),
                    encoding="utf-8")


def write_mismatches(path, mismatches: list) -> None:
    path.write_text(json.dumps([m.to_dict() for m in mismatches], indent=2,
                               ensure_ascii=False), encoding="utf-8")


CSV_COLUMNS = ["crop_id", "category", "batch_size", "decoder", "text",
               "token_count", "eos_emitted", "eos_position",
               "position_limit_reached", "confidence_mean", "confidence_min",
               "enc_ms", "init_ms", "step_ms", "step_calls",
               "text_match", "token_match", "first_mismatch_idx"]


def write_per_crop_csv(path, records: list, ref_by_id: dict | None) -> None:
    """Long format: one row per (crop, decoder mode). Comparison columns are
    filled only for batched rows vs the reference; reference/legacy rows blank."""
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(CSV_COLUMNS)
        for rec in records:
            t_ok = s_ok = ""
            mm_idx = ""
            if ref_by_id is not None and rec.decoder == "batched" and not rec.skipped_empty:
                ref = ref_by_id.get(rec.crop_id)
                if ref is not None and not ref.skipped_empty:
                    t_ok = ref.token_ids == rec.token_ids
                    s_ok = ref.text == rec.text
                    if not t_ok:
                        rt, bt = ref.token_ids, rec.token_ids
                        mm_idx = next(
                            (i for i in range(min(len(rt), len(bt))) if rt[i] != bt[i]),
                            min(len(rt), len(bt)))
            w.writerow([
                rec.crop_id, rec.category, rec.batch_size, rec.decoder,
                rec.text, rec.token_count, rec.eos_emitted, rec.eos_position,
                rec.position_limit_reached, rec.confidence_mean,
                rec.confidence_min,
                rec.timings_ms.get("encoder", ""),
                rec.timings_ms.get("decoder_init", ""),
                rec.timings_ms.get("decoder_step_total", ""),
                rec.decoder_step_calls,
                s_ok, t_ok, mm_idx,
            ])


# ---------------------------------------------------------------- terminal

def _short(h: str) -> str:
    return h[:8] + "…"


def print_header(pristine: dict, derived: dict, gate: dict, vocab_size: int) -> None:
    print(f"MODEL  encoder {_short(pristine['encoder.onnx'])}  "
          f"init {_short(pristine['decoder_init.onnx'])}  "
          f"step {_short(pristine['decoder_step.onnx'])}  "
          f"vocab {vocab_size} tok")
    print(f"DERIVED patches {_short(derived['patched_sha256']['encoder.onnx'])}  "
          f"gate={'B1-EXACT' if gate.get('pass') else 'FAILED'} "
          f"(enc {gate.get('encoder_max_abs_diff'):.1e} / "
          f"dec {gate.get('decoder_max_abs_diff'):.1e})")
    print("NOTE   desktop timings ≠ Android performance "
          "(dispatch counts do transfer)")


def print_context(roi_count: int, corpus_root: str, profile: str) -> None:
    print(f"\nROIs: {roi_count}  corpus: {corpus_root}  session-profile: {profile}")


def _token_stats(records: list) -> dict:
    counts = [r.token_count for r in records if not r.skipped_empty]
    return {
        "avg": round(sum(counts) / len(counts), 1) if counts else 0.0,
        "max": max(counts) if counts else 0,
        "eos": sum(1 for r in records if r.eos_emitted),
        "pos_limit": sum(1 for r in records if r.position_limit_reached),
    }


def print_reference_block(records: list, counters: dict, times_ms: dict) -> None:
    ts = _token_stats(records)
    total = round(times_ms["encoder"] + times_ms["decoder_init"]
                  + times_ms["decoder_step"], 1)
    print("\nB=1 reference")
    print(f"  runs: enc={counters['encoder']} init={counters['decoder_init']} "
          f"step={counters['decoder_step']}")
    print(f"  time: enc {times_ms['encoder']}ms init {times_ms['decoder_init']}ms "
          f"step {times_ms['decoder_step']}ms total {total}ms")
    print(f"  tokens: avg {ts['avg']} max {ts['max']}   "
          f"eos {ts['eos']}  pos-limit {ts['pos_limit']}")


def print_legacy_block(records: list, counters: dict, times_ms: dict) -> None:
    ts = _token_stats(records)
    print("\nB=1 android-legacy (defect emulation — NOT a correctness baseline)")
    print(f"  runs: enc={counters['encoder']} init={counters['decoder_init']} "
          f"step={counters['decoder_step']}")
    print(f"  time: step {times_ms['decoder_step']}ms  "
          f"tokens: avg {ts['avg']} max {ts['max']}  "
          f"eos {ts['eos']}  pos-limit {ts['pos_limit']}")


def _delta(after: dict, before: dict, key: str) -> int:
    return after[key] - before[key]


def print_batch_block(batch_size: int, counters_before: dict, counters_after: dict,
                      times_before: dict, times_after: dict, ref_counters: dict,
                      ref_times_ms: dict, stats: dict, mismatches: list,
                      verbose: bool) -> None:
    enc = _delta(counters_after, counters_before, "encoder")
    init = _delta(counters_after, counters_before, "decoder_init")
    step = _delta(counters_after, counters_before, "decoder_step")
    print(f"\nB={batch_size}")
    print(f"  runs: enc={enc} init={init} step={step} "
          f"({_pct(step, ref_counters['decoder_step'])} step dispatches)")
    total = round(sum(times_after.values()) - sum(times_before.values()), 1)
    ref_total = (ref_times_ms["encoder"] + ref_times_ms["decoder_init"]
                 + ref_times_ms["decoder_step"])
    print(f"  time: total {total}ms ({_pct(round(total, 3), round(ref_total, 3))})")
    print(f"  matches reference: {stats['token_matches']}/{stats['roi_count']}")
    for m in mismatches:
        fm = m.first_mismatch
        print(f"  MISMATCH {m.crop_id}: first diff @{fm['index']} "
              f"ref={fm['reference']}('{fm['reference_text']}') "
              f"got={fm['batched']}('{fm['batched_text']}')")
        if verbose:
            print(f"    ref tokens ({len(m.reference_tokens)}): {m.reference_tokens}")
            print(f"    got tokens ({len(m.batched_tokens)}): {m.batched_tokens}")
            print(f"    ref text: {m.reference_text!r}")
            print(f"    got text: {m.batched_text!r}")


def _pct(value: float, ref: float) -> str:
    if not ref:
        return "n/a"
    return f"{(value / ref - 1.0) * 100:+.0f}%"


def print_rss(peak_by_b: dict) -> None:
    parts = [f"{mb} (B={b})" for b, mb in peak_by_b.items()]
    print(f"\npeak RSS: {' / '.join(parts)}")
