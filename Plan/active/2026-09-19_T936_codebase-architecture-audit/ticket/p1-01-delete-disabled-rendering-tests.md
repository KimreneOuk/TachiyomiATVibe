# Ticket P1-01: Delete 8 permanently disabled rendering test suites

**Phase:** 1 — Zero-Risk Purge | **Risk:** Zero (files never execute) | **Type:** Deletion only

## Evidence (verified in main worktree @ `7262bf4`, 2026-09-19)

All 8 files carry the class-level annotation
`@Disabled("Superseded by Desktop 1:1 text layout engine port")` — they compile on every
build and run zero assertions.

| File (under `app/src/test/java/eu/kanade/translation/rendering/`) | Lines |
|---|---|
| `MissingTextReproTest.kt` | 543 |
| `TextLayoutPlannerMaskMetadataTest.kt` | 424 |
| `TextLayoutPlannerFreeTextTest.kt` | 347 |
| `TextLayoutPlannerFinalSafetyTest.kt` | 360 |
| `TextLayoutPlannerSlice5Test.kt` | 253 |
| `TextLayoutPlannerQualityRepairTest.kt` | 245 |
| `TextLayoutPlannerContainedRescueTest.kt` | 224 |
| `TextLayoutPlannerShiftCeilingRepairTest.kt` | 190 |
| **Total** | **2,586 non-blank (~2,900 raw lines)** |

(Audit report quoted 2,902 raw lines; both counts refer to the same files — counting method differs.)

These suites cover the OLD pre-port layout engine slices (repair/shift-ceiling/contained-rescue
heuristics). The replacement engine is covered by the enabled suites in the same package.
They represent 38% of rendering test code volume.

## Changes

1. `git rm` all 8 files listed above. Nothing else.

## What this ticket must NOT do

- Do not touch any enabled test in `rendering/` or any production file.
- Do not touch `TextLayoutPlanner.kt` itself.

## Verification

1. `git grep -l "Desktop 1:1 text layout engine port"` returns no hits under `app/src/test/`.
2. `./gradlew :app:compileDebugUnitTestKotlin` — green.
3. `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.rendering.*"` — green,
   test-class count drops by exactly 8.

## Commit

`test(translation): delete 8 rendering suites disabled by desktop layout engine port`
