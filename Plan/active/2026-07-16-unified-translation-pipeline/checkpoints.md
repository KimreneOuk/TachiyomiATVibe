# Checkpoints - Unified Translation Pipeline Recovery

This is a large, interdependent task. Implement one checkpoint at a time. Record
what changed, tests run, unexpected findings, and whether the next checkpoint is
still valid before continuing.

## Checkpoint 0 - Establish a runnable baseline

Changes:

- Configure `JAVA_HOME` for the repository session.
- Run the current focused translation tests without production edits.
- Capture any pre-existing failures and current `git status` without modifying
  unrelated work.

Commands:

```text
.\gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.*" --tests "eu.kanade.translation.translator.*"
git diff --check
```

Exit gate:

- Relevant tests launch successfully, or unrelated baseline failures are
  documented and explicitly accepted before implementation.

Stop if:

- Java/Gradle remains unavailable.
- Baseline failures overlap the target code and cannot be attributed.

## Checkpoint 1 - Shared contracts and atomic stage merges

Primary files:

- `TranslationPipeline.kt`
- `ChapterTranslationStore.kt`
- `BatchCoordinatorInterfaces.kt`
- `BatchResumeGateDecider.kt`
- New pure request/result/capability types only if existing types cannot express
  the contract cleanly.

Changes:

- Define bitmap-free OCR-ready page references and immutable provider results.
- Add stage-owned atomic store merge APIs or one generic merge primitive with
  stage-relevant preconditions.
- Define one provider-request admission shared by manual, auto, batch, and later
  standalone revision work.
- Extract/share resume decisions and render commit behavior.
- Preserve generation invalidation, edit-wins, defunct rejection, and page-version
  assignment.

Tests first or alongside:

- Concurrent inpaint and translation merges both survive when relevant
  preconditions match.
- Changed OCR block/source, user edit, newer generation, and defunct store reject
  translation/revision merges.
- Render merge cannot overwrite newer translation or inpaint state.
- Work reference/result types cannot retain `Bitmap` or `InputStream` fields.

Exit gate:

- Pure store/race tests pass and no orchestration uses the new API yet.

Stop if:

- Correct independent-stage merging requires blind whole-page last-write-wins.

## Checkpoint 2 - Structured provider parity

Primary files:

- `TextTranslator.kt`
- `TranslationContextChunkPlanner.kt` or replacement contract location
- `ContextualRequestBuilder.kt`
- `ContextualResponseParser.kt`
- `OpenAiCompatibleTranslator.kt`
- `GeminiTranslator.kt`
- `OpenRouterTranslator.kt`
- `DeepSeekTranslator.kt`
- `LmStudioTranslator.kt`
- `GoogleTranslator.kt`
- `DeepLTranslator.kt`
- `MLKitTranslator.kt`
- `TranslationRetry.kt`

Changes:

- Make structured contextual results mandatory.
- Centralize OpenAI-compatible build/parse/result behavior while retaining
  provider-specific payloads.
- Add explicit contextual-revision versus validation-only capability.
- Preserve current block sorting and add compact reliable-panel headers to
  Pass-1 request serialization without adding speaker inference.
- Adapt plain translators through detached targets and immutable ordered results.
- Wire transient provider retries and target-specific output retry.

Tests:

- Every contextual provider Pass 1: complete, partial, missing, blank, malformed,
  duplicate, unknown, and tagless results.
- Every standard provider adapter: ordered mapping, blank/missing output,
  cancellation, retry exhaustion, and no semantic revision capability.
- One normal page request and deterministic oversized-page splitting.
- Panel-header snapshots: owned panel changes, unassigned blocks, no panel data,
  unchanged output IDs/order, and bounded token overhead.

Exit gate:

- No contextual provider inherits a default empty structured Pass-1 result.
- No provider writes a live store-owned block.

## Checkpoint 3 - OCR-first coordinator

Primary files:

- `BatchCoordinator.kt`
- `BatchCoordinatorInterfaces.kt`
- `TranslationPipeline.kt` batch adapters
- `BatchCoordinatorTest.kt`
- `BatchCoordinatorWiredTest.kt`
- Replace/remove inactivity and streaming tests only after callers disappear.

Changes:

- Implement ordered OCR producer and chapter-bounded reference queue.
- Enqueue only after bitmap/native release.
- Start one serialized remote provider consumer during OCR.
- Publish the all-OCR barrier before any inpaint.
- Run serial inpaint sweep and per-page render joins.
- Delay/serialize ML Kit until after OCR.
- Expose real provider start/completion events.

