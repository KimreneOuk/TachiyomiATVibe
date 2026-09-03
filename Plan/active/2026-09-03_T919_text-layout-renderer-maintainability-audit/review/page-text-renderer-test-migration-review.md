# Review — `f4d8a0b` PageTextRenderer test migration

## Verdict

**Conditionally acceptable as the planned test-only migration commit.** The
added renderer tests instantiate and draw the live `TranslationOverlayView`;
they do not import `PageTextRenderer`, `StaticLayout`, or its rotated-vertical
helpers. The commit correctly preserves the production overlay's fallback
policy rather than copying the unused renderer's fail-closed behavior.

Do not use this commit as proof that the migration is green yet: Android test
source compilation was not verified, and the agreed repeated-draw allocation
gate is still absent. One safety test also permits a silent no-ink outcome.

## Verified migration coverage

| Safeguard / retired behavior | Result in `f4d8a0b` | Evidence |
| --- | --- | --- |
| Tests execute the live painter | Verified | Every rendering helper creates `TranslationOverlayView`, binds layouts through its test seam, and calls its drawing seam (`TranslationOverlayViewInstrumentedTest.kt:263-267`; `TranslationOverlayViewRenderingInstrumentedTest.kt:110-125`). Those seams delegate to production preparation/drawing (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt:247-257,175-245`). |
| Concave, component/cell, disconnected, shared-cell-gap clips | Migrated | `TranslationOverlayViewInstrumentedTest.kt:55-142` covers the agreed cases. |
| Overlay fallback, rather than PageTextRenderer fail-closed dimensions | Migrated correctly | The test uses mismatched geometry dimensions and asserts remaining cell/legacy clipping plus visible ink (`TranslationOverlayViewInstrumentedTest.kt:144-165`), matching production's null-component-path fallback (`TranslationOverlayView.kt:97-110,185-191`). |
| Fractional legacy/positioned clipping, complex glyph containment, left origin, software canvas, vertical CJK and mixed Latin | Migrated | `TranslationOverlayViewRenderingInstrumentedTest.kt:27-108` draws through the overlay. The mixed-Latin test characterizes unrotated overlay output rather than importing PageTextRenderer rotation (`:50-67`). |
| Lifecycle/rebind and color-estimator ownership | Migrated | `TranslationOverlayViewLifecycleInstrumentedTest.kt:18-38` uses the overlay and public `clear()`; `RenderColorEstimatorInstrumentedTest.kt:16-42` calls only the estimator. |
| PageTextRenderer-only `StaticLayout` and rotated-Latin assumptions | Not imported, as intended | No hit for `PageTextRenderer`, `StaticLayout`, `verticalOrientation`, or `TextLayoutPlanner.vertical*` in the four added/changed test files (review search); direct overlay drawing is `Canvas.drawText` (`TranslationOverlayView.kt:228-244`). |

## Findings

### M1 — Android test-source compilation remains unverified

- **Severity:** HIGH
- **Likelihood:** medium
- **Classification:** verification gap
- **Evidence status:** VERIFIED
- **Primary evidence:** the implementation report explicitly records no
  compile task result (`Plan/active/2026-09-03_T919_text-layout-renderer-maintainability-audit/engineering/page-text-renderer-test-migration-implementation.md:28-39`). Independent review could not run the required task because the environment has neither `JAVA_HOME` nor `java` configured; `./gradlew.bat :app:compileDevDebugAndroidTestKotlin --offline --no-daemon` stops before Gradle configuration with that error.
- **Impact:** source inspection found no definite Kotlin error, but this adds four
  Android test sources with resource, AndroidX, and internal-visibility usage;
  removal must not proceed on an uncompiled migration baseline.
- **Confirm/refute:** run `:app:compileDevDebugAndroidTestKotlin` with the
  project JDK, then the four focused instrumentation classes on API 26+ as
  specified in the removal plan.

### M2 — The disconnected-component regression test can pass when the overlay paints nothing

- **Severity:** MEDIUM
- **Likelihood:** medium
- **Classification:** test correctness / coverage gap
- **Evidence status:** VERIFIED
- **Primary evidence:** `disconnectedMaskClipsToAssignedComponentOnly()` asserts
  only zero alpha in the unassigned right component
  (`app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayViewInstrumentedTest.kt:99-115`). Unlike the concave case, it does not assert any ink in the assigned component; the reusable nonzero assertion exists at `:281-289` but is not called here.
- **Impact:** a future cache/path regression that drops the complete layout, or
  otherwise produces zero ink, satisfies this test. That weakens the stated
  live-painter proof of assigned-component rendering.
- **Confirm/refute:** require nonzero ink inside component 0 in this fixture,
  while retaining the existing zero-alpha assertion for component 1.

### M3 — The allocation-free-after-bind safeguard is still not covered by the production overlay suite

- **Severity:** MEDIUM
- **Likelihood:** medium
- **Classification:** coverage gap
- **Evidence status:** VERIFIED
- **Primary evidence:** the agreed removal review requires a repeated-draw
  allocation/lifecycle gate (`Plan/active/2026-09-03_T919_text-layout-renderer-maintainability-audit/review/page-text-renderer-removal-review.md:151-152`). The new lifecycle test checks only stale state after rebind/clear
  (`TranslationOverlayViewLifecycleInstrumentedTest.kt:18-38`), and the
  rendering tests draw each prepared view once (`TranslationOverlayViewRenderingInstrumentedTest.kt:110-125`). The overlay prepares component paths in bind but performs actual drawing in `onDraw`/`drawLayout` (`TranslationOverlayView.kt:82-111,130-145,175-245`).
- **Impact:** the removal would eliminate the only class whose contract comments
  explicitly describe bind-time shaping/allocation behavior, without a direct
  regression check for the retained render path. This is not a behavior failure
  in `f4d8a0b`, but it remains an acceptance blocker from the prior review.
- **Confirm/refute:** add the agreed repeated-frame/application-allocation gate
  or record an equivalent Android profiler/code-level proof before the deletion
  commit.

### M4 — Vertical exact-pixel characterizations are intentionally implementation-coupled but source-consistent

- **Severity:** LOW
- **Likelihood:** medium
- **Classification:** expected characterization-test tradeoff
- **Evidence status:** VERIFIED
- **Primary evidence:** expected vertical output duplicates the overlay’s local
  punctuation map and layout math in the test
  (`TranslationOverlayViewRenderingInstrumentedTest.kt:196-251`), then compares
  bitmaps exactly (`:37-67`). The live implementation maintains the same private
  logic (`TranslationOverlayView.kt:147-172,266-273`).
- **Impact:** this properly avoids importing PageTextRenderer behavior, but a
  future intentional vertical-layout edit must update both production and test
  expectation; it is a characterization, not an independent oracle.
- **Confirm/refute:** acceptable if the exact current overlay output is the
  chosen baseline. If cross-device font differences make it flaky, retain the
  component/bounds assertions and replace only the full-bitmap equality with a
  documented stable oracle; do not reintroduce PageTextRenderer’s glyph
  rotation.

## Scope / source-reference assessment

The commit is correctly limited to test migration plus its implementation
report: `git show --name-status f4d8a0b` lists no production source deletion or
renderer substitution. Therefore the pending `ComponentClipCache` move and
zero-live-reference audit are not defects in this commit; both remain mandatory
for the planned removal commit. The pure direction test rename is likewise
scheduled in that later commit by the removal plan, not omitted from this one.

`git diff --check f4d8a0b^ f4d8a0b` produced no whitespace errors. No
PageTextRenderer-only API import was found in the migrated tests. No production
code or tests were edited by this review.

## Acceptance before advancing to removal

1. Resolve M1 with a successful Android-test compile and focused device runs.
2. Resolve M2 so assigned-component visibility is asserted.
3. Resolve M3 with the previously required repeated-draw allocation evidence.
4. Preserve the commit boundary: do not change overlay drawing semantics merely
   to make a migrated test resemble PageTextRenderer.
