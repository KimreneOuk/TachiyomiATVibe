# T922 — Full-Suite Unit-Test Flakiness Diagnosis

Date: 2026-09-05 · Machine: DESKTOP-8VBDTJF · Worktree: `optimize_translation_pipeline_ux` @ `7a95f9c94ec369efb6273ba7a209f7e42ddc4f80` + T922 dirty state
Method: like-for-like reproducer loops (identical gradle filter, identical machine) on the T922 worktree vs a throwaway baseline clone reconstructing the pre-T922 state.

## VERDICT (up front)

**PRE-EXISTING test flakiness. The T922 changeset is not the cause.**
The dominant flake (`MangaScreenModelCancelledBatchReconciliationTest`) reproduces on the clean
baseline clone at a statistically indistinguishable rate (2/6 vs 3/6 filtered-loop runs, Fisher p=1.0).
Root causes are test-side: a lost SharedFlow emission (subscription race) and a marginal fixed probe
budget. Two minimal test-side stabilizations were applied (no assertion weakening, no production change,
both files are committed repo content, not Director-owned dirty content). Post-fix full suite: **4/4 green**.

## 1. Failure evidence per run (before any fix)

### Full suite `:app:testStandardDebugUnitTest --rerun` (Main Leader's runs)

| Run | Result | Failure |
|-----|--------|---------|
| 1 | FAIL | `eu.kanade.translation.coexistence.D10PartialDownloadAdmissionTest."rerun after the download completes translates the missing pages and clears the partial info"` — `expected READY, was PENDING` at `D10PartialDownloadAdmissionTest.kt:539` |
| 2 | PASS | — |
| 3 | PASS | — |
| 4 | FAIL | `eu.kanade.tachiyomi.ui.manga.MangaScreenModelCancelledBatchReconciliationTest."cancelled batch strands chapter in translating state with no restart affordance"` |

Run-4 exact message (from `app/build/test-results/.../TEST-eu.kanade.tachiyomi.ui.manga.MangaScreenModelCancelledBatchReconciliationTest.xml`, the on-disk run-4 report):

```
java.lang.AssertionError: Timed out after 10000ms waiting for: item shows the live TRANSLATING state
(state=Success(... Item(chapter=Chapter(id=5 ...), downloadState=DOWNLOADED,
translationState=NOT_TRANSLATED, translationProgress=null, translationRequest=null ...) ...))
```

Note: the FIRST await of the test (TRANSLATING after the status emission) never observed the state in
10 s; the item stayed `NOT_TRANSLATED` — the emission was lost, not merely slow.

### Reproducer filtered loop (identical filter both trees)

Command: `./gradlew :app:testStandardDebugUnitTest --rerun --tests "eu.kanade.translation.**" --tests "eu.kanade.tachiyomi.ui.manga.*"`
→ 200 classes / 1462 tests per iteration, ~2–3.5 min each.

**CURRENT worktree (T922 changeset), 11:43–12:01:** `P, F, P, F, F, P` → **3/6 failed**
- iter 2: `MangaScreenModelCancelledBatchReconciliationTest` (same first-await lost-emission signature as Leader run 4) **and** `D2ManualBatchInterleavingTest."batch to manual - tap while batch holds the page at provider start end and render"` (`TimeoutCancellationException` in `StateFlow.first`, i.e. `CoexistenceBarrier.awaitArrival`, `CoexistenceBarrier.kt:94`)
- iter 4 + iter 5: `StandardLaneMultiPageCompletionTest."fresh standard batch translates every page of a multi-page chapter"` — "multi-page standard batch never translates p1: static PRIOR_PAGE_INCOMPLETE skip is permanent within a pass"

**BASELINE clone (HEAD `7a95f9c` + baseline_tracked.patch + 5 pre-existing untracked files; NO T922 changes), 12:19–12:31:** `F, P, P, P, P, F` → **2/6 failed**
- iter 1 and iter 6: **the exact same** `MangaScreenModelCancelledBatchReconciliationTest."cancelled batch strands…"` with the same signature.

**Baseline full suite ×4 (12:07–12:17):** 4/4 PASS — small-sample luck; the filtered loop is the honest
comparator and it flakes on baseline too.

### Rates

