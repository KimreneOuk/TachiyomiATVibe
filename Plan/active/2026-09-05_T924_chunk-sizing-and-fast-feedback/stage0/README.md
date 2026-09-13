# T924 Stage 0 — artifact index and integration record

Date: 2026-09-05 · Baseline: `adbe643` (verified: `git rev-parse HEAD`; working tree
clean except untracked Plan docs) · Status: Stage 0 specification complete.
Independent review verdict: **ACCEPT-WITH-DEVIATIONS** (`stage0-review.md`) with
all deviations folded into the artifacts (F-1, F-2, F-3, F-4, F-6, F-7, F-8,
I-1 addressed; F-5 recorded as the Stage-1 golden-fixture deviation). Remaining
exit condition: Director acceptance of `decision-briefs.md` (DB-01..DB-13).

Stage 0 was authorized for specification only (`HANDOFF_PROMPT.md`,
`engineering/delivery-readiness-audit.md`). No production code was written or
modified; HEAD remains `adbe643`.

## Artifact map

| # | File | Work item | Namespace(s) | Content |
|---|---|---|---|---|
| 1 | `requirements-catalog.md` | A | T924-R001..R045, T924-INV-01..25 | Stable IDs for every MUST/invariant; 17-row evidence-matrix coverage cross-check; verified current-code guarantees |
| 2 | `traceability-ledger.md` | A | (rows for all 70 IDs) | Live ledger: requirement → WP → code owner → test/evidence → accepted result → reviewer → status |
| 3 | `contracts-schemas-fingerprints.md` | B | T924-SC-01..22, T924-FP-01..09 | 8 versioned DTOs (field tables + JSON examples); canonical serialization; versioning/unknown-version/corruption rules; crash-safe publication; semantic fingerprints; 13×8 invalidation matrix; 5 decision briefs (§7) |
| 4 | `contracts-state-transactions.md` | C | T924-ST-* (sparse: 01-16, 20-24, 30-34), T924-TX-* (sparse: 01-12 incl. 03.1, 20-23) | 13-state durable phase-transition/recovery table; `checkpointOcr` CAS contract with precise CLOSE-vs-REBASE rule and crash-point × manifest-state table; downstream CAS clauses; OOM/cancellation; corruption semantics |
| 5 | `contracts-provider-analysis.md` | D | T924-AP-01..08, T924-DR-A..D | Typed structured-analysis protocol (JSON schemas, caps, hard-fail evidence validation); typed provider error taxonomy; chapter-only authority inputs; small-chapter policy; 4 decision briefs |
| 6 | `feature-flags-stage-gates.md` | E | T924-FF-* | Two feature flags (default OFF) with mid-run-flip and rollback-window semantics on the real `TranslationPreferences` mechanism; quantitative stage gates 1-8 (PROPOSED-GATE numbers marked); kickoff/exit evidence process |
| 7 | `work-packages.md` | E | WP0-WP12 | Per-WP goal, verified source/test entry points at HEAD, new files, risks, exit pointers |
| 8 | `reference-scenarios.md` | F | T924-N1/N2/N3-* | Three normative end-to-end storyboards (200-page normal; fragmented resume; concurrent reader + malformed response) with per-step durable-state assertions; 26-item assertions index |
| 9 | `decision-briefs.md` | Main Leader | — | Consolidated Director decision asks with recommendations (read this to close Stage 0) |

Precedence (per delivery audit): explicit Director decision > product invariants
(`design/profile-preflight-requirements.md`) > `design/final-target-migration.md`
> `design/chapter-profile-batch-design.md` > these stage-0 specs.

## Stage 0 checklist coverage (README "Stage 0 — required before production coding")

| README item | Covered by |
|---|---|
| 1. Stable IDs + live traceability ledger | `requirements-catalog.md`, `traceability-ledger.md` |
| 2. Versioned DTOs, canonical serialization, unknown-version behavior, migrations, crash-safe manifest publication | `contracts-schemas-fingerprints.md` §1-4 (T924-SC-01..22) |
| 3. Durable phase-transition/recovery table + OCR checkpoint/rebase transaction preconditions, atomic writes, postconditions, failure states | `contracts-state-transactions.md` §1-2 (T924-ST-*, T924-TX-01..12) |
| 4. Semantic fingerprint inputs + complete invalidation matrix | `contracts-schemas-fingerprints.md` §5-6 (T924-FP-01..09 + matrix) |
| 5. Profile/provider contracts + mixed malformed-response retention policy | `contracts-provider-analysis.md` (T924-AP-*), decision T924-DR-A |
| 6. Feature flags, fallback behavior, quantitative stage gates, rollback conditions | `feature-flags-stage-gates.md` (T924-FF-*) |
| 7. Executable reference scenarios (200-page, fragmented resume, concurrent + malformed) | `reference-scenarios.md` (N1/N2/N3) |

