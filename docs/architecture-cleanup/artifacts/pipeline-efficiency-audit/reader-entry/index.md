---
kind: review
title: "Priority audit: slow chapter list and opening existing translations"
comments: none
---

# User priority and scope

The [detailed proposed implementation plan](../../loading-performance-plan) now records the phased architecture, tests, measurement gates and rollback. This document remains the source audit; the linked plan is the implementation proposal.

The owner identified slow chapter-list loading and reader entry with existing translated text as the largest problems. These now precede Auto-generation, failed-page prefetch and settings cleanup. The target is time to first chapter list and time to the landing page with persisted translated image/text, including warm re-entry. Showing the original page sooner alone is not sufficient to satisfy translated-reader entry.

Baseline: `4de121bf`, clean `inpainting-modes-redesign` checkout. Read-only source audit; no production edits, builds, device commands, commits or pushes. No durations or measured speedups are claimed. Existing regression tests were read, not run. Both GLM 5.3 Flash/max agents delivered independent reports, and the orchestrator checked their load-bearing source claims. This consolidated review is the current evidence authority.

- [Chapter-list source investigation](chapter-list)
- [Persisted-reader source investigation](persisted-reader)

# Source-backed ranking

| Priority | Problem / trigger | Expected user benefit | Complexity |
| --- | --- | --- | --- |
| 1 | First chapter-list publication waits for sequential translation-status resolution on downloaded chapters | High potential reduction in time to usable list, especially with many translated chapters | Medium |
| 2 | New probe creation clears unrelated cached statuses, then the probe is removed; repeated multi-chapter scans can reopen stores | High potential reduction in repeated list-loading work and storage contention | Medium |
| 3 | Downloaded reader page enumeration waits for the translation store, which reads/verifies snapshots across the chapter | High potential reduction in time to first persisted translated landing page | High |
| 4 | Chapter status lookup opens recovery-capable full stores instead of a cheaper status/index projection | Broad reduction in chapter-list storage work; must preserve journal/corruption truth | High |
| 5 | Full-list status mapping runs inside a retryable atomic StateFlow update during rebuilds | Avoid repeated storage work under concurrent progress/state updates | Low–Medium |

This ranking follows the owner's reported impact and verified dependency structure. Measurement must determine which costs dominate their device. Priorities 1/2/4 overlap and should be implemented as a coherent status-loading design rather than separate competing caches.

**Smallest first change:** move rebuild storage work outside the atomic StateFlow update (L3, Low–Medium), then fix per-document cache invalidation (L2, Medium) and separate list-row publication from badge resolution (L1, Medium). These address chapter-list loading without starting the high-complexity reader hydration rewrite. The reader experiment remains focused on time to persisted translated landing-page content.

## Reader milestones: distinguish loading state from page content

`ReaderActivity` installs the view hierarchy with `setContentView` before launching asynchronous `viewModel.init` (`ReaderActivity.kt:174-194`). Its `viewerChapters` collector binds chapters only after loaded state arrives (`:219-224`). This permits the reader screen/loading shell to appear while artifact work runs; actual frame timing has not been measured. The downloaded loader barrier below blocks both first original-page and first persisted translated-page content.

After image binding, an overlay cache miss can still delay first translated text: `TextLayoutCoordinator.bind` hydrates/plans on a background executor and applies on Main with a generation fence (`TextLayoutCoordinator.kt:75-124`); `TranslationOverlayView` draws an empty overlay while Planning (`:174-181`). This is already asynchronous/cached and is not the upstream page-list publication barrier. Measure it separately rather than declaring layout free or attributing all reader delay to it.

## L1 — Chapter-list publication is gated on sequential durable lookups

**Verified:** initial load calls `toChapterListItems` before preparing the success state (`MangaScreenModel.kt:525-528`). Rebuilds combine chapter data, download-cache changes, download queue, translation queue and pending requests, then await the same mapping before publication (`:474-494`). Mapping is an ordinary sequential `map` (`:702-704`) and awaits `getChapterTranslationStatus` for each downloaded chapter (`:723-730`). The work is already dispatched to IO; moving it off Main did not remove this publication dependency.

**Proposal:** publish chapter rows from database/download/queue state promptly, using a genuine pending/unknown status for unresolved durable badges. Resolve/enrich translation badges afterward under bounded concurrency, with visible-row priority and stale-list/version cancellation. Keep prior known status while refreshing when valid; do not label unknown as definitively NOT_TRANSLATED. Live requests and queue state must remain immediate. Preserve error/retry details when a terminal badge resolves.

**Validation:** gate a slow durable lookup and verify rows are usable before release; status merges target the current manga/chapter revision only; canceled/reordered refresh cannot overwrite new rows; queue/download updates remain responsive; known terminal errors and warnings retain their actions. Compare first-row frame and time to complete badges for manga with many translated downloads.

