---
kind: ticket
title: "Ticket 05 page-scoped Paddle integration notes"
status: 1
comments: none
---

# Ticket 05 integration contract

This ticket wires the reviewed Paddle leaf planner and bucket executor into the
existing page analysis boundary. The detector, per-region geometry, page native
quarantine, generation fences, leases, checkpoints, and non-Paddle OCR paths
remain authoritative.

## Batch-call serialization decision

`PaddleOcrV6SmallEngine.recognizeBucketBatch` calls the reviewed executor, whose
buffer pool intentionally permits two input buffers but only one output buffer.
The integration therefore **serializes every batch call at the page-adapter
seam with one `Mutex` per Paddle page coordinator/engine session**. Calls are
not allowed to overlap and are not permitted to rely on the executor's
allocation-failure downgrade when the output pool is occupied. This preserves
the supported `maxOutputBuffers = 1` memory contract and keeps the planner's
single-thread confinement explicit. `RoiPageRecognitionEngine.nativeGuard`
continues to cover the complete page analysis; the adapter does not acquire a
second native guard per microbatch and does not claim in-flight ORT
preemption.

## Page and ownership invariants

- A planner is created for exactly one `(pageId, generation)` and rejects every
  other leaf identity. A batch is submitted only after all its leaves belong to
  that planner, so no tensor contains leaves from two pages or generations.
- Results are mapped by the planner's input order and leaf identity before the
  page result is marked OCR-ready. Any cancellation or execution failure calls
  planner cancellation/release and publishes no partial page result.
- Manual, automatic, and chapter mode identity is carried into the page policy
  and page log; their cross-page admission remains owned by the existing
  schedulers. This adapter drains one page independently, so the chapter path
  streams pages and never retains chapter crops, while look-ahead metadata
  cannot enter this page's tensor.
- The existing B1 single-crop call remains the Paddle fallback for allocation
  downgrade. MangaOCR, MLKit, and all durable translation envelopes are
  untouched.

## Evidence to record at handoff

The implementer handoff will include the page-scoped batch sequence trace,
proof that all leaves resolve before commit, no-cross-page assertions,
mode-priority tests, cancellation/resume results, changed files, commands and
test counts, and explicit `CONFIRMED`/`FAILED`/`UNTESTED` labels.
