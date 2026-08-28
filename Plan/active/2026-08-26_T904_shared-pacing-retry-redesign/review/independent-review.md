---
kind: review
title: "T904 independent adversarial review"
---

# T904 independent adversarial review

Reviewed commit: `a53fc502d388e68655db22419f511c16168f036e`  
Chain: `926ae00 → 011e601 → f1450e9 → b56f56c → e18ed84 → a53fc50`  
Scope: source and tests only; no product code changes made.

Severity uses the requested classes: BLOCKER, MAJOR, MINOR, NOTE. Findings are
listed in descending impact and cite the assembled source with file:line
evidence.

## 1. Governor admission coverage, fairness, and lock scope

**Result: no BLOCKER/MAJOR finding.**

- **VERIFIED:** every translation-scoped `OkHttpClient.newCall` in the
  assembled tree is inside `ProviderRequestGovernor.executeValue`: the remote
  page engine (`RemotePageTranslationEngine.kt:123-143`), all four model-list
  fetches (`AiModelFetcher.kt:86-170`), and the DeepL, Gemini, Google, and
  OpenAI-compatible provider boundaries. Gemini's thinking fallback is a
  second `post(...)` and therefore also traverses the governor
  (`GeminiTranslator.kt:128-136`, `139-185`). The old compatibility facade has
  no production call sites (`TranslationStageContracts.kt:188-200`).
- **VERIFIED:** the governor keeps its mutex around bucket bookkeeping only;
  `block()` runs after admission and `release()` is in `finally`
  (`ProviderRequestGovernor.kt:326-373`). Retry backoff is outside that mutex
  (`TranslationRetry.kt:218-259`). Waiter cancellation removes the waiter and
  rethrows cancellation (`ProviderRequestGovernor.kt:269-322`).
- **VERIFIED:** bounded interactive priority is implemented in waiter
  selection, with an age escape hatch for background work
  (`ProviderRequestGovernor.kt:494-518`). Production single-page reader entry
  installs `INTERACTIVE` priority (`TranslationPipeline.kt:679-697`); auto's
  stream path remains background by default.
- **NOTE:** admission diagnostics report the metadata attempt number, but each
  provider constructs its metadata once outside `withTranslationRetry` and
  then reuses it inside the retry closure (for example
  `GeminiTranslator.kt:146-158`). Consequently repeated transport attempts are
  charged and paced correctly but are logged as `attempt=1`. This weakens
  operational verification of the “every attempt” contract; it is not itself
  a quota or correctness bypass.

## 2. Retry-budget integrity and retry multiplication

- **MAJOR — the reader/single-page compatibility path bypasses the hard
  `RequestRetryBudget`.** The batch path invokes
  `translateAiChunkWithAdaptiveRetry` (`TranslationPipeline.kt:1956-1963`),
  but the single-page path calls `ct.translateContextual(chunk)` directly
  (`TranslationPipeline.kt:3717-3749`). That call is followed by up to two
  additional partial requests (`TranslationPipeline.kt:3781-3795`), while
  Gemini's provider wrapper independently allows three transport attempts and
  may issue a thinking request plus a fallback POST per attempt
  (`GeminiTranslator.kt:83-92`, `GeminiTranslator.kt:128-136`). With no
  `withRequestRetryBudget` context on the reader path, one page can therefore
  issue up to 18 HTTP calls (3 semantic invocations × 3 transport attempts ×
  2 Gemini POSTs), and a transient/quota exhaustion is handled by the legacy
  single-page catch as `StageStatus.FAILED` (`TranslationPipeline.kt:3813-3817`)
  rather than the shared typed pause outcome. This violates the contract's
  single finite envelope budget and leaves manual/rolling reader behavior
  outside the pause/retry semantics.
- **MINOR — a governor-deferred request is counted as a compatibility attempt
  even though no HTTP call occurred.** `ProviderRequestGovernor.executeResult`
  throws `ProviderRequestPausedException` before entering its `try/finally`
  (`ProviderRequestGovernor.kt:337-349`), but `requestStructured` treats any
  unchanged budget after an exception as an ungoverned logical request and
  calls `tryConsumeAttempt()` (`AiTranslationRetryController.kt:510-529`). A
  foreground-budget defer consequently increments `attemptsUsed` without a
  network attempt; this is overcharging rather than a bypass, but makes the
  persisted/diagnostic retry count inaccurate and can consume budget in mixed
  compatibility adapters.

## 3. Pause semantics, committed prefix, and context frontier

