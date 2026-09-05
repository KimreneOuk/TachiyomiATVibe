# Repository Health — T922 Translation Pipeline Fix and Timing Logs

## Status

**YELLOW**

The dedicated `optimize_translation_pipeline_ux` worktree is on a current, non-diverged base and is suitable for this task, but it contains a substantial pre-existing uncommitted change set. Future implementation must preserve those edits and work surgically; bulk replacement, cleanup, rebasing, or branch switching in this worktree is unsafe.

## Repository identity and base

- Worktree: `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux`
- Repository remote: `origin = https://github.com/KimreneOuk/TachiyomiATVibe.git`
- Branch: `optimize_translation_pipeline_ux`
- HEAD: `7a95f9c94ec369efb6273ba7a209f7e42ddc4f80` (`checkpoint: verified ORT 1.27.0 working HTP/GPU platform and isolation baseline`)
- Tag at HEAD: `checkpoint-qnn-ort-1.27.0-working`
- Upstream tracking branch: none
- Fetched base: `origin/main = 392a541f2f620857729b3ef7820679ba3f90c9a9`
- Merge base with `origin/main`: `392a541f2f620857729b3ef7820679ba3f90c9a9`
- Divergence from `origin/main`: 0 behind, 1 ahead
- Detached HEAD: no
- Merge/rebase/cherry-pick/revert state: none detected
- Staged changes: none

The branch base is current as of the repository-health check. Do not rebase or merge before the existing uncommitted work is attributed and captured.

## Pre-existing dirty state to preserve

Tracked modifications present before this requested implementation:

- `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/README.md`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationFeedback.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/aot/AotBoxGeometry.kt`
- `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6DetEngine.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt`
- `app/src/main/java/eu/kanade/translation/runtime/onnx/HardwareDiscoveryEngine.kt`
- `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt`
- `app/src/main/java/eu/kanade/translation/runtime/onnx/QnnDiagnostics.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationFeedbackTest.kt`
- `app/src/test/java/eu/kanade/translation/inpainting/aot/AotBoxGeometryTest.kt`
- `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnProbeModelTest.kt`
- `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnProviderOptionsTest.kt`
- `gradle/libs.versions.toml`

Meaningful untracked files present before this requested implementation:

- `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/auto-translation-failure-and-recovery-handover.md`
- `app/src/main/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngine.kt`
- `app/src/main/java/eu/kanade/translation/runtime/onnx/QnnContextCacheManager.kt`
- `app/src/test/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngineTest.kt`
- `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnContextCacheManagerTest.kt`

The tracked diff totals approximately 566 insertions and 72 deletions across 14 files, plus the five meaningful untracked files above. These changes are task-related in subject matter, but their authorship and acceptance state are unknown; they must be treated as Director-owned work.

## Safe implementation boundaries

The following likely implementation entry points were clean at inspection time and are the safest places for future surgical changes:

- Bubble CPU-primary routing and local runtime recovery:
  - `app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt`
- Shared recognition/inpainting stage timing:
  - `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt`
- Single-page native and HTTP/render phase timing:
  - `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt`
  - `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt`
  - `app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt`
- Manual scheduling/page lifecycle:
  - `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
  - `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
  - `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt`
- Auto scheduling/page lifecycle:
  - `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt`
- Batch scheduling/page lifecycle:
  - `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt`
  - `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt`
  - `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- Existing clean test entry point:
  - `app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorTest.kt`
- A new narrowly scoped timing/trace utility and new tests may be added under the existing translation packages without colliding with tracked files, provided names are checked immediately before creation.

These files were clean only at the time of this report. The implementer must rerun `git status --short` before editing because all agents share the worktree.

## High-risk overlap boundaries

Avoid replacing or broadly refactoring these files:

- `OnnxRuntimeProvider.kt` already contains extensive changes to generic HTP options, routing decisions, provider labels, session configuration, and creation-time CPU fallback.
- The untracked `ModelRoutingEngine.kt` and its test already define model support/demotion behavior. Never recreate, delete, or overwrite them.
- `AOTInpainting.kt` already contains QNN context-cache/routing changes and separate inpainting behavior changes.
- `PaddleOcrV6DetEngine.kt` already contains accelerator configuration and memory-pool changes.
- `HardwareDiscoveryEngine.kt`, `QnnDiagnostics.kt`, QNN tests, and `gradle/libs.versions.toml` are part of the current hardware/runtime experiment and must remain intact.
- `CleanedPublication.kt` and reader-feedback files contain unrelated correctness/UI fixes; unified logging should not be implemented by rewriting them.

If robust segmenter fallback needs changes in `OnnxRuntimeProvider.kt` or `ModelRoutingEngine.kt`, first capture the exact pre-edit diff, then make a minimal hunk-level addition that preserves all existing code. Prefer containing the bubble-specific session ownership and one-shot CPU retry in the currently clean `OnnxBubbleSegmenter.kt` when technically valid. Do not infer that the untracked routing implementation is disposable.

## Required preservation procedure

Before implementation:

1. Record `git status --short --branch` and `git diff --stat`.
2. Save the current tracked patch outside the repository or with `git diff --binary` to a uniquely named task artifact; separately inventory untracked files. Do not use stash because untracked task files and shared-agent edits can be obscured.
3. Hash or copy the five pre-existing untracked files to a safe task-local backup location outside the Git worktree if ownership cannot be confirmed.
4. Rerun status immediately before every edit group and stop if another agent has begun editing the same file.
5. Use hunk-level patches only. Do not run formatters across modules or broad mechanical rewrites.
6. After implementation, inspect `git diff -- <each touched file>` and compare the pre-existing dirty-file patch with the captured baseline.
7. Keep the repository-health report change distinct from product/source changes during review.

## Recommendation

Continue in this dedicated worktree because its committed checkpoint and dirty runtime work form the relevant task context. The safe course is a surgical implementation limited primarily to the currently clean segmenter, shared page-phase, and manual/auto/batch scheduler files. Any necessary edit to the already-dirty ONNX provider/routing files requires explicit diff preservation and independent review. Do not clean, reset, stash, rebase, merge, or switch branches in this worktree.
