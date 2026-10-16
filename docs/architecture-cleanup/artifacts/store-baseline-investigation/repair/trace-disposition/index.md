---
title: "Unexpected full-suite trace failure ? completion-boundary investigation"
kind: ticket
status: 2
assignee: ""
comments: none
---

The repaired store tests pass in both fresh full app suites. StandardDebug2254 tests has zero failures; DevDebug2254 tests has one newly observed TranslationSchedulerTraceTest failure at line164 (schedule_end count expected1, observed0). Preserve this run; do not fold it into baseline debt or retry unchanged.

Primary source observation: cancelled-manual test waits for run_end, then asserts schedule_end; scheduler completion handler emits run.end before schedule.end. close only cancels the scope and does not join that handler. This exposes a concrete observation window, but the actual failing run did not instrument that window. Review the real trace emission methods and distinguish a supported test-wait flaw from demonstrated cause of every possible trace failure.

Scope at this stage is source audit and a minimal deterministic test-only reproducer/repair plan. Candidate repair awaits the final schedule terminal before asserting unchanged exact cardinalities/outcomes/correlation. Cover analogous success and cancel-before-dispatch waits without replacing real-thread scheduler semantics or adding sleep-based stabilization. Use the existing trace sink seam to force run_end visible while schedule_end is still gated if practical; do not add a generic framework or production hooks.

Parent and Gemini must disposition the proposed bounded change before edits. Accepted store reader patch stays in place. No full reruns, production/scheduler cancellation changes, assertion weakening, commits or pushes while disposition is pending. Finish this necessary test blocker before declaring a clean replacement baseline or entering Phase3.

# Focused acceptance and final full-suite dispatch

Gemini accepted the final TraceTest +3/-2 diff and controlled proof in [code review](code-review). All assertions and three method identities remain intact; temporary instrumentation removed. Final focused3/0 bothflavors and baseline-only LiteRT formatting debt verified. GLM now executes fresh full app variants on the frozen two-file tree (ProbeTest+TraceTest). Earlier Dev full failure remains immutable evidence. Final evidence review and parent integration/publication remain pending; no Phase3 implementation starts during this step.

# Final disposition

Final full suites passed2254 tests with0 failures/errors and1 benchmark skip per flavor. Gemini accepted the final evidence. The three wait-line repair was published with the reader fix at `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`; [closure](../closure) records remote verification and remaining limits. Original failure/proof evidence is retained.
