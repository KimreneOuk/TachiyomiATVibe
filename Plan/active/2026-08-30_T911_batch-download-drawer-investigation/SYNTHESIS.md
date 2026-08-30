# T911 Main Leader synthesis — batch download handoff and live drawer

## Decision summary

The Director's reproduction is valid on committed HEAD
`dd9652bc44411646a0b86d21b98a23127e3e13b8`. This is not one isolated drawer
bug. It is a cross-component ownership and projection problem with three direct
causes:

1. **VERIFIED / HIGH:** confirming batch translation never selects the progress
   drawer. The only drawer-open transition is a later DETAILS tap.
2. **VERIFIED / HIGH:** while download owns the work, the catalog download ring
   and batch drawer intentionally read different models. The drawer is given an
   empty translation snapshot and renders unknown page count as `0/0`; it never
   receives downloader percentage/page information.
3. **VERIFIED mechanism / STRONG INFERENCE for the persistent symptom / HIGH:**
   manga chapter-list rebuilds discard an already-observed progress snapshot by
   reconstructing the item with `translationProgress = null`. The active
   collector does not replay an unchanged canonical snapshot, so the drawer can
   fall back to `0/0` during handoff or after terminal cleanup.

Additional verified correctness gaps make the same UI become permanently stale:
orphan pending requests on download cancel/remove/stop, non-atomic persistence
boundaries with no startup reconciler, cancellation check/use races, last-item-
only multi-select wiring, zero-page/pre-registration tracker gaps, and
translation/config failures mislabeled as download failures.

**Recommendation:** authorize a staged cross-component repair. Fix navigation
and the keyed canonical UI projection first; add durable request reconciliation
and cancellation generations second; harden tracker/handoff exits third. Do not
begin with a broad stock-downloader rewrite, and do not integrate the sibling
`7c517d5` commit wholesale.

## What happens today

| Phase | Current owner | What the drawer sees | Main failure boundary |
| --- | --- | --- | --- |
| Confirmation | Manga screen dialog/group, memory only | Nothing; confirmation dismisses | No code opens progress drawer |
| Accepted / downloading | Durable pending request + downloader queue | Empty translation snapshot with request subtitle | Download percentage is a separate row-only field |
| Download finalized | Downloader/files/cache | Still pending/empty until callback finishes | DOWNLOADED is published before rekey and handoff |
| Preparing | Durable pending request | `0/0` formatted as real progress | Process death can strand PREPARING |
| Translation queue | Durable translation queue | Store/tracker fallback, sometimes empty | Pending clear and queue persistence are separate mutations |
| OCR/AI/inpaint/render | Artifact store + in-memory tracker | Live page/stage progress | List rebuild can erase copied snapshot |
| Terminal | Durable artifacts + bounded in-memory terminal cache | Copied item snapshot, if not erased | Queue removal can clear progress and details affordance |

The drawer and manga translation ring share the same
`ChapterList.Item.translationProgress`. Therefore, if “catalog progress” meant a
determinate *translation* percentage, a stable simultaneous drawer `0/0` is a
contradiction in the intended composition and points to the verified rebuild
race. If it meant the adjacent ordinary download ring, the disagreement is the
deterministic split-owner design described above.

## Root causes and edge cases

### Deterministic user-path defects

- Confirm closes the configuration dialog but never opens progress.
- Unknown translation total is displayed as numeric zero during download,
  rekey, queue setup, archive enumeration, and engine setup.
- The drawer does not project download state, percent, page total, queue
  position, pause, cancellation, or removal.
- Translated and error indicators do not consistently expose DETAILS, so final
  diagnostics/progress become difficult or impossible to reopen.

### Handoff, cancellation, and persistence

- Pending request, downloader queue, translation queue, artifacts, and UI
  selection are independent owners; the handoff is not a transaction.
- A crash can leave STARTING lost before async persistence, PREPARING without a
  queue, or both pending and translation-queue ownership after queue persistence
  but before pending clear.
- There is no startup reconciler for pending + downloaded files, pending +
  translation queue, pending + no queue/files, missing chapter/source, or an
  external download completion.
- Requests have no generation token. A late callback can satisfy a newer request
  for the same chapter; cancel and re-request are not fenced.
- Cancel after the screen model's eligibility check can be undone by the later
  WAITING write or queue admission.
- Removing/cancelling/clearing/stopping a download does not reliably transition
  its translation request, leaving indefinite WAITING.

### Progress/store/tracker

- Full list rebuilds replace live/terminal progress with null.
- Known ordered page keys are omitted from totals when placeholder store writes
  are rejected or delayed.
- Empty/unreadable chapters and some abort exits can leave a live nonterminal
  zero-page tracker.
- A real zero-page failure and an unknown-yet total are represented identically.
- Terminal tracker cache is memory-only and bounded to 20; durable terminal
  details are not reliably reconstructed after re-entry/process death.

### Download and failure semantics

- Download success is visible before optional rekey and translation admission.
  An exception after files finalize can flip the object back to ERROR and be
  called `DOWNLOAD_FAILED`, even when the actual failure is translation/store/
  service setup.
- One failed page is detected only after the other pages complete, explaining a
  separate 80–99% failure pattern. It does not explain this report, where the
  download completed.
- Temp-directory/storage operations before the downloader's protected try,
  offline stop, missing-source restore, download removal, and silent queue
  filters do not all notify the pending-request owner.
