# T924 — Chunk sizing & fast-feedback package

## Date
2026-09-05

## Origin
Follow-up to T923 (batch translation source investigation). Director observed:
~40 pages OCR'd with zero translations on a 200-page chapter, and batch OCR
artifacts not reused by reader auto/manual retranslation. Root causes and
citations: `../2026-09-05_T923_batch-translation-source-investigation/engineering/code-investigation.md` §H.

## Director intent
Safer AND faster: see translation results from each chunk sooner, without
regressing reader stability, bounded memory, or provider-call correctness.
Director proposed sizing chunks by TEXT REGIONS (blocks) instead of pages
(20-25 or 30-40 blocks). Discussion converged on a specific design — recorded
in `design/chunk-sizing-options.md`.

## Status
**ROUTE COMPLETE (2026-09-12).** The Director's approved route —
Fix → A/B → default ON → delete legacy, plus the Phase-4 standard-engine
lane ("both AI and standard engine do the same OCR, standard engine
continues batch translation just like AI without the glossary") — is fully
executed on branch `t924/batch-profile-pipeline`:

- `ChapterProfileBatchCoordinator` is THE Batch coordinator for BOTH engine
  lanes (AI profile lane; STANDARD per-page lane), sharing the whole-chapter
  OCR preflight, durable run records, and the ST-14 resume machine.
- The legacy `SequentialBatchCoordinator` and the FF-01 A/B flag are DELETED
  (zero-legacy waves D1 `ab82e6d` + D2 `eeff99f`; doc-truth pass `439226e`).
- FF-02 (persisted layout) remains staged OFF awaiting its gate-7.8 device
  evidence.
- Latest verified state: forced unit sweep 242 suites / 1764 tests / 0
  failures at `eeff99f`; installed on the Director device as
  `0.17.1-452` (2026-09-12). No pushes/merges to main without Director
  instruction.
- The authoritative execution history — every wave, commit, review, and the
  open follow-up list (LI-5/LI-6/LI-7, LI-8 façade/CAS hardening, finalize
  projection authority, schema-bump candidates) — is
  `implementation-sequence.md`. Read THAT first for current state; the
  sections below are the original plan of record.

Historical staging record (superseded by the above): architecture was
re-investigated at HEAD `adbe643`; the target settled as a chapter-profile
AI Batch pipeline with full bounded OCR preflight, hierarchical chapter
analysis, a frozen versioned profile, global multi-dimensional envelope
planning, and structural split/backoff (`design/chapter-profile-batch-design.md`,
`design/final-target-migration.md`). Stage 0 closed its contract set
(`stage0/`, review ACCEPT-WITH-DEVIATIONS folded); production coding then
proceeded wave by wave per `implementation-sequence.md`. Manual/Auto remains
latency-oriented.

## Authoritative reading order

Later items narrow earlier ones. If records conflict, use this precedence:
explicit Director decision, final product requirements, final-target reconciliation,
chapter-profile design detail, then the stage-specific contract.

1. This README — scope, invariants, status and open decisions.
2. `design/profile-preflight-requirements.md` — active product requirements.
3. `design/chapter-profile-batch-design.md` — primary code-backed architecture.
4. `design/final-target-migration.md` — controlling migration sequence and
   persisted-layout corrections.
5. `engineering/delivery-readiness-audit.md` — dependencies, stage gates,
   evidence matrix and decisions that block coding.
6. `stage0/` — Stage 0 executable specifications: requirements catalog +
   traceability ledger, schema/fingerprint, state/transaction and
   provider/analysis contracts, feature flags + stage gates, work packages
   WP1-12 with verified entry points, reference scenarios, and the
   consolidated `stage0/decision-briefs.md` awaiting Director acceptance.
   `stage0/README.md` is the index; `stage0/stage0-review.md` is the
   independent conformance verdict.
7. `engineering/plan-completeness-audit.md` — independent anti-drift review.
8. T923 and the remaining T924 engineering reports — current-code evidence.
9. `design/chunk-sizing-options.md` and `design/batch-architecture-overview.md` —
   superseded decision history only; do not implement their Fast/small-first or
   progressive contextual-AI recommendations.

At every stage kickoff, record the actual base commit and reverify load-bearing
source assumptions. At stage exit, record the finish commit, implemented requirement
IDs, test/device/provider evidence, deviations, rollback state and an independent
conformance verdict.

## Stage 0 — required before production coding

1. Assign stable IDs to every MUST/invariant and create a live traceability ledger:
   requirement → work package → code path → automated test → device/provider
   evidence → reviewer → status.
2. Specify exact versioned DTOs, canonical serialization, unknown-version behavior,
   migrations and crash-safe manifest publication.
3. Specify the durable phase-transition/recovery table and OCR checkpoint/rebase
   transaction preconditions, atomic writes, postconditions and failure states.
4. Specify semantic fingerprint inputs and the complete invalidation matrix.
5. Resolve the profile/provider contracts and mixed malformed-response retention
   policy before their provider stages.
6. Define feature flags, fallback behavior, quantitative stage gates and rollback
   conditions.
7. Add executable reference scenarios for a normal 200-page chapter, fragmented
   resume, and concurrent reader activity plus malformed provider output.

## Open decisions (Director)
1. Exact Chapter Translation Profile and analysis-chunk schemas; analyzer model/
   provider relationship to the translator.
2. Provider/model shared quota table and TPM limits. The Batch/background norm is
   15 RPM across analysis + translation; Gemini-free total traffic must also fit
   its shared provider quota.
3. Initial structural/page/block/output budgets after instrumentation. Suggested
   32 blocks/8 pages is an experiment, not an accepted default.
4. Whether small chapters skip provider analysis and the measured threshold.
5. User and series glossary UX, authority, and promotion workflow. Initial
   recommendation: no automatic promotion of model-derived canon.
6. Whether independently complete pages from a mixed malformed response commit
   immediately or remain candidates until split recovery completes.
7. Profile-change invalidation policy for already completed machine translations.
8. Whether non-AI Batch also adopts full OCR preflight or retains current flow.
9. Configuration snapshot/next-run semantics and consistent add-versus-replace UX
   remain separate scope decisions.
10. OCR checkpoint transaction that preserves reusable OCR/mask state while
    closing/rebasing BATCH candidate ownership before releasing the page lease.
11. Native reader-priority/starvation thresholds between OCR pages.
12. Persisted layout DTO and the font/platform compatibility boundary in its
    fingerprint; stroke width currently remains layout-affecting.

## Proposed architecture scope (not implementation authorization)
1. Durable AI Batch phase/run state and immutable analysis/profile artifacts.
2. Full serial, one-page-at-a-time OCR sprint with immediate bitmap release and
   an atomic OCR checkpoint before lease release.
3. Hierarchical structured analysis and evidence-aware profile reconciliation.
4. Frozen profile-aware translation request contract and relevant-subset matcher.
5. Global whole-page planner with token, output, block, page and structural limits.
6. Page-safe validation plus deterministic whole-page split/backoff.
7. Force-path OCR evidence reuse and sparse stream-to-download identity migration.
8. Shared provider pacing for analysis and translation; intended 15-RPM policy.
9. Preparation/profile progress extensions; reuse existing page-stage/buffered UI.
10. Boundary, lifecycle, coexistence, schema and 200-page device/provider tests.
11. A later persisted `LAYOUT_PREPARE` stage: color estimation plus durable
    source-image-space text layout, with reader-side async planning retained as a
    compatibility fallback until migration completes.

## Constraints
- Page atomicity is NEVER broken (no page splits across committed envelopes).
- One Batch provider envelope in flight.
- Progressive PROBE handoff is retained for old paths but replaced by logical
  look-ahead in the new contextual-AI Batch planner.
- Token ceilings remain hard backstops; structural limits add stricter boundaries.
- Gap-free context, leases, candidate/committed display and reader priority remain.
