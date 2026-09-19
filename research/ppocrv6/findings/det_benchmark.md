# PP-OCRv6 Small DET benchmark

**Evidence class:** host-CPU measurement, reproducible raw run. This is not an
Android-device benchmark and it is not a labeled detector accuracy evaluation.

## Run contract

- Fixed manifest: `research/dataset/manifest.json` (SHA-256
  `dfa5bd6a50379dc019c4fd79fa7a89538cc96ccf94e599a31023b4878c1689ea`).
- Three chapters / 76 pages / 486 Studio OCR regions. At run time, all 76
  source paths existed and decoded with OpenCV (`76 readable`, `0 file
  missing`, `0 decode failures`). Images were not copied into the worktree.
- Model: PP-OCRv6 Small DET ONNX, SHA-256
  `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`;
  `CPUExecutionProvider`, ONNX Runtime, ORT intra-op threads `4`.
- Preprocessing follows the shipped `inference.yml`: BGR decode, aspect
  preserving `max_side_len` resize with 32-pixel alignment, `/255` and
  ImageNet mean/std normalization, CHW. DB postprocess uses threshold `0.20`,
  box threshold `0.45`, unclip ratio `1.4`, and max candidates `3000`.
- Warm mode: all 76 pages per limit after one unmeasured warm-up inference.
  Cold mode: first 3 manifest pages per limit; each page creates a fresh ORT
  session and includes session construction in `total_ms`.

## Warm results (all 76 pages per row)

| max side | total median ms | total P90 ms | total P95 ms | inference median ms | pages/s (median) | max RSS MB | mean detections | Studio-box proxy recall (IoU50, mean/page) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 640 | 1934.2 | 2557.1 | 2770.6 | 1452.3 | 0.517 | 177.7 | 14.47 | 0.165 |
| 768 | 2531.7 | 4044.6 | 4345.6 | 2006.3 | 0.395 | 188.0 | 15.47 | 0.155 |
| 960 | 3247.3 | 6008.7 | 6362.8 | 2486.7 | 0.308 | 244.6 | 18.29 | 0.102 |
| 1024 | 3185.1 | 5299.8 | 5929.6 | 2441.0 | 0.314 | 275.1 | 19.05 | 0.104 |
| 1280 | 3213.8 | 8673.0 | 9347.2 | 2102.0 | 0.311 | 411.5 | 21.54 | 0.069 |

The run completed `380/380` warm page-limit records with `0` errors. The
non-monotonic medians and the large 1280 P90/P95 tail are host scheduling and
input-shape effects, not a claim that a larger limit is faster. Concurrent
research jobs were present on the host, so these numbers should be used for
relative characterization only.

## Cold results (3 pages per row)

| max side | total median ms | total P90 ms | total P95 ms | inference median ms | pages/s (median) | max RSS MB |
|---:|---:|---:|---:|---:|---:|---:|
| 640 | 4488.5 | 4576.9 | 4587.9 | 1384.4 | 0.223 | 105.1 |
| 768 | 10995.0 | 11202.9 | 11228.9 | 3603.2 | 0.091 | 106.9 |
| 960 | 5634.3 | 7295.9 | 7503.6 | 1916.2 | 0.177 | 148.9 |
| 1024 | 5900.1 | 7313.9 | 7490.6 | 2239.0 | 0.169 | 167.3 |
| 1280 | 4044.3 | 4944.9 | 5057.5 | 1369.6 | 0.247 | 249.5 |

The cold sample is intentionally small (`n=3`), so it is a startup-cost
characterization rather than a stable percentile estimate.

## Quality proxies and negative findings

The Studio OCR boxes are existing application annotations, not an independent
detector ground-truth set. The IoU50 columns are therefore **resolution
quality proxies only**. They must not be reported as precision, recall, mAP,
or accuracy. The proxy mean/page did not improve monotonically with input
limit (0.165 at 640 versus 0.069 at 1280); this negative result is consistent
with annotation/model/postprocess mismatch and is insufficient to select a
quality setting. Detection counts did increase (14.47 to 21.54 mean/page), but
that also is not evidence of accuracy.

No labeled accuracy conclusion is supported by this corpus. A proper quality
decision requires held-out, independently labeled text-region polygons and a
fixed matching protocol.

## Raw artifacts

- Script: `research/benchmark_det.py`
- Raw JSON: `research/results/det_benchmark.json` (timestamped copy:
  `det_benchmark_20260918T052155Z.json`)
- Per-page CSV: `research/results/det_benchmark.csv` (timestamped copy:
  `det_benchmark_20260918T052155Z.csv`)

The JSON records every page/limit/mode, stage timings (decode, preprocess,
inference, DB postprocess, total), input/original shapes, RSS, detection-count
and box statistics, status/error, and the proxy fields. Host fingerprint:
Windows 10, Python 3.11, Intel64 Family 6 Model 186, ORT CPU EP. The manifest
and model hashes above make the run auditable.
