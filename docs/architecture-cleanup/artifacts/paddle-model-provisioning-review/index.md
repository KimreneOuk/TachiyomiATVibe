---
title: "Review: PaddleOCR and Inpainting Model Provisioning"
kind: review
comments: none
---

Review of the newly added model provisioning changes across `scripts/fetch_models.py`, `scripts/models.manifest`, `scripts/test_fetch_models.py`, `.github/workflows/`, `README.md`, `docs/PADDLE_OCR_MODEL_DOWNLOADS.md`, and `docs/MODEL_SOURCES.md`.

## Status: All Findings Addressed / Ready for Push

All identified review items have been resolved and verified. No remaining blockers found.

## 1. Findings and Resolutions

### High: Fresh builds provisioned only with `--group paddle aot lama` fail at runtime in `OnnxModelStore.ensureModels()`

- **Status**: **RESOLVED**
- **Location**: `scripts/fetch_models.py` (`MODEL_GROUPS`), `README.md`, `docs/PADDLE_OCR_MODEL_DOWNLOADS.md`, `docs/MODEL_SOURCES.md`.
- **Initial Finding**:
`RoiPageRecognitionEngine.initialize()` unconditionally calls `modelStore.ensureModels()`, which copies `detector-v4-s_int8.onnx`, `encoder.onnx`, `decoder_init.onnx`, `decoder_step.onnx`, and `vocab.txt` without fallback. In addition, Stage-1 text detection requires `detector-v4-s_int8.onnx` before Paddle line detection can run on ROI crops. Building an APK using only `--group paddle aot lama` on a clean checkout therefore failed at runtime with `FileNotFoundException`.
- **Resolution**:
Documentation in both `README.md` and `docs/PADDLE_OCR_MODEL_DOWNLOADS.md` now explicitly clarifies:
  - Fresh runnable builds require the full default provisioning (`python3 scripts/fetch_models.py` without `--group`).
  - Baseline page detector and MangaOCR dependencies are clearly enumerated as necessary for engine initialization.
  - The `--group paddle aot lama` flag is explicitly documented for updating those specific model families in an already-provisioned workspace or for isolated binary inspection.

### Medium: Test runner import portability in `scripts/test_fetch_models.py`

- **Status**: **RESOLVED**
- **Location**: `scripts/test_fetch_models.py` lines 5–7.
- **Initial Finding**:
Bare module import `from fetch_models import ...` failed with `ModuleNotFoundError` when tests were invoked from the repository root via `python3 -m unittest scripts/test_fetch_models.py` or `pytest`.

## 2. Additional Improvements Verified

- **Immutable Upstream Pinning**: The original PP-OCRv6 manga models in `scripts/models.manifest` (`inference.onnx`, `PP-OCRv6_small_rec.txt`, and `det/inference.onnx`) have been repinned from the mutable Hugging Face branch `main` to immutable commit revision `8ee29e094b6aac77dd067c70805438782fa16ab0`.
- **Release Publication & Hash Verification**: The public release `paddle-ocr-litert-v1` is published with all seven matching LiteRT asset digests, source notices, and checksums. Full group hash verification and anonymous fetch contracts pass.

## 3. Verified Invariants Matrix

| Area | Verified Contract | Result |
| --- | --- | --- |
| **Manifest vs `OnnxModelStore` Assets** | The 7 LiteRT assets (`manga_rec_{160,320,480,640,1600}_fp16.tflite`, `manga_det_960_fp16.tflite`, `PP-OCRv6_small_rec.txt`) under `ocr/paddle-v6-small/litert/` match `PADDLE_LITERT_ASSET_DIR` and `PADDLE_LITERT_REQUIRED_ASSETS` in `OnnxModelStore.kt` byte-for-byte in path naming. | Pass |
| **Paddle ONNX Fallback Grouping** | `--group paddle` provisions all 10 assets under `ocr/paddle-v6-small/`, maintaining `inference.onnx`, `det/inference.onnx`, and dictionary fallbacks needed by `OnnxModelStore.ensurePaddleOcrV6Small()` and `ensurePaddleOcrV6Det()`. | Pass |
| **Atomic & Streaming Verification** | Downloads write to `.{filename}.*.part` in target directories with streaming length cap enforcement, SHA-256 validation prior to `os.replace`, and clean temporary file unlinking on failure. Existing files with matching size and SHA-256 are safely skipped. | Pass |
| **Clean Scope Isolation** | `remove_models` with `--group` restricts file unlinking to the selected manifest entries and prunes emptied ancestor directories up to `app/src/main/assets/models` without removing sibling families or the model root. | Pass |
| **CI Workflow Sequencing** | Both `.github/workflows/build_pull_request.yml` and `build_push.yml` execute `python3 -m unittest discover -s scripts -p 'test_fetch_models.py'` before model asset downloads, gating packaging on contract verification. | Pass |
| **Documentation Alignment** | `README.md`, `docs/PADDLE_OCR_MODEL_DOWNLOADS.md`, and `docs/MODEL_SOURCES.md` accurately state build prerequisites, exact file paths, byte counts, and SHA-256 hashes. | Pass |
