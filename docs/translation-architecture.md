# Translation architecture

`eu.kanade.translation` contains the reader's page translation path and the chapter batch path. Start at `workflow`, follow shared execution through `pipeline`, then follow specialized work into `engines` and durable/live state into `persistence`.

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

`pipeline/TranslationPipeline.kt` is the shared execution entry point. Its single-page work is split between `pipeline/SinglePageOnnxPhase.kt` (native-lane decode, recognition, inpainting and OCR commit) and `pipeline/SinglePageHttpRenderPhase.kt` (provider work and rendering outside the native permit). `engines/vision/ocr/RoiPageRecognitionEngine.kt` composes detection, optional bubble segmentation and OCR for the ROI path; `MlKitFullPageRecognitionEngine` is the alternate full-page path. `pipeline/PageStoreWriter.kt` and `persistence/chapter/ChapterTranslationStore.kt` mediate candidate stage writes and commits. `persistence/artifact/ChapterArtifactEngine.kt` owns durable chapter artifact documents and recovery.

## Execution modes

| Mode | Entry and owner | Shared pieces | Mode-specific work |
| --- | --- | --- | --- |
| Manual page | `workflow/TranslationManager` → `scheduling/TranslationScheduler` | `pipeline/TranslationPipeline`, `EngineLane`, chapter store, vision/translator/rendering engines | A user request is admitted and deduplicated for one page. |
| Auto reader | `workflow/TranslationManager` / reader session → `scheduling/TranslationScheduler` and `RollingAutoCoordinator` | Same page pipeline, chapter store, provider and native lane | Reader windows, auto ownership/generations, pause and teardown rules. |
| Batch chapter | `workflow/ChapterTranslator` → `pipeline/batch/BatchChapterTranslator` and batch workers | Chapter store, artifact engine, OCR/translator/inpainting/rendering engines and diagnostics | Batch preflight, envelopes, ordered chunk work, candidate publication and resume/recovery. Batch progress events, tracking and reconciliation are grouped in `pipeline/batch/progress`. |

The session coordinator in `workflow` arbitrates reader and batch admission. Page leases and write fences remain the final protection against stale or competing writers; do not treat admission policy as a replacement for those checks. Manual, auto and batch paths share engines and chapter state, but use different scheduling and publication flows.

## Package ownership

| Package | Put this concern here |
| --- | --- |
| `workflow` | Request admission, reader/batch session ownership, chapter lifecycle and reader teardown. `TranslationManager` is the public façade. |
| `scheduling` | Which page jobs run and when: manual/auto reader jobs, rolling windows, cancellation and native-run quarantine. Workflow decides ownership and intent. |
| `pipeline` | Page execution, engine lane, stage contracts, writes, decoding, memory governance and single-page phases. `pipeline.batch` owns batch-specific preflight, execution, publication and recovery; `pipeline.batch.progress` owns progress events, tracking and reconciliation. |
| `engines/vision/{detection,segmentation,ocr,webtoon}` | Text/panel detection, bubble masks, recognition engines and webtoon image behavior. OCR engines compose the specialized recognition stages. |
| `engines/translator` | Translation contracts and provider behavior. Add a provider under `engines/translator/providers`; keep provider request/response details there. `contextual`, `analysis`, `retry` and `routing` retain their focused roles. |
| `context` | Chapter and series context used to prepare translation requests. |
| `engines/inpainting`, `engines/rendering` | Cleaned-image generation and translated text layout/rendering. |
| `persistence/{artifact,chapter,queue,internal}` | Artifact documents and recovery; live chapter state, mutation coordination and translation-file locations; durable queue records; chapter-state collaborators. See the authority distinction below. |
| `model` | Shared translation values, page state and domain types. Keep feature policy and projections with their owning subsystem. |
| `engines/runtime/onnx` | ONNX initialization, model availability and device/runtime integration. |
| `diagnostics` | Trace/event vocabulary and diagnostic projections shared across execution paths. |
| `presentation` | Reader-facing translation truth, projections and notification copy. |
| `util` | Small general helpers only. Put translation policy beside the subsystem that owns it. |

For a provider, follow an existing implementation in `engines/translator/providers`, implement the established translator contract, and wire it through the existing engine builder/router. Do not add provider parsing or policy to `workflow/TranslationManager` or batch execution.

## Live state and durable persistence

`persistence/chapter/ChapterTranslationStore` is the process-local owner of the current page state, display flows, generation, and mutation/commit coordination. Its lease table, store mutex, lease tokens, source snapshots and generation checks work together. `persistence/internal/PageStageLeaseTable` shares the store's lock and lease map; it is not an independent cache.

`persistence/artifact/ChapterArtifactEngine` and its document/manifest collaborators own durable artifact state: candidates, committed pointers, stage records, sidecars, crash recovery and retention. On restart, the manifest and pointed-to documents are the source for reconstructing page state; the in-memory chapter store is the source for current live projections and guarded mutations. `persistence/queue/TranslationQueueStore` and `TranslationPendingRequestStore` own their respective durable queues/requests. Glossary and attempt-ledger collaborators persist their domain records through the artifact path.

Keep candidate output separate from committed display output until the artifact commit succeeds. A failed or stale candidate must not replace the currently committed page result.

## Concurrency and dependencies

- A page-stage lease grants one writer origin ownership. A lease binds the store generation and page version; each guarded write must still match the lease token and artifact candidate fences.
- Session admission coordinates manual/auto reader and batch work. Manual preemption of auto work, batch admission, and teardown ordering are intentional ownership transitions.
- Cancellation does not prove native work has exited. `NativeRunQuarantine` retains admission until the actual native call returns; `EngineLane` serializes native engine use and close/rebuild operations.
- Durable publication must remain atomic through the artifact document/manifest protocol. Coroutine cancellation and `NonCancellable` lease cleanup paths preserve this contract.
- Keep dependencies pointed toward concrete owners: presentation/workflow → scheduling and pipeline → engines → live state, persistence and domain types. Low-level model, persistence/artifact, diagnostics and engine code should not reach up into batch execution or reader workflow.
- Add no package cycle, process-global state, or general-purpose abstraction without a real boundary. Prefer an existing concrete owner and a focused test seam.

Changes to leases, cancellation, generation fencing, store commits, artifact publication, recovery, or session coexistence need tests that exercise the relevant state transition and race—not only a happy-path output assertion.
