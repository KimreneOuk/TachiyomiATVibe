# Model sources and licenses

The app's runtime model files are downloaded into `app/src/main/assets/models/` by `scripts/fetch_models.py`. `scripts/models.manifest` pins each release asset's byte size and full SHA-256. The fetcher uses only the `models-v1` GitHub release assets; the upstream links below document provenance and are not fallback download sources.

The runtime continues copying these assets into the app's private files directory. This change only changes how source builds obtain the files.

## Per-file inventory

| File under `app/src/main/assets/models/` | Origin and conversion evidence | License and open points |
| --- | --- | --- |
| `detection/detector-v4-s_int8.onnx` | RT-DETR text detector. Internal commit `cc75e41`; T922's `engineering/npu-gpu-hardware-acceleration.md` §4 identifies ORT CPU dynamic integer quantization, but the source checkpoint and exact quantization invocation were not retained. | Unknown for this exact artifact. No verified source checkpoint or model license is recorded. |
| `detection/manga_panel_detector_int8.onnx` | YOLO26-nano panel detector in T937's translation pipeline inventory; internal commit `1a6f082`. T922 §4 identifies ORT CPU dynamic integer quantization, but the source checkpoint and exact invocation were not retained. | Unknown for this exact artifact. No verified source checkpoint or model license is recorded. |
| `inpainting/aot.onnx` | Dynamic AOT-GAN graph introduced in internal commit `cc75e41`. The exact checkpoint URL and export command were not retained. | The original [AOT-GAN implementation](https://github.com/researchmm/AOT-GAN-for-Inpainting/blob/master/LICENSE) is Apache-2.0. That does not by itself identify the license of this specific exported checkpoint. |
| `inpainting/aot-512.onnx` | The current 61,469,172-byte file replaced the earlier static model in internal commit `5563bdb`. T922 §3 identifies Qualcomm AI Hub Workbench build `aihub-2026.07.31.1`. The onnxslim conversion report at internal `tools/aot_conversion/REPORT.md` (commit `24e6411`) documents a different 22,854,564-byte output and does not reproduce this file. The exact Workbench export command and job URL were not retained. | The [Qualcomm model card](https://huggingface.co/qualcomm/AOT-GAN) labels its model MIT and identifies CelebA-HQ weights; the original implementation repository is Apache-2.0. Confirm the terms attached to the exact internal Workbench export before redistributing it. |
| `ocr/encoder.onnx` | Manga OCR encoder split for mobile inference. Internal commit `cc75e41`; T927 `DESIGN.md` §4.1 records this exact artifact hash. The source checkpoint revision and ONNX export command were not retained. | The [manga-ocr code](https://github.com/kha-white/manga-ocr) and [model card](https://huggingface.co/kha-white/manga-ocr-base) declare Apache-2.0. The model card identifies Manga109-s and CC-100 training data; retain dataset attribution and verify the applicable terms for this precise model export. |
| `ocr/decoder_init.onnx` | Manga OCR decoder initialization graph. Internal commit `cc75e41`; T927 `DESIGN.md` §4.1 records this exact artifact hash. The source checkpoint revision and ONNX export command were not retained. | Same Manga OCR license and training-data notes as the encoder above. |
| `ocr/decoder_step.onnx` | Manga OCR autoregressive decoder-step graph. Internal commit `cc75e41`; T927 `DESIGN.md` §4.1 records this exact artifact hash. The source checkpoint revision and ONNX export command were not retained. | Same Manga OCR license and training-data notes as the encoder above. |
| `ocr/vocab.txt` | Manga OCR vocabulary exported with the split ONNX graphs. Internal commit `cc75e41`; the exact tokenizer revision and extraction command were not retained. | Same Manga OCR license and training-data notes as the encoder above. |
| `ocr/paddle-v6-small/inference.json` | Official [PaddlePaddle PP-OCRv6 small recognition ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513), pinned in the manifest. | The model card declares Apache-2.0. |
| `ocr/paddle-v6-small/inference.onnx` | Official [PaddlePaddle PP-OCRv6 small recognition ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513), pinned in the manifest. The release asset is named `paddle-v6-small-rec-inference.onnx` to distinguish it from the detection file. | The model card declares Apache-2.0. |
| `ocr/paddle-v6-small/PP-OCRv6_small_rec.txt` | PaddleOCR's `ppocrv6_dict.txt` at pinned commit [`b03f464`](https://github.com/PaddlePaddle/PaddleOCR/blob/b03f46425e8ff4442b268ce449e3eef758146cd4/ppocr/utils/dict/ppocrv6_dict.txt); distributed in the app under its existing filename. | PaddleOCR is Apache-2.0. |
| `ocr/paddle-v6-small/det/inference.onnx` | Official [PaddlePaddle PP-OCRv6 small detection ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/28fe5895c24fd108c19eb3e8479f4ab385fbfc62), pinned in the manifest. The release asset is named `paddle-v6-small-det-inference.onnx` because the recognition model has the same destination basename. | The model card declares Apache-2.0. |
| `segmentation/manga109_bubble_int8.onnx` | Bubble segmenter added in internal commit `1a6f082`. The internal [Hugging Face model card](https://huggingface.co/huyvux3005/manga109-segmentation-bubble) describes a YOLO11n model trained using MS92/MangaSegmentation and Manga109. Internal `tools/requantize_seg_int8.py` describes an alternate calibrated output named `best_int8_manga.onnx`; it does not reproduce this deployed file. Exact export lineage is unresolved. | The upstream model card declares Apache-2.0, but names Manga109 (not Manga109-s) as training data. The [official Manga109 terms](https://manga109.github.io/manga109-project-website/en/index.html) restrict the full dataset to academic, non-commercial use and forbid dataset redistribution. The dataset variant and rights for this exact derived model have not been verified; its redistribution status remains unresolved. |

## Conversion recipes and limits

The internal T922 report states that the text and panel detector ONNX files contain ORT dynamic integer quantization (`DynamicQuantizeLinear`, `ConvInteger`, and `MatMulInteger`). It does not record the original FP32 checkpoint URLs or exact ORT arguments, so no reproducible quantization command can be given for those current artifacts.

The current `aot-512.onnx` is the later Qualcomm AI Hub Workbench export recorded by T922. Internal commit `24e6411` and `tools/aot_conversion/convert_aot_512.py` document the earlier conversion command:

```sh
pip install onnxslim onnx onnxruntime numpy
python tools/aot_conversion/convert_aot_512.py
```

That command statically shapes the then-current `aot.onnx`, runs ONNX shape inference and `onnxslim.slim`, and writes a 22,854,564-byte graph. Internal commit `5563bdb` replaced that output with the 61,469,172-byte Qualcomm export. The old recipe is retained as historical provenance only; it does not reproduce the manifest's current `aot-512.onnx` hash.

The internal Manga109 segmenter tooling records an alternate calibrated export pipeline, but writes a different filename and explicitly does not overwrite the deployed artifact. The checked-out records do not establish a reproducible command for `manga109_bubble_int8.onnx`; no command is inferred here.

The exact Manga OCR checkpoint revision, model split/export command, and vocab extraction command were not retained in the internal reports reviewed for this change. T927's pinned artifact hashes prove which bytes were used in its later analysis, not how those bytes were produced.

## References

- Internal model inventory and public-hosting record: `Plan/active/2026-09-25_T938_public-hosting-readiness/model-inventory.tsv` and `Plan/active/2026-09-25_T938_public-hosting-readiness/team/01-licensing/report.md`.
- Internal detector and AOT notes: `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/npu-gpu-hardware-acceleration.md`.
- Internal Manga OCR hashes: `Plan/active/2026-09-12_T927_mangaocr-batching-desktop-lab/DESIGN.md` §4.1.
- Internal segmenter export investigation: `tools/requantize_seg_int8.py` and commit `1a6f082`.
- PaddleOCR upstream license: [PaddleOCR LICENSE](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE).
