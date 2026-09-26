# Model sources and licenses

The app's runtime model files are downloaded into `app/src/main/assets/models/` by `scripts/fetch_models.py`. `scripts/models.manifest` pins each release asset's byte size and full SHA-256. The fetcher uses only the `models-v1` GitHub release assets; the upstream links below document provenance and are not fallback download sources.

The runtime continues copying these assets into the app's private files directory. This change only changes how source builds obtain the files.

## Per-file inventory

| File under `app/src/main/assets/models/` | Origin and conversion evidence | License and open points |
| --- | --- | --- |
| `detection/detector-v4-s_int8.onnx` | RT-DETR text detector, int8-quantized for CPU inference (the graph uses `DynamicQuantizeLinear`, `ConvInteger`, and `MatMulInteger`). The source checkpoint and exact quantization invocation were not retained. | Unknown for this exact artifact. No verified source checkpoint or model license is recorded. |
| `detection/manga_panel_detector_int8.onnx` | YOLO26-nano panel detector, int8-quantized for CPU inference (same operator family as the text detector). The source checkpoint and exact invocation were not retained. | Unknown for this exact artifact. No verified source checkpoint or model license is recorded. |
| `inpainting/aot.onnx` | Dynamic AOT-GAN graph. The exact checkpoint URL and export command were not retained. | The original [AOT-GAN implementation](https://github.com/researchmm/AOT-GAN-for-Inpainting/blob/master/LICENSE) is Apache-2.0. That does not by itself identify the license of this specific exported checkpoint. |
| `inpainting/aot-512.onnx` | Statically shaped 512x512 AOT graph, produced by a Qualcomm AI Hub Workbench export (build `aihub-2026.07.31.1`). An earlier internal conversion via onnxslim shape-inference and slimming produced a different 22,854,564-byte graph and does not reproduce this file. The exact Workbench export command and job URL were not retained. | The [Qualcomm model card](https://huggingface.co/qualcomm/AOT-GAN) labels its model MIT and identifies CelebA-HQ weights; the original implementation repository is Apache-2.0. Confirm the terms attached to the exact Workbench export before redistributing it. |
| `ocr/encoder.onnx` | Manga OCR encoder split for mobile inference. The source checkpoint revision and ONNX export command were not retained. | The [manga-ocr code](https://github.com/kha-white/manga-ocr) and [model card](https://huggingface.co/kha-white/manga-ocr-base) declare Apache-2.0. The model card identifies Manga109-s and CC-100 training data; retain dataset attribution and verify the applicable terms for this precise model export. |
| `ocr/decoder_init.onnx` | Manga OCR decoder initialization graph. The source checkpoint revision and ONNX export command were not retained. | Same Manga OCR license and training-data notes as the encoder above. |
| `ocr/decoder_step.onnx` | Manga OCR autoregressive decoder-step graph. The source checkpoint revision and ONNX export command were not retained. | Same Manga OCR license and training-data notes as the encoder above. |
| `ocr/vocab.txt` | Manga OCR vocabulary exported with the split ONNX graphs. The exact tokenizer revision and extraction command were not retained. | Same Manga OCR license and training-data notes as the encoder above. |
| `ocr/paddle-v6-small/inference.json` | Official [PaddlePaddle PP-OCRv6 small recognition ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513), pinned in the manifest. | The model card declares Apache-2.0. |
| `ocr/paddle-v6-small/inference.onnx` | Official [PaddlePaddle PP-OCRv6 small recognition ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513), pinned in the manifest. The release asset is named `paddle-v6-small-rec-inference.onnx` to distinguish it from the detection file. | The model card declares Apache-2.0. |
| `ocr/paddle-v6-small/PP-OCRv6_small_rec.txt` | PaddleOCR's `ppocrv6_dict.txt` at pinned commit [`b03f464`](https://github.com/PaddlePaddle/PaddleOCR/blob/b03f46425e8ff4442b268ce449e3eef758146cd4/ppocr/utils/dict/ppocrv6_dict.txt); distributed in the app under its existing filename. | PaddleOCR is Apache-2.0. |
| `ocr/paddle-v6-small/det/inference.onnx` | Official [PaddlePaddle PP-OCRv6 small detection ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/28fe5895c24fd108c19eb3e8479f4ab385fbfc62), pinned in the manifest. The release asset is named `paddle-v6-small-det-inference.onnx` because the recognition model has the same destination basename. | The model card declares Apache-2.0. |
| `segmentation/manga109_bubble_int8.onnx` | Bubble segmenter (YOLO11n per the upstream model card, trained using MS92/MangaSegmentation and Manga109). An alternate calibrated int8 export pipeline used during development wrote a different filename (`best_int8_manga.onnx`) and does not reproduce this deployed file. Exact export lineage is unresolved. | The upstream [model card](https://huggingface.co/huyvux3005/manga109-segmentation-bubble) declares Apache-2.0, but names Manga109 (not Manga109-s) as training data. The [official Manga109 terms](https://manga109.github.io/manga109-project-website/en/index.html) restrict the full dataset to academic, non-commercial use and forbid dataset redistribution. The dataset variant and rights for this exact derived model have not been verified; its redistribution status remains unresolved. |

## Conversion recipes and limits

No reproducible quantization command can be given for the text and panel detectors: their graphs contain ORT dynamic integer quantization (`DynamicQuantizeLinear`, `ConvInteger`, and `MatMulInteger`), but the original FP32 checkpoint URLs and exact ORT arguments were not retained.

An earlier static-shape conversion of `aot.onnx` (ONNX shape inference plus `onnxslim.slim` on the 512x512-shaped graph) produced a 22,854,564-byte predecessor of `aot-512.onnx`. The deployed file later replaced it with the 61,469,172-byte Qualcomm AI Hub Workbench export. The old recipe is historical provenance only; it does not reproduce the manifest's current `aot-512.onnx` hash.

The Manga109 segmenter's alternate calibrated export pipeline wrote a different filename and explicitly did not overwrite the deployed artifact, so no reproducible command exists for `manga109_bubble_int8.onnx`; none is inferred here.

The exact Manga OCR checkpoint revision, model split/export command, and vocab extraction command were not retained. Pinned artifact hashes prove which bytes are distributed, not how those bytes were produced.

## References

- [PaddleOCR LICENSE](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE) and the pinned model repositories linked in the table above.
- [manga-ocr](https://github.com/kha-white/manga-ocr) and its [model card](https://huggingface.co/kha-white/manga-ocr-base).
- [AOT-GAN for Inpainting](https://github.com/researchmm/AOT-GAN-for-Inpainting) and the [Qualcomm AOT-GAN model card](https://huggingface.co/qualcomm/AOT-GAN).
- [Manga109 dataset terms](https://manga109.github.io/manga109-project-website/en/index.html).
