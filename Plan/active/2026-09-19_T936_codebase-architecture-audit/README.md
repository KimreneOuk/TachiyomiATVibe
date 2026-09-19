# T936 — Comprehensive Codebase Architecture & Technical Debt Audit → Remediation

## User Request (2026-09-19)

> "You are the lead software architecture auditor and orchestrator.
> Your job is to coordinate multiple specialist child agents to inspect this codebase for technical debt, unnecessary complexity, poor structure, stale tests, naming problems, oversized files, and overengineering.
> This phase is AUDIT ONLY.
> Do not modify production code.
> Do not refactor files.
> Do not delete tests.
> Do not rename anything.
> Do not implement fixes.
> The final output should be a comprehensive technical-debt report following the reporting rules defined in AGENT.md."

## Follow-up (2026-09-19, same day)

> Transition to EXECUTION phase. 5 risk-ordered phases:
> 1. Zero-Risk Purge; 2. Storage Unification; 3. Coexistence Simplification & Pipelined Batch;
> 4. Monolith Decomposition; 5. Package Architecture, Test Renaming & Comment Hygiene.
> Write tickets first; wait for Director confirmation before executing.

## Audit Base

- Worktree: `codebase_architecture_audit` (branch `codebase_architecture_audit`, base `main` @ `7262bf4`)
- Baseline: 1,446 Kotlin files, 254,675 lines total. Translation layer: 573 files, 154,571 lines (60.69% of repository).
- Artifacts synced from the audit worktree into this folder on 2026-09-19:
  - `report/DIRECTOR_REPORT.md` — master findings
  - `engineering/batch-pause-cancel-trace-and-mutual-exclusion-evaluation.md`
  - `engineering/storage-and-coexistence-architecture-redesign.md`

## Critical Safety Invariants (never violate)

1. **DO NOT DELETE `aot-512.onnx`** — actively required by Qualcomm QNN HTP (NPU) static-shape
   inpainting (`AOTInpainting.kt:115-124` resolves it via `paths.inpaint512Model`).
2. **DO NOT TOUCH** downloaded manga chapter images, CBZ archives, reading history, or databases.
3. Device hardware acceleration routing (CPU, Adreno GPU, Qualcomm NPU) must remain independent
   and fully functional. See Ticket P1-05: NNAPI removal is **blocked** on runtime evidence —
   the pinned ONNX Runtime AAR *does* contain the NNAPI execution provider, contradicting the
   audit's "dead subsystem" proof.

## Execution Phase

Branch strategy: one branch per phase off `main` (`t936/phase1-zero-risk-purge`, ...).
One commit per ticket. Every ticket must leave `:app:testDebugUnitTest` green.

### Phase 1 — Zero-Risk Purge (COMPLETE — implementation + independent review PASS WITH NOTES)

Executed on `t936/phase1-zero-risk-purge` (7 commits @ `e2d8a89`, base `main` @ `7262bf4`):
2,079×2 flavor unit tests green; APK evidence independently reproduced by reviewer.

| Ticket | Title | Result |
|---|---|---|
| [P1-01](ticket/p1-01-delete-disabled-rendering-tests.md) | Delete 8 permanently disabled rendering test suites (~2,900 lines) | Done |
| [P1-02](ticket/p1-02-remove-duplicate-segmentation-asset.md) | Delete duplicate asset `best_int8.onnx` (3.28 MB) | Done |
| [P1-03](ticket/p1-03-plug-asset-doc-leak.md) | Keep OCR model docs out of the APK (corrected: relocate to `docs/models/`; AGP packaging excludes proven placebo for assets) | Done |
| [P1-04](ticket/p1-04-untrack-repo-bloat.md) | Untrack ~74 MB of prototype models & benchmark outputs | Done |
| [P1-05](ticket/p1-05-nnapi-excision-blocked.md) | NNAPI excision — **BLOCKED on Director decision** (AAR inspection contradicts audit's dead-subsystem premise) | Deferred |

Reports: [implementation](team/p1-implementation-report.md) · [review](team/p1-review-report.md)
Corrections made during execution: flavor-qualified Gradle tasks (dev/standard); P1-03 mechanism.
Awaiting Director: merge decision, P1-05 Option A/B, Phase 2 go.

### Phases 2–5

To be ticketed after Phase 1 lands. Roadmap in `report/DIRECTOR_REPORT.md` §8, with the
coexistence model corrected by `engineering/batch-pause-cancel-trace-and-mutual-exclusion-evaluation.md` §3
(mutual-exclusion session model: `IDLE` / `BATCH_SESSION` / `READER_SESSION`, quiescent pause via
`cancelTranslatorJobAndJoin()` with bounded 3s timeout, Pass 1 sequential OCR push-through,
Pass 2 overlapped Lane A rolling translation + Lane B concurrent inpainting, render join).

## Status

- [x] Independent specialist investigation & evidence verification (audit phase)
- [x] False-positive correction (verified `aot-512.onnx` is actively required for Qualcomm QNN HTP)
- [x] Consolidated Director report written to `report/DIRECTOR_REPORT.md`
- [x] Audit artifacts synced into main workspace
- [x] Phase 1 tickets written — awaiting Director review before execution
- [x] Phase 1 execution (7 commits on `t936/phase1-zero-risk-purge` @ `e2d8a89`, review PASS WITH NOTES, review notes closed)
- [ ] Director: merge Phase 1 → main
- [ ] Director decision: P1-05 NNAPI (Option A defer-and-verify vs B excise now)
- [ ] Phase 2 ticketing + execution (storage unification & legacy elimination)
