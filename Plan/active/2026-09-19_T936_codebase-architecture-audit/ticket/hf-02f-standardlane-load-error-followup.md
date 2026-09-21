# Ticket HF-02F (follow-up): StandardLane full-load ERROR variant — mechanism uncaptured

**Priority:** LOW (tracked follow-up, non-blocking) | **Origin:** HF-02 blocker investigation

## Background

During HF-02 verification, `StandardLaneMultiPageCompletionTest.fresh standard batch translates
every page of a multi-page chapter` exhibited two distinct failure signatures:

1. **IO-dispatch starvation** (10s timeout waiting for p0 transport start) — FIXED by
   `CoroutineStart.UNDISPATCHED` batch launch (d93450c).
2. **Unjoined orphan teardown bleed** (negative test's discarded `BatchRun.job` executing against
   unmocked statics) — FIXED by retain + `cancelAndJoin` before teardown (d93450c).

Both mechanisms verified: isolation 10/10 (implementor) + 3/3 (orchestrator gate machine, which
previously failed 2-of-4), full Dev green on both machines.

## Open item

A third signature remains **observed once, mechanism never captured**:

- Full-suite run, WITH the harness fix applied: `expected TRANSLATED but was ERROR` at line 83;
  p2 marked ERROR **pre-transport** (p0/p1 transported fine); test completed in 0.59s (fast-fail,
  not starvation); no page error snapshot emitted.
- Evidence preserved: `team/hf-02-blocker-evidence/full-dev1-StandardLane-20260921-084220.xml`
  (plus full failure-run-1..10 series from the pre-fix isolation loop).
- One subsequent instrumented full run and one clean full run per machine: PASS. Not reproduced
  since; conditional diagnostics never fired on a failing run.

## Suspects (from call-graph analysis)

- `BatchLaneWorkers` pre-transport ERROR paths (lease/retry rejection racing under load).
- `ChapterTranslator` catch-all page ERROR marking under latency (e.g., decode/render mock
  timeout interpreted as page failure).
- JVM-global pollution from unrelated earlier test classes (shared statics / saturated IO pool
  from unjoined work elsewhere in the suite).

## Task (when picked up)

1. Add durable (non-temporary) page-error observability to the coexistence harness — every
   transition to ERROR must carry a reason in test output.
2. Run full-suite repetition (≥10 full runs or a load-approximating harness) until captured.
3. Root-cause and fix the mechanism; if production code, route through review.

## Constraints

- No assertion weakening; no blanket timeout inflation to mask latency.