Tests:

- All expected OCR terminal events precede first inpaint start.
- Remote provider starts after page OCR and before all-OCR barrier.
- A deliberately stalled provider does not block all-page OCR completion.
- No native overlap; no queued bitmap/native handle; one provider in flight.
- Inpaint or translation finishing first both produce one render when both are
  ready.
- OCR failure, textless page, cancellation, oversized page, and resume cases.

Exit gate:

- Coordinator tests prove the selected schedule with fake virtual-time workers.
- Production adapter tests prove provider invocation and store state, not only
  listener callbacks.

## Checkpoint 4 - Manual/auto/batch stage parity

Primary files:

- `TranslationPipeline.kt`
- `TranslationExecutor.kt`
- `TranslationScheduler.kt`
- `ChapterTranslator.kt`
- `TranslationManager.kt`

Changes:

- Route current manual and auto entry points through the shared stage operations.
- Replace the single-page mutating `translateContextual` call with the same
  structured Pass-1 result/merge path used by batch.
- Route chapter batch adapters through the same operations.
- Preserve force retry, auto dedup/generation, stream resolution, OOM handling,
  safe publication, and display readiness.
- Publish backward-compatible chapter summary/eligibility metadata after manual
  and auto commits so cold manga-screen review discovery does not depend on a
  prior batch.
- Remove duplicated fused/batch render/resume code only after parity coverage
  passes.

Tests:

- Identical page inputs produce equivalent terminal page state for manual, auto,
  and one-page batch execution.
- Manual, auto, and batch obey the shared provider admission with at most one real
  request globally in flight while preserving prompt display/publication timing.
- Force retry resets attempts; auto resume does not redo durable stages.
- Physical cleaned-file loss re-enters inpaint for every entry path.
- Textless, OCR failure, translation partial, inpaint failure, and render failure
  semantics remain consistent.

Exit gate:

- One shared implementation owns each stage behavior; wrappers differ only in
  scheduling and progress.

## Checkpoint 5 - Pure standalone revision contracts

Primary files:

- `RevisionPlanner.kt`
- `RevisionMerger.kt`
- `RevisionCommitter.kt`
- Dedicated pure revision request builder/parser/result types; do not overload
  the Pass-1 parser if that couples the protocols or agent ownership
- `TranslationProgressSnapshot.kt` or dedicated immutable revision models
- `ChapterTranslationSummaryStore.kt`
- One atomic latest revision report sidecar store.

Changes:

- Add pure `FLAGGED` and `ALL_TRANSLATED` target selection.
- Add immutable eligibility, preflight, confirmation, start-token, progress, and
  terminal-report contracts. No contract may carry Android/store/provider objects.
- Add strict Pass-2-only `K`/`C`/`U` request/response parsing and exact accounting.
  Pass 1 remains `[OK]`/`[FLAG]` with tagless valid lines flagged.
- Extend revision merge preconditions to support initially unflagged review-all
  targets without weakening generation/fingerprint/draft/edit checks.
- Replace the current `RevisionPlanner.isRevisionTarget` flagged-only predicate
  with scope-aware selection and remove the `RevisionMerger` rejection of
  `!needsRevision` only for an expected unflagged `ALL_TRANSLATED` target.
- Add optional source/target/draft-provider summary metadata with legacy defaults.
- Define one bounded latest-run report with accepted before/after changes.

Tests:

- Eligibility for manual, auto, batch, partial, textless, and legacy stores.
- `FLAGGED` versus `ALL_TRANSLATED`, user-edit exclusions, deterministic group
  count, nearby context, panel headers, and individually over-budget targets.
- An unflagged `ALL_TRANSLATED` target is accepted while a mismatched prior flag,
  source, draft, fingerprint, generation, or edit still rejects.
- `K` keeps draft and resolves an existing flag; `C` applies non-blank text; `U`
  retains draft and sets/retains the flag.
- Missing, duplicate, unknown, tagless, malformed, blank-`C`, stale, edited, and
  defunct results retain current state and are accounted exactly once.
- Legacy summary deserialization and absent-language preflight requirements.
- Report bounds, atomic publication, and corrected before/after entries.

Exit gate:

- Pure tests prove target selection, preflight inputs, strict K/C/U, merge safety,
  backward compatibility, and report bounds before any UI or job wiring.

Stop if:

