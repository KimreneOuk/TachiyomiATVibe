# Translation code map

`eu.kanade.translation` owns the reader's manga translation path, its chapter batch path, and the engines and durable records those paths use. This guide is a short route into package ownership, request flow, state, and concurrency boundaries.

## Architecture at a glance

Requests move from the reader through the facade into coordinator ownership, then into single-page execution; state and results flow back through projections.

```text
Reader / chapter UI
    | request
    v
TranslationManager (package root) -- app-facing wiring facade, no execution or queue state
    +-- coordinator -- owns session admission, request lifecycle, reader lifecycle, batch queue
    |       \-- processing/TranslationProcessor -- sole single-page execution path;
    |           batch work drives the same operations one page at a time
    |           +-- engines -- vision -> translator -> inpainting -> rendering
    |           \-- persistence -- fenced candidate commits, artifacts, durable queues
    reader presentation -- owns layout, drawing, and display fallback
            | state and results
            v
        coordinator/persistence projections -> reader UI
```

## Where do I make this change?

| Concern | Start here |
| --- | --- |
| Translator/provider implementation | `engines/translator/providers/`; shared contracts are under `engines/translator/`. |
| OCR | `engines/vision/ocr/`. |
| Text or panel detection | `engines/vision/detection/`; bubble segmentation is under `engines/vision/segmentation/`. |
| Inpainting | `engines/inpainting/`. |
| Text rendering and layout | `engines/rendering/`. |
| Manual/auto reader work | `coordinator/ReaderTranslationCoordinator.kt` (reader tokens, session state, teardown). |
| Request admission and dedup | `coordinator/TranslationRequestCoordinator.kt` with `coordinator/TranslationSessionCoordinator.kt`. |
| Batch queue, progress and terminal cleanup | `coordinator/BatchTranslationCoordinator.kt`; progress events live in `processing/batch/progress/`. |
| Single-page execution | `processing/TranslationProcessor.kt`; native safety in `processing/execution/`, memory policy in `processing/memory/`. |
| Durable artifact behavior | `persistence/` (`ChapterArtifactEngine.kt` and collaborators). |
| Live page state and leases | `persistence/ChapterTranslationStore.kt` with `PageStageLeaseTable.kt`. |
| Chapter reset / cleaned-image lifecycle | `persistence/ChapterDataResetController.kt`, `persistence/CleanedImageLifecycleController.kt`. |
| UI and projection behavior | `presentation/` for translation projections; `app/src/main/java/eu/kanade/presentation/` for Compose UI surfaces. |

## Follow the main flow

1. Start at `TranslationManager.kt` (package root) for the public facade, then follow `coordinator/TranslationRequestCoordinator.kt` and `coordinator/TranslationSessionCoordinator.kt` for request and reader/batch admission, `coordinator/ReaderTranslationCoordinator.kt` for reader work ownership, and `coordinator/BatchTranslationCoordinator.kt` for batch queue and lifecycle.
2. `processing/TranslationProcessor.kt` is the only single-page execution path and shares its operations with batch work. `processing/execution/` retains native-run quarantine, the prepared-page boundary and the image-stream registry. `processing/planning/PageWorkPlanner.kt` remains batch planning support; reader admission and viewport work belong to the coordinator. `processing/memory/TranslationMemoryBudget.kt` and `MemoryGovernance.kt` own page decode and reclamation decisions. `engines/runtime/EngineMemoryBudget.kt` owns engine memory diagnostics. The reader presentation layer owns layout and drawing.
3. Follow engine calls into `engines/vision`, `engines/translator`, `engines/inpainting`, `engines/rendering`, or `engines/runtime`. Those packages own specialized recognition (`engines/vision/ocr/TranslationSafetyPrimitives.kt` guards native-buffer drains), provider, cleanup, layout, and model deployment (`engines/runtime/ModelDeployment.kt`) behavior.
4. Follow page mutations and live chapter state into `persistence/ChapterTranslationStore.kt` and `TranslationFileProvider.kt`, and durable artifact writes into `persistence/ChapterArtifactEngine.kt` and its collaborators. `model` contains shared values and page state used along the way.

