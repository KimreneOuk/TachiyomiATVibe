# T924 Stage 0 — feature flags, quantitative stage gates, and stage process

> **STATUS (2026-09-12, supersedes the tables below):** T924-FF-01
> (`translation_batch_profile_pipeline`) COMPLETED its A/B lifecycle and was
> REMOVED in the zero-legacy wave — the profile coordinator is the only
> Batch pipeline and dispatch is engine-category-only. T924-FF-02
> (`translation_batch_persisted_layout`) remains staged OFF pending
> gate-7.8 device evidence. This document is retained as the historical
> contract basis — the T924-FF-xx clause IDs it defines are still cited by
> code KDoc and by `implementation-sequence.md` (the authoritative record
> of what shipped).

Date: 2026-09-05
Baseline: `adbe643` (verified: `git rev-parse HEAD` = `adbe643df9d99953202dbb8c93c5dd7e504bfd6c` at the time of writing)
Owner work item: T924 Stage 0 item E
Namespace: **T924-FF-01..** (flag/gate clauses). WP0-WP12 IDs are kept from
`engineering/delivery-readiness-audit.md`.
Labels: **VERIFIED** = confirmed in the repository at HEAD `adbe643` (path + symbol cited).
**PROPOSED-GATE** = a proposed numeric default or process rule that requires
explicit Director acceptance before it becomes binding. Nothing numeric in this
document is accepted until the Director accepts it.

Precedence (per README and delivery audit): explicit Director decision >
product invariants > `design/final-target-migration.md` >
`design/chapter-profile-batch-design.md` > this stage-0 spec. Conflicts are
recorded in §5, not silently resolved.

---

## 1. Feature-flag specification

### 1.1 The app's real settings mechanism (VERIFIED)

The flag spec is defined on top of the mechanism the app already uses; no new
flag infrastructure is authorized.

- Preference store contract: `core/common/src/main/kotlin/tachiyomi/core/common/preference/PreferenceStore.kt`
  (`PreferenceStore`, `getBoolean`/`getString`/`getEnum` accessors) and
  `InMemoryPreferenceStore.kt` in the same package for JVM tests.
- Translation preference home: `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
  (class wraps `preferenceStore`; string keys, e.g. `translation_enabled` at
  `:67`, `translation_experimental_qnn` at `:126`, `translation_diagnostics`
  at `:225`, `translation_rate_limit_safe` at `:240` — hidden/experimental
  boolean toggles already exist as precedent).
- DI registration: `app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt:61`
  (`TranslationPreferences(get())`).
- Settings UI: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt`.
- Runtime read pattern: pipeline code receives settings as values via
  `TranslationSettingsSummary` (`app/src/main/java/eu/kanade/translation/model/TranslationSettingsSummary.kt`,
  read-only snapshot over `TranslationPreferences`) or reads a `Preference` at
  the point of use (e.g. `StrictConfigFromPrefTest.kt` covers `fromPref`
  coercion).
- Unit-test idiom: plain JUnit tests under `app/src/test/java/…` with kotest
  matchers (`io.kotest.matchers.shouldBe`) and `InMemoryPreferenceStore`
  (see `app/src/test/java/eu/kanade/translation/translator/StrictConfigFromPrefTest.kt:8`).

**T924-FF-00 (VERIFIED, binding):** every T924 flag is a boolean
`Preference` accessor added to `TranslationPreferences` with a
`translation_`-prefixed string key, registered through the existing DI module;
no new flag framework, no remote-config, no per-device experiment service.

### 1.2 Flag registry

| ID | Preference key (proposed) | Grants | Default | First ships in |
|---|---|---|---|---|
| T924-FF-01 | `translation_batch_profile_pipeline` | contextual-AI-Batch coordinator: OCR preflight, analysis/profile, global envelope translation, overlap (the new pipeline of Stages 3-6) | OFF | Stage 3 (first consumer); flag itself may be introduced with Stage 3 code |
| T924-FF-02 | `translation_batch_persisted_layout` | persisted `LAYOUT_PREPARE`: durable draw-plan publication + reader hydration (Stages 7) | OFF | Stage 7 only, per `final-target-migration.md` §1.4 ("Do not make persisted layout a prerequisite for the new OCR/profile pipeline feature flag") |

**T924-FF-02 independence (binding, from final-target-migration §1.4):** FF-01
and FF-02 are independent. FF-02 requires a working FF-01 path (Stage 7 depends
on Stage 3-6 evidence), but FF-01 must be complete, resumable and defaultable
without FF-02. All four combinations must behave sanely; the matrix
(on/off × on/off) is part of the Stage 7 and Stage 8 gates.

