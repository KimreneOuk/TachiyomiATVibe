# HF-02 implementation report: manual UX truth

Date: 2026-09-20  
Branch: `t936/hotfix-manual-ux-truth`

## Commits

- `0c613e9 docs(plan): HF-02 manual UX truth ticket`
- `5c23bdf fix(translation): manual-mode reader truth (no batch framing, live stage feedback, attributed cancel)`
- This report is committed separately as `docs(plan): HF-02 manual UX truth implementation report`.

## Task 1 — manual reader bars no longer show batch framing

The reader bar was projecting the active translation progress snapshot without checking the
session origin. That made a one-page MANUAL request look like a one-page batch (`Batch 0/1`).

The fix routes `TranslationSessionCoordinator.state` through `ReaderViewModel` and
`ReaderActivity` into `ReaderAppBars`/`BottomReaderBar`. Batch counters, batch status text, and
the request-phase batch icon are now projected only when the state is `BATCH_SESSION`.
`TranslationUiTruth.readerBarLine` also has the same explicit session guard, so the presentation
truth helper cannot manufacture a batch line for a manual reader. No session-state transition
semantics were changed.

Behavioral test added:

- `ReaderBarTruthTest.reader bar hides batch framing outside a batch session` — manual/non-batch
  truth returns no batch status line.

## Task 2 — live manual stage truth

The trace showed that intermediate states were not reaching the shared store, rather than being
emitted and merely hidden by the reader. OCR was published, but the single-page native path kept
the inpaint transition in a local `PageTranslation`; the HTTP path similarly changed local state
before translation and rendering. The overlay therefore observed OCR RUNNING followed by one
terminal snapshot.

`SinglePageOnnxPhase` now publishes the inpaint RUNNING snapshot before native inpainting.
`SinglePageHttpRenderPhase` publishes snapshots before translation, retry translation, rendering,
and retry rendering. Existing reader observation/mapping then exposes the sequence as OCR,
translating, inpainting/cleaning, and rendering/finishing without changing the terminal artifact
or HF-01 source-file-name keying.

Behavioral test added:

- `ReaderTranslationFeedbackTest.manual page stage transitions remain observable at every stage` —
  the observed `PageTranslation` sequence maps to Reading Text, Cleaning Bubbles, Translating
  Text, and Finishing Page.

## Task 3 — cancellation attribution and origin protection

Archaeology of the literal `All translation cancelled` found the reader teardown cancel-all
entry point in `ReaderTeardownCoordinator`; its callers include reader stop/close, translation
toggle-off, background lifecycle, and explicit reader stop-all. Chapter switching separately
uses `awaitReaderStop`. Rapid page scrolling itself does not directly invoke the all-cancel path;
it can exercise lifecycle/chapter transition teardown. The confusing batch affordance was also
made unavailable to manual readers by Task 1.

The scheduler now carries cancellation reasons for active manual jobs and writes the reason into
the cancellation/error snapshot. Reader-directed actions provide attributed reasons such as
`Manual stop requested from reader`, `Translation disabled by user`, `reader backgrounded`, and
`Reader translation stop`; durable teardown writes use the caller's reason. Auto cancellation is
tagged as AUTO and does not cancel a page currently leased to a different origin (including a
MANUAL page). This keeps auto-window teardown from killing in-flight manual work while retaining
existing lease-free auto behavior.

Behavioral test added:

- `CancelSyncStoreWriteTest.auto cancellation does not cancel a page owned by a manual lease` — an
  AUTO cancellation leaves the MANUAL-owned page RUNNING.

The additive P2-00 diagnostic also logs compiled ONNX providers at environment and session-options
creation (`OnnxRuntimeProvider.logCompiledProviders`); it does not alter provider routing.

## Task 4 — latency evidence

The local verification environment has JVM/store/UI-truth tests but no device logcat or native
model execution trace. Consequently, device stage wall times (detector initialization, OCR,
provider call, inpaint, and render) are **N/A from local runs** and are intentionally not
fabricated. The orchestrator's fresh device reproduction should capture the per-stage
`durationMs` values for the final report/device verdict. Local tests validate that each stage is
observable, not its device latency.

## Verification

All Gradle commands used `JAVA_HOME=C:\\Program Files\\Android\\Android Studio\\jbr`,
`--no-parallel --max-workers=1`, and temporarily copied
`app/src/standard/google-services.json` to `app/google-services.json`; the temporary file was
removed after each command.

Focused suites:

1. `./gradlew.bat :app:compileDevDebugUnitTestKotlin :app:testDevDebugUnitTest --tests ReaderBarTruthTest --tests ReaderTranslationFeedbackTest --tests CancelSyncStoreWriteTest --no-parallel --max-workers=1` — **PASS**.
2. `./gradlew.bat :app:compileStandardDebugUnitTestKotlin :app:testStandardDebugUnitTest --tests ReaderBarTruthTest --tests ReaderTranslationFeedbackTest --tests CancelSyncStoreWriteTest --no-parallel --max-workers=1` — **PASS**.

Full suites and flake isolation:

1. `:app:testDevDebugUnitTest --no-parallel --max-workers=1` — 2,030 tests; two load-sensitive
   coexistence failures: `BatchDispatchResumeWiringTest.re-dispatch after a reset demotes real
   work in the same lane while healthy pages stay retired()` failed with `state=TRANSLATE` and
   `pagesTranslated=1` after the 10,000 ms completion wait, and
   `StandardPipelineCoexistenceTest.flagged standard lane runs the real shell end-to-end with
   full OCR before translate()` ended at `TRANSLATE` instead of `COMPLETE`. Each class passed
   three isolated Dev runs (0,0,0).
2. A second full Dev run — 2,030 tests; the same
   `BatchDispatchResumeWiringTest` 10-second `state=TRANSLATE` timeout plus
   `StandardLaneMultiPageCompletionTest.fresh standard batch translates every page of a
   multi-page chapter()` (`expected TRANSLATED, got ERROR`). Both classes passed three isolated
   Dev runs (0,0,0). These are existing batch/coexistence end-to-end timing signatures under full
   suite load, not manual-reader, legacy-storage, or HF-02-specific failures.
3. `:app:testStandardDebugUnitTest --no-parallel --max-workers=1` — first full run: 2,026 tests;
   `MangaScreenModelTranslationDrawerTest` initialization timed out after 5,000 ms waiting for
   `screen state becomes Success (state=Loading)`. The test passed three isolated Standard runs
   (0,0,0). A clean final full Standard rerun completed **PASS**, 2,026 tests.

Per flake policy, every full-suite failure was isolated three times and passed; no touched manual,
reader teardown, storage, or HF-01 regression failed in isolation. The transient failures match
the repository's pre-existing timing-flake pattern in end-to-end translation/UI tests under load.

APK gate:

- `./gradlew.bat :app:assembleDevDebug --no-parallel --max-workers=1` — **BUILD SUCCESSFUL**.
- APK inspected: `app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk`.
- `best_int8.onnx`: absent (0 entries).
- Documentation/metadata under `assets/models/ocr/` (`.md`, `.yml`, `.gitattributes`): absent (0
  entries).
- `assets/models/segmentation/manga109_bubble_int8.onnx`: present (1 entry).
- `assets/models/ocr/paddle-v6-small/inference.onnx` and
  `assets/models/ocr/paddle-v6-small/det/inference.onnx`: present (2 entries total).

The working tree was clean of unintended files after the temporary Google Services copy was
removed. No HF-01 keying, session semantics, or durability paths were changed.
