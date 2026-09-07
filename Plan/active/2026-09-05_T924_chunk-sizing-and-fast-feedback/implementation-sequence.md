# T924 implementation sequence — smallest, safest, fastest

Date: 2026-09-05 · Base: `adbe643` · Branch: `t924/batch-profile-pipeline` ·
Worktree: `..\TachiyomiAT-t924-impl` (see `REPO_HEALTH.md`)
Status: Director accepted Stage-0 briefs DB-01..DB-13 (2026-09-05, "unless a
concrete contradiction in current code" — none exists: the Stage-0 review
re-verified 31/31 load-bearing citations and ~122 work-package entry points at
HEAD). This document converts the Stage-0 contracts into the implementation
order. Priority order applied throughout: correctness/data safety > resume/crash
safety > reader responsiveness > memory safety > speed > throughput.

Source of truth for all contract details: `stage0/` (esp.
`contracts-state-transactions.md` T924-ST/TX, `contracts-schemas-fingerprints.md`
T924-SC/FP, `contracts-provider-analysis.md` T924-AP, work-packages.md entry
points, feature-flags-stage-gates.md gate tables). Requirement IDs cited from
`stage0/requirements-catalog.md`.

## Verdict on the Director's proposed ordering

**Accepted with four corrections** (none reopens a settled decision):

1. **Step 2 (persisted layout) early is allowed with guardrails** — it is not
   unsafe if (a) FF-02 defaults OFF, (b) the async `TextLayoutPlanner` fallback
   is always retained (R039), (c) completion semantics are NOT redefined in
   Step 2 — the `DISPLAY_READY` redefinition stays gated on Pager+Webtoon
   hydration coverage (gate 7.8) and happens only in Step 7. Scope note: the
   reader benefit accrues to **Batch-prepared pages only**; Manual/Auto pages
   keep async planning by accepted design (R039), so "fixes reader re-layout
   cost" means "for Batch-completed chapters".
2. **Step 4 (pure planners) must not be serialized behind Step 3** — both
   depend only on Step 1 (audit WP graph: WP3 ∥ WP2/WP4). Run Step 4 as a
   parallel track or before Step 3; serializing it wastes calendar time and
   buys nothing (planners are provider-free, device-free pure logic).
3. **One manifest v3 bump in Step 1 covering ALL pointer kinds** — including
   `layoutPlans`/`colorPreparations` even though the layout track lands in
   Step 2. A second schema bump mid-flight would re-risk the DB-05 read-only
   rollback tradeoff.
4. **Two missing reuse fixes have homes**: R012 (forced-OCR reuse decoupled
   from inpaint readiness, `PageWorkPlanner.canReuseNative`) belongs in Step 1
   — it is the same evidence-input area and tiny; R013 (sparse stream→download
   rekey) belongs in Step 3 — the preflight reuse plan is the first consumer
   that needs it.

The 15-RPM Batch sub-limit (DR-C/DR-D nested buckets) is needed by Step 5
(analysis traffic), not Step 6 — it lands with Step 5.

## Sequence (S1..S8)

Legend: WPn = work-package IDs from `stage0/work-packages.md` (verified entry
points there); gates = `stage0/feature-flags-stage-gates.md` §2 rows.

```text
S1 WP1+WP2  persistence foundations + checkpointOcr        (no flags; additive)
   |
   +--> S2 WP8+WP9  persisted layout track   [FF-02, parallel track]
   |
   +--> S3 WP4  OCR-preflight coordinator    [FF-01]  (includes R013)
   |      |
   |      +--> S5 WP5 analysis + frozen profile (incl. 15-RPM sublimit)
   |             |
   |             +--> S6 WP6 profile-aware translation + split/backoff
   |                    |
   |                    +--> S7 WP7 inpaint overlap; then WP9 completion gate
   |                           (DISPLAY_READY redefinition, gate 7.8)
   |
   +--> S4 WP3  pure planners (corpus/chunk/pre-merge/matcher/envelope/
                taxonomy/split ledger)  — PARALLEL with S3/S2; provider-free
   |
   ... S8 WP10-12 evaluation, default-on, PROBE cleanup (after S7 evidence)
```

Strictly serial spine: **S1 → S3 → S5 → S6 → S7 → S8**.
Independent/parallelizable after S1: **S2** (needs S1 DTOs/publication only),
**S4** (needs S1 profile-domain DTOs only). S2 ∥ S4 ∥ S3.

### S1 — Persistence foundations + checkpoint transaction (WP1+WP2)

Everything additive; no runtime consumer; old behavior byte-identical.

- Manifest `SCHEMA_VERSION` 2→3 with all new pointer kinds (T924-SC-04).
- DTOs + canonical JSON + unknown-version/corruption rules (T924-SC-01..22):
  `artifact/ChapterRunRecord.kt`, `PageOcrCheckpoint.kt`,
  `AnalysisChunkResult.kt`, `ChapterTranslationProfile.kt`, `EnvelopePlan.kt`,
  `ChapterDrawPlan.kt`, `ColorStylePreparation` (schema only for the last two —
  layout logic is S2).
