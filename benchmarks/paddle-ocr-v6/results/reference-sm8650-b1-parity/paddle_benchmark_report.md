# Paddle OCR v6 B1 Android parity

- Evidence: **CONFIRMED** on `PKG110` / `SM8650` (API 36, `arm64-v8a`).
- Provider: **CONFIRMED** actual registered provider `cpu`; runtime `1.28.0`; requested route `QUALCOMM_QNN_HTP`.
- Batch: **CONFIRMED** measured batch size 1; `batched=true` is recorded from this B1 runner, not inferred from the engine name.
- Memory: **CONFIRMED** PSS sampled every 75 ms (38 samples); peak 246530 KiB; peak Java heap 73130032 bytes; thermal at peak `none`.
- Session creation: `1611.506 ms`; model preparation: `24.309 ms`; total: `3158.198 ms`.
- Corpus: 2/2 pages processed; downloaded corpus `disabled`; external corpus `disabled`.
- B1 parity: **CONFIRMED** exact text and confidence-bit equality for 2 samples.
- Detector matrix: `crop-only benchmark; detector-present/absent parity is covered by JVM fixtures`.

## B1 parity samples

| Page | Region | Leaf | Bucket | Text exact | Confidence exact | Reference confidence bits | B1 confidence bits |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| fixture_small_640 | fixture_small_640 | fixture_small_640 | 640 | true | true | 1065345780 | 1065345780 |
| fixture_large_1600 | fixture_large_1600 | fixture_large_1600 | 1600 | true | true | 1065322600 | 1065322600 |

## Width buckets

| Bucket | Samples | Cold p50 (ms) | Warm 1 p50/p95 (ms) | Warm 2 p50/p95 (ms) | Range (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 640 | 1 | 139.419 | 74.132/74.132 | 68.159/68.159 | 68.159–74.132 |
| 1600 | 1 | 175.708 | 173.448/173.448 | 166.726/166.726 | 166.726–173.448 |

`UNTESTED` means the external corpus was not supplied or a bucket had no samples; no result is substituted for missing Android evidence.

The device run completed before the test/benchmark changes were committed. The
APK reported `d6a1685` as its build SHA; the exact source tree used for this
run is now committed as `765be24`.
