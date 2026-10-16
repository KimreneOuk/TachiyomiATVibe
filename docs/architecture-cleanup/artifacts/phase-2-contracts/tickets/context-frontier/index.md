---
title: "P2.3 — shared context-frontier ownership"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Scope

Completed with Gemini acceptance and 58/58 focused tests per flavor. Parent committed `aef57913` on architecture-cleanup; rename-aware Git diff: seven logical files, +6/-4 lines, no file-count change. Tracked tree clean afterward; original report unchanged and untracked. Local commit pending Phase 2 completion/push.

## Prepared compile manifest

[Read-only preparation](../../preparation/context-and-replacement) was source-checked at `d8252cbe`. Seven logical files: move the frontier definition; replace imports in ProfileEnvelopeExecutor and BatchResumePlanner; remove the now-same-package import from ChapterContextService; add imports to BatchChapterTranslator, BatchLaneWorkers and the existing BatchContextFrontierTest. Preserve declaration/body and test paths. Recheck references at the implementation base after P2.2 is accepted.

Suggested selectors: BatchContextFrontierTest, ProfileEnvelopeDispatchTest, StandardPipelineCoordinatorTest and context.*; the combined preparation set also includes TranslationManagerDeleteResetOrderingTest and ChapterQueueConflictDetectionTest for the following slice. Its current source forecast is 65 tests, including 12 coordinator tests; executed XML governs. Earlier fresh phase evidence remains available in epic scratch; this preparation did not execute tests.

Separate small compiling commit after caller inspection, governed by [Phase 2](../..). Move pipeline/batch/BatchContextFrontier.kt to context/ preserving the existing class name and behavior; a rename is unnecessary for ownership correction. Update ChapterContextService, BatchChapterTranslator, BatchLaneWorkers, ProfileEnvelopeExecutor, BatchResumePlanner and actual tests/references discovered by search. This removes context → pipeline.batch without changing ordering/frontier semantics.

Preserve the full frontier implementation, including synchronization, failures, natural page order, gap behavior and snapshots; do not treat it as a trivial counter or alter batch scheduling. Tests may stay in their present source paths with new imports. Validate existing frontier/context/recovery coverage before/after, fresh compile both flavors, and record package/import-only fidelity. No callback decomposition or persistence edits. Deliver diff/results for Gemini/Antigravity review; no child commits/pushes.
