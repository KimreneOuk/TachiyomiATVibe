# T924 Phase 4 Wave A — STANDARD_PIPELINE batch lane

Implementer report. Worktree: `TachiyomiAT-t924-impl` (branch `t924/batch-profile-pipeline`,
HEAD `ec7998e`, nothing committed — all changes are working-tree only).

Baseline before Wave A: full sweep `eu.kanade.translation.*` = 242 suites / 1782 tests / 0 failures.
After Wave A: **243 suites / 1788 tests / 0 failures** (same sweep).

## Director design (implemented)

Both AI and standard engines do the same OCR preflight; the standard engine then continues with
per-page batch translation exactly like the AI lane minus everything AI-specific (no glossary,
no analysis/profile/envelope phases or pointers), then the shared engine-agnostic Stage-7
FINALIZE. Standard translation commits through the LEGACY per-page machinery (BatchLaneWorkers
idiom), never `ProfileEnvelopeExecutor.commitPages`. Completion is translation-terminal WITHOUT
in-pass render exactly like the AI lane (renderStatus stays PENDING).

## Task 1 — lane identity + pure dispatch

`app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt`

- `BatchCoordinatorKind.STANDARD_PIPELINE` added (:3013-3018) with KDoc: same coordinator with
  standard lane — pure FULL OCR → per-page batch translate tail → shared finalize.
- `dispatchKind(...)` rewritten (:3159-3180) as a pure truth table:
  `!flag → LEGACY_SEQUENTIAL`; `engineCategoryIsStandard → STANDARD_PIPELINE`;
  `contextualAiParity → PROFILE_PIPELINE`; else `LEGACY_SEQUENTIAL`.
  Defaults keep the old 1-arg call sites compiling (`engineCategoryIsStandard=false`,
  `contextualAiParity=false`). Wave A consequence: bare flag-ON no longer implies
  PROFILE_PIPELINE — the gate must qualify the engine.
- `BatchChapterTranslator.profilePipelineDispatchKind` (:1185-1205) delegates to it; KDoc
  documents the full truth table (AI_MODEL+contextual → PROFILE_PIPELINE, AI_MODEL+non-contextual
  → LEGACY, STANDARD → STANDARD_PIPELINE, flag off → LEGACY).

Tests: `ProfilePipelineDispatchGateTest.kt` fully rewritten — 6 tests: STANDARD dispatch (incl.
pathological `contextualAiParity=true` still → STANDARD_PIPELINE), contextual AI →
PROFILE_PIPELINE, non-contextual AI → LEGACY, flag OFF → LEGACY for every parity input, the full
2x2x2 truth table, and `dispatchKind` default-args compatibility.
`OcrPreflightFlagOffMidRunTest.kt` `flag off dispatch` test updated (:178-189): FF-01 OFF is
legacy regardless; the flagged lane now requires explicit contextual-AI parity (bare flag-ON
stays LEGACY) — FF-01 OFF behavior itself is untouched.

## Task 2 — coordinator standard mode

`ChapterProfileBatchCoordinator.kt`

- Class doc extended (:126) with the Wave A paragraph: standard lane does the same pure FULL OCR
  preflight (whole-corpus gap gate kept — PAUSE on gaps), then a per-page translate tail
  (`runStandardTranslateAndFinalize`), then the shared Stage-7 finalize; no frozen-profile reuse
  probe, no analysis/profile/envelope phases or pointers, no glossary; overlap windows bracket
  every per-page call.
- Constructor: `textTranslator: TextTranslator? = null` (widened from `ContextualTextTranslator?`),
  new `private val standardLane: Boolean = false` (:231) and
  `private val standardTranslateOutcome: (suspend (OcrReadyPageRef) -> ChunkCompletionOutcome)?`
  (:241) with KDoc (null + standardLane is a typed CONFIGURATION pause).
- `frozenProfileReuse` fenced AI-only (:323-327): `standardLane` never probes (the probe would
  short-circuit into the envelope phase, which requires a frozen profile).
- Lane branch (:548-556): after the gap-pause gate, `standardLane` returns
  `runStandardTranslateAndFinalize(...)` — the analysis/profile/envelope phases are never entered.
- Envelope path unchanged for AI: `textTranslator as? ContextualTextTranslator` — non-contextual
  AI gets the same typed CONFIGURATION pause as before.