- **MAJOR — incomplete `PARTIAL` pages can advance the rolling-context frontier.** The resume path explicitly admits `StageStatus.PARTIAL` as reusable (`TranslationPipeline.kt:1379-1399`), and the generic skip branch records every non-failed AI page—including `PARTIAL`—into `recordContextPage` (`TranslationPipeline.kt:2748-2778`). `BatchContextFrontier.record` trusts the caller's `terminalFailure` flag and immediately appends the detached page to rolling context without rejecting `PARTIAL` (`BatchContextFrontier.kt:50-73`). A quota/transport pause that leaves a partial candidate can therefore seed later provider envelopes with incomplete output after resume, violating the contract that the context frontier advances only on complete output. The frontier should require a complete/ready (or explicitly textless) page at its boundary.
- **BLOCKER — unexpected coordinator/compatibility exceptions are converted into successful completion.** The coordinator's `failureOutcome` maps every non-`ProviderFailureException` to `ChunkCompletionOutcome.Completed()` (`SequentialBatchCoordinator.kt:164-180`); the remote and standard translation catches feed this conversion (`SequentialBatchCoordinator.kt:228-253`, `255-300`, `311-382`). The same broad swallowing exists for OCR and inpaint stage exceptions (`SequentialBatchCoordinator.kt:60-103`, `387-417`). An unexpected programming or persistence failure can consequently close render gates and let the pass continue with a missing/unchanged page, while the contract requires unexpected programming/persistence errors to remain exceptions. The regression test currently codifies this unsafe behavior by expecting all pages to render after injected OCR/inpaint/translation `RuntimeException`s (`SequentialBatchCoordinatorTest.kt:408-437`).

## 4. Durability, atomicity, crash windows, and migration

- **MAJOR — the “durable” pre-download request and queue are written with asynchronous `SharedPreferences.apply()`.** `TranslationPendingRequestStore.add/remove/clear` use the AndroidX `edit {}` extension without `commit = true` (`TranslationPendingRequestStore.kt:25-33`, `47-57`), and the persisted translation queue does the same (`TranslationQueueStore.kt:42-48`, `70-72`). The corresponding in-memory `StateFlow` is updated immediately (`TranslationManager.kt:306-319`), so the UI can acknowledge the request while a hard process kill loses the disk write. On restart the chapter can disappear from `loadPendingTranslationRequests()`/`restoreQueue()`, lose auto-delete protection, and have no durable intent to resume. This is the exact async-persistence window called out by the phase report and contradicts the queue/pending durability contract.
- **MAJOR — a failed atomic failure publication is reported as a durable pause anyway.** `persistAiFailure` returns `ChapterTranslationStore.PatchResult` (`TranslationPipeline.kt:1493-1555`), but every pause/terminal call site ignores a `Rejected` result and proceeds to return a typed paused/failed outcome (`TranslationPipeline.kt:2152-2173`, `2179-2198`, `2201-2258`, `2857-2933`, `3062-3079`). The store correctly rolls its in-memory page back when manifest publication fails (`ChapterTranslationStore.kt:616-640`), and the final cleanup only preserves pages recorded in `durableFailurePageKeys` (`TranslationPipeline.kt:3228-3237`). Thus a disk/SAF/publication failure can leave the UI/queue in `PAUSED` while no failure record or candidate survives restart; the user is told to retry a state that was never durably recorded. The caller must treat rejection as an exceptional/non-durable stop and avoid claiming a durable pause.
- **VERIFIED:** candidate snapshot + durable failure are published through one manifest update after the immutable sidecar write (`ChapterArtifactStore.kt:300-368`, `371-399`); promotion removes the translation failure only when the committed pointer is atomically published (`ChapterArtifactStore.kt:408-536`). Manifest backup/temporary-file recovery and additive schema defaults are present (`ChapterDocumentIo.kt:162-235`, `ChapterArtifactStore.kt:60-127`, `ChapterArtifactManifest.kt:15-50`).

## 5. UX and lifecycle

- **MAJOR — persisted queue restoration races reader auto-delete protection.** The
  queue is restored in a fire-and-forget application-scope coroutine
  (`TranslationManager.kt:186-210`), and `ChapterTranslator.restoreQueue()` does
  the disk load and chapter lookups before it updates the live `queueState`
  (`ChapterTranslator.kt:152-168`). Until that completes,
  `isChapterTranslationProtected()` and `protectedChapterIds()` consult only the
  live queue plus pending-request state/store (`TranslationManager.kt:284-304`).
  A reader can therefore finish during process startup, enqueue or execute
  `deletePendingChapters()` (`ReaderViewModel.kt:890-895`, `1887-1894`) while a
  persisted QUEUE/PAUSED chapter is absent from the protection set, and delete
  source files needed by the batch before restoration rehydrates them. This
  violates the explicit auto-delete protection contract; protection must include
  the durable queue synchronously/atomically or deletion must wait for restore.
