# T917 Phase 3 — Implementation Log, part A (D5 + grace)

Branch: `t917/coexistence-v3`. Scope: D5 (glossary-aware translation reuse
gate) + the §4 patchPage candidate-grace alignment, per
`engineering/phase3-design.md` §1, §4, §5, §6 steps 1–3 (authoritative spec).
D9/D6 are part B and were NOT touched.

## 1. Files changed per commit

### Commit `e9cee3d` — `t917(p3): d5 tests` (RED)

| File | Change |
|---|---|
| `app/src/test/java/eu/kanade/translation/coexistence/D5GlossaryAwareReuseTest.kt` | NEW (370 lines) — design §5.1 tests (a)–(d) over the real planning seam: real `ChapterTranslationStore` built with an ARTIFACTS-authority `ChapterArtifactStore` (production fresh-chapter recipe: `AtomicChapterDocuments(FakeChapterDocumentIo())` + `ChapterArtifactLayout` + `loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L))` + LEGACY→ARTIFACTS authority flip), real `StageFingerprints`/`PageDecode.batchExpectedFingerprints`, real `BatchResumePlanner` constructed exactly as `BatchChapterTranslator` does. (a) matured glossary must re-translate stale AI-lane REUSE → asserts `p0` decision `RUN` + exactly one RUN; (b) repair pass + `stampBatchProvenance` re-fold → version stays 1, pass 2 all REUSE (convergence via `updateGlossary` equality gate); (c) no `updateGlossary` ever → `artifactManifest?.glossary == null`, gate OFF, all REUSE; (d) `isAi = false` → translation-stage decisions byte-identical (`REUSE`/`VALID_ARTIFACT`), zero TRANSLATION-stage RUN. Textless page fixture = OCR READY + empty blocks + `SKIPPED` (`isTextlessTerminal`) → TERMINAL_COMPLETE, exempt |

### Commit `241d9c7` — `t917(p3): d5 gate+stamp`

Production (design §1.2–§1.3; additive, nullable, gate OFF when null):

| File | Change |
|---|---|
| `app/src/main/java/eu/kanade/translation/model/PageTranslation.kt` | New comparable field `var translationGlossaryVersion: Int? = null` (after `layoutFingerprint`) with D5 KDoc; `resetTranslation()` also nulls it |
| `app/src/main/java/eu/kanade/translation/model/PageWorkPlan.kt` | `StageReasonCode.GLOSSARY_MATURED`; `BatchPlannerInput.currentGlossaryVersion: Int? = null` (null = gate OFF) |
| `app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt` | `decideStage` gains `currentGlossaryVersion`/`recordedGlossaryVersion` params; `planPage` threads `input.currentGlossaryVersion` + `page?.translationGlossaryVersion`; AI-lane-only rule: TRANSLATION REUSE downgrades to `RUN`/`GLOSSARY_MATURED` iff `current > (recorded ?: 0)` — placed **before** the dependency-blocking pass so LAYOUT re-plans consistently (WAIT_FOR_DEPENDENCY → render re-runs after repair) |
| `app/src/main/java/eu/kanade/translation/store/ChapterGlossaryStore.kt` | `internal fun currentGlossaryVersion(): Int?` — manifest glossary version iff authority is ARTIFACTS, else null (absence of glossary pointer / legacy authority = gate OFF) |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` | Delegating `internal fun currentGlossaryVersion() = glossaryStore.currentGlossaryVersion()` |
| `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt` | `buildBatchPagePlans`: `currentGlossaryVersion = if (isAi) store.currentGlossaryVersion() else null`; `stampBatchProvenance` TRANSLATION branch stamps `translationGlossaryVersion = store.currentGlossaryVersion() ?: 0` |
| `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt` | Single-page stamp **post-fold**: immediately after the contextual `store.updateGlossary(...)` block, `pageTranslation.translationGlossaryVersion = store.currentGlossaryVersion() ?: 0` (a pre-fold stamp would guarantee one wasted repair per manual page per session) |

Tests: none added (the RED suite from `e9cee3d` turns GREEN here, unchanged).

### Commit `58360c8` — `t917(p3): patchPage grace + regression test`

| File | Change |
|---|---|
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` | §4 fix: `patchPage`'s dependency clause now mirrors `publishLocked`'s candidate grace — `"candidate dependency fingerprint changed"` fires only when the **durable candidate exists** (`artifactManifest?.pages?.get(pageKey)?.candidate != null`) and its `dependencyFingerprint` differs from the expected one. Root cause documented: `snapshotLocked` fills `dependencyFingerprint` with the `StageFingerprints.pageSnapshot(page)` fallback while `candidateGenerationId` is null when no candidate exists, so a candidate-less page was falsely rejected. Fail-direction preserved: lease/generation/pageVersion fences stay armed; `pageWriteRejection` and `persistArtifactMutationLocked` already had the grace |
| `app/src/test/java/eu/kanade/translation/ChapterTranslationStorePatchPageGraceTest.kt` | NEW (191 lines) — §5.4 regression: real artifact-backed store; BATCH lease → `updatePageGuarded` seed → `cancelPageStageWork` (durable candidate cleared, record stays candidate-less) → MANUAL capture (candidateGenerationId null + fallback dependencyFingerprint non-null = the false-reject shape); test 1: `patchPage` Accepted while the MANUAL lease is held; test 2: after `releasePageStageLease`, same patch rejected via `"page lease token"` (fail-closed) |

