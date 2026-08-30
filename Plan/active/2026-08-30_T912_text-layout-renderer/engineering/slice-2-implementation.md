# T912 slice 2 — bounded ordered RLE conversion and intersecting clip wiring

## Result

Implemented slice 2 of architecture revision 2 on `codex/text-layout-renderer`.

- `MaskGeometry.kt`: `Component` is now a plain class with a LAZY `stableKey`
  (and the geometry-level `stableKey` is lazy too), so the T912 path never
  materializes coordinate strings; `fromSpans` behavior (including its
  key-ordered component sort) is unchanged for its callers. Added
  `MaskConversionBudgets` (page-scoped, constructor-tunable defaults
  100,000 derived spans / 200,000 RLE ints / 64 components / 1,000,000 sweep
  comparisons per page, with internal counters), public
  `OrderedMaskFallbackReason` + sealed `OrderedMaskResult`
  (Success/Fallback), and `fromOrderedRle` delegating to the internal
  primitive core `fromOrderedRleCore(width, height, bounds, runs, budgets)`.
  The core is a two-pass ordered conversion: Pass A splits runs arithmetically
  at row boundaries in Long math (merging adjacent same-row pieces),
  aborting BEFORE any span cap would be exceeded; Pass B fills exactly
  `3 × acceptedSpanCount` int storage plus a counted row-offset array (no
  sorting), runs the existing two-pointer union-find sweep against the page
  work budget, and counts union roots BEFORE any `Component`, span list,
  string key, or `Path` can be allocated. Component ids are first-appearance
  (row-major) 0..n-1, string-free, intentionally not cross-path comparable
  with `fromSpans` ids.
- `TextLayoutPlanner.kt`: `BlockLayout.maskKey` replaced with
  `planGeometryId: Int?` and `cellRect: FloatRect?` added. `buildMaskRegions`
  now walks blocks in input order through an internal `SharedMaskSession`:
  reference-identity cache first (no rescan for repeated instances),
  FNV-1a-style 64-bit streaming fingerprint bucketing with full
  geometry-defining equality verification (score deliberately excluded — no
  geometric effect), caps 128 references / 32 unique masks per page with
  beyond-cap masks GROUPLESS (negative ids, still partitioned by reference
  identity), and fingerprint + conversion scans sharing the page RLE-int
  budget. Partition math (midpoint cuts, parent boxes, single-block = bounds
  rect) is byte-identical to before. After `placeBlock`, metadata
  (maskGeometry, planGeometryId, maskComponentId, cellRect = the placement
  regionOverride) is wired only when the group converted, geometry dimensions
  equal `roundToInt` page dims, and the block's OCR rectangle resolves to
  exactly one component (ties get NO metadata); a 64-assignment page cap on
  distinct (groupId, componentId) pairs applies. Placement/font/text fields
  are untouched — `equalizeSharedMaskFonts` remains (slice 3) and its `copy`
  preserves the new fields.
- `PageTextRenderer.kt`: new top-level JVM-pure generic
  `ComponentClipCache<P>` (no android.graphics in the class) keyed by the
  packed Long `(planGeometryId, componentId)`, requiring stored-geometry
  instance identity on hit, cap-checking BEFORE create. `bind()` fails closed
  on incomplete metadata (maskGeometry set but planGeometryId/componentId
  missing or out of range) and keeps the dims-vs-page and page-dims checks.
  `draw()` composes all three clips in order: component path → cellRect →
  legacy clipRect; unmasked layouts have null path/cellRect so their legacy
  behavior is byte-identical. `PreparedLayout` carries the cellRect; all
  construction stays in `bind()` (draw() allocation-free).
- `BubbleMaskRle.kt` needed no changes (its constructor already guarantees
  ordered, in-mask, positive-length runs).
- Tests: new JVM `MaskGeometryOrderedRleTest` (8 tests), new JVM
  `ComponentClipCacheTest` (6), new JVM `TextLayoutPlannerMaskMetadataTest`
  (8), and the instrumented `layout(...)` helper now passes `planGeometryId`
  (derived from geometry) plus an optional `cellRect`, with the new
  `componentClipIntersectsCellRect` pixel test (compile-gated).