**Non-flags (binding decision of this spec):** the 15-RPM Batch sublimit, the
structural envelope budgets (32 blocks / 8 pages experiment), retry depth/leaf
caps, starvation thresholds, and layout cache sizes are **measured constants
with a single owner (WP owning the stage), not feature flags**. They are tuned
via code constants + recorded evidence, and go/no-go on them happens at stage
gates, not at runtime. This prevents flag sprawl and matches delivery-audit
blocking decisions 12-17 being "tunable during flagged evaluation", not user
options. If the Director later wants any of them user-visible, that is a new
clause.

### 1.3 T924-FF-01 — contextual-AI-Batch coordinator flag

- **T924-FF-01a (dispatch point, VERIFIED anchors):** the flag is consulted at
  exactly one runtime decision point: coordinator construction inside
  `BatchChapterTranslator` (`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt:69`,
  class; the current `SequentialBatchCoordinator(` construction at `:622`).
  Flag ON constructs the new `ChapterProfileBatchCoordinator` (WP4);
  flag OFF constructs `SequentialBatchCoordinator` (`pipeline/batch/SequentialBatchCoordinator.kt:46`)
  unchanged. `runPass1` (`SequentialBatchCoordinator.kt:59`) remains the legacy
  entry.
- **T924-FF-01b (default state):** OFF. While OFF, the legacy progressive
  coordinator, `BatchAdmissionProbe`/PROBE handoff
  (`pipeline/batch/BatchAdmissionProbe.kt:32`), and `StreamingChunkPlanner`
  (`translator/contextual/StreamingChunkPlanner.kt:21`) behave byte-for-byte as
  at baseline. Automated proof: existing characterization suite
  `app/src/test/java/eu/kanade/translation/pipeline/batch/Phase0BatchTranslationCharacterizationTest.kt`
  plus the `coexistence/` suites must pass unchanged with the flag off.
- **T924-FF-01c (runtime behavior when OFF — new artifacts never required):**
  when the flag is OFF the runtime must never *require* any Stage 1+ artifact
  (run record, OCR checkpoint, analysis chunk, frozen profile, envelope plan)
  to display, translate, resume a legacy run, or hydrate the reader. New
  sidecars are write-only from the new path; readers treat them as opaque
  unknown files and must skip them (schema namespace T924-SC-* owns the
  unknown-version rule). Reader layout hydration keeps the current
  reader-side asynchronous planner as compatibility fallback
  (`rendering/TextLayoutCoordinator.kt:50`, `rendering/TextLayoutPlanner.kt:540`)
  until FF-02 has shipped *and* completion semantics are redefined (Stage 7).
- **T924-FF-01d (flag capture at run snapshot):** the first act of a flagged
  run is to persist the flag state into the run record
  (`ChapterRunRecord.frozenRunConfig` — schema per T924-SC-*; WP1). The flag is
  read **once per run**; settings changes mid-run follow the design's
  "settings apply next run; apply-now = stop, new generation, re-plan" rule
  (`chapter-profile-batch-design.md` §3.1 RUN_SNAPSHOT).
- **T924-FF-01e (flag flipped OFF while a flagged run is in flight) — specified
  semantics (PROPOSED-GATE for Director acceptance):**
  1. The in-flight run **safely completes under the path it started with**
     (resume-on-old-path is NOT used mid-run; the run already holds durable
     new-path state that the legacy coordinator cannot interpret).
  2. After flag-off, **no new flagged run may start** and **no queue-restore or
     process-restart may re-enter the new path**: at resume the recorded
     `frozenRunConfig.flagProfilePipeline` is honored only if the current flag
     is still ON; if the flag is now OFF, the restored chapter is (a) finished
     if the new path already committed final per-page displays, or (b) dropped
     to the legacy path using only legacy artifacts (committed displays,
     glossary pointer), with new-path sidecars left untouched for later
     re-enable. The reader never sees a regression: last committed display
     remains visible (candidate-vs-committed invariant).
  3. A run that never reached its first durable publication may simply not
     resume on the new path (it restarts legacy), losing nothing durable.
  Test obligation: a dedicated flag-mid-run lifecycle test (WP4 test file,
  `OcrPreflightFlagOffMidRunTest`) asserting all three cases plus
  queue-restore behavior (`ChapterTranslatorQueueRestoreTest.kt` is the idiom
  to extend).
