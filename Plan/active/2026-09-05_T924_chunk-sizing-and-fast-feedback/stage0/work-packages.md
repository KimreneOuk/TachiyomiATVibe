# T924 Stage 0 — work packages WP0-WP12 with verified entry points

Date: 2026-09-05
Baseline: `adbe643` = `adbe643df9d99953202dbb8c93c5dd7e504bfd6c` (VERIFIED via
`git rev-parse HEAD` at writing time; re-verify at each stage kickoff per
`feature-flags-stage-gates.md` §3).
Namespace: flag/gate clauses are **T924-FF-**\* (see
`stage0/feature-flags-stage-gates.md`); schemas/state/provider namespaces are
owned by parallel Stage 0 agents (T924-SC/FP-\*, T924-ST/TX-\*, T924-AP-\*,
requirements T924-R\*/T924-INV-\*). This file references their namespaces; it
does not define them.

Label conventions: **VERIFIED** = path and symbol confirmed to exist at HEAD
(line numbers are hints only). **NEW** = file/symbol to be created by the WP.
Requirement/invariant hooks are referenced by well-known name (page atomicity,
one-envelope-in-flight, gap-free context, one-decoded-page,
candidate-vs-committed, user-edit authority); stable IDs come from the
requirements catalog.

All main-source paths below are relative to
`app/src/main/java/eu/kanade/translation/` unless prefixed otherwise; all test
paths relative to `app/src/test/java/eu/kanade/translation/` unless prefixed.

## Dependency graph (kept from delivery-readiness-audit, unchanged)

```text
WP0 decision closure + traceability ledger
  |
  +--> WP1 run/artifact schemas + migrations + semantic fingerprints
  |      |
  |      +--> WP2 atomic OCR checkpoint/rebase
  |      |      |
  |      |      +--> WP4 feature-flagged OCR-preflight coordinator
  |      |
  |      +--> WP3 pure corpus/chunk/profile/envelope/retry planners
  |             |
  |             +--> WP5 typed analysis + profile freeze/resume
  |                    |
  |                    +--> WP6 profile-aware translation + split/backoff
  |                           |
  |                           +--> WP7 translation/inpaint overlap
  |
  +--> WP8 persisted-layout DTO/fingerprint prototype
         |
         +--> WP9 LAYOUT_PREPARE publication + reader hydration/fallback

WP4 + WP5 + WP6 + WP7 + WP9
  --> WP10 lifecycle/device/provider evaluation
  --> WP11 default enablement
  --> WP12 contextual-AI PROBE cleanup after rollback window
```

WP8 may proceed beside coordinator work after WP0. WP6 depends on a frozen
profile, not persisted layout. WP12 never precedes default evidence plus the
T924-FF-01f rollback window.

---

## WP0 — decision closure and executable specifications (this Stage 0)

- **Goal:** close the blocking contracts so WP1+ coding needs no ad-hoc
  decisions: requirements ledger (item A), schemas (item B), state machine
  (item C), provider contract (item D), flags/gates/work-packages (item E,
  this file pair), reference scenarios (item F).
- **Stage mapping:** Stage 0.
- **Status: IN PROGRESS.** This file and `stage0/feature-flags-stage-gates.md`
  are item E deliverables and are complete; other items are owned by parallel
  agents. WP0 exits only when the delivery-audit Stage 0 exit condition holds
  (all blocking decisions 1-6 accepted; schemas validated; every durable
  transition named; every later stage with exact entry points — the WP sections
  below supply those entry points).
- **Source entry points:** read-only; none change.
- **Test entry points:** none.
- **Hooks:** all six well-known invariants named in the ledger.
- **Exit pointer:** delivery-audit "Stage 0" gate; blocking decisions 1-6.

---

## WP1 — run/artifact schemas, migrations, semantic fingerprints

- **Goal:** additive, versioned durable records — `ChapterRunRecord`,
  `PageOcrCheckpoint`, `AnalysisChunkResult`, `ChapterTranslationProfile`,
  `EnvelopePlan` (+ Stage 7 draw-plan DTO in WP8) — with canonical
  serialization, crash-safe manifest pointers, and semantic fingerprints that
  ignore transaction identities. Old runtime behavior remains selected
  (FF-01/FF-02 off).
