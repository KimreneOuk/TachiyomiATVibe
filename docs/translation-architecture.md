# Translation architecture

`eu.kanade.translation` owns manga page translation: the reader's page path and the chapter batch path. Start at `TranslationManager` (the app-facing wiring facade), follow orchestration into `coordinator`, single-page execution into `processing/TranslationProcessor`, and specialized work into `engines` and durable/live state into `persistence`. There are no `workflow`, `scheduling`, or `pipeline` packages anymore; this document describes the consolidated ownership map.

See the [translation input quality improvement plan](translation-input-quality-plan.md) for the Standard/AI audit, proposed implementation phases, and evaluation criteria.

## Main page flow

```text
reader or batch request
  -> TranslationManager facade (wiring only)
  -> session and work admission (coordinator owners)
  -> TranslationProcessor page operations
     (decode -> recognition -> chapter context -> provider translation
      -> inpainting -> candidate validation)
  -> fenced candidate commit (persistence owners)
  -> live display projection and reader stream (reader presentation owns layout and drawing)
```

`TranslationManager.kt` (package root) is the only app-facing entry point. It wires collaborators at construction and delegates entry calls to coordinator APIs; it owns no execution or queue state and exposes no scheduler- or pipeline-shaped controls.

`processing/TranslationProcessor.kt` is the sole single-page execution path, and batch work drives the same operations one page at a time. `processing/execution/NativeRunQuarantine.kt` retains admission until the actual native call returns, and `processing/EngineLane.kt` serializes native engine use and close/rebuild operations. `processing/memory/TranslationMemoryBudget.kt` and `MemoryGovernance.kt` own page decode/preflight/prefetch decisions and memory-reclamation policy; engine-level heap and system-memory facts stay in `engines/runtime/EngineMemoryBudget.kt`. `processing/PageStoreWriter.kt` and `persistence/ChapterTranslationStore.kt` mediate candidate stage writes and commits. `persistence/ChapterArtifactEngine.kt` owns durable chapter artifact documents, manifests and recovery.

## Execution modes

| Mode | Entry and owner | Shared pieces | Mode-specific work |
| --- | --- | --- | --- |
| Manual page | `TranslationManager` -> `coordinator/TranslationRequestCoordinator` (request lifecycle, dedup, generations) with `coordinator/TranslationSessionCoordinator` (batch session admission) | `processing/TranslationProcessor`, engine lane and quarantine, chapter store, vision/translator/rendering engines | A user request is admitted, deduplicated for one page, and executed through the processor. |
| Auto reader | `TranslationManager` -> `coordinator/ReaderTranslationCoordinator` (reader tokens, session/viewport state, teardown) | Same processor execution contract, chapter store, engines | Reader window demand, work ownership per page key, pause and teardown rules. Reader presentation owns layout and drawing. |
| Batch chapter | `TranslationManager` -> `coordinator/BatchTranslationCoordinator` (queue, terminal cleanup, progress) gated by `TranslationSessionCoordinator` admission, `ChapterQueuePreflight` and `ChapterQueueConflictDetection` | Same processor operations, chapter store, artifact engine, engines and diagnostics | Preflight, queue policy (`BatchQueuePolicy`), ordered page work via processor operations, progress events/tracking/reconciliation (`processing/batch/progress`), download re-key (`CompletedDownloadRekeyCoordinator`). |

Session admission is centralized in `coordinator/TranslationSessionCoordinator`: reader and batch work claim the session there before admission validation and queue mutation. Page leases and write fences remain the final protection against stale or competing writers; do not treat admission policy as a replacement for those checks.

## Package ownership

