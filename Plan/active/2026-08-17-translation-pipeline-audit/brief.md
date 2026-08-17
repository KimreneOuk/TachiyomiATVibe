# Translation Pipeline Architecture Audit — Task Brief

## Objective
Provide an exhaustive, detailed technical audit of the TachiyomiAT translation pipeline codebase across all three modes (Manual/Single-Page, Auto/Rolling-Window, and Batch/Pre-Translation), detailing every stage, every ML model, tensor dimensions, preprocessing/postprocessing, and analyzing input predictability/batchability for future NPU acceleration.

## Scope Boundary
- Audited subsystems:
  - Mode orchestration (`TranslationManager`, `TranslationScheduler`, `RollingAutoCoordinator`, `BatchCoordinator`, `ChapterTranslator`, `TranslationPipeline`)
  - Stage 1 Detection & Segmentation (`OnnxPageTextDetector`, `OnnxPanelDetector`, `OnnxBubbleSegmenter`)
  - Stage 2 OCR / Text Recognition (`MangaOcrEngine`, `PaddleOcrV6SmallEngine`, `PaddleOcrV6DetEngine`, `MlKitRoiOcrEngine`)
  - Stage 3 Translation (`TextTranslator`, `TranslationPrompts`, `StreamingChunkPlanner`, `ContextualTranslationBatch`, `RevisionPlanner`)
  - Stage 4 Inpainting (`PageInpaintingEngine`, `SmartBubbleTextCleaner`, `AOTInpainting`, `BoundaryAwarePipeline`)
  - Stage 5 Rendering (`PageTextRenderer`, `TextLayoutPlanner`, `RenderColorEstimator`)
  - Runtime & Memory contracts (`OnnxModelStore`, `OnnxRuntimeProvider`, `NativeRunQuarantine`, `TranslationMemoryBudget`)
- Analysis target: Static vs dynamic tensor shapes, batching viability `[B, C, H, W]`, and NPU acceleration readiness.

## Acceptance Criteria
- Detailed step-by-step description of data and execution flow for all 3 modes.
- Exact model input/output tensor specifications, pre/post-processing math, and dimensions.
- Comprehensive evaluation of static/predictable input shapes and batchability for NPU execution.
- Alignment with project memory, concurrency, and native quarantine contracts in AGENTS.md.
