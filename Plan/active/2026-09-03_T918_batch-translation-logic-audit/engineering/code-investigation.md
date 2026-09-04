# T918 technical investigation — batch translation logic

## Scope and evidence

This is a read-only investigation of the live implementation and its JVM tests.
Line references are one-based source locations in this checkout.  Evidence labels
mean: **VERIFIED** = directly established by production code and/or a focused test;
**STRONG INFERENCE** = follows from the control flow but was not exercised here as
an end-to-end Android/device run; **UNKNOWN** = a meaningful property for which no
direct coverage was located.

The short version: a chapter batch is a persisted, natural-page-order pipeline.
It plans each *stage* of every page against durable artifacts and fingerprints,
then works one bounded chunk at a time.  Completed reader/manual/automatic work
is eligible for reuse when it is current, but AI context is intentionally more
strict: only a contiguous completed prefix can seed context.  A missing page
prevents later pages from being used as context; a non-textless terminal failure
also blocks later AI provider admission.

## 1. Preconditions, admission, and durable inputs

### What must exist for a batch to start

| Requirement | Current behavior and evidence |
|---|---|
| HTTP source and chapter identity | **VERIFIED.** `queueChapter` casts the source to `HttpSource` and returns if it cannot; it also ignores a chapter already in the queue ([ChapterTranslator.kt:495-503](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L495)). |
| Valid OCR and target-language configuration | **VERIFIED.** Preference parsing is guarded and rejects invalid values; ML Kit also rejects unsupported target languages ([ChapterTranslator.kt:504-527](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L504)). |
| Translation artifact store | **VERIFIED.** Execution reuses an active reader store when available, otherwise opens/creates the chapter artifact; inability to resolve it changes the chapter to `ERROR` ([ChapterTranslator.kt:539-581](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L539)). |
| Downloaded readable page files | **VERIFIED.** It requires `findChapterDir`; missing files emit a typed failure instead of entering the pipeline ([ChapterTranslator.kt:583-605](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L583)). Directory pages and archive entries are filtered to images and sorted in natural order ([ChapterTranslator.kt:608-635](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L608)). |
| At least one page | **VERIFIED.** An empty ordered page set aborts the tracker with “Chapter has no readable pages to translate” and does no work ([BatchChapterTranslator.kt:196-205](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L196)). |
| OCR/translation engines | **VERIFIED.** The batch starts a generation, acquires the serialized native lane, and rebuilds engines under a mutex. Failure/timeout releases batch leases and aborts progress ([BatchChapterTranslator.kt:216-250](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L216)). |

