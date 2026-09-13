# T923 — Batch translation: source-code investigation

Date: 2026-09-05 · HEAD: `adbe643` (T922 included) · Method: direct source
reading (subagent delegation unavailable this session — Main Leader fallback).
Every claim below carries a `file:line` citation verified at HEAD. Prior audit
reports (T918) were used only as a hypothesis list; each load-bearing claim was
re-derived from code.

## A. Verdict

Batch translation is a **persisted, stage-aware chapter scheduler**, not a page
loop. It plans each page's remaining work from durable state (statuses +
fingerprints + physical file checks), runs one bounded chunk of pages through
OCR → (translation ∥ inpaint) → render, and only admits the next chunk when the
current one is terminal. Storage work goes through an artifact store with
crash-safe publication and per-page writer leases so the reader and batch never
write the same page concurrently. No data-loss or duplicate-provider-call
defect was found on the audited paths; the sharp edges are documented in §F.

## B. Step-by-step pipeline

### B1. Entry and preconditions

| # | Step | Evidence |
|---|---|---|
| 1 | Manga-screen "translate chapter" action enqueues the chapter; `ChapterTranslator` runs the job and calls `pipeline.translateBatch` → `BatchChapterTranslator.translateBatch`. | `ChapterTranslator.kt:678`, `TranslationPipeline.kt:1258-1267`, `BatchChapterTranslator.kt:194` |
| 2 | Chapter translation store resolved/opened (artifact dir under the translations manga dir; legacy flat file only as fallback/migration). Unresolvable → chapter `ERROR`. | `ChapterTranslator.kt:563-580` |
| 3 | Chapter files located via `downloadProvider.findChapterDir`. Missing (deleted download or never-downloaded streamed chapter) → typed fail "Chapter files not found — the download may have been deleted". Batch requires local page bytes. | `ChapterTranslator.kt:583-605` |
| 4 | Page enumeration: archive chapters share one mmap'd `ArchiveReader` (no per-page re-decompression); directory chapters open files directly. Image entries only, natural-sorted. | `ChapterTranslator.kt:613-634` |
| 5 | `ResumeOrdering.naturalOrder` fixes the traversal 1..N; reader viewport/last-read position is never an input. | `ChapterTranslator.kt:638-643` |
| 6 | `store.preRegisterPages(keys, probedSourcePageCount, sourceCountKnown)` — a rejected pre-registration fails the chapter with a typed reason; the manifest's trusted total is the SOURCE total, not the found count. | `ChapterTranslator.kt:651-664` |
| 7 | Per-chapter progress tracker created and `rebuildFromStore()` so resumed pages show real progress before new events. | `ChapterTranslator.kt:666-674` |
| 8 | Zero readable pages → tracker aborted as `FAILED_NO_PAGES` (distinct from 0/0). | `BatchChapterTranslator.kt:217-228` |

### B2. Batch preamble (inside `store.withGeneration`)

| # | Step | Evidence |
|---|---|---|
| 9 | One T922 `TranslationScheduleTrace` opens per batch invocation and closes exactly once in the outer `finally` on every exit path. | `BatchChapterTranslator.kt:203-253` |
| 10 | `store.beginGeneration(...)` + `withGeneration`: all batch writes carry generation/pageVersion preconditions (optimistic concurrency). | `BatchChapterTranslator.kt:282-285`, `ChapterTranslationStore.kt:190-206` |
| 11 | Engine setup acquires the serialized native lane and `engineRebuildMutex`; timeout or failure → release all batch leases + tracker abort. | `BatchChapterTranslator.kt:299-336` |
| 12 | Lane selection: `isAi = AI_MODEL engine && ContextualTextTranslator`; LM Studio gets the 16k-token profile. | `BatchChapterTranslator.kt:348-357`, `TranslationContextChunkPlanner.kt:190-200` |
| 13 | Chapter glossary accumulator seeded from already-translated pairs so restarts keep name continuity. | `BatchChapterTranslator.kt:362-365` |
| 14 | Source-fingerprint preflight: one parallel IO hash per page BEFORE planning, so replacing a page under the same key cannot reuse stale artifacts. Unreadable stream → `UNKNOWN_SOURCE_FINGERPRINT` sentinel, not an abort. | `BatchChapterTranslator.kt:384-409` |
| 15 | `BatchResumePlanner` plans the whole chapter via `PageWorkPlanner.planChapter`: per page, per stage (detection/OCR/translation/inpaint/layout) it validates status, payload, fingerprints/provenance, durable failure metadata, and (AI lane) glossary version. Outcomes: RUN / REUSE / TERMINAL_COMPLETE / WAIT_FOR_DEPENDENCY / FAILED_RETRYABLE / FAILED_TERMINAL. | `BatchResumePlanner.kt:91-108`, `BatchChapterTranslator.kt:416-429` |
| 16 | Context frontier seeded from the contiguous natural-order prefix of reusable/terminal pages only. | `BatchResumePlanner.kt:123-150`, `BatchContextFrontier.kt:34-48` |