| Package | Put this concern here |
| --- | --- |
| (root) | `TranslationManager` facade and its wiring (`TranslationManagerCoordinators.kt`, `TranslationManagerInitialization.kt`). Nothing else lives here. |
| `coordinator` | Orchestration and live coordination state: session admission (`TranslationSessionCoordinator`, `TranslationSession`), request lifecycle (`TranslationRequestCoordinator`, `TranslationPendingRequestStore`), reader lifecycle (`ReaderTranslationCoordinator`, `ReaderTranslationContracts`), batch orchestration (`BatchTranslationCoordinator`, `BatchQueuePolicy`, `ChapterQueuePreflight`, `ChapterQueueConflictDetection`), download re-key (`CompletedDownloadRekeyCoordinator`, `DownloadPageStreamResolver`), durable queue records (`TranslationQueueStore`), read models (`TranslationProgressProjection`, `TranslationQueueStatusProjection`), and store resolution (`TranslationStoreResolver`). One owner per state; projections read, they do not mutate. |
| `processing` | Single-page execution and its page-level helpers: `TranslationProcessor` (sole execution path), decode (`PageDecode`), stage semantics (`PostOcrStageSemantics`, `BatchPageOperation`), writes (`PageStoreWriter`), publications (`CleanedPublication`, `DeferredPagePublications`), attempt authority (`AttemptAuthority`), native safety (`execution/NativeRunQuarantine`, `execution/PreparedPageBoundary`, `execution/TranslationStreamRegistry`), engine lane (`EngineLane`), timeouts (`TranslationProcessingTimeouts`), memory pressure (`MemoryPressurePolicy`, `memory/`), device permitting (`adaptive/DevicePagePermitGate`), and batch support (`batch/` admission probe and outcomes, `batch/progress/` events, tracker, registry, reconciler, `planning/PageWorkPlanner`). |
| `engines/vision/{detection,segmentation,ocr,webtoon}` | Text/panel detection, bubble masks, recognition engines and webtoon image behavior. OCR engines compose the specialized recognition stages; `TranslationSafetyPrimitives` keeps the OCR native-buffer drain guard beside its lock owner. |
| `engines/translator` | Translation contracts and provider behavior. Add a provider under `engines/translator/providers`; keep provider request/response details there. `contextual` and `retry` retain their focused roles. |
| `context` | Chapter and series context used to prepare translation requests. |
| `engines/inpainting`, `engines/rendering` | Cleaned-image generation and translated text layout/rendering. |
| `persistence` | Durable and live chapter state, all at one level: live store (`ChapterTranslationStore`, `PageStageLeaseTable`), guarded access (`ChapterTranslationAccessController`, `ChapterBulkMutator`, `ChapterPageMutations`, `ChapterCandidateTransactions`), artifact documents/manifests/recovery (`ChapterArtifactEngine` and collaborators), reset and cleaned-image lifecycle (`ChapterDataResetController`, `CleanedImageLifecycleController`, `ChapterArtifactDeletion`, `ChapterArtifactRekey`), durable status (`DurableChapterStatusResolver`, `StoreStatusProjector`), write drain (`StoreWriteDrainCoordinator`, `ChapterStorePublicationBridge`), and file locations (`TranslationFileProvider`). |
| `model` | Shared translation values, page state and domain types. Keep feature policy and projections with their owning subsystem. |
| `engines/runtime` | `EngineMemoryBudget` provides heap/system snapshots, neural-inpaint reserves and engine memory diagnostics. `ModelDeployment` owns stamps and integrity checks for installed model files; `engines/runtime/onnx` owns ONNX initialization, model availability and device/runtime integration. |
| `diagnostics` | Trace/event vocabulary and diagnostic projections shared across execution paths. `ReaderEntryTrace` also measures artifact backup recovery and retention work. |
| `presentation` | Reader and confirmation-dialog projections, including the active translation settings summary and notification copy. |
| `util` | Small general helpers only, including shared SHA-256 digesting. Put translation policy beside the subsystem that owns it. |

For a provider, follow an existing implementation in `engines/translator/providers`, implement the established translator contract, and wire it through the existing engine builder/router. Do not add provider parsing or policy to `TranslationManager` or to batch orchestration.

## Live state and durable persistence

`persistence/ChapterTranslationStore` is the process-local owner of the current page state, display flows, generation, and mutation/commit coordination. Its lease table (`PageStageLeaseTable`), store mutex, lease tokens, source snapshots and generation checks work together; the lease table shares the store's lock and lease map and is not an independent cache.

