# Batch architecture overview — superseded intermediate proposal

Superseded by `chapter-profile-batch-design.md`. Retained for decision history.

2026-09-05; source reviewed at `adbe643`. Architecture only: no implementation
or runtime tests. This supersedes the earlier Fast-default recommendation in
`chunk-sizing-options.md`. Source citations below are relative to
`app/src/main/java/`; shortened translation paths start at `eu/kanade/translation/`.

## 1. Intent and status

Director priority: translation quality, fewer API calls, and lower token usage.
15 requests/minute is the agreed intended cloud batch norm, not current code.
First-result latency is secondary. Whole pages, one AI envelope in flight,
probe handoff, gap-free rolling context, stage leases and token ceiling remain
invariants. Android 8+, reader stability, and bounded memory remain constraints.

Current implementation already provides stage-aware scheduling, shared artifacts,
foreground-service operation, buffered progress UI, and token-driven envelopes.
Fast/Efficient selectors, block soft caps, small-first-envelope rules, 15-RPM
default and the force-reuse fix have NOT been implemented.

## 2. Before work starts

1. Translate admission ensures source images are downloaded/finalized before
   download-triggered batch handoff. Batch itself consumes local directory or
   archive streams and explicitly fails if the chapter cannot be found
   (`ChapterTranslator.kt:583-635`; `eu/kanade/tachiyomi/data/download/Downloader.kt:594-616,782-818`).
2. Pages are naturally ordered; page keys and source-count evidence are
   preregistered, and progress is rebuilt from persisted state
   (`ChapterTranslator.kt:642-674`). Actual work keys define tracker totals;
   unrelated stored leftovers cannot inflate them
   (`pipeline/batch/TranslationBatchProgressTracker.kt:179-205`). Source-count
   cross-checking separately records missing pages (`ChapterTranslationStore.kt:1435-1470`).
3. Engine initialization and all-page source hashing precede stage planning
   (`pipeline/batch/BatchChapterTranslator.kt:299-336,384-429`). This startup I/O
   remains even with a small first envelope; hashing has no explicit per-batch
   fanout bound.
4. Planner compares stage status, payload, source/configuration fingerprints,
   durable failures and AI glossary version. Missing physical cleaned images
   downgrade reuse (`pipeline/batch/BatchResumePlanner.kt:91-108,217-255`).
   Reader viewport is not the resume cursor. Valid native stages can survive
   even when translation/layout need work.

## 3. Fragmented example

Assume matching source/settings and usable artifacts: pages 1,2,4 translated;
3,5,6 missing. Preserve completed work and schedule missing stages on 3,5,6.
This does not imply separate requests for those pages: they may share an envelope.

The initial rolling context contains the contiguous prefix 1,2. Page 4 is retained
behind gap 3 and cannot enter preceding-page rolling context for that pending
envelope. Once 3 completes, retained 4 can advance the frontier for subsequent
requests (`pipeline/batch/BatchContextFrontier.kt:34-90`). Chapter glossary is
separate and may contain historical pairs from later pages.

Implementation nuance: initial chapter planning changes later REUSE decisions to
WAIT_FOR_DEPENDENCY (`model/PageWorkPlanner.kt:114-149`). AI lane has a live
READY/SKIPPED + translation-fingerprint gate to avoid repaying completed pages
after a gap (`pipeline/batch/BatchLaneWorkers.kt:1322-1358,1377-1389`). This gate
is narrower than complete provenance validation; do not describe every fragment
as fully revalidated. Changed configuration/source or glossary maturation can
legitimately require repair (`model/PageWorkPlanner.kt:290-309`).

## 4. Chunk execution and efficiency proposal

Current loop: ordered OCR/admission -> boundary/probe -> provider translation
with native inpaint work -> per-page render join -> next chunk. Provider emissions
wait behind OCR barrier; retained probe is not admitted twice
(`pipeline/batch/SequentialBatchCoordinator.kt:560-613,617-695`;
`pipeline/batch/BatchLaneWorkers.kt:1436-1453,1639-1671`). A normal envelope has
one logical provider operation; retries can produce multiple HTTP attempts.

Revised proposal:

