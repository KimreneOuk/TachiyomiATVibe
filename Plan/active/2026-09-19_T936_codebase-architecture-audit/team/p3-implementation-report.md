# T936 Phase 3 implementation report

Date: 2026-09-20
Branch: t936/phase3-coexistence-pipeline
Scope: P3-01 session admission, P3-02 quiescent transition, and P3-03 cross-origin machinery removal.

## Result

Phase 3 is implemented. Batch and reader work now share one explicit session owner; a reader switch from a running batch waits for real translator unwind before admission; and the cross-origin attach/defer/rescan paths proven unreachable by that gate are removed. Same-origin batch overlap, guarded writes, generation fencing, and durability cleanup remain intact.

The branch contains the P3-03 production/test deletion commit plus two narrowly-scoped test-fixture follow-ups. The follow-ups initialize TranslationSessionCoordinator in Unsafe.allocateInstance manager fixtures, which bypass Kotlin field initializers; they do not alter production behavior or weaken assertions.

## P3-01 — session admission state machine

- Added TranslationSessionCoordinator with IDLE, BATCH_SESSION, PAUSING, and READER_SESSION states, serialized state publication, typed admission results, and explicit finishSession symmetry.
- Added the tests-first TranslationSessionCoordinatorTest contract before the implementation. It covers idle-to-batch/reader admission, duplicate batch idempotence, reader rejection while batch owns the session, teardown symmetry, no-bypass scheduler gating, and retry re-admission.
- Routed the three manual reader translation entry points, the live auto-window path, reader lifecycle/re-arm paths, batch start/pause/cancel/requeue/replace/multi-chapter entry points, and reader teardown through the coordinator. The dormant requestAutoWindow path remains quarantined.
- Kept the lower-level lease/attach machinery functional until P3-03, so this commit introduced admission without an intermediate behavior gap.

## P3-02 — quiescent batch-to-reader transition

- Parameterized ChapterTranslator.cancelTranslatorJobAndJoin(timeoutMs) while preserving the existing two-second default for delete/rekey/reset callers.
- Added the BATCH_SESSION → PAUSING → READER_SESSION transition. The join runs from the IO/application scope outside manager, reader-teardown, store, and artifact locks.
- A three-second join timeout is explicitly not quiescence: the coordinator stays PAUSING, logs the attempt, retries at a two-second cadence, and rejects reader work until the old coroutine has really unwound and its finally/NonCancellable cleanup has completed.
- Added the UI switch/confirmation path and honest typed pause/switch truth. A dismissed switch leaves batch ownership unchanged.
- TranslationSessionCoordinatorQuiescenceTest covers successful admission, timeout/retry, checkpoint reuse, durability-preserving teardown, and reader teardown during a switch. Existing durability assertions remain unchanged.

## P3-03 — cross-origin removal

### Production changes

- Split RollingAutoCoordinator.PROVIDER_DRAIN_GRACE_MS from the removed attach timeout budget; D6 drain and D7 epoch assertions retain the same values and now reference the provider-drain constant.
- Removed TranslationPipeline.ATTACH_TIMEOUT_MS, attachToOwnerTerminal, SinglePageOutcome.AttachedUnresolved, the denied-lease-to-attach redirect, and the attach-wait scheduler branches. Denied reader acquisition now produces a typed rejection at the session boundary.
- Removed deferredPages, ocrDeferred, and the ChapterProfileBatchCoordinator S8 foreign-owner gap rescan. OCR preflight publication, checkpoint reuse, source identity checks, watchdogs, failure ledger, and incomplete-corpus pause behavior remain.
- Removed the obsolete page-lease release-waiter map and completion helper. Same-origin attach/detach, MANUAL-evicts-AUTO policy, generation/version fencing, NonCancellable release, origin teardown, and BatchWriteGate are retained.
- Updated UI truth and reader outcome tests so a batch-owned reader request reports the session switch/pause state rather than an attach-wait state.