- **MINOR — one notification ID collapses multiple paused reminders.** Both
  active progress (`TranslationForegroundService.kt:150-168`) and every paused
  reminder (`TranslationForegroundService.kt:193-219`) use
  `Notifications.ID_TRANSLATION_PROGRESS`. When two chapters are paused, the
  later `showPaused` replaces the earlier notification, so the earlier chapter's
  retry action/reminder is not surfaced at the device level. The queue/list still
  retains state, but the claimed notification visibility is incomplete.
- **VERIFIED:** the manga confirmation path publishes `STARTING` before the
  download probe or archive/provider work (`MangaScreenModel.kt:996-1021`), and
  `PAUSED` is excluded from `isAnyBatchTranslationActive` and foreground-service
  start (`TranslationManager.kt:234-235`, `419-425`). The service stops and leaves
  a non-ongoing reminder for a paused-only queue
  (`TranslationForegroundService.kt:119-145`, `193-219`).

## 6. Normal-reader regression and platform/memory constraints

- **MAJOR — the normal reader's auto-translation callback still performs a
  synchronous SAF/store bridge on the UI thread.** `onPageSelected()` invokes
  `handleAutoTranslation()` directly on the reader callback path
  (`ReaderViewModel.kt:1120-1170`), which calls the non-suspending
  `openTranslationSession()` (`ReaderViewModel.kt:1187-1197`). That method enters
  `openOrCreateActiveChapterTranslationStore()` via `runBlocking(Dispatchers.IO)`
  (`TranslationManager.kt:1194-1209`, `963-979`); the implementation first probes
  the translation document/manifest and can open or migrate artifact files
  (`TranslationManager.kt:1021-1045`). A cold chapter or legacy migration can
  therefore park the main thread on SAF binder/disk work on every page-selection
  auto kick, defeating the newly added suspend/IO path and risking reader ANRs on
  Android 8+ devices. The corresponding `open...Suspend` helper is only used by
  some flows (`TranslationManager.kt:988-1004`), not this normal-reader entry.
- **MAJOR — Stop All Translation can likewise block the reader UI on durable
  cleanup.** The Compose callback calls `ReaderViewModel.stopAllTranslation()`
  directly (`ReaderActivity.kt:609-614`), which synchronously invokes
  `TranslationManager.cancelAllPageTranslations()` (`ReaderViewModel.kt:2276-2284`,
  `TranslationManager.kt:1947-1965`). That method uses `runBlocking` around
  `clearTransientQueuePages`; the latter holds the store mutex while cancelling
  candidates, publishing manifests/generation records, and persisting state
  (`ChapterTranslationStore.kt:1331-1392`, `1814-1827`). On SAF-backed chapters
  this is unbounded main-thread I/O and can freeze/ANR the ordinary reader stop
  action. The off-main `stopReaderTranslations` path does not cover this UI
  action.
- **VERIFIED:** the per-page reader boundary keeps native OCR/inpaint under its
  bounded native lane and performs provider HTTP/render work outside that lane
  (`TranslationPipeline.kt:661-677`, `731-778`, `3652-3667`); no additional
  normal-reader HTTP client bypass was found in the assembled provider paths.

## 7. Diagnostics and privacy hygiene

- **MAJOR — migrated legacy failure text is persisted and surfaced without
  scrubbing.** `LegacyArtifactMigration` copies `page.activeError` verbatim
  into durable failure metadata (`LegacyArtifactMigration.kt:394-408`).
  `PageTranslation.activeError` is the raw stage/error message
  (`PageTranslation.kt:85-104`), and the migrated value is later copied into
  the pause snapshot (`TranslationManager.kt:1406-1420`) and rendered in the
  progress/settings UI and paused notification (`TranslationProgressSheet.kt:738-746`,
  `TranslationSettingsSheet.kt:230-233`, `TranslationForegroundService.kt:200-218`).
  An old exception string can contain a provider response/body, prompt, signed
  URL/query data, source text, or other sensitive material. The migration path
  therefore violates the no-prompts/no-bodies/no-secrets diagnostics contract;
  it must convert legacy text to a bounded category/code or constant-safe
  summary before durable storage and presentation.
- **VERIFIED:** the new structured batch diagnostics boundary records only
  bounded counts, timings, stage/category labels, and opaque page/fingerprint
  identifiers (`BatchTranslationDiagnostics.kt:7-13`, `104-184`); provider
  response logging uses status/length/hash metadata rather than request or
  response bodies (`GeminiTranslator.kt:158-181`, `OpenAiCompatibleTranslator.kt:73-123`).

## Verdict

**REJECT (with blockers).** The assembled chain has one BLOCKER and multiple
MAJOR correctness, durability, lifecycle, and privacy findings. In particular,
unexpected coordinator exceptions can be silently converted to successful
completion, and the reader compatibility path can multiply transport and
semantic retries outside the hard request budget. The integration should not be
approved until the blocker is fixed and the listed MAJOR findings are addressed
or explicitly waived with a contract update.

