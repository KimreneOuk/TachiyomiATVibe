# T924 Wave 2 — Independent Review (stages S2/WP8, S3/WP4, S4 planners, feature flags)

Reviewer: independent Reviewer (did not author any reviewed diff)
Date: 2026-09-05
Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`
Range: `7c301bc..HEAD` (4 commits: 7c301bc FF accessors, 1d3fcb1 S2/WP8, 73f6933 S4 planners, 5427b35 S3/WP4)
All paths below are relative to `app/src/main/java/eu/kanade/translation/` unless noted.
Test paths relative to `app/src/test/java/eu/kanade/translation/`.

---

## Verdict

## ACCEPT-WITH-FIXES

All four slices conform to their contracts; the D1 fix landed correctly; no BLOCKER, no MAJOR finding. Acceptance is conditioned on the three MINOR fixes/constraints in F1–F3 being scheduled before the consuming stages (S5/WP5, WP9, any flag-surface widening). Stage 1 substrate is consumed, not re-implemented, and the consumption points checked out.

---

## 1. Findings

### F1 — MINOR (defect-by-omission, later-stage trap): `decideResume` is dead code in production

`pipeline/batch/ChapterProfileBatchCoordinator.kt:467` defines `decideResume(record, currentFlagOn)`; `grep -rn "decideResume" app/src/main/java/` returns exactly one hit — the definition. Production flag-off enforcement is currently only the implicit one: dispatch reads the current flag and constructs `SequentialBatchCoordinator` (`pipeline/batch/BatchChapterTranslator.kt:632-680`). That is correct for every state this slice can produce (FF-01e.3 / FF-01e.2b), but the `TreatAsFinished` case (FF-01e.2a, `ChapterProfileBatchCoordinator.kt:471-472`, state `COMPLETE`) cannot be exercised by production until a later stage publishes `COMPLETE` runs — and nothing forces that stage to call `decideResume`.

Fix (scheduling, not code-now): the stage that first publishes `ChapterRunState.COMPLETE` must wire `decideResume` into the resume path and add the dispatch-level test. Record this obligation in the S5 kickoff. Severity stays MINOR because no current code path can reach the unsafe state (preflight never publishes COMPLETE — `ChapterProfileBatchCoordinator.kt:230-270` ends at `OCR_PREFLIGHT`/PAUSED).

### F2 — MINOR (design limitation): second canonical hasher (`PlannerFingerprints`) duplicates the `StageFingerprints` encoding core

`translator/contextual/GlobalEnvelopePlanner.kt:411-435` (`PlannerFingerprints.encode/appendField`) is byte-identical in discipline to `artifact/StageFingerprints.kt` `fingerprintIndexed`/`appendField` (verified side-by-side: `len:value|` fields, `[$index]` list elements, `<null>` literal, SHA-256 lowercase hex, `%02x`). Deviation 3 was honest and the ownership reason (artifact/** read-only for that slice) was real. But two encoding cores can drift, and `EnvelopePlan.planInputFingerprint` (computed via `PlannerFingerprints`, `GlobalEnvelopePlanner.kt:364-383`) is a persisted DTO field: a future discipline change in one core silently forks fingerprint spaces.

Fix (before WP5 persists analysis artifacts): move `PlannerFingerpoints`' two composite builders into `StageFingerprints` (existing-file edit, additive), or add an explicit encoder-version constant hashed into `planInputFingerprint`. Until then the golden tests (`translator/contextual/GlobalEnvelopePlannerGoldenTest.kt:377-391`, pinned fingerprint `5643a00c…df8`) bound the drift. RULING on deviation 3: ratified as a slice-scoped exception, with the consolidation owed.

### F3 — MINOR (product risk, time-boxed): non-AI translators run preflight-only under FF-01 (risk R3)

Dispatch consults FF-01 alone (`BatchChapterTranslator.kt:637-639`); a Batch run with a non-AI engine and FF-01 ON stops after preflight with PAUSED (`ChapterProfileBatchCoordinator.kt:265-270`) and never translates. With the flag default-OFF and no UI (`T924FeatureFlagsTest.kt:20-25`; grep for `translation_batch_profile_pipeline` in `presentation/`/`ui/`: zero hits — FF-01g holds), evaluation traffic is explicit, so this is acceptable **for now only**.

Fix (before the flag reaches any broader audience, at latest before S5 ships translation stages): either gate dispatch on `translationEngineCategory == AI_MODEL` parity with the legacy contextual lane, or make the flagged coordinator fall through to the legacy coordinator when the frozen config's provider cannot serve the later stages. Track as a Stage-5 kickoff item.

### F4 — NOTE: contributing-corpus fingerprint payload order is a cross-stage convention, not enforced by code

`AnalysisChunkPlanner.kt:192-198` and `OcrCorpusManifest.kt:108-117` hash contributing sets with `naturalOrderProven = true` over payload order (core-then-context; `AnalysisChunkPlanner.kt:192` `window.corePages + window.overlapPages`). `StageFingerprints.ocrCorpusFingerprint` (`artifact/StageFingerprints.kt:251-272`) hashes payload order verbatim when `naturalOrderProven=true`. Same set in another order gives a different fingerprint. Deviation 9 is ratified (it matches the T924-AP-03 request payload order and is pinned by the oracle test), but WP5 request builders MUST reproduce core-then-context order. Record the convention in the schemas contract (§1.3 note) at next touch.

### F5 — NOTE: `rehydrate` loss is only detectable by count comparison

`rendering/LayoutDrawPlanProjection.kt:140-154` silently skips unresolvable plan blocks (correct FF-02b fail-safe), but the `List<BlockLayout>` return does not distinguish "all resolved" from "lossy"; the caller (WP9) must compare sizes against `plan.blocks` (or `validationError`) before trusting hydration. Recommend an explicit result type or a documented size-check contract in the WP9 handoff. Not a defect in this slice.

### F6 — NOTE: flag frozen twice (field + counter), consistent

D1 fix verified end-to-end: `artifact/ChapterRunRecord.kt` adds `val flagProfilePipeline: Boolean? = null` (additive-optional per T924-SC-14, schemas contract `contracts-schemas-fingerprints.md:304` — "new optional field with default … does NOT bump schemaVersion"); dispatch passes it (`BatchChapterTranslator.kt:651` `flagProfilePipeline = profilePipelineEnabled`); it participates in `frozenRunConfigFingerprint` by construction (`ChapterProfileBatchCoordinator.kt:515-517` hashes `ArtifactDocumentJson.encodeToString(config)` with `encodeDefaults=true`); counter key retained (`ChapterProfileBatchCoordinator.kt:123`, `COUNTER_FLAG` :440) for pre-field records. Test asserts both (`pipeline/batch/OcrPreflightFlagOffMidRunTest.kt:191-192`). ST-03.1 not violated: a flag flip between runs changes the fingerprint, so runId selection (`ChapterProfileBatchCoordinator.kt:106-109`) starts a new run id — exactly ST-03.1's mismatch rule; per-page checkpoint reuse is keyed by source sha, not runId (`:306-317`), so a flag flip costs zero re-OCR. The pre-field→post-field fingerprint change also costs only a new runId, checkpoints survive.

### F7 — NOTE: analysis-chunk golden fixture file missing (gate 2.1 partial)

Gate 2.1 names `AnalysisChunkPlannerGoldenTest` over `app/src/test/resources/t924/golden/*.json`. Delivered tests are thorough (17 tests incl. 100-seed permutation invariance) but inline; only `t924/golden/envelope-plan-small.json` exists. Owed before the Stage-2 exit report is written (WP3 gate rows).

### F8 — NOTE: RUN_SNAPSHOT/OCR_PLAN publications are best-effort WARN-only

`ChapterProfileBatchCoordinator.kt:342-366` — a rejected run-record publication never fails the pass. Consistent with ST-06 (per-page checkpoints authoritative, counters advisory) and tested (`OcrPreflightFlagOffMidRunTest.kt:270-276` no-auto-start). A run whose very first publication fails simply has no record; `decideResume(null, …)` handles it. No action.

### F9 — NOTE: manifest byte-equality assertion in the flag-off test is trivially true

`OcrPreflightFlagOffMidRunTest.kt:194-207` asserts manifest equality across `decideResume`, which is a pure function of (record, Boolean) — it cannot write. Harmless documentation-as-test; the real guarantee is the purity of the signature. No action.

### F10 — NOTE: `%02x` default-locale hex formatting

`DrawPlanFingerprint.kt:102`, `PlannerFingerprints.kt:418`, `ChapterProfileBatchCoordinator.kt:539` all use `"%02x".format(byte)`, consistent with the pre-existing `StageFingerprints.sha256Hex`. Java `%x` is not locale-sensitive (only `%d` localizes digits); matches the S1 core. Deviation 8 (wp8) ratified.

---

## 2. Mandate item 1 — flag conformance (all VERIFIED against code)

| Clause | Evidence | Verdict |
|---|---|---|
| FF-00 (real mechanism) | `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt` diff 7c301bc: two `getBoolean` accessors, keys `translation_batch_profile_pipeline` / `translation_batch_persisted_layout`, both default `false`; existing DI module unchanged | PASS |
| FF-01a exactly-one dispatch | `BatchChapterTranslator.kt:632` local `runBatchPass1`, single read `:637-639`, `dispatchKind` mapping `ChapterProfileBatchCoordinator.kt:453-458`; single call site `:687`; `runBatchPass1` called once per pass (grep: 2 hits = def + call) | PASS |
| FF-01b OFF = byte-for-byte legacy | OFF branch `BatchChapterTranslator.kt:661-680` vs pre-change construction (`git show 7c301bc` :622-638): identical argument list incl. the verbatim T917 D3 comment and `deferredPages`; only difference is receiver form (`coordinator.runPass1(...)` → `.runPass1(...)` on the constructed instance) | PASS |
| FF-01d flag frozen | See F6 — field optional, in fingerprint, counter retained, flip = new runId, reuse unaffected | PASS |
| FF-01e case coverage | `decideResume` `ChapterProfileBatchCoordinator.kt:470-475`: OFF+COMPLETE=TreatAsFinished, OFF+else (incl. null record)=DropToLegacy, ON=RunFlaggedPath; tests `OcrPreflightFlagOffMidRunTest` cases 2–4. Production wiring debt = F1 | PASS with F1 |
| FF-10 no auto-start | Coordinator executes only via `runPass1` from admission; decision takes no queue input (`:467-469`); test `OcrPreflightFlagOffMidRunTest.kt:248-277`: zero OCR, `activeRun` null, no checkpoints, no lease | PASS |
| FF-01g no UI | grep `translation_batch_profile_pipeline` / `translationBatchProfilePipeline` in `eu/kanade/presentation/`, `eu/kanade/tachiyomi/ui/`: zero hits | PASS |
| FF-02 default OFF, unused by WP8 | Accessor exists (`TranslationPreferences.kt` :255 region); grep `translationBatchPersistedLayout` outside preferences: zero hits — WP8 consumes nothing flag-gated, dispatch belongs to WP9 as scoped | PASS |
| FF-02 independence | WP8 adds no FF-01 coupling; `DrawPlanFingerprint.kt` reads no preferences | PASS |

---

## 3. Mandate item 2 — safety (all VERIFIED)

- **No OCR parallelization / ST-06 serial loop**: `ChapterProfileBatchCoordinator.runPass1` `:139-228` is a plain `for` loop; grep for `async`/`launch`/`runBlocking` in the file: zero. `maxInFlight == 1` asserted with an AtomicInteger peak tracker in `pipeline/batch/OcrPreflightCoordinatorTest.kt:141-150,226`.
- **One decoded page**: native handoff released in page `finally` `:220-227` before the next iteration; `yield()` `:144` between pages (reader priority, suspension not sleep).
- **No inpaint during preflight**: `runInpaintStage` never called by the coordinator (grep: zero hits in the file); only `runOcrStage` `:165`.
- **Lease release strictly after checkpoint (TX-06)**: `checkpointPage` `:274-299` runs inside `try`; `releaseBatchLease` in `finally` `:225` — after the checkpoint attempt in both Committed and Rejected paths; `releaseNativeHandoff` `:224` precedes it.
- **Committed display never touched (TX-07)**: only `checkpointOcr(CLOSE)` (`:286-298`, `OcrCheckpointMode.CLOSE` :296) — the S1 transaction preserving committed display; test asserts `committed == null` on every touched page record and `isTranslationDisplayReady == false` (`OcrPreflightCoordinatorTest`, happy path; `OcrPreflightFlagOffMidRunTest.kt:208-210`).
- **No completion redefinition**: terminal is `BatchPass1Status.PAUSED` + STOP_REASON (`:265-270`); `BatchPass1Status` enum pre-exists T924 (`pipeline/batch/BatchCoordinatorInterfaces.kt:135-147`, last touched `37c0902` t917) and `BatchProgressReconciler.reconcilePaused` (`:122`) already handles PAUSED — stopped-not-finished is the legacy pause projection, not a new semantic.
- **TextLayoutPlanner.kt zero diff**: `git diff 7c301bc..HEAD -- rendering/TextLayoutPlanner.kt` = empty (0 lines). Stronger than the allowed additive edits.
- **SequentialBatchCoordinator.kt untouched**: same check = empty.
- **Legacy path cost**: one `translationPreferences.translationBatchProfilePipeline().get()` + one `when` (`BatchChapterTranslator.kt:637-639,680`). Nothing else on the OFF path.
- **Scope audit**: `git diff --name-status 7c301bc..HEAD` = 19 files (6 main modified/added, 1 domain, 12 tests/fixtures), each inside its slice's declared set; per-commit file lists match the three reports; no file outside the declared sets.

---

## 4. Mandate item 3 — planners

- **Exact-once coverage**: `GlobalEnvelopePlanner.verifyCoverage` (`GlobalEnvelopePlanner.kt:313-337`) independently checks total count, per-block multiplicity, missing, canonical block order (list equality with the expected concatenation), and canonical page order. Called before every Success (`:292`). Proof structure sound: greedy whole-page fold `:216-235` plus independent re-verification — not self-certifying.
- **Page atomicity**: oversized single page rejected whole before accumulation (`:197-211`); accumulation only ever adds whole pages; per-envelope single ownership + reading-order contiguity property-tested (`GlobalEnvelopePlannerGoldenTest` atomicity test :106-130 area).
- **Deterministic tie-breaks / permutation invariance — real, not tautological**: canonical order `naturalPageIndex ?: MAX_VALUE then pageKey` (`:167-170`, same in `AnalysisChunkPlanner.kt:159-162`); duplicate pageKey dedupe by min under a total comparator (`OcrCorpusManifest.kt:134-151`, deviation 10 — fixed after the property test caught input-order leakage; the fix is the right one); 100 seeded LCG permutation loops assert full-plan equality AND byte-size equality (`GlobalEnvelopePlannerGoldenTest.kt:347-366`). Input is genuinely shuffled per seed.
- **Budgets are code constants, not flags**: `EnvelopePlannerPolicy` defaults 32/8/16384/8192 (`:44-50`), `AnalysisChunkPolicy` 16/1/512/16384 (`AnalysisChunkPlanner.kt:28-47`) — constructor defaults, no preference reads anywhere in `translator/contextual/` planners; policy fingerprinted as data (`:66-75`), consistent with the non-flags clause and invalidation matrix row 7 (policy-only change keeps translations: policy lives in `planInputFingerprint`, not in any translation-identity fingerprint).
- **planFingerprint hashing view mirrors SC-10**: `:285-288` — `plan.copy(planFingerprint = "", createdAtEpochMs = 0L)`, canonical re-encode via shared `ArtifactDocumentJson`, SHA-256. Self-reference blanked, operational field zeroed, FP-01 honored. `planInputFingerprint` covers corpus fp + planner version + policy fp + ordered pending block set (`:364-383`).
- **Golden fixture genuinely byte-pinned**: `app/src/test/resources/t924/golden/envelope-plan-small.json` (1498 bytes); test reads resource bytes and asserts UTF-8 byte equality against the canonical encode PLUS the literal fingerprint `5643a00c7f98e158e61246c6ad7413f933ff1eaade91b3efa06f45e6b0339df8` (`GlobalEnvelopePlannerGoldenTest.kt:368-391`). Fixture content spot-checked: scene close before p3 with `crossesSceneBoundary=false` on the post-break envelope (p3 is group-first, `drop(1)` semantics `:255`) — correct.
- **Chunk planner**: overlap = last-N core pages of previous window, shed farthest-first under the token cap (`AnalysisChunkPlanner.kt:237-249`); single oversized page still forms its own chunk (caps bound windows, never split pages, `:147-151`); core sets partition the corpus exactly once (property-tested); `evidenceResolves` (`:114-118`) implements the pure V1/V9 subset incl. the `p12_b4`-under-`p13` prefix guard; V8 excerpt-hash recompute and status persistence correctly left to WP5.
- **OcrCorpusManifest**: never-guess order rule (`:162-166`), negative/out-of-range/duplicate index handling (`:154-196`), `isComplete` composite (`:199-204`), corpus fingerprint oracle equality (`:206-211`). Note: `isComplete` does not require `trustedPageCount == presentPageCount`; trusted/untrusted reported separately by design — later stages demand re-validation. Acceptable as documented (`:33-39`).

---

## 5. Mandate item 4 — deviations

### WP4 (D1–D4, I1)

| Dev | Ruling | Basis |
|---|---|---|
| D1 flag as counter | SUPERSEDED — orchestrator's field fix landed in 5427b35 and is verified (F6). Counter retained for pre-field records: ratified | `ChapterRunRecord.kt` diff; `BatchChapterTranslator.kt:651`; `OcrPreflightFlagOffMidRunTest.kt:191-192` |
| D2 state=OCR_PREFLIGHT + counters instead of activePhase/diagnostic | RATIFIED. `ChapterRunState.OCR_PREFLIGHT` exists in the S1 enum (`ChapterRunRecord.kt:19-22` region); no DTO field invented; resume reads checkpoints not record state (ST-01.4) | `ChapterProfileBatchCoordinator.kt:246-260` |
| D3 collapsed SOURCE_VALIDATION | RATIFIED. Source fingerprints computed pre-construction by the shell (`BatchChapterTranslator.kt:653-655`), `orderedSourceDigest` embedded at RUN_SNAPSHOT (`:128-131`); consistent with ST-04 "per-page identities ride the existing source field" | report §6; code |
| D4 `MODEL_HASH_UNSPECIFIED = "unspecified"` | RATIFIED with note: when real engine identity lands, frozenConfig changes, fingerprint changes, new runId (ST-03.1 correct), checkpoint reuse unaffected (content-keyed). Must land before Stage-4 analysis fingerprinting freezes model identity into reuse decisions | `ChapterProfileBatchCoordinator.kt:479-506` |
| I1 unknown source re-OCR | RATIFIED. `:315` — null or mismatched current sha fails the reuse check (fail-closed); UNKNOWN means the hash stage already failed | code |

### Planners (1–11)

1 Placement `translator/contextual/` — RATIFIED (WP3 entry points; outside parallel-owned trees).
2 Contributing fingerprints over caller-supplied (pageKey, fp) pairs — RATIFIED (S1 substrate boundary; manifest shows the assembly pattern).
3 Planner-local `PlannerFingerprints` — RATIFIED AS EXCEPTION, consolidation owed (F2).
4 planFingerprint hashing view — RATIFIED (SC-10 mirror verified, §4 above).
5 Serialization cap = 256 KiB × contributing pages — RATIFIED. Reading matches the coordinator directive ("per contributing page"); total-plan budget for a 200-page chapter ≈ 51 MB upper bound, practically far under; pure in-memory check (`:295-304`), no IO.
6 Zero-block pages excluded from chunks (reported) — RATIFIED (`AnalysisChunkPlanner.kt:184-185`).
7 sceneRefs/profileSubsetRefs empty — RATIFIED (frozen-profile scenes + relevant-subset matcher are separate S4/WP5 scope; `crossesSceneBoundary` already computed generally, `:255`).
8 Chunk output planner-shaped (`PlannedAnalysisChunk`), not the persisted DTO — RATIFIED; WP5 must map into `AnalysisChunkResult` with status/excerpt-hash validation (test-gap item 5).
9 Contributing-set order core-then-context with `naturalOrderProven=true` — RATIFIED as the payload-order interpretation (T924-AP-03); convention must be recorded and honored by WP5 (F4).
10 Dedupe retention = min under total canonical comparator — RATIFIED (property test caught the original input-order leakage; fix is permutation-safe).
11 Zero-block pages excluded from envelope budgets/coverage/planInputFingerprint — RATIFIED (`:173`, `:374`; coverage check uses `plannable`, `:292`).

### WP8 (1–8)

1 TextLayoutPlanner.kt zero-diff — RATIFIED (strongest possible form of the allowed additive edits; verified empty diff).
2 Test file names differ from the Stage-7 gate oracles (`DrawPlanDtoRoundTripTest`, `DrawPlanCompatibilityTest`) — RATIFIED for this slice; those named oracle files remain owed at the Stage-7 exit (gate rows 7.1/7.2), by WP9.
3 Blank `stableBlockId` projects as-is, plan fails DTO validation — RATIFIED (provable-only; surfaced by `validationError`, never remapped) (`LayoutDrawPlanProjection.kt:96` + KDoc :58-63).
4 `lines` always projected — RATIFIED (`encodeDefaults=true` writes them anyway; fidelity).
5 Render-order reconstruction via the planner's own sort — VERIFIED REAL: planner sort `compareByDescending(score).thenBy(input index)` (`rendering/TextLayoutPlanner.kt:634-639`) vs rehydrate sort `compareByDescending(block.score).thenBy(inputIndex)` (`LayoutDrawPlanProjection.kt:149-152`) — identical keys; `inputIndex` IS the planner input ordinal, so tie-breaks coincide. LAYOUT_PLANNER_VERSION-bump coupling documented; WP9 must enforce the bump discipline.
6 `sdkShapingBucket` = raw int + nearest VERSION_CODES name — RATIFIED. Bucket table verified correct (1=BASE … 34=UPSIDE_DOWN_CAKE, 35=VANILLA_ICE_CREAM, 36=BAKLAVA, `DrawPlanFingerprint.kt:73-92`); unknown future SDK keeps its own int, keys never collide (maximally conservative per decision 7.5).
7 `fontAssetSha256(bytes)` pure — RATIFIED; production digest of `res/font/animeace.ttf` still unrecorded (wp8-report risk 3) — WP9 wiring obligation.
8 `%02x` default-locale — RATIFIED (F10).

---

## 6. Mandate item 5 — risk rulings (WP4 R1–R4)

| Risk | Ruling | Condition |
|---|---|---|
| R1 ~200 per-page manifest publications/run | ACCEPT-FOR-NOW | Counters are advisory (ST-06); de-slide to every-N-pages is correctness-neutral. REQUIRE the gate 3.4/3.6 device measurement before any default-on decision; if manifest-write cost shows up, de-slide then. Each publication is a full-manifest CAS move (`publishActiveRun`, `artifact/ChapterArtifactStore.kt:319-356`) plus a content-addressed sidecar — on slow flash 200 of them is a real cost; do not let this reach default-on unmeasured. |
| R2 durable failure ledger deferred | ACCEPT-FOR-NOW | Preflight REJECTED/exception yields FAILED with anchor page (`:183-199,209-219`); chapter halts visibly; re-attempt re-OCRs the failed page (candidate torn down, B0 semantics). No silent progress loss; a poisoned page cannot loop invisibly because the FAILED outcome stops the pass each time. Fix in the next S3 slice as planned: wire the preflight stop into the durable failure ledger (`persistUnexpectedBatchStageFailure` idiom). |
| R3 non-AI translators preflight-only under FF-01 | ACCEPT-FOR-NOW, HARD DEADLINE | See F3. Flag OFF-by-default with no UI makes it invisible to users today. Must be resolved (dispatch gate or coordinator fall-through) before Stage 5 translation stages ship or the flag gets any surface. |
| R4 orientation from decoded dims not EXIF | ACCEPT-FOR-NOW | `orientationOf` (`:399-404`) derives PORTRAIT/LANDSCAPE from stored page dims; matches the M1 harness convention; stored in checkpoint SourceIdentity so a future EXIF-accurate source changes fingerprints only when reality changes. Revisit only if an EXIF source is threaded into the lane. |

---

## 7. Mandate item 5b — test gaps (MUST exist before the consuming stages)

1. **Before any stage publishes `ChapterRunState.COMPLETE` (S5+)**: dispatch-level flag-off-mid-run test where `decideResume` (or its production replacement) is actually on the resume path — gate 3.8's full obligation. Current coverage proves the pure decision only (F1).
2. **Before S5/WP5**: queue-restore-level test extending the `ChapterTranslatorQueueRestoreTest` idiom (FF-01e test obligation names it; current test asserts the decision signature and no side effects only).
3. **Before S5/WP5**: request-builder test asserting the contributing set order sent to providers equals core-then-context (pins deviation 9 end-to-end; F4).
4. **Before WP5 persists analysis chunks**: chunk planner golden fixture under `t924/golden/` (gate 2.1, F7) + the `PlannedAnalysisChunk`→`AnalysisChunkResult` mapping test incl. status and excerpt-hash validation boundaries (deviation 8 handoff).
5. **Before WP5**: consolidation of `PlannerFingerprints` into `StageFingerprints` or encoder-version pinning (F2), with the golden fingerprint literal re-pinned.
6. **Before WP9**: production `assetSha256` for `res/font/animeace.ttf` recorded and pinned (wp8 risk 3); `DrawPlanFingerprint.platformShapingKey()` exercised once on-device (unit tests must keep passing explicit keys — `Build.VERSION.SDK_INT` is 0 on JVM).
7. **Before WP9**: hydration-loss contract test — caller detects a lossy `rehydrate` (count mismatch) and falls back per FF-02b (F5).
8. **Before flag surface widening / Stage 5**: R3 resolution test (non-AI engine + FF-01 ON behaves sanely — either never dispatches flagged, or is translated by the flagged path) (F3).
9. **Stage-3 exit**: checkpoint-REJECTED mid-run durability test asserting prior pages' checkpoints survive AND the failed page's candidate is torn down (R2 wiring evidence).

---

## 8. Mandate item 6 — independent verification run

Environment: worktree `TachiyomiAT-t924-impl`, `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr`.

| Command | Result |
|---|---|
| `./gradlew :app:compileStandardDebugKotlin` | exit 0, BUILD SUCCESSFUL |
| `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.artifact.*" --tests "eu.kanade.translation.model.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.rendering.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.translator.contextual.*" --tests "eu.kanade.translation.ChapterTranslationStore*" --tests "eu.kanade.translation.OcrCheckpointRestartReuseTest" --tests "eu.kanade.translation.T924FeatureFlagsTest"` | BUILD SUCCESSFUL in 2m 55s, exit 0 |
| JUnit XML tally (`app/build/test-results/testStandardDebugUnitTest/*.xml`) | **classes=102 tests=792 failures=0 errors=0 skipped=0** — matches the orchestrator's reported run exactly |
| `git diff --name-status 7c301bc..HEAD` | 19 files, all within declared slice sets |
| `git diff 7c301bc..HEAD -- rendering/TextLayoutPlanner.kt pipeline/batch/SequentialBatchCoordinator.kt` | empty (both zero-diff claims VERIFIED) |

---

## 9. Evidence classification summary

Key claims verified against primary evidence (code/commits/test XML), not reports: FF-01a single dispatch (file:line above), FF-01b verbatim OFF branch, FF-01d field+fingerprint (F6), FF-10 no-auto-start, SC-14 additive legality, TX-06 ordering in `finally`, PAUSED pre-existing, TextLayoutPlanner/SequentialBatchCoordinator zero diff, planner exact-once + atomicity + permutation properties, SC-10 hashing view, byte-pinned golden fixture, full test-suite reproduction (792/792). Assumptions taken from S1 review (not re-reviewed per assignment): checkpointOcr internals, publishActiveRun transaction internals, `EnvelopePlan`/`PageLayoutDrawPlan` DTO validation internals — their consumption sites here are conformant.

## 10. Stage-gate status note

Per T924-FF-21, this verdict covers wave-2 conformance at the code level. Stage exits (gate tables 2.2/2.3 rows, device evidence) remain open until their exit reports carry the REQUIRED rows with numbers/artifacts; F7 and the test-gap list are the delta between this acceptance and those exits.
