# T912 slice 1 — baseline and contract characterization

## Result

Repaired the existing renderer instrumentation fixture against the current
production APIs and added a characterization test for three distinct blocks
that share one continuous segmentation mask. The fixture now uses
`RenderColorEstimator.recomputeFor` and `TranslationBlock.textColor`; it no
longer references the reverted `resolveLayoutColors`, a planned-layout
footprint argument, or a `BlockLayout.textColor` field.

The planner's ordinary Latin line wrapper no longer invents hyphens when a word
is wider than the available line. Source hyphens remain breakpoints. The JVM
test now pins both behaviors (`HANAZUMI` stays atomic; `NEE-CHAN` may break as
`NEE-`/`CHAN`).

The shared-mask characterization uses block IDs and asserts one output per
nonblank input plus exact per-block text. It intentionally tests the current
`List<BlockLayout>` API; the explicit `LayoutResult`/non-draw outcome model is
reserved for the later planner slices and was not invented here.

## Verification

- `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; ./gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.rendering.TextLayoutPlannerTest`
  — passed; 31 tests.
- `$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
  — passed; Android test Kotlin compilation is now clean.
- `git diff --check` — passed.

No device/emulator was available, so instrumentation execution and pixel
assertions remain for the later Android verification gate.

## Scope and risks

This slice does not add adaptive mask geometry, component partitioning, clips,
positioned lines, free-text widening, or final collision retries. The existing
planner still has those pre-T912 behaviors; subsequent slices must replace
them behind the reviewed contracts.