## L2 — Fresh probes invalidate previous chapters' cached statuses

**Verified:** `DurableChapterStatusResolver.persistedChapterStatus` caches only non-null results (`:78-100`). A cache miss with an existing manifest invokes `withProbeStore` (`:104-126`). Every newly created probe clears the **whole** durable status cache (`:204-213`), then the caller's resolved status is reinserted. In the absence of active adoption, the owned probe is removed and closed after projection (`:224-233`; `ActiveChapterStoreRegistry.kt:167-172`). Sequential queries of different non-active translated chapters therefore create new probes, each invalidating statuses populated by prior queries. The next full-list scan can repeat that pattern. A held/active store takes a different path.

The behavior is intentional recovery invalidation but has a cross-chapter cost. `DurableStatusWipeGateTest.kt:207-235` tests fresh versus concurrently held probe behavior; it does not establish that a sequential multi-chapter sweep retains unrelated cache entries.

**Proposal:** narrow invalidation to the recovered chapter/document identity or use per-identity mutation generations. Ensure recovery/seeding, delete/reset/rekey, permission changes and persisted writes invalidate the appropriate status. Do not simply remove the clear: recovering a chapter can change its correct badge. Do not keep all full chapter stores resident merely to avoid reopening.

**Validation:** query translated chapters A/B/C sequentially, then query them again; assert unchanged A/B/C results survive and unnecessary opens do not recur. Recover/delete/update B and verify B refreshes while unchanged A/C retain valid status. Include held probe, active adoption, storage identity change and concurrent mutation tests. Count cache hits/misses, opens, clears and close/flush operations; timing alone is insufficient to explain the mechanism. The repeated-cache-collapse scenario requires multiple non-active chapters queried in a conflicting order; a single translated chapter can remain cached, and concurrent reader/progress adoption can retain a probe as an active store.

## R1 — Downloaded page-list loading waits for whole-chapter translation open

**Verified:** `DownloadPageLoader.getPages` awaits `getChapterTranslationForReader` (`:69-87`) before building directory/archive page lists (`:90-101`). That manager method opens an existing active chapter store on a miss (`TranslationManager.kt:1245-1268`). `ChapterLoader.loadChapter` cannot publish loaded pages until `getPages` returns (`ChapterLoader.kt:48-72`), and `ReaderViewModel.loadChapter` awaits this loader before publishing `viewerChapters` (`ReaderViewModel.kt:897`, `:932-944`). This directly puts translation-artifact loading on the initial reader display path.

Full open loads the manifest and replays private journal epochs (`ChapterTranslationStore.kt:4873-4892`), then iterates **every manifest page**, reading candidate and committed snapshots where pointers exist and checking fingerprints (`:4895-4913`). This loop runs even when the journal inventory is trusted. Untrusted inventory can additionally require journal seeding (`:5019-5033`). Journal artifact validation can itself read snapshots (`ChapterJournalRecovery.kt:413-437`). This is verified I/O/data work, not a measured duration or proof that every read is redundant.

**Proposal:** separate chapter identity/recovery/status inventory from visible-page content materialization. Hydrate the requested/landing page and a bounded nearby window first, deferring unrelated page snapshots while maintaining acknowledged journal-prefix authority, tombstone/corruption handling, hash-verified committed pointers and exact image/text matching. A status/index result must never become permission to show an unverified candidate. Account for the loader changing the landing index to `last_page_read` for unread chapters (`ChapterLoader.kt:66-69`).

**Safety qualification to Agent C's proposal:** the existing `getOrLoadPageSnapshot` is a starting seam, not a safe drop-in for this architecture. It chooses candidate before committed (`ChapterTranslationStore.kt:4405-4406`), reads that snapshot without the open-time expected-fingerprint comparison (`:4409`), and populates committed-display state if a committed pointer exists (`:4414-4424`). Lazy open must explicitly distinguish and verify candidate/committed records and journal winners before publishing them; retaining the current helper unchanged would not establish those invariants.

**Validation:** a large translated downloaded chapter should reach the first translated landing-page frame before unrelated pages finish hydration. Verify preserved page keys, committed generations, hash/geometry parity, missing/corrupt payload behavior, interrupted journal prefix, untrusted inventory migration and tombstones. Compare directory/archive, downloaded/online, cold process/warm re-entry, start/middle landing page, and matching untranslated chapter. Record first reader frame, first page image and first translated overlay separately.

