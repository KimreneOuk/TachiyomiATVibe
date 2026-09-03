# T917 Phase 5 — UI-truth and copy product specification

**Audience:** Implementer and Reviewer  
**Scope:** UI projections, surfaces, copy, accessibility, truthful progress, and the
Phase-3 persistence-outcome carry-over. This document specifies Phase 5 only; it does
not change the downloader, reader image pipeline, persistence architecture, or provider
behavior. No Gradle invocation or source change is part of this product specification.

The v3 contract is authoritative: every intent is visible, every batch page has one
terminal result, stale work never publishes, and UI/durable state may disagree only
transiently (`docs/architecture/translation-subsystem-coexistence-v3-draft.md:232-240`).

## 0. Contradictions and refinements against the verified current UI/code

### 0.1 Current state vocabulary and what Phase 4 exposes

The repository already has most of the typed vocabulary needed by this phase. Do not
introduce a second UI state machine or new state enum when a projection of these values
is sufficient.

| State or outcome | Verified source | Current exposure | Phase-5 implication |
|---|---|---|---|
| Manual `Completed` | `SinglePageOutcome.Completed` (`app/src/main/java/eu/kanade/translation/scheduling/TranslationExecutor.kt:134-137`) | Stored in the scheduler's bounded `manualOutcomes` map (`TranslationScheduler.kt:94-106`, `:632-649`), but no production UI consumer exists. Durable reader feedback can show `Translated` only when `PageDisplayProjection.displayReady` is true (`ReaderTranslationFeedback.kt:58-72`). | Keep `Completed` reserved for an owned, committed/displayable result or a legitimate terminal no-op. Add a read-only projection to the UI; do not treat presence in `manualOutcomes` alone as success. |
| Manual `Paused` | `SinglePageOutcome.Paused` (`TranslationExecutor.kt:139-144`), produced for typed provider/governor deferral (`TranslationPipeline.kt:480-511`). | Batch pause fields are rendered in the drawer and notification, but the manual outcome itself is not rendered. | Render reason and retry time, and distinguish an automatic retry from an explicit retry. |
| `Attached` / `AttachedUnresolved` / `Rejected` | `TranslationExecutor.kt:147-156`; attach wait is bounded and typed (`TranslationPipeline.kt:529-569`). | Not mapped by `ReaderTranslationFeedback`, whose state set contains only queue/stages/translated/deferred/failed (`ReaderTranslationFeedback.kt:21-30`). Page holders select durable or auto feedback, not manual outcome feedback (`PagerPageHolder.kt:230-244`; `WebtoonPageHolder.kt:168-184`). | These are already exposed to the scheduler, but not rendered. They must become visible manual intent outcomes without starting duplicate paid work. |
| Batch `PERSISTENCE_REJECTED` / `PersistenceRejected` | `BatchPass1Status.PERSISTENCE_REJECTED` and `ChunkCompletionOutcome.PersistenceRejected` (`BatchCoordinatorInterfaces.kt:134-195`). `BatchProgressReconciler` deliberately marks the affected page pending and sets `nonDurableFailure` (`BatchProgressReconciler.kt:137-198`). | The batch path retains the distinction internally, but the tracker snapshot has no durable-publication failure field. The chapter may be `READY_WITH_WARNINGS`, which can be mistaken for success. | Carry the existing rejection fact into the progress projection with a bounded value field/reason. It must override success copy and terminal success counts for the rejected page. Do not add an enum. |
| Batch pause and durable retry metadata | `TranslationProgressSnapshot` already carries `pauseReason`, `nextEligibleRetryAtEpochMs`, and `requestState` (`TranslationProgressSnapshot.kt:45-79`). Durable pauses are filled from manifest failure metadata (`BatchProgressProjector.kt:319-334`). | Drawer subtitle and pill render pause reason/time (`TranslationProgressSheet.kt:912-920`, `:562-585`); the paused foreground notification renders reason/time and a retry action (`TranslationForegroundService.kt:193-218`). | Preserve these surfaces, fix precedence and copy, and use the same mapping for manual/auto/batch. A durable cap with no retry timestamp is not an automatic retry. |
| Rolling-auto queued/stages/ready/deferred/failed | `AutoSlotState` has queued, four stages, ready, deferred(reason), and failed(retryable) (`AutoWindowState.kt:41-63`). Reader mapping is explicit (`ReaderAutoTranslationUiState.kt:161-176`). | Bottom-bar/status-rail copy renders queue, stages, ready-ahead count, one deferral reason, and one undifferentiated failed count (`AutoTranslationStatus.kt:73-98`, `:158-188`). `pausedTranslations` retains retry epochs internally (`RollingAutoCoordinator.kt:128`, `:563-600`) but the reader projection loses the epoch. | Keep the bounded slot model. Add only the missing retry-time value to the auto UI projection (or an equivalent bounded accessor); do not add another pause enum. Render retryable and terminal failure differently. |
| Page durable lifecycle | `PageLifecycle` has `Pending`, `Running`, `Done`, `Textless`, `Cancelled`, and `Failed(stage,retryCount,reason)` (`PageTranslationState.kt:10-18`). | Reader feedback derives translated/failed/running and suppresses cancellation (`ReaderTranslationFeedback.kt:64-72`). `Failed(retryable)` is mapped to the same label as terminal failed (`:233-242`). | Preserve display readiness precedence. Add retryable/terminal copy and a visible cancellation result when cancellation was user-paid-work cancellation, without painting an old error over a committed result. |
| Artifact states | `ArtifactStageStatus` includes `READY`, `FAILED_RETRYABLE`, `FAILED_TERMINAL`, `PARTIAL`, `STALE`, and `CORRUPT` (`artifact/ArtifactContracts.kt:11-44`); durable failure metadata carries category, retry count, reason, and retry epoch (`:198-219`). | `StoreStatusProjector` gives terminal failure precedence, then retryable durable failure as `PAUSED` (`store/StoreStatusProjector.kt:56-124`). | Use manifest truth for chapter/page status. `FAILED_RETRYABLE` is not success; `INTERRUPTED` cap copy is a special manual-recovery pause even though its artifact status is retryable. |
| D8 stall and typed timeout outcome | The Phase-4 design specifies a bounded occupancy signal, `NativeStallState`, and `SinglePageOutcome.Stalled` (`engineering/phase4-design.md:250-317`). | At the current Phase-4 checkpoint those D8 types are not present in the searched source. A null native result is still returned as `Completed` (`TranslationPipeline.kt:446-478`), and the HTTP timeout path also falls through to `Completed` (`:483-515`). | Implementer must land the Phase-4 typed state before or with this UI pass. Phase 5 must render it; no UI may infer a stall from a spinner or elapsed time. |
| D10 partial source-page truth | The Phase-4 design defines a partial manifest fact and trusted source total (`engineering/phase4-design.md:393-418`). | Current code historically derives `expectedPageCount` from enumerated files, so an active half-download can appear as a complete subset (`phase4-design.md:349-367`). `ChapterArtifactManifest` currently has only `expectedPageCount` and `expectedPageCountTrusted` (`artifact/ChapterArtifactManifest.kt:20-25`). | Render the Phase-4 manifest fact when it exists. A known source total is trusted; an absent source list is explicitly unknown. Never fabricate missing page records. |