- **Stage mapping:** Stage 1.
- **Source entry points (existing files that change):**
  - `artifact/ChapterArtifactManifest.kt` — `data class ChapterArtifactManifest` (`:16`),
    `LegacyMigrationMetadata` (`:91`); add run/profile/analysis pointers.
  - `artifact/ChapterArtifactStore.kt` — `class ChapterArtifactStore` (`:39`);
    publication ordering: immutable sidecar before atomic manifest update.
  - `artifact/ArtifactContracts.kt` — `ArtifactStage` (`:47`), `ArtifactOrigin` (`:56`);
    extend for new artifact kinds.
  - `artifact/ChapterDocumentIo.kt` — `interface ChapterDocumentIo` (`:31`),
    `AtomicChapterDocuments` (`:215`); sidecar write/replace primitives.
  - `artifact/ChapterArtifactManifestReader.kt` — `internal object ChapterArtifactManifestReader` (`:18`);
    unknown-version behavior.
  - `artifact/StageFingerprints.kt` — `object StageFingerprints` (`:16`); new
    semantic fingerprint functions (OCR content, corpus, profile input/content).
  - `artifact/ArtifactRetention.kt` — sidecar garbage collection rules.
- **NEW files:** `artifact/ChapterRunRecord.kt`, `artifact/PageOcrCheckpoint.kt`,
  `artifact/AnalysisChunkResult.kt`, `artifact/ChapterTranslationProfile.kt`,
  `artifact/EnvelopePlan.kt` (DTOs + canonical JSON coding), schema version
  registry alongside `ArtifactContracts.kt`.
- **Test entry points:** extend `artifact/ChapterArtifactStoreTest.kt`,
  `artifact/LegacyArtifactMigrationTest.kt`, `artifact/StageFingerprintsTest.kt`,
  `artifact/AtomicChapterDocumentsTest.kt`, `ChapterTranslationStoreArtifactMigrationTest.kt` (root).
  NEW: `artifact/ChapterRunRecordSchemaTest.kt`,
  `artifact/SidecarCrashPublicationTest.kt`, `artifact/SemanticFingerprintTest.kt`.
  Fixtures: `app/src/test/resources/t924/manifest-v-current.json` + golden JSONs.
- **Hooks:** user-edit authority, candidate-vs-committed (authority survives
  migration); fingerprint immutability rules (T924-SC-\*).
- **Key risks:** accidentally fingerprinting mutable transaction identity;
  breaking old-manifest load; retention deleting sidecars still referenced.
- **Exit pointer:** gates §2.1 rows 1.1-1.5, 1.7 (evidence/stage1/).

## WP2 — atomic OCR checkpoint/rebase

- **Goal:** origin-neutral `checkpointOcr` store transaction: while Batch holds
  the lease, validate generation/page-version/lease-token/source/dependency
  fingerprints, publish the immutable OCR snapshot, close/rebase the BATCH
  candidate, preserve committed display, return the new write identity; only
  then may the caller release the lease. Mandatory before any orchestration work.
- **Stage mapping:** Stage 1 (late), consumed by Stage 3. Per the delivery
  audit's Stage-3 opening condition ("Land `checkpointOcr` before enabling
  preflight"), WP2 must close before Stage 3 starts (stage0-review F-8 note).
- **Source entry points (existing files that change):**
  - `ChapterTranslationStore.kt` (root) — `class ChapterTranslationStore` (`:84`);
    add `checkpointOcr` near the existing candidate/patch CAS machinery
    (candidate writes reject foreign origin; generation/version write fences live here).
  - `artifact/ChapterArtifactStore.kt` — `class ChapterArtifactStore` (`:39`),
    guarded candidate writes (design cites `:346-399`).
  - `store/PageStageLeaseTable.kt` — `internal class PageStageLeaseTable` (`:26`);
    lease ordering with checkpoint publication.
  - `model/PageTranslation.kt` — `data class PageTranslation` (`:9`),
    `StageStatus` (`:248`), mask boxes (`:237`): durable OCR payload + new
    stable OCR artifact identity/natural index where missing.
  - `store/ChapterAttemptLedger.kt` — `internal class ChapterAttemptLedger` (`:36`):
    provenance notes if rebase interacts with attempt records.
- **NEW files:** none required (transaction lives in the store); optionally
  `store/OcrCheckpointContract.kt` for the CAS input/result types (T924-ST-\*
  owns the table).
