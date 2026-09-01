# T917 Phase 1 — Implementation Log (finishing session)

Branch: `t917/coexistence-v3`. Scope: test code only, no production changes, no git
write commands. This session finished and verified the harness/tests built by the
previous (cancelled) implementer session.

## 1. Files created / modified / deleted

| File | Action |
|---|---|
| `app/src/test/java/eu/kanade/translation/coexistence/PlanProbeTest.kt` | **Deleted** (throwaway planner probe of the previous session) |
| `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt` | Modified: `create(pageKeys, preRegisterInStore)` (line 145) and `launchBatch(pageKeys)` (line 731) — see §4 deviations |
| `app/src/test/java/eu/kanade/translation/coexistence/D2ManualBatchInterleavingTest.kt` | Modified: test 2 (`manual to batch …`, line 116) — empty-start store, explicit batch keys, red message C-01→C-02 (line 156) |
| `app/src/test/java/eu/kanade/translation/coexistence/D3ReaderOwnedPageAcrossBatchTest.kt` | Modified: empty-start store (line 43), explicit batch keys (line 74), removed the impossible pre-reconciliation `p1 render READY` await (line 78 comment), red message now names p1 (line 113); removed a temporary diagnostic watchdog |
| `app/src/test/java/eu/kanade/translation/TranslationManagerAutoArbitrationTest.kt` | Verified only (D4 update by the previous session was complete and correct — no further edit) |
| `Plan/active/2026-09-01_T917_coexistence-v3-implementation/engineering/phase1-implementation-log.md` | Created (this file) |

## 2. Exact Gradle commands (Windows Git Bash, `JAVA_HOME` = Android Studio JBR)

```
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.D2ManualBatchInterleavingTest" --tests "eu.kanade.translation.coexistence.D3ReaderOwnedPageAcrossBatchTest"
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.coexistence.*"
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest"
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.SequentialBatchCoordinatorTest" --tests "eu.kanade.translation.ChapterTranslatorTerminalExitsTest"
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"
# determinism: the coexistence filter above, 10 consecutive invocations (bash for-loop)
```

## 3. Intentional RED tests — captured failure messages (excerpts)

1. `D2ManualBatchInterleavingTest > batch to manual - tap while batch holds the page at provider start end and render` (0.35 s — left as-is per instruction)
   > `D2 batch→manual (C-01) at PROVIDER_START: manual tap on a batch-owned page finished without waiting for the batch owner (wait-and-attach not implemented)` expected:<true> but was:<false>
2. `D2ManualBatchInterleavingTest > manual to batch - batch must not start paid work on the manually-owned page` (6.4 s; was 10 s TimeoutCancellationException before this session)
   > `D2 manual→batch (C-02): batch stranded the manually-owned page instead of defer-and-rescan within the pass: stranded={p1=Translation incomplete — expected page was stranded by a prior run and not reached}` expected:<{}> but was:<{ "p1" = … }>
3. `D3ReaderOwnedPageAcrossBatchTest > reader-owned page across a full batch is re-translated in the same pass and never stranded` (0.31 s; was 10 s timeout)
   > `D3 (C-02): reader-owned page (p1) was skipped and never rescanned, so reconciliation stranded it: stranded={p1=Translation incomplete — expected page was stranded by a prior run and not reached}` — the C-02 oracle (stranded empty / chapter TRANSLATED / tracker 2/2) is asserted; the run aborts at the first (stranded) clause, as designed for RED.
4. `TranslationManagerAutoArbitrationTest > manager suppresses same-chapter auto while the chapter batch is queued` (0.83 s) — RED proving re-arm still succeeds today:
   > `D4: same-chapter auto re-armed while the chapter batch was still queued (batch-lifetime suppression guard missing from the re-arm path)` expected:<false> but was:<true>
   All other tests in the file GREEN. The rename + target-contract assertions + restore-after-queue-drains check per note §3.3 were already present and correct.

GREEN verifications: `NormalMangaIsolationTest` PASSED (re-verified in every determinism run);
`SequentialBatchCoordinatorTest` + `ChapterTranslatorTerminalExitsTest` fully GREEN
(BUILD SUCCESSFUL).

## 4. Deviations from the design note

1. **New harness capability (harness-only, test code): empty-start store.**
   `create(preRegisterInStore = false)` (`TranslationCoexistenceHarness.kt:145-180`)
   mirrors production's fresh-chapter state: `ChapterTranslator.kt:637` pre-registers
   pages at batch start, and a manual tap on a never-translated chapter sees
   `store.state.value[pageKey] == null`, for which `PageWorkPlanner.plan(null)` plans
   all stages RUN. Root cause this fixes: the previous harness pre-registered PENDING
   page entries; against a PENDING entry the four-stage planner marks
   OCR/TRANSLATION/INPAINT/LAYOUT `WAIT_FOR_DEPENDENCY` (DETECTION plans RUN and blocks
   them), the legacy boolean projection maps that to `run*=false` for all four, and
   `SinglePageOnnxPhase.translateSinglePageOnnx` takes the "single-page resume skip"
   silent early return (`SinglePageOnnxPhase.kt:296-302`) — the manual tap did nothing,
   parked at no barrier, and both manual-first tests timed out. Confirmed by a
   temporary in-test state probe (permitHolder=null, no arrivals, one lease-write,
   job completed silently). Production `ReaderViewModel.kt:2157-2168` resolves
   `effectiveForce=false` for a fresh PENDING page, so `force=true` was NOT an
   acceptable workaround; the empty-start store is the production-faithful shape.
