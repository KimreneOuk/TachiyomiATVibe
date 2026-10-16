---
title: "Review: Phase 4 LiteRT DBNet Detector & Phase 2 Instrumentation Seam"
kind: review
comments: none
---

# Review: Phase 4 LiteRT DBNet Detector & Phase 2 Instrumentation Seam

**Review Target**: Uncommitted Phase 4 / Phase 2 implementation changes across:

- `PaddleOcrV6DetEngine.kt`
- `PaddlePageOcrCoordinator.kt`
- `PaddleOcrV6SmallEngine.kt`
- `PaddleOcrV6BatchExecutor.kt`
- `TranslationPipelineDiagnostics.kt`
- `TranslationTrace.kt`

**Skill Applied**: `traycer-review` (Evidence-based verification against active workspace source).
**Operational Constraints**: Do not edit production source; build command has passed; no tests executed.

## Executive Summary

| Category | Status | Primary Observation |
| --- | --- | --- |
| **Performance & Hot-Path GC** | [DEFECT] | `preprocessLiteRt` executes **5.5 million individual JNI/buffer puts** per frame, and `detectLinesLiteRt` allocates a **3.68 MB `FloatArray` per frame** on the JVM heap. |
| **Parity & Validation Contract** | [WARNING] | Verification script `compare_litert_det.py` warped pages to $960 \\times 960$, leaving Android's centered letterbox, mean-color zero padding, and border artifact behavior unvalidated. |
| **Timing & Observability** | [WARNING] | `[paddle_page]` `coordinatorMs` subsumes all learned DBNet text detection calls without logging detection latency or invocation counts, masking stage distribution. |
| **Telemetry & Metrics** | [WARNING] | `PaddleOcrV6BatchTelemetry.actualBatchSize` reports attempted failed/downgraded tiers (e.g. B8) rather than committed/successful batch sizes. |
| **Dead Code / Seam Hygiene** | [CLEANUP] | `needsUnpaddedReread` is initialized to `false` and never mutated, rendering the re-wired fallback loop in `PaddlePageOcrCoordinator` dead code. |
| **LiteRT Letterbox & Coordinate Mapping** | [VERIFIED] | `backProjectLetterboxed` correctly projects map-space coordinates back to arbitrary crop bounds and clips boundary edges. |
| **GPU Lifecycle & Fallback** | [VERIFIED] | Detector GPU invoke fallback preserves input buffer contents and latches CPU execution gracefully without buffer re-allocation defects. |
| **Batch Retries & Error Propagation** | [VERIFIED] | `PaddleOcrV6BatchExecutor` accumulates stage timings across downgrades and re-throws `CancellationException` cleanly. |

## Validated Actionable Findings

### 1. [CRITICAL / PERFORMANCE] Unvectorized 5.5 Million Buffer Calls and 3.68 MB Per-Frame Allocation in LiteRT Detector

- **Location**: `PaddleOcrV6DetEngine.kt` (lines 347–350, 403–424)
- **Impact**:
  1. `while (out.hasRemaining()) out.put(0f)` performs $960 \times 960 \times 3 = 2,764,800$ individual method invocations across the direct buffer boundary.
  2. The nested loops perform another $2,764,800$ absolute `out.put(index, float)` calls.
  3. Total: **$5,529,600$ method/JNI calls per speech bubble detection**. In a manga page with 15 bubbles, this triggers **over 82 million method calls**, consuming 30–60 ms in ART overhead alone and destroying the sub-50 ms neural execution target.
  4. In `detectLinesLiteRt`, `FloatArray(OUTPUT_MAP_ELEMENTS)` allocates $921,600$ floats (**3.68 MB**) directly on the JVM garbage-collected heap on every single detection call (~55 MB of garbage generated per 15-bubble page).
- **Remediation**:
  1. Replace the element-by-element float packing with a pooled/scratch array and vectorized bulk transfers (`out.put(scratchArray, offset, length)`), mirroring the pattern already established in `PaddleOcrV6SmallEngine` (`liteRtNhwcScratch`) and the ONNX detector path (`scratchPlane`).
  2. Zero-initialize only the unwritten letterbox padding margins (or memset the buffer once upon allocation and only clean up margins), rather than rewriting 2.76M zeroes on every frame.
  3. Reuse a pre-allocated scratch `FloatArray(OUTPUT_MAP_ELEMENTS)` (cleared on `reclaimPooledMemory()`) for `DbPostProcess.detectLines` probability map extraction instead of reallocating 3.68 MB on every inference.

