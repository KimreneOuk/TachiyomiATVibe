# T917 Phase 2 — Implementation Log, part A (D1 + D4)

Branch: `t917/coexistence-v3`. Scope: production changes D1 (three-origin lease
model) and D4 (manager-level batch suppression) per `engineering/phase2-design.md`
§1 + §4 (authoritative spec); D2/D3 are part B and were NOT touched.
Log location note: this is a NEW file (`phase2-implementation-log.md`) rather than
an appended section in `phase1-implementation-log.md` — cleaner, matches the
`phase2-design.md` naming; a one-line pointer was appended to the phase-1 log.

## 1. Files changed per commit

### Commit `d99a93b` — `t917(p2): D1 origins`

Production (design note §1.1–§1.4):

| File | Change |
|---|---|
| `app/src/main/java/eu/kanade/translation/TranslationStageContracts.kt` | `PageWriteOrigin` = `{ MANUAL, AUTO, BATCH }` (READER_ADHOC deleted) with the two-vocabulary rule KDoc; new shared top-level `PageWriteOrigin?.toArtifactOrigin(): ArtifactOrigin` (MANUAL/AUTO/null → READER_ADHOC, BATCH → BATCH) |
| `app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt` | §1.2 matrix: the one new rule — MANUAL request evicts an in-flight AUTO lease (fresh record + new token); all other cross-origin requests still Denied; same-origin re-entry untouched; release/cancel were already origin-checked (`pageLeases[key]?.origin == origin`), so an evicted AUTO holder can never remove MANUAL's lease |
| `app/src/main/java/eu/kanade/translation/scheduling/TranslationExecutor.kt` | §1.4.1 (D1 slice): `translateSinglePage` gains `origin: PageWriteOrigin = MANUAL` (default keeps every existing call site compiling); return type stays `Unit` — the `SinglePageOutcome` change is part B (D2) |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | `translateSinglePage` override threads `origin`; `runSinglePageBoundary` gains `origin` param (default MANUAL — `translateSinglePageFromStream` keeps Unit and MANUAL per §1.4.1); `acquireReaderPageLease`/`releaseReaderPageLease` gain `origin`; `prepareSinglePage` + `translatePreparedPage` acquire/release as **AUTO** (§1.3: only RollingAutoCoordinator calls them); `translateSinglePageHttpRender` stub gains `origin` |
| `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt` | `translateSinglePageHttpRender` gains `origin: PageWriteOrigin = MANUAL`; the :158 stamp is now `origin.toArtifactOrigin().name` — still `"READER_ADHOC"` for the single-page path (never `"MANUAL"`/`"AUTO"`; `PageWorkPlanner.stageEvidence` parses with `ArtifactOrigin.valueOf`) |
| `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt` | Legacy auto resume path (:341) passes `origin = PageWriteOrigin.AUTO` (§1.3); the manual tap (:590) keeps the default MANUAL |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` | Private `toArtifactOrigin` removed (the shared top-level mapping replaces it, same result) |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` | Stranded-page sweep (:2563/:2594) acquires/releases as **AUTO** (§1.3: reader-side automatic maintenance) |

Tests:

| File | Change |
|---|---|
| `coexistence/D2ManualBatchInterleavingTest.kt` | §6 compile fix :140 `READER_ADHOC` → `MANUAL` (+ one comment word); red assertions otherwise untouched |
| `coexistence/D3ReaderOwnedPageAcrossBatchTest.kt` | §6 compile fix :70 `READER_ADHOC` → `MANUAL` |
| `coexistence/D1OriginPriorityTest.kt` | NEW — see §3 |
| `ChapterTranslationStorePhase3Test.kt` | Compile fix (design note §6 missed it): :100/:170 `READER_ADHOC` → `MANUAL` (same intent: reader-origin lease distinct from BATCH) |
| `CancelSyncStoreWriteTest.kt`, `scheduling/RollingAutoCoordinatorTest.kt` | Compile ripple of §1.4.1: the two test `TranslationExecutor` fakes add the `origin` parameter to their `translateSinglePage` overrides |

