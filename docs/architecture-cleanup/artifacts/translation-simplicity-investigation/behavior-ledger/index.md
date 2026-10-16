---
kind: review
title: "Reader simplicity inventory: required behavior versus optional coordination"
comments: none
---

# Behavior inventory before coordinator retirement

Baseline `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`; static investigation. This ledger separates existing requirements from optimization choices under the user's new report. It does not declare RollingAutoCoordinator redundant before replacement coverage. See [reassessment](..).

| Behavior | Baseline mechanism | Required outcome / candidate action |
| --- | --- | --- |
| Tap current page | Scheduler/manual pipeline | One common single-page runner. Explicit request is acknowledged or typed rejected, never silently discarded. |
| Auto viewport N / next page | Rolling coordinator/window | Keep earlier N/N+1 demand as the candidate product baseline. Suppress next-page admission when memory is insufficient; no configurable speculative wave required. |
| Swipe onto prefetched N+1 | Auto/manual arbitration | Keep zero-restart promotion for compatible active work: same operation, lease and provider work. Priority changes future admission; they cannot preempt native inference or undo a paid request. |
| Page deduplication | Coordinator/scheduler registrations | One active compatible attempt per session/canonical page; no duplicate native/provider work. |
| Force/config/source replacement | Existing lease/generation/fingerprint checks | Preserve incompatibility handling and ordered replacement; a forced retry is not swallowed as a duplicate. |
| Chapter switch | Generations/resolver invalidation/teardown | Old callbacks lose authority immediately; actual native drain precedes engine/store destruction. Keep committed display. |
| Cancellation | Scheduler joins/quarantine/lease release | Abandon requested work, suppress future unauthorized writes, drain actual native ownership. Bounded UI acknowledgement is not destruction permission. |
| Auto disabled | Coordinator stop/window clearing | Remove automatic demand while preserving explicit manual demand and committed output. Paid-call handling is stage-specific. |
| Provider call already charged/in flight | Rolling coordinator bounded NonCancellable provider drain | Preserve the product policy deliberately: active same-page promotion keeps the call; navigation/auto-off may drain according to existing bounded policy, discard stale results, and avoid restart/recharge. Native drain and provider drain are different. No automatic blanket cancel or unlimited HTTP NonCancellable scope. |
| Memory / local compute | Memory gate, prepared capacity, compute semaphore | Keep conservative memory limits and LOCAL_COMPUTE exclusion. Prepared queues/adaptive prefetch capacity are optional mechanisms. |
| Missing page stream / stream-ready event | Resolver generation guard and poke | Keep event-driven wake/revalidation. Do not spin scroll retries or turn temporary absence into permanent success/failure. |
| Provider retry/backoff | Typed pause/defer, reconciliation | Keep truthful non-success and existing rate/quota/backoff protection. Replace distributed auto slot bookkeeping where a small queue fact suffices. |
| Textless / resume / refresh | Pipeline/store projections | Preserve terminal textless handling, eligible resume and existing committed image during failed refresh. |
| Batch arbitration | Session policy and leases | Reader and batch cannot concurrently assume exclusive chapter/page authority. Batch scheduling stays distinct and recoverable. |
| UI progress | AutoSlotState plus shared PageView | Prefer shared PageView/display projections and auto toggle. Keep required transient queue feedback without another canonical lifecycle. |
| Scheduling traces | Coordinator trace lifetimes | Keep useful operation correlation and truthful terminal outcomes; retire auto-only trace plumbing if no longer required. |
| Atomic publication/recovery | Store, artifact engine/journal | Required guarantee. Current implementation can evolve in a later isolated slice; no decomposition of live authority in this work. |

# Reader concurrency options to measure

| Policy | Simplicity | Cost / preserved behavior |
| --- | --- | --- |
| One active reader page, next page pending | Least task/bitmap/provider coordination; sequential complete page jobs | Current page behind an already selected page waits; no cross-page HTTP/native overlap. Same-operation promotion still works. Candidate performance baseline, not already accepted shipping behavior. |
| At most two active reader pages, sequential stages within each | Common runner; one native lane; N HTTP can overlap N+1 local work | More active resource/drain bookkeeping, but no separate prepare/consume channels. Leading compromise if serial policy harms reading. LOCAL_COMPUTE may still need one active execution permit. |
| Stage-specific channels/adaptive waves | Highest coordination cost | Not the default; retain only with demonstrated benefit not obtainable from the simpler policy. |

Prior Step 1 required cross-page overlap. The new report makes optimization preservation conditional and permits simple independent-page concurrency when necessary. That changes the decision criterion; it does not prove globally serial reading is responsive enough. Measure page/next-page latency, native admission waits, peak memory and paid-call behavior before choosing the active-page limit.

Queue priority among pending work can remain two levels. Promotion of active work is an intent update, not a restart. Strict reprioritization of waiters already inside device/provider gates is optional until the required foreground-latency behavior demands it. If that requirement is restored, the previous live-waiter critique becomes relevant again; merely renaming priorities would not satisfy it.

# Test preservation and retirement

- Keep store lease/generation/version fences, candidate/committed readiness, durable artifact recovery and actual native completion tests.
- Rewrite old manual-preempts-auto tests to assert compatible same-job promotion only after that replacement exists; preserve incompatibility/preemption and teardown cases separately.
- Retire channel/window/adaptive-controller implementation assertions only with the corresponding intentionally retired behavior. Replace genuine admission/trace assertions first.
- Virtualize control-plane clocks and scheduling with existing/injected dispatchers. Keep physical native/IO thread probes as appropriately bounded integration tests.
- No test file is classified flaky merely from a sleep, timeout or mock constructor. The previous test-reliability review contains corrected claims and no runtime demonstration for most candidates.

# Readiness boundary

The user's priority order is clear enough to continue investigation without asking permission. The final serial-versus-two-page choice and bitmap boundary are not yet established by measurements. Record these as open technical/product tradeoffs rather than silently converting recommendations into committed behavior. The next implementation plan must name each kept/simplified/retired behavior and its test, and update file accounting after those choices.