| Series | Current (pre-fix) | Baseline | Fisher exact |
|---|---|---|---|
| Full suite | 2/4 | 0/4 | p=0.44 (n.s.) |
| Filtered loop | 3/6 | 2/6 | p=1.0 (n.s.) |
| Pooled | 5/10 | 2/10 | p=0.35 (n.s.) |

Failures by test (pooled both trees, 20 suite executions):
`MangaScreenModelCancelledBatchReconciliationTest` ×4 (incl. ×2 on clean baseline) — the dominant flake;
`StandardLaneMultiPageCompletionTest` ×2; `D2ManualBatchInterleavingTest` ×1; `D10PartialDownloadAdmissionTest` ×1.
None of the failures is in a T922-added test.

## 2. Root causes (with file:line)

### RC1 — Lost SharedFlow emission: `MangaScreenModelCancelledBatchReconciliationTest` (dominant; flakes on clean baseline)

- The screen model subscribes to the manager status flow **asynchronously**:
  `MangaScreenModel.kt:530-541` (`observeTranslations()`, launched from `init` at `MangaScreenModel.kt:232`
  via `screenModelScope.launchIO { translationManager.statusFlow()…flowWithLifecycle(lifecycle).collect { withUIContext { updateTranslationState(it) } } }`).
- The test's `translationStatusFlow` is `MutableSharedFlow<Translation>(extraBufferCapacity = 16)` —
  **zero replay** (`MangaScreenModelCancelledBatchReconciliationTest.kt:160`). A `tryEmit` fired before the
  collector's subscription is live is **silently dropped forever**; no await budget can recover it.
- Test `Order(1)` (`:291-300`) emits immediately after the `Success` boot-wait (`:263-268`) and awaits
  `TRANSLATING` with a 10 s bounded `state.first {}` (`awaitItem`, `:369-385`). Under full-suite load the
  IO-pool subscription start occasionally loses the race → item stays `NOT_TRANSLATED` → exactly the
  observed 10 s timeout signature.
- Reproduced twice on the clean baseline ⇒ pre-existing.

**Fix applied (test-side, setUp only):** hold setup until the collector has actually subscribed —
`translationStatusFlow.subscriptionCount.first { it > 0 }` inside the existing bounded `runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { … } }` block
(`MangaScreenModelCancelledBatchReconciliationTest.kt:262-276`). No assertion touched; both tests
(`Order(1)`, `Order(2)`) keep their semantics — the first emission now cannot outrun the subscription,
and the subscriber persists for the rest of the class.

### RC2 — Marginal fixed budget on a POSITIVE probe: `StandardLaneMultiPageCompletionTest` (2 failures on current)

- `StandardLaneMultiPageCompletionTest.kt:58` awaited p1's transport start — a **positive** probe
  ("p1 must start, else the static-skip defect is back") — using `NEGATIVE_PROBE_MS = 2_000L`
  (`TranslationCoexistenceHarness.kt:155`). Starting p1 requires p0's entire fake-engine pipeline plus
  p1's planner admission to complete within 2 s — marginally tight under full-suite load.
- Failed 2/6 on current, 0/6 on baseline (n.s.); mechanism is a marginal budget, load-sensitive.

**Fix applied (test-side, one line):** probe budget `NEGATIVE_PROBE_MS` → `AWAIT_TIMEOUT_MS` (10 s,
`StandardLaneMultiPageCompletionTest.kt:58` area). The named RED assertion ("never translates p1") is
unchanged and still fires on expiry; only the bound widens.

### Not fixed — documented (single occurrences, no evidenced test-side defect)

- `D2ManualBatchInterleavingTest` (×1, current): 10 s timeout in `CoexistenceBarrier.awaitArrival`
  (`CoexistenceBarrier.kt:94`, `StateFlow.first`). A StateFlow cannot drop updates — the miss means the
  real-dispatcher pipeline genuinely did not reach the barrier within 10 s under load. The wait is already
  event-driven; no safe minimal stabilization exists without weakening the oracle.
- `D10PartialDownloadAdmissionTest` (×1, Leader run 1): `p1.translationStatus` was `PENDING` at
  `:539` **after** `batch.reconciliation.await()` had resolved and after all manifest-truth assertions
  (registered pages, cleared partial info, trusted total) had passed. That means store pre-registration
  committed the durable truth but the batch ended without working p1. Not reproduced in 10 baseline or
  6 current-filtered executions. Candidate pre-existing pipeline admission/deferral race (the
  `deferredPages` / `awaitLeaseHandback` admission path in `SequentialBatchCoordinator` is the natural
  suspect) — a **production-code investigation for the Director**, not test stabilization. Left untouched.

