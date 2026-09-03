# Technical investigation: reader entry path

## Scope and evidence

This is a source-level investigation of the downloaded-directory reader path at `8e8bcd1`. No production source was changed. The connected device was visible at `192.168.100.223:34075`, but the current process log buffer contained none of the relevant reader timing messages, so the remaining device latency is **UNKNOWN** until instrumentation is deployed and the target chapter is reopened.

## Actual entry sequence

1. **VERIFIED** — `ReaderViewModel.init()` runs on an IO context and calls its private `loadChapter()`; that calls `ChapterLoader.loadChapter()` before publishing `ViewerChapters` (`ReaderViewModel.kt:950-1014`).
2. **VERIFIED** — `ChapterLoader` does not publish `ReaderChapter.State.Loaded` until `PageLoader.getPages()` returns. It assigns `last_page_read` to `requestedPage` only after that call (`ChapterLoader.kt:35-64`). Therefore all downloaded page enumeration remains on the entry critical path even though it is off Main.
3. **VERIFIED** — `DownloadPageLoader.getPages()` resolves the chapter path, opens the translation store, tests whether the path is an archive, and for a directory calls `DownloadManager.buildPageList()` (`DownloadPageLoader.kt:42-62`). `buildPageList()` resolves the same source/manga/chapter path a second time (`DownloadManager.kt:161-163`).
4. **VERIFIED** — directory enumeration calls `listFiles()`, then `file.isFile` for every child, reads the name, optionally sniffs content, sorts, and creates a `Page` for every image (`DownloadManager.kt:172-203`). The resulting list is mapped to stable `ReaderPage` objects with lazy original-stream closures (`DownloadPageLoader.kt:85-115`).
5. **VERIFIED** — after `ViewerChapters` is published, `ReaderActivity` removes the spinner and synchronously invokes the selected viewer's `setChapters()` on Main (`ReaderActivity.kt:788-795`).
6. **VERIFIED** — `WebtoonAdapter` copies all current `ReaderPage` references into `newItems` and runs `DiffUtil.calculateDiff()` (`WebtoonAdapter.kt:38-88`). It does not create 260 image holders: `onCreateViewHolder`/`onBindViewHolder` are RecyclerView callbacks (`WebtoonAdapter.kt:116-140`), so only the laid-out/cache window binds and decodes pages.
7. **VERIFIED** — first layout selects `min(requestedPage, lastIndex)`, moves to the corresponding stable `ReaderPage`, then makes the recycler visible (`WebtoonViewer.kt:259-267`). Pager mode has the same complete-list/landing-page contract (`PagerViewer.kt:277-287`).

## SAF query finding

- **VERIFIED** — the bundled UniFile bytecode (`unifile-e0def6b3dc`, `TreeDocumentFile`) implements `isFile()` with `DocumentsContractApi19.isFile(context, uri)`, i.e. a provider query for each page.
- **CONTRADICTION** — names returned by `TreeDocumentFile.listFiles()` are cached in each child object. `listFiles()` calls `DocumentsContractApi21.listFilesNamed()`, then constructs each `TreeDocumentFile` with both URI and name; `getName()` returns the cached `mName`. Thus the current code does not issue a fresh name query for every listed child. The avoidable dominant loop is at least one synchronous `isFile` provider query per child, not necessarily the claimed two metadata queries per child.
- **VERIFIED** — `TreeDocumentFile.listFiles()` itself first calls `isDirectory()` (one provider query) and then performs the child listing. `DownloadPageLoader` also calls `chapterPath.isFile`; `DownloadManager` then resolves the same hierarchy again. These are smaller but avoidable fixed query costs.
- **STRONG INFERENCE** — `ImageUtil.isImage(name) { openInputStream() }` does not open a stream for normal downloader-produced images because it accepts a known extension first (`ImageUtil.kt:39-43`). Downloader publication always appends a detected supported extension and falls back to `jpg`; names are zero-padded, and split parts use a `.jpg` suffix (`Downloader.kt:878-879, 1050-1052, 1168-1173, 1233`). Header sniffing is therefore not normally another 260 reads for this target, although it can occur for an unknown-extension foreign entry.
- **STRONG INFERENCE** — eliminating per-child `isFile` and duplicate path resolution should collapse the downloaded-directory cost from O(page count) Binder round trips to one child listing plus in-memory filtering/sorting. Device instrumentation must validate the magnitude.

## Why a partial `List<ReaderPage>` is unsafe

The present API contract is a complete, stable, indexed list, not an appendable page stream:

- **VERIFIED** — both viewers select the restored page by indexing `chapter.pages` and use its total size for end-of-chapter preloading (`WebtoonViewer.kt:226-266`, `PagerViewer.kt:226-286`).
- **VERIFIED** — adapters copy the complete current list; pager mode also inserts split-page objects relative to stable indexes and can reverse the whole list for R2L (`PagerViewerAdapter.kt:47-126`).
- **VERIFIED** — reader progress, slider jumps, next-download threshold, warm-window eviction, live translation reconciliation, and auto-translation use `size`, `lastIndex`, identity lookup, indexed access, or full iteration (`ReaderViewModel.kt:733-812, 1178-1297, 1484-1485, 2705-2725`).
- **VERIFIED** — current-page restoration uses a stable zero-based `chapterPageIndex`/`requestedPage`; the viewer indexes into the complete list. Replacing a 260-page chapter with a three-element window would misreport page count, make a restored index such as 180 invalid, trigger end-of-chapter behavior early, and break navigation/translation index identity.
- **STRONG INFERENCE** — asynchronously appending to the list would add Main-thread adapter updates and races with viewer selection, RecyclerView diffing, chapter recycling, and translation observers. A fully new paged-list abstraction could solve those issues but is a broad cross-component redesign with high regression risk and is not necessary if descriptors can be obtained in one provider query.

## Existing laziness and bounded work

- **VERIFIED** — `ReaderPage.originalStream` is only a closure around `ContentResolver.openInputStream`; directory entry does not decode 260 originals (`DownloadPageLoader.kt:91-104`).
- **VERIFIED** — Webtoon image work starts from holder binding/status collection, so the active/visible RecyclerView window is naturally prioritized (`WebtoonPageHolder.bind()` and `loadPageAndProcessStatus()`).
- **VERIFIED** — translated-image factories are lazy: `getCleanedImageStream()` returns a lambda; the provider lookup and stream open occur only when invoked (`CleanedImageLifecycleController.kt:182-213`).
- **VERIFIED** — translation snapshots/streams are bounded by `ReaderPageWarmWindow`: pager attach/eviction radii are 2/5 and webtoon radii 4/10; far streams are nulled (`ReaderPageWarmWindow.kt`, `ReaderViewModel.kt:763-817`). This is wider than literal `n+1,n+2` for webtoon but remains bounded and protects rapid scrolling.
- **STRONG INFERENCE** — retaining 260 small `ReaderPage` descriptors is acceptable on the stated 6 GB devices; decoded bitmaps and open streams, not descriptor objects, dominate memory. A complete descriptor index plus lazy holder I/O is the appropriate boundary.

## Current-page restoration nuance

- **VERIFIED** — `ChapterLoader` computes `requestedPage = last_page_read` only after `getPages()` (`ChapterLoader.kt:47-64`), so the loader cannot currently use the landing index to prioritize any entry work.
- **VERIFIED** — saved-state restoration is applied by a `state` collector after the current chapter is published (`ReaderViewModel.kt:576-590`), while initial viewer selection consumes `requestedPage` when it receives `ViewerChapters`.
- **STRONG INFERENCE** — existing event ordering usually preserves restoration, but it is unsuitable as a prerequisite for a loader-level current-page window. If future work truly needs the landing index before enumeration, `ReaderViewModel` must set `chapter.requestedPage` before `ChapterLoader.loadChapter()`, and `ChapterLoader` must stop overwriting an explicit request after loading. That change needs dedicated restoration tests and should not be coupled to the low-risk SAF fix unless measurement requires it.

## Risks and failure semantics

- **Normal manga** — skipping `isFile` is safe for app-managed downloads when filtering strictly by supported image extension because the downloader publishes only extension-bearing image pages plus non-image metadata. A manually inserted directory named `001.jpg` would become a page descriptor and fail lazily on open; this edge case should be documented/tested. Do not change `DirectoryPageLoader` for arbitrary local-source directories in the same patch.
- **Order and numbering** — preserve the exact existing lexicographic filename sort and `mapIndexed` zero-based index. Zero-padding and split suffixes make this canonical for downloads. Do not order by translation-map insertion order.
- **Errors** — preserve null/throw handling for `listFiles`, skip null names, and preserve the empty-list error. A stale individual URI should fail in the page holder via the existing explicit `IOException`, not abort entry for all other pages.
- **Concurrency/lifecycle** — publish one immutable descriptor list. Avoid a background job that mutates it after `ReaderChapter.State.Loaded`; such a job would need cancellation on `PageLoader.recycle()` and synchronization with both adapters and translation collectors.
- **Memory** — do not pre-open streams or decode headers. Page descriptors may contain URI/name and lazy factories only. Existing holder/viewer scopes already cancel page jobs on recycle/destroy.

