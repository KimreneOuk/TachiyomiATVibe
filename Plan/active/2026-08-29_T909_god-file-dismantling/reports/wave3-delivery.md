# T909 Wave 3 — Delivery Report

Owner: Implementer · Date: 2026-08-29 · Branch: `optimize_translation_finishing_page` (no push)
Scope: Phases 12–14 of `dismantling-plan.md` — behavior-preserving moves only.
Verification: `JAVA_HOME=<Android Studio jbr>`; targeted tests per phase, then full filtered suite
`./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "eu.kanade.tachiyomi.ui.manga.*" --tests "eu.kanade.tachiyomi.ui.reader.*" --tests "eu.kanade.tachiyomi.data.*" --tests "eu.kanade.tachiyomi.extension.*"` (variant `standardDebug`).

## Result summary

- **3 commits landed (one per phase), 0 phases reverted.** Full filtered suite green after every phase
  (final forced re-run: **1073 tests / 0 failures / 0 errors**).
- 2 new files (~1,631 lines); 2 god-files reduced:
  `TranslationPipeline.kt` 4,578 → 3,360 (−1,218 across Phases 12+14),
  `TranslationManager.kt` 1,777 → 1,704 (−73). Wave-3 diff total: 5 files, +1,895 / −1,387.
- **MANUAL SMOKE PENDING** (see bottom).

| Phase | Commit | Gate result |
|---|---|---|
| 12 | `3e58716` | AiTranslationRetryPlannerSinglePage + CancelSyncStoreWrite + PreparedPageRuntimeBoundary green; full suite 1073/0/0 |
| 13 | `48e5cf7` | TranslationManagerArtifactRead + TranslationManagerDownloadFailureRecovery green; full suite 1073/0/0 (see flake investigation below) |
| 14 | `06b7de1` | Phase-12 gates + RollingAutoCoordinator green; full suite 1073/0/0 (incl. forced `--rerun-tasks` re-run) |

Char-identity verification (all three phases): every moved region was diffed line-by-line against the
pre-move file; the only deltas are package/imports/visibility (dropped `private `, `private data class`
→ `internal data class`, top-level dedent of `OnnxPhaseResult`). No logic edits, renames, reorderings,
or comment changes inside moved bodies.

## Phase 12 — Pipeline single-page HTTP+render phase · `3e58716`

