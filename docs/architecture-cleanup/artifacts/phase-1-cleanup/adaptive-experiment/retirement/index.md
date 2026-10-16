---
title: "P1.4 implementation — retire unused adaptive computation, preserve admission"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Completion — accepted and locally committed

Gemini/Antigravity accepted the mechanism, retained tests and saved two-flavor evidence. Coordinator committed six filesystem paths as b44143f2: +243/−858 lines including new test path, net615 lines removed. Two production files retired; one mixed test file replaced/renamed with seven live cases. Main284, ordinary unit315. Review caller-list and cleanup-wording inaccuracies were returned for artifact-only correction against source; they do not alter the independently checked gate bodies, tests or accepted code. Remote not updated yet; P1.5 consolidation follows.

# Delivery checkpoint — historical pre-review record

Implemented against 1c1d247e, uncommitted. Two main files deleted, two edited, one mixed test suite renamed and reduced from14→7 retained live tests. Tracked diff +10/−858 plus233 lines in the new untracked test path gives615 net lines removed; ordinary git diff omits that new path, so reviewer must inspect it explicitly. Coordinator verified saved baseline19classes/61tests per flavor and post19classes/54tests per flavor, all zero failures/errors/skips. Coexistence18classes/47tests unchanged. Main sources284; ordinary unit files315. User report hash unchanged and original Phase0 evidence preserved.

[Results](results) record scope, comparisons and limits. Gemini3.8Flash/high actual-code review requested through Antigravity before commit. P1.5 not started; no completion claim yet.

# Settled scope

Base 1c1d247e on architecture-cleanup. Parent selected retirement under the existing simplicity-first authorization. No new framework, adaptive policy, native ownership, scheduling semantics or performance claims. GLM implements; Gemini/Antigravity reviews actual diff before local commit.

| Exact path | Allowed change |
| --- | --- |
| app/src/main/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobController.kt | Delete controller and four computation-only types after refreshed outside-experiment reference checks |
| app/src/main/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobSignalAdapter.kt | Delete adapter and experiment-only global observer subscription; do not change observer registry or trace producers |
| app/src/main/java/eu/kanade/translation/pipeline/adaptive/DevicePagePermitGate.kt | Add co-located DEFAULT_CAPACITY = 1, use it for constructor default instead of controller constant; admission/priority/cancellation/release bodies unchanged |
| app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt | Remove controller/adapter fields/imports and adapter close; default construction uses DevicePagePermitGate() while TestConstruction injected gate precedence remains unchanged |
| app/src/test/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobControllerTest.kt → DevicePagePermitGateTest.kt | Rename class/file; retain seven live gate/trace tests, delete seven computed-controller/adapter-only tests and exclusively used helpers/imports |

# Protected invariants and tests

Production gate capacity remains one; no priority, waiter, timeout, cancellation, permit-release, render-reacquisition or NativeRunQuarantine changes. Preserve injected capacity supplier and TestConstruction gate behavior. Leave DeviceStageNormalization, diagnostics event taxonomy/observers, memory admission, stage normalization setters, all engine/native/store code and existing coexistence harness behavior untouched. Only stale wording within edited files may be adjusted to describe the current retained contract.

Retained tests: pipeline native attachment immediately acquires idle permit; source bounds normalization does not reset trace duration; foreground overtakes queued background; foreground wait cap cleans waiter; cancellation cleans waiter/permit remains usable; remote release/reacquisition; background-only FIFO at capacity one. Preserve original assertions and fixture cleanup; substitute DEFAULT_CAPACITY only where the old constant is referenced. FIFO case removes only controller warm-up/computed-output assertions, uses the fixed default gate and retains FIFO/release assertions. Retain FakeClock or other helpers needed by live tests. Never delete the entire mixed suite or change arbitrary scheduling to get tests green.

# Validation

Pipeline construction loses one global observer, so the full coexistence package is a justified regression surface. Preserve baseline adaptive.* and coexistence.* XML in both DevDebug and StandardDebug before edits; record observed failures rather than assuming green. After edits run these same selectors with fresh main/test compilation in both variants. Compare retained per-method/per-class counts, explicitly account for class rename and seven removed experiment tests, record any new failure separately. No repeat-away; investigate relevant failure only when new evidence warrants. Existing Phase 0 ledger and original XML remain immutable.

Prove unchanged gate operational bodies and native/trace/memory boundaries by diff/reference comparison. Scan all source/testDebug/resources/keep rules/reflection strings for deleted symbols; distinguish documentation mentions from executable references. Correct parent audit's ownership/count/constant wording as documented in the parent decision. Store results at results/index.md with exact scope, preserved baseline/post XML/log commands, file/test counts, report hash, known limits and reviewer-ready diff. Stop for actual-code review; no commits/pushes/subagents or further slice.

Forecast only: main 286→284 source files (two deletions), ordinary unit test count stays315 (one rename), testDebug1/androidTest6 unchanged. Seven tests retired, seven live retained in renamed suite. No count quota overrides actual reachability.
