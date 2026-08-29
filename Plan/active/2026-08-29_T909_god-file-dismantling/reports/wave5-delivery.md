# T909 Wave 5 Delivery — Phase 20 (Pipeline batch internals) + ride-along

Owner: Implementer · Date: 2026-08-28 · Branch: `optimize_translation_finishing_page` (no push, per rules)
Plan: `dismantling-plan.md` §3 Phase 20, §5.2, §6 risks 3/5 · Detail: `pipeline-investigation.md` §3 S3, §4 #7, §5.7
Line ranges in the plan were stale (Phases 1–19 already shrank the file); all regions were located by DECLARATION NAME after a full re-map of the current file.

## Headline

- **7/7 commits landed (6 sub-commits + ride-along), every gate green, no reverts.**
- `TranslationPipeline.kt`: **3,360 → 1,079 lines** (−2,281; plan target ≈2,240).
- New package `eu.kanade.translation.pipeline.batch` (6 files, 4,171 lines incl. ctor wiring + docs):
  `HeldBitmapRegistry` (68) · `BatchWriteGate` (251) · `BatchResumePlanner` (287) · `BatchRenderJoin` (312) · `BatchLaneWorkers` (1,561) · `BatchChapterTranslator` (692).
- `**MANUAL SMOKE PENDING**` — batch translate / resume mid-batch / pause-stop cannot run in this environment (plan §2 rule 8, pipeline blind spot B1).

## Per sub-commit

| Commit | What moved | Files | Pipeline Δ | Gates | Result |
|---|---|---|---|---|---|
| `fadf6e7` 20.1 | Held-bitmap registry: `HELD_BITMAP_MAX_COUNT`/`HELD_BITMAP_BYTE_CEILING` (KDoc verbatim), `heldBitmapBytes`/`countSlots`/`bitmapRegistry`, `holdCleaned`, `recycleHeld` | + `pipeline/batch/HeldBitmapRegistry.kt` | 3,360→3,320 | full suite (`eu.kanade.translation.*`, ui.manga, ui.reader, data, extension) | GREEN |
| `d3e802e` 20.2 | Batch write gate: `guardedBatchUpdate`, `refreshBatchIdentity`, `batchWritePrecondition`, `persistAiFailure`, `persistAiFailureOrThrow`, `releaseBatchLease`, `persistBatchPageWithOomRecovery`, `BatchWriteIdentity`, `ProviderFailure.toFailureCategory` (sole caller moved with it) | + `pipeline/batch/BatchWriteGate.kt` | 3,320→3,193 | `ChapterTranslatorQueueRestoreTest`, then full suite | GREEN |
| `6ecf271` 20.3 | Resume planning: `BatchResumeGate` enum, `buildBatchPagePlans`/`batchPagePlans`, `stampBatchProvenance`, `translationFailureFence`, frontier seeding, `plannedTranslationDecision`, `recordReusableContextPage`, `plannedTranslationNeedsWork`, `plannedRenderNeedsWork`, `resumeGate`, `rollingContext` + `recordContextPage` | + `pipeline/batch/BatchResumePlanner.kt` | 3,193→3,008 | `BatchResumeGateDeciderTest`, then full suite | GREEN |
| `0769078` 20.4 | Render join: `tryRender` (bitmap recycle sites kept with it), `renderMutexes`, `nativeRenderSignals`/`translationRenderSignals`, `signalFor`, `renderJoin` → class now implements `RenderJoinWorker` directly | + `pipeline/batch/BatchRenderJoin.kt` | 3,008→2,810 | `DisplayReadyStageCountTest`, then full suite | GREEN |
| `a77355c` 20.5 | Lane workers: `translateChunkAi`, `completeChunklessPage`, `chunkCounter`, coordinator comment, `streamsByKey`, `nativeWorker`, `translatorWorker` | + `pipeline/batch/BatchLaneWorkers.kt` | 2,810→1,583 | `SequentialBatchCoordinatorTest`, `BatchTranslateBlockMergeTest`, `Phase0BatchTranslationCharacterizationTest`, then full suite | GREEN |
| `cd99029` 20.6 | Shell: `translateBatch` (KDoc + body), `persistUnexpectedBatchStageFailure`; `TranslationPipeline.translateBatch` is now a thin delegator | + `pipeline/batch/BatchChapterTranslator.kt` | 1,583→1,079 | full suite | GREEN |
| `a26aa0b` ride-along | Deleted pure pass-through `ChapterTranslator.translateChapter` (plan §5.2, authorized); sole call site now calls `translateChapterInternal` | `ChapterTranslator.kt` | — | `ChapterTranslatorQueueRestoreTest`, then full suite | GREEN |