2. **`launchBatch(pageKeys)`** (`TranslationCoexistenceHarness.kt:731`): the per-page
   lane-serialization `CompletableDeferred`s (`transportStarted` / `nativeStageDone`,
   see `FakeTransportTranslator` doc) were previously seeded from
   `store.state.value.keys`; with an empty-start store the keys are not observable
   before the batch's own pre-registration, so manual-first tests pass
   `listOf("p0","p1")` explicitly. Default behavior (seed from store keys) unchanged.
3. **D3 choreography correction**: the pre-reconciliation
   `store.state.first { it["p1"]?.renderStatus == READY }` await was impossible by
   construction (the manual holds the p1 lease while parked at PROVIDER_END; its
   render commit happens only after release) and was the second guaranteed-timeout
   bug. Removed; the p1 terminal assertion after release+join remains.
4. **Red-message accuracy**: D2.2's clue renumbered C-01→C-02 (defer-and-rescan is
   the C-02 defect); D3's clue now names the stranded page as p1.
5. **New android.jar shims beyond note §1.2: NONE.** This session added no new
   framework stubs. The shims already present from the previous session
   (`mockkObject(PageDecode)` with `callOriginal` re-stubs for the pure-JVM helpers,
   `RenderColorEstimator.recomputeFor` no-op, the relaxed `Bitmap` stand-in with
   stubbed `byteCount` in `FakeCoexistence.stubBitmap` instead of the note's
   Unsafe-allocated Bitmap, and the `cleanedPublication` mock delegate) are documented
   in the harness KDoc and `review/phase1-verification.md`.
6. **Unexercised path (risk note for Phase 2):** the manual path's post-release
   render commit (`SinglePageHttpRenderPhase` → `store.patchPage` with the
   manifest-free precondition handed over by the manual publish shim) is not reached
   in the RED state — both manual→batch tests abort at the red assertion before the
   manual is released. It will first execute when D2.2/D3 flip green in Phase 2.

## 5. Baseline-green sweep

Command: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`
Result: **1231 tests completed, 4 failed — exactly the four intentional reds above;
no pre-existing unrelated failures.** BUILD time ~1 m.

## 6. Determinism (note §2 / PLAN §5)

10 consecutive Gradle invocations of the coexistence filter, one signature per run
(failed-set + completed count): **10/10 identical** — every run: 4 tests, 3 failed
(D2 both methods, D3), 1 passed (`NormalMangaIsolationTest`). No deviation.
(Design note §3 asks for 100 consistent runs before `checkpoint/t917-p1-done`; this
session executed 10 per the delegation instruction — remaining runs are deferred to
the Reviewer's Phase-1 verification.)

## 7. Touched-files proof (`git status --short` at end of session)

```
 M app/src/test/java/eu/kanade/translation/TranslationManagerAutoArbitrationTest.kt
?? Plan/active/2026-09-01_T917_coexistence-v3-implementation/engineering/
?? app/src/test/java/eu/kanade/translation/coexistence/
```

All touched production-tree paths are under `app/src/test/**`; the `engineering/`
directory contains the T917 planning docs (harness notes + this log).

## 8. Reviewer condition 1 — applied (post-verification edit)

Reviewer Finding 1 (review/phase1-verification.md): D2.2/D3 asserted the reconciliation
oracles while the reader-owned page was still parked at PROVIDER_END with its lease held;
once Phase 2 lands defer-and-rescan ("rescans after lease release within the pass;
reconciliation only after rescan", PLAN.md:59) the reconciliation could only complete after
the manual release, so a green run would have hit the 10 s bound. Fix applied —
choreography-only, RED oracles untouched:

- `D2ManualBatchInterleavingTest.kt` (`manual to batch …`): order is now
  launchBatch → `release(PROVIDER_END,"p1")` → await `batch.reconciliation` → assert
  stranded-empty / chapter-TRANSLATED → joinAll → terminal render-state + exactly-once
  paid-call asserts. Also fixed the Finding-4 message wording ("each page translated exactly
  once (batch p0, manual p1)"). A first attempt placed `joinAll` between release and the
  reconciliation await; that timed out (10 s) because the manual's post-release render path
  — today still defective/unsettled — was being joined before the RED assertion could fire.
  The reconciliation await is the pass-end event the release unblocks, so it is asserted
  before the job joins; `joinAll` remains as a green-state completeness gate.
- `D3ReaderOwnedPageAcrossBatchTest.kt`: same reordering — `release(PROVIDER_END,"p1")`
  now happens before the reconciliation + tracker-terminal awaits and the C-02 assertions;
  joinAll + p1-terminal/paid-call asserts follow.

Verification (coexistence filter re-run): identical outcome set — 4 tests, 3 failed with
byte-identical messages (D2.1 C-01 @ PROVIDER_START 0.35 s; D2.2 C-02 stranded-p1 5.45 s;
D3 C-02 stranded-p1 0.33 s), `NormalMangaIsolationTest` PASSED (2.34 s). No timeouts.
