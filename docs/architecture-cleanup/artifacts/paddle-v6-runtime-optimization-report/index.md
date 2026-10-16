---
title: "PaddleOCR v6 Small runtime and model optimization diagnostic"
kind: spec
comments: none
---

# PaddleOCR v6 Small runtime and model optimization diagnostic

## Scope and evidence

Reviewed the LiteRT PaddleOCR implementation and assets in the local redesign checkout at commit 7d895216, the saved OnePlus 12 log in this epic, the completed Phase 5/6 benchmark artifacts, and the repository’s ONNX model files. No source code or models were changed, and no new workload was run on the phone during this investigation.

The saved device evidence is unusually decisive for the app-level bottleneck. The exact delay varies by page and width mix, but the evidence below shows that the current integrated GPU model can be fast while the page pipeline spends most of its time rebuilding and serially invoking it.

## Diagnostic summary

| Finding | Evidence | Priority |
| --- | --- | --- |
| The integrated recognizer is using Adreno OpenCL, with the current graph fully delegated. There is no evidence of per-op CPU fallback in this run. | The page trace reports provider litert_gpu_delegate. LiteRT logs replace all 356/356 or 357/357 nodes with one LITERT_CL partition. | Settled |
| The dominant app cost is repeated model creation plus one-crop-at-a-time dispatch. | A 26-leaf page had batchSize=1, 26 batches, avgInferMs=44.44, and 1,156.96 ms dispatch wait. Eleven CompiledModel/graph initializations appear inside its 1.306 s OCR stage. | P0 |
| The rec runner retains only one width bucket. Changing bucket evicts and recompiles the prior runner. | MAX_RESIDENT_LITERT_RUNNERS is 1; the page-wide LiteRT batch path still iterates crops and invokes the recognizer one at a time. | P0 |
| GPU timing is not split cleanly. | CompiledModel.run is timed separately, but the OpenCL log sets hint_waiting_for_completion=false; readFloat happens inside the CTC timer and can wait for GPU completion and copy the entire output. | P0 instrumentation |
| The MangaOCR comparison is not per equivalent crop. | One logged page had 9 text regions expanded to 26 Paddle line leaves. MangaOCR receives the original region crops; PaddleOCR is explicitly horizontal and splits regions into line leaves. | Important for comparisons |
| The NPU error is provider registration failure, not evidence of a missing phone NPU or a failed OCR operator. | Environment.create() is called without a Qualcomm LiteRT provider. The app includes Google LiteRT and GPU artifacts, not the Qualcomm LiteRT delegate; the runtime logs kLiteRtStatusErrorNotFound while separately registering OpenCL. | Settled |
| Sub-50 ms looks realistic for warm common-width crops, but is not established for 1600-wide lines. | Phase 6 measured the surgically repaired 640 model at 4.29 ms on Adreno. Phase 5 measured 1600 on 4-thread XNNPACK at 114.96 ms; there is no equivalent 1600 GPU result in that benchmark. | Needs width-specific measurement |

## What is happening in the app

The saved trace for page key pf0ebe378b8e8928a reports 9 text-region items and 26 Paddle recognition leaves. Its OCR stage took 1,306 ms; the crop summary reports 26 leaves, batch size 1, and 44.44 ms average inference latency. The page coordinator records 26 batches and 1,156.96 ms of wait/dispatch time.

Within that same OCR interval, 11 separate LiteRT FlatBuffer model initializations and OpenCL graph initializations are logged. Each graph is then wholly assigned to one GPU partition. This matches the implementation:

- PaddleOcrV6SmallEngine.kt:603 limits resident LiteRT runners to one.
- PaddleOcrV6SmallEngine.kt:248-254 closes the resident runner when another width bucket is needed.
- PaddleOcrV6SmallEngine.kt:420-439 handles the LiteRT batch path by looping through crops and calling recognizeWithConf on each one.
- PaddlePageOcrCoordinator.kt:352-356 directly maps crops to single-crop calls when maxBatch is 1. The logged run used batchSize 1.
- The five fixed recognition buckets have separate FlatBuffers. Each is about 10.62 MB, so they duplicate about 53.1 MB of weights in the package.

Changing line widths can therefore evict a GPU graph, compile another, and later compile the previous width again. The first recognition using each shape also pays compile/setup cost. The saved trace includes a large-object GC during the same interval, consistent with the model and output-buffer churn.

The trace’s OCR stage record says provider=none, while its crop summary and the LiteRT logs say litert_gpu_delegate. Use the latter as the provider evidence for this run; the higher-level provider field needs propagation fixed before it can be trusted.

## GPU delegate and LiteRT findings

