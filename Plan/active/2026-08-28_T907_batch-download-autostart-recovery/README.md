# Task T907 — Batch download auto-start and failed-state recovery

## Status

IMPLEMENTATION AUTHORIZED by Director (2026-08-28). Fixes a regression
introduced by T904 commit e18ed84 ("unify batch UX and reader lifecycle").

## Objective

Restore working batch translation downloads:

- Slice 1: translation-driven chapter downloads must start pumping without
  the user tapping "Start downloading now" (S1: chapters stall in QUEUED).
- Slice 2: `DOWNLOAD_FAILED` pending-request phase must be self-clearing —
  retry or re-download advances it, and manual reader translation /
  rolling-auto translation must never be gated by a stale failed state
  (S2/S3: sticky "Download failed — retry to continue" wedge).

## Verified root cause (evidence at HEAD ea76a66)

- Batch path enqueues with `downloadManager.downloadChapters(...)`
  (MangaScreenModel.kt:1038) and relies on stock auto-start
  `if (autoStart && wasEmpty)` (Downloader.kt:293). The e18ed84 bridge
  (MangaScreenModel.kt:1041-1045) force-starts only entries already in
  ERROR state; a fresh QUEUE entry behind a stale/restored entry never
  starts.
- Every downloader error path calls
  `translationManager.markTranslationDownloadFailed(...)`
  (Downloader.kt:326,389,445), which durably persists phase
  `DOWNLOAD_FAILED` (TranslationManager.kt:292-298) projected as
  "Download failed — retry to continue" (BottomReaderBar.kt:75-90,130-136;
  TranslationManager.kt:1361-1381). Only explicit cancel, a fully
  successful requeue, or clearQueue clears it
  (TranslationManager.kt:301-306,503-546).
- `DownloadManager.startDownloads()` is idempotent
  (DownloadManager.kt:71-79): safe to call unconditionally.
- `queueTranslationAfterDownload` has exactly one call site:
  MangaScreenModel.kt:1035.

## Contract

- Base: HEAD `ea76a66` on `optimize_translation_finishing_page`; work on
  branch `t907/fix`; primary workspace fast-forwards only after review.
- Do NOT modify stock Mihon downloader behavior (`wasEmpty` auto-start,
  pause semantics) — normal manga must not regress. Scope changes to the
  translation-driven paths.
- No changes to the shared request governor, PAUSED/cooldown semantics,
  or the artifact store.
- Preserve guarded store/user-edit preconditions; no diagnostics leaks
  (no API keys/prompts/response bodies).
- Reuse `acknowledgePendingTranslationState` (TranslationManager.kt:81-89)
  for phase reset where possible.
- Every slice adds/updates focused tests. Do not build new test harnesses;
  follow existing patterns (T904/T906 test suites).

## Gates

- `:app:compileStandardDebugKotlin`
- App unit tests (standardDebug, full tier — all failures gating; AOT
  exclusion moot since T906)
- `:domain` tests (68)
- spotlessCheck clean
- Build env: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`;
  wrapper `gradlew.bat` (Windows / Git Bash).

## Delivery order

1. Slice 1 (MangaScreenModel batch + retry paths, unconditional
   `startDownloads()`) + focused test.
2. Slice 2 (TranslationManager self-clearing DOWNLOAD_FAILED: requeue
   reset, manual/auto entry clear, download-resume advance; verify UI
   projections follow state) + focused tests.
3. Full gates + independent review (max two loops, per T904 policy).

## Reporting

Report commit hash, verification numbers, deviations, and risks to the
Main Leader. Do not address the Director directly.

## Delivery record (2026-08-28)

DELIVERED. Fix commit `2085c03` on `t907/fix` (parent `b65a21f`); primary
workspace `optimize_translation_finishing_page` fast-forwarded `b65a21f →
2085c03`. Diff: 3 production files (MangaScreenModel.kt seam
`enqueueTranslationDownloads` with unconditional idempotent
`startDownloads()`; TranslationManager.kt `clearStaleDownloadFailedRequest`
hooked at rolling-auto entry; ReaderViewModel.kt manual-entry clear) + 8 new
tests (2 suites).

Gates (implementer, re-verified by reviewer on the same tree): compile
SUCCESS; `:app:testStandardDebugUnitTest` 1072/0 (138 suites; baseline ~1064
+ 8 new); `:domain:test` 68/0; spotlessCheck SUCCESS.

Independent review: VERDICT PASS. Requeue-durable overwrite confirmed
(`WAITING_FOR_DOWNLOAD`, commit=true, inside `pendingRequestMutationLock`);
no new main-thread I/O class; failure→download-completes→batch-launch
verified (no phase gate). Non-gating follow-ups: (1) wrap
`clearStaleDownloadFailedRequest` check-then-clear in
`pendingRequestMutationLock` (tiny race vs downloader-thread revival); (2)
hook wiring at ReaderViewModel:2133 / TranslationManager:1283 is untested at
this tier (consistent with prior practice).

APKs built `2026-08-28 22:37` from `2085c03` at
`app/build/outputs/apk/standard/debug/`: arm64-v8a 436M, armeabi-v7a 224M,
universal 543M, x86_64 239M, x86 238M.

Residual: runtime trigger of the *first* download failure (S2) remains
unattributed — capture logcat (`Downloader` / download notifier) during a
repro if it recurs on-device.
