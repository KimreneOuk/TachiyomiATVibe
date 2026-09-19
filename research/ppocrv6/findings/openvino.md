---
kind: review
title: "PP-OCRv6 OpenVINO laptop acceleration"
comments: none
---

# OpenVINO / PP-OCRv6 findings

Date: 2026-09-18. Harness: [`benchmark_openvino.py`](../benchmark_openvino.py). Raw machine-readable evidence: [`openvino_ppocrv6.json`](../results/openvino_ppocrv6.json) and [`openvino_ppocrv6.csv`](../results/openvino_ppocrv6.csv).

## Executive recommendation

OpenVINO is technically viable for a desktop/laptop experiment on this Intel host. The observed route worth further investigation is GPU detector plus CPU recognizer: its connected real-corpus run was 6,995 ms for two crops versus 19,603 ms for CPU+CPU. This is an Intel-host observation only. It does not justify Android, ARM, Snapdragon, Qualcomm GPU, or Qualcomm HTP support.

Treat `AUTO` as CPU here because compiled execution devices were `(CPU)`. Do not call it an accelerator selector without checking the actual execution-device metadata. Keep any production decision gated on target-device compile success, output parity, memory, and thermal testing.

## Host and corpus

- OpenVINO `2026.1.0-21367-63e31528c62-releases/2026/1`; ONNX Runtime `1.24.1` CPU reference.
- `Core().available_devices`: `['CPU', 'GPU']`.
- CPU: `13th Gen Intel(R) Core(TM) i5-1334U`; GPU: `Intel(R) Iris(R) Xe Graphics (iGPU)`.
- The harness used real manga crops from `app/src/test/resources/corpus/aot/*/dynamic_out.png`, not random tensors. The connected hybrid path ran detector output boxes into recognizer crops.
- DET model: 1,377 ordered ops, 9,880,512 bytes. REC model: 882 ordered ops, 21,159,378 bytes. SHA-256 values are in the raw JSON.

## Compile/support/performance matrix

The warm values below are real-crop inference measurements. Cold is the first request after compile. RSS is the process RSS delta observed after compile, not Android memory.

| stage | requested device | status / execution | compile ms | cold mean ms | warm mean ms | unsupported ops | ORT FP32 parity |
|---|---|---|---:|---:|---:|---:|---|
| DET | `CPU` | success / `CPU` | 3692 | 2115 | 1741 | 0/1377 | max abs 2.13e-5; boxes 3/3 |
| DET | `GPU` | success / `GPU.0` | 11791 | 5844 | 1016 | 0/1377 | max abs 0.0478; boxes 1/1 |
| DET | `AUTO` | success, CPU fallback / `(CPU)` | 4581 | 1952 | 1582 | query unsupported; compile succeeded | max abs 1.53e-5; boxes 1/1 |
| DET | `NPU` | **blocked at compile** | — | — | — | — | `0x78000004 - [NPU_VCL] Unrecognized device ID!` |
| REC | `CPU` | success / `CPU` | 3726 | 880 | 754 | 0/882 | max abs 2.75e-5; text 3/3 |
| REC | `GPU` | success / `GPU.0` | 5646 | 1237 | 202 | 0/882 | max abs 0.0277; text 1/1 |
| REC | `AUTO` | success, CPU fallback / `(CPU)` | 636 | 829 | 812 | query unsupported; compile succeeded | max abs 1.74e-5; text 2/2 |
| REC | `NPU` | **blocked at compile** | — | — | — | — | `0x78000004 - [NPU_VCL] Unrecognized device ID!` |

`AUTO` query probing itself returned `AUTO: cannot parse valid device from` in this OpenVINO build, but compilation/inference succeeded and explicitly reported `(CPU)`. That is evidence of CPU fallback, not GPU use.

## Connected hybrid evidence

| detector | recognizer | samples | boxes/iteration | pipeline mean ms |
|---|---|---:|---:|---:|
| CPU | CPU | 2 | 23 | 19,603 |
| GPU | CPU | 2 | 23 | **6,995** |
| CPU | GPU | 2 | 23 | 10,322 |

Each iteration ran DET on the real crop, converted returned boxes to crop images, then ran REC on those crops. The GPU-detector/CPU-recognizer route was fastest in this bounded run. The sample is intentionally small because concurrent native runtime jobs on this host made broad sweeps resource-limited; the raw rows preserve the exact sample counts and timings.

## Parity and interpretation

- CPU OpenVINO output was numerically close to the pristine ORT FP32 reference and produced identical sampled detector boxes/recognizer text.
- GPU output was not bit-identical: DET max absolute difference was `0.0478331`, REC max absolute difference was `0.0277114`. Sampled boxes and greedy CTC text still matched (`1/1` each in the GPU rows). A product decision needs an explicit numerical tolerance and broader corpus validation; do not silently assume GPU equivalence.
- All direct CPU/GPU `query_model` results reported zero unsupported ops and compiled execution on the requested device. No partition/fallback was observed for those direct paths.
- NPU failures occurred before inference at compilation. The exact Intel error is retained in JSON/CSV and is not evidence about Qualcomm or Snapdragon NPUs.

## Limits and next action

This is a host-only investigation. Android packaging, ARM CPU performance, sustained thermal behavior, Android RSS, target runtime/provider behavior, and Qualcomm hardware remain unverified. HETERO and AUTO hybrid rows were intentionally not launched after sufficient direct evidence; no claim is made for them.

Recommended action: use the harness on the target Android hardware/runtime and reject any route whose compiled execution-device metadata, memory, thermal behavior, or ORT/reference quality gate is not acceptable.
