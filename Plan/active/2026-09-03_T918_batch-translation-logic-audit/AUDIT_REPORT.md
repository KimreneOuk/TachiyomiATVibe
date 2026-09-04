# Batch translation logic audit

## Verdict

The batch system is a persisted, stage-aware chapter scheduler, not a simple
page loop. It correctly reuses compatible completed reader or batch work,
enforces natural page order for translation, and maintains bounded native-memory
work by processing one chunk to a terminal state before OCR admission continues.

No confirmed data-corruption or duplicate paid-provider-call defect was found
in the paths audited. The significant constraints are intentional context safety
and two operational limits described in [Risks and evidence gaps](#risks-and-evidence-gaps).

## 1. What a batch needs before it can work

1. A queued chapter whose source is an `HttpSource`, a valid OCR/target-language
   configuration, and a resolvable chapter translation store.
2. A readable chapter directory/archive containing at least one image page. Page
   files are filtered and put in natural order; reader position is not an input.
3. Usable translation/OCR engines. The native lane is acquired serially and
   engines are rebuilt under a mutex.
4. A per-page source fingerprint and expected stage fingerprints. It hashes each
   source page before planning; an unreadable preflight stream gets the explicit
   `source-fingerprint-unavailable` sentinel rather than immediately aborting.

The chapter queue itself is persisted. After process restart queued items
rehydrate as queued/paused and require an explicit user Start, so the app does
not silently resume expensive work.

## 2. The unit of work is five dependent artifacts

Each page is planned from durable state rather than merely checking whether a
status says `READY`.

```
detection ──> OCR ──> translation ──> layout/render
     └──────────────> inpaint ──────┘
```

For every stage, `PageWorkPlanner` validates completion status, payload presence,
fingerprint/provenance, source identity (for detection/inpaint), and durable
failure metadata. Incomplete, cancelled, running, partial, payload-less, or
fingerprint-mismatched evidence schedules work again or waits for its dependency.
Only terminal, compatible artifacts are reusable. A retryable durable failure
waits until its retry deadline unless forced; a failure associated with an old
fingerprint stops fencing the current configuration.

Batch records provenance when it commits its own OCR, inpaint, translation, and
layout stages. Reader manual/auto work lives in the same chapter store and uses
the same expected fingerprints: it is therefore reusable by batch if valid. A
reader result is not discarded merely because its origin is not `BATCH`.

The resume gate then selects one of three efficient paths:

| Gate | Meaning |
|---|---|
| `SKIP_ALL` | Native artifacts are valid; do not decode/OCR again. |
| `INPAINT_ONLY` | OCR/mask remains valid but the cleaned output is stale, missing, or needs a new inpaint mode. |
| `FULL` | OCR must be redone, followed by dependent stages. |

A cleaned-image file is physically checked, so metadata alone cannot cause a
missing image to be reused. Legacy pages without an inpaint-mode record remain
compatible to avoid a mass first-run retranslation.

## 3. Manual/auto work and fragmented resume

### Work in progress elsewhere

Batch obtains a per-page OCR-stage lease. If reader manual/auto work owns the
page, batch defers it instead of racing it. At the end of an otherwise completed
pass it waits for lease handback and rescans deferred pages, at most twice. If
the other owner has already completed durable work, the rescan selects
`SKIP_ALL`, so batch does not repeat the provider request.

### Completed pages with holes or distant islands

There are two distinct answers:

- **Reuse/render:** native stages remain per-page. A completed page after a
  gap can still be reused; batch does not redo good OCR/inpaint solely because
  an earlier page is missing.
- **Translation/context:** the chapter planner is intentionally gap-free. Once
  an earlier page needs translation, later nonterminal pages are marked
  `WAIT_FOR_DEPENDENCY (PRIOR_PAGE_INCOMPLETE)`. Their render waits too. This
  prevents a later provider request from overtaking missing dialogue.

For contextual AI, `BatchContextFrontier` is stricter still. It seeds rolling
context only from a contiguous natural-order prefix of currently reusable or
terminal pages. A complete page 20 cannot influence a provider request for a
missing page 5. It is retained and folds into context only when traversal reaches
it after predecessors are resolved.

Textless terminal pages count as continuity: they advance the frontier without
adding translated pairs. Partial output does not. A non-textless terminal
translation failure establishes a hard context gap, and subsequent AI pages are
not admitted in that run. They remain reusable/pending on disk, rather than
being translated with false continuity.

## 4. How chunks are determined

### Contextual-AI lanes

The chunk is a greedy whole-page provider envelope, not a fixed page count.
`StreamingChunkPlanner` estimates input cost from prompt overhead, page/block
protocol keys, source text, configured context window, safety margin, reserved
minimum output, and response framing. Its effective budget is:

```
context window − safety margin − minimum output − response-envelope reserve
```

It adds complete pages while they fit. It will not split a page across calls.
An individual oversized block or a page that cannot fit alone is rejected;
otherwise the preceding envelope flushes before the next page would overflow it.
The planner starts with a default 8,192-token context window, 512-token safety
margin, and 256-token output floor; LM Studio uses 16,000 tokens. Requested
output is reduced when necessary, but never below the safety floor.

Before dispatch it includes bounded rolling context (at most 32 pairs) and the
chapter glossary. If that makes the request too large, it removes glossary
context first, then rolling context; it does not split source text or violate
the output floor.

### Standard/non-contextual lanes

These do not provider-batch pages. A remote lane uses an OCR/native scheduling
chunk of seven pages (`MAX_NATIVE_LOOKAHEAD_PAGES + 1`); the provider still
receives pages individually. Local compute uses a one-page chunk to prevent
local model contention with native work.

## 5. Exactly when OCR stops and parallel work begins

For every chunk, OCR executes one page at a time in the serialized native lane.
All pages belonging to the current chunk finish OCR before the chunk’s
translation or inpainting branches begin.

On an AI overflow, the page that revealed the boundary is an intentional
one-page **OCR-only probe**. It already has OCR persisted and is owned by the
planner, but becomes the first page of the next chunk. It cannot inpaint,
render, or permit any later OCR admission until the preceding chunk is terminal.
This is the only adaptive lookahead.

The normal remote-chunk sequence is:

1. Sequential OCR of all current-chunk pages; persist each OCR result.
2. Release the OCR barrier.
3. Start the ordered provider translation request(s) asynchronously.
4. At the same time, run native inpainting sequentially, reusing the page-local
   decoded OCR handoff and releasing it immediately after inpaint/cancellation.
5. For each page, wait for *both* translation and native gates, then render in
   page order.
6. Wait for both branches and every render settlement before admitting OCR for
   the next chunk.

Thus remote network/provider time overlaps only current-chunk inpainting—not
the next chunk’s OCR. Local compute is inline: translation precedes inpaint and
the next page’s OCR, favoring stability over throughput.

## 6. Stops, failures, and cleanup

Stopping an active queued chapter returns it to `QUEUE`, preserving artifacts
for planning-based continuation. Explicit removal/cancellation aborts tracker
work only when the queue entry was removed and flushes the store.

Retryable provider failure or partial response pauses at an anchor page and
halts future OCR admission. Unexpected OCR, inpaint, render, or translation
errors fail the current pass at the affected page, leave its tail pending, and
reconcile durable state. Rejected persistence is kept distinct from a fabricated
page failure. On every exit, pending decoded handoffs, leases, and temporary
batch state are released; durable candidates/failures are retained for a later
plan.

## Risks and evidence gaps

| Finding | Classification | Impact |
|---|---|---|
| Source-fingerprint preflight creates one IO task per page and reads the entire chapter before scheduling. Shared archive readers may see high IO/descriptor pressure. | Design limitation; strong inference | Medium operational risk for very long/archive chapters. |
| A reader-owned page that exceeds the bounded lease handback plus two rescans can leave batch stranded/error even if manual work later succeeds. | Expected bounded-fairness behavior | Medium user-visible recoverability limit; restart/retry reconciles it. |
| A non-textless terminal gap blocks the later AI tail. | Intentional context-integrity behavior | Low availability trade-off; avoids discontinuous AI context. |
| A source-equal provider response is treated as unfinished and can be sent again. | Intentional conservative retry behavior | Low; avoids accepting accidental provider echoes. |

There is focused unit/integration coverage for planner reuse decisions, context
frontier gaps, probe/barrier/overlap scheduling, cancellation/restart, and
reader/batch ownership. Missing high-value coverage includes real archive
concurrency stress, lease-held-beyond-rescan behavior, multi-gap manual/auto
fragmentation through the full pipeline, fingerprint-preflight failure recovery,
and measured native bitmap memory/cancellation stress.

## Recommendation

Preserve the page-atomic, gap-safe scheduler. The next safe improvement is to
bound (or serialize for archives) source-fingerprint preflight, then add the
missing deterministic integration/stress coverage before changing chunk
parallelism or allowing AI to cross a terminal text gap.

## Evidence

Detailed technical trace: `engineering/code-investigation.md`.

Independent failure-mode audit: `review/failure-mode-audit.md`.