- New `runStandardTranslateAndFinalize` (:1723-1963):
  - TRANSLATE record at entry (:1748) with the OCR corpus fingerprint and NO profile pointer;
  - null seam → `COUNTER_SKIPPED_NO_TRANSPORT` + `COUNTER_STOP` record and typed PAUSED with
    `STANDARD_NO_SEAM_REASON` (:1750-1770; const at :3060);
  - `overlapScheduler?.onInpaintCommitted` hook installed (:1774-1777, D2 parity with the AI lane);
  - overlap-loop idiom (:1780-1795): `runOverlapLoop()` beside the serial translate tail, stopped
    between pages once the tail ends;
  - per page (:1797-1963): `ensureActive`, `yield`, terminal-skip
    (`standardPageTerminalAtTranslate`, :1965 — `hasRenderedResult`, textless terminal, READY,
    PARTIAL, or SKIPPED: resume-reuse parity, never re-pay a provider for a terminal page), then
    `onRemoteWindowOpened()` / finally `onRemoteWindowClosed()` around the seam call
    (OcrReadyPageRef with generation only — no lease fields; the BCT wrapper arms them);
  - outcome mapping mirrors the legacy schedule (SBC): Completed → count pages; Paused → typed
    PAUSE (anchor/retryable/failure/nextEligible) with a TRANSLATE + `COUNTER_STOP` record;
    Failed → FAILED; Unexpected → FAILED with unexpected stage; PersistenceRejected →
    PERSISTENCE_REJECTED — each with its published record;
  - drained → final TRANSLATE record with `COUNTER_PAGES_TRANSLATED`, then
    `runFinalizeAndComplete(...)` verbatim (shared Stage-7: serial inpaint drain, layout sweep,
    stranded reconciliation, NonCancellable flush, single COMPLETE).
- `pageWorkProductResolvable` (:2088-2115) extended with `isNoTextTerminal`
  (`isTextlessTerminal || translationStatus == SKIPPED`) accepted for live state AND candidate
  snapshots — the LI-2 evidence class for standard-lane no-text commits. Verified the only
  SKIPPED-translation writers are the legacy textless commit (BatchLaneWorkers :1420) and
  `finalizePostOcrStage` (PostOcrStageSemantics.kt :15-16), so AI-lane records are unaffected.

## Task 3 — injected standard translate seam (BCT)

`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt`

- `runBatchPass1` computes `engineCategoryIsStandard` (:696-698) from
  `translationPreferences.translationEngineCategory()` and passes it into the dispatch gate.
- `dispatchedFlaggedLane` is now TRUE for STANDARD_PIPELINE as well (any non-LEGACY kind).
- New local `suspend fun standardTranslateOutcome(ref)` (:722+): the per-page wrapper that adapts
  the coordinator seam to the legacy per-page machinery:
  1. `tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)` — deny
     logs INFO and returns `Completed(emptySet())` (skip; never preempts MANUAL/reader work);
  2. granted → register `BatchWriteIdentity` (generation, pageVersion, leaseToken,
     candidateGenerationId, dependencyFingerprint, artifactPageVersion) in the map the overlap
     scheduler and guarded writes share;
  3. arm the ref via `ref.copy(leaseToken=..., candidateGenerationId=..., dependencyFingerprint=...,
     artifactPageVersion=...)` and delegate to
     `batchLaneWorkers.translatorWorker.translateOutcome(armed)` — the delegate IS
     `TranslatorLaneWorker.translateOutcome`, so commit fencing, candidate lifecycle, and
     partial-response rejection are the exact LEGACY per-page path (never
     `ProfileEnvelopeExecutor.commitPages`);
  4. `finally` releases the BATCH lease (TX-06).
  - **Documented adaptation (the single one):** the preflight releases page leases after each
    checkpoint CLOSE, so the wrapper re-acquires a fresh Translation-stage lease and re-arms the
    write identity per page (the AI envelope lane keeps its lease across the barrier instead).
    Deny semantics preserved: a denied lease is a skip, never an error.
- New `STANDARD_PIPELINE` branch (:854-905): reads `translationStandardEngine()`; credential is
  the DeepL API key only for `StandardEngine.DEEPL` else empty;
  `frozenConfig(providerKey = "standard:" + engine.name.lowercase(Locale.ROOT), credentialId =
  sha256Hex(key).take(16) or "", flagProfilePipeline = flag)` — no AI provider identity, no
  analysis chunk runner; OverlapScheduler wired with the identical idiom as the AI branch;
  coordinator constructed with `standardLane = true` and `standardTranslateOutcome = { seam(ref) }`.
- No glossary reads/writes anywhere in the branch (verified by grep; pinned by test).

## Task 4 — shell wiring

Covered above (providerKey/credentialId/dispatchedFlaggedLane). `sha256Hex` is the coordinator's
existing companion helper (`ChapterProfileBatchCoordinator.sha256Hex`), so fingerprint shape
matches the AI lane's credential fingerprints exactly.

## Task 5 — tests

New `app/src/test/java/eu/kanade/translation/pipeline/batch/StandardPipelineCoordinatorTest.kt`
(4 tests, all green):

1. **T2 end-to-end standard run** (4 pages, p2 textless): record sequence pinned via hooks —
   the first OCR-observable durable record is `OCR_PLAN` (RUN_SNAPSHOT is published before the
   OCR loop and is pinned by the preflight-completion assertions), then `TRANSLATE` (durable
   before the first provider call), then `FINALIZE` (durable before the inpaint drain); run ends
   COMPLETED / `TRANSLATE_COMPLETE_REASON`, COMPLETE record with `COUNTER_RUN_COMPLETE=1` and
   `COUNTER_PAGES_TRANSLATED=4` (all seam-completed pages incl. the textless skip commit);
   translated pages READY + render PENDING + inpaint READY with translator stamps; p2
   translation/render SKIPPED with zero translator calls; seam invoked for every page;
   manifest profile/envelopePlan null + analysisChunks empty; glossary untouched; candidate
   pointers present (LI-2).