- Efficiency-oriented default. Block cap/minimum remain tuning candidates;
  35/15 is not an accepted optimal configuration. Pending nonblank request
  blocks are the relevant count, not all detected regions.
- Preserve room for useful context and output. Current rolling-context attachment
  drops glossary first, then rolling pairs if still over budget
  (`translator/contextual/TranslationContextChunkPlanner.kt:92-120`). Maximizing
  input fill does not prove maximum quality.
- Optional Fast mode would trade more requests/prompt overhead for smaller
  envelopes and possibly a 3-4-contributing-page first envelope. Efficient is
  currently a discussion label for token-driven fill; whether either label
  should become a preference remains open. Inpainting FAST/QUALITY is unrelated.
- Resolve oversized-page/min-fill precedence before coding. Suggested refinement:
  allow a token-safe oversize page to join an under-minimum buffer rather than
  mandate shipping it alone. Never override hard token admission. Tail, rejected
  pages, token boundaries and optional first-envelope boundaries are explicit
  minimum-fill exceptions.
- Preserve pre-check emission: four contributing pages may require OCR of a
  fifth probe before dispatch. Zero-contribution pages mean block caps do not
  guarantee wall-clock or OCR-page latency bounds.
- Defer next-chunk OCR overlap; it is a separate ownership/memory change.

Default token constants are 8192/512/256 with 1400 estimated prompt overhead;
LM Studio uses 16000. Response reserve also includes page/fixed overhead, not
just blocks (`translator/contextual/TranslationContextChunkPlanner.kt:21-39,179-202`;
`translator/contextual/StreamingChunkPlanner.kt:215-222`).

## 5. Request pacing

Current cloud governor defaults to 60 RPM, 60000 estimated TPM, one-second
spacing, one in-flight request per bucket; bucket identity is provider/model/
credential scope (`translator/ProviderRequestGovernor.kt:21-35,66-84,655-673`).
Batch, reader, auto and settings share it. Retries are charged per actual attempt
(`:343-394`).

Recommend implementing the intended 15 RPM as a shared applicable cloud bucket
default while preserving reader priority, token limits and provider cooldowns.
This is an application policy, not verification of every provider's quota. If
Director wants strictly batch-only 15 RPM, add a sub-limit beneath the shared
provider quota, not an independent allowance that reader traffic can exceed.
The distinction remains open. The limiter is a rolling window, not an obligatory
four-second sleep. Reader headroom only applies while an interactive waiter
exists; an active batch request is not preempted (`:443-468`).

## 6. Shared artifacts and reader-visible results

Reader and batch resolve the registry-backed chapter store. Candidate stage work
is separate from committed display. Reader subscribes to `store.display`, attaches
translated streams and refreshes enabled translation as display-ready results
arrive (`eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:2863-2917`). Thus a page
can appear at its committed render; no whole-chapter completion is needed.

Per-page leases prevent reader/batch competing writers. MANUAL can evict AUTO,
but not BATCH (`store/PageStageLeaseTable.kt:75-99`). Batch defers reader-owned
pages, with bounded handback rescans (`pipeline/batch/BatchLaneWorkers.kt:824-846`;
`pipeline/batch/SequentialBatchCoordinator.kt:698-735`). Generation, version,
dependency and lease preconditions reject stale writes
(`ChapterTranslationStore.kt:826-861`).

Two reuse issues need distinct design work:

1. Forced translation currently requires both OCR and inpaint readiness to reuse
   either (`model/PageWorkPlanner.kt:30-41`). Proposed fix independently validates
   reusable OCR/detection using source/config evidence; current force API lacks
   those expected inputs.
2. Streamed pages use URL keys; downloaded pages use filenames. Migration requires
   equal online/disk/store page counts (`TranslationManager.kt:1267-1281`;
   `ChapterTranslationStore.kt:1328`). Sparse reader artifacts may miss migration
   and be redone after download. Guard is VERIFIED; user impact is STRONG
   INFERENCE requiring a regression test. Successful migration also globally
   cancels/joins batch job (`TranslationManager.kt:1287`); unrelated batch impact
   and open-reader rebinding need coverage.

## 7. Background work, another request, settings

