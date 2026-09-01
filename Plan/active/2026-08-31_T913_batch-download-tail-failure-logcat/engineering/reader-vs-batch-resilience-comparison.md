# T913 technical comparison — reader resilience versus batch pre-download

## Executive conclusion

Baseline: current HEAD `1a9fd886b5b02e00c1dd187ce122608a6411400c`
(the diagnostic-only T913 commit). This comparison is read-only; no product code
was changed.

**STRONG INFERENCE:** a highly repeatable “all but the final one or two pages”
failure across many chapters is more consistent with a deterministic boundary
than with independent random network loss. The leading boundaries are:

1. the last one or two *unsettled workers* exhaust retries while the other page
   continues, especially under the downloader's fixed concurrency of two;
2. a source-specific terminal page/URL rule consistently rejects structurally
   special end pages;
3. page bytes are written but a temporary-file rename returns `false`, leaving
   one or two `.tmp` files: the downloader marks those pages `READY`, then its
   exact on-disk count rejects the chapter; or
4. SAF/directory/CBZ publication or exact-count logic rejects an otherwise
   usable set of pages.

The third boundary deserves special attention. `UniFile.renameTo` results are
ignored for the per-page network path, cache-copy path, final directory rename,
and CBZ rename at
`app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt:857-858`,
`:901-910`, `:571-581`, and `:1111-1118`. A false return is not an exception.
The page is still marked `READY` at `:773-779`, but validation excludes `.tmp`
files and requires exact equality at `:1039-1092`. That produces precisely
`ready=N, on_disk=N-1/N-2` with no page throwable.

The reader's apparent success does **not** prove the same full-chapter operation
is healthy. Online reading is page-local, uses an app-internal atomic LRU cache,
does not publish a durable chapter through SAF/CBZ, and never requires every page
to succeed before showing or translating one page. The batch path deliberately
has an all-pages durable-download gate before translation admission.

Recommendation: use the new `BatchDownloadTrace` capture to select the boundary
before changing behavior. If it reports `ready=N, on_disk<N`, fix checked page
publication/rename first. If it reports page HTTP/source failures, run a
translation-scoped concurrency-one and URL-refresh experiment. Do not replace
the downloader with the reader loader or admit a whole batch from the evictable
reader cache.

## Why “the final one or two” is a deterministic signature

### The progress tail is not necessarily the literal last page

The downloader consumes source-order pages with `flatMapMerge(concurrency = 2)`
at `Downloader.kt:473-516`. Each network page gets one initial attempt plus
three retries with 2/4/8-second delays at `:821-891`. A failing request occupies
one of the two lanes while successful pages settle. Consequently, the UI can
end with one or two remaining pages even when the bad page index is earlier in
the chapter. This is a deterministic consequence of a two-lane queue plus slow
retry exhaustion, not evidence by itself that the source's literal final page
is bad.

The new trace distinguishes these cases:

- repeated `page_attempt_failed` at the same index and
  `validation ready<N` means acquisition failure;
- no page failure plus `validation ready=N on_disk<N` means page-publication,
  SAF enumeration, split-count, or ignored-rename failure;
- successful validation followed by `finalization ... failed` means metadata,
  archive, rename, or cache publication;
- `download_terminal state=downloaded` followed by `handoff ... failed` means
  rekey/admission, not download.

### Why random network loss ranks lower

- Independent transient loss should vary in page count/index and should often
  recover within the existing four attempts. A consistent one/two-page deficit
  over many chapters instead implies a stable source rule, concurrency policy,
  filename/provider behavior, or completion invariant.
- The batch path already uses the same `ChapterCache` for image reuse before
  going to the network (`Downloader.kt:751-769`). If the Director has actually
  opened the same tail pages in the reader and those exact URL-keyed bytes remain
  cached, batch should take the cache-copy branch. Failure after that points
  away from HTTP and toward cache-to-SAF copy, rename, splitting, or validation.
- This remains **UNKNOWN** until one correlated trace is captured. “Reader
  translation works” may mean only the currently visible/window pages worked;
  it does not establish that every page in the chapter was fetched.

## Side-by-side behavior

