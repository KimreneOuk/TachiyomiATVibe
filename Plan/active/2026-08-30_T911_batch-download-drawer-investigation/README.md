# Task T911 — Batch download handoff and drawer observability investigation

## Status

INVESTIGATION ONLY. No production or test code changes are authorized.

## Director report (2026-08-30)

Reproduction on a fresh, not-yet-downloaded chapter:

1. Open the chapter's manga screen.
2. Trigger batch translation.
3. The chapter download starts and finishes.
4. The batch translation drawer does not open automatically after download.
5. The Director must tap batch translation again to open the drawer.
6. The drawer remains at `0/0`, while the catalog/chapter UI shows progress.

Director requests a deep investigation of the download process, state ownership,
handoff into batch translation, and live batch UI/UX, including edge cases.

## Objective

Determine the current committed behavior and root cause(s) for:

- the missing immediate/open-on-handoff drawer response;
- the download-to-translation ownership transition;
- disagreement between drawer progress (`0/0`) and catalog progress;
- any race, lifecycle, persistence, restored-state, or multi-chapter condition
  that can make the live UI silent, stale, misleading, or detached from the
  running work.

The output is an evidence-backed diagnosis and recommended repair boundaries,
not an implementation.

## Required investigation

Trace the full state machine and data ownership from the first batch-translation
tap through:

`confirm/configure -> pending request -> download enqueue/start -> download
progress/completion/failure/cancel -> translation queue admission -> coordinator
start/restore -> OCR/chunk/translation progress -> drawer/catalog projection ->
terminal cleanup/retry`.

For each transition identify:

- owner and source of truth;
- persistence and process-death behavior;
- producer/consumer Flow or callback;
- UI gate, subscription timing, and initial/default value;
- thread/lifecycle scope and cancellation behavior;
- behavior when the same action is tapped again.

Explicitly compare the drawer progress source with the catalog/chapter progress
source. Explain exactly how `0/0` can coexist with visible catalog progress.

## Edge cases to cover

- chapter already downloaded, partially downloaded, queued, downloading,
  paused, failed, cancelled, deleted, or removed from queue;
- stale/restored downloader queue, stale pending request, duplicate taps,
  overlapping batch requests, and app/process restart at every major phase;
- drawer opened before download, during download, at completion, after
  translation starts, and after completion/failure;
- leave/re-enter manga screen, enter/exit reader, configuration change,
  background/foreground, and foreground-service lifetime;
- single chapter vs multiple chapters, first selected chapter vs later chapter,
  zero-page/empty/invalid chapter, missing source/network/storage, and a chapter
  becoming downloaded outside this request;
- progress with unknown total, reconciliation after restart, completed artifacts,
  retry/resume, cancellation, and stale terminal state;
- normal non-translation downloads and normal manga regression boundaries;
- Android 8.0+, bounded memory, and target devices with at least 6 GB RAM.

## Current-tree and historical context

Investigate the current working tree as-is. Distinguish committed behavior from
uncommitted changes. T907 previously fixed translation-triggered downloader
auto-start and stale `DOWNLOAD_FAILED`; do not assume it solved drawer ownership
or progress projection.

Relevant prior reports/contracts (re-verify every reused claim against current
source):

- `Plan/active/2026-08-28_T907_batch-download-autostart-recovery/README.md`
- `Plan/active/2026-08-26_T903_batch-pretranslate-broad-audit/engineering/batch-pipeline-architecture.md`
- `Plan/active/2026-08-26_T903_batch-pretranslate-broad-audit/review/batch-ux-observability-audit.md`
- `Plan/active/2026-08-26_T903_batch-pretranslate-broad-audit/review/batch-failure-mode-audit.md`
- `Plan/active/2026-08-25_T901_batch-translation-verification/engineering/code-investigation-ui-gating.md`

Likely source/test entry points:

- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
- manga-screen batch drawer / progress / chapter indicator composables
- `app/src/main/java/eu/kanade/tachiyomi/data/download/`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/translation/batch/`
- `app/src/main/java/eu/kanade/tachiyomi/data/translation/`
- reader translation entry and batch-state observation under `ui/reader/`
- focused tests under the corresponding `app/src/test` paths

## Evidence standard

Classify important claims as VERIFIED, STRONG INFERENCE, ASSUMPTION, UNKNOWN,
or CONTRADICTION. Cite current `file:line` primary evidence. Record missing test
coverage and the evidence/logging/device reproduction needed to close UNKNOWNs.

## Deliverables

- Repository Steward: `REPO_HEALTH.md`
- Technical Lead: `engineering/download-handoff-and-ui-ownership.md`
- Reviewer / Failure-mode Auditor: `review/edge-case-and-ux-state-audit.md`
- Main Leader synthesis: root causes, severity, repair boundaries, acceptance
  criteria, test matrix, and recommendation to the Director.

