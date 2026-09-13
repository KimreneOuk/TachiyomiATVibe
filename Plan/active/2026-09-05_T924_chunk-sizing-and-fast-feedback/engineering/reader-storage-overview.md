# Reader, storage and fragmented resume overview

Architecture investigation only; current HEAD `adbe643`. No source changes or tests executed. Paths below are relative to `app/src/main/java/`.

## 1. Sources and preparation — VERIFIED

Batch consumes finalized local chapter images, not the reader's HTTP stream. `eu/kanade/translation/ChapterTranslator.kt:583-605` resolves the downloaded chapter and emits an explicit failure if missing; `:615-635` builds natural-order archive/directory stream closures and indexes. Archive input shares an ArchiveReader. `:642-674` preregisters page keys with source-count evidence, creates the tracker and rebuilds its progress from persisted state before work starts. `pipeline/batch/BatchResumePlanner.kt:91-108` builds per-stage plans using stored state, current source/configuration fingerprints, durable failures and current glossary version. Thus reading position is not the resume cursor.

A completed download is selected on a new chapter load before HTTP (`eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt:87-115`). Directory pages open original local URIs and retain a separate translated stream (`DownloadPageLoader.kt:151-194,207-224`). Undownloaded HTTP chapters load source/cache page lists and fetch image bytes only when the image cache misses (`HttpPageLoader.kt:60-79,173-189`). Already loaded chapters return early (`ChapterLoader.kt:37-39,79-80`): do not promise an already open HTTP loader instantly switches to download storage when downloading finishes.

## 2. Download interruption — VERIFIED

Downloader uses a temporary chapter directory (`data/download/Downloader.kt:460-461`). Each resumed page removes its incomplete `.tmp`, then prefers an existing completed image, then reader image cache, then network (`:878-951`). This is page-level reuse, not HTTP byte-range continuation. Final validation requires all pages ready and matching on-disk page count (`:1506-1510`); failed validation records download failure and returns before translation handoff (`:594-616`). Only after metadata/archive-or-rename/cache publication and DOWNLOADED status does handoff rekey artifacts and start requested translation (`:657-759,782-818`). Handoff failure has separate translation failure semantics and must not relabel a successfully downloaded chapter as a download failure (`:764-768,842-855`).

An interrupted temporary chapter is not a successfully finalized local chapter; reading normally remains HTTP/cache until completion. Caveat: reader's downloaded check is path-existence based, not a fresh source-count/image-integrity audit (`DownloadCache.kt:150-159`; `DownloadProvider.kt:82-86`). A manually truncated/deleted finalized chapter may load fewer pages or report file-open errors, and is not proven to transparently repair or fall back to network. `DownloadPageLoader.kt:173-181` explicitly throws IOException for inaccessible local source URI.

## 3. Shared translation artifacts and immediate display — VERIFIED

Batch first resolves the shared active chapter store (`translation/ChapterTranslator.kt:545-547`); reader obtains that same registry-backed store (`translation/TranslationManager.kt:1308-1310,1321-1326,1371-1389`). Store holds candidate work separately from committed display. Reader's page flow subscribes to `store.display`, attaches available cleaned streams, and requests translation refresh when translation is enabled (`ui/reader/ReaderViewModel.kt:2863-2917`). Therefore a rendered page can appear as it commits; no whole-chapter completion wait is required. Precise promise: committed/display-ready pages appear, not raw OCR or mere API response. Holder binding and source readiness still matter.

Writes share per-page leases. Different origins are denied while another owns the page; the sole cross-origin eviction is MANUAL over AUTO, not MANUAL over BATCH (`translation/store/PageStageLeaseTable.kt:75-99`). Batch denied OCR leases records deferral rather than racing writes (`pipeline/batch/BatchLaneWorkers.kt:824-846`). Generation/version/dependency/lease fencing rejects stale results (`ChapterTranslationStore.kt:826-861`). This is coordinated coexistence, not unrestricted simultaneous work on a page.

