# OpenVINO / laptop acceleration findings

Date: 2026-09-18  
Harness: [`research/test_openvino.py`](../test_openvino.py)  
Raw matrix: [`results/openvino_probe.json`](../results/openvino_probe.json)  
Matrix CSV: [`results/openvino_benchmark.csv`](../results/openvino_benchmark.csv)  
Targeted repeat: [`results/openvino_targeted_benchmark.json`](../results/openvino_targeted_benchmark.json)

## Executive recommendation

**INFERRED:** OpenVINO is viable for a laptop/desktop experiment, but this
run does not justify an Android integration or a Qualcomm HTP claim.  The
useful partition on this Intel host is **GPU encoder + CPU autoregressive
decoder**; keep decoder init/step on CPU unless a separate device-specific
benchmark proves otherwise.  Treat AUTO as CPU here and do not use it as an
accelerator selector.  Re-test on the target Android hardware/runtime before
making a mobile product decision.

## Runtime and hardware inventory

- **OBSERVED:** OpenVINO `2026.1.0-21367-63e31528c62-releases/2026/1` is
  importable. `Core.available_devices` is `['CPU', 'GPU']`.
- **OBSERVED:** CPU is `13th Gen Intel(R) Core(TM) i5-1334U`; GPU is
  `Intel(R) Iris(R) Xe Graphics (iGPU)`.
- **OBSERVED:** ONNX Runtime is `1.24.1` with only
  `AzureExecutionProvider` and `CPUExecutionProvider`; an
  `OpenVINOExecutionProvider` is not available. No ORT OpenVINO-EP path was
  benchmarked.
- **OBSERVED:** Direct `NPU` compilation fails for all three models before
  inference with the Intel NPU plugin error `0x78000004 ... [NPU_VCL]
  Unrecognized device ID!`. This is an Intel-host/plugin result, not evidence
  about Qualcomm HTP or any Android NPU.
- **OBSERVED:** In the bounded matrix (`--timeout-s 30`), no probe timed out
  and no child ended from a native crash; CPU/GPU/AUTO/HETERO returned
  successful inference rows, while the three NPU children returned nonzero
  with the explicit compiler error above. The raw child stdout/stderr is
  retained in `openvino_probe.json`.

## Model contract

**OBSERVED:** The local Hugging Face checkout contains the three split ONNX
models used by the harness:

| stage | input contract | output contract | SHA-256 |
|---|---|---|---|
| encoder | `[1,3,224,224]` FP32 | `[1,196,256]` FP32 | `D1FB455A07C1508CC56A4F4E15E2ED74ACA9A4D78FD220ECC0FF39625D67E1B3` |
| decoder_init | hidden `[1,196,256]`, token `[1,1]` | logits `[1,9415]` plus K/V tensors | `612F97E22848620FB36FCAC611467689CB4213D91F2D84F6A19042AD57D475F1` |
| decoder_step | hidden/token/position plus self/cross caches | logits plus one-token self K/V | `A244B814A3A669190F9EAE737960997633597CFA055FA186347E872BEE834BC9` |

The harness feeds deterministic random FP32 hidden/image tensors and zero/one
integer IDs/caches.  It checks that outputs are finite; this is a runtime
throughput probe, not OCR-quality validation.

## Compilation and operator support

- **OBSERVED:** CPU and GPU compile and infer all three models. `query_model`
  reports zero unsupported operations for direct CPU, direct GPU, and
  `HETERO:GPU,CPU` probes.
- **OBSERVED:** `HETERO:GPU,CPU` reports compiled execution device `GPU.0`
  for all three models in this environment; no CPU fallback was observed in
  the direct query/compiled-device metadata.
- **OBSERVED:** `AUTO` reports compiled execution device `(CPU)` for all
  three models. It is therefore a CPU route in this run, despite GPU being
  available.
- **OBSERVED:** NPU failures occur at compilation, so unsupported-op analysis
  is not meaningful for NPU; the error is a device/driver identity failure.

## Stage timings

The matrix was run with one warmup and three measured inferences per cell. The
small sample is exploratory; use the targeted ten-iteration repeat for the
hybrid/dispatch comparisons below.

| stage | CPU mean | GPU mean | AUTO mean | HETERO mean |
|---|---:|---:|---:|---:|
| encoder | 39.06 ms | 10.77 ms | 113.70 ms | 15.93 ms |
| decoder_init | 5.57 ms | 4.21 ms | 5.29 ms | 5.35 ms |
| decoder_step | 3.92 ms | 4.46 ms | 4.55 ms | 8.49 ms |

**OBSERVED:** Direct GPU is substantially faster for the encoder on this host,
while decoder-step GPU is not faster than CPU and HETERO is slower. These are
Intel laptop measurements and should not be generalized to ARM/Android.

## Decoder dispatch and hybrid measurements

The targeted repeat used two warmups and ten measured iterations. Each hybrid
iteration performs one encoder call, one decoder-init call, then eight
decoder-step calls. The dispatch rows perform sixteen separate decoder-step
calls per iteration, so the reported per-step value includes host/runtime call
overhead.

| configuration | mean | median | p95 | per-step/pipeline |
|---|---:|---:|---:|---:|
| CPU encoder + CPU decoder | 151.22 ms | 120.67 ms | 306.30 ms | 18.90 ms |
| GPU encoder + CPU decoder | **49.90 ms** | **48.72 ms** | **61.28 ms** | **6.24 ms** |
| CPU encoder + GPU decoder | 68.65 ms | 67.52 ms | 78.21 ms | 8.58 ms |
| CPU decoder-step dispatch, 16 calls | 50.23 ms | 49.04 ms | 62.06 ms | 3.14 ms/call |
| GPU decoder-step dispatch, 16 calls | 70.42 ms | 69.84 ms | 74.01 ms | 4.40 ms/call |

**OBSERVED:** On this host, the hybrid GPU-encoder/CPU-decoder route is the
fastest of the targeted configurations. CPU decoder-step dispatch is roughly
linear across sixteen calls; there is no measured benefit from moving the
autoregressive step to the GPU. The CPU+CPU hybrid has a large tail, so the
mean/median gap should be treated as host scheduling noise until repeated under
controlled affinity and power settings.

## Limitations and next test

- **UNVERIFIED:** Android OpenVINO packaging, ARM CPU performance, memory
  pressure, and sustained thermal behavior.
- **UNVERIFIED:** Qualcomm GPU/NPU behavior. OpenVINO's Intel NPU failure must
  not be used to infer Qualcomm HTP capability.
- **UNVERIFIED:** Numerical parity against the Android OCR implementation or
  end-to-end token decoding quality.
- **INFERRED:** A practical mobile architecture should keep the decoder loop
  on the CPU unless the target runtime exposes a validated accelerator path;
  encoder acceleration is the larger opportunity because it dominates the
  static stage on this host.

Recommended follow-up: repeat the same harness on the target Android device
with the actual runtime/provider, record stage-level CPU and accelerator
labels, and reject any route whose provider falls back silently.