| Dimension | Reader manual / rolling auto | Batch pre-translation download | Consequence |
| --- | --- | --- | --- |
| Page list | `HttpPageLoader` first tries cached list, then source (`ui/reader/loader/HttpPageLoader.kt:59-81`). | Uses an existing in-memory list or calls the source directly (`Downloader.kt:443-455`). | Reader can benefit from prior list state; importing it can also preserve stale signed URLs. |
| Scheduling | One priority queue consumer, current page priority 1/2 and four-page prefetch priority 0 (`HttpPageLoader.kt:36-54,100-125,148-167`). | Two page flows concurrently for the whole chapter (`Downloader.kt:473-516`). | A concurrency-sensitive source may succeed in reader and fail in batch. |
| Image URL | Resolves only when URL is empty (`HttpPageLoader.kt:175-181`). | Also resolves only when empty (`Downloader.kt:478-507`). | Reader has no URL-refresh behavior to copy; both can retain a non-null expired URL. |
| Transport retry | No in-call retry. An `ERROR` page is requeued on a later `loadPage`, or explicitly by viewer retry (`HttpPageLoader.kt:87-125,175-197`). | Four immediate attempts with fixed backoff (`Downloader.kt:821-891`). | “Copy reader retry” is not a stronger network policy; it is user/lifecycle-driven retry. |
| Cache | Atomic `DiskLruCache` editor/commit; response is closed and uncommitted edits abort (`data/cache/ChapterCache.kt:142-160`). Manual fallback uses the same cache (`ReaderViewModel.kt:2238-2265`). | Reuses cache bytes but copies them to chapter storage through a `.tmp` plus unchecked rename (`Downloader.kt:751-769,901-910`). | Same source bytes can work in reader yet fail at cache-to-SAF publication. |
| Fault isolation | A loader exception marks only that page `ERROR` (`HttpPageLoader.kt:175-197`). Rolling auto defers a missing stream and continues scanning later pages (`RollingAutoCoordinator.kt:563-579`); prepare/translate failures become per-slot failures (`:617-659,338-458`). | Page exceptions are isolated during acquisition, but after all flows settle a single failed/missing page makes the chapter `ERROR` (`Downloader.kt:520-540,1039-1092`). | Reader “works” means partial/page-local availability; batch requires complete durable input. |
| Source readiness | Loader publishes after `originalStream` is assigned (`HttpPageLoader.kt:183-191`); a weak, generation-gated callback reconciles auto (`ReaderViewModel.kt:1248-1308`). | Handoff occurs only after exact chapter validation and finalization (`Downloader.kt:520-632`). | Reader can defer and wake one slot; batch has no partially-ready admission state. |
| Persistence | Online pages live in a 100 MiB evictable app cache (`ChapterCache.kt:33-39,201-208`). | Chapter files, optional CBZ, metadata, cache index, then `DOWNLOADED` (`Downloader.kt:543-602,1103-1118`). | Reader cache is not durable batch ownership and cannot replace chapter publication. |
| SAF / CBZ | Online reader cache does not use the selected download SAF tree or CBZ. Downloaded reader opens only an already-published directory/archive (`ChapterLoader.kt:78-103`; `DownloadPageLoader.kt:42-63`). | Creates `_tmp`, writes/renames every image, may split, writes metadata, archives/renames, and enumerates provider files. | Reader success does not exercise the likely storage boundary at all. |
| Completion | Visible page can render/translate independently. Missing streams are `SourceUnavailable`, not chapter failure; this recovery is tested at `RollingAutoCoordinatorTest.kt:405-434`. | Requires `READY == expected` and `onDisk == expected` before admission (`Downloader.kt:1039-1092`). | The two paths answer different success questions. |
| Tall images | Online reader stores original bytes; no download-time split. | Split defaults on, writes `NNN__001.jpg` pieces, deletes the original on success, and keeps original on split failure (`Downloader.kt:926-953`; `core/common/.../ImageUtil.kt:214-269`). Validation special-cases split pieces (`Downloader.kt:1056-1066`). | Split/provider/count behavior is a deterministic batch-only variable; A/B it in capture. |
| Translation admission | Manual can use `originalStream`, downloaded file, or lazy HTTP cache stream (`ReaderViewModel.kt:2160-2235`). Rolling auto resolves only current/ahead streams and defers unavailable slots (`ReaderAutoTranslationPageResolver.kt:145-167`). | Downloader must reach `DOWNLOADED`, then rekey and call the generation-fenced coordinator (`Downloader.kt:632-716`; `translation/manager/TranslationRequestCoordinator.kt:499-526`). Batch worker then requires a published chapter path (`ChapterTranslator.kt:572-623`). | Starting batch before full publication is an architectural change, not a retry tweak. |
| Lifecycle | Reader resolver generations invalidate stream handles (`ReaderAutoTranslationPageResolver.kt:29-123,169-203`); background stops work and clears streams (`ReaderViewModel.kt:2420-2468`). | Download queue/partial directory is expected to survive reader absence; translation request is durable and generation-fenced. | Importing reader cancellation ownership would make background batch less reliable. |
| Memory | Visible/ahead rolling window, serialized native lane, and memory-gated prefetch (`TranslationScheduler.kt:126-203`; `RollingAutoCoordinator.kt:529-659`). | Download holds page metadata and at most two network transfers; later batch translation holds one page bitmap by design (`BatchChapterTranslator.kt:154-180`). | Rolling boundedness is reusable; retaining all reader streams/bytes is not. |