For a new provider, first read `engines/translator/TextTranslator.kt`, `engines/translator/TranslationEngineBuilder.kt`, and one implementation under `engines/translator/providers`. Keep provider request and response behavior with the provider implementation.

## Package ownership

| Package | Responsibility |
| --- | --- |
| (root) | `TranslationManager` facade plus its wiring (`TranslationManagerCoordinators.kt`, `TranslationManagerInitialization.kt`). |
| `coordinator` | Session admission, request lifecycle, reader work ownership and teardown, batch queue and lifecycle, download re-key, durable queue records, and read-model projections. |
| `processing` | The single-page execution path, decode, stage semantics, page writes and publications, native safety, engine lane, timeouts, memory governance, device permitting, and batch support (planning, progress events/tracking/reconciliation). |
| `engines/vision` | Text and panel detection, bubble segmentation, OCR, the OCR native-buffer drain guard, and webtoon image behavior. |
| `engines/translator` | Translator contracts, provider implementations, contextual requests, and retries. |
| `engines/inpainting` | Page cleanup and inpainting implementations, including AOT, bubble, LiteRT and OpenCV paths. |
| `engines/rendering` | Text layout, measurement, draw-plan construction, and persisted layout hydration. |
| `engines/runtime` | Engine heap/system-memory snapshots, NNAPI snapshots, neural-inpaint reserves and memory diagnostics; model deployment stamps and integrity checks; `engines/runtime/onnx` owns ONNX runtime setup, model routing and storage, and device capabilities. |
| `persistence` | Live chapter state, leases and guarded mutations; durable artifact documents, manifests, recovery and retention; chapter reset and cleaned-image lifecycle; durable status resolution; write drain; `TranslationFileProvider` file locations. |
| `model` | Shared translation values, page state, and domain contracts. Keep feature policy and projections with their owning package. |
| `context` | Chapter and series context used to prepare translation requests. |
| `presentation` | Reader and confirmation-dialog projections, including translation settings summaries and notification copy. |
| `diagnostics` | Trace and diagnostic data shared across execution paths. `ReaderEntryTrace` also measures artifact backup recovery and retention. |
| `util` | Small general helpers, including shared SHA-256 digesting. Keep translation policy with the subsystem that owns it. |

## State and durable records

`persistence/ChapterTranslationStore.kt` is the in-process authority for live page state, guarded mutations, leases, and display flows. Its synchronization, generation fences, and commit coordination work together; on open, `persistence/ChapterStoreOpener.kt` seeds the store from durable artifact state, and accepted store mutations then remain the single live projection source. `persistence/ChapterArtifactEngine.kt` owns manifests, immutable page payloads, and committed display pointers. Queue and pending-request records have their own persistence owners (`coordinator/TranslationQueueStore.kt`, `coordinator/TranslationPendingRequestStore.kt`).

Candidate output must remain separate from committed display output until artifact commit succeeds. A stale or failed candidate must not replace the last committed page result.

## Concurrency and changes

Coordinator admission does not replace page leases or write fences. Cancellation does not prove a native call has exited; preserve the native-lane and quarantine rules (`processing/execution/NativeRunQuarantine.kt`, `processing/EngineLane.kt`). Reader teardown cancels work without closing engines; only the explicit manager clear path closes them. Keep dependencies pointed from presentation through the facade and coordinators, through processing and engines, toward state, persistence, and model. Do not add package cycles, process-global state, or generic abstraction layers without a concrete boundary.

Changes to cancellation, coexistence, leases, generation fencing, durable commits, or recovery need focused tests for the affected transition and race. Run the translation unit tests for both Dev and Standard variants after structural changes.
