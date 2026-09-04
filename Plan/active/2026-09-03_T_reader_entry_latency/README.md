# Reader entry latency optimization

## Objective

Make a 260-page translated chapter enter the reader without perceptible blocking. The active page must become available first, with a small `n + 1`, `n + 2` lookahead; remaining pages must be resolved or hydrated lazily as navigation/scrolling requires them.

## Known baseline

- Branch: `optimize_reader_lazy_loading`
- Existing commits: `e487259`, `de943a9`, `5584e0a`
- Translation-store open improved from 58,774 ms to about 499 ms.
- Suspected remaining bottleneck: `DownloadManager.buildPageList()` synchronously performs repeated SAF metadata queries for every chapter file.
- Also inspect `ChapterLoader`, `ReaderViewModel`, and `WebtoonViewer.setChapters()` for eager work on all 260 page holders.

## Required work

1. Add high-resolution `LogPriority.INFO` timing around `DownloadPageLoader.getPages()`, `DownloadManager.buildPageList()`, and `WebtoonViewer.setChapters()`.
2. Measure the real remaining stall on device `192.168.100.223:34075` for package `app.kanade.tachiyomi.at.debug`.
3. Implement the smallest safe architecture that exposes the active page immediately and defers remaining page metadata/image/translation work.
4. Preserve normal manga behavior and reader stability on Android 8.0+ with bounded memory.
5. Add focused automated tests where practical, run translation unit tests, assemble/install the APK, and validate on the target device.
6. Keep commits small and incremental.

## Acceptance criteria

- Reader entry no longer waits for metadata or hydration of all 260 pages.
- Active page rendering is prioritized; at most a small forward window is eagerly prepared.
- Remaining pages continue to become available correctly as the user scrolls.
- Timing evidence identifies the post-translation costs before and after the change.
- No regression in the relevant unit tests/build, and no obvious crash or reader lifecycle failure in device logs.

## Entry points

- `DownloadPageLoader.kt`
- `DownloadManager.kt`
- `ChapterLoader.kt`
- `ReaderViewModel.kt`
- `WebtoonViewer.kt`

## Status (2026-09-03 evening)

- **Root causes 1+2 fixed**: the recursive retention sweep ran inside store
  opens at two more sites — `loadOrMigrate` (commit `722e955`, later narrowed
  to ≤8-page chapters in `57d2447` after it stranded the orphan-temp cleanup
  contract) and `closeAndFlush` reached from the probe `finally` on the
  reader-entry coroutine (`221f01e`). This was the "first open per process
  stalls ~15s, re-click is instant" signature on the 260-page chapter.
- **Entry-path slice landed** (`a9d916b`…`520e0f0`): skip no-op manifest
  re-read + legacy glossary read on authoritative opens; narrow over-eager
  durable-cache wipes (probe creation-gated, fresh-open gated); memoize
  positive translation-document lookups; registry fast-path + document/probe
  pass-through removing repeated SAF walks per open.
- **Review**: ACCEPT-WITH-NOTES
  (`review/entry-path-slice-review.md`). F1 fixed by reverting the
  queue-membership gate to wipe-per-emission (`368129d`) after verifying
  queueState never emits on progress ticks. F2/F3/F4/F6/F7 accepted
  limitations (documented in the report disposition).
- **Full unit suite green** (1,489 tests; the 9 failures were a test-harness
  gap for the new memo field + the 722e955 over-removal, fixed in
  `a8eacc7`/`57d2447`).
- **F5 pin tests landed** (`3f69e20`): created-probe wipe vs held-probe and
  active-store short-circuit (real on-disk artifact chapter),
  re-creation-after-release re-arms the wipe, deleteTranslation drops the
  document memo with the status cache.
- **Pending**: device validation on 192.168.100.223:34075 (wireless
  debugging dropped overnight — needs the Director to re-enable it and
  share the new port). Criteria: warm vm.init <700ms on the 260-page
  chapter, ~1 SAF document resolve per entry, no store-open churn from the
  chapter list; first-open ~1s confirming the closeAndFlush fix.


