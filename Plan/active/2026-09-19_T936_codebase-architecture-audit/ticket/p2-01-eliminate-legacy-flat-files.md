# Ticket P2-01: Eliminate the legacy flat-file translation subsystem

**Phase:** 2 | **Risk:** Medium (wide reference surface, no runtime behavior change for
artifact-era chapters) | **Type:** Deletion + guard simplification

## Director decision (pre-authorized, consequence flagged)

Eliminating legacy reads means chapters translated by pre-artifact app builds (stored as
`<chapter>.translation.json` + `<chapter>_images/`) will NO LONGER migrate: they appear
untranslated and must be retranslated. Artifact-era chapters are unaffected. Rollback = git
revert. This is the stated architecture goal (audit §Storage spec Step 1, Director principle 3).

## Verified evidence (main @ `65a738e`)

- Legacy subsystem files (all in `eu.kanade.translation.artifact`):
  - `LegacyArtifactMigration.kt` (465 lines) — flat-file → artifact DTO migration
  - `LegacyArtifactRescue.kt` (467) — LEGACY→ARTIFACTS manifest cutover/rescue
  - `LegacyChapterMigrationSource.kt` (404) — legacy open/migration source of truth
  - `LegacyFlatFileDecoder.kt` (72) — `.translation.json` decode/quarantine
- `ManifestAuthority` (ArtifactContracts.kt:113) is a 2-value enum; `LEGACY` is the
  DEFAULT authority for a fresh manifest (ChapterArtifactManifest.kt:53) with a rescue
  cutover flipping it to `ARTIFACTS`.
- ~20 production files reference `ManifestAuthority`; all are `== ARTIFACTS` admission
  guards (ReaderViewModel.kt:2996, ChapterProfileBatchCoordinator.kt:376, BatchRenderJoin,
  ChapterGlossaryStore, StorePersistenceScheduler, ChapterAttemptLedger, StoreStatusProjector,
  DurableChapterStatusResolver, TranslationManager, ChapterArtifactStore, ChapterTranslationStore,
  ChapterArtifactDeletion, LegacyChapterMigrationSource, LegacyArtifactRescue).
- `.translation.json` decode/quarantine seams also live in TranslationManager (~L1321-1333)
  and DurableChapterStatusResolver (~L93-98). `_images/` companion dir:
  ChapterArtifactLayout.kt:90 `legacyCompanionImageFile`.
- Tests: `LegacyArtifactMigrationTest.kt` is legacy-specific; several suites reference
  `ManifestAuthority` incidentally (artifact-authoritative defaults). ~80 test files match
  the symbols — most are incidental.

## Changes

1. Delete the 4 `Legacy*.kt` files and `LegacyArtifactMigrationTest.kt`.
2. Collapse `ManifestAuthority`: remove the enum and every `authority`/`ManifestAuthority`
   guard. Semantics after removal: the artifact manifest is ALWAYS authoritative (the
   current `== ARTIFACTS` behavior). Remove `authority` from `ChapterArtifactManifest`,
   `ChapterArtifactDeletion.AuthorityProbe`, and all `manifest.authority` checks in the
   ~20 files (guards that admitted only ARTIFACTS become unconditional; LEGACY-only
   branches are deleted).
3. Remove legacy read seams in TranslationManager (decodeLegacyChapterTranslation /
   quarantine / legacyPageJson usage, status-from-readable-pages fallback at ~L1181) and
   DurableChapterStatusResolver (decodeLegacyChapterStatus fallback) — replace with the
   artifact-only path (null when no artifact manifest).
4. Remove `legacyCompanionImageFile` / `_images/` references in ChapterArtifactLayout
   (check CleanedImageProbe/ArtifactRetention for legacy-image fallbacks and remove them).
5. Update incidental test references: tests constructing manifests with
  `authority = ManifestAuthority.*` drop the argument; tests specifically verifying legacy
   migration/cutover behavior are deleted (enumerate them in the report — do NOT weaken
   unrelated assertions to make things pass).
6. Deletion of on-disk legacy files is OUT OF SCOPE — the app simply stops reading them.
   No file-system sweeps, no deletion of user data.

## STOP-gate

Before coding, commit a seam inventory to your report: grep results for every symbol above,
the list of LEGACY-default manifest constructions, and the exact list of tests you will
delete vs modify. If you find a legacy path that is NOT purely read/migration (i.e., it is
on the write path for NEW work), STOP and report — that would contradict this ticket's premise.

## Verification

1. `git grep -l 'LegacyArtifact\|LegacyChapterMigration\|LegacyFlatFile\|ManifestAuthority'`
   under `app/src` returns nothing.
2. Both-flavor full unit suites green.
3. `assembleDevDebug` green.

## Commit

`refactor(translation): eliminate legacy flat-file translation subsystem and authority enum`
