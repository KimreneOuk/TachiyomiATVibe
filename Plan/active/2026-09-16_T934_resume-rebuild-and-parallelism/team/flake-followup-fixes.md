# Flake follow-up fixes — T934 fixture-side hardening (4 fixes)

Executor: Implementer subagent, 2026-09-16.
Base: `t934/resume-rebuild-and-parallelism` @ `5330001`.
Input: `team/flake-followup-diagnosis.md`.
Scope: test fixtures ONLY. `git diff --stat` after the work confirms edits are
confined to the 7 allowlisted files under `app/src/test/java/`. Three
production files (`ChapterProfileBatchCoordinator.kt`, `ChapterArtifactStore.kt`,
`ChapterArtifactManifest.kt`) were already dirty in the worktree from concurrent
work before this task started — untouched, and not part of this diff's authorship.

---

## FIX A — TempDir deletion race (diagnosis §4)

**Files:**
- `app/src/test/java/eu/kanade/translation/pipeline/batch/StandardPipelineCoordinatorTest.kt`
- `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchLeaseFlipHealTest.kt`

**Edit:** Every test method that creates `val store = lazyStore()` now ends
(after all assertions) with `store.closeAndFlush()` plus a short comment.
Grep confirmed the expected counts: 7 call sites in the coordinator test
(T2, T3, T4, T5, T6, T7, T8) and 4 in the flip-heal test (all four tests).
All 11 call sites are inside `runTest { }` bodies — suspend context, so the
suspend `closeAndFlush()` compiles without extra plumbing. No new imports
needed.

**Verification against code:**
- `StorePersistenceScheduler.kt:107-121`: `closeAndFlush()` flushes under the
  store mutex, then `persistScope.cancel()` — the scope that hosts both the
  250 ms debounced persist (`schedulePersist`, lines 191-203) and the
  fire-and-forget retention crawl (`reconcileArtifactRetentionAsync`,
  lines 179-189). Cancelling before `@TempDir`'s recursive delete removes the
  Windows delete/crawl race the diagnosis pins.
- `ChapterTranslationStore.kt:2771`: `suspend fun closeAndFlush() =
  persistenceScheduler.closeAndFlush()` — the store facade exposes it.
- `lazyStore()` in both fixtures is `ChapterTranslationStore.lazy(...)` with a
  non-null `fileCreator`/`artifactParent`, so the persist path is armed
  (memory-only early-returns do not apply).

**Assertion impact:** none (lifecycle hygiene only).

## FIX B — ambiguous completion oracle (diagnosis §2)

**File:** `app/src/test/java/eu/kanade/translation/coexistence/BatchDispatchResumeWiringTest.kt`

**Edit:** In `firstRun` (was lines 63-78), after
`batch.reconciliation.await().shouldNotBeNull()`, added a bounded poll of the
durable run record using the file's existing idioms:
`runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { while (durableRecord(artifact).second != ChapterRunState.COMPLETE) delay(50) } }`.
On timeout it re-reads the full record and fails via `error(...)` with
`state`, `runId`, and `phaseCounters` — so a load-induced typed pause is
reported at the source instead of surfacing later as
"expected COMPLETE but was TRANSLATE". The catch is placed inside the
existing `try/catch (t: Throwable) { harness.close(); throw t }`, so the
harness is still closed on the precise failure. New imports:
`kotlinx.coroutines.TimeoutCancellationException`, `kotlinx.coroutines.delay`.
No other assertion in the file was changed.

**Verification against code:**
- `TranslationCoexistenceHarness.kt:156`: `AWAIT_TIMEOUT_MS = 10_000L`.
- `TranslationCoexistenceHarness.kt:119`: `val store: ChapterTranslationStore`
  (public); tests already use `harness.store.artifactStore`.
- `durableRecord(artifact)` (file-local helper) re-reads the manifest each
  call — a valid polling source; post-reconciliation the record exists.
- Mechanism confirmed in `BatchChapterTranslator.kt` (~927-981): a
  non-COMPLETED `stoppedOutcome` still calls
  `BatchProgressReconciler.reconcile(...)` and returns the reconciliation —
  i.e. non-null reconciliation ≠ COMPLETE, exactly the ambiguity closed.
- `ChapterRunRecord.kt:98-122`: durable pause/failure signals live in
  `state` + `phaseCounters` (e.g. `preflightStop`, `envelopeFailures`);
  there is no free-text durable reason field, so the failure message reports
  state + counters as the "outcome status/reason".
- `TimeoutCancellationException` import precedent exists in both main and
  test sources (e.g. `EngineLane.kt`, `D11PermitFreeCommitTest.kt`).

**Assertion impact:** strengthened oracle only (mission-authorized); no
weakening.

## FIX C — over-pinned decode count (diagnosis §3)

