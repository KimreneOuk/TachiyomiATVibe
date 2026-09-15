# T925 — Batch ↔ Manual/Auto translation coexistence audit

## Director request (2026-09-07)

"Launch a large audit of this batch translation and how it coexists with
manual/auto translation. Although it is working, I just want you to hint
out potential issues."

Deliverable: a **potential-issues report only**. No fixes, no code changes.

## Scope

The chapter-level batch translation pipeline (BatchChapterTranslator,
scheduling lanes, durable store, reconciler, resume) and every way it
shares state and resources with:

- manual per-page translation from the reader,
- auto-translate-on-open / auto-translate settings,
- retry / rerun / delete affordances,
- the translation queue and its admission rules.

Audit base: worktree `TachiyomiAT-t924-impl`, branch
`t924/batch-profile-pipeline`, HEAD `25fe9fc`. FF-01/FF-02 default OFF —
both the legacy path and the batch-profile path are in scope.

## Slices

1. **Triggers & admission** — every path that starts translation work;
   concurrent-start races; manual request issued while a batch run is live.
2. **Shared resources** — native detector/OCR/inpaint queues, provider
   clients, executors/lanes, bitmap lifecycle; can batch starve or corrupt
   the reader path (and vice versa).
3. **State truth & data safety** — in-memory vs durable store, single-writer
   guarantees, drift writers, projector softeners, cancellation semantics,
   resume staleness, user-edit overwrite, display revocation.

## Standing constraints the audit checks against

Director priority order: correctness/data safety > resume/crash safety >
reader responsiveness > memory safety > implementation speed > throughput.
Ten Never rules (serial detector/OCR; no inpaint during OCR preflight; no
bitmaps across the OCR/provider barrier; no partial-commit; no unlimited
splits; never revoke valid committed display; never overwrite user edits;
Manual/Auto always usable; no parallel pipelines with ownership/memory risk).

## Status

- [x] Slices dispatched (3 parallel investigators)
- [x] Findings verified (4 HIGH claims spot-checked by Main Leader — all confirmed)
- [x] Director report — 4 HIGH, 9 MED, 9 LOW, plus clean/absence lists

