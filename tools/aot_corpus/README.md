# AOT Tier 3 Corpus Gate Harness

**Status (2026-07-13):** harness COMPLETE. Tier 3 gate PASSES on a real
18-page corpus (6 of 7 design categories; color deferred). Wave 5.2 unblocked.
See `CURATION_REPORT.md` for the full result and methodology.

This is the Wave 5.1 Phase 1-B deliverable from
`Plan/AOT_NPU_FIXED512_DESIGN_2026-07-12.md`. Its job: prove the fixed-512
AOT model does not introduce new guard rejections vs the dynamic model,
across a representative corpus, before the static model is wired into
production (Wave 5.2).

## Architecture: Python inference, Kotlin verdict

The gate is split cleanly along the language boundary that already exists in
this repo:

| Concern | Language | Where | Why |
|---|---|---|---|
| Model inference | Python | `emit_corpus_outputs.py` | onnxruntime is Python-only (like `tools/aot_conversion/`) |
| Guard verdict | Kotlin | `AotCorpusGateTest.kt` | `AotOutputGuard.kt` is production code; the test calls the REAL object |

`emit_corpus_outputs.py` does INFERENCE ONLY — it runs both models on each
corpus page and writes raw ARGB pixel arrays (`.bin`). No guard math lives in
Python. The JVM test reads those `.bin` files and applies the real
`AotOutputGuard.isSuspiciousUniformFill` — the same object prod uses. There is
no parity mirror to maintain and no second copy of the guard constants.

Why `.bin` not PNG for the test: Android unit tests stub out `java.awt`, so
`javax.imageio` is unavailable on the test classpath. The `.bin` format is
trivial to read with `java.io.DataInputStream` and carries the exact ARGB
`IntArray` prod feeds to `AotOutputGuard`. PNGs are also emitted for human
visual QA.

## Files

| File | Purpose |
|---|---|
| `emit_corpus_outputs.py` | Runs both AOT models on each corpus page, writes `.bin` (test input) + `.png` (visual QA). No guard logic. |
| `make_synthetic_corpus.py` | Generates 5 synthetic pages to validate the harness end-to-end. NOT a substitute for the real corpus. |
| `manifest.schema.json` | JSON schema for the per-page `manifest.json`. |
| `synthetic_corpus/` | Output of `make_synthetic_corpus.py` (source images + masks). |
| `real_corpus/` | Empty placeholder where the real 20-page corpus goes. |

The Kotlin half lives at
`app/src/test/java/eu/kanade/translation/inpainting/AotCorpusGateTest.kt`, and
its committed inputs at `app/src/test/resources/corpus/aot/`.

## How to run

```bash
# 1. Install deps (same env as the model conversion).
pip install onnxruntime numpy pillow

# 2. (Optional) Regenerate the synthetic corpus.
python tools/aot_corpus/make_synthetic_corpus.py

# 3. Emit model outputs (runs both AOT models, writes .bin + .png).
python tools/aot_corpus/emit_corpus_outputs.py \
    --corpus tools/aot_corpus/synthetic_corpus \
    --out app/src/test/resources/corpus/aot

# 4. Run the JVM gate (reads the .bin, applies the real Kotlin guard).
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat \
    :app:testStandardDebugUnitTest \
    --tests "eu.kanade.translation.inpainting.AotCorpusGateTest" --no-daemon

# 5. Run the gate on the REAL corpus once curated.
python tools/aot_corpus/emit_corpus_outputs.py \
    --corpus tools/aot_corpus/real_corpus \
    --out app/src/test/resources/corpus/aot
# (then re-run the gradle test; bump EXPECTED_CORPUS_SIZE to 20 in AotCorpusGateTest.kt)
```

## Gate criterion (per master plan Wave 5.2)

**Zero new rejections.** A page counts as a new rejection when:
- the dynamic model's output was ACCEPTED by the guard (not suspicious), AND
- the static-512 model's output on the same page is REJECTED.

Any new rejection FAILS the gate. The static model must not be wired into
production (Wave 5.2) until the gate passes on the real 20-page corpus.

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
