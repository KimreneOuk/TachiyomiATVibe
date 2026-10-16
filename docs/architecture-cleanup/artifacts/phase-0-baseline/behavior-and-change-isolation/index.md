---
title: "Phase 0 baseline: executable behavior and change-isolation inventory (static)"
kind: spec
comments: none
---

# Scope, baseline, verification limits

Baseline `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`, tracked tree clean, untracked user report preserved. Static source/test read only from this agent: no Gradle, no device, no test runs. "Test evidence" below means an existing assertion was located and read — it does NOT mean it passes. The runtime record is owned by [build-and-tests](../build-and-tests) and [failure-triage](../build-and-tests/failure-triage); six distinct failing methods are known and listed below. Device distributions are pending by user choice ([device-baseline](../device-baseline)).

## Verified source inventory (git ls-files at HEAD + filesystem recursion)

| Scope | Exact count | Composition |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation` | 291 tracked = **290 source** | 288 `.kt` + 2 `.java` (`engines/runtime/onnx/CheckNnapi.java`, `CheckXnnpack.java`); +1 non-source `README.md`. `tachiyomi/data/translation` (2 `.kt`) is outside the subtree and excluded. |
| `app/src/test/java/eu/kanade/translation` (unit tests) | **318** | all `.kt`, zero non-source |
| `app/src/testDebug/java/eu/kanade/translation` | **1** | `diagnostics/TranslationDebugMeasurementTest.kt` (separate source set; runs inside both flavor unit suites) |
| `app/src/androidTest/java/eu/kanade/translation` | **6** | journal microbench, ManualMeasurementBench, PaddleOcrLiteRtOnDevice, PageInpaintingEngineOnDeviceVerification, LiteRTOnDeviceVerification, RenderColorEstimatorInstrumented |

## Known baseline failures (six distinct methods — from the preserved full-suite ledger and [failure-triage](../build-and-tests/failure-triage); not run by this agent)

| Method (fully qualified > name) | Observed |
| --- | --- |
| `eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest` > `file sink preserves queue order and sanitizes embedded line breaks` | FAIL every observed run (both flavors, full + isolated). Format drift: `app/src/debug/.../TranslationTraceFileSink.kt:69–83` prepends `ts=`; order/sanitization still hold |
| `eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest` > `lazy store cuts over before first candidate and reopens from artifact pointers` | FAIL 4/4. Reopened `cleanedImageName` observed null vs expected `"page.cleaned.retry.jpg"`; cause open (promotion/reopen contract vs fixture drift) |
| same class > `pre-registered pages publish one durable baseline with only the changed candidate` | FAIL 4/4. Flushed manifest pages `[]` vs expected 3 keys; contract audit required |
| same class > `artifact-only lazy store writes pages without materializing flat compatibility file` | FAIL 4/4. Test body passes; JUnit `@TempDir` close-time `IOException`, Windows file lock; handle owner unsettled |
| same class > `lazy store without parent resolves artifact authority through artifactParentResolver` | FAIL 4/4. Same `@TempDir` close-time failure |
| `eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest` > `protocol outcome commits fully covered pages and parks omitted block pages without pausing` | Full-suite DevDebug FAIL (`UncompletedCoroutinesError`, 60 s) / isolated PASS both flavors; one observation per cell, mechanism hypothesized only |

Store reopen/adopt contracts are required input before the Phase 3 gate; no Phase 0 repair is authorized.

# Executable behavior inventory

| # | Behavior | Owner / entry (verified) | Required invariant | Test evidence (exact) | Gap | Retire candidate? |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Manual tap single page | `TranslationPipeline.translateSinglePage` (TranslationPipeline.kt:487–531; MANUAL origin, FOREGROUND permit :596; typed outcomes :733–746; `REASON_TRANSLATION_NOT_SAVED` :209) | Typed acknowledge/reject, never silent; stall rejects before admission; Completed only on durable commit | `app/src/test/java/eu/kanade/translation/coexistence/PipelineStallWatchdogTest.kt` > `parked native lane emits stall, rejects new tap, then clears and admits next tap`; `coexistence/P5HonestOutcomeTypingTest.kt` > `condition B - HTTP render timeout must not be typed Completed`, > `condition C - publication rejection must surface as typed non-success, not Completed` | Manual path covered via harness fakes; no device run | No. FOREGROUND priority and typed mapping keep |
| 2 | Auto visible + ahead window (OLD) vs N/N+1 candidate | `RollingAutoCoordinator.updateWindow/reconcilePass` (:197–261, :766–1019); desired = visible + consecutive ahead prefix (:1046–1068); prepared channel cap `recommendedPrefetchCapacity()` (:424) | Visible priority; memory defers non-foreground; source-missing defers then recovers; stale handoff bounded (cap 3 :1409) | `scheduling/RollingAutoCoordinatorTest.kt` > `memory deferral publishes Deferred Memory and refills on reconcile`, > `missing stream defers with SourceUnavailable and recovers on reconcile`, > `stale translate handoff re-prepares then succeeds`, > `persistently stale translate is bounded and flips to Failed`, > `foreground bypasses memory gate while ahead pages defer`; `scheduling/AutoWindowStateTest.kt` > `normal window targets the next N pages excluding the visible page`, > `readyAheadCount never counts the visible foreground page` | Current window = configurable ahead target + prepared channel — not plain N+1; N/N+1 remains the ledger's candidate product baseline, unproven responsive | Window *behavior* required; channel + wave mechanics are retirement candidates |
| 3 | Batch chapter translation | `TranslationPipeline.translateBatch` (:1254) → `BatchChapterTranslator` (ctor :79–139; 24 params / 20 fn-typed) → `BatchLaneWorkers` (ctor :95–161; 39 params / 11 fn-typed); generation envelope `store.withGeneration` (BatchChapterTranslator.kt:301) | Ordered traversal; chapter-level no-inpaint-before-OCR-final; failed predecessor strands successors; per-stage BATCH leases | `coexistence/StandardLaneMultiPageCompletionTest.kt` > `fresh standard batch translates every page of a multi-page chapter`, > `failed predecessor keeps successors honestly stranded`; `pipeline/batch/OverlapSchedulerTest.kt` > `non-final ocr pages are never inpaint candidates - no inpaint before ocr final`; `pipeline/batch/BatchContextFrontierTest.kt` > `resume seeds only the contiguous natural predecessor prefix` | No single end-to-end ordering test outside these; per-arg constructor rationale undocumented (Phase 3) | Overlap/speculative mechanics conditional; batch scheduling stays distinct |
| 4 | Cancel / replace / generation fence | Store leases `PageStageLeaseTable` (:59–283); `withGeneration` (TranslationPipeline.kt:614/:913); request fence `TranslationRequestCoordinator`; chapter switch = coordinator identity change (RollingAutoCoordinator.kt:211–217) | Old owner writes fail closed on stale token/generation; cancel flips RUNNING→CANCELLED without touching BATCH-leased pages | `coexistence/ReaderManualPreemptsAutoLeaseTest.kt` > `manual boundary evicts an auto lease whose later writes fail closed and cannot release the manual lease` (asserts `Rejected` on stale token, origin-checked release); `workflow/TranslationRequestGenerationFenceTest.kt` > `re-requested generation fences the previous request's late callback`; `scheduling/CancelBatchLeaseSkipTest.kt` > `markChapterCancelledSync leaves a BATCH-leased page untouched and cancels the lease-free page`; `scheduling/RollingAutoCoordinatorTest.kt` > `identity switch resets transient state and binds the new chapter window` | None material found at store/fence level | No — fencing is mandatory |
| 5 | Native physical completion | `NativeRunQuarantine.run` (strips caller Job :72, select await/timeout :87–98, NonCancellable drain :112–126); EngineLane drain grace; watchdog | A timed-out/cancelled caller cannot free the lane before real exit; late result rejected; rebuild blocked while parked | `pipeline/execution/NativeRunQuarantineTest.kt` > `timeout blocks new admission until underlying exit and discards late result` (`secondEntered.isCompleted shouldBe false` until `firstMayExit.complete`), > `exclusive lifecycle action cannot enter while timed out invocation is alive`; `coexistence/EngineEpochStopRaceTest.kt` > `close with a native call parked does not close the engines` | **Physical JNI cancellation/late-completion/drain has NO automated coverage**: the 6 androidTest classes are OCR/LiteRT/inpaint/journal/color measurement utilities and do not verify this race; device gate pending by user choice — this gap is not covered by any existing pending suite | **No.** Quarantine must survive any redesign |
| 6 | Paid-provider drain | `RollingAutoCoordinator.consumeTranslations` NonCancellable + `withTimeout(drainGraceMs)` (:546–566), grace 210_000 (:1398); attempt ledger `store.autoAttemptAllowed/resolveAttempt` (ChapterTranslationStore.kt:1689–1698, `ChapterAttemptLedger.kt:96`) | Cancelled window drains the in-flight call to commit; grace expiry cancels cleanly; ledger resolved on completion, unresolved only on process death | `coexistence/AutoProviderCallDrainsNotCancelsTest.kt` > `cancelled auto window drains the in flight call and commits instead of cancelling` (`translateCallsFor("p0") shouldBe 1`, `cancelledCalls.get() shouldBe 0`), > `grace expiry cancels the parked call without committing`; `coexistence/BatchAttemptLedgerDeathCycleTest.kt` > `paid batch call writes a ledger entry before the provider call and startup reconcile consumes it`, > `three interrupted death cycles pause the chapter refuse auto entries and yield to explicit force` | Drain bound vs real call budget asserted only as constant bounds (`AutoProviderCallDrainsNotCancelsTest.kt:171`, `EngineEpochStopRaceTest.kt:243`) | Policy is deliberate product behavior — keep or consciously change, never silently drop |
| 7 | Resume / recovery | Journal writer/recovery (`persistence/journal/*`); OCR checkpoint reuse; artifact-authoritative reopen; batch resume planner | Replay valid contiguous prefix; corrupt payload invalidates page not epoch; checkpoint survives restart, reused by reader+batch; cancelled batch restart reuses completed work | `persistence/journal/ChapterJournalRecoveryTest.kt` > `bad crc leaves the valid contiguous prefix and marks epoch corrupt`; `persistence/journal/ChapterJournalLagCrashTest.kt` > `named power loss before sync loses only the unsynced frame and after sync replays it`; `persistence/chapter/OcrCheckpointRestartReuseTest.kt` > `m1_ checkpointed ocr survives a process restart and is reused by reader and batch origins`; `coexistence/CancelledBatchRestartReuseTest.kt` > `cancelled batch restarts from completed work with only the remainder re-run` | **ArtifactOpenProbe reopen/adopt contracts currently failing (2 behavior + 2 fixture-resource methods above) — required before Phase 3 gate** | No |
| 8 | Cleaned-image publication / candidate vs committed | `CleanedImagePublisher.publish` (write→commit→retire, :24–87); `CleanedPublication.persistLazyCleanedBitmap` queues a closure capturing `cleanedBitmap`, returns its completion `Deferred` (:346–431); store `state` vs `display` flows (ChapterTranslationStore.kt:403–411) | **Required:** accepted or possibly-referenced cleaned files are retained until owner-aware retirement; a definitively unreferenced rejected file is cleaned up only with store-owner permission; candidate never hides committed display; writer completion joined before recycle. **Known hazard (observed baseline, NOT a requirement):** `publish` deletes the new file on commit cancellation/exception (:44–58) without proving no accepted reference exists | `persistence/chapter/CleanedImagePublisherTest.kt` > `old file is deleted only after accepted commit`, > `rejection preserves current old file and cleans unpublished new file`, > `commit exception cleans unpublished new file without deleting old file` (pins the hazard behavior, not a required rule), > `committed display retains superseded file until the next pointer is drained`; `persistence/chapter/ChapterTranslationStorePhase3Test.kt` > `candidate mutations stay out of reader display until promotion shape is ready`, > `cancelled transient candidate cannot hide committed display`; `pipeline/execution/TranslationStreamRegistryTest.kt` > `retired cleaned image remains readable while held and deletes on release` | Accepted/uncertain-publication receipt races are design-verified (mechanisms artifact), not fully test-covered; publisher authority boundary unresolved | No — guarded publication is mandatory; the hazard needs an owner-aware receipt decision |
| 9 | User edits | Committed-bundle retention + envelope edit fence | User-edited committed block survives checkpoint OCR close/adopt; edit fence excludes edited blocks from follow-ups | `persistence/chapter/ChapterTranslationStorePersistenceTest.kt` > `user edited committed block survives checkpointOcr close and adopt with bundle identity intact`; `engines/translator/retry/AiChunkAdaptiveRetryTest.kt` > `user edit fence excludes edited blocks and preserves their value` | Thinnest row: no dedicated conflict-rule suite; mechanism inferred from two suites | No |
| 10 | Source-ready / stream deferral | Coordinator defers `SourceUnavailable` when resolver null (:825–841); external `reconcile()` poke (:269–272) | Temporary absence is not terminal; event-driven wake, no spin | `scheduling/RollingAutoCoordinatorTest.kt` > `missing stream defers with SourceUnavailable and recovers on reconcile` | Scheduler-side wiring that emits the poke on stream-ready events not traced to a dedicated test — **unverified wiring**; mechanism verified at coordinator level only | Keep event-driven wake; drop any polling fallback if found |
| 11 | Memory governance | `TranslationMemoryBudget` (`hasHeadroomForPrefetch` :306, `recommendedPrefetchCapacity` :40); decode decisions; `MemoryGovernance` OOM gates; batch `CrossOriginBitmapBudget` | Prefetch pauses under pressure, visible page bypasses; decode refuses unsafe sizes; OOM does not kill the lane | `pipeline/memory/TranslationMemoryBudgetTest.kt` > `normal page decodes full quality when it fits heap budget`, > `huge source page still defers when source capped sample does not fit heap`; `coexistence/BatchParallelWindowBudgetTest.kt` > `cross origin bitmap budget bounds concurrency at 2 and suspends 3rd acquirer until release`; row-2 foreground-bypass method | Prefetch capacity + prepared channel are the optional mechanisms (ledger) | Budget/gates: no. Adaptive prefetch capacity: candidate |
| 12 | Local-compute exclusion | `TranslatorComputeClass.mayOverlapNative` (TranslatorComputeClass.kt:33); coordinator `Semaphore(1)` gate (:423) | Local translation never overlaps native OCR/inpaint | `scheduling/RollingAutoCoordinatorTest.kt` > `local compute serializes prepare and translate through a shared gate`; batch analog `CrossOriginBitmapBudget` | Exclusion lives in the coordinator only — a queue redesign must re-derive it | Exclusion required while LOCAL_COMPUTE exists; the Semaphore may move |
| UI | Rejection truth | `TranslationUiTruth.rejected` string-compares `REASON_TRANSLATION_NOT_SAVED` (:504); partial→Paused (SinglePageHttpRenderPhase.kt:536–583) | Persistence rejection never renders success; timer copy names the fired timer | `presentation/P5OutcomeProjectionTest.kt` > `persistence rejection projects the not saved truth and never success`; `presentation/P5CopyAndAccessibilityTest.kt` > `http render timeout placeholder names the http render timer`, > `native timeout placeholder names the onnx native timer` | — | String compare is the F-04 simplification target; behavior stays |

