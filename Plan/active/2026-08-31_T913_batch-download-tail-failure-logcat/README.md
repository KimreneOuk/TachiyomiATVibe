# Task T913 — Batch translation download-tail failure Logcat investigation

## Status

INVESTIGATION AUTHORIZED by Director (2026-08-31). Diagnostic logging may be
added only if the existing Logcat coverage cannot identify the failing
download-to-translation transition.

## Director report

Batch translation will not start until the entire chapter has downloaded. A
chapter can reach its last one or two pages and then fail, leaving batch
translation unable to start.

## Objective

Trace the current batch-translation request through chapter download page
completion, failure, and translation admission. Determine whether existing
Logcat output is sufficient to identify the exact failing page/stage and state
transition during an on-device reproduction. If it is not sufficient, add the
smallest safe diagnostic instrumentation, verify it, and prepare a filtered
Logcat capture before asking the Director to tap Batch Translate.

## Constraints

- Preserve normal manga download behavior and reader stability.
- Do not change product behavior or retry semantics in this task.
- Diagnostics must not expose API keys, prompts, response bodies, cookies,
  authorization headers, or user-readable page content.
- Prefer stable event tags/fields covering request/chapter/page identifiers,
  queue and pending-request phases, exception type, and sanitized error cause.
- Keep logging bounded; no image bytes or repeated high-frequency progress spam.
- Android 8.0+ and bounded memory remain required.
- Preserve unrelated working-tree changes.

## Required investigation

- Existing logging in the batch action, translation download request bridge,
  downloader queue, per-page download pipeline, completion/error callbacks,
  and translation coordinator admission.
- Whether a failure on the final one or two pages can be distinguished from a
  queue stall, source/network error, invalid page, storage write/rename error,
  cancellation, or completed-download recognition failure.
- Exact Logcat filters and device/package prerequisites for reproduction.

## Deliverables

- Repository health: `REPO_HEALTH.md`
- Technical report: `engineering/logcat-coverage-and-tail-failure.md`
- If needed, implementation report and focused verification under `engineering/`
- Main Leader starts capture and gives the Director one clear reproduction cue.

## Relevant context

- `Plan/active/2026-08-28_T907_batch-download-autostart-recovery/README.md`
- `Plan/active/2026-08-30_T911_batch-download-drawer-investigation/README.md`
- `Plan/active/2026-08-30_T911_batch-download-drawer-investigation/IMPLEMENTATION.md`
- `Plan/active/2026-08-30_T911_batch-download-drawer-investigation/SYNTHESIS.md`

## Likely source/test entry points

- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/translation/batch/`
- related tests under `app/src/test/`

