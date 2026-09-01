# Translation Module — Structure & Test Coverage

> Reference for the `eu.kanade.translation` package (TachiyomiAT exclusive),
> the automatic manga-translation pipeline (Detect → OCR → Translate → Inpaint
> → Render). Captures the file layout after the T909 god-file dismantle, which
> extracted `pipeline/`, `manager/`, `store/`, `artifact/` and merged batch
> state + workers into `pipeline/batch/`; root-level files are the facade hubs.
>
> 📖 See [`docs/architecture/translation-subsystem-coexistence-v3-draft.md`](./architecture/translation-subsystem-coexistence-v3-draft.md)
> for the multi-modal coexistence and concurrency specification (v2.1 was rejected by the
> T916 audit and is superseded; v3.0 is the governing draft).

---

## Package map (`app/src/main/java/eu/kanade/translation/`)

```
translation/
├─ ActiveChapterStoreRegistry.kt  Registry of live per-chapter stores
├─ ChapterResetPreflight.kt       Pre-reset validation gates
├─ ChapterTranslationStore.kt     Per-chapter JSON store of page translation states (sole live-state owner)
├─ ChapterTranslator.kt           Orchestrates staged, streaming chapter work
├─ CleanedImagePublisher.kt       Cleaned-image publication path
├─ MemoryPressurePolicy.kt        Heap-pressure classification and response policy
├─ PageTranslationKey.kt          Page identity key
├─ PostOcrStageSemantics.kt       Post-OCR stage transition semantics
├─ TranslationManager.kt          Central coordinator: jobs, auto-prefetch, cancel, lifecycle
├─ TranslationMemoryPressureForwarder.kt  Forwards memory-pressure signals to engines
├─ TranslationPendingRequestStore.kt      Durable pending-request bookkeeping
├─ TranslationPipeline.kt         Singleton pipeline: engines, decode, persist, OOM recovery
├─ TranslationQueueStore.kt       Persistent queue of pending chapter requests
├─ TranslationSession.kt          Active translation session (work kind + store handle)
├─ TranslationStageContracts.kt   Shared stage outcome / pause contracts
│
├─ artifact/                      Durable on-disk artifacts + legacy migration
│  ├─ ArtifactContracts.kt        Shared artifact identity helpers
│  ├─ ArtifactRetention.kt        Retention/cleanup policy for rendered artifacts
│  ├─ ChapterArtifactDeletion.kt  Artifact deletion plans
│  ├─ ChapterArtifactLayout.kt    On-disk layout rules
│  ├─ ChapterArtifactManifest.kt  Manifest model
│  ├─ ChapterArtifactManifestReader.kt  Manifest reading/validation
│  ├─ ChapterArtifactStore.kt     Artifact store (read/write chapter artifacts)
│  ├─ ChapterDocumentIo.kt        Chapter document IO interface
│  ├─ CleanedImageProbe.kt        Cleaned-image presence probes
│  ├─ LegacyArtifactMigration.kt  Legacy artifact migration
│  ├─ LegacyArtifactRescue.kt     Legacy artifact rescue paths
│  ├─ LegacyChapterMigrationSource.kt   Legacy chapter migration source
│  ├─ LegacyFlatFileDecoder.kt    Legacy flat-file chapter decoder
│  ├─ ModelIdentityCache.kt       Model identity cache
│  └─ StageFingerprints.kt        Stage fingerprint computation
│
├─ data/
│  ├─ TranslationFont.kt          Render font enum
│  └─ TranslationProvider.kt      File/cache path resolution for translated images
│
├─ detection/                     ONNX detection stage
│  ├─ OnnxPageTextDetector.kt     ONNX text-region detector (delegates dedupe to BoxGeometry)
│  ├─ OnnxPanelDetector.kt        ONNX panel detector
│  └─ PanelAssignment.kt          Assigns text regions to panels
│
├─ inpainting/                    Inpaint stage — root exposes the orchestrating surface
│  ├─ InpaintingMode.kt           QUALITY / FAST enum (shared config vocabulary)
│  ├─ PageInpaintingEngine.kt     Inpaint entry (used by recognition engines); delegates to aot/ + Planner
│  ├─ PageInpaintingPlanner.kt    ★ PURE erase-mask planner: computeMask (at OCR time) + build (persisted-aware)
│  ├─ aot/                        AOT neural-inpainting subsystem (incl. its NNAPI EP gates)
│  │  ├─ AOTInpainting.kt         AOT hub: orchestrates session, fallback, pixel ops, guards
│  │  ├─ AotBoxGeometry.kt        AOT box geometry helpers
│  │  ├─ AotFallbackCoordinator.kt  AOT fallback orchestration
│  │  ├─ AotModelContract.kt      AOT model I/O contract
│  │  ├─ AotOutputGuard.kt        ★ PURE neural-output sanity guard (mid-gray-fill detection)
│  │  ├─ AotPadPath.kt            AOT padding-path helpers
│  │  ├─ AotPixelOps.kt           ★ PURE AOT pixel operations
│  │  ├─ AotReportBubbleFill.kt   AOT report-style bubble fill decision
│  │  ├─ AotSessionLifecycle.kt   AOT session lifecycle
│  │  ├─ NnapiCapabilityGate.kt   NNAPI capability gating (AOT-only)
│  │  ├─ NnapiHealthMonitor.kt    NNAPI health monitoring (AOT-only)
│  │  ├─ PushPullGradient.kt      ★ PURE push-pull gradient ops
│  │  └─ StrictNnapiFallback.kt   Strict NNAPI fallback policy (AOT-only)
│  └─ bubble/                     Classic bubble-cleaning cluster
│     ├─ BoundaryAwarePipeline.kt ★ PURE containment flood + tier classification (FLAT/TEXTURED/COLOR)
│     ├─ BubbleCleanerMath.kt     ★ PURE bubble-cleaner math helpers
│     ├─ BubbleMaskBuilder.kt     ★ PURE mask/morphology helpers (BubbleMaskBuilderTest)
│     ├─ FastMarchingMethod.kt    ★ PURE Telea Fast Marching Method inpaint + adaptive threshold
│     └─ SmartBubbleTextCleaner.kt  Bubble cleaning core; delegates masks to BubbleMaskBuilder
│
├─ manager/                       Manager-side extracted coordinators (consumed by TranslationManager)
│  ├─ BatchProgressProjector.kt   Projects batch tracker state to UI progress
│  ├─ ChapterDataResetController.kt     Chapter data reset flows
│  ├─ CleanedImageLifecycleController.kt Cleaned-image lifecycle decisions
│  ├─ DurableChapterStatusResolver.kt   Durable chapter status resolution
│  ├─ ReaderTeardownCoordinator.kt      Reader teardown sequencing
│  └─ TranslationRequestCoordinator.kt  Request admission/dedup coordination
│
├─ model/
│  ├─ ChapterQueueConflictDetection.kt  ★ PURE queue conflict detection
│  ├─ ChapterQueuePreflight.kt    Queue preflight checks
│  ├─ Detection.kt                Bounding-box + label + score model
│  ├─ PageDisplayProjection.kt    ★ PURE reader display projection
│  ├─ PageDisplayState.kt         Page display state
│  ├─ PageTranslation.kt          Page state + TranslationBlock + StageStatus + InpaintMaskBox (durable erase mask)
│  ├─ PageTranslationHelper.kt    ★ PURE overlapping-block merge (mergeOverlap)
│  ├─ PageTranslationOwnership.kt Page-state ownership guards
│  ├─ PageTranslationState.kt     ★ PURE lifecycle/status predicates + cancelInFlightStages + hasCurrentInpaintMask
│  ├─ PageView.kt                 Reader-side view model
│  ├─ PageWorkPlan.kt             Per-page work plan model
│  ├─ PageWorkPlanner.kt          ★ PURE page work planning
│  ├─ Translation.kt              Per-chapter Translation aggregate
│  ├─ TranslationProgress.kt      ★ PURE batch progress aggregation
│  ├─ TranslationProgressSnapshot.kt    Immutable progress snapshots
│  ├─ TranslationRequestState.kt  Request state model
│  ├─ TranslationSettingsSummary.kt ★ PURE read-only config snapshot for the pre-translation confirm popup
│  └─ TranslationUiProjection.kt  ★ PURE UI projections
│
├─ ocr/
│  ├─ DbPostProcess.kt            ★ PURE differentiable-binarization post-process
│  ├─ MangaOcrEngine.kt           Japanese manga OCR (ONNX); see Memory contract #2
│  │                              (decoder position bound pos < 128) + ocr-engine-notes.md
│  ├─ MlKitOcrPreprocessor.kt     ML Kit crop preprocessor (scale/pad/contrast)
│  ├─ MlKitRoiOcrEngine.kt        ML Kit ROI OCR
│  ├─ OcrDiagnostics.kt           ★ PURE OCR diagnostics helpers
│  ├─ OcrModelCatalog.kt          ★ PURE model/language catalog (entries, coerce, defaults)
│  ├─ OcrTextFilter.kt            ★ PURE OCR text filtering
│  ├─ PaddleCtcDecoder.kt         ★ PURE CTC decode (decode, argmaxIndices)
│  ├─ PaddleOcrV6DetEngine.kt     PaddleOCR v6 detection engine
│  ├─ PaddleOcrV6SmallEngine.kt   PaddleOCR v6 small engine (HF `inference.onnx`)
│  ├─ RoiOcrEngine.kt             ROI OCR interface + reclaimPooledMemory contract
│  ├─ TextRecognizer.kt           OCR engine selector facade
│  └─ TextRecognizerLanguage.kt   Source-language enum
│
├─ pipeline/                      Single-page pipeline phases extracted from TranslationPipeline
│  ├─ CleanedPublication.kt       Cleaned-image publication step
│  ├─ EngineLane.kt               Engine construction/signature lane
│  ├─ MemoryGovernance.kt         Decode/inpaint memory governance
│  ├─ PageDecode.kt               Page bitmap decode
│  ├─ PageStoreWriter.kt          Page-state store writes
│  ├─ SinglePageHttpRenderPhase.kt  Single-page HTTP render phase
│  ├─ SinglePageOnnxPhase.kt      Single-page ONNX phase
│  └─ batch/                      Batch translation: state/progress + execution workers
│     ├─ BatchChapterTranslator.kt      Batch translation entry (owns SequentialBatchCoordinator)
│     ├─ BatchContextFrontier.kt        ★ PURE context frontier windows
│     ├─ BatchCoordinatorInterfaces.kt  Lane/worker interfaces + typed outcomes
│     ├─ BatchLaneWorkers.kt            Native/provider lane workers
│     ├─ BatchOomPolicy.kt              Batch OOM policy
│     ├─ BatchProgressReconciler.kt     ★ PURE progress reconciliation
│     ├─ BatchRenderJoin.kt             Per-page render join gates
│     ├─ BatchResumeGateDecider.kt      ★ PURE resume gate decisions
│     ├─ BatchResumePlanner.kt          Batch resume planning
│     ├─ BatchTranslationDiagnostics.kt ★ PURE batch diagnostics
│     ├─ BatchWriteGate.kt              Store write gating
│     ├─ HeldBitmapRegistry.kt          Held-bitmap admission registry
│     ├─ SequentialBatchCoordinator.kt  Serialized native + provider lanes, chunk barrier
│     ├─ TranslationBatchEvent.kt       Batch event model
│     ├─ TranslationBatchProgressTracker.kt   Live batch progress tracker
│     └─ TranslationBatchTrackerRegistry.kt   Tracker registry
│
├─ recognition/
│  ├─ BoxGeometry.kt              ★ PURE bbox IoU/area/geometric-dedupe (shared by detector + OCR)
│  ├─ MlKitFullPageRecognitionEngine.kt
│  ├─ OcrBlockDeduplication.kt    ★ PURE OCR block dedupe helpers
│  ├─ PageRecognitionEngine.kt    Recognition engine interface + reclaimPooledMemory
│  ├─ ReadingOrderSorter.kt       ★ PURE reading-order sort
│  ├─ RoiPageRecognitionEngine.kt ROI recognition (delegates dedupe to BoxGeometry;
│  │                              reclaims sub-engine native caches on OOM)
│  └─ VerticalLineOcr.kt          ★ PURE vertical-line OCR helpers
│
├─ rendering/
│  ├─ PageTextRenderer.kt         Draws translated text onto cleaned pages (no source-text fallback)
│  ├─ RenderColorEstimator.kt     ★ PURE colorPolicy/snapGray + Bitmap-bound estimate()
│  └─ TextLayoutPlanner.kt        ★ PURE neighbour-aware text layout solver (TextLayoutPlannerTest)
│
├─ runtime/onnx/
│  ├─ CheckNnapi.java             NNAPI availability probe (build-time Java probe)
│  ├─ CheckXnnpack.java           XNNPACK availability probe
│  ├─ DeviceCapability.kt         ONNX EP capability detection
│  ├─ HardwareDiscoveryEngine.kt  EP/hardware discovery
│  ├─ OnnxModelStore.kt           ONNX model asset loading/caching
│  ├─ OnnxRuntimeProvider.kt      OrtSystem singleton
│  ├─ QnnDiagnostics.kt           QNN EP diagnostics
│  └─ QnnProbeModel.kt            QNN probe model
│
├─ scheduling/
│  ├─ AutoWindowState.kt          Auto-prefetch window state
│  ├─ NativeRunQuarantine.kt      Native-run quarantine (invalidates late results)
│  ├─ PreparedPageBoundary.kt     Prepared-page handoff boundary
│  ├─ RollingAutoCoordinator.kt   Rolling auto-prefetch coordination
│  ├─ TranslationExecutor.kt      Per-page work interface (+ TranslationStageListener, PreparedPage)
│  ├─ TranslationLifecyclePolicy.kt ★ PURE scheduling classification (shouldSchedule/classify)
│  ├─ TranslationScheduler.kt     Job lifecycle: dedup, cancel, auto-prefetch windows
│  ├─ TranslationStoreResolver.kt Resolves live store for a chapter id
│  └─ TranslationStreamRegistry.kt ★ PURE per-page stream factory registry
│
├─ segmentation/                  ONNX bubble segmentation
│  ├─ BubbleMaskRle.kt            ★ PURE RLE mask coding
│  ├─ BubbleSegmentationDecoder.kt      Segmentation output decoding
│  ├─ MaskGeometry.kt             ★ PURE mask geometry helpers
│  └─ OnnxBubbleSegmenter.kt      ONNX bubble segmenter engine
│
├─ store/                         Store-side extracted collaborators (consumed by ChapterTranslationStore)
│  ├─ ChapterGlossaryStore.kt     Chapter glossary persistence
│  ├─ PageStageLeaseTable.kt      Page-stage lease table
│  ├─ StorePersistenceScheduler.kt      Store persistence scheduling
│  └─ StoreStatusProjector.kt     Store status projection
│
├─ translator/                    Translation stage — root is the contract + engine-selection surface
│  ├─ AiTranslatorKind.kt         AI translator enum (Gemini/DeepSeek/OpenRouter/LM Studio)
│  ├─ ProviderRequestGovernor.kt  Provider-request admission governance (single lane, retry budgets)
│  ├─ StandardTranslatorKind.kt   Standard translator enum (ML Kit/Google/DeepL)
│  ├─ TextTranslator.kt           Translator interface
│  ├─ TextTranslatorLanguage.kt   Target-language enum
│  ├─ TranslationBlockValidation.kt ★ PURE post-translate validation (blank/source-equal → PARTIAL/FAILED; all→READY)
│  ├─ TranslationEngineBuilder.kt Resolves active translator from preferences
│  ├─ TranslatorComputeClass.kt   LOCAL_COMPUTE / REMOTE_IO classification
│  ├─ contextual/                 Contextual chunking machinery (the Pass 1/Pass 2 AI-request pipeline)
│  │  ├─ BatchTranslationProtocol.kt    Batch prompt/response protocol
│  │  ├─ ChapterGlossaryBuilder.kt      ★ PURE glossary building
│  │  ├─ ContextualRequestBuilder.kt    Contextual (chunked) request building
│  │  ├─ ContextualResponseParser.kt    Contextual response parsing (request-local IDs)
│  │  ├─ ContextualTranslationBatch.kt  Contextual batch model
│  │  ├─ StableBlockIds.kt              ★ PURE stable block-ID derivation
│  │  ├─ StreamingChunkPlanner.kt       ★ PURE streaming chunk planning (token budget, reading order)
│  │  ├─ TranslationBlockFilters.kt     ★ PURE watermark (RTMTH) block removal
│  │  ├─ TranslationContextChunkPlanner.kt ★ PURE contextual chunk planning (Pass 2 revision windows)
│  │  ├─ TranslationPrompts.kt          Shared `bN|Text|[STATUS]` prompt and response parser
│  │  └─ TranslationResponseFaithfulness.kt ★ PURE response faithfulness checks
│  ├─ providers/                  Provider adapters (HTTP/SDK transports behind TextTranslator)
│  │  ├─ AiModelFetcher.kt        ★ PURE parseOpenAiModels/parseGeminiModels/normalizeBaseUrl
│  │  ├─ AiTranslator.kt          Abstract AI-adapter base (OpenAI-shape HTTP + Gemini SDK)
│  │  ├─ BaseTranslator.kt        Translator base class
│  │  ├─ DeepLApi.kt              DeepL endpoint/auth helper (Free vs Pro host from `:fx` key suffix)
│  │  ├─ DeepLTranslator.kt       DeepL adapter
│  │  ├─ DeepSeekTranslator.kt    DeepSeek adapter (pipe-delimited ID/status protocol)
│  │  ├─ GeminiTranslator.kt      Gemini adapter
│  │  ├─ GoogleTranslator.kt      Google Translate adapter
│  │  ├─ LmStudioTranslator.kt    LM Studio adapter (pipe-delimited ID/status protocol)
│  │  ├─ MLKitTranslator.kt       On-device ML Kit translator
│  │  ├─ NumberedLineResponseParser.kt ★ PURE legacy `[index] text` parser; retained for its standalone contract, not the active AI-provider protocol
│  │  ├─ OcrArtifactSanitizer.kt  ★ PURE OCR misread (N°/№/Ｎ０) stripper
│  │  ├─ OpenAiCompatibleTranslator.kt  Shared OpenAI-shape chat adapter base
│  │  └─ OpenRouterTranslator.kt  OpenRouter adapter
│  └─ retry/                      Provider failure + retry classification
│     ├─ AiTranslationRetryController.kt  AI retry controller (chunk outcomes)
│     ├─ AiTranslationRetryPlanner.kt     ★ PURE AI retry planning
│     ├─ ProviderFailureClassification.kt ★ PURE provider failure classification (typed pause vs retry)
│     └─ TranslationRetry.kt              ★ PURE retry classification + retry budget primitives
│
├─ util/
│  ├─ ChapterPages.kt             Shared chapter page listing helper
│  ├─ ModelDeployment.kt          Model deployment metadata helpers
│  ├─ ResumeOrdering.kt           ★ PURE forward-first-then-backfill resume ordering
│  ├─ ShortHash.kt                ★ PURE FNV-1a digest (API-key change detection)
│  ├─ TaskExtensions.kt           gms Task → coroutine bridge
│  ├─ TranslationBlockSorter.kt   ★ PURE block sorting helpers
│  ├─ TranslationMemoryBudget.kt  Heap-aware decode/inpaint gating
│  └─ TranslationSafetyPrimitives.kt    Shared concurrency safety primitives
│
└─ webtoon/                       Long-strip (webtoon) support
   ├─ WebtoonSeamStitcher.kt      Seam stitching for sliding detection
   └─ WebtoonSlidingDetector.kt   Sliding-window strip detection
```