## Closure → class conversion map (hotspot #2)

Principle used throughout: **shared mutable state objects keep ONE instance**, created where they were
created (shell → now `BatchChapterTranslator`), injected as same-name constructor vals into each moved
class; pipeline-private methods arrive as constructor lambdas behind **same-name private wiring members**
so moved bodies stay char-identical (the established "same-signature stubs keep call sites" pattern from
waves 1–4).

| Captured state (was local in `translateBatch`) | New owner / shape |
|---|---|
| `heldBitmapBytes`, `countSlots`, `bitmapRegistry` | Fields of `HeldBitmapRegistry`; shell held same-name aliases until the last consumer moved (removed at 20.6) |
| `batchWriteIdentities`, `durableFailurePageKeys` | Created in shell (`BatchChapterTranslator`); injected into `BatchWriteGate` (identities + failures) and `BatchLaneWorkers` (identities); outer `finally` uses the same instances |
| `contextFrontier` | Created in shell (needs `resolvedNaturalPageIndexes`); injected into `BatchResumePlanner` + `BatchLaneWorkers` |
| `rollingContext` (var) + `recordContextPage` | `BatchResumePlanner` field (`private set`) + method |
| `batchPagePlans` | `BatchResumePlanner` val, built in init by the moved `buildBatchPagePlans()` |
| `translationRegistry` | Created in shell; injected into `BatchWriteGate`(via set ops only), `BatchRenderJoin`, `BatchLaneWorkers`, used by shell `abortBatchCandidate` |
| `renderMutexes`, `nativeRenderSignals`, `translationRenderSignals`, `signalFor` | Private fields/method of `BatchRenderJoin` (sole consumer) |
| `glossaryStats`, `aborted`, `expectedBatchFingerprints` | Created in shell; injected into workers / gate / planner / render join |
| `chunkCounter`, `streamsByKey`, `ensureCompanionDir` | Sole consumer was the workers → became `BatchLaneWorkers` fields (bodies verbatim) |
| Pipeline `withNativeLane` | `NativeLaneRunner` interface (worker seam); shell keeps its own private same-name wiring fn |
| Pipeline `markPageTimedOut`, `analyzePage`, `decodePageBitmapForTranslation`, `preflightInpaintGate`, `inpaintPage`, `retryInpaintDownscaled`, `persistCleanedBitmap`, `loadPersistedCleanedBitmap`, `deleteRetiredCleanedFile`, `computeSourceFingerprint`, `batchExpectedFingerprints`, `inpaintingModeFromPref` | Ctor lambdas behind same-name private members (named-argument call sites in moved bodies required declared-parameter wiring fns for `inpaintPage`/`persistCleanedBitmap`/`persistBatchPageWithOomRecovery`/`updatePageFromCurrentSnapshot`) |
| Pipeline `releaseBatchPageLease`, `persistPageWithOomRecovery`, `abortBatchCandidate` | `BatchWriteGate` ctor lambdas / `BatchChapterTranslator` private fn (abort stayed with the shell — see deviations) |
| `engineRebuildMutex` + `ensureEnginesBuiltFor` | Same Mutex instance injected into `BatchChapterTranslator`; engine-setup body unchanged (`withLock { ensureEnginesBuiltFor(...) }`) |
| `onBatchClosed` (@Volatile) | Read via `onBatchClosedFn: () -> ...` ctor lambda + private getter — current instance at invocation time |
| `recognitionEngine`, `textTranslator` engine reads | Getter lambdas + same-name private getters |