### 0.2 Required corrections to current surfaces

1. **A transient reader pill is not a terminal-outcome surface.** `ReaderPageImageView`
   hides translated feedback after the existing short display period
   (`ReaderPageImageView.kt:695-703`). This is acceptable as a confirmation, but a
   pause, rejection, stall, or failure must remain discoverable through the reader
   status/control and the chapter progress drawer. A user intent cannot become
   invisible merely because a transient pill expired.
2. **Current attached/rejected outcomes are internally typed but visually absent.**
   The page-holder feedback path consumes durable page state and rolling-auto slot
   state only (`PagerPageHolder.kt:230-244`). Manual outcome values must be joined to
   that path with identity fencing; a late outcome from a prior page/chapter must be
   dropped.
3. **Current paused batch rendering is useful but not a complete contract.** The drawer
   already renders `Paused — reason · retry after time` and has Resume/Cancel controls
   (`TranslationProgressSheet.kt:363-475`, `:912-920`), and the notification has a retry
   action (`TranslationForegroundService.kt:200-218`). The same surface must not call a
   D9 cap “ready to resume” or imply automatic retry when the cap requires force.
4. **Rolling auto loses the retry epoch.** `pausedTranslations` stores the epoch but
   `AutoSlotState.Deferred` carries only a reason (`RollingAutoCoordinator.kt:563-574`,
   `AutoWindowState.kt:60-63`). The Phase-5 projection needs a bounded retry-time value
   for the affected target(s), while retaining the existing reason precedence and slot
   cap. A brief self-healing deferral may remain quiet; a persistent pause must be
   visible.
5. **Current failure copy erases retryability.** `ReaderPageFeedbackState.Failed` has a
   retryable flag, but `localizedLabel` always returns the same “Failed” resource
   (`ReaderTranslationFeedback.kt:28-30`, `:233-242`). Copy and actions must distinguish
   “retry available” from “action required/settings or source change.”
6. **Current chapter success icon lacks a useful accessibility label.** The translated
   indicator supplies `contentDescription = null` (`ChapterTranslationIndicator.kt:337-342`),
   while the state distinction `TRANSLATED` versus `READY_WITH_WARNINGS` exists in its
   tint and routing (`:66-72`, `:312-315`). The Phase-5 label must state Ready versus
   Ready with warnings and retain the details action.
7. **Current page mini chips have no accessibility description and no explicit partial or
   cancelled color/state.** `PageMiniChip` renders only the page number and color
   (`TranslationProgressSheet.kt:765-800`). Add semantics from the same pure mapping as
   visual text; do not make color the only state signal.
8. **Current progress has multiple notions of “done.”** Stage counts use succeeded,
   failed, skipped, and total (`TranslationBatchProgressTracker.kt:318-365`), while
   reader/notification completion uses `processedPages` (`TranslationProgressSnapshot.kt:91-99`,
   `TranslationForegroundService.kt:150-166`). Phase 5 must define one page-terminal
   count for chapter progress and use it consistently. Running, queued, buffered,
   attached-unresolved, persistence-rejected, and unknown-total pages cannot increase
   terminal success.
9. **D10 must be a decision, not a silent subset.** The Phase-4 evidence confirms that
   directory existence currently admits an active partial download and the old total is
   the found-file count (`engineering/phase4-design.md:351-367`). The UI must expose the
   finish-first versus translate-subset choice and carry the partial fact into the final
   chapter status.
10. **D8 timeout copy and state are currently false.** The timeout placeholder says
    “Translation timed out after 120 s” at `PageStoreWriter.kt:119-135`, although the
    design identifies separate ONNX/native and HTTP+render result timers
    (`engineering/phase4-design.md:254-315`). Copy must name the timer that fired and
    must never turn a null/timeout/rejected value into `Completed`.
11. **PersistenceRejected/Failed-as-value is the Phase-5 blocker.** The HTTP phase
    records `PersistenceRejected` at `SinglePageHttpRenderPhase.kt:625-639`, but the
    pipeline maps only `Paused` and then returns `Completed` at `TranslationPipeline.kt:503-515`.
    The Reviewer verified the same residual swallow for `PersistenceRejected` and
    Failed-as-value (`review/phase3-verification.md:268-286`, finding 5). This is a
    stale-success defect, not a copy-only issue, and is specified in §4 below.

## 1. Product contract and truth precedence

### 1.1 Surfaces

Use these names throughout implementation and tests:

- **Page chip:** the per-page reader feedback pill in `ReaderPageImageView`, plus its
  compact page state in the batch page overview. It may be transient for stage progress,
  but terminal/error/pause state must remain discoverable elsewhere.
