# Translation code map

`eu.kanade.translation` owns the reader's manga translation workflow, its page and chapter execution paths, and the engines and durable records those paths use. This guide is a short route into the code; the fuller flow and concurrency notes are in [`docs/translation-architecture.md`](../../../../../../../docs/translation-architecture.md).

## Follow the main flow

1. Start at `workflow/TranslationManager.kt` for the public façade, then follow `workflow/TranslationRequestCoordinator.kt` and `workflow/TranslationSessionCoordinator.kt` for request and reader/batch ownership.
2. Open `pipeline/TranslationPipeline.kt` for shared page execution. `pipeline/SinglePageOnnxPhase.kt` and `pipeline/SinglePageHttpRenderPhase.kt` split its native and provider/render work. For chapter translation, continue through `workflow/ChapterTranslator.kt` into `pipeline/batch/BatchChapterTranslator.kt` and the batch workers beside it. Batch progress events, tracking, and reconciliation live under `pipeline/batch/progress`.
3. Follow engine calls into `engines/vision`, `engines/translator`, `engines/inpainting`, or `engines/rendering`. Those packages own the specialized recognition, provider, cleanup, and layout behavior.
4. Follow page mutations and durable writes into `persistence/chapter` and `persistence/artifact`. `model` contains shared values and page state used along the way.

For a new provider, first read `engines/translator/TextTranslator.kt`, `engines/translator/TranslationEngineBuilder.kt`, and one implementation under `engines/translator/providers`. Keep provider request and response behavior with the provider implementation.

## Package ownership

| Package | Responsibility |
| --- | --- |
| `workflow` | Request admission, reader and batch session ownership, chapter lifecycle, and the `TranslationManager` façade. |
| `scheduling` | Which page jobs run and when: manual/auto reader windows, cancellation, native-run quarantine, and job coordination. It consumes workflow decisions; it does not define chapter or session intent. |
| `pipeline` | Shared page execution and stage contracts; `pipeline/batch` contains batch-specific preflight, analysis, execution, publication, and recovery; `pipeline/batch/progress` owns progress events, tracking, and reconciliation. |
| `engines/vision` | Text and panel detection, bubble segmentation, OCR, and webtoon image behavior. |
| `engines/translator` | Translator contracts, provider implementations, contextual/analysis requests, retries, and backend routing. |
| `engines/inpainting` | Page cleanup and inpainting implementations, including AOT, bubble, and OpenCV paths. |
| `engines/rendering` | Text layout, measurement, draw-plan construction, and persisted layout hydration. |
| `engines/runtime/onnx` | ONNX runtime setup, model routing and storage, and device capabilities. |
| `persistence/artifact` | Durable artifact documents, manifests, candidate/commit records, and recovery metadata. |
| `persistence/chapter` | Live chapter/page state coordination, leases, cleaned-image publication, and chapter translation file locations. |
| `persistence/queue` | Durable queue membership and pending request records. |
| `persistence/internal` | Concrete collaborators used by chapter-state and persistence owners; these are not a second public API. |
| `model` | Shared translation values, page state, and domain contracts. Keep feature policy and projections with their owning package. |
| `context` | Chapter and series context used to prepare translation requests. |
| `presentation` | Reader-facing translation truth, projections, and notification copy. |
| `diagnostics` | Trace and diagnostic data shared across execution paths. |
| `util` | Small general helpers. Keep translation policy with the subsystem that owns it. |

## State and durable records

`persistence/chapter/ChapterTranslationStore.kt` is the in-process authority for live page state, guarded mutations, leases, and display flows. Its synchronization, generation fences, and commit coordination work together. `persistence/artifact/ChapterArtifactEngine.kt` owns durable artifact records and crash recovery; manifests and committed document pointers are authoritative after restart. The in-memory store is authoritative for the current process projection. Queue and pending-request records have their own persistence owners.

Candidate output must remain separate from committed display output until artifact commit succeeds. A stale or failed candidate must not replace the last committed page result.

## Concurrency and changes

Workflow admission does not replace page leases or write fences. Cancellation does not prove a native call has exited; preserve the native-lane and quarantine rules. Keep dependencies pointed from presentation and workflow through pipeline and engines toward state, persistence, and model. Do not add package cycles, process-global state, or generic abstraction layers without a concrete boundary.

Changes to cancellation, coexistence, leases, generation fencing, durable commits, or recovery need focused tests for the affected transition and race. Run the translation unit tests for both Dev and Standard variants after structural changes.
