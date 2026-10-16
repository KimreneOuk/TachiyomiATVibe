---
title: "Review: PaddleOCR GPU/CPU Device Selection & Reader Override"
kind: review
comments: none
---

Review of changes implementing persistent PaddleOCR GPU/CPU device selection and temporary in-reader override with LiteRT XNNPACK 4-thread CPU routing.

**Scope of Review**:

- Domain & Preferences: `TranslationPreferences.kt` (`PaddleOcrComputeDevice` enum, `paddleOcrComputeDevice()`).
- Reader UI & State: `ReaderViewModel.kt`, `ReaderActivity.kt`, `TranslationSettingsSheet.kt`, `SettingsTranslationScreen.kt`, `strings.xml`.
- Engine & Pipeline Lifecycle: `EngineLane.kt`, `SinglePageOnnxPhase.kt`, `TranslationPipeline.kt`, `TranslationManager.kt`.
- Recognition Engines: `RoiPageRecognitionEngine.kt`, `PaddleOcrV6DetEngine.kt`, `PaddleOcrV6SmallEngine.kt`.
- Threading & Hardware Fallbacks: LiteRT `Accelerator.CPU` + `CpuOptions(numThreads = 4)`, ONNX CPU routing fallback.
- Regression Tests: `EngineLanePaddleDeviceTest.kt`.

## Executive Summary

| Category | Status | Primary Observation |
| --- | --- | --- |
| **Threading & CPU Options** | [VERIFIED] | LiteRT `Accelerator.CPU` correctly configures `CompiledModel.CpuOptions(numThreads = 4)` for both detector and recognizer. |
| **Reader Lifecycle** | [VERIFIED] | Temporary override is reset in `ReaderViewModel.init` and `onCleared()`; does not leak to other sessions or persist across app restarts. |
| **Batch & Session Isolation** | [VERIFIED] | `TranslationSessionCoordinator` strictly mutually excludes reader and batch sessions; quiescence is joined before switching; `BatchChapterTranslator` runs `ensureEnginesBuiltFor` only once during initial setup. |
| **Hardware Fallbacks** | [VERIFIED] | GPU failures (init or invoke) gracefully fall back to LiteRT CPU (4 threads). ONNX fallback skips GPU/HTP probing when CPU device is selected. |
| **UI Formatting & Layout** | [ADDRESSED] | Default chip renders as `Default: %s` (avoiding nested parentheses); `FlowRow` includes `verticalArrangement = Arrangement.spacedBy(extraSmall)`. |
| **Verification & Tests** | [VERIFIED] | `:app:compileStandardDebugKotlin` and 19 focused unit tests pass cleanly. |

## Detailed Findings

### 1. [VERIFIED] Session Mutual Exclusion & Engine Lifecycle Isolation

- **Location**: `TranslationSessionCoordinator.kt` (lines 33-83), `BatchChapterTranslator.kt` (line 344), `EngineLane.kt` (lines 558-598)
- **Analysis**:
  - `TranslationSessionCoordinator` enforces mutual exclusion between reader sessions and chapter batch jobs:
    - Reader requests arriving during `BATCH_SESSION` are rejected (`SessionRejection.BATCH_ACTIVE`) unless explicitly confirmed; switching joins the batch to quiescence before admitting reader work.
    - Batch requests arriving during `READER_SESSION` are rejected (`SessionRejection.READER_ACTIVE`).
  - `BatchChapterTranslator.ensureEnginesBuiltFor` is invoked exclusively during initial chapter engine setup (`pageKey = "<engine-setup>"`), not per batch page.
  - Furthermore, `SinglePageOnnxPhase` sets `useReaderPaddleOverride = true` whereas `BatchChapterTranslator` uses `useReaderPaddleOverride = false`, with all engine mutation protected under `NativeRunQuarantine` and `engineRebuildMutex`.
  - Rebuilding ping-pong cannot occur under supported flows because batch and reader execution never concurrently interleave across page boundaries.

