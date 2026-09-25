# Third-party asset notices

This file records provenance information for model weights and fonts bundled by the Android app. SHA-256 values identify the exact files in this snapshot; they do not by themselves establish redistribution permission. The root application license is in [LICENSE](LICENSE).

PaddleOCR's project and the PP-OCRv6 small-recognition model documentation identify Apache-2.0 licensing. The source/version mapping for each imported ONNX export should still be confirmed against its original artifact before publishing binaries.

| Bundled file | SHA-256 | Provenance and license status |
| --- | --- | --- |
| `app/src/main/assets/models/detection/detector-v4-s_int8.onnx` | `5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79` | provenance under investigation |
| `app/src/main/assets/models/detection/manga_panel_detector_int8.onnx` | `6b1728706197d54bf8023ebae890ef1a8d42ddc0de27e3edec22f32dc674075b` | provenance under investigation |
| `app/src/main/assets/models/inpainting/aot-512.onnx` | `7cafb478c750d0dc7a2e9bad6b034a0fbff5c5df6db95ceb165ba571026c2f8e` | provenance under investigation |
| `app/src/main/assets/models/inpainting/aot.onnx` | `ffd39ed8e2a275869d3b49180d030f0d8b8b9c2c20ed0e099ecd207201f0eada` | provenance under investigation |
| `app/src/main/assets/models/ocr/decoder_init.onnx` | `612f97e22848620fb36fcac611467689cb4213d91f2d84f6a19042ad57d475f1` | provenance under investigation |
| `app/src/main/assets/models/ocr/decoder_step.onnx` | `a244b814a3a669190f9eae737960997633597cfa055fa186347e872bee834bc9` | provenance under investigation |
| `app/src/main/assets/models/ocr/encoder.onnx` | `d1fb455a07c1508cc56a4f4e15e2ed74aca9a4d78fd220ecc0ff39625d67e1b3` | provenance under investigation |
| `app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx` | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` | PaddleOCR PP-OCRv6 small detection model; Apache-2.0 identified; exact imported artifact revision under investigation. See [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) and the [PP-OCRv6 small model documentation](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx). |
| `app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx` | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` | PP-OCRv6 small recognition model; Apache-2.0 identified by the [model card](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx); exact imported artifact revision under investigation. |
| `app/src/main/assets/models/segmentation/manga109_bubble_int8.onnx` | `2c80dab0b9df4455b40501614ebdf4bae7a90c3880635635e8d87baa50cdfa94` | provenance under investigation; the filename suggests possible Manga109-derived data, whose redistribution terms need review |
| `app/src/main/res/font/animeace.ttf` | `da397371e46e5ee93be5f59478a667c3a2c2434754a60624561034e18c8beaa9` | provenance under investigation |
| `app/src/main/res/font/comic_book.otf` | `434d0c242958605fe7d66cf818051091e446b052b7e6d1045daecb47abb9a6f9` | provenance under investigation |
| `app/src/main/res/font/manga_master_bb.ttf` | `2f8ed18184579b73c13fce81a3aafc7886f2e777a40afc79c764bb8b20e070f0` | provenance under investigation |

The non-Paddle model weights and all three bundled fonts need upstream source, version, and license/attribution confirmation. The Manga109-named segmentation model needs particular review. Update this manifest when that evidence is available.