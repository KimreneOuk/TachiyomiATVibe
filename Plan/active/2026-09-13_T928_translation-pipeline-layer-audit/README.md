# T928 — Translation pipeline layer audit: I/O cost, stage animation, scheduling

## Director request (2026-09-13)

> "Audit the translation pipeline including manual, auto and batch translation on how it passes through each layer."
>
> Concerns:
> 1. Pipeline is way too slow — suspects too much I/O done for safety. Is there a better optimized way?
> 2. The animation for a page being processed needs rework: shows up too slowly, misses some stages, inconsistent.
> 3. Explain how resources are allocated and scheduled to minimize lag / overhead latency before processing.

Standing directive: **CODEBASE OVER DOCUMENTATION.** Code at HEAD is the only
source of truth. Docs and prior plan folders explain why things were built a
certain way but may be stale — they are rationale context only, never evidence
of current behavior.

## Audit base

Worktree `TachiyomiAT-1.16.8-dev` (primary), branch `main`, HEAD `9c19ad0`.
Untracked working-tree files (`_gui_probe/`, one test file) are not part of the
audited pipeline.

## Deliverable

Findings report only. No fixes, no code changes.

## Ground rules (all slices)

- Every behavioral claim cites `file:line` from code at HEAD.
- Mark each claim VERIFIED (read in code) / DERIVED (follows from verified code)
  / SUSPECTED (needs runtime proof).
- Quantify structure where code allows: I/O ops per page/chapter, dispatch and
  thread hops per stage, lock scopes, fixed delays/poll intervals — even
  without runtime measurement.
- Trace manual, auto, and batch paths separately; call out divergences.

## Slices

1. `team/io/` — end-to-end layer trace for manual + auto + batch; full durable
   I/O inventory (writes, reads-back, serialization, manifests, checkpoints);
   which writes buy which crash-safety; optimization options with risk.
2. `team/ui/` — per-page stage emission → propagation → rendering chain;
   why the processing animation is late, skips stages, and is inconsistent
   across paths.
3. `team/sched/` — executors/lanes/native model lifecycle, admission and
   arbitration between manual/auto/batch, memory governance, pre-processing
   latency sources.

## Reports

Each slice writes `team/<slice>/report.md` and returns only:
"Completed. <one-line result>. Report: <path>"

## Status

- [x] Slices dispatched (3 parallel investigators)
- [x] Main Leader spot-verification — 8 load-bearing claims checked in code, all confirmed
- [x] Director report — `report/DIRECTOR_REPORT.md`