★ = pure JVM-testable logic (no Android/Bitmap/ONNX/ML Kit dependency).

### Naming vocabulary (read this before grepping)

- **Stage** — the canonical per-page vocabulary: a durable pipeline step with a
  persisted status (`StageStatus`, `TranslationStageContracts`, stage fields on
  `PageTranslation`). Say "stage" when talking about page state.
- **BatchPhase** — the batch *event-stream* phase enum (`OCR, TRANSLATE,
  INPAINT, RENDER, DISPLAY` in `pipeline/batch/TranslationBatchEvent.kt`)
  projected into progress UI. It mirrors the stages but lives in the batch
  event model, not in durable page state.
- **Pass 1 / Pass 2** — the contextual translation passes (initial chunked
  translation, then strict automatic revision); see "Staged batch
  pre-translation" below.

---

## Deduplication done

Before the structure pass, several algorithms were copy-pasted across files;
each now has a single tested source of truth:

| What | Was duplicated in | Now lives in |
|------|-------------------|--------------|
| `bN|Text|[STATUS]` AI response parsing | all four AI translators | `TranslationPrompts` |
| OCR artifact (N°/№/Ｎ０) stripping | DeepSeekTranslator (inline) | `OcrArtifactSanitizer` |
| bbox IoU / intersection / area + geometric-dedupe | OnnxPageTextDetector, RoiPageRecognitionEngine | `BoxGeometry` (+ `DedupThresholds`) |
| Mask building / dilation / feathering | SmartBubbleTextCleaner (private) | `BubbleMaskBuilder` |
| "any stage failed" + "cancel in-flight stages" | TranslationScheduler (3× inlined) | `PageTranslationState` (`isStageFailed`, `cancelInFlightStages`) |
| FNV-1a API-key digest | TranslationPipeline (private `shortHash`) | `ShortHash` |
| Translator interface + 2 enums + builder | TextTranslator.kt (god file) | `TextTranslator` + `StandardTranslatorKind` + `AiTranslatorKind` + `TranslationEngineBuilder` |

Per-stage threshold constants stay on their owners (`DedupThresholds`) so each
dedupe stage keeps its own tuning while sharing the algorithm.

---

## Memory & cross-page state contracts

> Established after the "translation degrades over time" investigation
> (progressive blur, text leaving its box, pipeline halting). These invariants
> are load-bearing — violating any of them reintroduces that class of bug.

