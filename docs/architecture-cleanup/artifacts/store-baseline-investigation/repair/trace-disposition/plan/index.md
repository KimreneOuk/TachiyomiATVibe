---
title: "TraceTest completion-boundary RED/GREEN plan"
kind: spec
comments: none
---

# Source chain (verified read-only, no edits/runs)

All facts below were re-verified at the current tree (HEAD 999bb9be + frozen one-file ProbeTest helper patch; TraceTest file byte-identical to HEAD).

## Production emission chain

| Step | Location | Fact |
| --- | --- | --- |
| Scope | TranslationScheduler.kt:85 | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — job body and completion handler run on IO worker threads, never the test's `runBlocking` event loop. |
| close | TranslationScheduler.kt:62-67 | `shutdownAutoCoordinator()` + `scope.cancel()` only; does NOT join or await the `invokeOnCompletion` handler (registered outside the coroutine, not scope work). |
| finally | TranslationScheduler.kt:395-398 | `traceOutcome` set and `manualOutcomes[jobKey]` written INSIDE the coroutine `finally` — BEFORE the completion handler runs. |
| Handler | TranslationScheduler.kt:435-445 | `outcomeToken` → `leaseWaitSpan.end()` (442) → `run.end` (443) → `schedule.end` (444). Two separate synchronous statements on one thread; `run_end` is observable before `schedule.end` executes. Only emission sites in the manual path (grep-verified: 443/444 only). |
| Run end | TranslationTrace.kt:1021-1049 | CAS-guarded idempotent, no suspension points; `emitRunEnd` at 1035. |
| Schedule end | TranslationTrace.kt:727-745 | CAS-guarded idempotent, no suspension points; `emitScheduleEnd` at 734. |
| Sink dispatch | TranslationPipelineDiagnostics.kt:1162-1170 | `emit` calls `sink.log(priority, line)` synchronously on the caller's thread (fail-open try/catch); `sink` is a settable `var` (174) — the existing test seam. No queue, no extra thread. |

## Test gate vs emission boundary

| Test method | Current gate | Gate releases at | Assertions after close |
| --- | --- | --- | --- |
| Case 1 success (101-139) | `manualOutcomeFor != null` (112) | Scheduler.kt:398 — BEFORE both terminals | run_end 131-135, schedule_end 137-138 — WIDEST window (both terminals may be unemitted) |
| Case 2 cancelled (145-166, the DevDebug failure) | `lines("run_end").isNotEmpty()` (155) | Scheduler.kt:443 — between the two terminals | schedule_end size at 164 — `expected:<1> but was:<0>` matches this window exactly |
| Case 3 cancel-before-dispatch (173-190) | `lines("run_end").isNotEmpty()` (182) | Scheduler.kt:443 | schedule_end at 188-189 — same hazard as case 2 |

Helper: `awaitCondition` (test 91-95) polls every 10 ms under `withTimeout(10_000)` on the test thread; `@Timeout(60)` bounds each method.

# Minimal GREEN repair (test-only, all 3 methods, per reviewer)

Wait for the LAST expected terminal (`schedule_end`) before any terminal assertions; every existing assertion and the real-thread polling semantics stay unchanged; no sleeps, no production edits, no API.

- Case 1: keep gate at 112; ADD `awaitCondition { lines("schedule_end").isNotEmpty() }` inside the `try` before the `finally` close.
- Case 2: replace gate at 155 with `awaitCondition { lines("schedule_end").isNotEmpty() }` (strictly follows `run_end` on the same handler thread, so it subsumes it).
- Case 3: replace gate at 182 identically.

Failure semantics: if the handler never ran, the method now fails by gate timeout instead of a misleading count assertion — no weakening. `awaitCondition`'s 10 s bound and `@Timeout(60)` unchanged.

# Deterministic gated RED/GREEN reproducer (temporary, scratch-preserved)

One temporary test method in the same file (existing seams only; removed after the diagnostic run, exact bytes preserved in scratch for the reviewer). Not a permanent mirror test.

