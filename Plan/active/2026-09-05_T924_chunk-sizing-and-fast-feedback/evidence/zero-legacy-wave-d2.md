# T924 Zero-Legacy Wave — D2 Evidence Report (dead-code sweep + tracker settle)

Worktree: `TachiyomiAT-t924-impl` (branch `t924/batch-profile-pipeline`, HEAD `ab82e6d`, working tree clean at start — verified).
Method: reference-analysis pass over the batch package + the D1 candidate list, then deletion of ONLY symbols
with zero remaining references across ALL source sets (`app/src/main`, `test`, `androidTest`; `domain`, `data`,
`core` greps included). Ambiguous or still-referenced code STAYS and is listed below.

## Result

`./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`:
**242 suites / 1764 tests / 0 failures / 0 errors.**
D1 baseline at `ab82e6d`: 242 / 1763 / 0. Delta: +1 test (the new tracker-settle pin). Suite count unchanged.
Safety net: `--tests "eu.kanade.tachiyomi.*"` 18 suites / 106 tests / 0 failures.
Net diff: 14 files, **+152 / −950 lines** (numstat below).

## The one behavior item (authorized): tracker RENDER-fraction settle

`BatchChapterTranslator.kt` (shell COMPLETED path, after `reconcileFlaggedCompleted` + stranded sweep +
`store.flush()`, immediately before `tracker?.finish(reconciliation)`): the shell now emits
`markRenderSkipped(pageKey)` for every expected page (`orderedStreams`). Under zero-legacy no lane emits
render events for translatable pages, so `perStage[RENDER].processed` stayed 0 forever and the processed/total
fraction topped out at 4/5 per display-ready page even at terminal. `StageCount.processed = succeeded+failed+skipped`,
so a SKIPPED render arm is processed work — the fraction now completes to 5/5. Bounded to the COMPLETED path
(once per page, before the terminal `BatchFinished`); PAUSED/FAILED/OOM/abort paths untouched; idempotent for
pages whose lane already emitted a render-skip (textless).