Visibility moves (allowed modulo): `BatchResumeGate` private-nested → internal top-level (BatchResumePlanner.kt);
`BatchWriteIdentity` private-nested → internal top-level (BatchWriteGate.kt); `toFailureCategory` moved
file-private → file-private (BatchWriteGate.kt).

## Verbatim verification

- **20.5**: scripted extraction; moved 1,263-line body diffed against the pre-move blob — **0 diffs** modulo the exact 12-space dedent (function-local → class member).
- **20.6**: moved 534-line shell+persistUnexpectedBatchStageFailure diffed against the pre-move blob — **0 diffs** besides exactly the 13 declared wiring rewrites (`this::x` → `x` collaborator refs) and one dropped blank line from the `ensureCompanionDir` removal.
- 20.1–20.4 bodies were copied verbatim by hand and re-checked by compile + green suites; call-site conversions were limited to: `recycleHeld/holdCleaned` → `heldBitmapRegistry.` (20.1), delegate locals (20.2/20.3), render-join construction (20.4).
- CancellationException rethrow discipline, `NonCancellable` flush ordering, nested `withNativeLane`, and all bitmap recycle/ownership points moved verbatim (verified in the diffs above).

## Stub / seam audit (rule 2)

- `TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS` — const stays on the companion; readers (`ReaderViewModel.kt:2525`, `PageStoreWriter`) unaffected. `BatchLaneWorkers` imports it from the companion.
- `ONNX_PHASE_TIMEOUT_MS`, `UNKNOWN_SOURCE_FINGERPRINT` — stay on the companion; `BatchChapterTranslator` imports them.
- `permitHolderPageKeySnapshot()` — existing stub untouched; `TranslationManager`/`BatchProgressProjector` unaffected.
- `batchTrackerRegistry` / `terminalSnapshotCacheSize()` — live in `TranslationManager`/`TranslationBatchTrackerRegistry`, untouched.
- `translateBatch` remains `suspend fun` on `TranslationPipeline` (delegator) — sole caller `ChapterTranslator.kt:624` unchanged.
- Grep of `app/src/test` + `app/src/main` found no test or production references to any moved internal symbol; no stubs beyond the delegator were required.

## Deviations (declared, none behavior-changing)

1. **`abortBatchCandidate` moved with the shell (20.6), not with 20.1/20.2.** The plan's stale ranges hinted registry/write-gate placement, but it spans registry + write-gate + store lease ops; the shell was its only faithful home until the shell itself moved. Passed as a lambda to `BatchRenderJoin`/`BatchLaneWorkers`.
2. **`ensureCompanionDir` local dropped from the shell in 20.6** — its only consumer (`nativeWorker`) moved in 20.5; the lambda itself moved verbatim into `BatchLaneWorkers`. This is move residue, not dead-code hunting.
3. **`persistUnexpectedBatchStageFailure` moved with the shell (20.6)** (sole caller = shell). `markBatchTranslationFailed` has zero callers (verified by grep) and was left untouched in `TranslationPipeline` per rule 4 — flagged here for a future cleanup decision, not acted on.
4. **`NativeLaneRunner` is a normal `interface`, not `fun interface`** — Kotlin prohibits SAM conversion for generic abstract methods; both wiring sites use explicit object expressions.
5. **Donor-file import cleanups** after 20.2/20.5/20.6 (provably-unused imports only; zero bytecode effect). The final tree was fully re-gated by the 20.6 and ride-along full-suite runs.

## Manual smoke

`**MANUAL SMOKE PENDING**` — batch translate, resume mid-batch, pause/stop (plan §2 rule 8; Phase 12/14 smoke covered the single-page paths already). Low-memory decode pass recommended per plan risk 5.

## Suggested next steps for the Main Leader

- Forward the MANUAL SMOKE PENDING flag to the Director before any release cut.
- Phase 20 completes the committed plan for `TranslationPipeline.kt` (1,079 lines vs ~450–650 target: the remainder is the single-page path + facade, which the plan intentionally leaves).