## Cross-agent reconciliations (settled during integration)

1. **Two-value `ArtifactOrigin` and "origin-neutral checkpoint" (C1 × B).**
   C found that `ArtifactOrigin` has exactly two durable values
   (`READER_ADHOC`, `BATCH`), so an "origin-neutral open candidate" is
   unrepresentable; C defines REBASE as close-BATCH-generation +
   open-named-successor in ONE manifest publication. B's
   `PageOcrCheckpoint.producedByOrigin` keeps the same two-value enum as
   *recorded provenance only*, with origin-neutrality expressed as a
   *consumption* rule (any origin may seed a fresh candidate from a matching
   checkpoint). **Settled: no third origin value is introduced; the two
   contracts compose.** WP2 implements C's T924-TX-03 against B's T924-SC DTO.
2. **HANDOFF_PROMPT staleness (A conflict 6).** A repeated
   plan-completeness-audit finding 1 claiming HANDOFF_PROMPT still directs
   readers to superseded records. Correction: the current `HANDOFF_PROMPT.md`
   explicitly forbids starting with `chunk-sizing-options.md` /
   `batch-architecture-overview.md` and names the active target — finding 1 was
   already addressed by that update. Both superseded documents also carry
   superseded/history banners. No action needed beyond this note.
3. **Manifest SCHEMA_VERSION 2 → 3 (B §7.2 × E rollback semantics).** B
   recommends bumping the manifest version when new pointers are added so a
   rolled-back older build preserves the chapter read-only (verified
   future-schema guard) instead of silently stripping new pointer fields
   (kotlinx read-modify-write drops unknown keys). E's flag-off-mid-run
   semantics (T924-FF-01e) already assume legacy-path operation without new
   artifacts; the combination is consistent but the tradeoff (older build
   cannot write chapters that carry new pointers during the rollback window)
   is a Director decision — see `decision-briefs.md` DB-05.
4. **Retry-root semantics across restarts (F §6 note 3 × C ST-12).** F treats
   the root `RequestRetryBudget` as per-attempt-tree (a resumed recovery opens
   a new tree) while the durable attempt ledger still accumulates for audit
   and the crash-loop cap (`MAX_CONSECUTIVE_UNRESOLVED = 3`) bounds repeat
   restarts. C's ledger clauses do not contradict this; flagged for the
   Stage-0 reviewer to confirm.
5. **Fingerprint exclusions.** B's T924-FP-01 (transaction identities never
   fingerprinted) matches C's T924-TX-02 input table (content identity carried
   opaquely) and F's provenance atoms. No divergence found.
6. **Review deviations folded (stage0-review F-1/F-2).** F-1: the
   no-active-candidate checkpoint branch (reader-committed page adopted by
   Batch, required by scenario N3-05) is now normative as **T924-TX-03.1**
   (ADOPT-COMMITTED); DB-02 acceptance carries the amendment. F-2: the
   persisted-layout requirement placement is **resolved** — the catalog's
   T924-R036..R041 (Part 3D) is authoritative; the flags/work-packages texts
   that called it an open documentation decision were corrected. LOW findings
   F-3 (catalog conflict 6 corrected), F-4 (invalidation row-8 rationale
   reattributed to final-target §3), F-6 (sparse namespace wording above),
   F-7 (N2-04 policy-scope wording), F-8 (WP2-before-Stage-3 note) and I-1
   (DB-11 wording) are also folded. F-5 (JSON examples for 4 of 8 DTOs) is
   recorded as the accepted Stage-1 deviation: the missing examples become the
   Stage 1 golden fixtures.

## Stage 0 exit status

Per the delivery-readiness audit, Stage 0 exits when all blocking decisions
are accepted, schemas have examples and validation rules, every durable
transition names preconditions/atomic writes/postconditions/crash results, and
every later stage has exact source/test entry points. The last three are done
(items 3, 6, 7 above; schema examples cover 4 of 8 DTOs — recorded deviation
F-5, closed by Stage-1 golden fixtures). The independent review
(`stage0-review.md`) returned ACCEPT-WITH-DEVIATIONS with all deviations
folded. The remaining exit condition is **Director acceptance of the
consolidated decision briefs** (`decision-briefs.md`), after which WP1 may be
authorized at a recorded base commit.
