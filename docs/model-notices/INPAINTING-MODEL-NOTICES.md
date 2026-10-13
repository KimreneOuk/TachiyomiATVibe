# Inpainting model notices

Review date: 2026-10-13.

This notice bundle records upstream metadata and available code-repository license texts. The cited model cards make different declarations, but neither checkpoint repository contains a standalone license/notice file at the pinned revisions. The exact license and redistribution authority for the trained checkpoint binaries therefore remains unresolved. No model copyright holder has been inferred or invented.

## Model files and source evidence

| Release file | Size | SHA-256 | Reviewed source metadata |
| --- | ---: | --- | --- |
| manga_lama_fused_fp16.tflite | 102,324,796 bytes | cb37f4edc40f7df953f6f599a2dbb467fc1654d99b4fc42758be1cac0252365f | mayocream/lama-manga-onnx at b55497aadbfcb9740e1ed16f008268d71b4f3f79 declares Apache-2.0; dreMaz/AnimeMangaInpainting at 2953a4e935bf01ad1471f6cbfd26ab81abeeb92d declares MIT. Both reviewed model repositories lack a standalone license/notice file. |
| aot_512_regen_gap_fp16.tflite | 11,469,264 bytes | da17c1cb341d994d54ca5a88a02cf59ef129e2081bff879fa3136b391b2e0087 | ogkalu/aot-inpainting at 42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189 declares MIT in its card; no standalone license/holder notice was present. |

The release SHA256SUMS.txt records the exact release file names and digests. These digests identify the binaries; they are not license statements.

## Included upstream code license files

The following texts are copied verbatim from the named upstream code repositories. They apply to those repositories' code, not automatically to the trained checkpoint or this optimized binary.

| Included file | Upstream source | Scope |
| --- | --- | --- |
| [advimman-lama-LICENSE](upstream-code-licenses/advimman-lama-LICENSE) | [advimman/lama LICENSE](https://github.com/advimman/lama/blob/main/LICENSE) | Apache-2.0 code license for the LaMa implementation repository; no checkpoint grant is inferred. |
| [researchmm-AOT-GAN-LICENSE](upstream-code-licenses/researchmm-AOT-GAN-LICENSE) | [researchmm/AOT-GAN-for-Inpainting LICENSE](https://github.com/researchmm/AOT-GAN-for-Inpainting/blob/master/LICENSE) | Apache-2.0 code license for the AOT-GAN architecture repository; no checkpoint grant is inferred. |
| [zyddnys-manga-image-translator-beta-0.3-LICENSE](upstream-code-licenses/zyddnys-manga-image-translator-beta-0.3-LICENSE) | [zyddnys/manga-image-translator beta-0.3 LICENSE](https://github.com/zyddnys/manga-image-translator/blob/beta-0.3/LICENSE) | GPL-3.0 code-repository license; it is not treated here as a verified license for the separately hosted inpainting.ckpt. |

The LaMa model card also references FourierUnitJIT code from [Carve-Photos/lama commit 5a67a02ad5047c33326695acf3bff8f9f44f19ac](https://github.com/Carve-Photos/lama/commit/5a67a02ad5047c33326695acf3bff8f9f44f19ac). It is included as attribution/provenance evidence, not as a statement that its code license governs the weights.

## Modification summary

The release artifacts were optimized locally without retraining. The LaMa graph has fused normalization, FP16 weights, and a FourierUnit convolution rewrite that removes dynamic BATCH_MATMUL nodes. The AOT graph uses a GlobalAveragePool replacement for the spatial reduction, FP16 weights, and a fixed 512 x 512 NHWC contract. No claim is made that the optimized outputs are identical to their source graphs or that they have no quality loss. The application tensor contracts and preprocessing are documented in [the download guide](../INPAINTING_MODEL_DOWNLOADS.md).

## Checksum file

The release includes SHA256SUMS.txt with these entries:

```text
cb37f4edc40f7df953f6f599a2dbb467fc1654d99b4fc42758be1cac0252365f  manga_lama_fused_fp16.tflite
da17c1cb341d994d54ca5a88a02cf59ef129e2081bff879fa3136b391b2e0087  aot_512_regen_gap_fp16.tflite
```