## Reader mechanisms that are safe to replicate

### 1. Checked atomic page publication — preferred, evidence-gated

The useful reader property is not its LRU cache itself; it is that `READY` is
published only after the cache editor commits (`ChapterCache.kt:142-160`). The
batch equivalent should require every storage operation to prove success:

- treat `renameTo(...) == false` as a page-stage failure;
- verify the final non-`.tmp` file exists and can be enumerated/opened before
  setting `Page.State.READY`;
- on retry, remove only the failed temporary artifact and preserve previously
  committed pages;
- likewise require the directory/CBZ final rename to succeed before publishing
  `DOWNLOADED`.

**Confidence:** high if trace is `ready=N, on_disk<N`; medium before capture.
**Risk:** low-to-medium (touches shared downloader storage semantics, so must be
fault-tested for internal and SAF storage, CBZ on/off). **Memory:** negligible.

### 2. Page-local deferred/retry state with an explicit wakeup

Rolling auto represents missing input as `Deferred(SourceUnavailable)`, keeps
scanning other desired pages, and wakes when the loader signals stream readiness
(`RollingAutoCoordinator.kt:563-579`; `ReaderViewModel.kt:1248-1308`). The same
concept can be replicated in a future translation-specific acquisition layer:
one page is waiting/retryable, other pages remain usable, and a source/cache
event reopens just that page.

Do not silently call a partial chapter complete. The durable request must still
show the missing page and either retry it or finish with an explicit partial
failure policy.

**Confidence:** high as a resilience pattern; low that it alone fixes this
incident. **Risk:** medium-high because it changes the all-pages admission
contract. **Memory:** low if it stores only compact page states and disk-backed
artifacts, not streams/bitmaps.

### 3. Generation/lifecycle fencing and deduplication

The reader resolver fences stale callbacks by identity/generation and clears
issued streams before loader recycling (`ReaderAutoTranslationPageResolver.kt:29-123,169-203`).
The batch request already has request-generation fences at its download/handoff
boundary. If partial page acquisition is introduced, extend that same generation
to page-ready callbacks so a late source response cannot satisfy a cancelled or
newer batch. This is safe and consistent with T911.

**Confidence:** high. **Risk:** low. **Memory:** one compact token/state per
active page, bounded to the active chapter.

### 4. Bounded prioritized acquisition, only if source evidence supports it

Reader HTTP loads are serialized and prioritize the visible page over four
ahead pages (`HttpPageLoader.kt:36-54,100-125,148-167`). A translation-driven
download may safely test concurrency `1` versus `2`, or use a source-scoped
politeness limit, without changing native/translation concurrency. Apply it only
to translation-attached chapters unless a generic downloader defect is proven.

**Confidence:** medium only when trace shows repeat HTTP failures under the two
lanes. **Risk:** low for correctness, medium for download latency. **Memory:**
slightly lower than current.

### 5. Page-scoped failure continuation in translation

Rolling auto catches and records a single page prepare/translation failure and
continues (`RollingAutoCoordinator.kt:617-659,338-458`); the bounded stale-handoff
retry is proven in `RollingAutoCoordinatorTest.kt:532-555`. Batch translation
already has per-page durable failures and reconciliation after it has inputs
(`BatchChapterTranslator.kt:555-638`). Reuse the outcome vocabulary and retry
budget if acquisition and translation are ever joined; do not create a second
unbounded retry system.

**Confidence:** high as shared semantics. **Risk:** medium because chapter-level
completion UX must define partial failure. **Memory:** negligible.

## Behaviors that must not be imported directly

- **Do not reuse `HttpPageLoader` as the batch downloader.** `loadPage` suspends
  indefinitely until caller cancellation (`HttpPageLoader.kt:106-115`), its
  priority is driven by reader holders, and its scope is recycled with the
  reader. Batch ownership must survive the reader not being open.
- **Do not treat `ChapterCache` as durable batch storage.** It is a shared
  100 MiB LRU (`ChapterCache.kt:33-39,201-208`) and entries may be evicted. It
  cannot support process recovery, downloaded-chapter truth, or later batch
  resume by itself.
- **Do not import the reader's cached page list blindly.** It can contain
  non-null expired/signed image URLs, and neither reader nor downloader refreshes
  a non-null URL after an HTTP failure. Cache use needs age/source validation.