- **Test entry points:** extend `ChapterTranslationStorePersistenceTest.kt`,
  `ChapterTranslationStorePhase3Test.kt`, `PostOcrStageSemanticsTest.kt`,
  `coexistence/D1OriginPriorityTest.kt`, `coexistence/D3ReaderOwnedPageAcrossBatchTest.kt`.
  NEW: `store/OcrCheckpointRebaseTest.kt` (fault injection at every publication
  boundary; stale writer rejected; fresh Manual/Auto/Batch candidate from checkpoint).
- **Hooks:** candidate-vs-committed, user-edit authority, one-decoded-page
  (enables release after checkpoint).
- **Key risks:** lease-vs-candidate ordering (release before rebase = stale
  writer identity exposure); committed display clobbering.
- **Exit pointer:** gates §2.1 row 1.6; Stage 3 re-run via §2.3 row 3.2.

## WP3 — pure corpus/chunk/profile/envelope/retry planners

- **Goal:** deterministic, native-free and provider-free: OCR corpus manifest
  construction, hierarchical analysis chunking, structured analysis validation,
  deterministic pre-merge, relevant-profile subset matcher, global
  multi-budget whole-page envelope planner, malformed-response taxonomy,
  shared root retry ledger.
- **Stage mapping:** Stage 2.
- **Source entry points:**
  - Extend: `translator/contextual/ContextualResponseParser.kt` —
    `object ContextualResponseParser` (`:21`) gains page-level completeness +
    explicit failure class (taxonomy lives in NEW `ResponseTaxonomy.kt`).
  - Extend: `translator/retry/TranslationRetry.kt` — `class RequestRetryBudget` (`:40`);
    shared-root ledger so children cannot construct fresh budgets.
  - Reference-only (do not extend for the new path):
    `translator/contextual/StreamingChunkPlanner.kt` — `class StreamingChunkPlanner` (`:21`)
    stays for Manual/Auto/legacy; `translator/contextual/StableBlockIds.kt` —
    `object StableBlockIds` (`:7`) for `pN_bN` identity; `model/PageWorkPlanner.kt` —
    `object PageWorkPlanner` (`:17`) retains stage rules.
  - Profile-domain types arrive from WP1 (`artifact/ChapterTranslationProfile.kt`,
    `artifact/AnalysisChunkResult.kt`, `artifact/EnvelopePlan.kt`).
- **NEW files:** `translator/contextual/OcrCorpusManifest.kt`,
  `translator/contextual/AnalysisChunkPlanner.kt`,
  `translator/contextual/ProfilePreMerger.kt`,
  `translator/contextual/RelevantProfileMatcher.kt`,
  `translator/contextual/GlobalEnvelopePlanner.kt`,
  `translator/retry/ResponseTaxonomy.kt` (+ root budget ledger type if it does
  not fit `TranslationRetry.kt`).
- **Test entry points:** extend `translator/contextual/ContextualResponseParserTest.kt`,
  `translator/retry/TranslationRetryTest.kt`, `pipeline/batch/BatchContextFrontierTest.kt`
  (frontier interplay with taxonomy), `translator/contextual/BatchEnvelopeLimitsTest.kt`
  (legacy limits stay; new planner gets its own). NEW:
  `translator/contextual/GlobalEnvelopePlannerGoldenTest.kt`,
  `translator/contextual/AnalysisChunkPlannerGoldenTest.kt`,
  `translator/contextual/ProfilePreMergeGoldenTest.kt`,
  `translator/contextual/RelevantProfileMatcherTest.kt`,
  `translator/retry/MalformedResponseTaxonomyTest.kt`,
  `translator/retry/SplitBackoffLedgerTest.kt`. Fixtures under
  `app/src/test/resources/t924/golden/`.
- **Hooks:** page atomicity (planner never splits a page), gap-free context
  (membership respects frontier), one-envelope-in-flight (sequential execution
  model).
- **Key risks:** non-determinism (iteration order, locale); budget numbers
  treated as final — they are PROPOSED-GATE experiments (32 blocks/8 pages).
- **Exit pointer:** gates §2.2 (evidence/stage2/).

## WP4 — feature-flagged OCR-preflight coordinator

- **Goal:** behind T924-FF-01 (default OFF), a new coordinator runs validate →
  run snapshot → source validation → OCR plan → full serial OCR preflight:
  one decoded page at a time, persist + `checkpointOcr`, release bitmap and
  lease, yield to interactive native demand. No analysis/translation/inpaint
  changes in this WP. Flag-off and flag-off-mid-run behavior per
  T924-FF-01b/01e.