### Commit (this one) — part A log

| File | Change |
|---|---|
| `engineering/phase3-implementation-log.md` | Created (this file) |

## 2. Exact commands (Git Bash, `JAVA_HOME` = Android Studio JBR)

```
# STEP 1 — RED capture
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.D5GlossaryAwareReuseTest" --rerun
# STEP 2 — gate+stamp, D5 turns GREEN; regression set (coexistence + model-layer neighbors)
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.model.*" --rerun
# STEP 3 — grace test + store neighbors
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.ChapterTranslationStorePatchPageGraceTest" --tests "eu.kanade.translation.coexistence.*" --rerun
# FINAL VERIFICATION — full sweep
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"
# neighbors
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.SequentialBatchCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.ChapterTranslatorTerminalExitsTest" --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest" --tests "eu.kanade.translation.coexistence.NormalMangaIsolationTest"
# determinism soak — 5 consecutive forced reruns, filter: coexistence.* + grace test
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.ChapterTranslationStorePatchPageGraceTest" --rerun  # x5
```

## 3. Red → green evidence excerpts

STEP 1 RED (commit `e9cee3d`, before any production change):
`D5GlossaryAwareReuseTest` → "4 tests completed, 1 failed". The one failure is
test (a), for the right reason — no gate exists, so REUSE stays where the
defect is:

```
D5 (a): matured glossary must re-translate the stale AI lane; ... expected:<RUN> but was:<REUSE>
```

(b)/(c)/(d) are green guards at RED (they assert behavior the pre-gate planner
already exhibits: convergence stamp, gate-off, lane isolation).

STEP 2 GREEN (commit `241d9c7`): same filter → `D5GlossaryAwareReuseTest`
4/4 passed; regression set `coexistence.* + model.*` → 101 tests, 0 failures,
0 errors.

STEP 3: pre-fix RED of the new grace test failed through the wrong clause of
the when-chain (`"candidate dependency fingerprint changed"` fires before the
lease clause), which is itself evidence of the §4 defect; post-fix
(`58360c8`) test 1 Accepted-while-lease-held and test 2 rejected via
`"page lease token"` → 2/2 GREEN; store + coexistence set 48 tests,
0 failures.

