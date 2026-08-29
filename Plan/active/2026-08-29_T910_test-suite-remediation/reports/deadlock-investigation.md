# T910 Item 4 — Deadlock P1: the 8 never-executed `RollingAutoCoordinatorTest` tests

Date: 2026-08-29 · Branch: `optimize_translation_finishing_page` · Owner: deadlock investigator
Task contract: `Plan/active/2026-08-29_T910_test-suite-remediation/README.md` (item 4)
Sources: T908 `reports/cleanup-implementation.md` §3/§5, T906 area3 §U3, archived patch
`Plan/active/2026-08-28_T908_translation-folder-housekeeping/discarded-worktree-snapshots/"t906-fix-area1 RollingAutoCoordinatorTest.patch"`.

## 0. Executive answer

The line-169 hang is a **TEST-INFRA bug, not a production deadlock**. The fixture's
gate-completion protocol encodes an admission-order assumption ("translate(0) starts
before prepare(1)") that the coordinator does not make and must not make: the translate
consumer is asynchronous to the reconcile loop, and under `Dispatchers.Unconfined` inside
`runBlocking` the consumer's channel-handoff resumption is queued to the event loop, so
page 1's native prepare legally acquires the shared compute gate first. The production
gate does exactly its job — it guarantees the two lanes never OVERLAP; it does not
guarantee translate-lane priority. Fix: reorder the fixture's gate releases (test-only).
Three sibling tests had lesser expectation bugs (racy / unsatisfiable-in-one-interleaving /
racy-read); all fixed test-side. **No production change. 8/8 now pass, 0 disabled.**

## 1. What was applied

1. The archived T906 patch (`runBlocking` → `runBlocking<Unit>` on the 8 silently-skipped
   tests) — `git apply`, clean.
2. `@Timeout(60)` (`org.junit.jupiter.api.Timeout`) added to each of the 8 affected tests.
   Empirical note: JUnit 5.11.4 `SameThreadTimeoutInvocation` interrupts the parked
   `runBlocking` thread and fails the test — the T908 "killed after ~25 min" hang is now
   bounded at 60 s (first guarded run: `TimeoutException ... timed out after 60 seconds`,
   gradle exit in 1m 20s).
3. javap over `app/build/tmp/kotlin-classes/standardDebugUnitTest`: all 8 methods now
   `public final void`; Jupiter discovers them (class executes 31 tests, not 23).

## 2. Per-test outcomes (one gradle invocation per test, `timeout -k 15 150` hard kill)

| # | Test (method name) | First isolated run | Verdict | Fix | Re-run |
|---|---|---|---|---|---|
| 1 | `remote overlap - native B starts while translate A is in flight` | PASS | — | — | — |
| 2 | `local compute serializes prepare and translate through a shared gate` (line 169) | **TIMEOUT-HANG** → failed by `@Timeout(60)` | TEST-INFRA deadlock | gate-order fix (§3) | PASS |
| 3 | `shutdown clears snapshot` | PASS | — | — | — |
| 4 | `chapter mismatched cancellation leaves the active scheduler owner running` | FAIL — `Expected null but actual was 1` (`prepareCallsByPage["p1"] shouldBe null`) | racy test expectation | contract-preserving rewrite (§4.1) | PASS |
| 5 | `same-chapter replacement is suppressed while cancellation is signalling` | PASS | — | — | — |
| 6 | `global cancellation epoch rejects a concurrent update and permits post-cancel rearm` | FAIL — `TimeoutCancellationException` at `autoSnapshot.first { it == null \|\| it.identity != oldIdentity }` (5 s) | unsatisfiable expectation in one legal interleaving | accept both interleavings (§4.2) | PASS |
| 7 | `stable scheduler snapshot switches pointer and rejects same-identity replay` | PASS isolated; FAIL in full-class run — `expected:<2> but was:<0>` at `stable.value!!.visiblePageIndex` | racy synchronous read of an asynchronously-switched flow | await the projection (§4.3) | PASS (full class, twice) |
| 8 | `reconcile admission guard suppresses batch or revision recovery poke` | PASS | — | — | — |

All 31 class methods now run: final XML — `tests: 31 failures: 0 errors: 0 skipped: 0`,
test-execution time 3.2 s.

## 3. Line-169 diagnosis (the P1)

### Evidence chain

(a) Thread dump mid-hang (jstack of the Gradle test worker, `@Timeout(3600)` diagnostic run):
`Test worker` in `TIMED_WAITING (parking)` at `BlockingCoroutine.joinBlocking` — the
runBlocking event loop is empty; there are **no** `DefaultDispatcher` threads and no other
app threads: every coordinator coroutine is suspended and the test body is parked awaiting
a gate nobody will open.

(b) Instrumented trace (temporary in-test markers, since removed) — state after the test's
`executor.awaitAndCompletePrepare(0)`:

```
after awaitAndCompletePrepare(0) | prepCount=2 trCount=0 byPage={p0=1, p1=1} trByPage={} maxConc=1 snapFG=Translating
```

`prepCount=2`: the reconcile loop already admitted prepare(1). `trCount=0`: the consumer
set the slot to `Translating` (hence `snapFG=Translating`) but never entered
`translatePreparedPage`. The hang point is the test's NEXT step, `awaitTranslateStarted(0)`.

### Mechanism (deterministic under Unconfined-in-runBlocking)

1. `updateWindow` starts the loop inline; prepare(0) runs inside
   `computeGate.withPermit { prepareSinglePage }`
   (`RollingAutoCoordinator.kt:585-600`; gate created at `:309` for LOCAL_COMPUTE,
   `Semaphore(1)`) and suspends on the fixture's `prepareGate(0)` — holding the permit.
2. Test completes `prepareGate(0)`. Inline cascade: prepare(0) finishes and releases the
   permit; the loop hands work(0) to the prepared channel
   (`RollingAutoCoordinator.kt:632-641`). The translate consumer
   (`consumeTranslations`, launched at `:315`) is resumed **asynchronously**: with
   `Dispatchers.Unconfined` on a thread running the runBlocking event loop, the
   channel-handoff resumption is queued to the loop's unconfined queue and drains only
   when the current unconfined dispatch ends — not inline inside the cascade.
3. The loop therefore proceeds in the same dispatch and admits page 1:
   `computeGate.withPermit { prepareSinglePage(1) }` acquires the (free) permit, emits
   READING/CLEANING, completes `prepareStarted(1)`, and suspends on the fixture's
   `prepareGate(1)` — **still holding the permit**.
4. The queued consumer task then runs: sets slot(0)=Translating, suspends acquiring the
   permit for `translatePreparedPage(0)` (`RollingAutoCoordinator.kt:353-366`) — behind
   prepare(1).
5. Cycle: test waits for `translateStarted(0)` ← translate(0) waits for the permit ←
   prepare(1) holds the permit and waits for `prepareGate(1)` ← only the test can open it,
   but the test is parked at step 5's beginning. Event loop empty → park forever.

### Blame: TEST-INFRA

In production prepare(1) is real work that completes on its own and releases the permit;
translate(0) then runs (FIFO semaphore). The observed schedule — prepare(0) → prepare(1) →
translate(0) → translate(1), never overlapping — satisfies the test's own contract
("at no point do both run simultaneously"). The fixture assumed translate-lane priority
that no code promises. `maxConcurrent == 1` (the real serialization invariant) was never
violated even in the hanging run (`maxConc=1` in the trace).

### Fix (test-only, RollingAutoCoordinatorTest.kt)

Release prepare(1) before waiting for translate(0):

```kotlin
executor.awaitAndCompletePrepare(0)
executor.awaitAndCompletePrepare(1)   // prepareStarted(1) already fired inline
executor.awaitTranslateStarted(0)     // gate now free → translate(0) runs
executor.maxConcurrent.get() shouldBe 1
executor.completeTranslate(0)
executor.awaitTranslateStarted(1)
executor.completeTranslate(1)
```

All original assertions retained (`maxConcurrent == 1` mid-flow and at the end; full
pipeline completion for both pages). A KDoc block documents the ordering contract and the
deadlock hazard of the old protocol.

## 4. Other test fixes (all expectation-side; production untouched)

### 4.1 `chapter mismatched cancellation...`

The failing assertion `prepareCallsByPage["p1"] shouldBe null` raced: between
`awaitTranslateStarted(0)` and `cancelAutoTranslations(31L)` the scheduler-owned
coordinator (its own `Dispatchers.Default` scope) legally admits p1's prepare — that is
pre-cancel admission, not a defect. The deterministic contracts are now asserted instead:
`cancelAutoTranslations(99L) shouldBe false` (mismatched cancel is a no-op — the title
contract), `translate(0)` still starts after the mismatched cancels,
`cancelAutoTranslations(31L) shouldBe true` (matched cancel stops the active owner), and
`translateCallsByPage["p1"] shouldBe null` (a cancelled owner can never drive p1 through
translate: `autoComplete=false` parks prepare(p1) on a gate only the test opens, so
cancellation unwinds it there).

### 4.2 `global cancellation epoch...`

In the interleaving where the global cancel wins the race, the concurrent update is
rejected by the epoch — and the cancelled old owner's snapshot is RETAINED (`cancel()`,
unlike `shutdown()`, never nulls it; contract asserted by `cancel stops admission...`).
`first { it == null || it.identity != oldIdentity }` can then never fire. Fixed by
accepting both legal outcomes (snapshot null / oldIdentity / newIdentity after the race)
and keeping the strong end-state guarantee: a post-cancel re-arm converges to
`newIdentity` within 5 s.