- **Reader overlay/status:** the reader bottom-bar auto status and batch status, and the
  current-page overlay feedback. It is the durable reader route for an outcome after the
  transient page pill disappears.
- **Batch queue/notification:** the manga translation drawer/chapter indicator and the
  foreground-service notification. These describe batch ownership, not manual page work.
- **Progress totals:** batch page totals and rolling-auto ready-ahead counts. Totals are
  counters of state, not performance claims. A percentage is allowed only when its total
  is trusted and its numerator is terminal work.
- **Accessibility content description:** one localized, state-complete description for
  every chip/status, plus action descriptions for Cancel, Retry, Resume, and Review.
  It must not rely on tint, animation, or an unlabeled page number.

A surface can be intentionally silent only when the state is a non-user-visible,
self-healing scheduler transition. Silence is not allowed for a user decision, pause,
stall, durable failure, partial download, or cancellation of paid work.

### 1.2 Truth precedence

The pure mapper used by all surfaces must apply this order:

1. **Identity/lifecycle fence:** ignore a snapshot or outcome from another chapter,
   page, owner version, or window version. Existing reader auto identity/version gates
   provide the pattern (`ReaderAutoTranslationUiState.kt:96-159`).
2. **Explicit current request:** a live pending request is shown as accepted, waiting
   for download, preparing, or its typed terminal request failure. It must not be
   replaced by an old disk success; this matches the queue-first projection
   (`TranslationUiProjection.kt:7-20`).
3. **Current batch queue state:** `QUEUE`, `TRANSLATING`, or `PAUSED` wins over a
   persisted chapter summary. Queue position is shown when known, not “ready now.”
4. **Current durable display:** a committed `PageDisplayProjection.displayReady` or
   textless terminal wins over a late running callback. A candidate is never display
   authority (`PageDisplayProjection.kt:8-18`, `:81-113`).
5. **Current durable failure/pause:** terminal durable failure, retryable durable
   failure, D9 cap, partial manifest fact, and non-durable publication rejection are
   shown with their reason. A non-durable rejection specifically suppresses any
   success wording.
6. **Transient owner state:** attached, queued, running, or auto-deferred state is shown
   only while no newer durable terminal result supersedes it.

No mapper may produce `Completed`, `Translated`, `Ready`, “all pages translated,” or a
successful progress increment from any of: null/timeout, attach-unresolved,
publication rejection, unknown-total preparation, queued/running work, or a stale
callback.

## 2. Appendix A — state → surface → copy

The quoted copy is the English source wording. It must be localized. `{reason}`,
`{time}`, `{owner}`, `{done}`, `{total}`, `{missing}`, and `{position}` are values from
state; `{total}` may be used only when `expectedPageCountTrusted` is true. Progress
counts are state observations, not latency measurements.