- Gate: `java.util.concurrent.CountDownLatch(1)`. Sink wrapper installed over the existing capture seam: `TranslationTraceSink { p, line -> capturedLines.add(line); if (line.contains("event=run_end ")) latch.await() }` — the handler thread parks INSIDE `run.end`'s emit (Scheduler.kt:443) after `run_end` is already captured; `schedule.end` (444) cannot execute. Deterministic because 443/444 are the only emission sites and both `end()` bodies are synchronous.
- Deadlock bounds: the parked thread is a `Dispatchers.IO` worker (Scheduler.kt:85), never the test thread; `finally { latch.countDown(); scheduler.close() }` releases before close; `@Timeout(60)` bounds the method; `tearDown` restores the sink.
- Body (mirrors case 2: `ManualFakeExecutor(throwCancellation = true)`):
  1. `translatePage` → `awaitTranslateStarted` → OLD gate `awaitCondition { lines("run_end").isNotEmpty() }` returns while the latch is held.
  2. RED proof: run the original line-164 assertion form under the held gate inside `try/catch (AssertionError)`; assert it was caught → shows the original early assertion fails deterministically in the forced window (the real flake evidence remains the preserved DevDebug XML 165EFEDB…).
  3. `latch.countDown()` → handler proceeds through 444 → corrected gate `awaitCondition { lines("schedule_end").isNotEmpty() }` (proven to remain pending until release) completes.
  4. `finally` release + `close()`; exactly-once assertions: run_start 1, run_end 1 outcome=cancelled, schedule_end 1 outcome=cancelled — terminals stay idempotent across the gated release.

# Authorized-run sequence (pending parent authorization; none run yet)

1. Apply reproducer + 3-method repair in one tree; preserve full modified-file bytes in scratch.
2. Focused original class + reproducer, DevDebug then StandardDebug (`--rerun`, unique pre-created scratch dirs, literal commands/IDs/exits/XML preserved immediately). Expected: 4/0 per flavor (3 repaired + reproducer).
3. Remove reproducer, restore file, verify byte-exact vs the repaired-only state; read-only spotless inventory once.
4. Full unfiltered rebaseline both flavors (per reviewer requirement), same preservation discipline, comparison vs Phase 2 immutable XML.
5. Accepted ProbeTest patch stays frozen throughout; eleven stat-only M paths and report hash untouched; no commits/pushes; repair/trace-disposition disposition recorded after results.

# Parent acceptance with required reproducer corrections

The three permanent final-event waits are authorized, preserving every existing assertion. The temporary reproducer must first address two concrete defects in the proposed proof:

1. Dispatchers.IO on the job does not guarantee invokeOnCompletion always executes there: registration after an already-completed job can invoke the handler inline on the registering thread. Use a temporary fake-executor cancellation-release gate so the job cannot finish until translatePage has returned with the completion handler registered. Only then release fake cancellation and allow the sink to block after recording run_end. Remove the temporary executor gate in the final diff.
2. Releasing the latch and then awaiting schedule_end does not demonstrate pending-before-release. Start the corrected wait as a controlled child (e.g. undispatched start), observe it suspended while the sink gate remains held, assert it is incomplete and no schedule terminal exists, then release and join it. Keep finally-release and bounded latch waits; no sleeps or production hooks.

If the old assertion failure is caught inside an otherwise passing reproducer, describe it as a forced counterexample, not a failing Gradle RED run. Preserve source, patch, gate observations and XML accurately.

Execute reproducer-focused checks once per flavor; remove all temporary instrumentation and gates byte-exactly, leaving only the three permanent wait changes (plus the separate accepted store helper patch). Run the final retained three-method TraceTest in both flavors to validate the exact final tree. One read-only complete formatting inventory is appropriate. Return actual final diff and proof/evidence for Gemini review before another full-suite run. The previous failing Dev and green Standard full-suite ledgers remain immutable.
