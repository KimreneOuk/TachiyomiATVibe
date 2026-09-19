# MangaOCR QNN HTP compatibility

Status: offline graph analysis complete; full HTP execution remains a device gate.

## Executive result

The exact three MangaOCR mobile graphs are valid ONNX and run on the laptop's
CPU execution provider. No QNN/QAIRT SDK tools or `adb` are installed here, so
there is no laptop evidence of HTP execution. The offline recommendation is:

| Graph | Offline classification | Device verdict | Recommendation |
|---|---|---|---|
| `encoder.onnx` | **PARTIAL** | **REQUIRES DEVICE TEST** | First HTP candidate; test static B=1 FP16/QDQ and inspect any CPU partition. |
| `decoder_init.onnx` | **PARTIAL** | **REQUIRES DEVICE TEST** | Test only after encoder; static shapes help, but Gather/LN/Softmax/INT64 input are runtime-sensitive. |
| `decoder_step.onnx` | **UNLIKELY** | **REQUIRES DEVICE TEST** | Keep autoregressive steps on CPU unless strict HTP proves a useful partition; cache-index Gather/Where paths are the risk. |

This is an operator/shape feasibility assessment, not a claim that any graph
is supported by every QAIRT version. A QNN EP registration message is not
execution evidence. The acceptance gate is a successful session creation and
real inference with `session.disable_cpu_ep_fallback=1`, followed by numerical
comparison against the CPU session.

## Exact graph inventory

Observed by `python research/inspect_qnn_compatibility.py --runtime` on
2026-09-18. The machine-readable record is
[`research/results/qnn_compatibility.json`](../results/qnn_compatibility.json).

| Graph | SHA-256 (prefix) | Nodes | Inputs | Outputs | Initializers |
|---|---:|---:|---|---|---|
| encoder | `d1fb455a07c1508cc56a4f4e15e2ed74aca9a4d78fd220ecc0ff39625d67e1b3` | 474 | `FLOAT [1,3,224,224]` | `FLOAT [1,196,256]` | 337 FLOAT + 13 INT64 |
| decoder_init | `612f97e22848620fb36fcac611467689cb4213d91f2d84f6a19042ad57d475f1` | 253 | `FLOAT [1,196,256]`, `INT64 [1,1]` | logits `[1,9415]`; K/V `[4,1,4,1,64]`, `[4,1,4,196,64]` | 118 FLOAT + 7 INT64 |
| decoder_step | `a244b814a3a669190f9eae737960997633597cfa055fa186347e872bee834bc9` | 276 | encoder + two `INT64 [1,1]` + four FLOAT caches | logits `[1,9415]`; K/V slices `[4,1,4,1,64]` | 104 FLOAT + 10 INT64 |

All model inputs and outputs have fixed dimensions; there are no symbolic or
unknown I/O dimensions. This does **not** mean the decoder computation is
compile-time constant: token and position values select different Gather rows
at each step. The `1` in `[4,1,4,...]` is the current batch dimension; the
leading `4` is the number of transformer layers, not batch.

### Encoder

The graph contains 98 Conv, 59 Transpose, 28 Reshape, 21 Erf, 21 Div,
20 Sub, 8 ReduceSum, 2 ReduceMean, 4 Pad, 8 Relu and 8 Sigmoid nodes, plus
the associated Add/Mul arithmetic. It is static B=1 and has no Gather/Where or
cache tensors. This makes it the best candidate for a fixed-shape HTP context,
but it is entirely FP32 and has no QuantizeLinear/DequantizeLinear nodes.
`Erf`, reductions, padding semantics and FP32-to-HTP conversion must be
validated against the exact QAIRT/QNN version.

### Decoder init

The graph is a one-token transformer prefill: 58 MatMul, 14
LayerNormalization, 8 Softmax, 3 Gather and 4 Concat nodes. Its cache outputs
are fixed shape, which is favorable for context compilation. The input token
is INT64 and the graph remains FP32 with no QDQ. It may form a useful HTP
partition on a compatible runtime, but partial CPU fallback is plausible.

### Decoder step

The step graph has 21 Gather (16 directly from the four cache inputs), 4 Where,
1 Equal, 1 LessOrEqual, 2 Cast, 1 Clip and 8 Unsqueeze nodes around the
attention math. Cache index inputs include `val_5`, `val_6`, `val_194` and
`val_268`; those values are derived from runtime position state. This is the
key reason a static-shape inspection is insufficient. A fixed extent such as
`[4,1,4,256,64]` does not remove dynamic indexing or conditional masking.

## QNN/HTP implications

These are **INFERRED** from the graph and the normal ONNX Runtime QNN EP
contract; they are not device support guarantees.

