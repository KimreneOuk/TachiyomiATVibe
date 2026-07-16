# Design - Unified Translation Pipeline Recovery

## Selected approach

Use one shared set of page-stage operations and three orchestration shapes:

- Manual/auto: one-page orchestration over the shared stages.
- Pre-translation: chapter OCR producer, serialized provider consumer, all-OCR
  barrier, serial inpaint sweep, per-page render joins, and final reconciliation.
- Semantic revision: a separate user-triggered, manager-owned text-only
  orchestration over any durable chapter source/draft pairs.

Do not patch the existing streaming coordinator in place. Its planner acceptance,
bitmap lifetime, barrier, and completion semantics are the source of the current
failure and duplicate behavior already implemented by the reader path.

## Required stage operations

Keep the public `TranslationExecutor` entry points initially, but make their
implementation delegate to internal shared operations in `TranslationPipeline`
or a small collaborator owned by it:

1. `runOcrStage`
   - Resolve resume/force policy.
   - Decode one page under native quarantine.
   - Detect and OCR only through the existing analysis path.
   - Persist dimensions, blocks, structural metadata, and `inpaintMaskBoxes`.
   - Commit terminal OCR state.
   - Recycle the decoded bitmap and reclaim native pools before returning.

2. `runPass1Stage`
   - Snapshot the latest OCR-owned blocks.
   - Build one page-context request using ordered blocks and request-local IDs.
   - Split only if that page exceeds provider limits.
   - Invoke one serialized provider lane.
   - Merge immutable results into the current store page with target-specific
     preconditions and run `TranslationBlockValidation`.

3. `runInpaintStage`
   - Start only after the relevant OCR barrier.
   - Re-decode the page under native quarantine.
   - Consume persisted masks; do not depend on transient OCR engine state.
   - Publish a verified versioned cleaned file and atomically merge only
     inpaint-owned fields.
   - Recycle all bitmaps in `finally`.

4. `runRenderJoin`
   - Observe or await translation and cleaned-image readiness for one page.
   - Keep the original image when translation failed or is blank.
   - Recompute render colors once and atomically merge render-owned fields.
   - Use one shared implementation for manual, auto, and batch.

5. `runPass1Quality`
   - Preserve readable contextual-provider drafts and persisted `[OK]`/`[FLAG]`
     self-reporting.
   - Apply bounded validation/output retry for missing or invalid targets.
   - Never start semantic review implicitly.

Names are illustrative. Prefer the smallest extraction that makes these units
single-sourced and plain-JVM-testable where Android types are not required.

## Work references and memory ownership

The OCR producer must never queue a `PageTranslation`, bitmap, input stream, or
native handle for every chapter page. Queue a small immutable reference:

```kotlin
data class OcrReadyPageRef(
    val pageKey: String,
    val pageIndex: Int,
    val generation: Long,
    val blockFingerprints: List<String>,
)
```

The provider consumer resolves a detached current snapshot immediately before
building the request. The queue is bounded by the finite expected chapter page
count and stores only references/preconditions, allowing OCR to finish even when
the provider is slow. Enqueue occurs after bitmap/native release, so it cannot
hold native resources while waiting.

Do not use a fixed capacity of two: that converts provider latency into an OCR
barrier and violates the selected all-OCR-first behavior.

## Atomic stage merges

Whole-page `pageVersion` equality is too coarse when translation and inpaint may
commit independently. Add explicit store operations or a generic stage-patch
primitive that merges into the latest owned page under the store mutex.

Required preconditions by stage:

- All stages: live store, matching run generation, expected page key.
- Translation Pass 1: expected OCR block fingerprints and source text; preserve
  any newer user edit or translation.
- Inpaint: matching OCR/inpaint schema, mask fingerprint or equivalent durable
  OCR identity; do not modify blocks or translation status.
- Render: matching cleaned-image identity and relevant block fingerprints; do
  not modify OCR, translation, or inpaint state.
- Revision: existing generation, page/block fingerprint, source, draft, expected
  prior `needsRevision` state, and `userEditedAt` checks remain mandatory.

Every accepted merge assigns a new page version. A page-version change caused by
an unrelated stage is not itself a failure if all stage-relevant preconditions
still match. Do not implement blind last-write-wins or retry an arbitrary whole
page copy.