- **Stage mapping:** Stage 3.
- **Source entry points (existing files that change):**
  - `pipeline/batch/BatchChapterTranslator.kt` — `internal class BatchChapterTranslator` (`:69`);
    the `SequentialBatchCoordinator(` construction at `:622` becomes the single
    FF-01 dispatch point; run-config freeze and bounded source-hash fan-out
    (design cites `:394-401`) land here.
  - `pipeline/batch/BatchLaneWorkers.kt` — `internal class BatchLaneWorkers` (`:90`);
    split an OCR-sprint worker out; remove decoded `nativeHandoff` across the
    OCR barrier for the new path only (design cites `:1016-1028`, inpaint
    re-decode `:1096-1153`).
  - `pipeline/SinglePageOnnxPhase.kt` — `internal class SinglePageOnnxPhase` (`:57`),
    `analyzePage` (`:837`): reused as-is per page; `inpaintPage` (`:971`)
    untouched in this WP.
  - `pipeline/EngineLane.kt` — `internal class EngineLane` (`:45`): native
    admission wrapper gains the reader-priority yield/starvation rule
    (thresholds are PROPOSED-GATE, gates §2.3 row 3.5).
  - `pipeline/batch/BatchOomPolicy.kt`, `pipeline/batch/HeldBitmapRegistry.kt`,
    `util/TranslationMemoryBudget.kt` — `object TranslationMemoryBudget` (`:14`):
    unchanged contracts, new traces.
  - `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`:
    add `translationBatchProfilePipeline()` (T924-FF-00 pattern).
  - `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt`:
    no surface until Stage 8 (T924-FF-01g).
  - Legacy untouched: `pipeline/batch/SequentialBatchCoordinator.kt` —
    `class SequentialBatchCoordinator` (`:46`), `runPass1` (`:59`);
    `pipeline/batch/BatchAdmissionProbe.kt` (`:32`) stays for legacy path.
- **NEW files:** `pipeline/batch/ChapterProfileBatchCoordinator.kt` (+ durable
  phase enum per T924-ST-\*), `pipeline/batch/OcrPreflightWorker.kt`.
- **Test entry points:** extend `pipeline/batch/Phase0BatchTranslationCharacterizationTest.kt`
  (flag-off parity), `ChapterTranslatorQueueRestoreTest.kt` (root),
  `scheduling/NativeRunQuarantineTest.kt`, `coexistence/TranslationCoexistenceHarness.kt`-based
  suites. NEW: `pipeline/batch/OcrPreflightCoordinatorTest.kt`
  (phase resume; provider-fake zero-call gate), `pipeline/batch/OcrPreflightFlagOffMidRunTest.kt`
  (T924-FF-01e cases), `pipeline/batch/OcrPreflightMemoryTraceTest.kt`.
- **Hooks:** one-decoded-page, candidate-vs-committed, gap-free context (OCR
  decisions not rewritten by translation gaps), user-edit authority.
- **Key risks:** memory regression from any retained handoff; flag leaking into
  legacy path; native starvation deadlock (bounded starvation rule required).
- **Exit pointer:** gates §2.3 (evidence/stage3/, device traces required).

## WP5 — typed analysis + profile freeze/resume

- **Goal:** typed structured provider API for analysis chunks + master
  reconciliation through the shared governor at BACKGROUND priority; validated
  chunk persistence; deterministic pre-merge + bounded reconciliation;
  atomic profile freeze; resume from first invalid artifact; 15-RPM aggregate
  Batch sublimit shared by analysis+translation.
- **Stage mapping:** Stage 4.
- **Source entry points (existing files that change):**
  - `translator/ProviderRequestGovernor.kt` — `AdmissionPriority` (`:43`),
    `ProviderQuotaPolicy` (`:66`): add Batch/background rolling sub-limit
    beneath the provider bucket (current default 60 RPM/60K TPM stays for
    interactive; design cites `:66-84,655-673`).
  - `TranslationPipeline.kt` (root) — `class TranslationPipeline` (`:88`),
    `withProviderRequestPriority(AdmissionPriority.INTERACTIVE)` usage
    (`:410-416`): new analysis calls carry `BACKGROUND`; Manual/Auto unchanged.
  - `artifact/ChapterArtifactStore.kt` + WP1 sidecar types: chunk persist +
    profile freeze publication (pointer attach last).
  - `store/ChapterGlossaryStore.kt` — `internal class ChapterGlossaryStore` (`:17`)
    and `translator/contextual/ChapterGlossaryBuilder.kt` — `object ChapterGlossaryBuilder` (`:21`):
    legacy compatibility only — NOT mutated during a frozen-profile run.
  - Provider plumbing reference: `translator/providers/GeminiTranslator.kt`
    (empty-`promptText()` swallow risk, design cites `:123-133`) — typed
    structured request path must surface provider errors.