| Truth state / §7 outcome | Manual page chip and reader overlay | Rolling auto page/status | Batch queue, notification, and progress | Accessibility content description and actions |
|---|---|---|---|---|
| **READY / translated** (`Completed` with committed display, or `reused-valid`) | Chip: **“Translated.”** Reader confirmation: **“Translated.”** The confirmation may use the existing transient behavior, but the reader control/drawer remains the discoverable route. | Page: **“Translated.”** Status: **“Auto · {done} of {total} ready ahead”** or **“Auto · Ready”** when there is no ahead target. Count only display-ready ahead slots; never count the visible page in ready-ahead. | Chapter: **“Ready”** or **“Translation complete.”** Numeric progress is `{terminal}/{trusted total} terminal`; success is only translated/reused/textless terminal as defined below. Do not use completion copy if warnings, missing source pages, or a non-durable failure exist. | “Page {index}: translated and ready to read.” Chapter: “Chapter translation ready; {done} of {total} terminal” when trusted. No Retry action. Read/details remains available. |
| **TEXTLESS terminal** (successful no-source-text result) | Chip: **“No translatable text.”** Do not say “Translated” when no translation text exists. | Slot may be Ready for window accounting, but page description is **“No translatable text.”** It is terminal and not a failure. | Count as terminal processed, not readable translated. Copy: **“Processed — no translatable text.”** It must not inflate “pages ready to read.” | “Page {index}: processed; no translatable text.” No failure/retry action unless another stage failed. |
| **WAITING_FOR_DOWNLOAD** (request accepted but source download owns the next step) | If shown in reader: chip/status **“Waiting for chapter download.”** Do not show a translation percentage or page total. | If auto lacks the source, use **“Auto paused · waiting for source.”** This is a source deferral, not provider success. | Drawer/notification: **“Waiting for chapter download before translation.”** Offer the existing download route or details. Progress: **“Waiting for download”** with an indeterminate bar; no `0/0`, no translation total. | “Translation waiting for the chapter download.” Action: “Open download/progress.” A downloader failure changes to its own terminal request failure; it must not remain waiting. |
| **ATTACHED / attached-to-owner** (manual intent attaches to batch or auto owner) | Chip: **“Translating · {owner} job.”** Reader overlay: **“Waiting for {owner} translation to finish.”** No second paid call and no manual “completed” toast. | Owner slot keeps its real stage (`Queued`, `Reading text`, `Cleaning bubbles`, `Translating text`, or `Finishing page`); the attached manual intent does not create a second slot. | Batch page remains pending/running according to its actual owner. It cannot increment terminal totals until the owner commits. Notification remains the owner’s truthful progress. | “Page {index}: waiting for the {owner} translation job; no duplicate request started.” Details route is available. |
| **ATTACHED_UNRESOLVED** (owner observed but did not reach terminal inside the attach bound) | Chip/overlay: **“Background translation did not finish yet.”** This is not “Translated” and not a generic silent return. Show Details; enable explicit Retry only after ownership is released and a new attempt is safe. | Keep the owner slot’s state if it is still current; otherwise show **“Auto translation waiting for the owner result.”** Do not mark the slot Ready. | Page is pending/unresolved; no terminal total increment. Batch detail: **“Still waiting for the background result.”** If the owner later commits, the state converges to READY; if it fails, show its failure. | “Page {index}: background translation did not finish within the wait; retry is available after the owner releases the page.” Action labels must say **“Retry page”** (explicit), never “Retrying automatically.” |
| **QUEUED / queued-behind** (`QUEUE`, request accepted, or owner has not entered a stage) | Chip: **“Queued.”** Reader overlay: **“Translation queued; work has not started.”** Do not claim OCR/provider activity before a stage event. | Slot: **“Queued.”** Aggregate: **“Auto · Preparing current page”** or **“Auto · Preparing {total} ahead.”** | Drawer: **“Queued for translation.”** If known: **“Queued ({position} of {total}) — waiting for earlier batches.”** Notification uses **“Translation queued”**, not a running page count. Progress is indeterminate or a trusted terminal count; queued pages add zero terminal work. | “Page {index}: translation queued.” Batch: “Chapter translation queued, position {position} of {total}.” Action: Details/Cancel; no Retry unless this is a failed request. |
| **TRANSLATING / stage-running** | Chip follows the actual stage: **“Reading text,” “Cleaning bubbles,” “Translating text,”** or **“Finishing page.”** Reader overlay uses the same stage and may coalesce short changes. | Use the slot’s actual stage labels. Aggregate status may say **“Auto · Preparing current page”** or ready-ahead copy; never turn the ready-ahead numerator into total chapter completion. | Drawer/notification: **“Translating chapter”** or the actual active stage. Progress denominator is trusted only when source/page set is trusted; numerator is terminal pages, not active pages. A running page is never done. | “Page {index}: {stage}; translation is in progress.” Cancel is explicit and labeled; do not expose internal coroutine/provider identifiers. |
| **PAUSED** (retryable provider/window deferral) | Chip: **“Paused · {reason}.”** If an eligible epoch exists: **“Paused · {reason}; explicit retry available after {time}.”** The reader status remains discoverable after the page pill disappears. | Status: **“Auto paused · {reason} · retries automatically when available”**; add **“after {time}”** when known. A deferred slot is not Ready. Do not imply a user must tap when the coordinator will retry. | Drawer/notification: **“Paused — {reason} · automatic retry after {time}”** for batch-owned automatic work. If the control is a user action, say **“Retry after {time}”** and provide Retry; do not call it automatic. Progress leaves paused pages non-terminal; saved terminal pages retain their counts. | “Page {index}: translation paused because {reason}; {automatic retry / explicit retry} after {time}.” Actions must be **“Retry translation”** only for an explicit retry path and **“Cancel”** for stopping paid work. |
| **STALLED** (D8 occupancy watchdog; native lane remains occupied after its named result timer) | Chip: **“Translation stalled.”** Reader overlay: **“Translation stalled — background work may be stuck.”** Details may say **“The ONNX/native result timer expired while the native lane remained occupied.”** Show Cancel and Retry; Retry is disabled until the lane is released/cancelled safely, then is an explicit new attempt. | Auto never infers this from elapsed UI time; only a typed stall signal may produce it. Show **“Auto translation stalled — background work may be stuck.”** No Ready/automatic retry claim. | Batch: **“Translation stalled — background job may be stuck.”** Notification is visible and ongoing only while work remains owned; offer Cancel/Details. The stalled page is not terminal success and does not increment totals. | “Page {index}: translation stalled; the ONNX/native result timer expired while work remains occupied.” Actions: **“Cancel translation”**, then **“Retry page”** after release. Do not offer a native kill. |
| **FAILED, retryable** (stage failure with retry remaining or `FAILED_RETRYABLE`, excluding D9 cap wording) | Chip: **“Translation failed — retry available.”** Reader overlay is immediate and remains discoverable through Details. | Slot: **“Auto failed — retry available.”** If coordinator can retry automatically, say **“Auto will retry when available”**; if not, say **“Retry required.”** Never use the same label for both. | Drawer: **“{done} terminal · {failed} failed, retry available.”** Notification: **“Translation failed — retry available.”** Failed current-pass pages count as terminal *failed*, never successful; retrying creates a new attempt. | “Page {index}: translation failed; retry is available.” Action: **“Retry page”** (explicit) or **“Retry automatically”** only when a real auto path exists. |
| **FAILED, terminal** (`FAILED_TERMINAL`, corrupt/stale/config/source failure, or exhausted stage retry) | Chip: **“Translation failed — action required.”** Reader overlay names the safe reason, such as **“Check translation settings or source.”** No Retry button unless the reason-changing action is available. | Slot: **“Auto failed — action required.”** Do not imply another automatic attempt. | Drawer/notification: **“Translation failed — action required.”** Progress counts the page as terminal failed, never as succeeded; failure summary names the sanitized reason/category. | “Page {index}: translation failed and needs attention; {reason}.” Action: **“Review settings/source”** or Details, not Retry. |
| **PARTIAL page** (`StageStatus.PARTIAL` / retryable partial provider output) | Chip: **“Partial translation — retry available.”** If a committed older display exists, keep the committed image and show a warning in Details; do not paint a red error over a readable committed result. | Slot: **“Auto partial — retry available.”** It is not Ready until the display-ready gate is satisfied; the ready-ahead numerator excludes it. | Page overview: **“Partial.”** Chapter: **“Ready with warnings”** only when the current committed result is usable; otherwise **“Partial translation — retry available.”** Progress treats the page as terminal failed/retryable for the current pass or as an explicit partial terminal according to the existing reconciler, but never as clean success. | “Page {index}: partial translation; some text is missing and retry is available.” Action: Retry page/Details. |
| **PARTIAL download / missing source pages** (D10 manifest fact) | Current available page may be translated normally. Chapter chip is **“Partial — missing source pages”** rather than Ready. Do not claim the current page’s success proves chapter completeness. | Auto source deferral: **“Auto paused · waiting for source pages.”** If auto works only on available pages, label the window as partial in Details; do not fabricate ahead pages. | Decision copy: **“This chapter is partially downloaded.”** Choices: **“Finish download, then translate”** or **“Translate downloaded pages only (partial).”** Known source count: **“{available} of {source total} source pages available; {missing} missing.”** Unknown source list: **“{available} downloaded pages available; source total unknown.”** Final drawer/notification: **“Partial translation — {missing} source pages not downloaded.”** Progress uses `{terminal}/{trusted source total}` only when trusted; otherwise **“{available} pages available · source total unknown.”** Missing pages are documented absence, not fake failed page records. | “Chapter translation is partial: {missing} source pages are missing” or “source total unknown.” Actions: **“Finish download”** and **“Translate downloaded pages only.”** |
| **REJECTED** (could not run/attach, admission/config/source/defunct store) | Chip: **“Translation not started — {reason}.”** Reader overlay remains visible until dismissed through Details; no Completed. In-flight duplicate rejection says **“Page is already translating.”** | Auto admission rejection is **“Auto translation not started — {reason}.”** A terminal source/config rejection is not a transient pause. | Request phase uses the existing typed labels: **“Download failed,” “Translation could not be queued,”** or **“Source unsupported.”** Notification is non-ongoing and actionable when it is a request terminal. No page terminal success is added. | “Page {index}: translation was not started because {reason}.” Action is Details, settings/source correction, or Cancel. |
| **PERSISTENCE_REJECTED / not durably saved** (existing `PersistenceRejected` value outcome or guarded publication rejection) | Chip: **“Translation not saved — retry required.”** Reader overlay: **“The translation could not be saved; no translated result was published.”** Do not show Translated/Ready, even if an in-memory candidate contains translated text. | Auto slot becomes a retryable failed/deferred result, never Ready: **“Auto translation could not be saved — retry available.”** A retry is explicit unless the existing coordinator truly schedules one. | Drawer/notification: **“Translation not saved — retry required.”** Progress does not increment the affected page as success; the snapshot carries a non-durable-publication warning. Chapter success copy is forbidden until a later durable commit converges. | “Page {index}: translation was produced but could not be saved; no durable result is available. Retry is required.” Action: **“Retry page/translation.”** This is distinct from provider failure and must not be called Completed. |
| **D9 durable cap pause** (`FailureCategory.INTERRUPTED`, durable `FAILED_RETRYABLE`, no retry epoch) | Chip: **“Paused — repeated interruption before completion.”** Reader overlay: **“Manual retry required.”** Do not say “will retry automatically.” | Auto is refused before paid work; status: **“Auto paused — manual retry required after repeated interruption.”** There is no automatic retry time. | Drawer/notification: **“Paused — repeated interruption before completion; manual retry required.”** Page remains unresolved/pending for the next run; it is not a successful terminal page. Provide the force/manual Retry action, not an automatic retry claim. | “Page {index}: translation paused after repeated interruption; manual retry required.” Action: **“Retry translation”** must call the existing force recovery (`clearAttemptCapForManualRetry`), which clears the cap and `INTERRUPTED` failure before admitting work (`ChapterTranslationStore.kt:422-430`). |
| **CANCELLED** (explicit paid-work cancellation or batch abort) | Chip/overlay: **“Translation cancelled.”** Saved display results remain visible; an in-flight page with no result is not called translated. | Explicit auto stop: reader status/snackbar **“Auto translation stopped.”** Do not silently erase a user-visible paid-work cancellation; ordinary coordinator cleanup after a user action must retain the action acknowledgement. | Drawer/notification: **“Batch translation cancelled — saved pages kept.”** Progress marks each page that the batch contract settles as cancelled/terminal, not succeeded; unfinished pages do not become fake failures. Notification is non-ongoing after cancellation. | “Page {index}: translation cancelled; saved translated pages were kept.” Action: **“Retry translation”** starts a new explicit request, never an automatic retry. |
| **Completed request with no active work** (`FINISHED`, no pages/unknown total) | No page success is inferred from an empty request. | Auto status clears when no identity/slots remain. | Drawer uses **“Completed”** only if a real terminal result exists; otherwise use the request phase/unknown-total label. Never render `0/0` as success. | “Translation request completed with no page total available” when needed; Details explains unknown total. |

