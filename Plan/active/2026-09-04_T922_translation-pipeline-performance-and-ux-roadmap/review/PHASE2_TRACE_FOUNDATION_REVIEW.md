# Phase 2 (Trace Foundation) — Independent Review

**Role:** Reviewer / Failure-mode auditor (T922)
**Date:** 2026-09-04
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`, HEAD `7a95f9c9` — unchanged from baseline capture)
**Reviewed artifact:** the 4 new files in `eu.kanade.translation.diagnostics` (2 main, 2 test)
**Governing contract:** plan §4 + mandatory amendments §10 (10.2, 10.3, 10.5, 10.6)
**Inputs:** role file, task README, plan, `PHASE2_TRACE_FOUNDATION_IMPLEMENTATION.md`, `BASELINE_CAPTURE_2026-09-04.md` + baseline patch at `C:/Users/User/Documents/T922_baseline_backup_2026-09-04/baseline_tracked.patch`, live source/tests.

## Verdict: APPROVED WITH NOTES

The Phase 2 foundation is correct against every audited amendment clause. Scope discipline is proven byte-for-byte. Tests are real and mutation-sensitive, with one confirmed coverage gap (F1) that must close with/before Phase 3 wiring. No changes are required to Phase 2's deliverable itself.

---

## 1. Scope verification — VERIFIED, zero deviation

Method and evidence:

| Check | Evidence | Result |
|---|---|---|
| HEAD / branch / staged state | `git rev-parse HEAD` = `7a95f9c94ec369efb6273ba7a209f7e42ddc4f80`; branch `optimize_translation_pipeline_ux`; `git diff --cached --binary` = 0 bytes | Matches baseline |
| 14 Director-owned dirty tracked files | Current `git diff --binary -- <14 baseline paths>` = **byte-identical** to `baseline_tracked.patch` (both SHA-256 `c0be540a29f9ab33e6d1ab33121bfbf1184c3730c6a988dfb708ccd6075ab467`, 56,290 bytes) | UNCHANGED |
| 5 protected untracked files | SHA-256 of all five match baseline record exactly (`e1dce9a9…`, `430102d9…`, `f66aaabe…`, `7d61f647…`, `de74c62c…`) | UNCHANGED |
| Phase 2 additions | Exactly 4 new untracked files, all in `translation/diagnostics` (main + test pairs) | ONLY new files |
| 2 extra dirty files vs baseline | `App.kt` (+21/−4 startup-diagnostics receiver, T922 §3.5 markers) and `OnnxBubbleSegmenter.kt` (+219/−29 CPU-primary + recovery seam, §3.1/§3.2/§6.1 markers) — attributable to Phase 1 per `PHASE1_STABILITY_IMPLEMENTATION.md` §1.1–1.2 | Pre-existing (Phase 1), not Phase 2 |
| No production wiring of the new API | Repo-wide grep: no file outside `translation/diagnostics` references `TranslationPipelineDiagnostics` / `TranslationTrace*` (only pre-existing `BatchDownload*` imports) | Confirmed foundation-only |

## 2. Trace math (amendment 10.6) — VERIFIED CORRECT

All evidence `TranslationTrace.kt` unless noted.

- **Transition settling:** `settle()` (lines 343–356) runs **before** the active-count mutation on every `enter`/`exit` (309–327), so elapsed time is attributed to the prior counts; `snapshot()` also settles. No transition loses or double-counts time.
- **Three distinct quantities:** busy totals are per-lane counts>0 integrals; `unionActiveNanos` is count≥1; `overlapUnionNanos` is count≥2; `concurrencySavingsNanos += max(count−1,0)*delta` (line 355). These are four independent integrals, correctly computed.
- **Triple-overlap 1 s case:** hand-traced — busy=1000/1000/1000, union=1000, overlap=1000, savings=(3−1)×1000=**2000 ms**. Test `triple overlap produces correct busy union overlap and savings` (TranslationTraceTest 190–207) asserts exactly this, plus `workMs=3000`. Verified.
- **Overlapping/nested case:** native [0,2000] ∩ provider [1000,3000] → overlap 1000, savings 1000, union 3000 — test 209–244 asserts all values, plus nested same-lane enters (count 1→2→1→0) yielding render busy 400 = union span only. Verified.
- **Repeated stage intervals SUM:** `recordStage` line 590: `stageNanos[stage] = (stageNanos[stage] ?: 0L) + durationNanos`. Test asserts `stageSumMs=200 bottleneckMs=200 retries=1` for two 100 ms OCR attempts. Verified.
- **Queue waits separately reportable + bottleneck-eligible:** queue stages (`lease_wait`, `native_queue`, `prepared_queue`, `provider_governor_wait`) sum into `run_end.queuedMs` (line 680), feed `schedule_end.maxQueueMs` online (488–492, 592–594), appear in the max-stage bottleneck scan (677–684), and non-queue stages accept an explicit caller-measured `queueMs` that participates in the lag rule (Diagnostics 478). Test `queue waits are eligible and can dominate the bottleneck` shows `bottleneck=lease_wait bottleneckMs=3000 queuedMs=3000`. Verified.
- **Negative-delta clamping:** `settle` line 344, `finishStage` line 613, `elapsedMsLocked` line 696 all `coerceAtLeast(0)`. Test drives a fake clock backwards through span end, run total, and accumulator settle. Verified.

## 3. Idempotent terminals (10.2) — VERIFIED

- **Double terminal = one event:** schedule/run/span/lane-token closes are all CAS-guarded (`AtomicBoolean.compareAndSet`, TranslationTrace.kt 397–404, 508–509, 639–651, 739). Test asserts exactly 1 `run_end` and 1 `schedule_end` with the *first* outcome preserved while `end()` returns false afterwards.
- **Lane token misuse fail-open:** double/triple `token.close()` is a no-op; `exit` without `enter` clamps at zero (line 325) and never throws. Tests assert `nativeBusyMs=100` exactly once and `renderBusyMs=0`.
- **Outcome enum coverage:** all mandated terminals present (`TranslationTraceOutcome`, lines 122–139): `cancelled_before_dispatch`, `cancelled_during_send`, `evicted`, `stale_handoff`, `coordinator_replaced`, `attached`, `skip`, `resume`, `teardown_exception`, plus `pause`, `timeout`, `failure`, `persistence_rejected`, `success` (16 incl. `started`). Enum test pins all 15 terminal tokens onto emitted lines.
- **No suspension:** grep of both main files — zero `suspend` declarations (only comment text). All emission paths are synchronous.

## 4. Privacy (10.3) — VERIFIED, with one test gap (F1)

- **Not ShortHash:** identity is HMAC-SHA256 with a 32-byte `SecureRandom` per-instance key, truncated to 64 bits, namespace-prefixed (`TranslationIdentityKeys`, 223–267). Test proves cross-launch uncorrelatability, within-launch stability, and non-derivation from the existing `ShortHash`.
- **No ID map exists** → nothing to bound or clear; sid/rid are process-random-prefix + counters.
- **Sanitizer:** `classifyError` (Diagnostics 279–289) is a closed whitelist; the *only* message inspection in the module is the digit-group regex `error code ([0-9]{3,5})` on `OrtException` messages (178, 311–314) — QNN 1100 recoverable, no text carried. Hostile-strings test (XSS/SQL/path/secret in chapter/page/error) asserts every hostile substring absent from every line and every `key=value` token matches a safe charset, with `RuntimeException → errorType=unknown`.
- **`reason=` free-text fields ARE bounded in code:** `route_change` (line 722) and `schedule_state` (line 737) both emit `reason=${safeToken(reason)}`; `safeToken` (752–755) requires a **full match** of `[A-Za-z0-9_.-]+` else collapses to `invalid`. I audited every string field on every event family: chapter/page/slowestPage (hex tokens), mode/origin/lane/stage/provider/model/state/plan/outcome (fixed enum tokens), errorType (whitelist or `safeToken`), errorCode (Long or `none`), everything else numeric. **No path exists for raw content to reach a log line.** However, mutation testing showed *no test pins* the `reason` sanitization — see F1.
- **pageIndex** is the only raw non-content field — explicitly accepted by §10.3.

## 5. Gating and overhead (10.5) — VERIFIED

- Gate = `@Volatile detailedTracingEnabled` defaulting to `BuildConfig.DEBUG` (Diagnostics 167–168); Phase 3/4 own preference wiring (documented deviation).
- Detailed (gated): `schedule_start`, `run_start`, `stage_start`, non-lag success `stage_end`, `schedule_state`, `route_change` — each emit checks the gate (355, 396, 445, 520, 548).
- Always emitted: `run_end`, `schedule_end`, plus lag/failure `stage_end` (481: suppress only when `!detailed && !lag && outcome==SUCCESS`). Gate-off test asserts the exact 4-line survivor sequence and that a lagged success survives while a plain success does not.
- **Coalescing:** `reportState` suppresses identical consecutive `(state, reason)` per schedule (471–476); test verifies cross-schedule independence.
- **Fail-open:** every emitter is `try/catch(Throwable)` and the sink call is double-wrapped (784–792). Verified by code read (no fault-injection test exists; acceptable given uniform wrapping).
- **No suspension** anywhere in the emission path.
- Overhead ≤3% / log-lines-per-page acceptance is **unmeasurable until wiring** — correctly deferred (implementer risk 1); carries into Phase 3/4.

## 6. Bounded memory — VERIFIED

Schedule = one accumulator (fixed `IntArray(5)` + six scalars) + four scalars + one `lastStateKey` string. Run = one fixed `EnumMap<Stage, Long>` (≤17 entries) + one `AtomicInteger`. The diagnostics object owns only gate/sink/id-generator/keys. **No list, no interval retention, no event retention anywhere** (verified by full read). No active-set map exists at all (stateless keyed identity), so the "cleared at termination" clause is vacuously satisfied.

## 7. Thread safety of the overlap accumulator — VERIFIED

All accumulator mutation (`settle`, counts, six totals) happens under a single `lock` (309–340); `snapshot` settles under the same lock. Schedule-level `noteQueueWait`/`noteRunFinished`/`reportState` are lock-guarded; run stage map is `stageLock`-guarded; terminals are CAS. The concurrency smoke test (2000×2 threads) passes. Note F5 on the test's strength.

## 8. Schema formatting and testability — VERIFIED

Fixed key order per family anchored by `identityPrefix` (schema, event, sid, rid, mode, origin, chapter, page, pageIndex → family fields); exact schema token first on every line; all values charset-bounded so the line stays `key=value`-parseable. The exact-line test asserts **eight complete event lines** — the strongest possible pinning. Injectable sink + injectable id-generator/identity keys let all 20 new tests run as plain JVM tests (no Robolectric imports; green on `testStandardDebugUnitTest`).

## 9. Test re-run and assertion strength (independent)

```
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.diagnostics.*"
  TranslationPipelineDiagnosticsTest  tests=7  failures=0 errors=0
  TranslationTraceTest                tests=13 failures=0 errors=0
  BatchDownloadDiagnosticsTest        tests=6  failures=0 errors=0   (pre-existing)
  BUILD SUCCESSFUL — 26/26 green (re-run by reviewer)