## Delta re-review: `0e947861a1cafdb45f7dc3c31e67fbf756fe76ad`

The delta is exactly one commit on top of the previously reviewed
`a53fc502d388e68655db22419f511c16168f036e`; the worktree is clean. The fix
report claims 14 changed files and 47 focused regressions, with the full-gate
results recorded there. This section is limited to source verification of the
prior findings and the fix slice's new failure modes.

### 1. Prior BLOCKER: untyped coordinator exceptions

- **RESOLVED for the reported success-conversion bug.** OCR and render
  exceptions now become `UnexpectedBatchStageException` rather than a fake
  successful page (`SequentialBatchCoordinator.kt:60-103`, `387-417`). Untyped
  translation/admission failures are converted to the distinct
  `ChunkCompletionOutcome.Unexpected` (`SequentialBatchCoordinator.kt:164-180`,
  `228-382`, `444-456`). The pass stops, carries only the affected anchor as
  terminal, and leaves later pages pending (`SequentialBatchCoordinator.kt:514-570`).
  The pipeline records the stage explicitly before reconciliation
  (`TranslationPipeline.kt:3224-3257`, `3331-3379`). The old
  `RuntimeException -> Completed` path is gone; no finding remains at this
  severity for that specific bug.

### 2. Prior MAJOR: reader retry-budget bypass and multiplication

- **RESOLVED for the request-count invariant.** The reader HTTP/render path
  creates one `RequestRetryBudget` and scopes both the initial translation and
  every bounded partial retry to it (`TranslationPipeline.kt:3813-3819`,
  `3885-3913`). Provider transport wrappers inherit that coroutine budget and
  charge only after governor admission (`TranslationRetry.kt:141-180`,
  `218-241`; `ProviderRequestGovernor.kt:349-359`), so Gemini's second
  thinking-fallback POST is charged as another actual HTTP attempt under the
  same ceiling. Exhaustion/deferred admission remains typed pause data and
  skips rendering (`TranslationPipeline.kt:3930-3985`, `3990-3995`). The
  compatibility bridge no longer charges a governor-deferred request
  (`AiTranslationRetryController.kt:512-534`). The former transport × semantic
  × fallback multiplication path is therefore bounded; no residual finding
  remains for this prior bypass.
- **MAJOR — the typed reader pause is discarded at the scheduler boundary.**
  `translateSinglePageHttpRender` now records `ChunkCompletionOutcome.Paused`
  for retryable provider failures (`TranslationPipeline.kt:3930-3985`) and
  returns that outcome (`TranslationPipeline.kt:4136`), but
  `translatePreparedPage` stores the non-null result only as `completed` and
  returns `true` for both `Paused` and `Failed`
  (`TranslationPipeline.kt:1117-1130`). The rolling coordinator interprets any
  `true` as success, marks the slot completed/Ready, and only re-prepares on
  `false` (`RollingAutoCoordinator.kt:368-381`). A transient/quota reader
  failure therefore becomes a completed slot that is never retried, despite
  the page being persisted as `PARTIAL`; this is a regression in the required
  pause/retry path and remains a MAJOR.

### 3. Prior MAJOR: `PARTIAL` context-frontier advancement

- **RESOLVED.** Reused context admission now accepts only OCR-ready/textless
  pages whose translation is `READY` or explicitly textless; `PARTIAL` was
  removed from `recordReusableContextPage` (`TranslationPipeline.kt:1381-1400`).
  The frontier itself rejects non-ready pages and only records a non-textless
  gap when the caller explicitly marks terminal failure
  (`BatchContextFrontier.kt:50-85`, `93-109`). Generic skip handling also
  avoids `recordContextPage` for partial output (`TranslationPipeline.kt:2803-2817`),
  and the focused regression asserts that a partial page leaves both frontier
  and rolling context unchanged (`BatchContextFrontierTest.kt:84-95`). No
  residual context-fence finding remains.

### 4. Prior MAJOR: pending/queue durability and publication rejection

- **RESOLVED for the asynchronous-preferences window.** Pending-request and
  queue mutations now use synchronous `SharedPreferences.commit()` before
  exposing the state change (`TranslationPendingRequestStore.kt:25-33`,
  `47-57`; `TranslationQueueStore.kt:42-48`, `70-72`). The direct
  protection query also reads the persisted queue, so a process death cannot
  leave a newly acknowledged request invisible to protection
  (`TranslationManager.kt:284-305`; `ChapterTranslator.kt:136-150`).