### 2. [ADDRESSED] Reader Default FilterChip Label Formatting

- **Location**: `TranslationSettingsSheet.kt` (line 196) & `strings.xml` (`reader_paddle_ocr_device_default`)
- **Analysis**:
  - `reader_paddle_ocr_device_default` string updated to `"Default: %s"`.
  - When the default is CPU (`"CPU (4 threads)"`), the chip renders cleanly as `Default: CPU (4 threads)` without nested parentheses.

### 3. [ADDRESSED] FlowRow Vertical Spacing

- **Location**: `TranslationSettingsSheet.kt` (lines 186-192)
- **Analysis**:
  - `FlowRow` now specifies `verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)` alongside `horizontalArrangement`.
  - Prevents 0dp vertical collision when chips wrap onto multiple rows on compact displays or large font scale settings.

### 4. [VERIFIED] LiteRT Threading API & XNNPACK Alignment

- **Location**: `PaddleOcrV6DetEngine.kt` (lines 378-380) and `PaddleOcrV6SmallEngine.kt` (lines 743-745)
- **Analysis**:
  - LiteRT 2.3.0 `CompiledModel.Options(Accelerator.CPU)` activates the XNNPACK delegate by default on Android.
  - Setting `cpuOptions = CompiledModel.CpuOptions(numThreads = 4)` correctly configures the CPU thread pool to 4 worker threads.
  - Execution provider labels are set to `"litert_xnnpack"`.
  - In `RoiPageRecognitionEngine.kt`, both recognizer and detector receive `numThreads = 4` and `preferGpu = (device == PaddleOcrComputeDevice.GPU)`.
  - Verification: Verified against compiled LiteRT 2.3.0 classes and unit tests.

### 5. [VERIFIED] Reader Lifecycle and State Boundaries

- **Location**: `ReaderViewModel.kt` (lines 559, 794, 1759)
- **Analysis**:
  - `ReaderViewModel.init` immediately resets `translationManager.setReaderPaddleOcrComputeDeviceOverride(null)`.
  - `ReaderViewModel.onCleared()` resets `translationManager.setReaderPaddleOcrComputeDeviceOverride(null)`.
  - The temporary override survives activity orientation changes and sheet dismissals within the session, but is discarded upon exiting the reader activity.
  - Reactive Compose state wiring updates the UI immediately when chips are toggled.

### 6. [VERIFIED] Hardware Fallback Paths

- **Location**: `RoiPageRecognitionEngine.kt` (lines 488-500), `PaddleOcrV6SmallEngine.kt` (lines 690-715), `PaddleOcrV6DetEngine.kt` (lines 184-198)
- **Analysis**:
  - If GPU fails during detector/recognizer initialization, `allowXnnpackFallback = true` catches the failure, logs a warning, and switches to CPU with 4 threads without throwing to the caller.
  - If GPU fails during runtime inference in `PaddleOcrV6SmallEngine.recognize`, the catch block switches to CPU and retries.
  - If LiteRT fails entirely and the system falls back to ONNX, `resolvePaddleOcrProvider()` guarantees that if `paddleOcrComputeDevice == PaddleOcrComputeDevice.CPU`, `PaddleOcrSessionFactory.resolve(PaddleOcrExecutionProvider.CPU)` is selected directly, bypassing any QNN GPU/HTP checks.

## Conclusion & Recommendation

All aspects of the implementation are verified:

1. Persistent user preference in Settings (`GPU` default, `CPU` with 4 threads).
2. Ephemeral reader-scoped override in Reader Translation Sheet.
3. Clean lifecycle teardown on Reader exit.
4. Robust CPU thread configuration via LiteRT `CpuOptions(numThreads = 4)`.
5. Session coordinator and engine lifecycle isolation prevent interleaved rebuild churn.
6. UI polish (label format and FlowRow spacing) addressed.

The changes are verified clean and ready for merge.