- **NEW files:** `translator/analysis/AnalysisProvider.kt` (typed contract,
  T924-AP-\*), `translator/analysis/AnalysisChunkExecutor.kt`,
  `translator/analysis/ProfileReconciler.kt`, `translator/analysis/ProfileFreezer.kt`.
- **Test entry points:** extend `translator/ProviderRequestGovernorTest.kt`,
  `translator/ProviderRequestGovernorReservationTest.kt`, `diagnostics/TranslationPipelineDiagnosticsTest.kt`.
  NEW: `translator/analysis/AnalysisChunkValidationTest.kt`,
  `translator/analysis/AnalysisResumeTest.kt`, `translator/analysis/ProfileFreezeTest.kt`,
  `translator/analysis/ProfileThenTranslateBoundaryTest.kt`,
  `translator/ProviderGovernorBatchSublimitTest.kt`.
- **Hooks:** gap-free context (no translation starts pre-freeze),
  candidate-vs-committed (freeze is an artifact publication), user-edit
  authority (user/series canon outrank frozen facts).
- **Key risks:** schema drift between analyzer and validator; provider errors
  swallowed; sub-limit mis-accounting across analysis+translation; auto-promotion
  of model facts into canon (forbidden — candidates only).
- **Exit pointer:** gates §2.4 (evidence/stage4/, real-provider eval required).

## WP6 — profile-aware translation + deterministic split/backoff

- **Goal:** whole-page envelopes in narrative order with frozen-profile subset,
  range-safe scene context, gap-free rolling history; live revalidation before
  dispatch; structural split/backoff under one shared root budget; taxonomy-
  driven retain/discard; frontier advances only on fully committed pages.
- **Stage mapping:** Stage 5.
- **Source entry points (existing files that change):**
  - `translator/retry/AiTranslationRetryController.kt` — driver
    `translateAiChunkWithAdaptiveRetry` (`:230`), `AiTranslationRetryPolicy` (`:41`),
    `AiChunkOutcome` (`:62`): add whole-page structural split above the frozen
    envelope retry; children share the root budget. (VERIFIED citation note:
    no class literally named `AiTranslationRetryController` exists — see flags
    doc §5.1.)
  - `translator/contextual/ContextualResponseParser.kt` — `object ContextualResponseParser` (`:21`):
    page-level completeness result consumed by split decisions.
  - `translator/contextual/TranslationPrompts.kt` — `object TranslationPrompts` (`:12`):
    profile-subset + range-scene + evidence/uncertainty prompt rules.
  - `pipeline/batch/BatchContextFrontier.kt` — `class BatchContextFrontier` (`:18`):
    unchanged authority; rolling context attached pre-dispatch only for `Complete`.
  - WP3 planner types (`GlobalEnvelopePlanner`, `ResponseTaxonomy`) + WP5
    frozen profile + WP2 checkpoints.
  - `translator/contextual/TranslationContextChunkPlanner.kt` (legacy planner
    caller, `:68`) untouched.
- **NEW files:** `pipeline/batch/ProfileEnvelopeExecutor.kt` (dispatch +
  re-plan-suffix on invalidation), `translator/retry/StructuralSplitPlanner.kt`.
- **Test entry points:** extend `translator/retry/AiTranslationRetryControllerTest.kt`,
  `translator/retry/AiTranslationRetryPlannerSinglePageTest.kt`,
  `translator/contextual/ContextualResponseParserTest.kt`,
  `translator/contextual/TranslationPromptsTest.kt`,
  `pipeline/batch/BatchContextFrontierTest.kt`,
  `coexistence/D2ManualBatchInterleavingTest.kt`, `coexistence/D9AttemptLedgerTest.kt`.
  NEW: `translator/retry/StructuralSplitBackoffTest.kt`,
  `pipeline/batch/ProfileEnvelopeExecutorTest.kt` (stale commits fail; suffix
  re-plan; one-envelope-in-flight trace).