The app uses LiteRT 2.3.0 and requests CompiledModel.Options(Accelerator.GPU). On this OnePlus 12 build the runtime loads OpenCL, and the recognition graphs in the saved trace show 356/356 or 357/357 nodes assigned to one LITERT_CL partition. That is a monolithic GPU graph: the old 5D attention, StridedSlice, HardSigmoid, and transposed-convolution compatibility problems described in Phase 5 were addressed by the Phase 6 graph surgery for the tested models.

This is not the legacy Interpreter GPU delegate path where unsupported nodes may remain on the CPU and cause GPU/CPU synchronization. The current CompiledModel call asks for GPU only. Google’s LiteRT sample migration notes that GPU-only CompiledModel creation fails when an operation cannot be accelerated, and the saved device logs show complete partitions for the graphs created here. Do not diagnose the current delay as unsupported operators falling back to CPU.

Historical device results already show the model-level opportunity:

| LiteRT model run on OnePlus 12 | Phase 5 baseline | Phase 6 repaired graph |
| --- | --- | --- |
| 640-wide rec, Adreno OpenCL | 129.82 ms with 12 partitions | 4.29 ms, 356/356 GPU nodes |
| 960 detector, Adreno OpenCL | 276.24 ms with 15 partitions | 11.91 ms, 346/346 GPU nodes |
| 1600-wide rec, XNNPACK CPU, 4 threads | 114.96 ms | No comparable 1600 GPU result recorded |

See [Phase 5 telemetry](../phase-5-on-device-telemetry-benchmarking) and [Phase 6 GPU graph surgery](../phase-6-gpu-monolithic-surgery). The current app trace confirms that the repaired GPU path is present, but its runner lifecycle prevents the page from realizing the low warm-model number.

The current instrumentation also misattributes some GPU time. OpenCL is logged with hint_waiting_for_completion=false. CompiledModel.run can return before the GPU has finished, and the subsequent output read can block until it has. The runner times run as neuralForwardMs, then starts ctcDecodeMs before calling outBuf.readFloat(). Consequently, the “forward” number can be too low and the “CTC” number includes output synchronization/readback.

Add separate createModelMs, inputBufferWriteMs, runCallMs, outputReadbackMs, argmaxMs, and CTC-collapse/dictionaryMs. Measure run plus readback as device-complete inference. Keep page coordination and queue/admission wait separate.

## Model size, shape, and work

Local ONNX metadata and the engine’s fixed-shape contract give these estimates. MAC means multiply-accumulate; conventional FLOPs are approximately 2× MACs. The Paddle estimates below cover the final CTC classifier only, so they are a lower bound on full-model compute.

| Model | Parameters and graph | Per-crop dominant work and output |
| --- | --- | --- |
| PaddleOCR v6 Small | 5,267,683 FP16 weight values; 629-node dynamic-width ONNX graph; 18,710 output classes. Each fixed LiteRT bucket repeats roughly 10.62 MB of model weights. | The final 120×18,710 classifier alone costs 120×18,710×(width/8) MACs: about 180M MAC / 359M FLOPs at width 640 and 449M MAC / 898M FLOPs at width 1600. |
| MangaOCR | Three FP32 ONNX files total about 64.7 MB. The encoder/init/step files contain 16.00M weight values including shared decoder tensors; exact tensor-value dedup gives about 10.32M unique values. | Static 224×224 encoder: about 1.109B Conv MAC / 2.22 GFLOPs; decoder init: about 108.8M MatMul MAC; each token step: about 6.55M MatMul MAC. Each generated token emits 9,415 scores. |

For Paddle, the rec head emits width/8 timesteps. At width 640, that is 80×18,710 = 1.497M FP32 scores, about 6.0 MB to materialize/read. At width 1600 it is 200×18,710 = 3.742M scores, about 15.0 MB. The decoder scans the entire class row at every timestep. This output/readback path grows 2.5× from width 640 to 1600, before accounting for the CNN and attention trunk.

MangaOCR has more unique weights and much higher encoder MACs per crop, but it does not produce a dense class vector for every image timestep. It emits a 9,415-score vocabulary vector per generated token and stops on EOS, with a maximum position of 128 in this app. It also receives one region crop where Paddle may receive several line crops. This explains why model size or whole-model FLOPs do not predict the perceived page latency. Compare page-level throughput and accuracy on the same detected regions, and separately compare both engines on the same pre-extracted line crops.

## NPU and Qualcomm QNN

The NPU warning is emitted while the LiteRT environment looks up/registers NPU accelerators. Paddle’s LiteRT setup calls Environment.create() without a Qualcomm BuiltinNpuAcceleratorProvider. The app dependencies shown in app/build.gradle.kts are LiteRT and its GPU artifact; no Qualcomm LiteRT delegate/runtime is declared. This is why the LiteRT NPU registry reports kLiteRtStatusErrorNotFound. The log then successfully registers GPU and creates OpenCL models. This message is not a model-operator error.

Two NPU/GPU experiments are viable:

