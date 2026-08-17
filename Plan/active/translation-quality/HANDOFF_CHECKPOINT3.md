# Handoff: Translation Pipeline Remediation — Resume at Checkpoint 3

Status date: 2026-07-15. Branch: `fix-translation-pipeline`.

This file is the pickup point for the next agent. Read it fully before starting.
Source of truth for requirements: `Plan/active/translation-quality/new_translation_fixes.md`.

## Working conventions (non-negotiable)

- Implementation is done ONLY by the `Implementation-model` subagent, in the
  foreground (`run_in_background: false`). The orchestrator coordinates, inspects,
  and verifies — it does not write production code. (Exception: the orchestrator
  may finish a stalled, clearly-scoped subtask directly, as it did for the
  Checkpoint 2 integration tests. Prefer delegating to Implementation-model.)
- Implementation-model has STALLED TWICE this session on large prompts. Split
  Checkpoint 3 into 3–4 smaller, self-contained Implementation-model calls
  (user approved this on 2026-07-15). See "Execution plan for Checkpoint 3" below.
- NO SILENT FALLBACK. Log every rejected patch, late result, over-budget target,
  malformed/missing/duplicate revision response, translator failure, and test
  failure with chapter/page/reason. (Project memory: "no fallback, make sure to
  log every failure.")
- Do NOT touch unrelated untracked files: `ORIGINAL_REQUEST.md`, `TestRegex.*`,
  `test.py`, `test_parse.kts`, `test_regex.main.kts`, `*.png`,
  `tools/aot_corpus/**`. Do not `git add -A`.
- Preserve/refine (don't overwrite) the existing uncommitted changes in
  `ChapterTranslationIndicator.kt` and `TranslationBatchProgressTracker.kt`.
- No commits/pushes unless the user explicitly asks. Branch first if asked to
  commit and currently on a non-main branch (we are on `fix-translation-pipeline`).

## Verify commands

- Build main: `./gradlew :app:compileStandardDebugKotlin --console=plain`
- Build tests: `./gradlew :app:compileStandardDebugUnitTestKotlin --console=plain`
- Focused translation tests:
  `./gradlew :app:testStandardDebugUnitTest --console=plain --tests 'eu.kanade.translation.*'`
- Whitespace: `git diff --check`
- Full suite: `./gradlew :app:testStandardDebugUnitTest`
- NOTE: on win32/Git Bash, `grep -E` and `-v`/`-i` together throw
  "conflicting matchers". Use plain `grep -i "pattern"` (single matcher) instead.

## Progress so far

### Checkpoint 1 — COMPLETE & VERIFIED ✅
State ownership, display gating, native quarantine.

Invariants delivered:
- `ChapterTranslationStore` is sole owner of live page/block state; outward
  snapshots/returns are deep-detached copies (nested blocks/lists too).
- Run/batch generation token + monotonic page version (not wall-clock) +
  stable block fingerprints + atomic patch preconditions. Late writes rejected
  & logged.
- Safe cleaned-file publication: write+verify versioned file -> commit name via
  store patch -> delete old only after commit accepted. Rejected new file
  cleaned; never deletes currently-referenced file.
- `StageStatus.SKIPPED` added (serialization-compatible). Textless pages skip
  translation/render, keep original, count as terminal success.
- Unified reader display readiness: text-bearing page shows cleaned image only
  when cleaned file + valid translation + overlay/render readiness all hold.
  Overlay-content fingerprint added to `PageView` (text-only Pass-2 changes
  refresh overlay without image decode).
- `NativeRunQuarantine` replaces watchdog force-release: timed-out native call
  invalidates its generation, keeps lane quarantined until real exit, late
  results rejected/logged, no second native call during quarantine.

New files:
- `app/src/main/java/eu/kanade/translation/model/PageTranslationOwnership.kt`
- `app/src/main/java/eu/kanade/translation/CleanedImagePublisher.kt`
- `app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt`
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStoreRaceTest.kt`
- `app/src/test/java/eu/kanade/translation/CleanedImagePublisherTest.kt`
- `app/src/test/java/eu/kanade/translation/model/PageDisplayReadinessTest.kt`
- `app/src/test/java/eu/kanade/translation/scheduling/NativeRunQuarantineTest.kt`

### Checkpoint 2 — COMPLETE & VERIFIED ✅
Staged scheduling + reliable contextual revision.

Invariants delivered (all wired into live `TranslationPipeline.translateBatch`):
- `BatchCoordinator` extracted: 1 serialized native lane, 1 serialized
  translator lane, bounded channel cap 2, per-page render join.
  Pipeline refs: `TranslationPipeline.kt:1138-1479`.
- OCR persists -> immutable work item offered to channel BEFORE same-page
  inpaint completes (REMOTE_IO overlap). Channel full -> finish inpaint,
  release bitmap+native permit, THEN suspending send.
- `TranslatorComputeClass`: Gemini/OpenRouter/DeepSeek/LM Studio/DeepL/Google =
  REMOTE_IO (overlap native); ML Kit = LOCAL_COMPUTE (serialized with native).
  Pipeline ref: `TranslationPipeline.kt:1151,1479`.
- `InactivityFlusher`: 250ms inactivity flush of incomplete contextual chunks,
  virtual-time-testable, one provider request at a time.
  Pipeline ref: `TranslationPipeline.kt:1368-1371`.
- Contextual translators return structured per-ID results (anchored IDs `p0_b3`,
  no persisted-block-ID migration). Duplicate/unknown/malformed/missing/blank
  cannot overwrite a draft. Valid nonblank without `[OK]`/`[FLAG]` accepted but
  auto-flagged. `ContextualTextTranslator.translateContextualStructured`.
- Pass 2 starts after complete Pass-1 translation barrier (not after
  inpaint/render). Final completion joins Pass2+inpaint+render+persist+reconcile.
- `RevisionPlanner`: reading order, max 20 targets, strict token budget, split
  before exceeding budget, over-budget target reported (no truncation),
  glossary + nearby non-target dialogue as context, output-targets-only.
- `RevisionMerger` + `RevisionCommitter` replaced the OLD BUGGY merge
  (~old `TranslationPipeline.kt:1520-1557`, now removed). Strict precondition
  re-check (generation, page version, fingerprint, draft, needsRevision,
  userEditedAt). Only unique valid nonblank applied corrections clear flag +
  count completed. Stale/edit/missing/blank/malformed/duplicate/rejected retain
  draft+flag, logged, counted failed. `TranslationBlockValidation` re-run after
  merge; READY/PARTIAL derived from validation, page NEVER forced READY.
  Pipeline refs: `TranslationPipeline.kt:1561,1661-1678`.

New files (prod):
- `app/src/main/java/eu/kanade/translation/batch/BatchCoordinator.kt`
- `app/src/main/java/eu/kanade/translation/batch/BatchCoordinatorInterfaces.kt`
- `app/src/main/java/eu/kanade/translation/translator/TranslatorComputeClass.kt`
- `app/src/main/java/eu/kanade/translation/translator/InactivityFlusher.kt`
- `app/src/main/java/eu/kanade/translation/translator/ContextualResponseParser.kt`
- `app/src/main/java/eu/kanade/translation/translator/ContextualTranslationBatch.kt`
- `app/src/main/java/eu/kanade/translation/translator/ContextualRequestBuilder.kt`
- `app/src/main/java/eu/kanade/translation/translator/RevisionPlanner.kt`
- `app/src/main/java/eu/kanade/translation/translator/RevisionMerger.kt`
- `app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt`

New files (test):
- `app/src/test/java/eu/kanade/translation/batch/BatchCoordinatorTest.kt`
  (isolated schedule invariants a–e, g-variant)
- `app/src/test/java/eu/kanade/translation/batch/BatchCoordinatorWiredTest.kt`
  (wired-pattern a–e: overlap, native serialization, ML Kit no-overlap,
  backpressure release-before-send, pass2-after-barrier)
- `app/src/test/java/eu/kanade/translation/translator/Checkpoint2IntegrationTest.kt`
  (f: stale/edit/missing rejected via store patch, only-applied clears flag;
  g: PARTIAL preserved after merge; compute-class routing; flusher budget)
- `app/src/test/java/eu/kanade/translation/translator/TranslatorComputeClassTest.kt`
- `app/src/test/java/eu/kanade/translation/translator/ContextualResponseParserTest.kt`
- `app/src/test/java/eu/kanade/translation/translator/InactivityFlusherTest.kt`
- `app/src/test/java/eu/kanade/translation/translator/RevisionPlannerTest.kt`
- `app/src/test/java/eu/kanade/translation/translator/RevisionMergerTest.kt`

### Checkpoint 3 — NOT STARTED ⬜ (your job)

See "Execution plan for Checkpoint 3" below.

### Final verification — NOT STARTED ⬜
Full JVM suite, emulator/device stress, final audit table.

## Commit plan (user requested a commit before going remote)

The user asked to "plan for a commit." No commit has been made yet. Recommended:

Scope: Checkpoints 1 + 2 only (both complete & verified green). The tree is clean
of whitespace errors (`git diff --check` passes) and the full
`eu.kanade.translation.*` suite passes.

Suggested commit on branch `fix-translation-pipeline` (do NOT commit to main):
- Message (example):
  `feat(translation): state ownership, staged batch + reliable Pass-2 (Checkpoints 1-2)`
- Files to include (tracked-modified + the translation-relevant untracked below).
  Use EXPLICIT paths, NOT `git add -A` (excludes unrelated untracked files).

EXCLUDE these untracked files from the commit:
- `ORIGINAL_REQUEST.md`, `TestRegex.class`, `TestRegex.java`, `test.py`,
  `test_parse.kts`, `test_regex.main.kts`, `after-show-translated.png`,
  `reopened-reader.png`, `translation-current.png`,
  `tools/aot_corpus/qa_fullpage_compare.py`, `tools/aot_corpus/qa_output_fullpage/`
- This handoff file itself can be committed or left untracked (user preference).

If the user wants Checkpoints 1+2 as separate commits instead, split by:
- Commit A = Checkpoint 1 files (ownership, CleanedImagePublisher,
  NativeRunQuarantine, PageView fingerprint, StageStatus.SKIPPED, display gating)
- Commit B = Checkpoint 2 files (BatchCoordinator + interfaces, compute class,
  InactivityFlusher, contextual structured results, RevisionPlanner/Merger/
  Committer, pipeline wiring)
Note: `TranslationPipeline.kt` spans both checkpoints — split commits would need
careful staging. A single combined commit is simpler and recommended.

## Execution plan for Checkpoint 3 (user approved: split into smaller Implementation-model calls)

Source: `new_translation_fixes.md` section 3. Split into 4 sequential
Implementation-model calls. Each call must: receive a self-contained prompt,
run foreground, build + test + `git diff --check` before returning, and report
files/invariants/tests/gaps. Do NOT start the next sub-call until the previous
one is verified green.

### Sub-call 3A — Progress reducer + rich counts + concurrent stages
Scope (plan §3.1, §3.2, §3.3):
- Typed batch events (`TranslationBatchEvent.kt` already exists as a stub — wire it)
  feed a PURE SERIALIZED REDUCER as the sole progress projection. Pipeline/store
  own stage state; tracker stops writing duplicate store transitions and becomes
  a serialized projection.
- `activeStages` as a SET (not single `activeStage`).
- Expand counts to succeeded/failed/skipped/processed/total. Fractions use
  processed work (failures complete the bar while visibly failed). Revision
  progress uses `(completed + failed) / total`; expose skipped/user-edited.
- Refine the uncommitted `ChapterTranslationIndicator.kt` change to use
  processed revision progress + localized copy (not hardcoded `"Rev"`).
Relevant files: `batch/TranslationBatchEvent.kt`, `batch/TranslationBatchProgressTracker.kt`,
`model/TranslationProgressSnapshot.kt`, `TranslationPipeline.kt`,
`presentation/manga/components/TranslationProgressSheet.kt`,
`presentation/manga/components/ChapterTranslationIndicator.kt`,
`i18n-at/.../strings.xml`.
Tests: reducer handles concurrent stages + exact succeeded/failed/skipped/
processed totals; terminal progress reaches 100% processed with visible failures.
Existing tests to keep green: `TranslationBatchProgressTrackerTest`,
`TranslationProgressSnapshotTest`.

### Sub-call 3B — Reconciliation + READY_WITH_WARNINGS + summary sidecar
Scope (plan §3.4, §3.5, §3.6):
- `BatchProgressReconciler.reconcile` must iterate the AUTHORITATIVE ordered
  page keys (currently ignores `orderedKeys` and iterates `pageMap`). Missing/
  cancelled/incomplete expected pages -> stranded failures. Unexpected keys
  reported, not counted as expected. Write stranded failures BEFORE terminal
  snapshot. Remove the stranded-page double count (reconciler increments
  `failedCount` for stranded AND tracker adds `result.failedCount +
  result.strandedPages.size`).
- Add `Translation.State.READY_WITH_WARNINGS`: readable partial drafts or
  unresolved revision targets -> warnings; hard OCR/inpaint/render failure ->
  ERROR. Warning-ready chapters stay readable/available with retry/review.
- Backward-compatible adjacent chapter-summary SIDECAR: version, expected page
  count, terminal outcome, unresolved revision count, update timestamp. Existing
  page JSON unchanged. Legacy JSON without summary = readable partial
  availability, not certified complete, until a full batch writes the sidecar.
  Publish atomically.
Relevant files: `batch/BatchProgressReconciler.kt`, `model/Translation.kt`,
`TranslationManager.kt` (`isChapterTranslated` ~L247-275 needs the summary),
new sidecar type/store.
Tests: missing ordered keys -> failures; unexpected keys don't inflate totals;
stranded counted once; warning-vs-error deterministic; sidecar round-trip,
atomic replace, legacy compat, expected-count mismatch, unresolved count.
Existing: `BatchProgressReconcilerTest` (will need updates for ordered-keys
behavior), `ChapterTranslatedPredicateTest`.

### Sub-call 3C — Lifecycle + UI delivery + memory pressure + glossary debounce
Scope (plan §3.7, §3.8, §3.9, §3.10, §3.11):
- Terminal emissions bypass throttling; can't be lost when chapter status
  changes (`MangaScreenModel.kt:539-581` cancels collector on terminal — final
  tracker emission can be missed).
- Hide Cancel in terminal phase (`TranslationProgressSheet.kt:165-175`).
- Display failed/skipped/user-edited revision counts + processed progress.
- Remove the GLOBAL active-store fan-in (`TranslationManager.kt:137-138,
  314-326`); reader state selects by chapter ID (`ReaderViewModel.kt:153-171`).
- Dispose live trackers/tick jobs/store references IMMEDIATELY on normal
  completion AND cancellation (`TranslationManager.kt:413-439`). Retain only
  immutable terminal snapshots in an access-ordered cache capped at 20.
- Forward `App.onTrimMemory` to translation management (`App.kt:70-215` has no
  override) so pre-translation gets pressure without an open reader. Reuse
  existing `TranslationManager.onMemoryPressure`.
- Debounce glossary persistence with page-store debounce
  (`ChapterTranslationStore.kt:215-248` writes immediately today); flush on
  completion/cancellation/store close (`flush()` ~L392-399 handles page map
  only — add glossary dirty state).
Relevant files: `tachiyomi/ui/manga/MangaScreenModel.kt`,
`presentation/manga/components/TranslationProgressSheet.kt`,
`TranslationManager.kt`, `tachiyomi/ui/reader/ReaderViewModel.kt`,
`tachiyomi/App.kt`, `tachiyomi/ui/reader/ReaderActivity.kt`,
`ChapterTranslationStore.kt`, `batch/TranslationBatchProgressTracker.kt`.
Tests: completion+cancellation dispose trackers; cache <=20 immutable snapshots;
cross-chapter reader isolation; app-level pressure reaches manager without
reader; glossary coalesces + flushes on completion AND cancellation; final
snapshot delivered despite throttling/status transition; terminal Cancel hidden.

### Sub-call 3D — Documentation cleanup
Scope (plan §3.12, §49):
- Update `docs/TRANSLATION_MODULE.md` to match IMPLEMENTED behavior: channel
  capacity 2, OCR->translate enqueue BEFORE same-page inpaint (REMOTE_IO),
  ML Kit serialized, `.jpg` versioned cleaned files + atomic publication,
  up-to-4 cleaned bitmaps / 48 MiB cap, 250ms inactivity flush, structured
  contextual results (anchored IDs), strict Pass-2 merge semantics,
  progress model (processed fractions, concurrent stages, READY_WITH_WARNINGS),
  summary sidecar.
- Remove STALE claims (OCR publishes to translation before inpaint was claimed
  but not implemented before CP2; "at most one cleaned bitmap"; `.cleaned.png`;
  Pass-2 "successful correction" semantics overstated). Lines ~824-873 are the
  main offenders.
- Leave historical/experimental notes untouched. Keep comments concise
  (concurrency, memory, publication, compatibility invariants only).
Tests: none (docs). Run `git diff --check`.

## Final verification (after Checkpoint 3) — todo item 4
1. `./gradlew :app:testStandardDebugUnitTest` (full suite) — must be green.
2. `git diff --check`.
3. Emulator/device validation (standard debug variant): build/install/launch via
   android-emulator MCP tools; automate deterministic reader/pre-translation
   checks that need Android resources/lifecycle. Stress: start pre-translation,
   open reader mid-batch, cancel, memory pressure; confirm remote request start
   precedes same-page inpaint completion and native/bitmap concurrency doesn't
   increase. Report device-only results verbatim; don't claim skipped checks passed.
4. Produce final audit table mapping every line item in `new_translation_fixes.md`
   to implementation files + passing tests, plus any explicitly unresolved criterion.

## Known ambiguities (from audit, decide during CP3 if needed)
- Textless downstream: which stages are SKIPPED (translation+render yes; inpaint
  only if detector-only masks need it — CP1 kept inpaint when masks require it).
- READY_WITH_WARNINGS: should it satisfy existing "translated" UI predicates
  while enabling retry/review? (Plan: readable/available yes.)
- Legacy sidecar: precise UI mapping for "partial availability until touched by
  a full batch".
- Duplicate IDs: reject only the duplicate occurrence vs reject all responses for
  that ID (CP2 chose: duplicate = ambiguous for that ID).
