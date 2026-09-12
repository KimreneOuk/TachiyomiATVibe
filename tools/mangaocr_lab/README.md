# MangaOCR Batching Desktop Laboratory (T927)

Host-side environment to develop, validate, and benchmark MangaOCR batch
decoding against the EXACT ONNX assets bundled in the Android app — before
any production change. Ground truth is this lab's corrected B=1 reference
decoder, NOT the existing Android host protocol (which carries a proven
KV write-slot off-by-one).

> **Desktop timings ≠ Android performance.** Wall-clock numbers here are
> host CPU only. What transfers: correctness, and dispatch counts
> (session.run calls per page).

## Layout

```
manga_ocr_lab.py          CLI (run / trace / numeric via run / verify-models /
                          make-fixtures / export-failure)
models.lock.json          pinned SHA-256 of the 4 bundled assets
test_crops/               regression corpus (synthetic fixtures committed;
                          real/ + previous_failures/ hold user/device crops)
work/                     derived patched graphs + gate.json (gitignored)
results/                  timestamped run artifacts (gitignored)
lab/
  assets.py               hashing, lock verification, vocab
  corpus.py               crop discovery, expected.txt sidecars
  preprocessing.py        Android-identical crop -> [3,224,224]
  sessions.py             ORT sessions + run counters/wall timing
                          (--session-profile android replicates the app's
                          thread/sequencing/spin settings numerically)
  decode_common.py        token/EOS/position policy constants + pure logic
  reference.py            CORRECTED B=1 decoder (ground truth)
  android_legacy.py       production-protocol emulation, defect included
                          (labeled legacy-defective; never a baseline)
  graphs.py               deterministic patch of pristine graphs to dynamic
                          batch + mandatory B=1 equivalence gate
  state.py                microbatch state machine (pure logic; the module
                          that ports mechanically to Kotlin later)
  batched.py              microbatch driver (true-size chunks, no padding)
  numeric.py              stage-level batched-row vs solo-B=1 tensor diffs
  tracing.py              per-step JSONL dumps (--trace)
  fixtures.py             deterministic synthetic corpus generator
  report.py               comparison, run.json/per_crop.csv/mismatches.json,
                          terminal report
```

## Quick start

```
python manga_ocr_lab.py verify-models
python manga_ocr_lab.py make-fixtures
python manga_ocr_lab.py run --input test_crops/ --batch-sizes 1,2,4,8 -v
python manga_ocr_lab.py run --input test_crops/digits --batch-sizes 8 --numeric-check
python manga_ocr_lab.py trace --crop test_crops/digits/sample_001.png --batch-size 4
python manga_ocr_lab.py run --input test_crops/ --batch-sizes 8 --decoder android-legacy -v
```

## Invariants

- The bundled assets are **never modified**. Batch-capable graphs are derived
  into `work/derived/<hash-key>/` and admitted only after the gate proves
  patched-B=1 ≡ pristine-B=1 (token equality + tight logit tolerances).
- `--batch-sizes 1` runs the batched state machine and must match the
  reference token-for-token — a permanent self-test of the state machine.
- Every mismatch lands in `mismatches.json` with full token sequences and
  the first diverging index. A mismatch is a **finding**, never hidden in
  an aggregate percentage.
- The lockstep constraint (probed in T927 evidence): `position` is a
  microbatch scalar — all rows advance together; finished rows are fed EOS
  filler; the microbatch stops at position 127. See
  `lab/state.py` and `Plan/active/2026-09-12_T927_*/evidence/`.