- Semantic fingerprints in `artifact/StageFingerprints.kt` (T924-FP-01..09).
- `checkpointOcr` transaction in `ChapterTranslationStore.kt` +
  `ChapterArtifactStore.kt` (T924-TX-01..12 **including TX-03.1
  ADOPT-COMMITTED**), lease ordering per TX-06; CAS helpers reused verbatim.
- R012: `model/PageWorkPlanner.kt` force path decoupled from inpaint readiness
  (evidence-input extension; `BatchResumePlanner` already supplies expected
  fingerprints).
- Publication through existing `AtomicChapterDocuments` / `publishManifestInternal`
  only (T924-SC-19/20); sidecar dirs + retention reachability (T924-SC-21).

Files touched (main): `artifact/ChapterArtifactManifest.kt`,
`artifact/ChapterArtifactStore.kt`, `artifact/ChapterDocumentIo.kt` (if pointer
io helpers needed), `artifact/ArtifactContracts.kt`, `artifact/StageFingerprints.kt`,
`artifact/ChapterArtifactLayout.kt`, `artifact/ArtifactRetention.kt`,
`ChapterTranslationStore.kt` (root), `model/PageWorkPlanner.kt`,
`TranslationStageContracts.kt` (if patch types extend). Tests:
`artifact/ChapterRunRecordSchemaTest`, `artifact/SemanticFingerprintTest`,
`artifact/SidecarCrashPublicationTest`, `store/OcrCheckpointRebaseTest`,
extensions to `AtomicChapterDocumentsTest`, `StageFingerprintsTest`,
`ChapterArtifactStoreTest`, `ChapterTranslationStorePhase3Test`,
`coexistence/D1OriginPriorityTest`, `ChapterTranslationStoreRekeyTest`.

**Milestone M1 (the safety gate):** OCR page → `checkpointOcr` → lease/bitmap
release → simulated process restart → fresh MANUAL/AUTO/BATCH candidate reuses
the checkpoint; stale writer rejected; committed display preserved. Proven at
store level with fault injection — no coordinator needed.

Tests before advancing: gates 1.1-1.7 (old manifests load; unknown version
predictable; crash-safe publication at every boundary; semantic-equal ⇒ equal
hash; user-edit/display authority survives; checkpoint fault matrix; whole
existing suite green). Rollback: `git revert` — nothing consumes the new code.

### S2 — Persisted layout track (WP8+WP9, FF-02 `translation_batch_persisted_layout`)

Independent of S3-S6; starts after S1. Publishes from the CURRENT legacy Batch
render join (so it is testable immediately and helps readers now).

- WP8: `rendering/TextLayoutPlanner.kt` serializable projection (NOT
  `BlockLayout`); `rendering/DrawPlanFingerprint.kt` (T924-FP-07: font asset
  sha256, typeface/style, measurement flags, planner + stroke-policy version,
  platformShapingKey = SDK-int conservative, sample size, page dims);
  `RenderColorEstimator` version id.
- WP9: `pipeline/batch/BatchRenderJoin.kt` publishes draw plan + color/style as
  separately invalidatable sub-results behind FF-02 with the full CAS
  precondition set (T924-TX-23); `rendering/PersistedLayoutHydrator.kt`;
  `rendering/TextLayoutCoordinator.kt` prefers valid plan, keeps bind-generation
  stale defense; `rendering/ReaderTextLayoutCache.kt` holds hydrated objects
  only; `TranslationOverlayView`/`ReaderPageImageView` consume source-image-space
  plan (already do); `TranslationPreferences.translationBatchPersistedLayout()`.

Tests before advancing: gates 7.1-7.7 (round-trip ≤0.5 px; compatibility matrix
mismatch ⇒ replan-never-mis-draw; invalidation rows 9/10/11; stale hydration
rejected; restart/LRU bind with planner-invocation counter == 0 on Pager AND
Webtoon; fallback green for Manual/Auto/legacy/corrupt/FF-off). **Gate 7.8
(DISPLAY_READY redefinition) is explicitly NOT in S2** — it moves to S7.
Rollback: FF-02 OFF (plans ignored, not deleted); revert commit.

### S3 — OCR-preflight coordinator shell (WP4, FF-01 `translation_batch_profile_pipeline`)

`validate/download → RUN_SNAPSHOT freeze → SOURCE_VALIDATION (bounded IO) →
OCR_PLAN → OCR_PREFLIGHT → stop/diagnostic`. Legacy `SequentialBatchCoordinator`
remains the flag-off path; single dispatch point in `BatchChapterTranslator`
(T924-FF-01a).

