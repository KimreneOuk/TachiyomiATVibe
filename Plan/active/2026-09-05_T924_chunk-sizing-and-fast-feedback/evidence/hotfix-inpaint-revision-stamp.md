# T924 hotfix evidence: inpaint-revision stamp in checkpointOcr

Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base HEAD `439226e`.
Changes left uncommitted in the working tree (3 files, 97 insertions, 0 deletions).

## Production bug

A fresh OCR result carries `PageTranslation.inpaintRevision = 0` (the type's
default; `mergeOcr` copies `result.inpaintRevision` at
`ChapterTranslationStore.kt:946` and the OCR preflight never inpaints).
`checkpointOcr` built the `PageOcrCheckpoint` with `inpaintMaskRevision = 0`,
which `PageOcrCheckpoint.validationError()` rejects
(`stale inpaintMaskRevision: 0`, gate at PageOcrCheckpoint.kt:76 vs
`CURRENT_INPAINT_REVISION = 10`). `checkpointOcrOnce` surfaced that as
`Rejected("checkpoint invalid: ...")`, and the coordinator's ST-06 rule made
every never-translated chapter's batch run phase-terminal.

## What changed

1. **Fix** — `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`,
   inside `checkpointOcr`, immediately after `val live = current ?: ...`
   (line 1074), before `val snapshotFingerprint = StageFingerprints.pageSnapshot(live)`
   (now line 1096); the stamp `if` sits at line 1082:

   ```kotlin
   if (live.inpaintRevision < PageTranslation.CURRENT_INPAINT_REVISION) {
       live.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
   }
   ```

   Placement is load-bearing: it precedes the snapshot fingerprint (so
   `ocrPageSnapshotPointer.contentFingerprint` matches
   `StageFingerprints.pageSnapshot(ocrSnapshot)` re-checked inside
   `ChapterArtifactStore.checkpointOcrOnce`), the `pageOcrContentFingerprint`
   computation (which reads `page.inpaintRevision`), and the DTO build
   (`inpaintMaskRevision = live.inpaintRevision`), so the published snapshot
   sidecar, the checkpoint sidecar, and the TX-03.1 committed-side comparison
   canonicalize on one value. No other stamp site added;
   `PageOcrCheckpoint.validationError()` and `CURRENT_INPAINT_REVISION` untouched.

2. **Production-shape regression test** —
   `app/src/test/java/eu/kanade/translation/OcrCheckpointRestartReuseTest.kt:134`
   `fresh ocr snapshot with default inpaintRevision commits at current revision`:
   merges an OCR result that leaves `inpaintRevision` at its default (fixture
   asserts the live page still reads 0 before checkpointing), runs the
   `ChapterTranslationStore.checkpointOcr` facade end to end, asserts
   `Committed`, then reads back the persisted checkpoint sidecar
   (`inpaintMaskRevision == CURRENT_INPAINT_REVISION`, i.e. reads back `Usable`)
   and the persisted OCR snapshot sidecar
   (`inpaintRevision == CURRENT_INPAINT_REVISION`).

3. **Read-side rejection test** (was missing; verified via grep —
   `LegacyArtifactMigrationTest.kt:416` covers the legacy *migration* path with
   CURRENT-1, not the checkpoint DTO gate) —
   `app/src/test/java/eu/kanade/translation/artifact/CheckpointOcrTransactionTest.kt:600`
   `stale inpaintMaskRevision checkpoint fails validation and reads back as absent`:
   a well-formed DTO with `inpaintMaskRevision = CURRENT_INPAINT_REVISION - 1`
   fails `validationError()` with `stale inpaintMaskRevision`, and a persisted
   copy reads back from `ChapterArtifactStore.readOcrCheckpoint` as `Absent`
   (quarantined), never `Usable`.

## Verification (red/green)

- Red: with the stamp temporarily removed,
  `fresh ocr snapshot with default inpaintRevision commits at current revision()`
  FAILED (only that test; the other fixtures set CURRENT explicitly, which is
  why the bug escaped to the field). Stamp then restored.
- Green final run:

```
cd C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-t924-impl
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew :app:testStandardDebugUnitTest \
  --tests "eu.kanade.translation.OcrCheckpointRestartReuseTest" \
  --tests "eu.kanade.translation.artifact.CheckpointOcrTransactionTest" \
  --tests "eu.kanade.translation.artifact.*" \
  --tests "eu.kanade.translation.pipeline.batch.OcrPreflightCoordinatorTest" \
  --tests "eu.kanade.translation.pipeline.batch.OcrPreflightRejectedMidRunDurabilityTest" \
  --tests "eu.kanade.translation.pipeline.batch.OcrPreflightQueueRestoreTest"
```

Result: BUILD SUCCESSFUL — 182 tests across 19 classes, 0 failures, 0 errors
(per-class counts verified in `app/build/test-results/testStandardDebugUnitTest/`):
OcrCheckpointRestartReuseTest 4 (incl. new test),
CheckpointOcrTransactionTest 14 (incl. new test), ChapterArtifactStoreTest 45,
LegacyArtifactMigrationTest 26, SemanticFingerprintTest 21,
AtomicChapterDocumentsTest 12, ChapterArtifactLayoutTest 11,
SidecarCrashPublicationTest 8, ChapterRunRecordSchemaTest 7,
ModelIdentityCacheTest 7, StageFingerprintsTest 6,
ProfileContentFingerprintGoldenTest 5, OcrPreflightCoordinatorTest 4,
ChapterArtifactStoreRetireActiveRunTest 3, ChapterArtifactStoreStaleManifestRetryTest 3,
OcrPreflightQueueRestoreTest 2, OcrPreflightRejectedMidRunDurabilityTest 2,
ChapterArtifactDeletionTest 2, plus the earlier identical green run of the
first three filters.

## Deviations from spec

- Test filter adjusted to real packages: `CheckpointOcrTransactionTest` lives in
  `eu.kanade.translation.artifact`, not `eu.kanade.translation` (spec's example
  filter would have matched nothing).
- The production-shape test additionally asserts the live page's revision is 0
  after the OCR merge (one line) to prove the fixture reproduces the production
  shape rather than passing vacuously.
- Comment text matches the spec verbatim except wrapping "canonicalize on one
  value (CleanedPublication stamps ...)" onto its own line to stay within the
  file's line width.
- Preflight coordinator tests (`OcrPreflight*`, 8 tests) included in the run
  since they reference checkpointOcr behavior; all pass unchanged.
- Not committed; full test sweep not run (orchestrator-owned).