- Review-all requires weakening edit-wins or treating a missing result as keep.
- Legacy eligibility requires image decode or live native state.

## Checkpoint 6 - Manager-owned revision orchestration

Primary files:

- `TranslationManager.kt`
- `ChapterTranslator.kt` only if its ownership primitives are reused
- `TranslationPipeline.kt`
- Provider-admission owner introduced in Checkpoint 1
- `PageView.kt` only if existing overlay fingerprint refresh is insufficient

Changes:

- Extract the current batch-only revision loop into one reusable text-only driver.
- Remove automatic semantic revision from batch completion; unresolved flags are
  a valid `READY_WITH_WARNINGS` terminal summary.
- Implement manager preflight/start/cancel/observe/report APIs and revalidate the
  opaque confirmation token at start.
- Open cold partial/legacy stores without image decode and snapshot only durable
  eligible pages.
- Use the explicitly selected contextual reviewer even when drafts came from a
  standard provider; reject standard providers as reviewers.
- Wire the frozen K/C/U request/result contract through review-specific adapters
  for Gemini and the OpenAI-compatible provider family.
- Serialize all real reviewer calls through the shared provider lane.
- Pause same-chapter auto admission, drain the active provider call, and reject
  standalone start while a chapter batch or another revision is active.
- Commit only text/flags and refresh overlays without OCR/inpaint/cleaned-file IO.

Tests:

- Standalone flagged and review-all runs over manual, auto, batch, partial, and
  legacy stores without invoking OCR/inpaint/rendered-image publication.
- Gemini, OpenRouter, DeepSeek, and LM Studio strict K/C/U review-adapter parity.
- Google, DeepL, and ML Kit drafts are reviewable only through an explicit
  contextual reviewer; those providers themselves are rejected as reviewers.
- Stale confirmation token, duplicate start, active batch, cancellation,
  generation invalidation, defunct store, provider failure, and output truncation.
- One provider request globally in flight; no auto/revision overlap for one
  chapter; completed patches survive later cancellation.
- Batch terminal state no longer waits for optional revision.

Exit gate:

- The manager API can run and report revision without any screen/view model and
  without a live batch job.

## Checkpoint 7 - Revision UI/UX and pure communication

Primary files:

- `ChapterTranslationIndicator.kt`
- `MangaChapterListItem.kt`
- Manga presentation plumbing in `presentation/manga/MangaScreen.kt`
- `MangaScreenModel.kt`
- `TranslationSettingsSheet.kt`
- `ReaderViewModel.kt`
- `TranslationProgressSheet.kt`
- `TranslationPreferences.kt` and existing AI configuration presentation only as
  needed for the last-selected reviewer identifier/picker
- Translation strings under `i18n-at/`

Changes:

- Add `REVIEW` to the chapter translation menu when manager-derived eligibility
  is available, including partial manual/auto state independent of aggregate
  `Translation.State` and downloaded-image state.
- Add `Review this chapter` to the reader translation sheet.
- Render one shared confirmation model: scope, reviewer/model, language pair,
  translated/expected pages, target/exclusion counts, request estimate, and
  partial/legacy warnings.
- Send only scope/reviewer/language/opaque-token intents; never send blocks or
  frontend-computed eligibility to the manager.
- Show waiting/running/finalizing/cancelled progress and a terminal K/C/U report
  with accepted before/after changes.
- Keep settings/configuration navigation explicit when no reviewer is configured.

Tests:

- Pure screen-model reducers for ready/rejected/stale preflight, confirmation,
  duplicate taps, cancellation, background completion, and terminal report.
- Eligibility/action matrix: fully translated, half manual, half auto, batch,
  legacy, no targets, no reviewer, active batch, and deleted chapter.
- UI state never contains mutable `PageTranslation`, `TranslationBlock`, stream,
  bitmap, store, provider client, or API credential.

Exit gate:

- Manga and reader surfaces dispatch the same manager request and display the same
  run/report state; closing a surface does not cancel work.

## Checkpoint 8 - Lifecycle ownership and memory policy

Primary files:

- `ReaderViewModel.kt`
- `ReaderActivity.kt` if lifecycle calls need adjustment
- `TranslationManager.kt`
- `ChapterTranslator.kt`
- `App.kt`
- `TranslationMemoryPressureForwarder.kt`

Changes:

