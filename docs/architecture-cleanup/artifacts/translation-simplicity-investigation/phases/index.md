---
title: "Final rebuild phase structure — correctness and simplicity first"
kind: spec
comments: none
---

# Scope and status

Current execution: Phase 1 host cleanup and [Phase 2 contracts and suspending boundaries](../../phase-2-contracts/closure) are complete. The reviewed test-only store/trace prerequisite is also complete and published on local/remote `architecture-cleanup` at `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`. Both full app variants now run2254 tests with0 failures/errors and1 benchmark skip. [Phase 3 ](../../phase-3-entry)[architecture planning](../../phase-3-planning) is complete, with an [independently approved memory-first plan](../../phase-3-planning/plan). [Phase 3 implementation](../../phase-3-implementation) is now authorized and underway, beginning with unwired operation-scoped types (S1). Phases 4-8 have not started. Latest user selection (16 October 2026): Gemini3.8Flash/high through Antigravity implements; GLM5.3Flash/max through OpenCode reviews. Autonomous continuation through the remaining phases is authorized; [current execution policy](../../execution-policy) governs recovery and roles.

This is the phase order for the translation maintainability rebuild, following the user's simplicity-first direction of 14 October 2026. It replaces the previous roadmap's optimization-preservation assumptions. The old Step 1 C00–C11 commits remain historical planning evidence and need revision before execution.