### Commit `dd9f17b` — `t917(p2): D4 suppression`

| File | Change |
|---|---|
| `app/src/main/java/eu/kanade/translation/TranslationManager.kt` | §4 guard, manager level (§0 correction): `updateAutoWindow` early-returns when `isBatchTranslationRetained(identity.chapterId)`; `requestAutoWindow` same early-return on `session.chapter.id`; `reconcileAutoWindow` passes `admissionGuard = { id -> !isBatchTranslationRetained(id) }` into the scheduler's existing hook. Signal = `isBatchTranslationRetained` (QUEUE|TRANSLATING|PAUSED, existing :649-656). `translateChapter`'s one-shot `shutdownAutoCoordinator` unchanged; `openTranslationSession` NOT gated (per §0) |

### Commit (this one) — part A log

| File | Change |
|---|---|
| `engineering/phase2-implementation-log.md` | Created (this file) |
| `engineering/phase1-implementation-log.md` | Appended one-line pointer to this file |

## 2. Exact commands (Git Bash, `JAVA_HOME` = Android Studio JBR)

```
# baseline capture (before changes)
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest"
# after D1 (step 1) and after D4 (step 2)
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest" --tests "eu.kanade.translation.ChapterTranslationStorePhase3Test" --tests "eu.kanade.translation.CancelSyncStoreWriteTest" --tests "eu.kanade.translation.scheduling.RollingAutoCoordinatorTest"
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest" --tests "eu.kanade.translation.coexistence.*"
# verification sweep
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"
# determinism soak (5 consecutive forced reruns, bash for-loop)
for i in 1 2 3 4 5; do ./gradlew :app:testStandardDebugUnitTest --rerun --tests "eu.kanade.translation.coexistence.*"; done
```

## 3. New D1 test — name + green proof

`eu.kanade.translation.coexistence.D1OriginPriorityTest` →
`manual boundary evicts an auto lease whose later writes fail closed and cannot release the manual lease`

Green proof (every run, incl. all 5 determinism reruns):
`TEST-...coexistence.D1OriginPriorityTest.xml: tests="1" skipped="0" failures="0" errors="0"`.