- New `pipeline/batch/ChapterProfileBatchCoordinator.kt` + `OcrPreflightWorker.kt`
  (loop per T924-ST-06/TX-06: decode → analyze → persist → checkpoint → recycle
  bitmap → release lease → yield-to-interactive check; no inpaint, no analysis,
  no translation).
- Reader-priority native admission in front of the quarantine
  (`pipeline/EngineLane.kt`): yield between pages, bounded anti-starvation
  (thresholds PROPOSED, device-tuned at this step's gate 3.5).
- R013 sparse rekey in `TranslationManager.kt` (stream→download identity
  migration without equal-count precondition).
- Run-record state transitions per T924-ST-02..06, startup resume per ST-16;
  progress phases per R014 (Preparing chapter / OCR extraction n/N).
- `TranslationPreferences.translationBatchProfilePipeline()`; no settings UI
  (T924-FF-01g).

Tests before advancing: gates 3.1-3.8 incl. device run (zero provider calls
before full preflight; one-decoded-page trace; flag-off parity suite;
flag-off-mid-run 3 cases; ~200-page on-device memory/thermal/throughput with
baseline-at-same-commit). Rollback: FF-01 OFF (kill switch; in-flight runs
complete under their started path per T924-FF-01e).

### S4 — Pure planners (WP3) — parallel track, provider-free

- `translator/contextual/OcrCorpusManifest.kt`, `AnalysisChunkPlanner.kt`,
  `ProfilePreMerger.kt`, `RelevantProfileMatcher.kt`, `GlobalEnvelopePlanner.kt`,
  `translator/retry/ResponseTaxonomy.kt` + shared root-budget ledger in
  `translator/retry/TranslationRetry.kt` (children cannot construct fresh
  budgets); extend `ContextualResponseParser.kt` with page-level completeness.
- StreamingChunkPlanner untouched (legacy callers).

Tests before advancing: gates 2.1-2.5 (golden fixtures: empty/textless, sparse,
200-page synthetic, boundary overlap, conflicting facts, future-fact scoping,
oversized single page, reordered/duplicate/unknown IDs, refusals, missing-only;
byte-determinism ≥100 seeded iterations; no child escapes 8/3/4 caps; no
partial-page commit or frontier advance). Rollback: dead code behind no flag —
revert.

### S5 — Chapter analysis + frozen profile (WP5)

**Status (wave 5, 2026-09-06):** slices A + B COMPLETE (`66fda24`/`f1cdde6`/
`d08bfad`/`28a75c5`); Stage 5 COMPLETE pending the provider-package transport
wiring — see §Wave-5 review obligations.

- `translator/analysis/AnalysisProvider.kt` (typed API per T924-AP-01..08;
  `promptText` forbidden), `AnalysisChunkExecutor.kt`, `ProfileReconciler.kt`,
  `ProfileFreezer.kt`; analyzer default = translator's provider+model (DB-07).
  **Reconcile/freeze DONE slice B (`d08bfad`:
  `translator/contextual/ProfileReconciler.kt` +
  `pipeline/batch/ProfileFreezePublication.kt` realizes the freezer role).**
- Governor: Batch sub-limit nested buckets (DR-D) + DR-C default table in
  `translator/ProviderRequestGovernor.kt` — **this step, not S6**.
- Freeze publication per ST-10/TX-22; skip rules (no-work/textless/compatible
  profile) per R035; small-chapter bypass instrumented but disabled (DB-10).
  **Reconcile/freeze + coverage-aware reconcile + ST-10/TX-22 + R035
  frozen-profile skip rule DONE slice B (`d08bfad`, review
  `evidence/wave5-review.md` §5; zero-OCR reuse probe at run start, ST-04
  per-page source-sha revalidation); DB-10 bypass still OWED.**
- Glossary stores untouched by the new path (INV-20).

Tests before advancing: gates 4.1-4.8 (validation hard-fails incl. evidence-hash
recompute; resume at first invalid chunk; UNKNOWN/CONFLICTING survive; weak
evidence never promotes gender; no future leakage; freeze immutable; no canon
promotion; shared 15-RPM trace with interactive priority; ≥1 real-provider
evaluation ≥95% structured acceptance). Rollback: FF-01 OFF for analysis-bearing
flows; committed per-page translations unaffected.

### S6 — Profile-aware translation (WP6)

**Status (wave 7a, 2026-09-06):** STAGE 6 COMPLETE — slice A
(`2e99ffb`/`f2ccb95`, review `evidence/wave6-review.md` ACCEPT — first
clean accept of T924) + slice B (`65e4a25`/`1ae5874`, review
`evidence/wave7-review.md` ACCEPT — second clean accept, fixes landed:
profile-subset prompt enrichment, gap-free rolling context,
execution-time token recompute with whole-page-boundary splits,
observability counters). Stage 7 in progress — see §Wave-7a review
obligations.

- `pipeline/batch/ProfileEnvelopeExecutor.kt` (dispatch + TX-21 revalidation +
  deterministic suffix re-plan), `translator/retry/StructuralSplitPlanner.kt`
  over the frozen-envelope retry driver (`translateAiChunkWithAdaptiveRetry`);
  profile-subset + scene-context + gap-free history in `TranslationPrompts.kt`
  (identity/gender evidence rules per §7 of the design).
- DR-A Option 1 retention semantics (commit independently complete MISSING_ONLY
  pages; AMBIGUOUS/REFUSAL discard) — flips only via a recorded Director change.
- Translation commits carry `profileContentFingerprint` + block identity
  (T924-TX-20/INV-25). **TX-20 DONE slice A (`2e99ffb`):** the FIRST
  legacy-merge-path touch, additive-nullable by design —
  `TranslationStagePatch` trailing nullable `profileContentFingerprint` +
  `envelopePlanFingerprint`; both-null fast path before any manifest read;
  mismatch rejects the whole patch before any block mutation; rejected
  commits never advance the frontier; reviewer-verified byte-identical for
  the legacy path (the runtime legacy Batch commits through the
  lambda-based `guardedBatchUpdate`, never this stage-contract API).

Tests before advancing: gates 5.1-5.8 (page atomicity across taxonomy classes;
mixed-response matches DR-A; stale commits fail; envelope-policy-only change
reuses translations; Manual/Auto completions skipped; gap-free frontier; one
envelope in flight; provider measurements vs same-commit flag-off baseline).
**First full old-vs-new A/B happens here.** Rollback: FF-01 OFF.
**Recorded notes on the gate rows (wave-7a review, `evidence/wave7-review.md`):**
gate 5.7 — the `promptShapeEnriched`/`promptShapeLegacy` counters count
BUILT chunks and `envelopeSplits` counts PLANNED splits, NOT sent
requests (F-W7-3); reconcile against the per-envelope logcat lines /
provider records before quoting request counts. Gate 5.6 — dense
single-block pages trim the rolling pairs entirely (the source side
alone exceeds the 1500-token cap; the frontier keeps full history;
F-W7-4) — if the A/B shows identity drift on dense chapters, per-line
pair truncation is the candidate remedy.

### S7 — Inpaint overlap + layout preparation completion (WP7 + WP9 gate)

- `pipeline/batch/OverlapScheduler.kt`: serial local inpaint during the single
  in-flight remote request, after profile freeze only; re-decode + durable mask;
  native admission unchanged; keep-or-revert decision rule gate 6.5 (<5%
  median wall-time improvement ⇒ keep serial).
- When translation + inpaint + color are all ready per page: publish persisted
  layout (S2 machinery) → then, and only after Pager+Webtoon hydration coverage
  evidence (gate 7.8), redefine completion as `DISPLAY_READY`. No rasterized
  translation output ever (R041).
- Tests: gates 6.1-6.5, 7.8, then re-run 7.5 on the new-path pages.

### S8 — Evaluation, default, cleanup (WP10-12; separate authorization)

Full matrix, default-on decision (DB-11 numbers), rollback window (2 releases /
6 weeks), then PROBE cleanup. Not scheduled until S7 evidence exists.

## The ten answers (condensed)

1. **Order:** S1 → (S2 ∥ S3 ∥ S4) → S5 → S6 → S7 → S8, with the serial spine
   S1→S3→S5→S6→S7. Director's steps map 1:1 except Step 4 is parallelized and
   Step 7's completion-semantics change is split behind its gate.
2. **Dependencies:** S2, S3, S4 each depend only on S1. S5 needs S3 (resumable
   preflight) + S4 (chunker/pre-merge). S6 needs S5 + S4. S7 needs S6 (+S2).
   S8 needs S7 evidence. Inside S1: schemas before fingerprints before
   `checkpointOcr` before fault tests.
3. **Files:** per-step lists above (verified against `stage0/work-packages.md`,
   whose ~122 entry points the reviewer confirmed at HEAD).
4. **Independent:** S2 (layout), S4 (planners) — both after S1, parallel to S3
   and to each other. Everything on the spine is serial.
5. **Tests per step:** gate rows listed per step above; M1's fault-injection
   matrix (TX-11 B0-BX) is the non-negotiable core of S1.
6. **Rollback points:** one revert-commit per step; flags default OFF (FF-01
   kill-switch per T924-FF-01e/01f; FF-02 plans ignored-not-deleted); S1/S4 are
   consumer-free additions; S8's default-on is the only externally visible
   change and reverts by flag default.
7. **Ordering corrections:** the four in "Verdict" above — layout early with
   guardrails (scope = Batch pages; fallback mandatory; no completion
   redefinition), planners parallelized, single v3 manifest bump, R012→S1 and
   R013→S3, 15-RPM lands in S5. Nothing in the proposed order is unsafe once
   these are applied.
8. **Deferred until device measurement:** detector/OCR cross-page concurrency;
   cross-page OCR fan-out; compressed-byte prefetch (second decoded bitmap
   forbidden regardless); adaptive envelope growth beyond static budgets;
   overlap keep-or-revert (gate 6.5 decides); quota TPM values + LM Studio
   desktop-class reclass (DR-C tunables); native starvation thresholds;
   small-chapter bypass threshold; layout cache size beyond the existing 12-page
   cache; widening platformShapingKey buckets. Native engine warmth and
   no-idle-between-pages come free with the serial S3 loop (yield check is not
   a sleep) — implement now, per the speed rules.
9. **Earliest 200-page OCR-sprint benchmark: end of S3** (gates 3.4/3.6 — first
   time the sprint runs on device; S1 proves checkpoint mechanics only at store
   level).
10. **Earliest old-vs-new A/B:** partial at end of S3 (preflight + reader
    coexistence + reuse vs baseline); **full translation A/B at S6** (gate 5.7
    compares calls/tokens/accepted-blocks/time against same-commit flag-off
    baseline); product-complete A/B including persisted layout at S7.

## Enforcement of the Director's safety rules (binding, restated as gates)

No parallel detector/OCR across pages (INV-16); no inpaint during preflight;
no decoded bitmap across the OCR barrier (INV-05, `HeldBitmapRegistry` trace);
no bulk lease/candidate holding (leases per active page/envelope only);
partial/malformed never advances the frontier (INV-04/22); one root retry
budget (INV-21); committed display never revoked for provenance/profile/settings
change (DB-01 residuals, matrix KEEP column); user edits never overwritten
(INV-07, TX-20 `expectedUserEditedAt`); older schema never writes new data
(SC-04 v3 bump + future-schema guard); Manual/Auto always usable (INV-11,
flag-off parity suite every step).

## Wave-2 review obligations (BINDING on later kickoffs — from evidence/wave2-review.md, 2026-09-06)

Verdict ACCEPT-WITH-FIXES. No blocker/major. Owed items by consuming stage.
**Wave-3 update (2026-09-06):** the S3-remainder, S5-precondition and WP9
items below were executed in commits `c2705c8` / `2cc209e` / `74302ec` /
`a56f232` (review `evidence/wave3-review.md` ACCEPT-WITH-FIXES, fixes
landed); struck items are DONE, remaining items stay owed.
**Wave-4 update (2026-09-06):** gap-3 and D4 below were discharged in
commit `66fda24` (reviewer-confirmed, `evidence/wave4-review.md` §6);
wave-4 obligations are in their own section at the end of this file.

- ~~**S3-remainder kickoff (next S3 slice):** wire the preflight stop into the
  durable failure ledger (`persistUnexpectedBatchStageFailure` idiom) — R2;
  checkpoint-REJECTED mid-run durability test (gap 9).~~
  **DONE wave-3** — ledger + gap-9 test in `2cc209e`; generation-less store
  fix in `74302ec` (cross-restart cap works); F-W3-1 success-path clear in
  `a56f232`.
- **S5 kickoff (analysis/profile, WP5):** F1 — the stage that first publishes
  `ChapterRunState.COMPLETE` must wire `decideResume` into the production
  resume path + dispatch-level flag-off-mid-run test (gap 1)
  **— still OWED (S5)**; ~~queue-restore test extending
  `ChapterTranslatorQueueRestoreTest` (gap 2)~~ **DONE wave-3 (`c2705c8`, +199
  lines, decision-only, no side effects)**; ~~request-builder test pinning
  contributing-set order = core-then-context (gap 3, F4 — also record the
  convention in schemas contract §1.3)~~ **DONE wave-4 (`66fda24` —
  `AnalysisRequestBuilder` fail-fast + `AnalysisRequestOrderTest`; the
  schemas-contract §1.3 note already exists; reviewer-confirmed,
  `evidence/wave4-review.md` §6)**; ~~F2 —
  consolidate `PlannerFingerprints` into `StageFingerprints` (or pin an
  encoder-version constant) BEFORE persisting analysis artifacts, re-pin the
  golden literal (gap 5)~~ **DONE wave-3 (`c2705c8`; goldens byte-valid
  UN-re-pinned, gap 5 not needed)**; ~~`PlannedAnalysisChunk`→
  `AnalysisChunkResult` mapping test incl. status/excerpt-hash boundaries
  (gap 4)~~ **DONE wave-3 (`c2705c8`, `AnalysisChunkMapping` + 6 tests)**;
  ~~F3 — non-AI dispatch gate (engine category parity or coordinator
  fall-through) BEFORE translation stages ship or flag gets any surface
  (gap 8)~~ **DONE wave-3 (`c2705c8`, `profilePipelineDispatchKind` parity
  gate + `ProfilePipelineDispatchGateTest` 4/4)**; ~~real engine/model identity
  replaces `MODEL_HASH_UNSPECIFIED` before analysis fingerprinting freezes
  it (D4)~~ **DONE wave-4 (`66fda24` — `BatchChapterTranslator` run snapshot
  :653-689 freezes `<engine>:<model>` + 16-hex one-way credential signature
  (LM Studio: base URL; else API key); reviewer-confirmed,
  `evidence/wave4-review.md` §6 dimension E).**
- **WP9 kickoff (layout publication/hydration):** ~~production
  `assetSha256(res/font/animeace.ttf)` recorded + pinned (gap 6)~~
  **JVM mechanism DONE wave-3 (`74302ec` — digest loaders installed + cached;
  the real resource hex value still OWED at the Stage-7 device rows)**;
  `platformShapingKey()` exercised once on-device (JVM SDK_INT=0) **— still
  OWED (device)**; ~~hydration-loss contract test — caller detects lossy
  `rehydrate` by count mismatch and falls back per FF-02b (gap 7, F5)~~
  **DONE wave-3 (`74302ec` — typed `HydratedLayout.Lossy` + caller-side typed
  fallback)**; `LAYOUT_PLANNER_VERSION` bump discipline if the planner sort
  ever changes (wp8 dev 5) **— standing rule**; ~~Stage-7 exit oracle files
  `DrawPlanDtoRoundTripTest`/`DrawPlanCompatibilityTest` named by gate rows
  7.1/7.2 are WP9's to deliver~~ **DONE wave-3 (`74302ec` — 7 + 14 tests,
  green)**; bridge production install + wp9-report §6 device rows **— still
  OWED (Stage 7)**.