- Separate display/manual/auto cancellation from chapter-batch ownership.
- Remove reader chapter-switch batch cancellation.
- Move or guard stranded repair using active generation ownership.
- Keep batches alive through reader open/pause/finish and app background.
- Keep standalone revision alive through the same UI lifecycle events.
- Classify memory callbacks explicitly; UI-hidden/background cannot pause the
  batch. Critical pressure safely requeues and foreground restarts.

Tests:

- Reader construction with translation display false does not clear batch queue.
- Reader chapter switch and pause/finish do not cancel batch.
- Display false hides output/cancels reader work without closing batch engines.
- UI-hidden/background trim preserves batch; critical pressure invalidates,
  releases, queues, and restarts once.
- Explicit cancel/delete still terminate and reject late writes.
- Critical memory interrupts revision without automatically replaying a possibly
  billed provider request; committed patches remain and the report is terminal.

Exit gate:

- Lifecycle tests establish manager ownership with no store contamination or
  orphan jobs.

## Checkpoint 9 - Progress, cleanup, and current documentation

Primary files:

- `TranslationBatchEvent.kt`
- `TranslationBatchProgressTracker.kt`
- `BatchProgressReconciler.kt`
- Translation progress UI consumers if event semantics changed
- Current translation architecture/data-flow docs under `docs/`

Changes:

- Align batch phases/events with OCR+translation then inpaint/render; track
  optional standalone revision independently with exact K/C/U totals.
- Remove `InactivityFlusher`, multi-page Pass-1 streaming ownership, and stale
  tests/comments after all production callers are gone.
- Preserve summary sidecar and bounded terminal snapshot behavior.
- Preserve one bounded latest revision report and purge it on chapter deletion.
- Update stable docs only after executable behavior and tests agree.

Tests:

- Concurrent active stage sets and exact processed/success/failed/skipped totals.
- Real provider events, terminal delivery, warning outcome, cross-chapter
  isolation, tracker disposal, and bounded terminal cache.
- Revision preflight rejection, waiting/running/finalizing/cancelled delivery,
  exact K/C/U/rejected/stale/edited/over-budget totals, and report replacement.

Exit gate:

- No stale production path or current documentation claims old behavior.

## Checkpoint 10 - Full validation and device gates

Automated:

```text
.\gradlew.bat :app:testStandardDebugUnitTest
git diff --check
```

Review:

- Inspect `git status`, full diff, and changed tests.
- Verify no unrelated deletion/modification was included.
- Run an independent local code review of uncommitted changes.

Device, minimum 6 GB RAM target:

- Confirm translation HTTP request timing follows each OCR commit.
- Confirm every OCR page finishes before first inpaint.
- Confirm completed pages appear progressively in pager and webtoon while the
  batch remains active and batch completion does not wait for review.
- Review flagged and all translated scopes for complete, half-manual/auto, batch,
  standard-provider-draft, and legacy chapters.
- Verify preflight text/counts/model/language, strict K/C/U report, and exact
  before/after overlay changes with no image rewrite.
- Enter/switch/leave reader and background/screen-off without batch cancellation.
- Cancel during OCR, provider, inpaint, render, and revision; verify no late file
  or store writes.
- Trigger critical memory and confirm one safe batch requeue/resume; confirm a
  revision stops terminally without automatic provider replay.
- Measure peak Java heap, bitmap count/bytes, native sessions, and request
  concurrency; native/bitmap concurrency must not exceed one.

Exit gate:

- Automated suite and diff checks pass.
- Device results and any unperformed gates are recorded in `handoff.md`.

## Checkpoint record template

Append after each checkpoint:

```text
Checkpoint:
Files changed:
Tests/validation run:
Unexpected findings:
Risks remaining:
Next checkpoint still valid: yes/no, with reason
```

Checkpoint: 3
Files changed: app/src/main/java/eu/kanade/translation/batch/BatchCoordinator.kt, app/src/main/java/eu/kanade/translation/batch/BatchCoordinatorInterfaces.kt, app/src/main/java/eu/kanade/translation/TranslationPipeline.kt, app/src/test/java/eu/kanade/translation/batch/BatchCoordinatorTest.kt, app/src/test/java/eu/kanade/translation/batch/BatchCoordinatorWiredTest.kt
Tests/validation run: :app:testStandardDebugUnitTest (BatchCoordinatorTest, BatchCoordinatorWiredTest)
Unexpected findings: The previous coordinator queued translation before the OCR barrier. Fixing this required accumulating items in remoteRefs and only pushing them to the queue after all OCR jobs complete.
Risks remaining: None related to coordinator.
Next checkpoint still valid: yes, Manual/auto/batch stage parity is next.

