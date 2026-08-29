# T908 — Cleanup Implementation Report

Date: 2026-08-29 · Branch: `optimize_translation_finishing_page`
Executor: Main Leader (implementer subagent completed edits but was cut off
before verification/report; Main Leader reviewed the full diff and completed
verification — the subagent's edits were reviewed line-by-line before acceptance).

## 1. Deduplication (4 groups, all behavior-preserving)

| Group | Was | Now |
|---|---|---|
| `getChapterPages(chapterPath)` ~45 identical lines | `ChapterTranslator.kt:684`, `TranslationPipeline.kt:5370` | canonical `internal fun getChapterPages(context, chapterPath)` in new `util/ChapterPages.kt` (kept the richer ChapterTranslator comments); both call sites delegate |
| `isDiagnosticsEnabled()` verbatim ×3 | `MangaOcrEngine.kt:481`, `PaddleOcrV6DetEngine.kt:311`, `PaddleOcrV6SmallEngine.kt:208` | shared `internal object OcrDiagnostics` (new `ocr/OcrDiagnostics.kt`); caching is now process-wide instead of per-class — equivalent because all three read the same read-once preference; unused `catch (e)` normalized to `catch (_)` |
| `safeAdd` byte-identical ×2 | `ProviderRequestGovernor.kt:823`, `TranslationRetry.kt:281` | single `internal fun safeAdd` in `ProviderRequestGovernor.kt`; `TranslationRetry` uses it (same package) |
| `identitiesMatch` ×2 | `ChapterArtifactStore.kt:1428`, `ChapterArtifactDeletion.kt:100` | single `internal fun` in `ArtifactContracts.kt` next to `LegacySourceIdentity` |

## 2. Never-called function triage (audit section A, 17 candidates)

Deleted (9) — zero references re-verified, no retention rationale found:

| Function | File |
|---|---|
| `getActivePageKeys` | TranslationManager.kt |
| `translatorStart` | TranslationManager.kt |
| `openChapterTranslationStore` | TranslationManager.kt |
| `openActiveChapterTranslationStore` | TranslationManager.kt |
| `observeActiveStore` | TranslationManager.kt |
| `getBatchTracker` | TranslationManager.kt |
| `getCompanionImageDirForChapter` | TranslationManager.kt |
| `tryRenderStandalone` | TranslationPipeline.kt |
| `antiAliasInset` (dead interface stub) | rendering/TextLayoutPlanner.kt |

Kept (8) — zero references but documented API surface / conservative default:

| Function | Retention evidence |
|---|---|
| `isPageActive`, `observeChapterTranslationStatus` | named in T902 phase-b-review notes |
| `deletePageTranslation`, `getContextualTranslator` | named in unified-translation-pipeline handoff/checkpoints |
| `patchBlock`, `mergeTranslation` | named in translation-quality / panel-aware-block-sorting design docs |
| `ensureArtifactAuthorityForMutation` | named in T902 phase-c4 implementation + technical design |
| `mergeInpaint` | no planning mention; 1-line helper kept under conservative default |

## 3. Verified-dead objects + stale artifacts (Main Leader, pre-delegation)

- Deleted `RenderQuality` (`model/PageTranslation.kt`) and
  `SharedProviderRequestAdmission` (`TranslationStageContracts.kt`) — audit-verified
  zero references.
- Deleted 2 generator byproducts: `app/src/test/resources/corpus/{aot,aot_sub512}/emission_report.json`
  (unreferenced; corpus tests enumerate directories only).
- Docs corrected: `docs/DATA_FLOW.md`, `docs/TRANSLATION_MODULE.md` no longer
  describe the nonexistent `RenderQuality.SIZE_LIMITED` marking / `renderQuality`
  metadata; now reference the real `decodeSampleSize > 1` recording.
- ADOPTED-THEN-REVERTED T906 test-enable patch: applying
  `runBlocking` → `runBlocking<Unit>` on 9 `RollingAutoCoordinatorTest` tests
  (snapshot of the removed t906-fix-area1 worktree) made the previously
  never-executed tests actually run — and **`local compute serializes prepare
  and translate through a shared gate` (RollingAutoCoordinatorTest.kt:169)
  deadlocks** (confirmed via thread dump: `Test worker` blocked in runBlocking,
  CPU frozen; killed after ~25 min). The revert restores the file to HEAD.
  This is a **P1 finding for the T906 thread**: the "8 never-executed tests"
  are not just skipped — at least one contains a latent test/coordinator
  deadlock that enabling exposes. The patch remains archived at
  `discarded-worktree-snapshots/t906-fix-area1 RollingAutoCoordinatorTest.patch`.

## 4. Verification

- `:app:compileStandardDebugKotlin` — **PASSED** (exit 0).
- `:app:testStandardDebugUnitTest` over translator, ocr, rendering, artifact,
  manga-UI and T907 recovery tests — **PASSED: 40 test classes, ~350 tests,
  0 failures, 0 errors** (1m 50s).

## 5. Follow-ups recommended

- **P1 (T906 thread):** `RollingAutoCoordinatorTest` — enabling the never-executed
  tests deadlocks at line 169; needs a dedicated fix task (test infra
  `ControllableExecutor` or a real `RollingAutoCoordinator` gate bug).
- `TranslationPipeline.kt` (5,415 lines) split — architecture task, not housekeeping.
- Remaining 23 file-locked public types → visibility demotion (mechanical, low priority).
- T906 audit-only recommendations beyond the test-enable patch remain open.
- Push decisions for the Director: local `main` (125 ahead of origin/main),
  `design_ai_org_roles` (4 ahead of its origin counterpart), remote branch pruning.