1. Existing ONNX Runtime QNN path. The app already uses the onnxruntime-android-qnn artifact and has PaddleOcrSessionFactory routing. Export fixed-width/fixed-batch ONNX QDQ models and test HTP with INT8 weights and activations. QNN HTP requires quantized models and does not support dynamic shapes. Disable CPU EP fallback during validation so coverage failures cannot masquerade as HTP success. QNN GPU can consume FP16 ONNX directly, but it should be benchmarked against the already-monolithic LiteRT OpenCL graph.
2. LiteRT Qualcomm delegate. Add a compatible Qualcomm AI Engine Direct delegate and runtime, construct the LiteRT environment with the Qualcomm NPU provider, and validate the user-app HTP path on this exact OnePlus build. Match runtime, delegate, QAIRT, and device skeleton versions. The Qualcomm delegate is separate from both LiteRT’s OpenCL GPU accelerator and ONNX Runtime’s QNN EP.

An Accelerator.NPU enum or a registered provider is not proof that all model operations ran on HTP. Require provider telemetry, graph coverage, and no-CPU-fallback validation.

## Quantization and model surgery

Recommended order:

1. Fix model lifetime and scheduling first. Group a page’s line crops by width before invoking the LiteRT runner. Reuse compiled models across pages; if memory permits, retain the most common buckets or a bounded multi-entry cache. The immediate goal is to reduce the 11 observed graph creations on a page to at most one per used bucket, then stop paying those creates again on later pages.
2. Implement real microbatches. The current five models are batch-one. Export B=4 or B=8 variants for common buckets and feed same-width crops together, or use the existing ONNX batch executor where its provider supports the graph. The LiteRT branch currently serializes regardless of the requested batch size.
3. Remove avoidable head work. The upstream recognition graph ends with Softmax; CTC’s greedy class argmax is unchanged if the terminal Softmax is removed. Prototype logits output and either calibrate confidence from logit margins or add a GPU-side argmax/top-score output so the app does not read back millions of scores. Confirm LiteRT GPU support for the added reduction; GPU-only CompiledModel should reject unsupported graph surgery at creation.
4. Test static INT8 PTQ with per-channel weights and representative activation calibration. For HTP, start with W8A8 QDQ fixed-shape models. For Adreno, benchmark INT8 against the current FP16 graph; quantized operations are not automatically faster on every GPU kernel. Include Japanese manga pages with rare kanji, kana, ruby/furigana, vertical text, screentones, and multiple fonts. Gate on exact-line accuracy and CER by character-frequency band, not only aggregate accuracy.
5. If width 1600 remains above 50 ms after caching/batching/quantization, evaluate a smaller high-width model, overlapping line tiling, or a trained lower-rank classifier. The 2,245,200-weight CTC projection is 42.6% of Paddle’s weights; rank-32/64 factorization or structured pruning could reduce MACs, but both need fine-tuning and character-level accuracy validation. Do not prune the 18,710-class vocabulary casually; that is where rare Japanese-character recall is lost.

Sub-50 ms per line is plausible for warm 160–640 buckets given the Phase 6 4.29 ms 640 GPU result, but it is not a promise for full wrapper latency. The 1600 bucket needs its own warm model-plus-readback benchmark and likely requires quantization or reduced-width surgery. Current XNNPACK data show 1600 at 114.96 ms.

## Acceptance benchmark

Run two comparisons on the same OnePlus 12 and fixed manga pages:

- Model-level: identical pre-extracted crops, warmup 5, at least 30 runs per width and route. Record cold CompiledModel create time separately; record create, buffer write, run+readback, CTC, and full line latency. Report p50/p95, bucket distribution, actual accelerator/partition coverage, RSS/GC, CER, and line exact-match.
- Page-level: run the same source pages through both MangaOCR and PaddleOCR with identical detection/region inputs. Report total OCR wall time, number of original regions, number of Paddle line leaves, batches, compile count, and recognized text quality. This prevents a region-versus-line work-unit mismatch from being mistaken for model speed.

A primary success gate is fewer than one graph creation per bucket per process after warmup, no per-op CPU partition, and p95 under 50 ms for width buckets 160–640. Track 1600 separately until measured. Quantized candidates must stay within the agreed OCR accuracy/CER gate on the Japanese validation set.

## External references

- [LiteRT NPU delegate overview](https://ai.google.dev/edge/litert/android/npu)
- [Google AI Edge CompiledModel migration and profiling guidance](https://github.com/google-ai-edge/litert-samples/blob/main/skills/litert-compiled-model-migration/SKILL.md)
- [ONNX Runtime QNN Execution Provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- [Qualcomm Android TFLite/QNN sample guidance](https://github.com/qualcomm/qai-appbuilder/blob/main/samples/apps/android/README.md)
- [Qualcomm AI Hub model compilation targets](https://workbench.aihub.qualcomm.com/docs/hub/compile_examples.html)