**File:** `app/src/test/java/eu/kanade/translation/coexistence/StandardPipelineCoexistenceTest.kt`

**Edit:** Replaced the per-arrival count with the mission's DISTINCT-page
form:
`arrivals.take(firstProviderStart).filter { it.first == BarrierPoint.NATIVE_ACQUIRE }.map { it.second }.distinct().size shouldBe pageKeys.size`
(variable `distinctPagesDecodedBeforeFirstTranslate`). Added the authorized
T934 conversion comment (S8 in-pass gap rescan in
`ChapterProfileBatchCoordinator` may legitimately re-decode a deferred page
before the translate tail, so decode multiplicity is not a schedule property;
distinct-page coverage before the first paid call IS; the legacy page-serial
schedule yields 1 distinct page and still fails). The pre-existing
explanation's still-true content ("preflight decodes EVERY page before any
translate"; legacy schedule shows exactly one; assertion is the schedule
discriminator) is folded into the new comment. The class KDoc's Contract-2
sentence remains true under distinct semantics and was left untouched.

**Verification against code:**
- `CoexistenceBarrier.kt:64`: `arrivals: StateFlow<List<Pair<BarrierPoint, String>>>`
  — `it.second` is the page key; `NATIVE_ACQUIRE` is arrived at the decode
  seams (harness lines ~461 and ~1158); `PROVIDER_START` is an existing
  `BarrierPoint`.
- `firstProviderStart >= 0` is still pinned before `.take(...)`, so the slice
  is well-defined.

**Assertion impact:** the single mission-authorized conversion, carrying the
required comment. Discrimination preserved (legacy schedule → 1 distinct page).

## FIX D — swallowed teardown join (diagnosis §1)

**Files (identical teardown shape, verified):**
- `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelTranslationDrawerTest.kt` (was lines 282-288)
- `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelMultiSelectBatchTest.kt` (was lines 291-297)
- `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelCancelledBatchReconciliationTest.kt` (was lines 294-300)

**Edit:** Replaced `runCatching { runBlocking { withTimeout(5_000) { ...join() } } }`
with `runBlocking { withTimeout(30_000) { model.screenModelScope.coroutineContext[Job]?.join() } }`
— no swallowing; a timeout now fails the owning class with its real cause.
Exact ordering preserved: ON_DESTROY → `scope.cancel` → join →
`evictCachedScreenModelScope()` → `setDelegate(null)` → `Dispatchers.resetMain()`
→ `mainThreadSurrogate.close()`. The existing quiesce comment was extended by
one line noting the join is authoritative (a >30s unwind fails THIS class
with the real cause rather than leaking into the next fixture).

**Verification against code:**
- All three files import `runBlocking`, `withTimeout`, `Job` already (used by
  the previous block) — no import changes; `runCatching` is stdlib.
- `model.screenModelScope` re-evaluates to the SAME voyager-cached scope until
  `evictCachedScreenModelScope()` runs, so cancel → join address the same
  instance (ordering unchanged from the ce8d925 hardening).
- The separate 5s `withTimeout` inside MultiSelect's per-test quiesce helper
  (~line 435) is NOT the teardown block and was left as-is per the mission
  scope (teardown join only).

**Assertion impact:** none.

---

## Compile inspection summary

- FIX A: `closeAndFlush` is suspend (`ChapterTranslationStore.kt:2771`); all
  11 call sites are in `runTest` bodies (suspend). No imports needed.
- FIX B: `TimeoutCancellationException` + `delay` imports added (precedent in
  tree); `durableRecord`/`harness.store.artifactStore`/`ChapterRunState`
  all pre-exist and are accessible; `error(...)` is stdlib; the catch sits
  inside the existing harness-close try.
- FIX C: pure collection-operator change over
  `List<Pair<BarrierPoint, String>>`; no imports needed.
- FIX D: no import changes; block shapes verified in the diff.
- Gradle/build NOT run (per hard rules) — Main Leader builds.

## Deviations

1. FIX B failure message: the durable record has no free-text reason field
   (`ChapterRunRecord.kt:98-122`), so "status/reason" is reported as
   `state` + `runId` + `phaseCounters` (counters carry the durable
   pause/failure signals, e.g. `preflightStop`).
2. FIX B polling: used the file's own `durableRecord` helper inside a
   `while + delay(50)` loop under the file's existing
   `runBlocking { withTimeout(AWAIT_TIMEOUT_MS) }` idiom, rather than an
   awaitUntil utility (none exists in this file).
3. FIX D comment extension written as a single long line (mission asked to
   "extend by one line").
4. No mechanism contradiction found — all four diagnosis mechanisms were
   re-verified against current code before editing; only line numbers had
   drifted (within the ranges the mission predicted).