- **Device gates before default-on:** R1 — measure per-page manifest
  publication cost (gates 3.4/3.6) before any default-on decision; de-slide
  counters to every-N-pages if it shows (correctness-neutral, ST-06)
  **— still OWED (device; unchanged by wave 3)**.
- ~~**Stage-2 exit report:** requires the analysis-chunk golden fixture (F7 —
  being landed now) plus WP3 gate rows.~~ **CLOSED** — F7 landed `ee858f9`;
  exit reports written (`evidence/stage2/exit-report.md`,
  `evidence/stage3/exit-report.md`, both COMPLETE-PENDING-DEVICE-GATES;
  `evidence/stage4/exit-report.md` COMPLETE).

## Wave-4 review obligations (BINDING on later kickoffs — from evidence/wave4-review.md, 2026-09-06)

Review of S5 slice A commit `66fda24`. Verdict **ACCEPT-WITH-FIXES**; both
CRITICAL-direction checks passed (V8 evidence-hash real recompute;
`release(usage=null)` keeps the rolling-window reservation so the 15-RPM
sublimit is real). **Fixes COMPLETE**: all five findings F-W4-1..F-W4-5 fixed
in `f1cdde6` (stale-prefix `validatePersistedPrefix` gate + typed PAUSED
"analysis prefix stale"; shared `SharedBatchRequestSublimitGate` singleton;
durable `AnalysisChunkResult.coverage`; validator tightening
`^e:([0-9a-f]{16})$` + V3-fatal missing term kind; dead relationship-V1
branch removed) and re-verified 548/0 targeted / 1667/0 full. All four §6
deviations and §7 risks RATIFIED (with extensions). Owed items carried to
slice B / the provider-package kickoff:
**Wave-5 update (2026-09-06):** the slice-B obligations this section carried
(profile reconcile/freeze, coverage-aware reconcile, ST-10/TX-22 freeze
publication, R035 skip rule) landed in `d08bfad` (review
`evidence/wave5-review.md` ACCEPT-WITH-FIXES, fixes `28a75c5`); the two
bullet obligations below are restated, with a third condition, in
§Wave-5 review obligations.

