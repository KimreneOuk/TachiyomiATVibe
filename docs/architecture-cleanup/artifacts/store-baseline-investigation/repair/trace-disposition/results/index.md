---
title: "TraceTest completion-boundary repair results"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Result

Implemented the accepted three-permanent-wait repair in `TranslationSchedulerTraceTest` plus the temporary gated reproducer per the corrected plan, ran reproducer-focused checks in both flavors, removed all temporary instrumentation byte-exactly, and validated the exact final tree with fresh focused runs in both flavors. No production edits, no commits/pushes, no full-suite rerun (held for Gemini review per instruction). The accepted ProbeTest helper patch stays frozen.

## Final actual diff (the only TraceTest change retained)

`app/src/test/java/eu/kanade/translation/scheduling/TranslationSchedulerTraceTest.kt`: +3/−2 vs HEAD, three gate lines only, every assertion unchanged:

| Method | Change |
| --- | --- |
| `manual translate emits one schedule and one run with lease wait and success terminal` (case 1) | ADDED `awaitCondition { lines("schedule_end").isNotEmpty() }` after the retained `manualOutcomeFor` wait (inside try, before finally close). |
| `cancelled manual intent closes exactly once as cancelled` (case 2, the DevDebug flake) | Gate at old line 155 replaced: `run_end` wait → `schedule_end` wait. |
| `cancel before dispatch still closes the run exactly once` (case 3) | Gate at old line 182 replaced identically. |

Repository content diff is exactly two files: this +3/−2 and the frozen ProbeTest 3/3 helper patch. Report SHA-256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD unchanged. Working tree remeasured after the change: 13 porcelain entries = 10 stat-only M restorations + 2 content diffs (ProbeTest, TraceTest) + the untracked report — TraceTest was one of the original eleven stat-only paths and now carries this real content diff, leaving ten.

## Reproducer as executed (temporary; removed after; bytes preserved)

Built with both parent corrections:

1. **Cancellation-release gate (prevents inline handler on the registering test thread):** `ManualFakeExecutor` gained a temporary opt-in `holdCancellation` flag plus a `cancellationHold` deferred and `releaseCancellation()`. The reproducer constructed `ManualFakeExecutor(throwCancellation = true, holdCancellation = true)`, called `translatePage` (returned with the completion handler registered on an INCOMPLETE job — inline invocation impossible), and only then called `releaseCancellation()`.
2. **Pending-before-release proof:** corrected wait started as `launch(start = CoroutineStart.UNDISPATCHED) { awaitCondition { lines("schedule_end").isNotEmpty() } }`; asserted `correctedWait.isActive == true` and `lines("schedule_end")` empty while the latch was held, then `latch.countDown()` → `join()` → `isActive == false`.
3. **Sink gate:** wrapper over the existing capture seam — `TranslationTraceSink { p, line -> captureSink.log(p, line); if (line.contains("event=run_end ")) latch.await() }` — parked the IO handler thread inside `run.end`'s emit (Scheduler.kt:443) with `run_end` captured; `schedule.end` (444) could not execute. Bounded cleanup: `finally { latch.countDown(); scheduler.close() }`; `@Timeout(60)`; tearDown restored the sink. No sleeps, no production hooks, no new harness.
4. **Honest labeling:** the old early assertion (`lines("schedule_end").size shouldBe 1`) executed under the held gate inside try/catch(AssertionError); the caught failure is a FORCED COUNTEREXAMPLE demonstrating the original early assert fails in the withheld window — it is NOT a failing Gradle RED run. The real observed flake remains the preserved DevDebug full-suite XML (165EFEDBEC94E1A63923794E44B5EA1B4485C802EC0B619D28F0D4B354CA2476).
5. Post-release exactly-once assertions passed: run_start 1, run_end 1 (cancelled), schedule_end 1 (cancelled) — terminals idempotent across the gated release.

## Gate coverage disclosure

The gated v2 runs validated cases 1 and 2 with repaired gates plus the reproducer; case 3 still carried its OLD `run_end` gate in those runs (an indentation-specific first replaceAll matched only the try-block gates; found via the final diff review and fixed before the final tree). Case 3's repaired `schedule_end` gate is validated by the final-run pair below. This is disclosed to keep the evidence mapping exact.

## Runs (serial, literal commands, unique dirs)

Scratch root: `C:\Users\ADMIN\.traycer\epics\699de408-00ac-4fe5-a903-b541c23063cc\scratch\store-baseline-repair\trace-disposition`. Wrapper exit is always 0 (tail is the last command); `GRADLE_EXIT:` markers in the registered logs are Gradle's true exit.

