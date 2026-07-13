# AOT Tier 3 Corpus Gate Harness

**Status (2026-07-13):** harness COMPLETE and proven on a 5-page synthetic
corpus. Real 20-page manga corpus is a HUMAN CURATION step — see "What's
missing" below.

This is the Wave 5.1 Phase 1-B deliverable from
`Plan/AOT_NPU_FIXED512_DESIGN_2026-07-12.md`. Its job: prove the fixed-512
AOT model does not introduce new guard rejections vs the dynamic model,
across a representative corpus, before the static model is wired into
production (Wave 5.2).

## Why a Python harness, not Kotlin?

`AotOutputGuard.kt` is pure (`IntArray` in, verdict out) and already JVM-tested
by `AotOutputGuardTest`. But the **model inference** that produces the output
the guard inspects runs through `onnxruntime-android`, which does not execute
in a JVM unit test. Options were:

1. Add a JVM `onnxruntime` dep (CPU-only) to the test classpath — new
   dependency, requires approval, and pulls a 50MB native lib into every
   developer's test run.
2. Robolectric — not a current dep; large effort.
3. Python with `onnxruntime` (already a dep for the model conversion in
   `tools/aot_conversion/`) + a faithful numpy reimplementation of the guard.

Option 3 was chosen: it runs in the same Python env already used for model
work, the guard reimplementation is parity-tested against the Kotlin guard
(see `test_aot_output_guard_parity.py`), and it produces the same JSON report
+ visual-QA artifacts a Kotlin harness would.

The tradeoff: the gate is run manually (not in CI), like the Tier 2 numerics
check. This matches the master plan's intent — Tier 3 is a pre-merge quality
gate, not a per-commit test.

## Files

| File | Purpose |
|---|---|
| `aot_output_guard.py` | Faithful numpy reimplementation of `AotOutputGuard.kt`. Constants MUST match; `test_aot_output_guard_parity.py` enforces this. |
| `test_aot_output_guard_parity.py` | Parity test: 8 cases mirroring `AotOutputGuardTest.kt`, asserting the Python mirror agrees with the Kotlin guard. Run before any corpus gate. |
| `run_corpus_gate.py` | The harness. Runs both models on every page in a corpus dir, applies the guard, writes `baseline_report.json` + optional side-by-side PNGs. |
| `make_synthetic_corpus.py` | Generates 5 synthetic pages to validate the harness end-to-end. NOT a substitute for the real corpus. |
| `manifest.schema.json` | JSON schema for the per-page `manifest.json`. |
| `synthetic_corpus/` | Output of `make_synthetic_corpus.py`. |
| `real_corpus/` | Empty placeholder where the real 20-page corpus goes. |
| `gate_output/` | Output of `run_corpus_gate.py` (gitignored — regenerable). |

## How to run

```bash
# 1. Install deps (same env as the model conversion).
pip install onnxslim onnxruntime numpy pillow

# 2. Confirm the Python guard matches the Kotlin guard.
python tools/aot_corpus/test_aot_output_guard_parity.py
# Expected: 8/8 passed.

# 3. (Optional) Regenerate the synthetic corpus.
python tools/aot_corpus/make_synthetic_corpus.py

# 4. Run the gate on the synthetic corpus (validates the harness).
python tools/aot_corpus/run_corpus_gate.py \
    --corpus tools/aot_corpus/synthetic_corpus \
    --out tools/aot_corpus/gate_output \
    --save-images

# 5. Run the gate on the REAL corpus once curated.
python tools/aot_corpus/run_corpus_gate.py \
    --corpus tools/aot_corpus/real_corpus \
    --out tools/aot_corpus/gate_output_real \
    --save-images
```

## Gate criterion (per master plan Wave 5.2)

**Zero new rejections.** A page counts as a new rejection when:
- the dynamic model's output was ACCEPTED by the guard (not suspicious), AND
- the static-512 model's output on the same page is REJECTED.

Any new rejection FAILS the gate. The static model must not be wired into
production (Wave 5.2) until the gate passes on the real 20-page corpus.

Pages where the guard verdict is unchanged but the output stats shifted
significantly (mean delta > 5, variance delta > 2) are flagged for visual QA
via the side-by-side PNGs but do not fail the gate.

## Corpus composition (target: 20 real pages)

| Category | Count | Why |
|---|---|---|
| Dense text (shounen/seinen) | 4 | Stress text-removal |
| Screentone-heavy | 3 | Edge case: grayscale + boundary artifacts |
| Color manga | 2 | Non-uniform background padding stress |
| Large bubbles | 3 | Big inpaint regions |
| Small pages (<512 either axis) | 3 | The crash case being fixed |
| Tall/wide asymmetric (>512 one axis) | 3 | Edge cases from the AOT design §3 |
| Vertical JP text | 2 | MangaOcr path interaction |

Each page is a folder under `real_corpus/<page_name>/` containing:
- `page.jpg` — the source page
- `mask.png` — the inpaint mask (white = erase, black = keep)
- `manifest.json` — metadata (see `manifest.schema.json`)

## What's missing (human curation required)

The harness is complete and proven. What does NOT exist is the real 20-page
corpus. Producing it requires:

1. **Sourcing 20 representative manga pages.** Ideally from titles the app's
   users actually read, covering the 7 categories above. Licensing/attribution
   must be considered if the corpus is ever published — for internal gate use,
   any real pages work.
2. **Generating the inpaint mask for each page.** The mask is what the AOT
   model's `mask` input sees in production. The simplest path: run the app's
   own detection + OCR pipeline on each page, capture the produced mask, save
   it as `mask.png`. This requires a device/emulator run OR extracting the
   mask-construction logic into a standalone tool (separate effort).
3. **Filling in each `manifest.json`** with the correct category + box count.

Until those land, the harness runs only on the 5 synthetic pages. The
synthetic run PROVES the harness works and the gate logic is sound; it does
NOT prove the static model is safe on real manga. That proof is the gate's
actual purpose and requires the real corpus.

## Parity contract

If `AotOutputGuard.kt` changes, `aot_output_guard.py` MUST be updated in the
same change, and `test_aot_output_guard_parity.py` MUST still pass. The
constants at the top of `aot_output_guard.py` are duplicated from the Kotlin
guard's private companion — they have no compile-time link. A future
improvement would be to generate the Python constants from the Kotlin file,
but for now the parity test is the guard.
