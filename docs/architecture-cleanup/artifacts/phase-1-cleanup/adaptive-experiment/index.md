---
title: "P1.4 — audit and retire only unnecessary adaptive experiment machinery"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Completion

Read-only audit and bounded retirement delivered. Live gate/priority/cancellation/normalization/stage telemetry preserved; seven experiment-only tests retired and seven live tests retained. All54 adaptive/coexistence tests pass in both debug variants (baseline61, exact−7). Gemini/Antigravity accepted actual code; coordinator local commit b44143f2. Source284 and ordinary unit315 files; observer computation intentionally retired. Artifact-only factual review corrections requested; no source changes required. P1.5 consolidated validation remains required before Phase1 closure.

# Decision — retire unused computed controller, retain fixed live gate

Coordinator reviewed the audit and current source. Retire the staged computed-concurrency experiment in this cleanup: the user's simplicity-first direction favors removing dormant optimization machinery, and no production caller applies its computed values. This intentionally retires the E20a/E20b staging implementation, rather than keeping it for a hypothetical later flip. Any future adaptive policy needs a separately measured design. This is a behavior-preserving admission cleanup: current production capacity is one and stays one, injected gates/suppliers stay supported.

The controller and adapter are live constructed objects whose computed results are unused outside the experiment; describe this as intentional retirement of unnecessary machinery, not zero-caller dead files. DevicePagePermitGate, DevicePermitPriority, DeviceStageNormalization, stage spans/observer registry and all required memory/native behavior remain. NativeRunQuarantine is constructed/owned by TranslationPipeline and supplied to EngineLane, correcting the audit's EngineLane-ownership wording. DeviceStageNormalization has five external production consumer files plus its declaring file (six search hits), not six external consumers. Pipeline's default controller is floor one; floor is an instance property, not a compile-time constant itself.

GLM implementation follows [bounded retirement ticket](retirement). Original read-only scope below is historical and fulfilled by [audit](audit).

# Investigation entry

Base architecture-cleanup local commit 1c1d247e. P1.1–P1.3 accepted and locally committed. User authorized Phase 1 verified cleanup with phone testing deferred. This ticket starts with a read-only audit: no source/test edits or Gradle runs until the exact retirement and preservation manifest is returned and accepted by coordinator.

# Candidate boundary

Inspect pipeline/adaptive/AdaptiveKnobController.kt, AdaptiveKnobSignalAdapter.kt and DevicePagePermitGate.kt, TranslationPipeline construction/event wiring, stage-event definitions and producers, AdaptiveKnobControllerTest and all callers across source/tests/resources/docs. Applied concurrency is reportedly pinned to floor, but this must be traced at the current commit. Computed output being unused by admission does not establish that telemetry subscriptions, event fields, normalization types, or tests are disposable.

# Required audit output

Provide exact consumer/ownership graph and keep/delete/replace table by class/method/file. Identify fixed capacity provenance, constructor defaults/injected gate behavior, event-subscription lifecycle and disposal, memory/thermal sampling, computed-only telemetry vs required stage records, immutable priority semantics, cancellation and permit-release invariants. Preserve active admission, foreground/reader priority, render reacquisition and traces needed by existing observers; quarantine/native/stage behavior is outside this cleanup. DeviceStageNormalization and stage contracts must not be lost through transitive deletion.

List tests that exercise only the retired computation versus actual admission, priority/trace or coexistence boundaries. Do not delete a mixed test file wholesale. Prefer co-location into an existing owner and no new files when cohesive; avoid inventing another knob/controller framework. Include exact proposed production and test paths, forecast file/test counts, suitable baseline/post selectors, and any legitimate consumer that prevents Phase 1 removal. No dead-code conclusion based merely on grep count or hardcoded floor; no runtime/performance claims without measurement.

Write audit at audit/index.md and reply with proposed bounded implementation scope or justified KEEP/DEFER. No code/config edits, builds, tests, models/devices/providers, commits/pushes/subagents during this audit. Original evidence and report preserved.
