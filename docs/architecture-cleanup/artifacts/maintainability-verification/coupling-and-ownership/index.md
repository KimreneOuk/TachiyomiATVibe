---
title: "Maintainability Verification: Coupling, Ownership, and Boundaries (F-01, F-05, F-06, F-10)"
kind: review
comments: none
---

# Executive Summary

This independent review inspects findings **F-01**, **F-05**, **F-06**, and **F-10** against git revision `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` (working tree clean).

| Finding | Verdict | Severity | Confidence | Headline Evidence & Qualified Disposition |
| --- | --- | --- | --- | --- |
| **F-01: Change Coupling & Constructor Parameter Explosion** | **Confirmed** | High | High | `BatchChapterTranslator` has **24** constructor parameters (**20** top-level function-typed params); `BatchLaneWorkers` has **39** parameters (**11** top-level function-typed params, **10** forwarded from Pipeline). Function signature modifications propagate through 5–6 production files and test call sites without semantic necessity. |
| **F-05: Responsibility Concentration in Core Orchestration** | **Partially Confirmed** | High | High | `ChapterTranslationStore` (5,061 LOC), `TranslationManager` (2,175 LOC), `NeuralInpaintingCoordinator` (2,033 LOC), and `TranslationPipeline` (1,472 LOC) concentrate mixed concerns beyond their extracted collaborators. Decomposition **must strictly preserve** `ChapterTranslationStore` as the singular live state/lease/fence authority. |
| **F-06: Recognition and Inpainting Contract Overlap** | **Confirmed** | High | High | `PageRecognitionEngine` contract declares both `analyze` and `inpaint`, and `RoiPageRecognitionEngine` instantiates `PageInpaintingEngine` internally under a shared `nativeGuard` (`Mutex`). `PageInpaintingEngine` is bypassed by pipeline architecture. Stage extraction must not prematurely split locks until detached inpaint teardown/drain is verified. |
| **F-10: Naming Drift, Reverse Imports, and Stale Docs** | **Partially Confirmed** | Medium | High | Architecture doc at line 65 contains a stale note (`model -> engines` language cycle does not exist). Reverse dependencies exist (`engines -> pipeline` and `context -> pipeline.batch`). `TranslationManager` is an intentional documented facade, not a rule violation. Arbitrary line-count limits and cosmetic mass renames are rejected. |

# Detailed Verification by Finding

## F-01: Excessive Coupling Through Constructor Parameter Explosion

### Verdict

**CONFIRMED** | Severity: **High** | Confidence: **High**

### Verified Constructor Parameter Counts & Categorization

#### 1. `BatchChapterTranslator` (`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt:79-138`)

- **Total parameters: 24**
  - **4 Object/Interface dependencies**:
    - `provider: TranslationFileProvider`
    - `translationPreferences: TranslationPreferences`
    - `nativeLane: NativeLaneRunner`
    - `engineRebuildMutex: Mutex`
  - **20 Top-level function-typed parameters (`(...) -> ...`)**:
    - `ensureEnginesBuiltFor`, `recognitionEngineFn`, `textTranslatorFn`, `computeSourceFingerprintFn`, `batchExpectedFingerprintsFn`, `inpaintingModeFromPref`, `inpaintingStampDecision`, `releaseBatchPageLease`, `persistPageWithOomRecovery`, `loadPersistedCleanedBitmap`, `deleteRetiredCleanedFile`, `markPageTimedOut`, `analyzePage`, `decodePageBitmapForTranslation`, `preflightInpaintGate`, `inpaintPage`, `retryInpaintDownscaled`, `persistCleanedBitmap`, `updatePageFromCurrentSnapshotFn`, `onBatchClosedFn`.
- **Callsites**: Production instantiation at `TranslationPipeline.kt:1216-1250`; test harness at `TranslationCoexistenceHarness.kt:598-650`.

#### 2. `BatchLaneWorkers` (`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt:95-160`)