Related verified owners: adaptive pin `AdaptiveKnobController.appliedConcurrency = floor` (:79–80; gate consumer TranslationPipeline.kt:282); attempt-charge idempotence (`PageTranslationState.kt:158–163`; `model/PageTranslationStateTest.kt` > `recordAttemptFailure is idempotent per attempt`); textless terminal (`PageTranslationState.kt:177–182`; `engines/inpainting/PageInpaintingEngineTest.kt` > `textless page with no erase regions completes without invoking backend`, > `backend failure on a textless masked page is not reported as success`; `pipeline/PostOcrStageSemanticsTest.kt` > `zero block zero mask OCR result remains terminal after stage handoff`); durable-vs-live vocab split (`ArtifactContracts.kt:11–103`: STALE/CORRUPT/FAILED_RETRYABLE/FAILED_TERMINAL have no live equivalent).

# Change-amplification exercises (static trace, exact files)

**Distinction (evidence, not assumption):** *data/field* additions propagate through data-class defaults and touch only semantic sites; *signature* changes propagate through every intermediate function-type declaration. No "inevitable 5 files" claim is made for field additions.

## A. Add OCR metadata (field, defaulted)

| Class | Files (verified) |
| --- | --- |
| Semantic | `model/PageTranslation.kt:302` (`TranslationBlock`); producers set the value: `engines/vision/ocr/RoiPageRecognitionEngine.kt:750/:1068`; durability if persisted: `model/PageTranslationView.kt:244/:274/:621` (`PublishedTranslationBlock` draft mapping), `persistence/chapter/TranslationStageContracts.kt:70` (`TranslationBlockPatch`), journal/manifest payload |
| Forwarding | **Zero** — blocks travel as data through `analyzePage`/store patches; no Pipeline→BatchChapterTranslator→BatchLaneWorkers callback signature edits |
| Tests | Only fixtures constructing blocks/serialization (`engines/vision/ocr/PaddleOcrB1TestFixtures.kt` etc.). A required (no-default) field would additionally edit both producer construction sites |

