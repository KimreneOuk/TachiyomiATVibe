# Model sources and licenses

[The model fetcher](../scripts/fetch_models.py) provisions the assets listed in [the manifest](../scripts/models.manifest). The manifest records relative asset paths, expected sizes, and SHA-256 digests for 19 build-time model files. Direct downloads are checked against the final asset hash. For converted files, the fetcher checks the downloaded source before running the converter and verifies the generated output afterward.

The inpainting inventory is now exactly two optimized LiteRT TFLite files: Manga LaMa FP16 and AOT FP16. Their release downloads, byte counts, hashes, source declarations, and modification notices are documented in [Inpainting model downloads](INPAINTING_MODEL_DOWNLOADS.md) and [the inpainting notice bundle](model-notices/INPAINTING-MODEL-NOTICES.md). The release is a source for build-time provisioning; the app itself does not download models at runtime.

PaddleOCR also provisions five optimized FP16 LiteRT recognition buckets, the 960-pixel LiteRT detector, and their dictionary. These repaired GPU graphs support the saved GPU/CPU device choice and temporary reader override. Their exact release assets, hashes, and tensor contracts are listed in [PaddleOCR model downloads](PADDLE_OCR_MODEL_DOWNLOADS.md). To provision just PaddleOCR, AOT, and LaMa, use `python3 scripts/fetch_models.py --group paddle aot lama`; default provisioning still downloads all model families.

## Provisioning and on-device storage

Provisioning is a build-time step, not an app-side model download. From the repository root, install the pinned conversion toolchain and fetch the assets:

```sh
python3 -m pip install -r scripts/converters/requirements.txt
python3 scripts/fetch_models.py
```

The fetcher writes each manifest entry beneath app/src/main/assets/models/ using the relative path from the manifest. Existing files are skipped only when both their size and SHA-256 match the manifest. Direct-download entries are verified after download; entries with a converter first verify the upstream checkpoint, run the listed converter, and verify the converted output. python3 scripts/fetch_models.py --clean removes only files listed in the manifest. The pull-request and push build workflows run this provisioning step before Gradle packaging.

At runtime, the inpainting engine extracts the selected bundled TFLite model into the app-private files/models/inpainting/ cache and validates the cached copy against the packaged asset. It does not contact GitHub or another model host. Other OCR/detection ONNX assets continue to use OnnxModelStore and its separate app-private cache. The current inpainting engine does not require the retired ONNX inpainting files. OnnxModelStore still attempts optional aot.onnx and aot-512.onnx copies during OCR initialization and logs warnings when those removed assets are absent; the returned nullable fields have no current inpainting consumer. The debug-only QNN diagnostics also checks for aot-512.onnx when explicitly triggered.

## Reader settings

There are exactly two neural inpainting choices: Manga LaMa FP16 (LiteRT GPU) and AOT FP16 (LiteRT GPU). The selected model is used for neural routes in BALANCE and QUALITY; FAST remains classical. Both neural engines are GPU-first and do not fall back to neural CPU inference. If a neural route cannot run, the app follows its configured classical fallback/error behavior and records degraded execution rather than silently changing the neural backend.

| Mode | Speech bubbles | Free text |
| --- | --- | --- |
| FAST | Classical median diffusion | OpenCV Telea |
| BALANCE | Classical median diffusion | Selected neural model when available |
| QUALITY | Selected neural model when available | Selected neural model when available |

Neural bubble cleanup uses supplied segmentation masks only, erodes the masks inward, and composites the result only within the segmentation region. Missing bubble segmentation preserves those bubble pixels and records the degraded/skipped condition. Free-text line groups keep their existing batching path. Route stamps track the mode, selected model, and degraded result so stale or failed neural output is not mistaken for a successful run.

## Distributed model inventory

