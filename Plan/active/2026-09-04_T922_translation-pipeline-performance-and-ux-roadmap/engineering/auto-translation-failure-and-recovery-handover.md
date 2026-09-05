# Task T922: Auto-Translation Pipeline Failure Root Cause & Claude Code Handover Guide

**Document ID:** `T922-ENG-HANDOVER-01`  
**Date:** 2026-09-04  
**Author:** Antigravity AI Senior Architect  
**Recipient / Target:** Claude Code AI Agent (Incoming Specialist)  
**Target Device:** OnePlus Ace 5 (`PKG110`, Snapdragon 8 Gen 3 / SM8650, Adreno 750, Hexagon v75 NPU, Android 16 / API 36)  
**Workspace Worktree:** `C:\Users\User\.gemini\antigravity\worktrees\TachiyomiAT-1.16.8-dev\optimize_translation_pipeline_ux`

---

## 1. Executive Summary

This document provides a complete, self-contained engineering handover for the Auto-Translation pipeline investigation, state synchronization fixes, and the Qualcomm QNN runtime execution failure (`Error code: 1100`).

### Current Status at Handover:
1. **Resolved & Verified:** The pipeline state deadlock that caused pages to freeze permanently on `"Reading text."` during Auto-Translation has been patched in `CleanedPublication.kt` and `ReaderTranslationFeedback.kt`, with comprehensive unit tests passing in `:app:testStandardDebugUnitTest`.
2. **Diagnosed with Logcat Evidence:** Auto-translation subsequently failed across all pages (1 through 7) due to Qualcomm QNN error `1100` (`QNN_GRAPH_ERROR_EXECUTION_ENGINE_ERROR`) inside `OnnxBubbleSegmenter.segment(OnnxBubbleSegmenter.kt:98)` executing on Hexagon HTP.
3. **Ready for Implementation:** The exact 3-step resolution (routing `bubble_segmenter` to CPU/XNNPACK, restoring dynamic finalization mode 3, and adding runtime fail-closed safety) is designed and ready for the incoming agent to apply.

---

## 2. Issue 1 (Resolved): Eviction State Deadlock ("Reading text." Chip Freeze)

### Symptom:
During rolling Auto-Translation, pages (e.g. pages 2 and 3) showed purple/pink indicator dots on the `SlotRail` in `AutoTranslationStatus.kt` (Material You dynamic theme tones for `primary` / `tertiary`), but their reader feedback chip remained stuck on `"Reading text."` even after inpainting completed.

### Root Cause:
1. In `CleanedPublication.kt`, `store.patchPage` recorded `inpaintStatus = StageStatus.READY`, but failed to synchronize `ocrStatus = StageStatus.READY` in the durable store.
2. When the user navigated past page 4, pages 2 and 3 were evicted from the active lookahead window before the translation stage committed.
3. The durable store entries remained stranded with `ocrStatus = RUNNING, inpaintStatus = READY`.
4. In `ReaderTranslationFeedback.kt:83`, `ocrStatus == StageStatus.RUNNING` forced `ReaderPageFeedbackState.ReadingText`, locking the chip.

### Code Fixes Applied & Verified:
- **`app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt` (lines 144–168):**
  Synchronized `ocrStatus = StageStatus.READY`, `blocks`, `inpaintMaskBoxes`, and dimensions into `store.patchPage`.
- **`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationFeedback.kt` (lines 78–87):**
  Guarded `ocrStatus == StageStatus.RUNNING && !isCleanedImageReady && inpaintStatus != StageStatus.READY -> ReaderPageFeedbackState.ReadingText`.
- **`app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationFeedbackTest.kt`:**
  Added unit tests verifying stale OCR running states are ignored when inpainting is ready. All passed.

---

## 3. Issue 2 (Active Root Cause): Auto-Translation Crash on All Pages (QNN Error 1100)

### Symptom:
When running Auto-Translation in the new build, pages failed immediately:
`pageKey=1.png ocr=FAILED error=ONNX recognition failed: Error code - ORT_FAIL - message: Non-zero status code returned while running QNN_10113738514267890715_1 node. Name:'QNNExecutionProvider_QNN_10113738514267890715_1_0' Status Message: QNN graph execute error. Error code: 1100`

### Physical Device Logcat Evidence (PID `18929`):
```log
09-04 18:37:22.642 18929 32247 E onnxruntime: [E:onnxruntime:, sequential_executor.cc:671 ExecuteKernel] 
Non-zero status code returned while running QNN_10113738514267890715_1 node. 
Name:'QNNExecutionProvider_QNN_10113738514267890715_1_0' Status Message: QNN graph execute error. Error code: 1100

09-04 18:37:22.644 18929 32247 E SinglePageOnnxPhase: ONNX recognition failed for 1.png; not falling back to full-page ML Kit
09-04 18:37:22.644 18929 32247 E SinglePageOnnxPhase: ai.onnxruntime.OrtException: Error code - ORT_FAIL - message: 
Non-zero status code returned while running QNN_10113738514267890715_1 node. Error code: 1100
	at ai.onnxruntime.OrtSession.run(Native Method)
	at ai.onnxruntime.OrtSession.run(OrtSession.java:421)
	at ai.onnxruntime.OrtSession.run(OrtSession.java:268)
	at ai.onnxruntime.OrtSession.run(OrtSession.java:236)
	at eu.kanade.translation.segmentation.OnnxBubbleSegmenter.segment(OnnxBubbleSegmenter.kt:98)
	at eu.kanade.translation.recognition.RoiPageRecognitionEngine.analyze$lambda$0$1(RoiPageRecognitionEngine.kt:293)
	at eu.kanade.translation.webtoon.WebtoonSlidingDetector.segmentSliding(WebtoonSlidingDetector.kt:195)
	at eu.kanade.translation.pipeline.SinglePageOnnxPhase.processSinglePage(SinglePageOnnxPhase.kt:1001)
```

