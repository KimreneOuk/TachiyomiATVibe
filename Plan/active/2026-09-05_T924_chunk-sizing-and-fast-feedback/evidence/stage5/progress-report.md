# T924 Stage 5 — Progress report

Task: T924 chapter-profile contextual-AI Batch translation pipeline (S5, WP5).
Branch: `t924/batch-profile-pipeline` · Worktree: `..\TachiyomiAT-t924-impl`.
Companion reports: `s5-preconditions-report.md` (wave 3), `s5a-analysis-report.md`
(slice A implementer report), `s5b-profile-report.md` (slice B implementer
report). Reviews: `../wave4-review.md` (slice A), `../wave5-review.md` (slice B).

## Wave 4 — 2026-09-06 — S5 slice A COMPLETE

Slice A (analysis provider + chunk persistence + Batch 15-RPM sublimit) ran the
full loop and is COMPLETE: implement → verify → review → fixes → re-verify.

- Implement — commit `66fda24` "feat(translation): T924-S5 slice A analysis
  provider + chunk persistence + Batch 15-RPM sublimit" (16 files, +3583/−47).
  Deliverables: `translator/analysis/` client (typed protocol T924-AP-01..08,
  V1-V9 validation with V8 evidence-hash recompute, refusal-first,
  chapter-only authority, ≤2 classified attempts with exactly-one identical
  reissue on malformed and none on refusal); `AnalysisChunkPublication`
  (one validated chunk = one `publishSidecarPointers` transaction, SC-20;
  content-addressed `analysis/f-<sha>.json` sidecar first, atomic
  `analysisChunks` append in chunk-ordinal order, ST-08 resume = persisted-
  prefix skip); `ChapterProfileBatchCoordinator` ANALYSIS_PLAN → ANALYSIS_CHUNKS
  after the COMPLETE preflight barrier (corpus re-derived from durable
  checkpoints, drift = typed PAUSED; MISSING_ONLY commits the empty-valid
  subset per DR-A Option 1; runner==null = typed CONFIGURATION pause; terminal
  stays PAUSED — wave-2 F1 honored); `ProviderRequestGovernor`
  BatchProviderSublimit (15 RPM rolling 60 s, credential-wide model-agnostic
  key, minimumSpacingMs=0, maxInFlight=1, RPM-only v1) + nested
  BatchRequestSublimitGate, all-or-nothing admission, interactive requests
  structurally skip the gate; `BatchChapterTranslator:653-689` run snapshot
  freezes real `<engine>:<model>` identity + 16-hex one-way credential
  signature (owed item D4 discharged).
- Verify — targeted 4-package suites (`translator.*`, `pipeline.batch.*`,
  `coexistence.*`, `artifact.*`) 545/0; full `eu.kanade.translation.*` tree
  1664/0.
- Review — `../wave4-review.md` ACCEPT-WITH-FIXES. Both CRITICAL-direction
  checks passed (V8 real recompute; `release(usage=null)` keeps the
  rolling-window reservation so the 15-RPM sublimit is real). Reviewer
  independently reproduced 545/0. All four §6 deviations and §7 risks
  RATIFIED (with extensions).