### 2.1 Mode-specific progress rules

- **Manual:** There is no chapter progress total for a single tap. Show the page outcome
  and, for an attached request, the owner’s progress. Never convert a manual `Rejected`,
  `AttachedUnresolved`, pause, stall, or persistence rejection into chapter `Translated`.
- **Rolling auto:** `readyAheadCount` is the number of display-ready ahead slots only;
  queued, processing, deferred, failed, and the visible page are excluded, as required by
  `AutoTranslationSnapshot` (`AutoWindowState.kt:222-240`). The label must say **ready
  ahead**, never chapter complete. Auto pause copy includes whether retry is automatic.
- **Batch:** Define `terminalPages` as pages in exactly one current-pass terminal
  category: translated, reused-valid, textless, failed-retryable, failed-terminal,
  cancelled, or an explicitly documented skipped page under the adopted contract. A
  queued, running, buffered, attached-unresolved, persistence-rejected, or missing
  source page is not a successful terminal page. The total is trusted only when the
  source/batch page set is trusted. If not trusted, use an unknown-total label and no
  percentage.
- **No contradictory terminal copy:** If any page has a non-durable publication
  rejection, partial-download fact, unresolved missing source page, or durable failure,
  the chapter cannot use “All pages translated and ready to read.” `READY_WITH_WARNINGS`
  may describe readable committed pages, but the warning must be present in the same
  surface as the status; it cannot be hidden behind a green completion pill.

## 3. Visibility budget, D12 factual copy, and D13 rules

### 3.1 Visibility budget

**Remain silent for self-healing:** stale callback discard, a single epoch-guard retry,
a brief auto deferral that resolves before its configured visibility threshold, internal
reconciliation, and ordinary UI coalescing. These are implementation maintenance, not
user outcomes. They may be logged through existing diagnostics, not shown as failure.

