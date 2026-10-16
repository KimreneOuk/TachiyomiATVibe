---
title: "P1.3 — repair diagnostic assertion drift and replace false test coverage"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Completion

P1.3a and P1.3b are reviewed and locally committed as 444b5abf and 1c1d247e respectively. P1.3a fixes the recorded diagnostic assertion mismatch, passes 3/3 in both debug variants and adds one passthrough case. P1.3b replaces two test-local mirrors with six production wave tests and a four-combination real-worker test: final affected set 24 tests per flavor, with the changed coordinator class rechecked 12/12 after unconditional finally cleanup. [Final review](production-coverage/review) accepted the diff. No files added/deleted in P1.3; one Phase 0 failing method repaired, five remain tracked. Original Phase 0 evidence and user report preserved. Commits have not been pushed.

The ordered scope and delivery checkpoints below describe earlier implementation states; this completion record supersedes pending-review/dispatch wording.

# Ordered scope

Base: local architecture-cleanup commit a9c38fb5, after accepted P1.1/P1.2. GLM 5.3 Flash/max implements; Gemini 3.8 Flash/high via Antigravity reviews actual diffs before commits. Phone testing remains deferred.

| Slice | Work | Status |
| --- | --- | --- |
| P1.3a | Repair TranslationDebugMeasurementTest timestamp expectation | Complete: Gemini/Antigravity accepted, local commit 444b5abf; both debug variants pass 3/3 |
| P1.3b | Replace local wave-selection and X6-terminal mirrors in BatchParallelWindowBudgetTest with production-exercising assertions | Dispatched under [exact](production-coverage) implementation scope; no deletion before replacement covers each invariant |

# P1.3a accepted and locally committed

[Gemini/Antigravity review](timestamp-assertion/review) accepted the one-file repair. Coordinator committed it locally as 444b5abf, without pushing. The following checkpoint records the pre-review delivery; acceptance supersedes its pending-review status.

# P1.3a delivery checkpoint (historical)

Uncommitted against a9c38fb5: only TranslationDebugMeasurementTest.kt changed, +24/−1 lines, no new files or production changes. Repair validates the timestamp prefix before exact payload assertions; one additional fixed-input passthrough test checks that existing timestamps are not duplicated. Coordinator independently read saved XML: each pre-change flavor 2 tests/1 failure; each post-change flavor 3 tests/0 failures/errors/skips. Original Phase 0 evidence and user report SHA256 remain unchanged. [Results](timestamp-assertion/results) record exact commands and limits. Review requested from Gemini 3.8 Flash/high in the existing Antigravity session; no commit before acceptance. P1.3b remains pending and is not covered by this delivery.

# P1.3a exact contract

Only editable source: app/src/testDebug/java/eu/kanade/translation/diagnostics/TranslationDebugMeasurementTest.kt. Production TranslationTraceFileSink now prepends a timestamp to each unprefixed entry before serial file writing. The existing failing assertion expects unprefixed lines; Phase 0 recorded this mismatch in both flavors. Keep production sink and ordinary test files unchanged.

Read persisted output after the existing flushForTesting barrier. Verify exactly three timestamped records, timestamp field format, and exact payloads/order after stripping only the validated timestamp prefix. Preserve embedded-newline sanitization assertion and finally-close behavior. Do not assert the exact current clock value or weaken the test to substring membership. Preserve the other lifecycle test. A small additional case for already-timestamped input is optional only if useful; no new files, dispatchers, clock interfaces, or production changes.

Run a narrow baseline of this class in both DevDebug and StandardDebug, preserve observed failure XML/logs, then the same class after the repair with fresh compilation and record exact outcomes. No retry-away or broad unrelated suites. Existing Phase 0 evidence remains unchanged; compare this repaired method separately from the remaining five baseline failure methods. Record commands, test counts, exact changed lines, report SHA256, and limitations in timestamp-assertion/results/index.md; then stop for review.

# P1.3b preservation gate

Current wave test contains a test-local selection implementation; production selection is inside ChapterTranslator.launchTranslatorJob. Current terminal test contains a local boolean-to-string implementation; production gap contract lives in StandardLaneWorker. Both production contracts are live. Keep CrossOriginBitmapBudget tests. Prefer existing test files and a cohesive production seam only when needed to exercise actual behavior; no scheduler-policy changes, callback refactor or helper framework. Final scope must name the actual files and all replacement cases before dispatch. Nothing in this slice authorizes blind test deletion.
