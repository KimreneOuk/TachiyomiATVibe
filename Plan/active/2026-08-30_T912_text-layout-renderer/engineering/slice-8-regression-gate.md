# T912 Slice 8 — Regression gate record

- Date: 2026-08-31 (gate execution window 15:00:53–15:10:36 local, UTC+07:00)
- Branch: `codex/text-layout-renderer`
- HEAD: `be28e3599c62c6d9f1e8840bfc737c237e554438` ("docs(plan): record T912 slice 7 verification review — ACCEPT, no blocking findings")
- Working tree: clean at gate start; no production or test source modified by this slice
- Base for delta analysis: T911 final commit `ece0e72` (T911 module-wide baseline: 157 suites / 1182 tests / 0 failures / 0 errors / 0 skipped)
- Environment: Windows, Git Bash; every Gradle invocation used
  `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat ...`
- Counting method: aggregate of JUnit XML `testsuite` attributes under
  `app/build/test-results/testDevDebugUnitTest/*.xml` (per-suite `tests/failures/errors/skipped`).

## Verdict: GATE PASS

Every mandated step passed with zero failures, errors, or skipped tests, and the
module-wide delta over the T911 baseline is exactly the T912 additions.

---

## STEP 1 — T912 focused suites (segmentation + rendering)

Command (repo root):

```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest \
  --tests "eu.kanade.translation.segmentation.*" \
  --tests "eu.kanade.translation.rendering.*"
```

Result: `BUILD SUCCESSFUL in 32s` (wall time 15:00:53 → 15:01:26, 33 s including
JVM/daemon overhead). 20 XML suites.

Totals: **20 suites / 186 tests / 0 failures / 0 errors / 0 skipped.**

Per-suite counts (tests / failures / errors / skipped):

| Suite | Tests | F | E | S |
|---|---:|---:|---:|---:|
| eu.kanade.translation.rendering.AdaptiveBandPlannerTest | 9 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.ComponentClipCacheTest | 6 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.MaskTextRegionPlannerTest | 13 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.PageLayoutPlanContractTest | 6 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.PageTextRendererDirectionTest | 8 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.RenderColorEstimatorDedupTest | 7 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.RenderColorEstimatorLayoutSamplingTest | 3 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.RenderColorEstimatorSamplingTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.RenderColorEstimatorTest | 7 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.TextLayoutPlannerFinalSafetyTest | 7 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.TextLayoutPlannerFreeTextTest | 17 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.TextLayoutPlannerMaskMetadataTest | 10 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.TextLayoutPlannerSlice5Test | 8 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.TextLayoutPlannerStrokeTest | 4 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.TextLayoutPlannerTest | 31 | 0 | 0 | 0 |
| eu.kanade.translation.rendering.TextLineBreakerTest | 19 | 0 | 0 | 0 |
| eu.kanade.translation.segmentation.BubbleSegmentationDecoderTest | 8 | 0 | 0 | 0 |
| eu.kanade.translation.segmentation.MaskGeometryOrderedRleTest | 9 | 0 | 0 | 0 |
| eu.kanade.translation.segmentation.MaskGeometryStressTest | 2 | 0 | 0 | 0 |
| eu.kanade.translation.segmentation.MaskGeometryTest | 7 | 0 | 0 | 0 |

T912 focused suites remain green at HEAD `be28e35`.

## STEP 2 — T911 regression suites (README-named areas)

Suite discovery: `find`/`grep` under `app/src/test/java` located all 23
README-named classes plus 12 sibling suites matching the mandated patterns
(`TranslationManager*`, `MangaScreenModel*`, `Batch*`, `Tracker*`):

- Sibling `TranslationManager*` (5): `TranslationManagerArtifactReadTest`,
  `TranslationManagerAutoDeleteProtectionTest`,
  `TranslationManagerDeleteResetOrderingTest`,
  `TranslationManagerPausedAffordanceTest`, `TranslationManagerReaderTeardownTest`.
- Sibling `Batch*` (7): `BatchTranslationForegroundPolicyTest`,
  `BatchContextFrontierTest`, `BatchTranslateBlockMergeTest`,
  `BatchTranslationDiagnosticsTest`, `DisplayReadyStageCountTest`,
  `Phase0BatchTranslationCharacterizationTest`, `BatchEnvelopeLimitsTest`.
- `BatchStageInvocationCounters` was inspected and excluded: 0 `@Test`
  annotations — a helper, not a suite.

All 35 suites were run in ONE Gradle invocation:

