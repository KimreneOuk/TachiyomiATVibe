# Lightweight MangaOCR alternatives

Date accessed: 2026-09-18

## Scope and evidence rules

This is a focused comparison of manga-specialized OCR recognizers and deployment variants. Generic OCR engines are intentionally excluded even when they are smaller. “Published” means a project/model-card claim or benchmark; “local measured” means inspection of the artifacts in this worktree; “downstream measured” means a result from a separate implementation. Published numbers are not treated as reproduced results.

The raw structured comparison is in [`research/results/alternatives_comparison.json`](../results/alternatives_comparison.json). The inspected `ogkalu/manga-ocr-mobile` artifact is summarized in [`research/results/onnx_inspection.json`](../results/onnx_inspection.json).

## Executive conclusion

The best immediate candidate for a controlled Android experiment is `ogkalu/manga-ocr-mobile`: its three ONNX graphs total 64.76 MB decimal locally, the encoder is a RepViT-style graph, and the repository is explicitly Apache-2.0/mobile-oriented. That is a packaging/runtime advantage, not an accuracy win: neither the model card nor the repository publishes a quality benchmark or phone latency, and the inspected graphs are fixed batch-1 with an autoregressive decoder.

The best evidence for a production-shaped Android path is the 48px CTC reference. Its source explicitly batches up to 16 crops, and the Yakuyomi engine reports a downstream Snapdragon 8 Gen 3 measurement after int8 quantization (about 3.6x faster than FP32 with 96.7% CTC parity). It is unsuitable as an automatic replacement without a license review (GPL-3.0) and a quality gate: the upstream issue tracker records the known qualitative tradeoff that CTC is faster but less accurate than the non-CTC 48px model.

For quality/size exploration, Baberu OCR is the most concrete newer option: the smallest published ONNX tier is 121 MB (int4 vision + int8 decoder), it is manga-crop-specific and multilingual, and its model card publishes held-out CER/exact-match results. It still needs a detector crop and Android delegate validation. Hayai OCR v2.1 reports even better author-side JMangaBench numbers and a 54.22 FPS L4 result, but its public artifact is F32/custom Transformers code with NaFlex and no ONNX/TFLite export, so it is a future conversion experiment rather than a drop-in Android model.

`dhleong/manga-ocr-android` is a useful packaging reference because it publishes quantized ONNX and a 113 MB TFLite OCR artifact. It has no reported accuracy or device latency, so it should be treated as a deployment lead, not evidence that quantization preserves quality. `l0wgear/manga-ocr-2025-onnx` is a reasonable 140.4 MB quality/reference export, but it is larger and less mobile-specific.

