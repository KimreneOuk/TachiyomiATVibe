# Translation Module — Structure & Test Coverage

> Reference for the `eu.kanade.translation` package (TachiyomiAT exclusive),
> the automatic manga-translation pipeline (Detect → OCR → Translate → Inpaint
> → Render). Captures the file layout after the readability/structure pass,
> including which units are unit-tested and which are Android/device-bound.

---

## Package map (`app/src/main/java/eu/kanade/translation/`)

```
translation/
├─ ChapterTranslator.kt          Orchestrates the 5-stage pipeline for one chapter
├─ ChapterTranslationStore.kt    Per-chapter JSON store of page translation states
├─ TranslationManager.kt         Central coordinator: jobs, auto-prefetch, cancel, lifecycle
├─ TranslationPipeline.kt        Singleton pipeline: engines, decode, persist, OOM recovery
├─ TranslationSession.kt         Active translation session (work kind + store handle)
│
├─ data/
│  ├─ TranslationFont.kt         Render font enum
│  └─ TranslationProvider.kt     File/cache path resolution for translated images
│
├─ detection/
│  ├─ Detection.kt               Bounding-box + label + score model
│  └─ OnnxPageTextDetector.kt    ONNX text-region detector (delegates dedupe to BoxGeometry)
│
├─ inpainting/
│  ├─ AOTInpainting.kt           AOT-based bubble inpainting
│  ├─ BubbleMaskBuilder.kt       ★ PURE mask/morphology helpers (BubbleMaskBuilderTest)
│  ├─ InpaintingMode.kt          QUALITY / FAST enum
│  └─ SmartBubbleTextCleaner.kt  Bubble cleaning core; delegates masks to BubbleMaskBuilder
│
├─ model/
│  ├─ PageTranslation.kt         Page state + TranslationBlock + StageStatus
│  ├─ PageTranslationHelper.kt   ★ PURE overlapping-block merge (mergeOverlap)
│  ├─ PageTranslationState.kt    ★ PURE lifecycle/status predicates + cancelInFlightStages
│  ├─ PageView.kt                Reader-side view model
│  └─ Translation.kt             Per-chapter Translation aggregate
│
├─ ocr/
│  ├─ MangaOcrEngine.kt          Japanese manga OCR (ONNX)
│  ├─ MlKitOcrPreprocessor.kt    ML Kit crop preprocessor (scale/pad/contrast)
│  ├─ MlKitRoiOcrEngine.kt       ML Kit ROI OCR
│  ├─ OcrModelCatalog.kt         ★ PURE model/language catalog (entries, coerce, defaults)
│  ├─ PaddleCtcDecoder.kt        ★ PURE CTC decode (decode, argmaxIndices)
│  ├─ PaddleOcrV6SmallEngine.kt  PaddleOCR v6 small engine (ONNX)
│  ├─ RoiOcrEngine.kt            ROI OCR interface + shared contract
│  ├─ TextRecognizer.kt          OCR engine selector facade
│  └─ TextRecognizerLanguage.kt  Source-language enum
│
├─ recognition/
│  ├─ BoxGeometry.kt             ★ PURE bbox IoU/area/geometric-dedupe (shared by detector + OCR)
│  ├─ MlKitFullPageRecognitionEngine.kt
│  ├─ PageRecognitionEngine.kt   Recognition engine interface
│  └─ RoiPageRecognitionEngine.kt ROI recognition (delegates dedupe to BoxGeometry)
│
├─ rendering/
│  ├─ PageTextRenderer.kt        Draws translated text onto cleaned pages
│  └─ RenderColorEstimator.kt    ★ PURE colorPolicy/snapGray + Bitmap-bound estimate()
│
├─ runtime/onnx/
│  ├─ DeviceCapability.kt        ONNX EP capability detection
│  ├─ OnnxModelStore.kt          ONNX model asset loading/caching
│  └─ OnnxRuntimeProvider.kt     OrtSystem singleton
│
├─ scheduling/
│  ├─ TranslationExecutor.kt     Per-page work interface
│  ├─ TranslationLifecyclePolicy.kt ★ PURE scheduling classification (shouldSchedule/classify)
│  ├─ TranslationScheduler.kt    Job lifecycle: dedup, cancel, auto-prefetch windows
│  ├─ TranslationStoreResolver.kt  Resolves live store for a chapter id
│  └─ TranslationStreamRegistry.kt ★ PURE per-page stream factory registry
│
├─ translator/
│  ├─ AiModelFetcher.kt          ★ PURE parseOpenAiModels/parseGeminiModels/normalizeBaseUrl
│  ├─ AiTranslatorKind.kt        AI translator enum (Gemini/DeepSeek/OpenRouter/LM Studio)
│  ├─ DeepSeekTranslator.kt      DeepSeek adapter (delegates parsing to NumberedLineResponseParser)
│  ├─ GeminiTranslator.kt        Gemini adapter
│  ├─ GoogleTranslator.kt        Google Translate adapter
│  ├─ LmStudioTranslator.kt      LM Studio adapter (delegates parsing to NumberedLineResponseParser)
│  ├─ MLKitTranslator.kt         On-device ML Kit translator
│  ├─ NumberedLineResponseParser.kt ★ PURE `[index] text` parser (DeepSeek/LM Studio)
│  ├─ OcrArtifactSanitizer.kt    ★ PURE OCR misread (N°/№/Ｎ０) stripper
│  ├─ OpenRouterTranslator.kt    OpenRouter adapter
│  ├─ StandardTranslatorKind.kt  Standard translator enum (ML Kit/Google)
│  ├─ TextTranslator.kt          Translator interface
│  ├─ TextTranslatorLanguage.kt  Target-language enum
│  ├─ TranslationBlockFilters.kt ★ PURE watermark (RTMTH) block removal
│  └─ TranslationEngineBuilder.kt Resolves active translator from preferences
│
└─ util/
   ├─ ShortHash.kt               ★ PURE FNV-1a digest (API-key change detection)
   ├─ TaskExtensions.kt          gms Task → coroutine bridge
   └─ TranslationMemoryBudget.kt Heap-aware decode/inpaint gating
```