2. **T3 resume parity**: re-dispatch over the completed run → RESUME_COMPLETE_REASON, zero OCR,
   zero seam calls, active-run pointer unchanged (zero-work COMPLETE via LI-2, with the SKIPPED
   no-text evidence class).
3. **T4 provider identity freeze**: `standard:google` vs `standard:deepl` provider keys, DeepL
   credential fingerprint (sha256-16 shape), empty credential for keyless engines,
   `analysisPolicyFingerprint`/`envelopePolicyFingerprint` at schema defaults, and
   `runConfigFingerprint` separation across engine and flag changes (ST-15).
4. **T5 overlap window discipline**: with p1's translate held in flight, `translator.calls ==
   [p1]`, at least one window opened, `overlapInpaintsExecuted == 0`, `serialInpaintsExecuted ==
   0`, nothing inpainted; after release the run COMPLETES, calls `[p1,p2,p3]`, every page
   inpainted, windows+serial counters == 3 — all inpaint drains by FINALIZE.

## Verification

- `StandardPipelineCoordinatorTest`: 4/4 green.
- MUST-stay-green battery (one invocation, all green, 85 tests / 0 failures):
  Stage7FinalizeCoordinatorTest (2), Stage7FinalizeResumeCoordinatorTest (3),
  BatchDispatchResumeWiringTest (3), OcrPreflightFlagOffMidRunTest (5),
  ProfilePipelineDispatchGateTest (6), AnalysisChunkValidationTest (23),
  BatchPostPassProjectionTest (4), StoreStatusProjectorRunRecordTest (6),
  CancelBatchLeaseSkipTest (5), ChapterArtifactStoreStaleManifestRetryTest (3),
  OcrPreflightCoordinatorTest (4), OcrPreflightRejectedMidRunDurabilityTest (2),
  ProfileEnvelopeDispatchTest (10), OverlapSchedulerTest (4), T918CancelledBatchRestartTest (1),
  StandardPipelineCoordinatorTest (4).
- Full sweep `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`:
  **243 suites / 1788 tests / 0 failures / 0 errors.**
- Nothing committed: HEAD still `ec7998e`; working tree carries the 4 modified files + 1 new test.

## Decisions and deviations

1. **Overlap DECISION interpretation (documented, not a deviation):** the DECISION "bracket every
   per-page translate call with the window mechanism for ALL standard engines" is implemented
   literally (open before / close after each seam call, all engines incl. MLKit). Consequence
   (same as the AI lane): already-committed pages' inpaint may ride a LATER page's open window;
   what never happens is native inpaint overlapping the in-flight translate call, and T5 pins
   both halves (zero inpaint while the first translate is in flight; full drain by FINALIZE).
2. **BCT seam adaptation (documented in code):** preflight releases page leases, so the seam
   wrapper re-acquires a Translation-stage BATCH lease and re-arms the write identity per page
   before delegating to `TranslatorLaneWorker.translateOutcome`. Deny → skip
   (`Completed(emptySet())`), never preempts reader/MANUAL work.
3. **`dispatchKind` default-args compatibility:** bare flag-ON now maps to LEGACY (Wave A truth
   table). The one existing call site that relied on flag-ON ⇒ PROFILE_PIPELINE
   (`OcrPreflightFlagOffMidRunTest.flag off dispatch`) was updated to pass
   `contextualAiParity = true` explicitly — production call sites always pass both inputs.
4. **COUNTER_PAGES_TRANSLATED counts seam-completed pages** including the textless skip commit
   (legacy parity), pinned at 4 for the 4-page fixture (p2 textless).
5. **Fixture-only note (test seam commit merge):** the test's `StandardSeam.commit` merge copies
   `renderStatus`/`inpaintStatus` alongside translation fields so the textless SKIPPED terminal
   is expressed exactly like the legacy textless commit.

## Discoveries for Wave B

1. **Pre-existing Stage-3 gap (not Wave A scope, affects the AI lane identically):** a genuinely
   textless OCR page whose blocks are all EMPTY (not merely blank-text) never opens an artifact
   candidate (`shouldPersistUpdate`, ChapterTranslationStore :2519 — empty-block pages are
   transient), so the preflight checkpoint CLOSE adopt-committed branch rejects with
   "committed bundle missing for checkpoint adoption" and the flagged run dies mid-preflight.
   The fixture models textless as a whitespace-only block (`block(" ")`) to stay on the legacy
   textless trigger (`count { it.text.isNotBlank() } == 0`). Worth a dedicated Wave B decision
   (either persist an empty-block SKIPPED candidate or teach the checkpoint CLOSE a
   no-content adopt path).
2. **kotlinx-coroutines-test idiom:** `backgroundScope.launch` coroutines are not driven by
   `advanceUntilIdle()` while the test body runs (they only progress when the body suspends);
   the mid-flight pinning pattern needs a plain `TestScope` child `launch` + `advanceUntilIdle()`
   + `join()` (used in StandardPipelineCoordinatorTest T5).