- **Do not copy reader partial-file tolerance into download completion.** The
  downloaded reader skips unreadable files and displays the readable remainder
  (`DownloadManager.kt:161-203`); a downloader must not call an N-page chapter
  complete after silently dropping entries.
- **Do not copy reader background cancellation.** Reader backgrounding stops
  work and clears streams (`ReaderViewModel.kt:2420-2468`); batch pre-translation
  is explicitly background/durable work.
- **Do not start the current whole-chapter batch worker from reader stream
  closures.** Batch fingerprint preflight fans over all ordered streams
  (`BatchChapterTranslator.kt:298-309`), archive streams and page artifacts have
  different lifetimes, and the worker expects a durable chapter path
  (`ChapterTranslator.kt:572-623`). A stream-first design needs a dedicated,
  bounded acquisition coordinator and persistence contract.
- **Do not assume manual lazy HTTP is more resilient.** It performs one
  `source.getImage` and cache commit with no retry or URL refresh
  (`ReaderViewModel.kt:2238-2265`); it merely avoids full-chapter gating.

## Ranked repair boundaries

1. **Observe, then fix checked publication at the exact failing stage.** Use the
   T913 trace already shipped. If `ready=N/on_disk<N`, enforce rename results and
   final-file verification; if finalization fails, fix only that SAF/CBZ stage.
   Highest confidence, smallest behavior change, no material memory cost.
2. **If trace shows source/HTTP failure, reproduce with translation-scoped
   concurrency one and classify status/error.** Add URL refresh only for a
   proven source contract/status (for example expired signed URL), with the same
   bounded attempt budget. Medium confidence/risk; lower memory, slower download.
3. **Add page-local deferred acquisition/retry while retaining truthful partial
   state.** This imports rolling auto's best resilience property and can let
   usable pages prepare while a tail page waits, but it requires a revised
   durable admission/completion contract. Medium architectural confidence,
   medium-high product/storage risk, low bounded memory if disk-backed.
4. **Build a stream-first batch mode that does not require a downloaded chapter.**
   This directly changes the product premise, but is not a safe patch: it needs
   page-list/version persistence, source/cache expiry rules, generation-fenced
   callbacks, background policy, cleanup, and partial terminal UX. High effort
   and risk; memory is acceptable only with a rolling window and immediate disk
   persistence.
5. **Replace batch acquisition with the reader loader/cache wholesale.** Reject.
   Its lifecycle, evictable cache, UI priority, and partial-success definition
   conflict with durable batch ownership.

## Evidence and tests required before repair selection

### One device matrix

Capture `BatchDownloadTrace` for the same source/chapter under:

1. current storage provider, CBZ setting, and split-tall setting;
2. internal app storage versus the current SAF tree;
3. CBZ off versus on;
4. split-tall off versus on;
5. reader-cache cold versus after opening the exact reported tail pages;
6. if HTTP failures are seen, a diagnostic translation-only concurrency of one
   versus current two.

Record only source ID, API level, provider type, toggles, chapter/page counts,
and tagged events—no URL, title, content, headers, or throwable messages.

### Focused automated tests

- Fake `UniFile.renameTo == false` for network page, cache-copy page, directory,
  and CBZ publication. Assert the page/chapter never reaches `READY/DOWNLOADED`
  and the prior committed files remain recoverable.
- Fake `listFiles` returning `null`, incomplete arrays, delayed visibility, and
  extra split parts; assert the exact validation classification.
- Fake source where only the two active lanes receive 429/403/expired-URL
  failures. Compare concurrency one/two and a bounded URL refresh without
  changing retry delays/count.
- Cache-prewarm test proving batch cache-copy either publishes a verified final
  file or emits a storage failure—never `READY` with only `.tmp` present.
- Directory/CBZ × internal/SAF × split/no-split fault tests, including false
  Boolean rename (not only thrown exceptions).
- If partial admission is authorized: coordinator tests mirroring
  `RollingAutoCoordinatorTest.kt:405-434` for missing-input defer/reconcile,
  plus process-death, cancel/re-request generation, bounded retention, and a
  terminal partial-failure projection.

## Decision rule from the first trace

- `ready < expected` + `page_attempt_failed`: source/network/page acquisition;
  investigate status, URL freshness, and concurrency.
- `ready = expected` + `on_disk < expected`: deterministic storage publication
  or enumeration; prioritize checked rename/file verification.
- validation success + finalization failure: metadata/SAF/CBZ boundary.
- download `DOWNLOADED` + handoff failure: rekey/admission/coordinator boundary;
  do not change page fetching.
- all events succeed but the UI still says one/two pages: projection-only issue;
  do not change downloader or reader behavior.

