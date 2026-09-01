# T917 Phase 1 — Deterministic Interleaving Harness: Design Note

Audience: Implementer. Scope: harness + failing (RED) tests only. No production change.
Evidence labels: [VERIFIED] = read in this checkout; line numbers are from HEAD at baseline tag
`checkpoint/t916-audit-baseline`.

## 0. The binding constraint first

There is **no Robolectric** on the app test source set (nothing in `app/build.gradle.kts`;
`InMemorySharedPreferences.kt:5-8` states "there is no Robolectric on this source set, and the
unit-test android.jar throws on real implementations"). [VERIFIED]

Consequences, [VERIFIED]:
- `TranslationPipeline`'s constructor cannot run on plain JVM: it builds `EngineLane`
  (`TranslationPipeline.kt:170-176`), whose `init` builds a real recognition engine
  (`EngineLane.kt:175`) via `RoiPageRecognitionEngine(context, …)` (`EngineLane.kt:220`);
  the defensive catch (`EngineLane.kt:178-205`) **also** calls `createRecognitionEngine`
  (`EngineLane.kt:186`), which throws `IllegalStateException("ONNX recognition unavailable…")`
  (`EngineLane.kt:226`). So `TranslationPipeline(context, …)` throws at construction on JVM.
- Existing tests therefore never construct the pipeline
  (`TranslationPipelineConcurrencyTest.kt:19-21` says so explicitly) and construct
  TranslationManager via `sun.misc.Unsafe.allocateInstance` + reflection field injection
  (`TranslationManagerAutoArbitrationTest.kt:187-212`, helper `setField` at 214-227).

The harness reuses that precedent: **real production objects, constructor bodies where they are
JVM-safe, `allocateInstance` + reflection wiring where they are not**. Every reflection-injected
field name below is read from the real class; a production rename fails the harness loudly with
`NoSuchFieldException` (same contract as `uninitializedManager`).

## 1. Harness architecture

New files (all under `app/src/test/java/eu/kanade/translation/coexistence/`):

```
coexistence/TranslationCoexistenceHarness.kt   // fixture: builds the real graph
coexistence/CoexistenceBarrier.kt              // CompletableDeferred gate controller
coexistence/FakeEngines.kt                     // fake PageRecognitionEngine + TextTranslator (+ call counters)
```

### 1.1 Component map (what is real, and how it is built)

| Component | Real class | Construction on JVM |
|---|---|---|
| Manager | `TranslationManager` (`TranslationManager.kt:86-95`; ctor params `context, provider, sourceManager, translationPreferences, downloadProvider`) | `Unsafe.allocateInstance` + setField: `scheduler` (:182), `translator`, `context`, `pendingRequestStore`, `pendingTranslationRequestsState`, `pendingRequestWriteVersions`, `pendingRequestMutationLock`, `pendingRequestGenerationCounters`, `downloadAttachGenerations`, `pendingGroupIdSequence` — exactly the `uninitializedManager` recipe (`TranslationManagerAutoArbitrationTest.kt:187-212`). Plus `pipeline` — see below. |
| Scheduler | `TranslationScheduler` (`TranslationScheduler.kt:51-55`: `executor: TranslationExecutor, storeResolver: TranslationStoreResolver, immediateStoreResolver: ((Long) -> ChapterTranslationStore?)? = null`) | **Real constructor.** `executor` = the harness-built real `TranslationPipeline`. Its own scope is `SupervisorJob() + Dispatchers.IO` (`:78`) — real dispatchers, see §5. |
| Pipeline | `TranslationPipeline` (`TranslationPipeline.kt:75-81`) | `allocateInstance` + setField of: `context`(mockk relaxed), `provider`, `downloadProvider`(mockk), `translationPreferences`, `streamRegistry` = real `TranslationStreamRegistry()`, `nativeRunScope` = `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (`:146`), `nativeRunQuarantine` = real `NativeRunQuarantine(scope)` (`:147`; class at `NativeRunQuarantine.kt:20-22`), `inFlightPageKeys` = `ConcurrentHashMap.newKeySet()` (`:142`), `engineRebuildMutex` = `Mutex()` (`:133`), `engines` (see EngineLane row), `pageStoreWriter` = real `PageStoreWriter(activeStoreResolver = { activeStoreResolver }, streamRegistry, handleCriticalTranslationOom = this::handleCriticalTranslationOom)` (wire per `TranslationPipeline.kt:223-227`), `cleanedPublication`, `singlePageHttpRenderPhase`, `singlePageOnnxPhase`, `batchChapterTranslator` — each built with its **real constructor**, copying the production wiring verbatim (`:472-476`, `:481-491`, `:497-508`, `:803-838`). Then set the same instance fields production sets in `init`: `activeStoreResolver = { store }` (`:214`), `onBatchClosed = null`, `onPageStuck = null`, `batchTrackerFactory = { … registry.createTracker(…) }` (`:165`, pattern from `ChapterTranslatorTerminalExitsTest.kt:72-78`). |
| EngineLane (inside pipeline) | `EngineLane` (`EngineLane.kt:30-36`) — `internal`, same module | `allocateInstance` + setField: `context`, `translationPreferences`, `nativeRunQuarantine`, `inFlightPageKeys`, `onPageStuck = { null }` (`:35`), plus the cached engine fields the `init` would set (`:161-206`): `currentFromLang`, `currentOcrModel`, `currentInpaintingMode`, `currentReadingOrder`, `recognitionEngine` = **fake** `PageRecognitionEngine`, `textTranslator` = **fake** `TextTranslator`, `currentTranslatorSignature`, `enginesClosed = false`. `withNativeLane` (`:45-74`), `closeEngines` (`:229-248`), `ensureEnginesBuiltFor` (`:264-298`) are then the REAL code. |
| Batch driver | `ChapterTranslator` (`ChapterTranslator.kt:76-92`, `pipeline` is ctor param `:91`) + real `TranslationQueueStore(context)` with `InMemorySharedPreferences` | **Real constructor** (pattern: `ChapterTranslatorTerminalExitsTest.kt:82-103`, incl. mock `context` whose `getSharedPreferences` returns `InMemorySharedPreferences`). Drive batches via the internal `translateChapterInternal(translation)` with a reflection-injected active `translationJob` (`ChapterTranslatorTerminalExitsTest.kt:187-208`). |
| Batch pipeline | `BatchChapterTranslator` (`BatchChapterTranslator.kt:61-120`) | **Real constructor** — every native/AI collaborator is already a ctor lambda (the class doc at `:57-59` calls them the "Engine/native collaborators" seam). Wire exactly as `TranslationPipeline.kt:803-838`, substituting the fakes of §1.2 for the ONNX/disk lambdas. |
| Lane workers | `BatchLaneWorkers` (`BatchLaneWorkers.kt:81`, internal) | **Built by real `BatchChapterTranslator.translateBatch`** (`:458-504`) — never built directly. The C-02 code lives at `BatchLaneWorkers.kt:745-763`. |
| Coordinator | `SequentialBatchCoordinator(nativeWorker, translatorWorker, renderJoin, listener)` (`SequentialBatchCoordinator.kt:28-33`) | **Built by real `translateBatch`** (`:507-511`) with the real `BatchRenderJoin` (`:434-448`). |
| Store | `ChapterTranslationStore(translationFile = null, fileCreator = null, initialPages = …)` (`ChapterTranslationStore.kt:76-86`) | **Real constructor**, memory-only (proven: `ActiveChapterStoreRegistryTest.kt:26`, `ChapterTranslatorTerminalExitsTest.kt:123`). Lease API: `tryAcquirePageStageLease` `:402-406`, `releasePageStageLease :408-409`, `releaseAllPageLeases :416-417`, `pageLeaseOwner :419`, `updatePageGuarded :501`, `patchPage :426`, `beginGeneration :389`, `preRegisterPages :1226`. |
| Lease table | `PageStageLeaseTable` (`PageStageLeaseTable.kt:23`) | Real, owned by the store (`ChapterTranslationStore.kt:113`). Two origins only: `PageWriteOrigin.BATCH / READER_ADHOC` (`TranslationStageContracts.kt:14-17`); `LeaseAcquisition.Granted/Denied` (`:182-186`). |
| Rolling auto | `RollingAutoCoordinator(executor, computeClass, memoryGate, injectedScope, predecessorCoordinators, ownerVersion)` (`RollingAutoCoordinator.kt:61-68`) | **Real**, created by the real scheduler inside `updateAutoWindow` (`TranslationScheduler.kt:171-176`). Pass `injectedScope` only through the coordinator ctor where the harness needs a test scope; default path is production-identical. |
| Registry / trackers | `ActiveChapterStoreRegistry` (`ActiveChapterStoreRegistry.kt`), `TranslationBatchTrackerRegistry.createTracker(chapterId, store, orderedPageKeys, scope)` | **Real constructors** (`ChapterTranslatorTerminalExitsTest.kt:82-85`). |

### 1.2 Fakes — only at the four sanctioned externals

1. **ONNX detection/OCR/inpaint** = fake `PageRecognitionEngine` (`PageRecognitionEngine.kt:8-13`,
   interface: `analyze(bitmap): PageTranslation`, `inpaint(bitmap, pageTranslation): Bitmap?`,
   `close()`) under `EngineLane.recognitionEngine`; for the **batch** lane, the fake is the
   `analyzePage` / `inpaintPage` / `decodePageBitmapForTranslation` / `persistCleanedBitmap`
   constructor lambdas of `BatchChapterTranslator` (`BatchChapterTranslator.kt:82-112`) — the
   exact seam production leaves open for them. `DecodedPage` (`PageDecode.kt:178-186`) carries a
   `Bitmap`: produce one with `sun.misc.Unsafe.allocateInstance(Bitmap::class.java)`; the harness
   never calls its methods (recycle sites are try/catch-guarded: `BatchRenderJoin.kt` finally
   `try { bitmap?.recycle() } catch (_: Exception) {}`; `BatchChapterTranslator.kt:528-531`).
2. **Provider HTTP transport** = fake `TextTranslator` (`TextTranslator.kt:11-17`:
   `fromLang`, `toLang`, `suspend fun translate(pages: MutableMap<String, PageTranslation>)`;
   `translatePage` delegates to `translate`). Batch standard lane calls
   `textTranslator.translatePage(pageKey, p)` (`BatchLaneWorkers.kt:1293`); single-page path via
   `SinglePageHttpRenderPhase`. The fake sets `block.translation = "tr-"+text` on every block so
   `TranslationBlockValidation.applyTo` lands `StageStatus.READY`
   (`TranslationBlockValidation.kt:75-93`), records per-page call counts (the paid-call oracle),
   and hosts the PROVIDER barriers. Keep prefs `translationEngineCategory() != AI_MODEL` so the
   batch uses this standard lane, and note `TranslatorComputeClass.forTranslator` maps unknown
   translators to `REMOTE_IO` (`TranslatorComputeClass.kt:57-73`) — no extra wiring needed.
3. **Document/disk I/O**: reuse `FakeChapterDocumentIo` (`artifact/FakeChapterDocumentIo.kt`)
   where a store needs an artifact backend (not needed for the memory-only store above);
   `mockkStatic("eu.kanade.translation.util.ChapterPagesKt")` to stub `getChapterPages`
   (precedent + rationale: `ChapterTranslatorTerminalExitsTest.kt:34-36,167-169`); and TWO
   documented Android-graphics shims, both disk/render IO, **not** coexistence collaborators:
   - `mockkObject(PageDecode)`: stub
     `decodePageBitmapForTranslation(context, recognitionEngineFn, fileName, streamFn)`
     (`PageDecode.kt`, called at `TranslationPipeline.kt:1029-1030`) — the single-page decode
     seam (the batch path needs no stub: it uses the ctor lambda).
   - `mockkObject(RenderColorEstimator)`: stub `recomputeFor(Bitmap?, List<TranslationBlock>)`
     (`RenderColorEstimator.kt:288`) to no-op. Without it, `estimate` calls `bitmap.width` /
     `bitmap.getPixels` (`:123-132`) which throw on the JVM android.jar (real render commit
     `store.mergeRender(patch)` at `BatchRenderJoin.kt:186-230` stays REAL).
   - Single-page cleaned-image publication: reflectively replace `pipeline.cleanedPublication`
     with `mockk<CleanedPublication>()` answering
     `persistOnnxCleanedImage(…) answers { it.invocation.args[4] }` (echo the `result` arg) —
     models "cleaned image durable" without `Bitmap.compress` (`CleanedPublication.kt:130`).
     Wire site: `TranslationPipeline.kt:472-476`, delegate `:949-955`.
4. **Android preferences** = `InMemorySharedPreferences` (`InMemorySharedPreferences.kt`),
   as in `ChapterTranslatorTerminalExitsTest.kt:66-68`.

For the D4 manager-level test, keep the existing mock-based fixture
(`TranslationManagerAutoArbitrationTest.kt:187-212`) — no pipeline needed there.

## 2. Barrier API

`CoexistenceBarrier` — CompletableDeferred gates, **no sleeps, no polling**:

```kotlin
enum class BarrierPoint { NATIVE_ACQUIRE, NATIVE_RELEASE, PROVIDER_START, PROVIDER_END,
                          RENDER, COMMIT, ENGINE_CLOSE, RECONCILE }

class CoexistenceBarrier(testScope: CoroutineScope) {
    val arrivals: StateFlow<List<Pair<BarrierPoint, String>>>          // (point, pageKey) event log
    fun arm(point: BarrierPoint, pageKey: String? = null): CompletableDeferred<Unit>
        // next arrival matching (point[, page]) completes the deferred AND parks the pipeline
    fun release(point: BarrierPoint, pageKey: String? = null)          // unpark (one waiter)
    fun disarm(point: BarrierPoint, pageKey: String? = null)           // pass-through from now on
    suspend fun awaitArrival(point: BarrierPoint, pageKey: String? = null)  // observe-only
}
```

Suspension points live **inside the fakes**, so the code between barriers is the real graph:

| Barrier | Where the fake suspends | Production event it names |
|---|---|---|
| NATIVE_ACQUIRE | entry of fake decode (batch: `decodePageBitmapForTranslationFn` lambda; single-page: `mockkObject(PageDecode)` stub) | first native work after `withNativeLane` admits the page (`EngineLane.kt:45-74` → `NativeRunQuarantine.run`, `NativeRunQuarantine.kt:41-80`) |
| NATIVE_RELEASE | return gate of fake `analyze`/`inpaintPage` | native lane exit / real permit release |
| PROVIDER_START / END | entry/exit of fake `TextTranslator.translate` (`BatchLaneWorkers.kt:1293`, `SinglePageHttpRenderPhase`) | paid provider call |
| RENDER | fake `loadPersistedCleanedBitmapFn` lambda — the real render path's first action (`BatchRenderJoin.kt:154`) | render join / `tryRender` |
| COMMIT | no injection point (store is real and final). Provided as an awaiter: `testScope.launch { store.state.first { it[page]?.renderStatus == StageStatus.READY } }` completes the COMMIT arrival. Event-driven (`StateFlow.first{}`), not polling. | durable stage commit (`store.updatePageGuarded :501`, `mergeRender`) |
| ENGINE_CLOSE | fake engines' `close()` (`EngineLane.closeEngines :229-248` calls both) | engine teardown on stop/complete |
| RECONCILE | no injection point. Await the `Deferred<ReconciliationResult?>` returned by real `translateBatch` (`BatchChapterTranslator.kt:186-194,613-637`) or the tracker terminal snapshot (`ChapterTranslatorTerminalExitsTest.kt:108-112`). | `BatchProgressReconciler.reconcile` |

Determinism rules: a test arms gates **before** launching the corresponding coroutine; every
waiter is a `CompletableDeferred`/`StateFlow.first{}`; all production scopes use real
dispatchers, so awaits must sit in `runBlocking { withTimeout(10_000) { … } }` (the codebase's
existing integration-test style) rather than virtual time. §8 of PLAN.md requires 100
consistent local runs before `checkpoint/t917-p1-done`.

## 3. Test inventory

### 3.1 `coexistence/D2ManualBatchInterleavingTest.kt`

Fixture: real scheduler + real pipeline + real store (`p0`,`p1` pre-registered), real
`ChapterTranslator` batch, fake engines/transport.

1. `batch to manual — tap while batch holds the page at PROVIDER_START, PROVIDER_END, and RENDER`
   - Choreography (per barrier X in that list): batch parks p0 at X; `manager.translatePage(..., "p0")`
     (real entry: `TranslationManager.kt:1573-1574` → `TranslationScheduler.translatePage :568-630`
     → real `pipeline.translateSinglePage :315-334`); then `barrier.release(X)`; join both.
   - Oracle (target contract, D2 wait-and-attach): manual job is NOT complete while batch still
     owns the page (`store.pageLeaseOwner("p0") == PageWriteOrigin.BATCH`); manual completes only
     after batch COMMIT + lease release; fake transport counted **exactly 1** call for p0.
   - RED reason (C-01): `acquireReaderPageLease` logs and returns `false` on
     `LeaseAcquisition.Denied` (`TranslationPipeline.kt:441-457`) and the boundary returns
     immediately (`:376-377`); scheduler's `finally` removes the job (`:603-611`). The assertion
     "manual still active while parked at X" fails at the first barrier — manual finished without
     waiting, with no completion signal, and no attach. Paid-call count is 1 today only because
     manual did nothing; keep the count assertion (guards Alternative-A preemption regressions
     in Phase 2).
2. `manual to batch — batch must not start paid work on the manually-owned page`
   - Choreography: arm NATIVE_ACQUIRE; `manager.translatePage("p0")` parks inside fake decode
     holding the READER_ADHOC lease (real `runSinglePageBoundary :377` granted) and the real
     native permit; start the batch; assert batch engine-setup is blocked behind the permit
     (deterministic — real `withNativeLane` at `BatchChapterTranslator.kt:226-250`); release
     NATIVE gates and PROVIDER gates until manual parks at PROVIDER_END; then release manual to
     completion and let the batch run p0.
   - Oracle: manual completes (render/commit real); batch reaches p0 only after the lease is
     free; total paid calls for p0 == 1.
   - RED reason: today the batch does not wait/rescan — `BatchLaneWorkers.runOcrStage` returns
     `null` on `LeaseAcquisition.Denied` (`BatchLaneWorkers.kt:745-763`) and the coordinator
     records a plain skip (`SequentialBatchCoordinator.kt:98-105`), so if the manual releases
     the lease *after* the batch passed p0, the page is never re-translated in that pass — the
     duplicate/stranding oracle below fails. (If release ordering makes this test's tail pass in
     isolation, tighten the choreography so the batch reaches p0 while manual still holds the
     lease; either way the "exactly-once paid call, one terminal state" oracle is the contract.)

### 3.2 `coexistence/D3ReaderOwnedPageAcrossBatchTest.kt`

- Scenario: reader owns p0 across a **full** batch (manual parked at PROVIDER_END — lease held,
  native permit free, so the batch runs); p1 is free and must complete end-to-end.
- Choreography: park manual on p0 (as in D2.2, through the cleaned-publication shim to reach the
  provider); start batch via `translateChapterInternal`; p1 completes all real stages (native →
  provider → `mergeRender` COMMIT); batch passes p0 (denied, skipped); RECONCILE awaited.
- Oracle: `reconciliation.strandedPages` empty; `reconciliation.chapterStatus == TRANSLATED`;
  p0 ends in exactly one terminal state and is re-translated within the same pass (defer-and-
  rescan, D3); tracker terminal snapshot shows 2/2 with no stranded/error states
  (`TranslationBatchTrackerRegistry` terminal cache, `ChapterTranslatorTerminalExitsTest.kt:108-112`).
- RED reason (C-02): p0 is skipped, never rescanned (`BatchLaneWorkers.kt:745-763` — "skipped
  this pass and rescanned later" is only the log text; the existing coordinator test encodes the
  skip as intended: `SequentialBatchCoordinatorTest.kt:372-406`). Reconciliation then strands it
  ("expected page was stranded…" / "left cancelled or non-terminal",
  `BatchProgressReconciler.kt:69-99`) and the chapter projects ERROR (`:101-107`); any stranded
  cleanup write goes through `guardedBatchUpdate`, which rejects without a batch identity
  ("batch page lease missing", `BatchWriteGate.kt:85-112`). Assert `strandedPages` empty → RED.

### 3.3 D4 — update `TranslationManagerAutoArbitrationTest.kt` (contract change)

- Test renamed: `manager suppresses same-chapter auto while the chapter batch is queued`
  (replaces `manager keeps auto window active while the chapter batch is queued`, lines 74-185).
- What changes: after `manager.translateChapter(...)` while the queue entry is active, the
  subsequent `manager.updateAutoWindow(...)` must **not** re-arm — `scheduler.autoSnapshot`
  stays `null` — and `manager.reconcileAutoWindow()` must not resurrect it. Re-arm succeeds
  again only after the queue drains (`queue.value = emptyList()`), verifying D4 is a
  batch-*lifetime* gate, not the one-shot shutdown at `translateChapter`
  (`TranslationManager.kt:664-692`, `:713-748`).
- Why it is a contract change: today `updateAutoWindow`'s only guards are
  `readerStopInFlight || globalAutoCancellationInFlight || chapterCancellationEpochs`
  (`TranslationScheduler.kt:158-160`) — there is no batch-active gate (M-06: the re-arm path's
  guard set contains no batch gate; `openTranslationSession` likewise,
  `TranslationManager.kt:1334-1353`). The existing test *requires* re-arm
  (`TranslationManagerAutoArbitrationTest.kt:124-140`); flipping it encodes the adopted D4
  Recommendation (draft §6 D4). Record in `PHASE-LOG.md` per PLAN.md §5 risk table.
- RED: with production unchanged, re-arm still succeeds → the new "stays null" assertion fails.

### 3.4 `coexistence/NormalMangaIsolationTest.kt` (gates all later phases)

- Scenario: a chapter with no reader session, no auto window, and translation-disabled state is
  present next to an active batch chapter.
- Observable oracle (all event-driven):
  1. no coordinator: `scheduler.autoSnapshot.value == null` after `reconcileAutoWindow()` — no
     `RollingAutoCoordinator` was created for it (real construction only happens in
     `updateAutoWindow`, `TranslationScheduler.kt:171-180`);
  2. no storage observation: `manager.selectActiveStore(disabledChapterId)` never emits a
     non-empty map and `observeActiveDisplayStore` returns `null` (no store was ever opened in
     the real `ActiveChapterStoreRegistry`, `TranslationManager.kt:1400-1407`);
  3. no extra decode: fake-decode call counter total for the disabled chapter == 0 (NATIVE gate
     arrivals log is the counter), i.e., no arbitration path ever reached the native lane.
- This is a harness-level proof that the *arbitration/observation entry points* are the only
  gates: the preference-level gating lives in `ReaderViewModel` (outside this graph); if the
  Implementer finds a manager-level translation-enabled check to hook, assert through it and
  note it in `review/phase1-verification.md`. This test must be GREEN at Phase 1 exit
  (PLAN.md Phase 1 exit: new D2/D3/D4 tests red; isolation + baseline green).

## 4. Run commands (Windows Git Bash)

```bash
export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"   # Gradle 8.12 verified (Phase 0)
cd "C:\Users\User\Documents\TachiyomiAT-1.16.8-dev\TachiyomiAT-1.16.8-dev\TachiyomiAT-1.16.8-dev"

# Harness + new coexistence tests only (fast loop):
./gradlew :app:testStandardDebugUnitTest \
  --tests "eu.kanade.translation.coexistence.*" \
  --tests "eu.kanade.translation.TranslationManagerAutoArbitrationTest" \
  --tests "eu.kanade.translation.pipeline.batch.SequentialBatchCoordinatorTest" \
  --tests "eu.kanade.translation.ChapterTranslatorTerminalExitsTest"

# Baseline-green check before tagging p1-done (touched-module unit tests):
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"
```

Keep each run under 10 min: the `--tests` filters above are mandatory; the dev flavor
`testDevDebugUnitTest` is an acceptable faster substitute if configured identically. Record the
exact command + result in `PHASE-LOG.md` (PLAN.md §2).

## 5. Risks / unknowns

1. **Android framework at init.** Any unguarded android.jar call throws. Mitigations already
   designed in: Unsafe `Bitmap` dummies (never invoked; recycle sites guarded,
   `BatchChapterTranslator.kt:528-531`), `RenderColorEstimator` + single-page decode +
   cleaned-publication shims (§1.2), `mockkStatic(ChapterPagesKt)` for enumeration. If a test
   still trips a stub ("Method … not mocked"), trace it before adding new shims; add each new
   shim to §1.2's list in the verification report.
2. **Dispatchers.** All real components run on `Dispatchers.IO/Default` scopes they create
   themselves (`TranslationScheduler.kt:78`, `ChapterTranslator.kt:250` [scope field],
   `TranslationPipeline.kt:146`). Do NOT use `StandardTestDispatcher`/`advanceUntilIdle` for the
   graph — virtual time does not reach those scopes. Use `runBlocking` + `withTimeout` +
   barrier awaits. (`kotlinx-coroutines-test`'s `runTest` is fine only for pure coordinator
   tests like `SequentialBatchCoordinatorTest.kt:4`.)
3. **StateFlow bookkeeping.** `autoSnapshot` is a `flatMapLatest`+`stateIn(Eagerly)` flow
   (`TranslationScheduler.kt:113-116`); re-arm/settle assertions must `first { }` on it with a
   timeout (as the existing test does, `:121`). Store `state` conflates; oracles must assert on
   the final map, not on intermediate emissions.
4. **Reflection fragility.** The pipeline/EngineLane field sets are name-coupled. Keep ALL
   reflection in one factory function with a single list of (name, value) pairs; a
   `NoSuchFieldException` there means production moved — re-derive from the cited wiring lines.
5. **Engine-setup blocking.** `translateBatch` holds the native permit for engine setup
   (`BatchChapterTranslator.kt:226-250`) — any manual holding the permit at batch start makes
   the batch wait (use it deliberately as in D2.2; never leave it armed across `release`
   mistakes, or the test deadlocks — always `withTimeout` the batch job).
6. **Unknown (flagged, do not invent):** whether `Translation.fromChapterId` (used by
   `restoreQueue`, `Translation.kt:52`) is safe on JVM. The harness never calls `restoreQueue`;
   queue entries are injected directly as in `TranslationManagerAutoArbitrationTest.kt:99-105`.
7. **Paid-call oracle granularity.** The fake counts `translate(pages)` invocations; a provider
   retry inside one call is invisible at this layer — acceptable for Phase 1 (C-01/C-02 do not
   change retry policy; D6/D9 counting comes in Phase 3).

## 6. Non-goals

- No production behavior change in Phase 1; no fix for C-01/C-02/C-03 — the D2/D3/D4 tests stay
  RED and must fail for the reasons in §3 (assert the target contract, not today's behavior).
- No Robolectric introduction; harness + tests live only under `app/src/test`.
- No new dependency: JUnit 5.11.4, kotest-assertions 5.9.1, mockk 1.13.14
  (`gradle/libs.versions.toml:104-106`, bundle `:132`, `app/build.gradle.kts:364`) and
  `kotlinx-coroutines-test` (BOM 1.10.1, `gradle/kotlinx.versions.toml:11-17`,
  `app/build.gradle.kts:372`) are already on the classpath. **No dependency addition is
  required.**
- No UI/ReaderViewModel work (Phase 5), no downloader/reader fixes (README scope "Out").