New `pipeline/SinglePageHttpRenderPhase.kt` (548); pipeline 4,578 → 4,194.
Moved verbatim (body 414 lines incl. KDoc): `translateSinglePageHttpRender` — outcome typing stays
`ChunkCompletionOutcome`; the bounded PARTIAL retry predicate (`SINGLE_PAGE_PARTIAL_MAX_RETRIES` loop
plus the no-throw PARTIAL → `Paused` fold) moved with the body.
Ctor: `translationPreferences`, `provider`, `streamRegistry`, `engines: EngineLane` (with same-name
`textTranslator` / `recognitionEngine` getters so in-flight calls observe rebuilds exactly as the
pre-move in-class getters did), `cleanedPublication`, plus two injected collaborators:
`expectedBatchFingerprints: (fromLang, toLang) -> BatchExpectedFingerprints` (engine-signature reads
deferred to call time) and `retryInpaintDownscaledFn` (ONNX-phase collaborator; injected from the
pipeline's own implementation until Phase 14 moved it). Same-name private shims inside the phase class
(`batchExpectedFingerprints`, `retryInpaintDownscaled`, `persistCleanedBitmap`,
`deleteRetiredCleanedFile`) keep the moved body's call sites — including its named-argument sites —
char-identical. `SINGLE_PAGE_PARTIAL_MAX_RETRIES` reached via
`import eu.kanade.translation.TranslationPipeline.Companion.SINGLE_PAGE_PARTIAL_MAX_RETRIES`;
`OnnxPhaseResult` via `import eu.kanade.translation.TranslationPipeline.OnnxPhaseResult`
(Phase 14 later simplified both — see below). Pipeline keeps the same-signature private stub; zero
call-site churn (callers: `runSinglePageBoundary`, `translatePreparedPage`).
Removed two now-unused pipeline imports (`RequestRetryBudget`, `withRequestRetryBudget`).
Frozen seams intact: `SINGLE_PAGE_TIMEOUT_MS` untouched on the companion; the four `@Volatile`
callbacks untouched; no ctor changes.

## Phase 13 — Manager durable status resolver · `48e5cf7`

New `manager/DurableChapterStatusResolver.kt` (170); manager 1,777 → 1,704.
Moved verbatim: `DurableChapterKey`, `DurableStatus`, `TranslationDocument` (types bumped
`private data class` → `internal data class`), `persistedChapterStatus` (incl. the runBlocking(IO)
bridge and its null-result comment), `resolveDurableChapterStatus` (private), `findTranslationDocument`,
`withProbeStore` (private, incl. its probe-adoption cache-clear + `closeAndFlush` release protocol),
plus a private `decodeLegacyChapterStatus` shim to `LegacyFlatFileDecoder` so the moved resolve body's
legacy-fallback line stays char-identical. The manager's orphaned `decodeLegacyChapterStatus` stub was
removed (its only caller moved); `statusFromReadablePages` and all other Phase-3a stubs untouched.
Wiring follows the Phase 9/11 precedent exactly: the manager keeps a
`private val durableStatusResolver get() = DurableChapterStatusResolver(...)` built **per access** from
provider lambdas (`providerProvider`, `sourceManagerProvider`, `activeStoresProvider`,
`durableStatusCacheProvider`), and same-signature private stubs for `persistedChapterStatus` and
`findTranslationDocument` keep all remaining call sites (progressProjector wiring, isChapterTranslated,
getChapterTranslationForReader, openExisting..., openOrCreate...Impl, deleteTranslation's plan capture).

### durableStatusCache audit (plan §6.8 — every reference accounted for)

Pre-move TranslationManager.kt (at `3e58716`) had 14 references: 1 declaration, 1 read, 1 write,
11 clears. Post-move state:

| Pre-move site (line) | Region | Post-move disposition |
|---|---|---|
| L156 declaration | field | **Stays on TranslationManager** (`private val durableStatusCache`) — reflection-pinned seam: `TranslationManagerArtifactReadTest:99` and `TranslationManagerReaderTeardownTest:263` do `setField(manager, "durableStatusCache", ...)` and would throw `NoSuchFieldException` otherwise. Type now imports the resolver file's `DurableChapterKey`/`DurableStatus`. |
| L219 init collector (`translator.queueState.collect`) | wiring | Routed → `durableStatusResolver.clearDurableStatusCache()` (now manager L210). |
| L630 cache read | `persistedChapterStatus` | Moved verbatim into resolver (`durableStatusCache[key]?.let { return it.state }`), reached via the provider lambda. |
| L643 cache write | `persistedChapterStatus` | Moved verbatim into resolver (`state?.let { durableStatusCache[key] = DurableStatus(it) }`). |
| L740 `getChapterTranslation(file)` (LEGACY rescue advance) | store open | Routed → resolver clear (manager L696). |
| L773 `openExistingChapterTranslationStore` | store open | Routed → resolver clear (manager L729). |
| L806 `withProbeStore` (probe rescue) | probe adoption | **Moved verbatim** — the clear lives inside `DurableChapterStatusResolver.withProbeStore` (resolver L161) against the provider-resolved map. |
| L892 `registerActiveTranslationStore` | registry | Routed → resolver clear (manager L819). |
| L899 `unregisterActiveTranslationStore` | registry/eviction | Routed → resolver clear (manager L826). |
| L1310 `deleteTranslation` (step 7 of the load-bearing ordering) | delete | Routed → resolver clear (manager L1237). |
| L1390 `resetChapterData` | reset | Routed → resolver clear (manager L1317). |
| L1426 `resetTranslationData` | reset | Routed → resolver clear (manager L1353). |
| L1501 `resetInpaintData` | reset | Routed → resolver clear (manager L1428). |
| L1568 `resetOcrData` | reset | Routed → resolver clear (manager L1495). |

Post-move grep proof: `durableStatusCache` appears in TranslationManager.kt exactly 3 times — the
declaration, a comment, and the `durableStatusCacheProvider = { durableStatusCache }` wiring; all 10
manager-side clears are `durableStatusResolver.clearDurableStatusCache()`; the 11th clear is verbatim
inside the resolver's `withProbeStore`. **No clear site was lost — stale TRANSLATED resurrection risk
addressed.**

### Mid-phase incident (caught and fixed before commit)

The first construction of the review run leaked an NPE in the full suite:
`UncaughtExceptionsBeforeTest` in `BatchTranslateBlockMergeTest` / `ChunkTranslationPayloadTest`
(different victim each run — cross-test contamination from a real-dispatcher coroutine). Root cause:
`TranslationManagerReaderTeardownTest` drives `unregisterActiveTranslationStore` on a real
`Dispatchers.IO` scope against an Unsafe-allocated manager whose `provider` field was never set; the
resolver ctor's non-null `provider: TranslationProvider` parameter tripped Kotlin's null-check
intrinsic, whereas the old inline `durableStatusCache.clear()` never touched `provider`.
Attribution evidence: combination reproduced 3/3 on the Phase-13 tree, 0/1 on the stashed Phase-12
baseline; single manager tests + the victim test passed both before and after.
Fix (pre-commit): ctor dependencies became provider lambdas with same-name getters
(`private val provider get() = providerProvider()`, etc.) — construction no longer resolves any
manager field, invalidation-only callers can never NPE on unset fields, and the moved bodies still
read `provider` / `sourceManager` / `activeStores` / `durableStatusCache` unmodified. This matches the
Phase 9/11 provider-lambda convention. Full suite then green twice (incl. one forced `--rerun-tasks`).

## Phase 14 — Pipeline single-page ONNX phase · `06b7de1`

New `pipeline/SinglePageOnnxPhase.kt` (1,083); pipeline 4,194 → 3,360.
Moved verbatim (891 lines): `translateSinglePageOnnx` (KDoc + body, 321), the resume/native-stage
cluster `renderResumedPage`, `resumeInpaintAndRender`, `retryInpaintDownscaled` (KDoc),
`analyzePage` (KDoc), `inpaintPage` (KDoc), `processSinglePage` (555), and `OnnxPhaseResult`
(KDoc + decl, 15 — now a top-level `internal data class` in the pipeline package).
`consecutiveOomCount` (`@Volatile private var`, only ever touched by `analyzePage`/`processSinglePage`)
moved with its owners; the pipeline's copy was deleted.
Ctor: `context`, `translationPreferences`, `provider`, `downloadProvider`, `streamRegistry`,
`engines: EngineLane` (same-name `recognitionEngine` / `currentInpaintingMode` getters +
`inpaintingModeFromPref` + `ensureEnginesBuiltFor` shims), `cleanedPublication`, `pageStoreWriter`,
`activeStoreResolverProvider` (the manager-wired `@Volatile` var, read per call), and
`engineRebuildMutex` — the **same mutex instance** the pipeline owns, so the moved comment's guarantee
("the caller already owns native quarantine; rebuild is therefore ordered after any timed-out
predecessor's real exit and cannot race closeEngines") holds unchanged. Same-name private shims
(`peekReaderPageStream`, `createFailedPagePlaceholder`, `updatePageFromCurrentSnapshot`,
`persistPageWithOomRecovery`, `loadPersistedCleanedBitmap`, `persistCleanedBitmap`,
`decodePageBitmapAtSize`, `decodePageBitmapForTranslation`, `preflightAnalyzeGate`,
`preflightInpaintGate`, `forceReleaseNativeBuffers`, `handleCriticalTranslationOom`, `getChapterPages`)
keep every moved call site char-identical; `PageDecode` / `MemoryGovernance` / same-package
`DecodedPage`, `LowMemory*`, `toPrecondition`, `copyForResume` resolve directly.

### Bitmap-ownership verification (plan §6.5 / blind spot B3)

Mechanical parity check of the union of the three moved regions (pre-move) against the new file:

| Ownership/recycle point | Pre-move regions | SinglePageOnnxPhase.kt |
|---|---|---|
| `recycle()` calls (recycle-in-finally + defensive double-recycles) | 6 | 6 |
| `if (e is CancellationException) throw e` rethrows | 7 | 7 |
| `BitmapPool.releaseAll()` after recycle-in-finally | 4 | 4 |
| `cleanedBitmap = null` ownership releases | 2 | 2 |

The `OnnxPhaseResult.cleanedBitmap` handoff across the permit boundary keeps its exact shape: the
onnx phase returns with the bitmap alive; recycle-in-`finally` remains in `translateSinglePageHttpRender`
(now `SinglePageHttpRenderPhase`, untouched by this commit); every recycle sits inside its original
`try/finally`. The batch held-bitmap registry (`countSlots` / `heldBitmapBytes` / `bitmapRegistry`)
was never part of the moved regions and is byte-for-byte untouched inside `translateBatch`.
Nested `withNativeLane` nesting unchanged: the moved function itself opens no lane; the callers
(`runSinglePageBoundary` / `prepareSinglePage`, which wrap it in `withNativeLane(ONNX_PHASE_TIMEOUT_MS)`)
and the batch workers are untouched.

Cross-file consequences shipped in the same commit (imports/visibility only):
`CleanedPublication.persistOnnxCleanedImage` now references same-package `OnnxPhaseResult`
(`TranslationPipeline.` qualifier and its `TranslationPipeline` import dropped);
`SinglePageHttpRenderPhase` dropped the `TranslationPipeline.OnnxPhaseResult` nested-class import
(`Companion.SINGLE_PAGE_PARTIAL_MAX_RETRIES` import stays). Pipeline keeps same-signature stubs for
`translateSinglePageOnnx`, `retryInpaintDownscaled`, `analyzePage`, `inpaintPage` (batch call sites at
translateBatch unchanged); `renderResumedPage` / `resumeInpaintAndRender` / `processSinglePage` have no
remaining pipeline callers (moved with their only caller). The Phase-12 wiring's
`retryInpaintDownscaledFn` lambda now resolves through the pipeline's new stub into the onnx phase —
wiring-only change, moved bodies untouched. Frozen seams intact (`SINGLE_PAGE_TIMEOUT_MS` companion
const, four `@Volatile` callbacks, `permitHolderPageKeySnapshot`, no ctor reorder).

## Gate notes

- Targeted gates were run per phase before each full suite (FQCNs as in the plan).
- The Phase 13 flake investigation (documented above) is the only gate anomaly of the wave; it was a
  real defect introduced and fixed **within** the phase, before its commit. No phase was reverted.
- Final suite executed twice for Phase 13-fix state and twice for Phase 14 (last runs forced via
  `--rerun-tasks`): 1073 / 0 / 0 both times.

## Deviations from plan

1. Phase 13: manager-side cache clears route through `DurableChapterStatusResolver.clearDurableStatusCache()`
   (a new delegating method on the resolver). The plan's "all clears route through the resolver" is
   satisfied literally; the method's KDoc documents the invalidation protocol.
2. Phase 13: the `durableStatusCache` field itself stays on the manager rather than moving into the
   resolver, because two test files reflection-write that exact field name on the manager
   (`setField(manager, "durableStatusCache", ...)` would otherwise throw). The resolver reads it
   through a provider lambda — the seam is shared, never copied.
3. Phase 14 commit touches four files (pipeline + new phase + two import-only edits in
   `CleanedPublication.kt` / `SinglePageHttpRenderPhase.kt`) because `OnnxPhaseResult` moved from a
   nested class to a top-level declaration as the plan specifies; both edits are import/qualifier-only.
4. `MANUAL SMOKE PENDING` — see below.

## MANUAL SMOKE PENDING

Per plan §2 rule 8 (pipeline body has no direct unit tests — blind spot B1/B3), the following cannot be
executed in this environment and remains **outstanding for the Director**:
- Reader single-page translate on a real device (exercises Phase 12 + Phase 14 end-to-end, including a
  low-memory decode pass): verify OCR/inpaint runs, PARTIAL retry behaves, translated text renders over
  the cleaned image, and cancel mid-page recycles cleanly.
- Optionally repeat once after Phase 20 lands (batch paths were not moved in this wave).
No automated gate covers these; all 1,073 automated tests pass.