### B3. Resume gate (what gets redone)

- Planned path: `SKIP_ALL` (everything durable-valid; no decode), `INPAINT_ONLY`
  (OCR/mask valid, cleaned output stale/missing), `FULL` (OCR must rerun) —
  `BatchResumePlanner.kt:217-256`.
- A `REUSE`-planned cleaned image is **physically checked**
  (`exists() && length() > 0`); metadata-only entries downgrade to
  INPAINT_ONLY/FULL. `BatchResumePlanner.kt:228-247`.
- Legacy pages with `inpaintingModeUsed == null` are treated as compatible so
  existing chapters are not mass-retranslated on first open.
  `BatchResumePlanner.kt:259-260`.
- A deferred page that the other origin (reader) has since driven to a terminal
  render is force-routed `SKIP_ALL` so the paid provider call is never repeated.
  `BatchLaneWorkers.kt:857-871`.

### B4. Chunk admission and the chunk loop

- AI lanes: `StreamingChunkPlanner` admits whole pages greedily under
  `context window − safety margin − min output − response reserve`
  (8192−512−256−reserve; LM Studio 16,000). Pages are atomic (never split
  across envelopes); an oversized page is rejected into `rejectedPages`; a page
  whose admission would overflow flushes the envelope and returns `PROBE` —
  the page that revealed the boundary is retained as the NEXT chunk's first
  page (it already owns planner state; re-admitting it would double its
  blocks). `StreamingChunkPlanner.kt:9-66`,
  `SequentialBatchCoordinator.kt:617-684`,
  `TranslationContextChunkPlanner.kt:21-23`.
- Standard lanes: no provider-side batching. Remote chunk = 7 pages
  (`MAX_NATIVE_LOOKAHEAD_PAGES=6` + 1); local compute = 1 page so local
  inference never contends with native work.
  `SequentialBatchCoordinator.kt:69-73,987`.
- Loop skeleton: sequential OCR admission per page → chunk boundary (probe or
  page count) → `processChunk` → next chunk only if the outcome is Completed.
  `SequentialBatchCoordinator.kt:617-696`.

### B5. Inside a chunk (`processChunk`)

1. All of the chunk's pages finish OCR first (the OCR barrier).
   `SequentialBatchCoordinator.kt:630-688`.
2. Remote: the translation lane runs async (`translationJob`), emitting ordered
   provider envelopes; per-page gates are armed. Local: translation runs inline
   before inpaint/next-page OCR (stability over throughput).
   `SequentialBatchCoordinator.kt:331-443,452-558`.
3. Concurrently, native inpaint runs page-by-page in the serialized native
   lane, releasing each decoded handoff in `finally` (leak-proof on cancel).
   `SequentialBatchCoordinator.kt:560-602,611-613`.
4. The render join waits for BOTH gates per page, then renders in page order;
   a page whose translation paused/failed is marked non-renderable and its
   gates are completed so the join still terminates.
   `SequentialBatchCoordinator.kt:194-249,307-318`.
5. `renderJob.await()` + `translationJob.await()`; only then is the next chunk
   admitted. `SequentialBatchCoordinator.kt:604-609,688-695`.

### B6. Commit, reconcile, teardown

