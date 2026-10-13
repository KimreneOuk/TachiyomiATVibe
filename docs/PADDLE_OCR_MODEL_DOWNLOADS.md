# Optimized PaddleOCR model downloads

The `paddle-ocr-litert-v1` GitHub release contains the exact optimized FP16-weight LiteRT files used by the on-device PaddleOCR GPU/CPU path. `scripts/fetch_models.py` downloads them before Gradle packaging and verifies every size and SHA-256 against `scripts/models.manifest`. The model files remain outside Git history.

```sh
python3 scripts/fetch_models.py --group paddle aot lama
```

**For a fresh runnable APK, use `python3 scripts/fetch_models.py` without a group** to provision the complete 19-file build inventory. OCR initialization also requires the baseline page detector, MangaOCR encoder/decoders, and vocabulary; those dependencies are outside the Paddle/AOT/LaMa subset. The group command is for updating those three families in an already provisioned checkout or inspecting/downloading the binaries separately. It does not need the ONNX conversion dependencies because those families use verified direct downloads. On Windows, substitute `py -3` for `python3`.

All Paddle LiteRT files are installed under `app/src/main/assets/models/ocr/paddle-v6-small/litert/`. The group also includes the original manga-tuned ONNX recognizer, detector, and dictionary for fallback. AOT and Manga LaMa retain their separately verified [inpainting release downloads](INPAINTING_MODEL_DOWNLOADS.md).

| Release asset | Bytes | SHA-256 |
| --- | ---: | --- |
| manga_det_960_fp16.tflite | 945516 | b724fe2e1a854620f1e685f0988264cbd1e4953281c55c78f58ae96d69888aff |
| manga_rec_160_fp16.tflite | 10619924 | 31aa784492b265c6e887c41e37bfd0e4dba542184ab3498fbc61e587d8671bda |
| manga_rec_320_fp16.tflite | 10620732 | 38c845de888421b3a017e510e7ae23ca36a61c5642d3906a010e6cbbe2926add |
| manga_rec_480_fp16.tflite | 10620120 | 6ab850de89c4dd0865e728d8cb1974cfb08a712e21fa9d53fcea41d1bc48b0bb |
| manga_rec_640_fp16.tflite | 10620684 | a2667f577bf9259f89acdf73fac552e92341ea655b5f767a1dae94dedfe8bc15 |
| manga_rec_1600_fp16.tflite | 10620216 | 8200c7f97f0d63b321a29c737337b8a564540689e13f37bf041c983a7bc07e2d |
| PP-OCRv6_small_rec.txt | 74947 | b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d |

The assets and `SHA256SUMS.txt` are available from [the PaddleOCR LiteRT release](https://github.com/KimreneOuk/TachiyomiATVibe/releases/tag/paddle-ocr-litert-v1).

## Provenance and changes

These are locally converted derivatives of the manga-tuned [Kellenok/PP-OCRv6_manga](https://huggingface.co/Kellenok/PP-OCRv6_manga) v0.2 FP16 ONNX recognizer and detector already recorded in the manifest. PaddleOCR and the upstream model cards declare Apache-2.0; see [third-party notices](../THIRD_PARTY_NOTICES.md). The character dictionary is byte-identical to the ONNX-path dictionary.

The optimized graph represents attention using tensors of rank at most four, replaces unsupported clipping patterns with equivalent supported operators, fuses GELU, and separates detector transposed-convolution bias to use a supported operator version. These are the repaired GPU graphs, not the earlier exports that produced multiple GPU/CPU partitions. The model interface and text vocabulary are retained; no retraining was performed.

The recognizer has a fixed `[1,48,width,3]` NHWC input and `[1,width/8,18710]` CTC output. The detector has a fixed `[1,960,960,3]` NHWC input and `[1,1,960,960]` probability-map output. Every bucket is B1; these files do not enable true LiteRT batching. GPU support and page latency still depend on the device and runtime lifecycle. CPU selection uses LiteRT XNNPACK with four threads.