- T907's start bridge exists and its focused helper tests pass. It requests a
  WorkManager/in-process start, but a restored/global queue remains a separate
  policy and timing risk. Translation changes must not silently rewrite normal
  Mihon download semantics.

### Multi-chapter and lifecycle

- The bottom action loops selected chapters through a single-item callback. Each
  call overwrites the group/dialog, so confirmation retains only the last item.
- Later queued chapters show no queue position or active owner; Resume can be a
  misleading no-op while another batch owns the lane.
- Active StateFlows normally reconnect after background/foreground, manga
  screen re-entry, and reader entry/exit. However, open-dialog state and terminal
  history are not durable, and restored work intentionally does not auto-resume
  OCR/LLM.
- Reader teardown protects active/queued/paused batch files; the older
  reader-exit deletion issue is not current behavior.

### Platform and regression boundary

- No unbounded progress retention was found. A compact keyed operation state is
  compatible with Android 8.0+, bounded memory, and the 6 GB floor.
- CBZ/directory/SAF finalization, provider enumeration, rename collision, low
  storage, offline transitions, and mixed download+translation memory peaks need
  fault/device tests before downloader behavior is changed.
- Normal non-translation downloads should retain current pause, scheduling,
  delete, notification, directory, and CBZ behavior.

## Recommended repair sequence

### Slice 1 — immediate and truthful UX

- Manga screen opens a chapter-keyed progress drawer in the same UI transaction
  as accepted confirmation; no late downloader callback performs navigation.
- Drawer observes a canonical keyed operation projection directly, not a copied
  chapter-list field that can be rebuilt away.
- Projection combines request phase, downloader state/progress, translation
  queue/position, tracker/store progress, and terminal outcome while leaving raw
  subsystem ownership separate.
- Render Accepted, Waiting, Downloading N%, Preparing, Queued, Translating,
  Paused, Failed, and Completed. Never use `0/0` for an unknown total.
- Preserve DETAILS access for pending, queued, active, paused, warning, error,
  and completed states.

### Slice 2 — durable coordinator and reconciliation

- Persist a coordinator-owned operation record with request generation, optional
  group ID, phase, timestamps, and typed failure.
- Fence callbacks/admission by generation and define cancel-intent versus
  cancel-intent-and-download.
- After both queues restore, idempotently reconcile pending requests with queue
  membership, valid files, download state, chapter/source existence, and
  terminal artifacts.
- Use one confirmation and the list API for all selected chapters; expose queue
  position and preserve later chapters.

### Slice 3 — tracker and handoff hardening

- Preserve progress across base-list emissions and reconstruct durable terminal
  state.
- Derive total from known ordered work keys; make pre-registration rejection,
  zero pages, OOM, missing files, and every exceptional exit produce a typed
  terminal snapshot.
- Separate download finalization outcome from rekey/translation/service outcome.
- Add fault-injected directory/CBZ/SAF tests before accepting any storage or
  downloader change.

## Acceptance gates for any implementation

1. Fresh undownloaded chapter opens progress immediately, displays download
   phase/progress, hands off exactly once, then shows `0/N` and live stages.
2. No second tap and no `0/0` for an unknown total.
3. Cache/queue/request/list emissions cannot erase unchanged live or terminal
   progress.
4. Download fail/pause/cancel/remove/clear/offline/missing source/zero page/
   storage errors are explicit and actionable.
5. Rotation, background/foreground, manga re-entry, reader enter/exit, and
   process death at each phase converge to one correct owner.
6. Late callbacks cannot revive a canceled request or cross request generations.
7. N selected chapters survive one confirmation and expose truthful queue state.
8. Terminal details reconstruct after restart and terminal-cache eviction.
9. Directory and CBZ on internal and SAF storage pass success, partial,
   collision, enumeration failure, retry, and no-data-loss scenarios.
10. Normal download behavior does not regress.

## Verification performed

- Source and tests were investigated independently by Technical Lead and
  Failure-mode Auditor at current HEAD.
- Focused current tests executed successfully: 24 tests, 0 failures, 0 errors
  across enqueue/start helper, pending acknowledgement/version fence, download
  failure recovery, tracker projection, and terminal registry suites.
- Those tests are local invariants only. No current test covers the real
  downloader-to-translation callback, immediate drawer selection, list rebuild
  retention, cancellation/removal races, multi-select, lifecycle/process
  recovery, or real SAF behavior.

## Non-current sibling commit

Commit `7c517d5775be65351dad5ca2d99ba77d1620c40a` in another clean worktree is one
commit ahead and was inspected read-only. It appears to improve unknown-total
wording, ordered-key tracker totals, some SAF/CBZ finalization, generic
translation failure classification, and direct downloader start. It does not
fix automatic drawer opening, download progress in the drawer, list-item
snapshot reset, startup reconciliation, request generations/cancel races,
multi-select, terminal details, or queue position. It also lacks matching tests
for its broad downloader/storage changes. Do not integrate it wholesale.

## Remaining runtime evidence

A single privacy-safe screen recording and chapter-ID-correlated trace should
capture request phase, download status/progress/page count, finalization/rekey,
translation queue/status, tracker registration/ordered-key count, store page
count, list rebuild, and drawer snapshot. Also record whether the visible
catalog progress is the download ring or translation ring, API level, storage
provider/URI type, save-as-CBZ setting, source, page count, and lifecycle event.
No URLs, API keys, prompts, response bodies, or translated content are needed.