## B. Change inpaint retry inputs

| Class | Files (verified) |
| --- | --- |
| Mechanical signature sites (5 production files carry duplicate declarations of the same function type) | Owner `pipeline/SinglePageOnnxPhase.kt:813`; `TranslationPipeline.kt:805–807` (lambda), `:1246` (pass-through), `:1370–1379` (forwarder); `SinglePageHttpRenderPhase.kt:85/:145–153`; `BatchChapterTranslator.kt:111–119` ctor + `:690`; `BatchLaneWorkers.kt:137–145` ctor + `:330–338` |
| Value-only change (same signature) | Owner + the two call sites only: `SinglePageHttpRenderPhase.kt:802`, `BatchLaneWorkers.kt:731` — no constructor edits |
| Semantic caveat | A change to the *inputs themselves* (e.g. different downscale inputs or retry triggers) may require additional semantic work at the callers that compute those inputs — not counted above. No concrete input exercise has been run, so no hard "1 semantic file" total is claimed for input-changing work |
| Tests | Coexistence/batch harnesses wiring these lambdas directly need matching signatures on a signature change (count not individually enumerated — unverified) |

## C. Change publication preconditions

| Class | Files (verified) |
| --- | --- |
| Semantic | `ChapterTranslationStore.kt:443–455` (`PatchPrecondition`), internal mappers `:2918/:2928/:2937/:4596`, `updatePageGuarded` fence; typed rejections `PatchResult.Rejected.Detail` (:457–479) |
| Optional/defaulted field | Store + only writers presenting the new evidence (`PageStoreWriter.kt:287`, `CleanedPublication.kt:136`, `BatchWriteGate.kt:255/:449`, `RecoveryWorker.kt:280/:491/:583`, `OverlapScheduler.kt:394`, `ProfileEnvelopeExecutor.kt:834/:993`, `ChapterProfileBatchCoordinator.kt:923/:1566` as needed) — no callback expansion |
| Required field | All direct constructors: the 8 production files above + ~12 test files constructing `PatchPrecondition(` (grep-verified) |
| UI | No edit unless a new rejection Detail class is added |