```

**Mutation testing performed by the reviewer** (files hash-verified restored afterwards):

| Mutation | Result |
|---|---|
| A: `concurrencySavingsNanos += delta` (break 10.6 savings math) | **CAUGHT** by 3 tests (triple-overlap, nested-intervals, exact-line schema) |
| B: `safeToken` returns raw value (break reason sanitizer) | **NOT CAUGHT — coverage gap (F1)** |
| C: `stageNanos[stage] = durationNanos` (overwrite instead of sum) | **CAUGHT** by repeated-stage test |
| D: gate bypass in `emitStageEnd` | **CAUGHT** by gate-off test |

Privacy tests against the actual §10.3 boundary (keyed identity, hostile chapter/page/error strings, error classification) are genuine and mutation-sensitive by construction (cross-launch inequality fails under any deterministic hash; hostile substrings fail on leak). Idempotency tests assert exact terminal counts with differing second-call outcomes, so a broken CAS would flip both the count and the outcome assertions.

---

## Findings

### F1 — MEDIUM (test gap; close with/before Phase 3 wiring)
The `safeToken` charset sanitizer for `reason=` (schedule_state, route_change) and errorType overrides is correct in code but **unpinned by tests**: reviewer mutation B (passthrough) survived the whole suite because every test `reason` happens to already match the safe pattern. The module's own comment scopes `safeToken` as defense-in-depth, and the §10.3 boundary (keyed identity) *is* tested — so this is a coverage gap, not a defect. **Required follow-up (non-blocking for Phase 2):** add to `TranslationPipelineDiagnosticsTest` a hostile `reason` (e.g. `"leak msg; drop table"`) on `reportState`/`routeChange` and assert `reason=invalid`, plus a hostile `errorType` override → `errorType=invalid`. Risk window opens the moment Phase 3 introduces real reason callers.

### F2 — MEDIUM (Phase 3/5 design note)
`route_change` is entirely detailed-gated (deviation 6). In release, accelerator→CPU demotions will be invisible under `TachiyomiAT.Translation`, while §10.5 retains "lag/failure events at a deliberate level" — a route demotion is a failure-family event. When wiring lands, add an always-on failure variant (e.g. emit route_change when `error != null` regardless of gate).

### F3 — MEDIUM (Phase 4 integration gap)
`TranslationScheduleTrace` has no stage-span API, but plan §4.4 (batch) requires "one envelope stage event" at schedule scope with per-page waiting attributed separately. Workaround today: a dedicated schedule-scoped pseudo-run (`pageRaw=null`) carrying the envelope stage — workable but implicit. Phase 4 should either adopt that convention deliberately or add a schedule-level `beginStage`.

### F4 — LOW/MEDIUM (Phase 3 wiring note)
Explicit `queueMs` passed to a **non-queue** stage end feeds the lag rule and the `stage_end` line but **not** `schedule_end.maxQueueMs` (only queue-stage durations reach `noteQueueWait`, TranslationTrace.kt 592–594). If Phase 3 measures a lease/admission wait as an explicit `queueMs` on the first execution stage, the schedule summary will under-report max queue wait. Wire such waits as queue stages (or extend `noteQueueWait` to explicit queueMs).

### F5 — LOW (test strength)
The concurrency smoke test runs on a frozen fake clock (all deltas 0), so it proves contention safety (no throw/deadlock) but not math correctness under concurrency; `settle` early-returns on zero deltas. Acceptable because all accumulator mutation is lock-confined by construction (verified by read), but a variant with a real monotonic clock and the `busy ≤ wall` invariant would make it meaningful.

### F6 — LOW (benign)
`reportState` builds the coalescing key outside `stateLock` (TranslationTrace.kt 472–476): two concurrent identical reports can both emit. Duplicate diagnostics only; no correctness/memory impact.

### F7 — LOW (seam hygiene)
`sink`, `detailedTracingEnabled`, `idGenerator`, `identityKeys` are process-global `@Volatile` seams, and `TranslationTraceIdGenerator` accepts an arbitrary prefix string. Production defaults are safe (random hex; hex-only tokens), but Phase 3/4 must treat these as process-fixed singletons and never feed non-`[0-9a-f-]` prefixes (an arbitrary prefix could break `key=value` parseability).

### F8 — INFO (naming parity for Phase 3)
Plan §4.4 names an Auto terminal `stale_retry`; the enum provides `stale_handoff` per §10.2 (which supersedes). Phase 3 should document the mapping (or deliberately add the token) so terminality parity tests don't "fail" on a wording mismatch.

### F9 — INFO (expected behavior, documented by implementer)
Late stage ends after run close still mutate the run's stage map and the schedule's max-queue/slowest state (state already terminal and owner-dropped; emission suppressed). Harmless; no action.

## Phase 3/4 wiring sufficiency (assignment item 10)

The API is sufficient to wire manual, rolling Auto, and batch **without adding suspension points**: `startSchedule`/`startRun` cover all three modes (mode/origin distinct); `enterLane` tokens feed the shared overlap accumulator from any thread; `TranslationTraceElement` + `TranslationTrace.beginStage` give deep synchronous code correlated emission across dispatcher hops with NO_OP fail-open outside traces; `reportState`/`routeChange`/`recordRetry` cover scheduling decisions, demotions, and retries; every terminal is idempotent and non-throwing, safe in `finally` and cancellation paths. Gaps for later phases are captured as F2, F3, F4 above (none block Phase 2).

## Required changes

**None for Phase 2.** Required follow-ups before/with Phase 3 wiring: F1 (hostile-reason test), F4 decision (maxQueueMs semantics). Recommended: F2, F3 design decisions at the start of Phases 3/4.

## Evidence index

- Scope: `git diff --binary` of 14 baseline paths ≡ baseline patch (SHA `c0be540a…`); 5 protected SHA-256 match; `git status --short` inventory.
- Tests: `app/build/test-results/testStandardDebugUnitTest/TEST-eu.kanade.translation.diagnostics.*.xml` (26/26, re-run by reviewer on 2026-09-04).
- Code: `app/src/main/java/eu/kanade/translation/diagnostics/TranslationPipelineDiagnostics.kt`, `…/TranslationTrace.kt` (line references above).
- Tests under review: `app/src/test/java/eu/kanade/translation/diagnostics/TranslationPipelineDiagnosticsTest.kt`, `…/TranslationTraceTest.kt`.
- Working tree verified clean of reviewer mutations afterwards (both source files hash-restored; 26/26 green re-confirmed).