- **Total parameters: 39**
  - **13 Batch Session Properties**:
    - `store`, `manga`, `chapter`, `source`, `provider`, `translationPreferences`, `tracker`, `batchGeneration`, `isAi`, `fromLang`, `toLang`, `orderedStreams` (type `List<Pair<String, () -> InputStream>>`), `resolvedNaturalPageIndexes`.
  - **11 Top-level function-typed parameters (`(...) -> ...`)**:
    - **10 Forwarded from Pipeline via `BatchChapterTranslator`**: `textTranslatorFn`, `recognitionEngineFn`, `freshSourceShaByPageKey`, `markPageTimedOutFn`, `analyzePageFn`, `decodePageBitmapForTranslationFn`, `preflightInpaintGateFn`, `inpaintPageFn`, `retryInpaintDownscaledFn`, `persistCleanedBitmapFn`.
    - **1 Locally constructed in `BatchChapterTranslator`**: `abortBatchCandidateFn` (instantiated at line 692).
  - **7 Context and Synchronization Primitives**:
    - `chunkProfile`, `requestedOutputTokens`, `translationRegistry`, `batchWriteIdentities`, `aborted`, `expectedBatchFingerprints`, `contextFrontier`.
  - **8 Scheduling and Tracing Collaborators**:
    - `resumePlanner`, `writeGate`, `renderJoin`, `heldBitmapRegistry`, `nativeLane`, `scheduleTrace`, `pageTraceRegistry`, `scheduleListener`.
- **Sum**: 13 session + 11 function-typed + 7 sync/context + 8 scheduling = **39 parameters**.

### Source-Only Change-Impact Exercises (Baseline Tracing)

Change propagation is strictly conditional on the shape of the change. Tracing distinguishes signature-only mechanical forwarding from genuinely required semantic edits.

#### Exercise (A): Add OCR Output Metadata

- **Domain property addition**: If OCR output metadata is added as a field within the existing domain models (e.g., inside `TextBlock` or `PageTranslation`), it requires **0 signature or forwarding changes** across the constructor chain.
- **Hypothetical signature-token addition**: If metadata is returned out-of-band or passed as an explicit parameter to `analyzePag`**e**:
  - **Genuinely required semantic edits (3 sites)**:
    - Producer: `RoiPageRecognitionEngine.kt:564-900`
    - Single-page caller: `SinglePageOnnxPhase.kt:908-950`
    - Batch caller: `BatchLaneWorkers.kt:308-320`
  - **Pure signature-forwarding edits (3 intermediate files)**:
    1. `TranslationPipeline.kt:1233` & `:1381` (ctor argument & wrapper)
    2. `BatchChapterTranslator.kt:96` & `:676` (ctor parameter & forwarding)
    3. `BatchLaneWorkers.kt:126` (ctor parameter)
  - **Affected test candidates**: `TranslationCoexistenceHarness.kt:636`

#### Exercise (B): Change Inpainting Retry Input

- **Hypothesis**: Add a retry parameter (e.g., downscale attempt index or custom timeout) to `retryInpaintDownscaled`.
- **Genuinely required semantic edits (3 sites)**:
  - Implementation: `SinglePageOnnxPhase.kt:813`
  - Single-page caller: `SinglePageHttpRenderPhase.kt:802`
  - Batch caller: `BatchLaneWorkers.kt:731`
- **Pure signature-forwarding edits (2 intermediate files)**:
  1. `SinglePageHttpRenderPhase.kt:85`, `:145` (ctor parameter & wrapper)
  2. `TranslationPipeline.kt:805`, `:1246`, `:1370`, `:1379` (ctor lambda to `SinglePageHttpRenderPhase`, ctor arg to `BatchChapterTranslator`, and pass-through method)
  3. `BatchChapterTranslator.kt:111`, `:690` (ctor parameter & re-forwarding)
  4. `BatchLaneWorkers.kt:137`, `:330` (ctor parameter & wrapper)
- **Affected test candidates (3 files across 5 callsites)**:
  - `TranslationCoexistenceHarness.kt:471, 641`
  - `BatchTelemetryTest.kt:434`
  - `BatchTerminalExitTest.kt:80, 165`
