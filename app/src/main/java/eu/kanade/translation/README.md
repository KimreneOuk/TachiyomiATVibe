# Translation code map

`eu.kanade.translation` owns the reader's manga translation workflow, its page and chapter execution paths, and the engines and durable records those paths use. This guide is a short route into the code; the fuller flow and concurrency notes are in [`docs/translation-architecture.md`](../../../../../../../docs/translation-architecture.md).

## Architecture at a glance

Requests move from the reader through ownership and scheduling into execution; state and results flow back through projections.

```text
Reader / chapter UI
    | request
    v
workflow -- owns admission and reader/batch lifecycle
    +-- scheduling -- selects and times manual/auto page jobs
    \-- pipeline -- executes page and chapter work
        +-- engines -- vision -> translator -> inpainting/rendering
        \-- persistence -- live page state, artifacts, and queue
            | state and results
            v
        presentation projections -> reader UI
```

## Where do I make this change?

| Concern | Start here |
| --- | --- |
| Translator/provider implementation | `engines/translator/providers/`; shared contracts and routing are under `engines/translator/`. |
| OCR | `engines/vision/ocr/`. |
| Text or panel detection | `engines/vision/detection/`; bubble segmentation is under `engines/vision/segmentation/`. |
| Inpainting | `engines/inpainting/`. |
| Text rendering and layout | `engines/rendering/`. |
| Manual/auto scheduling | `scheduling/`; use `workflow/` when changing request intent or ownership. |
| Reader/batch ownership | `workflow/`; chapter-specific execution is under `pipeline/batch/`. |
| Batch recovery | `pipeline/batch/recovery/`. |
| Durable artifact behavior | `persistence/artifact/`. |
| Live page state and leases | `persistence/chapter/`. |
| UI and projection behavior | `presentation/` for translation projections; `app/src/main/java/eu/kanade/presentation/` for Compose UI surfaces. |

## Follow the main flow

1. Start at `workflow/TranslationManager.kt` for the public façade, then follow `workflow/TranslationRequestCoordinator.kt` and `workflow/TranslationSessionCoordinator.kt` for request and reader/batch ownership.
2. Open `pipeline/TranslationPipeline.kt` for shared page execution. `pipeline/execution` owns the page executor contract, prepared-page boundary, native-run quarantine and image-stream registry. `pipeline/planning/PageWorkPlanner.kt` owns plans shared by single-page and batch paths, so the single-page path can use it without depending on a batch package. Chapter traversal snapshots its already ordered page stream directly. `pipeline/memory/TranslationMemoryBudget.kt` and `MemoryGovernance.kt` own page decode/preflight/prefetch decisions and reclamation. `engines/runtime/EngineMemoryBudget.kt` owns process heap/system snapshots, NNAPI snapshots, neural-inpaint reserves and engine memory diagnostics. `pipeline/SinglePageOnnxPhase.kt` and `pipeline/SinglePageHttpRenderPhase.kt` split native and provider/render work. For chapter translation, continue through `workflow/ChapterTranslator.kt` into `pipeline/batch/BatchChapterTranslator.kt`. Chapter-wide analysis and durable chunk publication are in `pipeline/batch/analysis`; AI profile envelope dispatch and plan publication are in `pipeline/batch/envelope`; progress events, tracking, and reconciliation are in `pipeline/batch/progress`; resume policy and recovery workers are in `pipeline/batch/recovery`.
3. Follow engine calls into `engines/vision`, `engines/translator`, `engines/inpainting`, `engines/rendering`, or `engines/runtime`. Those packages own specialized recognition (`engines/vision/ocr/TranslationSafetyPrimitives.kt` guards native-buffer drains), provider, cleanup, layout, and model deployment (`engines/runtime/ModelDeployment.kt`) behavior.
4. Follow page mutations and live chapter state into `persistence/chapter` (`ChapterTranslationStore` and `TranslationFileProvider`), and durable artifact writes into `persistence/artifact`. `model` contains shared values and page state used along the way.