**Must be visible:**

- a choice or admission decision (finish download versus partial translation, source or
  configuration rejection, attachment to another owner);
- a persistent provider/memory/source pause, with reason and retry semantics;
- a D8 stall, with Cancel and safe Retry-after-release affordances;
- a durable failure, including D9's repeated-interruption cap;
- partial download/source-page absence and whether the source total is known;
- explicit cancellation of paid work, including saved-work retention;
- publication/persistence rejection, with no stale success.

**Cross-surface consistency:** one pure mapping owns the wording and severity. A page
chip may be transient, but its reader status, drawer, notification, and accessibility
description must agree on state. A later durable terminal result may replace an earlier
running/paused label; an old callback may never replace it. A committed display with a
failed candidate uses “ready with warnings”/Details rather than a red error over the
readable image, consistent with `PageTranslation.shouldSurfaceError`
(`PageTranslationState.kt:199-220`).

### 3.2 D12 factual corrections

Use the binding D12 facts, not the rejected v2.1 claims: JPEG quality 90, full-stream
SHA-256, live-version cleaned filenames, current textless semantics, correct package
paths, `singleTask`, the actual conflated trigger and tier capacities, current startup
behavior, and the manifest/stage failure vocabulary (`translation-subsystem-coexistence-v3-draft.md:221-222`).
These are implementation facts, not marketing claims. Do not reintroduce any superseded
quality, hash-prefix, lifecycle, or queue wording.

Timeout copy must name the actual timer/phase, not an unrelated duration:

- native work: **“ONNX/native result timer expired; translation stalled or failed.”**
- HTTP/provider plus render: **“HTTP+render result timer expired; translation failed.”**
- a D8 occupancy signal: **“Native result timer expired while the native lane remains
  occupied; translation stalled.”**

Do not write “Translation timed out after …” using a different timer. The existing
placeholder at `PageStoreWriter.kt:124-127` is specifically a defect because it names a
wrong timer. If a configured timer value is ever shown, it must be the value of that
actual timer and must be marked `[TARGET]` until Phase 6 validates the user-facing bound;
the preferred user copy names the timer and omits the duration.

### 3.3 D13 number discipline

- No user-facing latency, throughput, frame-rate, quality, memory, or SLO number may
  appear until the Phase-6 measurement protocol has produced it by device tier and
  percentile. Every unmeasured number in implementation comments, specs, or copy is
  marked **`[TARGET]`**.
- State counts (`{terminal}/{trusted total}`, `{missing}`, queue position, and ready-ahead
  count) are observations and may be shown only with their truth label. They are not
  latency/percentage claims. Do not show a percentage when the total is unknown.
- A progress percentage derived from terminal count is allowed only for a trusted total;
  it must not be described as speed or expected completion time.
- Do not show an ETA, “usually completes in,” “worst case,” or percentage improvement.
- Avoid numeric retry claims. Copy must say **“automatic retry”** or **“explicit Retry”**,
  not “retry in N attempts.”
- A configured watchdog/result timer is a named mechanism, not evidence of observed
  latency. If the product exposes its numeric configuration, label it `[TARGET]` until
  Phase 6 measurements validate it.

### 3.4 Retry language

- **Automatic retry:** “Will retry automatically when available” and optionally
  “automatic retry after {time}.” No Retry button is implied.
- **Explicit retry:** “Retry now,” “Retry page,” or “Retry after {time}.” The user action
  starts a new attempt and must not be described as scheduled/automatic.
- **Manual D9 recovery:** always **“Manual retry required.”** The action must use the
  force path; ordinary Resume is not sufficient.
- **Stall recovery:** Cancel first or wait for the lane release; then the user may choose
  Retry. Never imply that the native call is killed.
- **Terminal failure:** use Review settings/source or Details; do not expose a Retry
  action that cannot be admitted.

## 4. Minimum fix for PersistenceRejected/Failed-as-value

This is a required Phase-5 correctness change, not optional copy polish.

### 4.1 Production change

At the `runGrantedSinglePageBoundary` result handoff in
`app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:480-515`, replace the
“map only `Paused`, then return `Completed`” tail with an exhaustive value mapping.

1. `ChunkCompletionOutcome.Completed` remains `SinglePageOutcome.Completed` only when
   the durable commit/display gate has succeeded or the page is a legitimate terminal
   no-op.
2. `ChunkCompletionOutcome.Paused` remains `SinglePageOutcome.Paused` with its retry
   epoch.
3. `ChunkCompletionOutcome.PersistenceRejected` becomes a visible typed non-success
   outcome, preferably existing `SinglePageOutcome.Rejected(owner = null, reason =
   "Translation could not be saved; retry required")` so no new enum is needed. The
   reason must be stable enough for the pure UI mapper to select the persistence copy.
4. Any `ChunkCompletionOutcome.Failed` or other value failure returned by the HTTP phase
   must become a visible typed non-success outcome with its safe reason, not
   `Completed`. If the D8 implementation adds a typed timeout/failure value, map it
   directly; otherwise use the existing `Rejected` shape as the minimum compatibility
   path.
5. A timeout/null result must use the D8 typed timeout/stall path (or the existing
   `Rejected` compatibility path before D8 is present). It must never fall through to
   `Completed`.
6. Keep the existing `finally` lease release, generation/lease/page-version fences,
   synchronous publication, and fail-closed `PatchResult.Rejected` behavior unchanged.
   Do not turn a failed publication into a fake durable `FAILED` record, do not mark the
   page display-ready, and do not adopt write-behind persistence in Phase 5. The store
   contract remains the synchronous reject-and-restore contract documented at
   `engineering/phase4-design.md:469-475`.

The batch path already preserves `PERSISTENCE_REJECTED` in
`BatchProgressReconciler` (`:137-198`). Carry its `nonDurableFailure` fact and safe reason
through the existing tracker/progress snapshot as a bounded value field (for example,
a boolean plus nullable reason; no enum). The drawer, notification, and progress mapper
must use that fact to show **“Translation not saved — retry required”**, leave the
affected page out of success totals, and forbid `Completed`/“All pages translated” copy.

