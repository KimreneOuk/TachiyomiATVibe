---
title: "S4 - coordinator, auto window and slot DTO retirement"
kind: ticket
status: 0
assignee: ""
comments: none
---

# Goal

Delete the retired auto machinery now that S1-S3 provide replacement coverage. Every deletion is preceded by its absorbed-invariant check from the [survey absorption table](../../seam-survey/index.md); a deletion without equivalent coverage is a hold, never a green.

# Files

| Path | Action |
| --- | --- |
| `scheduling/RollingAutoCoordinator.kt` | Delete whole after relocating `AutoSmoothnessTelemetry` verbatim to `scheduling/AutoSmoothnessTelemetry.kt` (call sites + test follow). |
| `scheduling/AutoWindowState.kt` | Delete whole (identity replaced by `ReaderSessionToken`; no renamed slot machine). |
| `ui/reader/ReaderAutoTranslationUiState.kt` | Delete if not already gone in S3. |
| `scheduling/TranslationScheduler.kt` | Delete owner versions, `AutoCoordinatorOwner`, retiring-coordinator set, `beginAutoCancellation`/`performAutoCancellation`/`finishAutoCancellation`, `retireLocked`, `shutdownRetiringCoordinator`, the S2 snapshot adapter, and the `runBlocking` sync bridge (after counting/migrating callers). Keep `chapterCancellationEpochs` until session fencing is demonstrated, store-fenced cancellation, bounded joins. |
| `workflow/TranslationManager.kt` | Final queue facade (`bindReaderQueue`/`submitReaderPage`/`updateReaderViewport`/`setReaderAutoEnabled`/`readerOutcomes`); remove the old auto window APIs. |
| `pipeline/TranslationPipeline.kt` | Remove the public `prepareSinglePage`/`translatePreparedPage` pair; internal guarded helpers stay shared by the runner. |
| `presentation/TranslationUiTruth.kt` | Remove `forAutoSlot` + `AutoSlotState` import. |
| `domain/.../TranslationPreferences.kt` | Retire `autoTranslatePrefetchCount()` accessor; stored key/data retained, no migration. |
| `presentation/reader/TranslationSettingsSheet.kt` | Delete prefetch slider, callback params, section plumbing; auto toggle stays. |
| `ui/reader/ReaderTranslationController.kt` / `ReaderViewModel.kt` | Remove `setAutoTranslatePrefetchCount`, the prefetch collector (`ReaderViewModel:616`), combine input `:198`, `TranslationSettingsState.autoTranslatePrefetchCount` `:1844`, controller setter `:1097`. |
| Tests | Delete/merge per the absorption table: `RollingAutoCoordinatorTest`, `RollingAutoCoordinatorTraceTest`, `RollingAutoCoordinatorRegistrationRaceTest`, `AutoWindowStateTest`, `TranslationManagerAutoArbitrationTest`, `ReaderManualPreemptsAutoLeaseTest`, `ReaderAutoTranslationUiStateTest`, `ReaderAutoTranslationLifecycleTest`. Re-seam (keep, update): `AutoProviderCallDrainsNotCancelsTest`, `EngineEpochStopRaceTest`, `NormalMangaIsolationTest`, `TranslationCoexistenceHarness`, `AdmissionTestFixture`, `AutoSmoothnessTelemetryTest`, `P5OutcomeProjectionTest` remainder. |

# Work items

1. Telemetry relocation (new file, same package, no behavior change) — compile gate before the coordinator deletion.
2. Coordinator/window/DTO file deletions with the coverage ledger checked item by item.
3. Scheduler/manager/pipeline machinery removal per the table; adapter count returns to zero.
4. Truth/preference/slider retirement (exactly N+1 becomes the only policy).
5. Legacy test deletions/merges; harness re-seaming.

# Test plan

Re-run the queue, scheduler, workflow, and re-seamed coexistence classes on both flavors (this is the retirement regression gate); assert no remaining references to deleted symbols (compile is the proof); Spotless inventory. Old preemption/channel-policy assertions are retired deliberately; their preserved invariants live in the S1/S2 suites.

# Gates

No deletion may proceed while its replacement case is unexecuted or red. Both flavors green on the retirement set before review.
