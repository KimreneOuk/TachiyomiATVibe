# Handoff - Unified Translation Pipeline Recovery

## Current status

- Investigation and design are complete.
- Checkpoint 0 passed; production implementation starts with the disjoint Agent A
  and Agent B work packages.
- The authoritative task artifacts are this folder's `brief.md`,
  `investigation.md`, `design.md`, `checkpoints.md`, and `tasks.md`.
- The prior flat plan is marked superseded; the temporary `.kilo` draft was
  removed to avoid duplicate authority.

## Decisions confirmed with the user

- Detect/OCR the chapter first; no batch inpaint before the all-OCR barrier.
- Enqueue Pass 1 as each page OCR becomes durable.
- One page-context Pass-1 request per page; split only an oversized page.
- Pass 1 uses page-only context; revision uses glossary and nearby chapter
  source/drafts.
- Current block sorting is preserved and compact panel headers provide a
  token-efficient layout hint; panels are never treated as speakers.
- Valid drafts display immediately and batch completion does not wait for
  semantic revision.
- Semantic revision is a user-triggered, manager-owned chapter action with
  `FLAGGED` and `ALL_TRANSLATED` scopes.
- Revision works over durable manual, auto, batch, partial, and legacy drafts.
- Gemini, OpenRouter, DeepSeek, and LM Studio may act as explicit contextual
  reviewers. Google, DeepL, and ML Kit remain validation/retry-only translators,
  but their drafts may be reviewed by a separately confirmed contextual model.
- Revision uses strict plain-text K/C/U results and no tool calling, speaker IDs,
  chain-of-thought output, silent provider switch, or positional fallback.
- Manga and reader entry points share immutable preflight/start/progress/report
  contracts; backend state and eligibility are authoritative.
- Confirmation shows coverage, scope, reviewer/model, language pair, exclusions,
  and request estimate. The terminal report shows exact outcomes and accepted
  before/after changes.
- Pre-translation continues through reader and app background lifecycle unless
  explicitly cancelled/deleted or critical memory handling intervenes.

## Next safe action

Run Agent A and Agent B in parallel from `tasks.md`. Review and freeze their
store/admission and provider contracts before releasing Agent C or Agent E.

## Validation already performed

- Live code paths, provider interfaces, store patch behavior, lifecycle calls,
  test locations, and commit attribution were inspected.
- `JAVA_HOME` was resolved for repository commands to Android Studio's bundled
  JBR at `C:\Program Files\Android\Android Studio\jbr`.
- `:app:testStandardDebugUnitTest` passed for
  `eu.kanade.translation.batch.*` and `eu.kanade.translation.translator.*`.
- Baseline `git diff --check` passed.

## Risks to retain during implementation

- Provider slowness must not backpressure OCR; queue only small page references.
- Independent translation/inpaint commits must not overwrite or spuriously reject
  one another due only to unrelated page-version changes.
- Re-decoding for inpaint adds I/O/CPU; device measurement is required.
- One request per page increases request count and prompt overhead.
- Splitting the current manual/auto fused native path is high-regression-risk and
  must follow parity tests.
- The current standalone revision path does not exist; the existing revision loop
  is batch-only and must be extracted after pure contracts pass.
- Provider/language metadata is not currently persisted. Legacy preflight needs
  explicit language confirmation and backward-compatible summary defaults.
- The manga translation indicator is currently hidden for non-downloaded
  chapters; partial manual/auto eligibility cannot be keyed only to batch state.
- `ALL_TRANSLATED` may produce many bounded requests, so confirmation and exact
  request accounting are required.
- Current worktree contains unrelated modifications/deletions; never revert or
  include them in this task.

## Prepared execution

- `tasks.md` is the implementation todo list and agent work-package source.
- Checkpoints are dependency gates, not permission to run agents concurrently on
  shared hotspot files.
- The orchestrator owns contract freeze, integration, shared-file conflict
  resolution, checkpoint records, and final validation.

## Checkpoint records

### Checkpoint 0

- Files changed: planning records only; no production/test files.
- Tests/validation run: focused batch and translator JVM tests passed;
  `git diff --check` passed; dirty worktree inspected.
- Unexpected findings: Java was available through Android Studio's bundled JBR,
  despite no global `JAVA_HOME`; numerous unrelated existing deletions and edits
  remain excluded from this task.