- **T924-FF-01f (kill switch / rollback window) (PROPOSED-GATE):**
  - The flag is the kill switch at every stage: turning it OFF is always a
    complete rollback of the new pipeline; no data migration is required to
    roll back (all Stage 1+ artifacts are additive).
  - Default-on (WP11) happens only after Stage 8 gates pass. After default-on,
    the legacy progressive contextual-AI path is retained for a rollback
    window of **the later of: 2 stable app releases, or 6 weeks of default-on
    field telemetry without a rollback event**. Only after that window closes
    without a rollback may WP12 delete the contextual-AI-only progressive
    OCR/PROBE ownership. (Matches final-target-migration Stage 8 and delivery
    audit "WP12 must never precede default evidence and a defined rollback
    window".)
  - Rollback trigger conditions during the window: any Stage-8 gate regression
    (see §2.9), crash-attribution to the new coordinator above the baseline
    path rate, or a Director decision. Rollback = ship build with flag default
    OFF; no user data action required.
- **T924-FF-01g (visibility):** PROPOSED-GATE — flag surfaces in
  `SettingsTranslationScreen` as an "Advanced/experimental" toggle only from
  Stage 8 (default-on candidate); during Stages 3-7 it exists in code and is
  settable via the preference store (debug/`InMemoryPreferenceStore` in tests)
  but no user-visible switch, so evaluation traffic is explicit, not organic.

### 1.4 T924-FF-02 — persisted LAYOUT_PREPARE flag

- **T924-FF-02a (dispatch points):** (1) Batch-side: `BatchRenderJoin`
  (`pipeline/batch/BatchRenderJoin.kt:43`) — its color-only "render" body
  becomes `LAYOUT_PREPARE` orchestration only when FF-02 is ON (color prep +
  geometry layout as separately invalidatable sub-results); (2) reader-side:
  `TextLayoutCoordinator` (`rendering/TextLayoutCoordinator.kt:50`) binding —
  prefer a valid persisted plan, else fall back to the current async planner;
  `TranslationOverlayView` (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt:34`)
  consumes the hydrated plan; `ReaderPageImageView`
  (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt:60`)
  continues to pass stored page dimensions.
- **T924-FF-02b (default OFF; fallback mandatory):** with FF-02 OFF (or with
  ON but a missing/invalid/corrupt plan, or for Manual/Auto and legacy data)
  the reader must always be able to fall back to runtime
  `TextLayoutPlanner.planPage` (`rendering/TextLayoutPlanner.kt:599`). This is
  the final-target-migration §8.4 narrowing — "no reader-time planning" only
  for valid Batch-prepared pages.
- **T924-FF-02c (mid-run flip):** layout plans are per-page, published late;
  flipping OFF mid-run means new pages simply do not publish plans and readers
  fall back to async planning; already-published plans stay on disk, are
  ignored by readers when FF-02 is OFF, and are invalidated by the normal
  invalidation matrix. No special recovery needed (PROPOSED-GATE).
- **T924-FF-02d (completion-semantics gate):** `DISPLAY_READY` completion is
  redefined only when "all reader paths can hydrate the durable plan"
  (final-target-migration Stage 7). Until that exit passes on Pager and Webtoon,
  completion semantics stay as today.
- **T924-FF-02e (rollback window):** same formula as T924-FF-01f; WP9's
  reader-binding fallback (async planner) may only be simplified after the
  FF-02 window closes (PROPOSED-GATE).

### 1.5 Flag persistence and queue restore

- **T924-FF-10 (VERIFIED anchors, binding):** flags persist in the normal
  preference store (survive process death by construction). Batch queue
  restore is owned by `TranslationQueueStore` (`translation/TranslationQueueStore.kt:32`)
  and `ChapterTranslator` (`translation/ChapterTranslator.kt:61`, with
  `mergeRestoredQueueEntries` at `:51`); restore must not auto-start a flagged run —
  the existing "queue restore without auto-start" requirement
  (`chapter-profile-batch-design.md` §14 lifecycle list) applies unchanged to
  the new path. Flag state for an interrupted run is re-derived from the run
  record (T924-FF-01d/01e), not from queue entry contents.

---

## 2. Quantitative stage gates for Stages 1-8

Conventions for every gate table:

- **Deterministic suite** = JVM unit test in module `app` under
  `app/src/test/java/eu/kanade/translation/…` following the existing JUnit +
  kotest idiom. New fixture files are listed per stage and live under
  `app/src/test/resources/t924/…` (new directory; VERIFIED that test resources
  are the only shared-fixture channel — existing suites keep fixtures inline or
  in `coexistence/FakeEngines.kt`, `rendering/Page15MockRig.kt` style helpers).
- **Evidence location (binding):**
  `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/evidence/stage<N>/`
  containing at minimum `exit-report.md`, `metrics/` (raw numbers, one file per
  device/provider run), `traces/` (when applicable), and `devices.md`
  (device, OS, app commit, provider/model, configuration, fixture/corpus
  identity, timestamp — per the delivery-audit evidence rule).
- **Baseline comparison rule (binding):** every "regression vs baseline" gate
  compares against a baseline run recorded at the SAME stage's kickoff commit
  on the same device with the flag OFF, stored in
  `evidence/stage<N>/baseline/`. "Baseline" never means a remembered number.
- **Reviewer rule (binding):** each row is pass/fail with a recorded number or
  an attached artifact; a reviewer accepts the stage iff all REQUIRED rows pass
  and every FAIL has a Director-accepted deviation entry in `exit-report.md`.

Memory/latency budget numbers below are **PROPOSED-GATE defaults pending
Director acceptance**, proposed because the design defers constants to
measurement (final-target-migration §7); they exist so a gate can fail, not to
pre-justify the design.

### 2.1 Stage 1 — contracts, artifacts and semantic identity (WP1, WP2)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 1.1 | Old manifests load | REQUIRED | `ChapterArtifactStoreTest`, `LegacyArtifactMigrationTest` extended with a checked-in pre-change manifest fixture (`app/src/test/resources/t924/manifest-v-current.json`) load and reconcile 100% |
| 1.2 | Unknown/new versions fail or downgrade predictably | REQUIRED | New `ChapterArtifactSchemaVersionTest`: unknown version → deterministic error enum (never a crash, never silent reinterpret) |
| 1.3 | Sidecar publication crash-safe | REQUIRED | New `SidecarCrashPublicationTest` using `FakeChapterDocumentIo.kt` fault injection at every write boundary: prior manifest state observable after every injected failure |
| 1.4 | Semantic-equivalent artifacts hash identically | REQUIRED | `StageFingerprintsTest` extended: transaction IDs, filenames, candidate IDs, page versions varied → semantic hashes equal |
| 1.5 | User-edit and committed-display authority survive migration/invalidation | REQUIRED | Extend `ChapterTranslationStorePersistenceTest` + `coexistence/D5GlossaryAwareReuseTest` idiom: user-edited block survives every new migration and invalidation transition |
| 1.6 | `checkpointOcr` CAS contract (WP2, mandatory before Stage 3) | REQUIRED | New `OcrCheckpointRebaseTest`: injected failure at every publication boundary preserves prior manifest; fresh Manual/Auto/Batch candidate starts from checkpoint; stale writer rejected; committed display preserved |
| 1.7 | Manual/Auto and legacy stability | REQUIRED | Whole existing suite set green at exit commit (list in exit report) |

Device/provider matrix: none (pure JVM). Rollback condition: Stage 1 is
additive-only; rollback = revert commit; any REQUIRED failure blocks Stage 2/3
coding, not a runtime rollback.

### 2.2 Stage 2 — pure planning, validation and retry policy (WP3)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 2.1 | Golden fixtures per delivery-audit Stage 2 exit list (empty/textless, sparse, 200-page synthetic, boundary overlap, conflicting facts, future-fact scoping, oversized single page, reordered IDs, duplicates, unknown IDs, refusals, missing-only) | REQUIRED | New suites `GlobalEnvelopePlannerGoldenTest`, `AnalysisChunkPlannerGoldenTest`, `ProfilePreMergeGoldenTest`, `RelevantProfileMatcherTest` over fixtures `app/src/test/resources/t924/golden/*.json` |
| 2.2 | Determinism | REQUIRED | Repeated planning of each fixture produces byte-identical (or declared canonical-form identical) plans; seeded property test ≥ 100 iterations |
| 2.3 | No child escapes root attempt/depth/leaf caps | REQUIRED | `SplitBackoffLedgerTest`: shared root `RequestRetryBudget` (`translator/retry/TranslationRetry.kt:40`) — total attempts ≤ 8 (existing default), depth ≤ 3, leaves ≤ 4 (PROPOSED-GATE numbers = existing 8 kept; depth/leaf pending Director) across randomized fault matrices |
| 2.4 | No partial page commits or frontier advance | REQUIRED | Extend `BatchContextFrontierTest` + new `MalformedResponseTaxonomyTest`: MISSING_ONLY retains only contiguous complete-page prefix; AMBIGUOUS discards parent targets; nothing partial renders or advances the frontier |
| 2.5 | Envelope membership | REQUIRED | Golden: every pending block exactly once, no page split across initial envelopes, oversized single page rejected (page atomicity) |

Device/provider matrix: none. Rollback condition: n/a (pure code behind no
flag); failures block WP5/WP6.

### 2.3 Stage 3 — OCR checkpoint and preflight coordinator (WP4, FF-01)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 3.1 | Zero provider calls before all pages READY/TEXTLESS and checkpointed | REQUIRED | Coordinator phase test with provider fake (idiom: `coexistence/FakeEngines.kt`): call counter == 0 until preflight complete |
| 3.2 | Checkpoint fault matrix | REQUIRED | (from 1.6, re-run through the coordinator) every publication boundary, process-death simulation between pages loses at most the active page |
| 3.3 | One decoded page at a time | REQUIRED | Trace assertion: live decoded-page count ≤ 1 at any sample; `HeldBitmapRegistry` (`pipeline/batch/HeldBitmapRegistry.kt`) reports no leak |
| 3.4 | Bounded memory on device | REQUIRED + **PROPOSED-GATE numbers**: peak Java+native+graphics ≤ baseline + 150 MB AND ≤ 1.5 GB absolute on the reference 6-GB device; bitmap count ≤ 2 transient; no `TranslationMemoryBudget` OOM-deferral storm (> 10% pages deferred) | Instrumented run per `chapter-profile-batch-design.md` §14 "Required device measurements", recorded in `evidence/stage3/metrics/` |
| 3.5 | Reader priority with Batch progress | REQUIRED + **PROPOSED-GATE**: reader OCR-contending wait p95 ≤ 400 ms; Batch yields native lane within ≤ 2 s of an interactive arrival; Batch starvation bound: Batch page progress ≤ 0 pages/10 min under sustained arrivals is a FAIL | Contention/device test recorded with distribution histogram |
| 3.6 | ~200-page real run completes OCR preflight | RECORD (throughput is tuning input, not pass/fail) | wall time, OCR pages/min after warmup, thermal state snapshots |
| 3.7 | Flag-off parity | REQUIRED | `Phase0BatchTranslationCharacterizationTest` + `coexistence/` suites green with FF-01 off at the same commit |
| 3.8 | Flag-off-mid-run semantics | REQUIRED | `OcrPreflightFlagOffMidRunTest` per T924-FF-01e (3 cases + queue restore) |

Device/provider matrix: ≥ 1 physical 6-GB device on Android ≥ 8 (VERIFIED app
minimum), 1 recent-Android device recommended; no provider calls. Rollback
condition: any REQUIRED failure → FF-01 stays OFF; fix forward; if discovered
after partial enable, flag default reverts to OFF (no user action).

### 2.4 Stage 4 — analysis and frozen profile (WP5)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 4.1 | Structured schema validation (terms/entities/scenes/narrative; evidence referential integrity) | REQUIRED | New `AnalysisChunkValidationTest`: unknown page/block refs, duplicate record IDs, missing fields, overlong fields, invalid enums → rejected chunk, never partially applied |
| 4.2 | Resume reuses valid chunks; restarts at first invalid/missing | REQUIRED | New `AnalysisResumeTest` with injected corruption at chunk k: chunks < k reused, ≥ k re-requested; attempt policy respected |
| 4.3 | Authority and evidence rules | REQUIRED | Fixtures from 2.1 re-run at profile level: UNKNOWN and CONFLICTING survive; weak evidence never promotes gender; later explicit gender evidence merges identity without backward plot leakage (range-scoped facts) |
| 4.4 | Frozen profile immutability | REQUIRED | `ProfileFreezeTest`: content hash stable; freeze after freeze rejected; correction candidates never mutate the frozen profile |
| 4.5 | No model fact promoted to series canon | REQUIRED | Assertion in `ProfileFreezeTest`: promotion outputs are candidates only |
| 4.6 | Shared Batch/background quota | REQUIRED | Extend `ProviderRequestGovernorTest`/`ProviderRequestGovernorReservationTest`: aggregate analysis+translation trace respects the 15-RPM rolling Batch sublimit beneath the provider bucket while an INTERACTIVE waiter wins priority (PROPOSED-GATE: 15 RPM is the accepted norm per README open-decision 2 — table per provider still owed by that decision) |
| 4.7 | Real-provider evaluation pass | REQUIRED + **PROPOSED-GATE**: on ≥ 1 real provider (Gemini free tier at minimum), structured-output acceptance without structural retry ≥ 95% of analysis chunks; ambiguous/terminal malformed rate ≤ 5% | `evidence/stage4/provider-eval.md` with model, dates, token counts; fixture transcripts recorded |
| 4.8 | Resume: profile complete / translation absent boundary | REQUIRED | New `ProfileThenTranslateBoundaryTest` |

Device/provider matrix: 1 device + ≥ 2 provider/model pairs (Gemini + one
OpenRouter or DeepSeek). Rollback condition: gate failure keeps FF-01 off for
analysis-bearing flows (the flag stays the kill switch); no runtime rollback of
committed translations (they are per-page ordinary commits).

### 2.5 Stage 5 — global profile-aware translation (WP6)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 5.1 | Page atomicity across normal, missing-only, ambiguous, refusal cases | REQUIRED (invariant: page atomicity) | Fault-matrix test over all taxonomy classes: zero page splits in any durable commit |
| 5.2 | Mixed-response handling matches accepted decision 6 | REQUIRED | `MalformedResponseTaxonomyTest` asserts the Director-accepted commit policy for independently complete pages (current PROPOSED-GATE default: commit-safe-pages-now for MISSING_ONLY, per design §9.2; if Director chooses retain-as-candidates, this row flips with the decision record) |
| 5.3 | Stale profile/source/page commits fail | REQUIRED | CAS tests: profile fingerprint mismatch, source change, page version change → rejected commit |
| 5.4 | Envelope-policy-only change does not invalidate compatible translations | REQUIRED | Fingerprint test: change envelope policy alone → existing translations reused |
| 5.5 | Manual/Auto completions during Batch skipped safely | REQUIRED | Extend `coexistence/D2ManualBatchInterleavingTest` idiom: manual completion + user edit between plan and dispatch → page excluded, suffix deterministically re-planned (user-edit authority) |
| 5.6 | Gap-free frontier | REQUIRED | Extend `BatchContextFrontierTest`: only contiguous fully committed pages advance; fragmented scenario (pages 1,2,4 complete) matches design §14 |
| 5.7 | Provider measurements | RECORD + **PROPOSED-GATE**: time-to-first-accepted-page regression vs same-commit flag-off baseline ≤ +30% (or ≤ 90 s absolute on the 200-page reference fixture, whichever is larger); malformed retry calls ≤ 1.5× baseline; per-envelope accepted-block distribution recorded | `evidence/stage5/provider-eval.md` + metrics |
| 5.8 | One envelope in flight | REQUIRED (invariant) | Trace: ≤ 1 outstanding Batch provider request at any sample |

Device/provider matrix: same as Stage 4 + fragmented-resume scenario on device
(process kill mid-envelope). Rollback condition: FF-01 off; committed pages
remain valid displays under legacy path.

### 2.6 Stage 6 — translation/inpaint overlap (WP7)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 6.1 | No inpaint during OCR phase; no concurrent native-page ownership | REQUIRED | Deterministic scheduler test + `scheduling/NativeRunQuarantineTest` idiom |
| 6.2 | Cancellation/failure join cleanly | REQUIRED | New `OverlapJoinTest`: cancel during either side → consistent store, no orphan lease |
| 6.3 | Stale cleaned-image commits rejected | REQUIRED | Extend `CleanedImagePublisherTest` |
| 6.4 | Peak memory within budget | REQUIRED + **PROPOSED-GATE**: overlap peak ≤ Stage-3 gate + 50 MB | device trace |
| 6.5 | Does overlap help? | RECORD + decision rule: if median wall-time improvement < 5% on the 200-page reference run, retain the simpler serial schedule without changing semantics (per delivery-audit Stage 6 exit) | `evidence/stage6/overlap-vs-serial.md` |

Device/provider matrix: 1 device, 1 provider. Rollback condition: overlap is
internally switchable (constant, not a flag — see T924-FF non-flags clause);
revert to serial schedule on failure.

### 2.7 Stage 7 — persisted LAYOUT_PREPARE (WP8, WP9, FF-02)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 7.1 | Golden round-trip reproduces planner geometry | REQUIRED + **PROPOSED-GATE tolerance**: ≤ 0.5 px error at 1× source-image scale for line origins/extents; exact equality for IDs and coordinates in canonical form | `DrawPlanDtoRoundTripTest` + fixtures |
| 7.2 | Font/platform compatibility boundary defined | REQUIRED | `DrawPlanCompatibilityTest`: matrix over typeface/style, measurement flags, planner version; mismatch → replan (fallback), never mis-drawn text |
| 7.3 | Invalidation matrix | REQUIRED | User translation edit → layout invalidated, OCR/cleaned image not; color-only change → draw/style invalidated, no reflow; stroke-width change → layout invalidated (geometry-affecting, per final-target-migration §3) |
| 7.4 | Stale layout/hydration rejected | REQUIRED | Extend `TextLayoutCoordinatorTest`: bind-generation mismatch drops durable plan (existing stale-bind defense retained) |
| 7.5 | Restart/LRU rehydrate without planner invocation | REQUIRED | Reader instrumentation test: `TextLayoutPlanner` invocation counter == 0 across process restart + LRU eviction binds with valid persisted plans (Pager and Webtoon) |
| 7.6 | Bind latency + serialization size | RECORD + **PROPOSED-GATE**: bind p95 ≤ legacy + 20 ms; serialized plan ≤ 256 KiB/page p95 on the reference fixture | `evidence/stage7/hydration-metrics.md` |
| 7.7 | Fallback correctness | REQUIRED | FF-02 off / corrupt / missing / legacy data / Manual/Auto → async planner path green (extends `rendering/ReaderTextLayoutCacheTest`, `PageDisplayReadinessTest`) |
| 7.8 | Completion semantics redefined only at full hydration coverage | REQUIRED | Gate check: DISPLAY_READY redefinition commit happens only after 7.5 passes on Pager AND Webtoon |

Device/provider matrix: Pager + Webtoon on 2 devices. Rollback condition:
FF-02 off restores reader-time planning everywhere; persisted plans are ignored
(not deleted) so re-enable is cheap.

### 2.8 Stage 8 — evaluation, default and cleanup (WP10, WP11, WP12)

| # | Check | REQUIRED/RECORD | Oracle |
|---|---|---|---|
| 8.1 | Full regression matrix (200-page stress, process death at every phase, low-memory/thermal, CJK identity/gender, mixed-theme lexical, malformed-provider, queue/service, Pager/Webtoon, Manual/Auto) | REQUIRED | Test list = design §14; results in `evidence/stage8/` |
| 8.2 | Cost gate vs baseline | REQUIRED + **PROPOSED-GATE**: first-run total provider calls (analysis+translation+retries) ≤ 2× legacy baseline on the 200-page fixture; profile-resuming run ≤ 1.15× baseline | provider eval traces |
| 8.3 | Quality gate | REQUIRED + **PROPOSED-GATE**: terminology-consistency defects (inconsistent retranslations of the same canonical form) ≤ 50% of legacy baseline on a fixed evaluation corpus; gender/identity errors ≤ 50% of baseline. Measurement protocol: fixed corpus, blind re-check list, two-pass count — protocol itself needs Director acceptance (this is the "quality evaluation protocol" gap from plan-completeness-audit finding 4) | `evidence/stage8/quality-protocol.md` + scored results |
| 8.4 | Memory/thermal/latency gates re-run (3.4, 3.5 numbers) on final build | REQUIRED | device traces |
| 8.5 | Reliability gates re-run (4.7, 5.7) | REQUIRED | provider eval |
| 8.6 | Rollback demonstrated | REQUIRED | A recorded flip of FF-01 (and FF-02) to OFF on a flag-on device: queue restore, reader display, legacy run all behave per T924-FF-01e; trace recorded |
| 8.7 | Legacy/PROBE intact until window closes | REQUIRED | `BatchAdmissionProbe`-owned progressive path still green for contextual lane under flag-off |
| 8.8 | Default enablement | Gate | WP11 flips FF-01 default to ON only when 8.1-8.6 are all green and Director accepts the exit report |

Device/provider matrix: full matrix (both devices, Pager+Webtoon, ≥ 2
providers, baseline+flagged runs). Rollback condition: default reverts to OFF;
WP12 (PROBE deletion) only after the T924-FF-01f window.

### 2.9 Cross-stage gates (every stage)

| # | Check | Oracle |
|---|---|---|
| X.1 | Manual/Auto and legacy stability | Existing suites listed in the exit report all green |
| X.2 | Compile + narrowest relevant test target | `app` unit-test module for touched packages (no gradle builds in Stage 0 itself) |
| X.3 | Traceability | Every implemented requirement ID (T924-R*/T924-INV-*) cited in the stage's exit report with test/evidence pointer (ledger owned by Stage 0 item A) |
| X.4 | Conformance verdict | Independent reviewer verdict recorded (§3) |

---

## 3. Stage kickoff/exit process (operationalizes plan-completeness-audit finding 5)

**T924-FF-20 (kickoff, PROPOSED-GATE as binding process):** at each stage
kickoff the implementing agent writes
`evidence/stage<N>/kickoff.md` containing:

1. Base commit hash (`git rev-parse HEAD`) and date.
2. Re-verification of the load-bearing source assumptions this document and
   the work-package file cite, as path + symbol (not line numbers):
   explicitly re-verified as existing/unchanged-in-role, or flagged as drifted.
   Line citations in older documents are navigation hints only
   (finding 5's rule).
3. Flag states at kickoff (FF-01/FF-02 and the stage's expected value).
4. Baseline runs recorded under `evidence/stage<N>/baseline/` where the stage
   has comparison gates.
5. Any open blocking decision that the stage must not silently resolve.

**T924-FF-21 (exit):** at stage exit, `evidence/stage<N>/exit-report.md`
records:

1. Finish commit hash; branch.
2. Implemented requirement IDs (T924-R*/T924-INV-*) and their test/evidence IDs.
3. Gate table results (each REQUIRED row pass/fail with number or artifact link).
4. Deviations, each as an explicit decision with rationale (never silent).
5. Rollback state: flag defaults, artifact compatibility, how to disable.
6. Independent conformance verdict: a reviewer who did not author the diff
   checks it against the traceability ledger, re-runs the stage gate rows, and
   records accept / accept-with-deviations / reject with reviewer ID. A stage
   is closed only on accept or Director-accepted deviations.

**T924-FF-22 (conflict handling, binding):** any conflict discovered at
kickoff (source drifted, citation wrong, documents disagree) stops the affected
work package and updates the decision register (README open decisions); it does
not invite an implementer to choose silently (delivery-audit precedence rule).

---

## 4. Requirement/invariant hooks used by the gates

Gates reference invariants by well-known name; stable IDs live in the
requirements catalog namespace (T924-R*/T924-INV-*, parallel Stage 0 item A):

- **page atomicity** — gates 2.5, 5.1; never split a page across committed envelopes.
- **one-envelope-in-flight** — gate 5.8; at most one Batch provider request at a time.
- **gap-free context** — gates 2.4, 5.6; only contiguous fully committed pages advance the frontier.
- **one-decoded-page** — gate 3.3; at most one decoded page bitmap at a time.
- **candidate-vs-committed** — gates 1.6, 3.2; committed display never regresses while candidates churn.
- **user-edit authority** — gates 1.5, 5.3, 5.5, 7.3; user edits survive re-plan/resume and reject stale commits.

## 5. Conflicts and citation drift recorded (not silently resolved)

1. **Citation drift (VERIFIED):** documents cite `AiTranslationRetryController`
   as a controlling class; at HEAD the file
   `translator/retry/AiTranslationRetryController.kt` defines
   `AiTranslationRetryPolicy` (`:41`), `AiChunkOutcome` (`:62`), and the driver
   `translateAiChunkWithAdaptiveRetry` (`:230`) — there is no class named
   `AiTranslationRetryController`. The file is the cited unit; the driver
   symbol is authoritative for WP6.
2. **Path correction (VERIFIED):** `ChapterTranslationStore` lives at
   `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` (root of
   the translation package), not under `store/`; `store/` holds
   `PageStageLeaseTable`, `ChapterGlossaryStore`, `ChapterAttemptLedger`.
3. **`checkpointOcr` does not exist at HEAD** (grep: zero matches) — consistent
   with final-target-migration naming it the mandatory first gate (WP2). Not a
   conflict; recorded so no one assumes it exists.
4. **Open conflict owned elsewhere:** whether independently complete pages from
   a mixed malformed response commit immediately or remain candidates is
   Director open decision 6; gate 5.2 encodes the currently proposed default
   and flips with the decision record.
5. **Persisted-layout requirement placement — RESOLVED (stage0-review F-2
   correction):** the product-level requirements and success rationale for
   persisted layout are placed in the requirements catalog as
   **T924-R036..R041** (Part 3D, resolving plan-completeness finding 6).
   WP8/WP9 authorization gates on their own Stage-7 evidence, not on any
   further documentation decision.
