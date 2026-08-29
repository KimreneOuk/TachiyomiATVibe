# Pipeline Investigation — `translation/TranslationPipeline.kt`

Task: T909 god-file dismantling · Owner: Technical Lead investigator · Date: 2026-08-29
Branch: `optimize_translation_finishing_page` · Evidence basis: full read of the file + caller/test greps.

**Measured size: 5,300 lines** (task table says 5,415; commit `73c126c` "remove verified-dead code
and deduplicate helpers" shrank it — use 5,300 for planning). One class, `TranslationPipeline`
(L152–5300), plus 2 tiny top-level declarations (L136, L138–150).

---

## 1. DECLARATION MAP

### Top level

| Lines | Declaration | Responsibility |
|---|---|---|
| 136 | `class LayoutFailureException(val blockIds)` | carries failed block IDs out of batch render layout |
| 138–150 | `private fun ProviderFailure.toFailureCategory()` | maps provider failure kinds → artifact `FailureCategory` |

### `class TranslationPipeline(...)` L152–5300 — implements `TranslationExecutor`, `Closeable`

Constructor deps (L153–157): `context: Context`, `provider: TranslationProvider`,
`downloadProvider: DownloadProvider`, `translationPreferences: TranslationPreferences`,
`streamRegistry: TranslationStreamRegistry` (all but context default to `Injekt.get()`).
Constructed eagerly by `TranslationManager` (TranslationManager.kt:95) and as a default
constructor arg of `ChapterTranslator` (ChapterTranslator.kt:69–75).

| Lines | Region | One-line responsibility |
|---|---|---|
| 160–162 | `close()` | cancels `nativeRunScope` |
| 164–217 | companion constants | `SINGLE_PAGE_TIMEOUT_MS` (read externally by ReaderViewModel.kt:2525), `SINGLE_PAGE_PARTIAL_MAX_RETRIES`, `ONNX_PHASE_TIMEOUT_MS`, `HELD_BITMAP_MAX_COUNT/_BYTE_CEILING`, `UNKNOWN_SOURCE_FINGERPRINT` |
| 219–230 | `BatchResumeGate` enum, `PermitHolder`, `permitHolder`, `permitHolderPageKeySnapshot()` | native-lane holder identity; `internal` accessor used by TranslationManager.kt:1340,1464 |
| 232–246 | `engineRebuildMutex`, `inFlightPageKeys`, `nativeRunScope`, `nativeRunQuarantine` | concurrency infrastructure |
| 248–264 | `onPageStuck`, `batchTrackerFactory` | outbound callbacks wired by TranslationManager (194, 201) |
| 266–295 | `withNativeLane(...)` | the sole native-permit admission wrapper (quarantine + timeout + holder bookkeeping) |
| 297–347 | engine cache `@Volatile` fields + `EngineSignature` | cached recognition/translator instances + full config fingerprint |
| 349–373 | `computeTranslatorSignature()` | folds all engine-selection prefs into `EngineSignature` |
| 375–420 | `init` | defensive engine build; invalid config must not crash startup (falls back to throwing stub translator L407–414) |
| 422–462 | `inpaintingModeFromPref`, `createRecognitionEngine`, `closeEngines` | engine factory + torn-down-under-quarantine |
| 464–480 | `consecutiveOomCount`, `enginesClosed`, `activeStoreResolver`, `onBatchClosed` | remaining mutable fields; `activeStoreResolver` wired by TranslationManager:163, read by ChapterTranslator.kt:237,520 |
| 482–521 | `peekReaderPageStream`, `createFailedPagePlaceholder` | stream peek + canonical FAILED `PageTranslation` factory |
| 523–660 | store-patch helpers: `resolveActiveStore`, `markPageTimedOut`, `PageSnapshot.toPrecondition`, `updatePageFromCurrentSnapshot`, `markPageFailed` | guarded durable failure/timeout writes shared by ALL paths |
| 662–798 | `translateSinglePage` / `translateSinglePageFromStream` (overrides) + `runSinglePageBoundary` | reader ad-hoc path: lease → ONNX lane → persist → HTTP+render, with per-phase failure marking |
| 800–870 | `acquireReaderPageLease`, `releaseReaderPageLease`, `releaseBatchPageLease`, `deleteRetiredCleanedFile` | page-lease admission (Phase 3) + superseded cleaned-file drain |
| 872–974 | `prepareSinglePage` (override) | prepared-page boundary native half; durable-before-publish |
| 976–1003 | `buildTerminalPreparedPage` | inspects store after null native result; terminal vs soft-skip |
| 1005–1147 | `translatePreparedPage` (override) | prepared-page translate/render half; stale-reference rejection (gen/version/fingerprints L1051–1062) |
| 1149–3360 | **`translateBatch` (2,212 lines)** | whole-chapter staged batch; see breakdown below |
| 3362–3435 | `markBatchTranslationFailed`, `persistUnexpectedBatchStageFailure` | batch durable failure writes |
| 3437–3444 | `BatchWriteIdentity` data class | per-page lease/identity bookkeeping |
| 3446–3491 | `ensureEnginesBuiltFor` | shared engine rebuild gate (signature + closed engines) |
| 3493–3813 | `translateSinglePageOnnx` (321) | permit-held ONNX phase: resume-plan fork (PageWorkPlanner L3554), stream resolution, decode, OCR/inpaint via `processSinglePage`, builds `OnnxPhaseResult` |
| 3815–4228 | `translateSinglePageHttpRender` (414) | permit-free HTTP translate (contextual or plain, PARTIAL retry loop L3969–3983), color recompute, render, guarded commit |
| 4230–4259 | `copyForResume`, `loadPersistedCleanedBitmap` | resume copy hygiene + disk reload |
| 4261–4292 | `getContextualTranslator` (32) | **DEAD CODE — zero callers in app/src or tests** |
| 4294–4428 | `persistCleanedBitmap`, `persistOnnxCleanedImage` | cleaned-image JPEG publication via extracted `CleanedImagePublisher` |
| 4430–4563 | `renderResumedPage`, `resumeInpaintAndRender` | resume paths (render-only / inpaint-only) |
| 4565–4650 | `retryInpaintDownscaled` | half-sample re-recognize recovery when inpaint produced nothing |
| 4652–4788 | `analyzePage` (137) | batch stage 1: detect+OCR + `store.mergeOcr` preconditioned persist |
| 4790–4852 | `inpaintPage` | batch stage 2: inpaint with guarded writes |
| 4854–4984 | `processSinglePage` (131) | fused single-page analyze+inpaint with per-stage OOM/deferred handling |
| 4986–5018 | `forceReleaseNativeBuffers`, `preflightAnalyzeGate`, `preflightInpaintGate` | native release + memory preflight gates |
| 5020–5072 | `reclaimTranslationMemory`, `logDecodeDecision`, `toMiB`, `handleCriticalTranslationOom` | memory reclaim + decode logging |
| 5074–5115 | `persistPageWithOomRecovery` | persist with OOM retry ladder |
| 5117–5196 | `decodePageBitmapAtSize`, `decodePageBitmapForTranslation` | buffered decode + `TranslationMemoryBudget` decision + SHA-256 source fingerprint |
| 5198–5216 | `computeSourceFingerprint` | streaming SHA-256 |
| 5218–5250 | `batchExpectedFingerprints` | per-stage expected fingerprints from current engine config |
| 5252–5296 | `DecodedPage`, `OnnxPhaseResult`, `LowMemoryDecodeDeferredException`, `LowMemoryRecognitionDeferredException` | value types + control-flow exceptions |
| 5298–5299 | `getChapterPages` | delegates to `util.getChapterPages` |

### Inside `translateBatch` (L1149–3360) — all locals, one closure web

| Lines | Local declaration | Notes |
|---|---|---|
| 1204–1279 | batch state: `batchWriteIdentities`, `durableFailurePageKeys`, `ensureCompanionDir`, `glossaryStats`, `heldBitmapBytes`, `countSlots`, `bitmapRegistry`, `translationRegistry`, `renderMutexes`, `aborted`, `expectedBatchFingerprints`, `contextFrontier`, `rollingContext` | ~13 mutable registries closed over by everything below |
| 1281–1301 | `recordContextPage`, `sourceFingerprints` (IO hash preflight) | |
| 1303–1441 | `stampBatchProvenance`, `buildBatchPagePlans`, `translationFailureFence`, frontier seeding, `plannedTranslationDecision/NeedsWork`, `plannedRenderNeedsWork` | planner-driven resume decisions |
| 1442–1605 | `guardedBatchUpdate`, `refreshBatchIdentity`, `batchWritePrecondition`, `persistAiFailure`, `persistAiFailureOrThrow`, `releaseBatchLease`, `persistBatchPageWithOomRecovery` | the batch write gate |
| 1607–1688 | `resumeGate` | `SKIP_ALL / INPAINT_ONLY / FULL` incl. metadata-only cleaned-image invalidation (disk existence checks) |
| 1690–1730 | `holdCleaned`, `recycleHeld`, `abortBatchCandidate` | bounded in-memory cleaned-bitmap registry |
| 1732–1901 | `tryRender` (170) | render join + `RenderStagePatch`/`store.mergeRender` commit |
| 1903–2315 | `translateChunkAi` (~413) | AI envelope: adaptive retry, glossary, per-page completion |
| 2316–2346 | `completeChunklessPage` | |
| 2365–2724 | `nativeWorker : NativeLaneWorker` (360) — `runOcrStage` (lease acquire, resume gate, decode, `analyzePage`, handoff), `runInpaintStage` (2539–2706), `releaseNativeHandoff` | adapter to extracted coordinator |
| 2726–3176 | `translatorWorker : TranslatorLaneWorker` (451) — `StreamingChunkPlanner` state, `admit`/`translateOutcome`/`translate`, `processAiEmission` (3032), `completeChunkOutcome` (3065) | adapter |
| 3177–3214 | `signalFor` + `renderJoin : RenderJoinWorker` | two-branch render join |
| 3216–3245 | `SequentialBatchCoordinator` + `runPass1` + held-bitmap leak sweep | |
| 3247–3340 | pause/stop reconciliation, stranded-page writes, `tracker.finish` | |
| 3341–3358 | outer `finally`: lease release/cancel, `NonCancellable` flush, `reconcileArtifactRetention`, `onBatchClosed` | |

---

## 2. INTERNAL COUPLING — shared mutable state

Class-level state and the regions that touch it (VERIFIED by grep + read):

| State | Decl | Writers/readers (regions) | Entanglement |
|---|---|---|---|
| `permitHolder` | 227–228 | `withNativeLane` only; read via `permitHolderPageKeySnapshot()` (230) by TranslationManager | LOW — clean extraction with the lane |
| `nativeRunScope`/`nativeRunQuarantine` | 245–246 | `close` (161), `withNativeLane`, `closeEngines` (449) | LOW |
| `inFlightPageKeys` | 241 | `runSinglePageBoundary` (751/771), `prepareSinglePage` (910/930), `closeEngines` (447) | LOW |
| `onPageStuck`, `batchTrackerFactory`, `activeStoreResolver`, `onBatchClosed` | 256/264/476/480 | set once by TranslationManager (194/201/163/173); invoked at 284, ChapterTranslator.kt:613, `resolveActiveStore` 550 + `translateSinglePageOnnx` 3532, batch finally 3357 | LOW-MED — callbacks are the pipeline's outbound API; keep as interface |
| engine cache: `currentFromLang/OcrModel/ReadingOrder`, `textTranslator`, `recognitionEngine`, `currentInpaintingMode`, `currentTranslatorSignature`, `enginesClosed` | 297–328, 464–468 | `init` (375–420), `closeEngines` (443–462), `ensureEnginesBuiltFor` (3457–3491), `batchExpectedFingerprints` (5222–5248), `persistCleanedBitmap` (4346, 4372), `retryInpaintDownscaled` (4638), `translateSinglePageHttpRender` (3867, 4224), batch log (1236), `nativeWorker.releaseDecodedPage` (2721) | **HIGH — hotspot #1**. Eight fields touched from 10+ scattered sites across single-page, batch, and lifecycle code |
| `engineRebuildMutex` | 232 | `translateBatch` (1220), `translateSinglePageOnnx` (3527) | belongs with engine cache |
| `consecutiveOomCount` | 464–465 | `analyzePage` (4691/4708/4722), `processSinglePage` (4873/4890/4904/4949) | LOW — OCR-stage-local |

The far bigger entanglement is **inside `translateBatch`: ~13 local mutable registries
(batch state L1204–1279) closed over by ~25 local functions and 3 anonymous worker objects**.
Usage density: `translationRegistry` 29 refs, `batchWriteIdentities` 12 refs, `contextFrontier`
10 refs, `tryRender`/`guardedBatchUpdate`/`resumeGate` etc. called from all three worker
objects (grep lines 1442–3326). **Hotspot #2: the batch closure web.** Nothing outside the
function can reach any of it — the entire web is movable as a unit, but only as a unit.

What can move CHEAPLY: decode/fingerprint/memory utilities (pure over injected deps + engine
reads), store-patch helpers (depend only on `activeStoreResolver` + `provider`),
cleaned-image publication (already half-extracted via `CleanedImagePublisher`).
What is ENTANGLED: engine cache (hotspot #1), batch closure web (hotspot #2), and the
`OnnxPhaseResult` handoff that carries a live `cleanedBitmap` across the permit boundary
(L3493–3813 → 3815–4228) with precise recycle discipline.

---

## 3. NATURAL SEAMS

### S1 — Single-page reader path (decode/prepare → OCR → translate → render)
Lines 662–798 + 3493–4228 + 4230–4650 + 4854–4984. Internal deps: native lane, engine cache,
store helpers, persist/publish cluster. Production entry: `TranslationExecutor`
(scheduling/TranslationExecutor.kt:39) implemented here; consumers are `ChapterTranslator`
(wraps pipeline), `RollingAutoCoordinator` (scheduling/RollingAutoCoordinator.kt:61), and
`TranslationScheduler` (scheduling/TranslationScheduler.kt:52) — all behind the interface.
Tests: **no test constructs the pipeline**; `PreparedPageRuntimeBoundaryTest` pins the boundary
semantics at store level, `CancelSyncStoreWriteTest` and `RollingAutoCoordinatorTest` use fakes
of `TranslationExecutor`, `AiTranslationRetryPlannerSinglePageTest` pins the PARTIAL-retry
predicate in isolation.

### S2 — Prepared-page boundary
Lines 872–1147. Same deps as S1 (it is S1 split in two). Callers: RollingAutoCoordinator via
interface. Tests: PreparedPageRuntimeBoundaryTest (contract descriptions), RollingAutoCoordinatorTest.

### S3 — Batch orchestration (translate + resume/recovery)
Lines 1149–3360 + 3362–3444. Caller: ChapterTranslator.kt:624 only. Tests: the `batch/` package
(16 test files) pins the EXTRACTED pieces (`SequentialBatchCoordinator`, `BatchProgressReconciler`,
`BatchResumeGateDecider`, `BatchContextFrontier`, …); `Phase0BatchTranslationCharacterizationTest`
pins `ResumeOrdering`, not the pipeline body. **No direct test executes `translateBatch`** —
the pipeline-internal half (resume gates, write gate, tryRender, chunk AI) is pinned only
end-to-end by the full suite. Precedent: `batch/BatchResumeGateDecider.kt:11` documents an
earlier extraction from this very function.

### S4 — Native lane / admission
Lines 219–295, 443–462, 4986–4990. Self-contained; only callback is `onPageStuck`.
Tests: TranslationPipelineConcurrencyTest replicates the `inFlightPageKeys` pattern in isolation
(it never builds a pipeline).

### S5 — Store patch / failure writing
Lines 482–660 (+ `persistPageWithOomRecovery` 5074–5115, `markBatchTranslationFailed` 3362–3383).
Deps: `activeStoreResolver`, `provider`, `ChapterTranslationStore` guarded-write API.
Tests: ChapterTranslationStore* suite pins the store API these call into.

### S6 — Cleaned-image publication
Lines 839–870, 4237–4259, 4294–4428. Already delegates to `CleanedImagePublisher` (own file +
CleanedImagePublisherTest). Deps: `provider`, `streamRegistry`, `store`, `currentInpaintingMode`.

### S7 — Decode + memory governance
Lines 4992–5072, 5074–5115, 5117–5250, 5252–5299. Deps: `TranslationMemoryBudget` (util/, pure),
`BitmapPool`, `context.imageLoader` (one Coil trim), engine reads only for fingerprints.
Tests: TranslationMemoryBudget tested in util/; decode helpers untested directly.

---

## 4. EXTRACTION CANDIDATES (ranked by low risk × high line yield)

Proposed package: `eu.kanade.translation.pipeline` (new), keeping `TranslationPipeline` as the
orchestration facade implementing `TranslationExecutor` + owning `translateBatch` until phase 7.

| # | Candidate | New file(s) | Moves (lines) | Line yield | Risk | Why |
|---|---|---|---|---|---|---|
| 1 | **Decode/memory utilities** | `pipeline/PageDecode.kt` + `pipeline/MemoryGovernance.kt` | 4986–5072, 5117–5299 (+`DecodedPage`,`LowMemory*`) | ~390 | LOW | near-pure functions over `TranslationMemoryBudget`/`BitmapPool`/prefs; only coupling is `recognitionEngine` reads for release (inject engine getter) |
| 2 | **Store patch / failure writer** | `pipeline/PageStoreWriter.kt` | 482–660, 5074–5115 | ~240 | LOW | stateless over injected resolver+store; used identically by all paths |
| 3 | **Cleaned-image publication** | `pipeline/CleanedPublication.kt` | 839–870, 4230–4259, 4294–4428 | ~230 | LOW | wraps already-extracted `CleanedImagePublisher`; needs `currentInpaintingMode` read (pass as param) |
| 4 | **Engine lane holder** | `pipeline/EngineLane.kt` | 219–295, 297–472, 3446–3491 (fields + `withNativeLane`, `closeEngines`, `ensureEnginesBuiltFor`, `EngineSignature`, `computeTranslatorSignature`, `createRecognitionEngine`, gates) + delete dead `getContextualTranslator` (4261–4292) | ~460 (incl. −32 dead) | MEDIUM | hotspot #1 but the boundary is crisp: 8 fields + 6 methods that only talk to prefs; pipeline keeps a `val engines: EngineLane` |
| 5 | **Single-page HTTP+render phase** | `pipeline/SinglePageHttpRenderPhase.kt` | 3815–4228 | ~415 | MEDIUM | deps expressible as ctor params (store, translator via EngineLane, prefs, publisher, storeWriter); outcome typing already `ChunkCompletionOutcome` |
| 6 | **Single-page ONNX phase + resume** | `pipeline/SinglePageOnnxPhase.kt` | 3493–3813, 4430–4650, 4652–4984 | ~670 | MEDIUM | needs EngineLane + phases 2/3/5 injected; `OnnxPhaseResult` moves with it |
| 7 | **Batch internals** (phased sub-steps, see §5) | `pipeline/batch/HeldBitmapRegistry.kt`, `BatchWriteGate.kt`, `BatchResumePlanner.kt`, `BatchRenderJoin.kt`, `BatchLaneWorkers.kt`, finally `BatchChapterTranslator.kt` | 1149–3360 + 3362–3444 | ~2,240 total (in 5–6 commits) | MEDIUM→HIGH | hotspot #2; only safe as incremental closure→class conversions; the shell (`translateBatch` signature + finally protocol 3341–3358) stays last |

Stays in `TranslationPipeline.kt` (the orchestration core, ~450 lines final): constructor, init,
`TranslationExecutor` overrides delegating to phase objects, `translateBatch` shell + finally
protocol, outbound callbacks, companion constants (`SINGLE_PAGE_TIMEOUT_MS` must stay reachable
as `TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS` for ReaderViewModel.kt:2525 or be re-exported).

---

## 5. EXTRACTION ORDER (each phase = one commit, independently compilable, revertible)

Verification gate per phase = targeted tests, then full `app` JVM test suite. (Implementer runs
gradle; this investigation is read-only.)

- **Phase 1 — Candidate 1 (decode/memory, ~390 lines).** Gate: full suite (no direct tests exist);
  compile is the main risk since members become `internal` top-level fns. Rollback: revert commit.
- **Phase 2 — Candidate 2 (store writer, ~240).** Gate: `ChapterTranslationStorePersistenceTest`,
  `ChapterTranslationStorePhase3Test`, `PostOcrStageSemanticsTest`, full suite.
- **Phase 3 — Candidate 3 (cleaned publication, ~230).** Gate: `CleanedImagePublisherTest`, full suite.
- **Phase 4 — Candidate 4 (EngineLane incl. dead-code delete, ~460).** Gate:
  `TranslationPipelineConcurrencyTest`, `TranslationManagerAutoArbitrationTest`,
  `TranslationManagerReaderTeardownTest`, full suite. Preserves defensive init semantics
  (invalid config must not crash startup, L375–420).
- **Phase 5 — Candidate 5 (HTTP+render phase, ~415).** Gate:
  `AiTranslationRetryPlannerSinglePageTest` (predicate moved with it), `CancelSyncStoreWriteTest`,
  `PreparedPageRuntimeBoundaryTest`, full suite.
- **Phase 6 — Candidate 6 (ONNX phase + resume, ~670).** Gate: same as 5 +
  `RollingAutoCoordinatorTest`, full suite.
- **Phase 7 — Candidate 7 (batch internals), ordered sub-steps, each its own commit:**
  1. `HeldBitmapRegistry` (1690–1712 + constants 196–214, ~90 lines). Gate: full suite.
  2. `BatchWriteGate` (1442–1605, 3437–3444, ~190). Gate: `ChapterTranslatorQueueRestoreTest`, full suite.
  3. `BatchResumePlanner` (1607–1688 + plan closures 1323–1441, ~230). Gate: `BatchResumeGateDeciderTest`, full suite.
  4. `BatchRenderJoin` (`tryRender` 1732–1901 + renderJoin 3177–3214, ~230). Gate: `DisplayReadyStageCountTest`, full suite.
  5. `BatchLaneWorkers` (nativeWorker 2365–2724, translatorWorker 2726–3176, `translateChunkAi` 1903–2315, `completeChunklessPage` 2316–2346, ~1,270). Gate: `SequentialBatchCoordinatorTest`, `BatchTranslateBlockMergeTest`, `Phase0BatchTranslationCharacterizationTest`, full suite.
  6. Rename shell → `pipeline/batch/BatchChapterTranslator.kt`; `TranslationPipeline` delegates (~2,212-net). Rollback per commit.

Manual smoke after phases 5–7 (reader single-page translate, batch translate, resume mid-batch)
because the pipeline body itself has no direct unit test (§3, Blind spot B1).

---

## 6. BLIND SPOTS

- **B1 — No test constructs `TranslationPipeline`.** `TranslationPipelineConcurrencyTest`
  replicates the `inFlightPageKeys` pattern (test L24–58) rather than instantiating the class;
  everything else fakes at the `TranslationExecutor` interface. Behavior regressions in moved
  bodies would pass targeted tests. Mitigation: characterization tests on `PageWorkPlanner`/
  store-merge contracts + manual smoke at phases 5–7.
- **B2 — `nativeHandoff: Any?` hidden type channel** (BatchCoordinatorInterfaces.kt:29;
  cast to private `DecodedPage` at 2594, 2709): the extracted coordinator ships an
  untyped reference to a pipeline-private class. When `DecodedPage` moves (phase 1), keep the
  cast sites compiling; consider typing the field in a later, behavior-preserving commit.
- **B3 — Bitmap lifecycle across the permit boundary.** `OnnxPhaseResult.cleanedBitmap` is
  handed alive from `translateSinglePageOnnx` to `translateSinglePageHttpRender` with recycle
  in `finally` (4210–4219) and defensive double-recycles everywhere; the held-bitmap registry
  balance (`countSlots`/`heldBitmapBytes`, release site 3232–3244) is exact. Extraction must not
  shift any recycle/ownership point.
- **B4 — Cancellation protocol.** `CancellationException` rethrow discipline at every catch;
  batch teardown uses `withContext(NonCancellable) { store.flush() }` (3353–3355) and
  lease release/cancel ordering (3344–3352). Moving the finally block changes nothing only if
  moved verbatim.
- **B5 — Outbound callback + companion API relied on externally:** `SINGLE_PAGE_TIMEOUT_MS`
  (ReaderViewModel.kt:2525), `permitHolderPageKeySnapshot()` internal (TranslationManager:1340,1464),
  four `@Volatile var` callbacks set by TranslationManager, `activeStoreResolver` read by
  ChapterTranslator (237, 520), `batchTrackerFactory` invoked by ChapterTranslator (613). All same
  module (`internal` is fine), but signatures must not drift.
- **B6 — Defensive init contract:** engine build at construction must swallow invalid config
  (init 375–420, stub translator 407–414) because TranslationManager constructs the pipeline
  eagerly as a field initializer. Extracting engine construction without this guard risks a
  startup crash regression.
- **B7 — String-keyed page identity and status strings:** pageKey strings, `PageWriteOrigin.*.name`
  provenance stamps (1312, 3839), `inpaintingModeUsed` string comparisons with legacy-null
  tolerance (3547, 1650) are contracts with the store/legacy rescue path — keep verbatim.
- **B8 — Nested `withNativeLane`** (translateBatch engine-setup L1211 wraps workers that open
  their own lanes at 2422, 2587): correctness depends on `NativeRunQuarantine` admission semantics;
  do not "simplify" nesting while moving.
- **B9 — Dead code found:** `getContextualTranslator` (4261–4292) has zero callers — delete, don't move.

---

## Verification summary

- All line ranges VERIFIED by direct read of `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` (5,300 lines).
- Caller map VERIFIED by grep across `app/src/main/java` (ChapterTranslator.kt:69,624,613,520,237; TranslationManager.kt:95,163,173,194,201,1340,1464; ReaderViewModel.kt:2525).
- Test map VERIFIED by grep across `app/src/test` (16 batch/ tests, scheduling/ boundary+coordinator tests; none constructs the pipeline).
- `getContextualTranslator` dead-code claim VERIFIED by repo-wide grep (single hit = definition).
