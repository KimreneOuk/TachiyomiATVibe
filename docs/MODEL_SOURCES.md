# Model sources and licenses

`scripts/fetch_models.py` populates `app/src/main/assets/models/` from the upstream repositories pinned in [`scripts/models.manifest`](../scripts/models.manifest). The manifest records the expected output size and full SHA-256 for the 13 distributed files. Direct downloads are checked against their final asset hash. For converted files, the fetcher also checks the downloaded source file before running the pinned converter and checks the generated output afterward.

This repository does not commit standalone model files or offer them as separate model downloads. The former `models-v1` release distribution is retired. Build jobs fetch direct files or convert pinned upstream checkpoints into app assets; the resulting APK includes those model assets. Locally derived outputs are regenerated from upstream source files, not uploaded copies of the prior internal binaries.

The app still seeds fetched assets into its private files directory at runtime. This work changes only how source builds obtain the model files.

## Distributed model inventory

| Model | Local path under `app/src/main/assets/models/` | Pinned upstream source URL | License | Derivation | Known caveats |
| --- | --- | --- | --- | --- | --- |
| `detector-v4-s_int8.onnx` | `detection/detector-v4-s_int8.onnx` | [Pinned ONNX file](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx) | Apache-2.0 declared by upstream model card. | Direct fetch; byte-identical. | None recorded. |
| `manga_panel_detector_int8.onnx` | `detection/manga_panel_detector_int8.onnx` | [Pinned checkpoint](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/resolve/40a2854663d537563cfb95c370288a84c6505b9a/manga_panel_detector_fp32.pt) | AGPL-3.0 under Ultralytics' trained YOLO licensing terms; the Hugging Face model card labels the repository Apache-2.0. | Converted by [`convert_panel_detector.py`](../scripts/converters/convert_panel_detector.py): 640×640 ONNX export, dynamic int8 quantization, and output transpose to the app's `[1,8400,6]` row layout. | Attributed to leoxs22. The model-card and Ultralytics license statements differ; this repository treats the derivative as AGPL-3.0. |
| `aot.onnx` | `inpainting/aot.onnx` | [Pinned ONNX file](https://huggingface.co/ogkalu/aot-inpainting/resolve/42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189/aot.onnx) | MIT declared by upstream model card. | Direct fetch; byte-identical. | Model card describes a traced AOT-GAN model and credits manga-image-translator. |
| `aot-512.onnx` | `inpainting/aot-512.onnx` | [Pinned source ONNX](https://huggingface.co/ogkalu/aot-inpainting/resolve/42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189/aot.onnx) | Same MIT source model as `aot.onnx`. | Converted by [`convert_aot_512.py`](../scripts/converters/convert_aot_512.py): fixed 512×512 input/output shapes, ONNX shape inference, then `onnxslim.slim`. | The prior 61,469,172-byte Qualcomm Workbench export is retired. Local corpus gates passed using separate fixtures not included in this repository; on-device validation is pending. |
| `encoder.onnx` | `ocr/encoder.onnx` | [Pinned encoder file](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/encoder.onnx) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Split mobile export from `ogkalu/manga-ocr-mobile`; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr) (Apache-2.0). |
| `decoder_init.onnx` | `ocr/decoder_init.onnx` | [Pinned decoder file](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/decoder_init.onnx) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Split mobile export; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr). |
| `decoder_step.onnx` | `ocr/decoder_step.onnx` | [Pinned decoder file](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/decoder_step.onnx) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Split mobile export; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr). |
| `vocab.txt` | `ocr/vocab.txt` | [Pinned vocabulary](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/vocab.txt) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Part of the split `ogkalu/manga-ocr-mobile` export; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr). |
| `inference.json` (PP-OCRv6 recognition config) | `ocr/paddle-v6-small/inference.json` | [Pinned configuration](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.json) | Apache-2.0 declared by upstream model card. | Direct fetch. | PaddleOCR PP-OCRv6 small recognition configuration. |
| `inference.onnx` (PP-OCRv6 recognition) | `ocr/paddle-v6-small/inference.onnx` | [Pinned ONNX file](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.onnx) | Apache-2.0 declared by upstream model card. | Direct fetch; byte-identical. | PaddleOCR PP-OCRv6 small recognition model. |
| `PP-OCRv6_small_rec.txt` | `ocr/paddle-v6-small/PP-OCRv6_small_rec.txt` | [Pinned PaddleOCR dictionary](https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/b03f46425e8ff4442b268ce449e3eef758146cd4/ppocr/utils/dict/ppocrv6_dict.txt) | Apache-2.0 under PaddleOCR. | Direct fetch, saved at the app's existing filename. | PaddleOCR dictionary source file is named `ppocrv6_dict.txt`. |
| `inference.onnx` (PP-OCRv6 detector) | `ocr/paddle-v6-small/det/inference.onnx` | [Pinned ONNX file](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx) | Apache-2.0 declared by upstream model card. | Direct fetch; byte-identical. | PaddleOCR PP-OCRv6 small detection model. |
| `manga109_bubble_int8.onnx` | `segmentation/manga109_bubble_int8.onnx` | [Pinned checkpoint](https://huggingface.co/huyvux3005/manga109-segmentation-bubble/resolve/f9a4108c4955136a810e5e92207972f3fb3a65fd/best.pt) | Apache-2.0 declared by model card; Ultralytics trained YOLO terms may apply AGPL-3.0, applicability unconfirmed. | Converted by [`convert_bubble_segmenter.py`](../scripts/converters/convert_bubble_segmenter.py): 640×640 ONNX export and ONNX Runtime dynamic int8 quantization. | Manga109 dataset terms for the trained weights and converted artifact remain unresolved. |

The panel detector is derived from and attributed to [leoxs22/manga-panel-detector-yolo26n](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n). The source checkpoint and converted model are treated as AGPL-3.0 under Ultralytics' [published YOLO licensing terms](https://www.ultralytics.com/license), which state that trained YOLO models are AGPL-3.0 by default. The Hugging Face model card currently labels the repository Apache-2.0; the notice records the AGPL determination used for this YOLO26 derivative. The converter and pinned dependency list are the corresponding source for the converted artifact. Build machines fetch the source checkpoint and convert it locally; the generated model is packaged into the APK, but the repository does not offer a standalone model download.

## Conversion recipes

Install the pinned converter toolchain once:

```sh
python3 -m pip install -r scripts/converters/requirements.txt
```

Each converter accepts a verified source file and an output path:

```sh
python3 scripts/converters/convert_aot_512.py --source aot.onnx --output aot-512.onnx
python3 scripts/converters/convert_panel_detector.py --source manga_panel_detector_fp32.pt --output manga_panel_detector_int8.onnx
python3 scripts/converters/convert_bubble_segmenter.py --source best.pt --output manga109_bubble_int8.onnx
```

The panel and bubble converters use Ultralytics ONNX export with fixed 640×640 inputs and ONNX Runtime dynamic int8 weight quantization. The panel converter adds a channels-last transpose because the app detector consumes rows shaped `[N, 4 + class_count]`. Both are reproducible replacement recipes, not claims that the earlier local ONNX files can be reproduced byte-for-byte. The AOT converter applies fixed 512×512 shapes before shape inference and slimming; it replaces the prior Qualcomm Workbench artifact with an output generated from the upstream dynamic graph. Converter and runtime versions are pinned in [`scripts/converters/requirements.txt`](../scripts/converters/requirements.txt), and the manifest pins each generated output hash.

The internal Manga109 segmenter tooling documented a separate static calibrated quantization path that used local manga pages and emitted a different output. Those calibration images are not part of this public repository. The public conversion therefore uses the source `.pt` file and the dynamic int8 recipe above.

## License notes

- PaddleOCR and the PP-OCRv6 model cards declare Apache-2.0.
- `ogkalu/manga-ocr-mobile` declares Apache-2.0; `kha-white/manga-ocr` is the underlying model lineage and also declares Apache-2.0.
- `ogkalu/aot-inpainting` declares MIT.
- The panel detector is treated as AGPL-3.0 based on Ultralytics' published licensing terms for trained YOLO models; the Hugging Face card metadata says Apache-2.0, so the distinction is recorded above.
- `huyvux3005/manga109-segmentation-bubble` declares Apache-2.0, but its Ultralytics YOLO licensing status and the Manga109 dataset terms applicable to the trained weights remain unresolved.

These source declarations do not determine the legal status of other repository code or settle dataset-rights questions. Review the linked model cards and licenses before redistribution.
