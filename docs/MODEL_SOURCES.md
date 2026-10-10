# Model sources and licenses

[`scripts/fetch_models.py`](../scripts/fetch_models.py) populates `app/src/main/assets/models/` from upstream repositories pinned in [`scripts/models.manifest`](../scripts/models.manifest). The manifest records the relative asset path, expected size, and full SHA-256 for all 13 distributed files. Direct downloads are checked against their final asset hash. For converted files, the fetcher checks the downloaded source before running the converter and checks the generated output afterward.

This repository does not commit model binaries or offer them as separate model downloads. The former `models-v1` release distribution is retired. The current manifest describes 199,479,458 bytes of model assets (about 190.2 MiB). CI fetches or converts the pinned sources before Gradle builds the APK, which bundles the resulting assets. Locally derived outputs are regenerated from upstream source files, not uploaded copies of prior internal binaries.

## Provisioning and on-device storage

Provisioning is a build-time step, not an app-side model download. From the repository root, install the pinned conversion toolchain and fetch the assets:

```sh
python3 -m pip install -r scripts/converters/requirements.txt
python3 scripts/fetch_models.py
```

The fetcher writes each manifest entry to `app/src/main/assets/models/<path>`. Existing files are skipped only when both their size and SHA-256 match the manifest. Direct-download entries are verified after download; entries with a converter first verify the upstream checkpoint, run the listed converter, and verify the converted output. `python3 scripts/fetch_models.py --clean` removes only files listed in the manifest. The pull-request and push build workflows run this provisioning step before Gradle packaging.

At runtime, `OnnxModelStore.ensureModels()` copies bundled assets into the app's private `<noBackupFilesDir>/tachiyomiat-models/` directory. For example, `models/inpainting/lama-manga.onnx` becomes `lama-manga.onnx` there, and the AOT files become `aot.onnx` and `aot-512.onnx`. The store does not contact the model hosts. It stamps each deployed copy with its asset path, bundled-model generation, and a SHA-256 computed from the packaged asset; cached bytes are checked against that stamp and recopied from the APK assets if the stamp is stale or the cached digest fails. The cache digest is compared with the saved stamp, not the manifest on each launch, so maintainers must bump the bundled-model generation when changing bytes at an existing asset path; otherwise an intact older cache can remain in use. Manifest verification happens in the build-time fetcher, while runtime verification protects the deployed cache.

## Reader settings

`FAST` uses classical inpainting. `BALANCE` keeps bubbles on the classical path and uses the selected neural model for free text when available; `QUALITY` uses the selected neural model for both region types when available. The **Neural Model** setting offers LaMa Manga (recommended and the default) and AOT-GAN (legacy). Persisted route stamps record the mode and neural model, such as `QUALITY:LAMA`; `_DEGRADED` marks a recorded fallback. Older plain stamps such as `QUALITY` remain readable as legacy AOT-GAN stamps when deciding whether a page can be reused.

## Distributed model inventory