1. **Partitioning:** QNN HTP supports a subset of ONNX operators and can leave
   unsupported nodes on CPU when fallback is enabled. Therefore a successful
   non-strict session can still be mostly CPU. Use strict fallback-disabled
   creation to expose an unsupported graph.
2. **Static shape/context:** the current B=1 shapes are suitable candidates
   for a shape-specialized context. B=2/4/8 requires separate exported graph
   variants (or a genuinely dynamic export); do not pad a B=1 graph and call
   it batching. Each variant needs its own context binary and numerical gate.
3. **FP16/QDQ:** none of the three graphs contains QDQ; all learned weights
   are FLOAT. HTP commonly benefits from FP16 or calibrated QDQ, but conversion
   can change attention/logit numerics. Preserve CPU FP32 as the reference and
   require token equality for decoder output. Do not quantize K/V caches to
   INT8 without an explicit accuracy experiment.
4. **Integer indices:** `input_ids` and `position_ids` are INT64. HTP support
   for the surrounding Gather/index path is runtime-dependent; an INT32 export
   may be worth a separate experiment, but it is a model variant and must not
   silently replace the current asset.
5. **Autoregressive dispatch:** even if decoder_step partially partitions,
   each token still needs a host-controlled step and cache update. The likely
   latency win is encoder batching, not moving the whole decoder loop to HTP.
6. **Context cache:** ORT context binaries are tied to model bytes, fixed
   shapes, provider options, backend/QAIRT version and device family. They are
   not portable laptop artifacts and must be regenerated after any export or
   QNN runtime change.

## Laptop evidence vs Snapdragon evidence

### Laptop (OBSERVED here)

- ONNX checker passed for all three assets.
- ORT CPU smoke inference passed for all three graphs using deterministic zero
  inputs; output shapes matched the declared outputs.
- Reported CPU session creation times were approximately 346 ms (encoder),
  147 ms (decoder init), and 159 ms (decoder step) on this host. These are
  correctness checks only and are not Android performance measurements.
- `qnn-onnx-converter`, `qnn-net-run`, `qnn-context-binary-generator`, QNN
  libraries and `adb` were not found. No Snapdragon inference was run here.

### Snapdragon (OBSERVED repository evidence, not rerun in this worktree)

`Plan/active/2026-08-17-npu-acceleration-architecture/progress.md` records a
OnePlus PKG110 (SM8650/Snapdragon 8 Gen 3, Android 16, SDK 36) run with the
repository's ORT 1.27 QNN build. QNN registration and route latching logged as
successful, but session capability setup failed with:

```text
QNN SetupBackend failed Failed to create device.
QNN_DEVICE_ERROR_INVALID_CONFIG
```

The strict probe also failed after trying `soc_model=57` and `htp_arch=75`.
The recorded consequence was honest CPU routing; the earlier QNN labels were
not execution proof. This device evidence is a **runtime/toolchain blocker**,
not evidence that these three model graphs are intrinsically invalid. Test a
newer QAIRT/ORT QNN package or another Snapdragon/runtime combination before
spending effort on model conversion.

## Reproduction and acceptance commands

### Host inventory

```powershell
python research/inspect_qnn_compatibility.py --runtime `
  --out research/results/qnn_compatibility.json
python research/inspect_qnn_compatibility.py --emit-commands
```

The script writes a stable JSON inventory, hashes, operator counts, cache
Gather paths, ONNX checker result and optional CPU smoke result. It never
labels CPU execution as QNN.

### Android strict gate

Use the existing debug diagnostics in the app and capture a real page run:

```bash
# From repository root on a host that has adb and an arm64-v8a Snapdragon device.
export APK=app/build/outputs/apk/debug/app-debug.apk
bash research/validate_qnn_android.sh eu.kanade.tachiyomi.debug qnn-device.log
# Open a MangaOCR page, then stop capture with Ctrl-C.
grep -E 'probeCombo|providers\\(|QNN SetupBackend|strict|context' qnn-device.log
```

The model-session options must include the equivalent of:

```kotlin
addQnn(mapOf("backend_type" to "htp", "soc_model" to "<device-specific>"))
addConfigEntry("session.disable_cpu_ep_fallback", "1")
```

For each graph, record: session-create success, first real `run()` success,
provider/partition metadata, context generation/reload time, and CPU-vs-HTP
output comparison. A PASS requires no `QNN SetupBackend failed`, no CPU
fallback, and valid output tensors. If strict creation fails, classify that
graph as unsupported for that device/runtime and retain the CPU route.

No app source, Gradle file, packaged model or runtime behavior was changed by
this investigation.