- **Takeaway**: Here the 5-file production propagation holds exactly because `TranslationPipeline` and `BatchChapterTranslator` have zero semantic involvement in inpaint downscaling, serving purely as functional plumbing.

#### Exercise (C): Change Cleaned Publication Preconditions

- **Precondition policy extension**: `persistCleanedBitmap` (`CleanedPublication.kt:121`) already accepts `ChapterTranslationStore.PatchPrecondition?`. Adding new preconditions or modifying fields within `PatchPrecondition` requires **0 edits** to the 10-argument function signature.
- **Hypothetical top-level parameter addition**: If an explicit parameter is added to `persistCleanedBitmap`:
  - **Semantic sites (4 files)**: `CleanedPublication.kt:121` (enforcement), `SinglePageHttpRenderPhase.kt:819`, `SinglePageOnnxPhase.kt:757`, `BatchLaneWorkers.kt:794` (callers).
  - **Pure forwarding sites (2 files)**: `TranslationPipeline.kt:1247, 1338`, `BatchChapterTranslator.kt:120, 691`.
  - **Affected test candidates**: `TranslationCoexistenceHarness.kt:416, 420, 644`, `NextPageAdmittedDuringParkedPublicationTest.kt:80`, `P5HonestOutcomeTypingTest.kt:82`, `BatchTelemetryTest.kt:435`, `BatchTerminalExitTest.kt:81, 166`.

### Duplicated Semantics vs. Meaningful Differences

- **Duplicated Semantics**:
  - Preflight decode gates (`MemoryGovernance.preflightAnalyzeGate`, `preflightInpaintGate`).
  - OOM catching, retry placeholder creation, and consecutive OOM tracking.
  - Guarded store updates (`updatePageGuarded`).
- **Meaningful Divergences (Must NOT be merged)**:
  - **Single-page path**: Tied to active reader viewport, foreground latency priority (`DevicePermitPriority.FOREGROUND`), reader slot prefetching, and span tracing (`TranslationTraceStage`).
  - **Batch path**: Coordinates chapter-wide progress (`TranslationBatchProgressTracker`), multi-page chunking profiles (`chunkProfile`), frontier management (`BatchContextFrontier`), and resume planning (`BatchResumePlanner`).
  - **Scheduling must remain separate**: Single-page and batch schedulers serve divergent concurrency, preemption, and priority goals.

### Smallest Useful Correction

- **Introduce Cohesive Stage Capabilities**: Replace bare function closures with cohesive domain interfaces:
  1. `CleanedImagePublisher`: encapsulates cleaned bitmap persistence, loading, and retirement.
  2. `PageStageExecutionSeam`: encapsulates stage execution (`analyzePage`, `inpaintPage`, `retryInpaintDownscaled`).
- **Direct Injection into Workers**: Inject these capabilities directly into `BatchLaneWorkers` (or a batch execution context) rather than disassembling them into 10–20 individual function lambdas in `TranslationPipeline` and re-forwarding them through `BatchChapterTranslator`.
- **Avoid Cosmetic Parameter Bundling**: Packaging 39 parameters into a single large parameter object merely obscures arity without improving change isolation. True decoupling requires capability seams.

## F-05: Responsibility Concentration in Core Orchestration Classes

### Verdict

**PARTIALLY CONFIRMED** (Confirmed responsibility concentration, but bounded by non-negotiable live-state authority) | Severity: **High** | Confidence: **High**

### Residual Responsibilities & Existing Collaborators

#### 1. `ChapterTranslationStore.kt` (5,061 LOC)

- **Existing Collaborators Already Extracted**:
  - `PageStageLeaseTable` (`persistence/internal/PageStageLeaseTable.kt`) shares the store mutex and lease map.
  - `ChapterAttemptLedger` (`persistence/internal/ChapterAttemptLedger.kt:34`) tracks attempt counts and caps.
