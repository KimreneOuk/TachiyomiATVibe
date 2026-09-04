# T918 batch translation — failure-mode audit

## Scope and evidence

Read-only review of the production batch entry/coordinator, resume planner,
AI streaming planner, write/lease paths, and their focused unit/integration
tests.  Source references below are one-based.  `VERIFIED` means the behavior
is directly established by production code and, where noted, an executable
test.  `STRONG INFERENCE` identifies a risk derived from code without a
production-faithful test of the exact external condition.

## Executive result

No confirmed data-corruption or duplicate-provider-call defect was found in
the examined resume/chunk/cancellation paths.  The normal safeguards are
substantial: stage reuse is fingerprinted, a batch lease excludes competing
writers, deferred reader-owned pages are re-scanned, and the coordinator does
not cross a chunk boundary until its render joins settle.

The material residual risks are operational rather than silent correctness
failures: the initial source-fingerprint preflight opens every page
concurrently (especially consequential for archives), and a manual operation
that keeps a lease beyond the bounded handback/re-scan policy can make the
batch end in an honest stranded/error result even if manual work eventually
finishes.

## Verified behavior matrix

| Area | What happens | Evidence | Status |
|---|---|---|---|
| Work order | The chapter is traversed in natural page order; reader position is not an input. | `ChapterTranslator.kt:635-643`; `PageWorkPlanner.kt:10-16` | VERIFIED |
| Reuse safety | A plan is made once for every page from stage status, payload presence, current fingerprints, source fingerprint for detection/inpaint, and durable failure metadata. Incomplete, cancelled, running, partial, or mismatched evidence reruns/waits rather than reuses. | `BatchResumePlanner.kt:87-108`; `PageWorkPlanner.kt:195-330,381-449` | VERIFIED |
| Reader/manual coexistence | Batch acquisition is denied while another origin owns the page; it records a deferral and does no work. Once released it rescans; if the other origin reached a durable render it forces `SKIP_ALL`, avoiding a second paid call. | `BatchLaneWorkers.kt:765-839`; `SequentialBatchCoordinator.kt:545-582`; `D3ReaderOwnedPageAcrossBatchTest.kt:38-147` | VERIFIED |
| Fragmented/distant completed pages | Only the contiguous natural-order prefix enters AI rolling context. A completed distant page is retained until predecessors arrive; it is not sent as earlier-page context. | `BatchContextFrontier.kt:34-85`; `BatchResumePlanner.kt:120-174`; `BatchContextFrontierTest.kt:13-56` | VERIFIED |
| Terminal gaps | A non-textless terminal failure creates a context gap. Later AI work is fenced rather than sent with discontinuous context; textless pages advance without a gap. | `BatchContextFrontier.kt:57-105`; `BatchLaneWorkers.kt:1315-1326,1580-1591`; `BatchContextFrontierTest.kt:29-79` | VERIFIED |
| OCR/chunk boundary | OCR is sequential. For an AI lane, an overflowing page is the sole OCR-only probe; it is retained as first input to the next chunk and cannot inpaint/render/admit more OCR until the previous chunk is terminal. | `SequentialBatchCoordinator.kt:17-27,466-543`; `BatchLaneWorkers.kt:1337-1345`; `SequentialBatchCoordinatorTest.kt:101-124` | VERIFIED |
| Parallelism/order | The full current chunk OCRs before either branch. Remote translation and serialized native inpaint overlap; render waits for both branches per page and next OCR admission waits for all pages in the chunk to settle. Local compute runs inline, avoiding native overlap. | `SequentialBatchCoordinator.kt:135-171,252-334,423-463`; `SequentialBatchCoordinatorTest.kt:127-157` | VERIFIED |
| AI chunk sizing | Whole pages are atomic. The planner counts prompt overhead, serialized block/page response overhead, text token estimates, configured profile/context limit, safety margin, and minimum output reserve. It flushes before an added whole page exceeds the budget; an individually oversize block/page is rejected. | `StreamingChunkPlanner.kt:16-19,80-145,194-223` | VERIFIED |
| Cancellation/typed failures | Cancellation propagates, releases handoffs/leases in `finally`, and is converted by the chapter boundary to an aborted tracker result only when the queued batch was removed. Provider pause/failure, unexpected stage failure, and rejected persistence are distinct outcomes that stop further admission. | `SequentialBatchCoordinator.kt:466-661`; `BatchChapterTranslator.kt:665-681`; `ChapterTranslator.kt:721-741`; `BatchCoordinatorInterfaces.kt:148-195,255-270`; `SequentialBatchCoordinatorTest.kt:160-181,528-551` | VERIFIED |

## Findings

### F-01 — Archive/source preflight has unbounded page-level concurrency

- Classification: design limitation / operational risk
- Severity: MEDIUM
- Likelihood: MEDIUM for very long chapters or slower sources; UNKNOWN for the concrete archive-reader implementation
- Evidence status: STRONG INFERENCE
- Evidence: `BatchChapterTranslator.kt:304-310` launches an `async(Dispatchers.IO)` fingerprint task for every page and waits for all before planning any page. For archive chapters all stream closures share one `ArchiveReader` (`ChapterTranslator.kt:613-630`); the hash function opens and reads every input independently (`PageDecode.kt:119-136`). There is no batch-local admission limit or archive-reader serialization around that preflight.
- Consequence: a large chapter delays all OCR until every source has been opened/read once, queues an unbounded number of jobs, and can create high concurrent I/O/descriptor/reader pressure. If the shared archive reader is not safe for concurrent `getInputStream`/reads, it can produce preflight hash failures. Failure is not silently reused in ordinary current artifacts: a real decoded source hash differs from the sentinel and schedules native work; nevertheless the up-front load remains.
- Confirm/refute: add an integration test with a production `ArchiveReader` spy that records simultaneous opens, then run it for a large archive. Define an explicit maximum desired source-read concurrency.

