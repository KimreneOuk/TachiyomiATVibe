# T923 — Batch translation source investigation

## Date
2026-09-05

## Director request
Investigate how batch translation works. Explain the processing step by
step, and how it handles edge cases involving storage and other
concurrent processes (reader manual/auto, downloads, app restart).
Report back in a structural, easy-to-understand way.

## Director constraint
**Documentation is a nudge; source code is what matters.** Prior audit
reports (T918, T903, T914) are hypotheses to verify, not evidence. Every
load-bearing claim in the deliverable must be derived from or verified
against source code at current HEAD (`adbe643`, T922 included), with
`file:line` citations.

## Context
T918's batch-translation-logic-audit (2026-09-03) predates T922, which
changed `SequentialBatchCoordinator.kt` (641 lines), `BatchChapterTranslator.kt`,
`BatchLaneWorkers.kt`, `BatchRenderJoin.kt`, and wired
`translation_trace_v1` observability into batch. Core-scheduling claims
in the audit may have drifted.

## Task contract
- Engineering investigates from source at HEAD and writes
  `engineering/code-investigation.md` (step-by-step flow + storage and
  inter-process edge cases, `file:line` evidence).
- Review independently cross-checks the engineering report against code
  and writes `review/verification.md`.
- Main Leader synthesizes the Director-facing structural report; the
  Director conversation is not flooded with detail.

## Scope
Batch chapter translation pipeline only (entry → queue → planning →
chunking → OCR/translation/inpaint/render → commit/cleanup). Include:
storage durability/flush/locations, disk-state edge cases (partial
files, unreadable pages, restart rehydration), and coexistence with
reader manual/auto translation, rolling Auto, and process death.