Tests:
- `pipeline/batch/TranslationBatchProgressTrackerTotalsTest.kt` — new
  `completion render-skip settle completes the 5 of 5 terminal fraction`: zero-legacy terminal page
  (OCR/INPAINT/TRANSLATE committed READY, render PENDING) + committed display bundle (reader re-derived,
  promoted via the store's display-promotion path, live page flipped back to PENDING). Asserts the pre-settle
  fraction is exactly 0.8 with `perStage[RENDER].processed == 0`, then the
  `markRenderSkipped` + `finish` sequence yields `doneStages == 5 / totalStages == 5`, `fraction == 1f`,
  `perStage[RENDER].processed == 1` (skipped). Suite 6/6 green.
- `coexistence/StandardLaneMultiPageCompletionTest.kt` — end-to-end pin on the REAL shell
  (`fresh standard batch translates every page…`): after the run completes, the registry's terminal snapshot
  has `perStage[RENDER].processed == pageKeys.size` (3) — the shell emitted the settle for every expected page.
  Suite 2/2 green.

## Delete/keep table (reference evidence, file:line = pre-edit HEAD ab82e6d)

### DELETED (zero remaining references across all source sets)

| Symbol / file | Evidence it was dead | Action |
|---|---|---|
| `ChapterTranslationStore.awaitPageLeaseRelease` (ChapterTranslationStore.kt:567-573, KDoc+wrapper) | Only hits anywhere were the wrapper + its own KDoc + the delegatee (grep `app/src`, `domain/src`, `data/src`, `core/src`) | Deleted; cascade below |
| `PageStageLeaseTable.awaitPageLeaseRelease` (PageStageLeaseTable.kt:191-215) + `withTimeoutOrNull` import | Sole caller was the deleted store wrapper | Deleted |
| `RenderJoinWorker` interface (BatchCoordinatorInterfaces.kt:118-132) — `onNativeBranchDone`, `onTranslationBranchDone`, `onTranslationBranchPaused`, `awaitAndRender`, `awaitAndSettle` | Only implementor: BatchRenderJoin; ZERO call sites for every method in any source set (D1 deleted the SBC await loops) | Interface deleted |
| `BatchRenderJoin` legacy arms (BatchRenderJoin.kt): `: RenderJoinWorker`, `signalFor`, `nativeRenderSignals`, `translationRenderSignals`, `onNativeBranchDone`, `onTranslationBranchDone`, `awaitAndRender`, `awaitAndSettle`, private `translationFailureFence` wrapper (62-81, 88-89, 625-660), `CompletableDeferred` import | All callers were the deleted SBC; remaining grep = declarations only | Deleted; `tryRender` + `publishPersistedLayoutForCompletedPage` machinery KEPT (see keep table) |
| Chunk-admission machinery: `ChunkAdmission` enum (BatchCoordinatorInterfaces.kt:55-62), `usesChunkAdmission` (interface :66 + BatchLaneWorkers.kt:1278), `admit` (interface :76-79 + BatchLaneWorkers.kt:1298-1302), `lastAdmission` (:1295 + writes :1449/:1470) | `usesChunkAdmission` had ZERO readers; `admit` had ZERO callers (grep incl. ProfileEnvelopeExecutor — it never touches lane workers); `lastAdmission` read only inside `admit`; `fallbackChunkPageCount` had zero hits at HEAD already (removed in D1) | Deleted |
| AI chunk-completion cascade (BatchLaneWorkers.kt): `translateChunkAi` (:334-370), `translateChunkAiTraced` (:372-792), `completeChunklessPage` (:794-824), `processAiEmission` (:1624-1651), `completeChunkOutcome` override (:1657-1759), `completeChunk`/`completeChunkOutcome` interface methods (BatchCoordinatorInterfaces.kt:100-115), `handledRejectedPages` (:1293) | Reference cascade from ZERO external callers: the coordinator drives translation via ProfileEnvelopeExecutor and never calls `translatorWorker`; the only `translateOutcome` caller is the shell's STANDARD tail (isAi=false there by dispatch construction); tests never construct BatchLaneWorkers | Deleted (~560 lines) |
| Newly-orphaned BatchLaneWorkers members: `rollingContext` property (:172), ctor params `contextualTranslator` (:100), `glossaryStats` (:108), `scheduleTrace` (:161), and their imports/args | Zero remaining refs after the cascade (verified by grep + clean compile) | Deleted; shell construction site updated |
| Shell glossary accumulator (BatchChapterTranslator.kt:369-374 + `ChapterGlossaryBuilder` import) | Only consumer was the deleted lane-worker chunk engine | Deleted |
| `BatchOomPolicy.kt` — ENTIRE FILE (`BatchOomPolicy`, `AbortDecision`, `DEFAULT_ABORT_THRESHOLD`) | Zero references outside the file; SBC was the only OOM-threshold consumer (shell's OOM abort uses its own `aborted` flag) | File deleted (`git rm`); stale KDoc mention in MemoryPressurePolicy.kt:18 reworded |
| `ChapterProfileBatchCoordinator.activeRunRecordOrNull` (companion, :3187-3192) | Declaration-only (grep all source sets) | Deleted |
| `TranslationBatchTrackerRegistry.disposeIfCurrent` (:109-116) | Declaration-only | Deleted |
| `TranslationBatchProgressTracker.markTranslatePartial` (:92), `markAiPending` (:96) | Declaration-only | Deleted |
| `BatchScheduleListener` dead callbacks (BatchCoordinatorInterfaces.kt): `inpaintStarted`, `inpaintFinished`, `translationRequested`, `translationFinished`, `renderStarted`, `renderFinished`, `allOcrBarrierReleased`, `pass1BarrierReleased`, `pass2Started`, `batchPaused` | Declaration-only (no callers, no overrides anywhere); `ocrStarted/ocrFinished/ocrPublished/ocrDeferred` KEPT (live callers) | Deleted |
| T918 companion helpers (T918CancelledBatchRestartTest.kt:61-85): `expectedFingerprints()`, `realSourceFingerprint()` + now-unused imports (`BatchExpectedFingerprints`, `PageDecode`, `ByteArrayInputStream`) | Declaration-only after D1's rewrite (D5/D10 tests have their OWN same-named helpers — untouched) | Deleted |

### KEPT (verified alive — or ambiguous, with note)

| Symbol | Evidence it is alive |
|---|---|
| `BatchResumePlanner` | Shell ctor BatchChapterTranslator.kt:416; BatchLaneWorkers + BatchRenderJoin ctor params; D5GlossaryAwareReuseTest.kt:198 |
| `BatchResumeGateDecider` | BatchResumePlanner.kt:261-296; BatchResumeGateDeciderTest.kt; Phase0BatchTranslationCharacterizationTest.kt |
| `BatchAdmissionProbe` | MangaScreenModel.kt:1188 (`evaluate`); D10PartialDownloadAdmissionTest.kt:260 `Class.forName` reflection pin |
| `PageWorkPlanner` (model/ package, not batch) | SinglePageOnnxPhase, BatchResumePlanner, TranslationLifecyclePolicy, PageWorkPlannerTest + ForceReuseTest — the manual/auto per-page path uses it |
| `BatchContextFrontier` | ProfileEnvelopeExecutor.kt:296/545/746/984; shell :391; BatchContextFrontierTest |
| `BatchWriteGate` | Shell ctor :436; BatchLaneWorkers; BatchRenderJoin; BatchWriteGateHealTest |
| `BatchProgressReconciler` — ALL arms | `reconcile`: shell stop branch BatchChapterTranslator.kt:953 + **StoreStatusProjector.kt:143 (non-COMPLETE-record manifests — the mission's warning confirmed)** + tests; `reconcileFlaggedCompleted`: shell COMPLETED :984 + ChapterTranslator.kt:721 + BatchPostPassProjectionTest; `reconcilePaused`: called by `reconcile` :64 |
| `BatchRenderJoin.tryRender` | BatchLaneWorkers commit boundaries (translate tail :825, textless commit, native worker inpaint paths :831/:892/:933 pre-edit) |
| `BatchRenderJoin.publishPersistedLayoutForCompletedPage` + layout-publication machinery | ChapterProfileBatchCoordinator.kt:1436/1627/1798 (T924-TX-23) |
| `translate` AI branch internals (`planner`, `pendingAiEmissions`, `contextBlockedPages`) | Runtime-unreachable in production (AI dispatch never reaches translatorWorker) but still REFERENCED inside live `translate` — the strict rule keeps them. NOTE for a later wave: with the chunk engine gone these are dead stores at most. |
| T917 defer-and-rescan wiring: shell `deferredPages` map + `ocrDeferred` listener override, `BatchLaneWorkers` write/remove sites (:860/:884 pre-edit) | Write-only since D1 (the in-pass re-scan consumer was SBC) but live-referenced. FOLLOW-UP: retire the whole wiring. |
| `PageStageLeaseTable.leaseReleaseWaiters` + `completeLeaseReleaseWaitersLocked` | Still called from three release paths (:146/:163/:181 pre-edit); completes nothing now (producer deleted). FOLLOW-UP: retire with the defer-and-rescan wiring. |
| Coexistence harness (TranslationCoexistenceHarness) | NO unused recipes found: every zero-external-use member (`unsafeAllocate`, `harnessPreferences`, `CapturingJobMap`, `installEngineDrainSeams`, `publishCleanedThroughStore`) is consumed internally. |
| `AiTranslationRetryController` (translator/retry) | OUT OF SCOPE (not the batch package): observed zero production references (test-only). Pre-existing, NOT D1-caused; recorded only. |

## Lines deleted per file (numstat, added/deleted)

| File | +/− |
|---|---|
| pipeline/batch/BatchLaneWorkers.kt | +20 / −697 |
| pipeline/batch/BatchCoordinatorInterfaces.kt | +1 / −68 |
| pipeline/batch/BatchRenderJoin.kt | +8 / −48 |
| pipeline/batch/BatchChapterTranslator.kt | +20 / −25 |
| store/PageStageLeaseTable.kt | +6 / −26 |
| pipeline/batch/BatchOomPolicy.kt | 0 / −28 (file) |
| pipeline/batch/ChapterProfileBatchCoordinator.kt | 0 / −7 |
| pipeline/batch/TranslationBatchTrackerRegistry.kt | 0 / −9 |
| pipeline/batch/TranslationBatchProgressTracker.kt | 0 / −2 |
| ChapterTranslationStore.kt | 0 / −8 |
| MemoryPressurePolicy.kt | +2 / −3 (KDoc) |
| test: T918CancelledBatchRestartTest.kt | 0 / −29 |
| test: TranslationBatchProgressTrackerTotalsTest.kt | +82 / 0 |
| test: StandardLaneMultiPageCompletionTest.kt | +13 / 0 |
| **Total** | **+152 / −950** |

## Verification

- `:app:compileStandardDebugKotlin` and `:app:compileStandardDebugUnitTestKotlin`: clean.
- Targeted: tracker/reconciler suites (Totals 6/6 incl. new pin, Tracker, Reducer, Registry, BatchPostPassProjection, BatchProgressReconciler, DisplayReadyStageCount) — green; `coexistence.*` + `ui.*` — green (2m29s earlier full pass; coexistence run 53s).
- Full sweep `--tests "eu.kanade.translation.*"`: **242 / 1764 / 0 failures / 0 errors** (baseline 242/1763/0; +1 = the new settle test).
- Safety net `--tests "eu.kanade.tachiyomi.*"`: 18 / 106 / 0.
- Post-deletion symbol sweep (grep over all deleted names) returns only comments describing the deletion and same-named, unrelated symbols (`ProviderRequestGovernor.lastAdmissionAtEpochMs`, D5/D10 tests' own private helpers).
- String-based-lookup audit (the harness reflects over field names): no quoted references to any deleted symbol in any source set.

## Remaining follow-ups (recorded, out of D2 scope)

1. D1 candidate 2 (unchanged): finalize stranded-write vs COMPLETED projection tension — one projection authority.
2. D1 candidates 3/4/7/8 (unchanged): manual-render probe gap; `COUNTER_FLAG` durable key retirement (schema bump); cross-origin OCR merge translation drop; stale-resume-plan re-OCR churn.
3. D2 finds, kept alive by reference but functionally orphaned — one batch:
   the T917 defer-and-rescan wiring (shell `deferredPages` map + `ocrDeferred` + lane-worker write/remove sites) and
   `PageStageLeaseTable`'s waiter machinery (`leaseReleaseWaiters` + `completeLeaseReleaseWaitersLocked`, now completes
   nothing); the AI-branch dead stores in `translate` (`planner`/`pendingAiEmissions`/`contextBlockedPages`) retire with it
   or with a deliberate behavior decision (they are referenced code today — not deletable under the zero-reference rule).
4. `AiTranslationRetryController` (translator/retry): zero production references, test-only — candidate for a later sweep.
