# Independent review: reader-entry instrumentation and SAF fast path

## Verdict

**Conditionally approve both commits.** I found no correctness defect that should block device validation. Commit `f0d9f46` removes the repeated SAF probes while preserving the complete, stable page-index contract and lazy image opening. Commit `8a54b60` is behavior-neutral instrumentation apart from small clock/logging overhead. The remaining release gate is device evidence: these commits have not yet demonstrated the target chapter's perceived entry time or navigation/lifecycle behavior on `192.168.100.223:34075`.

## Reviewed evidence

- Actual diffs for `8a54b60` and `f0d9f46`, not only the implementation reports.
- All repository call sites for `DownloadManager.buildPageList` (`rg -n "buildPageList\\("`): the only production caller is updated at `DownloadPageLoader.kt:139`; no stale source call site remains.
- Download publication and validation behavior in `Downloader.kt:878-879`, `Downloader.kt:1049-1055`, `Downloader.kt:1168-1173`, `Downloader.kt:1231-1236`, and supported extension definitions in `ImageUtil.kt:106-114`.
- Reader adapter/holder behavior in `WebtoonAdapter.kt:38-88`, `WebtoonAdapter.kt:133-138`, and `WebtoonPageHolder.kt:289,337-359`.
- Focused test rerun: `:app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.data.download.DownloadedPageIndexTest"` — **passed** (`BUILD SUCCESSFUL`, 2026-09-03).

## Findings

### F1 — Device performance and interaction acceptance is still unverified

- **Severity:** MEDIUM
- **Likelihood:** Certain (evidence gap); likelihood of a remaining user-visible stall is UNKNOWN
- **Classification:** Design/delivery limitation, not a demonstrated code defect
- **Claim status:** VERIFIED

The implementation still waits for one complete `chapterDir.listFiles()` and constructs all lightweight descriptors before `ChapterLoader` can publish the chapter (`DownloadManager.kt:246-276`, `DownloadPageLoader.kt:138-174`, `ChapterLoader.kt:48-62`). It avoids per-child `isFile` and content reads, but does not make the descriptor list progressive. That is the lowest-risk architecture and should be fast, but the target SAF provider's single listing latency is not established. No before/after target-device timing or restored-page/scroll/rotation evidence is present in the supplied reports.

**Confirm/refute:** install the new APK, reopen the exact 260-page chapter at a restored middle/end page at least three times, and capture `[reader_entry]` summaries plus time to first rendered bitmap. Exercise forward beyond the warm window, backward, slider jump, translation/original toggle, rotation/background return, and chapter end. A consistently small `listFilesMs`, `buildListMs`, adapter time, and first-image time with no ERROR/ANR refutes a remaining entry defect.

### F2 — Extension-only filtering intentionally narrows malformed/manual-folder compatibility

- **Severity:** LOW
- **Likelihood:** Low for app-managed downloads; plausible only for manually altered, legacy-foreign, or corrupt chapter directories
- **Classification:** Design limitation
- **Claim status:** VERIFIED

`collectDownloadedPageEntries` accepts a child solely from its cached name and URI (`DownloadManager.kt:56-74`). It therefore (a) admits a directory named like `001.jpg`, and (b) skips a valid image stored under an unknown or extensionless name that the old stream-sniff fallback could recognize. A corrupt supported-extension file was already admitted by the old implementation because `ImageUtil.isImage` short-circuits on the extension, so corrupt-file behavior is not newly regressed.

For downloader-owned chapters, the tradeoff is well supported: normal network pages are renamed to zero-padded names with a supported extension (`Downloader.kt:878-879,1049-1055`), split parts end in `.jpg` (`Downloader.kt:1231-1236`), and the production extension set matches `ImageUtil.ImageType` (`ImageUtil.kt:106-114`). Metadata such as `ComicInfo.xml` is excluded by name. `DirectoryPageLoader` for arbitrary local folders is untouched.

**Confirm/refute:** inspect one legacy sample produced by every supported historic download version, and on device verify that normal, split-tall, and translated downloaded directories all report the expected page count. A deliberately inserted `001.jpg` directory or extensionless valid image confirms the documented limitation; it does not invalidate the app-managed fast path.

### F3 — Focused tests do not exercise the production filename predicate or outer empty-list error

