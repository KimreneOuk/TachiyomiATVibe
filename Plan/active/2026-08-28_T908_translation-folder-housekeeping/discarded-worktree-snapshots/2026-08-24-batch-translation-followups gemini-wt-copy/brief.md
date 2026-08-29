---
kind: spec
title: "Batch-translation follow-up optimizations"
---

## Objective

Implement viewport-first reader coexistence, foreground-service batch hosting,
and same-visit OCR/inpaint decode reuse.

## Scope boundary

Work only from the batch-translation deslimming architecture and its required
predecessor records; preserve the existing uncommitted deslimming refactor.

## Acceptance criteria

The user-provided phase acceptance criteria and validation protocol are the
authoritative criteria for this task.

## Constraints

- Retain the single `SequentialBatchCoordinator` scheduler design.
- Preserve lease ownership and the one-bitmap-alive memory model.
- Do not commit or revert unrelated work.

## Stop condition encountered

The intended deslimmed workspace is not available in this checkout. See
`checkpoints.md` for evidence and required resolution.