For a new provider, first read `engines/translator/TextTranslator.kt`, `engines/translator/TranslationEngineBuilder.kt`, and one implementation under `engines/translator/providers`. Keep provider request and response behavior with the provider implementation.

## Package ownership

| Package | Responsibility |
| --- | --- |
| `workflow` | Request admission, reader and batch session ownership, chapter lifecycle, and the `TranslationManager` façade. |
| `scheduling` | Which page jobs run and when: manual/auto reader windows, cancellation, and job coordination. It consumes workflow decisions; it does not define chapter or session intent. |
| `pipeline` | Shared page execution and stage contracts; `pipeline/execution` owns page executor contracts, prepared-page publication, native-run quarantine and stream registry; `pipeline/planning` owns shared page-stage planning and stable page-order snapshots; `pipeline/memory` owns page decode/preflight/prefetch decisions and reclamation policy; `pipeline/batch` contains chapter coordination and mode-specific work; `pipeline/batch/analysis` owns chapter-wide analysis and durable chunk publication; `pipeline/batch/envelope` owns AI profile envelope dispatch and plan publication; `pipeline/batch/progress` owns progress events, tracking, and reconciliation; `pipeline/batch/recovery` owns resume policy and recovery workers. |
| `engines/vision` | Text and panel detection, bubble segmentation, OCR, the OCR native-buffer drain guard, and webtoon image behavior. |
| `engines/translator` | Translator contracts, provider implementations, contextual/analysis requests, retries, and backend routing. |
| `engines/inpainting` | Page cleanup and inpainting implementations, including AOT, bubble, and OpenCV paths. |
| `engines/rendering` | Text layout, measurement, draw-plan construction, and persisted layout hydration. |
| `engines/runtime` | Engine heap/system-memory snapshots, NNAPI snapshots, neural-inpaint reserves and memory diagnostics; model deployment stamps and integrity checks; `engines/runtime/onnx` owns ONNX runtime setup, model routing and storage, and device capabilities. |
| `persistence/artifact` | Durable artifact documents, manifests, candidate/commit records, and recovery metadata. Candidate-open outcomes are neutral; `pipeline.batch` maps reuse outcomes to batch diagnostics. |
| `persistence/chapter` | Live chapter/page state coordination, leases, cleaned-image publication, and `TranslationFileProvider` chapter file locations. |
| `persistence/queue` | Durable queue membership and pending request records. |
| `persistence/internal` | Concrete collaborators used by chapter-state and persistence owners; these are not a second public API. |
| `model` | Shared translation values, page state, and domain contracts. Keep feature policy and projections with their owning package. |
| `context` | Chapter and series context used to prepare translation requests. |
| `presentation` | Reader and confirmation-dialog projections, including translation settings summaries and notification copy. |
| `diagnostics` | Trace and diagnostic data shared across execution paths. `ReaderEntryTrace` also measures artifact backup recovery and retention. |
| `util` | Small general helpers, including shared SHA-256 digesting. Keep translation policy with the subsystem that owns it. |

## State and durable records

`persistence/chapter/ChapterTranslationStore.kt` is the in-process authority for live page state, guarded mutations, leases, and display flows. Its synchronization, generation fences, and commit coordination work together. On open, `ChapterJournalRecovery` replays the durable journal prefix to seed that store; accepted store mutations then remain the single live projection source. `persistence/artifact/ChapterArtifactEngine.kt` owns manifests, immutable page payloads, and committed display pointers. A pre-journal artifact manifest seeds chapters that have not yet acquired a journal. Queue and pending-request records have their own persistence owners.

Candidate output must remain separate from committed display output until artifact commit succeeds. A stale or failed candidate must not replace the last committed page result.

## Concurrency and changes

Workflow admission does not replace page leases or write fences. Cancellation does not prove a native call has exited; preserve the native-lane and quarantine rules. Keep dependencies pointed from presentation and workflow through pipeline and engines toward state, persistence, and model. Do not add package cycles, process-global state, or generic abstraction layers without a concrete boundary.

Changes to cancellation, coexistence, leases, generation fencing, durable commits, or recovery need focused tests for the affected transition and race. Run the translation unit tests for both Dev and Standard variants after structural changes.