- **RESOLVED for normal AI-failure publication.** Production call sites now
  use `persistAiFailureOrThrow`; a rejected page patch becomes an exception
  instead of being treated as a durable failure
  (`TranslationPipeline.kt:1559-1585`, with call sites at `1961`, `2038`,
  `2064`, `2087`, `2184`, `2210`, `2245`, `2896`, `2920`, `2947`, and
  `3107`). Inpaint/cleaned/terminal publication rejection is likewise
  propagated (`TranslationPipeline.kt:2591-2634`, `2653-2677`).
- **MAJOR — the rejection exception is reclassified by the coordinator.**
  `BatchPersistenceRejectedException` is an `IllegalStateException`, but the
  coordinator catches every non-cancellation exception around both chunk
  completion and per-page translation (`SequentialBatchCoordinator.kt:238-257`,
  `294-310`) and `failureOutcome()` only preserves
  `ProviderFailureException` (`SequentialBatchCoordinator.kt:166-187`). A
  rejected durable *transient* pause can therefore become `Unexpected`/FAILED,
  after which the pipeline's generic unexpected-stage path may write a terminal
  failure and the reconciler reports `ERROR` (`TranslationPipeline.kt:3224-3257`,
  `BatchProgressReconciler.kt:169-174`). This defeats the promised
  non-durable exceptional stop and can turn a provider pause into terminal
  state; the exception must remain distinguishable through the coordinator.
- **MAJOR — unexpected-stage failure publication still swallows rejection.**
  `persistUnexpectedBatchStageFailure` logs a rejected patch and returns
  (`TranslationPipeline.kt:3224-3237`, `3331-3379`). Its caller then adds the
  anchor to `durableFailurePageKeys` and completes reconciliation regardless
  (`TranslationPipeline.kt:3239-3257`), so cleanup can release the lease even
  though neither the failed stage nor its error is durable. A restart can
  therefore lose the unexpected failure, while the reconciler may already
  have emitted terminal `ERROR` from the in-memory outcome
  (`BatchProgressReconciler.kt:161-175`). This violates the fix report's
  publication-rejection rule and remains a MAJOR durability/state-integrity
  blocker.

### 5. Prior MAJOR: startup restore/delete protection and immediate UX

- **RESOLVED for the reported restore-versus-auto-delete window.** Chapter
  protection now consults both live state and the pending/queue stores directly
  (`TranslationManager.kt:284-305`), including the persisted queue accessor
  (`ChapterTranslator.kt:136-150`). Reader deletion checks therefore do not
  depend on the asynchronous restore launched at
  (`TranslationManager.kt:186-210`).
- **MAJOR — restore is still an unsynchronised destructive overwrite of a
  concurrently changed queue.** `restoreQueue()` snapshots ids, suspends for
  chapter/manga lookups, then replaces `_queueState` and rewrites the queue
  store from that stale snapshot (`ChapterTranslator.kt:159-176`; the suspend
  lookups are `Translation.kt:52-63`). A user can queue a new chapter through
  `queueChapter()`/`addToQueue()` during that window (`ChapterTranslator.kt:427-455`,
  `678-684`); restore then drops it from memory and persistence. The direct
  protection read prevents the original delete race but does not prevent this
  startup request-loss race, so queue restoration still needs serialisation or
  merge/revision protection.
- **MAJOR — synchronous durability repair makes the promised immediate
  acknowledgement block the UI thread.** The fixed pending store uses
  `edit(commit = true)` (`TranslationPendingRequestStore.kt:25-33`), while the
  Compose confirmation callback calls `acknowledgeTranslationRequests()` and
  only updates the visible request state after those commits return
  (`MangaScreenModel.kt:996-1009`; `TranslationManager.kt:243-315`). A tap for
  one or many chapters can therefore wait on disk I/O before the `STARTING`
  row is published, contradicting the immediate-acknowledgement requirement.
  Durability and prompt UI publication need a non-blocking transaction/IO
  boundary rather than synchronous commits on this main-thread path.

### 6. Prior MAJOR: normal-reader UI-thread SAF bridges

- **RESOLVED.** The normal reader auto callback now only schedules the work on
  `viewModelScope.launchIO` (`ReaderViewModel.kt:1123-1180`, `1190-1206`), so
  its compatibility `openTranslationSession()` bridge (which uses IO-scoped
  `runBlocking`, `TranslationManager.kt:964-980`, `1195-1211`) is no longer
  entered from the page-selection UI callback. The landing-page kick uses the
  same helper (`ReaderViewModel.kt:998-1018`).
