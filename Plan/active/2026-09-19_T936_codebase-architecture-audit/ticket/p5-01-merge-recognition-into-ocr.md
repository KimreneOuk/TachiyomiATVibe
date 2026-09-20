# Ticket P5-01: Merge `recognition` package into `ocr`

**Phase:** 5 | **Risk:** Low-Medium (import churn) | **Type:** Package move

## Current state

`eu.kanade.translation.recognition` (9 files): BoxGeometry, MlKitFullPageRecognitionEngine,
OcrBlockDeduplication, PaddlePageOcrCoordinator, PaddleVerticalRecognitionPlan, PageRecognitionEngine,
ReadingOrderSorter, RoiPageRecognitionEngine, VerticalLineOcr. Both packages implement text
detection/recognition. Target: one package `eu.kanade.translation.ocr`.

## Changes

1. Move all 9 files to `ocr/`, update package declarations + all imports across production and tests.
2. Name collisions: resolve by keeping the more specific name (e.g., if `ocr/BoxGeometry.kt` existed —
   it does not at time of writing; verify). Document any rename in the report.
3. Check `app/proguard-rules.pro` + gradle files for FQCN references to `translation.recognition` and
   update them (grep first; if none, state so).
4. Test files under `recognition/` in app/src/test move to the `ocr` test package; assertions untouched
   (only package/import lines may change).

## Verification

`git grep -l 'translation\.recognition' -- app/src` → 0. Full both-flavor suites green (assertion
diff zero — only package/import lines in tests). `assembleDevDebug` green.

## Commit

`refactor(translation): merge recognition package into ocr`