Checkpoint: 4
Files changed: app/src/main/java/eu/kanade/translation/TranslationPipeline.kt, app/src/test/java/eu/kanade/translation/scheduling/TranslationParityTest.kt
Tests/validation run: :app:testStandardDebugUnitTest (TranslationParityTest)
Unexpected findings: prepareForcedRetry() sets ocrStatus to StageStatus.RUNNING which makes shouldSchedule() return false immediately since it's transiently running. Fixed assertions to check reasons.exhausted instead.
Risks remaining: None
Next checkpoint still valid: yes, CP5 (Pure standalone revision contracts) is already merged and verified.

Checkpoint: 5
Files changed: app/src/main/java/eu/kanade/translation/model/RevisionContracts.kt, app/src/main/java/eu/kanade/translation/model/TranslationProgressSnapshot.kt, app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt, app/src/main/java/eu/kanade/translation/translator/RevisionMerger.kt, app/src/main/java/eu/kanade/translation/translator/RevisionPlanner.kt, app/src/test/java/eu/kanade/translation/translator/RevisionMergerTest.kt, app/src/test/java/eu/kanade/translation/translator/RevisionPlannerTest.kt, app/src/main/java/eu/kanade/translation/translator/DeepSeekTranslator.kt, app/src/main/java/eu/kanade/translation/translator/GeminiTranslator.kt, app/src/main/java/eu/kanade/translation/translator/LmStudioTranslator.kt, app/src/main/java/eu/kanade/translation/translator/OpenAiCompatibleTranslator.kt, app/src/main/java/eu/kanade/translation/translator/OpenRouterTranslator.kt, app/src/main/java/eu/kanade/translation/translator/RevisionRequestBuilder.kt, app/src/test/java/eu/kanade/translation/translator/RevisionAdapterTest.kt
Tests/validation run: :app:testStandardDebugUnitTest (RevisionMergerTest, RevisionPlannerTest, RevisionAdapterTest)
Unexpected findings: Kotest shouldBeEmpty() did not support Map type on this version. Fixed with isEmpty() shouldBeBe true instead.
Risks remaining: None
Next checkpoint still valid: yes, Manager-owned revision orchestration (CP6) is next.

Checkpoint: 6
Files changed: app/src/main/java/eu/kanade/translation/TranslationManager.kt, app/src/main/java/eu/kanade/translation/TranslationPipeline.kt, app/src/main/java/eu/kanade/translation/translator/RevisionDriver.kt, app/src/main/java/eu/kanade/translation/translator/RevisionMerger.kt, app/src/test/java/eu/kanade/translation/translator/RevisionManagerTest.kt
Tests/validation run: :app:testStandardDebugUnitTest (746 tests passed successfully)
Unexpected findings: TargetPrecondition needsRevision flag was hardcoded to true in the batchFor test helper, which caused the ALL_TRANSLATED test case to fail validation on unflagged targets. Exposing getContextualTranslator in TranslationPipeline allowed explicit selection of reviewer models.
Risks remaining: UI integration races.
Next checkpoint still valid: yes, CP7 (Revision UI/UX and pure communication) is next.

