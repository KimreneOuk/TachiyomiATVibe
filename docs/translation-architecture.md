# Translation architecture

`eu.kanade.translation` contains the reader's page translation path and the chapter batch path. Start at `workflow`, follow shared execution through `pipeline`, then follow specialized work into `engines` and durable/live state into `persistence`.

See the [translation input quality improvement plan](translation-input-quality-plan.md) for the Standard/AI audit, proposed implementation phases, and evaluation criteria.

## Main page flow

```text
reader or batch request
  -> session and work admission
  -> decode and recognition (text detection, optional bubble segmentation, OCR)
  -> chapter context and provider translation
  -> inpainting and text layout/rendering
  -> candidate validation and artifact commit
  -> live display projection and reader stream
```

`pipeline/TranslationPipeline.kt` is the shared execution entry point. Its single-page work is split between `pipeline/SinglePageOnnxPhase.kt` (native-lane decode, recognition, inpainting and OCR commit) and `pipeline/SinglePageHttpRenderPhase.kt` (provider work and rendering outside the native permit). `pipeline/planning/PageWorkPlanner.kt` owns plans shared by single-page and batch paths; keep it here rather than under `pipeline/batch` so the single-page path can share it without depending on a batch package. Chapter traversal snapshots pages in their existing natural order directly. `pipeline/memory/TranslationMemoryBudget.kt` and `MemoryGovernance.kt` own page decode/preflight/prefetch decisions and memory-reclamation policy. `engines/runtime/EngineMemoryBudget.kt` owns process heap and system-memory facts, NNAPI snapshots, neural-inpaint reserves, and memory diagnostics used by OCR and inpainting. `engines/vision/ocr/RoiPageRecognitionEngine.kt` composes detection, optional bubble segmentation and OCR for the ROI path. `pipeline/PageStoreWriter.kt` and `persistence/chapter/ChapterTranslationStore.kt` mediate candidate stage writes and commits. `persistence/artifact/ChapterArtifactEngine.kt` owns durable chapter artifact documents and recovery.

## Execution modes

| Mode | Entry and owner | Shared pieces | Mode-specific work |
| --- | --- | --- | --- |
| Manual page | `workflow/TranslationManager` → `scheduling/TranslationScheduler` | `pipeline/execution/TranslationExecutor`, `pipeline/TranslationPipeline`, `EngineLane`, chapter store, vision/translator/rendering engines | A user request is admitted and deduplicated for one page. |
| Auto reader | `workflow/TranslationManager` / reader session → `scheduling/TranslationScheduler` and `RollingAutoCoordinator` | Same page execution contract, pipeline, chapter store, provider and native lane | Reader windows, auto ownership/generations, pause and teardown rules. |
| Batch chapter | `workflow/ChapterTranslator` → `pipeline/batch/BatchChapterTranslator` and batch workers | Chapter store, artifact engine, OCR/translator/inpainting/rendering engines and diagnostics | Batch preflight, envelopes, ordered chunk work, candidate publication and resume/recovery. Chapter-wide analysis and durable chunk publication are grouped in `pipeline/batch/analysis`; AI profile envelope dispatch and plan publication are grouped in `pipeline/batch/envelope`; batch progress events, tracking and reconciliation are grouped in `pipeline/batch/progress`; resume decisions and recovery workers are grouped in `pipeline/batch/recovery`. |

The session coordinator in `workflow` arbitrates reader and batch admission. Page leases and write fences remain the final protection against stale or competing writers; do not treat admission policy as a replacement for those checks. Manual, auto and batch paths share engines and chapter state, but use different scheduling and publication flows.

## Package ownership

| Package | Put this concern here |
| --- | --- |
| `workflow` | Request admission, reader/batch session ownership, chapter lifecycle and reader teardown. `TranslationManager` is the public façade. |
| `scheduling` | Which page jobs run and when: manual/auto reader jobs, rolling windows, cancellation and auto reader windows. Workflow decides ownership and intent. |
| `pipeline` | Page execution, engine lane, stage contracts, writes, decoding and single-page phases. `pipeline.execution` owns the page executor contract, prepared-page boundary, native-run quarantine and image-stream registry. `pipeline.planning` owns `PageWorkPlanner` (shared stage planning for single-page and batch paths); chapter traversal snapshots its already ordered stream directly. `pipeline.memory` owns `TranslationMemoryBudget` and `MemoryGovernance`, page decode/preflight/prefetch decisions and memory-reclamation policy. Engine-level heap/native-memory facts are in `engines/runtime/EngineMemoryBudget`. `pipeline.batch` owns chapter coordination and mode-specific work; `pipeline.batch.analysis` owns chapter-wide analysis and durable chunk publication; `pipeline.batch.envelope` owns AI profile envelope dispatch and plan publication; `pipeline.batch.progress` owns progress events, tracking and reconciliation; `pipeline.batch.recovery` owns resume-stage policy and batch recovery workers. |
| `engines/vision/{detection,segmentation,ocr,webtoon}` | Text/panel detection, bubble masks, recognition engines and webtoon image behavior. OCR engines compose the specialized recognition stages; `TranslationSafetyPrimitives` keeps the OCR native-buffer drain guard beside its lock owner. |
| `engines/translator` | Translation contracts and provider behavior. Add a provider under `engines/translator/providers`; keep provider request/response details there. `contextual`, `analysis`, `retry` and `routing` retain their focused roles. |
| `context` | Chapter and series context used to prepare translation requests. |
| `engines/inpainting`, `engines/rendering` | Cleaned-image generation and translated text layout/rendering. |
| `persistence/{artifact,chapter,queue,internal}` | Artifact documents and recovery; live chapter state, mutation coordination and translation-file locations (`TranslationFileProvider`); durable queue records; chapter-state collaborators, including `ChapterGlossaryAccumulator`. The artifact engine returns neutral candidate-open outcomes; `pipeline.batch` maps candidate reuse to batch diagnostics. See the authority distinction below. |
| `model` | Shared translation values, page state and domain types. Keep feature policy and projections with their owning subsystem. |
| `engines/runtime` | `EngineMemoryBudget` provides heap/system snapshots, neural-inpaint reserves and engine memory diagnostics. `ModelDeployment` owns stamps and integrity checks for installed model files; `engines/runtime/onnx` owns ONNX initialization, model availability and device/runtime integration. |
| `diagnostics` | Trace/event vocabulary and diagnostic projections shared across execution paths. `ReaderEntryTrace` also measures artifact backup recovery and retention work. |
| `presentation` | Reader and confirmation-dialog projections, including the active translation settings summary and notification copy. |
| `util` | Small general helpers only, including shared SHA-256 digesting. Put translation policy beside the subsystem that owns it. |