## 3. T922-instrumentation hypotheses audited and rejected

- (a) Per-stage trace overhead under `BuildConfig.DEBUG=true`: **rejected as mechanism.** A 2-page batch
  emits ~15–25 trace lines (`BatchPhase4TraceWiringTest` counts); formatting + the no-op logcat sink
  (`returnDefaultValues`) is µs-scale — it cannot move 2 s/10 s budgets. Suite wall-time delta
  (baseline ~2.0–2.4 min vs current ~2.0–3.5 min per filtered/suite run) is within the range explained by
  the ~11 added test classes alone.
- (b) Singleton pollution from T922 tests: **rejected.** All 8 T922 tests that mutate
  `TranslationPipelineDiagnostics` (sink / `detailedTracingEnabled` / `idGenerator` / `identityKeys`)
  restore them via try/finally or `@AfterEach` (verified per file, incl. the deliberate mid-test sink swap
  in `RollingAutoCoordinatorRegistrationRaceTest.kt:142`, restored by `@AfterEach` → `restoreCapture()`).
- (c) Real race introduced by T922 instrumentation: **no concurrency-altering diff found.** Trace locks
  (`TranslationTrace.kt` `stateLock`/`stageLock`) are private, ns-scale guards around map snapshots; the
  `SequentialBatchCoordinator`/`BatchChapterTranslator`/`TranslationPipeline`/`TranslationScheduler` diffs
  are span wiring plus rethrow-preserving restructuring; `translateBatch` entry guards unchanged.
  Decisive execution-order evidence: within a test JVM the flaky classes run **before** any T922 test
  class (iter-6 XML timestamps: StandardLane #1, ui.manga #2-6, D10/D2 #43-46; first T922 class #58),
  so T922 test classes cannot even have leaked state into them.
- The `onnxruntime-android` bump 1.27.0 → 1.28.0 in the T922 changeset is an Android AAR native lib;
  it is never loaded by these JVM unit tests.

## 4. Files changed by this diagnosis (worktree, uncommitted, test-only)

1. `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelCancelledBatchReconciliationTest.kt` (+8: setUp subscription-readiness await, with explanatory comment)
2. `app/src/test/java/eu/kanade/translation/coexistence/StandardLaneMultiPageCompletionTest.kt` (+8/−1: positive-probe budget 2 s → 10 s, with explanatory comment)

No production file touched. Both files are committed T917/T918 repo content (not in the Director-owned
dirty set). Nothing committed.

## 5. Post-fix verification (12:35–12:44)

Full suite `:app:testStandardDebugUnitTest --rerun` ×4 consecutive: **4/4 PASS**
(exit 0 at 12:38:05, 12:40:05, 12:42:07, 12:44:10; logs `T922_repro/verify_{1..4}.log`).
Sanity run of both fixed classes before the gate: PASS (47 s).

## 6. Residual risk (honest)

`D2ManualBatchInterleavingTest` and `D10PartialDownloadAdmissionTest` each flaked once in ~14 current
executions and are not stabilized (mechanisms above). Expected residual full-suite flake frequency is low
(≈1 failure per ~10 suite runs, dominated by these two). If the Director wants them eliminated, D10
requires a production-side investigation of the batch admission/deferral path (pre-existing, T917-era);
D2 would need either a wider barrier budget or an acknowledged load-sensitive oracle.

## 7. Baseline experiment provenance

- Clone: `git clone --no-hardlinks <worktree> C:/Users/User/Documents/T922_baseline_clone`
- `git checkout 7a95f9c94ec369efb6273ba7a209f7e42ddc4f80` (worktree HEAD; T922 work is uncommitted so the clone got none of it)
- `git apply C:/Users/User/Documents/T922_baseline_backup_2026-09-04/baseline_tracked.patch` (14 files, pre-T922 dirty state)
- Copied the 5 pre-existing untracked files from the backup dir (ModelRoutingEngine.kt/.kt-test, QnnContextCacheManager.kt/.kt-test, handover .md) + `local.properties`
- Clone deleted after results were recorded (per task contract).