# Verified critical suite selectors (literal paths and FQNs, unit source set `app/src/test/java/`)

| Path under `app/src/test/java/eu/kanade/translation/` | Fully qualified class |
| --- | --- |
| `pipeline/execution/NativeRunQuarantineTest.kt` | `eu.kanade.translation.pipeline.execution.NativeRunQuarantineTest` |
| `pipeline/execution/PreparedPageRuntimeBoundaryTest.kt` | `eu.kanade.translation.pipeline.execution.PreparedPageRuntimeBoundaryTest` |
| `pipeline/execution/TranslationStreamRegistryTest.kt` | `eu.kanade.translation.pipeline.execution.TranslationStreamRegistryTest` |
| `coexistence/ReaderManualPreemptsAutoLeaseTest.kt` | `eu.kanade.translation.coexistence.ReaderManualPreemptsAutoLeaseTest` |
| `coexistence/AutoProviderCallDrainsNotCancelsTest.kt` | `eu.kanade.translation.coexistence.AutoProviderCallDrainsNotCancelsTest` |
| `coexistence/BatchAttemptLedgerDeathCycleTest.kt` | `eu.kanade.translation.coexistence.BatchAttemptLedgerDeathCycleTest` |
| `coexistence/P5HonestOutcomeTypingTest.kt` | `eu.kanade.translation.coexistence.P5HonestOutcomeTypingTest` |
| `coexistence/PipelineStallWatchdogTest.kt` | `eu.kanade.translation.coexistence.PipelineStallWatchdogTest` |
| `coexistence/EngineEpochStopRaceTest.kt` | `eu.kanade.translation.coexistence.EngineEpochStopRaceTest` |
| `coexistence/CancelledBatchRestartReuseTest.kt` | `eu.kanade.translation.coexistence.CancelledBatchRestartReuseTest` |
| `coexistence/StandardLaneMultiPageCompletionTest.kt` | `eu.kanade.translation.coexistence.StandardLaneMultiPageCompletionTest` |
| `scheduling/RollingAutoCoordinatorTest.kt` | `eu.kanade.translation.scheduling.RollingAutoCoordinatorTest` |
| `scheduling/AutoWindowStateTest.kt` | `eu.kanade.translation.scheduling.AutoWindowStateTest` |
| `scheduling/CancelSyncStoreWriteTest.kt` | `eu.kanade.translation.scheduling.CancelSyncStoreWriteTest` |
| `scheduling/CancelBatchLeaseSkipTest.kt` | `eu.kanade.translation.scheduling.CancelBatchLeaseSkipTest` |
| `workflow/TranslationRequestGenerationFenceTest.kt` | `eu.kanade.translation.workflow.TranslationRequestGenerationFenceTest` |
| `workflow/TranslationSessionCoordinatorTest.kt` | `eu.kanade.translation.workflow.TranslationSessionCoordinatorTest` |
| `workflow/TranslationManagerAutoArbitrationTest.kt` | `eu.kanade.translation.workflow.TranslationManagerAutoArbitrationTest` |
| `persistence/chapter/ChapterTranslationStorePhase3Test.kt` | `eu.kanade.translation.persistence.chapter.ChapterTranslationStorePhase3Test` |
| `persistence/chapter/ChapterTranslationStorePersistenceTest.kt` | `eu.kanade.translation.persistence.chapter.ChapterTranslationStorePersistenceTest` |
| `persistence/chapter/ChapterTranslationStoreLazyPersistenceTest.kt` | `eu.kanade.translation.persistence.chapter.ChapterTranslationStoreLazyPersistenceTest` |
| `persistence/chapter/ChapterTranslationStoreJournalCaptureTest.kt` | `eu.kanade.translation.persistence.chapter.ChapterTranslationStoreJournalCaptureTest` |
| `persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt` | `eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest` |
| `persistence/chapter/CleanedImagePublisherTest.kt` | `eu.kanade.translation.persistence.chapter.CleanedImagePublisherTest` |
| `persistence/chapter/OcrCheckpointRestartReuseTest.kt` | `eu.kanade.translation.persistence.chapter.OcrCheckpointRestartReuseTest` |
| `persistence/journal/ChapterJournalRecoveryTest.kt` | `eu.kanade.translation.persistence.journal.ChapterJournalRecoveryTest` |
| `persistence/journal/ChapterJournalLagCrashTest.kt` | `eu.kanade.translation.persistence.journal.ChapterJournalLagCrashTest` |
| `persistence/journal/ChapterJournalArtifactResolverTest.kt` | `eu.kanade.translation.persistence.journal.ChapterJournalArtifactResolverTest` |
| `pipeline/batch/BatchStaleTranslationPublicationTest.kt` | `eu.kanade.translation.pipeline.batch.BatchStaleTranslationPublicationTest` |
| `pipeline/batch/BatchResumeAndDisplayContractTest.kt` | `eu.kanade.translation.pipeline.batch.BatchResumeAndDisplayContractTest` |
| `pipeline/batch/OverlapSchedulerTest.kt` | `eu.kanade.translation.pipeline.batch.OverlapSchedulerTest` |
| `pipeline/batch/BatchLeaseFlipHealTest.kt` | `eu.kanade.translation.pipeline.batch.BatchLeaseFlipHealTest` |
| `pipeline/batch/BatchWriteGateHealTest.kt` | `eu.kanade.translation.pipeline.batch.BatchWriteGateHealTest` |
| `pipeline/batch/DisplayTailDrainTest.kt` | `eu.kanade.translation.pipeline.batch.DisplayTailDrainTest` |
| `pipeline/adaptive/AdaptiveKnobControllerTest.kt` | `eu.kanade.translation.pipeline.adaptive.AdaptiveKnobControllerTest` |
| `pipeline/memory/TranslationMemoryBudgetTest.kt` | `eu.kanade.translation.pipeline.memory.TranslationMemoryBudgetTest` |
| `pipeline/PostOcrStageSemanticsTest.kt` | `eu.kanade.translation.pipeline.PostOcrStageSemanticsTest` |
| `model/PageDisplayProjectionTest.kt` | `eu.kanade.translation.model.PageDisplayProjectionTest` |
| `model/PageDisplayReadinessTest.kt` | `eu.kanade.translation.model.PageDisplayReadinessTest` |
| `model/PageTranslationStateTest.kt` | `eu.kanade.translation.model.PageTranslationStateTest` |
| `presentation/P5OutcomeProjectionTest.kt` | `eu.kanade.translation.presentation.P5OutcomeProjectionTest` |
| `presentation/P5CopyAndAccessibilityTest.kt` | `eu.kanade.translation.presentation.P5CopyAndAccessibilityTest` |
| `presentation/P5VisibilityPrecedenceTest.kt` | `eu.kanade.translation.presentation.P5VisibilityPrecedenceTest` |
| `engines/inpainting/PageInpaintingEngineTest.kt` | `eu.kanade.translation.engines.inpainting.PageInpaintingEngineTest` |
| `engines/translator/retry/AiTranslationRetryPlannerSinglePageTest.kt` | `eu.kanade.translation.engines.translator.retry.AiTranslationRetryPlannerSinglePageTest` |
| `engines/translator/retry/AiChunkAdaptiveRetryTest.kt` | `eu.kanade.translation.engines.translator.retry.AiChunkAdaptiveRetryTest` |