- **Hooks:** page atomicity (core gate 5.1), one-envelope-in-flight, gap-free
  context, user-edit authority (Manual/Auto completions skipped, suffix re-planned).
- **Key risks:** split budget escape; mixed-response policy divergence from
  Director decision 6 (gate 5.2 flips with the decision); re-translating
  user-edited pages.
- **Exit pointer:** gates §2.5 (evidence/stage5/, provider eval required).

## WP7 — translation/inpaint overlap

- **Goal:** after profile freeze only: schedule serial local inpaint during the
  single in-flight remote Batch request; re-decode source, reuse durable mask;
  keep native admission, leases and candidate/committed rules; measured
  keep-or-revert decision (gates §2.6 row 6.5).
- **Stage mapping:** Stage 6.
- **Source entry points (existing files that change):**
  - `pipeline/batch/BatchRenderJoin.kt` — `internal class BatchRenderJoin` (`:43`):
    join scheduling for translation+inpaint (its color-only render body becomes
    LAYOUT_PREPARE orchestration only in WP9).
  - `pipeline/batch/BatchLaneWorkers.kt` — inpaint worker (`inpaintPageFn`
    wiring; design cites `:1096-1153`): invoked from overlap scheduler.
  - `pipeline/SinglePageOnnxPhase.kt` — `inpaintPage` (`:971`): re-decode from
    durable mask, unchanged contract.
  - `pipeline/CleanedPublication.kt`, `pipeline/DeferredPagePublications.kt`:
    cleaned-image publication joins.
  - `pipeline/EngineLane.kt` (`:45`): no concurrent native ownership.
- **NEW files:** `pipeline/batch/OverlapScheduler.kt` (deterministic, testable).
- **Test entry points:** extend `CleanedImagePublisherTest.kt`,
  `scheduling/NativeRunQuarantineTest.kt`, `coexistence/D6ForegroundFairnessTest.kt`.
  NEW: `pipeline/batch/OverlapSchedulerTest.kt` (no inpaint during OCR; clean
  cancel/failure join; stale cleaned commits rejected).
- **Hooks:** one-decoded-page (re-decode discipline), candidate-vs-committed.
- **Key risks:** native guard contention with reader; overlap not paying for
  its complexity (decision rule reverts to serial).
- **Exit pointer:** gates §2.6 (evidence/stage6/).

## WP8 — persisted-layout DTO/fingerprint prototype

- **Goal:** immutable source-image-space draw-plan DTO + compatibility
  fingerprint (font asset identity, typeface/style, measurement flags, planner
  version, platform shaping key); separately invalidatable color/style and
  geometry sub-results. Prototype/pure stage — no reader behavior change.
- **Stage mapping:** Stage 7 (first half). Gated on FF-02 being separately
  authorized; product requirements are placed in the requirements catalog
  (T924-R036..R041; placement resolved per stage0-review F-2).
- **Source entry points (existing files that change):**
  - `rendering/TextLayoutPlanner.kt` — `object TextLayoutPlanner` (`:540`),
    `plan` (`:579`), `planPage` (`:599`), `computeStrokeWidth` (`:3733`):
    extract a serializable projection of its output (do NOT serialize
    `BlockLayout`; final-target-migration §1.1).
  - `artifact/ChapterArtifactLayout.kt` — `class ChapterArtifactLayout` (`:40`):
    storage naming/retention for plan sidecars.
  - `artifact/ChapterArtifactManifest.kt` (`:16`) + `artifact/ChapterArtifactStore.kt` (`:39`):
    plan pointer + CAS publication inputs.
  - `rendering/RenderColorEstimator.kt` — `object RenderColorEstimator` (`:38`),
    `recomputeFor` (`:288`): color/style sub-result fingerprint.
- **NEW files:** `artifact/ChapterDrawPlan.kt` (versioned DTO + canonical
  coding), `rendering/DrawPlanFingerprint.kt`.
- **Test entry points:** extend `artifact/ChapterArtifactLayoutTest.kt`,
  `rendering/TextLayoutPlannerTest.kt`, `rendering/PageLayoutPlanContractTest.kt`,
  `rendering/TextLayoutPlannerStrokeTest.kt` (stroke width stays
  geometry-affecting). NEW: `artifact/DrawPlanDtoRoundTripTest.kt`,
  `rendering/DrawPlanCompatibilityTest.kt`.
