---
kind: review
title: "Maintainability plan: critique disposition and incorporated corrections"
comments: none
---

# Status and evidence

The [independent critique](../independent-review) delivered eight findings against the [detailed plan](..). The orchestrator read the report, checked the cited paths against `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` and amended ownership, capability, implementation and validation contracts. **Specification review is complete: the adversarial recheck found no remaining blocking mechanism contradictions. No production code, tests or build files changed, and no runtime verification was executed.**

The original critique's three P0 labels do not mean three production failures were reproduced. Native result cleanup and artifact-lock reentrancy were already identified design gates; the review usefully demanded more explicit mechanisms. The publication exception-to-delete path is an additional source-supported contract risk. Memory size, inevitable permanent leak, current deadlock and corrupted-file claims need runtime/order evidence before becoming observed incidents.

# Finding dispositions

| Review finding | Disposition | Incorporated change / remaining gate |
| --- | --- | --- |
| 1: late native result disposal | Accepted contract precision; fixed-size/permanent leak claim unverified | Result installed inside native invocation into registered slot; noncancellable native finalizer before admission release; explicit post-exit abandonment hook. Available output for a live slow provider is retained, not prematurely recycled. All claim/abandon/continuation-loss orderings remain mandatory MR-02 tests. |
| 2: first-write opening-lock recursion and stale install | Accepted explicit lock-order requirement; proposed path not a reproduced baseline deadlock | MR-07A now prescribes reservation/release, canonical document exclusion, single artifact-open acquire and short locked revalidation/install. Already-open bootstrap helper cannot reacquire; public initialization cannot be awaited from lock-holding callers. Identity/generation/init token/page/journal evidence rechecked, no stale page-map replacement. Reverse-edge removal and race tests block migration. |
| 3: cancelled publication deletes accepted image | Accepted source-supported contract risk | `CleanedImagePublisher` currently deletes new file if commit callback throws cancellation. MR-02 adds narrow noncancellable commit decision plus internal receipt recording; definite rejection/no-reference required for deletion, and uncertain/live/durable outcomes reconciled separately. Cancellation after accepted mutation but before response is a required test, not a claimed reproduced incident. |
| 4: separate native admissions need epoch/resource lease | Accepted fallback prerequisite | Separate admissions disabled until exact captured engine epoch borrow and operation-owned input/private scratch survive the queue gap. Close/rebuild/reclamation deny or await borrowers; no new native permit or parallel-session authorization. |
| 5: inpaint color estimate duplicates final render work | Accepted recommended consolidation with caller-parity gate | Final pure estimator at join/render on immutable final inputs; retire fresh inpaint-time call after all modes/resume/preparation consumers are inventoried. Changed retry bitmap can legitimately trigger another pass. Existing recognition/persisted style facts and blank-source behavior preserved. |
| 6: asynchronous fingerprint lookup versus pure planner | Accepted explicit per-page integration; source type corrected | `BatchExpectedFingerprints` is a configuration-evidence data class (`PageWorkPlan.kt:74`), not a Map. `LazySourceFingerprints` implements blocking source Map (`BatchChapterTranslator.kt:1203`). Resolve current/required source page evidence at suspending worker boundary, pass plain values to pure planners; provisional rows cannot authorize reuse/context. Genuine wider legacy/context prerequisites stay explicit. |
| 7: diagram skips manual admission barriers | Accepted diagram correction | Diagram now shows lease/source eligibility, required OCR writes/deferred-publication acknowledgment and recheck before HTTP entry; native inpaint still overlaps that branch. |
| 8: percentages lack empirical noise bands | Accepted explicit prerequisite; already deferred measurement gate | MR-00 must collect baseline latency, occupancy/fairness distributions and between-run noise before fixing budgets. Suggested percentages are investigation prompts, never passed evidence or tolerable regression allowances. |

# Source checks that matter to the correction

- `NativeRunQuarantine.run` strips caller Job and `awaitExitAndLogLate` can discard invocation output. The new result registry/finalizer must therefore operate before that return-value boundary.
- A result that is **unclaimed** may still belong to a live page waiting for HTTP. The critique's suggestion to recycle every unclaimed result immediately on native exit is rejected as unsafe; abandonment is a separate state.
- `CleanedImagePublisher.publish` has unconditional new-file deletion on cancellation from the commit callback. `CleanedPublication` has both direct store patch and memory-first/lazy hash-patch paths. Existing code does not establish that every cancellation happens before mutation; tests must control that boundary.
- `ARTIFACT_OPEN_LOCKS` holds a coroutine `Mutex`; the public artifact opener already acquires it before `openArtifactOnly`. A new asynchronous initializer must not be awaited from that held scope and reacquire it.
- `SinglePageOnnxPhase:1203` performs fresh inpaint color recomputation; `SinglePageHttpRenderPhase:756,875` recomputes at render on normal/retried outputs. Removing duplication requires caller/style parity, not assuming all estimator invocations are interchangeable.
- `BatchResumePlanner.buildBatchPagePlans` and batch profile/worker source callbacks currently inspect a synchronous source map; migrating the resolver requires all source-sensitive consumers to receive resolved evidence, while preserving pure configuration fingerprints and context validity.

# Implementation readiness

The revised plan is more explicit about the contracts to implement. The independent reviewer rechecked all five documents and confirmed the eight findings resolved at specification level or contained behind explicit implementation gates. MR-00 prototypes, lock maps, race fixtures and empirical distributions still gate MR-02/MR-07 and final release. No declaration of runtime safety or performance improvement follows from editing these documents.

The recheck is sufficient to proceed to implementation ticket breakdown. Starting production implementation remains a separately authorized action. Preserve the phase prerequisites in those tickets: no blanket claim that the plan's reviewed mechanisms have already passed native/device tests, and no downgrade of the actual migration risk merely because a textual contradiction was removed.
