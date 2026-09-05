# Baseline Capture — T922, 2026-09-04

Role: Repository Steward (baseline capture only; no modifications, resets, cleans, stashes, rebases, merges, or deletions performed in the worktree).

Predecessor report: `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/repository/REPO_HEALTH.md` (read, not modified).

## Overall readiness status

**GREEN** — for starting surgical edits.

The worktree exactly matches the state documented in REPO_HEALTH.md for branch, HEAD, tracked-dirty set, staged state, and the five protected untracked files. The only divergence is three new untracked task-document files (plan/review/report artifacts, no source code) created by other agents after REPO_HEALTH.md was written. The full tracked dirty patch and the five protected untracked files are now backed up outside the worktree. Standard caveat remains: all agents share this worktree, so rerun `git status --short` immediately before every edit group and stop if another agent is editing the same file.

## Current branch and HEAD

- Branch: `optimize_translation_pipeline_ux` (expected branch confirmed; not detached)
- HEAD: `7a95f9c94ec369efb6273ba7a209f7e42ddc4f80` (`checkpoint: verified ORT 1.27.0 working HTP/GPU platform and isolation baseline`)
- Last 3 commits:
  1. `7a95f9c` checkpoint: verified ORT 1.27.0 working HTP/GPU platform and isolation baseline
  2. `392a541` docs(t918): commit the batch-translation-logic-audit task records
  3. `7ba2628` merge: T921 reader-entry latency — retention sweep removal + entry-path SAF dedup slice
- Staged changes: none (`git diff --cached --binary` is 0 bytes)
- Merge/rebase/cherry-pick/revert/bisect state: none detected
- Untracked inventory method: `git status --short --untracked-files=all`, cross-checked with `git ls-files --others --exclude-standard` (identical results)

## Divergence vs REPO_HEALTH.md expectations

Matches:

- Branch and HEAD identical (`7a95f9c94ec369efb6273ba7a209f7e42ddc4f80`).
- All 14 dirty tracked files identical to the REPO_HEALTH.md list, with the same totals: 566 insertions, 72 deletions (`git diff --stat`).
- Staged changes: still none.
- The five meaningful protected untracked files are present and unchanged in identity (hashes recorded below).

New since REPO_HEALTH.md was written (3 untracked files; preserved, not removed):

1. `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/translation-pipeline-fix-and-observability-plan.md`
2. `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/repository/REPO_HEALTH.md` (the health report itself, now tracked as an untracked task artifact)
3. `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/review/translation-pipeline-fix-and-observability-plan-review.md`

All three are task documentation (engineering plan, review record, repository report). None touch product source or tests, so they do not change the surgical-edit risk picture. Additionally, this report itself (`repository/BASELINE_CAPTURE_2026-09-04.md`) becomes a ninth untracked file after capture.

## Dirty tracked files (full list, 14 files; 566 insertions, 72 deletions)

| File | Changes |
| --- | --- |
| `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/README.md` | 5 +- |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationFeedback.kt` | 3 +- |
| `app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt` | 101 ++++++++- |
| `app/src/main/java/eu/kanade/translation/inpainting/aot/AotBoxGeometry.kt` | 82 +++++++ |
| `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6DetEngine.kt` | 56 +++++-- |
| `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt` | 13 ++ |
| `app/src/main/java/eu/kanade/translation/runtime/onnx/HardwareDiscoveryEngine.kt` | 45 +++++- |
| `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt` | 88 ++++++--- |
| `app/src/main/java/eu/kanade/translation/runtime/onnx/QnnDiagnostics.kt` | 162 ++++++++++++++++++- |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationFeedbackTest.kt` | 23 +++ |
| `app/src/test/java/eu/kanade/translation/inpainting/aot/AotBoxGeometryTest.kt` | 48 +++++ |
| `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnProbeModelTest.kt` | 6 +- |
| `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnProviderOptionsTest.kt` | 4 +- |
| `gradle/libs.versions.toml` | 2 +- |

Staged patch: none existed, so no `baseline_staged.patch` was written (step skipped per assignment; `git diff --cached --binary` produced 0 bytes).

## Full untracked inventory (8 files at capture time)

Protected five (listed in REPO_HEALTH.md; backed up):

1. `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/auto-translation-failure-and-recovery-handover.md`
2. `app/src/main/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngine.kt`
3. `app/src/main/java/eu/kanade/translation/runtime/onnx/QnnContextCacheManager.kt`
4. `app/src/test/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngineTest.kt`
5. `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnContextCacheManagerTest.kt`

New since REPO_HEALTH.md (preserved in place; no source code):

6. `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/translation-pipeline-fix-and-observability-plan.md`
7. `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/repository/REPO_HEALTH.md`
8. `Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/review/translation-pipeline-fix-and-observability-plan-review.md`

A copy of this inventory is saved at `C:/Users/User/Documents/T922_baseline_backup_2026-09-04/untracked_inventory.txt`.

## Backup artifacts (outside the worktree)

Directory: `C:/Users/User/Documents/T922_baseline_backup_2026-09-04/`

- `baseline_tracked.patch` — full tracked baseline patch, `git diff --binary`, 56,290 bytes, 14 file diffs (verified via `grep -c '^diff --git'` = 14)
- `untracked_inventory.txt` — complete untracked inventory with capture metadata
- `Plan_active_2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap_engineering_auto-translation-failure-and-recovery-handover.md` — copy of protected file 1
- `app_src_main_java_eu_kanade_translation_runtime_onnx_ModelRoutingEngine.kt` — copy of protected file 2
- `app_src_main_java_eu_kanade_translation_runtime_onnx_QnnContextCacheManager.kt` — copy of protected file 3
- `app_src_test_java_eu_kanade_translation_runtime_onnx_ModelRoutingEngineTest.kt` — copy of protected file 4
- `app_src_test_java_eu_kanade_translation_runtime_onnx_QnnContextCacheManagerTest.kt` — copy of protected file 5
- `baseline_staged.patch` — intentionally not written; there were no staged changes

## SHA-256 hashes of the five protected untracked files

Original (worktree) and backup copy hashes are identical for all five; the copies are byte-exact.

| File | SHA-256 (original == copy) |
| --- | --- |
| `engineering/auto-translation-failure-and-recovery-handover.md` | `e1dce9a91899e9769c38c06736ad904b4b0fec266998f64e21ffcbb85e6c0240` |
| `app/src/main/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngine.kt` | `430102d925113ce4b0f7700bdfb68d986cf24922bb22364c6e2e4ea8613bd991` |
| `app/src/main/java/eu/kanade/translation/runtime/onnx/QnnContextCacheManager.kt` | `f66aaabe07a374e333ecfdf465aee5a339135f6306171cb3b7686d4cbcb641cf` |
| `app/src/test/java/eu/kanade/translation/runtime/onnx/ModelRoutingEngineTest.kt` | `7d61f647d579504632aee801e8dd7565310720b095e1d7662d552427cca8d50d` |
| `app/src/test/java/eu/kanade/translation/runtime/onnx/QnnContextCacheManagerTest.kt` | `de74c62c63d6c67c0729a8f88be4c3d92891950c643da3e2e6be9f68af700c32` |

## Standing constraints for implementers (unchanged from REPO_HEALTH.md)

- Do not clean, reset, stash, rebase, merge, or switch branches in this worktree.
- Treat all dirty tracked content and the five protected untracked files as Director-owned; preserve them; hunk-level additions only.
- Rerun `git status --short` immediately before every edit group; stop if another agent is editing the same file.
- After implementation, compare `git diff` per touched file against `baseline_tracked.patch` to prove pre-existing edits survived intact.
