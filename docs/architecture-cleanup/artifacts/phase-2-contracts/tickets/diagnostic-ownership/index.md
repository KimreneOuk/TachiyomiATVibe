---
title: "P2.1 — move shared telemetry to diagnostics ownership"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Scope

Completed and independently accepted by Gemini/Antigravity. Parent committed d8252cbe: 11 rename-aware logical files, +13/-10 lines. Both telemetry/gate suites pass 13/13 before and after per flavor; package/import-only fidelity verified. No file-count change; known LiteRT formatting remains debt. [Results](results), [review](review).

First implementation slice under [Phase 2](../..), starting at dab5c124 on architecture-cleanup. Move production NativeVisionTelemetry from pipeline/ to diagnostics/ and DeviceStageNormalization from pipeline/adaptive/ to diagnostics/. Bodies, APIs, constants, logging fields and math must remain byte-identical modulo package/import changes. Reuse the existing names; no adapters, typealiases or new logic.

Paths below are under app/src/main/java/eu/kanade/translation unless otherwise stated. NativeVisionTelemetry callers: engines/vision/ocr/PaddlePageOcrCoordinator.kt, pipeline/SinglePageOnnxPhase.kt, pipeline/SinglePageHttpRenderPhase.kt, and app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt. TranslationPipeline does not call it. DeviceStageNormalization callers: pipeline/TranslationPipeline.kt, SinglePageOnnxPhase.kt, SinglePageHttpRenderPhase.kt, batch/BatchRenderJoin.kt, and engines/vision/ocr/RoiPageRecognitionEngine.kt. Refresh full-tree references before editing, including fully qualified names and tests.

Existing same-package tests must receive explicit new imports: app/src/test/java/eu/kanade/translation/pipeline/NativeVisionTelemetryTest.kt and pipeline/adaptive/DevicePagePermitGateTest.kt. Keep their paths and assertions unchanged for this slice; test renaming is unnecessary. No other production/test edits except discovered import/package wiring required to compile. Do not change OCR/LiteRT methods or the gate.

# Validation and delivery

GLM is sole Gradle owner. Before edits, preserve narrow baseline XML for NativeVisionTelemetryTest and DevicePagePermitGateTest. After moves, run those classes with fresh compilation in DevDebug and StandardDebug. Save each result immediately into pre-created unique directories; avoid the earlier missing-parent copy/overwrite mistake. Validate moved bodies against base and scan all executable references. Read-only relevant formatting checks may run; do not touch known unrelated LiteRT formatting.

Write results/index.md with exact files, byte-fidelity proof, commands, XML counts and limits. Preserve original report SHA256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD and baseline logs. Stop for actual-diff Gemini/Antigravity review. No commits/pushes, subagents, full suites merely for import moves, or further Phase 2 edits until parent handoff.