- **Provider-package transport wiring (acceptance condition, F-W4-2
  extension):** the transport MUST construct `AnalysisChunkExecutor` with
  `SharedProviderRequestGovernor.instance` AND
  `SharedBatchRequestSublimitGate.instance` — per-executor default instances
  would split the 15-RPM pool at wiring time.
- **`providerKey` spelling alignment (wave-4 review §6 note):**
  `providerKey` uses `lmstudio` (enum name lowercased) where governor
  backends use `lm_studio` — fingerprint-internal only today; align the two
  spellings BEFORE any code compares them.
- Wave-2 F1 (`decideResume` production wiring) stays owed to the first
  COMPLETE-publishing stage — correctly untouched in slice A, which still
  publishes zero COMPLETE runs (review §6).

## Wave-5 review obligations (BINDING on the provider-package kickoff — from evidence/wave5-review.md, 2026-09-06)

Review of S5 slice B commit `d08bfad`. Verdict **ACCEPT-WITH-FIXES**; both
CRITICAL-direction checks passed (the ST-05 reuse probe runs before the OCR
loop with ST-04 per-page source-sha revalidation and zero provider calls by
control flow, proven by recording fakes at the real seams; the FP-05 golden
is genuinely self-verifying). **Fixes COMPLETE**: F-W5-1 MEDIUM
(oversized-alias drop in `boundedAliases` + validator-side overlong
`sourceNames`/`titles` V4 rejection — the deterministic
PERSISTENCE_REJECTED resume wedge) and F-W5-2 LOW (scene participant remap
keyed on the FINAL post-demotion ENTITY_IDENTITY facts) both fixed in
`28a75c5`, re-verified 581/0 targeted / 1700/0 full. All 7 deviations
RATIFIED. Slice-B obligations from the wave-4 ledger (profile
reconcile/freeze, coverage-aware reconcile, ST-10/TX-22, R035 skip rule)
LANDED in `d08bfad`. **Stage 5 is COMPLETE pending the provider-package
transport wiring.**

