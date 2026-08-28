# T903 failure-mode audit — batch pre-translation

**Tree audited:** committed `HEAD 926ae00` (T902 landed). Research only; no
source changes made.

Evidence labels used below: **VERIFIED** = directly established by current
source/tests; **STRONG INFERENCE** = the source establishes the mechanism and
the user-visible consequence follows from normal execution; **UNKNOWN** = the
repository cannot establish the runtime condition. Severity and likelihood are
separate.

## Executive verdict

The current batch scheduler does **not** fire multiple batch Gemini envelopes
concurrently: the coordinator waits for the current translation/render barrier
before discovering the next chunk (`SequentialBatchCoordinator.kt:322-323,
329-386`). The more damaging behavior is a combination of:

1. no global RPM/TPM pacing or shared cooldown;
2. HTTP retry confined to the current request, with no effective envelope retry
   for a failed/partial response;
3. a failed page becoming a natural-order context gap that blocks the chapter
   tail; and
4. the batch resume path explicitly skipping `StageDecision.FAILED` pages.

That explains the Gemini-free-tier symptom: the first quota burst can turn a
single envelope failure into an `ERROR` chapter, while a later Start action can
repeat the native work without making a provider call for the failed page.

## Severity × likelihood ranking

| ID | Severity | Likelihood | Finding / user-visible symptom |
|---|---|---|---|
| F1 | **CRITICAL** | **HIGH with free-tier 429s** | One exhausted/terminal envelope creates a durable context gap; the envelope pages and then the chapter tail become failed, and subsequent batch runs skip failed translation pages. The user sees a large error tail and no effective resume. |
| F2 | **HIGH** | **HIGH** | There is no global Gemini rate limiter. Batch requests are back-to-back at barrier release, and reader/manual requests bypass the nominal process-wide provider mutex. Free-tier 429s therefore recur or overlap with reader calls. |
| F3 | **HIGH** | **HIGH** | Gemini `Retry-After` is only parsed as integer seconds, capped at 30 seconds, and never propagated to other requests. Missing/long quota hints therefore result in short retries or an early retry while the quota window is still closed; no daily-cap circuit breaker exists. |
| F4 | **HIGH** | **MEDIUM-HIGH** | Adaptive AI retry plans are logged but not executed. A response with missing blocks is committed as `PARTIAL` (blank regions rendered) or, if no blocks translated, `FAILED`; batch does not re-request the missing blocks. |
| F5 | **HIGH** | **MEDIUM** | A failed chapter remains in the in-memory queue as `ERROR`; a retry tap while another chapter is running can be a no-op because `queueChapter` refuses the existing entry and `start()` refuses to mutate an already-running translator. The error indicator still exposes Start, with no feedback. |
| F6 | **HIGH** | **MEDIUM** | `BatchOomPolicy` and the `aborted` path are not wired. A native inpaint `OutOfMemoryError` is not caught by the batch `Exception` handlers, can abort the whole chapter, and can leave the live progress tracker without a terminal snapshot. |
| F7 | **MEDIUM-HIGH** | **MEDIUM** | Process death does not auto-resume work: the foreground service is `START_NOT_STICKY`, while queue restore only reconstructs `QUEUE` entries and deliberately does not start OCR/LLM. Artifacts survive, but the user must start again. |
| F8 | **MEDIUM** | **LOW-MEDIUM** | Queue restore is launched asynchronously without a mutation lock. A user enqueue racing startup restore can be overwritten in memory and in `SharedPreferences`. |
| F9 | **MEDIUM** | **CONDITIONAL** | A reader-owned page is skipped once by batch lease arbitration; the coordinator advances without an in-pass rescan. If that page has no committed output, reconciliation reports `ERROR`, while the attempted stranded-page failure write has no batch lease and is rejected. |

## F1 — failed envelope poisons the resumable chapter

**Classification:** VERIFIED mechanism; STRONG INFERENCE for the stated Gemini
429 user symptom. **Severity:** CRITICAL. **Likelihood:** HIGH on a free tier.

### Root-cause chain