## Chapter pre-translation schedule

### Remote providers

```text
coroutine A: ordered OCR producer
  for each expected page:
    run shared OCR stage
    release bitmap/native resources
    queue OcrReadyPageRef if translation is needed
  close queue
  publish all-OCR barrier

coroutine B: one provider consumer
  consume refs in reading order
  run one page-context Pass 1 per ref
  record actual request start and terminal result

after all-OCR barrier:
coroutine C: ordered serial inpaint sweep
  re-decode and inpaint eligible pages
  signal per-page native branch completion

per-page join:
  render when translation and inpaint are terminal and display prerequisites hold

final:
  await inpaint and render; reconcile every ordered key; publish summary
```

The provider lane may lag behind OCR and inpaint. The finite queue must not block
OCR. Rendering exposes valid drafts immediately; optional review is a separate
later operation.

### ML Kit

ML Kit is local compute. Complete the chapter OCR sweep first, then serialize
local translation and inpaint so they do not compete for the same device budget.
Prefer per-page `translate -> inpaint -> render` after the OCR barrier for earlier
page availability, while maintaining one active local/native operation.

## Pass-1 request contract

- One page per normal request.
- Ordered current-page OCR blocks only; no rolling chapter context or glossary.
- Preserve the existing sorter order. When reliable `panelAssignment=owned`
  membership changes, emit a compact `P<n>` header once for that group. Use
  `P?` only for an explicit unassigned group; omit headers when no reliable panel
  context exists.
- One short prompt rule defines panel headers as local layout context, never
  speaker identity. Do not add bubble/speaker inference.
- Stable request-local IDs map explicitly to page key and block index.
- Contextual providers return ID, text, parse status, and `[OK]`/`[FLAG]` quality.
- Tagless valid lines remain readable but become revision targets.
- Unknown, duplicate, blank, malformed, and missing IDs are rejected without
  positional fallback.
- An oversized page is split deterministically by ordered targets and token
  budget. The page is `READY`, `PARTIAL`, or `FAILED` only after all subrequests
  are merged/accounted.

## Provider capabilities

Add an explicit capability rather than inferring support from engine category:

```text
CONTEXTUAL_REVISION: Gemini, OpenRouter, DeepSeek, LM Studio
VALIDATION_RETRY_ONLY: Google, DeepL, ML Kit
```

`ContextualTextTranslator.translateContextualStructured` becomes mandatory. The
OpenAI-compatible base should centralize request building, parser invocation, and
structured result construction while subclasses retain provider-specific URL,
authentication, payload, and response extraction.

Plain translators continue translating detached targets, but an adapter maps
ordered output back to the common immutable page result. They never mutate the
store directly.

The provider that produced a draft and the provider selected to review it are
separate concepts. Google, DeepL, and ML Kit cannot be reviewers, but their
durable source/draft pairs may be reviewed by a contextual provider chosen and
confirmed by the user. Never select that reviewer silently.

Use `withTranslationRetry` around real provider calls for transient transport
errors. Output retry is separate, bounded, target-specific, and preserves valid
results from the first attempt.

## Standalone semantic revision

Semantic revision is explicit and optional. Batch completion never waits for it.
`TranslationManager` owns the job, admission, cancellation, progress, and report.
`ReaderViewModel` and `MangaScreenModel` only send immutable user intents and
observe immutable state.

### Scope

```kotlin
enum class RevisionScope {
    FLAGGED,
    ALL_TRANSLATED,
}
```

- `FLAGGED`: `needsRevision`, no user edit, non-blank source and draft.
- `ALL_TRANSLATED`: no user edit, non-blank source and draft, regardless of the
  historical flag or original translator.
- Missing/blank/source-equal drafts remain validation/retry work, not semantic
  targets.
- Review may run with one eligible page or an incomplete chapter. It touches only
  the durable pages present when the run snapshot is accepted.
- A later run can include pages translated after the earlier snapshot.

### Pure UI/backend contract

Names are illustrative; keep these models immutable and free of Android, stream,
bitmap, store-owned block, provider client, and callback references:

