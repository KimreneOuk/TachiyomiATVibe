# Flake follow-up diagnosis — the 4 order-dependent failures of run 5

Tree: `t934/resume-rebuild-and-parallelism` @ `5330001` (identical to green run 6).
Method: read-only source analysis of the 4 failing tests + harnesses/production paths.
No run artifacts of run 5 survive (test-results were overwritten by the green re-run),
so mechanisms below are derived from code, with confidence stated per item.

## Shared context (applies to all four)

- JUnit 5 runs classes **sequentially inside one fork** (no
  `junit-platform.properties`, no `maxParallelForks`), so "order-dependent" means:
  leaked JVM-global state from same-fork predecessors + fork-level CPU/heap
  pressure (Gradle `org.gradle.parallel=true`, 2020 tests) changing real-clock
  interleavings.
- The coexistence harness runs the REAL graph on REAL `Dispatchers.IO/Default`
  with real-time windows (`withTimeout`, 250 ms debounce, watchdogs) over fakes
  that are instant in isolation. Every failure below is a load-sensitive
  interleaving of a by-design recovery/teardown path that the test oracle does
  not model — not a broken production invariant.
- `GroupCommitConfiguration.enabled` (JVM-global `@Volatile`,
  `artifact/GroupCommitConfiguration.kt:7`) was checked as a cross-class flag
  leak: all three toggling classes reset it in teardown; its gate points
  (`ChapterArtifactStore.kt:1330`, `ChapterDocumentIo.kt:345`) are synchronous —
  **ruled out** as the run-5 mechanism.

---

## 1. MangaScreenModelTranslationDrawerTest — executionError
`java.lang.IllegalStateException: Already resumed, but proposed with update kotlin.Unit`

**Exact producer:** `CancellableContinuationImpl.alreadyResumedError` (message
constant verified present in the resolved kotlinx-coroutines 1.10.1 jar;
`proposedUpdate` prints the raw unwrapped value, so a `Unit`-resumed
continuation got a **second** resume).

**Mechanism (executionError = class-level lifecycle error, and Gradle attaches
uncaught coroutine exceptions to whichever class is running):**
- The fixture assembles a real `LifecycleRegistry` + `ArchTaskExecutor`
  delegate that runs "main" tasks **inline on the caller's thread** + a
  single-thread Main surrogate + **real `Dispatchers.IO`**. During teardown
  (`MangaScreenModelTranslationDrawerTest.kt:267-296`) the cancelled scope's
  collectors unwind on IO workers while the JUnit thread synchronously handles
  `ON_DESTROY` — lifecycle mutation and collector teardown genuinely overlap.
- The teardown hardening (ce8d925) has a **residual hazard: the join is
  bounded and swallowed** — `runCatching { withTimeout(5_000) { join() } }`
  (line 282-288). Under fork load an unwind that exceeds 5 s continues AFTER
  `evictCachedScreenModelScope()` → `setDelegate(null)` → `Dispatchers.resetMain()`
  → `mainThreadSurrogate.close()` (lines 292-295). Late continuations then
  dispatch into a closed/reset Main (`RejectedExecutionException`) or race the
  already-cancelled lifecycle teardown — the exception surfaces asynchronously
  and Gradle attributes it to this class (or the next one), masking the origin.
- The model also calls **mocked suspend functions concurrently from two
  threads** on the hot path: the collector side (`toChapterListItems` →
  `translationManager.getChapterTranslationStatus`, MangaScreenModel.kt:202)
  on scope-IO coroutines while the JUnit thread calls the same mocks via
  `rebuildChapters()` (test line 410-414). MockK's suspend interception is not
  thread-safe; a concurrent call/cancellation of the same suspend stub is a
  known "Already resumed" producer. MockK is absent from prod paths — this is
  fixture-only.
- Note: all three sibling fixtures (MultiSelectBatch, CancelledBatchReconciliation,
  this one) share the identical teardown shape and the same voyager
  "ScreenModelCoroutineScope" cache key (`evictCachedScreenModelScope()` evicts
  the shared key without cancelling it), so the offending unwind may originate
  in a predecessor fixture and only *land* here.

**Suspect ranking:** (a) teardown join-timeout leakage + async attribution — HIGH;
(b) MockK suspend-stub double-resume under two-thread use — MEDIUM;
(c) androidx lifecycle teardown race under the inline-delegate assembly — LOW-MEDIUM.
**Classification: fixture-hardening (no production leak identified).**

**Minimal fix:** `MangaScreenModelTranslationDrawerTest.kt:282-288` (and the two
sibling fixtures): drop `runCatching` and make the join authoritative, e.g.
`runBlocking { withTimeout(30_000) { model.screenModelScope.coroutineContext[Job]?.join() } }`
letting a timeout surface as THIS class's failure with its real cause; keep the
`setDelegate(null)`/`resetMain()` sequence strictly after the join. Optional:
replace the shared mocked suspend `getChapterTranslationStatus` stub with a
plain fakes-first stub (no MockK interception) on that hot path.