Provider-package transport-wiring kickoff carries THREE acceptance
conditions:

1. **SHARED governor + gate instances (F-W4-2):** the transport MUST
   construct `AnalysisChunkExecutor` with
   `SharedProviderRequestGovernor.instance` AND
   `SharedBatchRequestSublimitGate.instance` — per-executor default
   instances would split the 15-RPM pool at wiring time.
2. **`providerKey` spelling alignment (wave-4 review §6 note; wave-6
   F-W6-4 extension):** `providerKey` uses `lmstudio` (enum name
   lowercased) where governor backends use `lm_studio`, and the envelope
   work builder now derives the DR-D bucket key from
   `providerKey.substringBefore(':')` — a SECOND site. Align BOTH sites
   (shared mapping helper or a backend-agnostic bucket key) BEFORE any
   code compares them.
3. **Entity-list length caps (F-W5-1 validator-side):** ~~owed to this
   kickoff~~ **DONE in `28a75c5` — landed, not owed**:
   `AnalysisResponseValidator` V4-rejects overlong `sourceNames`/`titles`
   items and `ProfileReconciler.boundedAliases` drops aliases over
   `MAX_NAME_CHARS` (post-NFC) with an "oversized alias dropped" note.
   Recorded here so the kickoff verifies the anchor rather than re-opens
   the gap.

