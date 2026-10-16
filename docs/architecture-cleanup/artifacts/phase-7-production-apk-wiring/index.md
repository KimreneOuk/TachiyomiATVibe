---
title: "Phase 7: LiteRT OCR Wiring & On-Device Benchmark APK"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Phase 7: LiteRT OCR Wiring & On-Device Benchmark APK

## Outcome

Packaged the LiteRT PP-OCRv6 detector, five static-width recognizers, and dictionary; wired LiteRT-first initialization with ONNX fallback; fixed a TranslationManager startup initialization-order crash; and built and installed both debug APKs. Physical validation on the OnePlus 12 confirms GPU execution for recognizer buckets 160 and 320 and the detector. Recognizer bucket 480 still fails OpenCL delegate preparation, so buckets 480, 640, and 1600 run on XNNPACK. The GPU parity gate is not yet satisfied across all recognizer buckets.

## Runtime Changes

- `OnnxModelStore` verifies the seven LiteRT assets and provisions them into the app model cache.
- `RoiPageRecognitionEngine` attempts LiteRT with GPU preferred, XNNPACK fallback enabled, and four CPU threads. Provisioning or eager initialization failures use the existing ONNX path.
- Recognizer bucket interpreters are created lazily. A first-use LiteRT recognition failure switches to the configured ONNX recognizer and retries the crop.
- Both LiteRT GPU initializers use CompatibilityList best options when supported, or default GpuDelegate options when the bundled compatibility database does not recognize the device. Options allow precision loss, prefer sustained speed, and force OpenCL. GPU initialization errors use XNNPACK fallback.
- The recognizer runner cache is limited to one resident runner; an old runner is closed before creating another bucket.
- `TranslationManager` declares activeStores and the lazy durableStatusResolver before scheduler and init, preventing the initial queue StateFlow emission from touching an uninitialized lazy delegate.
- Six FlatBuffers and the recognition dictionary are packaged under assets/models/ocr/paddle-v6-small/litert/; .tflite assets are uncompressed.

## Packaged Models

| Asset | Bytes |
| --- | --- |
| manga_det_960_fp16.tflite | 945,516 |
| manga_rec_160_fp16.tflite | 10,619,924 |
| manga_rec_320_fp16.tflite | 10,620,732 |
| manga_rec_480_fp16.tflite | 10,620,120 |
| manga_rec_640_fp16.tflite | 10,620,684 |
| manga_rec_1600_fp16.tflite | 10,620,216 |
| PP-OCRv6_small_rec.txt | 74,947 |

Model source: audit/models/litert_gpu/; dictionary: existing PP-OCRv6 app asset.

## Instrumented Benchmark

PaddleOcrLiteRtOnDeviceTest uses AndroidJUnitRunner, provisions through OnnxModelStore, and measures all five recognizer buckets plus the detector with three warmups and five samples. The test launches the app activity to keep the process in the foreground. Provider mismatches are collected while the remaining buckets and detector run; the test then fails if any model does not use the GPU delegate.

Device: OnePlus 12, Snapdragon 8 Gen 3, Adreno 750, connected as 192.168.100.223:36539.

Command:
adb -s 192.168.100.223:36539 shell "am instrument -r -w -e class eu.kanade.translation.diagnostics.PaddleOcrLiteRtOnDeviceTest app.kanade.tachiyomi.vibe.debug.test/androidx.test.runner.AndroidJUnitRunner"

Full benchmark output from the completed run:

PADDLE_REC_BENCH: bucket=160, forwardMs=8.741, totalMs=10.923, minForwardMs=7.122, minTotalMs=8.271, provider=litert_gpu_delegate
PADDLE_REC_BENCH: bucket=320, forwardMs=10.937, totalMs=13.629, minForwardMs=8.936, minTotalMs=11.610, provider=litert_gpu_delegate
PADDLE_REC_BENCH: bucket=480, forwardMs=132.015, totalMs=135.096, minForwardMs=131.376, minTotalMs=134.389, provider=litert_xnnpack
PADDLE_REC_BENCH: bucket=640, forwardMs=180.290, totalMs=184.239, minForwardMs=176.485, minTotalMs=180.609, provider=litert_xnnpack
PADDLE_REC_BENCH: bucket=1600, forwardMs=389.473, totalMs=398.483, minForwardMs=332.685, minTotalMs=341.471, provider=litert_xnnpack
PADDLE_DET_BENCH: forwardMs=209.385, totalMs=242.923, minForwardMs=37.419, minTotalMs=67.845, provider=litert_gpu_delegate

Recognizer bucket 480 failed GPU delegate initialization with: Internal error: Failed to apply delegate: TfLiteGpuDelegate Init: Unrecognized Write selector. The engine fell back to XNNPACK; this sticky fallback also left buckets 640 and 1600 on XNNPACK. The detector passed its GPU provider assertion. Detector mean latency is higher than its minimum, indicating a substantial outlier in the five measured runs.

Instrumentation result: 1 test run, 1 failure, 0 ignored; duration 27.438s. Failure summary: expected litert_gpu_delegate; recognizer buckets 480, 640, and 1600 used litert_xnnpack. The test completed measurements for every bucket and detector before reporting the failure.

## Build and Device Verification

- Build command: `.\gradlew.bat assembleStandardDebug assembleStandardDebugAndroidTest --console=plain`
- Result: `BUILD SUCCESSFUL in 21s`; 303 actionable tasks, 17 executed, 286 up-to-date.
- git diff --check passed.
- Both APK installs to the connected device succeeded.
- D8 emitted a non-fatal Kotlin metadata rewrite warning for the instrumentation test class.

## APKs

### App APK for OnePlus 12

Path: C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVibe-inpainting-modes-redesign\app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk

- Size: 322,739,557 bytes
- SHA-256: 8AF23F1553EA8A5BB73D3B3B05027FD14C66AD7E51F601810505F25B87E62606

### Instrumentation APK

Path: C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVibe-inpainting-modes-redesign\app\build\outputs\apk\androidTest\standard\debug\app-standard-debug-androidTest.apk

- Size: 413,400 bytes
- SHA-256: BE2C1B6286493E8B82F2EA5C664ACAA3E7B635C6EC5F199A4FFE5FCC661F2D38

## Follow-up

Investigate and revise the 480-width recognizer graph or GPU conversion so OpenCL can compile it. Then rerun the on-device benchmark. Keep the one-runner cache bound and GPU provider assertions; current test evidence does not support claiming GPU execution across every recognition bucket.

