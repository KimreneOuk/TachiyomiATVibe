# Translation architecture

`eu.kanade.translation` contains the reader's page translation path and the chapter batch path. This guide is a map for contributors: follow the request into orchestration, then into the execution mode and the engine or state owner that actually does the work.

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

`TranslationPipeline` is the shared execution entry point. Its single-page work is split between `SinglePageOnnxPhase` (native-lane decode, recognition, inpainting and OCR commit) and `SinglePageHttpRenderPhase` (provider work and rendering outside the native permit). `RoiPageRecognitionEngine` composes detection, optional bubble segmentation and OCR for the ROI path; `MlKitFullPageRecognitionEngine` is the alternate full-page path. `PageStoreWriter` and `ChapterTranslationStore` mediate candidate stage writes and commits. `ChapterArtifactEngine` owns durable chapter artifact documents and recovery.

## Execution modes

| Mode | Entry and owner | Shared pieces | Mode-specific work |
| --- | --- | --- | --- |
| Manual page | `TranslationManager` → `TranslationScheduler` | `TranslationPipeline`, `EngineLane`, page store, OCR/provider/rendering | A user request is admitted and deduplicated for one page. |
| Auto reader | `TranslationManager` / reader session → `TranslationScheduler` and `RollingAutoCoordinator` | Same page pipeline, store, provider and native lane | Reader windows, auto ownership/generations, pause and teardown rules. |
| Batch chapter | `ChapterTranslator` → `BatchChapterTranslator` and `pipeline.batch` workers | Store, artifact engine, OCR/translator/inpainting/rendering engines and progress diagnostics | Batch preflight, envelopes, ordered chunk work, candidate publication and resume/recovery. |

The session coordinator arbitrates reader and batch admission. Page leases and write fences remain the final protection against stale or competing writers; do not treat admission policy as a replacement for those checks. Manual, auto and batch paths share engines and chapter state, but use different scheduling and publication flows.

## Package ownership

| Package | Put this concern here |
| --- | --- |
| `orchestration` | Translation requests, reader/batch session admission, chapter translation queue lifecycle and reader teardown. `TranslationManager` is the public façade. |
| `scheduling` | Manual and auto reader jobs, rolling windows, cancellation and native-run quarantine. |
| `pipeline` | Page execution, engine lane, stage contracts, writes, decoding, memory governance and single-page phases. `pipeline.batch` owns batch-specific preflight, execution, publication and recovery. |
| `detection`, `segmentation`, `ocr` | Text/panel detection, bubble masks and recognition engines. OCR engines coordinate their specialized detection/segmentation/OCR stages. |
| `context`, `translator` | Chapter context, translation contracts and provider behavior. Add a provider under `translator/providers`; keep provider request/response details there. `contextual`, `analysis`, `retry` and `routing` retain their focused roles. |
| `inpainting`, `rendering` | Cleaned-image generation and translated text layout/rendering. |
| `storage`, `store`, `artifact` | Live chapter state and mutation coordination; extracted state collaborators; durable artifact documents, manifests and recovery. See the authority distinction below. |
| `model` | Shared translation values, page state and domain types. Keep feature policy and projections with their owning subsystem. |
| `runtime` | Runtime integration such as ONNX initialization and model availability. |
| `diagnostics` | Trace/event vocabulary and diagnostic projections shared across execution paths. |
| `ui`, `webtoon` | Reader-facing translation presentation and webtoon-specific behavior. |
| `util` | Small general helpers only. Put translation policy beside the subsystem that owns it. |

For a provider, follow an existing implementation in `translator/providers`, implement the established translator contract, and wire it through the existing engine builder/router. Do not add provider parsing or policy to `TranslationManager` or batch orchestration.

## Live state and durable persistence

`ChapterTranslationStore` is the process-local owner of the current page state, display flows, generation, and mutation/commit coordination. Its lease table, store mutex, lease tokens, source snapshots and generation checks work together. `PageStageLeaseTable` shares the store's lock and lease map; it is not an independent cache.

`ChapterArtifactEngine` and its document/manifest collaborators own durable artifact state: candidates, committed pointers, stage records, sidecars, crash recovery and retention. On restart, the manifest and pointed-to documents are the source for reconstructing page state; the in-memory store is the source for current live projections and guarded mutations. `TranslationQueueStore` and `TranslationPendingRequestStore` own their respective durable queues/requests. Glossary and attempt-ledger collaborators persist their domain records through the artifact path.

Keep candidate output separate from committed display output until the artifact commit succeeds. A failed or stale candidate must not replace the currently committed page result.

## Concurrency and dependencies

- A page-stage lease grants one writer origin ownership. A lease binds the store generation and page version; each guarded write must still match the lease token and artifact candidate fences.
- Session admission coordinates manual/auto reader and batch work. Manual preemption of auto work, batch admission, and teardown ordering are intentional ownership transitions.
- Cancellation does not prove native work has exited. `NativeRunQuarantine` retains admission until the actual native call returns; `EngineLane` serializes native engine use and close/rebuild operations.
- Durable publication must remain atomic through the artifact document/manifest protocol. Coroutine cancellation and `NonCancellable` lease cleanup paths preserve this contract.
- Keep dependencies pointed toward concrete owners: UI → orchestration/scheduling → execution and engines → live state, persistence and domain types. Low-level model, artifact, diagnostics and engine code should not reach up into batch execution or reader orchestration.
- Add no package cycle, process-global state, or general-purpose abstraction without a real boundary. Prefer an existing concrete owner and a focused test seam.

Changes to leases, cancellation, generation fencing, store commits, artifact publication, recovery, or session coexistence need tests that exercise the relevant state transition and race—not only a happy-path output assertion.
