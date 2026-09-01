# T912 excessive-shift / mask-ceiling repair — implementation report

Branch `codex/text-layout-renderer`, base HEAD `b994bc6` (no rebase, no push),
worktree `t912-investigation-wt`.

## Result

Masked collision resolution can no longer exile a translation across a bubble
or accept geometry that crosses the real segmentation contour. Post-anchor
directional moves and free-strip refits are capped from the resolver-entry
layout, every span-mode candidate is revalidated with a stroke/AA-inclusive
paint envelope against the actual component rows, and a rejected masked
candidate-8 refit can no longer leak through the never-drop tail. The production
overlay now applies `component Path -> cellRect -> clipRect` to positioned,
horizontal legacy, and vertical legacy layouts.

Unmasked planning decisions are intentionally unchanged, including the existing
53.68 px and 378.08 px collision-resolution fixtures. Failed masked resolution
still produces `Draw`: it keeps the resolver-entry geometry with an own-ink
containment clip, while the component/cell painter clips provide pixel safety.

## Implementation

### 1. Resolver-entry masked relocation cap

`TextLayoutTuning` owns two isolated constants:

- `MAX_MASK_SHIFT_REGION_FRACTION = 0.25f`
- `MAX_MASK_SHIFT_PAGE_FRACTION = 0.04f`

The cap is
`min(0.25 * min(block.width, block.height), 0.04 * pageShortSide)`.
This follows the Technical Lead's initial tuning envelope without adjustment:
the focused fixtures show it rejects both known excessive moves while retaining
room for small local nudges. A larger nearby value had no supporting corpus
evidence and would weaken the Director's complaint; a smaller value would remove
useful collision recovery without evidence.

Candidates 4–7 skip a direction when the true required displacement exceeds the
cap or the available component/cell/page room; they do not evaluate a partial
move already known not to clear the obstacle. Candidate 8 compares its final
anchor to the resolver-entry anchor by Euclidean distance. The rules are gated
on `segmentationMask != null`; the unmasked code path preserves its previous
clamping and fallback byte decisions.

### 2. Stroke-aware component-row acceptance

For span-mode cells, resolver validation streams the existing row-major cell
spans. Each legacy extent, or each positioned line box, is inflated by
`ceil(max(2, strokeWidth) / 2 + aaGuard)` to match the overlay's actual stroke
floor plus AA coverage. Every touched integer row must contain one continuous
span covering the full inflated horizontal interval. Missing rows, stepped or
sloped ceilings, and component holes reject the candidate.

The scan allocates no dense mask, bitmap, row map, or candidate collection. It
reuses the already bounded cell spans and the existing maximum-eight candidate
ladder. Bounds-only cells retain rectangular containment because exact component
spans do not exist for them.

### 3. Candidate-8 tail safety

The former tail returned `clipCandidate` even after `tryCandidate` rejected it.
That compatibility behavior remains only for unmasked layouts. A masked rejected
refit now falls back to `layout.copy(clipRect = inkRectOf(layout))`, so the text
still draws at the resolver-entry geometry and the subsequently wired
component/cell metadata contains its painted pixels.

### 4. Production overlay hard-clip parity

`TranslationOverlayView.bind` prepares component `Path`s through the existing
`ComponentClipCache`, keyed by `(planGeometryId, componentId)` and capped at 64
components / 100,000 spans. Invalid, incomplete, mismatched, or over-budget path
metadata degrades to the available cell/legacy clips and still draws, preserving
never-drop behavior.

All three overlay bodies now sit under one common saved-canvas envelope:
component path, then float `cellRect`, then float `clipRect`. The old per-frame
`RectF` construction was removed. Paths and prepared-layout wrappers are created
only at bind time; draw does not build paths, rectangles, maps, or layout lists.

## Focused evidence

New JVM suite `TextLayoutPlannerShiftCeilingRepairTest` (4 tests):

