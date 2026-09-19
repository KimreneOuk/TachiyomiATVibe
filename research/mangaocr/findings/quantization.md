# MangaOCR precision and quantization findings

## Recommendation

Use FP16 as the only candidate worth a controlled Android compatibility trial. On this host it cut the three-model bundle from 64,721,852 bytes to 32,913,087 bytes (49.1% smaller), preserved greedy token IDs on all 8 fixtures, and kept encoder cosine similarity at 0.9999995–0.9999998. It was not faster on this CPU-only host (median full pipeline 232.2 ms vs 222.6 ms FP32), so the expected runtime benefit remains **UNVERIFIED** on Android NNAPI/GPU/NPU.

Do not ship either tested INT8 bundle for OCR quality. Dynamic weight INT8 and static calibrated INT8 both produced large encoder drift and zero exact token-sequence matches in the 8-fixture probe. Their file-size savings are real, but the accuracy result is a release blocker. QDQ is not decision-ready: the reproducible harness exists, but no QDQ run artifact was captured in this pass.

## Evidence and reproduction

- **OBSERVED:** Raw data is in [`fp16_results.json`](../results/fp16_results.json) and [`int8_results.json`](../results/int8_results.json). The harnesses are [`test_fp16.py`](../test_fp16.py), [`test_int8.py`](../test_int8.py), and [`test_qdq.py`](../test_qdq.py); shared app-compatible execution is in [`quantization_common.py`](../quantization_common.py).
- **OBSERVED:** Runs used Python 3.11, ONNX Runtime 1.24.1, ONNX 1.20.1, Windows CPU (`CPUExecutionProvider`; available providers also listed Azure), with no Android provider. The available `onnxruntime.transformers.float16` converter and `onnxruntime.quantization` APIs were usable; `onnxconverter_common` was not installed but was not required.
- **OBSERVED:** Inputs use the app’s exact grayscale, aspect-preserving resize, centered white 224×224 pad, and CHW normalization to [-1, 1]. Decoder execution follows the app’s `[CLS]`/position-1 KV-cache protocol.
- **OBSERVED:** Each run used 6 deterministic synthetic text-like crops (`seed=20260918`) plus 2 real crops sampled reproducibly from the supplied chapter’s 231 `.studio/ocr.json` OCR regions (`max_real=2`, same seed). Real image paths and boxes remain external; no source pages were copied into the repository. Synthetic crops are calibration/throughput fixtures only, not OCR ground truth.
- **OBSERVED:** Greedy decoding was capped at 8 generated tokens for this probe. Therefore parity is strong evidence for this bounded probe, not a full 128-position sequence guarantee.

## Model sizes

| variant | encoder | decoder_init | decoder_step | total | reduction |
|---|---:|---:|---:|---:|---:|
| FP32 | 17,070,003 | 24,875,052 | 22,776,797 | 64,721,852 | baseline |
| FP16 | 9,042,914 | 12,459,158 | 11,411,015 | 32,913,087 | 49.1% |
| dynamic weight INT8 | 6,530,341 | 6,439,083 | 5,898,856 | 18,868,280 | 70.8% |
| static QOperator INT8 | 6,105,728 | 6,387,853 | 5,859,412 | 18,352,993 | 71.6% |

## FP16 results

| measure | result |
|---|---:|
| encoder max absolute error (median across 8) | 0.00739 |
| encoder RMSE (median) | 0.00101 |
| encoder cosine similarity (range) | 0.9999995–0.9999998 |
| first-step logits RMSE (median) | 0.00349 |
| token agreement | 100% on 8/8 fixtures |
| exact token sequence | 8/8 fixtures |
| full-pipeline median, FP32 → FP16 | 222.6 → 232.2 ms |

The real chapter subset also had 2/2 exact token matches. The FP16 model’s first-step logits changed slightly but did not change greedy choices in this bounded run. The host timing is not evidence of a mobile speedup; this machine has only CPU execution enabled, and conversion/runtime kernel selection differs by Android provider.

## INT8 results

Calibration materialized 8 encoder samples (6 synthetic + 2 real), 8 decoder-init samples, and 32 decoder-step samples (4 positions per fixture). Both models were run end-to-end where conversion succeeded.

| variant | encoder cosine median | encoder RMSE median | first-step-logit RMSE median | exact token sequences | token agreement median |
|---|---:|---:|---:|---:|---:|
| dynamic weight INT8 | 0.6364 | 1.0488 | 1.8964 | 0/8 | 0.0 |
| static QOperator INT8 | 0.4057 | 1.3705 | 3.8771 | 0/8 | 0.0 |

Dynamic token agreement ranged from 0 to 0.333; static was 0 on every fixture. The two real chapter crops likewise had 0/2 exact matches for both variants. A single host timing row was retained by the harness for the candidate variants (dynamic 1,052.6 ms and static 185.8 ms on the final sampled fixture); this is not a balanced benchmark and must not be used as a performance claim. The model-size reduction alone is insufficient to justify the observed OCR loss.

### Static INT8 limitation

**OBSERVED:** The initial per-channel static attempt failed reproducibly in ORT 1.24.1 with `ValueError: operands could not be broadcast together with shapes (16384,) (64,)`. The recorded static result uses the portable per-tensor retry (`per_channel=False`). This is a tooling/model-graph limitation, not evidence that per-channel INT8 is accurate or Android-compatible.

## QDQ status

`test_qdq.py` implements the same reproducible real/synthetic calibration flow using `QuantFormat.QDQ`, QUInt8 activations, QInt8 weights, MinMax calibration, and per-tensor scales. No `research/results/qdq_results.json` was captured in this pass, so QDQ accuracy, model size, runtime, and Android compatibility are **UNVERIFIED**. Do not infer QDQ parity from the QOperator INT8 result; run the script and require the same tensor, greedy-token, real-crop, and device checks before considering it.

## Negative results and next action

- **NEGATIVE:** Dynamic INT8 did not preserve OCR token choices despite strong-ish logits cosine values; logits similarity is not a substitute for argmax/text parity.
- **NEGATIVE:** Static per-channel INT8 could not be calibrated by the installed ORT toolchain because of the broadcast error above.
- **UNVERIFIED:** Android memory/RSS, NNAPI/GPU/NPU kernels, thermal behavior, long-sequence decode, and full-chapter OCR quality were not measured here.

Recommended next step: package FP16 models behind an opt-in/internal build flag, then run an Android-device A/B test with the complete 231-region chapter corpus and a rollback path. Keep FP32 as the default until that device test verifies text parity and stability.
