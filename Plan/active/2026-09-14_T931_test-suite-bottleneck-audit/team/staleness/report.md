# T931 — Staleness / Harness / Timing / Wiring audit (team: staleness)

Date: 2026-09-14 · HEAD: `9c19ad0` · Scope: `app/src/test` (277 files, ~63,557 lines, 1,914 `@Test` in 266 test-bearing classes)
Method: read-only grep + file reads at HEAD; runtime numbers taken from the last recorded local run (`app/build/test-results/testStandardDebugUnitTest/`, 2026-09-03 16:08). The full suite was NOT run. One bounded compile attempt was made (see §D.5) and failed on environment, not code.

---

## 1. Verdict up front

**The Director's "stale legacy" claim is not supported by the code. At most ~2% of the suite is stale or dead weight; ~0% tests deleted machinery.**

- **0 test files, 0 test methods** reference deleted machinery (`SequentialBatchCoordinator`, FF-01 flag) as live API. Every one of the 10 test-file matches is a KDoc comment explaining deletion history (§A.1). The T924 zero-legacy wave (D1) explicitly **deleted the tests that pinned deleted behavior** (`OcrPreflightFlagOffMidRunTest`, `decideResume`/`TreatAsFinished` decision tests, flag-OFF wiring cases) in the same commits that deleted the production code — documented at `app/src/test/java/eu/kanade/translation/pipeline/batch/OcrPreflightQueueRestoreTest.kt:32-38` and `coexistence/BatchDispatchResumeWiringTest.kt:18-32`.
- The legacy-migration tests guard a **real, live upgrade path** (§A.2): `ChapterArtifactStore.kt:206` and `LegacyArtifactRescue.kt:49` call `LegacyArtifactMigration.migrateChapter` in production today, and the legacy flat-file glossary fallback is kept deliberately (`ChapterGlossaryStore.kt:14-15,101-111`).
- 0 `@Ignore`, 0 skipped tests in the recorded run; test churn moves in the same commits as code (recon fact, confirmed by the 66 test classes added/changed since Sep 3).
- The **real bottlenecks are elsewhere**: CI runs the same suite **twice per invocation** (§D.1), the coexistence harness's Unsafe/reflection wiring makes production refactors touch 17 test files at once (§B), and ~17s of the suite is blind `Thread.sleep` (§C).

Stale material actually found: `Page15MockRig` (1,018 lines, self-declared "NOT a regression test", runs during every suite execution on this machine), ~10 stale KDoc blocks, and 2 trivial flag-preference tests. That is the whole inventory.

---

## 2. Section A — Stale legacy inventory

### A.1 Tests referencing deleted machinery

Grep `SequentialBatchCoordinator` and `FF-?01` over `app/src/test` (case-insensitive for FF-01):

