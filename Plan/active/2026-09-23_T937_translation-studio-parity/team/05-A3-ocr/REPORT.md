# A3 — OCR provider parity

**Status:** Implemented. Real PaddleDet/A2 refiner and PaddleRec graph behavior are verified on synthetic probes. Full Studio end-to-end OCR remains unverified.

## Changes

- Kept MangaOCR and PaddleOCR v6 as selectable providers; MLKit remains excluded. Paddle recognition now uses 48px inputs, 640/1600 width buckets with gray-128 padding, supported B1/B2/B4/B8 caps, original-order result restoration, and per-crop retry after a failed multi-crop run ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L214), [`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L1031)).
- Wired the max-batch setting into MangaOCR and PaddleOCR. MangaOCR's optional serial timing setting forces B1 only when enabled; its default is false, so normal accelerated batching stays available ([`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L1031), [`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L2259), [`index.html`](../../../../../tools/translation_studio/static/index.html#L493)).
- Added provider-specific recognition crops: MangaOCR keeps the detected box and PaddleOCR receives a page-clamped 12px pad ([`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L1674)).
- Added Paddle region planning and assembly for detector lines, vertical CJK split/rotate handling, ink-gap vertical fallback, confidence filtering at 0.5, ordered batched composition, and the unpadded reread path for an empty vertical result ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L359), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L399), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L471), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L532), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L578)). The existing DB fragment merge retains its three-pass and same-line/gap thresholds ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L33), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L98)).
- Added Android line-join separators, post-confidence text-usability filtering, whole-region fallback confidence handling, and Korean glyph splitting for detector-derived vertical lines ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L427), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L509), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L599)). Japanese/Chinese lines concatenate; English/Korean lines join with spaces. CJK source languages require a supported CJK character; other languages require a letter.
- Added 17 model-free focused checks for preprocessing/buckets, batch fallback/order, line planning, vertical fallback/reread, line separators, usability and confidence filtering, Korean glyph splitting, MangaOCR decoding/preprocessing, settings wiring, and crop padding ([`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L72), [`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L139), [`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L165), [`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L185), [`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L276)).

## Verification

- `python -m py_compile tools/translation_studio/paddle_ocr.py tools/translation_studio/pipeline.py tools/translation_studio/selftest_ocr.py Plan/active/2026-09-23_T937_translation-studio-parity/team/05-A3-ocr/evidence/paddle_rec_probe.py` — passed.
- `python tools/translation_studio/selftest_ocr.py` — 17 tests passed. Pillow emitted a deprecation warning in the existing `tools/mangaocr_lab/lab/preprocessing.py:44` call to `Image.fromarray(mode="L")`.
- `node --check tools/translation_studio/static/app.js` — passed.
- `git diff --check` — passed.

## Real Paddle detector and A2 refiner evidence

The original A3 worktree held only a 132-byte Git LFS pointer for the detector, with payload oid `sha256:d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` and expected payload size 9,880,512 bytes. For this probe, that one ONNX asset was copied temporarily from the A2 worktree into the A3 model path, then the original pointer bytes were restored. The model file is not part of the commit.

The reproducible [probe script](evidence/paddle_detector_probe.py) drew `FREE TEXT` using Arial Bold onto an 800×360 synthetic page, then invoked the real A3 `PaddleDet.detect_lines` at A2's `.18/.34` thresholds. The detector returned `[37, 32, 386, 62, 0.9445974449473197]`. The script next called A2 `inpaint_page_android` from worktree commit `bcc6846b398322d2aa1188380a3c58e9807185b1` with the synthetic region labeled 2 and the same real detector. A2 called it on a 447×119 padded crop at `.18/.34`; it returned `[49, 43, 395, 74, 0.9376340302767382]`, recorded a `paddle-refined` source box `[225, 127, 571, 158]`, and routed the region as `freetext/opencv`. The inpaint mask contained 13,012 pixels.

Machine-readable output and visual inputs/results are saved as [`paddle_free_text_probe.json`](evidence/paddle_free_text_probe.json), [`paddle_free_text_fixture.png`](evidence/paddle_free_text_fixture.png), [`paddle_free_text_inpainted.png`](evidence/paddle_free_text_inpainted.png), and [`paddle_free_text_mask.png`](evidence/paddle_free_text_mask.png). The JSON records the detector model SHA-256, the A2 module path, crop dimensions, thresholds, lines, provenance, and route.

## Real PaddleRec evidence

The recognition ONNX was present locally in the primary worktree at 21,159,378 bytes. It was copied temporarily to the A3 model path for this run, then the original 133-byte LFS pointer was restored. The hydrated model was not staged or committed. Its SHA-256 was `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`; the existing recognition dictionary was 74,947 bytes. `PaddleRec.recognize_batch` ran on `CPUExecutionProvider` with max batch 4 and produced all three expected strings:

| Language | Synthetic input | Recognized output | Confidence |
|---|---|---|---:|
| Japanese | `日本語テスト` | `日本語テスト` | 0.999289 |
| Chinese | `中文测试` | `中文测试` | 0.999866 |
| English | `FREE TEXT` | `FREE TEXT` | 0.992853 |

The trace used width bucket 640 with B2 then B1; neither batch fell back. The [reproducible probe](evidence/paddle_rec_probe.py), [JSON output](evidence/paddle_rec_probe.json), and fixed [Japanese](evidence/paddle_rec_ja.png), [Chinese](evidence/paddle_rec_zh.png), and [English](evidence/paddle_rec_en.png) crops are saved together.

## Scope and remaining risk

No A1 detection or A2 inpaint source files were changed. Real PaddleDet, PaddleRec, and the A2 free-text refiner invocation are verified on synthetic inputs. The A2 probe exercises the refinement route and provenance, not erase quality; the recognition probe exercises `PaddleRec.recognize_batch`, not the full Studio page pipeline on a manga page. The PaddleRec pointer remains restored in the worktree, and no model blobs are committed.