### 4.2 Required tests for this defect

Write the failing assertions first, then the minimum production change:

- With a fake document publication that rejects, invoke the HTTP/render phase and the
  scheduler. Assert the returned/manual outcome is not `Completed`, is a visible typed
  non-success with a persistence reason, and `manualOutcomes` contains that outcome.
- Assert the page has no new durable display-ready result and the store remains
  fail-closed. A subsequent retry can re-read current state and commit successfully.
- Exercise a typed `ChunkCompletionOutcome.Failed` returned as a value and assert the
  same no-`Completed` rule. Preserve the existing thrown-failure behavior separately.
- Exercise batch `PERSISTENCE_REJECTED`: assert the affected page is pending/non-success,
  the progress snapshot carries the non-durable warning, the terminal success count is
  unchanged, and the UI mapper selects the not-saved copy.
- Assert the D9 ledger is not falsely resolved as a verified durable publication. Keep
  all existing generation, lease, candidate, and page-version rejection tests green.

## 5. Test-first plan for UI-truth assertions

Tests should prefer pure projections and graph-level durable assertions. Android view
classes are not required to be JVM-testable for this phase. Do not use Robolectric and
do not add dependencies. Keep all collections bounded; use the existing manual outcome
cap and auto-window target rather than retaining arbitrary history.

### 5.1 Pure state-to-surface tests

1. Add a pure mapper test covering every row in Appendix A for all three modes. Inputs
   are existing typed outcomes, `PageDisplayProjection`, `Translation.State`, request
   phases, auto slot states, durable failure metadata, partial manifest metadata, and
   the non-durable publication flag. Assert exact semantic label, severity, retry mode,
   action set, and accessibility description. Test identity mismatch and stale-terminal
   precedence.
2. Extend reader feedback tests (`ReaderTranslationFeedbackTest`) for retryable versus
   terminal failed copy, partial-with-committed-result suppression, attached,
   attached-unresolved, paused with/without time, stalled, persistence rejection, and
   cancellation. The coalescer may delay short stage changes, but terminal/pause/stall
   feedback is immediate and cannot be replaced by an old stage.
3. Extend auto projection/status tests (`ReaderAutoTranslationUiStateTest` and
   `AutoWindowStateTest`) for queued/stage/ready/deferred/failed retryable/failed
   terminal, retry-time retention, identity/version fencing, and ready-ahead numerator
   exclusion. Assert that a deferred slot is never Ready.
4. Extend `BatchHeroProjectionTest`, `TranslationProgressSheetSubtitleTest`, and
   `TranslationQueuePositionAndPhasesTest` for accepted, waiting, download failed,
   preparing, queued-behind, paused with retry time, D9 pause without time, partial
   known/unknown totals, persistence rejection, cancelled, and terminal completion.
   Assert no fake `0/0`, no “all translated” on warning/rejection, and no percentage with
   an unknown total.
5. Add a pure accessibility-label test for chapter indicator, page mini chip, reader
   page feedback, auto status, drawer hero, and notification content/action labels.
   Every visual state must have a non-empty semantic label independent of color.

### 5.2 Graph and durable-state tests

1. Add a Phase-5 coexistence graph test that drives manual tap against batch-owned work
   and asserts `Attached`/`AttachedUnresolved` in the bounded outcome projection, no
   duplicate paid call, and convergence to the owner’s durable result.
2. Assert the real scheduler/manager projection exposes the last bounded manual outcome
   without making the private map unbounded. The test must distinguish `Paused`,
   `Rejected`, persistence rejection, and `Completed`.
3. Assert rolling auto `pausedTranslations`/slot projection retains reason and retry
   epoch, releases only when eligible, and shows an explicit action only when the user
   must act. A brief retry that self-heals may produce no user notification.
4. Assert manifest truth for `FAILED_RETRYABLE`, `FAILED_TERMINAL`, `PARTIAL`, D9
   `INTERRUPTED`, and `expectedPageCountTrusted`. Use existing `StoreStatusProjector`
   and `BatchProgressReconciler` graph seams; never infer durable truth from a UI
   callback.
5. Assert progress convergence for manual+batch, manual+auto, and batch+auto pairs:
   UI may be ahead only during publication, then must equal the durable projection. A
   stale candidate, late callback, or publication rejection cannot publish success.
6. Retain and run `NormalMangaIsolationTest`: translation-disabled manga must never
   enter these mappings, arbitration, storage observation, or extra decode paths.

### 5.3 Test constraints

- No sleeps or wall-clock waits in pure tests; use virtual time only for a pure watchdog
  mapper/timer test. Graph tests use existing barriers.
- No real provider calls. Provider/governor calls are fake and paid-call counts remain
  exact.
- Every test names the durable or typed oracle it asserts; avoid log-string-only tests.
- Use a bounded fixture: one chapter, a bounded page list, bounded auto slots, and the
  existing bounded manual outcome map.

## 6. Acceptance criteria and exact implementation commit order

### 6.1 Acceptance criteria

Phase 5 is accepted only when all of the following are true:

1. Every v3 §7 outcome—completed, attached-to-owner, queued-behind, rejected, failed,
   translated/reused-valid, skipped where contractually permitted, failed-retryable,
   failed-permanent, and cancelled—has a demonstrated mapping for manual, rolling auto,
   and batch contexts, including page chip, reader status/overlay, batch
   queue/notification, progress treatment, and accessibility description.
2. `PersistenceRejected` and failed-as-value can never reach UI as `Completed` or any
   success copy. The store remains fail-closed; no non-durable candidate is displayed as
   a durable translation; retry can recover.
3. Stalled state is driven only by the typed Phase-4 occupancy signal and names the
   actual native/result timer. No native call is killed. Cancel and safe explicit Retry
   semantics are visible.