- **Hooks:** user-edit authority (edit invalidates layout only).
- **Key risks:** viewport-dependence sneaking into DTO; platform shaping drift
  (tolerance PROPOSED-GATE ≤ 0.5 px, gates §2.7 row 7.1).
- **Exit pointer:** gates §2.7 rows 7.1-7.2 (evidence/stage7/).

## WP9 — LAYOUT_PREPARE publication + reader hydration/fallback

- **Goal:** FF-02 consumer: Batch computes and publishes geometry + color/style
  as separately invalidatable sub-results after translation completes; reader
  Pager and Webtoon prefer a valid persisted plan with stale-bind defense and
  keep the async planner fallback; DISPLAY_READY completion redefined only
  after full hydration coverage.
- **Stage mapping:** Stage 7 (second half).
- **Source entry points (existing files that change):**
  - `pipeline/batch/BatchRenderJoin.kt` (`:43`): becomes `LAYOUT_PREPARE`
    orchestration when FF-02 on (currently only `RenderColorEstimator.recomputeFor`
    + `renderStatus=READY`; final-target-migration §1.3).
  - `rendering/TextLayoutCoordinator.kt` — `internal class TextLayoutCoordinator` (`:50`):
    hydrate durable plan into cache, keep bind-generation stale defense (design cites `:57-104`).
  - `rendering/ReaderTextLayoutCache.kt`: 12-page cache of hydrated objects/paths only.
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt` —
    `internal class TranslationOverlayView` (`:34`): consumes hydrated plan;
    source-image-space coordinates preserved (design cites `:26-31`, draw `:241-309`).
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt` —
    `open class ReaderPageImageView` (`:60`): passes stored page dimensions (design cites `:143-153,297-305`).
  - `model/PageDisplayProjection.kt`, `model/PageDisplayState.kt` + planner
    (`model/PageWorkPlanner.kt` `:17`): completion semantics flip behind the
    gate check.
  - `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`:
    add `translationBatchPersistedLayout()` (T924-FF-02).
- **NEW files:** `rendering/PersistedLayoutHydrator.kt`.
- **Test entry points:** extend `rendering/TextLayoutCoordinatorTest.kt`,
  `rendering/ReaderTextLayoutCacheTest.kt`, `model/PageDisplayReadinessTest.kt`,
  `model/PageDisplayProjectionTest.kt`, `webtoon/WebtoonOrientationAndPanelTest.kt`.
  NEW: `rendering/PersistedLayoutHydrationTest.kt` (planner-invocation counter
  == 0 on restart/LRU binds; corrupt/missing/legacy fallback; FF-02 off parity).
- **Hooks:** user-edit authority (stale hydration rejected), candidate-vs-committed.
- **Key risks:** completion-semantics regression for legacy chapters; hydration
  latency; cache pressure.
- **Exit pointer:** gates §2.7 rows 7.3-7.8 (evidence/stage7/).

## WP10 — lifecycle/device/provider evaluation

- **Goal:** full-matrix evaluation of retained legacy and new flagged paths:
  200-page stress, process death at every phase, low-memory/thermal, CJK
  identity/gender, mixed-theme lexical, malformed-provider, queue/service,
  Pager/Webtoon, Manual/Auto regression; cost/reliability/quality protocol
  results; rollback demonstration.
- **Stage mapping:** Stage 8 (evidence production; no product behavior change
  beyond what earlier WPs shipped behind flags).
- **Source entry points:** none change. Harnesses used:
  `coexistence/TranslationCoexistenceHarness.kt`, `coexistence/FakeEngines.kt`,
  `rendering/Page15MockRig.kt`, `pipeline/batch/BatchStageInvocationCounters.kt` (test helpers, VERIFIED present).
- **NEW files:** device/provider run scripts/logs live under
  `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/evidence/stage8/`;
  new instrumentation-only test hooks (trace counters) inside existing
  diagnostics: `pipeline/batch/BatchTranslationDiagnostics.kt`,
  `diagnostics/TranslationTraceTest.kt` idiom.