1. A deeply colliding masked layout with a distant free-strip candidate remains
   at its exact resolver-entry origin, carries a containment clip, stays `Draw`,
   preserves text/cardinality, remains within the computed cap, and replays
   deterministically with bounded attempts.
2. The cap selects the tighter region/page limit and rejects the pre-existing
   53.68 px and 378.08 px resolver displacements at a 25 px budget while
   accepting a 24 px local move.
3. A stroke envelope crossing one row of a stepped/sloped upper ceiling is
   rejected; the just-inside counterpart is accepted, both at the primitive
   row-span seam and through finalized legacy-layout envelope calculation.
4. A positioned line spanning a component hole is rejected through the
   finalized-layout envelope path.

New Android suite `TranslationOverlayViewInstrumentedTest` (3 tests) renders
thick-stroked text across a stepped ceiling, cell edge, and collision clip for
positioned, horizontal legacy, and vertical legacy layouts. Each test asserts
zero alpha outside `component path ∩ cellRect ∩ clipRect` and nonzero alpha
inside. The suite compiles; device execution was blocked before any test ran
(details below).

`TextLayoutPlannerTest` remains byte-for-byte unedited (31/31 green). Existing
unmasked exact-shift expectations remain green, which is the explicit ordinary
legacy compatibility evidence. `MissingTextReproTest` remains 11/11 green.

## Verification

Environment: Windows PowerShell, Android Studio JBR at
`C:\Program Files\Android\Android Studio\jbr`.

1. Focused rendering/segmentation plus AndroidTest compile:
   `./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*" :app:compileDevDebugAndroidTestKotlin`
   -> BUILD SUCCESSFUL in 48s. JVM: suites=24, tests=211, failures=0,
   errors=0, skipped=0. Protected counts include `TextLayoutPlannerTest` 31/31,
   `MissingTextReproTest` 11/11, `TextLayoutPlannerFinalSafetyTest` 7/7,
   `TextLayoutPlannerQualityRepairTest` 4/4, and new shift/ceiling tests 4/4.
   AndroidTest Kotlin compilation succeeded.
2. Full practical module gate:
   `./gradlew.bat :app:testDevDebugUnitTest`
   -> BUILD SUCCESSFUL in 1m05s. suites=173, tests=1321, failures=0,
   errors=0, skipped=0 (HEAD's 1317 + 4 new JVM tests).
3. Connected-device attempt:
   `./gradlew.bat :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewInstrumentedTest`
   built both APKs, then UTP failed before launch with
   `DeviceProviderProfileManager ... FileNotFoundException: Invalid file path`;
   Gradle reported `Finished 0 tests on PKG110 - 16`.
4. Direct ADB fallback was attempted only to bypass that host runner defect.
   Device `192.168.100.223:44873`, ABI `arm64-v8a`: target app APK
   `app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk`
   (`app.kanade.tachiyomi.at.debug`, 452,133,417 bytes) installed successfully.
   Test APK
   `app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk`
   (`app.kanade.tachiyomi.at.debug.test`, 276,907 bytes) failed installation
   with `Failure [-99]`. Instrumentation could not find the runner; zero device
   tests executed. Per Director instruction, no further installs or device
   settings changes were attempted.

## Deviations and remaining risk

- No tuning deviation: the Technical Lead's 0.25 region / 0.04 page values were
  adopted as isolated constants. Device/corpus observation may tune them later
  without changing the cap contract.
- The Android overlay pixel suite is compile-verified but not device-executed
  because of the two independent host/device installation failures above. This
  is the only open verification gap.
- The pre-existing tall/narrow vertical-partition span filter defect remains
  out of scope and unchanged. It can reduce available spans before fitting, but
  this repair still preserves `Draw` fallback and painter clipping.
- Exact glyph overhang is ultimately enforced by Canvas clipping; the JVM row
  predicate intentionally uses conservative layout envelopes rather than
  claiming font-engine pixel exactness.