```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest \
 --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest" \
 --tests "eu.kanade.translation.TranslationManagerQueueAdmissionFailureKindTest" \
 --tests "eu.kanade.translation.TranslationManagerDownloadNotificationsTest" \
 --tests "eu.kanade.translation.TranslationManagerDownloadFailureRecoveryTest" \
 --tests "eu.kanade.translation.TranslationManagerPendingAcknowledgementTest" \
 --tests "eu.kanade.translation.TranslationManagerStartupReconciliationTest" \
 --tests "eu.kanade.translation.TranslationRequestGenerationFenceTest" \
 --tests "eu.kanade.translation.TranslationPendingRequestStoreTest" \
 --tests "eu.kanade.translation.ChapterTranslatorTerminalExitsTest" \
 --tests "eu.kanade.translation.pipeline.batch.BatchTerminalExitTest" \
 --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTest" \
 --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTotalsTest" \
 --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressReducerTest" \
 --tests "eu.kanade.translation.pipeline.batch.SequentialBatchCoordinatorTest" \
 --tests "eu.kanade.translation.pipeline.batch.BatchResumeGateDeciderTest" \
 --tests "eu.kanade.translation.pipeline.batch.BatchProgressReconcilerTest" \
 --tests "eu.kanade.tachiyomi.ui.manga.MangaScreenModelTranslationDrawerTest" \
 --tests "eu.kanade.tachiyomi.ui.manga.MangaScreenModelMultiSelectBatchTest" \
 --tests "eu.kanade.translation.model.BatchHeroProjectionTest" \
 --tests "eu.kanade.translation.manager.BatchProgressProjectorDurableReconstructionTest" \
 --tests "eu.kanade.translation.model.TranslationUiProjectionTest" \
 --tests "eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistryTest" \
 --tests "eu.kanade.translation.pipeline.batch.TranslationBatchEventContractTest" \
 --tests "eu.kanade.translation.TranslationManagerArtifactReadTest" \
 --tests "eu.kanade.translation.TranslationManagerAutoDeleteProtectionTest" \
 --tests "eu.kanade.translation.TranslationManagerDeleteResetOrderingTest" \
 --tests "eu.kanade.translation.TranslationManagerPausedAffordanceTest" \
 --tests "eu.kanade.translation.TranslationManagerReaderTeardownTest" \
 --tests "eu.kanade.tachiyomi.data.translation.BatchTranslationForegroundPolicyTest" \
 --tests "eu.kanade.translation.pipeline.batch.BatchContextFrontierTest" \
 --tests "eu.kanade.translation.pipeline.batch.BatchTranslateBlockMergeTest" \
 --tests "eu.kanade.translation.pipeline.batch.BatchTranslationDiagnosticsTest" \
 --tests "eu.kanade.translation.pipeline.batch.DisplayReadyStageCountTest" \
 --tests "eu.kanade.translation.pipeline.batch.Phase0BatchTranslationCharacterizationTest" \
 --tests "eu.kanade.translation.translator.contextual.BatchEnvelopeLimitsTest"
```

Result: `BUILD SUCCESSFUL in 44s` (wall time 15:03:23 → 15:04:08, 45 s). All 35
requested suites produced XML (35 XML files).

Totals: **35 suites / 185 tests / 0 failures / 0 errors / 0 skipped.**

Per-suite counts (tests / failures / errors / skipped):

| Suite | Tests | F | E | S |
|---|---:|---:|---:|---:|
| eu.kanade.tachiyomi.data.translation.BatchTranslationForegroundPolicyTest | 1 | 0 | 0 | 0 |
| eu.kanade.tachiyomi.ui.manga.MangaScreenModelMultiSelectBatchTest | 4 | 0 | 0 | 0 |
| eu.kanade.tachiyomi.ui.manga.MangaScreenModelTranslationDrawerTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.ChapterTranslatorTerminalExitsTest | 3 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerArtifactReadTest | 9 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerAutoArbitrationTest | 2 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerAutoDeleteProtectionTest | 1 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerDeleteResetOrderingTest | 3 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerDownloadFailureRecoveryTest | 6 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerDownloadNotificationsTest | 7 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerPausedAffordanceTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerPendingAcknowledgementTest | 4 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerQueueAdmissionFailureKindTest | 4 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerReaderTeardownTest | 4 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationManagerStartupReconciliationTest | 7 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationPendingRequestStoreTest | 10 | 0 | 0 | 0 |
| eu.kanade.translation.TranslationRequestGenerationFenceTest | 8 | 0 | 0 | 0 |
| eu.kanade.translation.manager.BatchProgressProjectorDurableReconstructionTest | 6 | 0 | 0 | 0 |
| eu.kanade.translation.model.BatchHeroProjectionTest | 16 | 0 | 0 | 0 |
| eu.kanade.translation.model.TranslationUiProjectionTest | 3 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.BatchContextFrontierTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.BatchProgressReconcilerTest | 2 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.BatchResumeGateDeciderTest | 9 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.BatchTerminalExitTest | 3 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.BatchTranslateBlockMergeTest | 1 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.BatchTranslationDiagnosticsTest | 3 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.DisplayReadyStageCountTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.Phase0BatchTranslationCharacterizationTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.SequentialBatchCoordinatorTest | 17 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchEventContractTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchProgressReducerTest | 3 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTest | 8 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTotalsTest | 5 | 0 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistryTest | 4 | 0 | 0 | 0 |
| eu.kanade.translation.translator.contextual.BatchEnvelopeLimitsTest | 2 | 0 | 0 | 0 |