Batch coroutine ownership is outside ReaderViewModel (`translation/ChapterTranslator.kt:263-269,371`); live reading does not itself require batch to stop. Background Android service/process policy is a separate investigation; this report does not guarantee survival through process kill.

## 4. Fragmented pages — VERIFIED behavior with narrower evidence caveat

For 1,2 complete; 3 missing; 4 complete; 5,6 missing, all pages are traversed naturally and independent stages can reuse artifacts. Seeded rolling context advances through 1,2 only; missing 3 prevents 4 from leaking backward (`pipeline/batch/BatchContextFrontier.kt:34-44,57-90`; `BatchResumePlanner.kt:123-150`). Chapter planning may mark later pages WAIT_FOR_DEPENDENCY after the gap (`model/PageWorkPlanner.kt:114-149`). AI lane explicitly recognizes completed pages after such a gap using READY/SKIPPED plus translation fingerprint match, skips payment and records them into frontier (`BatchLaneWorkers.kt:1322-1329,1345-1358,1377-1389`). This check is narrower than complete source/OCR/glossary reuse evidence; do not describe every such skip as a complete revalidation of all provenance.

Pages 3,5,6 may share an envelope. Do not describe it as guaranteed separate provider calls for 3 then 5,6; retained page4 cannot become predecessor context for that same pending envelope while 3 is unresolved. Once 3 resolves, the frontier can advance through retained4 and update subsequent-envelope context. PARTIAL is displayable but cannot advance the frontier; terminal non-textless failure fences later AI (`BatchContextFrontier.kt:57-93,106-108`).

## 5. Significant streaming-to-download reuse caveat — VERIFIED guards, STRONG INFERENCE user impact

HTTP page keys are URL-derived (`HttpPageLoader.kt:74-79`); downloaded page keys are filenames (`DownloadPageLoader.kt:184`). Completion invokes rekey by page index (`Downloader.kt:792-798`). However `TranslationManager.kt:1267-1281` returns unless online/disk list sizes match AND store page count equals online chapter page count. `ChapterTranslationStore.kt:1328` repeats the full-count guard. A repository search found chapter preregistration only in batch (`ChapterTranslator.kt:651`), not ordinary reader loading. Therefore a sparse store created by translating only pages1,2,4 while streaming is at material risk of failing migration to filenames after downloading; batch then looks for new keys and can redo work. This needs a focused regression test and explicit sparse identity migration design before promising reader-to-batch reuse universally. It is separate from force-path OCR/inpaint coupling.

Successful rekey also calls global batch-job cancellation/join before page-level cancellation and generation change (`TranslationManager.kt:1283-1291`); unrelated active chapter behavior needs review before promising an unrelated download cannot interrupt an active batch. Already open HTTP holders capture an earlier pageKey in their display flow (`ReaderViewModel.kt:2849,2893-2896`), so active-reader rekey refresh is another specific test requirement. Tall-image splitting can change page mapping cardinality: mismatched cardinalities explicitly cause rekey to return; do not claim one-to-one reuse across transformed source page sets.

## Recommended architecture acceptance coverage

- Sparse reader translation -> completed download -> batch reuse, including active reader and tall split pages.
- Already-downloaded fragmented completion with matching and stale fingerprints/glossary.
- Pages3,5,6 in same envelope with retained4; no future context leakage.
- First committed render refreshes pager and webtoon while original bytes remain accessible.
- Batch/manual/auto collision, cancellation while lease held, and stale response rejection.
- Offline mid-download then retry preserves completed images; incomplete `.tmp` is redownloaded; batch starts only after finalization.
- Existing finalized chapter with missing/corrupt images and SAF permission loss gives honest incomplete/error state.
- Completion rekey during a different active batch; no accidental lost queue/resume.

Recommendation: retain one shared artifact authority, add sparse identity migration to the reuse design, and state first-result visibility in committed-page terms rather than whole-chunk completion.