* Gemini wraps HTTP errors as `GeminiApiException`, and the shared HTTP retry
  makes at most three attempts (`TranslationRetry.kt:26-36, 53-66`). When those
  attempts exhaust, `translateAiChunkWithAdaptiveRetry` catches the exception,
  logs a terminal page-atomic failure, and returns an empty delta map instead of
  throwing (`AiTranslationRetryController.kt:72-85`). The batch caller invokes
  this once with `retryDepth = 0` and does not use the returned map to initiate a
  second envelope (`TranslationPipeline.kt:1802-1811`).
* The chunk page objects therefore retain blank/untranslated blocks. Validation
  classifies zero translated blocks as `FAILED` and charges an attempt
  (`TranslationBlockValidation.kt:75-100`). The batch commits that status and
  records it as a terminal context failure (`TranslationPipeline.kt:1840-1882`).
  The private `markBatchTranslationFailed` helper also sets a page to `FAILED`,
  records an attempt, and persists the failure (`TranslationPipeline.kt:2748-2768`),
  but it is used for gap/planner rejection paths; the HTTP exhaustion itself is
  converted to validation failure by the controller above.
* `BatchContextFrontier` records a non-textless failed page as a gap and makes
  every later natural-order page ineligible for AI (`BatchContextFrontier.kt:50-79`).
  The batch path then persists later pages as `FAILED` with the blocked-gap
  reason (`TranslationPipeline.kt:1732-1755, 2482-2499`). At chapter finish,
  any failed page makes the reconciliation state `ERROR`
  (`BatchProgressReconciler.kt:53-89`).
* The next run does not repair the failed provider stage automatically. The
  planner represents persisted `FAILED` evidence as `StageDecision.FAILED`
  (`PageWorkPlanner.kt:184-188`), and `TranslatorLaneWorker.translate` treats
  `FAILED` as `shouldSkipTranslation`; it explicitly says a failed page never
  invokes the provider (`TranslationPipeline.kt:2421-2454`). This is independent
  of the in-memory `attemptCount` reset on process restart.

### What is preserved

Pages completed before the failed envelope are committed through guarded page
updates and render merges (`TranslationPipeline.kt:1854-1883,
1629-1684`). Aborting a candidate cancels only the live candidate generation;
it does not delete the prior committed bundle (`ChapterTranslationStore.kt:438-449,
1730-1743`). Thus the durable prefix is salvageable/reusable, but the failed
page and its blocked tail are not self-healing. The practical recovery currently
requires an explicit stage/data reset or an equivalent re-admission path; a
plain Start/resume does not suffice.

### User observation and direction

Likely observation: a few pages may be translated, then a quota error appears,
the remaining pages become failed/blocked, and retrying appears to do OCR or
progress work without successfully sending the failed page again. Direction:
separate transient provider failure from a permanent context gap, make failed
pages retryable with a bounded explicit policy, and permit a later run to
re-admit the provider stage.

## F2 — no global free-tier pacing; batch and reader admission diverge

**Classification:** VERIFIED. **Severity:** HIGH. **Likelihood:** HIGH.

`SharedProviderRequestAdmission` is only a coroutine `Mutex` and has no time,
token, or quota accounting (`TranslationStageContracts.kt:190-206`). The
current batch call sites are the standard per-page path
(`TranslationPipeline.kt:2521-2529`) and the AI envelope path
(`TranslationPipeline.kt:2569-2577`). There is no minimum inter-request delay or
RPM/TPM token bucket around `completeChunk` (`TranslationPipeline.kt:2585-2607`).

The batch itself is serialized, which is a useful control rather than a bug:
`SequentialBatchCoordinator` waits for the translation and render jobs before
the next chunk (`SequentialBatchCoordinator.kt:157-218, 290-323`). However,
serialization is not pacing: after the barrier, the next envelope is admitted
immediately. More importantly, the reader/manual contextual path calls
`ct.translateContextual(chunk)` directly without the shared admission
(`TranslationPipeline.kt:3216-3248`). A reader request can therefore overlap a
batch Gemini request and consume the same free-tier quota.