### 1. Pooled native buffers are NOT zeroed on acquire (`DirectBufferPool`)
`MangaOcrEngine` writes only the KV-cache positions it actually visits during a
decode; the decoder attends over the full `[4,1,4,MAX_LEN,64]` cache. The pool
hands out buffers in whatever state `FloatBuffer.clear()` left them (position
reset; backing bytes implementation-defined). **Contract: do NOT zero these
buffers.** Zeroing was tried once and corrupted OCR: zeroed key/value vectors
made the attention softmax spread uniformly across all positions, producing
degenerate repetitive output (e.g. `viletetetetotetoteritetiteterinitijanijijan`).
The consumer must overwrite every position it reads back. Correctness DOES
depend on the model's causal attention. Additionally, calling `clear()` uses
reflection to immediately invoke the cleaner on direct byte buffers, avoiding
native OS memory leaks.

### 2. MangaOcr decoder position bound (`MangaOcrEngine`)
The autoregressive decoder loop runs `decoder_step` ONNX graphlets one token at a
time. The loop MUST be bounded by `pos >= DECODER_POSITION_COUNT` (**128**), the
GPT-2 position-embedding table size (`node_embedding_1` has 128 entries; `pos==128`
overflows it → native Gather throws `idx=128 must be within [-128,127]` → chapter
ERROR → pipeline halt). This is **separate from** `MAX_LEN` (256), which is only
the KV-cache sequence dimension and is larger than the position table — bounding
by `MAX_LEN` crashes on long bubbles (128+ generated tokens). argmax stays
full-vocabulary (the token embedding does not overflow). See
`docs/ocr-engine-notes.md` "MangaOcr ONNX decoder contract" for the full graph
analysis. **Verify any change to this loop on a text-dense page on-device**, not
unit tests alone — graph internals aren't unit-testable.

### 3. Native timeout quarantine prevents unsafe re-entry
A timeout around uncancellable native OCR or inpaint **does not** release its
native admission permit. `NativeRunQuarantine` invalidates that run's generation
and rejects its late result, while keeping the native lane quarantined until the
actual call exits. New native work is not admitted during quarantine. This
prevents concurrent native calls after a timeout and makes late writes harmless;
store patches still require the current generation, page version, and block
fingerprint. `closeEngines()` also clears native in-flight bookkeeping.

### 4. OOM recovery frees native, not just Java, memory
`reclaimTranslationMemory` calls `recognitionEngine.forceReleaseNativeBuffers()`
in addition to `BitmapPool.releaseAll()` + Coil memory-cache trim + `System.gc()`.
The ONNX direct KV-cache buffers and the inpainter's retained working arrays are off-heap and
survive a Java GC; without reclaiming them the pressure that OOM'd page N
persists into page N+1 and the OOM recurs (the chronic-OOM feedback loop).
Engines expose `forceReleaseNativeBuffers()` on every sub-engine that holds
pooled native state; `MangaOcrEngine` clears its off-heap `kCachePool`,
`vCachePool`, and `inputPixelPool` buffers via `forceReleaseNativeBuffers()`.
`reclaimPooledMemory()` (the per-page cooperative release) is intentionally a
no-op for most engines because buffers are returned to pools in each `recognize`
`finally` block; only engines with cross-call retained state override it.

### 5. Neural-inpaint downgrade is visible
`AOTInpainting` logs the `canRunNeuralInpaint()` fallback to flat bubble-fill at
WARN unconditionally (not just under `translation_diagnostics`), because it is a
user-visible quality drop. After contract #4 frees native heap, the next page's
fresh snapshot lets neural inpaint recover on its own (no separate retry path).

### 6. Heap pressure must not persist blurry translations
`TranslationPipeline.decodePageBitmapForTranslation` uses
`TranslationMemoryBudget.chooseDecodeDecision()` before decoding. If the page is
a normal manga page, heap pressure can only produce a retryable low-memory
failure after reclaiming `BitmapPool`, engine native pools, Coil memory cache,
and running GC; it must not silently choose `sampleSize > 1`. Only pages that
exceed the hard source-pixel cap may persist sampled output (recorded as
`decodeSampleSize > 1`). New rendered images are saved as `.rendered.png`.

### 7. BitmapPool aspect-ratio exact keying
`BitmapPool` keys cached Bitmaps by exact dimensions (`"${width}x${height}"`) instead of area (`width * height`). Area-based keying caused aspect-ratio collisions (e.g., 200x1000 and 1000x200), where the pool returned a bitmap of the wrong orientation. This failed validation and was silently dropped to GC while allocating a new Bitmap, fragmenting heap. **Contract: always key pools by exact dimensions.**

### 8. SmartBubbleTextCleaner buffer size limits
To prevent massive text bubbles from permanently hoarding heap memory, `SmartBubbleTextCleaner` enforces `MAX_CACHED_PIXELS = 1_000_000` (4MB). Buffers exceeding this limit are allocated transiently and discarded after the clean operation rather than cached in long-lived instance fields.

### 9. Stuck RUNNING status prevention (Throwable catch)
Uncaught errors (such as `java.lang.OutOfMemoryError`, which is a `java.lang.Error` rather than a `java.lang.Exception`) must never escape the page translation pipeline without updating the store status. Catching `Throwable` in `translateSinglePage` and `translateSinglePageFromStream` and recording a `FAILED` page state (along with the error type/message) to the store prevents pages from getting stuck in a permanent `RUNNING` status. In the reader UI, a `RUNNING` status disables the "translate" icon and shows a cancel button; marking the page `FAILED` immediately restores the "translate" button to allow user manual retry.