**Existing optimizations that must not be counted as new findings:** downloaded preloading registers an active store, so the later ViewModel status query can use that active authority (`TranslationProgressProjection.kt:285-294`); downloaded entry does not automatically open the store twice. The registry already shares chapter/file opens (`ActiveChapterStoreRegistry.kt:63-88`). Cleaned-stream creation in the download mapper is a lazy factory; disk lookup/open happens only when invoked (`CleanedImageLifecycleController.kt:239-268`). Do not call every page's stream assignment an eager image read.

## R2 — Other reader entry paths can put a status probe before display, then open actively

`ReaderViewModel.loadChapter` awaits `getChapterTranslationStatus` before the UI publishes `viewerChapters` (`:918-944`), then awaits `observeLiveTranslationStore` (`:948`). When a loader has not already registered an active translated store and no valid status cache entry exists, the first operation can create and close a status probe, while the observer later opens an active store (`ReaderTranslationController.kt:1439-1450`). This is a conditional online/local/non-preloaded path, not the general downloaded path.

**Proposal:** one shared opening operation or a truly lightweight status hint; avoid sequential probe-then-active recovery for the same identity. Preserve store admission locks and ownership adoption. A first-frame policy must still address persisted translated display, not merely move original-image flashing earlier.

## L3 — Rebuild mapping is inside an atomic update callback

`updateSuccessState` invokes `MutableStateFlow.update` (`MangaScreenModel.kt:173-178`), and the rebuild callback performs the suspendable full list mapping inside it (`:484-494`). That atomic callback can be reevaluated if state changes concurrently; see [Kotlin's update API contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/update.html). Consequently progress/dialog/queue state updates can cause the expensive mapping and its lookup side effects to run again before a successful update. Actual retry frequency is unmeasured.

**Proposal:** compute storage-derived rows outside the retryable callback, then perform a pure cheap merge into the latest state using current chapter identity and live progress/request snapshots. Preserve existing translation snapshot retention rather than overwriting newer progress with the captured state.

**Validation:** concurrently mutate progress while a lookup is held; verify one mapping pass per accepted source emission and latest progress survives. Check manga changes, canceled list refresh, filtered chapters and row identity.

# Additional independently confirmed concerns and bounds

- Global download-cache/queue and translation/request emissions trigger the combined list rebuild even when the chapter database snapshot did not change (`MangaScreenModel.kt:474-481`). Scope/diff updates are a Medium-complexity follow-up after L1–L3; preserve all live chapter state transitions.
- Queued/requested and durable ERROR/READY_WITH_WARNINGS chapters can start progress collectors that open active stores (`MangaScreenModel.kt:568-587`, `:747-755`). Detail hydration can potentially be deferred until a progress surface needs it. The callback is job-guarded, and active-store sharing avoids claiming that every reconstruction always opens another store.
- Absent artifacts are intentionally not negative-cached because null can also mean permission/recovery failure. Repeated missing-manifest lookup is a source concern, but a short-lived absence cache requires distinguishing definite absence from inaccessible storage and invalidation on translation creation. There is no verified fixed number of SAF calls per missing chapter: positive location caching, absent manga directories and primary/backup paths differ.
- Metadata-only status projection is correctness-sensitive: the status projector uses hydrated page/display state and trusted journal inventory as well as manifest fields. Reading only a manifest header is not equivalent to current durable status authority.
- Bounded parallel snapshot reads are an experiment, not an established speedup or guaranteed semantics-preserving low-risk change. Validate storage provider contention, shared document I/O/thread safety, peak allocations, ordering and recovery consistency. Concurrency alone does not remove whole-chapter startup work.
- Journal replay reads private epochs and can consult artifact snapshots, but current compaction cadence and history size were not audited enough to establish a replay bottleneck. Add isolated replay/hydration spans and counts before proposing compaction changes.

# Measurement and sequencing

Existing reader traces include `vm.init`, `vm.loadChapter`, `download.getPages`, `translation.getForReader`, `vm.translationStatus`, `vm.stateUpdate`, `vm.openActiveStore`, `store.readManifest`, `store.recoverInterrupted`, and `probe.closeAndFlush`. Download loader logs separate translation, directory search, list-building and mapping times (`DownloadPageLoader.kt:107-114`). These can distinguish artifact-open delay from image/layout work without assuming AI inference is involved. They do not yet give a matched chapter-list first-frame/cache ledger or a complete first-translated-frame timeline.

First experiment: compare cold/warm entry for the same downloaded translated chapter against an untranslated one, capturing those existing stages and reader-visible frame milestones. For chapter lists, collect first visible rows, complete badges, cache hits/misses, probe opens and number of chapters/pages read under one rebuild. Do not sum nested traces into a wall budget.

Implement the cheap publication/cache improvements first once authorized; prototype visible-page hydration as a separate high-complexity change with recovery tests. Retain committed display, corruption handling, atomic artifact writes and journal authority. No production optimization has been implemented in this audit.