### 2. [HIGH / VALIDATION CONTRACT] Preprocessing Parity Gap Between Python Audit and Android Runtime

- **Location**: `PaddleOcrV6DetEngine.kt` (lines 389–425) vs `audit/baseline_ocr/compare_litert_det.py` (lines 75–81)
- **Mechanism**:
  - `compare_litert_det.py` evaluated the FlatBuffer candidate by naively stretching full pages to $960 \times 960$ with bilinear interpolation (`cv2.resize(rgb, (960, 960))`).
  - Android runtime (`PaddleOcrV6DetEngine.kt`) uses aspect-preserving centered letterboxing with ImageNet mean-color ($0.0\text{f}$) padding to static $960 \times 960$.
  - Reference ONNX Runtime (`PaddleOcrV6DetEngine.preprocess`) uses dynamic shapes rounded to multiples of 32 without square padding.
- **Impact**:
The recorded parity metrics ($L_\infty \le 0.023$, $\text{MSE} \le 2.56 \times 10^{-7}$, $\text{IoU} \ge 0.999$) documented in `phase-4-litert-det-migration` do not validate the production Android preprocessing pipeline. Specifically:
  1. Convolutions over padded borders (mean color zero) can trigger boundary gradient false positives or suppress edge characters in DBNet.
  2. Bilinear upsampling in the FPN neck behaves differently when aspect ratio is preserved vs stretched.
- **Remediation**:
Re-run `compare_litert_det.py` using identical aspect-ratio letterboxing with $0.0\text{f}$ padding to verify that the model produces IoU $\ge 0.98$ against the reference dynamic-shape ONNX detector on real manga bubbles.

### 3. [MEDIUM / OBSERVABILITY] Coordinator Timing Conflates Learned Detection and Scheduling Overhead

- **Location**: `PaddlePageOcrCoordinator.kt` (lines 216, 313–323)
- **Mechanism**:
    ```kotlin
    val coordinatorStartedAt = nowNanos()
    // ... planInitialRegion invokes paddleDet.detectLines on every bubble ...
    if (OcrDiagnostics.isEnabled()) {
        logcat(LogPriority.INFO) {
            "[paddle_page] coordinatorMs=${nanosToMs(nowNanos() - coordinatorStartedAt)} " +
                "queueWaitMs=${traces.sumOf { it.queueWaitMs }} " +
                "admissionWaitMs=${traces.sumOf { it.admissionWaitMs }} " +
                "dispatchMs=${traces.sumOf { it.dispatchMs }} " +
                "batchLatencyMs=$totalInferMs leaves=$leafCount batches=${traces.size}"
        }
    }
    ```
- **Impact**:
`coordinatorMs` measures wall-clock duration from start to finish of `recognizePage`. It now includes the cumulative execution time of all `paddleDet.detectLines` calls across all bubbles (which can total 500 ms – 1500 ms).
However, `[paddle_page]` only outputs recognition batch metrics (`queueWaitMs`, `admissionWaitMs`, `dispatchMs`, `batchLatencyMs`). It logs neither `totalDetMs` nor `detectionCount`.
Operators inspecting `[paddle_page]` will see `coordinatorMs=1400` with `dispatchMs=200`, creating the false appearance of 1200 ms of unaccounted dispatcher/queuing stalls.
- **Remediation**:
Track cumulative detector time during `planInitialRegion` and log it explicitly in `[paddle_page]` (e.g. `detMs=$totalDetMs detCalls=$detCalls`).

### 4. [MEDIUM / TELEMETRY CONTRACT] `PaddleOcrV6BatchTelemetry.actualBatchSize` Misreports Downgraded Batches

