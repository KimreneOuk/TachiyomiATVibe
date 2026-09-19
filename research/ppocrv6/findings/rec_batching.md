# PP-OCRv6 Small REC batching findings

Date: 2026-09-18  
Harness: [`benchmark_rec_batching.py`](../benchmark_rec_batching.py)  
Raw evidence: [`rec_batching_raw.csv`](rec_batching_raw.csv), [`rec_batching_parity.csv`](rec_batching_parity.csv), [`rec_batching_summary.json`](rec_batching_summary.json), [`rec_batching_page_audit.csv`](rec_batching_page_audit.csv)

## Executive recommendation

Use fixed-shape production bucketing and batch only within a width bucket. The checked-in REC ONNX accepts dynamic batch and width dimensions, but the production preprocessing deliberately exposes width 640 or 1600. On the real-crop sample, bucketed B=2/4/8/16 produced zero text and token mismatches against the measured B=1 reference, including controlled fixtures. The safest deployment candidates are B=4 or B=8; B=16 is accepted by the pristine ONNX CPU session and stayed parity-clean here, but it is outside the declared TensorRT profile's maximum batch of 8 and has a larger memory envelope.

Do not use the tight dynamic-width experiment as a drop-in optimization. It reduces padding and CPU latency, but batching a short tensor with gray padding to another crop's dynamic width changes the REC time axis/logits. In this run it produced 85 text mismatches and 137 token mismatches across 480 real-crop comparisons, with 135 output-shape mismatches. That is an explicit quality failure, not an acceptable performance trade.

## Evidence scope and labels

- **E0 — pristine model:** checked-in `app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx`, SHA-256 `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`.
- **E1 — production preprocessing:** Python mirror of `PaddleOcrV6SmallEngine`: 48px height, ceil aspect-ratio resize, clamp to 1600, 640/1600 width alignment, RGB NCHW, `(value / 255 - 0.5) / 0.5`, gray 128 pad, production CTC argmax/repeat-collapse/blank removal/dictionary decode. Manifest `ocr_box` values already contain the production 12px context expansion; tall crops are rotated 90° CCW as in the Paddle horizontal-line path.
- **E2 — real crops:** 48 regions sampled deterministically from the 486-region annotated manifest, spanning all six requested width buckets. The full manifest has 76 pages and 486 regions.
- **E3 — controlled fixtures:** blank, black, gradient, and checker fixtures, included in every mode/batch-size parity comparison.
- **E4 — exploratory:** `tight` uses the model's dynamic width with per-batch max-width gray padding. It is not current production behavior and is rejected by parity evidence.

### Manifest page audit

The harness opened every manifest path, recorded file byte size and SHA-256, then decoded with OpenCV and recorded dimensions. In this worktree the hard audit observed:

| Manifest pages | Readable pages | Unreadable pages | Manifest regions | Readable regions | Skipped paths |
|---:|---:|---:|---:|---:|---:|
| 76 | 76 | 0 | 486 | 486 | 0 |

The audit is runtime-specific: the manifest contains external Windows paths, so another shell with a different path mapping must use the emitted page-audit CSV and report its own skipped paths rather than treating this result as portable availability.

## Model batch envelope

The ONNX input is `x` with shape `[DynamicDimension.0, 3, 48, DynamicDimension.1]`; output is `[DynamicDimension.0, Reshape_471_o0__d2, 18710]`. Direct CPU ORT accepted B=1/2/4/8/16 and dynamic widths. The checked-in `inference.yml` TensorRT profile declares min/opt/max shapes `[1,3,48,160]`, `[1,3,48,320]`, `[8,3,48,3200]` (lines 6–21), so B=16 is outside that accelerator profile even though the pristine ONNX CPU path accepts it. No graph/export change was made.

## Production bucketed measurements

The table reports crops/sec, peak RSS delta in MiB, and mean padding waste for the 48-real-region sample plus four controlled fixtures (52 measured items per mode/batch size). Batches are homogeneous by source-width bucket. Latency quantiles below are per-crop total latency and include input stacking, ORT run, and decode; `copy_ms`, `run_ms`, and `decode_ms` are separately present in the raw CSV.

