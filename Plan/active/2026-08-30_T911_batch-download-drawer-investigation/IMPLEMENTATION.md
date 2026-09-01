# T911 Implementation contract — staged repair (AUTHORIZED 2026-08-30)

The Director authorized the full staged repair on 2026-08-30. All work happens
on branch `t911/repair` based at `67b1ff0`. One slice at a time; each slice
must have its focused tests green and pass a review gate before the next
slice starts. Specialists implement and test; the Main Leader integrates and
commits per slice.

## Hard constraints (all slices)

- Base: current `t911/repair`. Do not touch the sibling worktree
  `investigate_batch_download_failure` (`7c517d5`) and do not copy, merge, or
  cherry-pick its commit. If it independently fixed something, re-implement
  for this tree with tests.
- Preserve normal non-translation download semantics (pause, scheduling,
  delete, notifications, CBZ/directory). Translation changes must not alter
  behavior for chapters with no translation request.
- Android 8.0+, bounded memory, 6 GB floor: no unbounded retention; one
  compact keyed record per chapter is the maximum steady-state footprint.
- No new heavy dependencies. Match existing code style and DI patterns.
- Every behavior change ships with a focused unit test. Run the focused
  suites; report exact test counts and results.

## Slice 1 — immediate and truthful UX

Scope: manga screen + projection + sheet/indicator rendering only. No
persistence schema changes, no coordinator protocol changes.

1. **Drawer opens on confirmation.** `confirmChapterTranslation` selects the
   translation progress drawer for the confirmed chapter in the same UI
   transaction that acknowledges the request. A downloader callback must never
   perform navigation.
2. **Download phase joined into the batch projection.** While a pending
   request is `WAITING_FOR_DOWNLOAD`, the projection exposes the chapter's
   current download state/progress/pages from the downloader, without making
   the downloader an owner of translation state.
3. **Never render unknown totals as `0/0`.** While no translation page total
   exists (downloading, finalizing, preparing, queued, pre-registration), the
   sheet hero shows the phase (Accepted / Waiting for download / Downloading
   n% / Preparing / Queued) with no fake percentage or page count. A real
   zero-page failure stays a distinct error.
4. **Snapshot retention.** Keyed translation snapshots live in screen-model
   state; full chapter-list rebuilds (download cache/queue, translation queue,
   pending request emissions) and collector cancellation at terminal status
   must not erase an unchanged live or terminal snapshot.
5. **DETAILS reachable from all states** — pending, queued, downloading,
   translating, paused, translated, warning, error indicators route to the
   progress drawer.

Slice 1 acceptance (subset of SYNTHESIS gates 1–3, 5):
confirming a fresh undownloaded chapter opens the drawer immediately showing
a download phase with live progress, transitions to Preparing →
Queued/Translating with `0/N` only once a real total exists; no list emission
or terminal cleanup can revert it to `0/0`; drawer and ring agree.

## Slice 2 — durable coordinator, reconciliation, multi-select

1. Pending request record gains request generation, optional group ID,
   timestamps, and typed last failure (store migration stays
   backward-compatible).
2. Completion/admission callbacks are fenced by generation; cancel is defined
   as cancel-intent vs cancel-intent-and-download and cannot be undone by a
   later WAITING write or queue admission from an in-flight probe.
3. Download cancel/remove/clear/stop/offline/missing-source transitions the
   attached translation request to an explicit failed/cancelled state instead
   of waiting forever.
4. Startup reconciler runs once after both queues restore and idempotently
   resolves: pending+queue → queue wins; pending+valid files → admit once;
   pending+download queue → waiting; pending+neither → failed/cancelled;
   missing chapter/source → purge.
5. Multi-select uses the model's list API end-to-end: one confirmation for all
   selected chapters, one group request, queue position visible for later
   chapters, no same-source silent eviction.
6. Queue-admission/config failures get a distinct phase/reason and are never
   labeled `DOWNLOAD_FAILED`.

## Slice 3 — tracker totals, terminal exits, handoff failure split

1. Tracker total derives from known ordered work keys even when store
   placeholder writes are rejected/delayed; pre-registration failure surfaces
   as an explicit error, not a silent empty tracker.
2. Every exceptional exit (zero pages, OOM abort, missing files, unexpected
   exception) emits a typed terminal tracker snapshot — no live nonterminal
   `0/0` trackers.
3. Downloader separates download finalization outcome from rekey/translation
   admission/service outcome: a failure after files finalize must not flip the
   download back to `DOWNLOAD_FAILED`; translation intent gets the real cause.
4. Terminal details reconstruct after process death / re-entry from durable
   store/artifacts, not only the bounded memory cache.
5. Fault-injection tests for the touched downloader boundary (finalization vs
   handoff) and tracker exits.

## Gates

- Per slice: focused suites green (report counts), review report written to
  `review/sliceN-verification.md` by a separate reviewer pass, Main Leader
  commit `fix(translation): T911 slice N — <summary>`.
- Final: full `app/src/test` relevant suites green, no normal-download
  regression by inspection of touched paths, final Director report appended
  to SYNTHESIS.md.