| Model | Local path under `app/src/main/assets/models/` | Pinned upstream source URL | License | Derivation | Known caveats |
| --- | --- | --- | --- | --- | --- |
| `detector-v4-s_int8.onnx` | `detection/detector-v4-s_int8.onnx` | [Pinned ONNX file](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx) | Apache-2.0 declared by upstream model card. | Direct fetch; byte-identical. | None recorded. |
| `manga_panel_detector_int8.onnx` | `detection/manga_panel_detector_int8.onnx` | [Pinned checkpoint](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/resolve/40a2854663d537563cfb95c370288a84c6505b9a/manga_panel_detector_fp32.pt) | AGPL-3.0 under Ultralytics' trained YOLO licensing terms; the Hugging Face model card labels the repository Apache-2.0. | Converted by [`convert_panel_detector.py`](../scripts/converters/convert_panel_detector.py): 640×640 ONNX export, dynamic int8 quantization, and output transpose to the app's `[1,8400,6]` row layout. | Attributed to leoxs22. The model-card and Ultralytics license statements differ; this repository treats the derivative as AGPL-3.0. |
| manga_lama_fused_fp16.tflite | inpainting/manga_lama_fused_fp16.tflite | [GitHub release asset](https://github.com/KimreneOuk/TachiyomiATVibe/releases/download/inpainting-fp16-v1/manga_lama_fused_fp16.tflite) | Model-card declarations vary; checkpoint-specific redistribution grant not verified. | Locally optimized LiteRT TFLite derivative; 102,324,796 bytes; SHA-256 cb37f4edc40f7df953f6f599a2dbb467fc1654d99b4fc42758be1cac0252365f. | GPU-first FP16; NHWC RGB image [1,512,512,3] plus mask [1,512,512,1]. See the download guide for tensor scaling, provenance, and rights caveat. |
| aot_512_regen_gap_fp16.tflite | inpainting/aot_512_regen_gap_fp16.tflite | [GitHub release asset](https://github.com/KimreneOuk/TachiyomiATVibe/releases/download/inpainting-fp16-v1/aot_512_regen_gap_fp16.tflite) | Model-card metadata declares MIT, but the exact checkpoint-specific grant is not verified. | Locally optimized LiteRT TFLite derivative; 11,469,264 bytes; SHA-256 da17c1cb341d994d54ca5a88a02cf59ef129e2081bff879fa3136b391b2e0087. | GPU-first FP16, fixed 512 x 512 NHWC image/mask contract. See the download guide for tensor scaling, provenance, and rights caveat. |
| `encoder.onnx` | `ocr/encoder.onnx` | [Pinned encoder file](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/encoder.onnx) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Split mobile export from `ogkalu/manga-ocr-mobile`; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr) (Apache-2.0). |
| `decoder_init.onnx` | `ocr/decoder_init.onnx` | [Pinned decoder file](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/decoder_init.onnx) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Split mobile export; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr). |
| `decoder_step.onnx` | `ocr/decoder_step.onnx` | [Pinned decoder file](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/decoder_step.onnx) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Split mobile export; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr). |
| `vocab.txt` | `ocr/vocab.txt` | [Pinned vocabulary](https://huggingface.co/ogkalu/manga-ocr-mobile/resolve/aa0d7d3199f5843f8f5d743f85b44098c8e3ac98/vocab.txt) | Apache-2.0 declared by the upstream mobile export. | Direct fetch; byte-identical. | Part of the split `ogkalu/manga-ocr-mobile` export; underlying lineage is [`kha-white/manga-ocr`](https://github.com/kha-white/manga-ocr). |
| `inference.onnx` (PP-OCRv6 recognition) | `ocr/paddle-v6-small/inference.onnx` | [Pinned ONNX file](https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/8ee29e094b6aac77dd067c70805438782fa16ab0/rec/manga_rec_v0.2_fp16.onnx) | Apache-2.0 declared by upstream model card. | Direct fetch; byte-identical. | Kellenok PP-OCRv6 manga v0.2 FP16 recognition model; exact content pinned by manifest hash. |
| `PP-OCRv6_small_rec.txt` | `ocr/paddle-v6-small/PP-OCRv6_small_rec.txt` | [Content-pinned dictionary](https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/8ee29e094b6aac77dd067c70805438782fa16ab0/ppocrv6_dict.txt) | Apache-2.0 under PaddleOCR. | Direct fetch, saved at the app's existing filename. | Exact bytes pinned by the manifest hash. |
| `inference.onnx` (PP-OCRv6 detector) | `ocr/paddle-v6-small/det/inference.onnx` | [Pinned ONNX file](https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/8ee29e094b6aac77dd067c70805438782fa16ab0/det/manga_det_v0.2_fp16.onnx) | Apache-2.0 declared by upstream model card. | Direct fetch; byte-identical. | PaddleOCR PP-OCRv6 small detection model. |
| Optimized PaddleOCR LiteRT suite (7 files) | `ocr/paddle-v6-small/litert/` | [Verified release assets](PADDLE_OCR_MODEL_DOWNLOADS.md) | Apache-2.0 declared by the upstream PaddleOCR/model cards. | Five fixed-width FP16-weight recognizers, a detector, and matching dictionary; individual hashes and graph changes documented in the download guide. | Fixed B1 tensors; device selection does not enable true LiteRT batching. |
| `manga109_bubble_int8.onnx` | `segmentation/manga109_bubble_int8.onnx` | [Pinned checkpoint](https://huggingface.co/huyvux3005/manga109-segmentation-bubble/resolve/f9a4108c4955136a810e5e92207972f3fb3a65fd/best.pt) | Apache-2.0 declared by model card; Ultralytics trained YOLO terms may apply AGPL-3.0, applicability unconfirmed. | Converted by [`convert_bubble_segmenter.py`](../scripts/converters/convert_bubble_segmenter.py): 640×640 ONNX export and ONNX Runtime dynamic int8 quantization. | Manga109 dataset terms for the trained weights and converted artifact remain unresolved. |

The panel detector is derived from and attributed to [leoxs22/manga-panel-detector-yolo26n](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n). The source checkpoint and converted model are treated as AGPL-3.0 under Ultralytics' [published YOLO licensing terms](https://www.ultralytics.com/license), which state that trained YOLO models are AGPL-3.0 by default. The Hugging Face model card currently labels the repository Apache-2.0; the notice records the AGPL determination used for this YOLO26 derivative. The converter and pinned dependency list are the corresponding source for the converted artifact. Build machines fetch the source checkpoint and convert it locally; the generated model is packaged into the APK, but the repository does not offer a standalone model download.

## Conversion recipes

Install the pinned converter toolchain once:

```sh
python3 -m pip install -r scripts/converters/requirements.txt
```

The inpainting TFLite assets are prebuilt release files fetched directly and checked against their pinned size and SHA-256; the old ONNX conversion ladder is no longer part of the manifest. The remaining detector and bubble converters accept a verified source file and an output path:

```sh
python3 scripts/converters/convert_panel_detector.py --source manga_panel_detector_fp32.pt --output manga_panel_detector_int8.onnx
python3 scripts/converters/convert_bubble_segmenter.py --source best.pt --output manga109_bubble_int8.onnx
```

The panel and bubble converters use Ultralytics ONNX export with fixed 640 x 640 inputs and ONNX Runtime dynamic int8 weight quantization. The panel converter adds a channels-last transpose because the app detector consumes rows shaped [N, 4 + class_count]. Both are reproducible replacement recipes, not claims that the earlier local ONNX files can be reproduced byte-for-byte. Converter and runtime versions are pinned in scripts/converters/requirements.txt, and the manifest pins each generated output hash.

The internal Manga109 segmenter tooling documented a separate static calibrated quantization path that used local manga pages and emitted a different output. Those calibration images are not part of this public repository. The public conversion therefore uses the source .pt file and the dynamic int8 recipe above.

## License notes

- The Manga LaMa sources include model-card metadata declaring Apache-2.0 for mayocream/lama-manga-onnx and MIT for dreMaz/AnimeMangaInpainting; neither pinned model repository contains a standalone license or notice file. Those metadata declarations do not establish a checkpoint-specific redistribution grant for the optimized binary.
- The AOT source card at ogkalu/aot-inpainting declares MIT but contains no standalone license or copyright notice. The original zyddnys/manga-image-translator beta-0.3 repository has a GPL-3.0 code license, and researchmm/AOT-GAN-for-Inpainting has an Apache-2.0 code license; these code licenses do not determine the license of the manga-trained checkpoint.
- Upstream code license texts that were available are included verbatim under [the upstream-code-licenses directory](model-notices/upstream-code-licenses/). Each file's scope and pinned source are described in [the inpainting notice bundle](model-notices/INPAINTING-MODEL-NOTICES.md). No license text or copyright holder has been invented for a model card that lacks one.
- PaddleOCR / PP-OCRv6 model cards declare Apache-2.0.
- ogkalu/manga-ocr-mobile declares Apache-2.0; kha-white/manga-ocr is the underlying model lineage and also declares Apache-2.0.
- The panel detector is treated as AGPL-3.0 based on Ultralytics' published licensing terms for trained YOLO models; the Hugging Face card metadata says Apache-2.0, so the distinction is recorded above.
- huyvux3005/manga109-segmentation-bubble declares Apache-2.0, but its Ultralytics YOLO licensing status and the Manga109 dataset terms applicable to the trained weights remain unresolved.

Model-card declarations are recorded as source metadata, not as independent legal conclusions. The exact redistribution authority for these trained inpainting checkpoints remains unresolved; consult the linked sources and obtain a rights determination before relying on a broader grant.