---

## 2. BatchDispatchResumeWiringTest "re-dispatch after a reset demotes real work…"
`expected COMPLETE but was TRANSLATE` (line 162, reading run 1's durable record)

**Mechanism:** the fixture's oracle only requires the reconciliation deferred to
complete **non-null** — but a typed non-COMPLETED stop ALSO returns non-null:
`BatchChapterTranslator.kt:927-980` (PAUSED/FAILED/PERSISTENCE_REJECTED branch →
`BatchProgressReconciler.reconcile(...)` → `return@withGeneration reconciliation`).
The durable record's last published phase before the translate tail is
**TRANSLATE** (`ChapterProfileBatchCoordinator.kt:2107`); a stop inside the tail
leaves it there. So ANY load-induced typed pause in run 1 (lease/identity
contention, memory-pressure deferral, checkpoint attempt-ledger charge — the
paths pinned by `OcrPreflightRejectedMidRunDurabilityTest`) produces exactly
"expected COMPLETE but was TRANSLATE" — the "(run not finished within its await
window)" note in the run-5 signature. Publication rejection was investigated and
is NOT required for this mechanism; on `FakeChapterDocumentIo` the
`publishActiveRun` CAS + one-shot stale-manifest retry
(`ChapterArtifactStore.kt:349-359, 1788-1803`) make FINALIZE/COMPLETE
publication effectively unrejectable.

**Interfering state:** real-clock windows + `Dispatchers.IO` starvation from
same-fork load; no static leak (barrier, harness, store are per-test).

**Suspect ranking:** typed pause/early-stop under load — HIGH (only mechanism
consistent with a non-null reconciliation plus a TRANSLATE record).
**Classification: fixture-hardening (a paused run is legal production behavior;
the test assumed completion).**

**Minimal fix:** `BatchDispatchResumeWiringTest.kt:63-78` (`firstRun`) — after
`batch.reconciliation.await().shouldNotBeNull()`, poll
`durableRecord(artifact)` until `state == ChapterRunState.COMPLETE` inside
`AWAIT_TIMEOUT_MS` (awaitUntil-style), failing with the run outcome's
status/reason if it pauses; that converts the ambiguous assert into a precise
"run paused under load" failure and keeps every downstream pin valid.

---

## 3. StandardPipelineCoexistenceTest "flagged standard lane runs the real shell…"
`expected 2 but was 3` — the counter is **NATIVE_ACQUIRE arrivals before the
first PROVIDER_START** (`decodesBeforeFirstTranslate`, line 60-68)

**Mechanism:** NATIVE_ACQUIRE is arrived only by the harness decode seam
(`TranslationCoexistenceHarness.kt:460-463`). The primary preflight decodes each
page once — BUT a page whose primary attempt fails to produce an `OcrReadyPageRef`
**after** the decode arrival (the barrier logs the arrival at decode START)
becomes a corpus gap, and the **S8 in-pass gap rescan re-runs the page's OCR**
(`ChapterProfileBatchCoordinator.kt:715-787`, especially line 764
`nativeWorker.runOcrStage`) BEFORE the translate tail. Ref-null paths that leave
a gap without failing the run: lease-deferred/externally-completed
(BatchLaneWorkers.kt:324-341, 381), decode/recognition memory deferral
(`LowMemoryDecodeDeferredException`/`LowMemoryRecognitionDeferredException`,
lines 441-457, 506+), per-page watchdog timeout (coordinator line 604-618).
2 primary decodes + 1 rescan re-decode = 3 arrivals before the first paid call;
the run then completes normally, so the await succeeds and the schedule
discriminator assert fires on the +1. (CHECKPOINT_REJECTED is NOT the +1 — it
fails the run outright, coordinator line 655.)

**Interfering state:** fork heap/CPU pressure driving a memory-deferral or
deferral/timeout path on one page of a 2-page chapter; per-harness barrier rules
out cross-class arrival leakage.

**Suspect ranking:** S8 rescan re-decode after a transient primary-attempt
deferral — HIGH for the +1 identity; MEDIUM for which deferral path fired.
**Classification: fixture-hardening (the rescan is by design; the assert
over-pinned "every primary attempt completes").**

**Minimal fix:** `StandardPipelineCoexistenceTest.kt:60-68` — count DISTINCT
pages decoded before the first provider start:
`arrivals.take(firstProviderStart).filter { it.first == NATIVE_ACQUIRE }.map { it.second }.distinct().size shouldBe pageKeys.size`
The legacy page-serial schedule still fails this (1 distinct page before its
first PROVIDER_START), so the discrimination property is preserved.