- **RESOLVED.** The settings stop callback remains the direct
  `ReaderActivity` event (`ReaderActivity.kt:609-614`), but
  `ReaderViewModel.stopAllTranslation()` now resets UI state immediately and
  launches cleanup on IO (`ReaderViewModel.kt:2286-2304`); the manager bridge
  enforces `Dispatchers.IO` around the synchronous SAF cleanup
  (`TranslationManager.kt:1975-1984`). Translation-disable teardown follows
  the same off-main path (`ReaderViewModel.kt:629-642`). No prior UI-thread SAF
  bridge remains in these normal-reader paths.

### 7. Prior MAJOR: legacy failure-text sanitization

- **RESOLVED.** Legacy durable failure metadata now stores only the bounded
  stage summary, never `PageTranslation.activeError`
  (`LegacyArtifactMigration.kt:394-435`, `447-448`). The regression injects a
  provider-body/URL-like value and asserts the resulting message is the safe
  `Legacy ocr state requires retry` with no secret text
  (`LegacyArtifactMigrationTest.kt:294-311`). The migrated page/stage records
  carry status and artifact references, not the old error string, so the
  previous raw-text migration path is closed.

### 8. Prior MINOR dispositions

- **VERIFIED fixed:** the compatibility bridge charges its fallback attempt
  only when the request was not governor-deferred or budget-exhausted
  (`AiTranslationRetryController.kt:507-535`). This preserves the actual
  attempt count for a deferred admission.
- **ACCEPTED as documented low-risk follow-ups:** paused notifications still
  coalesce on `Notifications.ID_TRANSLATION_PROGRESS`
  (`TranslationForegroundService.kt:210-218`), and provider metadata defaults
  Gemini's `attempt` to the local wrapper value rather than the governor's
  global count (`GeminiTranslator.kt:146-158`; `ProviderRequestGovernor.kt:269-275`).
  The phase-7 report explicitly records both as presentation/observability-only
  limitations with no queue, retry, or durability impact
  (`engineering/phase7-review-fixes.md:72-82`).

### Delta verdict

**REJECT (with blockers).** The reported original BLOCKER and all seven original
MAJOR mechanisms are resolved at source, and the accepted MINOR dispositions
match the fix report. The delta nevertheless introduces or leaves four MAJOR
paths that block approval:

1. A typed reader `Paused`/`Failed` outcome is collapsed to boolean success by
   `translatePreparedPage`, so rolling auto translation marks a transient/quota
   page complete and never retries it (`TranslationPipeline.kt:1117-1130`;
   `RollingAutoCoordinator.kt:368-381`).
2. `BatchPersistenceRejectedException` is caught as an unexpected worker error,
   and the unexpected-stage persistence fallback ignores `Rejected`; both can
   convert a transient pause to terminal `ERROR` or release a lease without a
   durable record (`SequentialBatchCoordinator.kt:166-187`, `238-257`;
   `TranslationPipeline.kt:3224-3257`, `3331-3379`; `BatchProgressReconciler.kt:161-175`).
3. Startup queue restore can overwrite a concurrently queued chapter with its
   stale snapshot (`ChapterTranslator.kt:159-176`, `427-455`, `678-684`).
4. Synchronous pending-request commits run before the confirmation callback
   publishes `STARTING`, so the purported immediate acknowledgement can block
   on main-thread disk I/O (`TranslationPendingRequestStore.kt:25-33`;
   `MangaScreenModel.kt:996-1009`).

These require a follow-up fix and focused regression coverage before the
integration can be approved.

## Delta-2 re-review: `56179d7452e857585ab516f98a556ea83c277bb5`

The final-loop fix is one commit on top of the previously reviewed
`0e947861a1cafdb45f7dc3c31e67fbf756fe76ad`; the target branch and review
worktree were clean before inspection. This pass verifies the four Delta-1
MAJOR findings and looks for regressions in the fix slice. Findings are added
incrementally below.

### 1. Typed reader outcomes and finite retry budget

- **RESOLVED for the prior boolean-boundary finding.** The executor contract now
  returns `ChunkCompletionOutcome?`, with `null` reserved for stale/missing
  prepared state (`TranslationExecutor.kt:117-123`; `TranslationPipeline.kt:1028-1047`).
  `RollingAutoCoordinator` only marks a page `Ready` for
  `ChunkCompletionOutcome.Completed`; `Paused`, `Failed`, `Unexpected`, and
  `PersistenceRejected` stay out of the completed set
  (`RollingAutoCoordinator.kt:380-442`). Provider pauses are held out of
  admission until an external eligible reconcile, avoiding an immediate fresh
  budget (`RollingAutoCoordinator.kt:397-407`, `482-520`).
- **RESOLVED for sharing the reader attempt budget.** The reader HTTP/render
  path creates one `RequestRetryBudget` and scopes the contextual/standard call
  and bounded partial retries under it (`TranslationPipeline.kt:3869-3875`,
  `3948-3969`). The existing provider wrappers inherit that budget and charge
  actual admitted requests, so the typed boundary does not multiply transport,
  semantic, or fallback attempts.