The ticket proposed deleting the generic foreign-owner denial in PageStageLeaseTable. That denial remains as a fail-closed defense-in-depth check because the low-level lease and BatchWriteGate contracts still require one owner proof even though the session coordinator makes cross-session execution unreachable. No cross-session observer or attach path remains; same-origin batch translation/inpainting still needs the table token and generation behavior.

### Test disposition ledger

| Test | Disposition | Preserved/replaced behavior |
| --- | --- | --- |
| D2ManualBatchInterleavingTest | Deleted (220 LOC) | Batch/manual interleaving is removed by session admission; admission and quiescence contracts cover the switch/rejection behavior. |
| D3ReaderOwnedPageAcrossBatchTest | Deleted (166 LOC) | Reader-owned-page cross-origin choreography is impossible under mutual exclusion. Durable OCR checkpoint reuse remains covered by TranslationSessionCoordinatorQuiescenceTest and OcrCheckpointRestartReuseTest. |
| D6ForegroundFairnessTest | Rewritten as reader-only provider-window coverage | Provider priority, foreground window, and typed-pause assertions remain; batch/reader interleaving assertions are gone with the cross-origin behavior. |
| D9 attach-wait case | Deleted from D9AttemptLedgerTest | Attach-wait choreography is gone; attempt ledger and death-cycle coverage remain. |
| ManualAttachOnBatchTraceTest | Deleted (170 LOC) | The session-admission trace is asserted by P3-01 coordinator tests. |
| D6DrainNotCancelTest, D7EngineEpochStopRaceTest | Mechanical constant update | Drain grace and epoch-stop invariants are unchanged. |
| StandardPipelineCoexistenceTest | Minimal event/comment update | Fresh standard-lane ordering and completion assertions remain. |

No assertions were weakened in the retained artifact, lease, write-gate, checkpoint, or durability suites. BatchWriteGate, OverlapScheduler, same-origin attach/detach, BatchRenderJoin, generation fencing, T930/T934 cleanup, and translator/analysis were not removed or behaviorally simplified.

## Verification

Environment: JAVA_HOME=C:\Program Files\Android\Android Studio\jbr; all Gradle runs used --no-parallel --max-workers=1. The repository has dev and standard product flavors, so flavor-qualified tasks were used instead of ambiguous generic Debug task names.

Green commands:

    .\gradlew.bat :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin --no-parallel --max-workers=1

Both flavor unit-test compilation tasks succeeded.

    .\gradlew.bat :app:testDevDebugUnitTest --no-parallel --max-workers=1
    .\gradlew.bat :app:testStandardDebugUnitTest --no-parallel --max-workers=1

Final full flavor runs each completed all 2,025 tests successfully (Dev: 8m28s; Standard: 3m39s). Earlier combined/separate full attempts exposed only the load flakes recorded below; no isolation run reproduced them.

Focused P3-03 gate (green):

    .\gradlew.bat :app:compileDevDebugKotlin :app:compileDevDebugUnitTestKotlin --no-parallel --max-workers=1
    .\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.coexistence.D1OriginPriorityTest --tests eu.kanade.translation.coexistence.D5GlossaryAwareReuseTest --tests eu.kanade.translation.coexistence.D6DrainNotCancelTest --tests eu.kanade.translation.coexistence.D6ForegroundFairnessTest --tests eu.kanade.translation.coexistence.D7EngineEpochStopRaceTest --tests eu.kanade.translation.coexistence.D8StallWatchdogTest --tests eu.kanade.translation.coexistence.D9AttemptLedgerTest --tests eu.kanade.translation.coexistence.D10PartialDownloadAdmissionTest --tests eu.kanade.translation.coexistence.D11PermitFreeCommitTest --tests eu.kanade.translation.coexistence.StandardPipelineCoexistenceTest --tests eu.kanade.translation.pipeline.batch.StandardPipelineCoordinatorTest --tests eu.kanade.translation.pipeline.batch.BatchLeaseFlipHealTest --tests eu.kanade.translation.pipeline.batch.BatchWriteGateHealTest --tests eu.kanade.translation.OcrCheckpointRestartReuseTest --tests eu.kanade.translation.store.ChapterTranslationStorePhase3Test --no-parallel --max-workers=1