The UI/manager puts the chapter in a persistent queue then starts the queue worker
([TranslationManager.kt:729-765](../../../../app/src/main/java/eu/kanade/translation/TranslationManager.kt#L729)). The queue itself persists chapter IDs; after an app restart entries are restored as paused and require an explicit Start, so the application does not silently resume paid/OCR work on launch ([ChapterTranslator.kt:160-175](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L160), [177-226](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L177)).

Before running stages, the worker:

1. obtains every image stream and establishes natural page indexes;
2. preregisters the page keys in the artifact store and starts/rebuilds the
   progress tracker ([ChapterTranslator.kt:635-685](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L635));
3. computes a source fingerprint for every stream concurrently on `Dispatchers.IO`
   (falling back to the explicit `source-fingerprint-unavailable` sentinel), then
   derives current expected stage fingerprints ([BatchChapterTranslator.kt:291-329](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L291)).

**STRONG INFERENCE — cost/latency consequence.** The fingerprint preflight reads
every page before deciding reuse, so a fully reusable batch avoids OCR/provider
work but does not avoid chapter-wide image-byte I/O/hashing.

## 2. Per-stage plan: what “already done” means

### Planner model

`PageWorkPlanner.planPage` evaluates the five stages—detection, OCR, inpaint,
translation, layout/render—using persisted status, payload existence, fingerprints,
source identity, artifact origin and durable failure metadata
([PageWorkPlanner.kt:60-111](../../../../app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt#L60)). Dependencies are:

```
detection ──> OCR ──> translation ──> layout
     └──────────────> inpaint ──────┘
```

This is **VERIFIED** by the stage dependency table
([PageWorkPlanner.kt:348-355](../../../../app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt#L348)). Thus an OCR change makes translation/layout wait, but deliberately does *not* invalidate an otherwise valid erase mask/inpaint result.

An artifact is reusable only when it is terminal success, has the required
payload, and has matching provenance/fingerprints. Missing/unknown provenance,
fingerprint mismatch, incomplete payload, `RUNNING`, `CANCELLED`, and `PARTIAL`
are all replanned through `dependencyOrRun` ([PageWorkPlanner.kt:260-287](../../../../app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt#L260)). Durable retryable failures are not immediately run until their retry time,
unless force-retried; a failure recorded for an old fingerprint stops being a
fence ([PageWorkPlanner.kt:205-230](../../../../app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt#L205)).

`BatchResumePlanner` creates this complete chapter plan once, from the current
store snapshot and the source-fingerprint map ([BatchResumePlanner.kt:54-81](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L54)). It stamps batch provenance when it commits OCR, inpaint, translation, or layout ([BatchResumePlanner.kt:35-51](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L35)).

### Reader manual/automatic work and shared artifacts

**VERIFIED.** Batch planning has no “batch-only” page identity or separate
artifact namespace. It reads the shared `ChapterTranslationStore` and current
expected fingerprints, so reader-origin work with valid artifacts/fingerprints
can plan as `REUSE`; only a batch translation commit gets the `translationOrigin =
BATCH` stamp ([BatchResumePlanner.kt:54-81](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L54), [35-51](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L35)). The active reader store is explicitly preferred by the chapter worker
([ChapterTranslator.kt:545-580](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L545)).

There are three resume gates after the plan:

| Gate | Meaning |
|---|---|
| `SKIP_ALL` | valid OCR/inpaint artifacts; do not decode/OCR again. It may only need render validation/reload, or can fully settle. |
| `INPAINT_ONLY` | OCR/mask is usable but cleaned output is stale/missing or inpaint must rerun. |
| `FULL` | OCR must run again, followed by downstream work. |

**VERIFIED.** A physical cleaned-image check prevents metadata-only reuse: if
the file is absent, current mask permits `INPAINT_ONLY`, otherwise `FULL`
([BatchResumePlanner.kt:160-243](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L160)). Legacy pages with no recorded inpaint mode are treated as mode-compatible rather than being mass-invalidated ([BatchResumePlanner.kt:222-225](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt#L222)).

### Concurrent reader/batch ownership

The batch takes a per-page OCR-stage lease. If the reader/manual/auto origin owns
the page, batch does **not** perform competing work; it records a deferral
([BatchLaneWorkers.kt:767-789](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt#L767)). When that owner finishes and releases the lease, a completed pass waits boundedly and rescans deferred pages (at most two sweeps). If the other origin already rendered it, the rescan force-routes it through `SKIP_ALL`, preventing a repeat provider call ([SequentialBatchCoordinator.kt:545-582](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L545); [BatchLaneWorkers.kt:799-826](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt#L799)).

**VERIFIED test coverage:** `D2ManualBatchInterleavingTest` covers batch→manual
waiting at provider start/end/render and manual→batch deferral/no duplicate paid
call ([D2ManualBatchInterleavingTest.kt:36-115](../../../../app/src/test/java/eu/kanade/translation/coexistence/D2ManualBatchInterleavingTest.kt#L36), [115-186](../../../../app/src/test/java/eu/kanade/translation/coexistence/D2ManualBatchInterleavingTest.kt#L115)). `D3ReaderOwnedPageAcrossBatchTest` covers a reader-held page being rescanned in the same full batch ([D3ReaderOwnedPageAcrossBatchTest.kt:37-102](../../../../app/src/test/java/eu/kanade/translation/coexistence/D3ReaderOwnedPageAcrossBatchTest.kt#L37)).

## 3. Fragmented/distant work: reuse versus context

The answer differs depending on whether “continue” means render/reuse or AI
conversation context.

### Ordinary reuse

**VERIFIED.** The planner evaluates every page independently for native stages,
so completed pages far after a gap can remain reusable—batch does not redo their
OCR/inpaint merely because page 2 is unfinished. However, `planChapter` makes
translation ordered: after the first page requiring translation work, later
nonterminal translations become `WAIT_FOR_DEPENDENCY` with
`PRIOR_PAGE_INCOMPLETE`; their layout waits too ([PageWorkPlanner.kt:114-164](../../../../app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt#L114)). This preserves chronological context and prevents a later provider
request from overtaking the hole.

### AI context frontier

For contextual AI, a `BatchContextFrontier` is seeded in natural order. It stops
at the first absent, ineligible, or nonterminal page—later completed pages are
not injected into requests for the missing page ([BatchContextFrontier.kt:34-48](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt#L34)). As traversal subsequently reaches a reusable/completed page, it records that
page; the frontier only advances its contiguous next index and retains later
items until their predecessor exists ([BatchContextFrontier.kt:50-85](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt#L50)).

Consequences, all **VERIFIED**:

- A distant valid page remains displayable/reusable, but cannot provide rolling
  pairs to earlier or gap-crossing provider requests.
- A textless terminal page is a valid continuity step and does not block;
  `PARTIAL` output never advances context ([BatchContextFrontier.kt:56-66](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt#L56), [93-108](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt#L93)).
- A non-textless terminal translation failure records a `gapIndex`; pages after
  it are blocked from AI admission until a later run resolves the gap
  ([BatchContextFrontier.kt:60-76](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt#L60), [88-91](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchContextFrontier.kt#L88)). The worker returns a failure at the first blocked page without manufacturing failures for the entire tail ([BatchLaneWorkers.kt:1314-1345](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt#L1314)).

This behavior is directly covered by `BatchContextFrontierTest`: contiguous-prefix
seeding, terminal gap blocking, deferred folding after a gap resolves, textless
continuity, and partial-not-context ([BatchContextFrontierTest.kt:12-82](../../../../app/src/test/java/eu/kanade/translation/pipeline/batch/BatchContextFrontierTest.kt#L12)).

**Clarification:** “fragmented completion” does not mean “the batch immediately
translates every hole and then jumps to later pages.” It can reuse later durable
pages but deliberately serializes translation across the earliest unresolved
predecessor. Native OCR/inpaint on later pages may still be independently done.

## 4. How a chunk is determined

### AI/contextual translator chunks

There is no fixed “N pages per chunk” or “N blocks per chunk.” The contextual
path uses `StreamingChunkPlanner` and packs *whole pages* greedily by estimated
token budget. It begins at prompt overhead ([StreamingChunkPlanner.kt:21-51](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt#L21)), estimates each block including key overhead, and:

1. rejects an individual over-budget text block;
2. rejects a page whose complete text cannot fit even alone;
3. otherwise flushes the preceding chunk if adding the next complete page would
   exceed the calculated prompt budget;
4. appends all blocks of the accepted page—never splits a page between requests.

This is **VERIFIED** in the planner branching ([StreamingChunkPlanner.kt:71-143](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt#L71)). The budget is:

```
max context − safety margin − minimum output − batch response envelope reserve
```

where the response reserve accounts for fixed, page, block, and whitespace
protocol overhead ([StreamingChunkPlanner.kt:220-223](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt#L220); [TranslationContextChunkPlanner.kt:14-34](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt#L14)). Default is 8,192 context tokens, 512 safety, and 256 minimum output; LM Studio uses 16,000 context tokens ([TranslationContextChunkPlanner.kt:14-16](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt#L14), [constraintsFor](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt#L173)). Requested output tokens are an upper bound; the planner reduces the cap to fit input/context/reserve.

Before the provider call it adds bounded rolling pairs and chapter glossary. If
that would overflow, it first removes the glossary then removes all rolling
context; it will not lower the output below the safety floor
([TranslationContextChunkPlanner.kt:67-108](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt#L67)). Rolling pairs are capped at 32; combined rolling/glossary context is normally capped at 1,500 estimated tokens ([TranslationContextChunkPlanner.kt:31-37](../../../../app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt#L31)).

`BatchEnvelopeLimitsTest` confirms no static page/block cap (80 blocks can be one
chunk) and a dense page stays one whole envelope
([BatchEnvelopeLimitsTest.kt:13-30](../../../../app/src/test/java/eu/kanade/translation/translator/contextual/BatchEnvelopeLimitsTest.kt#L13)).

### Non-contextual translator chunks

For normal remote per-page translators, the coordinator uses a bounded fallback
chunk of `MAX_NATIVE_LOOKAHEAD_PAGES + 1` = **7 pages**. For local compute it is
one page ([SequentialBatchCoordinator.kt:46-51](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L46), [MAX_NATIVE_LOOKAHEAD_PAGES](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L690)). This count controls scheduling/memory lookahead, not provider request batching: standard translators are called per page.

## 5. Exact phase sequencing and parallelism

### The AI overflow/probe rule

The coordinator OCRs pages sequentially and passes each OCR-ready page to the
AI planner. When the newest page triggers flushing the previous token envelope,
that page is marked `PROBE`: it has OCR artifacts and planner ownership but is
held as the first page of the next chunk. It does not start inpaint/render or
allow further OCR until the prior chunk is terminal ([SequentialBatchCoordinator.kt:466-537](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L466)). This is exactly one lookahead page, not a generic prefetch window.

### One normal chunk

**VERIFIED sequence:**

1. OCR all pages of the current chunk in native-lane order. Each successful OCR
   persists its blocks/status before it publishes `OcrReadyPageRef`
   ([BatchLaneWorkers.kt:869-952](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt#L869)).
2. Release the chunk OCR barrier ([SequentialBatchCoordinator.kt:534-537](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L534)).
3. For a remote translator, launch the ordered provider/chunk translation as an
   `async` branch ([SequentialBatchCoordinator.kt:252-331](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L252)). Simultaneously, sequentially execute native inpaint for each page. Inpaint receives the page-local decoded OCR handoff when available, otherwise independently decodes, then releases it in `finally` ([SequentialBatchCoordinator.kt:423-455](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L423); [BatchLaneWorkers.kt:1006-1042](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt#L1006)).
4. For each page, a render coroutine awaits *both* the native and translation
   gates, then renders in chunk order ([SequentialBatchCoordinator.kt:135-170](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L135)). The render join checks failures, waits for usable translation + cleaned image, loads the persisted cleaned image if needed, and writes layout color data ([BatchRenderJoin.kt:101-170](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchRenderJoin.kt#L101), [192-267](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchRenderJoin.kt#L192)).
5. Await both branches and all renders before admitting next OCR ([SequentialBatchCoordinator.kt:458-463](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L458)).

Remote I/O therefore overlaps current-chunk inpaint only after all current-chunk
OCR has completed. It does **not** overlap the following chunk’s OCR. Local
compute is deliberately inline: translation completes before inpaint and before
the next OCR page, preventing local inference/native contention
([SequentialBatchCoordinator.kt:339-420](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L339)).

Focused coordinator tests prove the probe barrier, OCR-before-AI/inpaint overlap,
remote overlap and render join, ordered commits, bounded long-chapter operation,
and local non-overlap ([SequentialBatchCoordinatorTest.kt:101-159](../../../../app/src/test/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinatorTest.kt#L101), [278-372](../../../../app/src/test/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinatorTest.kt#L278), [528-580](../../../../app/src/test/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinatorTest.kt#L528)).

## 6. Stop, pause, failure, and cleanup edges

| Condition | Behavior |
|---|---|
| User pause/stop | **VERIFIED.** Job cancellation returns currently translating entries to `QUEUE`, preserving artifacts for planner-based resumption ([ChapterTranslator.kt:291-315](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L291)). |
| Explicit removal/cancel | **VERIFIED.** A `CancellationException` aborts the tracker only if the queue entry was removed; otherwise it remains resumable. The store is flushed ([ChapterTranslator.kt:721-729](../../../../app/src/main/java/eu/kanade/translation/ChapterTranslator.kt#L721)). |
| Retryable provider failure / partial output | **VERIFIED.** Stops current pass at an anchor as `PAUSED`; no future OCR is admitted. The committed display is retained rather than rendering the provisional candidate ([SequentialBatchCoordinator.kt:202-239](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L202); [BatchRenderJoin.kt:299-306](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchRenderJoin.kt#L299)). |
| Unexpected OCR/inpaint/render/translation exception | **VERIFIED.** Stops the pass; the outer shell persists an unexpected-stage failure for the anchor when possible, reconciles current state, and leaves tail pages pending ([SequentialBatchCoordinator.kt:642-657](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L642); [BatchChapterTranslator.kt:581-633](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L581)). |
| Guarded persistence rejection | **VERIFIED.** Treated as a distinct non-durable result, not forged into a terminal page error ([SequentialBatchCoordinator.kt:627-641](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L627)). |
| OOM policy trigger | **VERIFIED.** Aborts pending tracker work, releases leases, flushes, and returns rather than trying tail reconciliation ([BatchChapterTranslator.kt:564-579](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L564)). |
| Any batch exit | **VERIFIED.** Remaining decoded handoffs/bitmaps are released, non-durable batch stage work is cancelled, durable failures keep their candidate, all batch leases are released, and a non-cancellable flush/artifact-retention reconciliation runs ([SequentialBatchCoordinator.kt:658-660](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt#L658); [BatchChapterTranslator.kt:665-681](../../../../app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt#L665)). |

Tests cover typed pauses halting future OCR, unexpected stage failures not being
misreported as completed, persistence rejection, and prompt cancellation
([SequentialBatchCoordinatorTest.kt:185-277](../../../../app/src/test/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinatorTest.kt#L185), [410-545](../../../../app/src/test/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinatorTest.kt#L410)). `T918CancelledBatchRestartTest` further validates a cancelled batch restart reuses persisted completed work rather than re-decoding/OCRing it ([T918CancelledBatchRestartTest.kt:87-235](../../../../app/src/test/java/eu/kanade/translation/coexistence/T918CancelledBatchRestartTest.kt#L87)).

## 7. Important edge conclusions and remaining unknowns

1. **Fragmentation is intentionally conservative for AI context.** Later work is
   not discarded, but it cannot make AI requests skip an earlier unresolved or
   terminally failed text page. This is correct for continuity but can leave
   visually completed distant pages while the chapter status remains paused/failed.
   **VERIFIED.**
2. **“All OCR before translation” is chunk-scoped, not chapter-scoped.** OCR all
   pages in the current adaptive/fallback chunk; then remote provider and native
   inpaint overlap; rendering joins; then only the next chunk OCR begins.
   **VERIFIED.**
3. **A chunk overflow costs one OCR-only probe page.** It is intentional bounded
   lookahead, not a parallel OCR pipeline. **VERIFIED.**
4. **Reusing completed output is stricter than merely seeing READY status.** It
   requires valid artifact payload/provenance/fingerprints, and native reuse
   additionally checks the actual cleaned image. **VERIFIED.**
5. **UNKNOWN:** No located instrumentation/device test proves real bitmap peak
   memory under high-resolution long chapters or cancellation during a truly
   non-cancellable ONNX call; coordinator tests validate scheduling contracts,
   not native heap measurements.
6. **UNKNOWN:** No located full integration test constructs a mixed *distant*
   manual/auto completion pattern with multiple independent gaps and validates
   the exact final progress/UI projection. The pure frontier test covers the
   key context rule; coexistence tests cover a single reader-owned page.
7. **UNKNOWN:** Source-fingerprint read failure is deliberately represented by a
   shared sentinel. The planner’s exact reuse result when both old and new
   fingerprints are this sentinel is code-dependent but no focused test was
   located; this deserves a test because fingerprint availability affects safe
   reuse of replaced bytes.

## Recommendation

Keep the current page-atomic, context-gap-safe scheduler. Before changing its
performance behavior, add end-to-end tests for multiple fragmented reader/auto
states, missing-source-fingerprint reuse, and memory/cancellation stress; those
are the material evidence gaps rather than a demonstrated defect in chunk logic.