- Every guarded durable write goes through `BatchWriteGate` (generation,
  pageVersion, leaseToken, candidateGenerationId preconditions; provenance
  stamping incl. glossary version at translation commit).
  `BatchChapterTranslator.kt:436-444`, `BatchWriteGate.kt:30-52`,
  `BatchResumePlanner.kt:64-85`.
- Completion: `BatchProgressReconciler.reconcile` over durable state; stranded
  pages are marked FAILED with an explicit reason; `store.flush()`; tracker
  `finish`. `BatchChapterTranslator.kt:742-768`.
- Every exit path: batch leases released/cancelled, `NonCancellable` flush,
  `reconcileArtifactRetention()`, `onBatchClosed`.
  `BatchChapterTranslator.kt:769-796`.
- Unexpected worker exception → the affected page is made durably FAILED
  (`persistUnexpectedBatchStageFailure`) before reconciliation; if even that
  publication is rejected the pass reports `PERSISTENCE_REJECTED` instead of
  fabricating durable state. `BatchChapterTranslator.kt:683-737,808-858`.

## C. Storage & durability edge cases

| Case | Behavior | Evidence |
|---|---|---|
| Store layout | Artifact store = manifest + immutable per-stage artifact tree; legacy flat file demoted to rescue/migration source once authority flips LEGACY→ARTIFACTS (one-way; lost manifest on both copies rebuilds conservatively). | `ChapterArtifactStore.kt:20-48,66-80` |
| Crash/process death mid-stage | On load, interrupted RUNNING stages are recovered to retryable (lifecycle §13); unresolved attempt-ledger entries are consumed at startup and repeated interruptions raise an INTERRUPTED cap pause; user retry clears the cap explicitly. | `ChapterArtifactStore.kt:72-75`, `ChapterTranslationStore.kt:392-436` |
| Write durability | Debounced 250 ms persist + explicit `flush()` at every batch exit; the final flush runs `NonCancellable` so cancellation cannot starve it. | `StorePersistenceScheduler.kt:33,74-101,141-153`, `BatchChapterTranslator.kt:781-793` |
| Reader never sees half-work | Two-layer state: live candidate map vs `committedDisplay` last-known-good bundles; promotion is atomic under the store mutex only after a page reaches rendered shape; retries may thrash the candidate without hiding the committed bundle. | `ChapterTranslationStore.kt:110-125` |
| Cleaned-image deletion safety | Superseded cleaned files are RETAINED until the newer bundle promotes, then drained — a rapid promote sequence can't delete a file still displayed. | `ChapterTranslationStore.kt:128-136,1993-2023` |
| Metadata lies (missing file) | Cleaned image physically checked at resume; missing → downgrade, no reuse of a phantom image. | `BatchResumePlanner.kt:228-247,266-286` |
| Unreadable page image | Fingerprint preflight stores `UNKNOWN_SOURCE_FINGERPRINT` sentinel; decode failures are tracked (LowMemory deferred → OCR-failed status), never crash the chapter. | `BatchChapterTranslator.kt:398`, `BatchLaneWorkers.kt:933-939` |
| Deleted download mid-run | Pre-start: typed "Chapter files not found" failure. Mid-run closures would surface IO failure via the normal stage-failure path; queue removal → tracker abort + flush. | `ChapterTranslator.kt:589-605,721-730` |
| Artifact/retention hygiene | Bounded retention sweep runs at chapter boundary (`reconcileArtifactRetention`), deliberately NOT on reader-entry close (T921: SAF crawl stalled first open ~15s/68 pages). | `StorePersistenceScheduler.kt:103-139`, `BatchChapterTranslator.kt:794` |
| Queue across restart | Queue membership+order in SharedPreferences; rehydration rebuilds each entry via `fromChapterId`, deleted chapters self-heal (dropped); state rebuilt as QUEUE (refined to PAUSED/ERROR from artifacts); NEVER auto-starts work — explicit user start required. | `TranslationQueueStore.kt:18-22,42-64` |
| Cancellation semantics | Cancel while still queued = pause (tracker preserved for resume); cancel with queue entry removed = explicit removal (tracker aborted). | `ChapterTranslator.kt:721-730` |
| Disk-full / rejected publication | Guarded writes can be Rejected by the store; a rejected terminal persists as `PERSISTENCE_REJECTED` (distinct from a fabricated page failure) and the reconciler keeps the tail pending. | `BatchChapterTranslator.kt:689-717`, `ChapterTranslationStore.kt:67-82` |

