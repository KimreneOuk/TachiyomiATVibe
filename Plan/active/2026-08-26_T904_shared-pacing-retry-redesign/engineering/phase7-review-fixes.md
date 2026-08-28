---
kind: review
title: "T904 Phase 7 review fixes and verification"
---

# T904 Phase 7 review fixes

Status: complete on the isolated `t904/integration` worktree. The fix commit is
`0e947861a1cafdb45f7dc3c31e67fbf756fe76ad` (`Fix translation integration review findings`),
based on the previously assembled review commit `a53fc502d388e68655db22419f511c16168f036e`.
No changes were made to the primary workspace.

## Review findings addressed

1. `SequentialBatchCoordinator` now has an explicit `Unexpected` outcome. Untyped OCR,
   translation/admission, inpaint, and render exceptions stop the current pass, mark the
   affected page/stage failed where possible, and leave later pages pending. They can no
   longer be converted into successful completion. Coordinator regression coverage was
   updated and expanded for unexpected OCR, inpaint, and translation failures.
2. The reader single-page HTTP/render path now owns one `RequestRetryBudget` and wraps the
   contextual/standard translation plus bounded partial retries in `withRequestRetryBudget`.
   Typed transient, quota, and budget-exhausted failures produce pause semantics and
   `PARTIAL`, while terminal failures remain typed failures.
3. The context frontier only advances for OCR-ready/textless pages with a ready translation
   (or an explicitly textless page). `PARTIAL` output is not admitted as reusable context or
   recorded by generic skip handling.
4. Translation pending-request and queue persistence-critical SharedPreferences writes now
   use synchronous `commit = true`; StateFlow publication ordering is unchanged.
5. All `persistAiFailure` call sites now reject a `PatchResult.Rejected` publication as a
   non-durable exceptional stop instead of claiming a persisted pause/failure. The same
   rule applies to unexpected inpaint/cleaned-image/terminal-state publication.
6. Chapter deletion protection consults the persisted queue directly during startup, in
   addition to live queue/pending state, closing the restore-versus-auto-delete window.
7. Reader auto-translation store open/migration and translation teardown are dispatched on
   existing IO/suspend paths; the UI state reset remains immediate while blocking store work
   leaves the main thread.
8. Legacy migration converts `activeError` to a bounded stage-category summary before it is
   stored or surfaced. A regression test verifies that raw secret-like text is not retained.

## Verification

All commands used the Android Studio JBR and SDK explicitly because Java was unavailable on
the base PATH:

| Gate | Result |
| --- | --- |
| `spotlessCheck` | PASS |
| `:app:compileStandardDebugKotlin` | PASS |
| Focused coordinator/frontier/migration regressions | 47 tests, 47 passed |
| `:app:testStandardDebugUnitTest` | 1,056 tests, 1 failure, 0 errors; the only failure was the policy-excluded deterministic `eu.kanade.translation.inpainting.AotReportBubbleFillTest` (`reportBubbleFill preserves diagonal components without an inset interior()`) |
| `:domain:testReleaseUnitTest` | 68 tests, 68 passed, 0 failures/errors/skips |
| `git diff --check` | PASS |
| final worktree | CLEAN after commit |

The AOT failure is reproducible on the contract base and is the sole review-policy exclusion;
all other app tests passed.

## Cross-phase integration audit

- Governor admission was verified at every batch-reachable remote boundary: contextual and
  standard translators, Gemini thinking/fallback POSTs, AI model fetchers, and the remote page
  translation engine. No new raw OkHttp path bypasses the governor.
- The reader's semantic call, transport retries, and bounded partial retries share one finite
  budget (`RequestRetryBudget.DEFAULT_MAX_ATTEMPTS`), preventing retry multiplication.
- Paused work uses the existing `requeueExisting` path; committed prefix state remains intact
  and the uncommitted tail remains pending.
- `PAUSED` does not retain the foreground-service ownership path; persisted queue IDs and live
  queue/pending states both protect chapters from auto-delete during restore.
- New diagnostics and UI/durable summaries use safe categories, hashes, or constants; no raw
  keys, prompts, response bodies, or legacy active-error text were added to those surfaces.

## Minor dispositions

- Governor-deferred admission no longer consumes the compatibility logical-attempt charge;
  this minor was fixed in `AiTranslationRetryController`.