Baseline: `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. Host baseline execution and corrected static evidence are recorded in [Phase 0](../../phase-0-baseline). The user authorized proceeding with Phase 1 on `architecture-cleanup` while deferring phone testing. No device evidence is claimed. Phase 1's current changes and validation belong in its [execution ticket](../../phase-1-cleanup).

This settles sequencing, not every implementation mechanism. In particular, bitmap handoff and serial-versus-two-page reader concurrency remain explicit decisions in Phases 3–4. Do not present unmeasured alternatives as accepted shipping behavior.

# Final phase structure

| Phase | Purpose / included work | Completion gate |
| --- | --- | --- |
| **0 — Reproducible host baseline and behavior contract** | Pin revision/configuration. Run applicable host builds/tests and retain existing failures. Inventory required behavior and trace three change-isolation exercises. Record missing device/native evidence explicitly. | Host execution provenance, owned failures, behavior ledger and reviewed source traces complete. Per user decision, phone/native measurements are deferred to relevant Phases 3–4/8 gates. |
| **1 — Verified purge and test repair** | Remove confirmed orphan helper/rendering islands and exact dead members; retain live rendering paths. Co-locate justified sole-caller policy. Retire the pinned adaptive experiment if its admission/trace assertions are preserved. Repair false coverage and deterministic control-plane test seams. | Reference/compile checks and active-output/boundary parity. No deletion solely because a test is flaky, real-time, small or mock-heavy. Updated exact retirement manifest and counts. |
| **2 — Clear contracts and suspending boundaries** | Move telemetry/normalization to their real owners; type rejection semantics and add narrow dependency checks. Relocate shared context-frontier ownership in a separate small commit after caller inspection. Make ordered chapter replacement suspending. Replace hidden blocking fingerprint Map lookups with the simplest suitable suspend API/cache. | Diagnostic text cannot change UI actions; intended dependency checks pass; cancellation/replacement ordering and source/resume evidence remain correct. No new blocking compatibility shim or eager pristine-chapter hashing. |
| **3 — One sequential page job with safe resources** | Execute decode → recognition → inpaint → translate → style/render in an intelligible sequence. Remove detached inpaint and the early duplicate style pass. Keep a private working draft and existing provider identities. Resolve bitmap ownership across actual native exit and queued persistence writers; protect accepted/uncertain publication receipts. | Cancellation/timeout/teardown races, alias/null/no-erase/textless, writer completion, source/user-edit fences and candidate/committed publication tests. Native quarantine and engine guards remain safe. Measure ordering/publication costs; select the smallest safe ownership boundary. |
| **4 — Unified reader queue and auto retirement** | Manual tap and auto viewport use the same page runner. Implement deduplication, current/next-page demand, same-operation promotion, memory suppression, event-driven deferral and explicit paid-call/drain policy. Compare serial reader execution with bounded two-page overlap; retain necessary LOCAL_COMPUTE exclusion. Absorb useful tests, then remove old auto coordinator/channels/slot UI. | Required behavior ledger covered; no restart/recharge on compatible promotion; chapter teardown waits for physical native completion. Chosen concurrency/foreground latency is measured and consciously accepted. Old files retire only after replacement coverage. |
| **5 — Shared processing ownership and change isolation** | Establish cohesive concrete recognition/preparation, inpainting, provider/render and guarded-publication capabilities where truly shared. Separate recognition/inpaint roles only with safe lifecycle ownership. Replace Pipeline → BatchTranslator → LaneWorkers forwarding with those actual owners, not a mega-interface. Keep batch and reader scheduling distinct. | OCR metadata, retry-input and publication-rule exercises demonstrate fewer forwarding-only changes. Engine/model results and native ownership remain correct. OCR/LiteRT boundary review before engine-role changes. |
| **6 — Simpler batch orchestration** | Consolidate batch-only configuration, per-run state, scheduling and contextual planning around clear owners. Remove duplicate processing decisions now supplied by Phase 5. Retire overlap/adaptive/speculative mechanisms only when measured benefit does not justify them. Preserve legitimate ordered chunks, context gaps, resume/checkpoints and source eligibility. | Reader/batch contention, interrupted/restarted runs, partial/failed/terminal pages, source/config drift and contextual-order tests pass. Batch throughput/memory cost is measured and accepted. No generic executor or forced reader/batch scheduler merger. |
| **7 — Cohesive persistence internals** | Simplify/extract independent journal, serialization and artifact mechanics behind one live chapter-state/publication authority. Address first-write blocking initialization and its actual lock order separately. Remove verified persistence-only dead code. Preserve distinct durable/live status vocabularies. | Lease/generation/version fencing, accepted publication, process-death recovery, manifest/journal consistency and committed-display invariants. No independently mutable authority replicas or new canonical page state machine. |
| **8 — Integration, documentation and release validation** | Update implemented architecture/naming docs, enforce dependency rules, reconcile final file counts and remove temporary migration adapters. Run full variants and required physical-thread/device regressions. Review measured costs and release/rollback readiness. | Builds/full boundary suites, cancellation/native/device tests and recovery outcomes pass. Performance deviations are explained and accepted against Phase 0 distributions. OCR/LiteRT compatibility signed off on actual changed seams. No outstanding migration or unverified deletion. |

# Rules applying to every phase

Standing user instruction (15 October 2026): when each phase is complete and reviewed, commit all approved in-scope changes and push to `origin/architecture-cleanup` in KimreneOuk/TachiyomiATVibe. Verify the remote branch commit matches local HEAD, and record the published commit in that phase's closure artifact. Intermediate reviewed slice commits may be local until phase completion. Keep the user's original untracked architecture report out of staging. No additional publication confirmation is required for this branch; report any actual push blocker.

1. Ship small independently compiling slices. Diagnose failures before moving on; never retry away a failure or delete a live contract to obtain green.
2. Preserve one live state/publication authority, stale-write exclusion, committed-display retention, atomic durable recovery and actual native completion. Existing optimization/class structure can change; correctness obligations cannot disappear.
3. Measure performance at each relevant execution behavior change. Phone testing is deferred for verified non-behavioral cleanup and host test repair; missing physical/device evidence remains a gate for accepting relevant Phases 3–4 changes and release validation. Phase 8 is final integration, not the first benchmark. No arbitrary percentage/file-count target defines success.
4. Keep OCR/LiteRT algorithms, weights, tensors and backend migration out of these refactor commits. Review their lifecycle/admission interfaces where changed.
5. Retire implementation-specific tests only after preserved behavior has equivalent meaningful coverage. Keep actual native/IO completion probes separate from virtual-time control-plane tests.
6. Keep temporary adapters time-limited and counted. Recalculate removal/addition totals after Phases 1, 3–4 and final cleanup; the old net-minus-11 forecast is not the new design's quota.
7. Optional experiments and earlier optimizations require current evidence. Do not carry a Deferred registry, identity sidecar or dynamic priority observer into the baseline merely because the older plan specified it.

# Current position

## Phase 0 review adds an entry prerequisite

The [independent Phase 0 review](../../phase-0-baseline/review) found four failing lazy-store assertions/cleanup methods. Before Phases 3–4 alter page/reader ownership, ground their intended candidate/committed, pre-registration and resource-lifetime contracts and record any necessary isolated repair/rebaseline. This is a narrow correctness prerequisite; deep persistence restructuring remains Phase 7. Do not presume the failures are production regressions, outdated tests or fixture-only leaks without controlled/source evidence. The [correction ledger](../../phase-0-baseline/review-response) tracks the investigation gate.

The user authorized proceeding and deferring phone testing. **Phase 0 host/static baseline is complete; Phase 1 verified cleanup is in progress on `architecture-cleanup`; Phases 2–8 are not started.** Device/native checks remain deferred; controlled storage diagnostics remain required before publication/reader ownership changes. Actual Phase 0 test failures are preserved. Revised slices remain grounded in the [source reassessment](..) and [mechanism alternatives](../mechanisms).

# Authorized store prerequisite investigation

On 15 October 2026 the user authorized [targeted investigation of the four baseline store failures](../../store-baseline-investigation) using GPT 6 Luna/max/fast and Gemini/Antigravity review, starting at published 999bb9be. The investigation is complete and independently reviewed: a fixture manifest-reader leak sufficiently explains the four observed failures. The minimal helper repair and full rebaseline are now complete; [publication closure](../../store-baseline-investigation/repair/closure) records the new revision. Phase 3 ownership refactoring has not started.

# Authorized continuation to Phase 3 entry

The user instructed proceeding until Phase3. The [test-reader repair and rebaseline](../../store-baseline-investigation/repair) is complete, independently accepted and published. Phase3 entry scope is prepared; native/page ownership implementation is not part of the test-only repair and has not started.

# Same-page concurrency correction

The user explicitly chose preserving inpaint + external-translation overlap after OCR. [Phase 3 execution](../../phase-3-implementation) is held for technical-plan revision and independent review. The goal is simpler owned orchestration, not serialization of independent native and network work. Memory-first remains settled; no performance result is inferred.

# Overlap plan accepted and execution resumed

Independent Gemini review accepted the revised same-page overlap plan. [Phase 3 implementation](../../phase-3-implementation) resumes with S1; prior hold statements are historical. Host/runtime/device proof gates remain future work.

# Validation policy for subsequent phases

Standing user instruction: audit and prevent repeated costly or stalled checks. The [validation audit and run policy](../../phase-3-implementation/validation-audit) applies across remaining phases: targeted iteration, no gratuitous full rebuilds/sweeps, one monitored Gradle owner, bounded host-run guards, preserved failed evidence and actual task exit accounting. Broad suites stay at justified integration/consolidation boundaries. Production real-exit obligations remain unbounded by these host-test limits.

# Proposed reader-loading work between Phases 3 and 4

The Side agent delivered a source-refreshed [loading-performance audit](../../loading-performance-plan/refresh-audit), independently accepted with corrections. It proposes separate reader-entry/chapter-list work after Phase 3 (and a limited isolated UI/projection subset potentially in parallel). This is a proposed insertion, not an execution authorization or a completed phase. The existing Phase 3 scope and single Gradle owner remain in force until the user decides the additional sequencing.

# Autonomous continuation ? current state

The user authorized all remaining phases without routine supervision on 16 October 2026. S1-S4 of Phase 3 are accepted, committed locally through ff21fd15; S5 source/interface preparation is in progress before source edits. Current implementation/review roles are [Gemini / GLM respectively](../../execution-policy). Historical status paragraphs above are chronological records, not a reset of completed work. Phases 4-8 still need their grounded planning, implementation and validation; device gates remain explicit.
