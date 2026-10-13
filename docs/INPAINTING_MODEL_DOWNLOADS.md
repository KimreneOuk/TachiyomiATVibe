# Inpainting model downloads

These two raw TFLite files are attached to the public GitHub release tagged inpainting-fp16-v1 and are available for anonymous download. The Android app does not download models at runtime: CI/build provisioning fetches these files, verifies their byte counts and SHA-256 digests, and packages them as assets.

| Model | Release asset | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Manga LaMa FP16 | [manga_lama_fused_fp16.tflite](https://github.com/KimreneOuk/TachiyomiATVibe/releases/download/inpainting-fp16-v1/manga_lama_fused_fp16.tflite) | 102,324,796 | cb37f4edc40f7df953f6f599a2dbb467fc1654d99b4fc42758be1cac0252365f |
| AOT FP16 | [aot_512_regen_gap_fp16.tflite](https://github.com/KimreneOuk/TachiyomiATVibe/releases/download/inpainting-fp16-v1/aot_512_regen_gap_fp16.tflite) | 11,469,264 | da17c1cb341d994d54ca5a88a02cf59ef129e2081bff879fa3136b391b2e0087 |

The release includes SHA256SUMS.txt; compare the locally downloaded files against it before use. From a POSIX shell:

```sh
sha256sum -c SHA256SUMS.txt
```

In PowerShell, run Get-FileHash with Algorithm set to SHA256 for each file and compare its Hash output to the table or checksum file. Hash verification detects an altered or truncated download; it does not establish ownership, permission, or a license grant.

## Sources and license evidence

This section records source repository declarations and code licenses separately from the license status of the trained checkpoint files. The pinned Hugging Face revisions identify the reviewed cards/trees; they do not resolve redistribution rights where the repository supplies only license metadata and no standalone license or notice.

### Manga LaMa FP16

- mayocream/lama-manga-onnx reviewed at [revision b55497aadbfcb9740e1ed16f008268d71b4f3f79](https://huggingface.co/mayocream/lama-manga-onnx/commit/b55497aadbfcb9740e1ed16f008268d71b4f3f79); its model card metadata declares Apache-2.0 and describes the manga inpainting model/FourierUnitJIT lineage. No standalone LICENSE or NOTICE file was present at that revision.
- dreMaz/AnimeMangaInpainting reviewed at [revision 2953a4e935bf01ad1471f6cbfd26ab81abeeb92d](https://huggingface.co/dreMaz/AnimeMangaInpainting/commit/2953a4e935bf01ad1471f6cbfd26ab81abeeb92d); the card metadata declares MIT and describes lama_large_512px.ckpt as a fine-tuned big-LaMa checkpoint for anime/manga. No standalone LICENSE or NOTICE file was present at that revision.
- The advimman/lama implementation has an Apache-2.0 LICENSE, copied verbatim as [advimman-lama-LICENSE](model-notices/upstream-code-licenses/advimman-lama-LICENSE). This is a license for the code repository and is not evidence that the trained weights carry Apache-2.0.
- The model card links FourierUnitJIT to [Carve-Photos/lama commit 5a67a02ad5047c33326695acf3bff8f9f44f19ac](https://github.com/Carve-Photos/lama/commit/5a67a02ad5047c33326695acf3bff8f9f44f19ac). That code lineage is recorded for attribution; it does not decide checkpoint rights.
- The exact redistribution terms for the manga-trained checkpoint and optimized binary remain unverified.

### AOT FP16

- ogkalu/aot-inpainting reviewed at [revision 42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189](https://huggingface.co/ogkalu/aot-inpainting/tree/42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189); its model card declares MIT and identifies the traced manga inpainting model lineage. No standalone LICENSE or copyright-holder notice was present at that revision.
- The source card attributes the model lineage to zyddnys/manga-image-translator. Its [beta-0.3 code license](https://github.com/zyddnys/manga-image-translator/tree/beta-0.3) is GPL-3.0 and is copied verbatim as [zyddnys-manga-image-translator-beta-0.3-LICENSE](model-notices/upstream-code-licenses/zyddnys-manga-image-translator-beta-0.3-LICENSE). This is a code-repository license, not a verified license for inpainting.ckpt.
- The [researchmm/AOT-GAN-for-Inpainting code repository](https://github.com/researchmm/AOT-GAN-for-Inpainting) has an Apache-2.0 LICENSE, copied verbatim as [researchmm-AOT-GAN-LICENSE](model-notices/upstream-code-licenses/researchmm-AOT-GAN-LICENSE). This applies to that repository's code and does not establish the license of the manga-trained checkpoint.
- The exact redistribution terms and copyright holder for the trained manga checkpoint/optimized AOT binary remain unverified.

## Modifications and tensor contracts

No retraining was performed for these release artifacts. The local work changed graph representation, weight precision, and fixed tensor contracts; it does not claim that the outputs are identical to the source models or that quality loss is absent.

| Model | Recorded graph changes | Input/output contract |
| --- | --- | --- |
| Manga LaMa FP16 | Fused normalization operations, stored weights in FP16, and rewrote the FourierUnit path to convolution so the exported graph has no dynamic BATCH_MATMUL operations. | RGB NHWC image [1,512,512,3], values in [0,1], premultiplied by (1-mask) so masked pixels are zero; mask [1,512,512,1] with 1 for holes; RGB output [1,512,512,3] in [0,1]. |
| AOT FP16 | Replaced the spatial reduction pattern with GlobalAveragePool for the supported static graph path, stored weights in FP16, and fixed the interface at 512 x 512 NHWC. | RGB input [1,512,512,3] is normalized as pixel / 127.5 - 1, then multiplied by (1-mask); mask [1,512,512,1] with 1 for holes; output [1,512,512,3] in the signed model range and decoded by the app. |

Bubble and free-text routing remain mode-based: FAST uses classical median diffusion for segmentation bubbles and Telea for free text; BALANCE keeps bubble cleanup classical and uses the selected GPU neural model for free text when available; QUALITY routes both through the selected GPU neural model when available. Bubble erase masks come only from supplied segmentation masks, are eroded inward, and final blending stays within the segmentation region. When a bubble mask is missing, that bubble's pixels are preserved and the run is marked degraded/skipped. Free-text line groups retain their existing batching. A failed GPU neural route never switches to neural CPU inference; configured classical fallback/error handling is reported as degraded.

The scripts/models.manifest entries pin the release URLs, byte counts, and SHA-256 digests. For additional source and notice detail, see MODEL_SOURCES.md.
