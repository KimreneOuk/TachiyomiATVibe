# Tier 3 Corpus Correction Report — 2026-07-13

## Result

The corrected offline corpus contains **42 independent free-text samples from
14 real source pages**. Generation and emission completed with **0 failures**.
The production Kotlin guard reports **0 new static-512 rejections**.

The previous 12-entry corpus incorrectly combined all free-text detections on a
source page: Paddle DET ran on the whole page, lines were selected by center,
and all selected lines shared one crop and mask. Production instead processes
each detector-v4 free-text ROI independently.

## Corrected pipeline

For every detector-v4 label-2 box that has no parent bubble and does not overlap
a bubble:

1. create that ROI with production `PADDLE_CROP_PAD = 12`;
2. run Paddle v6 small DET on only that ROI;
3. apply DB threshold 0.18, box threshold 0.34, 8-connected components, minimum
   area 16, maximum area fraction 0.5, and the exact horizontal/vertical fragment
   merge (`sameLineFrac=0.6`, `gapFactor=1.0`, at most three passes);
4. back-project that ROI's merged lines to page coordinates;
5. create an independent centered 512 crop and fixed-pill mask (`pad=16`,
   `dilate=8`);
6. emit stable identity `real_NNN__ft_NNN`.

No hidden fallback is allowed. A Paddle-empty ROI is a corpus failure rather
than a detector-box sample. All page, ROI, geometry, image, validation, and
inference failures are logged and force a nonzero exit.

## Coverage

| Metric | Value |
|---|---:|
| Source chapter pages scanned | 30 |
| Source pages with AOT free-text groups | 14 |
| Independent gate samples | 42 |
| Generator failures | 0 |
| Emitter failures | 0 |
| Minimum required samples | 20 |

Source pages represented: `real_001`, `real_002`, `real_008`, `real_010`,
`real_011`, `real_012`, `real_017`, `real_020`, `real_021`, `real_023`,
`real_024`, `real_025`, `real_026`, and `real_030`.

## Determinism proof

The full generator was run twice into separate `build/aot_corpus` staging
folders. After normalizing only the absolute `source` path in the generation
report:

- 127 files in each tree;
- zero relative-path differences;
- zero SHA-256 content differences.

The emitter was then run independently over both staged corpora. After
normalizing only the absolute `corpus` path in the emission report:

- 295 files in each tree;
- zero relative-path differences;
- zero SHA-256 content differences.

Only validated staging output was synchronized into tracked corpus, gate, and QA
locations.

## Guard and QA result

All 42 identities are present in the visual QA report. Forty-one samples are
accepted by dynamic and static models. `real_023__ft_001` is rejected by both as
a uniform near-white fill. It is not a new static rejection and demonstrates
that the guard still exercises a real failure mode.

The Kotlin gate checks exact count, stable identity format, uniqueness,
multiple-source-page coverage, dimensions, non-empty masks, and zero new
rejections.

## Reproduction

See `README.md` for staged generation, determinism comparison, emission, QA,
and test commands. Production Android runtime files were not changed.
