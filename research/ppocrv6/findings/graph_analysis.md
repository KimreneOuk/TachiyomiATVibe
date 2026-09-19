# PP-OCRv6 small ONNX graph analysis

Status labels: **CONFIRMED** is read directly from the serialized graph or
reproduced locally; **LIKELY** is an engineering inference; **UNTESTED** means
the target provider/runtime is absent; **FAILED** means an explicit check
failed. The complete per-node inventory is in
[`../results/ppocrv6_graphs.json`](../results/ppocrv6_graphs.json), generated
by [`../inspect_onnx.py`](../inspect_onnx.py).

## Scope and provenance

The two shipped assets are the PP-OCRv6 small detector and the existing
PP-OCRv6 small recognizer. Both files are tracked at commit
`79970d5a9c850c15fa9c974add71bc81fd60fd1a`
(`feat(ocr): integrate PP-OCRv6 small det model for text-line detection`). No
model was downloaded for this inspection.

| model | repository path | bytes | SHA-256 | ONNX IR / opset | initializers / parameter elements |
|---|---|---:|---|---|---:|
| DET | `app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx` | 9,880,512 | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` | IR 10 / ai.onnx 14 | 169 / 2,453,368 FLOAT |
| REC | `app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx` | 21,159,378 | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` | IR 6 / ai.onnx 11 | 241 / 5,267,732 (202 FLOAT + 39 INT64) |

**CONFIRMED — graph validity.** `onnx.checker.check_model` passes for both
assets. There are no external tensor-data files and no model metadata or
producer version embedded in either file. The repository-side YAML identifies
the graphs as `PP-OCRv6_small_det` and `PP-OCRv6_small_rec`.

## Inputs, outputs, and dynamic dimensions

All graph I/O tensors are `FLOAT`.

| graph | input | output | dynamic behavior |
|---|---|---|---|
| DET | `x`: `[N, 3, H, W]` | `fetch_name_0`: `[N, 1, H_out, W_out]` | **CONFIRMED:** batch, height, and width are symbolic. Tested inputs preserve spatial size (`H_out=H`, `W_out=W`). YAML documents dynamic ranges 32–4000 for H/W. |
| REC | `x`: `[N, 3, 48, W]` | `fetch_name_0`: `[N, T, 18710]` | **CONFIRMED:** batch and width are symbolic; height is fixed at 48. CPU tests produced `W=160 → T=20` and `W=320 → T=40`. YAML documents width 160–3200 and batch 1–8. |

ONNX shape inference preserves the output symbols; this is not a claim of
unbounded support. Callers still need bounded page/ROI sizes and batches.

## Operator inventory

The JSON contains every node (242 DET nodes and 481 REC nodes), including each
input/output name, inferred shape expression, dtype, and scalar/list
attributes. Counts below are the complete operator-type inventory.

### DET (242 nodes)

| operator | count | operator | count | operator | count |
|---|---:|---|---:|---|---:|
| Add | 36 | Concat | 2 | Conv | 83 |
| ConvTranspose | 2 | Div | 13 | Erf | 13 |
| GlobalAveragePool | 8 | HardSigmoid | 13 | MaxPool | 1 |
| Mul | 39 | ReduceMean | 5 | Relu | 20 |
| Resize | 6 | Sigmoid | 1 |  |  |

DET is FP32 throughout its graph I/O and all 169 initializers are FLOAT. There
are no QuantizeLinear/DequantizeLinear nodes and no INT8 initializers.

### REC (481 nodes)

| operator | count | operator | count | operator | count |
|---|---:|---|---:|---|---:|
| Add | 103 | AveragePool | 1 | BatchNormalization | 3 |
| Concat | 3 | Conv | 57 | Div | 18 |
| Erf | 13 | HardSigmoid | 5 | Identity | 135 |
| MatMul | 13 | MaxPool | 1 | Mul | 43 |
| Pow | 5 | ReduceMean | 15 | Relu | 10 |
| Reshape | 8 | Shape | 4 | Sigmoid | 5 |
| Slice | 8 | Softmax | 3 | Sqrt | 5 |
| Squeeze | 8 | Sub | 5 | Transpose | 9 |
| Unsqueeze | 1 |  |  |  |  |

REC has 202 FLOAT and 39 INT64 initializers. The INT64 tensors are graph
shape/index constants; the model is not quantized. `Identity` is numerous
because the exported Paddle graph preserves pass-through values.

