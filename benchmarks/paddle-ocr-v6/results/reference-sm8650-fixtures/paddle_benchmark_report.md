# Paddle OCR v6 B1 Android baseline

- Evidence: **CONFIRMED** on `PKG110` / `SM8650` (API 36, `arm64-v8a`).
- Provider: **CONFIRMED** actual registered provider `cpu`; runtime `1.28.0`; requested route `QUALCOMM_QNN_HTP`.
- Batch: **CONFIRMED** measured batch size 1; `batched=false` is recorded from this B1 runner, not inferred from the engine name.
- Memory: **CONFIRMED** PSS sampled every 75 ms (46 samples); peak 214914 KiB; peak Java heap 66268576 bytes; thermal at peak `none`.
- Session creation: `1593.040 ms`; model preparation: `39.144 ms`; total: `3797.192 ms`.
- Corpus: 2/2 pages processed; downloaded corpus `disabled`; external corpus `disabled`.

## Width buckets

| Bucket | Samples | Cold p50 (ms) | Warm 1 p50/p95 (ms) | Warm 2 p50/p95 (ms) | Range (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 640 | 1 | 212.589 | 173.799/173.799 | 174.383/174.383 | 173.799–174.383 |
| 1600 | 1 | 423.136 | 361.787/361.787 | 369.769/369.769 | 361.787–369.769 |

`UNTESTED` means the external corpus was not supplied or a bucket had no samples; no result is substituted for missing Android evidence.
