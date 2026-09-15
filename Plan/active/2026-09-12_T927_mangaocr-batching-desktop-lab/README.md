# T927 — MangaOCR batching: desktop development/test laboratory (design)

## Director request (2026-09-12)

> "Design a desktop development/test laboratory for the MangaOCR batching work
> BEFORE modifying the Android production engine. Do NOT modify Android
> production code yet. … The first objective is a highly observable MangaOCR
> laboratory where correctness failures are easy to reproduce and understand."

Full 16-point requirement list is recorded in the session transcript; the
design addresses each point explicitly (see requirement map at the end of
DESIGN.md).

## Task contract

- **Deliverable:** DESIGN.md in this folder — exact folder structure,
  modules, execution flow, CLI, log format, result schemas, staged
  implementation order. Design only; no lab code and no Android changes yet.
- **Baseline evidence:** `evidence/onnx-graph-findings.md` (graph shapes,
  cache semantics, batch=1 baking, proven in-place patch, Android KV
  write-slot defect). Establishes the corrected B=1 semantics the reference
  decoder must implement.
- **Hard constraints:**
  - Lab uses the exact bundled model assets
    (`app/src/main/assets/models/ocr/`), hash-verified every run.
  - Correctness ground truth = corrected B=1 reference decoder
    (graph-convention KV semantics), never the Android decoder.
  - Desktop benchmark numbers are dispatch-overhead/correctness evidence
    only — explicitly NOT Android performance predictions.
  - No GUI. CLI only.
- **Out of scope (until separately authorized):** implementing the lab
  itself, patching bundled assets in the app, any
  `MangaOcrEngine`/pipeline change.

## Relationship to prior tasks

- T924 (batch pipeline), T925/T926 audits: established Stage-1 OCR as the
  wall-time dominant and decoder_step as the dominant native cost.
- T927 prepares the experimental ground for the MangaOCR batching design
  that those audits ranked as the #1 optimization direction.
