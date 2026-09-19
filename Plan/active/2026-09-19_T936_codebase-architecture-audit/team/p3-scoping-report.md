# T936 Phase 3 pre-code scoping investigation

Date: 2026-09-20
Branch: `t936/phase3-coexistence-pipeline`
Baseline: `main` at `5bcb592` (`docs(plan): T936 phase 2 review report + closure (PASS WITH NOTES)`)
Scope: read-only investigation. No production files or tests were changed.

## Executive summary

The session-mutual-exclusion direction is viable, but the deletion boundary is narrower than the audit/spec suggests.

1. The current batch path already has a real chapter-wide OCR barrier. `ChapterProfileBatchCoordinator.runPass1` performs OCR/checkpoint work for every ordered page, runs one bounded S8 gap rescan, publishes `OCR_PREFLIGHT`, and only then enters analysis/profile/translation. The requested “Pass 1 completes all OCR+segments before Pass 2” is therefore mostly a naming/decomposition change, not a new scheduling invariant.
2. There is no fixed five-page translation scheduler in the current main branch. The standard lane translates one page at a time; the AI profile lane dispatches one planned envelope at a time; `StreamingChunkPlanner` is token-budget driven. The literal value `5` belongs to durable group-commit staging (`MAX_STAGED_PAGES = 5`), not a five-page rolling translation window.
3. Session exclusion can remove cross-origin batch/reader arbitration, reader-to-batch observer attachment, and the batch deferral/rescan machinery that exists solely for a competing reader owner. It cannot remove the whole lease system or `BatchWriteGate`: same-origin translation/inpaint overlap still shares page tokens, guarded writes, generation fencing, and T934 owner-proof healing.
4. Pause is currently non-blocking. `ChapterTranslator.cancelTranslatorJobAndJoin()` exists only as a delete/rekey/reset safety join, has a fixed two-second bound, and has no timeout parameter. A three-second session join must be added at a coordinator seam, outside manager/reader locks. A timeout must not be treated as quiescence while the old batch coroutine can still execute native work or publish late writes.
5. The audit's `translator/analysis` premise is stale: current main has six files and 1,872 LOC, not seven files/~1,800, and the package is live in the AI profile lane with extensive production and test references. The audit's “10–20 second attach” number is also stale: `ATTACH_TIMEOUT_MS` is 210,000 ms (90 s ONNX + 120 s single-page).

Recommended ticket shape: first introduce and test the session admission state machine and an explicit quiescent batch transition; then simplify only the cross-origin branches proven unreachable under that gate; keep the internal batch lease/write contracts and split/decompose the live pipeline by phase afterward.

## 1. Session/admission landscape

### 1.1 Page-stage lease table and current origin matrix

`app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt` is 284 LOC.

| Span | Current behavior | Session-exclusion disposition |
| --- | --- | --- |
| 73–79 | Contract says one origin owns a page/stage; a reader request on a batch-owned page observes/attaches rather than opening a competing writer. Leases carry generation/page-version identity. | The cross-session premise becomes unnecessary once admission guarantees no BATCH+READER overlap for the same active session. Keep generation/page-version fencing for resume/rekey/stale writers. |
| 81–149 | `tryAcquirePageStageLease` is the D1 matrix. MANUAL evicts AUTO (96–98); any other foreign-origin request is denied (99–103); same-origin acquisition increments `attaches` and returns the existing token (105–125); a fresh grant mints a new token (127–147). | The MANUAL-vs-BATCH denial/attach branch can disappear after a hard session gate. MANUAL-vs-AUTO is a separate reader-session policy and should only be removed if the new reader state machine serializes manual and auto admission too. Same-origin attach remains required by the current overlap design. |
| 151–162 | Plain release is `NonCancellable`, mutex-protected, origin checked, and wakes release waiters. | Keep the teardown/fencing semantics. |
| 164–194 | `releasePageStageLeaseIfUnattached` is T934 R1.2's attach-aware release; it avoids removing a live same-origin sibling's record. | Keep while translation and inpainting can overlap. It is not a batch/reader feature. |
| 196–231 | `detachPageStageLeaseIfAttached` undoes a sibling attach without deleting the live writer record. | Keep while the overlap scheduler can observe a same-origin stage and attach speculatively. |
| 233–266 | Candidate cancellation and origin-wide release used by page cancellation and batch teardown. | Keep. Mutual exclusion does not remove cancellation, generation cleanup, or `NonCancellable` release. |
| 268–270 | `pageLeaseOwner` is a lock-free reader of current ownership. | Keep until all readers are routed through the session facade and no diagnostic/status projection needs it. |
| 272–283 | Release-waiter map completion is explicitly a T917 D3 defer/rescan remnant. The only waiter producer (`awaitPageLeaseRelease`) was deleted; current release paths complete no live waiter. | This is the cleanest lease-table deletion candidate, after the remaining D3/defer consumers are removed. |

