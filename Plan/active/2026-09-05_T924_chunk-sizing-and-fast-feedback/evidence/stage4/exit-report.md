# T924 Stage 4 exit report — pure planners (WP3 subset)

Date: 2026-09-05 · Verdict: **COMPLETE** (F7 landed in commit `ee858f9` — `analysis-chunks-small.json` golden + byte-stability test, contextual package 122/0; F2 consolidation landed wave-3 in commit `c2705c8` — `PlannerFingerprints` deleted, goldens byte-valid UN-re-pinned, `GlobalEnvelopePlannerGoldenTest` 16/16 green; wave3-review item 4 PASS)
Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`.
Slice report: `evidence/stage4/planners-report.md` (contract anchors,
algorithms, constants, deviations 1-11).

## Commit map

| Commit | Content |
|---|---|
| `7c301bc` | Base carry: FF-01/FF-02 flag accessors (S2-owned; not S4 scope) |
| `73f6933` | S4 slice (orchestrator-committed): NEW `translator/contextual/OcrCorpusManifest.kt`, `AnalysisChunkPlanner.kt`, `GlobalEnvelopePlanner.kt`; tests `OcrCorpusManifestTest` (13), `AnalysisChunkPlannerGoldenTest` (17), `GlobalEnvelopePlannerGoldenTest` (16); golden fixture `app/src/test/resources/t924/golden/envelope-plan-small.json` |

Base HEAD `7c301bc`; all implementer work was left uncommitted by directive
and landed via the orchestrator commit above.

## Gates status (feature-flags-stage-gates §2.2, rows 2.1-2.5)

| Row | Status | Evidence |
|---|---|---|
| 2.2 Determinism | PASS (S4 scope) | 100-seeded LCG permutation-invariance property in all three planner tests (each loop runs full-plan equality ×100 ⇒ 300 property iterations); golden planFingerprint literal pinned |
| 2.5 Envelope membership | PASS (S4 scope) | `GlobalEnvelopePlannerGoldenTest`: every pending block exactly once in canonical order; no page split; oversized single page rejected whole (blocks/input/output tokens) |
| 2.1 Golden fixtures | PARTIAL | Envelope golden (`envelope-plan-small.json`, 1498 bytes, byte-for-byte + planFingerprint `5643a00c7f98e158e61246c6ad7413f933ff1eaade91b3efa06f45e6b0339df8`) and chunk goldens landed. **F7: the analysis-chunk golden fixture (`analysis-chunks-small.json` + byte-stability test) is in flight via a separate fix — see `evidence/stage4/f7-fix-report.md` (uncommitted at HEAD `5427b35`).** Profile pre-merge / relevant-matcher / taxonomy suites are S4 remainder (not yet implemented) |
| 2.3 Retry caps | NOT IN SCOPE YET | Split/backoff ledger (`ResponseTaxonomy`, root-budget children) is S4 remainder |
| 2.4 Taxonomy / frontier | NOT IN SCOPE YET | `MalformedResponseTaxonomyTest` + frontier extension are S4 remainder |

Determinism properties proven: input-permutation invariance (canonical sorts +
deterministic dedupe), operational-field exclusion (`createdAtEpochMs` zeroed
in the `planFingerprint` hashing view per SC-10/FP-01), oracle equality with
`StageFingerprints.ocrCorpusFingerprint`, unproven-order degradation stable.

## Owed before WP5 persistence (binding obligations)

- **F2 — encoder consolidation — DONE wave-3 (`c2705c8`)**:
  `PlannerFingerprints` consolidated into `StageFingerprints` (four envelope
  builders + public `canonicalFingerprint`/`sha256Hex` + `EnvelopePlanInputPage`
  carrier) BEFORE any analysis artifact was persisted; goldens byte-valid
  without re-pinning (gap 5 not needed); verified by wave3-review item 4.
- **F7 — analysis-chunk golden fixture**: in flight via a separate fix
  (`f7-fix-report.md`: fixture `analysis-chunks-small.json` + one byte-stability
  test added to `AnalysisChunkPlannerGoldenTest`, uncommitted at
  `5427b35`); the Stage-2 exit report requires it plus the WP3 gate rows.

## Deviations

Ratified: none silent. All eleven recorded decisions/deviations in
`evidence/stage4/planners-report.md` §"Deviations / recorded decisions"
(placement, caller-supplied fingerprints, planner-local hasher, hashing view,
256 KiB interpretation, zero-block pages ×2, sceneRefs/profileSubsetRefs,
planner-shaped output, contributing-set order) were reviewed under
`evidence/wave2-review.md` **ACCEPT-WITH-FIXES** (no blocker/major). The
contributing-set core-then-context order is pinned by an oracle test and its
request-builder pin (gap 3 / F4) is owed at S5.

## Verification counts

- Package run: `./gradlew :app:testStandardDebugUnitTest --tests
  "eu.kanade.translation.translator.contextual.*"` — **121 tests, 0 failures,
  0 errors, 0 skipped** (includes all pre-existing suites, untouched and
  green), **then +F7** analysis-chunk golden fixture lands as a separate fix.
- Planner slice contribution: 46 new tests (13 + 17 + 16).
- Wave-2 integration run: 102 classes / 792 tests / 0 failures, independently
  reproduced by the reviewer.
- Compile: `:app:compileStandardDebugKotlin` exit 0 (parallel-agent
  contention retried per wait-60s protocol; never on planner files).

## Deferred items (recorded, not gaps)

- `sceneRefs` / `profileSubsetRefs` stay empty in this slice — frozen-profile
  scenes and the relevant-subset matcher are separate S4/WP5 scope;
  `crossesSceneBoundary` is already computed from `sceneBoundaryBefore`.
- WP5 mapping: `PlannedAnalysisChunk` is planner-shaped, not the persisted
  `AnalysisChunkResult` — mapping test incl. status/excerpt-hash boundaries is
  gap 4, owed at S5.
- Budget/token constants remain PROPOSED-GATE experiments (R033);
  `maxBlocksPerChunk` (512) and token defaults flagged for the S5 owner.
- StreamingChunkPlanner retirement for AI Batch: later stage.

## Rollback state

Pure provider-free code behind no flag; rollback = revert `73f6933`.
Nothing consumes the planners yet; `rendering/**` and `pipeline/batch/**`
untouched by this slice.