The token planner makes whole-page envelopes under the provider safety budget
(`TranslationContextChunkPlanner.kt:17-41`, `StreamingChunkPlanner.kt:107-129,
214-216`), so a normal chapter generates multiple provider requests (the exact
count is content-dependent). Director-supplied free-tier RPM limits make this
request pattern a STRONG INFERENCE for frequent 429s, but request count and
absence of pacing are VERIFIED.

Direction: use one quota-aware admission/cooldown shared by batch, reader, and
other provider callers; keep storage/render work outside the narrow HTTP
admission interval so a quota sleep does not monopolize unrelated state work.

## F3 — Gemini retry hint and quota exhaustion handling are too local

**Classification:** VERIFIED. **Severity:** HIGH. **Likelihood:** HIGH for
free-tier quota exhaustion.

Gemini reads only a numeric `Retry-After` value and interprets it as seconds
(`GeminiTranslator.kt:145-155`). HTTP-date form, fractional values, and other
non-numeric forms are ignored. The retry layer then chooses either that value or
local exponential backoff, clamps it to `[0, 30_000]` ms, and sleeps only the
current request (`TranslationRetry.kt:83-100`). A missing hint therefore uses
roughly 1–2 seconds plus jitter for the first two retries; a provider hint longer
than 30 seconds is deliberately shortened.

There is no shared cooldown state, per-model daily quota counter, or branch on
Gemini `providerCode`/`providerStatus`; all HTTP failures flow through the same
retry classifier (`GeminiTranslator.kt:147-155,257-262`,
`TranslationRetry.kt:68-85`). A `RESOURCE_EXHAUSTED` 429 with no header is
therefore retried like any other transient 429 and then falls into F1. Direction:
honor provider timing in a shared limiter and distinguish quota exhaustion from
short-lived transport/server errors so the batch can pause instead of burning
the envelope and poisoning the chapter.

The thinking-mode fallback is not the primary failure: a 400 with supported
thinking config retries once without that config inside the outer request retry
(`GeminiTranslator.kt:119-135,222-233`). Conversely, an HTTP-success response
with no candidate/usable text throws `GeminiEmptyResponseException`
(`GeminiTranslator.kt:275-297`), which is not classified transient by the
string classifier (`TranslationRetry.kt:105-130`) and consequently takes the
same terminal/blank-page path as F1.

## F4 — adaptive partial retry is planned but not executed

**Classification:** VERIFIED. **Severity:** HIGH. **Likelihood:** MEDIUM-HIGH
for long/dense Gemini responses.

After one contextual response, the retry controller computes missing pages and
blocks, logs `stage2_retry_plan`, and returns; it never recursively calls itself
or constructs a missing-block request (`AiTranslationRetryController.kt:88-106`).
The only caller supplies `retryDepth = 0` once (`TranslationPipeline.kt:1804-1811`).
This differs from the reader path, which has an explicit two-pass missing-block
loop (`TranslationPipeline.kt:3279-3295`).

Batch validation intentionally renders a page with some translated blocks as
`PARTIAL`, leaving missing regions blank and not charging the page retry counter
(`TranslationBlockValidation.kt:85-100`). The batch commits/render-joins that
page (`TranslationPipeline.kt:1840-1883`); reconciliation reports
`READY_WITH_WARNINGS` when partial pages exist (`BatchProgressReconciler.kt:86-89`),
and the chapter queue removes both `TRANSLATED` and `READY_WITH_WARNINGS`
entries (`ChapterTranslator.kt:288-295`). A user can therefore see a chapter
that appears finished enough to leave the queue while speech regions remain
blank. Direction: execute bounded targeted retries for missing blocks, or keep
partial chapters explicitly retryable and visible.

## F5 — queue/error state can make retry timing look like a dead button

**Classification:** VERIFIED mechanism; STRONG INFERENCE for the race-like user
experience. **Severity:** HIGH. **Likelihood:** MEDIUM.

An unexpected batch failure sets the `Translation` object to `ERROR`, but the
queue entry is removed only for `TRANSLATED` or `READY_WITH_WARNINGS`
(`ChapterTranslator.kt:288-307`). `queueChapter` refuses to add a chapter whose
ID is already present, regardless of whether its status is `ERROR`
(`ChapterTranslator.kt:342-345`). The error indicator nevertheless exposes
`ChapterTranslationAction.START` (`ChapterTranslationIndicator.kt:251-273`).