## D. Other processes / coexistence edge cases

| Case | Behavior | Evidence |
|---|---|---|
| Reader manual/auto owns a page | Batch takes a per-page OCR-stage lease (`tryAcquirePageStageLease`, origin BATCH). Denied (MANUAL/AUTO owns) → page DEFERRED, never a competing writer. | `BatchLaneWorkers.kt:824-846`, `PageStageLeaseTable.kt:19-47` |
| Deferred page later finished by reader | The coordinator re-runs deferred pages WITHIN the same pass once the lease is handed back (`awaitPageLeaseRelease`); the externally-completed gate routes them SKIP_ALL so the paid provider call is not repeated. | `SequentialBatchCoordinator.kt:698-735`, `BatchLaneWorkers.kt:857-871`, `BatchChapterTranslator.kt:628-637` |
| Lease never returns | Handback wait is bounded (`LEASE_HANDBACK_WAIT_MS = SINGLE_PAGE_TIMEOUT_MS`); after `RESCAN_MAX_ATTEMPTS = 2` sweeps the reconciler reports the page honestly instead of looping forever. | `BatchChapterTranslator.kt:860-868`, `SequentialBatchCoordinator.kt:710-735,993` |
| Reader-visible state during batch | Batch reuses reader-produced work if fingerprints/provenance validate (origin need not be BATCH); both share the same chapter store and expected fingerprints. | `BatchResumePlanner.kt:91-108`, `TranslationStageContracts.kt:27-40` |
| Reader entry latency | Retention sweep removed from `closeAndFlush` (T921) so batch/reader store closes never stall reader entry. | `StorePersistenceScheduler.kt:103-117` |
| AI context integrity vs fragmented resume | Reuse is per-page (a good page 20 is not redone because page 5 is missing), but rolling CONTEXT only advances over a contiguous terminal prefix; a non-textless terminal translation failure creates a hard gap that blocks later AI admission (`blocksLaterAi`); textless terminal pages advance the frontier without adding pairs; PARTIAL output never advances context. | `BatchContextFrontier.kt:34-91`, `BatchResumePlanner.kt:123-175` |
| Provider failure taxonomy | NETWORK/RATE_LIMIT/QUOTA/SERVER → TRANSIENT (pause at anchor, `nextEligibleRetryAtEpochMs`, tail stays pending); REFUSAL/AUTH/CONFIG/SOURCE/PROTOCOL map to their own categories; retryable vs terminal decides Paused vs Failed. | `BatchWriteGate.kt:8-19`, `BatchLaneWorkers.kt:726-762,1490-1515` |
| Duplicate paid calls | Three independent fences: per-page lease (one writer), externally-completed SKIP_ALL routing, and source-fingerprint equality fencing (artifacts planned against current bytes only). | `BatchLaneWorkers.kt:833-871`, `BatchResumePlanner.kt:91-108` |
| Memory pressure | Native lane is serial (one bitmap alive at a time); held cleaned bitmaps bounded by 4-count AND 48 MB byte ceiling (spill to durable .cleaned.jpg); OOM recovery wrapper on persists; 3 consecutive OOMs → chapter abort with "retry after restart". | `BatchChapterTranslator.kt:180-193`, `HeldBitmapRegistry.kt:12-40`, `BatchOomPolicy.kt:9-27`, `BatchChapterTranslator.kt:667-682` |
| Engine contention | OCR/inpaint/engine-setup all serialize through `nativeLane.run` with per-stage timeouts (`markPageTimedOut` on OCR timeout); engine rebuilds under a dedicated mutex. | `BatchChapterTranslator.kt:299-313`, `BatchLaneWorkers.kt:903-911` |

## E. T918 audit delta (claims re-verified at HEAD)

