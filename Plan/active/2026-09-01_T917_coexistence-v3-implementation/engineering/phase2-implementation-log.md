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