If the translator is idle, `TranslationManager.startTranslation` eventually
causes `ChapterTranslator.start` to convert non-translated entries, including
`ERROR`, back to `QUEUE` (`TranslationManager.kt:289-296`,
`ChapterTranslator.kt:188-197`). But if another chapter is already running,
`start()` returns without changing the queue (`ChapterTranslator.kt:188-191`),
and the target's `queueChapter` call was already a no-op. When the other chapter
finishes, no later event necessarily requeues the target. This is the
conditional dead-button case: tapping Start while another-source work is active
can leave the failed chapter in `ERROR` until the user taps again or explicitly
cancels/resets it.

The queue loop is intentionally one active source group/chapter at a time
(`ChapterTranslator.kt:254-282`), so this is a queue transition issue rather
than accidental multi-chapter concurrency. Direction: make retry an explicit
atomic `ERROR -> QUEUE` transition (or remove/re-add the entry) and emit a user
visible result when an existing error entry is rearmed.

## F6 — OOM abort policy is not wired into the batch path

**Classification:** VERIFIED. **Severity:** HIGH. **Likelihood:** MEDIUM on
large pages/devices.

`BatchOomPolicy.shouldAbort` defines a three-consecutive-OOM abort decision but
has no production call site in the current tree (`BatchOomPolicy.kt:9-26`; source
reference search finds only the policy/test). `translateBatch` creates an
`aborted` flag, checks it before OCR and at finalization, but never sets it
(`TranslationPipeline.kt:1235, 2008-2010, 2692-2700`). The apparent OOM-abort
branch is therefore unreachable.

OCR analysis catches `OutOfMemoryError` and produces a failed placeholder
(`TranslationPipeline.kt:4015-4033`). The separate batch `inpaintPage` catches
`Exception`, not `OutOfMemoryError` (`TranslationPipeline.kt:4114-4147`), and
the coordinator's inpaint wrapper likewise catches only `Exception`
(`SequentialBatchCoordinator.kt:290-318`). A native inpaint OOM can therefore
escape the pass, reach the chapter-level `Throwable` catch, and set the queue
entry to `ERROR` (`ChapterTranslator.kt:511-524`).

There is an additional progress-lifecycle risk: the batch `finally` flushes and
releases leases but does not abort the tracker (`TranslationPipeline.kt:2731-2743`).
The tracker registry removes a live tracker automatically only after a terminal
event (`TranslationBatchTrackerRegistry.kt:48-60,74-100`); the chapter-level
non-cancellation catch does not emit one. A failed OOM can thus leave a stale
live progress stream until explicit teardown, even though the queue says
`ERROR`. Artifact load does recover persisted `RUNNING` stages to retryable
states on a later open (`ChapterArtifactStore.kt:720-754`), so this is primarily
an in-process abort/progress problem. Direction: wire the policy, catch/convert
native OOM at the batch boundary, abort remaining pages and tracker explicitly,
then release/rebuild engines deterministically.

## F7 — foreground service/process lifecycle does not resume automatically

**Classification:** VERIFIED design limitation; actual OS kill conditions are
UNKNOWN. **Severity:** MEDIUM-HIGH. **Likelihood:** MEDIUM.

The service starts when the manager sees a queue/translation entry
(`TranslationManager.kt:289-296`) and polls queue state every second, stopping
when no entry is `QUEUE` or `TRANSLATING`
(`TranslationForegroundService.kt:72-86`, `BatchTranslationForegroundPolicy.kt:5-9`).
It returns `START_NOT_STICKY` (`TranslationForegroundService.kt:55-61`), so a
service/process death does not recreate the worker. Queue persistence stores only
chapter IDs (`TranslationQueueStore.kt:36-62`); restore reconstructs entries as
`QUEUE` but deliberately does not start the translator
(`ChapterTranslator.kt:145-175`).

Normal reader background/exit is safer: reader teardown cancels reader-owned
jobs but preserves a chapter while `isBatchTranslationActive` is true
(`TranslationManager.kt:219-229,1679-1687`). If the application process is
killed, however, durable artifacts remain but translation stops until the user
starts the restored queue. Direction: use a restartable worker/service contract
or make the restored queue and UI explicitly report “paused after process death”.