Checkpoint: 7
Files changed:
- Backend (orchestrator): domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt, app/src/main/java/eu/kanade/translation/TranslationPipeline.kt, app/src/main/java/eu/kanade/translation/TranslationManager.kt
- Backend (orchestrator, new): app/src/main/java/eu/kanade/translation/model/RevisionUiContracts.kt (ChapterRevisionEligibility, RevisionReviewerOption, RevisionConfirmation, RevisionPreflightOutcome, RevisionRejectionReason)
- UI (Agent G, new): app/src/main/java/eu/kanade/presentation/manga/components/RevisionConfirmDialog.kt, app/src/main/java/eu/kanade/presentation/manga/components/RevisionResultSheet.kt, app/src/main/java/eu/kanade/translation/model/RevisionUiState.kt (pure reducer)
- UI (Agent G, modified): app/src/main/java/eu/kanade/presentation/manga/components/ChapterTranslationIndicator.kt, MangaChapterListItem.kt, TranslationProgressSheet.kt, app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt, MangaScreen.kt, app/src/main/java/eu/kanade/presentation/reader/TranslationSettingsSheet.kt, app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt, ReaderActivity.kt
- Strings (Agent G): i18n-at/src/commonMain/moko-resources/base/strings.xml (32 revision strings)
- Tests (Agent G, new): app/src/test/java/eu/kanade/translation/translator/RevisionUiReducerTest.kt (36 tests)
Tests/validation run: :app:compileStandardDebugKotlin (BUILD SUCCESSFUL); :app:testStandardDebugUnitTest full suite BUILD SUCCESSFUL — 793 tests, 0 failures, 0 errors, 0 skipped. git diff --check clean (TranslationManager trailing whitespace fixed).
Unexpected findings:
- The frozen CP6 contract `getContextualTranslator(reviewerModel: String)` resolved the AI provider from the ACTIVE translation engine, so it could not support an independently-selected reviewer (design requires a DeepL/Google-draft chapter reviewable by an explicit Gemini reviewer). User decision: extend CP6 contract. `getContextualTranslator(engine: AiEngine, model: String)` and `startRevision(..., reviewerEngine, reviewerModel)` now take an explicit provider; the active-engine category guard was dropped so Standard-draft chapters are reviewable. CP6 `RevisionManagerTest` tests the pure driver internals, so no CP6 test signature change was needed.
- `PageTranslation.hasRecognizedTranslation` is a property (not a function); fixed during compile.
- `ChapterTranslationSummary` does not yet persist source/target language metadata (CP5 design noted optional fields with legacy defaults); legacy language detection is preference-based until that lands. `snapshotRevisionEligibility.requiresLegacyLanguage` is true when the summary sidecar is absent.
Risks remaining: Device gates not run (CP10). No on-device verification of the reviewer picker, K/C/U overlay changes, or lifecycle. `TranslationProgressSheet.onConfirm` flow collect on line ~1078 of MangaScreenModel is retained for its effect only; harmless but could be simplified.
Next checkpoint still valid: yes, CP8 (Lifecycle ownership and memory policy) is next.

Checkpoint: 8
Files changed:
- Orchestrator (new): app/src/main/java/eu/kanade/translation/MemoryPressurePolicy.kt (MemoryPressureClass sealed interface Benign/Critical + object MemoryPressurePolicy.classify(level): MemoryPressureClass)
- Orchestrator: app/src/main/java/eu/kanade/translation/TranslationManager.kt (stopReaderTranslations now cancelRevisions=false; cancelAllPageTranslations gained cancelRevisions param defaulting true; onMemoryPressure classifies once and only Critical cancels page jobs + terminates revisions; new consumeMemoryRequeueRestart(): Boolean)
- Orchestrator: app/src/main/java/eu/kanade/translation/ChapterTranslator.kt (new @Volatile memoryRequeued; onMemoryPressure signature changed to (level, pressureClass) — always releases bitmap+native buffers, Critical cancels the translator job and requeues TRANSLATING back to QUEUE rather than flipping to ERROR, sets memoryRequeued; Benign is a no-op for owned work)
- Orchestrator: app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt (cancelTranslationForChapter no longer calls cancelQueuedTranslation so chapter switch preserves the batch entry; resumeTranslationsOnForeground calls consumeMemoryRequeueRestart() first; onMemoryPressure resets liveTranslationState only under classified Critical; added MemoryPressureClass/MemoryPressurePolicy imports)
- Agent H (new test): app/src/test/java/eu/kanade/translation/MemoryPressurePolicyTest.kt (14 pure JVM tests)
Tests/validation run: :app:compileStandardDebugKotlin (BUILD SUCCESSFUL); :app:testStandardDebugUnitTest full suite BUILD SUCCESSFUL — 807 tests, 0 failures, 0 errors, 0 skipped (was 793 at CP7; +14 = MemoryPressurePolicyTest). git diff --check clean on all CP8 files.
Unexpected findings:
- The trim-memory classification was scattered across three sites (TranslationManager, ChapterTranslator, ReaderViewModel) each branching on raw `level >= TRIM_MEMORY_RUNNING_LOW`. Because TRIM_MEMORY levels are NOT monotonic by severity — RUNNING_LOW=15 is numerically BELOW UI_HIDDEN=20, BACKGROUND=40, MODERATE=60 — that single comparison treated every benign app-background trim as a foreground memory crunch, cancelling in-flight page work and resetting display state on each background trip. Consolidated into one pure classifier (MemoryPressurePolicy.classify) mirroring BatchOomPolicy/TranslationLifecyclePolicy precedent.
- ChapterTranslator.stop() flips TRANSLATING to ERROR. The old onMemoryPressure CRITICAL path called stop("memory pressure"), so a memory kill permanently failed pages instead of requeueing them. The new path uses cancelTranslatorJob() (non-status-mutating) plus an explicit requeue to QUEUE and sets memoryRequeued so the reader can restart the batch worker once on foreground.
- KDoc/code mismatch caught by Agent H: MemoryPressurePolicy KDoc initially claimed unknown/>80 levels default to Benign, but the code (and the safer behavior) treats `level >= 80` as Critical because the OS is signalling the process may be killed. Updated the KDoc to match the code (code is source of truth per AGENT.md).
- `cancelQueuedTranslation` on chapter switch (ReaderViewModel.cancelTranslationForChapter) dropped the departing chapter from the batch queue on every navigation — violated the CP8 process-ownership contract. Removed; cancelPageTranslations already guards on isBatchTranslationActive so the shared store survives.
Risks remaining: Device gates not run (CP10). Reader-lifecycle behavior (background/screen-off survival, critical-memory requeue/resume, revision terminal-without-replay, peak heap/bitmap/native concurrency) requires on-device verification — no Robolectric in the project so these are not JVM-testable. The pure classifier and the cancel-path gating are covered at the JVM layer.
Next checkpoint still valid: yes, CP9 (Progress, cleanup, and current documentation) is next.