For a provider, follow an existing implementation in `engines/translator/providers`, implement the established translator contract, and wire it through the existing engine builder/router. Do not add provider parsing or policy to `workflow/TranslationManager` or batch execution.

## Live state and durable persistence

`persistence/chapter/ChapterTranslationStore` is the process-local owner of the current page state, display flows, generation, and mutation/commit coordination. Its lease table, store mutex, lease tokens, source snapshots and generation checks work together. `persistence/internal/PageStageLeaseTable` shares the store's lock and lease map; it is not an independent cache.

`persistence/artifact/ChapterArtifactEngine` and its document/manifest collaborators own durable artifact state: candidates, committed pointers, stage records, sidecars, crash recovery and retention. On restart, the manifest and pointed-to documents are the source for reconstructing page state; the in-memory chapter store is the source for current live projections and guarded mutations. `persistence/queue/TranslationQueueStore` and `TranslationPendingRequestStore` own their respective durable queues/requests. Glossary and attempt-ledger collaborators persist their domain records through the artifact path.

Keep candidate output separate from committed display output until the artifact commit succeeds. A failed or stale candidate must not replace the currently committed page result.

## Concurrency and dependencies

- A page-stage lease grants one writer origin ownership. A lease binds the store generation and page version; each guarded write must still match the lease token and artifact candidate fences.
- Session admission coordinates manual/auto reader and batch work. Manual preemption of auto work, batch admission, and teardown ordering are intentional ownership transitions.
- Cancellation does not prove native work has exited. `pipeline/execution/NativeRunQuarantine` retains admission until the actual native call returns; `EngineLane` serializes native engine use and close/rebuild operations.
- Durable publication must remain atomic through the artifact document/manifest protocol. Coroutine cancellation and `NonCancellable` lease cleanup paths preserve this contract.
- Keep dependencies pointed toward concrete owners: presentation/workflow → scheduling and pipeline → engines → live state, persistence and domain types. Low-level model, persistence/artifact, diagnostics and engine code should not reach up into batch execution or reader workflow. Known exceptions today: presentation reads pipeline/scheduling result types (read-only projections), `workflow/TranslationRequestCoordinator` imports the pure `presentation/TranslationUiProjection`, and a `model`↔`engines` cycle remains for translator/OCR language value types. Do not add new exceptions.
- Keep engine-owned native-memory facts in `engines/runtime`; keep reader/page preflight, decode sampling and reclamation policy in `pipeline/memory`.
- Add no package cycle, process-global state, or general-purpose abstraction without a real boundary. Prefer an existing concrete owner and a focused test seam.

Changes to leases, cancellation, generation fencing, store commits, artifact publication, recovery, or session coexistence need tests that exercise the relevant state transition and race—not only a happy-path output assertion.

## Naming vocabulary

Use a suffix when it describes a type's primary responsibility, not as a generic decoration. Each suffix below appears in the translation subsystem; the role should remain distinct from nearby owners.

| Suffix | Intended role |
| --- | --- |
| `Engine` | Executes a focused processing capability such as recognition, translation, inpainting, or rendering. |
| `Coordinator` | Coordinates admission, lifecycle, or collaboration across distinct components. |
| `Planner` | Computes a plan, ordering, or layout; it does not execute the resulting work. |
| `Store` | Owns live or durable state and its guarded mutation boundary. |
| `Registry` | Tracks keyed or active objects for lookup; it is not the durable source of truth. |
| `Provider` | Supplies or adapts a backing implementation/resource behind a contract. |
| `Projection` | Builds a read-facing view of state; it does not own authoritative mutations. |
| `Policy` | Encapsulates a focused decision rule without taking over lifecycle coordination. |
| `Resolver` | Maps current input or state to a concrete target, reference, or status. |
| `Worker` | Performs a bounded asynchronous job; admission and durable ownership remain elsewhere. |

When a responsibility does not fit one of these roles, prefer a precise domain name over adding a suffix by analogy.
