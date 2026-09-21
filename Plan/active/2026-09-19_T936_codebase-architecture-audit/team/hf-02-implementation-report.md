# HF-02 implementation report: manual UX truth

Date: 2026-09-20  
Branch: `t936/hotfix-manual-ux-truth`

## Commits

- `0c613e9 docs(plan): HF-02 manual UX truth ticket`
- `5c23bdf fix(translation): manual-mode reader truth (no batch framing, live stage feedback, attributed cancel)`
- `c44e660 docs(plan): HF-02 manual UX truth implementation report`
- `d93450c test(translation): join StandardLane batch runs and undispatch launch (isolation determinism)`
  - Preserves the timeout and residual load-ERROR XML/HTML evidence under
    `team/hf-02-blocker-evidence/`.
- `7f42201 test(translation): remove temporary StandardLane failure diagnostics`
- The corrected flake ledger is committed separately as
  `docs(plan): update HF-02 flake ledger after harness determinism fix`.

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

Full suites and flake isolation ledger:

1. Historical HF-02 full-suite runs recorded load-sensitive coexistence failures in
   `BatchDispatchResumeWiringTest` (`state=TRANSLATE`, `pagesTranslated=1`, after a 10,000 ms
   completion wait), `StandardPipelineCoexistenceTest` (`TRANSLATE` instead of `COMPLETE`), and
   `StandardLaneMultiPageCompletionTest` (`expected TRANSLATED, got ERROR`). The affected classes
   passed their three isolated runs at that stage. These failures motivated the deterministic
   harness investigation; they are not silently counted as green.
2. The investigation identified two harness mechanisms and fixed both in `d93450c`:
   - an ordinary `Dispatchers.IO.launch` could leave the real batch coroutine undispatched behind
     full-suite IO load, producing the preserved 10-second p0 transport-start timeout;
   - the negative-control test discarded its `BatchRun.job`, allowing real work to unwind after
     global MockK/chapter-page teardown and bleed into a following test, including the preserved
     pre-transport load-ERROR signature.
   `CoroutineStart.UNDISPATCHED` now starts the batch from the caller, and both StandardLane tests
   retain and `cancelAndJoin` their batch jobs before removing global shims/stubs. Assertions were
   unchanged. The timeout evidence is
   `team/hf-02-blocker-evidence/StandardLaneMultiPageCompletionTest.failure-run-10-20260921-074650674.xml`;
   the one residual load-ERROR evidence is
   `team/hf-02-blocker-evidence/full-dev1-StandardLane-20260921-084220.xml`.
3. Post-fix isolation: the local StandardLane class passed **10/10** repetitions (two tests per
   run), and the parent verification machine passed **3/3** repetitions after previously failing
   2/4. The conditional failure diagnostics used for the investigation were removed in
   `7f42201`; no diagnostic code remains in the final test.
4. Post-fix full Dev: the parent verification machine completed **2,030 tests, 0 failures, 0
   errors, 0 skipped** (fresh XMLs at 09:51). The final clean local command
   `./gradlew.bat :app:testDevDebugUnitTest --no-parallel --max-workers=1 --console=plain`
   completed **PASS** in 4m09s; its 293 XML suites aggregate to **2,030 tests, 0 failures, 0
   errors, 0 skipped**. The earlier one-occurrence load-ERROR variant had no emitted page-state
   snapshot, so its exact production failure site remains unproven; it is filed as a tracked
   follow-up with both preserved XML/HTML artifacts rather than being relabeled as a pass.
5. `:app:testStandardDebugUnitTest --no-parallel --max-workers=1` — first full run: 2,026 tests;
   `MangaScreenModelTranslationDrawerTest` initialization timed out after 5,000 ms waiting for
   `screen state becomes Success (state=Loading)`. The test passed three isolated Standard runs
   (0,0,0). A clean final full Standard rerun completed **PASS**, 2,026 tests.

The ledger therefore distinguishes the two fixed harness mechanisms from the single residual
load-ERROR occurrence whose mechanism was not captured. No touched manual, reader teardown,
storage, or HF-01 regression failed in isolation, and no test assertion was weakened.

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