### F-02 — Reader-owned page can become stranded when its lease outlasts the bounded re-scan policy

- Classification: design limitation / expected bounded-fairness behavior
- Severity: MEDIUM
- Likelihood: LOW to MEDIUM (depends on long manual/provider/native work)
- Evidence status: VERIFIED
- Evidence: each deferred page waits only `SINGLE_PAGE_TIMEOUT_MS` for lease handback (`BatchChapterTranslator.kt:527-535,740-746`). A failed wait is deliberately left for reconciliation (`SequentialBatchCoordinator.kt:565-580`), and no more than two re-scan sweeps occur (`SequentialBatchCoordinator.kt:557-582,688-696`). Reconciliation then marks an expected pending page stranded (`BatchChapterTranslator.kt:639-657`). The positive case, in which manual releases within the bound and exactly one paid call is made, is covered by `D3ReaderOwnedPageAcrossBatchTest.kt:101-147`.
- Consequence: batch never races/duplicates manual work, but a user may see a batch failure/error even if the manual job later completes successfully. A new batch/retry is required to reconcile it.
- Confirm/refute: deterministic test that holds the manual lease past both waits and then completes it; assert the chapter’s terminal state, durable page state, and the behavior of the next batch start.

### F-03 — A non-textless terminal translation gap intentionally stops the later AI tail

- Classification: expected behavior (context-integrity trade-off)
- Severity: LOW
- Likelihood: LOW
- Evidence status: VERIFIED
- Evidence: a terminal predecessor adds `gapIndex` and blocks all higher indexes (`BatchContextFrontier.kt:57-91`); the AI lane records the first later blocked page and returns a failed outcome only after already admitted prefix settlement (`BatchLaneWorkers.kt:1315-1326,1580-1591`). Tests establish that completed distant pages remain reusable but never leak into context (`BatchContextFrontierTest.kt:29-56`).
- Consequence: one permanent bad page prevents AI translation of later missing pages in that invocation. This preserves terminology/rolling-context correctness, but it makes completion less available than independent-page translation.
- Confirm/refute: no refutation needed for current stated design; a product decision would be required to allow an explicitly context-reset tail mode.

### F-04 — AI planner preserves completed blocks but treats source-equal output as unfinished

- Classification: expected retry/resume behavior
- Severity: LOW
- Likelihood: MEDIUM on interrupted/partial AI batches
- Evidence status: VERIFIED
- Evidence: only a nonblank translation different from source is excluded from re-send (`StreamingChunkPlanner.kt:82-97`); a page with all nonblank blocks already complete becomes a chunkless completion (`StreamingChunkPlanner.kt:136-145`). Oversize data is rejected rather than split across requests (`StreamingChunkPlanner.kt:93-120`).
- Consequence: resume avoids re-paying for structurally valid translated blocks, but a provider that legitimately returns unchanged source text will be retried as unfinished. That is conservative and avoids accepting an accidental echo as translation.
- Confirm/refute: `StreamingChunkPlannerTest.kt` contains focused coverage for already-translated, source-equal, chunkless, and textless cases (test declarations at `44,95,146,171`); an end-to-end batch resume test with mixed partial blocks would increase confidence.

### F-05 — Source identity protection is strong once decode has succeeded; hash-preflight failure does not itself fail the batch

- Classification: expected availability behavior with a test gap
- Severity: LOW
- Likelihood: LOW
- Evidence status: VERIFIED for implementation; UNKNOWN for real-world failure frequency
- Evidence: preflight substitutes `UNKNOWN_SOURCE_FINGERPRINT` when hashing throws (`BatchChapterTranslator.kt:304-310`); hash errors are caught and logged (`PageDecode.kt:119-136`). A successful decode always derives SHA-256 from the exact decoded bytes (`PageDecode.kt:52-64,108-116`), and source mismatch invalidates detection/inpaint downstream (`PageWorkPlanner.kt:98-116,366-371`).
- Consequence: a transient preflight read failure favors progress rather than immediate chapter failure. Existing successfully decoded artifacts normally fail the source comparison against the sentinel and are rebuilt. There is no focused test for a hash failure followed by successful decode/restart, so the exact persistence/reuse behavior through the artifact store remains unverified.
- Confirm/refute: add an integration test where the first stream open throws only for preflight, later decode succeeds, then repeat with a changed page and verify no stale cleaned artifact is displayed.

## Test coverage assessment

Focused tests substantiate coordinator probe/barrier/overlap/cancellation behavior, context-frontier gaps, page planner reuse/fingerprint choices, reader-vs-batch ownership, and restart after cancellation. Not found in the reviewed test set: a production archive stress/concurrency test; a lease-held-past-rescan-limit test; a full pipeline test of noncontiguous prior manual/auto completions plus a new missing middle page; and hash-preflight-failure recovery coverage. These are coverage gaps, not evidence of current defects.

## Reviewer recommendation

Retain the current correctness model. Prioritize bounding/serializing fingerprint preflight (at least for shared archives) and add the two deterministic tests for expired reader-lease rescan and preflight hash failure. Keep the terminal-gap fence unless product explicitly accepts a context-reset tail policy.