The GitHub `manga-ocr-torchless` project was also checked. It is a useful ONNX Runtime distribution wrapper around the canonical Manga OCR export, not a new lightweight architecture: its README says the downloaded model is about 400 MB, claims 100% character-level parity on the original test suite, and advertises desktop CUDA/DirectML/CoreML/OpenVINO providers. It removes the PyTorch dependency but does not solve Android model size or provide an Android benchmark, so it is not scored as a separate candidate below. See [manga-ocr-torchless](https://github.com/liksunrice/manga-ocr-torchless).

## Comparison

| Candidate | Architecture and footprint | Runtime / batch behavior | Quality evidence | GPU/NPU and Android fit | License / activity | Assessment |
|---|---|---|---|---|---|---|
| **ogkalu/manga-ocr-mobile** | RepViT-style encoder + autoregressive decoder; **64.76 MB local measured** (17.1 + 24.9 + 22.8 MB graphs) | ONNX; encoder input `1x3x224x224`, decoder batch 1, KV-cache step decode (**local measured**) | No public accuracy result found | ORT-compatible in principle; no delegate measurement; smallest immediate candidate | Apache-2.0; HF page shows 10 commits | Run a quality/latency bake-off first, without changing the production model |
| **dhleong/manga-ocr-android** | Canonical Manga OCR converted/quantized; **118.9 MB quantized ONNX**, **113 MB TFLite** | Quantized ONNX or LiteRT/TFLite; batching not documented | No public accuracy result found | Best packaging for LiteRT/NNAPI exploration; delegate coverage unknown | Separate model license not stated; 13 commits | Good Android conversion reference, weak evidence base |
| **l0wgear/manga-ocr-2025-onnx** | Vision Encoder-Decoder; **140.4 MB ONNX** | Optimum/ONNX Runtime autoregressive generation; no mobile batch contract | No numeric export benchmark found | Portable ORT baseline, no mobile delegate evidence | Export license not stated; 4 commits, 3,361 monthly downloads | Keep as a quality/reference baseline, not first mobile target |
| **Baberu OCR** | 115M DINOv2 + custom 6-layer decoder, character-level, ja/zh/en; **121 MB smallest ONNX tier** | ONNX KV-cache loop; one bubble crop, detector required | Author-reported held-out Manga109-v2026 lCER **0.0345**, nCER **0.0871**; zh/en held-out exact match **0.85/0.82** | No Android artifact/delegate result; int4 support is a risk for mobile delegates | Apache-2.0; 11 commits | Best newer quality/size candidate if multilingual crop OCR is in scope |
| **Hayai OCR v2.1** | ~150M SigLIP2 NaFlex + 12-layer custom decoder; ~300 MB FP16 VRAM | Custom Transformers; crop-level, author reports batching gains | Author-reported JMangaBench CER **3.225%**, exact match **79.671%**; 54.22 FPS L4 | CUDA demonstrated; custom code/NaFlex and no mobile export make NPU uncertain | Apache-2.0; active HF card, 2,371 monthly downloads | Strong research candidate, not Android-ready |
| **manga-image-translator 48px CTC** *(reference)* | ResNet + 3 Transformer encoders + CTC; **165 MB ONNX export** | Explicit source batching up to 16; greedy CTC | No numeric upstream benchmark; issue reports faster/lower accuracy; Yakuyomi downstream: **3.6x ARM speedup**, **96.7% parity** after int8 | Proven in an arm64 Android engine; CPU MLAS was used because XNNPACK miscomputed it | GPL-3.0; mature upstream, ONNX export verifies max diff 0.002 | Benchmark/reference only unless GPL and accuracy tradeoff are accepted |

## Important interpretation details

* The `ogkalu` numbers are not a parameter-count claim. They are byte sizes and graph metadata measured from the checked-out files. `encoder.onnx` has 4,111,596 parameter elements and outputs `1x196x256`; the decoder graphs expose 9,415 logits and fixed batch 1. See the raw inspection JSON.
* The 48px CTC model is not “better because it is CTC.” CTC makes greedy, parallel time-step decoding and explicit crop batching attractive, but the upstream issue evidence says the speed comes with recognition failures on some lines. Treat CTC as a throughput baseline with a regression test, not a quality recommendation.
* Baberu and Hayai are crop recognizers, not full-page OCR systems. They still need the existing detector/region pipeline, and their benchmarks do not answer whether text detection, grouping, or reader rendering regresses.
* Quantized file size is not peak working memory. Decoder KV caches, multiple crop buffers, and ORT/LiteRT arenas must be measured on target devices.

## Android/NPU implications

1. **First experiment: `ogkalu` ONNX on CPU.** Measure cold load, warm single-crop latency, peak native heap, and a 1/4/8/16-crop queue. Because the exported graphs are static batch-1, do not assume batching until a re-export or a multi-session strategy is tested.
2. **Delegate experiment: `dhleong` TFLite.** Validate whether the OCR TFLite graph compiles on CPU, NNAPI, and GPU delegates and record fallback operators. Absence of an accuracy report means parity must be checked against the current recognizer on the manga corpus.
3. **Quality experiment: Baberu 121 MB tier.** Use the same detector regions and compare CER, empty/short-region behavior, SFX, vertical text, and mixed Japanese/English. The model card's held-out numbers are encouraging but are not a substitute for the app's corpus.
4. **Reference-only: CTC.** Reproduce the downstream int8/FP32 parity and latency on the target Snapdragon class, then perform a text-quality gate. Keep GPL-3.0 obligations visible in any product decision.

## Sources (all accessed 2026-09-18)

* [ogkalu/manga-ocr-mobile model files](https://huggingface.co/ogkalu/manga-ocr-mobile/tree/main) and [bluolightning project page](https://github.com/bluolightning/manga-ocr-mobile)
* [dhleong/manga-ocr-android model files](https://huggingface.co/dhleong/manga-ocr-android/tree/main)
* [l0wgear/manga-ocr-2025-onnx model card](https://huggingface.co/l0wgear/manga-ocr-2025-onnx) and [file listing](https://huggingface.co/l0wgear/manga-ocr-2025-onnx/tree/main)
* [Baberu OCR model card and benchmarks](https://huggingface.co/genshiai-daichi/baberu-ocr), [ONNX files](https://huggingface.co/genshiai-daichi/baberu-ocr/tree/main/onnx), and [quantization notes](https://huggingface.co/genshiai-daichi/baberu-ocr/blob/main/quantization-results.md)
* [Hayai OCR v2.1 model card and benchmarks](https://huggingface.co/JustANormalTinkerer/hayai-ocr-v2) and [Python package](https://pypi.org/project/hayai-ocr/)
* [manga-image-translator 48px CTC source](https://raw.githubusercontent.com/zyddnys/manga-image-translator/main/manga_translator/ocr/model_48px_ctc.py), [upstream accuracy discussion](https://github.com/zyddnys/manga-image-translator/issues/570), [ONNX export](https://huggingface.co/Skepsun/manga-translator-ui-onnx/tree/main), and [Yakuyomi Android measurements](https://github.com/joyeli/yakuyomi-engine)
* [canonical Manga OCR repository](https://github.com/kha-white/manga-ocr)

## Recommendation

Do not replace the current recognizer yet. Run a gated, corpus-backed bake-off with `ogkalu` as the first low-footprint candidate, `dhleong` as the LiteRT packaging reference, Baberu as the quality/multilingual candidate, and 48px CTC as the throughput baseline. Promote only a candidate that passes both accuracy and Android memory/latency gates on normal manga; specialized crop behavior must not regress the existing reader path.