- Fixes — commit `f1cdde6` "fix(translation): T924 wave-4 review fixes"
  (8 files, +183/−10): F-W4-1 MEDIUM stale-prefix gate
  (`validatePersistedPrefix` identity validation → typed PAUSED "analysis
  prefix stale" + cross-run regression test); F-W4-2 LOW shared
  `SharedBatchRequestSublimitGate` singleton (executor defaults to it);
  F-W4-3 LOW additive durable `AnalysisChunkResult.coverage`
  (COMPLETE/MISSING_ONLY, defaulted field); F-W4-4 LOW validator tightening
  (evidence-hash regex `^e:([0-9a-f]{16})$`, missing term kind V3-fatal,
  two new fatal-path tests); F-W4-5 NOTE dead relationship-V1 branch removed.
- Re-verify — targeted 548/0 (545 + 3 new tests); full tree 1667/0.

### NEXT — slice B (profile reconcile/freeze)

- `ProfileReconciler` / `ProfileFreezer` per `implementation-sequence.md` §S5:
  hierarchical reconciliation over the durable chunk list, freeze publication
  per ST-10/TX-22, skip rules R035, small-chapter bypass instrumented but
  disabled (DB-10), glossary stores untouched (INV-20).
- Reconcile must consume the durable `coverage` field (F-W4-3 fix):
  MISSING_ONLY chunks stay pending; the summary-only COMPLETE variant is
  covered by the same rule (review §7 extension, RATIFIED).

### Remaining S5 items after slice B

- Provider-package transport wiring — acceptance condition (F-W4-2
  extension): `AnalysisChunkExecutor` MUST be constructed with
  `SharedProviderRequestGovernor.instance` AND
  `SharedBatchRequestSublimitGate.instance`.
- `providerKey` spelling `lmstudio` (enum name lowercased) vs governor
  backends `lm_studio` — fingerprint-internal today; align before any code
  compares the two spellings (wave-4 review §6).
- Wave-2 F1 `decideResume` production wiring still owed to the first
  COMPLETE-publishing stage (correctly untouched in slice A).
- Gates 4.1-4.8 incl. ≥1 real-provider evaluation ≥95% structured
  acceptance; shared 15-RPM trace with interactive priority under real
  transport.

## Wave 5 — 2026-09-06 — S5 slice B COMPLETE; Stage 5 COMPLETE pending provider-package transport wiring

Slice B (profile reconcile + freeze + zero-OCR frozen-profile reuse) ran the
full loop and is COMPLETE: implement → verify → review → fixes → re-verify.

- Implement — commit `d08bfad` "feat(translation): T924-S5 slice B profile
  reconcile + freeze (ST-09/ST-10, TX-22) with zero-OCR frozen-profile
  reuse" (10 files, +2209/−29; parent `f1cdde6`). Deliverables
  (`s5b-profile-report.md`): pure deterministic `ProfileReconciler`
  (MISSING_ONLY pending-never-canon per wave-4 F-W4-3; conflicts retained
  as CONFLICTING `unresolvedFacts`, never averaged; chapter-only scope,
  zero series promotion; documented sort keys + `f-`/`s-` ids;
  validate-every-chunk-record gate; bound-safe demotion);
  `ProfileFreezePublication` (T924-TX-22 ONE `publishSidecarPointers`
  transaction; FP-05 recomputed-and-verified before any byte; version
  monotonic per chapter; content-addressed `profiles/f-<sha>.json` sidecar
  + manifest `ProfilePointer` in the same M2; supersede = new file + new
  pointer; five-gate `readReusableFrozenProfile`, ST-30);
  `ChapterProfileBatchCoordinator` ST-05 reuse probe at run start (every
  checkpoint revalidated against the CURRENT source sha, ST-04; one
  changed page kills reuse) skipping the entire run through analysis with
  ZERO OCR + ZERO provider calls, terminal PAUSED
  `PROFILE_FROZEN_REUSE_REASON` — the T924 fast-feedback core; after
  complete chunks: PROFILE_RECONCILE record → reconcile over the durable
  chunk list (re-read from the manifest) → freeze → PROFILE_FROZEN record
  → PAUSED. COMPLETE + `decideResume` untouched (wave-2 F1). FP-04 built
  from the same policy-fingerprint helper + provenance constants as the
  analysis identity — freeze-time and reuse-time identity equal by
  construction. Flagged (reviewer-RATIFIED): 4-line additive
  `ChapterArtifactStore.profileSidecarName` accessor. Tests +30, incl. the
  FP-05 golden fixture `app/src/test/resources/t924/golden/
  t924-profile-golden-v1.json` (self-verifying hash `345def24…f63386`).
- Verify — targeted suites 578/0 (baseline 548 + 30 new); full
  `eu.kanade.translation.*` tree 1697/0.
- Review — `../wave5-review.md` ACCEPT-WITH-FIXES; reviewer-independent
  rerun 578/0 matched exactly. Both CRITICAL checks passed: the reuse
  probe runs before the OCR loop with ST-04 source revalidation and zero
  provider calls by control flow (recording fakes at the real seams); the
  FP-05 golden is genuinely self-verifying. All 7 deviations RATIFIED.
- Fixes — commit `28a75c5` "fix(translation): T924 wave-5 review fixes —
  oversized-alias drop + post-demotion participant remap" (4 files,
  +101/−11): F-W5-1 MEDIUM `boundedAliases` length filter (an overlong
  entity alias persisted VALID and deterministically wedged the freeze as
  PERSISTENCE_REJECTED on every resume; oversized alias now dropped with a
  note, and the validator V4-rejects overlong `sourceNames`/`titles`
  items — source gap closed); F-W5-2 LOW scene participant remap keyed on
  the FINAL post-demotion ENTITY_IDENTITY facts, unresolvable participants
  dropped (§1.4).
- Re-verify — targeted 581/0; full tree 1700/0.

### Stage 5 status

Stage 5 is COMPLETE pending the provider-package transport wiring (the only
remaining S5 slice). The kickoff carries three acceptance conditions: (1)
construct `AnalysisChunkExecutor` with `SharedProviderRequestGovernor.instance`
AND `SharedBatchRequestSublimitGate.instance` (F-W4-2); (2) align the
`lmstudio` vs `lm_studio` `providerKey` spellings before any code compares
them; (3) F-W5-1's validator-side entity-list caps — ALREADY LANDED in
`28a75c5`, carried as a condition rather than a gap. Wave-2 F1
`decideResume` production wiring remains owed to S6 (first
COMPLETE-publishing stage; slice B publishes zero COMPLETE runs). Gates
4.1-4.8 incl. the real-provider evaluation ride the transport wiring.

Note carried for the completion stage: an all-MISSING_ONLY chunk set
freezes a VALID EMPTY profile — contract-consistent and pinned by tests
(`all pending chunks still reconcile to a valid empty canon`); the
completion stage must account for this semantic when wiring
skip-to-FINALIZE (wave-5 review §3 residual note).
