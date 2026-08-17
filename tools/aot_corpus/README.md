# AOT Tier 3 Offline Corpus Gate

The fixed-512 AOT gate uses real free-text groups that actually route through
`inpaintReportFreeTextAot512`. Model inference is offline Python; the verdict is
the production Kotlin `AotOutputGuard` in `AotCorpusGateTest`.

## Current corpus

- 42 independent free-text samples
- 14 source pages from the 30-page Okiraku chapter
- deterministic identity `real_NNN__ft_NNN`
- zero generation failures and zero emitter failures
- 41 samples accepted by both models; `real_023__ft_001` is rejected by both
  as uniform near-white, so there are zero new static-model rejections

A sample is one detector-v4 label-2 ROI outside every bubble. It is not a whole
page and is not merged with neighboring detector ROIs. Paddle DET runs on the
ROI padded by exactly 12 pixels, DB connected components are filtered and
merged with the same constants and three-pass algorithm as `DbPostProcess.kt`,
and that one refined group receives an independent 512 crop and fixed-pill mask.

There is no detector-box fallback. Empty Paddle output, unreadable input, bad
geometry, an empty mask, model inference failure, malformed identity, or missing
asset is logged in a JSON report and makes the command return nonzero.

## Tracked assets

| Path | Purpose |
|---|---|
| `real_corpus_ft/` | source crops, masks, manifests, generation report |
| `qa_output/` | 42 visual comparisons plus CSV/Markdown/JSON QA reports |
| `app/src/test/resources/corpus/aot/` | emitted ARGB binaries and PNGs consumed/inspected by the JVM gate |
| `test_corpus_tools.py` | focused DB merge, identity, and validator tests |

`real_corpus_faithful/` is historical page-level output and is not the gate
input. `qa_fullpage_compare.py` and `qa_output_fullpage/` are separate untracked
full-page QA work and must not be overwritten by this workflow.

## Reproducible staged workflow

Always generate under `build/` first. Never point a generator directly at the
tracked asset directories.

```bash
SRC='tools/Okiraku Ryoushu no Tanoshii Ryouchi Bouei ~Seisan-kei Majutsu de Na mo na Kimura wo Saikyou no Jousai Toshi ni~ Chapter 33 &#8211; Rawkuma'

python tools/aot_corpus/generate_masks_faithful.py \
  --src "$SRC" --out build/aot_corpus/staging-a --clean
python tools/aot_corpus/generate_masks_faithful.py \
  --src "$SRC" --out build/aot_corpus/staging-b --clean

python tools/aot_corpus/emit_corpus_outputs.py \
  --corpus build/aot_corpus/staging-a \
  --out build/aot_corpus/emitted-a --clean
python tools/aot_corpus/emit_corpus_outputs.py \
  --corpus build/aot_corpus/staging-b \
  --out build/aot_corpus/emitted-b --clean

python tools/aot_corpus/qa_compare_modes.py \
  --corpus build/aot_corpus/staging-a \
  --out build/aot_corpus/qa-a --clean
```

Compare relative paths and SHA-256 digests between both generation trees and
both emission trees. Reports record only stable source/corpus directory names,
so every relative path and every byte, including JSON reports, must match.
Promote only after:

1. both generator runs report at least 20 samples, multiple source pages, and
   `failure_count: 0`;
2. both emitter runs report identical outputs and `failure_count: 0`;
3. Python tests pass;
4. QA reports contain every identity;
5. the Kotlin gate and full Gradle suite pass.

## Tests

```bash
python -m unittest discover -s tools/aot_corpus -p 'test_*.py' -v

JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat \
  :app:testStandardDebugUnitTest \
  --tests 'eu.kanade.translation.inpainting.AotCorpusGateTest' --no-daemon

JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat test --no-daemon
```

The JVM gate requires exactly 42 unique identities matching
`real_\d{3}__ft_\d{3}`, at least two source pages, 512x512 emitted arrays,
matching dimensions, non-empty masks, and zero new static-512 rejections. It
also validates each manifest's identity/page/source mapping, generator,
explicit `fallback_used: false`, positive Paddle line count, and non-empty
`paddle_lines` provenance. The emitter independently requires a valid
`generation_report.json` with zero failures and an exact ordered identity set.
