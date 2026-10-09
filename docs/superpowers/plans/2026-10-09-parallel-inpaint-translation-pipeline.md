# Parallel Inpainting & Translation Pipeline + PaddleOCR Governor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce single-page translation latency to 2.2s–3.5s by truly overlapping Hexagon NPU inpainting with HTTP translation and preventing PaddleOCR dynamic batch governor downshifting.

**Architecture:** Decouple inpainting coroutine dispatch from `SinglePageOnnxPhase` so OCR text blocks immediately unblock HTTP translation on `Dispatchers.IO` while inpainting runs on `Dispatchers.Default` (Hexagon HTP); normalize PaddleOCR batch latency by leaf count in `RoiPageRecognitionEngine` to preserve B8–B16 throughput; fix `PROVIDER_GOVERNOR_WAIT` trace span placement.

**Tech Stack:** Kotlin Coroutines (`async`/`await`, `Deferred`), ONNX Runtime, Android Graphics (`Bitmap`), Flow / SQLite.

## Global Constraints

- Never push commits to remote repository (local commits on branch `release/apk-6-candidate` only).
- Zero visual pop-in: Canvas rendering must wait for both inpainting and translation before displaying to the reader.
- Strict bitmap lifecycle safety: Guarantee recycling on normal completion, cancellation, and error paths.
- Thread isolation: Inpainting runs on `Dispatchers.Default` (via Qualcomm QNN HTP), HTTP translation runs on `Dispatchers.IO`.

---

### Task 1: Normalize PaddleOCR Batch Governor Latency Evaluation

**Files:**
- Modify: [`app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt`](file:///C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe/app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt#L405-L435)
- Test: [`app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrRollingP95HysteresisDowngradePolicyTest.kt`](file:///C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe/app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrRollingP95HysteresisDowngradePolicyTest.kt)

**Interfaces:**
- Consumes: `completedCoordinator.lastBatchTrace` (`batchLatencyMs: Double`, `leafCount: Int`)
- Produces: Normalized per-crop sample `trace.batchLatencyMs / trace.leafCount.coerceAtLeast(1)` fed into `paddleBatchGovernor.record(...)` with calibrated per-crop threshold, preventing multi-leaf batches (e.g. 16 leaves in 1100ms) from falsely triggering B1 downgrade.

- [ ] **Step 1: Write unit test verifying that batched execution with healthy per-leaf latency does not trigger downgrade**
- [ ] **Step 2: Run test to observe failure/baseline**
- [ ] **Step 3: Update `RoiPageRecognitionEngine.recordPaddleBatchLatencies` to pass per-leaf normalized latency to the governor (or scale threshold by leaf count)**
- [ ] **Step 4: Run unit tests to verify they pass**
- [ ] **Step 5: Commit changes**

---

### Task 2: Decouple Inpaint Coroutine Dispatch to Allow Immediate HTTP Translation

**Files:**
- Modify: [`app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt`](file:///C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe/app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt#L1160-L1275)
- Modify: [`app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt`](file:///C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L570-L670)

**Interfaces:**
- Consumes: `pageTranslation.blocks`, `bitmap`, `recognitionEngine.inpaint(bitmap, pageTranslation)`
- Produces: `OnnxPhaseResult.inpaintJob: Deferred<Bitmap?>?` returned immediately upon OCR finish without suspending the caller thread or running inpaint synchronously before `translateSinglePageHttpRender` begins.

- [ ] **Step 1: Move `updatePageFromCurrentSnapshot("single-page inpaint running")` inside `inpaintJob` coroutine**
- [ ] **Step 2: Ensure `processSinglePage` and `translateSinglePageOnnx` return immediately upon OCR completion with `inpaintJob` actively running on `Dispatchers.Default`**
- [ ] **Step 3: In `TranslationPipeline.kt`, ensure `translateSinglePageHttpRender` is invoked immediately without waiting for inpainting**
- [ ] **Step 4: Run unit tests to verify compilation and pipeline lifecycle**
- [ ] **Step 5: Commit changes**

---

### Task 3: Scope `PROVIDER_GOVERNOR_WAIT` Telemetry Span and Synchronize Inpainting before Rendering

**Files:**
- Modify: [`app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt`](file:///C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L270-L330)

**Interfaces:**
- Consumes: `ctx.inpaintJob: Deferred<Bitmap?>?`, `runTranslate(pageTranslation)`
- Produces: Accurate trace spans where `PROVIDER_GOVERNOR_WAIT` only records `governor.acquire()`, and `inpaintJob.await()` joins right before `renderPageBitmap()`

- [ ] **Step 1: Adjust `governorSpan` in `SinglePageHttpRenderPhase.kt` to only wrap `governor.acquire()`**
- [ ] **Step 2: Verify `inpaintJob.await()` joins concurrently after `runTranslate()` finishes and handles any inpainting exception safely**
- [ ] **Step 3: Run unit tests to verify behavior**
- [ ] **Step 4: Commit changes**

---

### Task 4: Build, Deploy & Verify on Device

**Files:**
- Run: `./gradlew assembleDevArm64Debug`
- Deploy: `adb install -r ...`
- Profile: [`scripts/trace_stream.ps1`](file:///C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe/scripts/trace_stream.ps1) & [`scripts/aggregate_stats.py`](file:///C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiATVibe/scripts/aggregate_stats.py)

- [ ] **Step 1: Build APK using Android Studio JBR**
- [ ] **Step 2: Install APK onto OnePlus PKG110**
- [ ] **Step 3: Stream live logcat while reading and translating chapter pages**
- [ ] **Step 4: Confirm inpaint and translation timestamps overlap in trace log**
- [ ] **Step 5: Confirm PaddleOCR batch governor maintains B8/B16 without dropping to B1**
- [ ] **Step 6: Measure and report total page-turn latency reduction**