| Model | Local path under `app/src/main/assets/models/` | Pinned upstream source URL | License | Derivation | Known caveats |
| --- | --- | --- | --- | --- | --- |
| `detector-v4-s_int8.onnx` | `detection/detector-v4-s_int8.onnx` | [Pinned ONNX file](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx) | Apache-2.0 declared by upstream model card. | Direct fetch; byte-identical. | None recorded. |
| `manga_panel_detector_int8.onnx` | `detection/manga_panel_detector_int8.onnx` | [Pinned checkpoint](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/resolve/40a2854663d537563cfb95c370288a84c6505b9a/manga_panel_detector_fp32.pt) | AGPL-3.0 under Ultralytics' trained YOLO licensing terms; the Hugging Face model card labels the repository Apache-2.0. | Converted by [`convert_panel_detector.py`](../scripts/converters/convert_panel_detector.py): 640×640 ONNX export, dynamic int8 quantization, and output transpose to the app's `[1,8400,6]` row layout. | Attributed to leoxs22. The model-card and Ultralytics license statements differ; this repository treats the derivative as AGPL-3.0. |
| `lama-manga.onnx` | `inpainting/lama-manga.onnx` | [Pinned int8 ONNX file](https://huggingface.co/Liiesl/lama-manga-onnx-quant/resolve/51d07e18caf9b1258585d3706fec8986ccbb8cfb/lama-manga_int8.onnx) | Apache-2.0 declared in the manifest for this model. | Direct fetch of int8 weights; 59,809,275 bytes; SHA-256 `502ce98fbd8d030501040d4505daea0616003266f4eb82b1d27fd4fca5b55f4a`. | Recommended default neural model for BALANCE and QUALITY. Input tensor `input` is float `[1,4,512,512]`: planar RGB in `[0,1]` with masked pixels zeroed, followed by a binary mask channel. Output is `[1,3,512,512]`. |
| `aot.onnx` | `inpainting/aot.onnx` | [Pinned ONNX file](https://huggingface.co/ogkalu/aot-inpainting/resolve/42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189/aot.onnx) | MIT declared by upstream model card. | Direct fetch; byte-identical. | Current provenance is attested from the pinned `ogkalu/aot-inpainting` re-upload of the manga-tuned AOT-GAN trained by zyddnys for manga-image-translator. The redesign did not replace or retrain these weights; AOT-GAN remains the legacy neural option. |
| `aot-512.onnx` | `inpainting/aot-512.onnx` | [Pinned source ONNX](https://huggingface.co/ogkalu/aot-inpainting/resolve/42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189/aot.onnx) | Same MIT source model as `aot.onnx`. | Converted by [`convert_aot_512.py`](../scripts/converters/convert_aot_512.py): fixed 512×512 input/output shapes, ONNX shape inference, then `onnxslim.slim`. The current fixed-size runtime centers smaller crops and fills the 512×512 context by edge replication. | The retired 61,469,172-byte Qualcomm AI Hub export was trained on CelebA-HQ; its fixed-contract mismatch caused face/eye hallucinations on manga. Older installs may still have that export in their private model cache. Local corpus gates passed using separate fixtures not included in this repository; on-device validation is pending. |
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

The panel and bubble converters use Ultralytics ONNX export with fixed 640×640 inputs and ONNX Runtime dynamic int8 weight quantization. The panel converter adds a channels-last transpose because the app detector consumes rows shaped `[N, 4 + class_count]`. Both are reproducible replacement recipes, not claims that the earlier local ONNX files can be reproduced byte-for-byte. The AOT converter applies fixed 512×512 shapes before shape inference and slimming, deriving the fixed contract from the pinned manga-tuned upstream graph; the runtime supplies out-of-crop context with edge replication. This supersedes the retired Qualcomm AI Hub CelebA-HQ-trained export. Converter and runtime versions are pinned in [`scripts/converters/requirements.txt`](../scripts/converters/requirements.txt), and the manifest pins each generated output hash.

The internal Manga109 segmenter tooling documented a separate static calibrated quantization path that used local manga pages and emitted a different output. Those calibration images are not part of this public repository. The public conversion therefore uses the source `.pt` file and the dynamic int8 recipe above.

## License notes

- The LaMa Manga manifest entry and PaddleOCR / PP-OCRv6 model cards declare Apache-2.0.
- `ogkalu/manga-ocr-mobile` declares Apache-2.0; `kha-white/manga-ocr` is the underlying model lineage and also declares Apache-2.0.
- `ogkalu/aot-inpainting` declares MIT.
- The panel detector is treated as AGPL-3.0 based on Ultralytics' published licensing terms for trained YOLO models; the Hugging Face card metadata says Apache-2.0, so the distinction is recorded above.
- `huyvux3005/manga109-segmentation-bubble` declares Apache-2.0, but its Ultralytics YOLO licensing status and the Manga109 dataset terms applicable to the trained weights remain unresolved.

These source declarations do not determine the legal status of other repository code or settle dataset-rights questions. Review the linked model cards and licenses before redistribution.
