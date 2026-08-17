# Handoff

**Status:** Focused implementation complete; targeted unit validation passed.

## Changed

- `ReaderPageImageView` now tracks whether the currently selected image is the translated image and whether that image has finished decoding.
- Overlay blocks remain cached but are hidden while an image replacement is in flight.
- Blocks bind only after the selected translated image reaches `onImageLoaded()` and an SSIV is available.
- Switching to the original image, image-load failure, and holder recycle clear stale overlay state.
- Pager and webtoon holders announce the selected image before starting decode.
- Added a page-state regression test verifying cleaned-image readiness is distinct from translated-overlay readiness.

## Deliberately not changed

- FAST/QUALITY inpainting routing.
- Segmentation-mask composition and blank-OCR preservation.
- Existing ONNX/provider/detector edits already present in the worktree.
- `inpaintingModeUsed` schema/propagation beyond the existing committed behavior.
- Persisted-mask planner behavior and its existing tests.

## Validation

Passed:

```text
./gradlew.bat :app:testStandardDebugUnitTest \
  --tests "eu.kanade.translation.inpainting.PageInpaintingPlannerTest" \
  --tests "eu.kanade.translation.model.PageTranslationStateTest" \
  --tests "eu.kanade.translation.batch.BatchResumeGateDeciderTest" \
  --tests "eu.kanade.translation.scheduling.TranslationLifecyclePolicyTest" \
  --no-daemon
```

Result: `BUILD SUCCESSFUL in 1m 13s`; all selected tests passed. The build emitted only existing deprecation/delicate-API warnings.

Also passed: `git diff --check`.

Not performed: emulator/manual visual validation, instrumentation tests, and the full unit suite.

## Root-cause status

The source proves inpainting runs in both modes. QUALITY can use a logged, memory-aware push-pull route when neural candidates are unavailable or exhausted. The strongest concrete overlay defect was the image replacement race; a current broad incomplete-inpainting defect remains unproven without fresh device logs and original/cleaned artifact comparison.