## Runtime smoke check

**CONFIRMED — local CPU execution.** With ONNX Runtime 1.24.1 and its
`CPUExecutionProvider`, zero-filled FP32 inputs ran successfully:

| graph | input | observed output |
|---|---|---|
| DET | `[1,3,32,32]` | `[1,1,32,32]` |
| DET | `[1,3,736,736]` | `[1,1,736,736]` |
| REC | `[1,3,48,160]` | `[1,20,18710]` |
| REC | `[1,3,48,320]` | `[1,40,18710]` |
| REC | `[8,3,48,320]` | `[8,40,18710]` |

**CONFIRMED — available local providers.** This environment reports only
`AzureExecutionProvider` and `CPUExecutionProvider`; no CUDA, NNAPI,
DirectML, CoreML, QNN, or vendor NPU provider is installed. The smoke check
therefore says nothing about Android hardware execution.

## Acceleration and portability assessment

### CPU — CONFIRMED / likely usable

The CPU EP executes both graphs for the documented input families. Main visible
cost drivers are DET's 83 `Conv` + 2 `ConvTranspose` nodes and REC's 57 `Conv`
+ 13 `MatMul` nodes, all with FP32 weights. Dynamic dimensions prevent relying
on one fixed-shape memory plan; bound page/ROI sizes and recognition batches.

### CUDA/GPU — UNTESTED; LIKELY partition-sensitive

No CUDA EP is present here. Common GPU backends generally cover the listed
convolution, pooling, elementwise, reshape, transpose, and matrix operators,
but exact support depends on backend/version and shape specialization.
`ConvTranspose`, `Resize`, `Erf`, `HardSigmoid`, and symbolic H/W or sequence
length are likely partition or graph-optimization pressure points. This is a
portability risk, not a confirmed failure.

### Android NPU / NNAPI — UNTESTED; LIKELY static-shape pressure

No Android NNAPI or vendor NPU runtime is available. Both graphs are FP32 and
dynamically shaped; REC additionally has runtime shape construction (`Shape`,
`Slice`, `Squeeze`, `Unsqueeze`, `Reshape`, `Transpose`) around its variable
length sequence. Many mobile accelerators prefer a small set of static shape
signatures and may partition unsupported nodes back to CPU. A fixed-shape
export/calibration path is a separate experiment, not an assumption.

### Qualcomm QNN/HTP — UNTESTED; LIKELY blockers are shape and dtype details

No QNN EP, Qualcomm SDK, device, or HTP compiler is present, so there is no
claim of compile or device support. The graphs contain no explicit QNN
quantization contract: I/O and learned weights are FP32, and neither graph has
`QuantizeLinear`/`DequantizeLinear`. Even when a QNN release lists an operator,
acceptance depends on rank, attributes, constant-vs-runtime inputs, dtype, and
static dimensions. Likely first blockers are:

* symbolic DET `N/H/W` and REC `N/W/T` dimensions;
* REC's dynamic shape/index subgraph (`Shape`, `Slice`, `Squeeze`, `Unsqueeze`,
  `Reshape`, `Transpose`);
* `Erf` and `HardSigmoid` in both graphs, plus `ConvTranspose` and `Resize` in
  DET; and
* FP32-only execution if the intended HTP path requires quantized tensors.

These are hypotheses to validate with the target QNN SDK using no-CPU-fallback
compile/run, not evidence that QNN definitely rejects the models.

## Findings and next action

* **CONFIRMED:** Reproducible hashes, opsets, sizes, parameter counts, full
  operator inventories, and dynamic I/O descriptions are in the JSON result.
* **CONFIRMED:** Both graphs pass ONNX validation and execute on the local CPU
  EP for representative documented shapes.
* **LIKELY:** CPU is the reliable baseline. Hardware acceleration will be most
  predictable after constraining DET H/W, REC width, and batch to a small set of
  static signatures; keep a CPU fallback for specialized behavior.
* **UNTESTED:** CUDA/GPU, Android NNAPI/vendor NPU, and QNN/HTP compilation or
  execution.
* **FAILED:** None of the graph-level checks failed.

Recommended next action: run the same inputs through target Android EPs, first
with CPU fallback disabled to expose unsupported partitions, then with fallback
enabled to measure whether acceleration materially beats the validated CPU
baseline.
