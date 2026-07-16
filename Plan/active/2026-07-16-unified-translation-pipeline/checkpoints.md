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