### Why did this happen?
1. **The Build Delta:**
   - In the earlier build (PID `26779`), QNN options passed:
     `soc_model = "57", htp_arch = "75", htp_performance_mode = "burst", htp_graph_finalization_optimization_mode = "3"`.
     With Mode 3 optimization, the compiler legalized the unquantized FP32 YOLO11 `bubble_segmenter.onnx` graph for DSP execution.
   - In the new build (PID `18929`), `buildGenericHtpOptions()` stripped those options to bare `backend_type = "htp"`. Without optimization mode 3 and target arch, QNN HTP emitted an unoptimized graph partition that crashed Qualcomm's Hexagon DSP execution engine at runtime (`Error code: 1100`).
2. **Session Invalidation & Cascade:**
   - Because `createSessionWithFallback()` only tests session creation (not inference), `bubble_segmenter.onnx` was marked `SUPPORTED`.
   - When `segment()` ran on `1.png`, the DSP execution engine crashed. `OnnxBubbleSegmenter` had no try-catch or runtime fallback, leaving the faulted DSP session active in memory.
   - Pages 2 through 7 hit the same poisoned session, causing all pages to fail immediately.
3. **Manual vs Auto-Translation Parity:**
   - Manual translation and Auto-translation use the exact same backend (`translateSinglePageOnnx`). Manual translation worked in PID `26779` solely because that process was running the old build with optimization mode 3. In the new build, manual translation would fail identically.

---

## 4. Hardware Acceleration Target Matrix

Per the master architecture plan (`Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/ROADMAP.md`), the routing contracts are:

| Model / Workload | Primary Target | Fallback Route | Status & Latency |
| :--- | :--- | :--- | :--- |
| **AOT-GAN Inpainting** | **Qualcomm Hexagon HTP** | **CPU / XNNPACK** | **Verified 320 ms steady-state** (50.43 dB PSNR, 181 ms cache reload). |
| **Bubble Segmenter (YOLO11)** | **CPU / XNNPACK** | None | **~150 ms on Cortex-X4 CPU**. Unquantized FP32 model; does not belong on DSP. |
| **Text Detector (Paddle / v4)** | **CPU / XNNPACK** | None | **~165–200 ms on CPU**. Dynamic quant ops rejected by HTP. |
| **Panel Detector** | **CPU / XNNPACK** | None | **~75 ms on CPU**. |
| **MangaOCR (batched)** | **CPU / XNNPACK** | None | **~600–800 ms on CPU** (8–11 blocks). |

---

## 5. Step-by-Step Implementation Instructions for Claude Code

### Step 1: Route `OnnxBubbleSegmenter` to CPU/XNNPACK
**File:** `app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt`  
**Location:** `initialize(modelFile: File)`

Replace lines 34–38 with:
```kotlin
session = OnnxRuntimeProvider.createSessionWithFallback(
    modelFile.absolutePath,
    useAccelerator = false,
    useXnnpack = true,
    providerSink = { executionProviderLabel = it },
)
```

### Step 2: Restore Finalization Mode 3 & Dynamic Capability in `buildGenericHtpOptions()`
**File:** `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt`  
**Location:** `buildGenericHtpOptions`

Ensure QNN options dynamically use `DeviceCapability` values and Mode 3 optimization while remaining generic across SoCs:
```kotlin
internal fun buildGenericHtpOptions(
    performanceMode: String? = "burst",
    socModel: String? = DeviceCapability.qnnSocModel,
    htpArch: String? = DeviceCapability.qnnHtpArch,
    finalizationMode: String? = "3",
    extraOptions: Map<String, String> = emptyMap(),
): Map<String, String> = buildMap {
    put("backend_type", "htp")
    performanceMode?.let { put("htp_performance_mode", it) }
    socModel?.let { put("soc_model", it) }
    htpArch?.let { put("htp_arch", it) }
    finalizationMode?.let { put("htp_graph_finalization_optimization_mode", it) }
    putAll(extraOptions)
}
```

### Step 3: Implement Runtime Failover in `OnnxBubbleSegmenter`
**File:** `app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt`  
**Location:** `segment(bitmap: Bitmap)`

Add a fail-closed try-catch around `current.run(mapOf("images" to tensor))` so that if any unexpected runtime `OrtException` occurs:
1. Log the error.
2. Mark the model failed in `ModelRoutingEngine.recordFailure`.
3. Re-initialize the session on CPU in-place.
4. Retry the inference once on CPU so the active page never fails.

---

## 6. Verification and Deployment Commands

Run using PowerShell from the worktree root:

```powershell
# 1. Set JDK environment
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 2. Run unit tests
.\gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"
.\gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.viewer.ReaderTranslationFeedbackTest"

# 3. Assemble arm64 debug APK
.\gradlew.bat :app:assembleStandardDebug

# 4. Deploy to connected OnePlus Ace 5
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.100.223:44121 install -r "app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk"

# 5. Launch application
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.100.223:44121 shell am start -n app.kanade.tachiyomi.at.debug/eu.kanade.tachiyomi.ui.main.MainActivity

# 6. Verify translation telemetry in logcat
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.100.223:44121 logcat -d | Select-String -Pattern "translation_perf|translation_page|AOTInpainting"
```
