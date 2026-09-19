# TachiyomiAT Codebase Architecture & Technical Debt Audit Report

**Task:** T936 — Architecture, Modularity, Overengineering, Test Suite, and Debt Audit  
**Date:** 2026-09-19  
**Status:** Audit Only (No production modifications)  
**Base:** `codebase_architecture_audit` worktree  

---

## Executive Summary

An exhaustive, skeptical architectural audit of **TachiyomiAT** was conducted across the codebase, specifically targeting the core translation, recognition, rendering, and storage subsystems.

### Codebase Metric Baseline
- **Total Kotlin Files in Repository:** 1,446 files (254,675 lines).
- **Translation Subsystem:** 573 files (85,600 production lines across 285 files; 68,971 test lines across 288 files; 154,571 total lines).
- **Subsystem Share:** The translation layer accounts for **60.69%** of the entire repository codebase.
- **Extreme Monoliths:** 29 files exceed 1,000 lines; 5 files exceed 2,000 lines; 85 files exceed 500 lines.

### Critical Verification & Debunking of Dangerous Misconception
A prior investigation draft recommended deleting `aot-512.onnx` (58.62 MB) as "dead code". **This recommendation was fundamentally false and dangerous.**
Direct code tracing at HEAD confirms:
1. `aot-512.onnx` is actively resolved via `paths.inpaint512Model` in [`RoiPageRecognitionEngine.kt:317`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt#L317).
2. It is passed into [`AOTInpainting.initialize(fixedModelFile, ...)`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt#L115-L124).
3. At lines 118–123, when `HardwareDiscoveryEngine.resolveRoute() == HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP`, it initializes `fixedQnnSession` on the Qualcomm NPU.
4. The Qualcomm QNN HTP backend strictly requires static input tensor dimensions (512x512). Dynamic models crash or fall back. Deleting `aot-512.onnx` would break on-device NPU hardware inpainting.

The genuine dead code and duplication uncovered by this audit includes a **completely non-functional NNAPI acceleration subsystem** (because the bundled ONNX Runtime artifact does not compile NNAPI) and a **3.28 MB byte-identical duplicate ONNX model** tracked in Git assets.

---

## 1. Architecture and Modularity Audit

### 1.1 Giant Monoliths (>2,000 Lines)

#### [ChapterProfileBatchCoordinator.kt](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt) (4,477 lines)
- **Severity:** HIGH | **Confidence:** HIGH
- **Location:** Line 367 to 4477
- **Mixed Responsibilities:** Monopolizes 7 distinct lifecycle phases in a single class:
  1. Pass 1 OCR preflight & memory gating ([L367–L948](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L367-L948), 581 lines)
  2. Analysis planning & chunk execution ([L949–L1311](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L949-L1311), 362 lines)
  3. Profile reconcile, synthesis, and freeze ([L1312–L1652](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L1312-L1652), 340 lines)
  4. Envelope planning & AI dispatch ([L1653–L2004](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L1653-L2004), 351 lines)
  5. Finalize & completion phase ([L2005–L2237](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L2005-L2237), 232 lines)
  6. Standard lane fallback execution ([L2238–L2522](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L2238-L2522), 284 lines)
  7. Work recovery, display tail draining, and disk transaction logging ([L2523–L4477](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L2523-L4477), 1,954 lines)
- **Impact:** High cognitive friction. A bug fix in OCR memory gating forces navigating a 4,500-line class holding broad coroutine scopes and mutexes.
- **Recommended Direction:** Extract isolated phase workers (`PreflightWorker`, `AnalysisWorker`, `ProfileReconciler`, `EnvelopeDispatcher`, `FinalizeWorker`). The coordinator should only route high-level phase state transitions.

#### [TextLayoutPlanner.kt](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt) (3,816 lines)
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Location:** Line 289 to 3816
- **Mixed Responsibilities:** Merges low-level geometry math, font shape measurement, iterative collision relaxation, CJK line break heuristics, and DTO assembly:
  - RLE mask unpacking & Union-Find connected component labeling ([L289–L350](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt#L289-L350))
  - Font stroke & bounding box math ([L378–L400](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt#L378-L400))
  - Iterative obstacle collision relaxation loops ([L1600–L1750](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt#L1600-L1750))
  - CJK vertical/horizontal line wrapping algorithms ([L3544–L3816](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt#L3544-L3816))
- **Impact:** High algorithmic complexity mixed with DTO transformations in a single file.
- **Recommended Direction:** Extract pure math to `MaskGeometryClustering.kt` and font wrapping heuristics to `FontFittingAlgorithms.kt`. Maintain `TextLayoutPlanner` as a pure facade.

#### [ReaderViewModel.kt](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt) (3,118 lines)
- **Severity:** HIGH | **Confidence:** HIGH
- **Location:** Line 47 to 3100
- **Mixed Responsibilities:** Core reader navigation and viewer logic is heavily coupled to translation state machines. Exactly **642 lines** manage `ChapterTranslationStore`, `RollingAutoCoordinator`, slot allocation, retry loops, and persisted layout reader bridge hooks ([L2973–L3000](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L2973-L3000)).
- **Impact:** Upstream reader features risk regressing when translation coordinator logic evolves.
- **Recommended Direction:** Extract `ReaderTranslationController`. `ReaderViewModel` should delegate translation actions through a clean, single-point interface.

#### Dual Store Plumbing: [ChapterTranslationStore.kt](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt) (2,920 lines) & [ChapterArtifactStore.kt](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt) (2,360 lines)
- **Severity:** HIGH | **Confidence:** HIGH
- **Scale:** 5,280 lines across both files. Storage layer totals 30 files and 11,908 lines across 3 packages (`eu.kanade.translation`, `eu.kanade.translation.store`, `eu.kanade.translation.artifact`).
- **Structural Flaw:** `ChapterTranslationStore` wraps `artifactStore: ChapterArtifactStore?` ([L112](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt#L112)). Every store operation passes through two locking mechanisms (Mutex in translation store, synchronized blocks in artifact store) and maps back and forth between legacy DTOs and artifact snapshot DTOs.
- **Impact:** Double locking overhead, redundant object allocation, risk of lock inversion.
- **Recommended Direction:** Merge into a single unified storage engine. Remove intermediate wrapper store.

---

## 2. Overengineering and Complexity Audit

### 2.1 Coordination & Orchestration Layer Proliferation (22 Files, 16,574 Lines)
- **Severity:** HIGH | **Confidence:** HIGH
- **Inventory:**
  - **7 Coordinators (7,445 lines):** [`ChapterProfileBatchCoordinator`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt) (4,477), [`RollingAutoCoordinator`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/auto/RollingAutoCoordinator.kt) (1,372), [`TranslationRequestCoordinator`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/TranslationRequestCoordinator.kt) (560), [`PaddlePageOcrCoordinator`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/recognition/PaddlePageOcrCoordinator.kt) (462), [`BatchCoordinatorInterfaces`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchCoordinatorInterfaces.kt) (241), [`ReaderTeardownCoordinator`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/manager/ReaderTeardownCoordinator.kt) (176), [`TextLayoutCoordinator`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/rendering/TextLayoutCoordinator.kt) (139).
  - **5 Schedulers & Executors (3,962 lines):** [`ProfileEnvelopeExecutor`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeExecutor.kt) (1,347), [`TranslationScheduler`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/TranslationScheduler.kt) (1,235), [`OverlapScheduler`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/OverlapScheduler.kt) (765), [`PaddleOcrV6BatchExecutor`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6BatchExecutor.kt) (400), [`AnalysisChunkExecutor`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/AnalysisChunkExecutor.kt) (361).
  - **10 Controllers, Projectors & Managers (5,167 lines):** [`TranslationManager`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationManager.kt) (1,873), [`AiTranslationRetryController`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/AiTranslationRetryController.kt) (1,034), [`BatchProgressProjector`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/manager/BatchProgressProjector.kt) (547), [`ChapterDataResetController`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/manager/ChapterDataResetController.kt) (491), [`StoreStatusProjector`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/StoreStatusProjector.kt) (226), [`CleanedImageLifecycleController`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/manager/CleanedImageLifecycleController.kt) (213), [`StorePersistenceScheduler`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/StorePersistenceScheduler.kt) (204), [`QnnContextCacheManager`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/runtime/onnx/QnnContextCacheManager.kt) (157), [`AotFallbackCoordinator`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/AotFallbackCoordinator.kt) (80).
- **Accidental Complexity:** Actual image processing (OCR, Inpainting, LLM, Canvas Draw) is buried beneath 6 to 8 nested coordination layers. Each layer instantiates its own `CoroutineScope`, mutexes, state flows, and timeout watchers.
- **Recommended Direction:** Flatten the architecture. Concrete stages should be invoked directly by a single chapter pipeline runner.

### 2.2 Triple Progress Projection
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Evidence:**
  - [`TranslationBatchProgressTracker.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTracker.kt) (568 lines): Channel event reducer.
  - [`BatchProgressProjector.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/manager/BatchProgressProjector.kt) (547 lines): Reactive combine over store registries.
  - [`StoreStatusProjector.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/StoreStatusProjector.kt) (226 lines): Fragmented store bridge.
- **Impact:** 3 separate systems compute overlapping progress counts.
- **Recommended Direction:** Delete `BatchProgressProjector` and `StoreStatusProjector`. Stream progress directly from store manifest snapshot.

---

## 3. Dead Code, Assets & Legacy Subsystems

### 3.1 Dead NNAPI Acceleration Subsystem (Major Hidden Debt)
- **Severity:** HIGH | **Confidence:** HIGH
- **Files Involved:**
  - [`NnapiCapabilityGate.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/NnapiCapabilityGate.kt) (218 lines)
  - [`NnapiHealthMonitor.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/NnapiHealthMonitor.kt) (165 lines)
  - [`StrictNnapiFallback.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/StrictNnapiFallback.kt) (142 lines)
  - [`CheckNnapi.java`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/runtime/onnx/CheckNnapi.java) (38 lines)
  - [`AOTInpainting.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt#L219-L278) (150+ lines of dead NNAPI session init and loops)
  - Corresponding tests: `NnapiCapabilityGateTest.kt`, `NnapiHealthMonitorTest.kt`, `StrictNnapiFallbackTest.kt`.
- **Proof:** [`gradle/libs.versions.toml:42`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/gradle/libs.versions.toml#L42) packages `com.microsoft.onnxruntime:onnxruntime-android-qnn`. AAR lacks NNAPI execution provider. Confirmed in [`SettingsTranslationScreen.kt:101-102`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt#L101-L102): *"NNAPI is deliberately absent: the onnxruntime-android-qnn artifact does not compile the NNAPI execution provider, so offering it would be a lie."*
- **Impact:** Entire subsystem can never activate at runtime. Pure dead maintenance overhead.
- **Recommended Direction:** Delete all NNAPI files, tests, and initialization branches.

### 3.2 Duplicate Asset in Repository (`best_int8.onnx`)
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Files:**
  - [`app/src/main/assets/models/segmentation/best_int8.onnx`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/assets/models/segmentation/best_int8.onnx) (3,444,163 bytes)
  - [`app/src/main/assets/models/segmentation/manga109_bubble_int8.onnx`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/assets/models/segmentation/manga109_bubble_int8.onnx) (3,444,163 bytes)
- **Proof:** Both files yield identical SHA-256: `2C80DAB0B9DF4455B40501614EBDF4BAE7A90C3880635635E8D87BAA50CDFA94`.
- [`app/build.gradle.kts:141`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/build.gradle.kts#L141) explicitly excludes `best_int8.onnx` from APK packaging.
- **Recommended Direction:** Delete `best_int8.onnx` from Git tracking and remove packaging exclusion.

### 3.3 Leak of Documentation Files into Production APK
- **Severity:** LOW | **Confidence:** HIGH
- **Files:**
  - `app/src/main/assets/models/ocr/paddle-v6-small/det/README.md`
  - `app/src/main/assets/models/ocr/paddle-v6-small/det/inference.yml`
  - `app/src/main/assets/models/ocr/paddle-v6-small/det/.gitattributes`
- **Proof:** [`build.gradle.kts:142-144`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/build.gradle.kts#L142-L144) excludes `paddle-v6-small/README.md` and `inference.yml` at root, but omits recursive glob for subfolder `det/`.
- **Recommended Direction:** Update build excludes pattern to `assets/models/ocr/**/*.md`, `**/*.yml`, `**/.git*`.

### 3.4 Repository Tree Bloat (99.85 MB)
- **Severity:** LOW | **Confidence:** HIGH
- **Locations:**
  - `experimental/models/manga109-segmentation-bubble/`: 40.38 MB (`best.pt`, `best.onnx`, `best_fp32.onnx`, `best_int8_broken.onnx`).
  - `tools/aot_corpus/`: 49.79 MB benchmark output images (`qa_output` is 29.33 MB).
  - `research/ppocrv6/`: 8.77 MB (includes 4.29 MB `qnn_compatibility_raw.json`).
  - [`tools/dev/Page15MockRig.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/tools/dev/Page15MockRig.kt): 1,019-line hardcoded single-page test rig.
- **Recommended Direction:** Clean out obsolete prototypes and gitignore benchmark QA outputs.

---

## 4. Test Suite Audit

Test suite baseline: **310 test files containing 2,159 `@Test` functions (68,971 lines).**

### 4.1 Permanently Disabled Tests (Remove Candidate)
- **Severity:** HIGH | **Confidence:** HIGH
- **Files (8 files, 65 `@Test` functions, 2,902 lines):**
  - [`MissingTextReproTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/MissingTextReproTest.kt) (592 lines, 11 tests)
  - [`TextLayoutPlannerMaskMetadataTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerMaskMetadataTest.kt) (471 lines, 10 tests)
  - [`TextLayoutPlannerFreeTextTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerFreeTextTest.kt) (415 lines, 17 tests)
  - [`TextLayoutPlannerFinalSafetyTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerFinalSafetyTest.kt) (411 lines, 7 tests)
  - [`TextLayoutPlannerSlice5Test.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerSlice5Test.kt) (287 lines, 8 tests)
  - [`TextLayoutPlannerQualityRepairTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerQualityRepairTest.kt) (269 lines, 4 tests)
  - [`TextLayoutPlannerContainedRescueTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerContainedRescueTest.kt) (245 lines, 4 tests)
  - [`TextLayoutPlannerShiftCeilingRepairTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerShiftCeilingRepairTest.kt) (212 lines, 4 tests)
- **Annotation:** Class-level `@Disabled("Superseded by Desktop 1:1 text layout engine port")`.
- **Impact:** Accounts for **38.1% of all rendering test code**. Compiles on every run, executes zero assertions.
- **Recommended Direction:** Delete all 8 files.

### 4.2 Cryptic Milestone & Ticket Test Names (Rename Candidate)
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Scope:** 27 test files named after ephemeral delivery milestones instead of behavior:
  - 11 `D1`–`D11` coexistence files: [`D1OriginPriorityTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D1OriginPriorityTest.kt), [`D7EngineEpochStopRaceTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D7EngineEpochStopRaceTest.kt), [`D8StallWatchdogTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D8StallWatchdogTest.kt), [`D10PartialDownloadAdmissionTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D10PartialDownloadAdmissionTest.kt), [`D11PermitFreeCommitTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D11PermitFreeCommitTest.kt), etc.
  - 5 Milestone files: [`MilestoneM2KillTheStallsTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/MilestoneM2KillTheStallsTest.kt), [`MilestoneM6DurabilityAtScaleTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/milestone/MilestoneM6DurabilityAtScaleTest.kt), etc.
  - 11 Ticket files: `T918CancelledBatchRestartTest.kt`, `T924FeatureFlagsTest.kt`, `T934CompletionOracleTest.kt`, `T934WriteTimeDigestsTest.kt`.
- **Impact:** Test failures report delivery sprint codes instead of broken domain invariants.
- **Recommended Direction:** Rename files to describe the behavioral invariant tested (e.g. `PipelineStallWatchdogTest`).

### 4.3 High Mock Density Fragility (Rewrite Candidate)
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Scope:** 3 presentation-layer test files exhibit severe mock coupling:
  - [`MangaScreenModelMultiSelectBatchTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelMultiSelectBatchTest.kt): 36 `mockk`, 50 `every` stubs.
  - [`MangaScreenModelCancelledBatchReconciliationTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelCancelledBatchReconciliationTest.kt): 36 `mockk`.
  - [`MangaScreenModelTranslationDrawerTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelTranslationDrawerTest.kt): 35 `mockk`.
- **Impact:** Tests assert call sequences across 15+ mocked subsystems rather than observable UI state flows.
- **Recommended Direction:** Replace mock trees with lightweight fake storage and repository stubs.

---

## 5. Naming, Package & Folder Structure Audit

### 5.1 OCR vs Recognition Package Synonymy
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Locations:**
  - `eu.kanade.translation.ocr` (17 files): [`PaddleOcrV6DetEngine.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6DetEngine.kt), [`RoiOcrEngine.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ocr/RoiOcrEngine.kt), [`PaddleOcrV6BatchExecutor.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6BatchExecutor.kt).
  - `eu.kanade.translation.recognition` (9 files): [`PaddlePageOcrCoordinator.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/recognition/PaddlePageOcrCoordinator.kt), [`RoiPageRecognitionEngine.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt), [`VerticalLineOcr.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/recognition/VerticalLineOcr.kt).
- **Problem:** Both packages implement text detection, box clustering, and OCR execution.
- **Recommended Direction:** Merge `eu.kanade.translation.recognition` into `eu.kanade.translation.ocr`.

### 5.2 Cluttered Translation Root Directory
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Scope:** 17 Kotlin files (8,444 lines) dumped directly in `eu.kanade.translation` without domain grouping:
  - `ChapterTranslationStore.kt` (2,920 lines), `TranslationPipeline.kt` (1,502 lines), `ChapterTranslator.kt` (961 lines), `TranslationManager.kt` (1,873 lines).
- **Target Structure:**
  ```
  eu.kanade.translation/
  ├── model/            # Immutable data models & DTOs
  ├── pipeline/         # Core stages (ocr, inpainting, translator, rendering)
  ├── orchestration/    # High-level pipeline dispatchers (batch, reader)
  ├── storage/          # Unified chapter store, disk IO, and artifact manifest
  └── runtime/          # Hardware discovery & ONNX execution providers
  ```

---

## 6. Comments and Documentation Hygiene

### 6.1 Ticket Jargon Pollution
- **Severity:** MEDIUM | **Confidence:** HIGH
- **Metric:** 1,611 internal ticket references across 145 Kotlin source files in `app/src/`:
  - `T924`: 701 occurrences in 145 files
  - `T917`: 354 occurrences in 84 files
  - `T934`: 216 occurrences in 58 files
  - `T911`: 131 occurrences in 43 files
  - `T909`: 107 occurrences in 37 files
- **Examples:**
  - [`ChapterProfileBatchCoordinator.kt:102-153`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L102-L153): 52-line header essay recounting historical Jira milestones (`ST-02..06`, `ST-07`, `ST-08`, `ST-09`, `TX-22`, `FP-04`) and dead feature flags (`FF-01`).
  - [`SettingsTranslationScreen.kt:90`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt#L90): Ticket nomenclature leaks to UI: `"Persisted layout reader bridge (FF-02)"`.
- **Impact:** Bloats context windows for developers and AI agents with expired sprint debates.
- **Recommended Direction:** Strip ticket identifiers from comments, keeping only technical invariants.

---

## 7. Cross-Auditor Synthesis & Root Cause

### Root Causes
1. **Append-Only Iterative Delivery:** New feature slices (e.g. batch profile coordinator, artifact migration) wrapped older engines instead of replacing them. Result: 22 coordination classes and dual-store architecture.
2. **Phantom Platform Support:** Retaining NNAPI fallback machinery after shipping ONNX Runtime build that dropped NNAPI.
3. **Test Suite Abandonment:** Leaving 2,902 lines of disabled tests in tree after porting desktop text layout engine.

---

## 8. Prioritized Remediation Roadmap

### Phase 1 — Dead Assets, Disabled Tests & Phantom Subsystems (Zero Risk)
1. Delete 8 disabled test files (2,902 lines) in `app/src/test/java/eu/kanade/translation/rendering/`.
2. Delete duplicate asset `app/src/main/assets/models/segmentation/best_int8.onnx` (3.28 MB) and remove exclusion rule in `app/build.gradle.kts`.
3. Add packaging excludes for `assets/models/ocr/**/README.md` and `*.yml`.
4. Excise dead NNAPI subsystem (`NnapiCapabilityGate.kt`, `NnapiHealthMonitor.kt`, `StrictNnapiFallback.kt`, `CheckNnapi.java`, and NNAPI sessions in `AOTInpainting.kt`).

### Phase 2 — Storage Unification & Progress Consolidation
1. Merge `ChapterTranslationStore.kt` and `ChapterArtifactStore.kt` into unified storage engine. Remove dual mutexes and redundant DTO wrappers.
2. Delete `BatchProgressProjector.kt` and fold `StoreStatusProjector.kt` back into store. Derive UI progress directly from store manifest state flow.

### Phase 3 — Monolith Decomposition
1. Decompose `ChapterProfileBatchCoordinator.kt` (4,477 lines) into phase workers (`PreflightWorker`, `AnalysisWorker`, `ProfileReconciler`, `EnvelopeDispatcher`, `FinalizeWorker`).
2. Extract pure geometry math from `TextLayoutPlanner.kt` (3,816 lines) into `MaskGeometryClustering.kt`.
3. Extract `ReaderTranslationController` out of `ReaderViewModel.kt`.

### Phase 4 — Package Reorganization & Test Renaming
1. Merge package `eu.kanade.translation.recognition` into `eu.kanade.translation.ocr`.
2. Organize 17 root translation files into `storage/`, `pipeline/`, `orchestration/`.
3. Rename 27 ticket-named test files (`D1`–`D11`, `MilestoneM*`, `T934*`) to reflect behavioral invariants.
4. Replace fragile `mockk` trees in `MangaScreenModel` unit tests with fake stores.

### Phase 5 — Comment & Repo Cleanup
1. Strip 1,611 internal ticket tags (`T924`, `T917`, etc.) and header essays from source files.
2. Clean `experimental/` (40.38 MB) and `tools/aot_corpus/qa_output` (29.33 MB) from Git tracking.

---

## 9. Coexistence Architecture & Corrected Evidence (Batch Pause/Cancel Trace)

*Full technical call trace and evaluation:* [`Plan/active/2026-09-19_T936_codebase-architecture-audit/engineering/batch-pause-cancel-trace-and-mutual-exclusion-evaluation.md`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/Plan/active/2026-09-19_T936_codebase-architecture-audit/engineering/batch-pause-cancel-trace-and-mutual-exclusion-evaluation.md)

### 9.1 Root Cause of Manual/Auto Lockout After Batch Pause/Cancel
1. **Asynchronous Non-Blocking Pause/Cancel:** [`ChapterTranslator.pause()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L391) calls `cancelTranslatorJob()`, which triggers non-blocking `translationJob?.cancel()`. The UI considers batch stopped immediately.
2. **Uncancellable Native ONNX Execution:** Active native C++ inference (`session.run()` for OCR, bubble detection, or inpainting) cannot be interrupted by coroutine cancellation. The coroutine cannot throw `CancellationException` or run its `finally` lease-release blocks until the native call returns.
3. **Lease Denied & Poisoned Observer Hang:** When user enters Reader and taps "Translate", [`PageStageLeaseTable.kt:99`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L99) denies `MANUAL` (`"page owned by BATCH"`). `TranslationPipeline.kt:485` redirects to [`attachToOwnerTerminal()`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L736) and hangs for up to `ATTACH_TIMEOUT_MS` (10–20s) waiting for an owner that is cancelling and will never finish, finally timing out with `ATTACHED_UNRESOLVED` (*"Background translation did not finish yet"*).

### 9.2 Corrected Evidence Table

| Prior Statement / Finding | Truth Status | Evidence & Code Justification |
| :--- | :--- | :--- |
| *"When a batch run is cancelled or paused, in-flight coroutines are not abruptly cancelled. They are wrapped in `withContext(NonCancellable)` to drain active work over a grace window (up to 10 seconds)."* | **INCORRECT** | **Conflated with Auto Mode.** [`D6DrainNotCancelTest.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/test/java/eu/kanade/translation/coexistence/D6DrainNotCancelTest.kt#L45-L67) tests [`RollingAutoCoordinator.drainGraceMs`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt#L98). Batch translation cancels via standard `Job.cancel()` in [`ChapterTranslator.kt:555`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L555). It has no 10-second drain grace window. |
| *"Batch translation executes under `withContext(NonCancellable)`."* | **PARTIALLY CONFIRMED** | Confirmed **only** for teardown flushes ([`BatchChapterTranslator.kt:1060`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L1060), [`ChapterProfileBatchCoordinator.kt:2136`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt#L2136)) and atomic lease table mutations ([`PageStageLeaseTable.kt:152, 180, 215, 235, 255`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L152)). OCR, inpainting, and provider calls are **not** in `NonCancellable`. |
| *"After pausing or cancelling Batch, Manual translation can be locked out for up to 10–20 seconds and fail with an ambiguous error."* | **CONFIRMED** | Caused by non-blocking job cancellation leaving native ONNX inference running, resulting in `LeaseAcquisition.Denied` ([`PageStageLeaseTable.kt:99`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt#L99)), followed by a hanging observation wait in [`attachToOwnerTerminal`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L752) bounded by `ATTACH_TIMEOUT_MS` (10–20s), outputting *"Background translation did not finish yet."* ([`TranslationUiTruth.kt:598`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt#L598)). |
| *"aot-512.onnx is dead code and should be deleted."* | **INCORRECT** | False positive. Required by [`AOTInpainting.kt:115-124`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/codebase_architecture_audit/app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt#L115-L124) for Qualcomm QNN HTP NPU hardware acceleration. |
| *"The NNAPI acceleration subsystem is completely dead."* | **CONFIRMED** | Verified against build config and ONNX Runtime AAR: NNAPI native runtime is absent. |
| *"best_int8.onnx is a duplicate asset."* | **CONFIRMED** | Byte-for-byte SHA-256 duplicate of `manga109_bubble_int8.onnx` (3.28 MB). |

### 9.3 Strategic Architecture Recommendation: Mutual Exclusion (Model B)
Adopt an explicit session state machine (`IDLE`, `BATCH_SESSION`, `READER_SESSION` [Manual + Auto]):
- Moving to Reader translation prompts: *"Batch translation is active. Pause Batch and switch to Reader?"*
- Batch pauses safely and joins to complete quiescence before Reader starts.
- Reader translations reuse all existing Batch-produced disk checkpoints.
- Eliminates: cross-origin preemption matrix, sibling attach refcounting, `attachToOwnerTerminal` hang loops, and in-pass gap rescans (~3,500 lines of accidental complexity).