| File:line | Reference | Kind | Classification |
|---|---|---|---|
| `coexistence/BatchDispatchResumeWiringTest.kt:20,28` | "flag-OFF wiring cases are gone with the FF-01 flag", "legacy SequentialBatchCoordinator no longer exists" | KDoc | **LIVE** (tests current dispatch-level resume wiring) |
| `coexistence/D3ReaderOwnedPageAcrossBatchTest.kt:31` | "was SequentialBatchCoordinator machinery, deleted with the legacy path" | KDoc | **LIVE** (reader-priority + durable resume contract) |
| `coexistence/TranslationCoexistenceHarness.kt:102` | build-graph description still lists `→ SequentialBatchCoordinator →` | **STALE KDoc** | HISTORICAL-PIN (doc only; the harness actually wires `ChapterProfileBatchCoordinator` at :565-615) |
| `coexistence/StandardPipelineCoexistenceTest.kt:16,25` | "FF-01 ON + STANDARD engine" wording; "legacy SequentialBatchCoordinator is NOT constructed — by behavior" | KDoc + live negative assertion | **LIVE** (asserts the deletion held; wording stale) |
| `pipeline/batch/OcrPreflightQueueRestoreTest.kt:36-38` | "no flag-OFF state … no legacy SequentialBatchCoordinator — those tests pinned deleted behavior and were deleted with it" | KDoc | **LIVE** (FF-10 queue-restore obligation) |
| `pipeline/batch/OcrPreflightCoordinatorTest.kt:39,238` | coordinator-shell coverage history; "flag frozen (FF-01d)" | KDoc | **LIVE** |
| `pipeline/batch/Stage7FinalizeCoordinatorTest.kt:60` | "deleted with the FF-01 flag; ST-14 covered by Stage7FinalizeResumeCoordinatorTest" | KDoc | **LIVE** |
| `pipeline/batch/ProfilePipelineDispatchGateTest.kt:7` | "FF-01 A/B flag is gone — dispatch gate is ENGINE-CATEGORY only" | KDoc | **LIVE** (truth table over current `BatchChapterTranslator.profilePipelineDispatchKind`) |
| `pipeline/batch/ProfileEnvelopeDispatchTest.kt:58` | "serial envelope dispatch behind FF-01" history | KDoc | **LIVE** |
| `ChapterTranslatorQueueRestoreTest.kt:69` | "the FF-01 flag and its decideResume decision tree are gone" | KDoc/comment | **LIVE** |
| `ChapterTranslationStoreArtifactMigrationTest.kt:184-189` | feeds removed `batchContextCheckpointHash`/`batchContextComplete`/`batchSceneCheckpoint` JSON fields into the decoder | Live test of schema tolerance | **STILL-LOAD-BEARING** (old devices' page JSON carries these keys) |
| `T924FeatureFlagsTest.kt:13` | "FF-01 … REMOVED — there is no flag to test. FF-02 keeps its lifecycle coverage here." | KDoc | HISTORICAL-PIN (2 trivial tests on FF-02 `translationBatchPersistedLayout` default/settable, :22-32) |

**Test-only re-implementations of deleted classes: none.** The only `*Coordinator` names in tests are test classes over live production coordinators (`AotFallbackCoordinatorTest`, `ChapterAnalysisPhaseCoordinatorTest`, `ChapterProfileFreezeCoordinatorTest`, `OcrPreflightCoordinatorTest`, `Stage7Finalize*Test`, `StandardPipelineCoordinatorTest`, `TextLayoutCoordinatorTest`, `RollingAutoCoordinator*Test`). In `app/src/main`, `SequentialBatchCoordinator` appears only in comments (`BatchLaneWorkers.kt:83`, `ChapterProfileBatchCoordinator.kt:89`).

### A.2 Legacy-migration tests — do they guard real users' data?

**Yes. The migrated-from format can still occur on a real device upgrade, and the production entry points are live at HEAD:**

- Production trigger 1 — first open of an old chapter: `ChapterArtifactStore.kt:206` `stampChapterKey(LegacyArtifactMigration.migrateChapter(legacy))` inside the store's load-or-migrate path; exercised end-to-end by `ChapterTranslationStoreArtifactMigrationTest.kt:202-229` (`ChapterTranslationStore.open()` on a real `Chapter 1.json` flat file → sibling manifest + versioned glossary sidecar + non-destructive rename to `.migrated`).
- Production trigger 2 — crash rescue: `LegacyArtifactRescue.kt:49` (class wired at `ChapterArtifactStore.kt:89-90`), covering manifests damaged/partial after process death.
- Legacy flat-file read fallbacks still exist in main: `ChapterGlossaryStore.loadGlossary` legacy path `ChapterGlossaryStore.kt:101-111` — `store.legacyDocuments()` (`ChapterTranslationStore.kt:2497-2498`) with KDoc "The LEGACY flat-file read fallback in [loadGlossary] is kept deliberately" (`ChapterGlossaryStore.kt:14-15`).
- Pre-artifact chapter JSON is what every upgrading device has on disk; the migration is lazy (per-chapter, at open), so old-format chapters persist in the wild for as long as users upgrade from pre-artifact builds. `ChapterRunRecord.kt:61-73` keeps the nullable `flagProfilePipeline` field in the schema specifically so **flag-era durable run records keep their fingerprints and resume unchanged** — durable on-device state, not dead code.

Classification of the two files:

| Test file (lines, tests) | Verdict | Evidence |
|---|---|---|
| `artifact/LegacyArtifactMigrationTest.kt` (602 ln, 26 tests) | **STILL-LOAD-BEARING** — pure-function contract of the migration used by both live production call paths | exercises `LegacyArtifactMigration.migratePage/migrateChapter` (:107-451), metadata fail-closed matrix (:517-593), determinism (:392-399) |
| `ChapterTranslationStoreArtifactMigrationTest.kt` (816 ln, 22 tests) | Mixed: 10 tests **STILL-LOAD-BEARING** (real `ChapterTranslationStore.open()` migration, corrupt-JSON, missing-image, cutover, process-death reopen: :202-248, :377-488); 12 tests are **current-behavior** artifact-store tests that merely live in this file (lazy store, pre-registration, artifact-only persistence: :293-375, :490-814) | production-path KDoc :46-56 |

**DEAD-PATH tests found: none** in the migration area. **HISTORICAL-PIN: `T924FeatureFlagsTest`** (2 tests) and the stale KDoc blocks listed in A.1.

### A.3 Behavior replaced by T924's two-lane redesign

The redesign's hygiene is unusually good: replaced tests were **deleted with the behavior**, and the survivors pin the replacement contract:

| Replaced behavior | Old test | Fate | Replacement (live) |
|---|---|---|---|
| FF-01 flag-OFF mid-run DropToLegacy/TreatAsFinished | `OcrPreflightFlagOffMidRunTest` | **Deleted with the flag** (`OcrPreflightQueueRestoreTest.kt:32-38`) | `OcrPreflightQueueRestoreTest` (queue-restore obligation, :44-50) |
| Shell-level `resumeCompletedOutcome`/`decideResume` consultation | flag-OFF wiring cases | **Deleted** (`BatchDispatchResumeWiringTest.kt:18-32`) | ST-14 resume at coordinator level: `Stage7FinalizeResumeCoordinatorTest`, `StandardPipelineCoordinatorTest` ("re-dispatch over an interrupted standard run continues the same run id") |
| Legacy schedule never publishes run records | — | Deletion pinned as a negative assertion | `StandardPipelineCoexistenceTest` point 1 (:25-29): transport called exactly once/page + `standard:mlkit` run record present |
| Legacy in-pass defer-and-rescan | — | Deleted with legacy path | `D3ReaderOwnedPageAcrossBatchTest.kt:24-33` (reader priority + durable resume) |

**Bottom line A:** stale-legacy mass = `Page15MockRig` (1,018 ln) + ~10 stale KDoc blocks + 2 trivial tests ≈ **1.6% of suite lines, 0.2% of tests**. Nothing else is dead.

---

## 3. Section B — Harness quality

### B.1 `translation/coexistence/TranslationCoexistenceHarness.kt` (1,254 lines)

- **What it simulates:** the REAL production translation graph, not a mock of it — `TranslationManager → TranslationScheduler → TranslationPipeline` (real `EngineLane`, real `SinglePageOnnxPhase`/`SinglePageHttpRenderPhase`, real `BatchChapterTranslator → ChapterProfileBatchCoordinator`) over `ChapterTranslationStore`. JVM-hostile constructors are bypassed with `sun.misc.Unsafe.allocateInstance` + reflection field injection (:100-116 design KDoc; EngineLane :331-353; Pipeline :617-661; Manager :721-740). Fakes exist only at sanctioned externals: `FakeRecognitionEngine`/`FakeTransportTranslator` (`FakeEngines.kt:29,97,176` — shared by 4 files), `FakeChapterDocumentIo`, stub Bitmap/decode shims (:1142-1166). Determinism is event-driven: `CompletableDeferred` barriers + `StateFlow.first{}`, "no sleeps, no polling", every await inside `runBlocking { withTimeout(AWAIT_TIMEOUT_MS=10s) }` (:112-115, :156-163).
- **Consumers:** **17 test files** (grep `TranslationCoexistenceHarness`): D2, D3, D6 (x2), D7, D8, D9, D10, D11, P5 (x2), StandardPipelineCoexistenceTest, StandardLaneMultiPageCompletionTest, NormalMangaIsolationTest, T918CancelledBatchRestartTest, BatchDispatchResumeWiringTest, ManualRenderProbeBaseline (untracked probe).
- Recorded runtimes for its suites: 0.5-8.3s per class (e.g. `D2ManualBatchInterleavingTest` 8.3s/2 tests, `D7EngineEpochStopRaceTest` 6.2s/6) — the most expensive *legitimate* part of the suite.

### B.2 `translation/rendering/Page15MockRig.kt` (1,018 lines)

- **What it is:** a **scratch visualization rig**, self-declared at :21-22: *"SCRATCH laptop mock rig — NOT committed, NOT a regression test."* Runs the real `TextLayoutPlanner.planPage` against the Director's cached problem page (Konoka vol.3 p15) and emits SVG previews + stdout diagnostics for laptop debugging.
- **Consumers: 0.** Only it references `Page15MockRig`. 3 `@Test` methods guarded by `Assumptions.assumeTrue(fixturesPresent())` (:275, :719, :747).
- **But the fixtures ARE present on this machine** (`Plan/active/2026-08-30_T912_text-layout-renderer/engineering/fixtures/page15-committed.json` + `page15-source.jpg` exist), so the rig **executes during every unit-test run here** (recorded: 1.7s total) and **writes `rig-out/*.svg` files into `Plan/`** as a side effect (`outDir` :58, `buildSvg` :252-258). It is dev tooling embedded in the test source set.

### B.3 Fixture duplication across test files

| Signal | Files | Meaning |
|---|---|---|
| `private fun block(` (TranslationBlock builder) | **56** | page/block fixture re-built per file |
| `fun displayablePage(` | 5 | the "complete page" fixture duplicated (incl. both migration tests) |
| `fun pngBytes(` | 3 | PNG byte-fabricator duplicated 3x (~35 lines each) |
| `FakeChapterDocumentIo(` constructed | 21 | shared fake, but per-file assembly |
| `FakeUniFile` used | 21 | same |

The fakes themselves are shared (`FakeEngines.kt`, `FakeChapterDocumentIo.kt`, `com/hippo/unifile/FakeUniFile.kt`); the *domain fixtures* (blocks/pages/PNGs) are not. Default-argument builders currently absorb most model-field additions, but every new `TranslationBlock`/`PageTranslation` field that changes migration-relevant defaults must be reconciled across 56+ local builders.

### B.4 Top 3 maintenance hazards

1. **Unsafe+reflection pinning of production internals** — the harness names ~45 private fields across `EngineLane` (:336-350), `TranslationPipeline` (:622-650), `TranslationScheduler` (:691-696), `TranslationManager` (:728-739), plus scattered seams (`translationJob` :1100, `probedSourcePageCount`/`sourceCountKnown` :1089, `singlePageTimeoutMs` :666-678, engine-drain fields :823-839). A production rename fails loudly (`NoSuchFieldException`, :793) — but the blast radius is all 17 consumer files at once, so a routine T930-scale refactor of `TranslationPipeline` fields becomes a 17-file test-wave in the same commit. This is the single largest *structural* coupling in the suite.
2. **Harness hard-wires production timing/ordering assumptions** — `AWAIT_TIMEOUT_MS=10s` / `NEGATIVE_PROBE_MS=2s` (:156-163), `drainGraceMs` default 5s (:838), the per-page lane-serialization choreography `transportStarted`→`nativeStageDone` (:254-256, :512-529, :981-986), and `publishCleanedThroughStore` re-reading preconditions because production has a ~100ms JPEG-encode window the fake collapses (:946-953). T930 ("changes WHEN bytes hit disk") will collide with exactly these choreography points (persist-before-transport ordering, COMMIT barriers) even though it shouldn't change WHO owns writes.
3. **Fakes re-encode production semantics inline** (drift risk) — `batchAnalyze` re-performs the production OCR-before-inpaint store merge (:485-505), `batchInpaint` must settle `inpaintStatus=READY` "the real page inpainter settles… wrongly [silently] in those that do [assert]" (:521-527), and the manual publish shim hands phases a manifest-free precondition with a documented deviation (:1210-1218). Each is a comment-acknowledged place where the fake's contract, not the compiler, keeps it faithful; silent divergence here would give the whole coexistence net false confidence.

---

## 4. Section C — Timing / flakiness

The 7 `Thread.sleep` files (grep `Thread.sleep`), with what each awaits:

| File:line | Sleep | What it awaits | Virtual time should own it? |
|---|---|---|---|
| `tachiyomi/data/download/DownloadCacheRenewalGuardTest.kt:115,119,122,130,142,209` | 25/25/100/100/50/100 ms | polls a reflection-read `renewalJob` until inactive + fixed 100ms "beat"; polls disk for cache file (15s deadlines) | **Partly** — Job polls → `job.join()`; the 100ms blind "beat" → completion await. Real disk writes cannot go virtual, but the polling can. Recorded cost: **16.0s for 4 tests** (single slowest test in suite: 15.2s) |
| `tachiyomi/ui/manga/MangaScreenModelMultiSelectBatchTest.kt:410,453` | 250 ms blind; 10 ms poll | `settleProbe()` = fixed 250ms "settle wait" for a main-surrogate probe; `awaitUntil` 5s-deadline poll | **Yes** — the 250ms blind settle is the classic flake (too short on slow CI); replace with state await (`awaitUntil` already exists at :450-458) or `runTest` |
| `tachiyomi/ui/manga/MangaScreenModelTranslationDrawerTest.kt:404` | 10 ms poll | `awaitUntil` 5s-deadline state poll | Yes (bounded poll = low flake, minor latency) |
| `translation/ChapterTranslatorBatchStartGuardTest.kt:90,106` | `IN_FLIGHT_WINDOW_MS`; 25 ms poll | sleep **inside the stubbed resolver** to model the production in-flight window (scenario time, legitimate); `awaitTrue` poll | No (scenario sleep models real window; would need an injected clock) — costs real seconds by design |
| `translation/TranslationRequestGenerationFenceTest.kt:244` | 150 ms | **race**: starts a raw `Thread`, sleeps 150ms hoping it parks on `mutationLock`, then cancels | **Yes — highest flake risk in suite**: if the thread hasn't reached the lock within 150ms the premise breaks. Replace with a park-detection latch (thread-state watch or `CompletableDeferred` before `synchronized`) |
| `translation/pipeline/batch/ProfileEnvelopeDispatchTest.kt:583` | 1,100 ms | pushes file `lastModified()` past 1s mtime granularity so the ST-11 reuse branch can prove "no rewrite" | **No** (filesystem clock, not coroutine time) — but assertion should compare bytes+pointer instead of mtime; 1.1s/run and still unsafe on 2s-granularity filesystems (exFAT/FAT) |
| `translation/scheduling/RollingAutoCoordinatorTest.kt:42` | — | **KDoc only** ("no `[delay]`/`[Thread.sleep]` polling") | False positive |

**Real-clock assertions:** only two sites capture wall-clock for assertions: `D6ForegroundFairnessTest.kt:177` → `:235` asserts `retryAt ∈ [p0WallMs, p0WallMs+62s]` (same-clock derivation, 62s window — negligible flake risk), and the deadline patterns above (`System.currentTimeMillis()+timeout`) which are safe by construction. No `System.nanoTime` benchmark assertions found.

**Summary C:** ~17s of pure sleep across 6 files; 2 genuinely flake-prone patterns (150ms lock race; 250ms blind settle); 1 slow-by-design scenario (batch start guard); 1 mtime hack that is slow and FS-dependent.

---

## 5. Section D — Build wiring + runtime cost

### D.1 When tests run

| Trigger | Tests run? | Evidence |
|---|---|---|
| Every PR | **Yes — full suite ×2** | `.github/workflows/build_pull_request.yml`: `./gradlew spotlessCheck assembleStandardRelease testReleaseUnitTest testStandardReleaseUnitTest` ("Build app and run unit tests" step); triggers on all paths except md/most i18n strings |
| Push to main / tags | **Yes — full suite ×2** | `.github/workflows/build_push.yml`, same step: `./gradlew assembleStandardRelease testReleaseUnitTest testStandardReleaseUnitTest` |
| Local commit / push | **No** | all four git hooks (`.git/hooks/pre-push`, `post-checkout`, `post-commit`, `post-merge`) are git-lfs plumbing only; no husky; no `core.hooksPath` override |
| Local build | **No** | no `connectedCheck`/`test` dependency wiring in `app/build.gradle.kts`; `assemble*` does not depend on `test` |

**Duplication finding:** with flavors `standard` + `dev` (`app/build.gradle.kts:104-118`), the aggregate task `testReleaseUnitTest` = `testDevReleaseUnitTest` + `testStandardReleaseUnitTest`; CI then *also* lists `testStandardReleaseUnitTest` (deduped by Gradle). Net: **the same ~1,914 JVM tests execute twice per CI invocation**, for a suite whose tests are flavor-independent. CI pays 2× test wall-clock plus 2× test-source compilation per PR and per main push.

### D.2 Recorded runtime (no suite was run for this audit)

From `app/build/test-results/testStandardDebugUnitTest/` (2026-09-03 16:08, the last recorded full run of one variant):

- **201 class XMLs, 1,454 tests, 0 failures, 0 errors, 0 skipped; total serial test time 78.1s (~1.3 min).**
- Distribution: median 1ms, mean 53ms, p90 22ms — the suite is overwhelmingly pure-JVM fast tests.
- Slowest classes: `DownloadCacheRenewalGuardTest` 16.0s (sleeps), `D2ManualBatchInterleavingTest` 8.3s, `D7EngineEpochStopRaceTest` 6.2s, `AotCorpusGateTest` 4.6s, `TranslationManagerAutoArbitrationTest` 4.5s (coexistence = most expensive legitimate block).
- **66 of today's 266 test classes postdate that run** (the 2026-09-08..13 T918-T927 wave; e.g. `StandardPipelineCoexistenceTest`, `BatchDispatchResumeWiringTest`, `T918CancelledBatchRestartTest`, `AnalysisChunk*`, `DrawPlan*`). At the observed coexistence-class cost (0.5-8s/class) this adds roughly 1-2.5 min.

**Estimate, one variant, serial: ~2-4 min** (plus Gradle/Kotlin compile overhead, which on a warm daemon for test sources is typically 1-3 min for this tree). **Per CI invocation (×2 variants): roughly 6-14 min of test execution alone**, before `assembleStandardRelease`.

### D.3 ONNX / native — NOT a hotspot

7 files under `translation/runtime/onnx`, 45 tests, all pure JVM:
- `OnnxRuntimeProviderProvenanceTest.kt:15-19`: *"no physical ORT model or native runtime is involved"* — the provider decision logic is executed "over the real native surface … with inert String/Int tokens"; static initializers never load.
- `ModelRoutingEngineTest` uses model *names* (`"aot-512.onnx"`) as routing keys only (:17-79). `QnnContextCacheManagerTest` writes tiny dummy files to `@TempDir` (:30). No `System.loadLibrary`, no model downloads, no OrtSession.

### D.4 I/O-heavy tests

25 files use `@TempDir`. The heaviest real-I/O class is `ChapterTranslationStoreArtifactMigrationTest` (22 tests, **2.28s** recorded) — real filesystem manifest/glossary writes per test, entirely reasonable. `DownloadCacheRenewalGuardTest`'s 16s is sleep/poll-dominated, not I/O-dominated. Nothing pathological.

### D.5 Compile verification attempt (mandated single command)

`./gradlew :app:compileDebugUnitTestKotlin --offline -q` (8-min cap) — **failed to start, not a code failure**: `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH` (0.19s). No JDK is reachable from this shell; compilation therefore not directly verified. Indirect evidence: a full variant of the suite last compiled+ran green on 2026-09-03, and 200 of 266 test classes are unchanged since.

### D.6 Where the bottleneck actually is

1. **CI doubles the suite for no JVM-test benefit** (§D.1) — the largest pure waste, on every PR.
2. **Harness/production coupling** (§B.4 #1) — makes every pipeline refactor a multi-file test wave; this is friction, not staleness, and it buys the project's most valuable race coverage.
3. **~17s of blind sleep + 2 flake patterns** (§C) — small in wall-clock, large in trust erosion.
4. The suite itself (median 1ms/test) is **fast**; "huge bottleneck" is not true of test execution on a developer machine (minutes, not hours).

---

## 6. Top 5 de-bottlenecking actions (ranked by dev-time saved / risk)

1. **Run the suite once per CI invocation.** Replace `testReleaseUnitTest testStandardReleaseUnitTest` with `testStandardReleaseUnitTest` in both workflows (`.github/workflows/build_pull_request.yml`, `build_push.yml`). Saves ~50% of CI test time (≈3-7 min/PR) and one test-source compilation. *Risk: near zero* (unit tests don't exercise flavor code paths; `dev` flavor coverage loss is theoretical).
2. **Evict the scratch rig from the test source set.** Move `rendering/Page15MockRig.kt` (1,018 ln, self-declared "NOT a regression test", :21-22) to a tools/ source set or dev-launcher task. Stops executing dev tooling on every test run and stops SVG writes into `Plan/` (`rig-out`, :58). *Risk: zero.*
3. **Replace the two flake-prone sleeps with deterministic waits.** `TranslationRequestGenerationFenceTest.kt:244` (150ms lock race → park-detection latch) and `MangaScreenModelMultiSelectBatchTest.kt:410` (250ms blind settle → existing `awaitUntil` pattern); convert `DownloadCacheRenewalGuardTest` job-polling to `job.join()` (removes most of its 16s). Saves ~17s and removes the suite's two highest flake-probability sites. *Risk: low, localized to 3 files.*
4. **Extract shared page/block/PNG fixtures.** One internal `TestPages` object (block/displayablePage/pngBytes) replaces 56 + 5 + 3 per-file copies. Directly reduces T930 churn (any store/model field change currently fans out across dozens of fixture builders). *Risk: low, mechanical.*
5. **Sequence harness updates with T930 instead of "later".** The coexistence harness is load-bearing — keep it. But T930 (commit-point contract, staged manifest mutations) must include, in the same commits, the harness field-list updates (`TranslationCoexistenceHarness.kt:336-350,622-650,666-678`) and any choreography-point changes (:981-986, :1210-1218). Promote the untracked `ManualRenderProbeBaseline.kt` (single ~10s end-to-end smoke over the full graph) into the tracked suite as the fast canary for exactly these breakages. *Risk: none if sequenced; large if deferred (a stale harness = silent false confidence in the safety net).*

---

## 7. Evidence appendix (commands)

- `grep -rn "SequentialBatchCoordinator" app/src/test --include="*.kt"` → 5 files, comment-only; `grep -rln` over `app/src/main` → 2 files, comment-only (`BatchLaneWorkers.kt:83`, `ChapterProfileBatchCoordinator.kt:89`).
- `grep -rniE "FF-?01"` → 10 test files (KDoc/live-negative), main hits comment-only except frozen compat field `ChapterRunRecord.kt:61-73`.
- Timing aggregation over `app/build/test-results/testStandardDebugUnitTest/*.xml` (201 files): totals and per-class/per-test tables above.
- `ls .github/workflows` → `build_push.yml`, `build_pull_request.yml`, `lock.yml`; hook inspection: LFS-only.
- One bounded compile attempt: failed on missing JAVA_HOME (environment), reported per mission rules; full test task never run.
