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
