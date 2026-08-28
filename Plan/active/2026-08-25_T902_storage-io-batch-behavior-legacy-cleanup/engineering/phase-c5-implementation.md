# T902 Phase C5 implementation

## Result

Removed the now-unreachable legacy runtime helpers after C2 rescue and C4
admission established the artifact-only write boundary. Live candidate,
promotion, recovery, and legacy read/rescue paths remain intact.

## Changes

- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt`
  - Deleted the obsolete stage-transaction APIs
    `beginStage`, `commitStagePayload`, `promoteCandidate`, and
    `markCandidateStageFailed`, their `PromotionBundle`, and resync-only
    publication helpers. Live candidate APIs and recovery/retention logic were
    retained.
- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
  - Deleted `tempFileNameFor`; artifact document naming is owned by the atomic
    document helper.
- Deleted `ChapterTranslationSummaryStore.kt` and its obsolete naming test.
  Summary files are ignored by status and only chapter-scoped cleanup can
  remove stale garbage.
- `LegacyArtifactMigration.kt` and related tests no longer contain the
  obsolete identity-resync entry point; C2 one-way rescue remains the only
  migration path. `legacyGlossaryMatches` and `resyncAndPublish` were removed.
- `ChapterArtifactStoreTest.kt` and `LegacyArtifactMigrationTest.kt` removed
  only dead-stage/resync-only coverage; live candidate, cancel, restart,
  recovery, collision, and migration fixtures remain.

## Evidence

- Repository production/test search finds no definitions or callers for the
  removed stage APIs, `tempFileNameFor`, summary runtime, or resync helpers;
  historical Plan artifacts are unchanged.
- Focused artifact/migration/manager tests: 149 PASS, 0 failures/errors.
- Translation slice: 962 tests, exactly one known independent
  `AotReportBubbleFillTest` pixel mismatch.
- `spotlessCheck` was run; the formatter identified only mechanical Kotlin
  layout/import changes, which were applied before the final check. `git diff
  --check` is run as the final gate below.

## Final validation

- `.\gradlew.bat spotlessCheck :app:testStandardDebugUnitTest
  :domain:testReleaseUnitTest --no-daemon --console=plain` — Spotless PASS;
  domain release unit tests PASS (68 tests, 0 failures/errors); app
  standard-debug tests completed 1022 with exactly one known independent
  `eu.kanade.translation.inpainting.AotReportBubbleFillTest` pixel mismatch.
- Translation slice `:app:testStandardDebugUnitTest --tests
  'eu.kanade.translation.*'` — 962 tests, exactly the same one known AOT
  mismatch and no translation-module failures.
- Cumulative artifact/migration/manager focused suite — 149 tests PASS,
  0 failures/errors.
- `spotlessCheck` — PASS. `git diff --check` — clean. No commit was created.

## Boundaries and risks

No reader/scheduler/provider/prompt pipeline was deleted or retuned. The
source-compatible lazy `fileCreator` symbol remains as an ignored test seam;
removing it is deferred to avoid unrelated source/API churn. Legacy flat JSON,
glossary, and `.corrupt` files remain available to C2 rescue/C3 fail-closed
cleanup where their manifest still requires them.

## Final translation-module audit fix

The final audit's F-B1 delete/reset ownership gap is fixed in the cumulative
delta. `TranslationManager.deleteTranslation` now captures a validated
artifact deletion plan before the existing cancel/join/defunct/stream-clear
teardown, then removes the manifest and its `.tmp`, `.bak`, and `.corrupt`
siblings before removing the chapter artifact tree. Chapter-wide
`resetChapterOcrData` continues to use this path; per-page `resetOcrData` and
`deletePageTranslation` remain page-scoped store mutations.

The plan only admits legacy names recorded by supported migration metadata:
the canonical source/glossary name while preservation is still `INTENT`, or
the exact resolved name once it is `PRESERVED`. Each candidate is re-read
immediately before deletion and is removed only when SHA-256 and byte length
match the recorded identity; mtime is not part of ownership. Missing,
mismatched, malformed, unsupported, or externally inserted files remain.
If any manifest authority sibling cannot be removed, the artifact tree and
legacy recovery data are retained for retry. Companion images are retired only
after the manifest authority is gone and the existing stream-registry safety
barrier completes; a failed authority deletion therefore cannot leave a
manifest pointing at deleted images. Durable status cache is invalidated after
the teardown.

The F-M1 optimization is also applied: an already `VERIFIED` manifest with no
source/glossary preservation in `INTENT` or `PRESERVED` skips repeated health
verification and manifest rewrites; pending preservation states still retry
their cleanup/verification path.

Focused storage/deletion suite: 64 tests passed (0 failures/errors), including
raw and URI-style deletion failure/identity fixtures. Deterministic translation
slice: 964 tests, exactly one known independent
`AotReportBubbleFillTest` pixel mismatch. Final gate
`spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest
--no-daemon --console=plain`: Spotless passed; domain release tests passed (68);
app standard-debug completed 1,024 tests with exactly the same known AOT
mismatch. `git diff --check` is clean. No commit was created.

Remaining risk is intentionally fail-closed: an unsupported SAF operation or
delete failure retains recovery data and reports an error for a later retry;
it never guesses ownership or deletes an external/mismatched legacy file.