- Risks remaining: shared hotspot files must remain orchestrator-serialized.
- Next checkpoint still valid: yes; CP1 and CP2 have disjoint ownership and can
  proceed in parallel before coordinator work begins.

## Completion report requirements

When implementation finishes, report:

- production, test, and documentation files modified;
- tests added or updated;
- automated and device validation actually performed;
- validation not performed and why;
- memory/performance measurements;
- assumptions, residual risks, and follow-up work.

### Checkpoint 1

- Files changed: ChapterTranslationStore.kt, TranslationStageContracts.kt
- Tests/validation run: focused batch and translator JVM tests passed.
- Unexpected findings: None.
- Risks remaining: Ensure orchestration uses the new APIs correctly.
- Next checkpoint still valid: yes.

### Checkpoint 2

- Files changed: Contextual translators and parsing contracts.
- Tests/validation run: focused batch and translator JVM tests passed (with compilation fixes for structured providers).
- Unexpected findings: LmStudioTranslator, DeepSeekTranslator, and OpenRouterTranslator lacked translateContextualStructured implementation. This was fixed by the orchestrator.
- Risks remaining: Ensure exact K/C/U outputs in pass 2 adapters.
- Next checkpoint still valid: yes, Agents C and E can proceed in parallel.

### Checkpoint 3

- Files changed: app/src/main/java/eu/kanade/translation/batch/BatchCoordinator.kt, app/src/main/java/eu/kanade/translation/batch/BatchCoordinatorInterfaces.kt, app/src/main/java/eu/kanade/translation/TranslationPipeline.kt, app/src/test/java/eu/kanade/translation/batch/BatchCoordinatorTest.kt, app/src/test/java/eu/kanade/translation/batch/BatchCoordinatorWiredTest.kt
- Tests/validation run: :app:testStandardDebugUnitTest (BatchCoordinatorTest, BatchCoordinatorWiredTest)
- Unexpected findings: The previous coordinator queued translation before the OCR barrier. Fixing this required accumulating items in remoteRefs and only pushing them to the queue after all OCR jobs complete.
- Risks remaining: None related to coordinator.
- Next checkpoint still valid: yes.

### Checkpoint 4

- Files changed: app/src/main/java/eu/kanade/translation/TranslationPipeline.kt, app/src/test/java/eu/kanade/translation/scheduling/TranslationParityTest.kt
- Tests/validation run: :app:testStandardDebugUnitTest (TranslationParityTest)
- Unexpected findings: prepareForcedRetry() sets ocrStatus to StageStatus.RUNNING which makes shouldSchedule() return false immediately since it's transiently running. Fixed assertions to check reasons.exhausted instead.
- Risks remaining: None.
- Next checkpoint still valid: yes.

### Checkpoint 5

- Files changed: app/src/main/java/eu/kanade/translation/model/RevisionContracts.kt, app/src/main/java/eu/kanade/translation/model/TranslationProgressSnapshot.kt, app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt, app/src/main/java/eu/kanade/translation/translator/RevisionMerger.kt, app/src/main/java/eu/kanade/translation/translator/RevisionPlanner.kt, app/src/test/java/eu/kanade/translation/translator/RevisionMergerTest.kt, app/src/test/java/eu/kanade/translation/translator/RevisionPlannerTest.kt, app/src/main/java/eu/kanade/translation/translator/DeepSeekTranslator.kt, app/src/main/java/eu/kanade/translation/translator/GeminiTranslator.kt, app/src/main/java/eu/kanade/translation/translator/LmStudioTranslator.kt, app/src/main/java/eu/kanade/translation/translator/OpenAiCompatibleTranslator.kt, app/src/main/java/eu/kanade/translation/translator/OpenRouterTranslator.kt, app/src/main/java/eu/kanade/translation/translator/RevisionRequestBuilder.kt, app/src/test/java/eu/kanade/translation/translator/RevisionAdapterTest.kt
- Tests/validation run: :app:testStandardDebugUnitTest (RevisionMergerTest, RevisionPlannerTest, RevisionAdapterTest)
- Unexpected findings: Kotest shouldBeEmpty() did not support Map type on this version. Fixed with isEmpty() shouldBeBe true instead.
- Risks remaining: None.
- Next checkpoint still valid: yes.