Coverage confirmed for every README-named area: batch admission/arbitration/
progress/terminal exits; download notifications, recovery, handoff failure split;
drawer opening, restored state, multi-select, queue position, chapter indicators;
hero/progress projections and durable reconstruction.

## STEP 3 — Module-wide target (`:app:testDevDebugUnitTest`)

This is the same narrowest-practical module-wide target used as T911's final
gate.

Command:

```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest
```

Result: `BUILD SUCCESSFUL in 1m 13s` (wall time 15:05:17 → 15:06:31, 74 s).
167 XML suites.

Totals: **167 suites / 1287 tests / 0 failures / 0 errors / 0 skipped.**

### Comparison against the T911 baseline (157 suites / 1182 tests)

| Metric | T911 baseline | T912 HEAD | Delta |
|---|---:|---:|---:|
| Suites | 157 | 167 | +10 |
| Tests | 1182 | 1287 | +105 |
| Failures | 0 | 0 | 0 |
| Errors | 0 | 0 | 0 |
| Skipped | 0 | 0 | 0 |

The delta is exactly the T912 additions, verified independently via
`git diff --stat ece0e72..HEAD -- app/src/test/java`:

- 10 new unit suites (+104 tests):
  `AdaptiveBandPlannerTest` (9), `ComponentClipCacheTest` (6),
  `MaskTextRegionPlannerTest` (13), `PageLayoutPlanContractTest` (6),
  `TextLayoutPlannerFinalSafetyTest` (7), `TextLayoutPlannerFreeTextTest` (17),
  `TextLayoutPlannerMaskMetadataTest` (10), `TextLayoutPlannerSlice5Test` (8),
  `TextLineBreakerTest` (19), `MaskGeometryOrderedRleTest` (9).
- 1 added test in an existing suite (+1):
  `TextLayoutPlannerTest` 30 → 31 `@Test`
  (`RenderColorEstimatorLayoutSamplingTest` unchanged at 3).
- 104 + 1 = +105 tests; 157 + 10 = 167 suites. No suite-count or test-count
  surprises; no failures anywhere (XML scan for nonzero
  failures/errors/skipped across all 167 files returned nothing).
- The same diff also modified
  `app/src/androidTest/java/.../rendering/PageTextRendererInstrumentedTest.kt`
  (covered by the STEP 4 compile gate, not by JVM counts).

## STEP 4 — Instrumentation compile gate + device availability probe

Command:

```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin
```

Result: `BUILD SUCCESSFUL in 25s` (wall time 15:10:10 → 15:10:36, 26 s),
exit code 0. The repaired instrumentation sources
(`PageTextRendererInstrumentedTest.kt` and friends) compile at HEAD.

Device probe:

```
"C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices -l
```

Output: `List of devices attached` — **no device or emulator attached.**

Therefore `:app:connectedDevDebugAndroidTest` was NOT run (the mandate runs it
only if a device is actually available). Instrumentation EXECUTION remains
deferred (compile-gated only), consistent with slices 1–7.

---

## Summary

| Step | Target | Suites | Tests | Failures | Errors | Skipped | Wall |
|---|---|---:|---:|---:|---:|---:|---:|
| 1 | T912 focused (`segmentation.*`, `rendering.*`) | 20 | 186 | 0 | 0 | 0 | 33 s |
| 2 | T911 regression suites (35 FQCNs, one invocation) | 35 | 185 | 0 | 0 | 0 | 45 s |
| 3 | Module-wide `:app:testDevDebugUnitTest` | 167 | 1287 | 0 | 0 | 0 | 74 s |
| 4 | `:app:compileDevDebugAndroidTestKotlin` | — | — | — | — | — | 26 s |
| 4 | `connectedDevDebugAndroidTest` | not run — no device attached | | | | | — |

Final verdict: **GATE PASS** — 0 failures, 0 errors, 0 skipped across all
executed steps; module-wide delta over T911 is exactly the T912 additions
(+10 suites, +105 tests).