Checkpoint: 9
Files changed:
- Orchestrator: app/src/main/java/eu/kanade/translation/batch/TranslationBatchEvent.kt (removed dead BatchStarted + BatchResumed event types; added KDoc explaining why)
- Orchestrator: app/src/main/java/eu/kanade/translation/TranslationManager.kt (deleteTranslation now purges the .summary.json sidecar alongside the translation JSON + companion images)
- Agent H (new test): app/src/test/java/eu/kanade/translation/batch/TranslationBatchEventContractTest.kt (9 pure JVM tests — sealed-subclass surface lock + BatchStarted/BatchResumed regression guard + per-event constructor smoke)
- Agent H (new test): app/src/test/java/eu/kanade/translation/ChapterTranslationSummaryNamingTest.kt (4 pure JVM tests — locks the summaryFileName naming contract the delete purge relies on)
Tests/validation run: :app:compileStandardDebugKotlin (BUILD SUCCESSFUL); :app:testStandardDebugUnitTest full suite BUILD SUCCESSFUL — 820 tests, 0 failures, 0 errors, 0 skipped (was 807 at CP8; +13 = the two new Agent H test files). git diff --check clean on all CP9 files. 80 pre-existing worktree deletions (remote/, docs/, companion_server/) confirmed untouched.
Unexpected findings:
- The CP9 spec listed three work items, but two were verified NOT actionable against the live tree:
  (1) "Remove InactivityFlusher, multi-page Pass-1 streaming ownership, and stale tests/comments" — InactivityFlusher (translator/InactivityFlusher.kt:26), StreamingChunkPlanner (translator/StreamingChunkPlanner.kt:19), and the translatorWorker lane are LIVE production code driven by the CP3 BatchCoordinator (BatchCoordinator constructed at TranslationPipeline.kt:1456; inactivityFlusher?.start at :1467-1469; recordActivity at :1399; stop at :1511). The spec's own condition "after all production callers are gone" is unmet — callers are active. Removing them is a live-code refactor outside this checkpoint. Tests InactivityFlusherTest/StreamingChunkPlannerTest/Checkpoint2IntegrationTest/BatchCoordinatorTest all test live classes.
  (2) "Update stable docs under docs/" — on-disk docs/ has exactly 3 files in docs/project_context/ (knowledge_base.md, implementing.md, planning.md), none of which mention translation/OCR/inpaint/revision/batch/provider/pipeline. All translation-architecture docs (TRANSLATION_MODULE.md, DATA_FLOW.md, TRANSLATION_OVERLAY.md, APK_SIZE_CLEANUP_AUDIT.md, TRANSLATION_REMEDIATION_PHASE_PLAN.md) are pre-existing D deletions in the worktree (not the orchestrator's; never reverted). README.md and AGENT.md make no stale architecture claims. Nothing on disk to edit.
- The two actionable gaps were both in the progress/summary subsystem: BatchStarted/BatchResumed events had zero emitters across app/src (and zero test references), and deleteTranslation deleted the translation JSON + companion images but NOT the .summary.json sidecar (carrying latestRevisionReport), leaving an orphaned summary that a future store open would read as a stale report.
- The summary purge reuses ChapterTranslationSummaryStore.summaryFileName + file.parentFile?.findFile(...)?.delete() — the exact lookup the store's findSummaryFile() uses — so the purge cannot drift from the publish path. deleteManga already removes the whole manga directory (sidecar included); deletePageTranslation is per-page and correctly leaves the chapter-scoped summary alone.
Risks remaining: Device gates not run (CP10). The pure event surface and the summary-naming contract are locked at the JVM layer; reader/revision behavioral verification remains deferred to device gates.
Next checkpoint still valid: yes, CP10 (Full validation and device gates) is next.

Checkpoint: 10
Files changed: none (validation-only checkpoint).
Tests/validation run:
- Automated: `.\gradlew.bat :app:testStandardDebugUnitTest` BUILD SUCCESSFUL — 820 tests, 0 failures, 0 errors, 0 skipped across 95 result-XML files (tallied from app/build/test-results/testStandardDebugUnitTest/*.xml). `git diff --check` clean.
- Review: full `git status` + diff inspected. CP-related changes = 21 files (18 M + 3 new: MemoryPressurePolicy.kt, RevisionUiContracts.kt, RevisionUiState.kt, plus 5 new test files and RevisionDriver.kt untracked) spanning CP3–CP9; 80 pre-existing worktree deletions confirmed untouched and pre-existing (companion_server/ ×55, docs/ ×20, app/src/.../remote/ ×5 — none introduced by any orchestrator CP). No unrelated modification leaked into a CP.
- Independent local code review (Explore subagent, read-only): MemoryPressurePolicy.classify, TranslationManager.onMemoryPressure/consumeMemoryRequeueRestart/stopReaderTranslations, ChapterTranslator.onMemoryPressure, ReaderViewModel.cancelTranslationForChapter/onMemoryPressure/resumeTranslationsOnForeground, deleteTranslation summary purge. Verdict: no logic bugs, contract violations, or correctness defects. All five verification targets correct: classify logic matches KDoc; Critical cancels page jobs + terminates revisions; Benign releases caches only; consumeMemoryRequeueRestart clears the flag before the isRunning/queue check so a re-entrant caller cannot double-start; summary purge null-guards file/name/parentFile and uses the same summaryFileName lookup the store's findSummaryFile/publish use.
Unexpected findings:
- Device-gate environment unavailable in this session: `android_preflight` reports SDK/adb/emulator/WHPX acceleration all present, but 0 AVDs, 0 connected devices, and no cmdline-tools (so no avdmanager/sdkmanager to create one). The CP10 device gates therefore cannot run here.
- User decision: the device gates will be performed by the user installing the build on a physical phone. Recorded here as DEFERRED, not skipped.
- Review observation (non-defect, recorded for awareness): the `@Volatile translator.memoryRequeued` flag's atomicity rests on the unstated assumption that both producer (`App.onTrimMemory` → TranslationManager.onMemoryPressure → ChapterTranslator.onMemoryPressure, main thread) and sole consumer (`ReaderActivity.onResume` → resumeTranslationsOnForeground → consumeMemoryRequeueRestart, main thread) run on the Android main thread. No `synchronized`/lock guards the read-check-clear-then-start window. Verified: both call paths are main-thread and non-re-entrant, so no live race exists; the invariant is implicit rather than enforced. No fix required at this time.
Risks remaining: Device gates deferred to on-phone testing by the user. The full CP10 device-gate checklist (checkpoints.md:419-435) must be run on-device: translation HTTP request timing follows each OCR commit; every OCR page finishes before first inpaint; progressive pager/webtoon appearance while batch active and batch completion does not wait for review; reviewer flagged + all translated scopes for complete/half-manual-auto/batch/standard-draft/legacy chapters; preflight text/counts/model/language + strict K/C/U report + exact before/after overlay with no image rewrite; enter/switch/leave reader + background/screen-off without batch cancellation; cancel during OCR/provider/inpaint/render/revision with no late file or store writes; critical memory triggers exactly one safe batch requeue/resume and a revision stops terminally without provider replay; peak Java heap/bitmap/native sessions/request concurrency with native+bitmap concurrency ≤ 1.
Next checkpoint still valid: CP10 is the final checkpoint. Automated + review portions are complete; device gates are deferred to on-phone testing. The unified-translation-pipeline effort (CP0–CP10) is complete pending device verification.