| T918 claim | Status at `adbe643` |
|---|---|
| Stage model + planner-driven reuse (5 artifacts, fingerprints, durable failures) | **Holds** (§B2/B3) |
| Resume gates SKIP_ALL / INPAINT_ONLY / FULL + physical cleaned-file check + legacy inpaint-mode compatibility | **Holds** (`BatchResumePlanner.kt:217-298`) |
| Lease defer/handback + 2 rescans; SKIP_ALL routing avoids repeat paid calls | **Holds** — T917 D3 code now explicitly in coordinator (`SequentialBatchCoordinator.kt:698-735`) |
| Gap-free translation planner; contiguous context frontier; textless continuity; non-textless gap blocks AI tail | **Holds** (`BatchContextFrontier.kt:34-91`) |
| Chunking: greedy whole-page envelope, 8192/512/256, LM Studio 16000; remote lookahead 7, local 1 | **Holds** (`StreamingChunkPlanner.kt:9-66`, `TranslationContextChunkPlanner.kt:21-23,197`, `SequentialBatchCoordinator.kt:73,987`) |
| OCR-only probe retained as next chunk's first page | **Holds** (`SequentialBatchCoordinator.kt:617-684`) |
| Stop/failure semantics: pause-at-anchor, unexpected-stage durable failure, persistence-rejected distinctness, teardown releases | **Holds**, and T922 added typed schedule outcomes per status (`BatchChapterTranslator.kt:800-806`) |
| Restart rehydration: queue persisted, no auto-start | **Holds** (`TranslationQueueStore.kt:18-22`) |
| Risks: preflight IO per page; lease-bounded fairness; AI tail block; source-equal response re-send | **Unchanged** (see §F) |
| T922 delta not in T918 | Observability only (`translation_trace_v1` wiring through batch: schedule/run/stage spans, sweep registries, lane accumulators — `SequentialBatchCoordinator.kt:39-44,86-169`) plus CPU-primary segmentation routing; **no behavioral change to batch scheduling/storage logic found** |

## F. Risks / limitations observed in code

1. **Fingerprint preflight IO burst**: one `async(Dispatchers.IO)` hash per
   page reads the whole chapter before any work (`BatchChapterTranslator.kt:394-409`).
   For very long/archive chapters this is a startup IO/descriptor spike — by
   design (correctness), unbounded in degree.
2. **Lease-bounded fairness**: a reader-owned page that holds its lease past
   `SINGLE_PAGE_TIMEOUT_MS` handback and 2 rescans is reported by the
   reconciler as pending/failed even though it may finish later
   (`SequentialBatchCoordinator.kt:710-735`). Restart/retry reconciles it.
3. **AI tail blocked by hard gap**: a non-textless terminal translation
   failure intentionally fences later AI pages until a run resolves the gap
   (`BatchContextFrontier.kt:88-91`) — availability trade-off for context
   integrity.
4. **Oversized page rejection**: a single page whose blocks exceed the
   effective token budget is rejected by the planner (never split); the batch
   reports it as rejected rather than degrading page atomicity
   (`StreamingChunkPlanner.kt:24-33`).

## G. Evidence index

Primary files read in full or in relevant part this task:
`BatchChapterTranslator.kt`, `SequentialBatchCoordinator.kt`,
`BatchResumePlanner.kt`, `BatchContextFrontier.kt`, `BatchLaneWorkers.kt`
(lease/OCR/admission/pause regions), `BatchWriteGate.kt`, `BatchOomPolicy.kt`,
`HeldBitmapRegistry.kt`, `ChapterTranslationStore.kt` (API + persistence +
attempt-cap regions), `StorePersistenceScheduler.kt`, `ChapterArtifactStore.kt`
(header/contract), `PageStageLeaseTable.kt`, `TranslationQueueStore.kt`,
`TranslationStoreResolver.kt`, `ChapterTranslator.kt` (entry region),
`StreamingChunkPlanner.kt`, `TranslationContextChunkPlanner.kt` (constants),
`TranslationStageContracts.kt`.

## H. T923 follow-up diagnostic (Director-observed behavior, 2026-09-05)