FINAL VERIFICATION:
- Full sweep `--tests "eu.kanade.translation.*"`: **1238 tests, 0 failures,
  0 errors** (= Phase-2's 1232 + 4 D5 + 2 grace).
- Neighbors GREEN: `SequentialBatchCoordinatorTest` 17/0,
  `ChapterTranslatorTerminalExitsTest` 3/0,
  `TranslationManagerAutoArbitrationTest` 2/0, `coexistence.*` all green.
- `NormalMangaIsolationTest` GREEN and UNTOUCHED (file unchanged since Phase-1
  commit `45879f9`).
- Determinism soak: 5 consecutive `--rerun` runs of
  `coexistence.* + ChapterTranslationStorePatchPageGraceTest` → identical
  all-green each run (11 tests, 0 failures, 0 errors; BUILD SUCCESSFUL in
  45–49 s per run).

## 4. Deviations (all documented; none change design semantics)

1. **No new harness shim.** Design §5.1 tests are realized at the real
   planner seam (real store/glossary/fingerprints/`BatchResumePlanner`)
   instead of full-graph + fake `ContextualTextTranslator`. Rationale: the
   harness's memory-only store has no artifact manifest → no glossary version
   → the gate would be dead code in the test; a full-graph run on an
   artifact-backed store would need JVM image-probe/BitmapFactory shims.
   The RUN-decision ↔ exactly-one-paid-call mapping is already pinned by the
   D2/D3 transport oracles, so the planner-tier scope loses nothing the
   design cared about.
2. **D5 fixture commits via `updatePageGuarded`, not `patchPage`.** The
   fixture itself hit the §4 false-reject (capture's `dependencyFingerprint`
   is the pageSnapshot fallback when no candidate exists). Isolating D5 from
   the §4 fix keeps each suite honest; the §4 behavior has its own suite.
3. **Fixture pages keep `renderStatus = PENDING`** (READY translation, live
   candidate) so the candidate stays live without coupling D5 to
   display-promotion.
4. **Store-level delegating `currentGlossaryVersion()`** added on
   `ChapterTranslationStore` (necessary wiring for the planner's
   `store.currentGlossaryVersion()` call; planner already holds the store,
   not the glossary store).
5. **Test (d)'s zero-paid-work assertion counts TRANSLATION-stage decisions
   only** — p0's LAYOUT stage legitimately plans RUN (free native work) under
   the PENDING-render fixture.

## 5. Repository state

`git log --oneline -8` (captured before this log commit):

```
58360c8 t917(p3): patchPage grace + regression test
241d9c7 t917(p3): d5 gate+stamp
e9cee3d t917(p3): d5 tests
66494b0 t917(p2): phase gate — soak 100/100 identical (forced --rerun, green set); PLAN D5/D6 corrections per phase3-design; phase3 design note
d1df615 t917(p2): review record + phase log — ACCEPT-WITH-NOTES; lease-over-plan precedence adopted as D1 corollary (Director-visible); clean-build sweep 1232/0; gate soak in progress
58afd34 t917(p2): part B implementation log (D2+D3) — commits 48ddee1 + 37c0902, sweep 1232/0, determinism 5/5 identical, generation/candidate-fence finding + lease-owned precondition refresh documented
37c0902 t917(p2): D3 defer-and-rescan
48ddee1 t917(p2): D2 wait-and-attach — SinglePageOutcome typed outcome (Completed/Attached/AttachedUnresolved/Rejected), Denied->attachToOwnerTerminal observes owner terminal commit within ATTACH_TIMEOUT_MS (zero native/provider/render work, cancellation-safe), scheduler manualOutcomes bounded map (cap 32) + attach-family cancel guard; harness repairs (latent, never-executed-in-RED): barrier release unparks parked arrivals via taken-gate, RENDER arrival keyed by page key not cleaned file name; D2.1 GREEN at all 3 barriers paid-calls==1, D3 RED unchanged, D2.2 GREEN (documented deviation), isolation GREEN
```

After this log commit: `git status --short` → clean (verified post-commit).
---

# T917 Phase 3 — Implementation Log, part B (D9 + D6)

Branch: `t917/coexistence-v3`. Scope: §3 D9 (durable attempt ledger + cap +
startup reconcile) and §2 D6 (foreground fairness reserve + typed pause +
drain-not-cancel), per `engineering/phase3-design.md` §2, §3, §5, §6 steps
4–9 (authoritative spec). Five commits, each compiling, fixed RED→GREEN order.

## 1. Files changed per commit

### Commit `be3c4d5` — `t917(p3): d9 tests` (RED)

| File | Change |
|---|---|
| `app/src/test/java/eu/kanade/translation/coexistence/D9AttemptLedgerTest.kt` | NEW (462 lines) — design §5 D9 suite over the real store + artifact authority (fresh-chapter recipe): (1) paid batch call writes a ledger entry BEFORE the provider call and startup reconcile consumes it (resolved entry → `entries` empty, no false crash-count); (2) three interrupted death cycles → chapter pauses (`retryCount` = 3 consecutive-unresolved), subsequent AUTO entries REFUSED (typed pause, zero provider billing) while explicit force still runs; (3) attach-waiting manual writes ZERO ledger entries (attach family never bills). All commit-2 seams invoked through an `invokeSuspending` reflection bridge whose missing-member path throws a named AssertionError — RED fails by assertion, never by timeout |
| `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt` | `Harness.create(storeOverride: ChapterTranslationStore? = null)` (:148) — lets the D9 test install an artifact-authority store (durable sidecars observable) without touching every other suite's default |

### Commit `0eaf7e1` — `t917(p3): d9 ledger+cap+reconcile` (GREEN)

Production (design §3; additive, fail-open writes, fail-closed cap):

| File | Change |
|---|---|
| `app/src/main/java/eu/kanade/translation/store/ChapterAttemptLedger.kt` | NEW (162 lines) — bounded durable ledger document (`attempts/ledger.json`): entry = pageKey + providerKeyHash + origin + generation + startedAt; `consecutiveUnresolved` counter; serialization + bounded merge |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` | `recordAttemptStart` (:364, returns admitted=false only when the AUTO consecutive cap refuses — then the caller must NOT bill), `resolveAttempt` (:378), `applyAttemptCapPause` (:397, durable PAUSE failure + `retryCount = consecutiveUnresolved`, description "D9 attempt cap reached…") |
| `app/src/main/java/eu/kanade/translation/TranslationManager.kt` | `reconcileAttemptLedgersForStartup` (:549, wired from the startup path :441) — bounded to the opened chapter set, never a library scan; resolves committed pages, counts consecutive-unresolved, applies the cap pause |
| `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactManifest.kt` | `AttemptOrigin { MANUAL, AUTO, BATCH }` (:275) + ledger carry fields |
| `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactLayout.kt` | attempts directory (:56) + `attemptLedgerFileName = "$attemptsDirectoryName/ledger.json"` (:98) |
| `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt` | ledger read/write/merge transactions (+16 lines) |
| `app/src/main/java/eu/kanade/translation/artifact/ArtifactContracts.kt` / `ArtifactRetention.kt` | contract surface; ledger sidecar exempt from artifact retention pruning |
| `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt` | standard per-page path: durable entry BEFORE the paid call (:1318, fail-open), `resolveAttempt` on any completed call (success or typed failure :1339/:1342); CancellationException rethrows WITHOUT resolving (process-death analogue) |
| `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt` | `runLedgerWrapped` (:232–:253) wraps the manual paid call: record → resolve-on-completion, CE-safe |
| `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt` | MANUAL attempt entry around the native+HTTP chain (+21 lines) |
| `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` | AUTO entry lifecycle: `recordAutoAttemptStart` (:361–:387, returns typed Paused when the cap refuses — no provider call billed), `runAutoAttempt`/`resolveAutoAttempt` (:394–:414; resolve on any completed call, unresolved ONLY on cancellation) |
| `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactLayoutTest.kt` | +2: attempts dir / ledger file name |
| `app/src/test/java/eu/kanade/translation/coexistence/D9AttemptLedgerTest.kt` | 3/3 GREEN (RED suite intent unchanged; bridge targets now exist) |

### Commit `8dd55e4` — `t917(p3): d6 tests` (RED)

| File | Change |
|---|---|
| `app/src/test/java/eu/kanade/translation/translator/ProviderRequestGovernorReservationTest.kt` | NEW (286 lines) — pure unit suite over the real `ProviderRequestGovernor` + `ProviderQuotaPolicy` with a virtual clock (delay advances `now`, no real waiting): test 1 THE reserve (interactive waiter parked → BACKGROUND effective limits shrink to `requests-1` / `tokens*(1-fraction)`; the window-deferred background call gets `nextEligibleRetryAtEpochMs = window start + windowMs` exactly; the second INTERACTIVE call still admits at the full window); tests 2–4 non-regression guards (interactive pair shares the full window; no revocation of already-billed background calls; single oversized request still admitted); test 5 fraction range guard `0.0 < f ≤ 1.0` via a reflection ctor bridge that names the missing seam at RED |
| `app/src/test/java/eu/kanade/translation/coexistence/D6ForegroundFairnessTest.kt` | NEW (267 lines) — full-graph harness: real scheduler + pipeline + `GovernedTransport` (test `TextTranslator` routing `executeValue` through the real governor with per-page costs); choreography: batch page p0 admitted under BACKGROUND → manual tap q0 admitted under INTERACTIVE while p0 holds the window → tap q1 exhausts the window → scheduler `manualOutcomes["20:q1"]` must be the typed Paused variant carrying the governor retry epoch ∈ [p0 admission wall time, +62 s]; batch joins cleanly; zero retries of the paid call |
| `app/src/test/java/eu/kanade/translation/coexistence/D6DrainNotCancelTest.kt` | NEW (453 lines) — real `RollingAutoCoordinator` + real artifact store: (1) cancel the window mid-call → release the gate → the call must DRAIN: finish, commit translation-terminal state, consume its D9 entry, `cancelledCalls == 0`, exactly one paid call; (2) drain grace companion bound = 90 000 ms; (3) grace expiry (300 ms, gate never released) cancels the parked call cleanly (cancellation-class) leaving the D9 entry unresolved ×1, no re-issue. 7-param ctor bridge names the missing `drainGraceMs` seam at RED |
| `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt` | `nativeStageDone` / `transportStarted` lane-serialization maps made `internal` so tests can signal fake engine lane handoffs |

### Commit `dd3364e` — `t917(p3): d6 reservation+typing+drain` (GREEN)

Production (design §2):

| File | Change |
|---|---|
| `app/src/main/java/eu/kanade/translation/translator/ProviderRequestGovernor.kt` | §2.1: `ProviderQuotaPolicy.interactiveTokenReserveFraction: Double = 0.2` (:85) with range guard (:97–:98); `evaluate` (:452–:462): while the bucket holds at least one INTERACTIVE waiter, a BACKGROUND caller sees `effectiveRequestsPerMinute = requests-1` and `effectiveTokensPerMinute = floor(tokens*(1-fraction)) >= 0`; `tokenLimit = maxOf(effectiveTokensPerMinute, tokenCost)` so a single oversized request is still admittable; INTERACTIVE always evaluates against the full window; `nextEligibleAt` token-limit parameter widened to Long; no revocation of already-billed reservations anywhere |
| `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` | §2.3: `drainGraceMs: Long = PROVIDER_DRAIN_GRACE_MS` ctor param LAST (:81); public companion const `PROVIDER_DRAIN_GRACE_MS = 90_000L` (:1077); `consumeTranslations` wraps translate+commit in `withContext(NonCancellable) { withTimeout(drainGraceMs) { ... } }` (:441–:459) — timeout INNER so expiry is cancellation-class (D9 entry stays unresolved), completion path still generation-guarded so a drained result commits even though the window is gone; §2.2b verified pre-existing: the `ChunkCompletionOutcome.Paused` branch already feeds `deferTranslationRetry`/`pausedTranslations` (:477–:488) |
| `app/src/main/java/eu/kanade/translation/scheduling/TranslationExecutor.kt` | §2.2a: `SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs: Long?)` (:144) — a typed deferral is neither failure nor completion |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | §2.2a: `translateSinglePage` captures the HTTP+render phase's typed `ChunkCompletionOutcome` (:477) and maps `Paused` to `SinglePageOutcome.Paused(phaseOutcome.nextEligibleRetryAtEpochMs)` (:497–:505); the phase already typed governor deferrals (`ProviderRequestPausedException` is a retryable `ProviderFailureException`) as `Paused`, but the old code discarded the phase result and returned `Completed` — the swallow the fairness test exposed. `TranslationScheduler.translatePage`'s `manualOutcomes[jobKey] = outcome` (:633) then lands the typed pause with no scheduler change (its `when` is else-guarded) |
| `app/src/test/java/eu/kanade/translation/coexistence/D6DrainNotCancelTest.kt` | Fixture correction (see deviations #2): drained-call commit shape = translation-terminal (ocr/translation/inpaint READY + cleaned metadata), mirroring the harness manual publish shim; oracle `renderStatus READY` → translation-terminal statuses |
| `app/src/test/java/eu/kanade/translation/translator/ProviderRequestGovernorReservationTest.kt` | Ctor bridge unwraps `InvocationTargetException` so the fraction range guard surfaces as the real `IllegalArgumentException` for `shouldThrow` |

Tests: no behavioral test changes (commit-3 RED suites turn GREEN; only the
two fixture corrections above).

## 2. Exact commands (Git Bash, `JAVA_HOME` = Android Studio JBR)

```
# STEP 4 — D9 RED
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.D9AttemptLedgerTest" --rerun
# STEP 5 — D9 GREEN + artifact neighbors
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.D9AttemptLedgerTest" --tests "eu.kanade.translation.artifact.*" --rerun
# STEP 6 — D6 RED (three suites)
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.ProviderRequestGovernorReservationTest" --tests "eu.kanade.translation.coexistence.D6ForegroundFairnessTest" --tests "eu.kanade.translation.coexistence.D6DrainNotCancelTest" --rerun
# STEP 7 — D6 GREEN + governor/scheduler neighbors (existing expectations untouched)
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.ProviderRequestGovernorReservationTest" --tests "eu.kanade.translation.translator.ProviderRequestGovernorTest" --tests "eu.kanade.translation.coexistence.D6ForegroundFairnessTest" --tests "eu.kanade.translation.coexistence.D6DrainNotCancelTest" --tests "eu.kanade.translation.scheduling.RollingAutoCoordinatorTest" --tests "eu.kanade.translation.scheduling.ChapterTranslatorTerminalExitsTest" --tests "eu.kanade.translation.scheduling.SequentialBatchCoordinatorTest" --tests "eu.kanade.translation.scheduling.TranslationManagerAutoArbitrationTest" --tests "eu.kanade.translation.coexistence.NormalMangaIsolationTest" --rerun
# STEP 8 — full sweep
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --rerun
# STEP 9 — determinism: 5 consecutive forced --rerun rounds
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.translator.ProviderRequestGovernorReservationTest" --rerun  # x5
```

## 3. RED to green evidence excerpts

D9 STEP 4 RED (commit `be3c4d5`, before any production change): every failure
was the bridge's named assertion — the ledger/cap/reconcile seams did not
exist, which IS the §3 defect. Zero timeouts:

```
T917 D9 RED defect: recordAttemptStart is not implemented — the durable attempt ledger seam is missing
T917 D9 RED defect: applyAttemptCapPause is not implemented — the durable attempt ledger seam is missing
T917 D9 RED defect: reconcileAttemptLedgersForStartup is not implemented — the durable attempt ledger seam is missing
```

(The third test, attach-waiting manual writes zero entries, is a green guard:
the attach family never reaches a record call in the pre-D9 code either.)

D6 STEP 6 RED (commit `8dd55e4`, before any §2 change): 9 tests, 6 failed —
each failure naming its defect; the 3 non-regression guards green by design.
Literal messages:

```
ProviderRequestGovernorReservationTest
  T917 D6 RED defect: interactiveTokenReserveFraction is not configurable — the §2.1 policy reserve seam is missing
  (x2: the reserve test + the range-guard test)
  guards: interactive full window / no revocation / single oversized cost -> PASS (pre-reserve shapes pinned)

