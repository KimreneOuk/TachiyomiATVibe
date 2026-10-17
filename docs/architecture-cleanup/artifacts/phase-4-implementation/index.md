---
title: "Phase 4 - unified reader queue and auto coordinator retirement"
kind: spec
status: 1
assignee: ""
comments: none
---

# Status and authority

Prepared 17 October 2026 at worktree HEAD `b10c8b66` on `architecture-cleanup`. The user explicitly requested Phase 4 continuation on 17 October 2026; S1 is now accepted in the isolated `phase4-s1-core-queue` branch. Phase 3 remaining closure checks and device gates remain open; Phases 5–8 have not started. This artifact set is the Phase 4 entry package: the seam survey, the implementation plan, and the five execution slice tickets. Execution follows that explicit user reordering and the standing autonomous-continuation authorization of 16 October 2026. **No push is authorized** (16 October AGENTS.md supersedes earlier push instructions).

The [Phase 4 gate](../../translation-simplicity-investigation/phases/index.md) is authoritative. The [master queue API contract](../../translation-step1-plan/index.md) and the [caller map and lifecycle contracts](../../translation-step1-plan/reader-queue/index.md) supply the target design; where this package records a deviation it says so explicitly in the [plan](plan/index.md) decisions ledger.

# Naming reconciliation

The task shorthand "CURRENT/SPECULATIVE priorities" maps to the spec contract as follows: `ReaderPriority` has exactly two values, `CURRENT_PAGE` and `PREFETCH_NEXT`. "Speculative" is not a priority value — it is the demand class of an auto-origin viewport entry that has left the N/N+1 window and carries no explicit manual demand, and is therefore prunable. Explicit manual demand survives every prune. Tickets use the spec enum names.

# Deliverables

| Document | Content |
| --- | --- |
| [Seam survey](seam-survey/index.md) | Actual-code survey of the seven named files plus secondary seams, with retirement/absorption dispositions and test fan-out. |
| [Implementation plan](plan/index.md) | Queue design, demand/promotion/teardown contracts, deferral policy, migration staging, behavior ledger, file accounting, validation strategy. |
| [S1 core queue](tickets/s1-core-queue/index.md) | `ReaderTranslationQueue.kt` + executor seam + standalone unit suite. |
| [S2 facade wiring](tickets/s2-facade-wiring/index.md) | Scheduler/manager/pipeline/governor/teardown cutover; queue becomes the live reader engine. |
| [S3 UI integration](tickets/s3-ui-integration/index.md) | Controller, resolver rename, ViewModel migration to the typed facade. |
| [S4 retirement](tickets/s4-retirement/index.md) | Delete coordinator/window/UI-state files and obsolete auto machinery, DTOs, ahead-count preference surface; merge legacy tests. |
| [S5 consolidation](tickets/s5-consolidation/index.md) | Broad-but-bounded regression validation, adapter removal check, ledger sign-off. |

# Execution discipline

Small independently compiling slices; production always has exactly one live reader execution path. Never delete a live contract to obtain green; old files retire only after replacement coverage. Comments 1-4 lines, code-explaining only; reuse existing helpers. Validation follows the [standing run policy](../phase-3-implementation/validation-audit/index.md): targeted iteration, one monitored Gradle owner with the v3.4-class watchdog, attempt-specific evidence destinations with one-use launch claims, fresh XML preserved per attempt, focused gates on StandardDebug then DevDebug, read-only Spotless inventory per slice. Diagnose failures; never retry them away.

Device/physical gates remain deferred, not waived: the serial-versus-two-page-overlap acceptance and foreground-latency measurement attach to the Phase 4 behavioral gate and remain open until measured.