- **MAJOR — reader render/inpaint failures still return `Completed`.** The
  outcome remains initialized to `Completed` (`TranslationPipeline.kt:3874-3875`).
  If color/render work throws, the catch marks `renderStatus = FAILED` but never
  changes `translationOutcome` (`TranslationPipeline.kt:4083-4091`); when no
  cleaned bitmap is available, the fallback also marks render failed without
  changing the outcome (`TranslationPipeline.kt:4158-4169`). The final patch can
  be accepted and the function returns that unchanged `Completed`
  (`TranslationPipeline.kt:4180-4217`), after which the rolling coordinator marks
  the slot completed/`Ready` (`RollingAutoCoordinator.kt:380-387`). This still
  violates the round-2 requirement that a failed slot can never be promoted to
  `Ready`, and suppresses a later retry despite the durable failed stage.

### 2. Persistence-rejection routing and reconciliation

- **RESOLVED for coordinator distinguishability.** The coordinator now catches
  `BatchPersistenceRejectedException` before generic exceptions and maps it to
  `ChunkCompletionOutcome.PersistenceRejected`; the outcome is carried into
  `BatchPass1Status.PERSISTENCE_REJECTED` with no terminal page keys
  (`BatchCoordinatorInterfaces.kt:184-223`; `SequentialBatchCoordinator.kt:170-197`,
  `542-599`). Stage catches in OCR, inpaint, translation, and render preserve the
  marker instead of converting it to `Unexpected`
  (`SequentialBatchCoordinator.kt:60-78`, `249-268`, `285-323`, `420-440`).
- **RESOLVED for unexpected-stage publication rejection.** The fallback now
  throws the typed rejection when its guarded patch returns `Rejected`, and the
  pipeline converts an unexpected stop into an explicit non-durable
  `PERSISTENCE_REJECTED` reconciliation without adding the anchor to durable
  failure cleanup (`TranslationPipeline.kt:3260-3311`, `3393-3436`). The
  reconciler reports `READY_WITH_WARNINGS`, zero retryable/terminal counts, and
  `nonDurableFailure=true`, rather than `ERROR` or a durable pause claim
  (`BatchProgressReconciler.kt:122-198`).
- **MAJOR — the non-durable warning is removed from the retry queue.**
  `translateChapterInternal` assigns `translation.status` to the reconciler's
  `READY_WITH_WARNINGS` for `nonDurableFailure` (`ChapterTranslator.kt:632-641`),
  but `launchTranslationJob` removes every `TRANSLATED` or
  `READY_WITH_WARNINGS` translation from the queue (`ChapterTranslator.kt:413-422`).
  A rejected publication therefore loses the only queued chapter entry needed
  for a later retry, even though the outcome explicitly says no durable record
  exists. This turns the new non-durable reconciliation into silent work loss
  and remains a MAJOR.

### 3. Queue restore merge and mutation locking

- **RESOLVED for the reported startup queue-loss race.** Queue membership
  mutations and durable reads are serialized by `queueMutationLock`
  (`ChapterTranslator.kt:145-165`). `restoreQueue()` performs chapter/store
  lookups outside the lock, then takes a fresh `queueStore.load()` under the
  lock and merges restored entries with the current live queue before publishing
  and saving (`ChapterTranslator.kt:175-223`). Concurrent additions and removals
  therefore cannot be overwritten by the original pre-lookup snapshot; the
  focused helper tests cover retained additions and stale/removal filtering
  (`ChapterTranslatorQueueRestoreTest.kt:8-27`).
- **MAJOR — restored state wins over a concurrent same-ID requeue.** The merge
  resolver prefers `restoredById` over `liveById` (`ChapterTranslator.kt:51-57`,
  `194-201`). If a stale `PAUSED`/`ERROR` entry is being looked up and the user
  removes then re-adds that same chapter while lookup suspends, the fresh durable
  ID is retained but the newly queued live object is replaced by the old restored
  status. `start()` excludes `PAUSED` entries (`ChapterTranslator.kt:272-285`),
  so the user's explicit requeue can become unstartable. Merge ordering must let
  a concurrent live mutation win for an ID present in both maps.

### 4. Immediate `STARTING` acknowledgement and version fencing

- **RESOLVED for the UI acknowledgement boundary.** The confirmation method now
  updates the `STARTING` StateFlow before launching the worker that performs the
  synchronous preferences commit (`TranslationManager.kt:260-275`), so the
  visible row no longer waits on disk I/O. The commit is dispatched on the
  manager's IO scope, and normal phase/cancel mutations increment a per-chapter
  version (`TranslationManager.kt:334-360`).