## Verification

- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
  — BUILD SUCCESSFUL in 38s (wall ≈ 40s). 104 tests completed, 0 failures,
  0 errors, 0 skipped across 13 suites, including:
  - MaskGeometryOrderedRleTest: 8/8
  - ComponentClipCacheTest: 6/6
  - TextLayoutPlannerMaskMetadataTest: 8/8
  - Existing, unchanged: MaskGeometryTest 7, MaskGeometryStressTest 2,
    BubbleSegmentationDecoderTest 8, TextLayoutPlannerTest 31,
    PageTextRendererDirectionTest 8, TextLayoutPlannerStrokeTest 4,
    RenderColorEstimator suites 22 (7+5+7+3).
- Allocation-evidence probe confirmed live on this host: reflection probe of
  `com.sun.management.ThreadMXBean` reports `supported=true` and positive
  deltas, so the 100,000-islands test's `< 16 MB` allocation assertion and
  `< 5s` wall-time assertion actually executed (the stress case aborts at the
  component cap with `componentsBuilt == 0`).
- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
  — BUILD SUCCESSFUL in 26s. No device/emulator was available, so the new
  instrumented pixel test remains compile-gated only (same as slice 1).
- `git diff --check` — clean.

## Scope and risks

- Touched exactly the allowed files: `MaskGeometry.kt`, `TextLayoutPlanner.kt`,
  `PageTextRenderer.kt`, the instrumented test, three new JVM test files, and
  this report. `BubbleMaskRle.kt` unchanged (not needed). No
  TranslationOverlayView/batch/download/drawer/progress/pipeline files touched.
- Ordinary unmasked manga keeps byte-identical planner decisions and renderer
  behavior: unmasked blocks never enter the mask session, receive no metadata,
  and their draw path composes only the legacy clipRect as before. The
  existing 31 TextLayoutPlannerTest cases and all segmentation suites pass
  unchanged.
- Intentional deviations / local decisions (all recorded here):
  1. `fromOrderedRle` is `internal`, not public: Kotlin forbids a public
     function exposing the internal `MaskConversionBudgets` parameter type.
     The enum and sealed result are public as specified; planner and tests are
     in-module, so nothing else was needed.
  2. Geometry-level `MaskGeometry.stableKey` was made lazy in addition to
     `Component.stableKey` (value-transparent) so no code path in the T912
     pipeline materializes a geometry-wide coordinate string.
  3. Internal-core contract violations that none of the four fallback reasons
     covers (zero-length runs, out-of-order/overlapping runs, `bounds.size != 4`)
     throw `IllegalArgumentException`; production inputs are pre-validated by
     `BubbleMaskRle`'s `init`, so the planner path can never hit them.
  4. Budget bookkeeping: failed conversions consume `rleIntsScanned`,
     `derivedSpans`, and `sweepComparisons` (work actually performed) but never
     `componentsBuilt`; a pre-scan budget abort counts nothing. A mask that
     turns out beyond the 32-unique cap still pays its fingerprint scan.
  5. The test's thread-allocation probe goes through reflection because
     `java.lang.management`/`com.sun.management` are absent from the android.jar
     unit-test compile classpath; graceful skip is preserved as required.
  6. `draw()` now applies the legacy clipRect whenever present (composing clips)
     instead of only when no component path exists. In production the planner
     never sets clipRect on mask-region blocks, and all existing instrumented
     pixel tests are unaffected; this is the slice's intended intersection
     semantics.
- Remaining risks for later slices: GROUPLESS masks (beyond-cap cases) never
  receive clips, matching the fallback matrix (legacy rectangle only); the
  component-assignment budget (64 distinct pairs/page) is implemented but only
  exercised indirectly by the cap tests; instrumented pixel verification of
  `componentClipIntersectsCellRect` awaits the slice-8 device gate.