```kotlin
data class ChapterRevisionEligibility(
    val chapterId: Long,
    val eligibilityVersion: String,
    val translatedPages: Int,
    val expectedPages: Int?,
    val flaggedTargets: Int,
    val allTranslatedTargets: Int,
    val userEditedExclusions: Int,
    val reviewerOptions: List<ReviewerOption>,
    val persistedSourceLanguage: String?,
    val persistedTargetLanguage: String?,
)

data class RevisionPreflightRequest(
    val chapterId: Long,
    val scope: RevisionScope,
    val reviewerId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val eligibilityVersion: String,
)

sealed interface RevisionPreflightResult {
    data class Ready(val confirmation: RevisionConfirmation) : RevisionPreflightResult
    data class Rejected(val reason: RevisionRejectionReason) : RevisionPreflightResult
}

data class StartRevisionRequest(
    val confirmationToken: String,
)
```

`RevisionConfirmation` contains display-safe reviewer/model identity, scope,
translated/expected pages, target and exclusion counts, deterministic request
group estimate, language pair, partial-context warning, and no credentials.
`RevisionRejectionReason` is a typed reason plus non-localized arguments; the UI
maps it to strings.

Reviewer options come only from configured contextual AI profiles. Persist a
separate last-selected revision reviewer identifier for convenience; default to
the current AI translator only when it is configured and capable. The confirmation
always names the reviewer/model, so a stored default is never a silent switch.

The backend owns all target discovery. The frontend never supplies block IDs,
text, flags, fingerprints, page objects, or counts. `startRevision` re-runs every
preflight check and rejects a stale token rather than trusting displayed state.
Mint the opaque token from chapter ID, store generation, scope, ordered target
source/draft/fingerprint/flag/edit preconditions, language pair, and reviewer
configuration fingerprint. Credentials are excluded. Any input change invalidates
the token.

### Eligibility and confirmation

The manga screen and reader use the same manager preflight. Eligibility is
independent of aggregate `Translation.State` and can be true for a partial or
legacy chapter. A cold store is opened from durable JSON without image decode.

Preflight rejects or reports:

- no source/draft targets for the selected scope;
- contextual reviewer missing, unconfigured, or unsupported;
- source/target language absent until the user confirms it;
- active manager-owned chapter batch for the same chapter;
- another revision already active;
- every target individually over budget;
- stale/deleted/defunct chapter state.

Preflight warns, but does not reject, when only part of the chapter is translated.
It shows the estimated number of bounded reviewer requests, not an unverified
currency estimate.

### UI placement and behavior

- Primary: `ChapterTranslationIndicator` menu -> `Review translations` whenever
  manager-derived eligibility is known. Do not require a downloaded image for a
  text-only review.
- Partial manual/auto chapters must publish enough summary/eligibility state for
  the manga screen to expose the translation indicator even when no batch ran.
- Secondary: reader `TranslationSettingsSheet` -> `Review this chapter`.
- Both entry points open the same confirmation state and dispatch the same manager
  request.
- Default to `FLAGGED` when flagged targets exist; otherwise default to
  `ALL_TRANSLATED`. Never start on sheet open or dismissal.
- If no contextual reviewer is configured, keep the action discoverable and show
  a typed setup-required state with an explicit settings navigation action.
- Disable confirmation until required legacy language fields are selected and a
  fresh preflight returns `Ready`.
- During a run, replace start actions with details/cancel for that chapter and
  reject duplicate taps at the manager boundary as well as the UI boundary.
- While active, the existing chapter indicator/progress sheet and reader sheet
  show revision progress. Closing either UI does not own or cancel the job.
- A terminal result sheet reports kept, corrected, unresolved, rejected/stale,
  over-budget, and user-edited counts and lists accepted before/after changes by
  page/region.
- Keep `View last review` in the chapter translation menu while the bounded report
  exists; deleting translation artifacts removes that report.

The UI renders state and sends intents only. It does not infer provider
capability, target counts, partial status, or whether a result may be committed.

### Reviewer protocol

Use ordinary text completion for all contextual reviewers. Do not send tool or
function declarations, request chain-of-thought, or allow provider-side access to
application state.

Use the configured model/token limit through a review-specific adapter and a low,
deterministic review temperature supported consistently by the provider family.
Do not expose provider credentials in requests crossing the manager/UI boundary.

Pass 1 remains `ID|Translated Text|[OK]/[FLAG]`. Revision uses a separate strict
line protocol:

```text
r0|K
r1|C|Corrected target-language text
r2|U
```