★ = pure JVM-testable logic (no Android/Bitmap/ONNX/ML Kit dependency).

---

## Deduplication done

Before the structure pass, several algorithms were copy-pasted across files;
each now has a single tested source of truth:

| What | Was duplicated in | Now lives in |
|------|-------------------|--------------|
| `[index] text` LLM response parsing | DeepSeekTranslator, LmStudioTranslator | `NumberedLineResponseParser` |
| OCR artifact (N°/№/Ｎ０) stripping | DeepSeekTranslator (inline) | `OcrArtifactSanitizer` |
| bbox IoU / intersection / area + geometric-dedupe | OnnxPageTextDetector, RoiPageRecognitionEngine | `BoxGeometry` (+ `DedupThresholds`) |
| Mask building / dilation / feathering | SmartBubbleTextCleaner (private) | `BubbleMaskBuilder` |
| "any stage failed" + "cancel in-flight stages" | TranslationScheduler (3× inlined) | `PageTranslationState` (`isStageFailed`, `cancelInFlightStages`) |
| FNV-1a API-key digest | TranslationPipeline (private `shortHash`) | `ShortHash` |
| Translator interface + 2 enums + builder | TextTranslator.kt (god file) | `TextTranslator` + `StandardTranslatorKind` + `AiTranslatorKind` + `TranslationEngineBuilder` |

Per-stage threshold constants stay on their owners (`DedupThresholds`) so each
dedupe stage keeps its own tuning while sharing the algorithm.

---

## Test coverage (`app/src/test/java/eu/kanade/translation/`)

All tests are **plain JVM unit tests** — JUnit 5 + Kotest assertions, no
Robolectric, no MockWebServer. They target the ★ pure logic above; Android /
Bitmap / ONNX / ML Kit code is deliberately excluded (it needs a device).

| Test | Guards |
|------|--------|
| `translator/NumberedLineResponseParserTest` | `[index] text` parse, sparse/gaps, positional fallback, expectedCount cap, index>count contract, index collisions |
| `translator/OcrArtifactSanitizerTest` | N°/Nº/№/Ｎ０/N⁰ strip (before-punct / inline / leading / end-of-string), space collapse, glued-word limitation |
| `translator/TranslationBlockFiltersTest` | RTMTH watermark removal (case-insensitive, multi-page, embedded) |
| `translator/AiModelFetcherParseTest` | OpenAI `data[].id` + Gemini model filtering/prefix-strip, kotlinx Json, JSON-null id guard |
| `ocr/PaddleCtcDecoderTest` | CTC blank-collapse, space class, argmax |
| `ocr/OcrModelCatalogTest` | entries/coerce/defaultFor/isCompatible/labelsFor |
| `recognition/BoxGeometryTest` | IoU/area/intersection, degenerate boxes, dedupe thresholds (iou/containment/center+size paths) |
| `model/PageTranslationStateTest` | lifecycle, retry exhaustion, cancelled-page rescheduling |
| `model/PageTranslationHelperTest` | overlapping-block merge, orientation guard, transitive merge |
| `rendering/RenderColorEstimatorTest` | dark/light colorPolicy, gray-snap (saturated preserved) |
| `inpainting/SmartBubbleTextCleanerTest` | local-background fill (gray-rectangle regression guard) |
| `inpainting/BubbleMaskBuilderTest` | andMasks/maskCoverage/insideRoundedRect/bubbleInteriorMask/roundedAllowedMask + dilateMask quirk pin + removeEdgeTouchingComponents 2px margin + featherAlpha |
| `scheduling/TranslationStreamRegistryTest` | per-page stream registry eviction semantics |
| `scheduling/TranslationLifecyclePolicyTest` | shouldSchedule / classify / retry-exhaustion |
| `util/ShortHashTest` | FNV-1a digest: empty input, equality, determinism, hex output |