| Width bucket | B=1 crops/s / RSS / waste | B=2 crops/s / RSS / waste | B=4 crops/s / RSS / waste | B=8 crops/s / RSS / waste | B=16 crops/s / RSS / waste |
|---|---:|---:|---:|---:|---:|
| <=96 | 1.14 / 6.9 / 91.5% | 1.29 / 25.9 / 91.7% | 1.42 / 50.0 / 92.0% | 1.85 / 26.2 / 92.0% | 2.04 / 57.9 / 91.5% |
| <=160 | 1.42 / 0.0 / 88.2% | 1.51 / 0.1 / 88.0% | 1.31 / 1.4 / 87.7% | 1.51 / 49.6 / 87.4% | 1.55 / 3.3 / 88.2% |
| <=256 | 1.25 / 0.1 / 85.6% | 1.16 / 0.1 / 85.6% | 1.41 / 1.4 / 85.6% | 1.52 / 2.8 / 85.6% | 2.06 / 2.8 / 85.6% |
| <=384 | 1.60 / 0.0 / 85.3% | 1.40 / 0.0 / 85.3% | 1.41 / 1.4 / 85.3% | 1.44 / 54.3 / 85.3% | 1.54 / 2.8 / 85.3% |
| <=512 | 1.71 / 0.0 / 77.1% | 1.64 / 0.0 / 77.1% | 1.26 / 1.4 / 77.1% | 1.55 / 2.8 / 77.1% | 1.32 / 2.8 / 77.1% |
| >512 | 1.33 / 0.0 / 73.9% | 1.79 / 0.0 / 73.9% | 1.82 / 1.5 / 73.3% | 1.88 / 2.4 / 73.9% | 2.04 / 2.1 / 73.9% |

Because each source bucket still maps to the same production 640 shape in this sample, padding waste remains high. Batching reduces fixed session/inference overhead, but CPU throughput gains flatten in some buckets; device-provider measurements are still required before selecting a universal B.

### Aggregate production quantiles

| Batch | Crops | Padding waste | Per-crop p50 / p90 / p95 (ms) | Throughput (crops/s) | Peak RSS delta (MiB) |
|---:|---:|---:|---:|---:|---:|
| 1 | 52 | 84.8% | 727.8 / 958.6 / 1032.9 | 1.354 | 6.9 |
| 2 | 52 | 85.0% | 693.1 / 921.3 / 1021.5 | 1.408 | 25.9 |
| 4 | 52 | 84.9% | 687.9 / 1043.7 / 1177.5 | 1.405 | 50.0 |
| 8 | 52 | 85.1% | 651.8 / 976.3 / 1148.6 | 1.616 | 54.3 |
| 16 | 52 | 83.6% | 568.1 / 705.2 / 732.8 | 1.717 | 57.9 |

The raw CSV retains the per-batch observations for every bucket and batch size; rows with a single observation naturally have identical quantiles. The aggregate table's quantiles are over measured batches and include the four fixtures, while the bucket table makes the width-bucket behavior visible.

## Parity results

The parity file compares every B>1 result with the same mode's measured B=1 result for the same crop.

| Mode | Real comparisons | Text mismatches | Token mismatches | Output-shape mismatches |
|---|---:|---:|---:|---:|
| `bucketed` | 240 | 0 | 0 | 0 |
| `tight` | 240 | 85 | 137 | 135 |

All controlled fixtures were parity-clean in the grouped run. Tight mode's mismatches are caused by dynamic-width batching: the model produces a time dimension and logits conditioned on the padded batch width, so a crop's result is not invariant when another crop forces a larger width. This makes tight mode unsuitable without a separately validated graph/export and a quality-preserving masking strategy.

## Reproduction

```powershell
python research/benchmark_rec_batching.py `
  --dataset-manifest research/dataset/manifest.json `
  --batch-sizes 1,2,4,8,16 `
  --width-modes bucketed,tight `
  --max-crops 48 `
  --output-dir research/findings/rec_batching
```

The generated metadata records provider, model/dictionary hashes, input/output shapes, session-init time, page-audit records, runtime versions, accessible/skipped page counts, and sample limits. No production source or model was modified.