- `K`: keep the exact current draft. Clear an existing flag after a valid commit.
- `C`: apply a non-blank correction and clear the flag.
- `U`: keep the draft and set/retain `needsRevision=true`.
- Every requested ID must appear exactly once.
- Unknown, duplicate, missing, tagless, malformed, or blank-`C` results are
  rejected and retain the draft/flag.
- Split corrected lines with a bounded column split so literal `|` characters in
  corrected text cannot shift identity/status columns.
- Never treat omission as `K` and never map results positionally.

The application selects targets. The reviewer decides only `K`, `C`, or `U`
using source fidelity, target-language naturalness, glossary consistency, nearby
source/drafts, and compact panel boundaries. It must use `U` instead of inventing
speaker, gender, subject, or missing OCR meaning.

### Revision context and token bounds

- Preserve stable chapter/page/block reading order.
- Include source and draft for every output target.
- Include glossary and bounded nearby source/draft lines as context-only IDs.
- Include compact page-scoped panel headers when reliable metadata exists.
- Keep the existing maximum-target and prompt-token accounting; split before
  exceeding the provider budget.
- Do not split a target. Individually over-budget targets remain unresolved and
  appear in preflight/report accounting.
- `ALL_TRANSLATED` may require many groups; confirmation shows the deterministic
  group estimate before dispatch.

### Commit and report semantics

Revision uses detached snapshots and the existing strict merger/committer pattern.
Every target commit checks live store, run generation, page/block fingerprint,
source, draft, prior flag state, and `userEditedAt`. A change in any relevant
precondition rejects the result. `K`, `C`, and `U` are committed atomically so the
flag transition cannot race the fingerprint check.

Accepted revision changes text/flags only. `PageView.overlayFingerprint` refreshes
pager/webtoon overlays without image decode, inpaint, cleaned-file rewrite, or
rendered bitmap retention.

Persist exactly one atomic latest-run revision-report sidecar containing the
reviewer/model display identity, language pair, scope, timestamps, exact terminal
counts, and accepted before/after changes. A new run replaces it; chapter
translation deletion removes it. Do not keep unbounded history. Existing page JSON
remains backward compatible.

### Partial and legacy chapters

- Manual, auto, and batch stages update a compatible chapter summary so cold UI
  eligibility does not require scanning every page on every composition.
- New summary metadata records optional source/target language and draft provider.
- Keep `ChapterTranslationSummary.FORMAT_VERSION=1`; add new optional fields with
  serialization defaults so existing version-1 summaries remain readable. Give
  the separate revision-report sidecar its own version.
- Legacy defaults remain readable. When language metadata is absent, preflight
  requires confirmation and may backfill only the summary after successful start.
- The original provider does not limit review eligibility. A DeepL/Google/ML Kit
  draft may be reviewed by an explicitly selected contextual provider.
- `FLAGGED` may have zero legacy targets; `ALL_TRANSLATED` remains available.
- Translation `PARTIAL` pages contribute only their non-blank drafts. Revision
  does not claim to repair missing translations.

### Admission, cancellation, and lifecycle

- Add one provider-request admission shared by manual, auto, batch, and revision;
  sequential loops scoped to one operation are not sufficient.
- One revision job per chapter. A second start is rejected with active-run state.
- An active chapter batch blocks standalone revision. Manual/auto work for that
  chapter drains its current provider request, pauses new auto admission, then
  revision snapshots and runs; auto work may resume afterward.
- Explicit cancel preserves already committed atomic patches, rejects late
  results through generation invalidation, flushes state, and emits a cancelled
  report. It never rolls back accepted corrections.
- Reader close/switch, display disable, background, and screen-off do not cancel
  revision.
- Critical memory cancels the text-only revision safely and reports interruption;
  do not automatically replay a possibly billed provider request. The user may
  rerun remaining flagged/all targets.
- Delete marks the store defunct and rejects every late commit/report write.

## Resume and terminal semantics

- Share `BatchResumeGateDecider` or its extracted successor across every entry
  path, including physical cleaned-file checks.
- OCR-ready pages with current masks skip OCR and queue only missing Pass 1 work.
- Translation-ready/partial pages skip Pass 1. Persisted flags affect optional
  review eligibility, not batch completion.
