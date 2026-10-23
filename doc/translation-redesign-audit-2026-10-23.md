# Translation UX Redesign — Audit Report & Cleanup Plan

Date: 2026-10-23
Branch: `ui-experimentation`
Range covered: `6f09333a..3624d35a` (9 commits)
Status: all work committed; device install pending (device offline at time of build)

---

## 1. Scope

This document records the state of the translation UX/UI overhaul on `ui-experimentation`:
the batch translation sheet redesign, the reader drawer rebuild, the chapter-reset
consolidation, the persistence-layer cleanup, the coordinator decoupling, and the
audit that decided what was deliberately left unfinished. It closes out the audit
loop and defines the remaining execution plan.

## 2. Completed work

| Commit | Summary |
|---|---|
| `81df8439` | Shared design-system components (`components/designsystem`) + `TranslationOptions` lists (additive) |
| `c9c5cfcc` | Reader translation drawer rebuilt as focused sections (Activation/Engine/Language/Vision/Reset); old tabs and settings sections deleted |
| `bcc3d5df` | Batch translation config dialog + Focus/Details progress sheet redesign; manga screen wiring; vortex-named package deleted |
| `593bdc3a` | Chapter reset collapsed to a single "Reset Chapter"; granular chapter resets, preflight, and dead persistence members removed |
| `bf3cdcac` | Dead presentation code pruned; stale strings removed |
| `08ba4319` | Offline-download warning moved out of the translation coordinator (seam + presenter) |
| `4983cd06` | Cancel-undo and resume-paused notices moved to the screen model; resume hint i18n'd |
| `3624d35a` | 36 unreferenced i18n keys pruned (220 → 184) |
| `6f09333a..3624d35a` total | Working tree converged: manga package tests 21/21, spotless clean, i18n sweep at 0 dead keys |

Key structural outcomes:

- `MangaTranslationCoordinator` is pure orchestration: no `SnackbarHostState`, no
  `Injekt`, no `Application`, no Compose imports. UI notices are constructor callbacks
  (`onTranslationsQueuedBehindDownloads`, `onTranslationCancelled`, `onTranslationResumePaused`)
  presented by `MangaScreenModel`.
- The formerly-failing `MangaScreenModelMultiSelectBatchTest` is green; its root cause
  was an `Injekt.get<Application>()` lookup introduced into the coordinator's admission
  path by `cc2d54bc` without a fixture update.
- Chapter reset is one action everywhere at chapter level; manual-edit preservation is
  no longer offered at chapter level (documented in the dialog copy).

## 3. Fresh-eye audit findings (2026-10-23)

Independent read-only investigation of the three deliberately-unfinished items.

### 3.1 Engine-layer `Injekt.get<Application>()` — KEEP AS-IS

Sites: `engines/runtime/EngineMemoryBudget.kt`, `engines/runtime/onnx/QnnVisionContextCache.kt`,
`engines/runtime/onnx/QnnDiagnostics.kt`, `engines/inpainting/aot/NeuralInpaintingCoordinator.kt`,
`processing/memory/TranslationMemoryBudget.kt`.

Every lookup gathers device facts that do not exist off-device (`ActivityManager.MemoryInfo`,
`filesDir`, `nativeLibraryDir`, thermal/battery). The testable decision logic is already
separated and tested via parameter injection (`EngineMemoryBudgetTest`,
`TranslationMemoryBudgetTest`, `QnnSessionMemoryPrecheckTest`); the Application lookups are
fact-gatherers that only execute inside the installed app, and nearly all are defensively
guarded. An `AppContext`-interface refactor would touch ~10 files for zero behavior or
testability gain. Do not do it.

Optional tidy: `EngineMemoryBudget.kt:168` (`logSnapshot`) is the one unguarded lookup
among guarded siblings — wrap it when the file is next touched.

### 3.2 Reader per-page reset rows — CONSOLIDATE 5 ROWS TO 2

Current: `presentation/reader/sheet/TranslationResetSection.kt` renders five rows
(keep-edits, clear-edits, inpaint, OCR, everything) behind confirm dialogs, wired through
`ReaderTranslationSheet` → `ReaderViewModel` → `ReaderTranslationController` →
`ReaderTranslationActions` → `TranslationManager` → `ChapterDataResetController`.

Defects found:

1. **"Reset everything" is literally `resetOcrData()`** (`ReaderTranslationActions.kt:224-226`)
   — two rows invoke the identical function.
2. **"Clear edits" is broken**: the `preserveEdits=false` branch
   (`ChapterDataResetController.kt:204-215`) clears text but never nulls `userEditedAt`,
   so the AI apply path then skips those blocks as "user-edited"
   (`ContextualResponseParser.kt:323`) and re-OCR re-applies the now-empty translations
   (`ChapterStageMerges.kt:367-374`). The row suppresses re-translation — the opposite of
   its promise.
3. The inpaint row is near-redundant: the inpaint fingerprint includes the inpaint
   settings (`StageFingerprints.kt:73-88`), so changing vision settings already
   auto-invalidates inpainting.
