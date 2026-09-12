#!/usr/bin/env python3
"""MangaOCR batching desktop laboratory — CLI (T927).

No GUI, no daemon; every invocation is a fresh, self-describing run.
Desktop timings must NOT be read as Android performance predictions;
dispatch counts (session.run calls) DO transfer.

Examples:
  python manga_ocr_lab.py verify-models
  python manga_ocr_lab.py make-fixtures
  python manga_ocr_lab.py run --input test_crops/ --batch-sizes 1,2,4,8 --compare-reference
  python manga_ocr_lab.py run --input test_crops/digits --batch-sizes 8 --numeric-check -v
  python manga_ocr_lab.py trace --crop test_crops/digits/sample_001.png --batch-size 4
  python manga_ocr_lab.py export-failure --image page.png --box 10,20,300,80 --app-output "..." --id 2026-09-12-box17
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

LAB_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB_ROOT))

from lab import assets, corpus, graphs, hostinfo  # noqa: E402
from lab import reference, android_legacy, batched, numeric, report, tracing  # noqa: E402
from lab.sessions import SessionBank  # noqa: E402

WORK_DIR = LAB_ROOT / "work"
RESULTS_DIR = LAB_ROOT / "results"
DEFAULT_CORPUS = LAB_ROOT / "test_crops"


def _ts() -> str:
    return time.strftime("%Y%m%d-%H%M%S", time.gmtime())


# --------------------------------------------------------------- subcommands

def cmd_verify_models(args) -> int:
    print("MODEL ASSETS (app/src/main/assets/models/ocr)")
    assets.verify(args.allow_unlocked_models)
    lock = assets.load_lock()
    print(f"models.lock.json: pinned at {lock.get('pinned_utc', 'unknown')}")
    print("OK — all four assets match the lock" )
    return 0


def cmd_make_fixtures(args) -> int:
    from lab import fixtures
    target = Path(args.target) if args.target else DEFAULT_CORPUS
    print(f"FIXTURES -> {target}")
    counts = fixtures.generate(target)
    for cat, n in counts.items():
        print(f"  {cat:18s} {n}")
    print("done — `real/` stays empty for user-supplied device crops")
    return 0


def cmd_run(args) -> int:
    batch_sizes = _parse_batch_sizes(args.batch_sizes)
    print("MODEL ASSETS")
    pristine = assets.verify(args.allow_unlocked_models)

    print("DERIVED GRAPHS (cached patch + B=1 gate)")
    derived = graphs.ensure_derived(assets.model_paths(), WORK_DIR,
                                    skip_gate=args.skip_gate)
    gate = derived["gate"]
    vocab = assets.load_vocab()
    report.print_header(pristine, derived, gate, len(vocab))

    crops = corpus.discover(Path(args.input), args.category, args.limit)
    if not crops:
        raise SystemExit(f"no crops under {args.input}"
                         + (f" (category={args.category})" if args.category else "")
                         + " — run `make-fixtures` first")
    report.print_context(len(crops), args.input, args.session_profile)
    print(f"  corpus: {json.dumps(corpus.category_counts(crops))}")

    bank_pristine = SessionBank(assets.model_paths(), args.session_profile)
    bank_derived = SessionBank(derived["paths"], args.session_profile)

    # --- reference pass (pristine graphs; the ground truth) ------------------
    cb = bank_pristine.counters()
    ref_records = [reference.decode_reference(
        bank_pristine, _pixels(c.path), vocab, crop_id=c.crop_id,
        category=c.category, path=str(c.path), expected=c.expected) for c in crops]
    ref_by_id = {r.crop_id: r for r in ref_records}
    ref_counters = _subtract(bank_pristine.counters(), cb)
    ref_times = bank_pristine.times_ms()
    report.print_reference_block(ref_records, ref_counters, ref_times)
    if args.verbose:
        _per_crop_lines(ref_records, vocab)

    # --- optional legacy (defect emulation) ---------------------------------
    legacy_payload = None
    if args.decoder == "android-legacy":
        cb = bank_pristine.counters()
        legacy_records = [android_legacy.decode_android_legacy(
            bank_pristine, _pixels(c.path), vocab, crop_id=c.crop_id,
            category=c.category, path=str(c.path)) for c in crops]
        for r, c in zip(legacy_records, crops):
            r.expected = c.expected
        legacy_counters = _subtract(bank_pristine.counters(), cb)
        report.print_legacy_block(legacy_records, legacy_counters,
                                  bank_pristine.times_ms())
        legacy_payload = {"records": [r.to_dict() for r in legacy_records],
                          "session_runs": legacy_counters}
        if args.verbose:
            _per_crop_lines(legacy_records, vocab)

    # --- batched passes (derived graphs) ------------------------------------
    out_dir = Path(args.output) if args.output else RESULTS_DIR / f"{_ts()}-b{'_'.join(map(str, batch_sizes))}"
    out_dir.mkdir(parents=True, exist_ok=True)

    batch_payloads = []
    batched_records_by_b = []
    numeric_checks = []
    peak_by_b = {}
    mismatches_all = []
    for b in batch_sizes:
        cb = bank_derived.counters()
        tb = bank_derived.times_ms()
        with hostinfo.RssPeak() as rss:
            records = batched.run_batched_pass(bank_derived, crops, vocab, b)
        ca = bank_derived.counters()
        ta = bank_derived.times_ms()
        peak_by_b[b] = rss.peak_mb
        mismatches, stats = report.compare_to_reference(ref_by_id, records, b, vocab)
        mismatches_all.extend(mismatches)
        report.print_batch_block(b, cb, ca, tb, ta, ref_counters,
                                 ref_times, stats, mismatches, args.verbose)
        batched_records_by_b.append((b, records))
        batch_payloads.append({
            "batch_size": b,
            "roi_count": stats["roi_count"],
            "microbatch_count": math.ceil(
                sum(1 for r in records if not r.skipped_empty) / b),
            "session_runs": _subtract(ca, cb),
            "wall_time_ms": _subtract_f(ta, tb),
            "peak_process_rss_mb": rss.peak_mb,
            "vs_reference": stats,
        })
        if args.numeric_check:
            numeric_checks.extend(numeric.run_checks(
                bank_derived, crops, vocab, b, count=4,
                explicit_ids=args.numeric_check_crops))

    if args.numeric_check:
        print("\nNUMERIC CHECKS (batched row vs independent B=1)")
        for nc in numeric_checks:
            print(f"  crop={nc.crop_id} B={nc.batch_size}")
            print(f"    encoder     max|Δ|={nc.encoder['max_abs']:.3e} "
                  f"mean|Δ|={nc.encoder['mean_abs']:.3e}")
            print(f"    init logits max|Δ|={nc.init_logits['max_abs']:.3e} "
                  f"mean|Δ|={nc.init_logits['mean_abs']:.3e}")
            sl = nc.step_logits
            if sl["max_abs"] is not None:
                print(f"    step logits max|Δ|={sl['max_abs']:.3e} "
                      f"mean|Δ|={sl['mean_abs']:.3e}" + (f"  ({nc.note})" if nc.note else ""))
            else:
                print(f"    step logits: no comparable steps  ({nc.note})")

    report.print_rss(peak_by_b)

    # --- artifacts -----------------------------------------------------------
    expected = sum(1 for r in ref_records if r.expected is not None
                   and (r.text == r.expected or r.text == _post(r.expected)))
    run_payload = {
        "schema_version": 1,
        "timestamp_utc": _ts(),
        "desktop_caveat": True,
        "cli": {k: str(v) for k, v in vars(args).items()},
        "host": hostinfo.host_block(),
        "models": {
            "pristine_sha256": pristine,
            "locked": not args.allow_unlocked_models,
            "patched_sha256": dict(derived["patched_sha256"]),
            "gate": gate,
        },
        "corpus": {"root": args.input, "counts": corpus.category_counts(crops),
                   "roi_count": len(crops)},
        "reference": {"records": [r.to_dict() for r in ref_records],
                      "session_runs": ref_counters,
                      "wall_time_ms": ref_times,
                      "expected_matches": expected,
                      "expected_total": sum(1 for r in ref_records
                                            if r.expected is not None)},
        "batch_runs": batch_payloads,
        "numeric_checks": [nc.to_dict() for nc in numeric_checks],
        "peak_process_rss_mb_by_batch": peak_by_b,
    }
    if legacy_payload:
        run_payload["android_legacy"] = legacy_payload
    report.write_run_json(out_dir / "run.json", run_payload)
    report.write_per_crop_csv(out_dir / "per_crop.csv", ref_records, None)
    _append_batched_csv(out_dir / "per_crop.csv", batched_records_by_b,
                        ref_by_id, vocab)
    report.write_mismatches(out_dir / "mismatches.json", mismatches_all)
    print(f"\nartifacts: {out_dir}")
    return 0


def cmd_trace(args) -> int:
    print("MODEL ASSETS")
    pristine = assets.verify(args.allow_unlocked_models)
    derived = graphs.ensure_derived(assets.model_paths(), WORK_DIR,
                                    skip_gate=args.skip_gate)
    vocab = assets.load_vocab()
    report.print_header(pristine, derived, derived["gate"], len(vocab))
    bank = SessionBank(derived["paths"], args.session_profile)
    out_dir = Path(args.output) if args.output else RESULTS_DIR / f"{_ts()}-trace"
    summary = tracing.trace_crop(bank, Path(args.crop), vocab, args.batch_size,
                                 out_dir, trace_top=args.trace_top)
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    div = summary["first_logits_sum_divergence"]
    if div is None:
        print("TRACE OK — batched row logits_sum matches reference at every step")
    else:
        print(f"TRACE DIVERGENCE at row {div['row']} step {div['step']}: "
              f"ref {div['reference']} vs batched {div['batched']}")
    return 0


def cmd_export_failure(args) -> int:
    from PIL import Image
    x, y, w, h = (int(v) for v in args.box.split(","))
    out_dir = Path(args.target or DEFAULT_CORPUS) / "previous_failures" / args.id
    out_dir.mkdir(parents=True, exist_ok=True)
    with Image.open(args.image) as im:
        im.crop((x, y, x + w, y + h)).save(out_dir / "crop.png")
    meta = {
        "id": args.id,
        "source_image": str(Path(args.image).resolve()),
        "box": {"x": x, "y": y, "w": w, "h": h},
        "app_output": args.app_output,
        "expected": args.expected,
        "exported_utc": _ts(),
        "pristine_sha256": {n: assets.sha256(p) for n, p in assets.model_paths().items()},
    }
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False),
                                       encoding="utf-8")
    (out_dir / "crop.expected.txt").write_text((args.expected or "") + "\n",
                                               encoding="utf-8")
    print(f"exported failure {args.id} -> {out_dir}")
    return 0


# ------------------------------------------------------------------- helpers

def _pixels(path):
    from PIL import Image
    from lab.preprocessing import preprocess
    with Image.open(path) as im:
        return preprocess(im)


def _post(text: str) -> str:
    from lab.decode_common import android_postprocess
    return android_postprocess(text)


def _parse_batch_sizes(spec: str) -> list[int]:
    try:
        sizes = sorted({int(v) for v in spec.split(",") if v.strip()})
    except ValueError:
        raise SystemExit(f"--batch-sizes must be ints: {spec!r}")
    if not sizes or any(s < 1 for s in sizes):
        raise SystemExit(f"--batch-sizes must be >= 1: {spec!r}")
    return sizes


def _subtract(after: dict, before: dict) -> dict:
    return {k: after[k] - before[k] for k in after}


def _subtract_f(after: dict, before: dict) -> dict:
    return {k: round(after[k] - before.get(k, 0.0), 2) for k in after}


def _per_crop_lines(records: list, vocab: list[str]) -> None:
    from lab.decode_common import token_text
    for r in records:
        if r.skipped_empty:
            print(f"    {r.crop_id}: EMPTY (skipped)")
            continue
        tail = (f" eos@{r.eos_position}" if r.eos_emitted
                else " POS-LIMIT" if r.position_limit_reached else "")
        mark = ""
        if r.expected is not None:
            mark = "  ✓expected" if (r.text == r.expected
                                      or r.text == _post(r.expected)) else "  ✗EXPECTED"
        print(f"    {r.crop_id}: {r.text!r} [tok={r.token_count}{tail}]{mark}")


def _append_batched_csv(csv_path: Path, batched_records_by_b: list,
                        ref_by_id: dict, vocab: list[str]) -> None:
    """Appends one row per (crop, batched-run) with comparison columns vs the
    reference — long format, comparison never aggregated away."""
    import csv as _csv
    with open(csv_path, "a", newline="", encoding="utf-8") as f:
        w = _csv.writer(f)
        for b_size, recs in batched_records_by_b:
            mismatches, _ = report.compare_to_reference(ref_by_id, recs, b_size, vocab)
            mm_by_id = {m.crop_id: m for m in mismatches}
            for rec in recs:
                ref = ref_by_id.get(rec.crop_id)
                t_ok = s_ok = ""
                mm_idx = ""
                if ref is not None and not rec.skipped_empty and not ref.skipped_empty:
                    t_ok = ref.token_ids == rec.token_ids
                    s_ok = ref.text == rec.text
                    if not t_ok:
                        mm_idx = mm_by_id[rec.crop_id].first_mismatch["index"]
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


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="manga_ocr_lab", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("verify-models", help="hash + lock verification")
    sp.add_argument("--allow-unlocked-models", action="store_true")
    sp.set_defaults(fn=cmd_verify_models)

    sp = sub.add_parser("make-fixtures", help="generate the synthetic corpus")
    sp.add_argument("--target", help="corpus root (default tools/mangaocr_lab/test_crops)")
    sp.set_defaults(fn=cmd_make_fixtures)

    sp = sub.add_parser("run", help="decode corpus: reference + batched passes")
    sp.add_argument("--input", default=str(DEFAULT_CORPUS))
    sp.add_argument("--batch-sizes", default="1,2,4,8")
    sp.add_argument("--compare-reference", action="store_true", default=True)
    sp.add_argument("--decoder", choices=["reference", "android-legacy"],
                    default="reference",
                    help="android-legacy ADDS the defect-emulation pass")
    sp.add_argument("--numeric-check", action="store_true")
    sp.add_argument("--numeric-check-crops", help="comma-separated crop ids")
    sp.add_argument("--session-profile", choices=["android", "lab"], default="android")
    sp.add_argument("--limit", type=int)
    sp.add_argument("--category")
    sp.add_argument("--output", help="results dir (default timestamped)")
    sp.add_argument("--allow-unlocked-models", action="store_true")
    sp.add_argument("--skip-gate", action="store_true")
    sp.add_argument("-v", "--verbose", action="store_true")
    sp.set_defaults(fn=cmd_run)

    sp = sub.add_parser("trace", help="per-step JSONL dumps for one crop")
    sp.add_argument("--crop", required=True)
    sp.add_argument("--batch-size", type=int, default=4)
    sp.add_argument("--trace-top", type=int, default=5)
    sp.add_argument("--session-profile", choices=["android", "lab"], default="android")
    sp.add_argument("--output")
    sp.add_argument("--allow-unlocked-models", action="store_true")
    sp.add_argument("--skip-gate", action="store_true")
    sp.set_defaults(fn=cmd_trace)

    sp = sub.add_parser("export-failure", help="device failure -> corpus entry")
    sp.add_argument("--image", required=True)
    sp.add_argument("--box", required=True, help="x,y,w,h")
    sp.add_argument("--app-output", required=True)
    sp.add_argument("--expected")
    sp.add_argument("--id", required=True)
    sp.add_argument("--target", help="corpus root (default test_crops)")
    sp.set_defaults(fn=cmd_export_failure)

    args = p.parse_args(argv)
    if getattr(args, "numeric_check_crops", None):
        args.numeric_check_crops = [v.strip() for v in
                                    args.numeric_check_crops.split(",") if v.strip()]
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