## F8 — startup queue restore races user enqueue

**Classification:** STRONG INFERENCE from unsynchronized concurrent mutations.
**Severity:** MEDIUM. **Likelihood:** LOW-MEDIUM.

`TranslationManager` launches `translator.restoreQueue()` asynchronously during
initialization (`TranslationManager.kt:170-175`). `restoreQueue` reads persisted
IDs and then replaces `_queueState` wholesale (`ChapterTranslator.kt:152-164`),
while user enqueue appends to that same state and persists it
(`ChapterTranslator.kt:582-588`). There is no mutex/ready barrier across these
operations. If restore reads the old list, a user enqueues, and restore then
publishes/saves its old snapshot, the new entry can disappear both in memory and
from `SharedPreferences`. Direction: serialize restore with queue mutations or
merge restored IDs rather than replacing the live queue.

## F9 — reader-owned lease denial can strand a batch page for the run

**Classification:** VERIFIED conditional path. **Severity:** MEDIUM.

Batch lease acquisition returns `null` immediately when a reader owns the page,
with a comment claiming it will be rescanned later (`TranslationPipeline.kt:2012-2024`).
The coordinator advances its cursor once and has no rescan loop for that page
(`SequentialBatchCoordinator.kt:343-380`). If the page has no already-committed
rendered output, reconciliation classifies it as stranded/error
(`BatchProgressReconciler.kt:53-89`). The compensating write at batch finish
uses `guardedBatchUpdate`, which rejects without a stored batch identity/lease
(`TranslationPipeline.kt:1377-1384,2711-2724`), so the deferred page may not even
receive the intended durable failure marker. A later batch run after the reader
lease is gone can recover it, but the current run reports an avoidable chapter
error. Direction: defer/retry denied pages inside the same pass, or persist a
lease-free retryable state explicitly.

## Answers to the requested questions

* **Does batch fire new envelopes while an earlier one retries?** Not within the
  batch coordinator: the current chunk blocks the next chunk. There is no
  inter-envelope pacing, and reader calls bypass the batch mutex, so cross-mode
  overlap remains possible.
* **Is `Retry-After` honored?** Only for a parseable integer-seconds header on
  the current Gemini call, capped at 30 seconds. It is not a global cooldown and
  is absent for many free-tier 429 responses.
* **What does one exhausted envelope do?** HTTP exhaustion becomes an all-blank
  page validation failure; those pages are durable `FAILED`, the first failure
  creates a context gap, later pages are blocked/failed, and reconciliation sets
  the chapter `ERROR`. `markBatchTranslationFailed` is the per-page durable
  helper, not a direct chapter-state setter.
* **Can already translated pages be salvaged?** Yes, the committed prefix and
  prior render bundles remain durable and reusable. The failed page/tail is not
  automatically re-admitted because `StageDecision.FAILED` is skipped by the
  batch translator.
* **What does a partial provider response do?** It is committed as `PARTIAL`,
  rendered with missing regions blank, and the batch retry plan is only logged;
  no targeted request is issued.
* **What happens on reader exit/background?** Reader-owned jobs/streams are
  torn down while queued batch ownership is preserved. Process death is
  different: the non-sticky service does not restart the batch, although
  artifacts and queue membership can be restored for a later manual Start.

## Highest-value confirmation tests/logs

The repository unit tests cover retry classification and isolated coordinator
barriers (`TranslationRetryTest.kt:48-73`,
`SequentialBatchCoordinatorTest.kt:159-181`) but do not exercise a real
`GeminiTranslator -> AiTranslationRetryController -> ChapterTranslationStore`
429 path. A device trace should correlate:

* `TranslationRetry` `retry_exhausted` and `GeminiTranslator`
  `provider_http_failure status=429`;
* `TranslationBatchRetry event=stage2_failure` followed by page
  `translationStatus=FAILED` / `blocked by non-textless terminal context gap`;
* a subsequent batch run showing `StageDecision.FAILED` and no new Gemini
  request for the same page; and
* queue `ERROR` plus a live tracker after an injected native OOM.

