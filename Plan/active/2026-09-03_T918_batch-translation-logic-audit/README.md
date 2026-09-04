# T918 — Batch translation logic audit

## Objective

Produce a source-evidenced, structured audit of the current batch translation
pipeline. Explain prerequisites, normal execution, reuse/resume after reader
manual or automatic translation, behavior with fragmented and distant completed
pages, chunk planning, OCR stopping conditions, and OCR/translation/inpainting
parallelism and sequencing.

## Scope

- Read-only investigation; do not change production code.
- Treat live source and tests as primary evidence.
- Distinguish verified behavior from inference and identify untested edges.

## Key source landmarks already known

- `docs/architecture/batch-translation-pipeline.md`
- `docs/superpowers/plans/2026-08-19-chunked-rolling-batch-translation.md`

## Deliverables

- `engineering/code-investigation.md`: control flow, state/reuse, chunking and
  concurrency, with code references.
- `review/failure-mode-audit.md`: independent edge/failure audit with severity,
  likelihood, and evidence.

