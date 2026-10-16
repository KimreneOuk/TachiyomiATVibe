---
title: "Review: PaddleOCR LiteRT Recognition Integration Seam"
kind: review
comments: none
---

# Code Review: PaddleOCR v6 LiteRT Recognition Integration Seam

**Target File**: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6SmallEngine.kt`
**Review Scope**: Runtime correctness, lifecycle, concurrency, memory, preprocessing layout, GPU/XNNPACK fallback, and compatibility with current ONNX/batch callers.
**Baseline & Contract**: Opt-in seam for Phase 3 manga recognition LiteRT models (`manga_rec_<W>_fp16.tflite`). ONNX remains production default.

## Executive Summary

| Category | Status | Primary Observation |
| --- | --- | --- |
| **Runtime Correctness** | [DEFECT] | GPU invoke fallback reallocates direct buffers without re-populating crop data, running CPU inference on zeros. |
| **Lifecycle & State** | [WARNING] | GPU fallback is not sticky across runner evictions or adjacent width buckets. |
| **Concurrency & Thread Safety** | [WARNING] | `liteRtModelFiles` lacks `@Volatile` visibility barrier outside synchronized blocks. |
| **Memory & Performance** | [WARNING] | Bounded runner cache (`MAX_RESIDENT_LITERT_RUNNERS = 2`) churns heavily under mixed-width page batches. |
| **Preprocessing Layout** | [VERIFIED] | NHWC float layout `[1, 48, W, 3]` and normalization `(x / 255 - 0.5) / 0.5` match model expectations. |
| **GPU / XNNPACK Fallback** | [VERIFIED] | Strict mode (`allowXnnpackFallback = false`) correctly prevents CPU fallback. |
| **ONNX / Batch Compatibility** | [VERIFIED] | Opt-in guard preserves existing ONNX initialization and batch execution paths. |

## Validated Actionable Findings

### 1. [CRITICAL] GPU Invoke Fallback Runs Inference on Zero-Filled Buffers

- **Location**: `PaddleOcrV6SmallEngine.kt` (lines 583–597, `PaddleOcrV6LiteRtRunner.recognize`)
- **Mechanism**:
    ```kotlin
    val inputBuffer = inputFloats
    inputBuffer.clear()
    writeInput(inputBuffer)
    inputBuffer.position(0)
    inputBytes.clear()

    try {
        invoke()
    } catch (failure: Exception) {
        if (!gpuActive || !allowXnnpackFallback) throw failure
        ...
        closeInterpreterAndDelegate()
        initializeCpu()
        allocateInferenceBuffers() // Replaces inputBytes/inputFloats with fresh zeroed direct buffers
        invoke()                   // Runs inference on all zeros; writeInput was never re-invoked!
    }
    ```
- **Impact**: If GPU `invoke()` throws an execution exception, the fallback catch block reallocates direct memory (`allocateInferenceBuffers()`), but never rewrites the crop image data. The CPU interpreter runs against all zeros, yielding blank or corrupted CTC decoded text instead of a valid OCR result.
- **Remediation**: Re-populate the input buffer inside the fallback block before invoking the CPU interpreter:
    ```kotlin
    closeInterpreterAndDelegate()
    initializeCpu()
    allocateInferenceBuffers()
    inputFloats.clear()
    writeInput(inputFloats)
    inputFloats.position(0)
    inputBytes.clear()
    invoke()
    ```

### 2. [HIGH] Runner Cache Thrashing and GPU Re-initialization Penalty (`MAX_RESIDENT_LITERT_RUNNERS = 2`)

- **Location**: `PaddleOcrV6SmallEngine.kt` (lines 290–297, 345–355, 516)
- **Mechanism**:
  - `MAX_RESIDENT_LITERT_RUNNERS = 2` limits the LRU cache to two resident width runners.
  - In `recognizeBucketBatch()`, crops are processed via `crops.map { recognizeWithConf(it) }`, where each crop selects its tightest width bucket (`160`, `320`, `480`, `640`, `1600`).
  - Manga pages commonly interleave speech balloons of 3+ distinct aspect ratios (e.g., small exclamation at 160, dialogue sentence at 320, wider caption at 480/640).
- **Impact**: Interleaved widths evict resident runners continuously. Each eviction closes the `Interpreter` and `GpuDelegate`, while each cache miss initializes a new `GpuDelegate` (OpenCL kernel compilation and device checks) and allocates ~1.5 MB to ~15 MB direct ByteBuffers. This destroys batch throughput and risks native memory fragmentation.
- **Remediation**:
  1. In `recognizeBucketBatch()`, bucket or sort crops by their target width before execution to process all crops of a given width consecutively, preserving input order when constructing the output list.
  2. Alternatively, consider caching up to 5 runners (the total direct buffer memory across all 5 widths combined is only ~28 MB off-heap, and `.tflite` model weights are memory-mapped).

### 3. [MEDIUM] Non-Sticky GPU Failure Causes Repeated Delegate Initialization Failures

- **Location**: `PaddleOcrV6SmallEngine.kt` (`PaddleOcrV6LiteRtRunner`, `createLiteRtRunner`)
- **Mechanism**:
  - When GPU initialization or invocation fails for width $W$, fallback switches only that runner instance to CPU (`gpuActive = false`).
  - If a crop requires a different width $W_2$, `createLiteRtRunner(W2)` still receives `preferGpu = liteRtPreferGpu` (true).
  - Furthermore, if width $W$ is evicted and later recreated, it will attempt GPU delegate setup again.
- **Impact**: If a device fails GPU delegation (e.g., driver incompatibility or shader error), every new or recreated width bucket runner repeats the failed GPU initialization and fallback cycle.
- **Remediation**: When fallback to XNNPACK occurs due to GPU failure, latch `liteRtPreferGpu = false` at the engine level so subsequent runners instantiate directly on CPU without incurring GPU timeout/error penalties.

### 4. [MEDIUM] Thread Visibility Hazard on `liteRtModelFiles`

- **Location**: `PaddleOcrV6SmallEngine.kt` (lines 37, 241, 290)
- **Mechanism**:
  - `private var liteRtModelFiles: Map<Int, File> = emptyMap()` is read in `recognizeWithConf`:
        ```kotlin
        if (liteRtModelFiles.isNotEmpty()) return recognizeWithConfLiteRt(crop)
        ```
  - This check occurs outside `synchronized(liteRtLock)` and the variable is not marked `@Volatile`.
- **Impact**: If `initializeLiteRt` is executed on one thread/coroutine and `recognizeWithConf` is called on a different worker thread, changes to `liteRtModelFiles` may not be immediately visible, potentially attempting the uninitialized ONNX path.
- **Remediation**: Annotate `liteRtModelFiles` (or a boolean flag `isLiteRtActive`) and `executionProviderLabel` with `@Volatile`.

### 5. [LOW / PERFORMANCE] Individual `FloatBuffer.put()` Call Overhead in Preprocessing

- **Location**: `PaddleOcrV6SmallEngine.kt` (lines 461–466, `preprocess`)
- **Mechanism**:
    ```kotlin
    if (channelLast) {
        for (pixel in pixels) {
            out.put(normalize(pixel shr 16 and 0xFF))
            out.put(normalize(pixel shr 8 and 0xFF))
            out.put(normalize(pixel and 0xFF))
        }
    }
    ```
- **Impact**: For a 1600-width crop (`76,800` pixels), this executes **230,400 individual JNI / method invocations** of `out.put(Float)` on every recognition call.
- **Remediation**: Use a reusable float array chunk (or a pooled array) and bulk write with `out.put(floatArray, offset, length)` to reduce buffer call overhead.

### 6. [LOW / ALLOCATION] Unnecessary Sorting of Static Widths in Hot Loop

- **Location**: `PaddleOcrV6SmallEngine.kt` (line 344)
- **Remediation**: Pre-sort into a static immutable list:
    ```kotlin
    private val SUPPORTED_LITERT_WIDTHS_SORTED = listOf(160, 320, 480, 640, 1600)
    ```

### 7. [OBSERVABILITY] Telemetry Provider String Mapping Mismatch

- **Location**: `PaddleOcrV6SmallEngine.kt` vs `TranslationPipelineDiagnostics.kt:1120`
- **Mechanism**:
  - The engine sets `executionProviderLabel` to `"litert_xnnpack"` or `"litert_gpu_delegate"`.
  - `TranslationPipelineDiagnostics.providerFromLabel` checks `normalized == "xnnpack"`, `normalized.contains("qnn_htp")`, etc., but does not match `"litert_xnnpack"` (due to exact string check) or `"litert_gpu_delegate"`.
- **Impact**: Both execution modes map to `TranslationTraceProvider.NONE` in diagnostic spans.
- **Remediation**: Update `providerFromLabel` when wiring LiteRT into the UI/diagnostics to recognize `litert_xnnpack` and `litert_gpu_delegate`.

## Verdict

- **Code Architecture**: Solid seam design with clear opt-in boundaries and proper resource cleanup hooks (`reclaimPooledMemory`, `forceReleaseNativeBuffers`, `closeBackends`).
- **Blocking Defect for Deployment**: **Finding 1 (GPU fallback re-populates zero buffer)** must be corrected before LiteRT model evaluation on devices, as any GPU invoke fallback will produce silent recognition failures.