Foreground service supports ongoing batch during reading; reader teardown leaves
active batch engines/stores alive (`TranslationManager.kt:691-697`;
`manager/ReaderTeardownCoordinator.kt:67-100`). Notifications show progress and
Stop All. Service is START_NOT_STICKY: process death stops execution; persisted
queue restores without automatic start (`eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt:49-95`;
`TranslationManager.kt:633-634`). Stop All clears queue intent but retains artifacts.

Only one chapter worker runs globally (`ChapterTranslator.kt:388-393`). Duplicate
same chapter is suppressed. Another-source work waits. Same-source single action
can show Replace/Cancel for active work and silently evicts other waiting
same-source chapters; multi-selection appends (`TranslationManager.kt:765-768,807-844,883-941`;
`eu/kanade/tachiyomi/ui/manga/MangaScreen.kt:384-408`). Recommend consistent Add
to queue by default with explicit Replace, as separate UX work, not assumed scope.

Configuration is neither wholly frozen nor wholly live: languages/provider/output
are read at start; reading order is read live; workers use shared engine suppliers
(`pipeline/batch/BatchChapterTranslator.kt:282-312,348-356,570-592`;
`pipeline/batch/BatchLaneWorkers.kt:176-178,1397`; `pipeline/EngineLane.kt:410-439`).
Recommend immutable run configuration with edits applying next run; applying now
requires explicit stop/replan. Shared engine ownership must uphold that contract.
This is proposed; no specific mixed-config race was runtime reproduced. Reader
master translation disable explicitly stops translator/engines
(`eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:664`).

Buffered UI already exists (`pipeline/batch/TranslationBatchProgressTracker.kt:383-389`;
`eu/kanade/presentation/manga/components/TranslationProgressSheet.kt:695-701,980`).
Improve clarity if needed; do not rebuild it as a missing capability.

## 8. Download and reader source choice

On new load, finalized downloaded chapters take precedence over HTTP. Otherwise
HTTP loader uses cache before fetching image bytes. Already loaded chapters do
not automatically swap loaders (`eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt:37-39,87-115`;
`HttpPageLoader.kt:173-189`). Original source and translation artifacts are
separate; having OCR/translated data alone does not guarantee original images
are available offline.

Download retries reuse completed image files/cache, discard incomplete `.tmp`,
then fetch missing pages. Completion validation precedes batch handoff
(`eu/kanade/tachiyomi/data/download/Downloader.kt:878-951,1506-1510,594-616`).
An ordinary interrupted temporary download does not start its requested batch.
A finalized folder later damaged/deleted is different: reader's presence check
is not a fresh integrity audit and local open can fail without automatic network
repair (`eu/kanade/tachiyomi/data/download/DownloadCache.kt:150-159`;
`eu/kanade/tachiyomi/ui/reader/loader/DownloadPageLoader.kt:173-181`).

## 9. Acceptance priorities

Scheduling changes ship together with chunk-boundary coverage. Cover fragmented
3/5/6 in one envelope with retained 4; exact cap/min-fill equality; token oversize;
first-envelope/probe/tail exceptions; textless/resumed pages; partial/refusal;
pause/cancel and lease settlement; per-page reader refresh in pager/webtoon.

Add targeted cases for sparse stream-to-download migration, download completion
during unrelated batch, config edits/rebuilds during work, same-source queue
replacement, retry cooldown and process restart, missing files/SAF access/disk
publication failures. These newly found areas are recommendations, not silently
authorized implementation scope.

Partial provider results pause and cannot advance rolling context; terminal
non-textless gaps fence later AI work (`pipeline/batch/BatchLaneWorkers.kt:535-619`;
`pipeline/batch/BatchContextFrontier.kt:56-90`). Cleaned bitmap registry caps 4/48 MiB with spill,
not whole-process memory; three consecutive OOMs abort
(`pipeline/batch/HeldBitmapRegistry.kt:17-57`; `pipeline/batch/BatchOomPolicy.kt:9-24`).

Supporting source reports: `../engineering/reader-storage-overview.md`,
`../engineering/queue-config-overview.md`, `../engineering/overview-edge-review.md`,
and `../engineering/source-reverification.md`.
