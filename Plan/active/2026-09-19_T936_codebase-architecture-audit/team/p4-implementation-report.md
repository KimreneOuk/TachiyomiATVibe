# T936 Phase 4 implementation report

Branch: `t936/phase4-monolith-decomposition`

Phase 4 was completed as behavior-preserving extraction work. Production logic was moved without changing test assertions or modifying test files. The final tree has zero files under `src/test` changed relative to `bc21d7c`.

## P4-01 — ChapterProfileBatchCoordinator

The coordinator was decomposed into focused phase workers in this order:

| Worker | Commit |
| --- | --- |
| `AnalysisWorker` | `7b0194c` |
| `ProfileReconciler` | `101e0d4` |
| `EnvelopeDispatcher` | `ce6e0a2` |
| `StandardLaneWorker` | `4a34a48` |
| `FinalizeWorker` | `acfd766` |
| `RecoveryWorker` | `0620a98` |
| `PreflightWorker` | `7baa1b7` |

The recovery/display-tail and disk-transaction work was extracted into `RecoveryWorker`. Preflight owns checkpoint/fingerprint reuse and the remaining coordinator bodies were left in place where they are shared context or routing rather than placing shared logic in a catch-all worker. `ChapterProfileBatchCoordinator` is 2,091 lines after extraction; this is above the ticket's heuristic target, but the remaining bodies are intentionally shared/router logic and no unsafe forced move was made.

Focused verification passed:

```text
./gradlew :app:compileDevDebugUnitTestKotlin --no-parallel --max-workers=1
./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.coexistence.ChapterProfileFreezeCoordinatorTest' --tests 'eu.kanade.translation.coexistence.BatchDispatchResumeWiringTest' --no-parallel --max-workers=1
```

No tests were changed.

## P4-02 — TextLayoutPlanner

Pure rendering-kernel moves were completed:

- `MaskGeometryClustering` and its deterministic mask-session helpers moved to `MaskGeometryClustering.kt` (`f48c280`).
- Font fitting, direction, wrapping, rectangle, glyph-orientation, and stroke kernels moved to `FontFittingAlgorithms.kt` (`b949493`).
- `TextLayoutPlanner` keeps compatibility wrappers and the existing public `VerticalOrientation` seam.

`TextLayoutPlanner` is 3,470 lines after the pure moves. Rendering tests have zero diff. Focused verification passed:

```text
./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.segmentation.MaskGeometryDeterministicAssignmentTest' --tests 'eu.kanade.translation.segmentation.MaskGeometryOrderedRleTest' --tests 'eu.kanade.translation.rendering.TextLayoutPlannerTest' --tests 'eu.kanade.translation.rendering.TextLayoutPlannerDirectionTest' --tests 'eu.kanade.translation.rendering.TextLayoutPlannerStrokeTest' --no-parallel --max-workers=1
```

The rendering-specific tests included in this focused run remained green; no test files were modified.

## P4-03 — ReaderTranslationController

`ReaderTranslationController` was extracted in `cc80183` (amended from the initial P4-03 commit). It owns the reader translation actions, auto-window state/observation, store bridges, retry/reset paths, manual page dispatch, and the P3 batch-to-reader admission handoff. `confirmBatchReaderSwitch` was explicitly moved into the controller during final seam review so the `switchReaderSession` admission call is not left in the ViewModel.

`ReaderViewModel` retains the existing public method names/types and state surfaces as thin delegates. The final sizes are:

- `ReaderViewModel.kt`: 1,849 lines
- `ReaderTranslationController.kt`: 1,618 lines

Verification passed after the final seam move:

```text
./gradlew :app:compileDevDebugKotlin --no-parallel --max-workers=1
./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.reader.*' --tests 'eu.kanade.tachiyomi.ui.reader.viewer.*' --tests 'eu.kanade.translation.ui.*' --no-parallel --max-workers=1
```

Both commands were rerun after the seam adaptation and passed. No tests were changed.

## Phase-end verification

The flavor-qualified tasks were used because this repository has `dev` and `standard` product flavors; generic `Debug` task names are not valid task entry points here.

Temporary Google Services setup: each Dev/Standard build copied `app/src/standard/google-services.json` to `app/google-services.json` in a `try/finally` block, and the root copy was removed after every invocation. `Test-Path app/google-services.json` was false after the final build.

Compile gate:

```text
./gradlew :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin --no-parallel --max-workers=1
```

Result: `BUILD SUCCESSFUL` (3m 8s).

Full-suite gate:

```text
./gradlew :app:testDevDebugUnitTest :app:testStandardDebugUnitTest --no-parallel --max-workers=1
```

The final combined invocation completed the Dev task and then encountered one known load-sensitive failure in Standard:

```text
eu.kanade.translation.coexistence.BatchDispatchResumeWiringTest
  re-dispatch after a reset demotes real work in the same lane while healthy pages stay retired
  IllegalStateException at BatchDispatchResumeWiringTest.kt:103
```

The source's timeout diagnostic identifies this as the run-1 `ChapterRunState.COMPLETE` wait under load. The required isolation audit was performed with the exact test in both flavors: Standard 3/3 and Dev 3/3 isolated retries passed (`CODES=0,0,0` in each flavor). The final full Standard retry passed:

```text
./gradlew :app:testStandardDebugUnitTest --no-parallel --max-workers=1
```

Result: `BUILD SUCCESSFUL` (3m 25s). The final full Dev retry also passed:

```text
./gradlew :app:testDevDebugUnitTest --no-parallel --max-workers=1
```

Result: `BUILD SUCCESSFUL` (3m 19s). The one combined-run failure and one standalone Dev attempt have the same pre-existing load/timing signature; no failure reproduced in isolation and no legacy/storage regression was indicated. No assertion was changed or weakened.

APK gate:

```text
./gradlew :app:assembleDevDebug --no-parallel --max-workers=1
```

Result: `BUILD SUCCESSFUL` (1m 33s). APK inspected: `app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk` (296,552,979 bytes).

APK contents:

| Check | Result |
| --- | --- |
| `best_int8.onnx` | absent |
| OCR `.md`, `.yml`, `.gitattributes` | 0 entries |
| `assets/models/segmentation/manga109_bubble_int8.onnx` | present |
| OCR `inference.onnx` entries | 2 present |

The assemble log also contains D8 Kotlin-metadata rewrite warnings, including for the extracted controller, but the task completed successfully. These are toolchain warnings, not test or packaging failures.

Repository checks:

```text
$changed = git diff --name-only bc21d7c..HEAD
@($changed | Where-Object { $_ -match 'src[\\/]test' }).Count
```

Result: 0 test files changed. The working tree is clean aside from the final report file pending its dedicated report commit.
