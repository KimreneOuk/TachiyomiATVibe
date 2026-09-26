# Model sources and licenses

`scripts/fetch_models.py` populates `app/src/main/assets/models/` from the upstream repositories pinned in [`scripts/models.manifest`](../scripts/models.manifest). The manifest records the expected output size and full SHA-256 for the 13 distributed files. Direct downloads are checked against their final asset hash. For converted files, the fetcher also checks the downloaded source file before running the pinned converter and checks the generated output afterward.

This repository does not commit standalone model files or offer them as separate model downloads. The former `models-v1` release distribution is retired. Build jobs fetch direct files or convert pinned upstream checkpoints into app assets; the resulting APK includes those model assets. Locally derived outputs are regenerated from upstream source files, not uploaded copies of the prior internal binaries.

The app still seeds fetched assets into its private files directory at runtime. This work changes only how source builds obtain the model files.

## Distributed model inventory

| File under `app/src/main/assets/models/` | Source and conversion | License and open points |
| --- | --- | --- |
| `detection/detector-v4-s_int8.onnx` | Byte-identical to [`ogkalu/comic-text-and-bubble-detector`](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/tree/16e8a622f91fabc6b5b65c96d32d1183f8843546); fetched directly. | The upstream model card declares Apache-2.0. |
| `detection/manga_panel_detector_int8.onnx` | Converted from [`leoxs22/manga-panel-detector-yolo26n`](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/tree/40a2854663d537563cfb95c370288a84c6505b9a) by [`convert_panel_detector.py`](../scripts/converters/convert_panel_detector.py). The script exports a fixed 640×640 ONNX graph, dynamically quantizes Conv/MatMul/Gemm weights to int8, and transposes YOLO26's `[1,6,8400]` result to the app's expected `[1,8400,6]` row layout. | AGPL-3.0. The Hugging Face model card labels the checkpoint Apache-2.0, while Ultralytics says its trained YOLO models are AGPL-3.0 by default; this repository treats the YOLO26-derived weights as AGPL-3.0. Attribution: leoxs22. [`convert_panel_detector.py`](../scripts/converters/convert_panel_detector.py), its shared helper, and the pinned dependencies are the corresponding conversion source. Build jobs fetch the checkpoint and convert it locally; the resulting APK includes the model asset. |
| `inpainting/aot.onnx` | Byte-identical to [`ogkalu/aot-inpainting`](https://huggingface.co/ogkalu/aot-inpainting/tree/42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189); fetched directly. Its model card describes it as a traced AOT-GAN model. | The upstream model card declares MIT and credits the AOT-GAN model trained for manga-image-translator. |
| `inpainting/aot-512.onnx` | Converted from the upstream [`aot.onnx`](https://huggingface.co/ogkalu/aot-inpainting/tree/42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189) by [`convert_aot_512.py`](../scripts/converters/convert_aot_512.py). The converter fixes `image`, `mask`, and `inpainted` to 512×512, runs ONNX shape inference, then `onnxslim.slim`. The old 61,469,172-byte Qualcomm Workbench export is retired; the generated output has a new hash pinned in the manifest. A local `AotCorpusGateTest` run against separately licensed internal fixtures (42 native pages and all four sub-512 pad fixtures) passed with zero failures. Those fixtures are not included in this public repository, so public CI skips the corpus-gated tests. On-device validation is pending. | Same MIT source model as `aot.onnx`. |
| `ocr/encoder.onnx` | Byte-identical to [`ogkalu/manga-ocr-mobile`](https://huggingface.co/ogkalu/manga-ocr-mobile/tree/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98); fetched directly. | The upstream mobile export declares Apache-2.0. It is the source of the split mobile graphs. The underlying model lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr), which declares Apache-2.0; see its [base model card](https://huggingface.co/kha-white/manga-ocr-base) for training-data details. |
| `ocr/decoder_init.onnx` | Byte-identical to the pinned `ogkalu/manga-ocr-mobile` repository; fetched directly. | Same source and license notes as `encoder.onnx`. |
| `ocr/decoder_step.onnx` | Byte-identical to the pinned `ogkalu/manga-ocr-mobile` repository; fetched directly. | Same source and license notes as `encoder.onnx`. |
| `ocr/vocab.txt` | Byte-identical to the pinned `ogkalu/manga-ocr-mobile` repository; fetched directly. | Same source and license notes as `encoder.onnx`. |
| `ocr/paddle-v6-small/inference.json` | Pinned [PP-OCRv6 small recognition ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513); fetched directly. | The model card declares Apache-2.0. |
| `ocr/paddle-v6-small/inference.onnx` | Byte-identical to the pinned PP-OCRv6 small recognition ONNX repository; fetched directly. | The model card declares Apache-2.0. |
| `ocr/paddle-v6-small/PP-OCRv6_small_rec.txt` | PaddleOCR's `ppocrv6_dict.txt` at the pinned [PaddleOCR source revision](https://github.com/PaddlePaddle/PaddleOCR/blob/b03f46425e8ff4442b268ce449e3eef758146cd4/ppocr/utils/dict/ppocrv6_dict.txt); fetched directly under the app's existing filename. | PaddleOCR declares Apache-2.0. |
| `ocr/paddle-v6-small/det/inference.onnx` | Pinned [PP-OCRv6 small detection ONNX repository](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/28fe5895c24fd108c19eb3e8479f4ab385fbfc62); fetched directly. | The model card declares Apache-2.0. |
| `segmentation/manga109_bubble_int8.onnx` | Converted from [`best.pt`](https://huggingface.co/huyvux3005/manga109-segmentation-bubble/tree/f9a4108c4955136a810e5e92207972f3fb3a65fd) by [`convert_bubble_segmenter.py`](../scripts/converters/convert_bubble_segmenter.py): Ultralytics ONNX export at 640×640, then ONNX Runtime dynamic int8 quantization. The output hash differs from the earlier locally derived ONNX file. | The model card declares Apache-2.0, while Ultralytics says its trained YOLO models are AGPL-3.0 by default; the terms applicable to this checkpoint need confirmation. The applicable Manga109 terms for trained model weights and this converted artifact also remain unresolved. |

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
