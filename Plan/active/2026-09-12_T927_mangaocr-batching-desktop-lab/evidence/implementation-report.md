# T927 Implementation Report — MangaOCR Desktop Laboratory (S0–S4 complete)

Date: 2026-09-12
Scope: `tools/mangaocr_lab/` (new, self-contained). **Zero Android production
files touched** — `app/src/main/assets/models/ocr/` verified pristine by hash
before and after every run (`manga_ocr_lab.py verify-models`).

## Delivered

All five stages from DESIGN.md §17, verified end-to-end on the real bundled
assets (Python 3.11, onnx 1.20.1, onnxruntime 1.24.1, Windows host):

| Stage | Contents | Status |
|---|---|---|
| S0 | `assets.py`, `models.lock.json`, `corpus.py`, `verify-models` | done, 4/4 hashes OK |
| S1 | `preprocessing.py`, `sessions.py`, `decode_common.py`, `reference.py`, `records.py`, `report.py` | done |
| S2 | `android_legacy.py`, `tracing.py`, `make-fixtures`, `export-failure` | done |
| S3 | `graphs.py` (patch + mandatory gate), `state.py`, `batched.py`, `numeric.py` | done |
| S4 | RSS sampling (`hostinfo.py`), README, `.gitignore` | done |

## Verified results (golden run: 50 synthetic fixtures, 10 categories)

- **Graph gate**: derived (patched) graphs pass the mandatory B=1 equivalence
  gate — enc max|Δ|=1.2e-05, dec max|Δ|=1.9e-05, tokens equal. Cached in
  `work/derived/<hash-key>/gate.json`; re-runs reuse without re-deriving.
- **State-machine self-test**: batched path at `--batch-sizes 1` matches the
  reference decoder **50/50 token-exact** on the full corpus.
- **Batching**: 50/50 matches at B=1/2/4/8; `mismatches.json` empty at every B.
- **Numeric checks**: encoder / init logits / step logits all **bit-exact
  (max|Δ| = 0.000e+00)** for in-batch rows vs independent B=1, at every B,
  including rows with heterogeneous neighbors. The step-count-differs notes
  (e.g. solo=5 vs in-batch=7) are the shared lockstep length — reported, as designed.
- **Dispatch model** (transfers to Android as session.run counts):
  step calls 749 (B=1) → 526 (B=2, −30%) → 338 (B=4, −55%) → 180 (B=8, −76%).
  enc/init: 50 → 25 → 13 → 7.
- **Position-limit coverage**: `long/` fixtures hit the 127-token shared
  ceiling and take POSITION_LIMIT (2 crops), matching reference behavior.
- **Empty fixtures**: white crops decode to the model's prior (`キャー`) —
  same as production; `skipped_empty` guard only triggers on zero-size input.
- **android-legacy emulation** (`--decoder android-legacy`): reproduces the
  proven KV write-slot defect — `1234567890` → `1234556769000` (13 tokens) vs
  reference `12345676900`; short sequences (≤4 tokens) unaffected, consistent
  with divergence-from-step-3 evidence. 816 step calls (defect lengthens
  degenerate sequences).
- **trace**: `results/<run>-trace/<id>.reference.jsonl` + `<id>.b4.jsonl`;
  target row's logits_sum identical to the reference at every step (first
  divergence: none). Fillers from sibling files, target at row B//2.
- **export-failure**: crops a box + writes `previous_failures/<id>/{crop.png,
  meta.json, crop.expected.txt}`; the export is immediately discoverable by
  `run` (round-trip verified, then removed as it was smoke data).
- Peak RSS (desktop, informational): 333 / 365 / 421 / 540 MB at B=1/2/4/8.

## Lab-side findings worth keeping

1. **Raw model accuracy on NEAREST-downscaled synthetic digits is imperfect
   even under the corrected decoder** (e.g. `1234567890` → `12345676900`).
   This is model+preprocessing behavior (Android `drawBitmap` with null paint
   = nearest sampling, faithfully mirrored), not a decode-protocol issue.
   Batch-vs-reference equality is bit-exact everywhere — the two concerns are
   now cleanly separated, which is precisely what the lab was built for.
2. **Divergence detector semantics**: a batch trace row is only comparable to
   the reference for the *target* crop; filler rows legitimately differ.
   `tracing._first_divergence` filters to the target row.

## Commit layout (proposed, not executed)

Everything is new files under `tools/mangaocr_lab/` (fixtures + code + lock +
README + .gitignore). `work/` and `results/` are gitignored; `test_crops/real/*`
and `test_crops/previous_failures/*/` (device exports) are ignored, synthetic
fixtures are committed. No repo files outside `tools/mangaocr_lab/` changed.