### 4.3 `stable scheduler snapshot...`

Read `stable.value!!` synchronously after `updateAutoWindow` returned — but the
scheduler-level `autoSnapshot` re-points through `flatMapLatest` on the scheduler's
`Dispatchers.IO` scope, so projection delivery is asynchronous to the call returning.
Passed isolated, failed under full-class load. Fixed by awaiting the projection
(`first { identity == old && visiblePageIndex == 2 && windowVersion == firstVersion + 1 }`)
and asserting `ownerVersion` unchanged on the awaited value. All four original assertions
preserved.

## 5. Allowlist changes (`app/config/runblocking-allowlist.txt`)

The allowlist is now **EMPTY** (comments only). Per-entry dispositions:

| Removed entry | Cure | Proof |
|---|---|---|
| `scheduling/RollingAutoCoordinatorTest.kt` | 8 revived tests run green; remaining 23 bare `= runBlocking {` declarations converted to `runBlocking<Unit>` (pre-conversion javap: all 31 already void) | full class 31/31 green ×3; `checkTestRunBlocking` green |
| `translation/TranslationManagerReaderTeardownTest.kt` | 2 bare methods converted (lines 35, 111) | javap: 4/4 test methods void; class re-run green (4 tests) |
| `translation/TranslationManagerAutoArbitrationTest.kt` | 1 bare method converted (line 40) | javap: 2/2 void; class re-run green |
| `translation/TranslationManagerDownloadFailureRecoveryTest.kt` | 1 bare method converted (line 120) | javap: 5/5 void; class re-run green |
| `core/migration/MigratorTest.kt` (lives at `mihon/core/migration/MigratorTest.kt`) | 6 bare methods converted | javap: all test methods void; class re-run green (6 tests) |