- Current cleaned images skip inpaint; missing/stale files re-enter inpaint.
- Textless pages remain original and downstream translation/render are `SKIPPED`.
- Every expected page must be terminal before chapter reconciliation; absent,
  cancelled, or incomplete keys become explicit failures.

## Lifecycle ownership

- `TranslationManager`/`ChapterTranslator` own chapter batches and standalone
  revision jobs.
- Reader display preference owns display and reader manual/auto scheduling only.
- Reader chapter switch owns page jobs and stream closures only; it must not call
  `cancelQueuedTranslation` for a manager-owned batch.
- Reader open/repair must query active generation ownership before changing old
  `PENDING`/`RUNNING` state. Prefer moving stranded repair into manager-owned
  reconciliation rather than guarding UI code.
- Reader pause/finish and app background/screen-off leave batch/revision running.
- UI-hidden/background trim may release disposable caches but not cancel work.
- Explicit critical memory policy cancels a batch generation, releases pools,
  leaves the chapter queued, and restarts the batch on foreground. It cancels a
  standalone revision terminally without replaying a possibly billed request.
  Use explicit level classification, not numeric `>= RUNNING_LOW` ordering.
- Explicit cancel/delete invalidates the generation, joins bounded work, flushes
  state, disposes trackers, marks deleted stores defunct, and rejects all late
  commits.

## Progress model

Keep typed reducer events, but events must correspond to actual operations:

- Before OCR barrier: OCR and remote translation may both be active.
- After OCR barrier: inpaint, translation, and render may overlap.
- Provider requested/completed events wrap the provider call, not queue/planner
  acceptance.
- Failures count as processed and remain visibly failed.
- Standalone revision reports exact `K`, `C`, `U`, rejected/stale, over-budget,
  cancelled, and user-edited targets independently of batch progress.
- Batch terminal summary is published only after ordered-key reconciliation.
- Readable drafts with unresolved flags produce `READY_WITH_WARNINGS`; this is a
  valid batch terminal outcome and enables optional review.

## Alternatives rejected

### Patch the inactivity flush only

Rejected because it leaves page-interleaved native work, false provider events,
coarse barriers, duplicate manual/batch behavior, and reader lifecycle ownership.

### Reuse the current fused manual function unchanged

Rejected because fused recognize/inpaint cannot provide an all-chapter OCR
barrier. Shared stage operations are the reusable unit.

### Keep multi-page Pass-1 streaming

Rejected by user decision. It reduces request count but reintroduces chunk/page
ownership, inactivity flushing, partial completion, and cross-page ID complexity.

### Capacity-2 translation channel

Rejected because a slow provider blocks the OCR sweep. The new queue stores only
small references and is naturally bounded by expected chapter pages.

### Use standard translators as semantic reviewers

Rejected because their APIs cannot consume source, draft, chapter context, and
return a reasoned correction. Their supported translation contract is validation
and bounded retry. This does not prevent an explicitly selected contextual AI
reviewer from reviewing a durable draft originally produced by a standard
translator.

### Automatic semantic revision

Rejected because it creates unconfirmed provider cost, cannot reliably use full
context for partial manual/auto chapters, and couples batch terminal state to an
optional quality operation. Pass-1 validation and self-reporting remain automatic.

### Tool calling or model-selected targets

Rejected because tools differ across providers, add tokens, and would let the
provider blur application-owned target/admission rules. The application selects
targets; the reviewer returns strict text decisions only.

### Infer speakers from panels

Rejected because a panel may contain multiple speakers. Compact panel headers are
layout context only and preserve current sorted order.

## Expected trade-offs

- Re-decoding for inpaint adds storage I/O and decode CPU but removes long-lived
  bitmaps and enables the hard OCR barrier.
- One request per page increases provider request count and repeated prompt
  overhead compared with multi-page chunks, in exchange for simple ownership,
  resume, retry, and progress semantics.
- `ALL_TRANSLATED` review can issue many optional requests; preflight makes count,
  coverage, model, and language explicit before the user confirms.
- A latest-run before/after report adds a small bounded sidecar or terminal cache
  but makes model changes auditable without retaining images or unbounded history.
- Splitting manual/auto fused native work into shared OCR and inpaint stages is a
  behavior-preserving internal rewrite with regression risk; checkpoint gates
  require parity tests before batch wiring proceeds.
