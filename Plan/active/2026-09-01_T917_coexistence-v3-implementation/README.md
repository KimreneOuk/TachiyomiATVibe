# Task T917 — Translation Coexistence v3.0 Implementation

## Objective

Implement the coexistence contract defined in
`docs/architecture/translation-subsystem-coexistence.md` (v3.0; the draft file
`translation-subsystem-coexistence-v3-draft.md` was promoted to canonical at
`docs/architecture/translation-subsystem-coexistence.md` on 2026-09-03).
All decision points D1–D13 are **resolved as of 2026-09-01**: the Director adopted every
Recommendation (§6 of the draft). The goal is to replace silent-failure and contradictory
coexistence behavior with visible, ownership-clean, tested contracts — without regressing
reader performance or normal (translation-disabled) manga.

## Authority (read in this order)

1. Your role file (`docs/roles/<role>.md`).
2. This README and `PLAN.md` (same folder).
3. `docs/architecture/translation-subsystem-coexistence.md` — the normative spec
   (§4 verified baseline, §6 decisions, §7 outcome contract, §8 verification plan, §9 rollout).
4. The evidence base for any finding you touch:
   `Plan/active/2026-09-01_T916_translation-coexistence-strict-audit/STRICT_AUDIT_REPORT.md`
   (search by finding ID: C-01…C-03, H-01…H-10, M-01…M-11).

Do not read `AGENTS.md`. Do not preload unrelated team knowledge.

## Scope

**In:** D1–D12 implementation; D13 reclassification plus measurement-protocol preparation;
the §7 terminal-outcome/UI-truth contract; the §8 interleaving harness and acceptance tests;
the UI state→surface appendix (Phase 5).

**Out:** downloader/reader fixes already merged (`3392234`, `4f365df`); new translation
features; killing hung native calls (explicitly rejected — D8 makes stalls visible instead);
performance optimization (Phase 6 only *measures*; optimization is a later task).

## Constraints

- **Test-first:** each phase writes its failing interleaving tests before production change.
- **Checkpoint discipline (Director requirement):** every turn ends in a state that can be
  rolled back. See `PLAN.md` §2 for the tag/branch policy — it is binding on all specialists.
- **Global product constraints:** Android 8.0+, bounded memory on the ≥6 GB device class,
  reader stability first, and normal-manga non-regression (the §8 isolation test gates every
  phase that touches arbitration, storage observation, or decode paths).
- **No silent outcomes:** §7 of the draft is non-negotiable. Every user intent reaches a
  visible terminal outcome.

## Deliverables and report paths

| Deliverable | Path |
|---|---|
| Phase design notes (required for D1, D7, D11) | `engineering/phase<N>-<topic>.md` |
| Interleaving harness + phase tests | in-repo, `app/src/test/java/eu/kanade/translation/…` |
| Phase acceptance records | `review/phase<N>-verification.md` |
| Phase gate log (what landed, tag name, test status) | `PHASE-LOG.md` |

Specialists report back only: "Completed. <one-line result>. Report: <path>".
