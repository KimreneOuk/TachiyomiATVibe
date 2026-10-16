---
title: "Phase 2: Fine-Grained Inference Instrumentation & Telemetry Calibration"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Phase 2: Fine-Grained Inference Instrumentation & Telemetry Calibration

## Overview

Based on the [Phase 0 Truth Audit](../phase-0-truth-audit), prior telemetry conflated queue wait, bitmap scaling, padding, NCHW buffer packing, ONNX `session.run()`, and CTC decoding into a single `waitMs` metric. This phase instruments exact stage-by-stage timings to establish clean separation between neural execution and mobile pipeline overhead across both LiteRT and ONNX backends.

## Instrumentation Contracts

1. **Explicit Timing Breakdown**:
  - `preprocessMs`: AR-preserving scaling, canvas padding, and float buffer packing.
  - `neuralForwardMs`: Dedicated nanosecond timestamps immediately surrounding `session.run()` / LiteRT `interpreter.run()`.
  - `ctcDecodeMs` / `dbPostProcessMs`: Argmax, token deduplication, blank removal, and dictionary mapping (recognizer); DB contour binarization and unclip poly extraction (detector).
  - `queueWaitMs` & `admissionWaitMs`: Dispatch and synchronization latency under `batchCallMutex`.
  - `coordinatorMs`: Page-level spatial grouping, leaf dispatch, and block assembly.
2. **Device Hardware Telemetry**:
  - Device identity: SoC (Snapdragon 8 Gen 3), GPU (Adreno 750), NPU (Hexagon HTP), CPU cores.
  - Execution provider & delegate confirmation: Validate whether LiteRT delegate runs on GPU (`litert_gpu`) without CPU fallback nodes, or gracefully switched to XNNPACK (`xnnpack`).
  - Provider labels mapped cleanly into `TranslationPipelineDiagnostics` and `TranslationTrace` tokens.
3. **Progress & Status**:
  - Telemetry provider tokens (`xnnpack` / `litert_gpu`) wired in `TranslationPipelineDiagnostics.kt` and `TranslationTrace.kt`.
  - Explicit timing breakdown integrated alongside the LiteRT runner paths in `PaddleOcrV6SmallEngine.kt` and `PaddleOcrV6DetEngine.kt`.

## Dependencies

- Prerequisite: [Phase 1: Accuracy Baseline](../phase-1-accuracy-baseline)
- Successor: [Phase 3: LiteRT Recognition Migration](../phase-3-litert-rec-migration), [Phase 4: LiteRT Detection Migration](../phase-4-litert-det-migration)

## Runtime Instrumentation Update (2026-10-13)

- Single-crop ONNX and LiteRT recognizer diagnostics now report `preprocessMs`, `neuralForwardMs`, and `ctcDecodeMs` separately.
- ONNX batch execution records the same three stages in `PaddleOcrV6BatchTelemetry`, including attempted batches that are retried or downgraded.
- Detector ONNX and opt-in LiteRT paths report `preprocessMs`, `neuralForwardMs`, and `dbPostProcessMs`; LiteRT GPU recovery is separately visible as `providerRecoveryMs`.
- Page diagnostics report `coordinatorMs`, `queueWaitMs`, `admissionWaitMs`, `dispatchMs`, and batch latency. `admissionWaitMs` now measures time waiting to acquire `batchCallMutex`; `dispatchMs` measures the protected recognition call.
- Verification: `./gradlew.bat :app:compileStandardDebugKotlin --console=plain` passed. Device-side timing calibration and delegate confirmation remain pending.

## Review Follow-up: Detector and Batch Telemetry (2026-10-13)

- Page logs now identify `coordinatorWallMs` as wall time and separately aggregate detector call count, `detectorMs`, `detectorPreprocessMs`, `detectorNeuralForwardMs`, and `detectorPostProcessMs`. Recognition `queueWaitMs`, `admissionWaitMs`, `dispatchMs`, and `batchLatencyMs` stay independently reported.
- `PaddleOcrV6BatchTelemetry.actualBatchSizes` records batches whose results were accepted. `attemptedBatchSizes` records tiers tried before successful completion, including provider failures and latency downgrades.
- The updated Kotlin compilation passed. Unit tests and device-side calibration were not run during this follow-up.