### Checkpoint 6

- Files changed: app/src/main/java/eu/kanade/translation/TranslationManager.kt, app/src/main/java/eu/kanade/translation/TranslationPipeline.kt, app/src/main/java/eu/kanade/translation/translator/RevisionDriver.kt, app/src/main/java/eu/kanade/translation/translator/RevisionMerger.kt, app/src/test/java/eu/kanade/translation/translator/RevisionManagerTest.kt
- Tests/validation run: :app:testStandardDebugUnitTest (746 tests passed successfully)
- Unexpected findings: TargetPrecondition needsRevision flag was hardcoded to true in the batchFor test helper, which caused the ALL_TRANSLATED test case to fail validation on unflagged targets. Exposing getContextualTranslator in TranslationPipeline allowed explicit selection of reviewer models.
- Risks remaining: UI integration races.
- Next checkpoint still valid: yes.

### Checkpoint 7 — Revision UI/UX and pure communication

- Decision (user-approved): the frozen CP6 reviewer contract was widened. `getContextualTranslator(reviewerModel: String)` and `startRevision(..., reviewerModel)` are now `getContextualTranslator(engine: AiEngine, model: String)` and `startRevision(..., reviewerEngine, reviewerModel)`, so the reviewer is independently selectable from any configured contextual AI profile, decoupled from the active translation engine. A Standard-draft (DeepL/Google/ML Kit) chapter is now reviewable by an explicitly selected contextual reviewer.
- Files changed (orchestrator backend): TranslationPreferences.kt (+revisionReviewerEngine pref), TranslationPipeline.kt (explicit-engine reviewer resolver), TranslationManager.kt (engine+model preflight/start, getLatestRevisionReport, snapshotRevisionEligibility, runRevisionPreflight, revisionReviewerOptions).
- Files added (orchestrator): model/RevisionUiContracts.kt — pure, immutable, Android/store/bitmap/stream/client/credential-free UI communication boundary.
- Files changed (Agent G UI): ChapterTranslationIndicator (+REVIEW action), MangaChapterListItem, TranslationProgressSheet (+view-report), MangaScreenModel (+eligibility, Dialog.RevisionConfirm/RevisionResult, preflight/start/cancel/result actions), MangaScreen, TranslationSettingsSheet (+Review this chapter section), ReaderViewModel, ReaderActivity. New composables RevisionConfirmDialog + RevisionResultSheet, shared by manga and reader. New pure reducer model/RevisionUiState.kt.
- Tests added: RevisionUiReducerTest (36 pure JVM tests — Ready vs all 6 rejection reasons, reviewer/scope reducers, idempotency, surface-close-never-cancels invariant, terminal report bounding, full eligibility matrix, contract-purity reflection test).
- Strings: 32 revision strings under i18n-at.
- Validation: :app:compileStandardDebugKotlin BUILD SUCCESSFUL; full :app:testStandardDebugUnitTest BUILD SUCCESSFUL — 793 tests, 0 failures, 0 errors; git diff --check clean.
- Validation not performed: device/emulator UI run, reviewer timing, K/C/U overlay changes, lifecycle — deferred to CP10.
- Risks remaining: no on-device verification yet; the reviewer reuses each provider's existing API-key/base-URL preference (no separate reviewer credential), which is intentional per design.
- Next checkpoint still valid: yes, CP8 (Lifecycle ownership and memory policy) is next.

### Checkpoint 8 — Lifecycle ownership and memory policy