- **Residual Mixed Responsibilities**:
  1. *Live state & reactive dispatch*: `state: StateFlow<PersistentMap<String, PageTranslation>>` (lines 235–350).
  2. *Lease coordination & fencing*: `PageStageLeaseTable`, store mutex, generation counters (lines 1809–1890).
  3. *Guarded page mutations*: `updatePageGuarded`, `mergeOcr`, `mergeTranslation`, `mergeRender` (lines 1891–2510).
  4. *Direct Journal storage, recovery & epoch GC*: `ChapterJournalStorage`, `ChapterJournalWriter`, `FileChapterJournalStorage`, replay reducer, snapshot management (`withJournalCapturePermit`, lines 4398–4600).
  5. *Direct Artifact candidate/commit coordination*: `ChapterArtifactEngine`, `checkpointOcr`, `persistDurableStageFailure` (lines 2520–3050).
  6. *Bulk page migration & rekeying*: `replaceAll`, `rekeyPages`, `preRegisterPages`, `clearTransientQueuePages` (lines 3050–3650).
- **NON-NEGOTIABLE ARCHITECTURAL CONSTRAINT**:
  <user_quoted_section>ChapterTranslationStore MUST remain the sole process-local authority for live page state, page leases, generation fencing, and the store mutex.Live state and lease tracking cannot be separated into distinct objects without introducing concurrency races between in-memory leases and guarded store commits.</user_quoted_section>
- **Valid Decomposition**: Delegate journal compaction/snapshotting completely to `ChapterJournalManager`, and extract bulk page migration/rekeying policies into focused internal helpers.

#### 2. `TranslationManager.kt` (2,175 LOC)

- **Existing Collaborators Already Extracted**:
  - `ActiveChapterStoreRegistry` (`persistence/chapter/ActiveChapterStoreRegistry.kt`) owns the map of active stores.
  - `TranslationRequestCoordinator` (`workflow/TranslationRequestCoordinator.kt`) coordinates pending requests and download handoffs.
- **Residual Mixed Responsibilities**:
  1. *Public facade*: entry point for UI and background listeners (lines 369–380, 742–748).
  2. *Store creation & directory hydration*: `openOrCreateActiveChapterTranslationStoreSuspend` (lines 1768–1910).
  3. *Download re-keying state machine*: `rekeyTranslationForCompletedDownload` (lines 1467–1748).
  4. *Batch queue manipulation*: `translateChapter`, `translateChapters`, `prioritizeChapter`, `clearQueue`, `cancelRunningChapterForReplace` (lines 877–1110).
  5. *Auto window coordinator*: viewport tracking, auto-window updates, reconciliation (`updateAutoWindow`, `reconcileAutoWindow`, lines 1930–1990).
- **Valid Decomposition**: Extract store hydration/opening into `ChapterStoreFactory`, and extract download re-keying into `DownloadRekeyCoordinator`, keeping `TranslationManager` as a clean facade.

#### 3. `NeuralInpaintingCoordinator.kt` (2,033 LOC)

- **Existing Collaborators Already Extracted**:
  - `InpaintBoxGeometry` (`engines/inpainting/InpaintBoxGeometry.kt`) owns box expansion, IoU, and grouping.
- **Residual Mixed Responsibilities**:
  - Direct buffer pooling (`DirectBufferPool`, `sharedImgPixels`, `scratchLock`).
  - LiteRT GPU delegate initialization and fallback session setup.
  - Bubble tile partitioning, overlap fusion, stroke distance-field calculation, and feather blending.
  - Classical OpenCV fallback (Telea / Navier-Stokes).
- **Valid Decomposition**: Extract bubble tile partitioning and distance-field feathering into a dedicated geometry collaborator.

#### 4. `TranslationPipeline.kt` (1,472 LOC)

- **Residual Mixed Responsibilities**:
  - Owns `nativeRunQuarantine` and coordinates `EngineLane`.
  - Hosts `NativeStallWatchdog`.
  - Serves as the callback bus forwarding 20 bare lambdas to `BatchChapterTranslator`.
- **Valid Decomposition**: Remove pass-through callback plumbing as outlined in F-01.