The important boundary is that “one high-level session at a time” does not mean “one batch writer at a time.” The batch itself still has translation and inpainting components that share a BATCH page record. Removing `releasePageStageLeaseIfUnattached` or `detachPageStageLeaseIfAttached` together with the foreign-origin branches would reintroduce the T934 T1→T2 token-flip failure that `BatchLeaseFlipHealTest` protects.

### 1.2 TranslationPipeline admission and owner attachment

`app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` is 1,502 LOC.

- Timeout constants are at 115–151: `SINGLE_PAGE_TIMEOUT_MS = 120_000`, `ONNX_PHASE_TIMEOUT_MS = 90_000`, and `ATTACH_TIMEOUT_MS = ONNX_PHASE_TIMEOUT_MS + SINGLE_PAGE_TIMEOUT_MS` (210,000 ms).
- `translateSinglePage` and the stream variant acquire a READER lease at 399–421 and 438–485. A denied lease enters `attachToOwnerTerminal` instead of returning a simple session denial.
- `attachToOwnerTerminal` is 733–793. It reads the owner and waits under `withTimeoutOrNull(ATTACH_TIMEOUT_MS)` (752) for a terminal store state; timeout/cancellation produces `AttachedUnresolved`.
- `acquireReaderPageLease` and release helpers are 795–823. The comments preserve the D1 matrix and the MANUAL-on-BATCH attach behavior.
- Batch entry remains `translateBatch` at 1,280–1,289.

Under a correct session gate, `attachToOwnerTerminal`, `AttachedUnresolved`, and the MANUAL-on-BATCH observer path can be deleted or replaced by a typed `BatchSessionActive` admission result. This is not a local delete: `ATTACH_TIMEOUT_MS` is also used as `RollingAutoCoordinator.PROVIDER_DRAIN_GRACE_MS` at `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt:1353–1359`, and is asserted by `D6DrainNotCancelTest.kt:189–204` and `D7EngineEpochStopRaceTest.kt:242–256`. First split the auto-drain budget into its own constant; then remove the attach-specific constant and tests.

### 1.3 BatchWriteGate is not merely cross-origin arbitration

`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchWriteGate.kt` is 337 LOC. The class and identity contract are 61–82; `guardedBatchUpdate` is 101–193.

It does all of the following:

- carries generation, page-version, lease-token, candidate-generation, dependency-fingerprint, and artifact-page-version preconditions;
- refreshes a stale same-BATCH identity and retries once (123–141);
- performs the T934 owner-proof BATCH re-acquire on a token-change rejection (141–184), refusing a foreign owner and refusing a different run identity;
- persists durable AI failures and OOM-recovery updates (216–335).

The foreign-owner refusal branch is a candidate for simplification once BATCH and READER cannot overlap. The guarded writes, identity map, same-BATCH refresh, generation checks, durable-failure recording, and owner-proof behavior remain entangled with T930/T934 durability and must survive. The class is injected into `BatchChapterTranslator`, `BatchLaneWorkers`, `BatchRenderJoin`, and `ProfileEnvelopeExecutor`; its focused tests are `BatchLeaseFlipHealTest.kt` (351 LOC) and `BatchWriteGateHealTest.kt` (153 LOC).

### 1.4 Deferred pages and the in-pass rescan

There are two coupled pieces:

- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt` (1,209 LOC), 547–569: creates the `deferredPages` map and listener. `ocrDeferred` records a foreign owner.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt` (1,211 LOC), 326–341: BATCH OCR admission records a denial and returns no page reference.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt` (4,511 LOC), 724–804: S8 performs a bounded gap rescan, adopting a completed checkpoint or re-running OCR, then 806–849 publishes `OCR_PREFLIGHT` and pauses if the corpus is still incomplete.

The cross-origin reason for this machinery is explicit in the comments: a reader-owned page must not be preempted, and the batch must not translate against an incomplete corpus. With an admission gate that prevents a reader session from starting until a batch session has quiesced, `deferredPages`, `ocrDeferred`, and the S8 rescan can be removed. The coordinator's checkpoint reuse, source identity checks, per-page watchdog, failure ledger, and durable `OCR_PREFLIGHT` record cannot be removed with them.

### 1.5 Same-origin sibling attach/detach

The current overlap scheduler is an independent entanglement:

- `app/src/main/java/eu/kanade/translation/pipeline/batch/OverlapScheduler.kt` is 765 LOC.
- `tryClaimInpaintOwnership` is 579–599. A BATCH acquire can return an existing BATCH stage; the scheduler calls `detachPageStageLeaseIfAttached` and defers instead of deleting the live writer record.
- `inpaintOne` is 601–755. It protects actual inpaint with `inpaintMutex` (602), keeps the page's write slot through commit, and releases only after the attempt settles (743–748).
- Candidate selection is 502–536 and intentionally admits OCR-final pages regardless of translation status (511–521), which is how inpainting overlaps provider translation.

Session mutual exclusion removes a foreign reader owner from this path, but it does not remove the same-origin translation/inpaint race. The sibling attach/detach/refcount contract must remain unless Phase 3 deliberately gives up Lane B overlap and serializes all inpaint after translation.

## 2. Pause/cancel mechanics and required session gates

### 2.1 Current ChapterTranslator behavior

`app/src/main/java/eu/kanade/translation/ChapterTranslator.kt` is 961 LOC.

- `stop` is 353–377, `pause` is 391–396, and `clearQueue` is 425–428. All call the private non-blocking `cancelTranslatorJob`.
- `cancelTranslatorJob` is 554–557: it calls `translationJob?.cancel()` and immediately sets `translationJob = null`.
- The actual chapter coroutine claims `inFlightChapterIds` at 497–516 and releases the claim only in its `finally` at 543–551, after the batch coroutine fully unwinds. This is why a new same-chapter run can still wait after the UI believes pause/stop completed.
- `cancelTranslatorJobAndJoin` is 572–583. It has no parameter; it uses the fixed `BATCH_JOIN_TIMEOUT_MS = 2_000L` (line 90), was written for delete/rekey/reset safety, and logs that it proceeds after timeout under the defunct-store/native quarantine protections.
- `TranslationManager.rekeyTranslationForCompletedDownload` calls the join through `runBlocking` at 1,330. `ChapterDataResetController` calls it at 142 and 246. These are the only production join call sites in current main.

There is no existing `cancelTranslatorJobAndJoin(timeoutMs = 3000)` for the batch-to-reader transition. `pauseTranslation` at `TranslationManager.kt:733–735` only calls `translator.pause()`.

### 2.2 Where a session join belongs

The new join belongs in a session-owned coordinator, immediately after the state machine marks the batch `PAUSING` and stops new batch scheduling, and before it publishes `READER_SESSION` admission. It must run outside `TranslationManager`'s request mutation lock, `ReaderTeardownCoordinator.readerTeardownMutex`, and any store/artifact mutex. A safe ordering is:

1. Session coordinator atomically changes `BATCH_SESSION` → `PAUSING` and rejects new reader work while the transition is in progress.
2. Call the non-blocking pause/stop operation so no new chapter work is scheduled.
3. Call `cancelTranslatorJobAndJoin(timeoutMs = 3000)` from an IO/application coroutine, not while holding a manager or reader mutex.
4. Preserve the existing `NonCancellable` store flush and lease cleanup in batch teardown.
5. Only after the old batch has joined and released its leases publish `READER_SESSION` and admit the queued reader request.

The timeout needs an explicit policy. A timed-out native call is not quiescent: ONNX/JNI or bitmap decode can remain in native code until it returns, and the chapter coroutine's `finally` may not yet have released the lease. Therefore a three-second timeout may return control to the UI, but it must not silently claim that the old session is safe. The state machine should either remain `PAUSING`/deny reader work until actual unwind, or enter a separately specified quarantined state that prevents the new reader session from touching the old store/engines. “Timeout then immediately `READER_SESSION`” would preserve the current race under a new name.

### 2.3 Exact UI and public entry points to gate

The current UI is not centralized behind one admission function. These are the gates a session state machine must cover:

| Surface | Current call sites | Required session behavior |
| --- | --- | --- |
| Reader manual page request | `ReaderViewModel.kt:2214–2348`; three direct `translationScheduler.translatePage` calls at 2306, 2336, 2346; scheduler entry `TranslationScheduler.kt:663`. | If `BATCH_SESSION`, show/return the explicit switch decision; do not enter the old lease-denied/attach observer. If allowed, await the quiescent transition before dispatch. |
| Reader auto window | `ReaderViewModel.kt:1284–1342`, update call at 1329; `TranslationManager.updateAutoWindow` 1574–1603; `TranslationScheduler.updateAutoWindow` 186–255. | Same session admission as manual. The manager currently suppresses auto when `isBatchTranslationRetained` is true, but this is a chapter-retention check, not a global session transition. |
| Legacy auto request/reconcile | `TranslationManager.requestAutoWindow` 1543–1560; `reconcileAutoWindow` 1609–1613; scheduler `requestAutoWindow` 309 is explicitly deprecated/dormant. | Do not build a new state machine around the dormant request path. Gate the live `updateAutoWindow` path and keep the dormant path quarantined or delete it separately. |
| Reader lifecycle | `ReaderViewModel.onCleared` 935–957 (`requestReaderStop`), chapter switch 2034–2059 (`awaitReaderStop`), background 2531–2546 (`stopReaderTranslations`), stop-all 2395–2421 (`cancelAllPageTranslationsOffMain`), translation toggle-off 661–700. | These transitions must release/stop the active READER session and coordinate with a pending batch switch; they must not race a batch session's join. |
| Reader retry/resume | `ReaderViewModel.kt:2423–2437` calls `startTranslation`; 2564–2574 re-arms auto. | Re-enter only the correct session; do not use generic `startTranslation` to bypass admission. |
| Batch pause/start | `MangaScreenModel.kt:907–913` → `TranslationManager.pauseTranslation/startTranslation`; foreground service uses `TranslationForegroundService.kt:87` and `:160`. | Pause must become a session transition with an observable `PAUSING`/quiescent outcome, not just a mutable queue status. |
| Batch chapter actions | `MangaScreenModel.kt:945–1028` (start/cancel/requeue), `:1269–1287` (resume), `:1300–1322` (replace/cancel-running/new start), `:1395` (multi-chapter admission). Manager implementations are `translateChapter` 793–852, `cancelRunningChapterForReplace` 1009–1023, `cancelQueuedTranslation` 1782–1799. | Every path that can cancel, replace, requeue, or start a batch must consult the same session owner. A replacement cannot start a new reader/batch session while the old chapter coroutine is still unwinding. |
| Reader teardown facade | `ReaderTeardownCoordinator.kt:67–103`, `:105–131`, `:139–175`; manager delegates at 690–694 and 1838–1842. | This is the natural reader-side seam, but it must delegate admission to the session coordinator rather than acquire a separate hidden state. |

Current asymmetry is important: auto updates consult `isBatchTranslationRetained`, while reader manual page calls can go directly to `TranslationScheduler.translatePage`, reach the pipeline, and then enter `attachToOwnerTerminal` on a batch-owned page. The state machine must close that direct path.

## 3. `translator/analysis` package inventory

The relevant package is `app/src/main/java/eu/kanade/translation/translator/analysis` (not `translation/analysis`). It has six files and 1,872 LOC:

| File | LOC | Public/internal surface and role |
| --- | ---: | --- |
| `AnalysisChunkExecutor.kt` | 361 | `AnalysisTextTransport`, `AnalysisChunkAttempt`, `AnalysisChunkExecutor`, `AnalysisChunkRunner`, `AnalysisChunkRunOutcome`, `AnalyzerProvenanceFactory`, evidence/request helpers. |
| `AnalysisEngineTransport.kt` | 246 | `AnalysisEngineTransport`; token/budget constants and typed engine completion transport. |
| `AnalysisRequestBuilder.kt` | 179 | `AnalysisRequestBuilder`, request DTOs (`RequestPage`, `RequestBlock`, `AnalysisChunkRequest`). |
| `AnalysisResponseValidator.kt` | 743 | `AnalysisResponseValidator`, `AnalysisResponseOutcome`, `ValidatedAnalysisResponse`, parser and schema validation. |
| `AnalysisWire.kt` | 135 | `AnalysisRunIdentity`, `AnalysisEvidenceTexts`, validated term/entity/relationship/scene DTOs and coverage types. |
| `GlossarySynthesizer.kt` | 208 | `GlossarySynthesisOutcome`, glossary DTOs, `GlossarySynthesizer`, `AnalysisEngineGlossarySynthesizer`, parser. |

Production references are live:

- `BatchChapterTranslator.kt:34–36`, `:776–795`, and `:836–841` construct the engine-backed `AnalysisChunkExecutor`/`AnalysisEngineTransport` runner and glossary synthesizer for the AI profile lane.
- `ChapterProfileBatchCoordinator.kt:63–72`, constructor seams `:247–259`, analysis execution `:1069–1279`, and glossary synthesis `:1565–1631` consume the typed API and persist its results.
- The standard lane intentionally bypasses the package (`BatchChapterTranslator.kt:847–855`): it has no analysis runner, contextual translator, profile, envelope, or glossary work. That is a lane choice, not evidence that the package is dead.

Tests directly pin it in `AnalysisChunkValidationTest.kt`, `AnalysisEngineTransportTest.kt`, and `EightKilobyteComplianceTest.kt`, and through fakes/seams in `ChapterAnalysisPhaseCoordinatorTest.kt` (508 LOC), `ChapterProfileFreezeCoordinatorTest.kt`, `DisplayTailDrainTest.kt`, `ProfileEnvelopeDispatchTest.kt`, `ProfileEnvelopePromptEnrichmentTest.kt`, `Stage7FinalizeCoordinatorTest.kt`, `Stage7FinalizeResumeCoordinatorTest.kt`, and `StrandedPageTerminalRoutingTest.kt`.

Verdict: live and bypassed only by the standard lane. It is not a clean deletion candidate. `AnalysisResponseValidator.kt` is the largest file, but its 743 LOC are exercised by typed response/phase tests. If Phase 3 decomposes the monolith, retain these seams and move them only with tests.

## 4. Current pass structure and the proposed Pass 1/Pass 2 split

### 4.1 Existing phase sequence

The relevant current LOC are:

- `ChapterProfileBatchCoordinator.kt`: 4,511 LOC; `runPass1` 366–930.
- `BatchChapterTranslator.kt`: 1,209 LOC; dispatch/build shell 638–908 and post-pass handling 944–1075.
- `BatchLaneWorkers.kt`: 1,211 LOC; native/translator lane adapters 306–314 and 320–865 / 867–1,210.
- `OverlapScheduler.kt`: 765 LOC; continuous overlap loop 254–331, serial drain 359–391, candidate/claim/inpaint 484–755.
- `BatchRenderJoin.kt`: 648 LOC; render join `tryRender` 120–346 and persisted layout publication 607–647.

The current fresh chapter sequence is:

1. `runPass1` checks artifact authority and run-resume gates (366–411), then loops pages in order (517–722).
2. Each OCR result is checkpointed before its BATCH lease is released (637–720). The checkpoint helper is 3,890–3,945 and calls `store.checkpointOcr` at 3,939 with `OcrCheckpointMode.CLOSE`.
3. If any page was denied/deferred or otherwise has no checkpoint, S8 runs a bounded in-pass gap rescan (724–804). This is where reader contention can cause a second OCR attempt.
4. The coordinator flushes and publishes `OCR_PREFLIGHT` at 806–835. If the corpus is incomplete, it returns `PAUSED` at 836–849; no paid translation starts against a partial corpus.
5. AI runs analysis plan/chunks/profile reconcile/freeze (`runAnalysisPhase` 950 onward; analysis chunk execution 1,069–1,279; profile/glossary 1,298–1,631), then enters envelope planning/translation at 1,656 onward.
6. Standard bypasses AI analysis and enters `runStandardTranslateAndFinalize` at 2,243–2,488. It publishes TRANSLATE, loops ordered pages 2,318–2,449, and calls the standard seam once per page.
7. During translation, `OverlapScheduler` runs beside the serial translate loop (`runOverlapLoop` launched at 2,299–2,312). It can inpaint any OCR-final candidate, including one whose translation is still pending (candidate rule 502–536). `inpaintMutex` serializes actual native inpaint, so “concurrent inpainting” means overlap with translation, not multiple simultaneous inpaint calls.
8. Finalization drains remaining inpaint serially (`drainSerial` 373–391), repairs order-inverted render terminals, publishes layouts through `BatchRenderJoin`, reconciles stranded pages, and flushes. `BatchRenderJoin` is an idempotent per-page join with a render mutex; the old signal/await render worker is already gone (file comment 50–63).

### 4.2 Is there already a Pass 1 / Pass 2 split?

Semantically, yes; structurally, no.

- The current OCR preflight is already a chapter-wide Pass 1 barrier for a fresh complete run. `checkpointOcr` fires after each page's OCR, but translation does not begin until the ordered OCR loop and S8 rescan finish, `OCR_PREFLIGHT` is published, and the AI/standard branch is selected.
- The public method is misleadingly named `runPass1`: it contains OCR, analysis/profile, envelope or standard translation, overlap scheduling, and finalization. There is no separate `runPass2` entry point.
- A proposed Pass 1 that means “all pages have durable OCR segments before any translation/inpaint” would mostly codify the existing barrier. Its observable divergence is removal of S8 reader-contention rescan and the elimination of the current overlap path's foreign-owner cases, not a new OCR algorithm.
- A proposed Pass 2 that means “Lane A rolling translation starts in five-page chunks while Lane B inpaints” is not what current main implements. The standard lane is serial per-page; the AI lane is serial per-envelope after a precomputed plan. Inpainting is overlap-admitted by OCR readiness and provider windows, not five-page batches.

### 4.3 Closest Lane A and Lane B analogues

**Lane A (rolling translation):**

- Standard: `runStandardTranslateAndFinalize`'s ordered per-page loop (2318–2348) and `BatchLaneWorkers.translatorWorker` (867–1,210).
- AI profile: `runEnvelopePlanAndTranslate` (1,656 onward) delegates to `ProfileEnvelopeExecutor`, whose contract at `ProfileEnvelopeExecutor.kt:50–95` and loop at `:198–316` is one provider envelope in flight, with live revalidation and re-planning.
- `StreamingChunkPlanner.kt:11–18`, `:100–179` can flush token-fitting chunks as pages arrive, but the current profile lane uses the durable envelope plan and the current standard lane does not use a fixed page count.
- The only literal five-page value is `GroupCommitConfiguration.MAX_STAGED_PAGES = 5` (`GroupCommitConfiguration.kt:12`), used for store mutation staging at `ChapterTranslationStore.kt:349–387` and `CommitPoint.BATCH_CHUNK`. It is not a translation-chunk contract.
- AI analysis chunks are explicitly capped at `AnalysisChunkResult.MAX_CORE_PAGES = 16` with two overlap pages (`AnalysisChunkResult.kt:70–72, 101–110, 153–154`).

**Lane B (concurrent inpainting/render):**

- `OverlapScheduler.runOverlapLoop` (254–331) drains candidates during provider windows and commit nudges; `nextInpaintCandidate` (502–536) requires OCR READY but does not require translation READY.
- `inpaintOne` (601–755) serializes native inpaint with `inpaintMutex`, maintains a BATCH identity, and releases only after durable commit/failure.
- `BatchRenderJoin.tryRender` joins translation and inpaint prerequisites per page; `publishPersistedLayoutForCompletedPage` (624–647) handles the decoupled persisted-layout publication. The final serial drain remains the fallback/settle point.

## 5. Test landscape: retainable invariants versus removed interleavings

The current coexistence directory has 11 D-named files (D4 is absent; D6 has two files):

| File | LOC | Current focus | Phase 3 disposition |
| --- | ---: | --- | --- |
| `D1OriginPriorityTest.kt` | 134 | MANUAL evicts AUTO and stale AUTO writes fail closed. | Not a batch/reader interleaving. Preserve or adapt to the reader-session MANUAL/AUTO policy unless the state machine explicitly serializes those origins too. |
| `D2ManualBatchInterleavingTest.kt` | 220 | Batch→manual wait/attach and manual→batch defer/rescan. | Delete/rewrite the interleaving choreography. Replace with session switch confirmation, pause/join, and rejection/queued intent assertions. |
| `D3ReaderOwnedPageAcrossBatchTest.kt` | 166 | Reader owns p1 while batch preflights p0/p1; batch pauses and follow-up run resumes. | Remove the concurrent ownership scenario. Keep the durable checkpoint/follow-up reuse assertions in a session-transition or resume test. |
| `D5GlossaryAwareReuseTest.kt` | 412 | Profile/glossary maturation and zero-paid-call reuse. | Survives; it is durable profile/checkpoint truth, not batch/reader coexistence. |
| `D6DrainNotCancelTest.kt` | 456 | AUTO window cancellation drains a provider call within a grace budget. | Survives. It is reader AUTO teardown, not batch pause, and is the reason `ATTACH_TIMEOUT_MS` cannot be deleted without splitting the budget constant. |
| `D6ForegroundFairnessTest.kt` | 270 | Batch and manual chapter share one provider window; manual priority and typed exhaustion. | Delete/rewrite the cross-session interleaving. A reader-only provider-window test can preserve governor semantics. |
| `D7EngineEpochStopRaceTest.kt` | 586 | AUTO stop/drain, engine borrow/close, epoch retry and honest second failure. | Survives as reader engine lifecycle coverage; remove only assertions specifically coupled to batch+reader overlap if later found. |
| `D8StallWatchdogTest.kt` | 233 | Parked native reader lane, stall rejection, timeout and re-admission. | Survives inside READER_SESSION. |
| `D9AttemptLedgerTest.kt` | 467 | Batch attempt ledger/death-cycle cap plus an attach-waiting manual case at 403–466. | Keep ledger/death-cycle tests; delete/rewrite the attach-waiting test (403–466) because `attachToOwnerTerminal` disappears. |
| `D10PartialDownloadAdmissionTest.kt` | 544 | Partial/unknown download truth and missing-page admission. | Survives; batch admission/durability is independent of coexistence. |
| `D11PermitFreeCommitTest.kt` | 191 | Next page admitted while prior cleaned-image publication is parked. | Survives if Lane A/B overlap remains; it is an internal batch pipeline contract, not reader coexistence. |

Additional named suites:

| File | LOC | Disposition |
| --- | ---: | --- |
| `ManualAttachOnBatchTraceTest.kt` | 170 | Rewrite/delete attach-state assertions; replace with session admission and typed switch/reject tracing. |
| `NormalMangaIsolationTest.kt` | 133 | Preserve the “no unintended work” invariant, but route it through the session gate rather than lease observation. |
| `StandardPipelineCoexistenceTest.kt` | 132 | Preserve full-OCR-before-translate ordering; update only if the explicit Pass 2 contract changes the observable event sequence. |
| `StandardPipelineCoordinatorTest.kt` | 902 | Preserve standard lane ordering, no-inpaint-during-first-translate, checkpoint reuse, gap safety, and finalize behavior. |
| `T918CancelledBatchRestartTest.kt` | 200 | Preserve cancellation/restart, durable OCR/checkpoint reuse, remainder-only paid work, and no re-decode. |
| `DisplayTailDrainTest.kt` | 531 | Preserve order-inverted display-tail repair and final COMPLETE truth. |
| `BatchLeaseFlipHealTest.kt` | 351 | Preserve; it protects same-origin sibling lease/token behavior that session exclusion does not remove. |
| `BatchWriteGateHealTest.kt` | 153 | Preserve same-BATCH identity heal and foreign-owner fail-closed behavior until the simplified gate proves the latter unreachable. |
| `T934ProjectorRebuildTruthTest.kt` | 188 | Preserve durable run-record projection/rebuild truth. |
| `T934WriteTimeDigestsTest.kt` | 328 | Preserve write-time identity/digest durability. |
| `MilestoneM2KillTheStallsTest.kt` | 153 | Queue steering and provider-governor eventization survive; adapt only if batch session queue semantics change. |
| `MilestoneM3OcrPushThroughTest.kt` | 98 | Lazy source fingerprints and unrelated slot formula survive. |
| `MilestoneM4ParallelWindowsTest.kt` | 167 | The budget and wave-lookahead tests are batch scheduler/resource tests; do not delete from the name alone. Re-evaluate whether two batch chapters remain admitted in one BATCH_SESSION. |
| `MilestoneM5LanProviderHeadroomTest.kt` | 294 | Provider headroom survives unless Phase 3 intentionally changes provider admission. |
| `MilestoneM6DurabilityAtScaleTest.kt` | 207 | Durability at scale survives. |
| `T934CompletionOracleTest.kt` | 213 | Presentation completion oracle survives as a projection contract. |
| `T934ReaderBarTruthTest.kt` | 228 | Reader truth projection survives; adapt only for new session-state messaging. |
| `T934RebuildTruthTransitionsTest.kt` | 210 | Rebuild transition truth survives. |

The safe deletion set is therefore not “all D tests.” The direct batch+reader contracts are D2, D3, D6ForegroundFairness, the attach portion of D9, and `ManualAttachOnBatchTraceTest`; D1 is a reader-origin policy decision, and the rest protect durable, reader-only, or internal-batch invariants.

## 6. Risk ledger and premise corrections

### Top five risks

1. **A bounded join is mistaken for quiescence.** Native ONNX/JNI and bitmap decode are not interruptible at the coroutine boundary. Evidence: `ChapterTranslator.kt:497–515` (claim held until full unwind), `:554–583` (non-blocking cancel and current fixed two-second join), and `TranslationPipeline.kt:139–151` (90-second native timer). If the new 3-second join returns and the state machine immediately admits Reader, the old late-write/lease race remains.
2. **Not every entry point will be gated.** Auto already suppresses on `isBatchTranslationRetained` (`TranslationManager.kt:1543–1595`), but manual Reader calls go directly through `TranslationScheduler.translatePage` (`ReaderViewModel.kt:2214–2348` → `TranslationScheduler.kt:663`). Service actions, chapter replacement, retry, toggle-off, background, chapter switch, and multi-chapter queue actions have separate paths. A partial gate will create a new race at an unreviewed seam.
3. **Internal batch overlap is mistaken for cross-session coexistence.** `OverlapScheduler` deliberately inpaints during translation; same-origin sibling attaches and `BatchWriteGate` owner-proof healing protect its write tokens. Deleting all lease/refcount/write-gate code because BATCH and READER no longer overlap would regress T934 invariants and can produce rejected or clobbered commits.
4. **The Pass 2 redesign changes durable/resume semantics or latency without being necessary.** Current main already waits for all OCR checkpoints before paid translation on a complete run, has a bounded S8 rescan, and has explicit durable `OCR_PREFLIGHT`. The new design should first extract the barrier and name the phases. Introducing a fixed five-page rolling scheduler would be a new behavior, not a cleanup, and would change progress, pause/resume, and first-page latency.
5. **The audit's “dead” components are live and heavily tested.** The analysis package is six live files/1,872 LOC; `ATTACH_TIMEOUT_MS` is 210 seconds and is reused as AUTO drain grace; `BatchWriteGate` is a T934 durability seam. Incorrect deletion tickets would force emergency reversals during the highest-risk phase.

### Audit/spec statements that are wrong or need qualification

- “`translator/analysis` is seven files/~1,800 LOC and deletable” is false on current main: it is six files/1,872 LOC and production-wired in the AI profile lane.
- “Attach waits 10–20 seconds” is false on current main: `ATTACH_TIMEOUT_MS` is 210,000 ms. The old number likely came from an earlier branch or a conceptual phase budget. Removing the attach path also requires separating AUTO's provider-drain grace constant and its D6/D7 assertions.
- “Pass 1 OCR push-through” is not a missing chapter-wide barrier. `runPass1` already completes OCR/checkpoint preflight (plus S8 gap rescan) and publishes `OCR_PREFLIGHT` before translation. The missing piece is a clean phase boundary, not a first implementation of the barrier.
- “Lane A rolling translation in five-page chunks” has no current implementation evidence. The five-page constant is group-commit staging; AI analysis has 16 core pages + 2 overlap; translation is serial per page or per envelope.
- “Delete `BatchWriteGate` and sibling attach/refcounting under mutual exclusion” is unsafe unless Lane B overlap is also removed. Session exclusion only removes foreign-origin contention; T934 same-origin token flips remain possible.
- The audit's D1–D11 naming inventory is also imprecise: current main has no D4 file and has two D6 files. Test disposition must be behavior-based, not filename-based.
- `TranslationManager.requestAutoWindow`/`TranslationScheduler.requestAutoWindow` are marked dormant/deprecated; live auto admission is `updateAutoWindow` through `RollingAutoCoordinator`. A Phase 3 ticket should not spend scope on the dormant path unless it is explicitly deleting it.

## Investigation conclusion

Proceed with Phase 3 ticketing, but split it into explicit safety steps:

1. Add a single session owner/state machine and route every reader/batch entry point above through it.
2. Add a quiescent batch transition with an explicit timeout outcome. Do not call timeout-returned work quiescent without a quarantine/continued-PAUSING policy.
3. Add focused transition tests first: BATCH→READER confirm/reject, pause/join success, timeout behavior, no direct scheduler bypass, and reader teardown symmetry.
4. Only then remove the attach/defer/rescan code proven unreachable, splitting `ATTACH_TIMEOUT_MS` from AUTO drain grace.
5. Preserve `PageStageLeaseTable` generation/token cleanup, same-origin sibling attach/detach, `BatchWriteGate`, `OverlapScheduler`, render-tail drain, T930/T934 durability, and the listed surviving tests while decomposing the 4,511-line coordinator.
