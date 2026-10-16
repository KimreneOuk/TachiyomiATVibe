---
title: "Phase 1 — verified purge, consolidation and test repair"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Authorization and scope

**Current status: complete (host cleanup).** Seven reviewed commits are published locally and remotely on `architecture-cleanup` at `dab5c124`. [Final closure and remaining gates](closure) supersede the historical checkpoints below. Phase 2 is not started.

User authorized proceeding while deferring phone testing on 14 October 2026. Work on `architecture-cleanup`, initially matching remote at `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. Preserve the user's untracked architecture report and the original [Phase 0 evidence](../phase-0-baseline). GLM 5.3 Flash/max/OpenCode implements; Gemini 3.8 Flash/high/Antigravity independently reviews actual diffs.

This phase removes reference-verified dead code, co-locates justified policy, repairs false coverage/assertion drift, and adds diagnostics only for a concrete unexplained failure. It does not change reader scheduling, native cancellation/admission, OCR/LiteRT algorithms or weights, live state authority, guarded publication or batch callback contracts. Host tests validate these slices; device/native evidence remains deferred to affected later changes.

# Ordered execution slices

| Slice | Work | Gate/status |
| --- | --- | --- |
| P1.1 | [Orphan helpers and sole-caller resume policy](orphan-helpers) | Complete: 42/42 before/after; Gemini/Antigravity accepted actual diff; local commit b452499c |
| P1.2 | Remove unwired rendering island, retain live TextLayoutPlanner/CollisionRelaxation roots | Complete: 166 retained tests pass; Gemini/Antigravity accepted; local commit a9c38fb5 |
| P1.3 | [Timestamp assertion and production-test coverage](test-repair) | Complete: local reviewed commits 444b5abf and 1c1d247e; test repairs and production coverage accepted |
| P1.4 | [Pinned adaptive experiment retirement](adaptive-experiment) | Complete: preserved live gate/telemetry,54tests pass perflavor; local reviewed commit b44143f2 |
| P1.5 | [Consolidated host validation and exact accounting](consolidated-validation) | Complete: lint and fixture repairs accepted; full Standard recheck contains only four tracked baseline store failures; commits f39cfd2a and dab5c124 |

Source inputs: [production purge evidence](../translation-step1-plan/production-manifest), [test manifest](../translation-step1-plan/test-manifest), [simplicity-first reassessment](../translation-simplicity-investigation). Earlier exact totals are forecasts, not quotas. Refresh call graphs before editing; a name/suffix/one caller alone never establishes dead code.

# Baseline and validation rules

Original full suites: 4588 executions, 11 failure executions across six distinct methods, zero errors, two deliberate skips. Both debug assembles passed. Selected diagnostic runs do not replace this baseline. Original XML/logs are preserved under epic scratch and summarized in Phase 0 artifacts.

For each slice record actual paths/symbols, reachability evidence, diff scope, executed commands, fresh test counts/outcomes, reviewer verdict and unresolved limitations. Only one agent owns Gradle at a time. No paid-provider contact, model fetch/conversion, toolchain install, device execution or production logging sweep. Avoid broad test reruns unless new failures or changed scope justify them.

Storage assertion/cleanup causes remain unresolved. Any controlled host investigation must preserve source and original evidence; fixes need a grounded contract. Phase 1 closure cannot claim these mechanisms are sound without the designated evidence.

# Historical completion checkpoints

## Current accepted slice — P1.1

Actual six-path diff: three files deleted, three modified, +56/−227 lines (net −171); no new files. Coordinator verified requested translation subtree counts: main source 290→288 (288→286 Kotlin, two Java retained), ordinary unit tests 318→317. User report hash unchanged. Branch `architecture-cleanup` now has local reviewed commit `b452499c`, not pushed yet.

Live BatchResumePolicy was co-located with its original internal visibility because StandardPipelineCoexistenceTest has two direct assertions; the prior plan missed that test consumer. Gemini verified this technical deviation and accepted P1.1 through the original successful Antigravity session after startup failures elsewhere. [Actual results](orphan-helpers/results) and [independent code review](orphan-helpers/review) govern slice closure. P1.2 is now dispatched.

## Accepted slice — P1.2

Uncommitted actual diff against b452499c: six paths, 3,542 deletions, zero insertions; two production and two test files removed. Coordinator independently verified preserved XML: baseline 19 classes/188 tests, final 17 classes/166 tests, zero failures/errors/skips in both runs. The 22 removed tests belong to the two retired helpers. Source totals now 286 main sources and 315 ordinary unit test files; no additions. TextLayoutPlanner is 2,607→1,332 lines, CollisionRelaxation 1,133→392. An incorrect 1,321-line report count was returned for artifact correction. User report hash remains unchanged.

[Implementation evidence](rendering-island/results) records the corrected intermediate compile failure and remaining test-consumed seams. [Gemini/Antigravity review](rendering-island/review) accepted the actual diff. Local commit a9c38fb5 contains only the six reviewed paths; origin/architecture-cleanup remains at the original baseline. P1.1 and P1.2 together remove seven files (four main, three ordinary unit test), add none and reduce the tree by 3,713 net lines. Main sources are 286; ordinary unit test sources 315; testDebug one and androidTest six remain unchanged.

Each delivered slice compiled and preserved its affected live boundaries with independent review. Phone testing remains deferred, and later phase entry obligations remain in the final closure record.