### Test Anchors & Acceptance Criteria

- **Actual Existing Test Anchors**:
  - `ChapterTranslationStoreJournalCaptureTest.kt`
  - `ChapterJournalCompactionTest.kt`
  - `ChapterJournalRecoveryTest.kt`
  - `StandardPipelineCoexistenceTest.kt`
  - `NormalMangaIsolationTest.kt`
- **Acceptance Criteria**:
  - Store mutations remain strictly synchronized under `ChapterTranslationStore.mutex` with validated lease tokens.
  - Journal replay, compaction, and snapshot recovery remain 100% byte-compatible.

## F-06: Recognition and Inpainting Contract Overlap

### Verdict

**CONFIRMED** | Severity: **High** | Confidence: **High**

### Exact Source Locations & Architecture Analysis

- **Interface Contamination**: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PageRecognitionEngine.kt:7-27`
  - Declares both `analyze(bitmap)` and `inpaint(bitmap, pageTranslation)`, plus compound `recognize(bitmap)` mutating `pageTranslation.cleanedBitmap`.
- **Internal Delegation**: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt:988-1025`
  - `inpaint()` instantiates or delegates to `PageInpaintingEngine` inside `nativeGuard.withLock`.
- **Lock Nature**: `RoiPageRecognitionEngine.kt:162`
  - `nativeGuard` is `kotlinx.coroutines.sync.Mutex` (lines 162, 564, 989, 1318), NOT `ReentrantLock`. Non-suspending `nativeGuard.tryLock()` is used in `close()` and `forceReleaseNativeBuffers()`.
- **Pipeline Callsites**: `SinglePageOnnxPhase.kt:562, 721, 1066, 1200`
  - All pipeline inpainting calls route through `recognitionEngine.inpaint(...)`.
- **Quarantine Ownership**: `TranslationPipeline` owns `nativeRunQuarantine`; `EngineLane` receives and coordinates it.
- **Orphaned Engine**: `PageInpaintingEngine.kt:17-154`
  - Dedicated inpainting engine exists with mode resolution and LiteRT GPU binding, but is completely bypassed by the pipel`ine`.

### Concurrency Realities & Phased Correction

- **Why Locks Were Shared**: Both OCR and neural/classical inpainting execute native C++ code (ONNX runtime, LiteRT, OpenCV). `nativeGuard` prevents native session teardown or buffer draining while inference is active.
- **Detached Inpaint Hazard**: Detached inpainting (`SinglePageOnnxPhase.kt:1182`) escapes `withNativeLane` at launch. Therefore, one **cannot assume** `EngineLane` native exclusivity alone safely replaces `nativeGuard`.
- **Safe Recommendation**:
  1. **Phase 1 (Contract Seam)**: Remove `inpaint()` and compound `recognize()` from `PageRecognitionEngine`. Make `PageInpaintingEngine` a first-class collaborator in `EngineLane` alongside `PageRecognitionEngine` and `TextTranslator`. Wire `SinglePageOnnxPhase` directly to `PageInpaintingEngine`.
  2. **Phase 2 (Lock Decoupling - Prerequisite Gated)**: Do **not** immediately split native locks. Retain a shared native guard or coordinate mutual exclusion until detached inpaint cancellation, buffer drainage (`forceReleaseNativeBuffers`), and engine teardown are verified with dedicated race tests.

### Test Anchors & Acceptance Criteria

- **Actual Existing Test Anchors**:
  - `PaddleOcrV6PageLifecycleTest.kt`
  - `PaddlePageOcrCoordinatorTest.kt`
  - `PageInpaintingEngineTest.kt`
  - `PostOcrStageSemanticsTest.kt`
- **Acceptance Criteria**:
  - `PageRecognitionEngine` contract contains only OCR/vision analysis methods.
  - OCR unit tests can execute with a mock recognition engine with zero inpainting dependencies.

## F-10: Naming Drift, Reverse Imports, and Documentation Accuracy

### Verdict