- **Location**: `PaddleOcrV6BatchExecutor.kt` (lines 85, 108, 412–413)
- **Mechanism**:
    ```kotlin
    actualBatchSizes += actualBatch
    val overBudget = latencyBudgetMs != null && attempt.latencyMs > latencyBudgetMs
    if (overBudget && currentBatchSize > MIN_BATCH_SIZE) {
        downgradeReasons += PaddleOcrV6BatchDowngradeReason.LATENCY_FAILURE.wireValue
        currentBatchSize = lowerBatch(currentBatchSize)
        continue
    }
    // ...
    val actualBatchSize: Int
        get() = actualBatchSizes.maxOrNull() ?: 0
    ```
- **Impact**:
When an attempt at chunk size 8 fails the latency budget or throws a provider failure, `actualBatchSizes` records `8`. After downgrading, the batch succeeds at chunk size 4.
Because `actualBatchSize` computes `actualBatchSizes.maxOrNull()`, it returns `8` instead of `4`. Telemetry reports that batch size 8 was achieved, corrupting governor hysteresis and downstream telemetry dashboards.
- **Remediation**:
Only add to `actualBatchSizes` when an attempt is committed, or separate `attemptedBatchSizes` from `successfulBatchSizes` so `actualBatchSize` reflects executed batches.

### 5. [LOW / CLEANUP] Dead Code in Coordinator Unpadded Reread Fallback

- **Location**: `PaddlePageOcrCoordinator.kt` (lines 260–295, 393)
- **Mechanism**:
`needsUnpaddedReread` is initialized to `false` in `planInitialRegion` (line 393) and is never set to `true` anywhere in the codebase.
The fallback filter `val fallbackStates = states.filter { it.needsUnpaddedReread && it.text.isEmpty() }` evaluates to empty on every page.
- **Impact**:
Wiring `paddleDet = paddleDet` into `fallbackPlan` at line 273 is dead code that will never execute in production.
- **Remediation**:
Either remove the unused fallback block or wire the trigger condition that was intended to request an unpadded reread.

## Verified Invariants & Seam Positives

1. **LiteRT Letterbox Coordinate Projection**:
 `PaddleOcrV6DetEngine.backProjectLetterboxed` properly offsets coordinates by `(bbox - offset) * (cropDim / resizedDim)` and coerces bounding boxes within `[0, cropDim - 1]`. Box dimensions with non-positive span are cleanly filtered out (`mapped[2] > mapped[0] && mapped[3] > mapped[1]`).
2. **Detector GPU Fallback Lifecycle**:
 Unlike the earlier recognition runner issue, `PaddleOcrV6DetEngine.detectLinesLiteRt` preserves the populated `inputBytes` during invoke fallback: `initializeLiteRtCpu` reinitializes the interpreter without wiping direct input buffers, and latches `liteRtPreferGpu = false` so subsequent frames run directly on CPU.
3. **Phase 3 Recognizer Hardening**:
 The uncommitted changes in `PaddleOcrV6SmallEngine.kt` verify all previously requested remediations:
  - GPU fallback correctly re-populates input buffers via `writeInput(inputFloats)`.
  - Recognizer crops in `recognizeBucketBatch` are pre-grouped by width bucket (`indicesByWidth`) to prevent GPU delegate thrashing across mixed-aspect-ratio bubbles.
  - Provider labels (`litert_xnnpack` and `litert_gpu_delegate`) are properly mapped into `TranslationPipelineDiagnostics` and `TranslationTrace`.
  - Thread visibility is ensured via `@Volatile` annotations on model files and provider labels.
4. **Timing Breakdown & Batch Retries**:
 `PaddleOcrV6BatchExecutor` cleanly instruments `preprocessMs`, `neuralForwardMs`, and `ctcDecodeMs`. When batch retries or latency downgrades occur, timings from all attempts are accumulated into total batch telemetry, preserving accurate resource accounting.

## Verdict & Recommended Action

- **Architecture**: **APPROVED**. The seam design, fallback latches, coordinate projection, and fine-grained telemetry provide a solid foundation for the Phase 4 detector integration.
- **Release Blocker**: **Finding 1 (5.5 million JNI puts & 3.68 MB per-frame allocation)** must be remediated to prevent severe CPU throttling and GC stalls during page reading.
- **Verification Gate**: **Finding 2 (Aspect-ratio letterbox parity validation)** must be evaluated with letterboxed inputs before enabling LiteRT detector by default in production.