Also carried:

- Wave-2 F1 (`decideResume` production wiring) remains OWED — slice B
  publishes zero COMPLETE runs (every terminal is PAUSED or
  PERSISTENCE_REJECTED); owed to S6, the first COMPLETE-publishing stage.
  **Wave-6 update:** slice A (`2e99ffb`) also publishes zero COMPLETE runs
  — F1 is now expected at Stage 7's completion work, not S6 slice A
  (§Wave-6 review obligations).
- Completion-stage note: an all-MISSING_ONLY chunk set freezes a VALID
  EMPTY profile (contract-consistent, pinned by tests) — the completion
  stage must account for this when wiring skip-to-FINALIZE (wave-5 review
  §3 residual note).
- DB-10 small-chapter bypass instrumented-disabled stays owed (unchanged).
- Gates 4.1-4.8 incl. ≥1 real-provider evaluation ≥95% structured
  acceptance ride the transport wiring.

## Wave-6 review obligations (BINDING on later kickoffs — from evidence/wave6-review.md, 2026-09-06)

Review of S6 slice A commit `2e99ffb`. Verdict **ACCEPT** — the first clean
accept of T924 (no CRITICAL/HIGH; all nine dimensions pass; reviewer
independent rerun 597/0 matched exactly + legacy-adjacent suites green).
All 5 deviations RATIFIED. **Fixes COMPLETE**: F-W6-1 LOW (transient BATCH
lease held on the TX-21 drift/lost-page early-return paths — release before
both early returns) and F-W6-2 LOW (untested coordinator-level ST-11
plan-reuse branch — run-1-terminal-failure/run-2-reuses-plan pin with zero
republication, zero re-OCR) both fixed in `f2ccb95`, re-verified 598/0
targeted / 1717/0 full. Two findings carried:

- **FP-06 durable provenance home (+ reuse-invalidation matrix row 6):**
  the validity-critical half of FP-06 (stale-profile/plan identity at
  commit) LANDED via the TX-20 patch fields; the RECORDING side (translator
  signature + protocol version, prompt version, per-block source hashes,
  per-envelope aggregate) needs a schema/manifest extension (SC-14-class
  evaluation). Owed to the retrans-validation/evidence stage; must land
  before any "retranslate under same provenance" reuse claim is made.
- **F-W6-3 store strict-mode consideration:** `mergeTranslationLocked`
  applies per-block and commits a page when ANY block applied even if
  others rejected (pre-existing; unreachable in slice A — the full M4
  ladder rejects the whole patch first). Consider store-level strict mode
  (reject when `rejectedTargets.isNotEmpty()`) at Stage 7 layout commits,
  where per-block CAS + page atomicity interact again.
