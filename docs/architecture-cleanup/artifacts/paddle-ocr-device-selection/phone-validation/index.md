---
title: "Phone validation of PaddleOCR device controls"
kind: ticket
status: 1
assignee: ""
comments: none
---

Device: OnePlus PKG110, wireless ADB `192.168.100.223:36539`. Test date: 2026-10-13.

Built Standard Debug `0.17.1-72` and installed the ARM64 APK over the existing debug app with `adb install -r`; application data was retained.

APK: `C:/Users/ADMIN/Documents/Coding Related/tachiyomiATVibe-inpainting-modes-redesign/app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk`.

SHA256: `AE4310F9B6D7CE59EDCE79CB3108754E05465A33E9CA32BC8A6D1462C45A77F8`.

| Check | Result | Evidence |
| --- | --- | --- |
| Settings device picker | Passed | Live picker offers GPU and CPU (4 threads) |
| Saved CPU survives process restart | Passed | Selected CPU through UI, force-stopped/relaunched app, reread CPU preference |
| Real CPU recognition | Passed | `preferGpu=false`, `cpuThreads=4`, detector `provider=litert_xnnpack`, page OCR `ocr=litert_xnnpack` |
| Reader temporary GPU override | Passed | User selected GPU; recognizer `preferGpu=true`, detector and completed page OCR report `litert_gpu_delegate` |
| Override does not change saved CPU | Passed | Read persisted preference after GPU OCR: still CPU |
| Reader exit restores Default | Pending | Requires exiting and reopening reader after temporary override |

Representative CPU page OCR times observed during the user's live reading: 187, 303, 408, 621, 916, 1091, 1116, and 1298 ms. These are different pages with different text counts and are functional observations, not a matched CPU/GPU benchmark. Other vision and inpainting stages contribute additional time.

After the reader GPU selection, two 13-block pages completed with OCR times 3707 ms and 1435 ms. The first includes the transition to newly created GPU sessions. These were not matched to the CPU pages, so they do not establish a CPU/GPU speed ratio.

Selected runtime evidence:

```text
23:51:26.536 PaddleOCR v6 LiteRT configured: widths=[160, 320, 480, 640, 1600], preferGpu=false allowXnnpackFallback=true cpuThreads=4, dictionary=18708
23:51:26.542 PaddleOCR detector LiteRT loaded: provider=litert_xnnpack input=[1,960,960,3]
23:51:26.542 LiteRT init: PaddleOCR v6 recognizer and detector configured (device=CPU, CPU threads=4, CPU fallback enabled)
23:51:29.787 [translation_perf] providers(detector=cpu, segmenter=qnn_gpu, panel=qnn_gpu, ocr=litert_xnnpack) stage=recognition total=3244ms ocr=1115.943021ms (blocks=11 batched=false batchSize=1 dynamic=false governorReason=b1_default)
23:54:46.448 PaddleOCR v6 LiteRT configured: widths=[160, 320, 480, 640, 1600], preferGpu=true allowXnnpackFallback=true cpuThreads=4, dictionary=18708
23:54:47.477 PaddleOCR detector LiteRT loaded: provider=litert_gpu_delegate input=[1,960,960,3]
23:54:47.477 LiteRT init: PaddleOCR v6 recognizer and detector configured (device=GPU, CPU threads=4, CPU fallback enabled)
23:54:53.178 [translation_perf] providers(detector=cpu, segmenter=qnn_gpu, panel=qnn_gpu, ocr=litert_gpu_delegate) stage=recognition total=5699ms ocr=3706.62953ms (blocks=13)
23:55:05.601 [translation_perf] providers(detector=cpu, segmenter=qnn_gpu, panel=qnn_gpu, ocr=litert_gpu_delegate) stage=recognition total=3042ms ocr=1435.427187ms (blocks=13)
```

The user confirmed they are actively testing the default CPU route. Automated touches were stopped to avoid competing with their reading; further checks will use their selections or a coordinated uninterrupted test window.