Run: `.\gradlew.bat :app:testStandardDebugUnitTest` (121 tests, all green)

---

## Remaining debt — prioritized map for future work

Each item is grounded in a full read of the current code. Difficulty and payoff
noted so the next pass can pick the highest-value, lowest-risk item first.

### 1. `TranslationPipeline.kt` — partial split (MEDIUM effort, HIGH payoff)

Still ~1.65k lines. The single-page path is cohesive, but four responsibilities
are entangled with it. A full seam map exists (generated by exhaustive read);
the key constraint is that `recognitionEngine`, `textTranslator`, and
`autoFallbackToFast` are **three-way shared mutable state**, so most extractions
must funnel through a single owner rather than becoming stateless helpers.

**Cleanest remaining extraction — `PageBitmapIO` (do this first):**
All plain (non-suspend) functions, touching only injected singletons
(`streamRegistry`, `context`) plus a nested `DecodedPage`. No host mutable state.
Move these out together:
- `peekReaderPageStream`, `createFailedPagePlaceholder`, `copyForResume`
- `loadPersistedCleanedBitmap`, `persistCleanedBitmap`, `persistRenderedBitmap`
- `decodePageBitmap`, `decodePageBitmapAtSize`, `getChapterPages`
- nested `DecodedPage` (must move with `decodePageBitmap`; promote to top-level)
- Callers: `processSinglePage`, `resumeInpaintAndRender`, `retryInpaintDownscaled`
  take `DecodedPage`, so they need the promoted type.

**Harder extractions — defer until PageBitmapIO lands:**
- `EngineConfig`: `EngineSignature`, `computeTranslatorSignature`,
  `inpaintingModeFromPref`, `createRecognitionEngine`, `closeEngines`, and the
  six engine fields. Must be a **stateful collaborator** (the orchestrator
  live-mutates `textTranslator`/`recognitionEngine` on config + lang change).
- `OomRecovery`: `consecutiveOomCount`, `autoFallbackToFast`, `enginesClosed` +
  `downgradeOnnx*`, `handleCriticalTranslationOom`, `persistPageWithOomRecovery`.
  Most entangled group — `autoFallbackToFast` is R/W from three places and
  `downgradeOnnxAfterOom` rewrites `recognitionEngine`. Needs a shared-state
  design, not a pure helper.
- Two further implicit responsibilities surfaced: **permit/watchdog infra**
  (`translatorPermit`, `inFlightPageKeys`, `withLeakProofPermit`, ~lines 105–196)
  and **active-store bookkeeping** (`currentChapterTranslation`,
  `activeStoreResolver`, `register/unregisterActiveStore`).

**Already done this pass:** extracted `ShortHash`; removed dead
`currentChapterPath` field (was write-only, never read) and the unused `store`
parameter of `registerActiveStore`.

### 2. Translator adapter consolidation (MEDIUM effort, MEDIUM payoff)

OpenRouter / DeepSeek / LM Studio share the same `choices[0].message.content`
→ parse flow with near-identical OkHttp setup, 60s timeouts, `close()` body
(evict pool + shutdown dispatcher), and `response.body` shape-checking. Gemini
is the odd one (Google SDK, not raw HTTP). A future `OpenAiChatTranslator` base
could fold the three OpenAI-shape adapters together; DeepSeek/LM Studio already
share `NumberedLineResponseParser`, OpenRouter + Gemini share the JSON-object
shape. Watch: the `sanitizeOcrArtifacts` step is currently DeepSeek-only — if the
others start emitting の-misreads, route them through `OcrArtifactSanitizer` too.

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