`persistence/ChapterArtifactEngine` and its document/manifest collaborators own durable artifact state: candidates, committed pointers, stage records, sidecars, crash recovery and retention. On restart, the manifest and pointed-to documents are the source for reconstructing page state (`ChapterStoreOpener` seeds the live store from them); the in-memory chapter store is the source for current live projections and guarded mutations. `coordinator/TranslationQueueStore` and `coordinator/TranslationPendingRequestStore` own their respective durable queues/requests.

Keep candidate output separate from committed display output until the artifact commit succeeds. A failed or stale candidate must not replace the currently committed page result.

## Concurrency and dependencies

- A page-stage lease grants one writer origin ownership. A lease binds the store generation and page version; each guarded write must still match the lease token and artifact candidate fences.
- `TranslationSessionCoordinator` coordinates manual/auto reader and batch admission. Manual preemption of auto work, batch admission, and teardown ordering are intentional ownership transitions. Reader teardown cancels work without closing engines; engines are manager-scoped and only the explicit manager clear path closes them.
- Cancellation does not prove native work has exited. `processing/execution/NativeRunQuarantine` retains admission until the actual native call returns; `processing/EngineLane` serializes native engine use and close/rebuild operations.
- Durable publication must remain atomic through the artifact document/manifest protocol. Coroutine cancellation and `NonCancellable` lease cleanup paths preserve this contract.
- Keep dependencies pointed toward concrete owners: app/presentation -> `TranslationManager` facade -> coordinators -> processing -> engines -> live state, persistence and domain types. Low-level model, persistence, diagnostics and engine code should not reach up into coordinators or the processor. Known exceptions today: presentation reads coordinator result types (read-only projections), coordinator wiring reads the pure `presentation/TranslationUiProjection`, and a `model`<->`engines` cycle remains for translator/OCR language value types. Do not add new exceptions.
- Keep engine-owned native-memory facts in `engines/runtime`; keep reader/page preflight, decode sampling and reclamation policy in `processing/memory`.
- Add no package cycle, process-global state, or general-purpose abstraction without a real boundary. Prefer an existing concrete owner and a focused test seam.

Changes to leases, cancellation, generation fencing, store commits, artifact publication, recovery, or session coexistence need tests that exercise the relevant state transition and race—not only a happy-path output assertion.

## Known deferred seams

These are recorded, deliberate leftovers — change them only with their owners in mind:

- Admission claims precede generation validation in `coordinator/TranslationRequestCoordinator` (single, bulk and restored paths) and `coordinator/BatchTranslationCoordinator` start/requeue; this ordering is pre-existing behavior.
- `processing/planning/PageWorkPlanner` and `processing/batch/progress/TranslationBatchProgressTracker`/`BatchProgressReconciler` retain legacy render-phase semantics that no producer sets anymore; they await a decision on render ownership.
- `persistence/ChapterDataResetController` cancels and joins live reader/batch work through coordinator APIs before touching stores — correct behavior, but the coupling is a documented seam.
- The package boundary test `TranslationPackageBoundaryTest` still guards the retired `pipeline` package name and is vacuously green.

## Naming vocabulary

Use a suffix when it describes a type's primary responsibility, not as a generic decoration. Each suffix below appears in the translation subsystem; the role should remain distinct from nearby owners.

| Suffix | Intended role |
| --- | --- |
| `Engine` | Executes a focused processing capability such as recognition, translation, inpainting, or rendering. |
| `Coordinator` | Coordinates admission, lifecycle, or collaboration across distinct components. |
| `Controller` | Owns a bounded lifecycle or procedural flow (reset, cleaned-image lifecycle) as a concrete owner. |
| `Planner` | Computes a plan, ordering, or layout; it does not execute the resulting work. |
| `Store` | Owns live or durable state and its guarded mutation boundary. |
| `Registry` | Tracks keyed or active objects for lookup; it is not the durable source of truth. |
| `Provider` | Supplies or adapts a backing implementation/resource behind a contract. |
| `Projection` | Builds a read-facing view of state; it does not own authoritative mutations. |
| `Policy` | Encapsulates a focused decision rule without taking over lifecycle coordination. |
| `Resolver` | Maps current input or state to a concrete target, reference, or status. |
| `Worker` | Performs a bounded asynchronous job; admission and durable ownership remain elsewhere. |

When a responsibility does not fit one of these roles, prefer a precise domain name over adding a suffix by analogy.