Symptom: on a 200-page chapter, batch detect/OCR advanced ~40 pages with zero
translations; a later auto/manual retranslate did not reuse the batch OCR.

### H.1 Chunk size is token accumulation, not a page count

- `admit` → `planner.accept(pageKey, p)` only BUFFERS the page
  (`tracker?.markAiBuffered`, BatchLaneWorkers.kt:1439-1444). The envelope
  flushes (and only then does a provider request fire) when the NEXT page
  overflows the token budget, returning `ChunkAdmission.PROBE`
  (BatchLaneWorkers.kt:1445-1454, SequentialBatchCoordinator.kt:672-674).
- There is NO maximum page count on an adaptive envelope — only the token
  budget (`promptBudget` = 8192 − 512 − 256 − response reserve,
  StreamingChunkPlanner.kt:220-224) with REAL jtokkit cl100k token counting
  (TranslationContextChunkPlanner.kt:144-148) plus 1,400 prompt overhead.
- Text-sparse pages (~100-200 tokens) ⇒ one envelope can hold 30-45 pages.
  The OCR barrier (SequentialBatchCoordinator.kt:630-688) runs ALL of them
  before request #1; inpaint/render also wait behind the barrier. A ~40-page
  envelope is simultaneously a ~40-page response — elevated provider
  timeout/truncation risk → retryable pause at the anchor → 0 translated.

### H.2 Reader-side OCR reuse after an interrupted batch

- Batch commits OCR per page immediately: `analyzePage` stamps
  sourceFingerprint + detection/ocr fingerprints and persists via
  preconditioned `mergeOcr` (SinglePageOnnxPhase.kt:913-915, 929-955;
  ChapterTranslationStore.kt:826-929). Cancellation retains committed
  pointers (ChapterArtifactStore.kt:585-592 "retaining the committed pointer").
- Rolling Auto always plans with `force = false` (TranslationScheduler.kt:401);
  a manual tap resolves force=true ONLY when a stage is FAILED
  (ReaderViewModel.kt:2213-2226). force=false uses evidence-based `planPage`,
  which REUSES OCR READY + blocks + stored fingerprints (expected null ⇒
  config/source checks trivially pass, PageWorkPlanner.kt:275-287,366-372).
  `copyForResume` only drops in-memory bitmaps (CleanedPublication.kt:252-257).
- Therefore the observed redo maps to:
  1. Pages mid-OCR at interruption → RUNNING/CANCELLED ⇒
     INTERRUPTED_STAGE/CANCELLED_STAGE ⇒ redo (PageWorkPlanner.kt:260-264).
     Legitimate.
  2. **Force-path native reuse gap (the real finding):** under `force=true`
     the planner reuses native work ONLY when BOTH OCR *and* inpaint are
     complete — `canReuseNative = ocrReady && inpaintReady`,
     `runOcr = !canReuseNative` (PageWorkPlanner.kt:30-41). An interrupted
     batch that never crossed the OCR barrier has inpaintStatus PENDING on
     every page, so ANY forced retranslate re-detects + re-OCRs all of them
     even though valid batch OCR exists on disk.
  3. While the batch was still active/paused, auto/manual lease acquisition
     is DENIED and the page defers (BatchLaneWorkers.kt:833-844) — looks
     like "not using the artifacts".

### H.3 Recommended follow-ups (not implemented — Director decision)

1. Cap adaptive envelope page count (e.g. 8-12) in addition to the token
   budget: bounds OCR-barrier latency, bounds response size, first
   translation lands early. Single lever in StreamingChunkPlanner; per T918,
   pair with chunk-boundary coverage tests.
2. Close the force-path gap: let forced retranslates reuse committed
   OCR/detection when fingerprints match instead of requiring inpaint
   completion (PageWorkPlanner.kt:30-41).
3. Surface the buffered-page count in the batch tracker so "N buffered,
   0 sent" is visible rather than appearing stuck.

These follow-ups evolved into the T924 design record (block-count soft cap
supersedes the page-count cap in item 1, per Director proposal and
discussion): `../2026-09-05_T924_chunk-sizing-and-fast-feedback/design/chunk-sizing-options.md`.