- **MAJOR — the version fence has an unprotected commit/state-update window.**
  A normal phase mutation increments the version, commits its new phase, and only
  then updates the StateFlow (`TranslationManager.kt:334-344`). The async
  `STARTING` writer checks the version/state, commits `STARTING`, and checks them
  again (`TranslationManager.kt:368-393`). Because those operations share no
  manager lock, the writer can commit `STARTING` after the normal `PREPARING` or
  cancellation commit but before that mutation publishes its StateFlow update;
  the post-commit check then sees the old `STARTING` state and returns. The later
  normal StateFlow update leaves durable storage stale, and a process death can
  restore the wrong phase (or resurrect a cancelled request). The four-iteration
 retry does not close this interleaving; state/version mutation and the fence
 need one serialization boundary.

### Delta-2 convergence and self-fix (checkpoint)

Director policy requires the remaining Delta-2 defects to be fixed in the
integration branch rather than returned for another implementation loop. The
following fixes are being applied as one self-fix commit:

1. Reader render/inpaint failure paths now create a retryable typed
   `ChunkCompletionOutcome.Failed`, so a failed render cannot be promoted to
   `Ready` by rolling auto coordination (`TranslationPipeline.kt`,
   `translateSinglePageHttpRender`).
2. A non-durable `PERSISTENCE_REJECTED` reconciliation is retained in the
   chapter queue instead of being removed alongside ordinary
   `READY_WITH_WARNINGS` completion (`ChapterTranslator.kt`,
   `launchTranslationJob`).
3. Queue restore merge prefers a live same-ID requeue over stale pre-lookup
   restored state, while preserving durable ordering (`ChapterTranslator.kt`,
   `mergeRestoredQueueEntries`).
4. Pending-request versions, StateFlow publication, and synchronous store
   writes now share a manager mutation lock; the asynchronous `STARTING`
   writer cannot interleave between a normal durable commit and its state
   publication (`TranslationManager.kt`).

The source compile check passed at this checkpoint with the repository's
Android Studio JBR and SDK explicitly configured; final gate results are
recorded below.

### Delta-2 self-fix completion

The remaining four Delta-2 MAJOR findings are resolved in the single
self-fix commit `40c213e12e83e9aab58076f13a13d0d2485c8e89`
(`fix: close final translation review races`) on `t904/integration`:

- Reader rendering, failed publication, and exhausted inpaint fallback now call
  `markRenderFailure`, which records a retryable typed `Failed` outcome instead
  of returning the initial `Completed` value (`TranslationPipeline.kt:3874-3892`,
  `4102-4105`, `4150-4165`, `4171-4179`). Rolling auto therefore cannot mark a
  failed render `Ready`.
- `ChapterTranslator.launchTranslationJob` receives the batch reconciliation and
  removes `READY_WITH_WARNINGS` only for durable completion; a non-durable
  `PERSISTENCE_REJECTED` warning remains queued for an explicit retry
  (`ChapterTranslator.kt:415-431`, `506-512`, `638-664`).
- Restore merge now prefers the live same-ID object after suspendable lookup,
  preserving a fresh user requeue over stale PAUSED/ERROR state while retaining
  durable order (`ChapterTranslator.kt:51-58`, `189-207`; regression at
  `ChapterTranslatorQueueRestoreTest.kt:29-38`).
- A manager-level lock now serializes request version increments, StateFlow
  publication, and every synchronous pending-store write. The asynchronous
  `STARTING` writer checks and commits under that same lock, closing the
  commit/state-update interleaving (`TranslationManager.kt:124-125`,
  `262-281`, `340-390`).

### Final verification

All required gates were run with Android Studio JBR and the configured Android
SDK (`C:\Users\User\AppData\Local\Android\Sdk`):

- `spotlessCheck`: PASS.
- `:app:compileStandardDebugKotlin`: PASS.
- Full `:app:testStandardDebugUnitTest` with the policy-excluded
  `eu.kanade.translation.inpainting.AotReportBubbleFillTest`: PASS (the
  exclusion was applied through a temporary Gradle test filter; no other test
  classes were excluded).
- `:domain:testReleaseUnitTest`: PASS.
- `git diff --check`: PASS.
- Final `t904/integration` worktree: clean at
  `40c213e12e83e9aab58076f13a13d0d2485c8e89`.

The accepted low-risk follow-ups remain unchanged: paused notifications use
the existing coalesced ID, and Gemini retry metadata reports the local wrapper
attempt. No new BLOCKER/MAJOR findings remain in the reviewed scope.

### Final verdict

**APPROVE-AFTER-SELF-FIX** — all four Delta-2 residual MAJOR findings were
fixed and verified in commit `40c213e12e83e9aab58076f13a13d0d2485c8e89`.
