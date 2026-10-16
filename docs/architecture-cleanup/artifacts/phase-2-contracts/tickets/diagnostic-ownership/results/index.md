---
title: "P2.1 diagnostic-ownership — results and evidence"
kind: spec
comments: none
---

# P2.1 implementation results

Slice `phase-2-contracts/tickets/diagnostic-ownership/index.md` on `architecture-cleanup` @ `dab5c124`. Moves only: package/import wiring; APIs, bodies, constants, logging fields and math byte-identical. Sole Gradle owner; no commits/pushes/subagents/devices/models/config/lint-sweep/Phase-2.2 work; original report and all prior XML untouched (report SHA256 re-verified `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`).

# Exact change (11 logical files = 2 moves + 7 production callers + 2 tests; 13 filesystem paths with old/new counted separately)

Tracked `git diff --numstat`: five callers 1/1 (import swap), two phases 2/1 (adaptive import out, two diagnostics imports in), two tests +1 (import), two old paths 0/106 and 0/20 (deleted); two new untracked files (105 + 20 lines).

| Path | Δ | Content |
| --- | --- | --- |
| `pipeline/NativeVisionTelemetry.kt` → `diagnostics/NativeVisionTelemetry.kt` | moved | package line only + dropped now-same-package `TelemetryTrace` import; body byte-identical |
| `pipeline/adaptive/DeviceStageNormalization.kt` → `diagnostics/DeviceStageNormalization.kt` | moved | package line only; body byte-identical |
| `engines/vision/ocr/PaddlePageOcrCoordinator.kt` | 1/1 | `pipeline.NativeVisionTelemetry` → `diagnostics.NativeVisionTelemetry` (resorted above `engines.*`) |
| `tachiyomi/ui/reader/ReaderTranslationController.kt` | 1/1 | same swap (resorted before `diagnostics.ReaderEntryTrace`) |
| `pipeline/TranslationPipeline.kt` | 1/1 | `pipeline.adaptive.DeviceStageNormalization` → `diagnostics.DeviceStageNormalization` |
| `pipeline/batch/BatchRenderJoin.kt` | 1/1 | same swap |
| `engines/vision/ocr/RoiPageRecognitionEngine.kt` | 1/1 | same swap |
| `pipeline/SinglePageOnnxPhase.kt` | 2/1 | adaptive DSN import out; `diagnostics.DeviceStageNormalization` + `diagnostics.NativeVisionTelemetry` in (same-package usages now imported) |
| `pipeline/SinglePageHttpRenderPhase.kt` | 2/1 | same |
| `test/…/pipeline/NativeVisionTelemetryTest.kt` | +1 | `import eu.kanade.translation.diagnostics.NativeVisionTelemetry`; path + assertions unchanged |
| `test/…/pipeline/adaptive/DevicePagePermitGateTest.kt` | +1 | `import eu.kanade.translation.diagnostics.DeviceStageNormalization`; path + assertions unchanged |

All imports placed in ASCII sort order; no adapters, typealiases, renames, or logic. The gate and OCR/LiteRT methods untouched.

# Fidelity proof (vs base `dab5c124`)

Declaration/import edits, listed explicitly:

- NativeVisionTelemetry`.kt` (base 106 lines → new file 105 lines): (a) package declaration replaced — `package eu.kanade.translation.pipeline` → `package eu.kanade.translation.diagnostics`; (b) `import eu.kanade.translation.diagnostics.TelemetryTrace` removed (same package after the move; no other import touched). No line was added or reordered.
- DeviceStageNormalization`.kt` (base 20 lines → new file 20 lines): package declaration replaced only — `package eu.kanade.translation.pipeline.adaptive` → `package eu.kanade.translation.diagnostics`.

Ordered-body equality (verified programmatically, not by difference counting): after removing the replaced package line and the one dropped import from the base file, the remaining lines compare ordered-equal to the new file minus its package line —

- NativeVisionTelemetry: **104/104 lines ordered-equal** — i.e. the object body (KDoc through closing brace) is byte-identical, as are the surviving `java.util.Locale` import and all logging fields/math.
- DeviceStageNormalization: **19/19 lines ordered-equal** — entire body byte-identical.

# Reference scan

- After edits: **zero** tree-wide matches for `translation.pipeline.NativeVisionTelemetry` / `translation.pipeline.adaptive.DeviceStageNormalization` (executable references, fully-qualified names, tests). Plain-symbol scan resolves to exactly the 11 files above; no fully-qualified production usages existed beyond the audited imports (verified pre-edit against the ticket manifest — audit matched it exactly).

# Validation

Selectors: `--tests eu.kanade.translation.pipeline.NativeVisionTelemetryTest --tests eu.kanade.translation.pipeline.adaptive.DevicePagePermitGateTest`, `--rerun` fresh compilation, DevDebug then StandardDebug sequentially.

| Run | Shell | Exit | Result |
| --- | --- | --- | --- |
| Pre-baseline DevDebug | `71feca65` | 0 | telemetry **6/0**, gate **7/0** (BUILD SUCCESSFUL 1m 38s) |
| Pre-baseline StandardDebug | same | 0 | 6/0 + 7/0 (33s) |
| Post DevDebug | `b200ae62` | 0 | fresh compile; **6/0 + 7/0** (1m 43s) |
| Post StandardDebug | same | 0 | fresh compile; **6/0 + 7/0** (44s) |
| `:app:spotlessCheck` (read-only) | same | 1 | **only** the known pre-existing `LiteRTMangaInpaintingEngine.kt` wrapping debt (untouched, recorded baseline); zero violations in any touched file — import placements are sort-clean |

Note: shell `71feca65`'s process exit code 127 is an artifact of a trailing PowerShell cmdlet appended after all Gradle runs and XML copies had completed (`BASE_DEV_EXIT=0`/`BASE_STD_EXIT=0` in-log); no Gradle run was affected. Evidence dirs were pre-created (`mkdir -p`) before every copy; post-run copies verified by re-reading the XML (counts above).

# Limits

- No full suites run (import moves only, per ticket); both moved-object consumers covered by the two preserved classes plus fresh main+test compilation of the whole module per flavor.
- LiteRT wrapping stays recorded debt; no formatting performed anywhere.
- Four baseline ArtifactOpenProbe failures remain the Phase 3/4 entry gate; JDK21-local/JDK17-CI parity and device/JNI measurements remain deferred.
- Phase 2 cache design question (serialized suspend vs old Deferred plan) untouched here; no reader/native/persistence work started.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Baseline XML: `scratch/phase-2-contracts/diagnostic-ownership/pre-baseline/{dev,std}/xml/`
- Post XML: `scratch/phase-2-contracts/diagnostic-ownership/post-change/{dev,std}/xml/`
- Logs: `commands/…/{71feca65-63f7-4ae9-81ff-655a0823ed80,b200ae62-2392-4b45-8efb-230de5d74143}/output.log`
- Ticket: `artifacts/phase-2-contracts/tickets/diagnostic-ownership/index.md`

# Next

Stopped for actual-diff Gemini/Antigravity review. No commits/pushes, no further Phase 2 edits until parent handoff.
