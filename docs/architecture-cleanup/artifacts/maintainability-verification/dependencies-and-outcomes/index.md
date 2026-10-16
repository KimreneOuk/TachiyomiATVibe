---
title: "Verification report: F-03 dependencies, F-04 rejection semantics, F-08 state projections"
kind: review
comments: none
---

# Executive Summary

Independent source verification was performed against commit `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` with a clean working tree.

| Finding | Audit Claim | Verdict | Severity / Confidence | Core Verified Fact |
| --- | --- | --- | --- | --- |
| **F-03** | Layering & dependency inversion: lower packages (`engines`, `context`) import `pipeline` | **Confirmed** | Medium / High | Three exact reverse imports verified: `RoiPageRecognitionEngine` &rarr; `DeviceStageNormalization`, `PaddlePageOcrCoordinator` &rarr; `NativeVisionTelemetry`, and `ChapterContextService` &rarr; `BatchContextFrontier`. All three targets are pure helpers/state trackers. Zero architecture checks exist in Gradle or CI. |
| **F-04** | String-matching rejection semantics: `TranslationUiTruth` matches human sentence `REASON_TRANSLATION_NOT_SAVED` and interpolates raw strings into UI/accessibility | **Confirmed** | High / High | Conflates pre-run admission denial with post-run persistence commit failure; new failure messages or producers silently fall through to the default branch (stripping `RETRY`); tests lock in raw English literals. |
| **F-08** | State model fragmentation & semantic duplication across `PageLifecycle`, `PageDisplayProjection`, `PageDisplayState`, `AutoSlotState`, `TranslationUiTruth` | **Unsupported** | Low / High | Full getter chain audit shows **zero contradictory readiness decisions**: `PageLifecycle.hasRenderedResult` delegates directly to `isTranslationDisplayReady`, which delegates to `toPageDisplayProjection().displayReady`. Active models serve distinct, legitimate roles (durability truth vs. lookahead scheduling vs. UI mapping). |

# Finding F-03: Package Dependency Direction & Layering Violations

### Verdict & Ratings

- **Verdict**: **Confirmed**
- **Severity**: **Medium** (structural friction violating documented package layering; does not cause runtime deadlocks, but allows cross-subsystem coupling to spread unchecked)
- **Confidence**: **High** (verified via exhaustive source and import matrix audit)

### Exact Evidence & Usages

#### 1. `RoiPageRecognitionEngine` &rarr; `pipeline.adaptive.DeviceStageNormalization`