D6ForegroundFairnessTest
  T917 D6 §2.2a defect: the manual outcome must be the typed Paused variant — expected:<Paused> but was:<Completed>
  (reveals the real §2.2a defect: the pipeline SWALLOWS the typed deferral and reports Completed into manualOutcomes)

D6DrainNotCancelTest
  T917 D6 RED defect: drainGraceMs is not configurable — the §2.3 bounded drain-not-cancel seam is missing from RollingAutoCoordinator
  T917 D6 RED defect: PROVIDER_DRAIN_GRACE_MS is missing — the §2.3 drain bound companion constant does not exist
  T917 D6 §2.3 defect: the drained call must commit READY even though the window is gone — RED strands the page because cancel() tears the call down mid-flight (expected:<READY> but was:<PENDING>)
```

STEP 7 GREEN (commit `dd3364e`): all three D6 suites GREEN; neighbors GREEN
(`ProviderRequestGovernorTest`, `RollingAutoCoordinatorTest`,
`ChapterTranslatorTerminalExitsTest`, `SequentialBatchCoordinatorTest`,
`TranslationManagerAutoArbitrationTest`, `NormalMangaIsolationTest`) with zero
changes to existing test expectations — the §2.1 reserve is purely additive to
the admission predicate, so no existing governor behavior test conflicted
(no STOP condition triggered).

STEP 8 FULL SWEEP: `eu.kanade.translation.*` → **1250 tests, 0 failures,
0 errors, 0 skipped** (= part A's 1238 + 3 D9 + 9 D6);
`NormalMangaIsolationTest` GREEN and UNTOUCHED.

STEP 9 DETERMINISM: 5 consecutive forced `--rerun` rounds of
`coexistence.* + ProviderRequestGovernorReservationTest` → identical each run:
**21 tests, 0 failed** (JUnit XML aggregates compared across runs), BUILD
SUCCESSFUL 42 s–1 m 43 s per run. No sleeps/polling anywhere; all waits are
barrier/deferred/event-driven, and the D6 unit choreography runs on a virtual
clock.

## 4. Deviations (all documented; none change design semantics)

1. **D6 fairness fixture is single-page-batch + empty-start manual store.**
   The full-graph harness has a latent lane-serialization deadlock if a second
   batch page's inpaint parks on `transportStarted[p0]` while p0's translate
   turn queues behind the native lane (only the ~45 s ONNX phase timeout would
   break it). The choreography therefore uses ONE batch page and an
   empty-start manual store (pre-registered PENDING pages make the manual path
   resume-skip). Manual pages also can never reach `renderStatus READY` on
   this fixture (fake cleaned image has no decodable bytes), so the fairness
   oracle is the paid-call completion + typed outcome, not display promotion —
   the same documented fixture deviation family as part A note §1.2.
2. **Drain-test commit shape is translation-terminal, not `renderStatus
   READY`.** Promotion to render READY validates the cleaned base file through
   `BitmapFactoryCleanedImageProbe` (`ChapterArtifactStore.displayBaseIsValid`,
   :889–:900), which cannot decode on the JVM. The DrainExecutor commits the
   exact shape the harness manual publish shim commits (ocr/translation/
   inpaint READY + cleaned metadata; no render promotion). The §2.3 oracle
   set — drained call finishes with `cancelledCalls == 0`, D9 entry consumed,
   exactly one paid call, PENDING untouched on grace expiry — is unchanged in
   strength. Diagnostic-run evidence: the drain shield worked on the first
   GREEN build; the only failure was the fixture's own
   `ARTIFACT_PUBLICATION_FAILED` rejection, not a cancellation.
3. **`companion object` on `RollingAutoCoordinator` made public** to expose
   `PROVIDER_DRAIN_GRACE_MS` (the test reads it via `getField`); the two
   pre-existing constants inside it became explicitly `private const`.
   `drainGraceMs` is the LAST constructor parameter with a production default,
   so every existing call site (including the scheduler's named-arg call)
   compiles unchanged.
4. **Two committed RED test files received fixture-only corrections in commit
   `dd3364e`** (deviations #2 + the bridge unwrap). RED was demonstrated and
   captured against the committed RED versions; no assertion semantics
   changed — the drain oracle's status assertions and the fraction-guard
   exception surfacing were aligned with what the production seam actually
   guarantees on a JVM fixture.
5. **§2.2b is verification-only.** `pausedTranslations` +
   `deferTranslationRetry` (design §2.2b) already existed in
   `RollingAutoCoordinator.consumeTranslations`; the D6 GREEN commit adds a
   pointer comment, no behavioral change.

## 5. Repository state

`git log --oneline -5` (captured before this log commit):

```
dd3364e t917(p3): d6 reservation+typing+drain
8dd55e4 t917(p3): d6 tests
0eaf7e1 t917(p3): d9 ledger+cap+reconcile
be3c4d5 t917(p3): d9 tests
58360c8 t917(p3): patchPage grace + regression test
```

Exit criteria: per-commit RED-before-its-GREEN verified; final sweep 1250/0;
`NormalMangaIsolationTest` green + untouched; neighbors green with unchanged
expectations; determinism 5/5 identical; no sleeps/polling; JUnit 5 +
kotest-assertions + mockk only (no Robolectric).