Dev APK assembly succeeded:

    .\gradlew.bat :app:assembleDevDebug --no-parallel --max-workers=1

The build completed successfully in 10m32s. APK inspection used jar tf over the five APKs under app/build/outputs/apk/dev/debug/, including app-dev-universal-debug.apk:

- Absent: assets/models/segmentation/best_int8.onnx.
- Absent: all moved OCR documentation/metadata (README.md, inference.yml, and .gitattributes) under assets/models/ocr/.
- Present: assets/models/segmentation/manga109_bubble_int8.onnx.
- Present: both assets/models/ocr/paddle-v6-small/inference.onnx and assets/models/ocr/paddle-v6-small/det/inference.onnx.

The final source checks were green:

    git grep -n -I -E 'attachToOwnerTerminal|AttachedUnresolved|deferredPages|ocrDeferred|ATTACH_TIMEOUT_MS|leaseReleaseWaiters|completeLeaseReleaseWaitersLocked' -- app/src

Result: no matches. A broad S8 search has one unrelated drawable path-data match in app/src/main/res/drawable/ic_extension_24dp.xml; no batch S8 implementation remains. git diff --check was clean. The temporary root app/google-services.json required by flavor builds was removed and is not tracked.

## Flake ledger

These failures occurred only in full-suite/load runs and passed three consecutive isolation retries each. They match the campaign's pre-existing end-to-end translation/UI timing-flake pattern; none failed in isolation or in a legacy/storage suite.

| Test | Full-run failure signature and run | Isolation outcome |
| --- | --- | --- |
| eu.kanade.translation.coexistence.BatchDispatchResumeWiringTest | IllegalStateException: run 1 never reached ChapterRunState.COMPLETE within 10000ms ... state=TRANSLATE with a load-induced typed pause; occurred in full Dev/Standard attempts. | :app:testDevDebugUnitTest --tests eu.kanade.translation.coexistence.BatchDispatchResumeWiringTest passed 3/3. |
| eu.kanade.translation.coexistence.StandardPipelineCoexistenceTest | expected:<COMPLETE> but was:<TRANSLATE> at the standard-lane completion assertion during a full Standard run. | :app:testStandardDebugUnitTest --tests eu.kanade.translation.coexistence.StandardPipelineCoexistenceTest passed 3/3. |
| eu.kanade.translation.coexistence.StandardLaneMultiPageCompletionTest | expected:<TRANSLATED> but was:<ERROR> during full Standard and Dev runs. | :app:testStandardDebugUnitTest --tests eu.kanade.translation.coexistence.StandardLaneMultiPageCompletionTest passed 3/3. |
| eu.kanade.translation.pipeline.batch.ProfileEnvelopePromptEnrichmentTest | JUnit TempDirectory cleanup IOException: Failed to delete temp directory for a generated artifact JSON; no product assertion failed. | :app:testDevDebugUnitTest --tests eu.kanade.translation.pipeline.batch.ProfileEnvelopePromptEnrichmentTest passed 3/3. |

## Locking and lifecycle note

Admission is decided by TranslationSessionCoordinator before manager/scheduler work starts. The quiescent join runs outside manager, reader, store, and artifact locks. Existing internal ordering remains: facade/session admission precedes page-stage lease work; page lease monitor/mutex and per-name document/open locks retain their existing ownership and do not acquire the session gate during document I/O. NonCancellable teardown flushes and generation/owner fencing remain in place.

## Commits

- 41c61b6 — tests-first session admission contract.
- fe241ca — session admission routing.
- ca0f507 — quiescent batch-to-reader transition.
- 87455f0 — cross-origin attach/defer/rescan removal.
- 0bc4419 — initialize session coordinator in manager fixtures that bypass constructors.
- c06d5de — initialize session coordinator in the generation-fence fixture.

The report is committed separately as docs(plan): T936 phase 3 implementation report.