- **Severity:** LOW
- **Likelihood:** Low
- **Classification:** Test coverage limitation
- **Claim status:** VERIFIED

`DownloadedPageIndexTest` injects a test predicate supporting only `jpg/png/webp` (`DownloadedPageIndexTest.kt:26-30,65-69,90-92`), so it does not directly cover production `ImageUtil.isImage`, including `avif/gif/heif/jxl`. It also tests the pure entry/list helpers but not `DownloadManager.buildPageList(null)`, a failed/empty listing's localized error, or the directory-vs-archive dispatch. The production mapping for 260 entries, stable indices, URI preservation, skipped invalid children, and absence of `isFile`/stream calls is covered and passed.

**Confirm/refute:** add instrumentation or Android/Robolectric coverage for all `ImageUtil.ImageType` extensions and the null/empty-list error path; retain an archive smoke test. Device page-count validation also covers the highest-value integration behavior.

## Invariant audit

| Area | Result | Evidence |
|---|---|---|
| App-managed download invariants | **STRONG INFERENCE — preserved** | Downloader emits zero-padded supported-extension names; split pages use stable `__NNN.jpg` suffixes (`Downloader.kt:878-879,1049-1055,1231-1236`). |
| Archive path | **VERIFIED — unchanged** | Archive detection and `ArchivePageLoader` dispatch remain at `DownloadPageLoader.kt:87-90,120-131`; the new directory seam is only used in the `else` branch. |
| Unsupported/corrupt entries | **VERIFIED — bounded limitation** | Unsupported names are skipped; stale URI retrieval is caught per child (`DownloadManager.kt:65-74`). Supported-extension corrupt content continues to fail lazily, as before. |
| URI lifetime | **VERIFIED — no new lifetime regression found** | Child URI is copied into immutable `DownloadedPageEntry`, then `Page`, then captured by the lazy reader stream (`DownloadManager.kt:34-37,69,86-90`; `DownloadPageLoader.kt:142-159`). No child `UniFile` handle is retained. Persisted SAF access remains the provider's existing responsibility. |
| Ordering/index stability | **VERIFIED — preserved** | Same lexicographic filename sort and zero-based `mapIndexed` semantics (`DownloadManager.kt:86-90`); 260-entry focused test passes. |
| Empty list | **VERIFIED — behavior preserved** | Missing handle or zero accepted entries throws the existing localized page-list-empty error (`DownloadManager.kt:241,268-270`). Webtoon therefore should not receive an empty downloaded-directory list. |
| Logging overhead/errors | **VERIFIED — acceptable** | Aggregate INFO logs only; no per-page INFO output. Optimized path has two clock reads around each cached name read and a single final message (`DownloadManager.kt:58-64,280-290`). Exceptions are rethrown after recording their class. This is minor O(n) CPU work, not Binder I/O. |
| Android 8 compatibility | **VERIFIED** | `SystemClock.elapsedRealtimeNanos()` is available well before API 26; no newer platform API was introduced. |
| Normal manga behavior | **STRONG INFERENCE — preserved for app downloads** | Complete descriptors are still published, originals remain unopened until holder binding, and arbitrary local directories are untouched (`DownloadPageLoader.kt:142-159`; `WebtoonAdapter.kt:60-64,133-138`; `WebtoonPageHolder.kt:289,337-359`). |
| Removed signature callers | **VERIFIED — none remain in repository source** | Repository-wide search found only the new declaration and updated call at `DownloadPageLoader.kt:139`. |
| Viewer list handling | **VERIFIED — no eager 260-image work found** | Webtoon copies references and runs `DiffUtil` (`WebtoonAdapter.kt:38-88`); actual page load starts on bound holders (`WebtoonPageHolder.kt:289,337-359`). `WebtoonViewer.setChapters` now measures the synchronous adapter/move/visibility costs (`WebtoonViewer.kt:261-300`). |

## Recommendation

Proceed to target-device validation without changing production code first. If the aggregate logs show the single `listFiles()` or main-thread adapter step is still perceptible, use that measurement to select the next narrow optimization. Do not replace the complete stable page list with a three-element mutable window unless a broader paged-list contract is designed and tested across both viewers, restoration, progress, transitions, and translation identity.