Quick-check requested by the task: **pre-conversion javap showed every `@Test` method in
the four other files already compiled `void`** — their bare `runBlocking {` lambdas end in
Unit-typed expressions, so none of them was vanishing. Those conversions are
style-hardening against future inference drift (the guard's pattern matches the
declaration form regardless of inferred type), not silent-skip fixes.

## 6. Final verification outputs

- `:app:testStandardDebugUnitTest --tests "eu.kanade.translation.scheduling.RollingAutoCoordinatorTest" --console=plain`
  → `BUILD SUCCESSFUL` — three runs (two with `--rerun-tasks`), 5m17s / 2m36s / 6m06s wall
  (dominated by forced recompilation), test execution 3.2 s; XML: `tests: 31 failures: 0
  errors: 0 skipped: 0`. Completes in minutes; nothing hangs.
- `:app:testStandardDebugUnitTest` over all five affected classes
  (RollingAutoCoordinatorTest 31, ReaderTeardown 4, AutoArbitration 2,
  DownloadFailureRecovery 5, Migrator 6) → `BUILD SUCCESSFUL in 55s`, 0 failures,
  0 skipped.
- `:app:checkTestRunBlocking` → `BUILD SUCCESSFUL in 6s` (empty allowlist, all files
  conform).
- `:app:spotlessCheck` → the only violations are 3 pre-existing UNTRACKED files owned by
  the parallel T910 coverage item (`InMemorySharedPreferences.kt`,
  `TranslationManagerAutoDeleteProtectionTest.kt`, `TranslationQueueStoreTest.kt`);
  none of the files touched by this task appear. Not modified here (out of scope).

## 7. Residual risk

- **Test 2's gate ordering is load-bearing**: it relies on the deterministic
  Unconfined/event-loop drain order of kotlinx-coroutines (documented in the test's KDoc).
  If kotlinx ever changed unconfined dispatch to preempt mid-dispatch, the alternate
  admission order would park the test at `awaitAndCompletePrepare(1)` — bounded by
  `@Timeout(60)` as a clean failure, not a hang. A fully order-agnostic fixture would need
  polling, which the file's no-polling philosophy forbids.
- **Test 6 exercises the cancel-wins interleaving empirically** (the observed one); the
  update-wins interleaving is structurally covered by interleaving-tolerant assertions but
  only fires if the race flips. Both converge on the same final `newIdentity` guarantee.
- **Latent-timing tests now fail loudly** under pathological CI slowdown (as `@Timeout`
  failures within 60 s) instead of hanging the suite — the intended trade.
- **Production exposure unchanged**: the investigation found no coordinator/scheduler
  defect; the shared compute gate's lack of translate-lane priority is legitimate
  (FIFO serialization only), and both affected production behaviors
  (`RollingAutoCoordinator.kt:309/353-366/585-600`, `TranslationScheduler`
  cancel-epoch retention semantics) are now covered by executing tests for the first time.
- The 3 untracked coverage files fail `spotlessCheck` (pre-existing, T910 item 3 scope).