- **`lmstudio` vs `lm_studio` spelling (F-W6-4 extension):** now TWO sites
  — `providerKey` itself AND the envelope work builder's DR-D bucket key
  derived from `providerKey.substringBefore(':')`; the provider-package
  alignment must cover BOTH sites (shared mapping helper or a
  backend-agnostic bucket key). See §Wave-5 review obligations condition 2.
- Wave-2 F1 (`decideResume` production wiring) still OWED — expected at
  Stage 7's completion work, not S6 slice A (slice A publishes zero
  COMPLETE runs; every branch returns PAUSED or PERSISTENCE_REJECTED;
  render is Stage 7).

## Wave-7a review obligations (BINDING on the Stage-7 kickoff — from evidence/wave7-review.md, 2026-09-06)

Review of S6 slice B commit `65e4a25`. Verdict **ACCEPT** — the second
clean accept of T924 (reviewer independent rerun 613/0; all 5
deviations RATIFIED; no D2 flake on the reviewer run). **Fixes
COMPLETE**: F-W7-1 LOW (full-range context reserve not a strict upper
bound — AVAILABLE_FROM first-page shift + cap-tail asymmetry; per-
CANDIDATE sub-batch recompute in `splitForTokenFit`) and F-W7-2 LOW
(`usableAt` lenient null defaults — default-DENY RANGE_SCOPED-without-
range and AVAILABLE_FROM-without-page, malformed scoped forms still
text-match yet stay excluded) both fixed in `1ae5874`, re-verified
614/0 targeted / 1733/0 full. **STAGE 6 is COMPLETE** (slices A + B).
Recorded notes carried:

- **F-W7-3 (gate 5.7 readings):** the `promptShapeEnriched`/
  `promptShapeLegacy` counters count BUILT chunks and `envelopeSplits`
  counts PLANNED splits — not sent requests. The Director's old-vs-new
  A/B must reconcile against the per-envelope logcat lines / provider
  records before quoting request counts.
- **F-W7-4 (dense pages):** dense single-block pages trim the rolling
  pairs entirely (the source side alone exceeds the 1500-token cap;
  the frontier keeps full history). If the gate 5.7 A/B shows identity
  drift on dense chapters, per-line pair truncation is the candidate
  remedy.

Stage-7 owed items (status 2026-09-07):

- **Wave-2 F1 — `decideResume` production wiring: CLOSED (2026-09-07,
  commit `45216e7`).** Stage 7 had already wired
  `resumeCompletedOutcome` (the production replacement) into
  `BatchChapterTranslator.runBatchPass1` before coordinator
  construction; the remaining obligation was the dispatch-level
  flag-off-mid-run test (gap 1) — now `BatchDispatchResumeWiringTest`
  (coexistence harness, real shell): OFF+COMPLETE finishes the chapter
  with zero schedule work; OFF+interrupted flagged run starts the
  legacy schedule with T924 sidecars untouched. 2/2 green + 15
  neighboring FF-01e tests green.
- **Independent review of Stage 7 (`3dd1181`) + wave-7c (`571b9f1`):
  DONE (2026-09-07) — PROCEED WITH FIXES.** F-1 HIGH (analysis
  double-admission: every chunk stalled to its foreground-wait deadline
  and paused under `maxInFlight=1`; FF-01 could never freeze a profile
  with a real engine) — FIXED in `45216e7` with a self-admitting
  transport regression test on a real `maxInFlight=1` governor.
  Remaining review findings (all flagged-path-only, zero FF-OFF
  regression): F-2 MED no FINALIZE-resume branch (crash between
  FINALIZE and COMPLETE re-runs the machine from RUN_SNAPSHOT);
  F-3 MED COMPLETED returned even when the record publication was
  rejected; F-4 MED TreatAsFinished not gated on per-page display
  commitment (inpaint-FAILED pages could be retired as finished);
  F-5/F-6/F-7 LOW (overlay bind-time key, retention outside
  NonCancellable, observability nits). Recommend F-2/F-3/F-4 as the
  next flagged-path wave.
- **F-W6-3 store strict-mode consideration:** at Stage 7 layout
  commits, where per-block CAS + page atomicity interact again,
  consider store-level strict mode (reject when
  `rejectedTargets.isNotEmpty()`).
- **Bridge production install + wp9-report §6 device rows:** the real
  font digest hex value + `platformShapingKey()` on-device exercise +
  the Pager/Webtoon holder legs (wave-3 remains, unchanged).
- **Gate 7.8 device evidence before any DISPLAY_READY activation:**
  Pager AND Webtoon hydration coverage; completion semantics are NOT
  redefined before it.
- **DB-10 small-chapter bypass:** instrumented-disabled stays; the
  threshold decision is a Stage-8 measurement decision (unchanged).