- Paused notification IDs remain coalesced to the existing single notification/channel. This
  is a low-risk presentation limitation with no queue, retry, or durability impact, so it is
  accepted for this slice.
- Gemini provider metadata still reports the local retry-wrapper attempt value for retries.
  This is observability-only; actual HTTP admission and the shared request budget remain
  correct. It is accepted as a contained follow-up rather than changing provider metadata
  plumbing in this review-fix commit.

## Remaining risks

- Base preflight remains YELLOW: repository steward commit `99c11a492e8480a1c19a007c9ab83c4525cd6fb3`
  recorded clean implementation worktrees at `926ae00`, no upstream, and a diverged remote
  optimize branch. This branch was not rebased against origin refs.
- Java is not available on the base PATH; the successful compile/test gates used the Android
  Studio JBR and configured Android SDK.
- The deterministic AOT test failure remains excluded by policy. No device or instrumentation
  validation was run, and asynchronous teardown can complete after the immediate UI reset.
- The accepted notification-ID and Gemini-attempt-metadata minors remain as documented above.

## Round 2 delta fixes

Status: complete on the isolated `t904/integration` worktree. The final round-2 fix commit is
`56179d7452e857585ab516f98a556ea83c277bb5` (`fix: preserve typed batch retry outcomes`),
based on `0e947861a1cafdb45f7dc3c31e67fbf756fe76ad`. No changes were made to the primary
workspace.

### Resolutions

1. **Typed reader outcome.** `translatePreparedPage` now returns a typed
   `ChunkCompletionOutcome?`; `RollingAutoCoordinator` distinguishes completed, paused,
   retryable/terminal failed, unexpected, and persistence-rejected results. Paused or failed
   slots are deferred/reprepared and are never marked `Ready`. Reader contextual, standard,
   and partial retries share one finite `RequestRetryBudget`.
2. **Persistence rejection routing.** `BatchPersistenceRejectedException` is preserved through
   the sequential coordinator's stage catches and outcome mapping. Unexpected-stage publication
   rejection propagates as `PERSISTENCE_REJECTED` with an explicit non-durable reconciliation;
   it cannot become terminal `ERROR`, claim a durable pause, or release a lease as if durable
   publication succeeded.
3. **Queue restore race.** `ChapterTranslator.restoreQueue` takes a fresh durable snapshot after
   suspendable lookups, merges it with restored/live entries under the queue mutation lock, and
   self-heals stale IDs without dropping concurrent additions or removals.
4. **STARTING acknowledgement.** The in-memory/StateFlow `STARTING` acknowledgement is
   published immediately. Synchronous `commit=true` persistence then runs on the manager IO
   scope with a per-chapter version fence, preserving process-death restoration and later
   phase/cancel ordering without blocking the acknowledgement boundary.

### Round 2 verification

| Gate | Result |
| --- | --- |
| Focused round-2 regressions | 45 tests, 45 passed |
| `spotlessCheck` | PASS |
| `:app:compileStandardDebugKotlin` | PASS |
| `:app:testStandardDebugUnitTest` | 1,062 tests, 1 failure, 0 errors; sole failure was the policy-excluded `eu.kanade.translation.inpainting.AotReportBubbleFillTest` (`reportBubbleFill preserves diagonal components without an inset interior()`); all other tests passed |
| `:domain:testReleaseUnitTest` | 68 tests, 68 passed, 0 failures/errors |
| `git diff --check` | PASS |
| final worktree | CLEAN after commit |

The successful gates used Android Studio JBR and the configured Android SDK explicitly because
Java was unavailable on the base PATH. The base/repository health remains YELLOW as recorded
above, and no device or instrumentation validation was run.

### Round 2 self-audit and remaining risks

- The reader boundary no longer collapses paused/failed outcomes into boolean success; rolling
  auto completion only records `Ready` for an actual completed outcome.
- Persistence rejection remains distinguishable from unexpected programming failure in both
  coordinator and reconciliation paths. Non-durable rejection is surfaced as a warning stop,
  not a terminal success or durable pause claim.
- Queue restore merges additions made while lookups suspend, and pending-request acknowledgement
  publishes before off-main synchronous persistence. The persisted queue/pending protection paths
  remain active during restore.
- Accepted low-risk minor dispositions are unchanged: paused notification IDs remain coalesced,
  and Gemini retry metadata still reports the local wrapper attempt. Governor-deferred attempt
  accounting was fixed in the earlier slice.
