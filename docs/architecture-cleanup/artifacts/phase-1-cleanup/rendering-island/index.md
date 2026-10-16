---
title: "P1.2 — remove the unwired rendering island, preserve live layout"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Completion — reviewed and locally committed

Gemini 3.8 Flash/high through Antigravity accepted the actual diff and saved test evidence in [independent review](review). Coordinator committed the six scoped paths as a9c38fb5 on architecture-cleanup. Commit is local only; user report remains untracked and unchanged. StandardDebug/consolidated validation remains P1.5 work, and device testing remains deferred.

# Delivery checkpoint — historical pre-review record

P1.2 is implemented against b452499c but remains uncommitted. Actual diff: six scoped paths, 3,542 deletions, no insertions. Preserved baseline XML records 188 tests/19 classes; final XML records 166 tests/17 classes with zero failures/errors/skips. Two retired helper suites account for all 22 removed tests. Coordinator verified these totals and the unchanged user-report SHA256. TextLayoutPlanner line count is 2,607→1,332; CollisionRelaxation is 1,133→392.

[Results and command evidence](results) record intermediate over-deletions corrected before the successful final compile. Gemini 3.8 Flash/high review through the existing Antigravity session is pending. Ticket remains in progress; no next slice or commit before acceptance.

# Entry and scope

P1.1 accepted by Gemini/Antigravity and locally committed as b452499c on architecture-cleanup. User authorized continuing verified cleanup with phone tests deferred. Implementing agent must refresh reachability at this base; prior audit/line references are evidence, not automatic deletion instructions.

Inputs: [production manifest](../../translation-step1-plan/production-manifest) and [dead-code reference audit](../../maintainability-refactor-plan/preflight-purge/dead-code). No numerical LOC or file target overrides correct reachability.

# File/symbol manifest

| Path | Intended action |
| --- | --- |
| app/src/main/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlanner.kt | Delete after proving all production references are within the dead island |
| app/src/main/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlanner.kt | Delete with the island |
| app/src/test/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlannerTest.kt | Delete only tests of the removed, non-production helper |
| app/src/test/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlannerTest.kt | Delete only tests of the removed, non-production helper |
| app/src/main/java/eu/kanade/translation/engines/rendering/TextLayoutPlanner.kt | Remove unreachable island members/types, their exclusively used imports/constants and stale KDoc; keep live planner bodies/outputs unchanged |
| app/src/main/java/eu/kanade/translation/engines/rendering/CollisionRelaxation.kt | Remove only dead post-anchor ladder and exclusive delegates/helpers; retain the file and live collision behavior |

TLP candidates from prior audit: fitTextUnmasked, adaptiveBlockLayout, bandBeatsRectangleFit, applySiblingFontHarmony, harmonizedReplacement, dilateSpans, clipSpansToRect, rescueCeilingSpans, containedReflowRescue, paintEnvelopeContainedInComponent, resolvePostAnchorPlacement delegate, boundedFreeTextWideningPlan, buildSharedCellPlans, symmetricFitRegion, withSharedCellMetadata, componentAssignmentAllowed, componentBounds, plannerMember; types PlacedFootprint, SharedCellPlan, RescueBudget and FinalResolution. Additional transitive orphan members can be removed only with per-symbol evidence and no live/test consumer.

CR candidates: resolvePostAnchorPlacement ladder, footprintCollides, minShiftDisplacement, freeRectDisplacement, fitIntoFreeRect, boundedFreeTextWideningPlan, collisionFreeWidthForBand and exclusively ladder-owned delegates. Keep overflows, growIntoFreeSpaceIfNeeded, resolveMinimalDisplacementX, freeSpace helpers, translateRect/translateLayout and any shared dependencies reachable from live roots.

Freeze live plan/planPage/planPageInternal, fitTextInMask/fitTextVertical, placeBlock, live rendering/math contracts and tests. Public/internal test consumers count as consumers, not dead by naming. Never delete functions simply because a compiler stops complaining or a sole call disappears; resolve remaining graph to live entry points/tests.

# Validation

Before edits, run and preserve the actual rendering test class XML/logs. Split retiring helper test counts from retained live suite counts. After edits, run retained rendering tests with fresh edited main/test compilation. Relevant geometry/publication tests should remain in the slice if their caller surfaces are affected. Match retained per-class counts and assertions; do not invent expected totals from historical approximations.

Prove live bodies unchanged by exact before/after comparison or an equivalent documented structural diff. Run git diff --check and preserve original Phase 0 evidence. One Gradle owner; no broad unrelated suite/formatting repair. Record exact call graph, file counts and changed paths in results/index.md. No algorithms/native/OCR/LiteRT/store/scheduler/provider changes, models, devices, commits or pushes by child.

Expected file-only delta if graph agrees: minus two production and two test files, zero additions; main source 288→286 and ordinary unit tests 317→315. Additional method-only deletion changes LOC, not file count. Stop for fresh Gemini/Antigravity review before P1.3.