testDebug set (separate source set, runs in both flavor suites): `app/src/testDebug/java/eu/kanade/translation/diagnostics/TranslationDebugMeasurementTest.kt` = `eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest` — currently the failing file-sink assertion.

# Verified gaps (absence/explicitness checked, not runtime-proven)

1. **Detached inpaint job: absence of explicit references checked.** A grep over all 318 unit-test sources finds zero references to `inpaintJob` or the detached `CoroutineScope(Dispatchers.Default).async` (SinglePageOnnxPhase.kt:1182). This rules out a test that names the detached job; it does NOT prove absence of indirect behavioral coverage (boundary bitmap flush/recycle is covered by `PreparedPageRuntimeBoundaryTest` > `lazy auto boundary flushes before eager cleaned bitmap recycle` and > `production helper nulls cleaned bitmap on source before returning`). Cancellation between async launch and await is not exercised by any located test.
2. **Early color pass consumer unproven.** `SinglePageOnnxPhase.kt:1203` `recomputeFor` runs inside the detached child; no located test establishes a required downstream consumer of that early pass (estimator policy itself: `RenderColorEstimatorTest`, `RenderColorEstimatorDedupTest` — pure functions only).
3. **Physical native cancellation/late-completion/drain**: no unit test and no instrumented test targets this race; the six androidTest classes are measurement utilities and do not supply this coverage. Device gate pending by user choice.
4. **Auto ahead-window wiring**: the scheduler-side producer of the stream-ready poke is not traced to a dedicated test (unverified wiring).
5. **Six failing methods** are recorded with owned triage in [failure-triage](../build-and-tests/failure-triage): 1 sink-format assertion, 2 store reopen/promotion contract assertions, 2 JUnit `@TempDir` close failures (test bodies pass), 1 full-suite-only protocol parking failure. No Phase 0 repair authorized.

# Status

Phase 0 static inventory **complete** for this agent's scope: behavior rows, impact exercises, selectors, and failure ledger cross-referenced. Runtime truth remains with the preserved build ledger; device distributions pending by user choice; ArtifactOpenProbe contracts gate Phase 3. No repository file changed; working tree still clean except the preserved untracked report.
