# PageTextRenderer removal — commit 1 test migration implementation

## Scope

This commit adds coverage to the actual production renderer,
`TranslationOverlayView`, and moves the Bitmap-only color-estimator
characterization to `RenderColorEstimatorInstrumentedTest`. It intentionally
does not change `PageTextRenderer`, `ComponentClipCache`, planner logic, or
overlay production code.

## Added coverage

- Component clipping: concave holes with thick outlines, component/cell
  intersection, disconnected assigned components, and two positioned layouts
  sharing a component with a hard alpha gap.
- Production fallback policy: dimension-mismatched component metadata leaves
  text visible while the available cell and legacy clips remain hard bounds.
- Production painting: legacy and positioned fractional canvas transforms,
  complex positioned glyph containment, left-origin positioned lines,
  horizontal/vertical software-canvas smoke coverage, and vertical CJK
  punctuation output.
- Mixed CJK/Latin vertical characterization: the existing overlay painter is
  pinned as unrotated for Latin and constrained by the component clip. This is
  deliberately not the removed renderer's rotated-Latin behavior.
- Lifecycle: rebind and public `clear()` cannot leave stale prepared layouts.
- Color ownership: `recomputeFor` samples the current block OCR rectangle in
  a test owned by `RenderColorEstimator`.

## Intentionally not imported

- PageTextRenderer's fail-closed invalid-dimension omission. The live overlay
  deliberately uses visibility-preserving fallback; this commit tests that
  actual policy instead.
- PageTextRenderer's rotated-Latin vertical behavior and StaticLayout-only
  one-line shaping assertions. Neither is live overlay behavior.

## Verification

- `git diff --check`: passed.
- Attempted Android test-source compilation using the bundled Android Studio
  JBR directly:

  ```powershell
  & 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' `
    -classpath gradle\wrapper\gradle-wrapper.jar `
    org.gradle.wrapper.GradleWrapperMain :app:compileDevDebugAndroidTestKotlin `
    --console=plain --offline --no-daemon
  ```

  The command reached Gradle configuration but the execution bridge returned
  before a task result or exit code. A bounded follow-up found the wrapper and
  Gradle daemon JVMs still running and no compile-output evidence. This is an
  **unverified gate**, not a pass; no test result is claimed.

## Changed files

- `app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewInstrumentedTest.kt`
- `app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewRenderingInstrumentedTest.kt`
- `app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewLifecycleInstrumentedTest.kt`
- `app/src/androidTest/java/eu/kanade/translation/rendering/RenderColorEstimatorInstrumentedTest.kt`