- Files changed (orchestrator backend):
  - NEW app/src/main/java/eu/kanade/translation/MemoryPressurePolicy.kt — pure JVM-testable classifier. `sealed interface MemoryPressureClass { data object Benign; data object Critical }` + `object MemoryPressurePolicy.classify(level: Int): MemoryPressureClass`. Critical = `level in 10..15 || level >= 80`; Benign = everything else (UI_HIDDEN 20, BACKGROUND 40, MODERATE 60, unknown). Mirrors BatchOomPolicy / TranslationLifecyclePolicy precedent; no `android.content.ComponentCallbacks2` statics (they don't resolve in plain-JVM tests; no Robolectric in the project).
  - TranslationManager.kt: `stopReaderTranslations(reason)` now `cancelAllPageTranslations(cancelBatchQueue=false, cancelRevisions=false)` — reader background/close no longer kills standalone revisions (process-owned, not reader-owned). `cancelAllPageTranslations(cancelBatchQueue=false, cancelRevisions=true)` gained the `cancelRevisions` param; user stop/disable paths keep the default `true`, background gets `false`. `onMemoryPressure(level)` classifies once; only Critical cancels single-page jobs + terminates revisions; Benign releases caches only. New `consumeMemoryRequeueRestart(): Boolean` restarts the batch worker exactly once when `translator.memoryRequeued` was set by critical pressure and the queue is non-empty + not running.
  - ChapterTranslator.kt: new `@Volatile var memoryRequeued: Boolean = false` distinguishes memory-paused from user-paused. `onMemoryPressure(level, pressureClass: MemoryPressureClass)` — signature changed (was `onMemoryPressure(level: Int)`). Always releases bitmap pool + native buffers; Critical calls `cancelTranslatorJob()` (non-status-mutating) and requeues TRANSLATING back to QUEUE rather than flipping to ERROR, then sets `memoryRequeued`; Benign is a no-op for owned work. The old CRITICAL path called `stop("memory pressure")` which permanently failed pages — removed.
  - ReaderViewModel.kt: `cancelTranslationForChapter` no longer calls `cancelQueuedTranslation`, so a chapter switch no longer drops the batch entry (the store already survives via the `isBatchTranslationActive` guard in cancelPageTranslations). `resumeTranslationsOnForeground` calls `consumeMemoryRequeueRestart()` first (runs before the auto-translate gate so a requeued batch for a different chapter restarts regardless of auto-translate). `onMemoryPressure(level)` resets `liveTranslationState` only under classified Critical (was `level >= TRIM_MEMORY_RUNNING_LOW` which caught benign background trims). Added imports for MemoryPressureClass/MemoryPressurePolicy.
- Files NOT modified (verified): App.kt (forwarder bridge unchanged), TranslationMemoryPressureForwarder.kt (stays pure pass-through — classification lives in MemoryPressurePolicy), ReaderActivity.kt (onPause/onResume/finish hooks unchanged — they call VM methods now made correct), ChapterTranslator.cancelTranslatorJobAndJoin (delete path unchanged).
- Tests added (Agent H): MemoryPressurePolicyTest — 14 pure JVM tests (boundary inclusivity at 9/10/15/16/79/80, all benign levels, edge cases 0/negative/Int.MAX_VALUE, and an enumerated 0..120 sweep asserting the Critical set equals exactly 10..15 + 80..120).
- Independent review (Agent H): 6/6 PASS — user-stop/disable paths still cancel revisions (default cancelRevisions=true); Critical memory uses cancelTranslatorJob (not stop) and requeues to QUEUE; no orphan jobs; no store contamination (isBatchTranslationActive guard intact); consumeMemoryRequeueRestart is idempotent and only starts when queue non-empty + not running; no dead branch in onMemoryPressure.
- Validation: :app:compileStandardDebugKotlin BUILD SUCCESSFUL; full :app:testStandardDebugUnitTest BUILD SUCCESSFUL — 807 tests, 0 failures, 0 errors, 0 skipped (was 793 at CP7; +14 = MemoryPressurePolicyTest). git diff --check clean on all CP8 files. CP7 RVM symbols verified intact (0 reverted CP7 lines).
- Validation not performed (deferred to CP10 device gates): reader enter/switch/leave + background/screen-off without batch cancellation; cancel during OCR/provider/inpaint/render/revision verifying no late file/store writes; critical memory triggering one safe batch requeue/resume and a terminal revision stop without provider replay; peak Java heap / bitmap count+bytes / native sessions / request concurrency (must not exceed one). No Robolectric in the project so these are not JVM-testable.
- Risks remaining: only the pure classifier and cancel-path gating are covered at the JVM layer; reader-side behavioral guarantees await device verification at CP10.
- Next checkpoint still valid: yes, CP9 (Progress, cleanup, and current documentation) is next.

### Checkpoint 9 — Progress, cleanup, and current documentation

- Reality check (three explore agents + orchestrator verification): two of the CP9 spec's three work items were NOT actionable against the live tree.
  - "Remove InactivityFlusher, multi-page Pass-1 streaming ownership, stale tests/comments": InactivityFlusher (translator/InactivityFlusher.kt:26), StreamingChunkPlanner (translator/StreamingChunkPlanner.kt:19), and the translatorWorker lane are LIVE production code driven by the CP3 BatchCoordinator (constructed TranslationPipeline.kt:1456; inactivityFlusher?.start :1467-1469; recordActivity :1399; stop :1511). The spec's own gate "after all production callers are gone" is unmet — callers are active. Removing them is a live refactor outside CP9. Tests InactivityFlusherTest/StreamingChunkPlannerTest/Checkpoint2IntegrationTest/BatchCoordinatorTest all test live classes.
  - "Update stable docs under docs/": on-disk docs/ has 3 files in docs/project_context/ (knowledge_base.md, implementing.md, planning.md), none mention translation/OCR/inpaint/revision/batch/provider/pipeline. All translation-architecture docs (TRANSLATION_MODULE.md, DATA_FLOW.md, TRANSLATION_OVERLAY.md, APK_SIZE_CLEANUP_AUDIT.md, TRANSLATION_REMEDIATION_PHASE_PLAN.md) are pre-existing D deletions in the worktree (not the orchestrator's; never reverted). README.md and AGENT.md make no stale architecture claims. Nothing on disk to edit.
- Two actionable gaps (both in the progress/summary subsystem):
  - Files changed (orchestrator): app/src/main/java/eu/kanade/translation/batch/TranslationBatchEvent.kt — removed dead BatchStarted + BatchResumed event types (zero emitters across app/src, zero test references; the reducer's defensive else->previous branch was their only handling). Added KDoc explaining the batch start/resume lifecycle is owned by BatchCoordinator and not surfaced through this event stream. Sealed class now has exactly 8 subtypes: PagePhase, RevisionStarted, RevisionChunkRunning, RevisionChunkCompleted, RevisionChunkFailed, RevisionFinished, BatchAborted, BatchFinished.
  - Files changed (orchestrator): app/src/main/java/eu/kanade/translation/TranslationManager.deleteTranslation — now purges the .summary.json sidecar alongside the translation JSON + companion images. Reuses ChapterTranslationSummaryStore.summaryFileName(name) + file.parentFile?.findFile(...)?.delete() (the exact lookup the store's findSummaryFile() uses), so the purge cannot drift from the publish path. Guards null name and null parentFile. deleteManga already removes the whole manga directory (sidecar included); deletePageTranslation is per-page and correctly leaves the chapter-scoped summary alone.
- Files NOT modified (verified): the InactivityFlusher/StreamingChunkPlanner/BatchCoordinator lane (LIVE), the reducer's else->previous branch (kept as defensive default), deleteManga, deletePageTranslation, all docs/ files, README.md, AGENT.md.
- Tests added (Agent H): TranslationBatchEventContractTest (9 pure JVM tests — sealed-subclass exact-set lock, BatchStarted/BatchResumed regression guard, per-event constructor smoke). ChapterTranslationSummaryNamingTest (4 pure JVM tests — locks summaryFileName for chapter.json / chapter / a.b.c.json / .hidden so a rename cannot silently strand the sidecar on delete).
- Independent review (Agent H B3): 4/4 PASS — only BatchStarted/BatchResumed removed (8 subtypes intact, BatchPhase/PhaseStatus enums unchanged); summary purge guards nulls and runs after file?.delete(); no caller of deleteTranslation or BatchStarted/BatchResumed broken; reducer else->previous intact.
- Validation: :app:compileStandardDebugKotlin BUILD SUCCESSFUL; full :app:testStandardDebugUnitTest BUILD SUCCESSFUL — 820 tests, 0 failures, 0 errors, 0 skipped (was 807 at CP8; +13 = the two new Agent H test files). git diff --check clean. 80 pre-existing worktree deletions (remote/, docs/, companion_server/) confirmed untouched. CP9 modified exactly 2 main files (TranslationBatchEvent.kt + TranslationManager.kt); other M files are CP7/CP8 work already recorded.
- Validation not performed (deferred to CP10 device gates): reader lifecycle survival, critical-memory requeue/resume, revision terminal-without-replay, peak heap/bitmap/native concurrency. The pure event surface and summary-naming contract are locked at the JVM layer.
- Risks remaining: none at the JVM layer. Reader/revision behavioral verification remains deferred to CP10 device gates.
- Next checkpoint still valid: yes, CP10 (Full validation and device gates) is next.

### Checkpoint 10 — Full validation and device gates (automated + review complete; device deferred to on-phone)

- Scope: CP10 is validation-only (no production/test code changed). Three parts per checkpoints.md:404-440 — automated JVM suite + diff check; review (git status/diff/changed tests, no-unrelated-work verification, independent local code review); device gates (minimum 6 GB RAM target).
- Automated — PASS: `.\gradlew.bat :app:testStandardDebugUnitTest` BUILD SUCCESSFUL — 820 tests, 0 failures, 0 errors, 0 skipped across 95 result-XML files (tallied from app/build/test-results/testStandardDebugUnitTest/*.xml; was 820 at CP9, unchanged = no code touched this checkpoint). `git diff --check` clean.
- Review — PASS:
  - `git status` + full diff inspected. CP-related changes = 18 modified main/UI files + 3 new main files (MemoryPressurePolicy.kt, model/RevisionUiContracts.kt, model/RevisionUiState.kt) + RevisionDriver.kt + 5 new test files, spanning CP3–CP9. No orchestrator-authored file appears in the deletion set.
  - 80 pre-existing worktree deletions confirmed pre-existing and untouched: companion_server/ ×55, docs/ ×20, app/src/main/java/eu/kanade/translation/remote/ ×5 + its test. These predate the orchestrator CPs and are never reverted (per AGENT.md).
  - Independent local code review (read-only Explore subagent) of the CP8/CP9 behavioral surface: MemoryPressurePolicy.classify; TranslationManager.onMemoryPressure / consumeMemoryRequeueRestart / stopReaderTranslations / cancelAllPageTranslations(cancelRevisions) call sites; ChapterTranslator.onMemoryPressure + memoryRequeued; ReaderViewModel.cancelTranslationForChapter / onMemoryPressure / resumeTranslationsOnForeground; deleteTranslation summary purge. Verdict: no logic bugs, contract violations, or correctness defects. cancelAllPageTranslations(cancelBatchQueue=true) at ReaderViewModel:559 (user disabled translation) and :2144 (explicit "Stop all translation") correctly keep the default cancelRevisions=true; the two stopReaderTranslations callers ("reader closed" / "reader backgrounded") correctly take cancelRevisions=false via the internal path.
- Device gates — DEFERRED (user to run on physical phone):
  - Reason: `android_preflight` reports SDK/adb/emulator/WHPX acceleration present, but 0 AVDs, 0 connected devices, and no cmdline-tools (no avdmanager/sdkmanager to create an emulator) in this environment. User decision: install the build on a physical phone and run the device checklist there.
  - On-phone checklist to run (checkpoints.md:419-435): (1) translation HTTP request timing follows each OCR commit; (2) every OCR page finishes before first inpaint; (3) completed pages appear progressively in pager and webtoon while the batch remains active, and batch completion does not wait for review; (4) reviewer flagged + all translated scopes across complete / half-manual-auto / batch / standard-provider-draft / legacy chapters; (5) preflight text/counts/model/language + strict K/C/U report + exact before/after overlay changes with no image rewrite; (6) enter/switch/leave reader + background/screen-off without batch cancellation; (7) cancel during OCR/provider/inpaint/render/revision with no late file or store writes; (8) critical memory triggers exactly one safe batch requeue/resume, and a revision stops terminally without automatic provider replay; (9) peak Java heap, bitmap count/bytes, native sessions, request concurrency — native/bitmap concurrency must not exceed one.
- Residual risk recorded for awareness (non-defect): the `@Volatile translator.memoryRequeued` flag is mutated by `onMemoryPressure` (producer) and consumed/cleared by `consumeMemoryRequeueRestart` (sole consumer). Correctness rests on both running on the Android main thread (producer via App.onTrimMemory, consumer via ReaderActivity.onResume) — verified true today, but the invariant is implicit, not enforced by a lock. No fix required; flag for future review if either path is ever dispatched off-main.
- Next checkpoint: CP10 is the final checkpoint. Automated + review portions are complete and pass; device gates are deferred to the user's on-phone testing. The unified-translation-pipeline effort (CP0–CP10) is code-complete pending device verification.

