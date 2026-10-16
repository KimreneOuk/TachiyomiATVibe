---
kind: spec
title: "PaddleOCR, AOT and LaMa portable handoff"
comments: none
---

The user requested GitHub main to contain the latest PaddleOCR changes and enough model provisioning support to continue work on another device.

Published code: commit `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` on both GitHub `main` and `inpainting-modes-redesign`; local main was fast-forwarded with a clean checkout. GitHub's `refs/heads/main` was verified at the same SHA.

The optimized Paddle LiteRT models used in the phone build were previously local ignored files absent from the manifest. They are now separately published as verified assets in `paddle-ocr-litert-v1`. The manifest pins all five recognition widths, detector and dictionary; AOT and Manga LaMa remain in the public `inpainting-fp16-v1` release. Original Paddle ONNX fallback files now use fixed upstream revision `8ee29e094b6aac77dd067c70805438782fa16ab0`.

Fresh-checkout sequence:

```sh
git pull origin main
python -m pip install -r scripts/converters/requirements.txt
python scripts/fetch_models.py
```

The default command provisions the full 19-file inventory. `--group paddle aot lama` updates only those families or downloads their binaries for inspection; it cannot alone produce a fresh runnable APK because OCR initialization also requires the baseline page detector and MangaOCR files.

Validation completed:

- Built and installed Standard Debug `0.17.1-72`; real phone logs confirmed CPU XNNPACK with four threads and temporary GPU execution without changing saved CPU.
- All 19 focused Android unit tests and repository-wide Spotless checks passed after correcting existing Kotlin formatting violations.
- Five model provisioning tests passed under unittest discovery, single-module unittest, and pytest.
- Anonymous downloads of all 12 selected Paddle/AOT/LaMa files into a new empty root passed size and SHA-256 verification.
- The three newly pinned ONNX fallback URLs were separately downloaded and verified.
- Public GitHub release digests match all seven optimized Paddle manifest entries.

The full default fetch was also attempted in the existing local Python environment and correctly rejected a panel-detector conversion generated with unpinned tool versions. That converter is outside the three direct-download model families; a fresh build must install the repository's pinned requirements as shown above. The earlier GitHub CI run completed provisioning and then failed formatting; the current formatting failure was fixed. The new GitHub CI run has been triggered but is not yet recorded as complete here.