4. All five rows share copy saying "for this chapter" while acting on the current page only.

Target shape (mirrors the manga screen's single Reset Chapter):

- Row 1: **Reset translation (keep edits)** — `resetTranslationData`, always preserving
  `userEditedAt` blocks (flag removed per the no-flag rule, which also deletes the broken
  `preserveEdits=false` branch).
- Row 2 (danger): **Reset page** — `resetOcrData` (full page invalidation; also backs
  per-page retranslate).

Implementation sketch (~150 net lines removed, 8 files + i18n): shrink
`TranslationResetSection` to 2 rows; trim the 5-callback chain to 2 through
`ReaderTranslationSheet`/`ReaderViewModel`/`ReaderTranslationController`/
`ReaderActivity`; delete `resetInpaintData`/`resetEverything` and the
`preserveEdits` flag; i18n: delete `reader_data_reset_clear_edits`, `..._inpaint`,
`..._ocr`, `..._everything`, fix the summary copy to "for this page". No test pins the
deleted backend paths; `TranslationManagerDeleteResetOrderingTest` pins `resetOcrData`,
which stays.

### 3.3 Test-pinned production-dead members — DELETE ALL

1. `TranslationProgressSheet.queuePositionLabel` / `ordinalSuffix`
   (`TranslationProgressSheet.kt:1034-1038`): zero production callers; forwarders to live
   logic in `TranslationUiTruth`. Pinned only by two cases in
   `TranslationQueuePositionAndPhasesTest`. Delete members + cases; move the ordinal
   edge-case coverage (11th/12th/13th…) into a `TranslationUiTruth`-level test under
   `app/src/test/java/eu/kanade/translation/presentation/`.
2. `ChapterTranslationStore.reconcileArtifactRetentionAsync`
   (`ChapterTranslationStore.kt:718-729`) + `ChapterArtifactEngine.reconcileRetentionAsync`
   + `retentionScope`/`retentionScopeJob`/`retentionInFlight` fields: dead machinery chain;
   `StoreArtifactRetentionCloseTest` constructs its scenario only through the dead entry
   point, so the invariant cannot occur in production. Delete members + test (~60 lines).
   Retention still happens via the open-time sweep (chapters ≤ 8 pages) and targeted
   orphan deletes in candidate transactions.
3. `PageVersionMismatch` and `PageLeaseTokenMismatch` detail subtypes
   (`ChapterTranslationStore.kt:220-228`), plus never-constructed `BatchPageLeaseMissing`
   (`:216`): zero readers; equivalent diagnostics already exist in the rejection reason
   string and `lastGuardedWriteRejectionDiagnostic`. Keep `ArtifactPublicationFailed`
   (load-bearing). ~15 lines.

### 3.4 Bounded-sweep findings

| # | Finding | Action |
|---|---|---|
| 1 | `ChapterDataResetController.reconcileBatchProgress` takes 4 dead params (`:352-365`) | Trim signature (2 call sites) |
| 2 | ~20-line companion-image retirement block duplicated across `resetInpaintData`/`resetOcrData` | Extract helper (mostly dies with §3.2) |
| 3 | Stale "for this chapter" copy on per-page rows | Folded into §3.2 |
| 4 | `TranslationProgressSheet.kt` is 1043 lines — past the god-file threshold | Split Focus/Details panels + copy helpers into sibling files when reader work touches it |
| 5 | **`ChapterTranslationStore.close()` + `reconcileArtifactRetention()` appear production-dead** — teardown uses `markDefunct`/`closeAndFlush`, so the full-tree retention sweep for large chapters (>8 pages) looks unreachable | OPEN DECISION (§4) |
| 6 | No dead i18n keys remain in the reset/reader-sheet area | — |

## 4. Execution plan

Ordered, each phase separately committable:

1. **Item 3 deletions** (mechanical, ~75 lines + 1 test file + 1 new small test).
2. **Reader reset consolidation (§3.2)** — user-facing win; natural entry into the
   reader pass.
3. **Small trims** — `reconcileBatchProgress` params, `logSnapshot` guard.
4. **OPEN DECISION — retention sweep (sweep finding 5)**: wire
   `reconcileArtifactRetention()` into `deleteTranslation`/defunct teardown (behavior
   change: restores full-tree orphan cleanup for large chapters), or delete the pair
   deliberately (accepts targeted-only retention). Needs a product call on whether
   large-chapter orphan cleanup is currently leaking files.

## 5. Verification state at time of writing

- `:app:spotlessCheck`, `:app:compileStandardDebugKotlin`,
  `:app:compileStandardDebugUnitTestKotlin` green.
- Manga package tests 21/21; persistence + reset-ordering tests green; 203 total filtered
  test results, 0 failures.
- i18n: 184 keys defined, 0 unreferenced.
- APK built at 14:26 (post-`3624d35a`); install to device pending — device was offline.