### 10. Memory relief after every page translation
Calling `recognitionEngine.reclaimPooledMemory()` in the cleanup `finally` blocks of the single-page path ensures that off-heap direct float buffers (KV-cache pools for MangaOcr) and working arrays (for SmartBubbleTextCleaner) are freed immediately after each page translation completes or fails. (`reclaimPooledMemory()` is a no-op for MangaOcrEngine — its KV-cache buffers are returned to the pool in each `recognize` `finally` block; the stronger `forceReleaseNativeBuffers()` is reserved for OOM recovery where the pools themselves must be drained.) The single-page path is split into an ONNX phase (`translateSinglePageOnnx`, under the permit) and a permit-free HTTP+render phase (`translateSinglePageHttpRender` — see contract #18); `reclaimPooledMemory()` runs in both so it fires on every exit regardless of which phase the page reached. This maintains a flat memory footprint and prevents progressive memory pressure accumulation across large chapters (25+ pages).

Reader memory is bounded by `ReaderPageWarmWindow`, whose radius is mode-aware
(`ReaderPageWarmWindow.radiusFor(mode)`): 2 for pager modes, 4 for the fast-scrolling
webtoon/continuous-vertical modes. The reader keeps translation metadata for the whole chapter, but translated image
streams are attached only inside that warm window and reopened from disk on
demand. This keeps a 200-page chapter proportional to the visible working set,
not to total chapter length.

### 11. ONNX CPU arena and memory-pattern optimizer are DISABLED (`OnnxRuntimeProvider.createSessionOptions`)
The ORT default is `enable_cpu_mem_arena=true` + `enable_mem_pattern=true`. With
those on, every session pre-allocates an arena sized to its largest-seen tensor
workspace and holds it for the session lifetime — the ORT maintainers document
this as the single largest ORT-side native-memory consumer on Android
(microsoft/onnxruntime#11627). This app creates multiple concurrent sessions
(text detector + MangaOcr encoder/decoder_init/decoder_step + AOT inpainting),
so arenas compound into hundreds of MB of
resident native heap that GC cannot reclaim. That footprint is invisible to the
Java GC (it's native malloc) but DOES count against the device's physical RAM,
which trips the `ActivityManager.lowMemory` / `availMem < threshold` gates in
`TranslationMemoryBudget.canStartAnalyze`/`canStartInpaint` — a chapter that
should have heap headroom gets deferred as "low memory" purely because the ORT
arenas are squatting on the device's RAM. This was traced as the dominant
runaway-native-pressure source during the 200-page pre-translation OOM
investigation (June 2026).

`createSessionOptions` calls the type-safe `setCPUArenaAllocator(false)` +
`setMemoryPatternOptimization(false)` (verified present in the ORT 1.21.0
Javadoc; preferred over `addConfigEntry("session.enable_cpu_mem_arena","0")`
because a typo in the string key silently no-ops whereas the type-safe methods
fail loudly). Both calls are wrapped in `runCatching` that logs at WARN if a
future ORT version removes them — the session is still created, just with the
default (arena-on) config, so a forward ORT bump cannot brick translation.

Hardware routing goes through `HardwareDiscoveryEngine` (session-scoped latching
circuit breaker): physical arm64 Snapdragon devices probe QNN HTP once, other
devices probe NNAPI, and everything else latches CPU/XNNPACK. Probes are
**end-to-end**: they create a real strict session (CPU fallback disabled) from
the embedded 91-byte single-Relu `QnnProbeModel`, ordered over
`soc_model+htp_arch -> soc_model -> autodetect` combos. This matters because
merely registering the QNN EP on `SessionOptions` cannot fail — `libQnnHtp.so`
is loaded and `QnnDevice_create()` runs only during session creation, where a
QNN failure is non-fatal and silently assigns every node to the CPU EP (the
root cause behind the app reporting `route=QUALCOMM_QNN_HTP` at CPU speeds on
Android 16 Snapdragons; see
`Plan/active/2026-08-17-npu-acceleration-architecture/progress.md` and upstream
onnxruntime-qnn#715). Accelerator sessions are created strict as well, so a
created "qnn_htp" session truly executes on the NPU and per-model partition
failures fall back to CPU for that model alone without tripping the breaker.
`[translation_perf]` logs therefore carry a `providers(...)` block naming the
EP that actually served each engine, and debug builds dump a one-shot
`[qnn_diagnostics]` report (probe matrix, ORT providers, packaged QNN libs,
real-model strict run with context-cache timings).

Three packaging/runtime constraints that are easy to get wrong:

- The standard `com.microsoft.onnxruntime:onnxruntime-android` AAR has **no QNN
  execution provider** — `addQnn()` always throws and the probe silently latches
  CPU (skipping NNAPI on Snapdragon too). The QNN-enabled drop-in artifact is
  `com.microsoft.onnxruntime:onnxruntime-android-qnn` (same Java API, arm64-v8a
  only; it compiles neither XNNPACK nor NNAPI, which is why XNNPACK
  registration is treated as optional and the Settings picker hides NNAPI).
  See `gradle/libs.versions.toml`.
- ORT 1.27 parses QNN provider options strictly: `soc_model`/`htp_arch` are
  **integer strings** (names like `"SM8650"` throw in `std::stoi`), `htp_arch`
  accepts only `0/68/69/73/75/81` (v79/SM8750 is unparseable — PR #31638), and
  context caching is **not** a provider option: `qnn_context_cache_enable`/
  `qnn_context_cache_path` are silently ignored; caching must use the
  `ep.context_enable` + `ep.context_file_path` session config entries with a
  per-model file path. All of this lives in
  `DeviceCapability.qnnSocModel/qnnHtpArch` and
  `OnnxRuntimeProvider.buildQnnProviderOptions`.
- The fixed-512 AOT model is the official Qualcomm AI Hub AOT-GAN export. Its
  I/O contract is **[0,1]**: pixels feed as `channel/255` without pre-masking
  (the graph masks internally) and output decodes as `value*255` — encoded via
  `AotPixelOps.encodeFixedImageChannel`/`decodeFixedChannel`. The dynamic
  `aot.onnx` export keeps the older [-1,1] pre-masked convention; the two paths
  must not share math.

Per-model routing within that latch: the text detector, panel detector, bubble
segmenter, and PaddleOCR det go through `createSessionWithFallback` (CPU retry
when the strict accelerator session cannot take the graph). MangaOcr and
PaddleOCR recognition stay CPU-only. AOT inpainting prefers XNNPACK (labeled
CPU honestly when the artifact lacks it), keeps a strict-NNAPI fixed-512
session behind `NnapiCapabilityGate`/`NnapiHealthMonitor`, and runs the strict
QNN HTP fixed-512 session ahead of both when the route latched Qualcomm, with a
QNN -> XNNPACK -> push-pull fallback cascade. For debug-only QAIRT
runtime-override experiments (newer `libQnn*.so` than the AAR ships), see
`app/src/debug/jniLibs/arm64-v8a/README.md`.

The trade-off is a modest per-inference CPU cost (the arena also serves as a
free-list, so without it each inference goes through malloc/free) in exchange
for a dramatically lower resident footprint — the correct trade-off for the
6GB / 20-30% heap target device class. If a future high-RAM device class needs
the arena for speed, gate the setting on `DeviceCapability` rather than
re-enabling it globally.

### 12. ONNX input tensors MUST use direct, pooled buffers (`MangaOcrEngine`, `PaddleOcrV6*`)
The MangaOcr encoder input tensor is built from the 224×224×3 normalized pixel array.
The previous code used `FloatBuffer.wrap(pixels)` — a **heap-backed** buffer.
ORT cannot use heap memory directly for native inference, so it allocates an
internal native copy. Per ORT issue #16937 (maintainer reply: *"FloatBuffer.wrap
will copy it into the float buffer, but it's not a direct one so we need to copy
it again in ORT. I recommend making the buffer direct"*), that native copy has
its own lifecycle: `OnnxTensor.close()` releases the Java tensor wrapper, but
the native-side copy ORT made is retained by the session's internal heap and
accumulates across calls. The ORT Javadoc on `OnnxTensor.close()` confirms:
*"Closes the tensor, releasing its underlying memory (if it's not backed by an
NIO buffer)."*

This was the dominant leak in the 200-page pre-translation OOM (June 2026). An
Eclipse MAT heap dump captured at the failure moment showed a single
`MangaOcrEngine` instance retaining **445 MiB across 562 `byte[]` instances**
(~773 KiB each — ORT's per-call native copy of the 600 KiB encoder input).
`recognize()` is called once per text ROI per page, so a text-heavy chapter
makes thousands of calls; each left one native copy behind, and the cumulative
445 MiB pinned the JVM heap at 494/512 MiB so no page could decode.

**Contract: every ONNX input tensor that holds more than a scalar MUST be
backed by a direct (`ByteBuffer.allocateDirect`) buffer, pooled when the shape
is fixed.** `MangaOcrEngine.recognize` now uses `inputPixelPool` (a
`DirectBufferPool` of 600 KiB direct buffers, `maxPoolSize = 2`) acquired
inside the `try` and released in the `finally`, mirroring the existing
`kCachePool`/`vCachePool` pattern for the KV-cache buffers. The start-token
`LongBuffer.wrap(longArrayOf(2))` is exempt — it's an 8-byte scalar whose
native copy is negligible. `forceReleaseNativeBuffers()` and `close()` clear
`inputPixelPool` alongside the KV-cache pools so OOM recovery and engine
teardown release it uniformly.

The same rule applies to PaddleOCR v6. `PaddleOcrV6DetEngine` writes its fixed
736×736 detector tensor directly into a pooled direct buffer, and
`PaddleOcrV6SmallEngine` writes its variable-width recognizer tensor directly
into a max-width pooled direct buffer while exposing only the active width via
the buffer limit. Neither engine may use `FloatBuffer.wrap(...)` for ONNX inputs
on the hot per-ROI/per-text-line path.

Verify any new ONNX tensor creation against this contract — a heap-backed
buffer on a hot per-ROI path will reintroduce this exact leak class. The
diagnostic signal is a heap dump whose top retainer is `MangaOcrEngine`
(or any engine) holding hundreds of `byte[]` of roughly the input-tensor
size.

### 13. `DirectBufferPool` MUST track in-use buffers by identity, not content (`DirectBufferPool.inUseBuffers`)
The pool's `inUseBuffers` set uses `Collections.newSetFromMap(IdentityHashMap())`,
NOT `ConcurrentHashMap.newKeySet()`. This is load-bearing: `java.nio.FloatBuffer.equals()`
and `hashCode()` are **content- and position-dependent** (per the JDK contract:
*"the hash code depends upon the remaining elements"*, which depends on
position and limit). The consumer (`MangaOcrEngine.recognize`) mutates the
buffer's position and limit between acquire and release (`clear → put → flip`),
so a content-equality set loses track of the buffer — `release()`'s `remove(buffer)`
silently returns false, the buffer is stranded in the in-use set forever, and
every subsequent `acquire()` allocates a fresh `ByteBuffer.allocateDirect`.

This was the **dominant** leak in the 200-page pre-translation OOM (June 2026),
found via a second Eclipse MAT pass after the contract #12 fix alone did not
resolve it. The heap dump showed `MangaOcrEngine` retaining **440 MiB across
489 `DirectByteBuffer` instances held by 3 `DirectBufferPool`s configured with
`maxPoolSize = 2`** — i.e. 163 buffers per pool where 2 was the cap. The pre-existing
`kCachePool`/`vCachePool` (KV-cache, 1 MiB each) each leaked ~171 MiB; the
`inputPixelPool` added by contract #12 (600 KiB each) leaked ~98 MiB on top.

`IdentityHashMap` uses `System.identityHashCode` + `===`, which are stable
across position/limit mutation. The set is wrapped in `synchronizedSet` as
defense-in-depth; all mutation already happens under `bufferLock`. See
`DirectBufferPoolTest.mutating buffer position between acquire and release
does not leak` and `pool with maxPoolSize one never allocates more than one
buffer under churn` — these two tests reproduce the exact acquire→clear→put→
flip→release pattern and fail loudly against any future regression to
content-equality tracking.

The diagnostic signal is a heap dump whose dominator tree shows
`DirectBufferPool` instances holding far more than `maxPoolSize` backing
buffers (e.g. `DirectByteBuffer` count >> `maxPoolSize`). The `totalBuffers`
`AtomicInteger` is also a runtime tell — if it climbs unboundedly across
calls while `availableBuffers` stays empty, the in-use set is leaking.

**Lesson:** when pooling NIO buffers, NEVER use a content-equality collection
(`HashMap`, `ConcurrentHashMap.newKeySet`, `HashSet`) for in-use tracking.
NIO buffer equality is defined over mutable state, which makes it unsuitable
as a map key across mutations. Use identity collections (`IdentityHashMap`)
or wrap the buffer in a stable identity holder.

### 14. Inpaint mask is durable + adapters never fall back to source text (`PageInpaintingPlanner`, `TranslationBlockValidation`, `PageTextRenderer`)
Two coupled invariants fix the "mixed source + translated text on one page" and
"translated text rendered on top of un-erased source" symptoms reported together.

**(a) The inpaint erase set MUST be captured at OCR time and persisted.**
`PageInpaintingPlanner.computeMask` runs ONCE at the end of
`PageRecognitionEngine.analyze` and writes the result to
`PageTranslation.inpaintMaskBoxes` (a new SERIALIZABLE field of `InpaintMaskBox`,
NOT the `@Transient allTextDetections`). The mask is the full erase set: every
OCR block's bubble box + text box, every detector-only region (filtered out of
OCR by dedupe/suppression but still needing erase), and would include watermark
boxes if present. At inpaint time `PageInpaintingPlanner.build` PREFERS the
persisted mask and only recomputes from the live `allTextDetections` when no
persisted mask exists (the fresh single-page path). This is load-bearing because:
  - `allTextDetections` is `@Transient`, so it is lost on serialize/deserialize
    (store reopen, process death, batch resume). Deriving the mask from it at
    inpaint time on the resume path produced an erase set of ONLY the surviving
    OCR blocks, leaving detector-only + watermark regions visible.
  - Watermark blocks are removed from `blocks` by `TranslationBlockFilters`
    AFTER translate (which runs before inpaint in the batch flow), so deriving
    the mask from `blocks` at inpaint time lost the watermark boxes too.
  - `PageTranslation.CURRENT_INPAINT_REVISION` was bumped 8 → 9 so pre-fix
    chapters (no `inpaintMaskBoxes`, or a mask at the old revision) are re-OCR'd
    via the `hasCurrentInpaintMask` resume gate rather than reused with a
    partial mask.

The stage-1 resume gate now requires BOTH non-empty blocks AND a current mask
(`hasCurrentInpaintMask`) before skipping re-OCR. A textless page (empty blocks)
is always treated as having a current mask — it has nothing to erase.

**(b) A blank / source-equal translation MUST NOT be treated as success.**
The AI adapters (`DeepSeekTranslator`, `GeminiTranslator`, `OpenRouterTranslator`,
`LmStudioTranslator`) previously pre-filled `block.translation = block.text` when
the model returned blank/`NULL`/missing output, and the renderer drew that via
`block.translation.ifBlank { block.text }`. Together this made a page with some
real translations + some untranslated blocks render the OCR text in place of the
missing translations while still counting as READY. Now:
  - Adapters leave `block.translation` BLANK on missing output (no source fallback).
  - `TranslationBlockValidation.applyTo` runs after EVERY translate path (AI batch,
    non-AI batch, single-page) and classifies the page into three outcomes:
    *all blocks translated → `READY`*; *some translated, some not → `PARTIAL`*
    (rendered; the missing regions render blank on the cleaned image; retryCount
    is NOT bumped so a partial doesn't burn the page's retry budget); *none
    translated → `FAILED`* (retryCount bumped, render skipped). It rejects blank
    AND source-equal translations by default.
  - The render gates in `TranslationPipeline` admit `READY` and `PARTIAL` alike,
    so a single bad block no longer skips the whole page.
  - `PageTextRenderer.render` draws ONLY `block.translation` by default
    (`renderSourceText` defaults to `false`); a blank translation renders as
    nothing. The translate path never passes `renderSourceText = true`, so
    drawing source text on a translated page is now impossible by construction.

**(c) Chapter status reflects per-page failures.**
`ChapterTranslator.translateChapterInternal` now inspects the store after the
batch: if any page that should have produced output (not textless, not rendered)
is in a failed stage, the chapter is `ERROR`, not `TRANSLATED`. Previously the
unconditional `TRANSLATED` after `translateBatch` returned hid partial failures
behind a green checkmark.

**Diagnostics:** `PageInpaintingPlanner.InpaintingInput.source`
(`PERSISTED` vs `RECOMPUTED`) is logged by `PageInpaintingEngine` so a resumed
batch that somehow lost its mask is visible. `TranslationBlockValidation` writes
a `"Translation incomplete: X/Y blocks translated"` reason on FAILED pages and a
`"Translation partial: X/Y blocks translated"` reason on PARTIAL pages.

### 15. Strict no-fallback policy (AI output validation, `PageTextRenderer`, config resolvers, `MLKitTranslator`, `PageInpaintingEngine`, reader UI)
"No fallback" means nothing is ever silently substituted: not source text for a
blank translation (contract #14b), not positional guesses for malformed model
output, not vertical layout for a stray CJK glyph, not a default language/engine/
inpaint mode for an invalid or unavailable config, and not a stale error message
for a page that produced output or was deliberately stopped.

**(a) Active AI output is ID-mapped pipe text.** All four AI adapters share
`TranslationPrompts`: Pass 1 expects `bN|Translated Text|[OK]` or
`bN|Translated Text|[FLAG]`; Pass 2 expects `bN|Corrected Text`. The parser
ignores malformed lines instead of assigning output positionally, and the
post-translation validator leaves missing or blank blocks partial/failed.
`NumberedLineResponseParser` still has a strict standalone `[index] text`
contract and tests, but it is not the active provider protocol.

**(b) Vertical layout is majority-CJK only.** `PageTextRenderer` renders a block
vertical only when CJK characters are the MAJORITY (>50%) of its non-whitespace
text (`shouldRenderVertical` / `cjkRatio`). The old `text.any(::isCJK)` rule
flipped the whole block vertical on a single CJK glyph, so an English line with
one residual Japanese char (`(笑)`, an untranslated name) got its Latin letters
stacked. `"(笑)"` (1/3) → horizontal; `"こんにちは"` (5/5) → vertical.

**(c) Invalid/unavailable config throws, it does not silently default.** The
config resolvers now THROW `IllegalArgumentException` on an unknown stored value
instead of silently rewriting it: `TextRecognizerLanguage.fromPref` (was →
Chinese), `TextTranslatorLanguage.fromPref` (was → English),
`StandardTranslatorKind.fromPref` (was → ML Kit). `MLKitTranslator.translate`
throws `IllegalStateException` when closed/unavailable (was: silent skip leaving
all blocks blank). `PageInpaintingEngine` throws when QUALITY mode is requested
but the neural inpainter isn't initialized (was: silent downgrade to FAST), unless
the user has explicitly enabled the `translation_inpaint_quality_fallback`
preference, which admits QUALITY→FAST with a WARN log. The
pipeline's `init{}` builds engines defensively so a config error at startup
defers to the first translate attempt instead of crashing app launch; the
translate entry points' try/catch surfaces the error as a FAILED page with a
"reconfigure translation settings" message; `ChapterTranslator.queueChapter`
surfaces it as a toast. `GoogleTranslator` now pins `sl=<configured source lang>`
instead of `sl=auto` so the configured language is authoritative. (OCR model
incompatibility stays a non-throwing `coerce` because the settings UI
legitimately auto-corrects it on language switch; the runtime surfaces a true
unavailability via `createRecognitionEngine` throwing.)

**(d) The reader error UI only surfaces real failures.** `PageTranslation.shouldSurfaceError`
admits only genuine terminal `FAILED` stages with no rendered/cleaned result.
PARTIAL pages, textless-terminal pages, cancelled pages, and pages carrying an
explanatory `errorMessage` ("Translation cancelled", "Page was stranded...",
"Translation partial: X/Y") are NOT painted red — the user sees the translated
image or nothing, never a stale/misleading error. The stranded-page sweep treats
textless-terminal pages as terminal so they are never flipped to CANCELLED.

### 16. Inpainting quality — feathering active, dilation compounds, tight AOT mask (`BubbleMaskBuilder`, `SmartBubbleTextCleaner`, `AOTInpainting`, `PageTranslationHelper`)
A bundle of coupled fixes for the three reported inpainting artifacts (box
borders around bubbles, destructive over-erasure, duplicate/overlapping text).
Each invariant closes a specific defect; together they make the cleaned image
match the local artwork instead of producing visible rectangles.

**(a) Mask dilation uses a disk structuring element.** `BubbleMaskBuilder.dilateMaskDisk`
grows set pixels by a precomputed circular kernel (`dx²+dy² ≤ radius²`),
rounding rectangle corners rather than chamfering them at 45° (the reported
"corners too sharp" artifact). A disk of radius N has the same diagonal reach
as a 4-neighbourhood grower of radius N, so it does not bridge thin gaps more.
Pinned by `BubbleMaskBuilderTest`.

**(b) Feathering at the mask boundary MUST be active.** `SmartBubbleTextCleaner`
computes a `featherAlpha` map (core = 1.0, ring = box-blurred ramp 0→1) and
the fill loop gates on `alpha > 0` (not `mask != 0`). The earlier code
`continue`d on every non-mask pixel, discarding the entire feather ring; with
`alpha` always 1.0 inside the mask the fill had a hard 1px edge — the reported
"box border" artifact, worst on speech bubbles (which route through the
boundary-aware `fillContained`). The shared fill body is extracted as the pure, tested
`applyFeatheredFill`, and `buildLocalBackground` interpolates a background for
the feather ring too (otherwise the ring blend would be a no-op).

**(c) The last-resort fallback marks pixels, not a solid rectangle.**
`SmartBubbleTextCleaner.tightDifferenceMask` sets the mask only on pixels that
actually differ from the ring median, never filling the whole enclosing
bounding box. The downstream dilation (now correct per (a)) covers the
anti-aliased fringe. This stops the "too much space" destruction where the
fallback erased all background between strokes. Pinned by
`SmartBubbleTextCleanerTest`.

**(d) Background sampling for a bubble MUST stay inside the bubble.**
`buildLocalBackground` / `buildDirectionalBackground` accept an optional
`bgSourceMask` (the eroded bubble interior); when supplied, only pixels inside
that region count as background references. Without it, the directional/Gaussian
scan reached past the bubble boundary into surrounding artwork and pulled that
color (e.g. blue sky) into the bubble fill — the reported color-bleed. The
free-text path (no parent bubble) leaves `bgSourceMask` null, preserving the
whole-context behavior.

**(e) The free-text erase mask is driven by PaddleOCR-v6 line boxes, not
detector-v4 rectangles or pixel heuristics.** This is the prototype
`mask_mode = paddle_boxes` (`tools/inpaint-debug-viewer/server.py`) ported into
the app. detector-v4 still supplies the COARSE free-text regions and the parent-
bubble grouping, but the actual erase target for free text is now:

1. For every detector-v4 free-text box, crop the page with `PADDLE_CROP_PAD`
   (12 px) of context.
2. Run PaddleOCR-v6 DET on that crop at the inpaint thresholds
   (`PADDLE_THRESH` 0.18 / `PADDLE_BOX_THRESH` 0.34 — lower than the rec-path
   defaults so Paddle finds the same text for erasing that it finds for
   recognition).
3. Back-project the returned line boxes to PAGE coords (crop origin + line
   bbox, clamped) — `AOTInpainting.refineFreeTextBoxes`.
4. Build the erase mask with `BubbleMaskBuilder.buildRectMask`: each Paddle box
   padded by `MASK_PAD` (8 px), filled SOLID, dilated with a disk SE (radius 2).

This is deliberately boring and direct: Paddle box → pad → mask → inpaint. The
prior bug was caused by shrinking/over-processing masks after detection — a
pixel-heuristic over detector-v4 rectangles produced sparse glyph-only pixels
or whole detector rectangles. Per-region fallback:
when Paddle returns 0 lines for a detector text region, that region falls back
to its detector-v4 box (counted in the `[inpaint] paddle_boxes` diagnostics log
alongside detectorText / paddleLines / fallback / finalMaskBoxes). The same
Paddle-box mask is the erase target for BOTH paths: the neural (AOT/QUALITY)
path (`inpaintFreeRegions` → `buildRectMask`) and the FAST path
(`LegacyFreeTextInpainter` / `PushPullGradient`, with
`fillSolidBoxes`/Telea as a fallback on error — see contract #16j).

`cleanRegions` is the null-`paddleDet` legacy fallback (detector-v4 boxes +
pixel heuristics), so a device without the det asset still erases free text.
`fillSolidBoxes` is the Paddle-active FAST equivalent. Only FREE text is
Paddle-refined; parented bubbles use the boundary-aware `fillContained` (AOT
stays free-text-only). The det engine loads for the inpainter whenever the
asset is available, decoupled from the `translation_experimental_paddle_masking`
pref (which now gates ONLY the parented-bubble path).

**(f) Overlapping blocks MUST be geometrically deduped before render.**
`PageTranslationHelper.dedupeGeometricOverlaps` drops the lower-score block of
any pair that is geometrically the same region (`BoxGeometry.isGeometricDuplicate`
with the shared `BoxGeometry.TEXT_DEDUP_THRESHOLDS` — iou 0.62 / containment
0.86 / center 0.12 / size 0.20). It is label-, parent-, and text-agnostic, so
it closes the gaps the recognition engine's own dedupe stages leave open
(cross-label overlaps, no-parent overlaps, differing-text overlaps) — the
reported "translated text rendered on top of itself". Wired into both
`RoiPageRecognitionEngine.analyze` (after post-OCR dedupe) and
`MlKitFullPageRecognitionEngine.convertToPageTranslation` (which previously did
no dedupe at all). Drop, not merge: merging concatenates OCR text and changes
the translation unit's semantics. Pinned by `PageTranslationHelperDedupeTest`.

**(g) Feathering uses a distance field, not a box blur — both paths.**
`BubbleMaskBuilder.distanceToMask` is a two-pass chamfer (3,4) distance
transform (O(n), one transient `IntArray`) that yields, per pixel, its distance
to the nearest mask pixel. `featherAlphaField` builds alpha = 1.0 inside the
mask, ramping linearly to 0.0 over `rampWidth` px outside. This replaces the
earlier O(n · r²) box-blurred `featherAlpha`, which produced a thin 2–6 px cliff
that exposed the PaddleOCR box rectangle whenever the fill did not match the
surroundings (the reported "sharp corners"). The neural path
(`AOTInpainting.featherBlend`, ramp = `FEATHER_RAMP_PX` 12 px) and the classical
path (`SmartBubbleTextCleaner`, which delegates `featherAlpha` →
`featherAlphaField`) share the ONE transform, so bubbles and free text get an
identical smooth monotonic edge. Pinned by `BubbleMaskBuilderTest`.

**(h) The neural crop is sized so the text box is ~1/3 of the inference tensor.**
`BubbleMaskBuilder.computeNeuralCrop(boxLongSide)` returns `clamp(boxLongSide×3,
384, 512)`; `AOTInpainting.inpaintFreeRegions` derives the crop margin from it so
the box occupies ~1/3 of the ≤512 tensor and ~2/3 is real page context. This
replaces the earlier `boxLongSide×2.5` margin clamped [64,256], whose *fraction*
of the tensor varied with box size. The clamp bounds memory (a 512 long side is
the prior worst case, since the inference tensor is capped there regardless), so
`canRunNeuralInpaint` sees no higher peak → no new heap-pressure downgrades on
the 6 GB target. It also gives a coherent resolution floor (the box is never
sub-128 in the tensor view). The erase mask is unchanged — still the padded
PaddleOCR text box; only the VIEW widens. Pinned by `BubbleMaskBuilderTest`.

**(i) Uniform near-white neural output is rejected, not just mid-gray.**
`AotOutputGuard.isSuspiciousGrayFill` now treats a uniform block as suspicious
when its mean luma is either in the mid-gray band (96–160) OR ≥ `NEAR_WHITE_MIN`
(238), subject to the same low-variance / low-channel-delta uniformity test.
Previously a pure-white (luma ~255) neural output — the documented "white block"
artefact — sat above `MID_GRAY_MAX` and passed straight to the page
(`AOTInpainting.inpaint` draws it; `featherBlend` softens only edges, not the
core). Now it trips the guard and falls back to `SmartBubbleTextCleaner.cleanRegions`
(unchanged caller). Genuine faint-screentone paper has real variance and stays
accepted. Pinned by `AotOutputGuardTest`.

**(j) The flat-fill never paints with a synthetic white median.** When the
containment seed was empty, `computeContainment` returns a fallback (padded-union)
mask with `interiorMedian = 0xFFFFFFFF` — a fabricated default, not a measured
colour. `SmartBubbleTextCleaner.fillContained` now detects this
(`interiorMedianUntrustworthy = isFallbackContainment && interiorMedian ==
WHITE_ARGB`) and routes to the textured Telea branch instead, AND skips
`paintExterior` in that branch (else every neighbour would become white and
Telea would still emit white). Non-fallback containment always has a flooded
area ≥ 0.8× the erase boxes, so its median is measured and trusted. This closes
the FAST-mode white-block path (bubbles / unparented text), complementing (i)
which closes the QUALITY-mode neural white block. `collectStats` /
`sampleBackgroundStats` now carry a `sampleCount` field (`0` in the empty-mask
branches) to make the degenerate case explicit and inspectable. Pinned by
`BoundaryAwarePipelineTest`.

**Diagnostics:** each recognition engine logs how many blocks the geometric
dedupe dropped. `BoxGeometry.TEXT_DEDUP_THRESHOLDS` is the single source of
truth for text-box dedup (previously a private constant on
`RoiPageRecognitionEngine`).

### 17. Retry exhaustion counts DISTINCT attempts, and the manual re-translate button forces a reset (`PageTranslationState.recordAttemptFailure`, `attemptCount`, `prepareForcedRetry`, `ReaderViewModel.translateSinglePage`)
Two coupled invariants fix the "once inpainting fails, the page can no longer
be reprocessed or retranslated" bug (reproduced in both QUALITY and FAST modes).

**(a) `retryCount` no longer gates auto-scheduling; a new `attemptCount` does.**
A single reader-path attempt touches multiple stages (OCR → inpaint →
translate → render), and an inpaint failure naturally cascades into a render
failure. The old code bumped `retryCount` in **every** failed-stage catch
block (`PageInpaintingEngine.inpaint`, the render-block else-branch,
`persistCleanedBitmap`, batch `inpaintPage`, `markBatchTranslationFailed`,
`TranslationBlockValidation.applyTo`, the OOM/decode placeholders, …). One
transient inpaint failure therefore incremented `retryCount` **twice** in one
attempt, immediately tripping `hasExhaustedRetries` (`MAX_STAGE_RETRIES = 2`).
Because `isStageFailed` triggers persistence, that state survived a chapter
reopen / process restart, so the page was permanently blacklisted for both
auto- and manual re-translation.

The fix introduces `PageTranslation.attemptCount` (`@Transient` — NOT
serialized, so exhaustion never survives a process restart) and a single
`PageTranslationState.recordAttemptFailure()` helper that increments it **at
most once per attempt**: it is a no-op when any stage is already FAILED, so
the downstream render-block path that fires as a consequence of an inpaint
failure does not double-count. `hasExhaustedRetries` now keys on
`attemptCount >= MAX_STAGE_RETRIES` (two *distinct* failed attempts), not
`retryCount`. Every former `retryCount++` site in the failure paths routes
through `recordAttemptFailure()` instead; the `createFailedPagePlaceholder`
factory carries an `attemptCount` parameter mirroring `retryCount` so the
fresh-placeholder and merge-with-existing paths both preserve the per-attempt
count. `retryCount` is retained for diagnostics/back-compat only.

**(b) The manual per-page translate button actually resets the bookkeeping.**
`PageTranslation.kt` claimed the manual button "always retries" and is "NOT
bound by `MAX_STAGE_RETRIES`". That was **false**: `ReaderViewModel.translateSinglePage`
called `TranslationScheduler.translatePage(...)` with **no** `force` in all
three branches (default `force = false`), and `prepareForcedRetry()` — the
only code that resets `retryCount`/`attemptCount` and clears FAILED statuses —
was gated behind `if (force)` in the pipeline and called nowhere else. So a
manual retry re-ran with `force=false`, never cleared the bookkeeping, and
the page stayed locked out.

Now `translateSinglePage(page, force: Boolean? = null)` **resolves** `force`
from the page's live state: `true` when any stage is FAILED (so
`prepareForcedRetry()` runs and the page is re-admitted), `false` otherwise
(preserving the resume optimization for healthy / partially-translated
pages). The auto-prefetch path (`TranslationScheduler.requestAutoWindow`)
does NOT go through this method and keeps resume semantics. So a user can
always force another attempt on a page auto-translate has given up on, while
a transient (heap-pressure) failure — expected to recover on the next attempt
per memory contracts #4/#6/#10 — is no longer promoted to a permanent
lock-out.

**Diagnostics:** `recordAttemptFailure()` keeps `retryCount` as the raw
per-failure count (visible in the page state), while `attemptCount` is the
gating counter. The manual-translate log line now includes `force=…` so a
retry that did or did not reset the bookkeeping is visible in logcat. Pinned
by `PageTranslationStateTest` (`recordAttemptFailure charges the attempt
exactly once per attempt`, `a single inpaint failure does not exhaust
retries`, `prepareForcedRetry resets the attempt counter …`,
`attemptCount is not serialized …`) and the block-validation
`applyTo` FAILED path (attemptCount charged once) — exercised indirectly by
`NumberedLineResponseParserTest`; a dedicated `TranslationBlockValidationTest`
does not exist (documented coverage gap).

---

## Translation status semantics & delete teardown

### "Translated" requires real output (`TranslationManager.isChapterTranslated`)
A chapter counts as translated **only** when at least one page produced real
output — a rendered/displayable image (`hasRenderedResult`) OR recognized text
blocks whose OCR + translation stages are READY (`hasRecognizedTranslation`).
Both helpers live in `PageTranslationState.kt` and are the same predicates the
reader uses to treat a page as `Done` / `NeedsRender`, so the on-disk status
stays consistent with the live UI.

Previously the check was `pages.isNotEmpty()`, which meant any single
placeholder page — all-`PENDING`/`CANCELLED`, no blocks, no rendered image —
made the chapter show `TRANSLATED` forever. Those placeholders are routinely
written by the stranded-page sweep (`ReaderViewModel.sweepStrandedPageStatus`,
on every chapter open) and by failed/aborted translations, so a chapter merely
opened once read as fully translated. Requiring real content closes that
false-positive. (Downloading a chapter never creates a translation file; it
only makes the chapter list query status, so the icon "appeared on download.")

### Batch chapter status reflects per-page failures (`ChapterTranslator.translateChapterInternal`)
`translateBatch` swallows per-page failures internally — a failed page is marked
`FAILED` in the store and the batch keeps going (it never throws). So reaching
the end of the batch used to mean an unconditional `Translation.State.TRANSLATED`,
which hid partial failures (some pages failed OCR/translate/inpaint) behind a
green checkmark. Now, after the batch returns, the chapter inspects every store
page: if any page that SHOULD have produced output (not textless, not rendered)
is in a failed stage, the chapter is `ERROR` so the user sees the failure and
can retry. This pairs with `TranslationBlockValidation` (contract #14), which
turns an incomplete translation into a page-level `FAILED` — without it a
half-translated page would still count as READY and slip past this gate.

### 18. Reader single-page path separates native work from HTTP translate (`TranslationPipeline.translateSinglePage`)
The reader/single-page path is split into two phases so the ONNX engine (the bottleneck) stays busy instead of idling during the network call — the same ONNX‖HTTP overlap the batch path already had:
1. **ONNX phase** (`translateSinglePageOnnx`, under native admission with `NativeRunQuarantine`): stream resolution → decode → `processSinglePage` (detect+OCR+inpaint) → persist `.cleaned`. The decoded page bitmap is recycled **before** the permit is released (the existing `bitmap.recycle()` + `BitmapPool.releaseAll()` finally).
2. **HTTP+render phase** (`translateSinglePageHttpRender`, **permit-free**): cooperative cancel → `textTranslator.translatePage` (+ PARTIAL retry) → Canvas render → persist. It captures a local `activeTranslator = textTranslator` reference so a concurrent engine rebuild/`closeEngines()` from a config change does not race the in-flight HTTP call (the old instance may be closed mid-flight → one page fails and retries with the new instance — an accepted trade-off, no drain logic).

Native quarantine and in-flight deduplication cover the ONNX phase only. The cleaned bitmap (`pageTranslation.cleanedBitmap`) crosses the boundary alive and is recycled in the HTTP+render phase after render. Resume/already-rendered short-circuits stay inside the ONNX phase and run unchanged. Peak held cleaned bitmaps is bounded by the warm window and the fact that ONNX (serial under the permit) is the bottleneck, so in practice 1–2 cleaned bitmaps are alive at once.

### 19. OCR engine-capability routing: MangaOcr gets the ROI only (`RoiPageRecognitionEngine`, `OcrTextFilter`, `RoiOcrEngine.prefersHorizontalText`)
Engines declare their vertical-handling capability via `RoiOcrEngine.prefersHorizontalText` (default `false` = reads vertical natively, e.g. MangaOcr; `true` = horizontal-line CTC head needing split/rotate, e.g. PaddleOCR). The orchestrator must honor it:
- **Native-vertical engines (`false`)** get only the ROI crop from the page detector — a single `recognizeWithConf(crop)` read. They are **never** routed into `recognizeMultiLine`/`recognizeDetColumns`/`recognizeVerticalColumnPerChar` (the per-glyph decomposition). A blank/unusable read stays blank (the block drops) instead of being force-decomposed into garbage. Rationale: MangaOcr does its own preprocessing (`MangaOcrEngine.preprocess`) and reads vertical natively; the per-glyph path is a CTC workaround that degrades it and was the root cause of nonsense translations.
- **Horizontal-line engines (`true`)** keep the full det-split + per-glyph/rotate pipeline.
The decomposition functions retain a defensive `engine.prefersHorizontalText` gate so a native engine reaching them (it no longer can) still reads whole. `OcrTextFilter.isUsable(text, language)` additionally requires a CJK character for CJK sources (drops Latin/symbol OCR misreads like `N0`/`N°`), and `recognizeSingleLine` drops sub-`OCR_MIN_CONFIDENCE` PaddleOCR reads (MangaOcr's default conf 1.0 is exempt). No source-text fallback is ever introduced (contract #14/#15).

### Placeholder pages are not persisted (`ChapterTranslationStore.shouldPersistUpdate`)
`updatePage` persists only durable progress: a rendered result, recognized
blocks, a cleaned image, a stage failure (for retry-exhaustion bookkeeping),
or a rendered→cleared transition. Transient placeholders (`RUNNING`/`PENDING`/
`CANCELLED` with no content and no failure) stay in the in-memory `StateFlow`
only — the reader still observes them live — and never reach disk. This stops
the stranded-page sweep's `CANCELLED`+`errorMessage` heals from writing entries
that would later read as `TRANSLATED`. The explicit cancel snapshot path
(`clearTransientQueuePages`) writes via `persistLocked()` directly and bypasses
this gate, so legitimate user cancels are still recorded when needed.

### `deleteTranslation` tears down work before deleting files
`TranslationManager.deleteTranslation` cancels **all** in-flight single-page
and auto-prefetch jobs for the chapter and joins them (bounded by
`JOIN_TIMEOUT_MS`) **before** deleting the on-disk translation file and
companion images. Ordering is load-bearing: an auto job can be mid-native-call
inside `OrtSession.run()` when the user taps delete; tearing work down first
lets it unwind at its next suspension point so a subsequent translate's engine
rebuild/close cannot free a native session out from under a running inference
(a native use-after-free / SIGSEGV that exits the app). The teardown also bumps
the chapter's auto generation (so any in-flight window stops dispatching new
pages) and evicts the shared `ChapterTranslationStore` so the next translate
resolves a fresh lazy store instead of one bound to the deleted file.

`ReaderViewModel.deleteCurrentChapterTranslation` mirrors the chapter-change
cleanup: clears per-page translated fields, re-observes the live store to bind
to the fresh one, and resets the merged translation state.

### Native close()/run() serialization (`RoiPageRecognitionEngine`)
`analyze()` and `inpaint()` hold a `nativeGuard` `Mutex` across each native
`OrtSession.run()`, and `close()` `tryLock`s it before freeing native sessions.
Every `close()` call site is already guarded by the translator permit (so no
translate, hence no native run, is in flight at close time), making the lock
free in practice; the `Mutex` is defense-in-depth that converts any future
permit-guard bypass into serialized execution instead of a SIGSEGV. `close()`
uses non-suspending `tryLock` (never blocks the main thread it is called from);
if the lock is ever held, it logs, skips the native free, and leaves the
`closed` flag + rebuild gate as the backstop.

### Note: `autoTranslateAfterDownload` preference has been removed
The dead `auto_translate_after_download` preference (`TranslationPreferences`),
its settings toggle (`SettingsTranslationScreen`), and its string resource were
removed — it never had a consumer (downloading a chapter did not trigger
translation). If auto-translate-on-download is later wanted, re-add it wired to
the batch trigger (`TranslationManager.translateChapter`), not as dead surface.

---

## Pre-translation confirmation popup

The manga-screen "Translate chapter" action (`MangaScreenModel.runChapterTranslationActions`
`START` branch) is gated behind a read-only settings review popup so the user
can verify what will run before the staged batch commits. The **reader per-page
/ auto path is unaffected** — only the batch trigger is intercepted.

- Gate preference: `TranslationPreferences.translationConfirmPretranslate()`
  (default `true`). When `false`, START runs the translate directly (the
  pre-feature behavior).
- Popup: `ConfirmTranslationDialog` (`presentation/manga/components/`), an
  `AlertDialog` mirroring `DeleteChaptersDialog`. Renders chapter name + the
  `TranslationSettingsSummary` rows (Translate From, Translate To, Translator
  Engine [+ LLM Model for AI_MODEL], OCR model, [Max Output Token Count for
  AI_MODEL], Inpainting mode), a "Don't show this again" `LabeledCheckbox`
  bound to the gate preference, and an "Open settings" `TextButton` that
  pushes `SettingsScreen(SettingsScreen.Destination.Translation)`.
- Settings snapshot: `TranslationSettingsSummary` + `snapshotTranslationSummary()`
  (`translation/model/`) is a ★ pure, side-effect-free resolver. It reads the
  preference store but never writes it (language fallbacks use label lookups
  instead of the mutating `*.fromPref` helpers, and OCR coercion passes
  `persistCorrection = false`), so a read for display cannot corrupt the stored
  config. `inpaintingMode` is carried as the raw `"FAST"/"QUALITY"` value; the
  composable maps it through the resource strings.
- Re-enable: the Translation settings screen exposes a `SwitchPreference` for
  `translationConfirmPretranslate` so a user who suppressed the popup can turn
  it back on.

`MangaScreenModel` exposes `confirmChapterTranslation(item)` (the moved launch
body), `showConfirmTranslationDialog(item)` (snapshot + set dialog state),
`translationConfirmPretranslate()` (read), and `setConfirmPretranslate(show)`
(write) so the composable binds directly to the ScreenModel.

---

## Test coverage (`app/src/test/java/eu/kanade/translation/`)

All tests are **plain JVM unit tests** — JUnit 5 + Kotest assertions, no
Robolectric, no MockWebServer. They target the ★ pure logic above; Android /
Bitmap / ONNX / ML Kit code is deliberately excluded (it needs a device).

> The `domain`-module pool test (`domain/src/test/.../pools/DirectBufferPoolTest`)
> covers the off-heap KV-cache buffer pool: recycle-on-release, drop-when-full,
> capacity preservation, `clear()` empties the pool. It deliberately does NOT
> assert zeroed buffers — see contract #1 (zeroing corrupts OCR).

---

## Staged batch pre-translation

The manga-screen chapter action (`MangaScreenModel` → `TranslationManager` →
`ChapterTranslator`) runs a staged batch. The reader's per-page / auto-prefetch
path remains separate.

### Scheduling and translation lanes

`SequentialBatchCoordinator` (in `pipeline/batch/`) owns one serialized native lane, one serialized provider-request
lane, a bounded translation channel with **capacity 2**, and a per-page render
join. OCR persistence creates a detached immutable work item. For `REMOTE_IO`, it
is offered to the translation channel **before same-page inpaint**, allowing the
remote request to overlap inpaint. OCR and inpaint themselves never overlap.

If the channel is full, the native lane first completes same-page inpaint and
releases the bitmap and native resources; only then does it suspend to send the
work item. It therefore never suspends while holding either resource. ML Kit is
`LOCAL_COMPUTE` and stays serialized with native work. Gemini, OpenRouter,
DeepSeek, LM Studio, DeepL, and Google Translate are `REMOTE_IO` and may overlap
same-page inpaint. The single provider lane prevents concurrent contextual provider
requests.

Contextual chunks retain the existing token budget and flush incomplete input after
**250 ms** of inactivity. Requests return structured, request-local result IDs such
as `p0_b3`; these IDs are anchored to the request and require no persisted block-ID
migration. Pass 1 accepts only valid nonblank translations. A valid line without
`[OK]` or `[FLAG]` remains readable but is automatically flagged for revision.
Malformed, unknown, missing, blank, or duplicate IDs cannot overwrite a draft; they
remain untranslated and follow partial/retry handling.

### Strict automatic revision (Pass 2)

Pass 2 begins after the complete Pass-1 translation barrier, without waiting for
inpaint/render. Final completion waits for revision, inpaint, rendering,
persistence, and reconciliation. The contextual chunk planners (`StreamingChunkPlanner`,
`TranslationContextChunkPlanner`, driven by `ContextualRequestBuilder`) preserve reading
order, emit at most 20 target IDs, respect the token budget without truncation, and include
the chapter glossary plus nearby source/draft dialogue as context. Providers output
corrections for target IDs only.

A merge is atomic and re-checks batch generation, page version, block fingerprint,
current draft, `needsRevision`, and `userEditedAt`. Only a unique, valid, nonblank
correction that passes those preconditions is applied, clears the revision flag, and
counts as completed. Missing, blank, malformed, duplicate, stale, edited, or
rejected results preserve the draft and remain flagged/count as failed.
`TranslationBlockValidation` runs again after every merge; it derives `READY` or
`PARTIAL` from the actual blocks and Pass 2 never force-marks a page `READY`.

### State ownership, safe publication, and reader readiness

`ChapterTranslationStore` is the sole owner of live page/block state. It stores and
emits deep detached snapshots, and workers submit atomic patches rather than
mutating shared page instances. Patch preconditions use the current run generation,
monotonic page version, and block fingerprint. `NativeRunQuarantine` invalidates
late results and blocks new native admissions until the timed-out call really exits.

Cleaned images are published as versioned **`.jpg`** files: write a temporary file,
verify it, atomically commit its filename through the store patch, then delete the
old file only after the commit is accepted. An interrupted or rejected publication
can leave an orphan to clean up, but never a store reference to a broken file.

For text-bearing pages, the reader retains the original image until all three are
ready: a cleaned file, valid translation, and a renderable overlay. A translation
failure therefore never exposes a cleaned-only page. Truly textless pages are
marked downstream `SKIPPED`, retain the original image, and are terminal success.
`PageView` includes an overlay-content fingerprint so text-only Pass-2 changes
refresh the overlay without decoding the image again.

### Memory model

The batch re-decodes source images as required and releases native admission and
page bitmaps at stage boundaries. Cleaned images are persisted rather than retained
as a chapter-wide map, but current scheduling and memory budgeting may support up
to **four cleaned bitmaps / 48 MiB**; this is a cap, not an at-most-one-bitmap
claim. Native OCR/inpaint serialization and backpressure release-before-send keep
that bounded while remote translation overlaps I/O wait time.

### Progress, outcomes, and lifecycle

Typed `TranslationBatchEvent` inputs feed a pure serialized reducer. The pipeline
and store own page stage state; `TranslationBatchProgressTracker` is only the
progress projection. Concurrent work is represented by an `activeStages` set,
rather than a misleading single active stage. Per-stage counts expose succeeded,
failed, skipped, processed, and total; processed work is `succeeded + failed +
skipped`, so a terminal failure advances its fraction. Revision progress is
`(completed + failed) / total` and additionally exposes skipped and user-edited
counts.

Reconciliation walks authoritative ordered page keys. Missing, cancelled, or
incomplete expected pages become stranded failures before the terminal snapshot;
unexpected keys are reported but do not inflate expected totals. Terminal events
bypass UI throttling. Live trackers are disposed immediately on normal completion
or cancellation, while a chapter-keyed, access-ordered LRU retains only detached
terminal snapshots (maximum **20**) for late observers.

`READY_WITH_WARNINGS` means the chapter remains readable with partial drafts or
unresolved revisions; hard OCR, inpaint, or render failures remain `ERROR`. An
adjacent, atomically published `*.summary.json` sidecar records format version,
expected page count, terminal outcome, unresolved revision count, and update time.
Existing page JSON is unchanged. Legacy page files with no readable summary remain
readable but are only partially available until a full batch writes a summary.

Active store selection is keyed by chapter ID, preventing a background chapter from
contaminating the open reader. Application trim-memory callbacks are forwarded to
translation management even without a reader. Chapter glossary persistence is
debounced with store writes and flushed on completion, cancellation, or close.

### Resume-aware ordering (`ResumeOrdering`)

Pages are ordered **forward-first from the resume page, then backfill**: a user
mid-chapter (resume page 50 of 200) gets pages 50–199 translated first, then 1–49.
`Chapter.lastPageRead` seeds the split. The helper is pure and unit-tested
(`ResumeOrderingTest`).

### Test coverage for the staged pipeline

The batch coordinator tests cover native serialization, `REMOTE_IO` overlap, ML Kit
serialization, capacity-2 backpressure, and the Pass-1/Pass-2 barrier. Contextual
parser and revision tests cover structured IDs, validation, budgets, duplicate or
stale results, edit-wins preconditions, and `PARTIAL` preservation. Store, reducer,
summary, lifecycle, and registry tests cover detached ownership, processed progress,
warning outcomes, atomic summary publication, terminal delivery, chapter isolation,
tracker disposal/LRU, memory-pressure forwarding, and glossary flush behavior.

---

| Test | Guards |
|------|--------|
| `translator/contextual/TranslationPromptsTest` | ID-mapped source lines, pipe-delimited Pass 1/Pass 2 prompt contracts, status parsing, language guidance, and rolling/glossary context |
| `translator/providers/NumberedLineResponseParserTest` | Retained legacy `[index] text` parser contract; not used by current AI adapters |
| `translator/providers/OcrArtifactSanitizerTest` | N°/Nº/№/Ｎ０/N⁰ strip (before-punct / inline / leading / end-of-string), space collapse, glued-word limitation |
| `translator/contextual/TranslationBlockFiltersTest` | RTMTH watermark removal (case-insensitive, multi-page, embedded) |
| `translator/providers/AiModelFetcherParseTest` | OpenAI `data[].id` + Gemini model filtering/prefix-strip, kotlinx Json, JSON-null id guard |
| `ocr/PaddleCtcDecoderTest` | CTC blank-collapse, space class, argmax |
| `ocr/MangaOcrDecoderGuardTest` | (REMOVED — the guard helpers it tested were reverted; see Memory contract #2. Do not reintroduce without an on-device regression test.) |
| `ocr/OcrModelCatalogTest` | entries/coerce/defaultFor/isCompatible/labelsFor |
| `recognition/BoxGeometryTest` | IoU/area/intersection, degenerate boxes, dedupe thresholds (iou/containment/center+size paths) |
| `model/PageTranslationStateTest` | lifecycle, retry exhaustion (attemptCount-based, not retryCount), cancelled-page rescheduling, render-quality trust, forced retry reset (attemptCount + retryCount), shouldSurfaceError (FAILED surfaces; PARTIAL/Cancelled/Textless/rendered suppress), hasRecognizedTranslation admits PARTIAL, recordAttemptFailure idempotency (inpaint→render cascade = one attempt), attemptCount not serialized |
| **(no test)** | `isChapterTranslated` (`TranslationManager`) content predicate is currently UNTESTED — the former `ChapterTranslatedPredicateTest` was deleted without a successor. Known coverage gap. |
| `model/TranslationProgressTest` | batch (done,total): rendered/textless/retry-exhausted count as done; pending/running don't; empty → (0,0) |
| `model/TranslationSettingsSummaryTest` | confirm-popup snapshot: STANDARD (no model/tokens rows) vs AI_MODEL (engine+model+tokens); MLKIT/GOOGLE/Gemini/OpenRouter/DeepSeek/LM Studio labels; blank model/tokens → null; unknown source/target language fallback without mutating store; Japanese OCR coercion is read-only; inpainting raw passthrough |
| `util/ResumeOrderingTest` | forward-first-then-backfill ordering; resume mid/start/end/last; empty; no aliasing |
| `ChapterTranslationStorePersistenceTest` (test root) | `shouldPersistUpdate`: placeholders (CANCELLED+error, pending, running) not persisted; rendered/cleaned/blocks/failed/transition ARE persisted |
| `model/PageTranslationHelperTest` | overlapping-block merge, orientation guard, transitive merge |
| `model/PageTranslationHelperDedupeTest` | geometric dedupe: overlapping different-text/identical/cross-label/nested/touching-bubbles; preserves reading order; no mutation; degenerate-box kept |
| `rendering/RenderColorEstimatorTest` | dark/light colorPolicy, gray-snap (saturated preserved) |
| `rendering/PageTextRendererDirectionTest` | vertical-vs-horizontal majority-CJK rule: pure CJK vertical, pure Latin horizontal, `(笑)` (1/3) horizontal, `あいうえお day` (5/8) vertical, 50/50 → horizontal, whitespace ignored, blank → horizontal |
| `inpainting/bubble/SmartBubbleTextCleanerTest` | local-background fill (gray-rectangle regression guard); tightDifferenceMask per-pixel (no solid rectangle); applyFeatheredFill ring-blend + ramp; buildLocalBackground bgSourceMask (color-bleed guard) |
| `inpainting/bubble/BubbleMaskBuilderTest` | andMasks/maskCoverage + dilateMaskDisk circle/rounding + removeEdgeTouchingComponents 2px margin + featherAlpha + buildRectMask (paddle_boxes: solid padded rect, clamp, union, empty, skip zero-area, disk-dilate growth) + `FastMarchingMethod.inpaintTelea` (Telea FMM: no-hole, gradient-fill, Dirichlet boundary) |
| `inpainting/PageInpaintingPlannerTest` | computeMask captures bubble+text+detector-only; build prefers persisted mask (PERSISTED) over lost allTextDetections on resume; build recomputes (RECOMPUTED) when no persisted mask; detector-only dedup vs OCR boxes |
| `model/InpaintMaskSerializationTest` | inpaintMaskBoxes round-trips through JSON; InpaintMaskBox.toIntArray; hasCurrentInpaintMask (current / pre-fix-empty / textless) |
| **(no dedicated test)** | `TranslationBlockValidation` has no direct test; its applyTo FAILED path is exercised indirectly via `translator/providers/NumberedLineResponseParserTest`. Known coverage gap. |
| `translator/StrictConfigFromPrefTest` | strict no-fallback config: TextRecognizerLanguage/TextTranslatorLanguage `fromPref` throw on unknown value (was → Chinese/English); StandardTranslatorKind.fromPref throw branch is unreachable (closed enum) and documented |
| `scheduling/TranslationStreamRegistryTest` | per-page/chapter/all/window stream registry eviction semantics |
| **(no test)** | `TranslationLifecyclePolicy` (scheduling) is currently UNTESTED — the former `TranslationLifecyclePolicyTest` no longer exists. Known coverage gap. |
| `util/ShortHashTest` | FNV-1a digest: empty input, equality, determinism, hex output |
| `util/TranslationMemoryBudgetTest` | full-quality vs heap-constrained vs source-size-limited decode decisions |
| `tachiyomi/ui/reader/ReaderPageWarmWindowTest` | current +/-2 warm-window boundaries for long chapters |

Run: `.\gradlew.bat :app:testStandardDebugUnitTest`

---

## Remaining debt — prioritized map for future work

Each item is grounded in a full read of the current code. Difficulty and payoff
noted so the next pass can pick the highest-value, lowest-risk item first.

### 1. ~~`TranslationPipeline.kt` — partial split~~ — DONE (T909, 2026-08)

The dismantle extracted the prescribed responsibilities into
`pipeline/` (`PageDecode`, `MemoryGovernance`, `PageStoreWriter`, `CleanedPublication`,
`EngineLane`, single-page phases), `manager/`, and `store/`, and merged batch
execution into `pipeline/batch/`. `TranslationPipeline.kt` is now ~1,050 lines
(was ~5,400). The three-way shared engine state now lives behind `EngineLane`.

**Current top debt (supersedes the old item):**
- `pipeline/` + `pipeline/batch/` (~5,900 lines) carry the suite's largest
  untested region — concurrency seams (lane workers, render join, resume gates)
  verified today only via `SequentialBatchCoordinator`-adjacent tests and the
  manual on-device smoke. A test-writing task is the highest-value follow-up.
- Deferred T909 phases: store stage-merge engine extraction (HIGH risk) and the
  AOT inpainting split (needs a corpus harness).

### 2. Translator adapter consolidation (MEDIUM effort, MEDIUM payoff)

OpenRouter / DeepSeek / LM Studio share the same `choices[0].message.content`
→ parse flow with near-identical OkHttp setup, 60s timeouts, `close()` body
(evict pool + shutdown dispatcher), and `response.body` shape-checking. Gemini
is the odd one (Google SDK, not raw HTTP). A future `OpenAiChatTranslator` base
could fold the three OpenAI-shape adapters together; all four AI providers share
the pipe-delimited `TranslationPrompts` contract. `OcrArtifactSanitizer` is a
defensive compatibility step for model output, not a separate provider protocol.

### 3. Pre-existing lint debt (LOW effort, LOW payoff, mechanical)

`spotlessKotlinApply` reports ~12 `max-line-length` / property-naming lints in
files not touched by the structure pass: long AI prompt strings in
`GeminiTranslator`/`OpenRouterTranslator`/`GoogleTranslator`, comment lines in
`MangaOcrEngine`/`TranslationManager`/`TranslationPipeline`, and
`AOTInpainting` property naming. None break compilation; all are one-line wraps.
A dedicated `spotlessApply` + wrap pass would clear them, but wrapping the
multi-line prompt strings risks subtle whitespace changes in the LLM prompts —
verify translation output is byte-identical before/after if you touch those.

### 4. Untested Android-bound logic (by design, LOW priority)

`RenderColorEstimator.estimate`, `MlKitOcrPreprocessor.preprocessRoi`,
`AOTInpainting`, the ONNX engines, and `PageTextRenderer` touch
`android.graphics.Bitmap`/ONNX/ML Kit and are correctly excluded from unit
tests. The project deliberately avoids Robolectric. Prefer the pattern already
established (`colorPolicy`, `snapGray`, `BoxGeometry`, `BubbleMaskBuilder`):
keep extracting pure decision functions out of Bitmap-coupled classes so the
*logic* is testable even when the *I/O* isn't.

### Out of scope — intentionally left cohesive

- **`TranslationScheduler.kt`** (~640 lines): one coherent job-lifecycle
  responsibility. Its internal duplication was DRY'd this pass
  (`cancelInFlightStages`, `isStageFailed`); forcing a file split would create
  awkward coupling between pieces that all share the same ConcurrentHashMaps.
- **`ChapterTranslator.kt`** (~510 lines) / **`TranslationManager.kt`** (~490):
  cohesive orchestrators. No clean extraction seam worth the churn.
