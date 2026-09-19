# CTD vs PP-OCRv6 Small DET

Date: 2026-09-18  
Status: matched CPU comparison completed; semantic replacement decision **not established**.

## Dataset and protocol

The fixed dataset manifest is `research/dataset/manifest.json`: 3 chapters, 76 pages, 486 annotated text regions (434 `text_bubble`, 52 `text_free`). The manifest's corrected source paths were readable from this host and were used for all 76 pages. The two originally supplied roots were also checked and recorded as missing:

```text
C:\Users\User\Downloads\manga_test_chapters\..._ch16                 MISSING
C:\Users\User\Documents\TachiyomiAT-1.16.8-dev\...\dl_out          MISSING
```

The manifest supplies `ocr_box` annotations. Metrics below use one-to-one greedy matching at IoU >= 0.50. CTD boxes are region-level detections; PP-OCRv6 DET boxes are text-line detections, so this is a deliberately strict granularity comparison. A lower-IoU sensitivity check is included to expose that mismatch; it is not a substitute metric.

The harness is [compare_ctd_ppocr.py](../compare_ctd_ppocr.py). Re-run with:

```powershell
python research/compare_ctd_ppocr.py `
  --manifest research/dataset/manifest.json `
  --out research/results/ctd_vs_ppocr
```

## Confirmed results

| Measure | CTD / detector-v4 | PP-OCRv6 Small DET |
|---|---:|---:|
| Pages completed | 76/76 | 76/76 |
| Ground-truth regions | 486 | 486 |
| Predicted boxes | 984 | 1,174 |
| TP / FP / FN @ IoU 0.50 | 477 / 507 / 9 | 0 / 1,174 / 486 |
| Aggregate precision / recall | 0.485 / 0.981 | 0.000 / 0.000 |
| Mean per-page precision / recall | 0.491 / 0.977 | 0.000 / 0.000 |
| Mean IoU of matched boxes | 0.736 | — |
| Mean wall time per page (CPU) | 6,880 ms | 3,274 ms |
| Median wall time per page (CPU) | 7,237 ms | 3,259 ms |
| ONNX model bytes | 11,120,765 | 9,880,512 |

PP-OCRv6 is approximately 2.10x faster per page in this host-side CPU run and its DET file is approximately 11% smaller. The process peak RSS recorded by the sequential run was 436,899,840 bytes; this is a combined process/session observation, not an isolated per-model allocation measurement.

At lower IoU thresholds, PP-OCRv6 reaches 416 TP / 758 FP / 70 FN at IoU >= 0.10 (precision 0.354, recall 0.856), but only 109 / 1,065 / 377 at IoU >= 0.20. Its predicted boxes are usually compact line interiors inside the larger annotated regions. This is evidence of a granularity/box-extent mismatch, not evidence that the model detects no text.

## Paired error taxonomy

### CTD

- **CONFIRMED:** high region recall on the fixed manifest (477/486 at strict IoU), with many duplicate/extra boxes (507 FP).
- **CONFIRMED:** the detector emits class-0 bubble boxes plus class-1/2 text boxes; raw and same-label-deduplicated counts are retained per page.
- **LIKELY:** many false positives are overlapping bubble/text hypotheses rather than missed text; verifying this at the crop/visual level requires reviewing the per-page box JSON.

### PP-OCRv6 Small DET

- **CONFIRMED:** 1,174 text-line boxes were emitted across 76 pages and inference completed without model errors.
- **CONFIRMED:** strict region-level IoU is poor because boxes are substantially tighter than the manifest `ocr_box`; the lower-IoU sensitivity and per-page raw boxes make this visible.
- **LIKELY:** PP-OCRv6 is useful as a line splitter/refiner inside an already localized ROI, which matches the production Kotlin integration contract, but the evidence does not support treating it as a drop-in CTD region detector.
- **UNTESTED:** exact false-positive semantics (SFX, strokes, panel art) need visual review against the source pages; aggregate boxes alone cannot label those causes.

## Category coverage and limits

The manifest supports `text_bubble` and `text_free` categories. It does not provide explicit labels for normal dialogue versus SFX, furigana/small text, rotated text, noise, or low-resolution pages. Therefore:

- **CONFIRMED:** bubble/free-text pages were included in the matched run (49 bubble-only pages, 1 free-only page, 22 mixed pages under manifest category aggregation).
- **UNTESTED:** explicit vertical/horizontal, furigana, SFX, rotated, noisy, and low-resolution strata.
- **FAILED/NOT AVAILABLE:** no `.studio/ocr.json` was directly readable at the two originally supplied roots; the manifest's copied region metadata was used instead.

## Raw evidence

- [summary.json](../results/ctd_vs_ppocr/summary.json) — model hashes, runtime versions, missing-path evidence, page count, RSS.
- [per_page.csv](../results/ctd_vs_ppocr/per_page.csv) — one row per page with counts, metrics, timings, and protocols.
- `research/results/ctd_vs_ppocr/*.jpg.json` — raw CTD/PP boxes and manifest truth boxes for each page.

Runtime inventory from the run: Python 3.11.0, ONNX Runtime 1.24.1, OpenCV 5.0.0, NumPy 2.2.2, Windows 10 build 26200, CPUExecutionProvider. Model SHA-256 values are recorded in `summary.json`.

## Decision boundary

**CONFIRMED:** PP-OCRv6 Small DET is faster and smaller, and it produces useful text-line candidates.  
**CONFIRMED:** On this region-level annotated corpus, CTD has materially better strict box recall/IoU.  
**UNTESTED:** whether PP-OCRv6 preserves end-to-end translation quality for SFX, furigana, rotated/noisy text, and low-resolution pages.  
**RECOMMENDATION:** keep CTD as the page/region detector and use PP-OCRv6 DET only for ROI line refinement until a separately labeled, category-stratified evaluation demonstrates non-regression for those missing classes.
