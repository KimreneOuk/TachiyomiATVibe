---
kind: spec
title: "PaddleOCR GPU/CPU selection behavior"
comments: none
---

The user requested a saved GPU/CPU choice and a temporary reader choice to compare PaddleOCR performance. GPU remains the default to preserve current behavior; CPU selects LiteRT XNNPACK with four threads for recognition and Paddle line detection.

| Control | Lifetime | Scope |
| --- | --- | --- |
| Translation settings → PaddleOCR device | Saved between launches | Default for reader OCR and chapter batches |
| Reader translation sheet → Default / GPU / CPU | Current reader ViewModel; survives sheet dismissal and chapter navigation, clears on reader exit | Reader OCR only; chapter batches use the saved default |

Device changes apply at the next OCR admission, after the current native call finishes. Changing the device does not invalidate already translated or prefetched pages: the device is an execution choice, not a different OCR model. Performance comparisons must use fresh pages or explicitly rerun OCR rather than timing cached results.

GPU retains the existing CPU fallback. The separate experimental ONNX provider setting applies only if LiteRT is unavailable; choosing CPU bypasses accelerator selection there too. Other vision models and inpainting keep their existing routing.

Validation: Standard Debug compilation and all 19 focused tests passed. The APK has been installed on the user's phone; the saved CPU selection survives restart and real pages use LiteRT XNNPACK with four threads. Remaining GPU override checks are tracked in [phone validation](phone-validation).