- **Location**: [`RoiPageRecognitionEngine.kt:50, 980`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt#L50)
- **Usage**:
    ```kotlin
    normalizationUnits = DeviceStageNormalization.regionArea(pageTranslation.blocks),
    ```
- **Definition**: [`DeviceStageNormalization.kt:8-20`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/adaptive/DeviceStageNormalization.kt#L8-L20) under `package eu.kanade.translation.pipeline.adaptive`.
- **Ownership & Nature**:
`DeviceStageNormalization` contains pure mathematical calculations (`sourcePixels`, `textVolume`, `regionArea`) on image dimensions and `TranslationBlock`s. It holds **no** pipeline state, **no** adaptive controller policy, and **no** dependency on pipeline classes (depends only on `model.TranslationBlock` and standard math functions).
The actual adaptive controller policy is isolated in `AdaptiveKnobController.kt`. Because `DeviceStageNormalization` was packaged in `pipeline.adaptive`, the lower-level OCR engine in `engines/vision/ocr` is forced to import from `pipeline`.

#### 2. `PaddlePageOcrCoordinator` &rarr; `pipeline.NativeVisionTelemetry`

- **Location**: [`PaddlePageOcrCoordinator.kt:17, 321`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinator.kt#L17)
- **Usage**:
    ```kotlin
    NativeVisionTelemetry.logCropSummary(
        pageKey = pageKey,
        leafCount = leafBoxes.size,
        batchSize = batchSize,
        avgInferMs = avgInferMs,
        provider = "paddle",
    )
    ```
- **Definition**: [`NativeVisionTelemetry.kt:10-95`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/NativeVisionTelemetry.kt#L10) under `package eu.kanade.translation.pipeline`.
- **Ownership & Nature**:
`NativeVisionTelemetry` is a pure logging wrapper delegating directly to `diagnostics.TelemetryTrace.log`. It holds no pipeline execution state.
Placing it in `pipeline` forces:
  - `PaddlePageOcrCoordinator` (`engines/vision/ocr`) to reach up into `pipeline`.
  - Outer UI controller (`ReaderTranslationController.kt:51, 278, 315, 952, 979, 991, 1661`) to reach into `pipeline` for tap-to-dispatch and display-attach UI telemetry. Placing shared telemetry formatting under `diagnostics` would provide clean, shared ownership without pulling pipeline contracts into engines or UI.

#### 3. `ChapterContextService` &rarr; `pipeline.batch.BatchContextFrontier`

- **Location**: [`ChapterContextService.kt:10, 143`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/context/ChapterContextService.kt#L10)
- **Usage**:
    ```kotlin
    val frontier = BatchContextFrontier(indexes)
    frontier.seed(...)
    return indexedRecords.asSequence()
        .filter { (index, record) -> index < currentIndex && index <= frontier.frontierIndex && record.pageKey in durablePages }
    ```
- **Definition**: [`BatchContextFrontier.kt:19-105`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt#L19) under `package eu.kanade.translation.pipeline.batch`.
- **Ownership & Nature**:
`BatchContextFrontier` is an algorithmic state helper tracking contiguous completed terminal predecessors in natural order and gap fencing. It updates rolling history strings via `TranslationContextChunkPlanner.updateRollingContext`.
It holds **zero** batch execution machinery: no coroutines, no workers, no persistence transactions, and no lifecycle coordination.
`context` is a lower-level domain service providing prompt preparation for both manual and batch requests. Placing `BatchContextFrontier` inside `pipeline.batch` forces `context` to depend upwards on a mode-specific orchestrator.

### Comprehensive Package Import Inventory

A full scan of inter-package imports across the 10 packages in `eu.kanade.translation.*` revealed:

- `engines` &rarr; `pipeline`: **exactly 2 imports** (`DeviceStageNormalization`, `NativeVisionTelemetry`).
- `context` &rarr; `pipeline`: **exactly 1 import** (`BatchContextFrontier`).
- `context` &rarr; `engines`: 1 import (`TranslationContextChunkPlanner`).
- `persistence`, `diagnostics`, `model`: **0 imports** of `pipeline`, `workflow`, or `scheduling`.
- `model`: **0 imports** of any peer translation packages (completely leaf).
- `workflow` and `scheduling` are **never** imported by `pipeline`, `engines`, `persistence`, `model`, `context`, or `diagnostics`.

### Existing Automated Architecture Checks

- **Build / Gradle**: `build.gradle.kts`, `app/build.gradle.kts`, and `buildSrc` contain **zero** ArchUnit, Konsist, or lint rules enforcing package boundaries.
- **CI Workflows**: `.github/workflows/build_pull_request.yml` runs only `spotlessCheck`, unit tests (`testStandardReleaseUnitTest`, `testDevReleaseUnitTest`), and Python model manifest tests.
- **Unit Tests**: No architecture boundary test exists in `app/src/test`.

### Nuance & Module Boundary Context

All these classes reside in the single `:app` Gradle module. Package relocations therefore reduce **source dependency and architectural coupling**, rather than offering module-level compile-isolation. Furthermore, the violations do not represent circular runtime execution loops; they are misplaced helper classes.

### Smallest Worthwhile Correction

1. **Relocate `DeviceStageNormalization`**: Move from `pipeline.adaptive` to `eu.kanade.translation.diagnostics` (or `model.normalization`), since its units are passed directly to diagnostic trace spans.
2. **Relocate `NativeVisionTelemetry`**: Move from `pipeline` to `eu.kanade.translation.diagnostics`.
3. **Relocate `BatchContextFrontier`**: Move from `pipeline.batch` to `eu.kanade.translation.context` (renaming to `ContextFrontier` or `RollingContextFrontier`), with `pipeline.batch` importing it as a shared frontier abstraction.
4. **Enforce Realistic Dependency Policy**:
     ```
      workflow, presentation
            ↓           ↓
         scheduling  pipeline
               ↘     ↙
            context, engines
               ↙        ↘
         persistence   diagnostics
               ↘        ↙
               model, util
     ```
    *Documented exceptions:*
  - `presentation` reading pipeline/scheduling read-only result types (e.g. `SinglePageOutcome`).
  - `workflow.TranslationRequestCoordinator` importing `presentation.TranslationUiProjection`.
5. **CI Check**: Add a lightweight Gradle task or Konsist/ArchUnit test verifying that `engines`, `persistence`, `context`, `diagnostics`, and `model` have 0 imports matching `eu.kanade.translation.pipeline.*`, `scheduling.*`, or `workflow.*`.

# Finding F-04: String Rejection Semantics in SinglePageOutcome.Rejected

### Verdict & Ratings

- **Verdict**: **Confirmed**
- **Severity**: **High** (coupling of core presentation state to string equality; brittle error classification causing silent loss of retry affordance; blocks localization and breaks accessibility)
- **Confidence**: **High** (verified in source and explicit unit tests)

### Exact Evidence & Trace

#### 1. The Human Sentence Constant

- [`TranslationPipeline.kt:209`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L209):
    ```kotlin
    const val REASON_TRANSLATION_NOT_SAVED = "Translation could not be saved; retry required"
    ```

#### 2. Producers and Coupling Failure Mode

- [`TranslationPipeline.kt:`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L572)[`74`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L742)[`2`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt#L572):
    ```kotlin
    SinglePageOutcome.Rejected(null, REASON_TRANSLATION_NOT_SAVED)
    ```
- [`Translation`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt#L319)[`UiTruth.kt:501-516`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/presentation/TranslationUiTruth.kt#L501-L516):
    ```kotlin
    private fun rejected(rejection: SinglePageOutcome.Rejected): PageUiTruth =
        if (rejection.owner == PageWriteOrigin.BATCH) {
            BATCH_SESSION_SWITCH
        } else if (rejection.reason == TranslationPipeline.REASON_TRANSLATION_NOT_SAVED) {
            NOT_SAVED
        } else {
            PageUiTruth(
                label = "Translation not started — ${rejection.reason}.",
                severity = UiSeverity.INFO,
                retryMode = UiRetryMode.NONE,
                retryAtEpochMs = null,
                actions = setOf(UiAction.DETAILS),
                terminalSuccess = false,
                contentDescription = "Translation was not started because ${rejection.reason}.",
            )
        }
    ```
- **The True Failure Mechanism**:
Because `REASON_TRANSLATION_NOT_SAVED` is a shared constant, editing that constant modifies both producer and consumer in lockstep. However, severe coupling failures remain:
  1. **Divergent Error Messages / New Producers**: If a new failure path or deeper store commit failure emits a rejection with a distinct diagnostic message (e.g. `"Failed to commit artifact document"`, `"Translation could not be saved: disk full"`), it misses the string check and silently drops into the `else` branch.
  2. **Silent Severity & Affordance Drop**: The `else` branch sets `severity = UiSeverity.INFO` and `actions = setOf(UiAction.DETAILS)` (no retry), whereas `NOT_SAVED` sets `severity = UiSeverity.WARNING`, `retryMode = UiRetryMode.EXPLICIT`, and `actions = setOf(UiAction.RETRY, UiAction.DETAILS)`. The user loses the ability to retry failed persistence.
  3. **Hardcoded Test Literals**: In [`P5OutcomeProjectionTest.kt:240-265`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/test/java/eu/kanade/translation/presentation/P5OutcomeProjectionTest.kt#L240), tests instantiate `SinglePageOutcome.Rejected(null, "Translation could not be saved; retry required")` with raw string literals, locking the UI test directly to arbitrary English wording.
  4. **Conflated Domains**: `SinglePageOutcome.Rejected` blends pre-execution admission denial (e.g., already translating, lease conflict) with post-execution persistence commit failure.
  5. **Accessibility Pollution**: Raw internal reason strings are directly concatenated into screen-reader announcements (`contentDescription = "Translation was not started because ${rejection.reason}."`).

### Smallest Worthwhile Correction

1. **Disentangle Persistence Failure from Admission Rejection**:
 Provide typed outcomes or reasons in `SinglePageOutcome`:
     ```kotlin
      sealed interface SinglePageOutcome {
          ...
          data class PersistenceFailed(val pageKey: String, val diagnostic: String = "") : SinglePageOutcome
          data class Rejected(val reason: RejectionReason, val diagnostic: String = "") : SinglePageOutcome
      }

      sealed interface RejectionReason {
          data object AlreadyTranslating : RejectionReason
          data class SessionConflict(val owner: PageWriteOrigin) : RejectionReason
          data object DefunctStore : RejectionReason
          data class LeaseHeldByOther(val owner: PageWriteOrigin, val stage: PageStage) : RejectionReason
      }
     ```
2. **Update `TranslationUiTruth`**:
 Map `SinglePageOutcome.PersistenceFailed` directly to `NOT_SAVED`.
 Map `RejectionReason.SessionConflict(PageWriteOrigin.BATCH)` to `BATCH_SESSION_SWITCH`.
 Map `RejectionReason.AlreadyTranslating` to a localized `"Translation already running"` truth with `UiSeverity.INFO`.
 Keep diagnostic strings strictly for logs and the `UiAction.DETAILS` dialog.

### Acceptance Criteria

- Diagnostic error/log wording changes cannot alter `truth.severity`, `truth.retryMode`, `truth.actions`, or user-visible outcome state.
- **T**`ranslationPipeline.REASON_TRANSLATION_NOT_SAVED` constant is removed from UI presentation branching.
- Unit tests in `P5OutcomeProjectionTest` assert against typed outcomes/reasons rather than hardcoded English diagnostic strings.

# Finding F-08: State Model Fragmentation & Semantic Duplication

### Verdict & Ratings

- **Verdict**: **Unsupported**
  - The audit claim of widespread semantic duplication, fragmented readiness rules, or competing state machines across `PageLifecycle`, `PageDisplayProjection`, `PageDisplayState`, `AutoSlotState`, and `TranslationUiTruth` is disproved by the full getter chain and call trace.
- **Severity**: **Low** (structural overlap in intermediate progress representations; no contradictory runtime behavior)
- **Confidence**: **High**

### Full Getter Chain & Readiness Gate Verification

Audit of [`PageTranslationState.kt:192-206`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt#L192):

```kotlin
val PageTranslationView.lifecycle: PageLifecycle
    get() = when {
        hasRenderedResult -> PageLifecycle.Done
        isTextlessTerminal -> PageLifecycle.Textless
        isStageCancelled -> PageLifecycle.Cancelled
        renderStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Render, retryCount, errorMessage)
        ...
```

Following the getter chain:

1. Line 194 evaluates `hasRenderedResult` first.
2. [`PageTranslationState.kt:69`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt#L69):
     ```kotlin
      val PageTranslationView.hasRenderedResult: Boolean
          get() = isTranslationDisplayReady
     ```
3. [`PageTranslationState.kt:41`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt#L41):
     ```kotlin
      val PageTranslationView.isTranslationDisplayReady: Boolean
          get() = toPageDisplayProjection().displayReady
     ```
4. **Result**: If a page has a committed display-ready result, `hasRenderedResult` is `true`. `lifecycle` immediately evaluates to `PageLifecycle.Done`. It does **not** fall through to intermediate failed candidate stages.
5. `AutoSlotState` similarly unifies on this gate: [`RollingAutoCoordinator.kt:1043`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt#L1043) assigns readiness solely via `storeState.isTranslationDisplayReady`.
6. `TranslationUiTruth.fromDurable` ([`TranslationUiTruth.kt:425`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/presentation/TranslationUiTruth.kt#L425)) projects directly from `PageDisplayProjection.displayReady`.

There is **zero contradictory readiness logic** across these components: `PageDisplayProjection.displayReady` is the single shared authoritative readiness gate.

### Production Usages of `PageView`

`PageView` is actively constructed and emitted across the reader production path:

- [`TranslationProgressProjection.kt:523-525`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/workflow/TranslationProgressProjection.kt#L523): `observePageView(chapterId, pageKey)` maps pages using `.toPageView()`.
- [`ReaderTranslationController.kt:1579, 1673`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt#L1579): Exposes `Flow<PageView>`.
- [`ReaderViewModel.kt:1811`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L1811): Forwards `observePageView`.
- [`PagerPageHolder.kt:173`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L173) & [`WebtoonPageHolder.kt:206`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt#L206): Collect `observePageView` to trigger `refreshTranslation()`.
- [`PageView.kt:37`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageView.kt#L37): Reads `lifecycle = lifecycle` during instantiation. Because `PageView` is a Kotlin data class, `lifecycle` and `overlay` participate in `equals()` and `hashCode()`.

### Analysis of Architectural Roles

The models do not represent fragmented duplicates; they serve distinct, legitimate concerns:

| Model | Owning Layer | Concern & Lifecycle |
| --- | --- | --- |
| `PageDisplayState` & `PageDisplayProjection` | `model` / `persistence` | **Committed vs. Candidate Display Truth.** Determines reader image visibility (`DISPLAY_READY`, `REFRESHING_WITH_COMMITTED_RESULT`, `FAILED_WITH_COMMITTED_RESULT`, `TEXTLESS_COMPLETE`). |
| `AutoSlotState` | `scheduling` | **Lookahead Window Scheduling.** Transient in-memory queue and lookahead states (`Queued`, `ReadingText`, `Cleaning`, `Translating`, `Rendering`, `Ready`, `Deferred(reason)`). |
| `SinglePageOutcome` | `pipeline.execution` | **Execution Intent Dispatch.** Synchronous/coroutine return from manual execution boundary (`Admitted`, `Completed`, `Paused`, `Stalled`, `Failed`, `Attached`, `Rejected`). |
| `TranslationUiTruth` | `presentation` | **UI Presentation Truth.** Maps durable display and scheduler outcomes into user-facing copy, severity, actions, and accessibility descriptions. |
| `PageLifecycle` | `model` | **Coarse Candidate Progress.** Synthesizes linear stage progress for `PageView` data-class emissions. |

### Conclusion & Recommendation

- **Do NOT invent a unified state machine.** Collapsing durable display state, scheduling lookahead, execution dispatch, and UI presentation into one enum would destroy concurrency boundaries and create tight coupling across subsystems.
- If future cleanup of `PageView` is desired, it should be treated as an independent simplification of view-holder invalidation signaling, not a mandated refactor to fix a non-existent state machine bug.