4. Pauses state their reason and retry time when known. Copy explicitly says whether
   retry is automatic or explicit. D9 says **manual retry required** and force-clears
   the cap; it is never presented as an automatic retry.
5. Partial downloads present the finish-first/translate-subset decision. Known source
   totals are labeled trusted; unknown source totals are labeled unknown. Missing pages
   are never silently counted as translated or fabricated as attempted failures.
6. Batch progress and notifications use terminal page states only. Queued, running,
   buffered, attached-unresolved, persistence-rejected, and unknown-total preparation do
   not increase successful totals. No unknown-total surface renders `0/0` or a percentage.
7. No surface contradicts a newer durable state, and no stale success remains after a
   durable failure/rejection. A committed readable result may remain visible with a
   warning for a failed refresh, but the warning is discoverable.
8. Accessibility descriptions match visible state/action semantics for every chip,
   page mini chip, overlay/status, drawer hero, notification, and action. No state is
   conveyed by color alone.
9. All new/updated tests are test-first RED-for-the-named-reason before implementation,
   then GREEN. No Robolectric, dependencies, Gradle configuration, or Android-version
   increase. Android 8.0+ and bounded-memory constraints remain intact.
10. Existing translation tests, coexistence tests, persistence-fence tests, notification
    tests, and normal-manga isolation remain green at the Phase-5 gate. The Implementer
    records the Phase-5 result and Reviewer verifies the UI-truth matrix.

### 6.2 Exact commit order for the Implementer

Every commit compiles, is independently revertible, and follows the required RED then
GREEN pair. Do not combine a production fix with the tests that prove it was needed.

1. **`t917(p5): persistence outcome red tests`** — add the minimum manual and batch
   publication-rejection/failed-as-value assertions from §4.2. Capture the failure that
   currently reports `Completed`; keep the existing fail-closed store assertion.
2. **`t917(p5): preserve typed non-success persistence outcomes`** — make the exhaustive
   `TranslationPipeline` outcome mapping and the bounded batch snapshot warning. No
   UI copy changes yet. Verify no `Completed` on timeout/rejection/value failure.
3. **`t917(p5): outcome projection red tests`** — add pure Appendix-A mapping tests,
   manual outcome exposure tests, auto retry-epoch tests, and identity/stale-success
   tests. Include attached, attached-unresolved, rejected, paused, stalled, D9, partial,
   cancelled, and both failure classes.
4. **`t917(p5): expose bounded UI-truth projections`** — expose read-only manual outcome
   values, retain bounded auto retry metadata, and implement the pure state-to-surface
   mapping using existing typed outcomes/durable fields. Add no new state enum.
5. **`t917(p5): terminal progress red tests`** — add failing tests for terminal-only
   totals, trusted versus unknown total labels, cancelled/partial/persistence-rejected
   counts, queue position, and no fake `0/0`.
6. **`t917(p5): make batch totals terminal and honest`** — update tracker/reconciler/
   notification/drawer projections to use terminal states and the non-durable warning;
   preserve complete-chapter behavior where the source total is trusted.
7. **`t917(p5): copy and accessibility red tests`** — add exact semantic copy tests for
   retry mode, timeout timer names, D9, partial download, stall actions, chapter warning
   labels, page mini chips, and notification actions.
8. **`t917(p5): render unified copy and accessibility`** — update string selection and
   all listed surfaces. Keep page stage coalescing, make terminal/pause/stall/rejection
   visible, add content descriptions/semantics, and remove stale success wording.
9. **`t917(p5): visibility precedence red tests`** — test self-healing silence versus
   visible decisions/pauses/stalls/durable failures/partial download/paid cancellation,
   candidate failure with committed display, and newer durable state over old callback.
10. **`t917(p5): enforce visibility budget and stale-state precedence`** — wire the pure
    precedence in reader, manga, drawer, and notification callers; ensure explicit
    cancellation acknowledgement and no contradictory surface.
11. **`t917(p5): phase gate record`** — record the complete test matrix, normal-manga
    isolation result, reviewer findings, and checkpoint result. Do not claim performance
    numbers; list all pending Phase-6 measurements. Tag `checkpoint/t917-p5-done` only
    after the required full touched-module test gate and Reviewer acceptance.

## 7. Phase-6 measurements that must validate this product contract

Phase 6 must measure before any number is promoted from `[TARGET]` or used as an SLO:

- low/mid/high device tiers within the supported device class, with warm and cold runs
  and the fixed corpus;
- p50/p95/p99 duration for native OCR/inpaint, HTTP/provider, render, and the complete
  manual/auto/batch chain; compare the observed distributions with the configured
  ONNX/native result timer, HTTP+render result timer, attach bound, provider drain grace,
  and occupancy watchdog threshold;
- frequency and duration of genuinely hung native occupancy, false-stall rate, safe
  release/retry behavior, and whether stall copy appears before or after the user has a
  meaningful decision;
- D6 interactive-reservation fairness: foreground wait behavior, background throughput,
  provider-window utilization, paid-call count, and retry/cancellation effects;
- D9 ledger correctness under process death, in-app cancellation, slow providers, and
  repeated reader-close patterns; confirm cap visibility and force recovery without
  re-billing on refusal;
- D10 admission overhead and correctness while downloading, known versus unknown source
  totals, partial-to-complete convergence, and no fake missing-page attempts;
- D11 staged permit-free publication effect, SAF/document latency, memory/GC behavior,
  and whether full write-behind is justified. Phase 5 does not enable the deferred
  write-behind core;
- peak heap/native memory and bounded state retention during long auto windows and
  multi-chapter batches, including notification/status update frequency;
- reader frame/render stability and translation-disabled normal-manga isolation;
- UI convergence latency from durable commit/rejection to each surface, without turning
  that observation into user-facing latency copy before measurement.

All measured values must be recorded by device tier and percentile before replacing
`[TARGET]` in user-facing or SLO text. Until then, the only honest user claims are the
state facts and terminal counts defined in this specification.
