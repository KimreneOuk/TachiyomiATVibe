# Detector-resolution adjudication

Date: 2026-09-18  
Status: host-only resolution/speed adjudication; no production files changed.

## Executive decision

**Use detector limit 640 as the current candidate for the composed
CTD→PP-OCR line-refinement path.** This is a speed/quality working choice,
not a claim that PP-OCRv6 Small DET replaces CTD.

- **CONFIRMED:** On the corrected 76-page CPU run, 640 is the fastest warm
  configuration and has the best strict region-proxy precision and recall of
  the tested limits (640/768/960/1024/1280).
- **LIKELY:** 640 is the best practical host baseline because increasing the
  limit adds latency and memory without improving this strict proxy. The
  choice is still a line-refinement hypothesis, not a validated end-to-end
  translation-quality result.
- **UNTESTED:** True line-level recall, crop/refinement quality, Android
  CPU/NNAPI/QNN behavior, and performance on SFX, furigana, rotated/noisy,
  and low-resolution strata.

Keep CTD as the page/region detector until a stratified line/region evaluation
of the composed path demonstrates non-regression.

## Evidence and accounting

The source is `research/results/det_benchmark.json`, created 2026-09-18 at
04:59:25Z, using PP-OCRv6 Small DET, ONNX Runtime 1.24.1,
`CPUExecutionProvider`, four threads, and five input limits. All 76 pages
completed successfully for every limit. Timings below use the 76 warm rows.

The manifest contains 486 annotated regions on 72 scored pages. Four pages
(the `tonari...ch1` pages 14–17) have `regions: []`; their proxy precision and
recall are `null` in the raw benchmark, so they are excluded from proxy
denominators rather than treated as known text misses. They remain included
in the 76-page timing and detection-count summaries.

For the table, pooled proxy precision is
`sum(IoU50 matches) / sum(predicted boxes)` and pooled proxy recall is
`sum(IoU50 matches) / 486`. The macro columns are the mean of the per-page
values emitted by the benchmark. These are region-proxy metrics only.

## Corrected warm-run results

| limit | scored pages | regions | IoU50 matches | predicted boxes on scored pages | pooled proxy P/R | mean page P/R | median total ms | pages/s median | max RSS MB | mean detections |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 640 | 72 | 486 | 88 | 1,059 | 0.083 / 0.181 | 0.101 / 0.174 | 1,934 | 0.517 | 177.7 | 14.47 |
| 768 | 72 | 486 | 81 | 1,132 | 0.072 / 0.167 | 0.096 / 0.163 | 2,532 | 0.395 | 188.0 | 15.47 |
| 960 | 72 | 486 | 57 | 1,338 | 0.043 / 0.117 | 0.050 / 0.107 | 3,247 | 0.308 | 244.6 | 18.29 |
| 1024 | 72 | 486 | 58 | 1,395 | 0.042 / 0.119 | 0.052 / 0.110 | 3,185 | 0.314 | 275.1 | 19.05 |
| 1280 | 72 | 486 | 38 | 1,566 | 0.024 / 0.078 | 0.031 / 0.073 | 3,214 | 0.311 | 411.5 | 21.54 |

Relative to 640, the 768 limit costs 31% more median wall time and reduces
median throughput to 76% of the 640 value. 960 costs 68% more and reduces
throughput to 60%; its recorded peak RSS is also 38% higher. The higher limits
emit more boxes, but not more strict region matches in this proxy.

## Proxy interpretation and granularity limits

The proxy compares PP-OCRv6 text-line boxes with manifest `ocr_box` regions
that represent larger bubble/free-text areas, using one-to-one greedy matching
at IoU >= 0.50. A line box contained inside a bubble can be useful for OCR yet
fail this region-level IoU test. Therefore:

- **CONFIRMED:** The corrected run is reproducible, complete (76/76), and
  supports the relative speed ordering above.
- **CONFIRMED:** At this strict threshold, 640 ranks above the other limits on
  the available region proxy (88 matches, 0.181 pooled recall). This is a
  ranking of proxy behavior, not detector accuracy.
- **CONFIRMED:** The independent CTD comparison reports PP-OCRv6 at 0/486
  strict region matches over 76 pages, while lowering IoU to 0.10 yields
  416 TP / 758 FP / 70 FN (precision 0.354, recall 0.856), and IoU 0.20 yields
  109 TP / 1,065 FP / 377 FN. The large threshold sensitivity exposes the
  line-versus-region extent mismatch.
- **LIKELY:** The increase in predicted boxes with resolution is mostly more
  line fragments/candidates and cannot be interpreted as improved or degraded
  text coverage without line-level labels and visual review.
- **UNTESTED:** Which boxes are useful text, SFX, strokes, panel art, or
  duplicate hypotheses; aggregate boxes cannot assign those semantics.

The proxy should not be used to claim that 960 or 1280 has worse text-line
accuracy than 640. It only shows that those settings do not improve overlap
with the current region annotations under IoU50.

## CTD versus PP-OCRv6 adjudication

The matched CPU comparison in `research/findings/ctd_vs_ppocr.md` reports CTD
477/486 strict region matches (precision 0.485, recall 0.981) versus PP-OCRv6
0/486 under the same region-level protocol. The comparison is useful for the
page/region role, but it is not a fair standalone line-detector comparison:
CTD emits region-level hypotheses and PP-OCRv6 emits compact text-line
hypotheses.

- **CONFIRMED:** CTD remains the defensible page/region detector on the fixed
  region-labeled corpus.
- **CONFIRMED:** PP-OCRv6 executes successfully and is materially faster on
  the host, producing text-line candidates.
- **LIKELY:** PP-OCRv6 is best evaluated as a line splitter/refiner inside a
  localized CTD ROI, matching the production integration contract.
- **UNTESTED:** Whether CTD→PP-OCR at 640 preserves region recall and
  translation quality across the missing categories and on Android.

## Candidate disposition

| candidate | speed status | quality/proxy status | disposition |
|---|---|---|---|
| 640 | **CONFIRMED** fastest warm host setting (1,934 ms median) | **CONFIRMED** best available strict proxy; **UNTESTED** true line quality | **Choose as working candidate** for ROI refinement, with CTD retained upstream |
| 768 | **CONFIRMED** slower (2,532 ms median) | **CONFIRMED** no strict-proxy gain over 640; **UNTESTED** line quality | Keep as an A/B fallback only if later line-level evaluation benefits from extra input scale |
| 960 | **CONFIRMED** substantially slower (3,247 ms median) and higher RSS | **CONFIRMED** no strict-proxy gain; **UNTESTED** line quality | Do not select by current evidence |

## Required follow-up before promotion

1. Build a stratified subset with line-level labels (including bubble,
   free-text, vertical, furigana, SFX/noise, and low-resolution cases).
2. Score CTD, PP-OCRv6 alone, and CTD→PP-OCR at 640/768/960 with line recall,
   region coverage, duplicate/false-positive rate, and translation-relevant
   crop review.
3. Run the composed path on representative Android devices, recording cold and
   warm stage times, PSS/native heap, provider provenance, cancellation, and
   thermal behavior. Keep 640 as the CPU fallback while this remains pending.