| Stage / flavor | Command (class filter differs only by task) | Shell ID | GRADLE_EXIT / outcome | Class XML result |
| --- | --- | --- | --- | --- |
| Gated v1 dev (BROKEN temporary gate — retained evidence, not a verification run) | ./gradlew.bat :app:testDevDebugUnitTest --tests 'eu.kanade.translation.scheduling.TranslationSchedulerTraceTest' --rerun --console=plain --stacktrace | 6b05b3da-229f-4868-944e-72a357d14399 | 1 / BUILD FAILED in 39s | 4 tests, 1 failure: case 2 timed out at 10.035s because the first temporary hold applied to ALL cancellation tests and the reproducer-only `releaseCancellation()` was never called there; reproducer, case 1, case 3 passed. Fixed by making the hold opt-in. XML B7C905BD…E4B9, log C9070D68…7D23 |
| Gated v2 dev | same, task `:app:testDevDebugUnitTest` | 9fdb9ad6-244b-4abd-802e-93c708ca1cf9 | 0 / BUILD SUCCESSFUL in 28s | 4 tests, 0 failures (3 existing methods + reproducer 0.069s; repaired gates covered cases 1–2 only — case 3 still ran its OLD gate in this stage, see disclosure). XML 324B39B3…4EAE, log 60147E60…485AD |
| Gated v2 standard | same, task `:app:testStandardDebugUnitTest` | 9e9b85e0-aa8a-4a68-9516-eb6c9f7fd97d | 0 / BUILD SUCCESSFUL in 50s | 4 tests, 0 failures (3 existing methods + reproducer 0.081s; same case-3 gate coverage as dev). XML ECB283D1…9A46A3, log 22FC4D6D…E1EF2 |
| Final tree dev | same, task `:app:testDevDebugUnitTest` | db555b69-8dbd-4d19-96de-bd21588d62b0 | 0 / BUILD SUCCESSFUL in 29s | 3 tests, 0 failures — all three repaired `schedule_end` gates exercised (0.010s on case 2). XML 1FBF3066…62871, log FEB05526…342B62 |
| Final tree standard | same, task `:app:testStandardDebugUnitTest` | 12b93bbf-e6e1-413f-9dba-8aba7cd6c4d1 | 0 / BUILD SUCCESSFUL in 29s | 3 tests, 0 failures — all three repaired `schedule_end` gates exercised (0.008s on case 2). XML 0DCBAA0B…62B3, log 25DD19FB…9161F8 |
| Spotless (full formatter inventory) | ./gradlew.bat spotlessCheck --console=plain --stacktrace | 6e9d3007-3ae8-4f11-a97e-ea135dcf275c | 1 / BUILD FAILED in 13s (standing debt) | Exactly ONE flagged file across the whole inventory: `src\main\java\eu\kanade\translation\engines\inpainting\litert\LiteRTMangaInpaintingEngine.kt` — the unchanged baseline LiteRT formatting debt. Complete log `spotless.log` 25,303 bytes (SHA-256 14085D818DF3946666654E5883755D371E9F3B5BCBE029015B021C85C747B699), true start headers, own BUILD line, no rotation/truncation; single distinct flagged filename, no truncation marker. |

## Patch provenance (scratch `patch\`)

| File | Bytes | SHA-256 |
| --- | --- | --- |
| TranslationSchedulerTraceTest.pre-edit.kt (byte-identical to HEAD) | 10,156 | 0AE8798339F91699250CD9E50B747B9F54F60EF4D6AD5786E6C7474E1DA8451E |
| TranslationSchedulerTraceTest.gated.kt (v1, broken-hold variant) | 12,876 | 4C60C44C5FE92A551195F23F3075CA29E25794F77139A4F761C0CDE54BD313E5 |
| TranslationSchedulerTraceTest.gated-v2.kt (opt-in hold; the run tree for gated v2) | 12,974 | 4E1825B66B60F992DC8D25CE0590D2D6B9BD5033E4205E20935D7CFEAED9B2E2 |
| TranslationSchedulerTraceTest.final.kt (retained tree; +3/−2 vs HEAD) | 10,232 | C986F99E251CFA2D2BC8E991867E2449BE43D9695282B27D6F579C17975E45A2 |

Temporary removal verified: no CountDownLatch/CoroutineStart/holdCancellation/cancellationHold/releaseCancellation/reproducer markers remain in the retained file.

## Limits

- Full app suites NOT rerun (held for Gemini actual-code review per instruction); domain/build/device/native/model actions: none.
- The DevDebug full-suite failure and StandardDebug full-suite green ledgers from the rebaseline remain immutable evidence.
- Case 3 gate-coverage mapping disclosed above; no unchanged retries were run (the v1 gated failure was a temporary-code defect, fixed, and its evidence retained).
- The reproducer's gated-window proof is deterministic construction + forced counterexample, not a stochastic RED.
