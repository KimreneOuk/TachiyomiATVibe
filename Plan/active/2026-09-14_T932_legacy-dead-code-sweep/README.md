# T932 — Legacy / dead-code detection sweep

## Question (Director)

"Detect any legacy files, codes that still exist. I believe we no longer
support legacy. We are pursuing a clean code project going forward."

## Ground rules

- Codebase over documentation: deadness must be REFERENCE-PROVED (who
  constructs/calls it, from where), same method as the T924 D2 sweep
  (commit eeff99f, -922 lines).
- This is DETECTION ONLY. No deletions in this task; deletion batches are
  proposed, Director approves.
- This repo is a Mihon fork: distinguish TachiyomiAT-owned translation
  legacy (deletable at will) from Mihon-upstream code (deletion has
  upstream-merge cost).

## Classification taxonomy (every finding gets exactly one)

- **DEAD** — zero live references / unreachable at HEAD. Delete-safe now.
- **MIGRATION** — executes only for pre-artifact / flag-era on-disk data.
  Deleting orphans that data on old installs; Director decision required
  (is any real device still carrying pre-artifact state?).
- **LIVE-MISLABELED** — named "legacy" but on today's hot path. Must NOT be
  deleted blindly; rename/re-home candidate instead. (Known example:
  `X_images/` companion consumption by the live reader.)
- **STALE-DOC** — comments/KDoc referencing removed machinery. Cosmetic.
- **UPSTREAM** — Mihon-inherited, unused by this fork. Merge-cost caveat.

## Known hot leads (from prior audits — verify, don't rediscover)

- `X_images/` legacy companion dir: LIVE reader consumption path.
- Legacy flat-file persist lane (StorePersistenceScheduler when
  authority != ARTIFACTS; legacyDocuments(); glossary flat fallback).
- LegacyArtifactMigration + LegacyArtifactRescue (live call sites at
  ChapterArtifactStore.kt:206, LegacyArtifactRescue.kt:49).
- `context/` dir in ChapterArtifactLayout: unwritten, swept by retention.
- `X.summary.json` sidecar convention (mentioned in layout KDoc — alive?).
- SequentialBatchCoordinator / FF-01 comment-only references.
- ChapterRunRecord.flagProfilePipeline flag-era compat field.
- T924FeatureFlagsTest FF-02; ~10 stale KDoc blocks in tests.
- Page15MockRig (Phase 0.3 already schedules eviction).
- `_gui_probe/` untracked dir at repo root (git status).

## Deliverables

- team/prod/report.md — production-code sweep (app/src/main).
- team/test-tools/report.md — tests, tools/, repo-root strays.
- team/build/report.md — build config, manifest, resources, deps.
- report/DIRECTOR_REPORT.md — classified inventory + tiered deletion plan
  + the one MIGRATION decision the Director must make.