- **Test entry points:** re-run gates §2.8 rows 8.1-8.7 + cross-stage §2.9;
  suites enumerated in flags doc §2.8 (existing suites all listed in
  `evidence/stage8/exit-report.md`).
- **Hooks:** all six invariants re-proven on device matrices.
- **Key risks:** post-hoc acceptance — every threshold was fixed in the gate
  tables BEFORE runs; deviations require Director decision.
- **Exit pointer:** gates §2.8; Director accepts default enablement.

## WP11 — default enablement

- **Goal:** flip T924-FF-01 default to ON (and later FF-02 per its own gate)
  only after WP10 evidence is accepted; expose the advanced toggle
  (T924-FF-01g); start the rollback-window clock (T924-FF-01f).
- **Stage mapping:** Stage 8 (end).
- **Source entry points:** `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
  (default value change), `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt`
  (visibility), `pipeline/batch/BatchChapterTranslator.kt` (`:622` dispatch
  unaffected — flag default only).
- **NEW files:** none.
- **Test entry points:** extend `translator/StrictConfigFromPrefTest.kt` idiom
  for default-value coverage; full flag-matrix parity suites (flag on/off ×
  Pager/Webtoon).
- **Hooks:** all six invariants (default-on must not weaken any).
- **Key risks:** enabling before provider quotas (decisions 12-13) accepted;
  enablement creeping into FF-02 scope.
- **Exit pointer:** gates §2.8 row 8.8 + Director acceptance.

## WP12 — contextual-AI PROBE cleanup after rollback window

- **Goal:** after the T924-FF-01f window closes without rollback, retire
  contextual-AI-only progressive OCR discovery and physical PROBE ownership;
  never delete shared legacy mechanisms earlier (non-contextual lanes and
  rollback surface keep them).
- **Stage mapping:** Stage 8 (post-window).
- **Source entry points (deletion/narrowing targets, all VERIFIED present):**
  - `pipeline/batch/BatchAdmissionProbe.kt` — `internal object BatchAdmissionProbe` (`:32`),
    `BatchAdmissionDecision` (`:21`): contextual-lane ownership only.
  - `pipeline/batch/BatchLaneWorkers.kt` (`:90`): AI planner admission +
    decoded `nativeHandoff` across the OCR barrier (contextual path).
  - `pipeline/batch/SequentialBatchCoordinator.kt` (`:46`, `runPass1` `:59`):
    retained for non-contextual lanes; only its contextual-AI callers removed.
  - `translator/contextual/StreamingChunkPlanner.kt` (`:21`): retained for
    Manual/Auto/legacy callers.
  - `store/ChapterGlossaryStore.kt` (`:17`): legacy compatibility retained.
- **NEW files:** none.
- **Test entry points:** delete/adjust contextual-only cases in
  `pipeline/batch/SequentialBatchCoordinatorTest.kt`,
  `translator/contextual/StreamingChunkPlannerTest.kt` (contextual-AI portions);
  keep non-contextual coverage green (`coexistence/NormalMangaIsolationTest.kt`,
  `coexistence/StandardLaneMultiPageCompletionTest.kt`).
- **Hooks:** page atomicity, gap-free context, one-envelope-in-flight (remain
  for every surviving path).
- **Key risks:** deleting a mechanism still used by non-contextual lanes;
  removing the rollback surface early.
- **Exit pointer:** flags doc T924-FF-01f window proof + Stage 8 exit report
  addendum.

---

## Conflicts recorded

1. **Citation drift (VERIFIED):** `AiTranslationRetryController` is a file
   name, not a class at HEAD; the controlling symbols are
   `AiTranslationRetryPolicy`, `AiChunkOutcome`, and
   `translateAiChunkWithAdaptiveRetry` (flags doc §5.1). WP6 hooks updated
   accordingly.
2. **Path correction (VERIFIED):** `ChapterTranslationStore.kt` is at the
   translation package root, not under `store/` (flags doc §5.2).
3. **`checkpointOcr` does not exist at HEAD** — WP2 creates it; no WP may
   assume it earlier (flags doc §5.3).
4. **Mixed-response commit policy** is Director decision 6; WP6 gate 5.2 flips
   with the decision record (flags doc §5.4).
5. **Persisted-layout product requirement placement** (finding 6) is RESOLVED:
   the requirements catalog Part 3D places them as T924-R036..R041 (corrected
   per stage0-review F-2); WP8/WP9 authorization gates on Stage-7 evidence only.