Covers the Main-Leader-required MANUAL-evicts-AUTO rule on the real store,
exercising exactly the calls production makes post-D1:
(a) AUTO acquires (prepareSinglePage's call) → MANUAL acquires (boundary's call)
→ evicted: owner flips AUTO→MANUAL with `manual.token == auto.token + 1`;
(b) the evicted AUTO side's next `patchPage` (guarded write) with its stale
token is `PatchResult.Rejected`, `errorMessage` stays null, owner still MANUAL
(fail-closed, never written over MANUAL's ownership);
plus matrix rows: AUTO-while-MANUAL Denied(owner=MANUAL); (c) AUTO
`releasePageStageLease` and `cancelPageStageWork` (=false) do NOT remove
MANUAL's lease; sanity: MANUAL writes under its own token (Accepted) and its
release frees the page.

Fixture deviation (recorded): store-level, same style as
`ChapterTranslationStorePhase3Test`, instead of the full coexistence harness —
the D1 semantics live entirely in the lease table + token fencing; driving the
full graph would only re-test D2's choreography. No new infrastructure.

## 4. Red-message identity check — UNCHANGED

Baseline captured before any change; byte-identical after D1 and after D4
(compared from `app/build/test-results/testStandardDebugUnitTest/TEST-*.xml`
`message` attributes):

1. D2.1: `D2 batch→manual (C-01) at PROVIDER_START: manual tap on a batch-owned page finished without waiting for the batch owner (wait-and-attach not implemented) expected:<true> but was:<false>`
2. D2.2: `D2 manual→batch (C-02): batch stranded the manually-owned page instead of defer-and-rescan within the pass: stranded={p1=Translation incomplete — expected page was stranded by a prior run and not reached} expected:<{}> but was:<{ "p1" = ... }>`
3. D3: `D3 (C-02): reader-owned page (p1) was skipped and never rescanned, so reconciliation stranded it: stranded={p1=...} expected:<{}> but was:<{ "p1" = ... }>`
4. D4 (red at baseline, GREEN after step 2): `D4: same-chapter auto re-armed while the chapter batch was still queued (batch-lifetime suppression guard missing from the re-arm path) expected:<false> but was:<true>`

## 5. Sweep + determinism results

- Full sweep `--tests "eu.kanade.translation.*"`: **1232 tests completed, 3 failed**
  (baseline 1231 completed / 4 failed + this part's 1 new test, D4 flipped green).
  The 3 failures are exactly D2 (both methods) + D3 with unchanged messages;
  everything else green incl. `TranslationManagerAutoArbitrationTest` (2/2) and
  `NormalMangaIsolationTest`. No unrelated failures to investigate.
- Determinism: 5 consecutive forced `--rerun` coexistence-filter runs —
  **5/5 identical**: 5 tests completed, 3 failed (D2.1, D2.2, D3), 2 passed
  (`NormalMangaIsolationTest`, `D1OriginPriorityTest`).
- Neighbor tests after D1: `ChapterTranslationStorePhase3Test` 8/8,
  `RollingAutoCoordinatorTest` 31/31, `CancelSyncStoreWriteTest` 3/3 — green.

## 6. Deviations / decisions (all within D1+D4 scope)

1. **Phase3Test compile fix beyond §6's list**: the design note's §6 enumerated
   only D2:140 + D3:70, but `ChapterTranslationStorePhase3Test.kt` :100/:170 also
   reference the deleted `READER_ADHOC`; fixed to `MANUAL` (compile-breaking
   omission; same reader-origin intent).
2. **Executor-fake compile ripple**: §1.4.1's interface change forces the two
   test `TranslationExecutor` implementations (`CancelSyncStoreWriteTest`,
   `RollingAutoCoordinatorTest.ControllableExecutor`) to declare the new
   `origin` parameter. Mechanical; no behavior change.
3. **`translateSinglePageFromStream` (scheduler :386) left at the default
   MANUAL**: spec §1.4.1 gives that method no `origin` param and enumerates only
   :341 as passing AUTO; this exactly preserves today's behavior for the legacy
   full-pipeline auto path (no new denial/eviction surface). Part B may revisit.
4. **D1 test fixture**: store-level instead of full-harness (see §3).
5. **No silent fixes**: no other failures appeared in the sweep; nothing outside
   D1+D4 was touched (no dependency/UI/threading changes; the lease-table dual
   locking and store mutex are untouched).

## 7. End state (after the log commit)

```
git log --oneline -6
dd9f17b (before log commit) t917(p2): D4 suppression — ...
d99a93b t917(p2): D1 origins — ...
277dc0e t917(p2): plan correction per phase2-design §0 — ...
861e4f8 t917(p1): phase gate — determinism soak 100/100 ...
d2f981b t917(p1): Reviewer condition 1 — ...
45879f9 t917(p1): coexistence harness + intentional RED contract tests ...

git status --short
(empty — clean)
```

---

# T917 Phase 2 — Implementation Log, part B (D2 + D3)

Branch: `t917/coexistence-v3`. Scope: production changes D2 (wait-and-attach)
and D3 (defer-and-rescan) per `engineering/phase2-design.md` §2 + §3, plus one
documented enabling fix at the single-page commit boundary (deviation 3 below).
No dependency additions, no UI work, no new threads/channels; waiter entries are
removed on completion and the scheduler manualOutcomes map is capped at 32.

## B1. Files changed per commit

### Commit `48ddee1` — `t917(p2): D2 wait-and-attach`

Production:

| File | Change |
|---|---|
| `pipeline/batch/BatchCoordinatorInterfaces.kt` | `ChunkCompletionOutcome.Attached` / `AttachedUnresolved` / `Rejected` typed outcomes (§2.1) |
| `TranslationPipeline.kt` | `translateSinglePage` returns typed `SinglePageOutcome` (§1.4.1 completion); `runObservedSinglePageBoundary` — the Denied branch performs ZERO native/provider/render work and observes the owner's terminal commit through `store.state` (`attachToOwnerTerminal`, bounded by `ATTACH_TIMEOUT_MS`, cancellation-safe); scheduler-facing `manualOutcomes` map (cap 32) records per-page outcomes for the attach family; attach-family cancel guard |
| `scheduling/TranslationScheduler.kt` | `translatePage` surfaces the typed outcome; records into `manualOutcomes`; cancels recorded entries on `close()` |

Tests (harness only — the two latent harness bugs below were never reachable while the D2 tests were RED):

| File | Change |
|---|---|
| `coexistence/CoexistenceBarrier.kt` | Fix 1: `arrive()` no longer removes the gate before awaiting — a claimed gate stays listed with a `taken` AtomicBoolean claim, so `release()` can find and unpark a PARKED arrival (release-after-park was impossible before) |
| `coexistence/TranslationCoexistenceHarness.kt` | Fix 2: RENDER barrier arrival keys on `cleanedImageName.removeSuffix(".cleaned.jpg")` — the production `loadPersistedCleanedBitmap` seam receives the cleaned FILE NAME, not the page key |

### Commit `37c0902` — `t917(p2): D3 defer-and-rescan`

| File | Change |
|---|---|
| `pipeline/batch/BatchCoordinatorInterfaces.kt` | `BatchScheduleListener.ocrDeferred(pageKey, owner)` NOOP-default event (§3.1) |
| `store/PageStageLeaseTable.kt` | §3.2 waiter registry: `leaseReleaseWaiters: ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableDeferred<Unit>>>` + `awaitPageLeaseRelease(pageKey, timeoutMs)` (register under `synchronized(pageLeases)`, `withTimeoutOrNull` bounded, waiter removed in `finally`); `releasePageStageLease`/`cancelPageStageWork`/`releaseAllPageLeases` complete matching waiters inside the same monitor that removes the lease (wake ⇒ real release) |
| `ChapterTranslationStore.kt` | `awaitPageLeaseRelease` delegator (§3.2) |
| `pipeline/batch/BatchLaneWorkers.kt` | Denied-lease branch records the deferral into the shared `deferredPages` map AND emits `ocrDeferred`; externally-completed gate — an existing `hasRenderedResult` page that this pass deferred is forced to `BatchResumeGate.SKIP_ALL`, so the rescan never repeats the provider call another origin already paid |
| `pipeline/batch/SequentialBatchCoordinator.kt` | §3.3 in-pass rescan: ctor gains `awaitLeaseHandback`/`deferredPages` (both defaulted ⇒ existing coordinator tests untouched); COMPLETED-path-only rescan loop, original order, `RESCAN_MAX_ATTEMPTS = 2`, each page gated on handback, re-run via the same single-page `runOcr` + `processChunk(finalChunk = true)` machinery; stop paths never rescan |
| `pipeline/batch/BatchChapterTranslator.kt` | Wires listener + recorder + `awaitLeaseHandback = store.awaitPageLeaseRelease(it, LEASE_HANDBACK_WAIT_MS)` (`LEASE_HANDBACK_WAIT_MS = SINGLE_PAGE_TIMEOUT_MS`); `deferredPages` shared between worker and coordinator (per-batch lifetime) |
| `pipeline/SinglePageHttpRenderPhase.kt` | Enabling fix (deviation 3): lease-owned commit precondition refresh at the final `patchPage` |

### Commit (this one) — part B log

| File | Change |
|---|---|
| `engineering/phase2-implementation-log.md` | Appended this part B section |

## B2. Exact commands (Git Bash, `JAVA_HOME` = Android Studio JBR)

```
# after D2 (step 3) — D2.1 green at all three barriers, D3 still RED
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.D2ManualBatchInterleavingTest" --rerun
# after D3 (step 4) — former-RED set + neighbors
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.D3ReaderOwnedPageAcrossBatchTest" --rerun
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.D2ManualBatchInterleavingTest" --tests "eu.kanade.translation.coexistence.D3ReaderOwnedPageAcrossBatchTest" --tests "eu.kanade.translation.coexistence.SequentialBatchCoordinatorTest" --tests "eu.kanade.translation.ChapterTranslatorTerminalExitsTest" --rerun
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.ChapterTranslatorTerminalExitsTest" --rerun
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.SequentialBatchCoordinatorTest" --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest" --rerun
# verification sweep (step 5)
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --rerun
# determinism soak (5 consecutive forced reruns)
for i in 1 2 3 4 5; do ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*" --rerun; done
```

## B3. Green oracle evidence

- STEP 4: coexistence package 5/5 green — `D1OriginPriorityTest` 1/0,
  `D2ManualBatchInterleavingTest` 2/0 (both methods), `D3ReaderOwnedPageAcrossBatchTest`
  1/0, `NormalMangaIsolationTest` 1/0 (tests/failures from
  `TEST-*.xml`); `ChapterTranslatorTerminalExitsTest` 3/0;
  `SequentialBatchCoordinatorTest` and `TranslationManagerAutoArbitrationTest`
  BUILD SUCCESSFUL. D3's paid-call oracle held: `callsFor(p0) == 1 &&
  callsFor(p1) == 1` — the rescan took the externally-completed SKIP_ALL route,
  no second provider call for p1.
- STEP 5 sweep: `--tests "eu.kanade.translation.*"` ⇒ **161 test classes,
  1232 tests, 0 failures, 0 errors, 0 skipped** (aggregated from
  `app/build/test-results/testStandardDebugUnitTest/TEST-*.xml`); includes
  `NormalMangaIsolationTest` GREEN.
- Determinism: 5 consecutive forced `--rerun` coexistence runs — **5/5 exit 0
  (BUILD SUCCESSFUL), each run 5 tests / 0 failures / 0 errors** (last run's
  XMLs: D1 1+0, D2 2+0, D3 1+0, isolation 1+0).
- Test sources: `git status` shows ZERO modified files under
  `app/src/test/` in both part B commits — all former-RED tests flipped green
  on production changes plus the two part-A-harness repairs in 48ddee1 only.

## B4. Deviations / decisions (all within D2+D3 scope)

1. **D2.2 went green at step 3 (early, planned for step 4)** — documented in the
   48ddee1 commit message. Root cause: the two harness repairs let D2.2's
   manual complete its own render after release; the batch's engine setup is
   slow enough that p1's OCR ran after the manual released, so no denial was
   ever recorded and the manual's commit won the generation race naturally.
   D2.2's assertions therefore pass without the rescan. D2.1's REQUIRED green
   (wait-and-attach at all three barriers) was achieved in the same commit;
   D3 stayed RED with its message unchanged at step 3, as specified.
2. **Harness repairs committed with 48ddee1**: the one-shot gate
   release-after-park fix and the RENDER page-key fix are phase-1 harness bugs
   of exactly the class phase-1 §4.6 predicted (paths first executed when D2/D3
   flip green). They are test-infrastructure only; the RED contract tests were
   never modified.
3. **Generation/candidate fence vs the live lease (the load-bearing finding)**:
   the approved design (phase2-design.md) never models the store's run-level
   write fences — `grep -i generation` on the design returns nothing. In D3's
   scenario the batch's `beginGeneration("batch start ...")` plus its
   engine-setup candidate re-plan (page identity + dependency fingerprints for
   ALL pages, including the reader-owned one) fenced the parked manual's final
   commit: first `Rejected reason=generation expected=0 actual=1`, and after
   refreshing the generation, `Rejected reason=candidate dependency fingerprint
   changed`. The manual therefore never reached terminal — contradicting
   design §3.3's explicit assumption "a handback page is terminal from its own
   owner's commit (hasRenderedResult → doneCount)". Enabling fix (in
   37c0902, `SinglePageHttpRenderPhase`): when the boundary STILL OWNS the page
   lease at commit time, the precondition is re-derived from a fresh snapshot
   keeping the writer fences (generation, pageVersion, lease token, block
   fingerprints) and dropping the batch-run plan identity
   (`candidateGenerationId`/`dependencyFingerprint`/`artifactPageVersion`) —
   D1's rule is that the live lease holder is the page's exclusive writer, so
   run-level plan re-planning must not invalidate its in-flight result. Any
   real intervening write is still rejected (pageVersion/lease-token are
   re-checked). No store-level semantics were changed; direct `patchPage`
   callers and every existing generation-fence test are untouched.
   **Reviewer follow-up recommended**: confirm this lease-over-plan precedence
   is the intended long-term semantics (or whether batch engine setup should
   skip candidate re-planning for lease-held pages instead).
4. **Phase-2 handback terminal wait removed in favor of the design-pure lease
   wait**: an intermediate implementation also waited (bounded) for the owner's
   terminal state after the lease release. Instrumentation proved it can never
   fire under the fence (the manual commits BEFORE releasing, so once the lease
   is free the terminal state is already published) — the commit in 37c0902
   uses exactly the design §3.2 `awaitPageLeaseRelease` shape, with a comment
   recording why that is sufficient.
5. **D3's reconciliation stranding branch now unreachable for C-02** (as the
   design §3.3 argues): with the rescan in-line, `reconcile` runs only after
   every deferred page was re-offered; the deferred pages map never outlives
   the pass (per-batch `LinkedHashMap`, worker + coordinator + listener share
   one map), and waiter entries never outlive a release/timeout.

## B5. End state (before the log commit)

```
git log --oneline -8
37c0902 t917(p2): D3 defer-and-rescan — ocrDeferred event + lease-waiter registry +
in-pass rescan (COMPLETED path only) + externally-completed SKIP_ALL gate +
lease-owned commit precondition refresh; D3+D2+coexistence+neighbors GREEN,
sweep 1232/0, determinism 5/5
48ddee1 t917(p2): D2 wait-and-attach — SinglePageOutcome typed outcome
(Completed/Attached/AttachedUnresolved/Rejected), Denied->attachToOwnerTerminal
observes owner terminal commit within ATTACH_TIMEOUT_MS (zero
native/provider/render work, cancellation-safe), scheduler manualOutcomes
bounded map (cap 32) + attach-family cancel guard; harness repairs (latent,
never-executed-in-RED): barrier release unparks parked arrivals via taken-gate,
RENDER arrival keyed by page key not cleaned file name; D2.1 GREEN at all 3
barriers paid-calls==1, D3 RED unchanged, D2.2 GREEN (documented deviation),
isolation GREEN
a3b22e9 t917(p2): part A implementation log (D1+D4) — commits d99a93b + dd9f17b,
sweep 1232/3 identical reds, determinism 5/5, deviations recorded
dd9f17b t917(p2): D4 suppression — batch-retained guard (QUEUE|TRANSLATING|PAUSED)
on manager updateAutoWindow/requestAutoWindow early-return and
reconcileAutoWindow admissionGuard; TranslationManagerAutoArbitrationTest D4
GREEN (2/2), coexistence unchanged (3 RED identical msgs + 2 GREEN)
d99a93b t917(p2): D1 origins — PageWriteOrigin MANUAL/AUTO/BATCH (READER_ADHOC
deleted), MANUAL-evicts-AUTO lease rule with origin-checked release,
origin-typed acquisition call sites, two-vocabulary artifact stamp mapping;
D2:140/D3:70/Phase3Test compile fixes; new D1OriginPriorityTest GREEN
277dc0e t917(p2): plan correction per phase2-design §0 — ...
861e4f8 t917(p1): phase gate — determinism soak 100/100 ...
d2f981b t917(p1): Reviewer condition 1 — ...

git status --short
(only this log file modified)
```