---

## 4. StandardPipelineCoordinatorTest T4 "standard lane records freeze…"
`org.junit.platform.commons.JUnitException: Failed to close extension context`

**Mechanism:** T4 (and its siblings) build REAL file-backed stores:
`@TempDir mangaDir` + `ChapterTranslationStore.lazy(fileCreator, artifactParent, …)`
(lines 109-121) and never close them. A full `runPass1` runs
`drainFinalizeAndComplete`, which fires `store.reconcileArtifactRetentionAsync()`
(coordinator line 2088) — a **fire-and-forget recursive crawl + delete on
`persistScope` (`SupervisorJob() + Dispatchers.IO`)**,
`StorePersistenceScheduler.kt:179-189` — plus `schedulePersist`'s 250 ms
debounced flush (line 191-203), also on `persistScope`. `runTest` returns
without joining either; JUnit's TempDirectory extension then recursively
deletes `mangaDir` while a background coroutine may be crawling/renaming/deleting
inside it. On Windows a delete that collides with a just-recreated/renamed or
open file throws (`AccessDeniedException`/`FileSystemException`) → the
extension closeable throws → `JUnitException: Failed to close extension context`.
In isolation the IO pool is idle and the crawl finishes before cleanup → green;
under full-suite load the crawl's dispatch is delayed → collision → flake.

**Interfering state:** per-store `persistScope` that outlives the test because
the fixture never calls `store.closeAndFlush()`; the retention sweep fired by
the production close path (coordinator 2088 / BatchChapterTranslator 1056) is
the long-lived tail.

**Suspect ranking:** never-closed store + TempDir deletion race on Windows — HIGH.
**Classification: fixture-hardening on an underlying real lifecycle smell**
(production closes stores via `closeAndFlush()`; the test skipped the close
step, and the async `close()` variant itself is launch+cancel, racy by design).
No production change required for the flake, but see note below.

**Minimal fix:** `StandardPipelineCoordinatorTest.kt` — in T4 (ideally all
runTest cases that use `lazyStore()`), end the runTest body with
`store.closeAndFlush()` (flushes, then cancels `persistScope`, joining the
sweep window); same for `BatchLeaseFlipHealTest.kt:60-68`, which uses the
identical `@TempDir` + `ChapterTranslationStore.lazy` recipe and is the second
site of this hazard (its writes also arm the 250 ms debounced flush).

---

## Wave-1 amplifier verdicts

- **T934ProjectorRebuildTruthTest** — NOT a leaker. Real `ChapterTranslationStore`
  + `ChapterArtifactStore` are memory-only (`translationFile/fileCreator/
  artifactParent` all null → `schedulePersist` and `reconcileArtifactRetentionAsync`
  early-return, `StorePersistenceScheduler.kt:180,196`); registries are per-test;
  the tracker runs on `backgroundScope` (auto-cancelled by `runTest`). The
  projector probe (`BatchProgressProjector.kt:409-438`, 500 ms throttle +
  `withContext(Dispatchers.IO)`) is flow-local and cannot double-resume; and the
  MangaScreenModel fixtures never execute it (their `TranslationManager` is a
  mock, so `observeBatchProgress` is stubbed). Contribution to run 5: negligible.
- **BatchLeaseFlipHealTest** — amplifier for failure class 4 (same TempDir +
  lazy-store + never-closed pattern). See above.
- **T934SheetAdvancedViewPreferenceTest** — harmless (no dispatchers, scopes,
  stores, or global state).

## Teardown-hardening double-resume review (requested)

`ON_DESTROY → scope.cancel → runCatching{ withTimeout(5s){ join() } } →
evict → setDelegate(null) → resetMain → close`: the ordering is right, but the
5 s bound plus `runCatching` makes the quiesce best-effort — under load it
proceeds while collectors are still unwinding, which is exactly the window that
produces the cross-thread lifecycle/dispatch exceptions attributed to a later
class. Additionally, `model.screenModelScope` is re-evaluated on every access
(voyager `getOrPutDependency`); the shared-key eviction makes scope identity
 JVM-global, so a predecessor fixture's unjoined scope keeps running against
the CURRENT fixture's Main surrogate. Keep the ordering, remove the silent
bound (fix in section 1).

## Recommended order of attack (all fixture-side, no production edits)

1. StandardPipelineCoordinatorTest + BatchLeaseFlipHealTest: `closeAndFlush()`
   before the TempDir close (fixes class 4, the only one with a deterministic
   Windows failure mode).
2. BatchDispatchResumeWiringTest `firstRun`: durable-closure oracle (class 2).
3. StandardPipelineCoexistenceTest: distinct-page decode discriminator (class 3).
4. MangaScreenModel* teardown: authoritative join + no swallowed timeout (class 1).
