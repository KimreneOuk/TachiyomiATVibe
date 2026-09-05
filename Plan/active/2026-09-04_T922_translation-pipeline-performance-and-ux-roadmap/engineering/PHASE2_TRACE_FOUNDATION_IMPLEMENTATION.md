# T922 Phase 2 (Trace Foundation) — Implementation Report

**Role:** Implementer
**Worktree:** `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux` (branch `optimize_translation_pipeline_ux`)
**Governing plan:** `translation-pipeline-fix-and-observability-plan.md` §4 (unified structured tracing); mandatory amendments §10 — §10.2 idempotent terminals, §10.3 privacy identities, §10.5 gating/overhead, §10.6 overlap math — supersede conflicting text.
**Contract:** self-contained trace foundation ONLY. No production pipeline wiring (manual/Auto = Phase 3, batch = Phase 4). Zero edits to existing production files. Nothing committed, pushed, or branched (Director's rule).

## Status: COMPLETE

Both production files and both test files created in the new `eu.kanade.translation.diagnostics` package. `:app:compileStandardDebugKotlin` builds cleanly. Focused test target `:app:testStandardDebugUnitTest --tests "eu.kanade.translation.diagnostics.*"`: **26/26 green** (20 new + 6 pre-existing `BatchDownloadDiagnosticsTest`). `git status` verified before and after: exactly 4 new files, 0 modifications to existing files.

---

## 1. Files created

All names verified free immediately before creation (`ls` + repo-wide type-name grep returned nothing):

| File | Content |
|---|---|
| `app/src/main/java/eu/kanade/translation/diagnostics/TranslationPipelineDiagnostics.kt` | `TranslationPipelineDiagnostics` object (schema formatter, gate, sink, start/end APIs, error classifier), `TranslationTraceSink` + `LogcatTranslationTraceSink`, `TranslationTraceLogPriority`, `TranslationTraceBudgets`, `TranslationTraceError` |
| `app/src/main/java/eu/kanade/translation/diagnostics/TranslationTrace.kt` | `TranslationScheduleTrace`, `TranslationRunTrace`, `TranslationStageSpan`, `TranslationLaneToken`, `TranslationLaneOverlapAccumulator`, `TranslationRunIdentity`, `TranslationTraceElement`, `TranslationTrace` (thread-local holder), `TranslationTraceClock`, `TranslationTraceIdGenerator`, `TranslationIdentityKeys`, bounded enums (mode, lane, stage, provider, model, outcome, plan, schedule state) |
| `app/src/test/java/eu/kanade/translation/diagnostics/TranslationPipelineDiagnosticsTest.kt` | 7 tests: schema/key order, hostile strings, budget table, outcome tokens, gating, state coalescing, cross-launch privacy |
| `app/src/test/java/eu/kanade/translation/diagnostics/TranslationTraceTest.kt` | 13 tests: dispatcher-hop identity, clock clamping, bottlenecks, overlap math, lane misuse, concurrency smoke, repeated-stage sums, idempotent terminals, slowest-page/max-queue, ID generator |

Style matches neighboring diagnostics code (JUnit 5 + kotest; `captureRecords`-style sink swap; `BatchDownloadDiagnostics`' `recordObserver` seam inspired the injectable `sink`).

## 2. Public API surface (key types and methods)

```
TranslationPipelineDiagnostics (object)
  const TAG = "TachiyomiAT.Translation"; const SCHEMA = "translation_trace_v1"; const NONE = "none"
  @Volatile var sink / detailedTracingEnabled / idGenerator / identityKeys      // injectable seams
  fun startSchedule(mode, origin = mode, chapterRaw, pages, clock): TranslationScheduleTrace
  fun startRun(schedule, pageRaw, pageIndex, plan = FRESH, clock): TranslationRunTrace
  fun routeChange(run?, schedule?, stage, model, from, to, reason, error?, retry)
  fun classifyError(Throwable?): TranslationTraceError                          // sanitizer entry point

TranslationScheduleTrace   enterLane(lane): TranslationLaneToken; reportState(state, reason, depth, nat, prov); end(outcome): Boolean (idempotent)
TranslationRunTrace        beginStage(stage, lane = default, provider, model, items): TranslationStageSpan; recordRetry(): Int; end(outcome, error?, errorType?, errorCode?): Boolean (idempotent)
TranslationStageSpan       end(outcome = SUCCESS, error?, items, errorType?, errorCode?, queueMs): Boolean (idempotent); close()  // AutoCloseable
TranslationLaneToken       close()  // exactly-once, fail-open; AutoCloseable
TranslationLaneOverlapAccumulator  enter(lane, nowNanos); exit(lane, nowNanos); snapshot(nowNanos): Snapshot(nativeBusyMs, providerBusyMs, renderBusyMs, unionActiveMs, overlapMs, concurrencySavingsMs, workMs)
TranslationTrace           currentRun(): TranslationRunTrace?; beginStage(...): TranslationStageSpan (NO_OP outside a trace); elementFor(run): TranslationTraceElement
TranslationTraceElement    ThreadContextElement<TranslationRunTrace?> — immutable identity across dispatcher hops
TranslationTraceBudgets    budgetMsFor(stage, provider); isQueueStage(stage); 11 tested constants
TranslationTraceClock      fun interface; SYSTEM = System.nanoTime
TranslationTraceIdGenerator(processPrefix)  nextScheduleId/nextRunId -> "<8hex>-s<r><n>"
TranslationIdentityKeys(keyBytes)           token(namespaceChar, raw) -> keyed HMAC token or "none"
```

Emitters/formatters (`scheduleStartRecord`, `runEndRecord`, `stageEndRecord`, `routeChangeRecord`, `scheduleStateRecord`, `safeToken`, …) are `internal` pure functions so tests assert exact strings without Android.

## 3. Design decisions

1. **ID scheme (§10.3).** sid/rid: process-random 8-hex prefix + per-process monotonic counters (`0000aa11-s1`, `0000aa11-r1`) — opaque, content-free, cross-launch-unstable. Chapter/page tokens: **HMAC-SHA256 with a 32-byte SecureRandom per-process key**, truncated to 8 bytes (16 hex), namespace-prefixed (`c`/`p` + 0x00 domain separator). This is the documented replacement for the forbidden unsalted FNV-1a `ShortHash` (which a test explicitly proves is not the derivation). Same input -> same token within a process (correlatable), uncorrelated across launches (test 10). No ID map exists at all, so there is nothing to bound or clear. Tokens are hex-only and can never inject `=`/space into the schema.
2. **Gate (§10.5).** `@Volatile var detailedTracingEnabled: Boolean = BuildConfig.DEBUG` (a simple settable boolean per contract; Phase 3/4 own preference wiring). Detailed = `schedule_start`, `run_start`, `stage_start` bodies, non-lag success `stage_end` bodies, `schedule_state`, `route_change`. Always emitted = `run_end`, `schedule_end`, plus lag/failure `stage_end` variants. Repeated identical `schedule_state` (state+reason) per schedule is coalesced via a single last-key field. Priorities: INFO terminals/starts; WARN for lag/failure stage ends, failure-family terminals, route changes.
3. **Sanitizer (§10.3).** `classifyError` whitelists: `OrtException -> ort`, `CancellationException -> cancel`, `OutOfMemoryError -> oom`, `SocketTimeoutException -> http`, `IOException -> io`, `IllegalState/IllegalArgument -> contract`, everything else `unknown`. **The only message inspection in the module** extracts the bare digit group from `error code (\d{3,5})` on OrtException messages exclusively (QNN 1100/6020 are only recoverable from there; `getCode()` returns an enum with no numeric accessor — verified via `javap` on the resolved ORT 1.27.0 API). Explicit `errorType`/`errorCode` overrides exist for Phase 3/4 callers that already hold typed codes; override tokens pass the `safeToken` charset sanitizer. No manga/chapter/page names, text, prompts, URLs, keys, or messages are representable in the API surface.
4. **Timing engine (§10.6).** All durations from the injectable monotonic clock; negative deltas clamp to 0 at settle points. Per-run state is one fixed `EnumMap<Stage, Long>`; repeated intervals for a stage SUM (retry test). Queue waits (`lease_wait`, `native_queue`, `prepared_queue`, `provider_governor_wait`) are ordinary stages: summed into `run_end.queuedMs`, feed `schedule_end.maxQueueMs` online, and are bottleneck-eligible. `lag = durationMs > budgetMs || queueMs > 1000`. `run_end.bottleneck` = max stage (ties -> lowest ordinal, deterministic); `schedule_end.bottleneck` = lane with greatest busy time; `slowestPage` = opaque page token of the longest run (one string + one long of state).
5. **Overlap accumulator (§10.6).** O(1): three lane-active counts + six totals; on every transition (and at snapshot) the elapsed delta settles into native/provider/render busy, union-active (>=1), overlap-union (>=2), and `concurrencySavingsMs = sum(max(count-1,0) * delta)`. Internally synchronized (lanes run on different threads); scheduler/storage lanes are ignored; exit-without-enter clamps at zero.
6. **Terminality (§10.2).** `end()` on schedule/run and `end()`/`close()` on spans/lane tokens are CAS-idempotent, never suspend, never throw (safe in `finally` and cancellation paths). 16 bounded outcome tokens including all required: success, failure, pause, timeout, cancelled(_before_dispatch/_during_send), evicted, stale_handoff, coordinator_replaced, attached, skip, resume, teardown_exception, persistence_rejected (+ started). Stage spans ending after their run closed are recorded but suppressed (no post-terminal noise).
7. **Fail-open.** Every emission is wrapped in `try/catch(Throwable)`; a broken formatter or sink can never propagate into pipeline code. `TranslationTrace.beginStage` outside a traced coroutine returns a shared NO_OP span.
8. **Bounded memory.** No lists anywhere: schedule = accumulator + 4 scalars; run = one EnumMap + one AtomicInteger. The object owns only gate/sink/id-generator/keys; completed traces are caller-owned.

## 4. Test coverage (contract case -> test)

| Contract case | Test |
|---|---|
| 1 fixed key order + schema per family | `every event family emits fixed key order with schema token` (8 exact-line assertions, all families) |
| 2 hostile strings never raw | `malicious page chapter and error strings never appear raw in emitted lines` (XSS/SQL/path/secrets + per-token charset check) |
| 3 identity across dispatcher hops | `identity persists across dispatcher hops via trace element` (Dispatchers.Default + element; deep `TranslationTrace.beginStage` with no run param) |
| 4 negative clock clamps | `negative clock deltas clamp to zero` (span, run total, accumulator settle) |
| 5 budgets deterministic | `budget table yields deterministic lag and budget values` (16 table values + 4 boundary spans incl. queue-budget edge `==` non-lag) |
| 6 bottleneck selection | `run summary selects the largest stage as bottleneck` + `queue waits are eligible and can dominate the bottleneck` |
| 7 overlap math + stage sums | `triple overlap...` (savings 2s, overlap-union 1s), `overlapping and nested intervals settle correctly`, `repeated stage intervals sum instead of overwriting`, `lane misuse is fail open...`, `concurrent lane transitions stay internally consistent` |
| 8 terminal idempotency + outcomes | `terminal closes are idempotent...` (double close = 1 event), `lane tokens exit exactly once and tolerate misuse`, `terminal outcomes enumerate the bounded token set` (15 tokens), `schedule tracks slowest page and max queue wait online` |
| 9 gating + coalescing | `gate off suppresses detailed events but keeps terminals lag and failure stage ends` + `repeated identical schedule states are coalesced within a schedule only` |
| 10 cross-launch privacy | `identical page names in different process launches produce uncorrelatable tokens` (equal within launch, unequal across, `ShortHash` proven not used) |

## 5. Verification (full output summary)

```
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew.bat :app:compileStandardDebugKotlin   -> BUILD SUCCESSFUL (twice: after prod edits, and reconfirmed)
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.diagnostics.*"
  -> BUILD SUCCESSFUL in 1m 4s
```

From `app/build/test-results/testStandardDebugUnitTest/`:

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| `TranslationTraceTest` (new) | 13 | 0 | 0 |
| `TranslationPipelineDiagnosticsTest` (new) | 7 | 0 | 0 |
| `BatchDownloadDiagnosticsTest` (pre-existing, package matched by the filter) | 6 | 0 | 0 |

Total 26/26 green. During iteration 3 failures were fixed (all test-side: three non-contiguous `shouldContain` snippets, one bogus whitespace assertion, one `NO_OP.isFinished` checked before `end()`, one clock-mixing bug in the slowest-page test); production code needed one compile round (object cannot host a `companion`; missing `kotlin.math.max`; non-const `entries.size`). Full suite and APK assembly intentionally NOT run; no device touched; nothing committed.

**Baseline preservation:** `git status --short` re-run immediately before creation (26 entries, all Director-owned/Phase-1) and after implementation (30 entries = 26 + exactly the 4 new files). Zero modifications to any existing file.

## 6. Deviations (intentional, recorded)

1. **`schedule_start`/`run_start` emit the full fixed field list** with zeros/`started` placeholders (contract lists one field set per family; fixed-order parseability for single-family consumers won over emitting sparse start lines). Same for `stage_start` (`budgetMs` is deterministic from the table).
2. **`errorType` includes `timeout` and `none`** beyond the contract's example list ("e.g." list); total surface remains 8 bounded tokens. `timeout` currently unreachable from the classifier (reserved for explicit overrides).
3. **Inpaint budget keyed on provider only** (`qnn_htp` -> 1000, all else -> 6000): the contract phrases the HTP budget as "AOT-GAN-on-QNN-HTP", but provider is the measured execution fact; the AOT-GAN association arrives via the `model` field. Documented in `TranslationTraceBudgets`.
4. **`engine_setup`/`render_join` budgets** are not in the plan table; assigned the conservative 1000 ms constant (documented in code).
5. **OrtException numeric extraction** (above): unavoidable to surface the mandated `errorCode=1100`, since ORT 1.27's `getCode()` is enum-only; digit-only regex on whitelisted class only.
6. **`routeChange` emits only under the detailed gate** (contract lists it among detailed events); Phase 3/5 wiring may add a failure variant if the Director wants route demotions visible in release.

## 7. Risks / notes for later phases

1. **No production call sites yet (by design).** The gate defaults to `BuildConfig.DEBUG`, but nothing emits until Phase 3/4 wiring; the acceptance overhead measurement (≤3%) is only possible after that.
2. **ThreadContextElement covers only coroutines launched with the element**; deep code on unmanaged threads sees `currentRun() == null` and gets the NO_OP span — wiring must wrap each lane's work per plan §4.4.
3. **HMAC-per-tokenization** costs microseconds at schedule/run creation only (not per event); irrelevant to the 3% overhead budget.
4. **`TranslationIdentityKeys`/`TranslationTraceIdGenerator` are `@Volatile` object fields** for test determinism; Phase 3/4 should treat them as process-fixed singletons (a mid-process rekey would only split correlation, never leak).
5. **Slowest-page/max-queue state survives schedule close** (end() tolerates late `noteRunFinished`); harmless since the object is dropped by its owner.