**PARTIALLY CONFIRMED** (Confirmed reverse imports and stale doc claim; rejected naming violation for facade and arbitrary file size limits) | Severity: **Medium** | Confidence: **High**

### Detailed Findings & Source Traces

#### 1. Stale Documentation Statement

- `docs/translation-architecture.md:65`:
  <user_quoted_section>"and a model -&gt; engines cycle remains for translator/OCR language value types. Do not add new exceptions."</user_quoted_section>
  - **Verified Reality**: **STALE**. `TextRecognizerLanguage.kt` and `TextTranslatorLanguage.kt` reside in `eu.kanade.translation.model` and import only ML Kit `TranslateLanguage` and Tachiyomi preferences. Neither imports `engines`. No cycle exist`s`.

#### 2. Verified Forbidden Reverse Dependencies

- **`engines` importing `pipeline`**:
  - `engines/vision/ocr/PaddlePageOcrCoordinator.kt:17`: `import eu.kanade.translation.pipeline.NativeVisionTelemetry`
  - `engines/vision/ocr/RoiPageRecognitionEngine.kt:50`: `import eu.kanade.translation.pipeline.adaptive.DeviceStageNormalization`
- **`context` importing `pipeline.batch`**:
  - `context/ChapterContextService.kt:10`: `import eu.kanade.translation.pipeline.batch.BatchContextFrontier`

#### 3. Facade Suffix & Naming Accuracy

- `TranslationManager`: `docs/translation-architecture.md:20, 41` explicitly identifies `TranslationManager` as the public facade. Therefore, using the `Manager` suffix is an intentional architectural design for the entry point, not a documented naming rule violation.
- `SinglePageOnnxPhase.kt`: Contradictory name, as it actively executes batch ONNX stages (`analyzePage:908`, `retryInpaintDownscaled:813`).

#### 4. Rejection of Arbitrary Metrics

- Arbitrary line limits (e.g. splitting files merely to be under 1,000 lines) are explicitly rejected. File cohesion and thread safety invariants take precedence over file length.

### Smallest Useful Correction

1. **Relocate Types**:
  - Move `NativeVisionTelemetry` and `DeviceStageNormalization` from `pipeline` to `diagnostics` or `engines/runtime`.
  - Move `BatchContextFrontier` from `pipeline.batch` to `context` or a shared `planning` package.
2. **Add CI Architecture Enforcement**:
  - Introduce an automated test verifying package dependency rules (`engines` !-> `pipeline`, `context` !-> `pipeline.batch`).
3. **Correct Documentation**: Remove the stale language-cycle note at line 65 of `docs/translation-architecture.md`.

### Test Anchors & Acceptance Criteria

- **Test Anchors**:
  - Proposed test: `app/src/test/java/eu/kanade/translation/ArchitecturePackageEnforcementTest.kt`
  - Existing: `NativeVisionTelemetryTest.kt`
- **Acceptance Criteria**:
  - Zero imports from `eu.kanade.translation.pipeline.*` inside `engines/` and `context/`.
  - Documentation accurately reflects current package relationships.

# Consolidated Maintainability Matrix

| Finding | Core Verified Cause | Proposed Structural Seam | Protected Invariants |
| --- | --- | --- | --- |
| **F-01** | Funneling 10+ bare closures through 3 constructor tiers | `CleanedImagePublisher`, `PageStageExecutionSeam` | Preserve independent scheduling policies and priority gates for reader vs. batch |
| **F-05** | Auxiliary tasks accumulating in core state/facade classes | `ChapterJournalManager`, `ChapterStoreFactory`, `DownloadRekeyCoordinator` | `ChapterTranslationStore` remains singular authority for live state, leases, and mutex |
| **F-06** | Bundling inpainting into OCR contract and engine lifecycle | Standalone `PageInpaintingEngine` registered in `EngineLane` | Retain native memory guard protection until detached inpaint teardown is verified |
| **F-10** | Unenforced package boundaries and documentation drift | Relocate telemetry/frontier types + CI architecture test | Enforce responsibility-based boundaries without superficial mass renames |
